package it.prime.protocollo17.ecr17.session

import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.event.ExchangeListener
import it.prime.protocollo17.ecr17.event.FrameDirection
import it.prime.protocollo17.ecr17.event.FrameKind
import it.prime.protocollo17.ecr17.event.ProtocolEvent
import it.prime.protocollo17.ecr17.event.ProtocolWarning
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.message.RequestEncoder
import it.prime.protocollo17.ecr17.message.ResponseParser
import it.prime.protocollo17.ecr17.model.AckMode
import it.prime.protocollo17.ecr17.model.CardVerificationRequest
import it.prime.protocollo17.ecr17.model.CloseCause
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.EcrPrintingRequest
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.HasResult
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.PhaseChange
import it.prime.protocollo17.ecr17.model.ReceiptLine
import it.prime.protocollo17.ecr17.model.ReprintRequest
import it.prime.protocollo17.ecr17.model.ReprintTarget
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.ecr17.model.TerminalIds
import it.prime.protocollo17.ecr17.model.TotalsRequest
import it.prime.protocollo17.ecr17.model.Verdict
import it.prime.protocollo17.ecr17.profile.AppLrcRxPolicy
import it.prime.protocollo17.ecr17.profile.ControlLrcRxPolicy
import it.prime.protocollo17.ecr17.profile.FinancialAckPolicy
import it.prime.protocollo17.ecr17.profile.ProtocolOptions
import it.prime.protocollo17.ecr17.profile.TerminalProfileId
import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.ecr17.testing.ScriptedTransport
import it.prime.protocollo17.ecr17.wire.DiscardReason
import it.prime.protocollo17.ecr17.wire.Hex
import it.prime.protocollo17.ecr17.wire.LrcMode
import it.prime.protocollo17.ecr17.wire.PanMasker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Scenari di sessione di §7.3 (SE-01…SE-53) in tempo virtuale, con il trasporto scriptato.
 *
 * Proteggono l'algoritmo vincolante di §3.21 e gli invarianti di money-safety: nessun reinvio di un pagamento senza
 * NAK valido (M2), un frame diventa esito solo col codice atteso (M4), ACK finale e drain best effort (M5), PAN
 * mascherato negli eventi (M9), errori del listener che fermano il comando solo prima della prima scrittura (M3,
 * M10). Profilo `Frames.fast` (stx, WIRE, tollerante, IMPLICIT_GO_AHEAD, ACK 40 ms, esito 200 ms) salvo dove
 * indicato. "Invii" = scritture del frame principale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Ecr17SessionTest {

    // ─── Harness ─────────────────────────────────────────────────────────────

    /** Listener di test: registra l'ordine delle chiamate e può lanciare sulla fase scelta. */
    private class TestListener(private val log: MutableList<String>) : ExchangeListener {
        val phases = mutableListOf<PhaseChange>()
        val progress = mutableListOf<String>()
        val receipts = mutableListOf<ReceiptLine>()
        val warnings = mutableListOf<ProtocolWarning>()
        var failOn: (PhaseChange) -> Throwable? = { null }
        var onPhaseHook: (PhaseChange) -> Unit = {}

        override suspend fun onPhase(change: PhaseChange) {
            phases += change
            log += "phase(${change.phase},${change.sends})"
            onPhaseHook(change)
            failOn(change)?.let { throw it }
        }

        override fun onProgress(text: String) {
            progress += text
        }

        override fun onReceiptLine(line: ReceiptLine) {
            receipts += line
        }

        override fun onWarning(warning: ProtocolWarning) {
            warnings += warning
        }
    }

    private class Harness(scope: TestScope, val options: ProtocolOptions, val command: CommandCode) {
        val log = mutableListOf<String>()
        val transport = ScriptedTransport(scope.backgroundScope, wallClock = { scope.testScheduler.currentTime })
        val events = mutableListOf<ProtocolEvent>()
        private var seq = 0L
        private val emitter = EventEmitter { build -> events += build(++seq, scope.testScheduler.currentTime) }
        val listener = TestListener(log)
        val token = CancelToken()
        val trace = TraceRecorder(
            exchangeId = 1,
            command = command,
            endpoint = ENDPOINT,
            profile = TerminalProfileId.INGENICO,
            optionsSummary = options.summary(),
            listener = listener,
            emitter = emitter,
            wallClock = { scope.testScheduler.currentTime },
        )
        val session = Ecr17Session(
            transport = transport,
            options = options,
            emitter = emitter,
            trace = trace,
            cancel = token,
            timeSource = scope.testScheduler.timeSource,
        )

        init {
            transport.onWrite = { bytes -> log += if (bytes[0] == STX) "write(app)" else "write(ctrl)" }
        }

        /** Spec costruita con le stesse regole del client (§3.23 punto 7). */
        fun spec(
            payload: ByteArray = defaultPayload(command),
            collectReceipt: Boolean = false,
            fresh: Boolean = true,
        ) = ExchangeSpec(
            exchangeId = 1,
            command = command,
            payload = payload,
            expectedReplies = command.expectedReplies,
            kind = command.kind,
            ackPolicy = if (command.usesFinancialAckPolicy) options.financialAckPolicy else FinancialAckPolicy.RETRANSMIT,
            collectReceiptAfterAck = collectReceipt,
            expectReceipt = (options.ecrPrinting && command.wantsEcrPrintingSetup) || collectReceipt,
            strictResultLrc = command.journaled || command == CommandCode.LAST_RESULT,
            freshConnection = fresh,
        )

        suspend fun run(spec: ExchangeSpec = spec()): ExchangeResult {
            if (!transport.isOpen) transport.connect(ENDPOINT, 1_000)
            return session.run(spec)
        }

        val warningCodes: List<WarningCode> get() = trace.warnings.map { it.code }
        val phaseList: List<ExchangePhase> get() = listener.phases.map { it.phase }
        val wireRx: List<ProtocolEvent.Wire>
            get() = events.filterIsInstance<ProtocolEvent.Wire>().filter { it.direction == FrameDirection.RX }
        val discarded: List<ProtocolEvent.Discarded> get() = events.filterIsInstance<ProtocolEvent.Discarded>()
        val writesHex: List<String> get() = transport.writes.map { Hex.spaced(it) }
    }

    private fun TestScope.harness(options: ProtocolOptions = Frames.fast, command: CommandCode = CommandCode.PAYMENT) =
        Harness(this, options, command)

    private fun bytes(delayMs: Long, vararg parts: ByteArray): ScriptedTransport.Step =
        ScriptedTransport.Step.Bytes(delayMs, parts.fold(ByteArray(0)) { acc, part -> acc + part })

    private fun closeAt(delayMs: Long): ScriptedTransport.Step = ScriptedTransport.Step.Close(delayMs)

    private fun app(payload: String, mode: LrcMode = LrcMode.STX) = Frames.app(payload, mode)

    private fun raw(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** LRC alterato con un delta che non è valido in nessuna delle 4 modalità (che differiscono di 01/02/03). */
    private fun corruptLrc(frame: ByteArray): ByteArray =
        frame.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0x10).toByte() }

    private fun success(result: ExchangeResult): ExchangeResult.Success =
        assertInstanceOf(ExchangeResult.Success::class.java, result, "atteso Success, era $result")

    private fun failure(result: ExchangeResult): ExchangeResult.Failure =
        assertInstanceOf(ExchangeResult.Failure::class.java, result, "atteso Failure, era $result")

    private fun ExchangeResult.Success.text(): String =
        String(checkNotNull(result) { "esito assente" }.payload, Charsets.ISO_8859_1)

    private fun masked(payload: String): String =
        String(PanMasker.maskApplicationPayload(payload.toByteArray(Charsets.ISO_8859_1)), Charsets.ISO_8859_1)

    private fun Harness.assertWarning(code: WarningCode) =
        assertTrue(code in warningCodes, "atteso il warning $code, presenti: $warningCodes")

    private fun Harness.assertNoWarning(code: WarningCode) =
        assertFalse(code in warningCodes, "warning $code inatteso, presenti: $warningCodes")

    // ─── Handshake ───────────────────────────────────────────────────────────

    /** A§9.6: in std con regola SAME_AS_APP ACK ed esito nello stesso chunk; l'ACK finale è 06 03 79. */
    @Test
    fun `SE-01 happy path std, ACK ed esito nello stesso chunk`() = runTest {
        val h = harness(options = Frames.fastStd)
        h.transport.onApplicationFrame(bytes(0, Frames.ACK_STD, app(Frames.RESULT_STD, LrcMode.STD)))

        val r = success(h.run())

        assertEquals(Frames.RESULT_STD, r.text())
        assertEquals(AckMode.EXPLICIT, r.ackMode)
        assertEquals(1, h.trace.sends)
        assertEquals("06 03 79", h.writesHex.last(), "ACK finale in std/SAME_AS_APP")
    }

    /** L§13.5 I-1, U2: il flusso reale dell'Ingenico (ACK 7A in stx, progress, esito E4) con il frame P1 della cattura. */
    @Test
    fun `SE-02 ACK 7A accettato in modalita stx (flusso reale)`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, Frames.PRG, app(Frames.E4)))

        val r = success(h.run(h.spec(payload = Frames.P1.toByteArray(Charsets.ISO_8859_1))))

        assertEquals(Frames.E4, r.text())
        assertEquals(1, h.trace.sends)
        val sent = h.transport.applicationWrites.single()
        assertArrayEquals(app(Frames.P1), sent, "frame P1 della cattura A§9.8")
        assertEquals(0x2F, sent.last().toInt() and 0xFF, "LRC stx di P1")
        assertEquals("06 03 7A", h.writesHex.last(), "ACK finale con regola WIRE")
        assertEquals(listOf("OPERAZIONE IN CORSO "), h.listener.progress)
        assertTrue(h.events.any { it is ProtocolEvent.Progress }, "evento progress")
    }

    /** I-2: esito approvato reale. */
    @Test
    fun `SE-03 esito approvato reale`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, Frames.PRG, app(Frames.E22)))

        assertEquals(Frames.E22, success(h.run()).text())
    }

    /** U3: un ACK SAME_AS_APP (78 in stx) è accettato anche con regola di uscita WIRE, senza avvisi. */
    @Test
    fun `SE-04 ACK 78 accettato con regola WIRE`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACK78, app(Frames.E22)))

        val r = success(h.run())

        assertEquals(AckMode.EXPLICIT, r.ackMode)
        assertTrue(h.trace.warnings.isEmpty(), "nessun warning atteso: ${h.warningCodes}")
    }

    /** U3, L§13.3: ACK con LRC di nessuna convenzione accettato in ricezione tollerante, con warning. */
    @Test
    fun `SE-05 ACK con LRC errato tollerato con warning`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, raw(0x06, 0x03, 0x00), app(Frames.E22)))

        val r = success(h.run())

        assertEquals(AckMode.EXPLICIT, r.ackMode)
        h.assertWarning(WarningCode.CONTROL_LRC_MISMATCH)
    }

    /** U3: in ricezione rigida l'ACK con LRC errato è scartato; senza altri segnali scatta il go-ahead, mai un reinvio. */
    @Test
    fun `SE-06 ACK con LRC errato scartato in modalita rigida`() = runTest {
        val h = harness(options = Frames.fast.copy(controlLrcRx = ControlLrcRxPolicy.STRICT))
        h.transport.onApplicationFrame(bytes(0, raw(0x06, 0x03, 0x00)), bytes(60, app(Frames.E22)))

        val r = success(h.run())

        assertTrue(h.discarded.any { it.reason == DiscardReason.CONTROL_LRC_STRICT }, "scarto CONTROL_LRC_STRICT")
        assertEquals(AckMode.IMPLICIT_GO_AHEAD, r.ackMode)
        assertEquals(1, h.trace.sends)
        assertEquals(Frames.E22, r.text())
    }

    /** A§9.6: 06 FF è scartato, l'ACK valido che segue chiude l'handshake. */
    @Test
    fun `SE-07 ACK spurio 06 FF poi ACK valido`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, raw(0x06, 0xFF), Frames.ACKW, app(Frames.E22)))

        val r = success(h.run())

        assertTrue(h.discarded.isNotEmpty(), "06 FF scartato")
        assertEquals(AckMode.EXPLICIT, r.ackMode)
    }

    /** I-3, U4, M2: senza ACK un pagamento NON viene reinviato; si attende l'esito. */
    @Test
    fun `SE-08 implicit go-ahead, un solo invio senza ACK`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(100, app(Frames.E22)))

        val r = success(h.run())

        assertEquals(1, h.trace.sends, "MONEY-SAFETY: un solo invio")
        assertEquals(AckMode.IMPLICIT_GO_AHEAD, r.ackMode)
        h.assertWarning(WarningCode.IMPLICIT_GO_AHEAD)
        assertEquals(
            listOf(
                ExchangePhase.SENDING, ExchangePhase.AWAITING_ACK, ExchangePhase.IMPLICIT_GO_AHEAD,
                ExchangePhase.AWAITING_RESULT, ExchangePhase.RESULT_RECEIVED, ExchangePhase.DRAINING,
                ExchangePhase.COMPLETED,
            ),
            h.phaseList,
        )
    }

    /** I-4: con RETRANSMIT e silenzio totale 1 invio + 3 reinvii, poi AckTimeout. */
    @Test
    fun `SE-09 RETRANSMIT, 4 invii poi AckTimeout`() = runTest {
        val h = harness(options = Frames.fast.copy(financialAckPolicy = FinancialAckPolicy.RETRANSMIT))

        val f = failure(h.run())

        assertEquals(Ecr17Failure.AckTimeout(4), f.failure)
        assertEquals(4, h.trace.sends)
        assertEquals(3, h.trace.timeoutRetransmissions)
        assertTrue(h.trace.anyMainFrameWriteAttempted)
    }

    /** U4: NAK valido a ogni invio ⇒ 3 reinvii poi Nak(4); nessun reinvio per timeout. */
    @Test
    fun `SE-10 NAK ritrasmette fino a 3 volte poi rifiuta`() = runTest {
        val h = harness()
        repeat(4) { h.transport.onApplicationFrame(bytes(0, Frames.NAKW)) }

        val f = failure(h.run())

        assertEquals(Ecr17Failure.Nak(4), f.failure)
        assertEquals(ExchangePhase.AWAITING_ACK, f.phase)
        assertEquals(4, h.trace.sends)
        assertEquals(4, h.trace.naksReceived)
        assertEquals(0, h.trace.timeoutRetransmissions)
    }

    /** I-5, A§9.6: NAK al primo invio, ACK ed esito al secondo. */
    @Test
    fun `SE-11 NAK poi ACK, due invii`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.NAKW))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E4)))

        val r = success(h.run())

        assertEquals(2, h.trace.sends)
        assertEquals(Frames.E4, r.text())
    }

    /** A§9.6: ACK ma nessun esito entro responseTimeoutMs. */
    @Test
    fun `SE-12 timeout esito dopo l'ACK`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW))

        val f = failure(h.run())

        assertEquals(Ecr17Failure.ResponseTimeout(200), f.failure)
        assertEquals(ExchangePhase.AWAITING_RESULT, f.phase)
    }

    /** I-6: né ACK né esito con il go-ahead: un solo invio e timeout dell'esito. */
    @Test
    fun `SE-13 nessun ACK ne esito con go-ahead`() = runTest {
        val h = harness()

        val f = failure(h.run())

        assertEquals(1, h.trace.sends)
        assertEquals(Ecr17Failure.ResponseTimeout(200), f.failure)
        assertEquals(ExchangePhase.AWAITING_RESULT, f.phase)
        assertEquals(AckMode.IMPLICIT_GO_AHEAD, h.trace.ackMode)
    }

    /** A§9.6: caduta subito dopo l'invio ⇒ Disconnected con scrittura tentata (il client ne farà un Unknown). */
    @Test
    fun `SE-14 caduta subito dopo l'invio`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(closeAt(0))

        val f = failure(h.run())

        assertEquals(CloseCause.PEER_CLOSED, (f.failure as Ecr17Failure.Disconnected).cause)
        assertTrue(h.trace.anyMainFrameWriteAttempted)
    }

    /** D2, M5: l'ACK finale fallisce ma l'esito già decodificato resta valido. */
    @Test
    fun `SE-15 caduta dopo l'esito, ACK finale fallito ma esito valido`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)))
        h.transport.failWritesAfter(1)

        val r = success(h.run())

        assertEquals(Frames.E22, r.text())
        h.assertWarning(WarningCode.CONTROL_SEND_FAILED)
        assertEquals(1, h.transport.writes.size, "solo il frame principale è stato scritto")
    }

    /** D3, M5: una caduta durante il drain non cambia l'esito. */
    @Test
    fun `SE-16 caduta durante il drain, esito valido`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)), closeAt(5))

        val r = success(h.run())

        assertEquals(Frames.E22, r.text())
        h.assertNoWarning(WarningCode.DRAIN_INTERRUPTED)
    }

    // ─── Orfani e codici inattesi ────────────────────────────────────────────

    /** D5: un esito stantio trovato alla connessione non riceve ACK, è segnalato come prova e non diventa l'esito. */
    @Test
    fun `SE-17 byte stantii scartati prima dell'invio`() = runTest {
        val h = harness()
        h.transport.onConnect(bytes(0, app(Frames.E22)))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E4)))

        val r = success(h.run())

        assertEquals(Frames.E4, r.text(), "l'esito è quello dopo l'invio")
        h.assertWarning(WarningCode.STALE_BYTES)
        val lost = h.trace.warnings.single { it.code == WarningCode.POSSIBLE_LOST_RESULT }
        assertEquals(masked(Frames.E22), lost.payload, "payload mascherato allegato")
        assertEquals(2, h.transport.writes.size, "nessun ACK all'orfano: solo P e l'ACK di E4")
    }

    /** I-8, U8: un 'E' durante uno 's' riceve ACK, è orfano e non diventa mai esito. */
    @Test
    fun `SE-18 codice inatteso mai restituito come esito`() = runTest {
        val h = harness(command = CommandCode.STATUS)
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E4)))

        val f = failure(h.run())

        assertEquals(Ecr17Failure.ResponseTimeout(200), f.failure)
        h.assertWarning(WarningCode.UNEXPECTED_REPLY)
        h.assertWarning(WarningCode.POSSIBLE_LOST_RESULT)
        assertEquals(listOf("06 03 7A"), h.writesHex.drop(1), "ACK inviato per E4")
    }

    /** L§13.6, U8: un 'U' stantio durante un pagamento è ignorato (ACK + warning) e l'esito vero arriva. */
    @Test
    fun `SE-19 U stantio ignorato durante un pagamento`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.U34), app(Frames.E22)))

        val r = success(h.run())

        assertEquals(Frames.E22, r.text())
        h.assertWarning(WarningCode.UNEXPECTED_REPLY)
        h.assertNoWarning(WarningCode.POSSIBLE_LOST_RESULT)
    }

    // ─── LRC dei frame applicativi ───────────────────────────────────────────

    /** A§9.6: esito con LRC errato ⇒ NAK; il terminale ritrasmette quello corretto. */
    @Test
    fun `SE-20 esito con LRC errato, NAK e attesa del corretto`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(
            bytes(0, Frames.ACKW),
            bytes(10, corruptLrc(app(Frames.E4))),
            bytes(50, app(Frames.E4)),
        )

        val r = success(h.run())

        assertEquals(Frames.E4, r.text())
        assertTrue("15 03 69" in h.writesHex, "NAK inviato: ${h.writesHex}")
        h.assertWarning(WarningCode.APP_LRC_INVALID)
    }

    /** L§10.3: per un comando non journaled un LRC valido solo in std è accettato con warning. */
    @Test
    fun `SE-21 esito valido solo in std accettato con warning (comando non journaled)`() = runTest {
        val h = harness(command = CommandCode.TOTALS)
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.T34, LrcMode.STD)))

        val r = success(h.run())

        assertEquals(Frames.T34, r.text())
        h.assertWarning(WarningCode.APP_LRC_OTHER_MODE)
    }

    /** L§10.3: con LRC applicativo rigido lo stesso frame riceve NAK e non diventa esito. */
    @Test
    fun `SE-22 esito valido solo in std con LRC rigido, NAK`() = runTest {
        val h = harness(options = Frames.fast.copy(appLrcRx = AppLrcRxPolicy.STRICT), command = CommandCode.TOTALS)
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.T34, LrcMode.STD)))

        val f = failure(h.run())

        assertTrue("15 03 69" in h.writesHex, "NAK inviato: ${h.writesHex}")
        assertEquals(Ecr17Failure.ResponseTimeout(200), f.failure)
    }

    /** A§9.6, A§11 p.7: esito atteso senza ACK prima ⇒ RESULT_BEFORE_ACK e, per un pagamento, "da verificare". */
    @Test
    fun `SE-23 esito senza ACK`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, app(Frames.E22)))

        val r = success(h.run())

        assertEquals(AckMode.RESULT_BEFORE_ACK, r.ackMode)
        assertEquals(1, h.trace.sends)
        h.assertWarning(WarningCode.EARLY_RESULT)
    }

    /** L§13.3: un 06 nudo seguito dall'esito è un ACK BARE e l'esito resta integro. */
    @Test
    fun `SE-24 ACK nudo seguito dall'esito`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, raw(0x06), app(Frames.E22)))

        val r = success(h.run())

        assertEquals(AckMode.BARE, r.ackMode)
        assertEquals(Frames.E22, r.text())
    }

    // ─── Scontrino, drain, progress ──────────────────────────────────────────

    /** A§9.6: righe S prima dell'esito: evento receipt e ACK sia alla riga sia all'esito. */
    @Test
    fun `SE-25 righe S prima dell'esito`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.RECEIPT), app(Frames.E22)))

        val r = success(h.run())

        assertEquals(Frames.E22, r.text())
        assertEquals(1, h.listener.receipts.size)
        assertTrue(h.events.any { it is ProtocolEvent.Receipt }, "evento receipt")
        assertEquals(listOf("06 03 7A", "06 03 7A"), h.writesHex.drop(1), "ACK alla riga S e all'esito")
    }

    /** A§5.5: con stampa su ECR le righe S dopo l'esito vengono raccolte; la sentinella chiude subito il drain. */
    @Test
    fun `SE-26 righe S dopo l'esito con stampa su ECR`() = runTest {
        val h = harness(options = Frames.fast.copy(ecrPrinting = true))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)), bytes(30, app(Frames.RECEIPT_END)))

        success(h.run())

        val line = h.trace.build(0).receipt.single()
        assertTrue(line.isEnd, "riga con sentinella")
        h.assertNoWarning(WarningCode.RECEIPT_END_MISSING)
        val completed = h.listener.phases.last { it.phase == ExchangePhase.COMPLETED }
        assertEquals(30L, completed.at, "drain chiuso dalla sentinella, non dalla finestra di 60 ms")
    }

    /** I-7, R14: l'esito ritrasmesso nel drain riceve un nuovo ACK ed è segnalato, mai un secondo esito. */
    @Test
    fun `SE-27 esito ritrasmesso nel drain`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E4)), bytes(10, app(Frames.E4)))

        val r = success(h.run())

        assertEquals(Frames.E4, r.text())
        h.assertWarning(WarningCode.RESULT_RETRANSMITTED)
        assertEquals(1, h.trace.resultRetransmissions)
        assertEquals(listOf("06 03 7A", "06 03 7A"), h.writesHex.drop(1), "due ACK in TX")
    }

    /** §3.21: un progress prima dell'ACK è un ACK implicito; l'ACK che segue è accettato senza warning. */
    @Test
    fun `SE-28 progress durante l'handshake, ACK implicito`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.PRG, Frames.ACKW, app(Frames.E4)))

        val r = success(h.run())

        assertEquals(AckMode.PROGRESS_BEFORE_ACK, r.ackMode)
        assertEquals(
            listOf(
                ExchangePhase.SENDING, ExchangePhase.AWAITING_ACK, ExchangePhase.ACKNOWLEDGED,
                ExchangePhase.AWAITING_RESULT, ExchangePhase.RESULT_RECEIVED, ExchangePhase.DRAINING,
                ExchangePhase.COMPLETED,
            ),
            h.phaseList,
        )
        // §7.3: "senza warning" vale per QUALSIASI warning (PRG è di 20 caratteri: niente PROGRESS_LENGTH).
        assertTrue(h.trace.warnings.isEmpty(), "nessun warning atteso: ${h.warningCodes}")
        assertFalse(h.trace.lateAck, "l'ACK preceduto dal progress non è un ACK tardivo")
    }

    /** A§5.2: i progress non estendono la deadline dell'esito. */
    @Test
    fun `SE-29 deadline esito non estesa dai progress`() = runTest {
        val h = harness()
        val progressEvery50 = (1..10).map { bytes(it * 50L, Frames.PRG) }
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW), *progressEvery50.toTypedArray())

        val f = failure(h.run())

        assertEquals(Ecr17Failure.ResponseTimeout(200), f.failure)
        val enteredAt = h.listener.phases.first { it.phase == ExchangePhase.AWAITING_RESULT }.at
        assertEquals(enteredAt + 200, testScheduler.currentTime, "timeout a 200 ms dall'ingresso in AWAITING_RESULT")
        assertTrue(h.listener.progress.size >= 3, "i progress sono arrivati: ${h.listener.progress.size}")
    }

    /** §3.21: un NAK dopo il go-ahead, senza altri segnali, è il NAK tardivo del nostro frame ⇒ reinvio. */
    @Test
    fun `SE-30 NAK tardivo dopo il go-ahead, reinvio`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(60, Frames.NAKW))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E4)))

        val r = success(h.run())

        assertEquals(Frames.E4, r.text())
        assertEquals(2, h.trace.sends)
        assertEquals(1, h.trace.naksReceived)
        assertEquals(0, h.trace.timeoutRetransmissions)
        h.assertNoWarning(WarningCode.POSSIBLE_DUPLICATE)
    }

    /** §3.21: ACK dopo il go-ahead ⇒ LATE_ACK e fase ACKNOWLEDGED, l'esito arriva dopo. */
    @Test
    fun `SE-31 ACK tardivo dopo il go-ahead`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(60, Frames.ACKW), bytes(100, app(Frames.E4)))

        success(h.run())

        h.assertWarning(WarningCode.LATE_ACK)
        assertTrue(h.trace.lateAck)
        val phases = h.phaseList
        assertTrue(
            phases.indexOf(ExchangePhase.ACKNOWLEDGED) > phases.indexOf(ExchangePhase.IMPLICIT_GO_AHEAD),
            "ACKNOWLEDGED registrata dopo il go-ahead: $phases",
        )
    }

    /** §3.21: l'ACK tardivo non estende la deadline dell'esito, fissata all'ingresso dopo il go-ahead. */
    @Test
    fun `SE-31b ACK tardivo, deadline non estesa`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(60, Frames.ACKW))

        val f = failure(h.run())

        assertEquals(Ecr17Failure.ResponseTimeout(200), f.failure)
        assertEquals(40L + 200L, testScheduler.currentTime, "go-ahead a 40 ms + 200 ms, non 60 + 200")
    }

    /** A§9.6: comando solo-ACK (E1): ACK ⇒ successo senza esito. */
    @Test
    fun `SE-32 solo ACK, E1 confermato`() = runTest {
        val h = harness(command = CommandCode.ECR_PRINTING)
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW))

        val r = success(h.run())

        assertNull(r.result)
        assertEquals(AckMode.EXPLICIT, r.ackMode)
        assertEquals(1, h.trace.sends)
    }

    /** A§9.6: E1 con NAK e poi ACK ⇒ due invii. */
    @Test
    fun `SE-32 solo ACK, E1 con NAK`() = runTest {
        val h = harness(command = CommandCode.ECR_PRINTING)
        h.transport.onApplicationFrame(bytes(0, Frames.NAKW))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW))

        val r = success(h.run())

        assertNull(r.result)
        assertEquals(2, h.trace.sends)
    }

    /** A§9.6: E1 senza risposta ⇒ 3 reinvii (RETRANSMIT) e AckTimeout(4). */
    @Test
    fun `SE-32 solo ACK, E1 in timeout`() = runTest {
        val h = harness(command = CommandCode.ECR_PRINTING)

        val f = failure(h.run())

        assertEquals(Ecr17Failure.AckTimeout(4), f.failure)
    }

    /** §3.21: la ristampa verso ECR raccoglie le righe S dopo l'ACK fino alla sentinella. */
    @Test
    fun `SE-33 ristampa verso ECR raccoglie le righe S`() = runTest {
        val h = harness(command = CommandCode.REPRINT)
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.RECEIPT), app(Frames.RECEIPT_END)))

        val r = success(h.run(h.spec(collectReceipt = true)))

        assertNull(r.result)
        val receipt = h.trace.build(0).receipt
        assertEquals(2, receipt.size)
        assertTrue(receipt.last().isEnd)
        h.assertNoWarning(WarningCode.RECEIPT_END_MISSING)
    }

    // ─── Listener e journal (M3, M10) ────────────────────────────────────────

    /** M3: onPhase(SENDING) è attesa prima di OGNI scrittura del frame principale. */
    @Test
    fun `SE-34 onPhase(SENDING) attesa prima di ogni scrittura`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.NAKW))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E4)))

        success(h.run())

        val log = h.log
        val sending1 = log.indexOf("phase(SENDING,1)")
        val sending2 = log.indexOf("phase(SENDING,2)")
        val appWrites = log.withIndex().filter { it.value == "write(app)" }.map { it.index }
        assertEquals(2, appWrites.size, "due scritture del frame: $log")
        assertTrue(sending1 in 0 until appWrites[0], "phase(SENDING,1) < write1: $log")
        assertTrue(appWrites[0] < sending2 && sending2 < appWrites[1], "write1 < phase(SENDING,2) < write2: $log")
    }

    /** M3: journal non scrivibile prima del primo invio ⇒ nessun byte inviato e fallimento Internal. */
    @Test
    fun `SE-35 journal non scrivibile prima del primo invio, nessun byte inviato`() = runTest {
        val h = harness()
        h.listener.failOn = { if (it.phase == ExchangePhase.SENDING && it.sends == 1) IOException("disco pieno") else null }

        val f = failure(h.run())

        assertInstanceOf(Ecr17Failure.Internal::class.java, f.failure)
        assertFalse(h.trace.anyMainFrameWriteAttempted)
        assertTrue(h.transport.writes.isEmpty(), "nessun byte scritto: ${h.writesHex}")
    }

    /** A§8 D14: Annulla dopo l'ACK (token + abort) ⇒ Cancelled(byUser) con scrittura tentata. */
    @Test
    fun `SE-36 annullamento dopo l'ACK`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW))
        backgroundScope.launch {
            delay(50)
            h.token.cancel()
            h.transport.abort()
        }

        val f = failure(h.run())

        assertEquals(Ecr17Failure.Cancelled(byUser = true), f.failure)
        assertTrue(h.trace.anyMainFrameWriteAttempted)
    }

    /** M9: il PAN in chiaro dell'esito non esce negli eventi RX né nel resultRaw del journal. */
    @Test
    fun `SE-37 PAN mascherato negli eventi RX`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22_CLEAR)))

        success(h.run())

        val rx = h.wireRx.single { it.kind == FrameKind.APPLICATION }
        assertArrayEquals(PanMasker.maskFrame(app(Frames.E22_CLEAR)), rx.bytes)
        assertFalse(String(rx.bytes, Charsets.ISO_8859_1).contains("4111111111111111"), "PAN in chiaro nell'evento")
        val received = h.listener.phases.single { it.phase == ExchangePhase.RESULT_RECEIVED }
        assertEquals(masked(Frames.E22_CLEAR), received.resultRaw)
        assertFalse(received.resultRaw!!.contains("4111111111111111"))
    }

    /** M10: un errore del listener su ACKNOWLEDGED non interrompe lo scambio. */
    @Test
    fun `SE-38 listener che lancia su ACKNOWLEDGED, lo scambio prosegue`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)))
        h.listener.failOn = { if (it.phase == ExchangePhase.ACKNOWLEDGED) IOException("journal") else null }

        val r = success(h.run())

        assertEquals(Frames.E22, r.text())
        h.assertWarning(WarningCode.JOURNAL_WRITE_FAILED)
        assertFalse(
            h.listener.warnings.any { it.code == WarningCode.JOURNAL_WRITE_FAILED },
            "JOURNAL_WRITE_FAILED non va rinviato al listener",
        )
    }

    /** M10: un errore del listener su IMPLICIT_GO_AHEAD non interrompe lo scambio. */
    @Test
    fun `SE-39 listener che lancia su IMPLICIT_GO_AHEAD, lo scambio prosegue`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(100, app(Frames.E22)))
        h.listener.failOn = { if (it.phase == ExchangePhase.IMPLICIT_GO_AHEAD) IOException("journal") else null }

        val r = success(h.run())

        assertEquals(Frames.E22, r.text())
        h.assertWarning(WarningCode.JOURNAL_WRITE_FAILED)
    }

    /** M10: dopo la prima scrittura un errore su SENDING#2 non blocca il reinvio. */
    @Test
    fun `SE-40 listener che lancia su SENDING 2 dopo un timeout, reinvio comunque`() = runTest {
        val h = harness(options = Frames.fast.copy(financialAckPolicy = FinancialAckPolicy.RETRANSMIT))
        h.transport.onApplicationFrame()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)))
        h.listener.failOn = { if (it.phase == ExchangePhase.SENDING && it.sends == 2) IOException("journal") else null }

        val r = success(h.run())

        assertEquals(2, h.trace.sends)
        assertEquals(Frames.E22, r.text())
        h.assertWarning(WarningCode.JOURNAL_WRITE_FAILED)
        h.assertWarning(WarningCode.POSSIBLE_DUPLICATE)
    }

    // ─── NAK e ACK ambigui (U3, §3.7) ────────────────────────────────────────

    /** U3: un 15 nudo seguito dall'esito non fa mai reinviare un pagamento. */
    @Test
    fun `SE-41 NAK nudo seguito dall'esito, nessun reinvio`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, raw(0x15), app(Frames.E22)))

        val r = success(h.run())

        assertEquals(1, h.trace.sends, "MONEY-SAFETY: nessun reinvio su NAK ambiguo")
        h.assertWarning(WarningCode.AMBIGUOUS_NAK)
        assertEquals(Frames.E22, r.text())
    }

    /** U3: NAK con LRC di nessuna convenzione ⇒ ambiguo, nessun reinvio, go-ahead ed esito. */
    @Test
    fun `SE-42 NAK con LRC di nessuna convenzione su un pagamento, nessun reinvio`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, raw(0x15, 0x03, 0x00)), bytes(100, app(Frames.E22)))

        val r = success(h.run())

        assertEquals(1, h.trace.sends, "MONEY-SAFETY: nessun reinvio su NAK ambiguo")
        h.assertWarning(WarningCode.AMBIGUOUS_NAK)
        assertEquals(AckMode.IMPLICIT_GO_AHEAD, r.ackMode)
        assertEquals(Frames.E22, r.text())
    }

    /** §3.7: 06 03 seguito da STX è un ACK BARE di 2 byte; l'esito non perde il suo STX. */
    @Test
    fun `SE-43 ACK troncato 06 03 seguito dall'esito`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, raw(0x06, 0x03), app(Frames.E22)))

        val r = success(h.run())

        assertEquals(AckMode.BARE, r.ackMode)
        assertEquals(Frames.E22, r.text())
    }

    // ─── RETRANSMIT (U4, M2) ─────────────────────────────────────────────────

    /** M2, L§7.3: anche con RETRANSMIT un progress prima dell'ACK vale come ACK: esattamente un invio. */
    @Test
    fun `SE-44 RETRANSMIT, progress prima dell'ACK vale come ACK`() = runTest {
        val h = harness(options = Frames.fast.copy(financialAckPolicy = FinancialAckPolicy.RETRANSMIT))
        h.transport.onApplicationFrame(bytes(10, Frames.PRG), bytes(100, app(Frames.E22)))

        val r = success(h.run())

        assertEquals(1, h.trace.sends, "MONEY-SAFETY: esattamente un invio")
        assertEquals(AckMode.PROGRESS_BEFORE_ACK, r.ackMode)
        assertEquals(Frames.E22, r.text())
    }

    /** §3.23: timeout seguito da NAK fino al limite ⇒ Nak con timeoutRetransmissions == 1 (il client ne fa un Unknown). */
    @Test
    fun `SE-45 RETRANSMIT, timeout poi NAK fino al limite`() = runTest {
        val h = harness(options = Frames.fast.copy(financialAckPolicy = FinancialAckPolicy.RETRANSMIT))
        h.transport.onApplicationFrame()
        repeat(4) { h.transport.onApplicationFrame(bytes(0, Frames.NAKW)) }

        val f = failure(h.run())

        assertInstanceOf(Ecr17Failure.Nak::class.java, f.failure)
        assertEquals(1, h.trace.timeoutRetransmissions)
        assertEquals(5, h.trace.sends)
        assertEquals(4, h.trace.naksReceived)
    }

    /** L§6.5 p.8: esito dopo un reinvio per ACK mancante ⇒ POSSIBLE_DUPLICATE (possibile doppio addebito). */
    @Test
    fun `SE-46 RETRANSMIT, timeout poi ACK ed esito`() = runTest {
        val h = harness(options = Frames.fast.copy(financialAckPolicy = FinancialAckPolicy.RETRANSMIT))
        h.transport.onApplicationFrame()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)))

        val r = success(h.run())

        assertEquals(Frames.E22, r.text())
        assertEquals(2, h.trace.sends)
        h.assertWarning(WarningCode.POSSIBLE_DUPLICATE)
    }

    /** §3.21: un 'U' stantio prima dell'ACK non è un ACK implicito: il NAK che segue fa ancora reinviare. */
    @Test
    fun `SE-47 U stantio prima dell'ACK, poi NAK, il NAK vale ancora`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(5, app(Frames.U34)), bytes(10, Frames.NAKW))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)))

        val r = success(h.run())

        assertEquals(Frames.E22, r.text())
        assertEquals(2, h.trace.sends)
        h.assertWarning(WarningCode.UNEXPECTED_REPLY)
        assertEquals("06 03 7A", h.writesHex[1], "ACK inviato al 'U'")
    }

    /** §3.16, R12: un esito con 1 bit alterato e LRC valido solo in std è rifiutato per un pagamento: mai APPROVED. */
    @Test
    fun `SE-48 errore di 1 bit valido solo in std rifiutato per un pagamento`() = runTest {
        val h = harness()
        val flipped = app(Frames.E4_FLIPPED_12, LrcMode.STD)
        assertEquals(0x46, flipped.last().toInt() and 0xFF, "vettore: LRC del frame alterato = LRC stx di E4")
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, flipped), bytes(50, app(Frames.E4)))

        val r = success(h.run())

        assertEquals(Frames.E4, r.text())
        assertTrue("15 03 69" in h.writesHex, "NAK al frame alterato: ${h.writesHex}")
        h.assertWarning(WarningCode.APP_LRC_INVALID)
        val response = ResponseParser.parse(r.result!!.payload, CommandCode.PAYMENT, maskPan = true) as HasResult
        assertEquals(Verdict.DECLINED, response.verdict, "MONEY-SAFETY: mai APPROVED")
    }

    /** M4: un 'E' arrivato durante uno 's' è orfano con prova mascherata, e lo 's' risponde normalmente. */
    @Test
    fun `SE-49 orfano della famiglia esiti durante un comando di servizio`() = runTest {
        val h = harness(command = CommandCode.STATUS)
        h.transport.onApplicationFrame(
            bytes(0, Frames.ACKW),
            bytes(5, app(Frames.E22)),
            bytes(20, app(STATUS_RESPONSE)),
        )

        val r = success(h.run())

        assertEquals(STATUS_RESPONSE, r.text())
        h.assertWarning(WarningCode.UNEXPECTED_REPLY)
        val lost = h.trace.warnings.single { it.code == WarningCode.POSSIBLE_LOST_RESULT }
        assertEquals(masked(Frames.E22), lost.payload)
        assertEquals(listOf("06 03 7A", "06 03 7A"), h.writesHex.drop(1), "ACK all'orfano e all'esito")
    }

    /** §3.21: un frame con LRC errato ripetuto all'infinito non tiene aperto il drain: tetto di 20 frame. */
    @Test
    fun `SE-50 drain con tetto, frame con LRC errato ripetuto all'infinito`() = runTest {
        val h = harness()
        val bad = corruptLrc(app(Frames.E4))
        val flood = (1..40).map { bytes(it * 15L, bad) }
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)), *flood.toTypedArray())

        val r = success(h.run())

        assertEquals(Frames.E22, r.text())
        h.assertWarning(WarningCode.DRAIN_INTERRUPTED)
        assertEquals(20, h.writesHex.count { it == "15 03 69" }, "un NAK per ciascuno dei 20 frame del drain")
        assertTrue(testScheduler.currentTime <= 5_000, "durata entro il tetto: ${testScheduler.currentTime}")
    }

    /** §3.21: con stampa su ECR attiva uno 's' non attende righe S: drain breve, nessun RECEIPT_END_MISSING. */
    @Test
    fun `SE-51 s con stampa su ECR, nessuna riga S attesa`() = runTest {
        val h = harness(options = Frames.fast.copy(ecrPrinting = true), command = CommandCode.STATUS)
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(STATUS_RESPONSE)))

        success(h.run())

        h.assertNoWarning(WarningCode.RECEIPT_END_MISSING)
        val draining = h.listener.phases.single { it.phase == ExchangePhase.DRAINING }.at
        val completed = h.listener.phases.single { it.phase == ExchangePhase.COMPLETED }.at
        assertEquals(Frames.fast.postResultDrainMs, completed - draining, "finestra = postResultDrainMs")
    }

    /** M9: PAN mascherato anche nei byte scartati (resync) e nelle righe S (evento e trace). */
    @Test
    fun `SE-52 PAN mascherato nei byte scartati e nelle righe S`() = runTest {
        val h = harness()
        val fragment = raw(0x02) + Frames.E22_CLEAR.substring(0, 35).toByteArray(Charsets.ISO_8859_1)
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.RECEIPT_PAN), fragment, app(Frames.E22)))

        val r = success(h.run())

        assertEquals(Frames.E22, r.text())
        val digitRun = Regex("[0-9]{13,}")
        val resync = h.discarded.filter { it.reason == DiscardReason.RESYNC_ON_NEW_START }
        assertTrue(resync.isNotEmpty(), "frammento scartato per resync")
        h.discarded.forEach {
            assertFalse(digitRun.containsMatchIn(String(it.bytes, Charsets.ISO_8859_1)), "PAN in chiaro in un Discarded")
        }
        val line = h.trace.build(0).receipt.single()
        assertFalse(line.raw.contains("4111111111111111") || line.text.contains("4111111111111111"), "riga S nella trace")
        val event = h.events.filterIsInstance<ProtocolEvent.Receipt>().single()
        assertFalse(event.line.text.contains("4111111111111111"), "riga S nell'evento")
        h.wireRx.forEach {
            assertFalse(String(it.bytes, Charsets.ISO_8859_1).contains("4111111111111111"), "PAN in chiaro in un Wire RX")
        }
    }

    /**
     * M9: un frammento di riga S con il PAN spezzato da 0x7F (doppia altezza), scartato per resync o rimasto come
     * rumore in coda, esce mascherato nel Discarded. Prima maskDigitRuns vedeva due sequenze da 8 cifre, sotto la
     * soglia, e il PAN arrivava in chiaro al log traffico.
     */
    @Test
    fun `SE-52b PAN spezzato da 0x7F mascherato nei byte scartati`() = runTest {
        val h = harness()
        val fragment = raw(0x02) + "123456780SPAN 41111111\u007F11111111".toByteArray(Charsets.ISO_8859_1)
        val noise = "41111111\u007F11111111".toByteArray(Charsets.ISO_8859_1)
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, fragment, app(Frames.E22), noise))

        success(h.run())

        val texts = h.discarded.map { String(it.bytes, Charsets.ISO_8859_1) }
        assertTrue(h.discarded.any { it.reason == DiscardReason.RESYNC_ON_NEW_START }, "frammento scartato per resync: $texts")
        // Frame troncato e rumore in coda sono pezzi tagliati: la sequenza che tocca la fine perde tutte le cifre (§3.8).
        assertEquals(2, texts.count { "********\u007F********" in it }, "frammento e rumore mascherati: $texts")
        texts.forEach { assertFalse("41111111" in it || "11111111" in it, "metà del PAN in chiaro in un Discarded: $it") }
    }

    // ─── M9 a cavallo dei frame (§3.8) ───────────────────────────────────────

    /** Riga S piena (200 caratteri) che finisce a metà del PAN, come nel formato a colonne Ingenico (A§9.11). */
    private val cutFirst = "025757330S" + "X".repeat(186) + "CARTA 41111111"
    private val cutSecond = "025757330S11111111 FINE}}\u001B"

    private fun ProtocolEvent.Wire.text() = String(bytes, Charsets.ISO_8859_1)

    /** Nessuna metà del PAN in chiaro in eventi Wire RX, eventi Receipt e trace. */
    private fun Harness.assertNoPanHalves() {
        val outputs = wireRx.map { it.text() } + events.filterIsInstance<ProtocolEvent.Receipt>()
            .flatMap { listOf(it.line.raw, it.line.text) } + trace.build(0).receipt.flatMap { listOf(it.raw, it.text) }
        outputs.forEach { assertFalse("41111111" in it || "11111111" in it, "metà del PAN in chiaro: $it") }
    }

    /**
     * Un PAN tagliato tra due righe S consecutive. Prima ogni riga era mascherata da sola: `41111111` | `11111111`
     * erano due sequenze sotto la soglia di 13 e il PAN usciva in chiaro nell'evento Wire RX (log traffico), nella riga
     * S e nella trace. Ora la coda della riga piena perde tutte le cifre e la testa della successiva tutte tranne le
     * ultime 4, con la stessa maschera in Wire RX, evento Receipt e trace.
     */
    @Test
    fun `SE-54 PAN tagliato tra due righe S`() = runTest {
        val h = harness(options = Frames.fast.copy(ecrPrinting = true))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)), bytes(30, app(cutFirst), app(cutSecond)))

        success(h.run())

        val lines = h.trace.build(0).receipt
        assertEquals(2, lines.size)
        assertTrue(lines[0].text.endsWith("CARTA ********"), lines[0].text)
        assertEquals("****1111 FINE", lines[1].text)
        assertEquals(lines, h.events.filterIsInstance<ProtocolEvent.Receipt>().map { it.line }, "eventi = trace")
        val wireS = h.wireRx.filter { it.code == 'S' }
        assertEquals(lines.map { it.raw }, wireS.map { it.text().substring(11, it.bytes.size - 2) }, "Wire RX = grezzo della riga")
        h.assertNoPanHalves()
        h.assertNoWarning(WarningCode.RECEIPT_END_MISSING)
    }

    /**
     * La seconda riga arriva con l'LRC errato (NAK) e poi corretta: la ritrasmissione continua ancora la riga prima,
     * quindi resta mascherata. Una riga rifiutata non abbassa mai la coda.
     */
    @Test
    fun `SE-55 riga S rifiutata e ritrasmessa, la coda resta`() = runTest {
        val h = harness(options = Frames.fast.copy(ecrPrinting = true))
        h.transport.onApplicationFrame(
            bytes(0, Frames.ACKW, app(Frames.E22)),
            bytes(30, app(cutFirst), corruptLrc(app(cutSecond)), app(cutSecond)),
        )

        success(h.run())

        h.assertWarning(WarningCode.APP_LRC_INVALID)
        val wireS = h.wireRx.filter { it.code == 'S' }
        assertEquals(3, wireS.size)
        wireS.drop(1).forEach { assertTrue("****1111 FINE" in it.text(), it.text()) }
        assertEquals("****1111 FINE", h.trace.build(0).receipt.last().text)
        h.assertNoPanHalves()
    }

    /** Righe S stantie arrivate prima dell'invio (orfani, §3.21 punto 1): la coda vale anche lì. */
    @Test
    fun `SE-56 righe S stantie con il PAN tagliato`() = runTest {
        val h = harness()
        h.transport.onConnect(bytes(0, app(cutFirst), app(cutSecond)))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)))

        success(h.run())

        h.assertWarning(WarningCode.STALE_BYTES)
        val stale = h.wireRx.filter { it.code == 'S' }.map { it.text() }
        assertEquals(2, stale.size)
        assertTrue("CARTA ********\u0003" in stale[0] && "****1111 FINE" in stale[1], stale.toString())
        h.assertNoPanHalves()
    }

    // Dalla revisione del 2026-10-19: casi in cui la coda vera non era quella tenuta dalla sessione.

    /** Seconda riga con 12 cifre in testa: senza la coda della prima, 12 cifre oltre le ultime 4 in chiaro. */
    private val shortCutFirst = "025757330S" + "X".repeat(190) + "CARTA 4111"
    private val longHead = "025757330S111111111111 TESTO"

    /**
     * Il terminale manda di nuovo una riga S già ACKata (il nostro ACK non gli è arrivato, o è arrivato tardi). Prima la
     * copia prendeva la coda lasciata dall'originale (0) e mostrava `111111111111` in Wire RX, riga e trace; ora una
     * copia identica continua la stessa riga dell'originale.
     */
    @Test
    fun `SE-57 riga S ripetuta dopo l'ACK, la copia resta mascherata`() = runTest {
        val h = harness(options = Frames.fast.copy(ecrPrinting = true))
        h.transport.onApplicationFrame(
            bytes(0, Frames.ACKW, app(Frames.E22)),
            bytes(30, app(shortCutFirst), app(longHead), app(longHead), app("025757330SFINE}}\u001B")),
        )

        success(h.run())

        val heads = h.wireRx.filter { it.code == 'S' }.map { it.text() }.filter { "TESTO" in it }
        assertEquals(2, heads.size)
        heads.forEach { assertTrue("********1111 TESTO" in it, it) }
        assertEquals(listOf("********1111 TESTO", "********1111 TESTO"), h.trace.build(0).receipt.map { it.text }.filter { "TESTO" in it })
        assertFalse(h.wireRx.any { "11111111" in it.text() }, "testa in chiaro in un Wire RX")
    }

    /** La copia rimasta nel buffer quando lo scontrino è già chiuso esce come residuo: coda sconosciuta, testa mascherata. */
    @Test
    fun `SE-58 copia di una riga S nel residuo`() = runTest {
        val h = harness(options = Frames.fast.copy(ecrPrinting = true))
        val last = "025757330S111111111111 FINE}}\u001B"
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)), bytes(30, app(shortCutFirst), app(last), app(last)))

        success(h.run())

        val residue = h.discarded.map { String(it.bytes, Charsets.ISO_8859_1) }.single { "FINE" in it }
        assertTrue("********1111 FINE" in residue, residue)
    }

    /**
     * Connessione riusata (PERSISTENT): le righe S rimaste a metà del comando precedente arrivano come orfani, e la coda
     * di quel comando non si conosce. La testa della prima riga S mostra solo le ultime 4 cifre.
     */
    @Test
    fun `SE-59 connessione riusata, coda sconosciuta`() = runTest {
        val h = harness()
        h.transport.onConnect(bytes(0, app(longHead)))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)))

        success(h.run(h.spec(fresh = false)))

        val stale = h.wireRx.single { it.code == 'S' }.text()
        assertTrue("********1111 TESTO" in stale, stale)
    }

    /** Byte scartati prima di una riga S: il pezzo perso può essere l'inizio del PAN, la testa dopo è mascherata. */
    @Test
    fun `SE-60 riga S dopo byte scartati`() = runTest {
        val h = harness(options = Frames.fast.copy(ecrPrinting = true))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)), bytes(30, raw(0xFF), app(cutSecond)))

        success(h.run())

        assertTrue(h.discarded.any { it.reason == DiscardReason.NOISE }, "byte scartato prima della riga")
        assertEquals("****1111 FINE", h.trace.build(0).receipt.single().text)
    }

    /** §5.5: la cancellazione della coroutine nel drain si propaga; RESULT_RECEIVED (con resultRaw) è già stata notificata. */
    @Test
    fun `SE-53 cancellazione della coroutine durante il drain`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)))
        val draining = CompletableDeferred<Unit>()
        h.listener.onPhaseHook = { if (it.phase == ExchangePhase.DRAINING) draining.complete(Unit) }
        var thrown: Throwable? = null

        val job = launch {
            try {
                h.run()
            } catch (e: CancellationException) {
                thrown = e
                throw e
            }
        }
        draining.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertInstanceOf(CancellationException::class.java, thrown, "CancellationException propagata")
        val received = h.listener.phases.single { it.phase == ExchangePhase.RESULT_RECEIVED }
        assertEquals(masked(Frames.E22), received.resultRaw)
        assertFalse(ExchangePhase.COMPLETED in h.phaseList)
    }

    /** §3.7, §3.21: il residuo dello splitter (rumore in coda) esce come Discarded(NOISE) a fine scambio, mai perso. */
    @Test
    fun `residuo dello splitter registrato a fine scambio`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22), raw(0xFF, 0xEE)))

        success(h.run())

        val residue = h.discarded.last()
        assertEquals(DiscardReason.NOISE, residue.reason)
        assertEquals("FF EE", Hex.spaced(residue.bytes))
    }

    // ─── Correzioni dalla revisione ──────────────────────────────────────────

    /** §3.21 Cancellazione (a): Annulla durante l'attesa del journal per SENDING ⇒ Cancelled, non Disconnected(ABORTED). */
    @Test
    fun `annullamento durante onPhase(SENDING) del primo invio, Cancelled e nessun byte`() = runTest {
        val h = harness()
        h.listener.onPhaseHook = {
            if (it.phase == ExchangePhase.SENDING) {
                h.token.cancel()
                h.transport.abort()
            }
        }

        val f = failure(h.run())

        assertEquals(Ecr17Failure.Cancelled(byUser = true), f.failure)
        assertEquals(ExchangePhase.SENDING, f.phase)
        assertFalse(h.trace.anyMainFrameWriteAttempted)
        assertTrue(h.transport.writes.isEmpty(), "nessun byte scritto: ${h.writesHex}")
    }

    /** §3.21 Cancellazione (a): Annulla durante SENDING del reinvio per NAK ⇒ Cancelled, con la prima scrittura tentata. */
    @Test
    fun `annullamento durante onPhase(SENDING) del reinvio, Cancelled`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.NAKW))
        h.listener.onPhaseHook = {
            if (it.phase == ExchangePhase.SENDING && it.sends == 2) {
                h.token.cancel()
                h.transport.abort()
            }
        }

        val f = failure(h.run())

        assertEquals(Ecr17Failure.Cancelled(byUser = true), f.failure)
        assertTrue(h.trace.anyMainFrameWriteAttempted)
        assertEquals(1, h.transport.applicationWrites.size, "il reinvio non è partito")
    }

    /** §3.21: in un comando solo ACK un progress è attività del terminale: al timeout nessun reinvio (M2). */
    @Test
    fun `solo ACK, progress senza ACK, AckTimeout senza reinvio`() = runTest {
        val h = harness(command = CommandCode.ECR_PRINTING)
        h.transport.onApplicationFrame(bytes(0, Frames.PRG))

        val f = failure(h.run())

        assertEquals(Ecr17Failure.AckTimeout(1), f.failure)
        assertEquals(ExchangePhase.AWAITING_ACK, f.phase)
        assertEquals(1, h.transport.applicationWrites.size, "un solo invio")
        assertEquals(0, h.trace.timeoutRetransmissions)
    }

    /** §3.21: anche una riga S inattesa in un comando solo ACK è attività del terminale: nessun reinvio. */
    @Test
    fun `solo ACK, riga S inattesa senza ACK, AckTimeout senza reinvio`() = runTest {
        val h = harness(command = CommandCode.ECR_PRINTING)
        h.transport.onApplicationFrame(bytes(0, app(Frames.RECEIPT)))

        val f = failure(h.run())

        assertEquals(Ecr17Failure.AckTimeout(1), f.failure)
        assertEquals(1, h.transport.applicationWrites.size, "un solo invio")
        h.assertWarning(WarningCode.UNEXPECTED_REPLY)
    }

    /** §3.21 p.5, A§5.5: righe S PRIMA dell'esito: il drain si fa comunque e l'esito ritrasmesso riceve il suo ACK. */
    @Test
    fun `sentinella prima dell'esito, il drain raccoglie l'esito ritrasmesso`() = runTest {
        val h = harness(options = Frames.fast.copy(ecrPrinting = true))
        h.transport.onApplicationFrame(
            bytes(0, Frames.ACKW, app(Frames.RECEIPT_END), app(Frames.E22)),
            bytes(10, app(Frames.E22)),
        )

        val r = success(h.run())

        assertEquals(Frames.E22, r.text())
        h.assertWarning(WarningCode.RESULT_RETRANSMITTED)
        assertEquals(1, h.trace.resultRetransmissions)
        h.assertNoWarning(WarningCode.RECEIPT_END_MISSING)
        assertEquals(
            listOf("06 03 7A", "06 03 7A", "06 03 7A"),
            h.writesHex.drop(1),
            "ACK alla riga S, all'esito e all'esito ritrasmesso",
        )
    }

    private companion object {
        val ENDPOINT = Endpoint("pos.test", 5577)
        const val STX: Byte = 0x02

        /** Risposta 's' di A§9.5 (data 01/02/25 15:30, stato 2, release V1.2.3). */
        const val STATUS_RESPONSE = "123456780s000000000001022515302V1.2.3"

        fun defaultPayload(command: CommandCode): ByteArray = RequestEncoder.encode(
            when (command) {
                CommandCode.STATUS -> StatusRequest
                CommandCode.PAYMENT -> PaymentRequest(amountCents = 1)
                CommandCode.TOTALS -> TotalsRequest()
                CommandCode.LAST_RESULT -> LastResultRequest()
                CommandCode.CARD_VERIFICATION -> CardVerificationRequest()
                CommandCode.ECR_PRINTING -> EcrPrintingRequest(enabled = true)
                CommandCode.REPRINT -> ReprintRequest(target = ReprintTarget.ECR)
                else -> error("comando non usato nei test di sessione: $command")
            },
            TerminalIds(),
        )
    }
}
