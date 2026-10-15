package it.prime.protocollo17.ecr17.model

import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.event.ProtocolWarning
import it.prime.protocollo17.ecr17.event.REVIEW_WARNINGS
import it.prime.protocollo17.ecr17.profile.TerminalProfileId

/** Fasi di uno scambio, nell'ordine in cui possono comparire (RunningCommand.requestSent usa l'ordinale). */
enum class ExchangePhase {
    PREPARING, CONNECTING, PRINTING_SETUP, SENDING, AWAITING_ACK, IMPLICIT_GO_AHEAD,
    ACKNOWLEDGED, AWAITING_RESULT, RESULT_RECEIVED, DRAINING, COMPLETED
}
// AWAITING_RESULT è emessa a ogni ingresso nel punto 4 di §3.21 (dopo ACKNOWLEDGED, IMPLICIT_GO_AHEAD o
// direttamente da AWAITING_ACK con RESULT_BEFORE_ACK). Non ha un record di journal (journalPhaseOf ⇒ null).

/** Come si è chiuso l'handshake del frame principale. */
enum class AckMode {
    EXPLICIT,               // ACK con ETX (LRC WIRE, SAME_AS_APP o MISMATCH tollerato)
    BARE,                   // ACK senza ETX o senza LRC
    PROGRESS_BEFORE_ACK,    // progress o riga S prima dell'ACK: ACK implicito, il terminale sta elaborando
    RESULT_BEFORE_ACK,      // esito atteso prima dell'ACK (con un comando finanziario: warning EARLY_RESULT)
    IMPLICIT_GO_AHEAD,      // nessun segnale entro ackTimeoutMs, si attende l'esito senza reinviare
}

/** resultRaw: solo con phase = RESULT_RECEIVED, payload dell'esito ISO-8859-1 con PAN mascherato. */
data class PhaseChange(
    val exchangeId: Long, val command: CommandCode, val phase: ExchangePhase, val at: Long, val sends: Int,
    val resultRaw: String? = null,
)

/** Fase con il suo istante (epoch ms), per la timeline dello scambio. */
data class PhaseStamp(val phase: ExchangePhase, val at: Long)

/**
 * Tutto ciò che è successo sul filo durante un comando, allegato a ogni esito che abbia raggiunto la sessione.
 *
 * Serve a due scopi: mostrare all'operatore cosa è accaduto (invii, NAK, ACK tardivi, avvisi) e decidere la
 * mappatura money-safety nel client (§3.23), che si basa su questi contatori e non sul solo risultato.
 */
data class ExchangeTrace(
    val exchangeId: Long,
    val command: CommandCode,
    val endpoint: Endpoint,
    val profile: TerminalProfileId,
    val optionsSummary: String,
    val sends: Int,                       // invii del frame principale
    /** true dal primo tentativo di transport.send del frame principale (anche fallito); mai azzerato (M10). */
    val anyMainFrameWriteAttempted: Boolean,
    /** NAK validi (Control NAK) ricevuti per il frame principale, compresi i NAK tardivi. */
    val naksReceived: Int,
    /** Reinvii causati da ACK mancante (solo RETRANSMIT o comandi non finanziari). */
    val timeoutRetransmissions: Int,
    val ackMode: AckMode?,
    val lateAck: Boolean,
    val phases: List<PhaseStamp>,
    val receipt: List<ReceiptLine>,
    val warnings: List<ProtocolWarning>,
    val resultRetransmissions: Int,
    val replayedAfterReconnect: Boolean,
    val printingSetupSent: Boolean,
    val startedAt: Long,
    val finishedAt: Long,
)

/**
 * Esito di un comando. Nessuna eccezione per i casi attesi.
 *
 * La distinzione tra NotSent, Refused, Failed e Unknown è il cuore della money-safety: dice se il terminale
 * può aver eseguito il comando. Solo Unknown di un comando journaled blocca i pagamenti (U7).
 */
sealed interface CommandOutcome {
    val command: CommandCode
    val trace: ExchangeTrace?

