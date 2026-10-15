package it.prime.protocollo17.ecr17.client

import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.event.ExchangeListener
import it.prime.protocollo17.ecr17.event.FrameDirection
import it.prime.protocollo17.ecr17.event.FrameKind
import it.prime.protocollo17.ecr17.event.ProtocolEvent
import it.prime.protocollo17.ecr17.event.ProtocolWarning
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.model.AckMode
import it.prime.protocollo17.ecr17.model.CardVerificationRequest
import it.prime.protocollo17.ecr17.model.CloseCause
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.LinkState
import it.prime.protocollo17.ecr17.model.OutcomeClass
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.PhaseChange
import it.prime.protocollo17.ecr17.model.PreAuthResponse
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.ecr17.model.TerminalIds
import it.prime.protocollo17.ecr17.model.TotalsRequest
import it.prime.protocollo17.ecr17.profile.ConnectionMode
import it.prime.protocollo17.ecr17.profile.Ecr17Config
import it.prime.protocollo17.ecr17.profile.FinancialAckPolicy
import it.prime.protocollo17.ecr17.profile.ProtocolOptions
import it.prime.protocollo17.ecr17.profile.TerminalProfileId
import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.ecr17.testing.ScriptedTransport
import it.prime.protocollo17.ecr17.transport.TransportErrorKind
import it.prime.protocollo17.ecr17.wire.PanMasker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Client (§3.23, §7.4: CL-01…CL-28) in tempo virtuale con il trasporto scriptato.
 *
 * Protegge la tabella di mappatura money-safety (vincolante): NotSent solo senza alcun tentativo di scrittura del
 * frame principale (M10), Refused solo se OGNI invio ha avuto un NAK, Unknown bloccante per i journaled senza esito;
 * un solo comando alla volta (M7); replay solo per s/T/G/E dopo una caduta (M1); chiusura del link persistente dopo
 * un esito mancante o sospetto (R2, R5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Ecr17ClientTest {

    // ─── Harness ─────────────────────────────────────────────────────────────

    private class Harness(scope: TestScope, options: ProtocolOptions, ids: TerminalIds, endpoint: Endpoint) {
        var config = Ecr17Config(TerminalProfileId.INGENICO, endpoint, ids, options)
        var provider: () -> Ecr17Config = { config }
        val transport = ScriptedTransport(scope.backgroundScope, wallClock = { scope.testScheduler.currentTime })
        val sink = mutableListOf<ProtocolEvent>()
        val client = Ecr17Client(
            configProvider = { provider() },
            transport = transport,
            eventSink = { sink += it },
            timeSource = scope.testScheduler.timeSource,
            wallClock = { scope.testScheduler.currentTime },
        )

        val links: List<LinkState> get() = sink.filterIsInstance<ProtocolEvent.Link>().map { it.state }
        val finished: List<ProtocolEvent.CommandFinished> get() = sink.filterIsInstance<ProtocolEvent.CommandFinished>()
    }

    private fun TestScope.harness(
        options: ProtocolOptions = Frames.fast,
        ids: TerminalIds = TerminalIds(),
        endpoint: Endpoint = ENDPOINT,
    ) = Harness(this, options, ids, endpoint)

    private val persistent = Frames.fast.copy(connectionMode = ConnectionMode.PERSISTENT)
    private val retransmit = Frames.fast.copy(financialAckPolicy = FinancialAckPolicy.RETRANSMIT)

    private fun bytes(delayMs: Long, vararg parts: ByteArray): ScriptedTransport.Step =
        ScriptedTransport.Step.Bytes(delayMs, parts.fold(ByteArray(0)) { acc, part -> acc + part })

    private fun closeAt(delayMs: Long): ScriptedTransport.Step = ScriptedTransport.Step.Close(delayMs)

    private fun app(payload: String) = Frames.app(payload)

    /** Codice in pos 10 di un frame STX…: indice 10 (STX + 9 byte). */
    private fun codeOf(frame: ByteArray): Char = (frame[10].toInt() and 0xFF).toChar()

    private fun ScriptedTransport.scriptStatusOk() = onApplicationFrame(bytes(0, Frames.ACKW, app(STATUS_RESPONSE)))

    private inline fun <reified T : CommandOutcome> outcome(value: CommandOutcome): T =
        assertInstanceOf(T::class.java, value, "esito inatteso: $value")

    private class PhaseListener(
        private val failOn: (PhaseChange) -> Throwable? = { null },
        private val hook: (PhaseChange) -> Unit = {},
    ) : ExchangeListener {
        override suspend fun onPhase(change: PhaseChange) {
            hook(change)
            failOn(change)?.let { throw it }
        }
    }

    // ─── Concorrenza, validazione, configurazione ────────────────────────────

    /** M7: un secondo comando durante il primo è rifiutato subito, mai accodato. */
    @Test
    fun `CL-01 secondo comando concorrente rifiutato`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW), bytes(150, app(Frames.E22)))

        val first = async { h.client.pay(PaymentRequest(amountCents = 100)) }
        runCurrent()
        assertEquals(CommandCode.PAYMENT, h.client.busy.value, "il primo è in corso")

        val second = h.client.status()

        assertEquals(CommandOutcome.NotSent(CommandCode.STATUS, Ecr17Failure.Busy, null), second)
        outcome<CommandOutcome.Answered>(first.await())
        assertNull(h.client.busy.value)
        assertEquals(1, h.transport.applicationWrites.size, "il secondo non ha scritto nulla")
    }

    /** A§8.2: la validazione avviene prima di connettersi. */
    @Test
    fun `CL-02 validazione prima della connessione`() = runTest {
        val h = harness(ids = TerminalIds(terminalId = "12AB"))

        val result = outcome<CommandOutcome.NotSent>(h.client.status())

        assertInstanceOf(Ecr17Failure.Validation::class.java, result.failure)
        assertEquals(0, h.transport.connectCount)
    }

    /** §3.16: porta mancante ⇒ configurazione non valida, nessuna connessione. */
    @Test
    fun `CL-03 porta mancante, configurazione non valida`() = runTest {
        val h = harness(endpoint = Endpoint("pos.test", 0))

        val result = outcome<CommandOutcome.NotSent>(h.client.status())

        assertInstanceOf(Ecr17Failure.ConfigInvalid::class.java, result.failure)
        assertEquals(0, h.transport.connectCount)
    }

    /** U5: in PER_COMMAND ogni comando apre e chiude la sua connessione. */
    @Test
    fun `CL-04 per comando, connect, scambio, close`() = runTest {
        val h = harness()
        h.transport.scriptStatusOk()

        outcome<CommandOutcome.Answered>(h.client.status())

        assertEquals(1, h.transport.connectCount)
        assertEquals(1, h.transport.closeCount)
        val links = h.links
        assertEquals(3, links.size, "eventi link: $links")
        assertInstanceOf(LinkState.Connecting::class.java, links[0])
        assertInstanceOf(LinkState.Connected::class.java, links[1])
        assertEquals(CloseCause.LOCAL, assertInstanceOf(LinkState.Closed::class.java, links[2]).cause)
    }

    /** U5: in PERSISTENT due comandi puliti usano la stessa connessione. */
    @Test
    fun `CL-05 persistente, una connessione per due comandi`() = runTest {
        val h = harness(options = persistent)
        h.transport.scriptStatusOk()
        h.transport.scriptStatusOk()

        outcome<CommandOutcome.Answered>(h.client.status())
        outcome<CommandOutcome.Answered>(h.client.status())

        assertEquals(1, h.transport.connectCount)
        assertEquals(0, h.transport.closeCount)
    }

    /** L§13.2: il peer chiude tra due comandi; il secondo riconnette PRIMA di inviare e scrive un solo frame. */
    @Test
    fun `CL-06 persistente, peer chiuso tra due comandi, riconnessione prima dell'invio`() = runTest {
        val h = harness(options = persistent)
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(STATUS_RESPONSE)), closeAt(100))
        h.transport.scriptStatusOk()

        outcome<CommandOutcome.Answered>(h.client.status())
        advanceTimeBy(200)
        assertEquals(CloseCause.PEER_CLOSED, (h.client.linkState.value as LinkState.Closed).cause)
        val mark = h.sink.size

        outcome<CommandOutcome.Answered>(h.client.status())

        val second = h.sink.drop(mark)
        val connecting = second.indexOfFirst { it is ProtocolEvent.Link && it.state is LinkState.Connecting }
        val connected = second.indexOfFirst { it is ProtocolEvent.Link && it.state is LinkState.Connected }
        val tx = second.indexOfFirst { it is ProtocolEvent.Wire && it.direction == FrameDirection.TX && it.kind == FrameKind.APPLICATION }
        assertTrue(connecting in 0 until connected && connected < tx, "Connecting < Connected < invio: $second")
        assertEquals(1, second.count { it is ProtocolEvent.Wire && it.direction == FrameDirection.TX && it.kind == FrameKind.APPLICATION })
        assertEquals(2, h.transport.connectCount)
    }

    // ─── Cadute e replay (M1) ────────────────────────────────────────────────

    /** M1, A§9.7, L§13.2: un pagamento NON viene mai ripetuto dopo una caduta: esito sconosciuto. */
    @Test
    fun `CL-07 MONEY-SAFETY, pagamento non ripetuto dopo la caduta`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(closeAt(0))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)))   // non deve mai essere usato

        val result = outcome<CommandOutcome.Unknown>(h.client.pay(PaymentRequest(amountCents = 100)))

        assertEquals(CloseCause.PEER_CLOSED, (result.failure as Ecr17Failure.Disconnected).cause)
        assertTrue(result.blocksPayments)
        assertEquals(1, h.transport.applicationWrites.size, "MONEY-SAFETY: un solo frame P")
    }

    /** A§9.7, L§13.2: s e T sono ripetuti UNA volta dopo una caduta. */
    @Test
    fun `CL-08 stato e totali ripetuti una volta dopo la caduta`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(closeAt(0))
        h.transport.scriptStatusOk()

        val status = outcome<CommandOutcome.Answered>(h.client.status())

        assertEquals(2, status.trace.sends)
        assertTrue(status.trace.replayedAfterReconnect)
        assertEquals(2, h.transport.connectCount)

        h.transport.onApplicationFrame(closeAt(0))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.T34)))

        val totals = outcome<CommandOutcome.Answered>(h.client.totals(TotalsRequest()))

        assertEquals(2, totals.trace.sends)
        assertTrue(totals.trace.replayedAfterReconnect)
    }

    /** L§13.2: la verifica carta (journaled, RECORD) non è ripetuta; esito sconosciuto che non blocca i pagamenti. */
    @Test
    fun `CL-09 verifica carta non ripetuta dopo la caduta`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(closeAt(0))

        val result = outcome<CommandOutcome.Unknown>(h.client.verifyCard(CardVerificationRequest()))

        assertFalse(result.blocksPayments)
        assertEquals(1, h.transport.applicationWrites.size)
    }

    // ─── Stampa su ECR (E1) ──────────────────────────────────────────────────

    /** A§4.3.6: con la stampa su ECR attiva E1 parte prima del pagamento. */
    @Test
    fun `CL-10 E1 inviato prima del pagamento con stampa su ECR`() = runTest {
        val h = harness(options = Frames.fast.copy(ecrPrinting = true))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22), app(Frames.RECEIPT_END)))

        val result = outcome<CommandOutcome.Answered>(h.client.pay(PaymentRequest(amountCents = 100)))

        val frames = h.transport.applicationWrites
        assertEquals(listOf('E', 'P'), frames.map(::codeOf))
        assertEquals("000000000E1", String(frames[0], 1, 11, Charsets.ISO_8859_1))
        assertTrue(result.trace.printingSetupSent)
        assertEquals(1, result.trace.sends, "gli invii di E1 non contano come invii del pagamento")
    }

    /** §3.23: E1 rifiutato ⇒ il pagamento non parte ed è NotSent (nessuna scrittura del frame principale). */
    @Test
    fun `CL-11 E1 rifiutato, il pagamento non parte`() = runTest {
        val h = harness(options = Frames.fast.copy(ecrPrinting = true))
        repeat(4) { h.transport.onApplicationFrame(bytes(0, Frames.NAKW)) }

        val result = outcome<CommandOutcome.NotSent>(h.client.pay(PaymentRequest(amountCents = 100)))

        assertEquals(Ecr17Failure.PrintingSetupFailed(Ecr17Failure.Nak(4)), result.failure)
        assertTrue(h.transport.applicationWrites.none { codeOf(it) == 'P' }, "nessun frame P")
        assertFalse(result.trace!!.anyMainFrameWriteAttempted)
    }

    /**
     * M4, §5.5, §3.23 p.10: con la stampa su ECR la raccolta degli orfani prima dell'invio la fa E1. L'esito orfano
     * arriva comunque al listener del comando (prova EVIDENCE), con il PAN mascherato, finisce nella trace del
     * pagamento e chiude il link persistente; le fasi di E1 restano fuori dal listener (journal).
     */
    @Test
    fun `E1 trova un esito orfano, POSSIBLE_LOST_RESULT al listener e link chiuso`() = runTest {
        val h = harness(options = persistent.copy(ecrPrinting = true))
        h.transport.onConnect(bytes(0, app(Frames.E22_CLEAR)))
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW))   // E1
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22), app(Frames.RECEIPT_END)))
        val warnings = mutableListOf<ProtocolWarning>()
        val phases = mutableListOf<PhaseChange>()
        val listener = object : ExchangeListener {
            override suspend fun onPhase(change: PhaseChange) {
                phases += change
            }

            override fun onWarning(warning: ProtocolWarning) {
                warnings += warning
            }
        }

        val result = outcome<CommandOutcome.Answered>(h.client.pay(PaymentRequest(amountCents = 100), listener))

        val lost = warnings.single { it.code == WarningCode.POSSIBLE_LOST_RESULT }
        val masked = String(
            PanMasker.maskApplicationPayload(Frames.E22_CLEAR.toByteArray(Charsets.ISO_8859_1)),
            Charsets.ISO_8859_1,
        )
        assertEquals(masked, lost.payload)
        assertFalse(lost.payload.orEmpty().contains("4111111111111111"), "PAN in chiaro nella prova")
        val codes = result.trace.warnings.map { it.code }
        assertTrue(WarningCode.STALE_BYTES in codes, "STALE_BYTES di E1 nella trace del pagamento: $codes")
        assertEquals(1, codes.count { it == WarningCode.POSSIBLE_LOST_RESULT }, "una sola volta nella trace: $codes")
        assertEquals(1, phases.count { it.phase == ExchangePhase.SENDING }, "solo il SENDING del pagamento")
        assertEquals(1, h.transport.closeCount, "link persistente chiuso dopo un warning sospetto di E1")
    }

    // ─── Esiti e opzioni ─────────────────────────────────────────────────────

    /** L§13.6: la risposta 'e' a G è smistata per codice ⇒ PreAuthResponse. */
    @Test
    fun `CL-12 G smistato per codice`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(E106)))

        val result = outcome<CommandOutcome.Answered>(h.client.sendLastResult())

        assertInstanceOf(PreAuthResponse::class.java, result.response)
    }

    /** §3.16: le opzioni sono lette una sola volta: un cambio a metà comando non ha effetto. */
    @Test
    fun `CL-13 opzioni lette una sola volta per comando`() = runTest {
        val h = harness()
        h.provider = {
            val current = h.config
            h.config = current.copy(options = current.options.copy(ackTimeoutMs = 1_000))
            current
        }
        h.transport.onApplicationFrame(bytes(100, app(Frames.E22)))

        val result = outcome<CommandOutcome.Answered>(h.client.pay(PaymentRequest(amountCents = 100)))

        assertEquals(
            AckMode.IMPLICIT_GO_AHEAD,
            result.trace.ackMode,
            "go-ahead a 40 ms: con 1000 ms l'esito a 100 ms sarebbe arrivato prima dell'ACK",
        )
        assertEquals(1_000, h.config.options.ackTimeoutMs, "il provider è stato cambiato durante il comando")
    }

    /** §3.23: connessione rifiutata o scaduta ⇒ NotSent con la causa giusta. */
    @Test
    fun `CL-14 connessione rifiutata o scaduta`() = runTest {
        val h = harness()
        h.transport.failNextConnect(TransportErrorKind.CONNECT_FAILED)
        val refused = outcome<CommandOutcome.NotSent>(h.client.status())
        assertInstanceOf(Ecr17Failure.ConnectFailed::class.java, refused.failure)

        h.transport.failNextConnect(TransportErrorKind.CONNECT_TIMEOUT)
        val timeout = outcome<CommandOutcome.NotSent>(h.client.status())
        assertEquals(Ecr17Failure.ConnectTimeout(ENDPOINT, Frames.fast.connectTimeoutMs), timeout.failure)
    }

    // ─── Annullamento ────────────────────────────────────────────────────────

    /** §3.23: Annulla durante la connessione ⇒ NotSent(Cancelled(byUser)). */
    @Test
    fun `CL-15 annullamento durante la connessione`() = runTest {
        val h = harness()
        h.transport.connectDelayMs = 1_000

        val pending = async { h.client.pay(PaymentRequest(amountCents = 100)) }
        advanceTimeBy(10)
        assertTrue(h.client.cancelCurrent(), "c'era un comando da annullare")

        val result = outcome<CommandOutcome.NotSent>(pending.await())
        assertEquals(Ecr17Failure.Cancelled(byUser = true), result.failure)
        assertTrue(h.transport.applicationWrites.isEmpty())
    }

    /** §3.23 p.1, p.5: un Annulla arrivato tra il lock e la connessione resta valido. */
    @Test
    fun `CL-15b annullamento tra il lock e la connessione`() = runTest {
        val h = harness()
        h.provider = {
            h.client.cancelCurrent()
            h.config
        }

        val result = outcome<CommandOutcome.NotSent>(h.client.pay(PaymentRequest(amountCents = 100)))

        assertEquals(Ecr17Failure.Cancelled(byUser = true), result.failure)
        assertEquals(0, h.transport.connectCount)
    }

    /** §2.12: la cancellazione della coroutine chiude il socket, emette CommandFinished e si propaga. */
    @Test
    fun `CL-16 cancellazione della coroutine, abort e rilancio`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW))
        var thrown: Throwable? = null

        val job = launch {
            try {
                h.client.pay(PaymentRequest(amountCents = 100))
            } catch (e: CancellationException) {
                thrown = e
                throw e
            }
        }
        advanceTimeBy(50)
        job.cancel()
        job.join()

        assertInstanceOf(CancellationException::class.java, thrown)
        assertTrue(h.transport.abortCount >= 1, "abort chiamato")
        assertEquals(OutcomeClass.UNKNOWN, h.finished.single().outcome, "P scritto e senza esito")
        assertNull(h.client.busy.value)
        h.transport.scriptStatusOk()
        outcome<CommandOutcome.Answered>(h.client.status())   // il lock è stato rilasciato
    }

    /** §3.23: un comando non journaled senza risposta è Failed, non Unknown. */
    @Test
    fun `CL-17 comando non journaled senza risposta, Failed`() = runTest {
        val h = harness()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW))

        val result = outcome<CommandOutcome.Failed>(h.client.status())

        assertEquals(Ecr17Failure.ResponseTimeout(Frames.fast.responseTimeoutMs), result.failure)
    }

    /** §3.19: seq monotono, CommandStarted…CommandFinished, ogni evento al sink prima dello SharedFlow. */
    @Test
    fun `CL-18 eventi, seq monotono e sink sincrono`() = runTest {
        val h = harness()
        h.transport.scriptStatusOk()
        val fromFlow = mutableListOf<ProtocolEvent>()
        val notYetInSink = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.client.events.collect { event ->
                if (h.sink.none { it.seq == event.seq }) notYetInSink += event.seq
                fromFlow += event
            }
        }

        outcome<CommandOutcome.Answered>(h.client.status())

        val seqs = h.sink.map { it.seq }
        assertEquals((1L..seqs.size).toList(), seqs, "seq monotono senza buchi")
        assertInstanceOf(ProtocolEvent.CommandStarted::class.java, h.sink.first())
        assertInstanceOf(ProtocolEvent.CommandFinished::class.java, h.sink.last())
        assertEquals(seqs, fromFlow.map { it.seq }, "lo SharedFlow riceve gli stessi eventi")
        assertTrue(notYetInSink.isEmpty(), "eventi arrivati allo SharedFlow prima del sink: $notYetInSink")
    }

    // ─── M10: mai NotSent dopo una scrittura ─────────────────────────────────

    /** M10: un bug dopo un esito APPROVATO (ACK finale che lancia) non diventa mai NotSent. */
    @Test
    fun `CL-19 bug dopo un esito APPROVATO, mai NotSent`() = runTest {
        val pay = harness()
        pay.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)))
        pay.transport.throwOnWrite(1, IllegalStateException("bug"))

        val payment = outcome<CommandOutcome.Unknown>(pay.client.pay(PaymentRequest(amountCents = 100)))
        assertInstanceOf(Ecr17Failure.Internal::class.java, payment.failure)
        assertTrue(payment.blocksPayments)

        val status = harness()
        status.transport.scriptStatusOk()
        status.transport.throwOnWrite(1, IllegalStateException("bug"))

        val failed = outcome<CommandOutcome.Failed>(status.client.status())
        assertInstanceOf(Ecr17Failure.Internal::class.java, failed.failure)
    }

    /**
     * M10: il flag di scrittura è impostato PRIMA della send. Una scrittura del pagamento fallita a metà può aver
     * consegnato byte al terminale: l'esito è Unknown (bloccante), mai NotSent.
     */
    @Test
    fun `scrittura del frame principale fallita, Unknown e mai NotSent`() = runTest {
        val h = harness()
        h.transport.failWritesAfter(0)

        val result = outcome<CommandOutcome.Unknown>(h.client.pay(PaymentRequest(amountCents = 100)))

        assertInstanceOf(Ecr17Failure.WriteFailed::class.java, result.failure)
        assertTrue(result.trace!!.anyMainFrameWriteAttempted)
        assertEquals(0, result.trace!!.sends, "nessun invio riuscito")
    }

    /** M10: cancellazione dopo RESULT_RECEIVED ⇒ CommandFinished UNKNOWN per P, FAILED per s. */
    @Test
    fun `CL-20 cancellazione dopo RESULT_RECEIVED`() = runTest {
        suspend fun cancelInDrain(command: CommandCode, response: String): OutcomeClass {
            val h = harness()
            h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(response)))
            val draining = CompletableDeferred<Unit>()
            val listener = PhaseListener(hook = { if (it.phase == ExchangePhase.DRAINING) draining.complete(Unit) })
            var thrown: Throwable? = null
            val request = if (command == CommandCode.PAYMENT) PaymentRequest(amountCents = 100) else StatusRequest
            val job = launch {
                try {
                    h.client.execute(request, listener)
                } catch (e: CancellationException) {
                    thrown = e
                    throw e
                }
            }
            draining.await()
            job.cancel()
            job.join()
            assertInstanceOf(CancellationException::class.java, thrown, "CancellationException per $command")
            return h.finished.single().outcome
        }

        assertEquals(OutcomeClass.UNKNOWN, cancelInDrain(CommandCode.PAYMENT, Frames.E22))
        assertEquals(OutcomeClass.FAILED, cancelInDrain(CommandCode.STATUS, STATUS_RESPONSE))
    }

    /** §3.20: timeout seguito da NAK fino al limite ⇒ invii misti ⇒ Unknown, non Refused. */
    @Test
    fun `CL-21 RETRANSMIT, timeout seguito da NAK fino al limite, Unknown non Refused`() = runTest {
        val h = harness(options = retransmit)
        h.transport.onApplicationFrame()
        repeat(4) { h.transport.onApplicationFrame(bytes(0, Frames.NAKW)) }

        val result = outcome<CommandOutcome.Unknown>(h.client.pay(PaymentRequest(amountCents = 100)))

        assertInstanceOf(Ecr17Failure.Nak::class.java, result.failure)
        assertTrue(result.blocksPayments)
    }

    /** L§6.5 p.8: esito dopo un reinvio per ACK mancante ⇒ Answered "da verificare". */
    @Test
    fun `CL-22 RETRANSMIT, timeout seguito da ACK ed esito, esito da verificare`() = runTest {
        val h = harness(options = retransmit)
        h.transport.onApplicationFrame()
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)))

        val result = outcome<CommandOutcome.Answered>(h.client.pay(PaymentRequest(amountCents = 100)))

        assertTrue(result.needsReview)
        assertEquals(listOf(WarningCode.POSSIBLE_DUPLICATE), result.reviewWarnings.map { it.code })
    }

    // ─── PERSISTENT: chiusura del link (§3.23 p.10) ──────────────────────────

    /** §3.23 p.10: dopo un Unknown il link persistente viene chiuso; il comando successivo riconnette. */
    @Test
    fun `CL-23 PERSISTENT, dopo un Unknown il collegamento viene chiuso`() = runTest {
        val h = harness(options = persistent)
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW))

        outcome<CommandOutcome.Unknown>(h.client.verifyCard(CardVerificationRequest()))
        assertEquals(1, h.transport.closeCount)

        h.transport.scriptStatusOk()
        outcome<CommandOutcome.Answered>(h.client.status())
        assertEquals(2, h.transport.connectCount, "nuova connessione dopo l'Unknown")
    }

    /** §3.23 p.10: un warning sospetto chiude il link anche con un esito valido. */
    @Test
    fun `CL-24 PERSISTENT, warning sospetto, collegamento chiuso`() = runTest {
        val h = harness(options = persistent)
        h.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.U34), app(STATUS_RESPONSE)))

        val result = outcome<CommandOutcome.Answered>(h.client.status())

        assertTrue(result.trace.warnings.any { it.code == WarningCode.UNEXPECTED_REPLY })
        assertEquals(1, h.transport.closeCount)
    }

    /** §3.23 p.10: uno 's' pulito lascia il link aperto e riusato. */
    @Test
    fun `CL-24 PERSISTENT, s pulito, link riusato`() = runTest {
        val h = harness(options = persistent)
        h.transport.scriptStatusOk()
        h.transport.scriptStatusOk()

        outcome<CommandOutcome.Answered>(h.client.status())
        outcome<CommandOutcome.Answered>(h.client.status())

        assertEquals(0, h.transport.closeCount)
        assertEquals(1, h.transport.connectCount)
    }

    /** §3.21 p.3: su un link riusato un esito prima dell'ACK è "da verificare" e il link si chiude. */
    @Test
    fun `CL-25 PERSISTENT, esito tardivo dopo l'invio successivo`() = runTest {
        val h = harness(options = persistent)
        h.transport.scriptStatusOk()
        h.transport.onApplicationFrame(bytes(1, app(Frames.E22)))

        outcome<CommandOutcome.Answered>(h.client.status())
        val payment = outcome<CommandOutcome.Answered>(h.client.pay(PaymentRequest(amountCents = 100)))

        assertTrue(payment.needsReview)
        val early = payment.reviewWarnings.single()
        assertEquals(WarningCode.EARLY_RESULT, early.code)
        assertTrue(early.detail.contains("riusata"), "il dettaglio cita la connessione riusata: ${early.detail}")
        assertEquals(1, h.transport.connectCount, "il pagamento ha riusato il link")
        assertEquals(1, h.transport.closeCount, "link chiuso a fine comando")
    }

    /** §3.16: validate() solo strutturale: le config rapide dei test passano, i valori assurdi no. */
    @Test
    fun `CL-26 controlli strutturali della configurazione`() = runTest {
        assertTrue(Frames.fast.validate().isEmpty(), "Frames.fast deve essere valida: ${Frames.fast.validate()}")

        val zeroAck = harness(options = Frames.fast.copy(ackTimeoutMs = 0))
        val r1 = outcome<CommandOutcome.NotSent>(zeroAck.client.status())
        assertInstanceOf(Ecr17Failure.ConfigInvalid::class.java, r1.failure)

        val tooManyNaks = harness(options = Frames.fast.copy(nakRetryCount = 9))
        val r2 = outcome<CommandOutcome.NotSent>(tooManyNaks.client.status())
        assertInstanceOf(Ecr17Failure.ConfigInvalid::class.java, r2.failure)
        assertEquals(0, zeroAck.transport.connectCount + tooManyNaks.transport.connectCount)
    }

    /** M3, M10: il listener che lancia produce NotSent solo prima del primo invio. */
    @Test
    fun `CL-27 listener che lancia, NotSent solo prima del primo invio`() = runTest {
        val first = harness()
        val failFirst = PhaseListener(failOn = {
            if (it.phase == ExchangePhase.SENDING && it.sends == 1) IOException("disco pieno") else null
        })
        val notSent = outcome<CommandOutcome.NotSent>(first.client.pay(PaymentRequest(amountCents = 100), failFirst))
        assertInstanceOf(Ecr17Failure.Internal::class.java, notSent.failure)
        assertTrue(first.transport.applicationWrites.isEmpty())

        val second = harness()
        second.transport.onApplicationFrame(bytes(0, Frames.NAKW))
        second.transport.onApplicationFrame(bytes(0, Frames.ACKW, app(Frames.E22)))
        val failSecond = PhaseListener(failOn = {
            if (it.phase == ExchangePhase.SENDING && it.sends == 2) IOException("disco pieno") else null
        })
        val answered = outcome<CommandOutcome.Answered>(second.client.pay(PaymentRequest(amountCents = 100), failSecond))
        assertTrue(answered.trace.warnings.any { it.code == WarningCode.JOURNAL_WRITE_FAILED })
    }

    /** §3.20: Refused solo se ogni invio ha avuto un NAK valido. */
    @Test
    fun `CL-28 Refused solo se ogni invio ha avuto un NAK`() = runTest {
        val h = harness()
        repeat(4) { h.transport.onApplicationFrame(bytes(0, Frames.NAKW)) }

        val result = outcome<CommandOutcome.Refused>(h.client.pay(PaymentRequest(amountCents = 100)))

        assertEquals(Ecr17Failure.Nak(4), result.failure)
    }

    // ─── Operazioni di collegamento ──────────────────────────────────────────

    /** cancelCurrent() senza comandi in corso non fa nulla. */
    @Test
    fun `cancelCurrent senza comando restituisce false`() = runTest {
        val h = harness()

        assertFalse(h.client.cancelCurrent())
        assertEquals(0, h.transport.abortCount)
    }

    /** Test connessione in PER_COMMAND: apre, misura e richiude senza inviare frame. */
    @Test
    fun `testConnection apre e richiude in PER_COMMAND`() = runTest {
        val h = harness()

        val result = h.client.testConnection()

        assertTrue(result.ok, "esito: $result")
        assertEquals(ENDPOINT, result.endpoint)
        assertEquals(1, h.transport.connectCount)
        assertEquals(1, h.transport.closeCount)
        assertTrue(h.transport.writes.isEmpty())
    }

    /** connect() vale solo in PERSISTENT; disconnect() chiude il link con causa LOCAL. */
    @Test
    fun `connect e disconnect del link persistente`() = runTest {
        val perCommand = harness()
        assertInstanceOf(Ecr17Failure.ConfigInvalid::class.java, perCommand.client.connect())

        val h = harness(options = persistent)
        assertNull(h.client.connect())
        assertTrue(h.transport.isOpen)
        assertNull(h.client.connect(), "già aperto verso lo stesso endpoint: nessuna nuova connessione")
        assertEquals(1, h.transport.connectCount)

        assertNull(h.client.disconnect())
        assertEquals(CloseCause.LOCAL, (h.client.linkState.value as LinkState.Closed).cause)
    }

    private companion object {
        val ENDPOINT = Endpoint("pos.test", 5577)

        /** Risposta 's' di A§9.5. */
        const val STATUS_RESPONSE = "123456780s000000000001022515302V1.2.3"

        /** 'e' 106 B di L§13.6 (preautorizzazione approvata), qui come risposta a G. */
        const val E106 = "123456780e000000000004111111111CLIAUTH01000500000000001230002111520" +
            "000" + "0" + "ACQ        " + "000042" + "000099" + "000000000000"
    }
}
