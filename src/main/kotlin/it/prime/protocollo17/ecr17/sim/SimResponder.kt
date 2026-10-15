package it.prime.protocollo17.ecr17.sim

import it.prime.protocollo17.ecr17.wire.ControlChars
import it.prime.protocollo17.ecr17.wire.FrameCodec
import it.prime.protocollo17.ecr17.wire.PanMasker
import it.prime.protocollo17.ecr17.wire.WireFrame
import java.time.LocalDateTime

// ─── Piano di risposta ──────────────────────────────────────────────────────────

/** Natura di un frame inviato dal simulatore: serve ai test e alle descrizioni del log. */
internal enum class OutKind { ACK, NAK, BARE_ACK, BARE_NAK, PROGRESS, RESULT, RECEIPT, ORPHAN }

/** Un frame completo da scrivere sul filo, con la descrizione italiana per il log del simulatore. */
internal class OutFrame(val bytes: ByteArray, val description: String, val kind: OutKind)

/**
 * Attesa della risposta dell'ECR (ACK/NAK) dopo un frame applicativo inviato dal simulatore.
 *
 * Un NAK provoca SEMPRE il reinvio (fino a [SimResponder.MAX_NAK_RESENDS]); con [validAck] (solo per l'esito e solo
 * con `retransmitResultIfNoValidAck`) anche un ACK assente o con l'LRC di un'altra convenzione entro [waitMs] fa
 * reinviare, al massimo [maxResendsWithoutValidAck] volte: è lo scenario T3/I-7 di L§7.2.
 */
internal class AckWait(
    val waitMs: Long,
    /** null = qualsiasi ACK chiude l'attesa; altrimenti i byte esatti dell'ACK secondo `ackRule`. */
    val validAck: ByteArray?,
    val maxResendsWithoutValidAck: Int,
)

/** Un passo del piano: [atMs] = istante minimo dall'arrivo della richiesta (i passi restano in ordine di lista). */
internal sealed interface SimStep {
    val atMs: Long

    /**
     * Una sola write con uno o più frame. Più frame nello stesso segmento servono a BARE_ACK (`06` seguito subito
     * dall'esito): è proprio il caso in cui uno splitter sbagliato si mangia l'inizio dell'esito (A§8 D1).
     */
    class Send(
        override val atMs: Long,
        val frames: List<OutFrame>,
        val ackWait: AckWait? = null,
        /** Cosa scrivere a un NAK o a un ACK mancante: l'esito corretto anche quando il primo invio era corrotto. */
        val resend: OutFrame? = null,
    ) : SimStep

    /** Chiusura del socket da parte del simulatore (guasti DROP_*). */
    class Close(override val atMs: Long, val reason: String) : SimStep
}

/** Risposta decisa per UNA richiesta: nuovo stato, passi da eseguire, note per il log. */
internal class SimPlan(
    val memory: SimMemory,
    val steps: List<SimStep>,
    val notes: List<String>,
    /** true: dopo l'ultimo passo la connessione si chiude (closeAfterTransaction). */
    val closeAfter: Boolean,
)

// ─── Stato del simulatore ───────────────────────────────────────────────────────

/**
 * Stato interno del simulatore, immutabile: [SimResponder] ne restituisce una copia aggiornata e
 * TerminalSimulator la conserva sotto lock. Contiene più di [SimState] (esito trattenuto, NAK_ONCE, scontrini),
 * perché i guasti che coinvolgono due richieste (anche su due connessioni) devono ricordare qualcosa.
 *
 * I payload sono stringhe ISO-8859-1 (1 carattere = 1 byte), come [SimState.lastResultPayload].
 */
internal data class SimMemory(
    val connections: Int = 0,
    val applicationFramesReceived: Map<Char, Int> = emptyMap(),
    val controlFramesReceived: List<String> = emptyList(),
    val requestsRejectedForLrc: Int = 0,
    /** Ultimo esito di transazione PRODOTTO (P X S p i c H): G lo restituisce identico. */
    val lastResultPayload: String? = null,
    val stan: Int = 0,
    val onlineId: Int = 0,
    val posTotalCents: Long = 0,
    val ecrPrintingArmed: Boolean = false,
    /** Importo dell'ultimo P/X/c approvato e non ancora stornato: lo storno lo sottrae dal totale POS. */
    val lastApprovedAmountCents: Long = 0,
    val preAuthSequence: Int = 0,
    val lastPreAuthCode: String? = null,
    /** LATE_RESULT_AFTER_NEXT_SEND: esito calcolato ma non inviato, in attesa della richiesta successiva. */
    val heldResultPayload: String? = null,
    /** NAK_ONCE: payload già rifiutato una volta; la sua ritrasmissione identica viene servita. */
    val nakOncePayload: String? = null,
    val lastFinancialReceipt: List<String>? = null,
    val lastServiceReceipt: List<String>? = null,
) {
    fun toState(): SimState = SimState(
        connections = connections,
        applicationFramesReceived = applicationFramesReceived,
        controlFramesReceived = controlFramesReceived,
        requestsRejectedForLrc = requestsRejectedForLrc,
        lastResultPayload = lastResultPayload,
        stan = stan,
        onlineId = onlineId,
        posTotalCents = posTotalCents,
        ecrPrintingArmed = ecrPrintingArmed,
    )
}

