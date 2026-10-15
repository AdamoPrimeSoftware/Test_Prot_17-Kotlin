package it.prime.protocollo17.ecr17.sim

import it.prime.protocollo17.ecr17.message.RequestEncoder
import it.prime.protocollo17.ecr17.message.ResponseParser
import it.prime.protocollo17.ecr17.model.CardType
import it.prime.protocollo17.ecr17.model.CardVerificationRequest
import it.prime.protocollo17.ecr17.model.CloseSessionRequest
import it.prime.protocollo17.ecr17.model.CloseSessionResponse
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.EcrPrintingRequest
import it.prime.protocollo17.ecr17.model.EntryMode
import it.prime.protocollo17.ecr17.model.IncrementalAuthRequest
import it.prime.protocollo17.ecr17.model.IncrementalAuthResponse
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.ecr17.model.PaymentLayout
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.PaymentResponse
import it.prime.protocollo17.ecr17.model.PreAuthClosureRequest
import it.prime.protocollo17.ecr17.model.PreAuthClosureResponse
import it.prime.protocollo17.ecr17.model.PreAuthRequest
import it.prime.protocollo17.ecr17.model.PreAuthResponse
import it.prime.protocollo17.ecr17.model.ReprintRequest
import it.prime.protocollo17.ecr17.model.ReprintTarget
import it.prime.protocollo17.ecr17.model.ReversalRequest
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.ecr17.model.StatusResponse
import it.prime.protocollo17.ecr17.model.TerminalIds
import it.prime.protocollo17.ecr17.model.TotalsRequest
import it.prime.protocollo17.ecr17.model.TotalsResponse
import it.prime.protocollo17.ecr17.model.Verdict
import it.prime.protocollo17.ecr17.profile.ControlLrcRxPolicy
import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.ecr17.wire.ControlLrcRule
import it.prime.protocollo17.ecr17.wire.FrameSplitter
import it.prime.protocollo17.ecr17.wire.LrcMode
import it.prime.protocollo17.ecr17.wire.SplitItem
import it.prime.protocollo17.ecr17.wire.WireFrame
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Risposte del simulatore senza socket (§7.6 SR-xx): lunghezze e layout di ogni risposta riletti con il
 * ResponseParser (due implementazioni indipendenti dello stesso layout, §4.1), richieste con LRC errato, guasti di
 * §4.5 come piani di passi, PAN mascherato nel log del simulatore (M9).
 *
 * Ora fissa: 18/03/2026 09:47, cioè DDDHHMM "0770947" come la cattura Ingenico [22] (A§9.8).
 */
class SimResponderTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 3, 18, 9, 47)
    private val iso = Charsets.ISO_8859_1

    // ─── Supporto ───────────────────────────────────────────────────────────────

    /** Frame applicativo come lo consegnerebbe lo splitter del simulatore (LRC in [mode]). */
    private fun frame(payload: String, mode: LrcMode = LrcMode.STX): WireFrame.Application {
        val splitter = FrameSplitter(LrcMode.STX, ControlLrcRxPolicy.TOLERANT)
        splitter.feed(Frames.app(payload, mode))
        return (splitter.next() as SplitItem.Parsed).frame as WireFrame.Application
    }

    private fun frame(request: Ecr17Request, mode: LrcMode = LrcMode.STX): WireFrame.Application =
        frame(String(RequestEncoder.encode(request, TerminalIds()), iso), mode)

    private fun respond(request: Ecr17Request, config: SimulatorConfig = SimulatorConfig(), memory: SimMemory = SimMemory()): SimPlan =
        SimResponder.respond(frame(request), config, memory, now)

    private val SimPlan.sends: List<SimStep.Send> get() = steps.filterIsInstance<SimStep.Send>()
    private val SimPlan.frames: List<OutFrame> get() = sends.flatMap { it.frames }
    private val SimPlan.kinds: List<OutKind> get() = frames.map { it.kind }

    private fun OutFrame.payload(): String = String(bytes, 1, bytes.size - 3, iso)

    private fun SimPlan.result(): String = frames.single { it.kind == OutKind.RESULT }.payload()

    private fun SimPlan.resultSend(): SimStep.Send = sends.single { s -> s.frames.any { it.kind == OutKind.RESULT } }

    private fun parse(payload: String, sent: CommandCode) = ResponseParser.parse(payload.toByteArray(iso), sent, maskPan = true)

    private fun hex(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    // ─── Esiti approvati: lunghezze e rilettura ─────────────────────────────────

    /** L'esito E del simulatore ha il layout dell'Ingenico (71 B, A§9.8 [22]) e il parser lo rilegge campo per campo. */
    @Test
    fun `SR-01 P approvato produce E 71 B riletto dal parser`() {
        val plan = respond(PaymentRequest(amountCents = 650))
        val payload = plan.result()

        assertEquals(71, payload.length, "lunghezza E base")
        assertTrue(payload.contains("0004111111111111111"), "il simulatore manda il PAN in chiaro (M9 si verifica sull'ECR)")
        val r = assertInstanceOf(PaymentResponse::class.java, parse(payload, CommandCode.PAYMENT))
        assertEquals(PaymentLayout.BASE_71, r.layout)
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals("02575733", r.terminalId)
        assertEquals("***************1111", r.pan, "PAN mascherato dal parser")
        assertEquals(EntryMode.CLESS_ICC, r.entryMode)
        assertEquals("SIM001", r.authCode)
        assertEquals("0770947", r.hostDateTimeRaw)
        assertEquals(CardType.CREDIT, r.cardType)
        assertEquals("00000000023", r.acquirerId)
        assertEquals("000001", r.stan)
        assertEquals("000001", r.onlineId)
        assertEquals(1, plan.memory.stan, "STAN incrementato")
        assertEquals(650L, plan.memory.posTotalCents, "totale POS + importo")
        assertEquals(payload, plan.memory.lastResultPayload, "esito ricordato per G")
        val bytes = plan.frames.single { it.kind == OutKind.RESULT }.bytes
        assertEquals(LrcMode.STX.compute(payload.toByteArray(iso)), bytes.last().toInt() and 0xFF, "LRC in stx")
    }

    @Test
    fun `SR-02 X approvato produce E 92 B con action e importo dell'host`() {
        val payload = respond(PaymentRequest(amountCents = 650, extended = true)).result()

        assertEquals(92, payload.length)
        val r = assertInstanceOf(PaymentResponse::class.java, parse(payload, CommandCode.PAYMENT_EXTENDED))
        assertEquals(PaymentLayout.EXTENDED_92, r.layout)
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals("000", r.actionCode)
        assertEquals(650L, r.hostAmountCents)
    }

    /** Storno: 84 B, pos 35-40 riservate (nessun codice autorizzazione), totale ridotto dell'ultimo approvato. */
    @Test
    fun `SR-03 S approvato produce E 84 B e storna l'ultimo importo`() {
        val afterPayment = respond(PaymentRequest(amountCents = 650)).memory
        val plan = respond(ReversalRequest(), memory = afterPayment)
        val payload = plan.result()

        assertEquals(84, payload.length)
        val r = assertInstanceOf(PaymentResponse::class.java, parse(payload, CommandCode.REVERSAL))
        assertEquals(PaymentLayout.REVERSAL_84, r.layout)
        assertEquals(Verdict.APPROVED, r.verdict)
        assertNull(r.authCode, "35-40 riservate")
        assertEquals("000", r.actionCode)
        assertEquals("000002", r.stan)
        assertEquals(0L, plan.memory.posTotalCents, "totale stornato")
    }

    @Test
    fun `SR-04 rifiuti di P X S H con descrizione, lunghezze 71 92 84 71 e action 100 per H`() {
        val config = SimulatorConfig(decision = SimDecision.DECLINE)
        val cases = listOf(
            Triple(PaymentRequest(amountCents = 10), CommandCode.PAYMENT, 71),
            Triple(PaymentRequest(amountCents = 10, extended = true), CommandCode.PAYMENT_EXTENDED, 92),
            Triple(ReversalRequest(), CommandCode.REVERSAL, 84),
            Triple(CardVerificationRequest(), CommandCode.CARD_VERIFICATION, 71),
        )
        for ((request, command, length) in cases) {
            val plan = respond(request, config)
            val payload = plan.result()
            assertEquals(length, payload.length, "lunghezza per $command")
            val r = assertInstanceOf(PaymentResponse::class.java, parse(payload, command))
            assertEquals(Verdict.DECLINED, r.verdict, "esito per $command")
            assertEquals("OPERAZIONE ANNULLATA", r.errorDescription, "descrizione per $command")
            assertEquals(CardType.CREDIT, r.cardType)
            assertEquals("000001", r.stan, "STAN anche sul rifiuto")
            assertEquals(0L, plan.memory.posTotalCents, "nessun movimento per $command")
            if (command == CommandCode.CARD_VERIFICATION) assertEquals("100", r.actionCode, "H KO: action in 37/3")
        }
    }

    @Test
    fun `SR-05 carta assente risponde 05 con il layout positivo e senza contatori`() {
        val plan = respond(PaymentRequest(amountCents = 10), SimulatorConfig(decision = SimDecision.CARD_NOT_PRESENT))
        val payload = plan.result()

        assertEquals(71, payload.length)
        val r = assertInstanceOf(PaymentResponse::class.java, parse(payload, CommandCode.PAYMENT))
        assertEquals(Verdict.CARD_NOT_PRESENT, r.verdict)
        assertEquals("05", r.resultCode)
        assertFalse(payload.contains("4111"), "nessun PAN senza carta")
        assertEquals(0, plan.memory.stan, "nessuna transazione")
    }

    @Test
    fun `SR-06 p produce e 106 B con importo e codice preautorizzazione nuovo`() {
        val plan = respond(PreAuthRequest(amountCents = 5_000))
        val payload = plan.result()

        assertEquals(106, payload.length)
        val r = assertInstanceOf(PreAuthResponse::class.java, parse(payload, CommandCode.PRE_AUTH))
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals(5_000L, r.preAuthorizedAmountCents)
        assertEquals("000000001", r.preAuthCode)
        assertEquals("000", r.actionCode)
        assertEquals("0770947", r.hostDateTimeRaw)
        assertEquals("00000000023", r.acquirerId)
        assertEquals("000001", r.stan)
        assertNull(r.cardType, "nel ramo OK la pos 48 è l'ultima cifra dell'importo")
        assertEquals("000000001", plan.memory.lastPreAuthCode, "codice ricordato")
        assertEquals(0L, plan.memory.posTotalCents, "la preautorizzazione non muove il totale")

        val ko = respond(PreAuthRequest(amountCents = 5_000), SimulatorConfig(decision = SimDecision.DECLINE)).result()
        assertEquals(106, ko.length)
        val k = assertInstanceOf(PreAuthResponse::class.java, parse(ko, CommandCode.PRE_AUTH))
        assertEquals(Verdict.DECLINED, k.verdict)
        assertEquals("100", k.actionCode)
        assertEquals(CardType.CREDIT, k.cardType)
        assertEquals("000001", k.stan)
    }

    @Test
    fun `SR-07 i produce i 75 B con il layout proprio`() {
        val payload = respond(IncrementalAuthRequest(amountCents = 1_000, originalPreAuthCode = "42")).result()

        assertEquals(75, payload.length)
        val r = assertInstanceOf(IncrementalAuthResponse::class.java, parse(payload, CommandCode.INCREMENTAL_AUTH))
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals("SIM001", r.authCode)
        assertEquals("00000000023", r.acquirerId)
        assertEquals("000001", r.stan)
        assertEquals("0770947", r.hostDateTimeRaw)
        assertEquals("000", r.actionCode)
        assertEquals("***************1111", r.pan)
    }

    @Test
    fun `SR-08 c produce c 74 B con action e somma l'importo al totale`() {
        val plan = respond(PreAuthClosureRequest(amountCents = 1_234, originalPreAuthCode = "1"))
        val payload = plan.result()

        assertEquals(74, payload.length)
        val r = assertInstanceOf(PreAuthClosureResponse::class.java, parse(payload, CommandCode.PRE_AUTH_CLOSURE))
        assertEquals(Verdict.APPROVED, r.verdict)
        assertEquals('c', r.replyCode)
        assertEquals("000", r.actionCode)
        assertEquals(1_234L, plan.memory.posTotalCents)
    }

    @Test
    fun `SR-09 s riporta TID, data e ora, stato 2 e release a multipli di 8`() {
        val plan = respond(StatusRequest)
        val payload = plan.result()

        assertEquals(39, payload.length, "31 + release di 8")
        val r = assertInstanceOf(StatusResponse::class.java, parse(payload, CommandCode.STATUS))
        assertEquals("02575733", r.terminalId)
        assertEquals(now, r.dateTime)
        assertEquals(2, r.statusDigit)
        assertEquals("SIMP17 1", r.softwareRelease)
        assertEquals(300L, plan.resultSend().atMs, "s: min(resultDelayMs, 300)")
        assertFalse(OutKind.PROGRESS in plan.kinds, "progress (400 ms) dopo l'esito: non inviato")

        val longRelease = respond(StatusRequest, SimulatorConfig(softwareRelease = "SIM 123456")).result()
        assertEquals(31 + 16, longRelease.length, "release portata a 16")
    }

    @Test
    fun `SR-10 T 34 B e C 44 B riportano il totale e C lo azzera`() {
        val memory = SimMemory(posTotalCents = 1_234)
        val totals = respond(TotalsRequest(), memory = memory).result()
        assertEquals(34, totals.length)
        val t = assertInstanceOf(TotalsResponse::class.java, parse(totals, CommandCode.TOTALS))
        assertEquals(1_234L, t.posTotalCents)

        val closePlan = respond(CloseSessionRequest(), memory = memory)
        val close = closePlan.result()
        assertEquals(44, close.length)
        val c = assertInstanceOf(CloseSessionResponse::class.java, parse(close, CommandCode.CLOSE_SESSION))
        assertEquals(Verdict.APPROVED, c.verdict)
        assertEquals(1_234L, c.posTotalCents)
        assertEquals(1_234L, c.hostTotalCents)
        assertEquals(0L, closePlan.memory.posTotalCents, "totali azzerati dalla chiusura")
    }

    /** G restituisce l'ultimo esito IDENTICO (byte per byte); senza esiti un E KO "NESSUNA OPERAZIONE". */
    @Test
    fun `SR-11 G restituisce l'ultimo esito identico o NESSUNA OPERAZIONE`() {
        val afterPayment = respond(PaymentRequest(amountCents = 650)).memory
        val g = respond(LastResultRequest(), memory = afterPayment)
        assertEquals(afterPayment.lastResultPayload, g.result(), "payload identico")
        assertEquals(afterPayment.stan, g.memory.stan, "G non è una transazione")

        val empty = respond(LastResultRequest()).result()
        assertEquals(71, empty.length)
        val r = assertInstanceOf(PaymentResponse::class.java, parse(empty, CommandCode.LAST_RESULT))
        assertEquals(Verdict.DECLINED, r.verdict)
        assertEquals("NESSUNA OPERAZIONE", r.errorDescription)
    }

    // ─── Richieste rifiutate ────────────────────────────────────────────────────

    /** §4.4: LRC errato nella modalità del simulatore ⇒ NAK, nessuna elaborazione, contatore (sonda di §9.2). */
    @Test
    fun `SR-12 richiesta con LRC errato riceve solo NAK e incrementa il contatore`() {
        val request = frame(PaymentRequest(amountCents = 10), mode = LrcMode.STD)
        val plan = SimResponder.respond(request, SimulatorConfig(), SimMemory(), now)

        assertEquals(listOf(OutKind.NAK), plan.kinds, "solo NAK")
        assertArrayEquals(hex(0x15, 0x03, 0x69), plan.frames.single().bytes, "NAK secondo ackRule WIRE")
        assertEquals(1, plan.memory.requestsRejectedForLrc)
        assertEquals(0, plan.memory.stan, "non elaborata")
        assertTrue(plan.notes.any { "LRC" in it }, "nota nel log: ${plan.notes}")

        val stdSim = SimResponder.respond(frame(PaymentRequest(amountCents = 10)), SimulatorConfig(appLrcMode = LrcMode.STD), SimMemory(), now)
        assertEquals(listOf(OutKind.NAK), stdSim.kinds, "simulatore in std, ECR in stx: rifiutata")
    }

    @Test
    fun `SR-13 codice non gestito o payload corto ricevono NAK`() {
        assertEquals(listOf(OutKind.NAK), SimResponder.respond(frame("000000000Z00"), SimulatorConfig(), SimMemory(), now).kinds)
        assertEquals(listOf(OutKind.NAK), SimResponder.respond(frame("00000"), SimulatorConfig(), SimMemory(), now).kinds)
    }

    // ─── Log del simulatore ─────────────────────────────────────────────────────

    /** M9: anche il log del simulatore maschera il PAN che manda in chiaro; l'LRC resta quello del filo. */
    @Test
    fun `SR-14 FrameOut con PAN mascherato e LRC originale`() {
        val result = respond(PaymentRequest(amountCents = 650)).frames.single { it.kind == OutKind.RESULT }
        val event = SimResponder.frameOut(at = 1L, frame = result)
        val logged = String(event.bytes, iso)

        assertFalse(logged.contains("4111111111111111"), "PAN in chiaro nel log: $logged")
        assertTrue(logged.contains("***************1111"), "PAN mascherato a larghezza fissa")
        assertEquals(result.bytes.size, event.bytes.size)
        assertEquals(result.bytes.last(), event.bytes.last(), "LRC originale")
        assertEquals(result.description, event.description)
    }

    // ─── Stampa su ECR e ristampa ───────────────────────────────────────────────

    @Test
    fun `SR-15 E1 arma la stampa e la procedura successiva invia 4 righe S dopo l'esito`() {
        val armed = respond(EcrPrintingRequest(enabled = true))
        assertEquals(listOf(OutKind.ACK), armed.kinds, "E: solo ACK")
        assertTrue(armed.memory.ecrPrintingArmed)

        val plan = respond(PaymentRequest(amountCents = 650), memory = armed.memory)
        assertEquals(
            listOf(OutKind.ACK, OutKind.PROGRESS, OutKind.RESULT) + List(4) { OutKind.RECEIPT },
            plan.kinds,
            "righe S dopo l'esito",
        )
        val lines = plan.frames.filter { it.kind == OutKind.RECEIPT }.map {
            ResponseParser.parseReceiptLine(it.payload().toByteArray(iso), maskPan = true)
        }
        assertEquals(listOf(false, false, false, true), lines.map { it.isEnd }, "sentinella solo sull'ultima")
        assertEquals("SIMULATORE P17", lines[0].text)
        assertEquals("PAGAMENTO CARTA ************1111", lines[1].text, "PAN in chiaro sul filo, mascherato dal parser")
        assertEquals("IMPORTO EUR 6,50", lines[2].text)
        assertEquals("ESITO APPROVATO", lines[3].text)
        assertFalse(plan.memory.ecrPrintingArmed, "disarmata dopo la procedura")

        val disarmed = respond(EcrPrintingRequest(enabled = false), memory = armed.memory)
        assertFalse(disarmed.memory.ecrPrintingArmed, "E0 disarma")
    }

    @Test
    fun `SR-16 con receiptAfterResult false le righe S precedono l'esito`() {
        val config = SimulatorConfig(receiptAfterResult = false)
        val plan = respond(PaymentRequest(amountCents = 10), config, SimMemory(ecrPrintingArmed = true))

        assertEquals(listOf(OutKind.ACK, OutKind.PROGRESS) + List(4) { OutKind.RECEIPT } + OutKind.RESULT, plan.kinds)
    }

    @Test
    fun `SR-17 R verso ECR invia 3 righe S dell'ultimo scontrino, verso il terminale solo ACK`() {
        val afterPayment = respond(PaymentRequest(amountCents = 650)).memory
        val toEcr = respond(ReprintRequest(target = ReprintTarget.ECR), memory = afterPayment)
        assertEquals(listOf(OutKind.ACK) + List(3) { OutKind.RECEIPT }, toEcr.kinds)
        val last = toEcr.frames.last().payload()
        assertTrue(last.endsWith("}}}}}}\u001B"), "sentinella sull'ultima riga")
        assertTrue(last.contains("ESITO APPROVATO"), "righe dell'ultimo scontrino")

        assertEquals(listOf(OutKind.ACK), respond(ReprintRequest(), memory = afterPayment).kinds)
        assertEquals(List(3) { OutKind.RECEIPT }, respond(ReprintRequest(target = ReprintTarget.ECR)).kinds.drop(1), "anche senza scontrino")
    }

    // ─── Tempi e regole dei controlli ───────────────────────────────────────────

    @Test
    fun `SR-18 ACK, progress ed esito ai tempi della configurazione`() {
        val config = SimulatorConfig(ackDelayMs = 5, progressDelayMs = 20, resultDelayMs = 150)
        val plan = respond(PaymentRequest(amountCents = 10), config)

        assertEquals(listOf(5L, 20L, 150L), plan.sends.map { it.atMs })
        assertArrayEquals(Frames.ACKW, plan.frames[0].bytes, "ACK WIRE 06 03 7A")
        assertArrayEquals(Frames.PRG, plan.frames[1].bytes, "progress Ingenico")
        assertFalse(plan.closeAfter)
    }

    @Test
    fun `SR-19 ackRule SAME_AS_APP produce ACK 78 e NAK 6B in stx`() {
        val config = SimulatorConfig(ackRule = ControlLrcRule.SAME_AS_APP)
        assertArrayEquals(Frames.ACK78, respond(StatusRequest, config).frames.first().bytes)
        assertArrayEquals(hex(0x15, 0x03, 0x6B), respond(StatusRequest, config.copy(fault = SimFault.NAK_ALWAYS, faultOnlyFinancial = false)).frames.single().bytes)
    }

    @Test
    fun `SR-20 retransmitResultIfNoValidAck attende l'ACK esatto di ackRule`() {
        val config = SimulatorConfig(retransmitResultIfNoValidAck = true, resultRetransmitDelayMs = 200, resultRetransmitMax = 2)
        val wait = respond(PaymentRequest(amountCents = 10), config).resultSend().ackWait!!
        assertArrayEquals(Frames.ACKW, wait.validAck)
        assertEquals(200L, wait.waitMs)
        assertEquals(2, wait.maxResendsWithoutValidAck)

        val plain = respond(PaymentRequest(amountCents = 10)).resultSend().ackWait!!
        assertNull(plain.validAck, "senza l'opzione qualsiasi ACK chiude l'attesa")
        assertEquals(SimResponder.ECR_REPLY_WAIT_MS, plain.waitMs)
    }

    /** E1 non è una transazione: l'ECR manda il comando vero sulla stessa connessione subito dopo (IT-27). */
    @Test
    fun `SR-21 closeAfterTransaction chiude dopo una transazione completa, non dopo E1 o un NAK`() {
        val config = SimulatorConfig(closeAfterTransaction = true)
        assertTrue(respond(StatusRequest, config).closeAfter)
        assertTrue(respond(ReprintRequest(), config).closeAfter)
        assertFalse(respond(EcrPrintingRequest(enabled = true), config).closeAfter, "E1 prepara la procedura successiva")
        val rejected = SimResponder.respond(frame(StatusRequest, mode = LrcMode.STD), config, SimMemory(), now)
        assertFalse(rejected.closeAfter)
    }

    // ─── Guasti (§4.5) ──────────────────────────────────────────────────────────

    private fun faulty(fault: SimFault, request: Ecr17Request = PaymentRequest(amountCents = 10), memory: SimMemory = SimMemory()) =
        respond(request, SimulatorConfig(fault = fault), memory)

    @Test
    fun `SR-22 NO_ACK e PROGRESS_NO_ACK non inviano l'ACK, solo il secondo invia il progress`() {
        assertEquals(listOf(OutKind.RESULT), faulty(SimFault.NO_ACK).kinds)
        assertEquals(listOf(OutKind.PROGRESS, OutKind.RESULT), faulty(SimFault.PROGRESS_NO_ACK).kinds)
    }

    /**
     * Prima: il progress partiva solo se più rapido dell'esito, quindi con resultDelayMs < progressDelayMs (valori
     * scelti dall'operatore) PROGRESS_NO_ACK diventava un NO_ACK e l'ECR in RETRANSMIT ritrasmetteva il pagamento.
     * Ora il progress precede sempre l'esito.
     */
    @Test
    fun `SR-32 PROGRESS_NO_ACK invia il progress anche con un esito più rapido del progress`() {
        val config = SimulatorConfig(fault = SimFault.PROGRESS_NO_ACK, progressDelayMs = 400, resultDelayMs = 100)
        val plan = respond(PaymentRequest(amountCents = 10), config)

        assertEquals(listOf(OutKind.PROGRESS, OutKind.RESULT), plan.kinds)
        assertEquals(listOf(100L, 100L), plan.sends.map { it.atMs }, "progress al più tardi insieme all'esito")
    }

    @Test
    fun `SR-23 SILENT, NO_RESULT, DROP_AFTER_REQUEST e DROP_AFTER_ACK non producono esiti`() {
        val silent = faulty(SimFault.SILENT)
        assertTrue(silent.steps.isEmpty(), "SILENT: nulla")
        assertEquals(0, silent.memory.stan, "nessuna transazione")

        assertEquals(listOf(OutKind.ACK, OutKind.PROGRESS), faulty(SimFault.NO_RESULT).kinds)
        assertNull(faulty(SimFault.NO_RESULT).memory.lastResultPayload)

        val dropRequest = faulty(SimFault.DROP_AFTER_REQUEST)
        assertEquals(1, dropRequest.steps.size)
        assertInstanceOf(SimStep.Close::class.java, dropRequest.steps.single())

        val dropAck = faulty(SimFault.DROP_AFTER_ACK)
        assertEquals(listOf(OutKind.ACK), dropAck.kinds)
        assertInstanceOf(SimStep.Close::class.java, dropAck.steps.last())
    }

    @Test
    fun `SR-24 DROP_AFTER_RESULT invia l'esito senza attendere l'ACK e chiude`() {
        val plan = faulty(SimFault.DROP_AFTER_RESULT)
        assertNull(plan.resultSend().ackWait, "nessuna attesa dell'ACK")
        assertInstanceOf(SimStep.Close::class.java, plan.steps.last())
    }

    /** BARE_NAK: un 15 nudo (ambiguo, mai un reinvio per l'ECR) e poi l'esito, senza ACK né progress. */
    @Test
    fun `SR-25 BARE_NAK e BARE_ACK`() {
        val bareNak = faulty(SimFault.BARE_NAK)
        assertEquals(listOf(OutKind.BARE_NAK, OutKind.RESULT), bareNak.kinds)
        assertArrayEquals(hex(0x15), bareNak.frames.first().bytes)

        val bareAck = faulty(SimFault.BARE_ACK)
        val send = bareAck.sends.single()
        assertEquals(listOf(OutKind.BARE_ACK, OutKind.RESULT), send.frames.map { it.kind }, "06 ed esito nello stesso segmento")
        assertArrayEquals(hex(0x06), send.frames.first().bytes)
        assertEquals(SimulatorConfig().ackDelayMs, send.atMs)
    }

    @Test
    fun `SR-26 BAD_LRC_RESULT_ONCE corrompe l'LRC del primo invio e reinvia l'esito corretto`() {
        val send = faulty(SimFault.BAD_LRC_RESULT_ONCE).resultSend()
        val first = send.frames.single().bytes
        val resend = send.resend!!.bytes
        val payload = first.copyOfRange(1, first.size - 2)

        assertTrue(LrcMode.entries.none { it.compute(payload) == (first.last().toInt() and 0xFF) }, "LRC non valido in nessuna modalità")
        assertEquals(LrcMode.STX.compute(payload), resend.last().toInt() and 0xFF, "reinvio con LRC corretto")
        assertArrayEquals(first.copyOfRange(0, first.size - 1), resend.copyOfRange(0, resend.size - 1), "stesso frame")
    }

    @Test
    fun `SR-27 UNEXPECTED_FRAME_BEFORE_RESULT invia un U stantio di 34 B prima dell'esito`() {
        val plan = faulty(SimFault.UNEXPECTED_FRAME_BEFORE_RESULT)
        assertEquals(listOf(OutKind.ACK, OutKind.PROGRESS, OutKind.ORPHAN, OutKind.RESULT), plan.kinds)
        val u = plan.frames.single { it.kind == OutKind.ORPHAN }.payload()
        assertEquals(34, u.length)
        assertEquals('U', u[9])
        assertNull(parse(u, CommandCode.PAYMENT), "un U non è mai un esito")
    }

    /** NAK_ONCE è per transazione: la ritrasmissione identica è servita, una nuova richiesta riceve di nuovo NAK. */
    @Test
    fun `SR-28 NAK_ONCE rifiuta il primo invio e serve la ritrasmissione, NAK_ALWAYS rifiuta sempre`() {
        val first = faulty(SimFault.NAK_ONCE)
        assertEquals(listOf(OutKind.NAK), first.kinds)
        val second = faulty(SimFault.NAK_ONCE, memory = first.memory)
        assertTrue(OutKind.RESULT in second.kinds, "ritrasmissione servita: ${second.kinds}")
        val third = faulty(SimFault.NAK_ONCE, memory = second.memory)
        assertEquals(listOf(OutKind.NAK), third.kinds, "nuova transazione: di nuovo NAK")

        val always = faulty(SimFault.NAK_ALWAYS)
        assertEquals(listOf(OutKind.NAK), always.kinds)
        assertEquals(listOf(OutKind.NAK), faulty(SimFault.NAK_ALWAYS, memory = always.memory).kinds)
    }

    /** L'esito trattenuto parte appena arriva la richiesta successiva (anche un s), che poi è servita normalmente. */
    @Test
    fun `SR-29 LATE_RESULT_AFTER_NEXT_SEND trattiene l'esito e lo invia prima della richiesta successiva`() {
        val held = faulty(SimFault.LATE_RESULT_AFTER_NEXT_SEND)
        assertEquals(listOf(OutKind.ACK, OutKind.PROGRESS), held.kinds, "nessun esito")
        val heldPayload = held.memory.heldResultPayload
        assertTrue(heldPayload != null && heldPayload.length == 71, "esito trattenuto: $heldPayload")
        assertEquals(heldPayload, held.memory.lastResultPayload, "esito prodotto: G lo restituirebbe")

        val next = faulty(SimFault.LATE_RESULT_AFTER_NEXT_SEND, request = StatusRequest, memory = held.memory)
        assertEquals(listOf(OutKind.ORPHAN, OutKind.ACK, OutKind.RESULT), next.kinds, "s servito normalmente dopo l'esito vecchio")
        assertEquals(heldPayload, next.frames.first().payload())
        assertEquals(0L, next.sends.first().atMs, "subito dopo la richiesta")
        assertNull(next.memory.heldResultPayload)

        val payment = respond(PaymentRequest(amountCents = 10), SimulatorConfig(fault = SimFault.LATE_RESULT_AFTER_NEXT_SEND), held.memory)
        assertTrue(OutKind.RESULT in payment.kinds, "dopo lo svuotamento la richiesta è servita normalmente")
    }

    @Test
    fun `SR-30 faultOnlyFinancial lascia funzionare i comandi di servizio`() {
        assertEquals(OutKind.ACK, faulty(SimFault.NO_ACK, request = StatusRequest).kinds.first())
        val everywhere = respond(StatusRequest, SimulatorConfig(fault = SimFault.NO_ACK, faultOnlyFinancial = false))
        assertNotEquals(OutKind.ACK, everywhere.kinds.first())
    }

    @Test
    fun `SR-31 STALE_RESULT_ON_CONNECT invia l'ultimo esito alla connessione senza attendere ACK`() {
        val config = SimulatorConfig(fault = SimFault.STALE_RESULT_ON_CONNECT)
        val last = respond(PaymentRequest(amountCents = 650)).memory

        val steps = SimResponder.onConnect(config, last)
        val send = steps.single() as SimStep.Send
        assertEquals(OutKind.ORPHAN, send.frames.single().kind)
        assertEquals(last.lastResultPayload, send.frames.single().payload())
        assertNull(send.ackWait, "l'ECR non ACKa gli orfani prima dell'invio")

        val fallback = (SimResponder.onConnect(config, SimMemory()).single() as SimStep.Send).frames.single().payload()
        assertEquals(SimResponder.noOperationPayload(config), fallback)
        assertTrue(SimResponder.onConnect(SimulatorConfig(), last).isEmpty(), "senza guasto nulla alla connessione")
        assertTrue(OutKind.RESULT in respond(PaymentRequest(amountCents = 10), config).kinds, "le richieste sono servite normalmente")
    }
}
