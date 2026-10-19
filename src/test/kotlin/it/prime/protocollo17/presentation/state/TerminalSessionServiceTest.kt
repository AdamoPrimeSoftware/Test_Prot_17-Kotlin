package it.prime.protocollo17.presentation.state

import it.prime.protocollo17.data.testing.FakeJournalRepository
import it.prime.protocollo17.data.testing.OutcomeFixtures
import it.prime.protocollo17.domain.model.command.CommandProgress
import it.prime.protocollo17.domain.model.command.ConfigResolution
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.model.settings.ProtocolSettings
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.domain.repository.PosTerminalRepository
import it.prime.protocollo17.domain.util.ConfigResolver
import it.prime.protocollo17.domain.util.PaymentBlockPolicy
import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.model.CardVerificationRequest
import it.prime.protocollo17.ecr17.model.CloseSessionRequest
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.ConnectionTestResult
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.ecr17.model.LinkState
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.PhaseChange
import it.prime.protocollo17.ecr17.model.PreAuthRequest
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.ecr17.model.TotalsRequest
import it.prime.protocollo17.presentation.UiFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class TerminalSessionServiceTest {

    /**
     * Repository finto che si comporta come PosTerminalRepositoryImpl verso il journal (attemptId prima di begin,
     * finish nel finally in NonCancellable) ma con lo scambio scritto dal test e un gate per tenerlo "in corso".
     */
    private class FakePosRepository(private val journal: JournalRepository) : PosTerminalRepository {
        var resolution: ConfigResolution = ConfigResolver.resolve(AppSettings())
        val cancelCalls = AtomicInteger()
        val clientExecutions = AtomicInteger()
        var journalError: String? = null
        var gate = CompletableDeferred<Unit>()
        val sent = CompletableDeferred<Unit>()
        var outcomeFor: (Ecr17Request) -> CommandOutcome = { OutcomeFixtures.answered(it.command) }

        /** Lo scambio: per default SENDING, ACKNOWLEDGED, AWAITING_RESULT, poi attesa del gate. */
        var exchange: suspend FlowCollector<CommandProgress>.(Ecr17Request, String?) -> Unit = { request, id ->
            emitPhase(journal, request, id, ExchangePhase.SENDING, 1)
            sent.complete(Unit)
            emitPhase(journal, request, id, ExchangePhase.ACKNOWLEDGED, 1)
            emitPhase(journal, request, id, ExchangePhase.AWAITING_RESULT, 1)
            gate.await()
        }

        override val linkState: StateFlow<LinkState> = MutableStateFlow(LinkState.Idle)
        override val busy: StateFlow<CommandCode?> = MutableStateFlow(null)
        override fun effectiveConfig(): ConfigResolution = resolution

        override fun execute(request: Ecr17Request): Flow<CommandProgress> = flow {
            val attemptId = if (request.command.journaled) journal.newAttemptId() else null
            var outcome: CommandOutcome? = null
            try {
                if (attemptId != null) {
                    journal.begin(attemptId, request, resolution.config)
                    currentCoroutineContext().ensureActive() // come PosTerminalRepositoryImpl: begin è NonCancellable
                }
                emit(CommandProgress.Started(request.command, attemptId, 0))
                clientExecutions.incrementAndGet()
                exchange(request, attemptId)
                outcome = outcomeFor(request)
            } finally {
                withContext(NonCancellable) { if (attemptId != null) journal.finish(attemptId, outcome) }
            }
            emit(CommandProgress.Finished(outcome!!, attemptId, journalError))
        }

        override fun cancelCurrent(): Boolean {
            cancelCalls.incrementAndGet()
            return true
        }

        override suspend fun testConnection(): ConnectionTestResult = error("non usato")
        override suspend fun connect(): Ecr17Failure? = null
        override suspend fun disconnect(): Ecr17Failure? = null
    }

    private val journal = FakeJournalRepository()
    private val repo = FakePosRepository(journal)
    private val notifications = AppNotifications()
    private val service = TerminalSessionService(repo, journal, notifications, wallClock = { 5_000 })

    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val notices = CopyOnWriteArrayList<AppNotifications.Notice>()
    private val finishedRuns = CopyOnWriteArrayList<FinishedRun>()

    init {
        collectors.launch { notifications.events.collect { notices += it } }
        collectors.launch { service.finished.collect { finishedRuns += it } }
    }

    @AfterEach
    fun tearDown() {
        repo.gate.complete(Unit)
        service.close()
        collectors.cancel()
    }

    private fun awaitTrue(message: String, timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            check(System.currentTimeMillis() < deadline) { "timeout: $message (stato ${service.state.value})" }
            Thread.sleep(5)
        }
    }

    private fun awaitIdle() = awaitTrue("comando concluso") { service.state.value.running == null }

    private fun awaitPhase(phase: ExchangePhase) = awaitTrue("fase $phase") { service.state.value.running?.phase == phase }

    private val pay = PaymentRequest(amountCents = 10)

    // ─── Rifiuti ───────────────────────────────────────────────────────────────

    /** M7: un secondo comando è rifiutato, mai accodato. */
    @Test
    fun `secondo comando durante il primo restituisce Busy`() {
        assertEquals(RunStart.Started, service.run(pay, RunOrigin.CONSOLE), "primo")
        assertEquals(RunStart.Busy, service.run(StatusRequest, RunOrigin.JOURNAL), "secondo")
        repo.gate.complete(Unit)
        awaitIdle()
        assertEquals(1, repo.clientExecutions.get(), "un solo comando eseguito")
    }

    @Test
    fun `UNKNOWN di P blocca un nuovo pagamento`() {
        journal.seedUnknown("vecchio", CommandCode.PAYMENT)

        val start = service.run(pay, RunOrigin.CONSOLE)

        assertTrue(start is RunStart.Blocked, "Blocked: $start")
        assertEquals(listOf("vecchio"), (start as RunStart.Blocked).blocking.map { it.attemptId }, "tentativo bloccante")
        assertEquals(0, repo.clientExecutions.get(), "nulla eseguito")
    }

    @Test
    fun `UNKNOWN di H blocca la famiglia E V ma non la preautorizzazione`() {
        journal.seedUnknown("verifica", CommandCode.CARD_VERIFICATION)

        assertTrue(service.run(pay, RunOrigin.CONSOLE) is RunStart.Blocked, "P bloccato")
        assertTrue(service.run(CardVerificationRequest(), RunOrigin.CONSOLE) is RunStart.Blocked, "H bloccato")
        assertEquals(RunStart.Started, service.run(PreAuthRequest(amountCents = 100), RunOrigin.CONSOLE), "p ammesso")
    }

    /** U7: G è proprio il comando che risolve un esito sconosciuto, quindi non è mai bloccato. */
    @Test
    fun `G ammesso anche con pagamenti bloccati`() {
        journal.seedUnknown("vecchio", CommandCode.PAYMENT)
        assertEquals(RunStart.Started, service.run(LastResultRequest(), RunOrigin.JOURNAL), "G")
    }

    @Test
    fun `configurazione non utilizzabile restituisce InvalidConfig`() {
        repo.resolution = ConfigResolver.resolve(AppSettings(protocol = ProtocolSettings(appLrcMode = "sxt")))

        val start = service.run(pay, RunOrigin.CONSOLE)

        assertTrue(start is RunStart.InvalidConfig, "InvalidConfig: $start")
        assertTrue((start as RunStart.InvalidConfig).messages.any { it.contains("sxt") }, "messaggio: ${start.messages}")
        assertEquals(0, repo.clientExecutions.get(), "nulla eseguito")
    }

    // ─── Annullamento ──────────────────────────────────────────────────────────

    @Test
    fun `requestCancel dopo SENDING chiama cancelCurrent senza annullare il job`() {
        service.run(pay, RunOrigin.CONSOLE)
        awaitPhase(ExchangePhase.AWAITING_RESULT)

        assertEquals(CancelResult.ABORTED_AFTER_SEND, service.requestCancel(), "abort dopo l'invio")
        assertEquals(1, repo.cancelCalls.get(), "cancelCurrent")
        assertTrue(service.state.value.running?.cancelRequested == true, "cancelRequested")
        assertTrue(service.state.value.running != null, "il comando attende l'esito del client (Unknown(Cancelled))")
        repo.gate.complete(Unit)
        awaitIdle()
    }

    /**
     * Prima: requestCancel prima dell'invio chiamava solo cancelCurrent(), che durante journal.begin (fsync) non
     * trovava nulla da annullare, e il pagamento partiva lo stesso. Ora il job viene annullato.
     */
    @Test
    fun `annullamento durante journal begin non esegue il client e chiude il tentativo`() {
        val gate = CompletableDeferred<Unit>()
        journal.beginGate = gate
        service.run(pay, RunOrigin.CONSOLE)
        awaitTrue("begin in corso") { journal.beginEntered.isCompleted }

        assertEquals(CancelResult.CANCELLED_BEFORE_SEND, service.requestCancel(), "annullato prima dell'invio")
        // begin attende in NonCancellable come il journal reale: l'fsync finisce dopo la richiesta di annullamento.
        gate.complete(Unit)
        awaitIdle()

        assertEquals(0, repo.clientExecutions.get(), "client mai eseguito")
        assertEquals(listOf("att-1" to null), journal.finishCalls.toList(), "finish(id, null)")
        val run = finishedRuns.single()
        val outcome = run.outcome
        assertTrue(outcome is CommandOutcome.NotSent && outcome.failure == Ecr17Failure.Cancelled(true), "NotSent(Cancelled): $outcome")
    }

    /** M10: se il journal registra l'invio, la sintesi non dice mai "non inviato". */
    @Test
    fun `annullamento in corsa con journal gia UNKNOWN produce Unknown senza trace`() {
        // Il frame è scritto (SENDING nel journal) ma la fase non è ancora arrivata al servizio.
        repo.exchange = { request, id ->
            journal.recordPhase(id!!, JournalPhase.SENDING, 1)
            repo.sent.complete(Unit)
            repo.gate.await()
        }
        service.run(pay, RunOrigin.CONSOLE)
        awaitTrue("SENDING scritto") { repo.sent.isCompleted }

        assertEquals(CancelResult.CANCELLED_BEFORE_SEND, service.requestCancel(), "il servizio non ha ancora visto SENDING")
        awaitIdle()

        assertEquals(JournalPhase.UNKNOWN, journal.entry("att-1")?.phase, "journal UNKNOWN")
        val outcome = finishedRuns.single().outcome
        assertTrue(outcome is CommandOutcome.Unknown, "Unknown: $outcome")
        assertNull(outcome.trace, "trace sintetizzata nulla")
        assertEquals("att-1", finishedRuns.single().attemptId, "attemptId")
        assertTrue(
            notices.any { it.kind == AppNotifications.Kind.ERROR && it.message.startsWith("Pagamento 0,10 €: esito sconosciuto") },
            "notifica: $notices",
        )
    }

    // ─── Fine comando e notifiche ──────────────────────────────────────────────

    @Test
    fun `pagamento approvato notifica successo con titolo e importo ed emette finished`() {
        service.run(pay, RunOrigin.CONSOLE)
        repo.gate.complete(Unit)
        awaitIdle()

        val run = finishedRuns.single()
        assertEquals(CommandCode.PAYMENT, run.command, "comando")
        assertEquals("att-1", run.attemptId, "attemptId")
        assertEquals(RunOrigin.CONSOLE, run.origin, "origine")
        assertEquals(run, service.state.value.lastByCommand[CommandCode.PAYMENT], "lastByCommand")
        assertEquals("02575733", service.state.value.lastSeenTerminalId, "TID visto")
        val notice = notices.single()
        assertEquals(AppNotifications.Kind.SUCCESS, notice.kind, "successo")
        assertEquals("Pagamento", notice.source, "titolo da CommandTitles")
        assertEquals("Pagamento 0,10 €: APPROVATO", notice.message, "importo da formatCents")
        assertEquals("Pagamento 0,10 €: APPROVATO", notice.displayText, "titolo non ripetuto sullo snackbar")
    }

    /** Prima la fonte era "Esito sconosciuto" e lo snackbar ripeteva le stesse parole due volte. */
    @Test
    fun `esito sconosciuto notifica errore con apertura del Journal`() {
        repo.outcomeFor = { OutcomeFixtures.unknown(it.command) }
        service.run(pay, RunOrigin.CONSOLE)
        repo.gate.complete(Unit)
        awaitIdle()

        val notice = notices.single()
        assertEquals(AppNotifications.Kind.ERROR, notice.kind, "errore")
        assertEquals("Pagamento", notice.source, "fonte = titolo del comando")
        assertEquals(
            "Pagamento 0,10 €: esito sconosciuto, pagamenti bloccati: esegui Ultimo esito (G)",
            notice.displayText,
            "testo sullo snackbar",
        )
        assertEquals(AppWindowId.JOURNAL, notice.openWindow, "apre il Journal")
    }

    /** Prima diceva "pagamenti bloccati", ma un H sconosciuto blocca solo la famiglia E V (PaymentBlockPolicy). */
    @Test
    fun `esito sconosciuto di H elenca i comandi bloccati da PaymentBlockPolicy`() {
        repo.outcomeFor = { OutcomeFixtures.unknown(it.command) }
        service.run(CardVerificationRequest(), RunOrigin.CONSOLE)
        repo.gate.complete(Unit)
        awaitIdle()

        assertEquals(
            "Verifica carta: esito sconosciuto, bloccati P, X, S e H: esegui Ultimo esito (G)",
            notices.single().displayText,
            "testo sullo snackbar",
        )
        val blocked = PaymentBlockPolicy.blockedCommands(journal.unresolved.value)
        assertEquals(
            setOf(CommandCode.PAYMENT, CommandCode.PAYMENT_EXTENDED, CommandCode.REVERSAL, CommandCode.CARD_VERIFICATION),
            blocked,
            "il testo dice esattamente ciò che la policy blocca",
        )
    }

    @Test
    fun `esito sconosciuto di C dice che si blocca solo la chiusura contabile`() {
        repo.outcomeFor = { OutcomeFixtures.unknown(it.command) }
        service.run(CloseSessionRequest(), RunOrigin.CONSOLE)
        repo.gate.complete(Unit)
        awaitIdle()

        assertEquals(
            "Chiusura contabile: esito sconosciuto, nuova chiusura contabile bloccata: verifica con Ultimo esito (G) o Totali",
            notices.single().displayText,
            "testo sullo snackbar",
        )
        assertEquals(setOf(CommandCode.CLOSE_SESSION), PaymentBlockPolicy.blockedCommands(journal.unresolved.value), "solo C")
    }

    /** Prima l'avviso diceva "APPROVATO" mentre il pannello dell'esito (ResultViewMapper) diceva "OK". */
    @Test
    fun `Totali e Chiusura contabile approvati notificano OK come il pannello`() {
        repo.exchange = { _, _ -> }
        repo.outcomeFor = { request ->
            when (request.command) {
                CommandCode.TOTALS -> UiFixtures.answered(CommandCode.TOTALS, UiFixtures.totals())
                else -> UiFixtures.answered(CommandCode.CLOSE_SESSION, UiFixtures.closeSession())
            }
        }
        assertEquals(RunStart.Started, service.run(TotalsRequest(), RunOrigin.CONSOLE), "T")
        awaitIdle()
        assertEquals(RunStart.Started, service.run(CloseSessionRequest(), RunOrigin.CONSOLE), "C")
        awaitIdle()

        assertEquals(listOf("Totali: OK", "Chiusura contabile: OK"), notices.map { it.displayText }, "parola del pannello")
        assertTrue(notices.all { it.kind == AppNotifications.Kind.SUCCESS }, "successo: $notices")
    }

    @Test
    fun `esito da verificare notifica un avviso con apertura del Journal`() {
        repo.outcomeFor = { OutcomeFixtures.answered(it.command, warnings = listOf(OutcomeFixtures.duplicateWarning()), retx = 1) }
        service.run(pay, RunOrigin.CONSOLE)
        repo.gate.complete(Unit)
        awaitIdle()

        val notice = notices.single()
        assertEquals(AppNotifications.Kind.WARNING, notice.kind, "avviso")
        assertTrue(notice.message.contains("da verificare"), "testo: ${notice.message}")
        assertEquals(AppWindowId.JOURNAL, notice.openWindow, "apre il Journal")
    }

    @Test
    fun `journalError aggiunge la notifica Journal non scritto`() {
        repo.journalError = "journal non scritto: disco pieno"
        service.run(pay, RunOrigin.CONSOLE)
        repo.gate.complete(Unit)
        awaitIdle()

        assertEquals("journal non scritto: disco pieno", finishedRuns.single().journalError, "journalError nel FinishedRun")
        val journalNotice = notices.single { it.source == "Journal" }
        assertEquals(AppNotifications.Kind.ERROR, journalNotice.kind, "errore")
        assertTrue(journalNotice.message.startsWith("Journal non scritto: pagamenti bloccati"), "testo: ${journalNotice.message}")
        assertTrue(notices.any { it.kind == AppNotifications.Kind.SUCCESS }, "l'esito vero resta notificato")
    }

    /**
     * Prima lo Stato (F5) usciva con l'avviso del servizio e con quello della Console sullo stesso snackbar, in ordine
     * non deterministico. Ora per CONSOLE_PING il servizio tace sull'esito ma l'errore del journal resta: è l'avviso
     * pubblicato subito dopo quello (mancante) dell'esito, quindi la verifica non dipende dai tempi.
     */
    @Test
    fun `ping dalla barra non notifica l'esito ma resta l'avviso del journal`() {
        repo.exchange = { _, _ -> }
        repo.outcomeFor = { UiFixtures.answered(CommandCode.STATUS, UiFixtures.status(2)) }
        repo.journalError = "journal non scritto: disco pieno"

        assertEquals(RunStart.Started, service.run(StatusRequest, RunOrigin.CONSOLE_PING), "avvio")
        awaitTrue("avviso del journal") { notices.any { it.source == "Journal" } }

        assertEquals(listOf("Journal"), notices.map { it.source }, "nessun avviso dell'esito: $notices")
        val run = finishedRuns.single()
        assertEquals(RunOrigin.CONSOLE_PING, run.origin, "origine")
        assertEquals(listOf(run), service.state.value.history, "il ping resta in cronologia")
        assertEquals("02575733", service.state.value.lastSeenTerminalId, "TID visto")
    }

    /** L'eccezione del ping vale solo per `s`: un altro comando con origine CONSOLE_PING non perde il suo avviso. */
    @Test
    fun `CONSOLE_PING su un comando diverso da s notifica l'esito`() {
        repo.exchange = { _, _ -> }
        repo.outcomeFor = { UiFixtures.answered(CommandCode.TOTALS, UiFixtures.totals()) }

        service.run(TotalsRequest(), RunOrigin.CONSOLE_PING)
        awaitTrue("avviso") { notices.isNotEmpty() }

        assertEquals("Totali: OK", notices.single().displayText, "avviso dell'esito")
    }

    /** Lo stesso `s` lanciato dal form (CONSOLE) ha l'avviso del servizio, con il testo di sempre. */
    @Test
    fun `s dalla Console notifica risposta ricevuta`() {
        repo.exchange = { _, _ -> }
        repo.outcomeFor = { UiFixtures.answered(CommandCode.STATUS, UiFixtures.status(2)) }

        service.run(StatusRequest, RunOrigin.CONSOLE)
        awaitTrue("avviso") { notices.isNotEmpty() }

        val notice = notices.single()
        assertEquals(AppNotifications.Kind.SUCCESS, notice.kind, "successo")
        assertEquals("Stato terminale: risposta ricevuta", notice.displayText, "testo sullo snackbar")
    }

    @Test
    fun `comando non inviato notifica errore`() {
        repo.outcomeFor = { OutcomeFixtures.notSent(it.command) }
        service.run(pay, RunOrigin.CONSOLE)
        repo.gate.complete(Unit)
        awaitIdle()
        assertEquals(AppNotifications.Kind.ERROR, notices.single().kind, "errore")
    }

    // ─── Stato in corso e cronologia ───────────────────────────────────────────

    @Test
    fun `resultWaitStartedAt impostato su AWAITING_RESULT e azzerato su SENDING`() {
        val reached = CompletableDeferred<Unit>()
        val resend = CompletableDeferred<Unit>()
        repo.exchange = { request, id ->
            emitPhase(journal, request, id, ExchangePhase.SENDING, 1, at = 10)
            emitPhase(journal, request, id, ExchangePhase.IMPLICIT_GO_AHEAD, 1, at = 20)
            emitPhase(journal, request, id, ExchangePhase.AWAITING_RESULT, 1, at = 30)
            emitPhase(journal, request, id, ExchangePhase.AWAITING_RESULT, 1, at = 40)
            reached.complete(Unit)
            resend.await()
            emitPhase(journal, request, id, ExchangePhase.SENDING, 2, at = 50)
            repo.sent.complete(Unit)
            repo.gate.await()
        }
        service.run(pay, RunOrigin.CONSOLE)
        awaitTrue("attesa esito") { reached.isCompleted }

        val waiting = service.state.value.running!!
        assertEquals(30L, waiting.resultWaitStartedAt, "prima AWAITING_RESULT dopo l'ultimo SENDING")
        assertTrue(waiting.implicitGoAhead, "implicitGoAhead")
        assertTrue(waiting.requestSent, "requestSent")

        resend.complete(Unit)
        awaitTrue("secondo invio") { service.state.value.running?.sends == 2 }
        assertNull(service.state.value.running!!.resultWaitStartedAt, "azzerato su SENDING")
        repo.gate.complete(Unit)
        awaitIdle()
    }

    @Test
    fun `cronologia piu recenti prima, tetto 200 e clearHistory`() {
        repo.exchange = { _, _ -> }
        repeat(RunnerState.HISTORY_CAP + 3) {
            assertEquals(RunStart.Started, service.run(StatusRequest, RunOrigin.CONSOLE), "avvio $it")
            awaitIdle()
        }

        val history = service.state.value.history
        assertEquals(RunnerState.HISTORY_CAP, history.size, "tetto")
        assertEquals((RunnerState.HISTORY_CAP + 3).toLong(), history.first().id, "più recente prima")
        assertTrue(history.zipWithNext().all { (a, b) -> a.id > b.id }, "ordine decrescente")

        service.clearHistory()
        assertTrue(service.state.value.history.isEmpty(), "cronologia svuotata")
        assertTrue(service.state.value.lastByCommand.containsKey(CommandCode.STATUS), "lastByCommand conservato")
    }

    // ─── Chiusura ──────────────────────────────────────────────────────────────

    /** R11: chiudendo durante un pagamento già inviato, il journal deve registrare UNKNOWN prima dell'uscita. */
    @Test
    fun `shutdown durante P dopo SENDING chiude il tentativo come UNKNOWN entro il timeout`() {
        service.run(pay, RunOrigin.CONSOLE)
        awaitPhase(ExchangePhase.AWAITING_RESULT)

        val started = System.currentTimeMillis()
        service.shutdown(timeoutMs = 3_000)

        assertTrue(System.currentTimeMillis() - started < 3_000, "entro il timeout")
        assertEquals(listOf("att-1" to null), journal.finishCalls.toList(), "finish(id, null)")
        assertEquals(JournalPhase.UNKNOWN, journal.entry("att-1")?.phase, "UNKNOWN nel journal")
        assertNull(service.state.value.running, "running == null")
        assertTrue(repo.cancelCalls.get() >= 1, "cancelCurrent")
    }

    @Test
    fun `shutdown senza comando ritorna subito`() {
        val started = System.currentTimeMillis()
        service.shutdown(timeoutMs = 3_000)
        assertTrue(System.currentTimeMillis() - started < 500, "immediato")
        assertEquals(0, repo.cancelCalls.get(), "nessun annullamento")
        assertFalse(journal.calls.any { it.startsWith("finish") }, "nessuna scrittura")
    }

    @Test
    fun `requestCancel senza comando restituisce NOT_RUNNING`() {
        assertEquals(CancelResult.NOT_RUNNING, service.requestCancel(), "nessun comando")
    }

    /** Un bug nel flusso (eccezione) non lascia l'overlay di occupato per sempre: esito sintetizzato. */
    @Test
    fun `eccezione inattesa del repository produce un esito sintetizzato`() {
        repo.exchange = { request, id ->
            emitPhase(journal, request, id, ExchangePhase.SENDING, 1)
            throw IOException("bug")
        }
        service.run(pay, RunOrigin.CONSOLE)
        awaitIdle()

        val outcome = finishedRuns.single().outcome
        assertTrue(outcome is CommandOutcome.Unknown && outcome.failure is Ecr17Failure.Internal, "Unknown(Internal): $outcome")
    }
}

/** Fase come la emette PosTerminalRepositoryImpl: record del journal (SENDING, ACKED) prima dell'inoltro. */
private suspend fun FlowCollector<CommandProgress>.emitPhase(
    journal: JournalRepository,
    request: Ecr17Request,
    id: String?,
    phase: ExchangePhase,
    sends: Int,
    at: Long = 100,
) {
    val journalPhase = when (phase) {
        ExchangePhase.SENDING -> JournalPhase.SENDING
        ExchangePhase.ACKNOWLEDGED -> JournalPhase.ACKED
        else -> null
    }
    if (id != null && journalPhase != null) journal.recordPhase(id, journalPhase, sends)
    emit(CommandProgress.Phase(PhaseChange(1, request.command, phase, at, sends)))
}