// ─── Responder ──────────────────────────────────────────────────────────────────

/**
 * Costruzione delle risposte del terminale finto (§4.4, §4.5): funzione PURA da (richiesta, config, stato, ora) a
 * piano di risposta, testabile senza socket. I tempi e l'I/O sono di SimConnection.
 *
 * Le risposte sono composte qui campo per campo, senza RequestEncoder né ResponseParser (§4.1): così i test
 * incrociati (simulatore contro parser) confrontano due implementazioni indipendenti del layout. I layout seguono
 * A§4.5 e il filo Ingenico osservato (A§9.8): LRC dei frame applicativi in `appLrcMode`, ACK/NAK secondo `ackRule`.
 *
 * Il simulatore manda il PAN in CHIARO (`config.pan`), come alcuni terminali reali (A§9.9): serve a verificare che
 * l'ECR lo mascheri ovunque (M9). Il suo stesso log lo maschera ([frameOut]).
 */
internal object SimResponder {

    /** Attesa dell'ACK/NAK dell'ECR dopo un frame applicativo (esito senza reinvii automatici, righe S, U, esito trattenuto). */
    const val ECR_REPLY_WAIT_MS = 1_000L

    /** Reinvii dello stesso frame su NAK dell'ECR: un terminale vero non insiste all'infinito. */
    const val MAX_NAK_RESENDS = 3

    /** s, T, G rispondono al più dopo 300 ms (§4.4): sono comandi di servizio, non elaborano una carta. */
    private const val FAST_RESULT_CAP_MS = 300L

    /** Comandi soggetti ai guasti con faultOnlyFinancial (commento di SimulatorConfig). */
    private const val FAULT_CODES = "PXSpicHC"

    /** Transazioni: decisione, STAN/online, esito ricordato per G. */
    private const val TRANSACTION_CODES = "PXSpicH"

    /** Procedure che consumano la stampa su ECR armata (CommandCode.wantsEcrPrintingSetup). */
    private const val PRINTING_CODES = "PXSpicHCT"

    /** Codici di richiesta gestiti; un altro codice riceve NAK. */
    private const val SUPPORTED_CODES = "sPXSpicHCTGER"

    private const val ACQUIRER = "00000000023"          // come le catture Ingenico (A§9.8)
    private const val ENTRY_MODE = "CLI"                 // contactless ICC
    private const val CARD_TYPE_CREDIT = '2'
    private const val ACTION_OK = "000"
    private const val ACTION_KO = "100"
    private const val NO_OPERATION_TEXT = "NESSUNA OPERAZIONE"
    private const val RECEIPT_HEADER = "SIMULATORE P17"

    /** Sei 0x7D + ESC: fine scontrino (A§4.5.9). */
    private val RECEIPT_END: String = ControlChars.RECEIPT_NEWLINE.toChar().toString().repeat(6) + ControlChars.ESC.toChar()

    private val ISO = Charsets.ISO_8859_1

    // ─── API ────────────────────────────────────────────────────────────────────

