package it.prime.protocollo17.ecr17.model

import java.math.BigDecimal
import java.time.LocalDateTime

// Modello della risposta: valore grezzo SEMPRE conservato + vista tipizzata calcolata (null se il grezzo è
// malformato), come raccomandato in L§5.6. Il PAN è già mascherato quando il parser è chiamato con
// maskPan = true (sempre, dal client).

// ─── Enum di decodifica ──────────────────────────────────────────────────────────

/** Esito applicativo (pos 11-12) letto dal codice grezzo. */
enum class Verdict {
    APPROVED, DECLINED, CARD_NOT_PRESENT, UNKNOWN_TAG, UNRECOGNIZED;

    companion object {
        /** "00"→APPROVED, "01"→DECLINED, "05"→CARD_NOT_PRESENT, "09"→UNKNOWN_TAG, altro (anche "")→UNRECOGNIZED. */
        fun fromResultCode(code: String): Verdict = when (code) {
            "00" -> APPROVED
            "01" -> DECLINED
            "05" -> CARD_NOT_PRESENT
            "09" -> UNKNOWN_TAG
            else -> UNRECOGNIZED
        }
    }
}

/** Modalità di lettura della carta (pos 32/3 delle risposte di transazione). */
enum class EntryMode(val wire: String) {
    ICC("ICC"), MAG("MAG"), MANUAL("MAN"), CLESS_MAG("CLM"), CLESS_ICC("CLI");

    companion object { fun fromWire(value: String): EntryMode? = entries.firstOrNull { it.wire == value.trimEnd() } }
}

/** Tipo di carta (pos 48/1, A§4.5.1). */
enum class CardType(val wire: Char) {
    DEBIT('1'), CREDIT('2'), OTHER('3');

    companion object { fun fromWire(value: String): CardType? = entries.firstOrNull { value.length == 1 && it.wire == value[0] } }
}

/** Layout della risposta 'E'/'V' (A§4.5.1-4.5.2). */
enum class PaymentLayout(val nominalLength: Int) {
    BASE_71(71), EXTENDED_92(92), REVERSAL_84(84), CARD_VERIFICATION_71(71), DCC_117(117)
}

// ─── Payload e campi ─────────────────────────────────────────────────────────────

/** Payload ricevuto (senza STX/ETX/LRC) decodificato ISO-8859-1 (1 byte = 1 char), PAN mascherato. */
data class RawPayload(val text: String) {
    val length: Int get() = text.length
    val replyCode: Char? get() = text.getOrNull(9)
}

/** Identificatore stabile dei campi decodificati (etichette italiane in presentation/model/FieldLabels). */
enum class FieldId {
    TERMINAL_ID, REPLY_CODE, RESULT_CODE, PAN, ENTRY_MODE, AUTH_CODE, HOST_DATE_TIME, ERROR_DESCRIPTION,
    CARD_TYPE, ACQUIRER_ID, STAN, ONLINE_ID, ACTION_CODE, HOST_AMOUNT, ORIGINAL_AMOUNT,
    DCC_APPLIED, DCC_RATE, DCC_CURRENCY, DCC_AMOUNT, DCC_PRECISION,
    PRE_AUTH_AMOUNT, PRE_AUTH_CODE, TERMINAL_DATE_TIME, STATUS, SOFTWARE_RELEASE, POS_TOTAL, HOST_TOTAL,
}

/** Campo per la vista "chiave/valore con posizione" della UI. raw = come sul filo (PAN mascherato). */
data class ResponseField(val id: FieldId, val pos: Int, val len: Int, val raw: String)

/** Dati DCC delle risposte 'V'/'v'. Ogni valore numerico ha accanto il grezzo: un parsing fallito non perde il dato. */
data class DccInfo(
    val applied: Boolean,
    val rateRaw: String, val rate: BigDecimal?,          // 4 decimali impliciti SEMPRE (A§4.5.1)
    val currencyCode: String,
    val amountRaw: String, val amountMinor: Long?,
    val precisionRaw: String, val precision: Int?,       // decimali dell'IMPORTO, non del tasso
)

/** Riga di scontrino 'S' (A§4.5.9). */
data class ReceiptLine(
    val raw: String,          // payload dalla pos 11, ISO-8859-1
    val text: String,         // 0x7D → '\n', 0x7F rimosso, sentinella rimossa
    val doubleHeight: Boolean,
    val isEnd: Boolean,       // terminava con 0x7D×6 + 0x1B
)

// ─── Gerarchia delle risposte ────────────────────────────────────────────────────

/** Risposta applicativa decodificata: sempre con il payload grezzo e la lista dei campi con pos/len. */
sealed interface Ecr17Response {
    val raw: RawPayload
    val terminalId: String
    val replyCode: Char
    val fields: List<ResponseField>
}

/** Risposte con esito 00/01/05/09. */
sealed interface HasResult : Ecr17Response {
    val resultCode: String
    val verdict: Verdict
}

/** Risposte di transazione: i campi di riconciliazione (L§6.5 punto 4). */
sealed interface TransactionResponse : HasResult {
    val stan: String?
    val onlineId: String?
    val hostDateTimeRaw: String?
    val authCode: String?
}

// ─── Risposte di transazione ─────────────────────────────────────────────────────

/**
 * Risposta 'E' o 'V' a P, X, S e H (A§4.5.1-4.5.2). Una sola classe con [layout] invece di una per variante: i
 * cinque layout hanno esito, PAN o descrizione errore, tipo carta, acquirer, STAN e online id alle stesse posizioni
 * e differiscono in pochi campi e nella coda, quindi i campi specifici sono nullable e valgono solo dove esistono.
 */
