package it.prime.protocollo17.ecr17.sim

import it.prime.protocollo17.ecr17.client.Ecr17Client
import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.model.AckMode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.ExchangeTrace
import it.prime.protocollo17.ecr17.model.LinkState
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.PaymentResponse
import it.prime.protocollo17.ecr17.model.ReprintRequest
import it.prime.protocollo17.ecr17.model.ReprintTarget
import it.prime.protocollo17.ecr17.model.StatusResponse
import it.prime.protocollo17.ecr17.model.TerminalIds
import it.prime.protocollo17.ecr17.model.Verdict
import it.prime.protocollo17.ecr17.profile.ConnectionMode
import it.prime.protocollo17.ecr17.profile.Ecr17Config
import it.prime.protocollo17.ecr17.profile.FinancialAckPolicy
import it.prime.protocollo17.ecr17.profile.ProtocolOptions
import it.prime.protocollo17.ecr17.profile.TerminalProfileId
import it.prime.protocollo17.ecr17.transport.TcpTransport
import it.prime.protocollo17.ecr17.wire.ControlLrcRule
import it.prime.protocollo17.ecr17.wire.LrcMode
import it.prime.protocollo17.ecr17.wire.PanMasker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.Collections

/**
 * Integrazione reale (§7.6 IT-01…IT-23): [TerminalSimulator] su 127.0.0.1 porta 0 contro [Ecr17Client] con
 * [TcpTransport], socket e timeout veri. Simulatore veloce (ACK 5 ms, progress 20 ms, esito 150 ms), ECR col profilo
 * Ingenico e timeout brevi (ACK 300 ms, esito 2 000 ms, drain 50 ms) salvo dove il caso chiede altro.
 *
 * È la prova che la money-safety regge su un filo vero: un pagamento senza ACK non viene ritrasmesso (M2), un
 * esito tardivo o stantio non diventa mai l'esito di un altro comando (M4), un NAK ambiguo non fa reinviare, e il
 * doppio invio, quando lo si sceglie, è visibile nei contatori del simulatore. Il simulatore manda il PAN in chiaro:
 * l'ECR e il log del simulatore devono mascherarlo (M9).
 */
class SimulatorIntegrationTest {