    /**
     * Piano di risposta a una richiesta applicativa ricevuta. Ordine delle decisioni (vincolante):
     * 1. LRC errato in `appLrcMode` ⇒ NAK, nessuna elaborazione, `requestsRejectedForLrc++` (§4.4);
     * 2. payload corto o codice non gestito ⇒ NAK;
     * 3. esito trattenuto da LATE_RESULT_AFTER_NEXT_SEND ⇒ inviato subito, poi questa richiesta è servita normalmente;
     * 4. guasto (solo P X S p i c H C con faultOnlyFinancial), ACK, eventuale progress, esito e righe S.
     */
    fun respond(request: WireFrame.Application, config: SimulatorConfig, memory: SimMemory, now: LocalDateTime): SimPlan {
        val b = PlanBuilder(config)
        val text = String(request.payload, ISO)
        val code = request.replyCode
        val ackAt = config.ackDelayMs.coerceAtLeast(0)

        if (config.appLrcMode !in request.check.matchingModes) {
            val valid = request.check.matchingModes.joinToString("/") { it.wireName }.ifEmpty { "nessuna modalità" }
            b.nak(ackAt, "LRC della richiesta non valido in ${config.appLrcMode.wireName}")
            b.note(
                "Richiesta '${code ?: '?'}' rifiutata: LRC %02X non valido in %s (valido in: %s), non elaborata"
                    .format(request.lrc, config.appLrcMode.wireName, valid),
            )
            return b.build(memory.copy(requestsRejectedForLrc = memory.requestsRejectedForLrc + 1))
        }
        if (code == null || code !in SUPPORTED_CODES) {
            b.nak(ackAt, "codice di richiesta non gestito")
            b.note("Richiesta con codice '${code ?: '?'}' non gestita dal simulatore: NAK")
            return b.build(memory)
        }

        var mem = memory
        var flushedHeld = false
        mem.heldResultPayload?.let { held ->
            b.application(
                at = 0,
                payload = held,
                description = "Esito trattenuto (richiesta precedente) inviato dopo la richiesta '$code'",
                kind = OutKind.ORPHAN,
                ackWait = frameWait(),
            )
            b.note("LATE_RESULT_AFTER_NEXT_SEND: esito trattenuto inviato prima di servire '$code'")
            mem = mem.copy(heldResultPayload = null)
            flushedHeld = true
        }

        val fault = effectiveFault(config, code, flushedHeld)
        if (fault != SimFault.NONE) b.note("Guasto attivo su '$code': ${fault.name}")

        // ─── Guasti che precedono l'ACK ───
        when (fault) {
            SimFault.SILENT -> return b.build(mem)
            SimFault.DROP_AFTER_REQUEST -> {
                b.close(0, "DROP_AFTER_REQUEST: chiusura del socket appena letta la richiesta")
                return b.build(mem)
            }
            SimFault.NAK_ALWAYS -> {
                b.nak(ackAt, "NAK_ALWAYS")
                return b.build(mem)
            }
            SimFault.NAK_ONCE -> {
                if (mem.nakOncePayload != text) {
                    b.nak(ackAt, "NAK_ONCE: primo invio rifiutato")
                    return b.build(mem.copy(nakOncePayload = text))
                }
                b.note("NAK_ONCE: ritrasmissione ricevuta, richiesta servita")
                mem = mem.copy(nakOncePayload = null)
            }
            else -> Unit
        }

        // ─── ACK della richiesta ───
        when (fault) {
            SimFault.NO_ACK, SimFault.PROGRESS_NO_ACK, SimFault.BARE_ACK -> Unit
            SimFault.BARE_NAK -> b.bareNak(ackAt)
            else -> b.ack(ackAt)
        }
        if (fault == SimFault.DROP_AFTER_ACK) {
            b.close(ackAt, "DROP_AFTER_ACK: chiusura dopo l'ACK, prima dell'esito")
            return b.build(mem)
        }

        // ─── Comandi solo-ACK ───
        if (code == 'E' || code == 'R') {
            if (fault == SimFault.BARE_ACK) b.bareAck(ackAt)
            if (code == 'E') {
                val enabled = text.getOrNull(10) == '1'
                mem = mem.copy(ecrPrintingArmed = enabled)
                b.note(if (enabled) "Stampa su ECR armata per la prossima procedura" else "Stampa su ECR disarmata")
            } else if (text.getOrNull(10) == '1') {
                val service = text.getOrNull(11) == '1'
                val stored = if (service) mem.lastServiceReceipt else mem.lastFinancialReceipt
                val lines = stored?.drop(1) ?: listOf("RISTAMPA", NO_OPERATION_TEXT, RECEIPT_HEADER)
                b.receiptLines(ackAt, config, lines)
            }
            if (fault == SimFault.DROP_AFTER_RESULT) {
                b.close(ackAt, "DROP_AFTER_RESULT: chiusura subito dopo l'ACK")
                return b.build(mem)
            }
            // E prepara la procedura successiva, che l'ECR invia sulla STESSA connessione (§3.23 punto 6): chiudere
            // qui farebbe fallire ogni comando con la stampa su ECR attiva. La transazione è quella che segue.
            return b.build(mem, closeAfter = config.closeAfterTransaction && code != 'E')
        }

        // ─── Comandi con esito ───
        val progressAt = config.progressDelayMs.coerceAtLeast(0)
        if (fault == SimFault.NO_RESULT) {
            b.progress(progressAt, config)
            b.note("NO_RESULT: nessun esito per '$code'")
            return b.build(mem)
        }
        val baseResultAt = config.resultDelayMs.coerceAtLeast(0)
        val resultAt = if (code in "sTG") minOf(baseResultAt, FAST_RESULT_CAP_MS) else baseResultAt

        val outcome = compute(code, text, config, mem, now)
        mem = outcome.memory

        when {
            fault == SimFault.NO_ACK || fault == SimFault.BARE_NAK || fault == SimFault.BARE_ACK -> Unit
            // Il progress È il guasto (ACK implicito, §4.5): con un esito più rapido del progress PROGRESS_NO_ACK
            // diventerebbe in silenzio un NO_ACK, quindi il progress parte al più tardi insieme all'esito.
            fault == SimFault.PROGRESS_NO_ACK -> b.progress(minOf(progressAt, resultAt), config)
            // Un comando che risponde prima del progress (s, T, G veloci) non lo manda: niente frame inutili.
            progressAt < resultAt -> b.progress(progressAt, config)
        }

        if (fault == SimFault.LATE_RESULT_AFTER_NEXT_SEND) {
            b.note("LATE_RESULT_AFTER_NEXT_SEND: esito di '$code' trattenuto fino alla richiesta successiva")
            return b.build(mem.copy(heldResultPayload = outcome.payload))
        }

        val receipt = if (mem.ecrPrintingArmed && code in PRINTING_CODES && outcome.receipt != null) {
            mem = mem.copy(ecrPrintingArmed = false)
            outcome.receipt
        } else {
            null
        }
        if (receipt != null && !config.receiptAfterResult) b.receiptLines(resultAt, config, receipt)
        if (fault == SimFault.UNEXPECTED_FRAME_BEFORE_RESULT) {
            b.application(
                at = resultAt,
                payload = header(config, 'U') + "000000" + "005" + "HELLO" + "0".repeat(10),
                description = "Frame U stantio prima dell'esito (L§13.6)",
                kind = OutKind.ORPHAN,
                ackWait = frameWait(),
            )
        }

        val resultWait = if (fault == SimFault.DROP_AFTER_RESULT) null else resultWait(config, b)
        val resultDescription = describeResult(code, outcome.payload)
        if (fault == SimFault.BARE_ACK) {
            // 06 seguito SUBITO dall'esito nello stesso segmento, dopo ackDelayMs e senza progress.
            b.bareAckWithResult(ackAt, outcome.payload, resultDescription, resultWait)
        } else {
            b.application(
                at = resultAt,
                payload = outcome.payload,
                description = resultDescription,
                kind = OutKind.RESULT,
                ackWait = resultWait,
                corruptLrc = fault == SimFault.BAD_LRC_RESULT_ONCE,
            )
        }
        if (fault == SimFault.DROP_AFTER_RESULT) {
            b.close(resultAt, "DROP_AFTER_RESULT: chiusura subito dopo l'esito, senza attendere l'ACK")
            return b.build(mem)
        }
        if (receipt != null && config.receiptAfterResult) b.receiptLines(resultAt, config, receipt)
        return b.build(mem, closeAfter = config.closeAfterTransaction)
    }

