package it.prime.protocollo17.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import it.prime.protocollo17.data.testing.FakeJournalRepository
import it.prime.protocollo17.data.testing.OutcomeFixtures
import it.prime.protocollo17.domain.model.command.CommandProgress
import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.model.settings.ProtocolSettings
import it.prime.protocollo17.ecr17.client.Ecr17Client
import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.event.ExchangeListener
import it.prime.protocollo17.ecr17.event.ProtocolWarning
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.PhaseChange
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.ecr17.testing.Frames
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class PosTerminalRepositoryImplTest {

    private val journal = FakeJournalRepository()
    private val client = mockk<Ecr17Client>()
    private var settings = AppSettings()
    private val repo = PosTerminalRepositoryImpl(client, journal, settingsProvider = { settings })
    private val pay = PaymentRequest(amountCents = 10)

    /** Il client finto invoca il listener come la sessione: SENDING, AWAITING_ACK, ACKNOWLEDGED, RESULT_RECEIVED, progress. */
    private fun clientAnswers(outcome: CommandOutcome = OutcomeFixtures.answered(), warnings: List<ProtocolWarning> = emptyList()) {
        coEvery { client.execute(any(), any()) } coAnswers {
            val cmd = firstArg<Ecr17Request>().command
            val listener = secondArg<ExchangeListener>()
            journal.calls += "client.execute"
            listener.onPhase(PhaseChange(1, cmd, ExchangePhase.SENDING, 0, 1))
            listener.onPhase(PhaseChange(1, cmd, ExchangePhase.AWAITING_ACK, 0, 1))
            listener.onPhase(PhaseChange(1, cmd, ExchangePhase.ACKNOWLEDGED, 0, 1))
            listener.onProgress("OPERAZIONE IN CORSO ")
            warnings.forEach(listener::onWarning)
            listener.onPhase(PhaseChange(1, cmd, ExchangePhase.RESULT_RECEIVED, 0, 1, resultRaw = Frames.E22))
            outcome
        }
    }

    private fun collect(request: Ecr17Request) = runBlocking {
        withTimeout(10_000) { repo.execute(request).toList() }
    }

    @Test
    fun `CREATED prima del client, SENDING dal listener, RECEIVED con il payload, Finished ultimo`() {
        clientAnswers()

        val progress = collect(pay)

        assertEquals(
            listOf(
                "begin:att-1", "client.execute", "phase:att-1:SENDING:1", "phase:att-1:ACKED:1",
                "phase:att-1:RECEIVED:1:${Frames.E22}", "finish:att-1:Answered",
            ),
            journal.calls,
            "ordine delle scritture (AWAITING_ACK non ha record)",
        )
        assertEquals(CommandProgress.Started(CommandCode.PAYMENT, "att-1", (progress.first() as CommandProgress.Started).at), progress.first(), "Started")
        val finished = progress.last() as CommandProgress.Finished
        assertTrue(finished.outcome is CommandOutcome.Answered, "esito vero")
        assertEquals("att-1", finished.attemptId, "attemptId")
        assertNull(finished.journalError, "nessun errore del journal")
        assertEquals(1, progress.count { it is CommandProgress.Finished }, "esattamente un Finished")
        assertTrue(progress.any { it is CommandProgress.Progress }, "progress inoltrato")
        assertEquals(4, progress.count { it is CommandProgress.Phase }, "tutte le fasi inoltrate")
    }

    /** L1: un valore illeggibile nel file rifiuta il comando, senza journal né client. */
    @Test
    fun `config non utilizzabile produce NotSent ConfigInvalid senza journal ne client`() {
        settings = AppSettings(protocol = ProtocolSettings(appLrcMode = "sxt"))

        val progress = collect(pay)

        assertEquals(2, progress.size, "Started + Finished")
        val outcome = (progress.last() as CommandProgress.Finished).outcome
        assertTrue(outcome is CommandOutcome.NotSent && outcome.failure is Ecr17Failure.ConfigInvalid, "NotSent(ConfigInvalid): $outcome")
        val problems = ((outcome as CommandOutcome.NotSent).failure as Ecr17Failure.ConfigInvalid).problems
        assertTrue(problems.any { it.key == "appLrcMode" }, "issue riportato: $problems")
        assertTrue(journal.calls.isEmpty(), "nessuna chiamata al journal: ${journal.calls}")
        coVerify(exactly = 0) { client.execute(any(), any()) }
    }

    /** M3: journal non scrivibile prima del primo invio ⇒ il comando non parte. */
    @Test
    fun `begin che lancia produce NotSent Internal con journalError e il client non e mai chiamato`() {
        clientAnswers()
        journal.failBegin = IOException("disco pieno")

        val progress = collect(pay)

        assertEquals(2, progress.size, "Started + Finished")
        assertEquals(null, (progress.first() as CommandProgress.Started).attemptId, "Started senza attemptId")
        val finished = progress.last() as CommandProgress.Finished
        val outcome = finished.outcome
        assertTrue(outcome is CommandOutcome.NotSent && outcome.failure is Ecr17Failure.Internal, "NotSent(Internal): $outcome")
        assertNotNull(finished.journalError, "journalError")
        coVerify(exactly = 0) { client.execute(any(), any()) }
    }

    /** R7: un disco pieno alla chiusura non trasforma un APPROVATO in un errore generico. */
    @Test
    fun `finish che lancia emette comunque Finished con l'esito vero e journalError`() {
        clientAnswers()
        journal.failFinish = IOException("disco pieno")

        val finished = collect(pay).last() as CommandProgress.Finished

        assertTrue(finished.outcome is CommandOutcome.Answered, "esito vero: ${finished.outcome}")
        assertTrue(finished.journalError!!.contains("pagamenti bloccati"), "journalError: ${finished.journalError}")
        assertTrue(journal.unresolved.value.any { it.attemptId == "att-1" }, "entry forzata bloccante")
    }

    @Test
    fun `collector annullato durante lo scambio chiude comunque il tentativo con finish null`(): Unit = runBlocking {
        val sent = CompletableDeferred<Unit>()
        coEvery { client.execute(any(), any()) } coAnswers {
            secondArg<ExchangeListener>().onPhase(PhaseChange(1, CommandCode.PAYMENT, ExchangePhase.SENDING, 0, 1))
            sent.complete(Unit)
            awaitCancellation()
        }

        val job = launch(Dispatchers.Default) { repo.execute(pay).collect {} }
        withTimeout(5_000) { sent.await() }
        job.cancelAndJoin()

        assertEquals(listOf("att-1" to null), journal.finishCalls.toList(), "finish(id, null)")
        assertEquals("UNKNOWN", journal.entry("att-1")?.phase?.name, "inviato ⇒ UNKNOWN")
    }

    /**
     * Cancellare durante begin (fsync lento) deve chiamare finish(id, null) e mai il client (R22). Il begin finto,
     * come quello reale, attende in NonCancellable: l'annullamento non lo fa lanciare. Il collector gira sul thread
     * di runBlocking, che resta BLOCCATO (senza sospendere) finché il produttore non chiude il tentativo: il
     * consumatore non può chiudere il canale prima di send, quindi solo il controllo di cancellazione dopo begin
     * ferma il comando. Prima il test passava anche senza quel controllo (begin cancellabile, corsa sul canale).
     */
    @Test
    fun `collector annullato durante begin chiama comunque finish null e mai il client`(): Unit = runBlocking {
        clientAnswers()
        val gate = CompletableDeferred<Unit>()
        journal.beginGate = gate

        val job = launch { repo.execute(pay).collect {} }
        withTimeout(5_000) { journal.beginEntered.await() }
        job.cancel()
        gate.complete(Unit) // l'fsync finisce DOPO la richiesta di annullamento
        assertTrue(journal.finishEntered.await(5, TimeUnit.SECONDS), "finish chiamato dal produttore")
        job.join()

        assertEquals(listOf("att-1" to null), journal.finishCalls.toList(), "finish(id, null)")
        assertEquals("NOT_SENT", journal.entry("att-1")?.phase?.name, "CREATED scritto, nulla inviato ⇒ NOT_SENT")
        coVerify(exactly = 0) { client.execute(any(), any()) }
    }

    /** M4: un esito orfano è prova per l'ultimo tentativo non risolto, mai per quello corrente. */
    @Test
    fun `POSSIBLE_LOST_RESULT allega la prova al tentativo non risolto piu recente diverso dal corrente`() {
        journal.seedUnknown("vecchio", CommandCode.PAYMENT, createdAt = 10)
        journal.seedUnknown("recente", CommandCode.CARD_VERIFICATION, createdAt = 20)
        val lost = ProtocolWarning(WarningCode.POSSIBLE_LOST_RESULT, "orfano", 5, payload = Frames.E22)
        // Il comando corrente (s, non journaled) riceve l'esito orfano di un tentativo precedente.
        clientAnswers(outcome = OutcomeFixtures.answered(CommandCode.STATUS), warnings = listOf(lost))

        val progress = collect(StatusRequest)

        assertEquals(1, journal.evidenceCalls.size, "una prova")
        val (target, payload, note) = journal.evidenceCalls.single()
        assertEquals("recente", target, "tentativo non risolto più recente")
        assertEquals(Frames.E22, payload, "payload mascherato")
        assertTrue(note.startsWith("orfano durante 's' delle "), "nota: $note")
        assertTrue(progress.any { it is CommandProgress.Warning && it.warning.code == WarningCode.POSSIBLE_LOST_RESULT }, "warning inoltrato")
    }

    @Test
    fun `la prova non va mai al tentativo corrente`() {
        val lost = ProtocolWarning(WarningCode.POSSIBLE_LOST_RESULT, "orfano", 5, payload = Frames.E22)
        val targets = CopyOnWriteArrayList<String>()
        coEvery { client.execute(any(), any()) } coAnswers {
            val listener = secondArg<ExchangeListener>()
            listener.onPhase(PhaseChange(1, CommandCode.PAYMENT, ExchangePhase.SENDING, 0, 1))
            listener.onWarning(lost)
            OutcomeFixtures.unknown()
        }

        collect(pay)
        journal.evidenceCalls.forEach { targets += it.first }

        assertTrue(targets.isEmpty(), "nessun altro tentativo non risolto: la prova resta nel log traffico ($targets)")
    }

    @Test
    fun `comando non journaled non tocca il journal`() {
        clientAnswers(outcome = OutcomeFixtures.answered(CommandCode.STATUS))

        val progress = collect(StatusRequest)

        assertEquals(listOf("client.execute"), journal.calls, "solo il client")
        assertNull((progress.first() as CommandProgress.Started).attemptId, "nessun attemptId")
        assertNull((progress.last() as CommandProgress.Finished).attemptId, "nessun attemptId")
    }

    @Test
    fun `testConnection e connect con config non utilizzabile non chiamano il client`(): Unit = runBlocking {
        settings = AppSettings(protocol = ProtocolSettings(connectionMode = "SEMPRE"))
        every { client.cancelCurrent() } returns false

        val test = repo.testConnection()

        assertTrue(!test.ok && test.failure is Ecr17Failure.ConfigInvalid, "rifiuto esplicito: $test")
        assertTrue(repo.connect() is Ecr17Failure.ConfigInvalid, "connect")
        coVerify(exactly = 0) { client.testConnection() }
        coVerify(exactly = 0) { client.connect() }
        assertEquals(false, repo.cancelCurrent(), "cancelCurrent delega al client")
    }
}
