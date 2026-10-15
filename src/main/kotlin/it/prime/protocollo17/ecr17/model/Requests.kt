package it.prime.protocollo17.ecr17.model

/** TID e cash register id di default (config). Validati da RequestEncoder: solo cifre, ≤ 8. */
data class TerminalIds(val terminalId: String = "00000000", val cashRegisterId: String = "00000000")

/** Tipo di pagamento richiesto (pos 23 di P/X/p, A§4.3.1). */
enum class PaymentType(val wire: Char) { AUTO('0'), DEBIT('1'), CREDIT('2'), OTHER('3') }

/** Destinazione della ristampa (pos 11 di R, A§4.3.7). */
enum class ReprintTarget(val wire: Char) { TERMINAL('0'), ECR('1') }

/** Scontrino da ristampare (pos 12 di R, A§4.3.7). */
enum class TicketType(val wire: Char) { LAST_FINANCIAL('0'), LAST_SERVICE('1') }

/**
 * Richiesta tipizzata. `cashRegisterId` null = valore della configurazione.
 *
 * Una classe per comando invece di una mappa di campi: il compilatore impedisce di inviare un `P` senza
 * importo o un `i` senza codice di preautorizzazione, e la validazione (RequestEncoder) lavora su tipi noti.
 */
sealed interface Ecr17Request {
    val command: CommandCode
}

/** Stato terminale 's' (10 B, A§4.3.8): non muove denaro, serve a verificare collegamento e parametri ECR. */
data object StatusRequest : Ecr17Request {
    override val command: CommandCode get() = CommandCode.STATUS
}

/**
 * Pagamento 'P' o, con [extended] = true, pagamento esteso 'X' (167 B, A§4.3.1). Una sola classe per i due codici
 * perché il layout di richiesta è identico: cambia solo la risposta (E 71 B contro E 92 B, A§4.5.2).
 */
data class PaymentRequest(
    val amountCents: Long,
    val paymentType: PaymentType = PaymentType.AUTO,
    val cardAlreadyPresent: Boolean = false,
    val receiptText: String = "",
    val cashRegisterId: String? = null,
    /** false = 'P', true = 'X' (pagamento con esito esteso). */
    val extended: Boolean = false,
) : Ecr17Request {
    override val command: CommandCode get() = if (extended) CommandCode.PAYMENT_EXTENDED else CommandCode.PAYMENT
}

/** Storno: stan null o vuoto ⇒ "000000" = ultima transazione (A§4.3.9). */
data class ReversalRequest(val stan: String? = null, val cashRegisterId: String? = null) : Ecr17Request {
    override val command: CommandCode get() = CommandCode.REVERSAL
}

/** Preautorizzazione 'p' (167 B, stesso layout di P/X, A§4.3.1); la risposta 'e' porta il codice da riusare in i/c. */
data class PreAuthRequest(
    val amountCents: Long,
    val paymentType: PaymentType = PaymentType.AUTO,
    val cardAlreadyPresent: Boolean = false,
    val receiptText: String = "",
    val cashRegisterId: String? = null,
) : Ecr17Request {
    override val command: CommandCode get() = CommandCode.PRE_AUTH
}

/** Integrazione di preautorizzazione 'i' (176 B, A§4.3.2): [originalPreAuthCode] è obbligatorio, mai inventato. */
data class IncrementalAuthRequest(
    val amountCents: Long,
    val originalPreAuthCode: String,
    val receiptText: String = "",
    val cashRegisterId: String? = null,
) : Ecr17Request {
    override val command: CommandCode get() = CommandCode.INCREMENTAL_AUTH
}

/** Chiusura di preautorizzazione 'c' (176 B, A§4.3.2): incassa [amountCents] sul codice di preautorizzazione dato. */
data class PreAuthClosureRequest(
    val amountCents: Long,
    val originalPreAuthCode: String,
    val receiptText: String = "",
    val cashRegisterId: String? = null,
) : Ecr17Request {
    override val command: CommandCode get() = CommandCode.PRE_AUTH_CLOSURE
}

/** Verifica carta 'H' (39 B, A§4.3.3). Il tipo verifica è sempre '0' (unico valore ufficiale): non è un parametro. */
data class CardVerificationRequest(
    val paymentType: PaymentType = PaymentType.AUTO,
    val cashRegisterId: String? = null,
) : Ecr17Request {
    override val command: CommandCode get() = CommandCode.CARD_VERIFICATION
}

/** Chiusura contabile 'C' (26 B, A§4.3.4): journaled perché un esito sconosciuto deve bloccare un nuovo 'C'. */
data class CloseSessionRequest(val cashRegisterId: String? = null) : Ecr17Request {
    override val command: CommandCode get() = CommandCode.CLOSE_SESSION
}

/** Totali 'T' (26 B, stesso layout di 'C', A§4.3.4): sola lettura, non chiude la sessione contabile. */
data class TotalsRequest(val cashRegisterId: String? = null) : Ecr17Request {
    override val command: CommandCode get() = CommandCode.TOTALS
}

/** Invia ultimo esito 'G' (22 B, A§4.3.5): chiede l'esito dell'ultima transazione; mai inviato in automatico (U7). */
data class LastResultRequest(val cashRegisterId: String? = null) : Ecr17Request {
    override val command: CommandCode get() = CommandCode.LAST_RESULT
}

/**
 * Abilita/disabilita la stampa su ECR 'E' (11 B, A§4.3.6). Solo ACK e senza cash register id; l'impostazione non è
 * persistente, per questo con "stampa su ECR" attiva si invia E1 prima di ogni comando che deve produrre righe 'S'.
 */
data class EcrPrintingRequest(val enabled: Boolean) : Ecr17Request {
    override val command: CommandCode get() = CommandCode.ECR_PRINTING
}

/** Ristampa 'R' (22 B, A§4.3.7): solo ACK, più righe 'S' se [target] = ECR. Enum e non Char: la DLL inviava NUL. */
data class ReprintRequest(
    val target: ReprintTarget = ReprintTarget.TERMINAL,
    val ticketType: TicketType = TicketType.LAST_FINANCIAL,
) : Ecr17Request {
    override val command: CommandCode get() = CommandCode.REPRINT
}

/** Importo in centesimi, se il comando ne ha uno (journal, UI). */
val Ecr17Request.amountCents: Long?
    get() = when (this) {
        is PaymentRequest -> amountCents
        is PreAuthRequest -> amountCents
        is IncrementalAuthRequest -> amountCents
        is PreAuthClosureRequest -> amountCents
        else -> null
    }