    /**
     * Passi da eseguire appena una connessione viene servita: con STALE_RESULT_ON_CONNECT l'ultimo esito (o un `E`
     * KO "NESSUNA OPERAZIONE" se non ce n'è) arriva prima di qualsiasi richiesta, senza attendere ACK: l'ECR deve
     * scartarlo nel silenzio pre-invio (`preSendQuietMs`) e segnalare un possibile esito perso.
     */
    fun onConnect(config: SimulatorConfig, memory: SimMemory): List<SimStep> {
        if (config.fault != SimFault.STALE_RESULT_ON_CONNECT) return emptyList()
        val b = PlanBuilder(config)
        b.application(
            at = 0,
            payload = memory.lastResultPayload ?: noOperationPayload(config),
            description = "STALE_RESULT_ON_CONNECT: esito stantio inviato alla connessione",
            kind = OutKind.ORPHAN,
            ackWait = null,
        )
        return b.steps
    }

    /** Evento di log di un frame inviato: PAN mascherato (M9), LRC originale. */
    fun frameOut(at: Long, frame: OutFrame): SimEvent.FrameOut =
        SimEvent.FrameOut(at = at, bytes = PanMasker.maskFrame(frame.bytes), description = frame.description)

    /**
     * Esito "nessuna operazione" (layout KO di E4, A§9.8): risposta a G senza un esito precedente e frame stantio di
     * ripiego. Non tocca contatori né totali.
     */
    fun noOperationPayload(config: SimulatorConfig): String =
        header(config, 'E') + "01" + leftText(NO_OPERATION_TEXT, 24) + "0".repeat(35)

    // ─── Guasti ─────────────────────────────────────────────────────────────────

    /**
     * Guasto che vale per QUESTA richiesta. STALE_RESULT_ON_CONNECT agisce solo alla connessione; dopo aver svuotato
     * un esito trattenuto la richiesta corrente è servita normalmente ("poi serve quella normalmente", §4.2).
     */
    private fun effectiveFault(config: SimulatorConfig, code: Char, flushedHeld: Boolean): SimFault {
        val fault = config.fault
        return when {
            fault == SimFault.NONE || fault == SimFault.STALE_RESULT_ON_CONNECT -> SimFault.NONE
            config.faultOnlyFinancial && code !in FAULT_CODES -> SimFault.NONE
            fault == SimFault.LATE_RESULT_AFTER_NEXT_SEND && flushedHeld -> SimFault.NONE
            else -> fault
        }
    }

