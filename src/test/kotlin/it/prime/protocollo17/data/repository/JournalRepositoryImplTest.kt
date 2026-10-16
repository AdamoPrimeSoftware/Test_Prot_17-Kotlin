package it.prime.protocollo17.data.repository

import io.mockk.coEvery
import io.mockk.spyk
import it.prime.protocollo17.data.local.journal.JournalStore
import it.prime.protocollo17.data.testing.OutcomeFixtures
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.JournalRecord
import it.prime.protocollo17.domain.model.journal.KnownResultSource
import it.prime.protocollo17.domain.model.journal.ResolutionKind
import it.prime.protocollo17.domain.model.settings.JournalSettings
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.CardVerificationRequest
import it.prime.protocollo17.ecr17.testing.Frames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class JournalRepositoryImplTest {

    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    private var settings = JournalSettings()

    private fun repo(store: JournalStore = JournalStore(dir)) =
        JournalRepositoryImpl(store = store, settingsProvider = { settings }, wallClock = { now++ })

    /** Store reale con append che falliscono a comando (disco pieno simulato). */
    private class FailingStore(dir: File) {
        var failAppends = false
        val store: JournalStore = spyk(JournalStore(dir)).also { s ->
            coEvery { s.append(any()) } coAnswers {
                if (failAppends) throw IOException("disco pieno") else callOriginal()
            }
        }
    }

    private fun records(): List<JournalRecord> = runBlocking { JournalStore(dir).readAll() }

    /** Eccezione lanciata da una chiamata sospesa (null se nessuna): runCatching mangerebbe anche la cancellazione. */
    private suspend fun errorOf(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }

    private val pay = PaymentRequest(amountCents = 10)

    @Test
    fun `begin scrive CREATED con il riepilogo`(): Unit = runBlocking {
        val repo = repo()
        repo.recoverOnStartup()
        val id = repo.newAttemptId()
        repo.begin(id, pay, OutcomeFixtures.config)

        val entry = repo.entries.value.single()
        assertEquals(id, entry.attemptId, "attemptId")
        assertEquals(CommandCode.PAYMENT, entry.command, "comando")
        assertEquals(JournalPhase.CREATED, entry.phase, "fase")
        assertEquals(10L, entry.amountCents, "importo")
        assertTrue(entry.requestSummary.startsWith("Pagamento 0,10 €"), "riepilogo: ${entry.requestSummary}")
        assertEquals("127.0.0.1:5577", entry.endpoint, "endpoint")
        assertEquals(JournalPhase.CREATED, records().single().phase, "record su disco")
        assertTrue(repo.unresolved.value.isEmpty(), "CREATED non è da risolvere")
    }

    @Test
    fun `recordPhase scrive SENDING ACKED GO_AHEAD RECEIVED`(): Unit = runBlocking {
        val repo = repo()
        val id = repo.newAttemptId()
        repo.begin(id, pay, OutcomeFixtures.config)
        repo.recordPhase(id, JournalPhase.SENDING, 1)
        repo.recordPhase(id, JournalPhase.GO_AHEAD, 1)
        repo.recordPhase(id, JournalPhase.ACKED, 1)
        repo.recordPhase(id, JournalPhase.RECEIVED, 1, rawResult = Frames.E22)

        assertEquals(
            listOf(JournalPhase.CREATED, JournalPhase.SENDING, JournalPhase.GO_AHEAD, JournalPhase.ACKED, JournalPhase.RECEIVED),
            records().map { it.phase },
            "fasi su disco",
        )
        assertEquals(Frames.E22, records().last().rawResult, "rawResult del RECEIVED")
        assertEquals(listOf(0, 1, 2, 3, 4), records().map { it.seq }, "seq crescente")
        val entry = repo.entries.value.single()
        assertEquals(JournalPhase.RECEIVED, entry.phase, "fase")
        assertTrue(entry.isInFlight, "in corso")
        assertEquals(Frames.E22, entry.receivedRecord?.rawResult, "receivedRecord")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordPhase(id, JournalPhase.RESULT, 1) } }
        Unit
    }

    @Test
    fun `finish Answered scrive RESULT con i campi di riconciliazione`(): Unit = runBlocking {
        val repo = repo()
        val id = repo.newAttemptId()
        repo.begin(id, pay, OutcomeFixtures.config)
        repo.recordPhase(id, JournalPhase.SENDING, 1)
        repo.finish(id, OutcomeFixtures.answered(naks = 1, retx = 0))

        val result = records().last()
        assertEquals(JournalPhase.RESULT, result.phase, "fase")
        assertEquals(listOf("000004", "000055", "R48432", "0770947"), listOf(result.stan, result.onlineId, result.authCode, result.hostDateTime), "campi")
        assertEquals(Frames.E22, result.rawResult, "rawResult mascherato")
        assertFalse(result.rawResult!!.contains("4111111111111111"), "nessun PAN in chiaro")
        assertEquals(1, result.naksReceived, "naksReceived")
        assertEquals(0, result.timeoutRetransmissions, "timeoutRetransmissions")
        val entry = repo.entries.value.single()
        assertEquals(JournalPhase.RESULT, entry.phase, "fase entry")
        assertFalse(entry.isUnresolved, "chiuso")
        assertTrue(repo.unresolved.value.isEmpty(), "nessun non risolto")
    }

    @Test
    fun `finish Answered da verificare finisce in unresolved`(): Unit = runBlocking {
        val repo = repo()
        val id = repo.newAttemptId()
        repo.begin(id, pay, OutcomeFixtures.config)
        repo.finish(id, OutcomeFixtures.answered(warnings = listOf(OutcomeFixtures.duplicateWarning()), retx = 1))

        val entry = repo.unresolved.value.single()
        assertTrue(entry.needsReview, "needsReview")
        assertTrue(entry.blocksPayments, "blocca")
    }

    @Test
    fun `finish Unknown scrive UNKNOWN e aggiorna unresolved`(): Unit = runBlocking {
        val repo = repo()
        val id = repo.newAttemptId()
        repo.begin(id, pay, OutcomeFixtures.config)
        repo.recordPhase(id, JournalPhase.SENDING, 1)
        repo.finish(id, OutcomeFixtures.unknown())

        assertEquals(JournalPhase.UNKNOWN, records().last().phase, "record")
        assertEquals(id, repo.unresolved.value.single().attemptId, "unresolved")
    }

    /** La cancellazione della coroutine non deve mai far sembrare "non inviato" un frame già scritto (M10). */
    @Test
    fun `finish null chiude come UNKNOWN dopo SENDING o RECEIVED e NOT_SENT dopo CREATED`(): Unit = runBlocking {
        val repo = repo()
        val afterSending = repo.newAttemptId().also { repo.begin(it, pay, OutcomeFixtures.config); repo.recordPhase(it, JournalPhase.SENDING, 1) }
        val afterReceived = repo.newAttemptId().also {
            repo.begin(it, pay, OutcomeFixtures.config)
            repo.recordPhase(it, JournalPhase.SENDING, 1)
            repo.recordPhase(it, JournalPhase.RECEIVED, 1, Frames.E22)
        }
        val afterCreated = repo.newAttemptId().also { repo.begin(it, pay, OutcomeFixtures.config) }

        repo.finish(afterSending, null)
        repo.finish(afterReceived, null)
        repo.finish(afterCreated, null)
        repo.finish("inesistente", null)

        val byId = repo.entries.value.associateBy { it.attemptId }
        assertEquals(JournalPhase.UNKNOWN, byId.getValue(afterSending).phase, "dopo SENDING")
        assertEquals(JournalPhase.UNKNOWN, byId.getValue(afterReceived).phase, "dopo RECEIVED")
        assertEquals(Frames.E22, byId.getValue(afterReceived).receivedRecord?.rawResult, "il payload ricevuto resta come prova")
        assertEquals(JournalPhase.NOT_SENT, byId.getValue(afterCreated).phase, "dopo CREATED")
        assertFalse(byId.containsKey("inesistente"), "tentativo inesistente: nulla")
        assertFalse(records().any { it.attemptId == "inesistente" }, "nulla su disco")

        // Un secondo finish(null) su un tentativo già chiuso non scrive nulla.
        val count = records().size
        repo.finish(afterSending, null)
        assertEquals(count, records().size, "nessun record in più")
    }

    /** §5.3.3: un errore di disco dopo il primo invio deve bloccare SUBITO i pagamenti, senza attendere un riavvio. */
    @Test
    fun `append fallito forza UNKNOWN non persistito e rilancia`(): Unit = runBlocking {
        val failing = FailingStore(dir)
        val repo = repo(failing.store)
        val id = repo.newAttemptId()
        repo.begin(id, pay, OutcomeFixtures.config)
        repo.recordPhase(id, JournalPhase.SENDING, 1)

        failing.failAppends = true
        val error = errorOf { repo.recordPhase(id, JournalPhase.ACKED, 1) }

        assertTrue(error is IOException, "eccezione rilanciata: $error")
        val entry = repo.unresolved.value.single()
        assertEquals(JournalPhase.UNKNOWN, entry.phase, "fase forzata")
        assertFalse(entry.persisted, "persisted")
        assertTrue(entry.blocksPayments, "blocca")
        assertEquals(listOf(JournalPhase.CREATED, JournalPhase.SENDING), entry.records.map { it.phase }, "solo i record su disco")

        // Anche finish che fallisce rilancia e lascia l'entry bloccante.
        assertTrue(errorOf { repo.finish(id, OutcomeFixtures.answered()) } is IOException, "finish rilancia")
        assertEquals(id, repo.unresolved.value.single().attemptId, "ancora bloccante")

        // Il disco torna: una prova EVIDENCE scritta (orfano ricevuto durante un 's') NON sblocca. Prima il fold
        // tornava alla fase in volo dell'ultimo record su disco (SENDING) e il pagamento usciva da unresolved.
        failing.failAppends = false
        repo.attachEvidence(id, Frames.E22, "orfano")
        val withEvidence = repo.unresolved.value.single()
        assertEquals(id, withEvidence.attemptId, "ancora bloccante dopo la prova")
        assertEquals(JournalPhase.UNKNOWN, withEvidence.phase, "fase ancora forzata")
        assertFalse(withEvidence.persisted, "persisted resta false: nessuna chiusura su disco")
        assertEquals(JournalPhase.EVIDENCE, withEvidence.records.last().phase, "prova registrata")

        // Un append successivo riuscito di un record con una fase ripristina il fold normale.
        repo.finish(id, OutcomeFixtures.answered())
        val restored = repo.entries.value.single()
        assertTrue(restored.persisted, "persisted ripristinato")
        assertEquals(JournalPhase.RESULT, restored.phase, "fase dall'ultimo record scritto")
        assertTrue(repo.unresolved.value.isEmpty(), "sbloccato")
    }

    @Test
    fun `begin fallito non crea entry e rilancia`(): Unit = runBlocking {
        val failing = FailingStore(dir).apply { failAppends = true }
        val repo = repo(failing.store)
        val error = errorOf { repo.begin("x", pay, OutcomeFixtures.config) }

        assertTrue(error is IOException, "IOException: $error")
        assertTrue(repo.entries.value.isEmpty(), "nessuna entry")
        assertTrue(repo.unresolved.value.isEmpty(), "nessun blocco")
    }

    @Test
    fun `resolve su entry non persistita che fallisce di nuovo resta bloccante`(): Unit = runBlocking {
        val failing = FailingStore(dir)
        val repo = repo(failing.store)
        val id = repo.newAttemptId()
        repo.begin(id, pay, OutcomeFixtures.config)
        repo.recordPhase(id, JournalPhase.SENDING, 1)
        failing.failAppends = true
        errorOf { repo.finish(id, OutcomeFixtures.unknown()) }

        val error = errorOf { repo.resolve(id, ResolutionKind.DISMISSED, "verificato sullo scontrino", null) }

        assertTrue(error is IOException, "resolve rilancia: $error")
        assertEquals(id, repo.unresolved.value.single().attemptId, "resta bloccante")
    }

    /** M6: una riga CREATED persa non deve far sparire un tentativo bloccante. */
    @Test
    fun `fold senza CREATED produce entry incompleta e bloccante dopo il recupero`(): Unit = runBlocking {
        JournalStore(dir).apply {
            append(JournalRecord("orfano", 1, 10, JournalPhase.SENDING, sends = 1))
            close()
        }

        val repo = repo()
        val recovered = repo.recoverOnStartup()

        val entry = repo.unresolved.value.single()
        assertNull(entry.command, "command")
        assertTrue(entry.incomplete, "incomplete")
        assertTrue(entry.blocksPayments, "blocksPayments")
        assertEquals(JournalPhase.UNKNOWN, entry.phase, "UNKNOWN")
        assertEquals(listOf("orfano"), recovered.map { it.attemptId }, "restituito dal recupero")
    }

    /**
     * Politica scelta dall'utente (§5.3.3): journal illeggibile all'avvio ⇒ l'app non parte. Prima recoverOnStartup
     * inghiottiva l'errore e pubblicava un'entry sintetica "journal-illeggibile-*": l'app partiva senza i tentativi
     * veri, e chiudendo quell'entry con una nota si sbloccavano i pagamenti (M6). Ora lancia e non pubblica nulla.
     * Il file illeggibile è simulato con `journal.jsonl` creato come cartella: readBytes lancia IOException.
     */
    @Test
    fun `journal illeggibile all'avvio lancia e non pubblica entry`(): Unit = runBlocking {
        assertTrue(File(dir, "journal.jsonl").mkdirs(), "journal.jsonl come cartella")
        val repo = repo()

        val error = errorOf { repo.recoverOnStartup() }

        assertTrue(error is IOException, "IOException rilanciata: $error")
        assertTrue(repo.entries.value.isEmpty(), "nessuna entry pubblicata: ${repo.entries.value}")
        assertTrue(repo.unresolved.value.isEmpty(), "nessuna entry sintetica bloccante")
    }

    /** Lo stesso con un errore di lettura qualunque dello store (file bloccato): rilanciato così com'è. */
    @Test
    fun `errore di lettura dello store rilanciato da recoverOnStartup`(): Unit = runBlocking {
        val store = spyk(JournalStore(dir)).also { s -> coEvery { s.readAll() } throws IOException("file bloccato da un altro processo") }
        val repo = repo(store)

        // Tipo e messaggio, non l'identità: il recupero dello stack trace delle coroutine può consegnarne una copia.
        val error = errorOf { repo.recoverOnStartup() }
        assertTrue(error is IOException, "IOException rilanciata: $error")
        assertEquals("file bloccato da un altro processo", error?.message, "stesso errore")
        assertTrue(repo.entries.value.isEmpty(), "nessuna entry pubblicata")
    }

    @Test
    fun `recoverOnStartup chiude i tentativi rimasti a meta`(): Unit = runBlocking {
        JournalStore(dir).apply {
            for ((id, last) in listOf("s" to JournalPhase.SENDING, "a" to JournalPhase.ACKED, "g" to JournalPhase.GO_AHEAD, "r" to JournalPhase.RECEIVED)) {
                append(JournalRecord(id, 0, 10, JournalPhase.CREATED, command = "P", amountCents = 10))
                append(JournalRecord(id, 1, 11, JournalPhase.SENDING, sends = 1))
                if (last != JournalPhase.SENDING) append(JournalRecord(id, 2, 12, last, sends = 1, rawResult = if (last == JournalPhase.RECEIVED) Frames.E22 else null))
            }
            append(JournalRecord("c", 0, 20, JournalPhase.CREATED, command = "P"))
            append(JournalRecord("ok", 0, 30, JournalPhase.CREATED, command = "P"))
            append(JournalRecord("ok", 1, 31, JournalPhase.RESULT, outcome = "APPROVED"))
            close()
        }

        val repo = repo()
        val recovered = repo.recoverOnStartup()

        val byId = repo.entries.value.associateBy { it.attemptId }
        for (id in listOf("s", "a", "g", "r")) {
            assertEquals(JournalPhase.UNKNOWN, byId.getValue(id).phase, "$id ⇒ UNKNOWN")
            assertEquals("app terminata durante lo scambio", byId.getValue(id).failureRecord?.failure, "$id failure")
        }
        assertEquals(Frames.E22, byId.getValue("r").receivedRecord?.rawResult, "RECEIVED resta come prova")
        assertEquals(JournalPhase.NOT_SENT, byId.getValue("c").phase, "CREATED ⇒ NOT_SENT")
        assertEquals(JournalPhase.RESULT, byId.getValue("ok").phase, "chiuso: invariato")
        assertEquals(setOf("s", "a", "g", "r", "c"), recovered.map { it.attemptId }.toSet(), "restituiti i tentativi chiusi ora")
        assertEquals(setOf("s", "a", "g", "r"), repo.unresolved.value.map { it.attemptId }.toSet(), "non risolti")

        // Il recupero è scritto su disco: un secondo avvio non chiude nulla di nuovo e mantiene il blocco.
        val again = repo().also { it.recoverOnStartup() }
        assertEquals(setOf("s", "a", "g", "r"), again.unresolved.value.map { it.attemptId }.toSet(), "blocco mantenuto al riavvio")
        assertTrue(runBlocking { again.recoverOnStartup() }.isEmpty(), "nessuna nuova chiusura")
    }

    @Test
    fun `attachEvidence non cambia la fase`(): Unit = runBlocking {
        val repo = repo()
        val id = repo.newAttemptId()
        repo.begin(id, pay, OutcomeFixtures.config)
        repo.recordPhase(id, JournalPhase.SENDING, 1)
        repo.finish(id, OutcomeFixtures.unknown())
        repo.attachEvidence(id, Frames.E22, "orfano durante 's' delle 09:41:02")

        val entry = repo.unresolved.value.single()
        assertEquals(JournalPhase.UNKNOWN, entry.phase, "fase invariata")
        assertEquals(Frames.E22, entry.evidence.single().rawResult, "prova")
        assertEquals("orfano durante 's' delle 09:41:02", entry.evidence.single().evidenceNote, "nota")
        repo.attachEvidence("inesistente", Frames.E22, "x")
        assertFalse(records().any { it.attemptId == "inesistente" }, "prova su tentativo inesistente ignorata")
    }

    @Test
    fun `knownResultsBefore include RESULT RECEIVED EVIDENCE e g dei RESOLVED ed esclude il tentativo`(): Unit = runBlocking {
        val repo = repo()
        // 1: chiuso con RESULT
        val first = repo.newAttemptId().also {
            repo.begin(it, pay, OutcomeFixtures.config)
            repo.recordPhase(it, JournalPhase.RECEIVED, 1, "RAW-1")
            repo.finish(it, OutcomeFixtures.answered())
        }
        // 2: UNKNOWN con prova, poi risolto con G
        val second = repo.newAttemptId().also {
            repo.begin(it, pay, OutcomeFixtures.config)
            repo.recordPhase(it, JournalPhase.SENDING, 1)
            repo.finish(it, OutcomeFixtures.unknown())
            repo.attachEvidence(it, "EV-2", "orfano")
            val g = CommandOutcome.Answered(
                CommandCode.LAST_RESULT, OutcomeFixtures.approvedPayment(raw = "G-2", stan = "000009"), OutcomeFixtures.trace(CommandCode.LAST_RESULT),
            )
            repo.resolve(it, ResolutionKind.CONFIRMED_BY_G, "G coincide", g)
        }
        // 3: il tentativo in esame
        val third = repo.newAttemptId().also {
            repo.begin(it, pay, OutcomeFixtures.config)
            repo.recordPhase(it, JournalPhase.RECEIVED, 1, "RAW-3")
        }
        // 4: creato dopo
        repo.newAttemptId().also { repo.begin(it, pay, OutcomeFixtures.config); repo.finish(it, OutcomeFixtures.answered()) }

        val known = repo.knownResultsBefore(third)

        assertTrue(known.none { it.attemptId == third }, "escluso il tentativo stesso")
        assertEquals(setOf(first, second), known.map { it.attemptId }.toSet(), "solo i tentativi precedenti")
        val sources = known.map { it.attemptId to it.source }.toSet()
        assertTrue((first to KnownResultSource.RESULT) in sources, "RESULT")
        assertTrue((first to KnownResultSource.RECEIVED) in sources, "RECEIVED")
        assertTrue((second to KnownResultSource.EVIDENCE) in sources, "EVIDENCE")
        val g = known.single { it.source == KnownResultSource.G_RESOLUTION }
        assertEquals("G-2", g.raw, "gRawResult")
        assertEquals("000009", g.stan, "gStan")
        assertEquals(known.sortedByDescending { it.at }, known, "più recenti prima")
    }

    @Test
    fun `resolve solo per tentativi da risolvere e con nota`(): Unit = runBlocking {
        val repo = repo()
        val closed = repo.newAttemptId().also { repo.begin(it, pay, OutcomeFixtures.config); repo.finish(it, OutcomeFixtures.answered()) }
        val review = repo.newAttemptId().also {
            repo.begin(it, pay, OutcomeFixtures.config)
            repo.finish(it, OutcomeFixtures.answered(warnings = listOf(OutcomeFixtures.duplicateWarning()), retx = 1))
        }

        assertThrows(IllegalStateException::class.java) { runBlocking { repo.resolve(closed, ResolutionKind.DISMISSED, "nota", null) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.resolve("inesistente", ResolutionKind.DISMISSED, "nota", null) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.resolve(review, ResolutionKind.REVIEWED, "  ", null) } }
        assertEquals(review, repo.unresolved.value.single().attemptId, "ancora da risolvere")

        repo.resolve(review, ResolutionKind.REVIEWED, "Totali controllati: nessun doppio addebito", null)

        assertTrue(repo.unresolved.value.isEmpty(), "risolto")
        val entry = repo.entries.value.first { it.attemptId == review }
        assertEquals(JournalPhase.RESOLVED, entry.phase, "fase")
        assertEquals(ResolutionKind.REVIEWED, entry.resolutionRecord?.resolution, "resolution")
        assertEquals("Totali controllati: nessun doppio addebito", entry.resolutionRecord?.note, "nota")
    }

    @Test
    fun `entries limitate a memoryAttempts piu tutti i non risolti, piu recenti prima`(): Unit = runBlocking {
        settings = JournalSettings(memoryAttempts = 2)
        val repo = repo()
        val unknownOld = repo.newAttemptId().also {
            repo.begin(it, CardVerificationRequest(), OutcomeFixtures.config)
            repo.recordPhase(it, JournalPhase.SENDING, 1)
            repo.finish(it, OutcomeFixtures.unknown(CommandCode.CARD_VERIFICATION))
        }
        val ids = (1..4).map { repo.newAttemptId().also { id -> repo.begin(id, pay, OutcomeFixtures.config); repo.finish(id, OutcomeFixtures.answered()) } }

        val shown = repo.entries.value.map { it.attemptId }
        assertEquals(listOf(ids[3], ids[2], unknownOld), shown, "ultimi 2 + il non risolto")
    }
}