    private val closeables = mutableListOf<AutoCloseable>()
    private val simEvents: MutableList<SimEvent> = Collections.synchronizedList(mutableListOf())
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
        collectors.cancel()
    }

    /** Opzioni Ingenico di §7.6: valide per i controlli strutturali (§3.16), fuori dai range dell'operatore. */
    private val ingenico = ProtocolOptions(ackTimeoutMs = 300, responseTimeoutMs = 2_000, postResultDrainMs = 50)

    private fun fastSim(
        fault: SimFault = SimFault.NONE,
        transform: (SimulatorConfig) -> SimulatorConfig = { it },
    ): SimulatorConfig = transform(SimulatorConfig(port = 0, ackDelayMs = 5, progressDelayMs = 20, resultDelayMs = 150, fault = fault))

    /** Simulatore avviato su una porta effimera; il suo log è raccolto per i controlli di mascheramento. */
    private suspend fun simulator(config: SimulatorConfig = fastSim()): Pair<TerminalSimulator, Int> {
        val sim = TerminalSimulator(config)
        closeables += sim
        collectors.launch(start = CoroutineStart.UNDISPATCHED) { sim.events.collect { simEvents += it } }
        return sim to sim.start()
    }

    private fun client(port: Int, options: ProtocolOptions = ingenico): Ecr17Client {
        val config = Ecr17Config(
            profile = TerminalProfileId.INGENICO,
            endpoint = Endpoint("127.0.0.1", port),
            ids = TerminalIds(),
            options = options,
        )
        return Ecr17Client(configProvider = { config }, transport = TcpTransport()).also { closeables += it }
    }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) { block() }
    }

    /** Il simulatore registra gli ACK dell'ECR in modo asincrono: si attende la condizione, mai un delay "di sicurezza". */
    private suspend fun awaitSim(sim: TerminalSimulator, what: String, predicate: (SimState) -> Boolean): SimState =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(AWAIT_MS) {
                var state = sim.snapshot()
                while (!predicate(state)) {
                    delay(POLL_MS)
                    state = sim.snapshot()
                }
                state
            } ?: fail("$what; stato del simulatore: ${sim.snapshot()}")
        }

    private fun answered(outcome: CommandOutcome): CommandOutcome.Answered =
        assertInstanceOf(CommandOutcome.Answered::class.java, outcome, "atteso Answered: $outcome")

    private fun payment(outcome: CommandOutcome): PaymentResponse =
        assertInstanceOf(PaymentResponse::class.java, answered(outcome).response)

    private val ExchangeTrace.codes: List<WarningCode> get() = warnings.map { it.code }

    private fun SimState.received(code: Char): Int = applicationFramesReceived[code] ?: 0

    private fun assertNoClearPanInSimLog() {
        val frames = synchronized(simEvents) { simEvents.filterIsInstance<SimEvent.FrameOut>() }
        assertTrue(frames.isNotEmpty(), "log del simulatore vuoto")
        val leaked = frames.filter { String(it.bytes, Charsets.ISO_8859_1).contains(CLEAR_PAN) }
        assertTrue(leaked.isEmpty(), "PAN in chiaro nel log del simulatore: ${leaked.map { it.description }}")
    }

    // ─── Percorso normale ───────────────────────────────────────────────────────

    @Test
    fun `IT-01 pagamento approvato con connessione per comando e ACK 7A`() = realTime {
        val (sim, port) = simulator()
        val outcome = client(port).pay(PaymentRequest(amountCents = 10))

        val response = payment(outcome)
        assertEquals(Verdict.APPROVED, response.verdict)
        assertEquals("***************1111", response.pan, "PAN mascherato dall'ECR")
        assertFalse(response.raw.text.contains(CLEAR_PAN), "grezzo mascherato")
        assertEquals(AckMode.EXPLICIT, answered(outcome).trace.ackMode)
        val state = awaitSim(sim, "ACK dell'ECR all'esito") { it.controlFramesReceived.isNotEmpty() }
        assertEquals(1, state.received('P'), "un solo P")
        assertEquals(listOf("06 03 7A"), state.controlFramesReceived, "ACK dell'ECR con regola WIRE")
        assertNoClearPanInSimLog()
    }

    @Test
    fun `IT-02 simulatore con ACK SAME_AS_APP 78 accettato senza warning`() = realTime {
        val (_, port) = simulator(fastSim { it.copy(ackRule = ControlLrcRule.SAME_AS_APP) })
        val outcome = answered(client(port).pay(PaymentRequest(amountCents = 10)))

        assertEquals(AckMode.EXPLICIT, outcome.trace.ackMode)
        assertTrue(outcome.trace.warnings.isEmpty(), "nessun warning: ${outcome.trace.codes}")
    }

    @Test
    fun `IT-03 pagamento rifiutato con descrizione`() = realTime {
        val (_, port) = simulator(fastSim { it.copy(decision = SimDecision.DECLINE) })
        val response = payment(client(port).pay(PaymentRequest(amountCents = 10)))

        assertEquals(Verdict.DECLINED, response.verdict)
        assertEquals("OPERAZIONE ANNULLATA", response.errorDescription)
    }

    // ─── ACK mancante, NAK, cadute ──────────────────────────────────────────────

    /** M2: senza ACK il pagamento parte UNA volta e si attende l'esito (implicit go-ahead), arrivato dopo l'attesa ACK. */
    @Test
    fun `IT-04 NO_ACK con implicit go-ahead invia un solo P`() = realTime {
        val (sim, port) = simulator(fastSim(SimFault.NO_ACK) { it.copy(resultDelayMs = 600) })
        val outcome = answered(client(port).pay(PaymentRequest(amountCents = 10)))

        assertEquals(1, outcome.trace.sends, "un solo invio")
        assertEquals(AckMode.IMPLICIT_GO_AHEAD, outcome.trace.ackMode)
        assertTrue(WarningCode.IMPLICIT_GO_AHEAD in outcome.trace.codes)
        assertEquals(1, awaitSim(sim, "ACK all'esito") { it.controlFramesReceived.isNotEmpty() }.received('P'), "simulatore: 1 P")
    }

    /**
     * Con RETRANSMIT il doppio addebito è VISIBILE: 4 P sul simulatore ed esito "da verificare". ACK 600 ms e
     * retryDelay 0: invii a 0/600/1200/1800 ms, esito a 2 100 ms dentro l'ultima attesa ACK (margine 300 ms per lato).
     */
    @Test
    fun `IT-05 NO_ACK con RETRANSMIT rende visibile il doppio invio`() = realTime {
        val (sim, port) = simulator(fastSim(SimFault.NO_ACK) { it.copy(resultDelayMs = 2_100) })
        val options = ingenico.copy(financialAckPolicy = FinancialAckPolicy.RETRANSMIT, ackTimeoutMs = 600, retryDelayMs = 0)
        val outcome = answered(client(port, options).pay(PaymentRequest(amountCents = 10)))

        assertEquals(4, outcome.trace.sends, "4 invii")
        assertEquals(3, outcome.trace.timeoutRetransmissions)
        assertTrue(WarningCode.POSSIBLE_DUPLICATE in outcome.trace.codes, "warning: ${outcome.trace.codes}")
        assertTrue(outcome.needsReview, "esito da verificare: pagamenti bloccati")
        assertEquals(4, sim.snapshot().received('P'), "simulatore: 4 P ricevuti")
    }

    @Test
    fun `IT-06 NAK_ONCE ritrasmette una volta ed ottiene l'esito`() = realTime {
        val (sim, port) = simulator(fastSim(SimFault.NAK_ONCE))
        val outcome = answered(client(port).pay(PaymentRequest(amountCents = 10)))

        assertEquals(2, outcome.trace.sends)
        assertEquals(1, outcome.trace.naksReceived)
        assertEquals(2, sim.snapshot().received('P'))
    }

    @Test
    fun `IT-07 NAK_ALWAYS porta a Refused dopo 4 invii`() = realTime {
        val (sim, port) = simulator(fastSim(SimFault.NAK_ALWAYS))
        val outcome = client(port).pay(PaymentRequest(amountCents = 10))

        val refused = assertInstanceOf(CommandOutcome.Refused::class.java, outcome, "atteso Refused: $outcome")
        assertEquals(Ecr17Failure.Nak(4), refused.failure)
        assertEquals(4, sim.snapshot().received('P'))
    }

    /** M1: caduta dopo l'invio di un pagamento ⇒ esito sconosciuto, mai un replay. */
    @Test
    fun `IT-08 DROP_AFTER_REQUEST porta a Unknown Disconnected`() = realTime {
        val (sim, port) = simulator(fastSim(SimFault.DROP_AFTER_REQUEST))
        val outcome = client(port).pay(PaymentRequest(amountCents = 10))

        val unknown = assertInstanceOf(CommandOutcome.Unknown::class.java, outcome, "atteso Unknown: $outcome")
        assertInstanceOf(Ecr17Failure.Disconnected::class.java, unknown.failure)
        assertEquals(1, sim.snapshot().received('P'), "nessun replay di un pagamento")
    }

    /** M5: l'ACK finale è best effort, l'esito già ricevuto resta valido anche se il terminale ha chiuso. */
    @Test
    fun `IT-09 DROP_AFTER_RESULT restituisce comunque l'esito`() = realTime {
        val (_, port) = simulator(fastSim(SimFault.DROP_AFTER_RESULT))
        assertEquals(Verdict.APPROVED, payment(client(port).pay(PaymentRequest(amountCents = 10))).verdict)
    }

    @Test
    fun `IT-10 NO_RESULT porta a Unknown ResponseTimeout dopo circa 2 s`() = realTime {
        val (_, port) = simulator(fastSim(SimFault.NO_RESULT))
        val startedAt = System.nanoTime()
        val outcome = client(port).pay(PaymentRequest(amountCents = 10))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        val unknown = assertInstanceOf(CommandOutcome.Unknown::class.java, outcome, "atteso Unknown: $outcome")
        assertEquals(Ecr17Failure.ResponseTimeout(2_000), unknown.failure)
        assertTrue(elapsedMs >= 2_000, "attesa dell'esito: $elapsedMs ms")
    }

    // ─── Frame inattesi, LRC, orfani ────────────────────────────────────────────

    /** M4: un U stantio riceve ACK ma non diventa mai l'esito del pagamento. */
    @Test
    fun `IT-11 UNEXPECTED_FRAME_BEFORE_RESULT segnala UNEXPECTED_REPLY e restituisce l'esito vero`() = realTime {
        val (sim, port) = simulator(fastSim(SimFault.UNEXPECTED_FRAME_BEFORE_RESULT))
        val outcome = answered(client(port).pay(PaymentRequest(amountCents = 10)))

        assertEquals(Verdict.APPROVED, payment(outcome).verdict)
        assertTrue(WarningCode.UNEXPECTED_REPLY in outcome.trace.codes, "warning: ${outcome.trace.codes}")
        val state = awaitSim(sim, "ACK al frame U e all'esito") { it.controlFramesReceived.size >= 2 }
        assertEquals(listOf("06 03 7A", "06 03 7A"), state.controlFramesReceived)
    }

    @Test
    fun `IT-12 BAD_LRC_RESULT_ONCE riceve NAK e poi l'esito corretto`() = realTime {
        val (sim, port) = simulator(fastSim(SimFault.BAD_LRC_RESULT_ONCE))
        val outcome = answered(client(port).pay(PaymentRequest(amountCents = 10)))

        assertEquals(Verdict.APPROVED, payment(outcome).verdict)
        assertTrue(WarningCode.APP_LRC_INVALID in outcome.trace.codes, "warning: ${outcome.trace.codes}")
        val state = awaitSim(sim, "NAK e poi ACK dell'ECR") { it.controlFramesReceived.size >= 2 }
        assertEquals(listOf("15 03 69", "06 03 7A"), state.controlFramesReceived, "NAK al primo esito, ACK al reinvio")
    }

    /**
     * L'esito stantio inviato alla connessione è scartato nel silenzio pre-invio e segnalato come possibile esito perso.
     * preSendQuietMs 250 (non 50) perché sotto carico il frame stantio non arrivi dopo l'invio, dove un `E` sarebbe
     * l'esito atteso del P: è proprio il rischio che il silenzio pre-invio esiste per ridurre.
     */
    @Test
    fun `IT-13 STALE_RESULT_ON_CONNECT segnala POSSIBLE_LOST_RESULT e restituisce l'esito nuovo`() = realTime {
        val (_, port) = simulator(fastSim(SimFault.STALE_RESULT_ON_CONNECT))
        val outcome = answered(client(port, ingenico.copy(preSendQuietMs = 250)).pay(PaymentRequest(amountCents = 10)))

        assertEquals(Verdict.APPROVED, payment(outcome).verdict, "esito nuovo, non quello stantio (KO)")
        assertTrue(WarningCode.STALE_BYTES in outcome.trace.codes, "warning: ${outcome.trace.codes}")
        val lost = outcome.trace.warnings.single { it.code == WarningCode.POSSIBLE_LOST_RESULT }
        assertTrue(lost.payload!!.contains("NESSUNA OPERAZIONE"), "prova allegata: ${lost.payload}")
    }

    @Test
    fun `IT-14 G dopo un pagamento restituisce lo stesso payload`() = realTime {
        val (_, port) = simulator()
        val client = client(port)
        val pay = payment(client.pay(PaymentRequest(amountCents = 10)))
        val last = payment(client.sendLastResult())

        assertEquals(pay.raw.text, last.raw.text, "G identico all'esito precedente")
        assertEquals(Verdict.APPROVED, last.verdict)
    }

    // ─── Connessione ────────────────────────────────────────────────────────────

    @Test
    fun `IT-15 connessione persistente, due s sulla stessa connessione`() = realTime {
        val (sim, port) = simulator()
        val client = client(port, ingenico.copy(connectionMode = ConnectionMode.PERSISTENT))
        answered(client.status())
        answered(client.status())

        val state = sim.snapshot()
        assertEquals(1, state.connections, "una sola connessione")
        assertEquals(2, state.received('s'))
    }

    /** Il terminale che chiude dopo ogni transazione (Nexi): il comando dopo riconnette, senza replay né doppi frame. */
    @Test
    fun `IT-16 connessione persistente con closeAfterTransaction riconnette al comando successivo`() = realTime {
        val (sim, port) = simulator(fastSim { it.copy(closeAfterTransaction = true) })
        val client = client(port, ingenico.copy(connectionMode = ConnectionMode.PERSISTENT))
        val first = answered(client.status())
        client.linkState.first { it is LinkState.Closed }
        val second = answered(client.status())

        listOf(first, second).forEach {
            assertEquals(1, it.trace.sends, "un solo frame STX per comando")
            assertFalse(it.trace.replayedAfterReconnect, "nessun replay")
        }
        val state = sim.snapshot()
        assertEquals(2, state.connections, "riconnessione")
        assertEquals(2, state.received('s'))
    }

    /**
     * Sonda "variante ACK" (L§10.6): l'ECR risponde con ACK 78 a un simulatore che vuole 7A e che quindi reinvia
     * l'esito. Il reinvio è visibile solo se cade dentro il drain dell'ECR: drain 1 500 ms, reinvio a 200 ms.
     */
    @Test
    fun `IT-17 esito ritrasmesso per ACK non valido visto nel drain`() = realTime {
        val (sim, port) = simulator(fastSim { it.copy(retransmitResultIfNoValidAck = true, resultRetransmitDelayMs = 200) })
        val options = ingenico.copy(controlLrcTx = ControlLrcRule.SAME_AS_APP, postResultDrainMs = 1_500)
        val outcome = answered(client(port, options).pay(PaymentRequest(amountCents = 10)))

        assertTrue(WarningCode.RESULT_RETRANSMITTED in outcome.trace.codes, "warning: ${outcome.trace.codes}")
        assertEquals(2, outcome.trace.resultRetransmissions, "resultRetransmitMax = 2")
        val state = awaitSim(sim, "3 ACK dell'ECR") { it.controlFramesReceived.size >= 3 }
        assertTrue(state.controlFramesReceived.all { it == "06 03 78" }, "ACK SAME_AS_APP: ${state.controlFramesReceived}")
    }

    @Test
    fun `IT-18 stampa su ECR con E1, 4 righe S, sentinella e PAN mascherato`() = realTime {
        val (sim, port) = simulator()
        val outcome = answered(client(port, ingenico.copy(ecrPrinting = true)).pay(PaymentRequest(amountCents = 650)))

        val lines = outcome.trace.receipt
        assertEquals(4, lines.size, "righe: $lines")
        assertEquals(listOf(false, false, false, true), lines.map { it.isEnd }, "sentinella sull'ultima")
        assertEquals("PAGAMENTO CARTA ************1111", lines[1].text)
        assertTrue(lines.none { CLEAR_PAN in it.text || CLEAR_PAN in it.raw }, "testi mascherati")
        assertTrue(outcome.trace.printingSetupSent, "E1 inviato prima del pagamento")
        assertFalse(WarningCode.RECEIPT_END_MISSING in outcome.trace.codes)
        val state = sim.snapshot()
        assertEquals(1, state.received('E'))
        assertEquals(1, state.received('P'))
        assertFalse(state.ecrPrintingArmed, "stampa disarmata dopo la procedura")
        assertNoClearPanInSimLog()
    }

    @Test
    fun `IT-19 stato del terminale`() = realTime {
        val (_, port) = simulator()
        val status = assertInstanceOf(StatusResponse::class.java, answered(client(port).status()).response)

        assertEquals("02575733", status.terminalId)
        assertEquals(2, status.statusDigit)
        assertEquals("SIMP17 1", status.softwareRelease)
    }

    /** M2: il progress è un ACK implicito, quindi anche con RETRANSMIT nessun reinvio e nessun doppio addebito. */
    @Test
    fun `IT-20 PROGRESS_NO_ACK con RETRANSMIT invia un solo P`() = realTime {
        val (sim, port) = simulator(fastSim(SimFault.PROGRESS_NO_ACK))
        val options = ingenico.copy(financialAckPolicy = FinancialAckPolicy.RETRANSMIT)
        val outcome = answered(client(port, options).pay(PaymentRequest(amountCents = 10)))

        assertEquals(AckMode.PROGRESS_BEFORE_ACK, outcome.trace.ackMode)
        assertEquals(1, outcome.trace.sends)
        assertFalse(WarningCode.POSSIBLE_DUPLICATE in outcome.trace.codes)
        assertFalse(outcome.needsReview)
        assertEquals(1, sim.snapshot().received('P'))
    }

    /** U3: un 15 nudo è ambiguo e non provoca MAI un reinvio. */
    @Test
    fun `IT-21 BARE_NAK non provoca reinvii`() = realTime {
        val (sim, port) = simulator(fastSim(SimFault.BARE_NAK))
        val outcome = answered(client(port).pay(PaymentRequest(amountCents = 10)))

        assertEquals(1, outcome.trace.sends)
        assertTrue(WarningCode.AMBIGUOUS_NAK in outcome.trace.codes, "warning: ${outcome.trace.codes}")
        assertEquals(1, sim.snapshot().received('P'))
    }

    /**
     * U8/M4: l'esito trattenuto di un pagamento rimasto sconosciuto arriva dopo la richiesta successiva; per un `s`
     * è un orfano (UNEXPECTED_REPLY) e la prova del possibile esito perso, mai lo stato del terminale.
     */
    @Test
    fun `IT-22 LATE_RESULT_AFTER_NEXT_SEND rende orfano l'esito tardivo`() = realTime {
        val (sim, port) = simulator(fastSim(SimFault.LATE_RESULT_AFTER_NEXT_SEND))
        val client = client(port)

        val pay = client.pay(PaymentRequest(amountCents = 10))
        val unknown = assertInstanceOf(CommandOutcome.Unknown::class.java, pay, "atteso Unknown: $pay")
        assertEquals(Ecr17Failure.ResponseTimeout(2_000), unknown.failure)
        val held = sim.snapshot().lastResultPayload!!

        val status = answered(client.status())
        assertInstanceOf(StatusResponse::class.java, status.response)
        assertTrue(WarningCode.UNEXPECTED_REPLY in status.trace.codes, "warning: ${status.trace.codes}")
        val lost = status.trace.warnings.single { it.code == WarningCode.POSSIBLE_LOST_RESULT }
        val expected = String(PanMasker.maskApplicationPayload(held.toByteArray(Charsets.ISO_8859_1)), Charsets.ISO_8859_1)
        assertEquals(expected, lost.payload, "la prova è l'esito trattenuto, mascherato")
    }

    /** Sonda LRC (§9.2 punto 1): un terminale in std rifiuta ogni richiesta stx con NAK, nessuna elaborazione. */
    @Test
    fun `IT-23 simulatore in std rifiuta le richieste stx con NAK`() = realTime {
        val (sim, port) = simulator(fastSim { it.copy(appLrcMode = LrcMode.STD) })
        val outcome = client(port).pay(PaymentRequest(amountCents = 10))

        val refused = assertInstanceOf(CommandOutcome.Refused::class.java, outcome, "atteso Refused: $outcome")
        assertEquals(Ecr17Failure.Nak(4), refused.failure)
        val state = sim.snapshot()
        assertEquals(4, state.requestsRejectedForLrc)
        assertEquals(0, state.stan, "nessuna transazione")
    }

    // ─── Casi in più della tabella §4.5 ─────────────────────────────────────────

    /** A§8 D1: un 06 nudo seguito subito dall'esito non deve mangiarsi l'inizio dell'esito. */
    @Test
    fun `IT-24 BARE_ACK seguito dall'esito nello stesso segmento`() = realTime {
        val (_, port) = simulator(fastSim(SimFault.BARE_ACK))
        val outcome = answered(client(port).pay(PaymentRequest(amountCents = 10)))

        assertEquals(AckMode.BARE, outcome.trace.ackMode)
        assertEquals(Verdict.APPROVED, payment(outcome).verdict, "esito integro")
        assertTrue(WarningCode.BARE_CONTROL in outcome.trace.codes)
    }

    @Test
    fun `IT-25 DROP_AFTER_ACK porta a Unknown Disconnected dopo l'ACK`() = realTime {
        val (_, port) = simulator(fastSim(SimFault.DROP_AFTER_ACK))
        val outcome = client(port).pay(PaymentRequest(amountCents = 10))

        val unknown = assertInstanceOf(CommandOutcome.Unknown::class.java, outcome, "atteso Unknown: $outcome")
        assertInstanceOf(Ecr17Failure.Disconnected::class.java, unknown.failure)
        assertEquals(AckMode.EXPLICIT, unknown.trace?.ackMode)
    }

    @Test
    fun `IT-26 ristampa verso ECR con 3 righe S`() = realTime {
        val (_, port) = simulator()
        val client = client(port)
        payment(client.pay(PaymentRequest(amountCents = 10)))
        val outcome = client.reprint(ReprintRequest(target = ReprintTarget.ECR))

        val acknowledged = assertInstanceOf(CommandOutcome.Acknowledged::class.java, outcome, "atteso Acknowledged: $outcome")
        assertEquals(3, acknowledged.trace.receipt.size)
        assertTrue(acknowledged.trace.receipt.last().isEnd)
    }

    private companion object {
        const val CLEAR_PAN = "4111111111111111"
        const val TEST_TIMEOUT_MS = 20_000L
        const val AWAIT_MS = 3_000L
        const val POLL_MS = 10L
    }
}