    private fun frameWait(): AckWait = AckWait(waitMs = ECR_REPLY_WAIT_MS, validAck = null, maxResendsWithoutValidAck = 0)

    /** Descrizione per il log: codice ed esito (pos 10-12), lunghezza; per `s` lo stato non ha un esito. */
    private fun describeResult(code: Char, payload: String): String {
        val reply = payload.getOrNull(9) ?: '?'
        val result = payload.substring(10, minOf(12, payload.length))
        return when (code) {
            's' -> "Stato '$reply' (${payload.length} B)"
            'G' -> "Ultimo esito '$reply' $result per G (${payload.length} B)"
            else -> "Esito '$reply' $result (${payload.length} B)"
        }
    }

    /** Attesa dopo l'esito: con retransmitResultIfNoValidAck vale solo l'ACK di ackRule entro resultRetransmitDelayMs. */
    private fun resultWait(config: SimulatorConfig, b: PlanBuilder): AckWait =
        if (config.retransmitResultIfNoValidAck) {
            AckWait(
                waitMs = config.resultRetransmitDelayMs.coerceAtLeast(1),
                validAck = b.ackBytes(),
                maxResendsWithoutValidAck = config.resultRetransmitMax.coerceAtLeast(0),
            )
        } else {
            frameWait()
        }

    // ─── Esiti ──────────────────────────────────────────────────────────────────

    /** Esito calcolato: payload, stato aggiornato e scontrino (4 righe) se la procedura ne produce uno. */
    private class Computed(val payload: String, val memory: SimMemory, val receipt: List<String>?)

    private fun compute(code: Char, text: String, config: SimulatorConfig, memory: SimMemory, now: LocalDateTime): Computed =
        when (code) {
            's' -> Computed(statusPayload(config, now), memory, null)
            'T' -> {
                val total = memory.posTotalCents
                val payload = header(config, 'T') + "00" + digits(total, 16) + "000000"
                val receipt = receipt("TOTALI", null, total, "ESITO OK")
                Computed(payload, memory.copy(lastServiceReceipt = receipt), receipt)
            }
            'C' -> {
                val total = memory.posTotalCents
                val payload = header(config, 'C') + "00" + digits(total, 16) + digits(total, 16)
                val receipt = receipt("CHIUSURA CONTABILE", null, total, "ESITO OK")
                val after = memory.copy(posTotalCents = 0, lastApprovedAmountCents = 0, lastServiceReceipt = receipt)
                Computed(payload, after, receipt)
            }
            'G' -> Computed(memory.lastResultPayload ?: noOperationPayload(config), memory, null)
            else -> transaction(code, text, config, memory, now)
        }

    /** P X S p i c H: decisione, contatori, totali, esito ricordato per G e scontrino. */
    private fun transaction(code: Char, text: String, config: SimulatorConfig, memory: SimMemory, now: LocalDateTime): Computed {
        check(code in TRANSACTION_CODES) { "codice di transazione inatteso: $code" }
        val decision = config.decision
        val counted = decision != SimDecision.CARD_NOT_PRESENT
        val stan = if (counted) next6(memory.stan) else memory.stan
        val online = if (counted) next6(memory.onlineId) else memory.onlineId
        val requested = requestedAmount(code, text)
        val f = Fields(config = config, now = now, stan = stan, online = online, approved = decision == SimDecision.APPROVE)

        var preAuthSequence = memory.preAuthSequence
        var preAuthCode = memory.lastPreAuthCode
        val payload = when (code) {
            'P', 'H' -> paymentBase(f, 'E', decision, koActionAt37 = code == 'H')
            'X' -> paymentBase(f, 'E', decision) + ACTION_OK +
                (if (decision == SimDecision.APPROVE) digits(requested, 8) else "0".repeat(8)) + "0".repeat(10)
            'S' -> reversal(f, decision)
            'p' -> {
                if (decision == SimDecision.APPROVE) {
                    preAuthSequence += 1
                    preAuthCode = digits(preAuthSequence.toLong(), 9)
                }
                preAuth(f, decision, requested, preAuthCode ?: "0".repeat(9))
            }
            'i' -> incremental(f, decision)
            'c' -> paymentBase(f, 'c', decision) + (if (decision == SimDecision.DECLINE) ACTION_KO else ACTION_OK)
            else -> error("codice di transazione inatteso: $code")
        }

        var total = memory.posTotalCents
        var lastApproved = memory.lastApprovedAmountCents
        if (decision == SimDecision.APPROVE) {
            when (code) {
                'P', 'X', 'c' -> {
                    total += requested
                    lastApproved = requested
                }
                'S' -> {
                    total -= lastApproved
                    lastApproved = 0
                }
            }
        }
        val receiptAmount = if (code == 'S') memory.lastApprovedAmountCents else requested
        val receipt = receipt(
            title = TITLES.getValue(code),
            pan = if (decision == SimDecision.APPROVE) panDigits(config) else null,
            amountCents = receiptAmount,
            outcome = when (decision) {
                SimDecision.APPROVE -> "ESITO APPROVATO"
                SimDecision.DECLINE -> "ESITO NEGATO"
                SimDecision.CARD_NOT_PRESENT -> "ESITO CARTA ASSENTE"
            },
        )
        val after = memory.copy(
            stan = stan,
            onlineId = online,
            posTotalCents = total,
            lastApprovedAmountCents = lastApproved,
            preAuthSequence = preAuthSequence,
            lastPreAuthCode = preAuthCode,
            lastResultPayload = payload,
            lastFinancialReceipt = receipt,
        )
        return Computed(payload, after, receipt)
    }

