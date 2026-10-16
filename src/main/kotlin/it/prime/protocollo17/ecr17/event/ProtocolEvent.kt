package it.prime.protocollo17.ecr17.event

import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.LinkState
import it.prime.protocollo17.ecr17.model.OutcomeClass
import it.prime.protocollo17.ecr17.model.PhaseChange
import it.prime.protocollo17.ecr17.model.ReceiptLine
import it.prime.protocollo17.ecr17.profile.TerminalProfileId
import it.prime.protocollo17.ecr17.wire.DiscardReason

// `ecr17` non usa slf4j (§2.10): tutta la diagnostica esce come ProtocolEvent e il data layer la scrive su
// file. Così l'estrazione futura del modulo non trascina dipendenze di logging.

/** Verso di un frame rispetto all'ECR. */
enum class FrameDirection { TX, RX }

/** Tipo di frame completo riportato nel log traffico. */
enum class FrameKind { APPLICATION, ACK, NAK, PROGRESS }

/** Avvisi del protocollo: nessuno interrompe lo scambio, ognuno spiega un'anomalia vista sul filo. */
enum class WarningCode {
    CONTROL_LRC_MISMATCH,   // ACK con LRC di nessuna convenzione (tollerato)
    BARE_CONTROL,           // ACK senza ETX o senza LRC
    AMBIGUOUS_NAK,          // NAK senza ETX/LRC o con LRC di nessuna convenzione: ignorato, nessun reinvio
    APP_LRC_OTHER_MODE,     // frame applicativo valido solo in un'altra LrcMode (accettato)
    APP_LRC_INVALID,        // frame applicativo con LRC errato (o valido solo in un'altra modalità per un esito "rigido"): NAK inviato
    IMPLICIT_GO_AHEAD,      // ACK non ricevuto: si attende l'esito senza reinviare
    LATE_ACK,               // ACK arrivato dopo il go-ahead
    SPURIOUS_CONTROL,       // ACK/NAK durante l'attesa dell'esito, ignorato
    UNEXPECTED_REPLY,       // codice non atteso per il comando (orfano, ACK inviato, mai esito)
    STALE_BYTES,            // byte trovati prima dell'invio (scartati)
    POSSIBLE_LOST_RESULT,   // un esito (E/V/e/i/c/v) non attribuibile: prima dell'invio, durante l'attesa o nel drain; payload mascherato allegato
    EARLY_RESULT,           // esito atteso prima di ACK/progress per un comando finanziario: "da verificare" (revisione)
    POSSIBLE_DUPLICATE,     // esito ricevuto dopo almeno un reinvio per ACK mancante: possibile doppio addebito (revisione)
    RESULT_RETRANSMITTED,   // esito identico ricevuto di nuovo nel drain: il nostro ACK non è stato accettato?
    CONTROL_SEND_FAILED,    // invio di ACK/NAK fallito (best effort)
    JOURNAL_WRITE_FAILED,   // il listener (journal) ha lanciato DOPO il primo tentativo di scrittura: lo scambio prosegue
    DRAIN_INTERRUPTED,      // caduta, errore o limite raggiunto durante il drain (esito comunque valido)
    RX_OVERFLOW,            // RxBuffer pieno: byte scartati
    PROGRESS_LENGTH,        // progress con lunghezza diversa da 20
    RECEIPT_END_MISSING,    // drain chiuso senza la riga S finale (ESC in coda) pur attendendo uno scontrino
}

/**
 * payload: solo per POSSIBLE_LOST_RESULT, payload ISO-8859-1 del frame orfano con PAN mascherato
 * (il repository lo allega al journal come prova, §5.5).
 */
data class ProtocolWarning(val code: WarningCode, val detail: String, val at: Long, val payload: String? = null)

/** Warning che rendono un esito Answered "da verificare" per i comandi RECORD_AND_BLOCK (M6, §3.20). */
val REVIEW_WARNINGS: Set<WarningCode> = setOf(WarningCode.EARLY_RESULT, WarningCode.POSSIBLE_DUPLICATE)

/** Eventi per log traffico e diagnostica. `seq` monotono per client; `at` = epoch ms. */
sealed interface ProtocolEvent {
    val seq: Long
    val at: Long

    data class CommandStarted(override val seq: Long, override val at: Long, val exchangeId: Long,
        val command: CommandCode, val endpoint: Endpoint, val profile: TerminalProfileId, val optionsSummary: String) : ProtocolEvent

    data class CommandFinished(override val seq: Long, override val at: Long, val exchangeId: Long,
        val command: CommandCode, val outcome: OutcomeClass, val failure: Ecr17Failure?) : ProtocolEvent

    data class PhaseChanged(override val seq: Long, override val at: Long, val change: PhaseChange) : ProtocolEvent

    data class Link(override val seq: Long, override val at: Long, val state: LinkState) : ProtocolEvent

    /** Un frame completo. bytes = byte sul filo con PAN mascherato (PanMasker.maskFrame, M9); lrcNote es. "LRC 2F ok (stx)". */
    class Wire(override val seq: Long, override val at: Long, val exchangeId: Long?, val direction: FrameDirection,
        val kind: FrameKind, val code: Char?, val bytes: ByteArray, val attempt: Int?, val lrcNote: String?) : ProtocolEvent

    /** bytes già passati da PanMasker.maskFragment (un frammento di esito può contenere il PAN, M9). */
    class Discarded(override val seq: Long, override val at: Long, val exchangeId: Long?, val bytes: ByteArray,
        val reason: DiscardReason) : ProtocolEvent

    data class Progress(override val seq: Long, override val at: Long, val exchangeId: Long, val text: String) : ProtocolEvent

    data class Receipt(override val seq: Long, override val at: Long, val exchangeId: Long, val line: ReceiptLine) : ProtocolEvent

    data class Warning(override val seq: Long, override val at: Long, val exchangeId: Long?, val warning: ProtocolWarning) : ProtocolEvent
}

/** Destinatario sincrono degli eventi. DEVE essere non bloccante (al massimo un trySend). */
fun interface ProtocolEventSink {
    fun onEvent(event: ProtocolEvent)

    companion object { val NONE = ProtocolEventSink { } }
}
