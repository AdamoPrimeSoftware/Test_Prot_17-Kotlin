package it.prime.protocollo17.domain.model.journal

import it.prime.protocollo17.ecr17.model.CommandCode
import kotlinx.serialization.Serializable

// Journal persistente dei tentativi finanziari (§5.3, U7). Un tentativo è una sequenza append-only di record
// JSONL; lo stato corrente (JournalEntry) è il fold di tutti i suoi record, così una riga persa o corrotta
// non fa sparire un tentativo bloccante (M6).

/** Fase di un tentativo. Tabella vincolante in §5.3.1 (chi la scrive e se chiude il tentativo). */
@Serializable
enum class JournalPhase { CREATED, SENDING, ACKED, GO_AHEAD, RECEIVED, EVIDENCE, RESULT, NOT_SENT, REFUSED, UNKNOWN, RESOLVED }

/** Come l'operatore ha chiuso un tentativo sconosciuto o da verificare. G non è mai automatico (U7). */
@Serializable
enum class ResolutionKind {
    CONFIRMED_BY_G,     // l'esito di G appartiene al tentativo (si registra il suo verdict)
    NOT_EXECUTED,       // G mostra un esito precedente: il tentativo non è stato eseguito
    REVIEWED,           // esito "da verificare" controllato (G, Totali o scontrino): nessun doppio addebito, o già stornato
    DISMISSED,          // chiuso dall'operatore dopo verifica esterna (scontrino, terminale)
}

/** Una riga JSONL. I campi null non vengono scritti (explicitNulls = false). */
@Serializable
data class JournalRecord(
    val attemptId: String,
    val seq: Int,
    val at: Long,
    val phase: JournalPhase,
    // CREATED
    val command: String? = null,
    val amountCents: Long? = null,
    val requestSummary: String? = null,
    val profile: String? = null,
    val endpoint: String? = null,
    val terminalId: String? = null,
    val cashRegisterId: String? = null,
    val optionsSummary: String? = null,
    // SENDING / ACKED / GO_AHEAD / RECEIVED
    val sends: Int? = null,
    // RECEIVED / EVIDENCE / RESULT
    val rawResult: String? = null,        // payload ISO-8859-1, PAN mascherato
    val evidenceNote: String? = null,     // EVIDENCE: "orfano durante 's' delle 09:41:02"
    // RESULT
    val outcome: String? = null,          // OutcomeClass.name
    val replyCode: String? = null,
    val resultCode: String? = null,
    val verdict: String? = null,
    val stan: String? = null,
    val onlineId: String? = null,
    val authCode: String? = null,
    val hostDateTime: String? = null,
    val ackMode: String? = null,
    val naksReceived: Int? = null,
    val timeoutRetransmissions: Int? = null,
    val needsReview: Boolean? = null,     // true: EARLY_RESULT o POSSIBLE_DUPLICATE su un comando RECORD_AND_BLOCK
    val reviewReason: String? = null,     // codici dei warning, es. "POSSIBLE_DUPLICATE"
    // NOT_SENT / REFUSED / UNKNOWN
    val failure: String? = null,          // Ecr17Failure.message
    val failurePhase: String? = null,     // ExchangePhase.name
    // RESOLVED
    val resolution: ResolutionKind? = null,
    val note: String? = null,
    val gVerdict: String? = null,
    val gRawResult: String? = null,
    val gStan: String? = null,
    val gOnlineId: String? = null,
    val gHostDateTime: String? = null,
)

/**
 * Vista aggregata di un tentativo: fold di TUTTI i suoi record in ordine di seq, anche se il CREATED manca
 * (riga corrotta): in quel caso command = null e i campi descrittivi sono vuoti.
 */
data class JournalEntry(
    val attemptId: String,
    /** null: record CREATED mancante o illeggibile ⇒ "tentativo con dati incompleti", trattato come RECORD_AND_BLOCK. */
    val command: CommandCode?,
    val createdAt: Long,
    val amountCents: Long?,
    val requestSummary: String,
    val endpoint: String,
    val profile: String,
    /** Fase dell'ultimo record diverso da EVIDENCE (forzata a UNKNOWN in memoria se un append è fallito). */
    val phase: JournalPhase,
    val lastUpdate: Long,
    val sends: Int,
    val records: List<JournalRecord>,
    /** false: l'ultimo append di questo tentativo è fallito; la fase in memoria è stata forzata a UNKNOWN (§5.3.3). */
    val persisted: Boolean = true,
) {
    val incomplete: Boolean get() = command == null
    val resultRecord: JournalRecord? get() = records.lastOrNull { it.phase == JournalPhase.RESULT }
    val receivedRecord: JournalRecord? get() = records.lastOrNull { it.phase == JournalPhase.RECEIVED }
    val evidence: List<JournalRecord> get() = records.filter { it.phase == JournalPhase.EVIDENCE }
    val failureRecord: JournalRecord? get() = records.lastOrNull { it.phase in setOf(JournalPhase.NOT_SENT, JournalPhase.REFUSED, JournalPhase.UNKNOWN) }
    val resolutionRecord: JournalRecord? get() = records.lastOrNull { it.phase == JournalPhase.RESOLVED }
    val isInFlight: Boolean get() = phase in setOf(JournalPhase.CREATED, JournalPhase.SENDING, JournalPhase.ACKED, JournalPhase.GO_AHEAD, JournalPhase.RECEIVED)
    val needsReview: Boolean get() = phase == JournalPhase.RESULT && resultRecord?.needsReview == true
    val isUnresolved: Boolean get() = phase == JournalPhase.UNKNOWN || needsReview

    /** Blocca TUTTI i comandi journaled; il blocco per famiglia di H/C è in PaymentBlockPolicy (§5.8). */
    val blocksPayments: Boolean get() = isUnresolved && (command == null || command.movesMoney)
}

/** Da dove viene un esito già visto (GAssessment, §5.3.4). */
enum class KnownResultSource { RESULT, RECEIVED, G_RESOLUTION, EVIDENCE }

/** Un esito già visto (per GAssessment, §5.3.4). raw = payload mascherato; campi null se non noti (RECEIVED, EVIDENCE). */
data class KnownResult(
    val attemptId: String,
    val source: KnownResultSource,
    val raw: String,
    val stan: String?,
    val onlineId: String?,
    val hostDateTime: String?,
    val at: Long,
)