    /** Dati comuni a un esito di transazione. */
    private class Fields(val config: SimulatorConfig, val now: LocalDateTime, val stan: Int, val online: Int, val approved: Boolean) {
        val stanText: String get() = digits(stan.toLong(), 6)
        val onlineText: String get() = digits(online.toLong(), 6)
        /** Codice autorizzazione "SIM" + 3 cifre (dallo STAN): riconoscibile come simulato a colpo d'occhio. */
        val authCode: String get() = "SIM" + digits((stan % 1000).toLong(), 3)
        val hostDateTime: String get() = hostDateTime(now)
        val pan: String get() = panField(config)
    }

    /**
     * Layout `E` base di 71 B (A§4.5.1) con il codice [reply] in pos 10:
     * - APPROVE: "00" + PAN 19 + entry 3 + auth 6 + data/ora host 7 + tipo carta + acquirer 11 + STAN + online;
     * - DECLINE: "01" + descrizione 24 a sinistra + riservato 11 (H: action "100" in 37/3) + tipo carta + acquirer + STAN + online;
     * - CARD_NOT_PRESENT: layout positivo con esito "05", PAN e auth a zero, STAN e online a zero (nessuna transazione).
     */
    private fun paymentBase(f: Fields, reply: Char, decision: SimDecision, koActionAt37: Boolean = false): String {
        val head = header(f.config, reply)
        return when (decision) {
            SimDecision.APPROVE -> head + "00" + f.pan + ENTRY_MODE + f.authCode + f.hostDateTime +
                CARD_TYPE_CREDIT + ACQUIRER + f.stanText + f.onlineText
            SimDecision.DECLINE -> head + "01" + leftText(f.config.declineText, 24) +
                (if (koActionAt37) ACTION_KO + "0".repeat(8) else "0".repeat(11)) +
                CARD_TYPE_CREDIT + ACQUIRER + f.stanText + f.onlineText
            SimDecision.CARD_NOT_PRESENT -> head + "05" + "0".repeat(19) + "   " + "000000" + f.hostDateTime +
                "0" + ACQUIRER + "000000" + "000000"
        }
    }

    /** `E` di storno, 84 B (A§4.5.2): pos 35-40 riservate (nessun codice autorizzazione), action 72/3, riservato 75-84. */
    private fun reversal(f: Fields, decision: SimDecision): String {
        val base = when (decision) {
            SimDecision.APPROVE -> header(f.config, 'E') + "00" + f.pan + ENTRY_MODE + "000000" + f.hostDateTime +
                CARD_TYPE_CREDIT + ACQUIRER + f.stanText + f.onlineText
            else -> paymentBase(f, 'E', decision)
        }
        return base + ACTION_OK + "0".repeat(10)
    }

    /**
     * `e` di 106 B (A§4.5.3). OK: PAN, entry, auth, importo 41/8, codice preautorizzazione 49/9, action 58/3,
     * data/ora 61/7, riservato 68-71, acquirer 72/11, STAN 83/6, online 89/6, riservato 95-106.
     * KO: descrizione 13/24, action "100" 37/3, tipo carta 48/1, acquirer/STAN/online nelle stesse posizioni.
     */
    private fun preAuth(f: Fields, decision: SimDecision, amountCents: Long, preAuthCode: String): String {
        val head = header(f.config, 'e')
        val tail = ACQUIRER + f.stanText + f.onlineText + "0".repeat(12)
        return when (decision) {
            SimDecision.APPROVE -> head + "00" + f.pan + ENTRY_MODE + f.authCode + digits(amountCents, 8) +
                preAuthCode + ACTION_OK + f.hostDateTime + "000" + "0" + tail
            SimDecision.DECLINE -> head + "01" + leftText(f.config.declineText, 24) + ACTION_KO + "0".repeat(8) +
                CARD_TYPE_CREDIT + "0".repeat(23) + tail
            SimDecision.CARD_NOT_PRESENT -> head + "05" + "0".repeat(19) + "   " + "000000" + "0".repeat(8) +
                "0".repeat(9) + ACTION_OK + f.hostDateTime + "000" + "0" + ACQUIRER + "000000" + "000000" + "0".repeat(12)
        }
    }