data class PaymentResponse(
    override val raw: RawPayload, override val terminalId: String, override val replyCode: Char,
    override val fields: List<ResponseField>,
    override val resultCode: String, override val verdict: Verdict,
    val layout: PaymentLayout,
    val pan: String?, val entryModeRaw: String?, val entryMode: EntryMode?,
    override val authCode: String?, override val hostDateTimeRaw: String?,
    val errorDescription: String?,
    val cardTypeRaw: String?, val cardType: CardType?,
    val acquirerId: String?, override val stan: String?, override val onlineId: String?,
    val actionCode: String?,                 // X-92, S-84, H-KO, V
    val hostAmountCents: Long?,              // X-92 pos 75/8
    val originalAmountCents: Long?,          // V pos 75/8
    val dcc: DccInfo?,                       // solo V
) : TransactionResponse

/**
 * Risposta 'e' alla preautorizzazione 'p' (106 B, A§4.5.3). Il tipo carta è letto solo nel ramo KO: nel ramo OK la
 * pos 48 è l'ultima cifra dell'importo preautorizzato (ambiguità della spec).
 */
data class PreAuthResponse(
    override val raw: RawPayload, override val terminalId: String, override val replyCode: Char,
    override val fields: List<ResponseField>,
    override val resultCode: String, override val verdict: Verdict,
    val pan: String?, val entryModeRaw: String?, val entryMode: EntryMode?,
    override val authCode: String?,
    val preAuthorizedAmountRaw: String?, val preAuthorizedAmountCents: Long?,
    val preAuthCode: String?, val actionCode: String?,
    override val hostDateTimeRaw: String?, val errorDescription: String?,
    val cardTypeRaw: String?, val cardType: CardType?,   // SOLO ramo KO
    val acquirerId: String?, override val stan: String?, override val onlineId: String?,
) : TransactionResponse

/**
 * Risposta 'i' all'integrazione (75 B, A§4.5.4). Classe propria perché il layout non è quello di 'e': i repo lo
 * leggevano come 'e' e sbagliavano tutto ciò che segue il tipo transazione. Nessun ramo KO né descrizione errore.
 */
data class IncrementalAuthResponse(
    override val raw: RawPayload, override val terminalId: String, override val replyCode: Char,
    override val fields: List<ResponseField>,
    override val resultCode: String, override val verdict: Verdict,
    val pan: String?, val entryModeRaw: String?, val entryMode: EntryMode?,
    val acquirerId: String?, override val authCode: String?,
    override val stan: String?, override val onlineId: String?,
    override val hostDateTimeRaw: String?, val actionCode: String?,
) : TransactionResponse

/**
 * Risposta 'c' (74 B) o 'v' con DCC (109 B) alla chiusura di preautorizzazione (A§4.5.5). Il blocco DCC di 'v' sta in
 * posizioni diverse da quelle di 'V': i repo lo perdevano senza segnalarlo.
 */
data class PreAuthClosureResponse(
    override val raw: RawPayload, override val terminalId: String, override val replyCode: Char,
    override val fields: List<ResponseField>,
    override val resultCode: String, override val verdict: Verdict,
    val pan: String?, val entryModeRaw: String?, val entryMode: EntryMode?,
    override val authCode: String?, override val hostDateTimeRaw: String?,
    val errorDescription: String?, val cardTypeRaw: String?, val cardType: CardType?,
    val acquirerId: String?, override val stan: String?, override val onlineId: String?,
    val actionCode: String?, val dcc: DccInfo?,
) : TransactionResponse

// ─── Risposte di servizio ────────────────────────────────────────────────────────

/** Risposta 's' allo stato terminale (A§4.5.6): data/ora del terminale, cifra di stato e release software. */
data class StatusResponse(
    override val raw: RawPayload, override val terminalId: String, override val replyCode: Char,
    override val fields: List<ResponseField>,
    val dateTimeRaw: String, val dateTime: LocalDateTime?,   // DDMMYYhhmm, anno 2000+YY, null se non valida
    val statusDigit: Int,                                    // 0..9, -1 se non è una cifra
    val softwareRelease: String,
) : Ecr17Response

/** Risposta 'T' ai totali (34 B, A§4.5.7): il totale POS c'è con qualsiasi esito, per questo non è nullable. */
data class TotalsResponse(
    override val raw: RawPayload, override val terminalId: String, override val replyCode: Char,
    override val fields: List<ResponseField>,
    override val resultCode: String, override val verdict: Verdict,
    val posTotalRaw: String, val posTotalCents: Long?,       // letto con QUALSIASI esito (A§4.5.7)
) : HasResult

/**
 * Risposta 'C' alla chiusura contabile (44 B, A§4.5.7). Ramo OK (totali POS e host) solo con esito "00", al contrario
 * dei pagamenti dove il ramo KO scatta solo con "01": l'asimmetria è della spec e va riprodotta.
 */
data class CloseSessionResponse(
    override val raw: RawPayload, override val terminalId: String, override val replyCode: Char,
    override val fields: List<ResponseField>,
    override val resultCode: String, override val verdict: Verdict,
    val posTotalRaw: String?, val posTotalCents: Long?,
    val hostTotalRaw: String?, val hostTotalCents: Long?,
    val errorDescription: String?, val actionCode: String?,
) : HasResult