    /** Il terminale ha risposto con un codice atteso. Approvato/rifiutato è in response (HasResult.verdict). */
    data class Answered(override val command: CommandCode, val response: Ecr17Response, override val trace: ExchangeTrace) : CommandOutcome {
        /** Warning di REVIEW_WARNINGS presenti nella trace (EARLY_RESULT, POSSIBLE_DUPLICATE). */
        val reviewWarnings: List<ProtocolWarning> get() = trace.warnings.filter { it.code in REVIEW_WARNINGS }
        /** Esito "da verificare": blocca i pagamenti finché l'operatore non lo chiude (M6, §5.3). */
        val needsReview: Boolean get() = command.movesMoney && reviewWarnings.isNotEmpty()
    }

    /** Comando solo-ACK (E, R) confermato. */
    data class Acknowledged(override val command: CommandCode, override val trace: ExchangeTrace) : CommandOutcome

    /** Nessun tentativo di scrittura del frame principale: esito certo, nessun effetto sul terminale. */
    data class NotSent(override val command: CommandCode, val failure: Ecr17Failure, override val trace: ExchangeTrace?) : CommandOutcome

    /**
     * OGNI invio del frame principale ha ricevuto un NAK valido e nessun reinvio è avvenuto per ACK mancante
     * (trace.timeoutRetransmissions == 0 && trace.naksReceived >= trace.sends): esito certo, non eseguito.
     * Con invii misti (un invio senza risposta seguito da NAK) l'esito è Unknown (§3.23).
     */
    data class Refused(override val command: CommandCode, val failure: Ecr17Failure.Nak, override val trace: ExchangeTrace) : CommandOutcome

    /** Comando NON journaled inviato senza risposta (es. timeout di s): nessun rischio di denaro. trace null solo se sintetizzato dal servizio (§6.4). */
    data class Failed(override val command: CommandCode, val failure: Ecr17Failure, val phase: ExchangePhase, override val trace: ExchangeTrace?) : CommandOutcome

    /** Comando journaled inviato senza esito: ESITO SCONOSCIUTO (U7). trace null solo se sintetizzato dal servizio (§6.4). */
    data class Unknown(override val command: CommandCode, val failure: Ecr17Failure, val phase: ExchangePhase, override val trace: ExchangeTrace?) : CommandOutcome {
        val blocksPayments: Boolean get() = command.journalPolicy == JournalPolicy.RECORD_AND_BLOCK
    }
}

/** Classe sintetica di un esito: una parola per log traffico, journal (`outcome`) ed eventi. */
enum class OutcomeClass { APPROVED, DECLINED, ANSWERED_OTHER, ACKNOWLEDGED, NOT_SENT, REFUSED, FAILED, UNKNOWN }

/** APPROVED/DECLINED solo per HasResult con verdict APPROVED/DECLINED; altri verdict o risposte senza esito ⇒ ANSWERED_OTHER. */
fun CommandOutcome.outcomeClass(): OutcomeClass = when (this) {
    is CommandOutcome.Answered -> when ((response as? HasResult)?.verdict) {
        Verdict.APPROVED -> OutcomeClass.APPROVED
        Verdict.DECLINED -> OutcomeClass.DECLINED
        else -> OutcomeClass.ANSWERED_OTHER
    }
    is CommandOutcome.Acknowledged -> OutcomeClass.ACKNOWLEDGED
    is CommandOutcome.NotSent -> OutcomeClass.NOT_SENT
    is CommandOutcome.Refused -> OutcomeClass.REFUSED
    is CommandOutcome.Failed -> OutcomeClass.FAILED
    is CommandOutcome.Unknown -> OutcomeClass.UNKNOWN
}

/** Risultato di "Test connessione": apertura (e chiusura in PER_COMMAND) misurata, senza inviare comandi. */
data class ConnectionTestResult(val endpoint: Endpoint, val ok: Boolean, val millis: Long, val failure: Ecr17Failure?)