    /**
     * `i` di 75 B (A§4.5.4), layout proprio senza ramo KO: esito, PAN, entry, acquirer 35/11, auth 46/6, STAN 52/6,
     * online 58/6, data/ora 64/7, action 71/3, riservato 74-75. Il codice preautorizzazione ricevuto è ignorato.
     */
    private fun incremental(f: Fields, decision: SimDecision): String {
        val head = header(f.config, 'i')
        return when (decision) {
            SimDecision.APPROVE -> head + "00" + f.pan + ENTRY_MODE + ACQUIRER + f.authCode + f.stanText +
                f.onlineText + f.hostDateTime + ACTION_OK + "00"
            SimDecision.DECLINE -> head + "01" + f.pan + ENTRY_MODE + ACQUIRER + "000000" + f.stanText +
                f.onlineText + f.hostDateTime + ACTION_KO + "00"
            SimDecision.CARD_NOT_PRESENT -> head + "05" + "0".repeat(19) + "   " + ACQUIRER + "000000" + "000000" +
                "000000" + f.hostDateTime + ACTION_OK + "00"
        }
    }

    /** `s` (A§4.5.6): TID `0s` + 10 zeri + DDMMYYhhmm + stato '2' (operativo) + release a multipli di 8. */
    private fun statusPayload(config: SimulatorConfig, now: LocalDateTime): String {
        val release = ascii(config.softwareRelease)
        val width = maxOf(8, (release.length + 7) / 8 * 8)
        val dateTime = two(now.dayOfMonth) + two(now.monthValue) + two(now.year % 100) + two(now.hour) + two(now.minute)
        return header(config, 's') + "0".repeat(10) + dateTime + "2" + release.padEnd(width, ' ')
    }

    // ─── Scontrino ──────────────────────────────────────────────────────────────

    private val TITLES: Map<Char, String> = mapOf(
        'P' to "PAGAMENTO",
        'X' to "PAGAMENTO",
        'S' to "STORNO",
        'p' to "PREAUTORIZZAZIONE",
        'i' to "INTEGRAZIONE PREAUT.",
        'c' to "CHIUSURA PREAUT.",
        'H' to "VERIFICA CARTA",
    )

    /**
     * Le 4 righe dello scontrino (§4.4). Con un'operazione approvata la riga del titolo porta il PAN in chiaro, come
     * fanno alcuni terminali sulle righe S: l'ECR deve mascherarlo (M9), e senza un PAN il test non lo verificherebbe.
     */
    private fun receipt(title: String, pan: String?, amountCents: Long, outcome: String): List<String> = listOf(
        RECEIPT_HEADER,
        if (pan != null) "$title CARTA $pan" else title,
        "IMPORTO EUR " + euro(amountCents),
        outcome,
    )

    // ─── Supporto ───────────────────────────────────────────────────────────────

    /** TID (8 cifre, config.terminalId) + '0' + codice. */
    private fun header(config: SimulatorConfig, code: Char): String = terminalId(config) + "0" + code

    private fun terminalId(config: SimulatorConfig): String {
        val digitsOnly = config.terminalId.filter { it in '0'..'9' }
        return digitsOnly.take(8).padStart(8, '0')
    }

    /** PAN in 19 cifre con riempimento '0' a sinistra (campo 13/19). */
    private fun panField(config: SimulatorConfig): String {
        val digitsOnly = config.pan.filter { it in '0'..'9' }
        return digitsOnly.takeLast(19).padStart(19, '0')
    }

    /** PAN senza gli zeri di riempimento, come lo stamperebbe un terminale. */
    private fun panDigits(config: SimulatorConfig): String = panField(config).trimStart('0').ifEmpty { "0" }

    /** Importo richiesto (pos 24/8) di P X p i c; 0 se assente o non numerico. */
    private fun requestedAmount(code: Char, text: String): Long {
        if (code !in "PXpic" || text.length < 31) return 0
        val raw = text.substring(23, 31)
        return if (raw.all { it in '0'..'9' }) raw.toLong() else 0
    }

    /** Data/ora host `DDDHHMM`: giorno dell'anno, ora, minuti (A§4.5.1). */
    private fun hostDateTime(now: LocalDateTime): String =
        now.dayOfYear.toString().padStart(3, '0') + two(now.hour) + two(now.minute)

    private fun two(value: Int): String = value.toString().padStart(2, '0')

    /** Contatore a 6 cifre: dopo 999999 riparte da 1. */
    private fun next6(value: Int): Int = if (value >= 999_999) 1 else value + 1

    /** Cifre a larghezza fissa (valori negativi trattati come 0, eccedenza troncata a sinistra). */
    private fun digits(value: Long, width: Int): String =
        value.coerceAtLeast(0).toString().padStart(width, '0').takeLast(width)

    /** Testo ASCII stampabile allineato a sinistra su [width] (troncato, riempito di spazi). */
    private fun leftText(value: String, width: Int): String = ascii(value).take(width).padEnd(width, ' ')

    /** Solo 0x20..0x7E: un carattere di controllo nel testo spezzerebbe il frame. */
    private fun ascii(value: String): String = buildString(value.length) {
        for (c in value) append(if (c.code in 0x20..0x7E) c else '?')
    }

    private fun euro(cents: Long): String {
        val abs = cents.coerceAtLeast(0)
        return "${abs / 100}," + (abs % 100).toString().padStart(2, '0')
    }

    // ─── Costruttore del piano ──────────────────────────────────────────────────

    /** Accumula passi e note; codifica i frame con l'LRC e la regola dei controlli del simulatore. */
    private class PlanBuilder(config: SimulatorConfig) {
        private val codec = FrameCodec(appMode = config.appLrcMode, controlRule = config.ackRule)
        private val ruleName = config.ackRule.name
        val steps = mutableListOf<SimStep>()
        private val notes = mutableListOf<String>()

        fun ackBytes(): ByteArray = codec.encodeControl(ControlChars.ACK)

        fun note(text: String) {
            notes += text
        }

        fun ack(at: Long) {
            steps += SimStep.Send(at, listOf(OutFrame(ackBytes(), "ACK ($ruleName)", OutKind.ACK)))
        }

        fun nak(at: Long, why: String) {
            steps += SimStep.Send(at, listOf(OutFrame(codec.encodeControl(ControlChars.NAK), "NAK ($ruleName): $why", OutKind.NAK)))
        }

        fun bareNak(at: Long) {
            steps += SimStep.Send(at, listOf(OutFrame(byteArrayOf(ControlChars.NAK.toByte()), "BARE_NAK: 15 nudo, senza ETX né LRC", OutKind.BARE_NAK)))
        }

        fun bareAck(at: Long) {
            steps += SimStep.Send(at, listOf(bareAckFrame()))
        }

        fun bareAckWithResult(at: Long, payload: String, description: String, ackWait: AckWait?) {
            val result = OutFrame(encode(payload), description, OutKind.RESULT)
            steps += SimStep.Send(at, listOf(bareAckFrame(), result), ackWait = ackWait, resend = result)
        }

        fun progress(at: Long, config: SimulatorConfig) {
            val text = ascii(config.progressText)
            val bytes = byteArrayOf(ControlChars.SOH.toByte()) + text.toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(ControlChars.EOT.toByte())
            steps += SimStep.Send(at, listOf(OutFrame(bytes, "Progress \"$text\"", OutKind.PROGRESS)))
        }

        /** Frame applicativo; con [corruptLrc] il primo invio ha l'LRC sbagliato in ogni modalità e il reinvio è corretto. */
        fun application(at: Long, payload: String, description: String, kind: OutKind, ackWait: AckWait?, corruptLrc: Boolean = false) {
            val correct = OutFrame(encode(payload), description, kind)
            val first = if (corruptLrc) {
                val bytes = correct.bytes.copyOf()
                // XOR 0xFF: le 4 modalità differiscono solo nei 2 bit bassi, quindi il valore non è valido in nessuna.
                bytes[bytes.size - 1] = ((bytes[bytes.size - 1].toInt() and 0xFF) xor 0xFF).toByte()
                OutFrame(bytes, "$description con LRC corrotto (BAD_LRC_RESULT_ONCE)", kind)
            } else {
                correct
            }
            steps += SimStep.Send(at, listOf(first), ackWait = ackWait, resend = correct)
        }

        /** Righe S una per frame, l'ultima con la sentinella; ognuna attende l'ACK dell'ECR. */
        fun receiptLines(at: Long, config: SimulatorConfig, lines: List<String>) {
            lines.forEachIndexed { index, line ->
                val last = index == lines.lastIndex
                val payload = header(config, 'S') + ascii(line) + if (last) RECEIPT_END else ""
                application(
                    at = at,
                    payload = payload,
                    description = "Riga S ${index + 1}/${lines.size}" + if (last) " (fine scontrino)" else "",
                    kind = OutKind.RECEIPT,
                    ackWait = frameWait(),
                )
            }
        }

        fun close(at: Long, reason: String) {
            steps += SimStep.Close(at, reason)
        }

        fun build(memory: SimMemory, closeAfter: Boolean = false): SimPlan =
            SimPlan(memory = memory, steps = steps.toList(), notes = notes.toList(), closeAfter = closeAfter)

        private fun bareAckFrame() = OutFrame(byteArrayOf(ControlChars.ACK.toByte()), "BARE_ACK: 06 nudo, senza ETX né LRC", OutKind.BARE_ACK)

        private fun encode(payload: String): ByteArray = codec.encodeApplication(payload.toByteArray(Charsets.ISO_8859_1))
    }
}
