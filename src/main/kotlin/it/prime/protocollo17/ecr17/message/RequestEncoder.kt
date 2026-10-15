package it.prime.protocollo17.ecr17.message

import it.prime.protocollo17.ecr17.error.RequestField
import it.prime.protocollo17.ecr17.error.ValidationError
import it.prime.protocollo17.ecr17.model.CardVerificationRequest
import it.prime.protocollo17.ecr17.model.CloseSessionRequest
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.EcrPrintingRequest
import it.prime.protocollo17.ecr17.model.IncrementalAuthRequest
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.PaymentType
import it.prime.protocollo17.ecr17.model.PreAuthClosureRequest
import it.prime.protocollo17.ecr17.model.PreAuthRequest
import it.prime.protocollo17.ecr17.model.ReprintRequest
import it.prime.protocollo17.ecr17.model.ReprintTarget
import it.prime.protocollo17.ecr17.model.ReversalRequest
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.ecr17.model.TerminalIds
import it.prime.protocollo17.ecr17.model.TicketType
import it.prime.protocollo17.ecr17.model.TotalsRequest

/**
 * Codifica delle richieste in payload ASCII a larghezza fissa (A§4.3). È anche l'unica fonte delle regole di
 * validazione (cifre, larghezze, ASCII): la usa domain/util/RequestFactory, così form e protocollo non divergono.
 *
 * Validazione ed encoding passano dallo STESSO codice di layout (un `FieldWriter` per comando): `validate` legge
 * gli errori raccolti, `encode` e i builder chiedono i byte. Prima, nei repo, le regole stavano solo nei builder e
 * venivano eseguite dopo la connessione; qui il client chiama `validate` prima di aprire il socket (§3.23 p.3) e la
 * UI ottiene gli stessi errori senza duplicarne le regole.
 *
 * Differenze volute rispetto ai repo (A§4.4): TID, cash register, STAN e codice di preautorizzazione devono essere
 * solo cifre; il testo scontrino solo ASCII stampabile 0x20..0x7E; mai un byte NUL (la DLL inviava 0x00 nella
 * ristampa, A§4.3.7); un valore troppo lungo è sempre un errore, mai un troncamento.
 */
object RequestEncoder {

    // ─── Larghezze e lunghezze (A§4.3) ──────────────────────────────────────────

    private const val ID_WIDTH = 8                // terminal id e cash register id
    private const val STAN_WIDTH = 6
    private const val PRE_AUTH_CODE_WIDTH = 9

    private const val STATUS_LENGTH = 10
    private const val PAYMENT_LIKE_LENGTH = 167
    private const val INCREMENTAL_LIKE_LENGTH = 176
    private const val CARD_VERIFICATION_LENGTH = 39
    private const val REVERSAL_LENGTH = 26
    private const val CLOSE_OR_TOTALS_LENGTH = 26
    private const val LAST_RESULT_LENGTH = 22
    private const val ECR_PRINTING_LENGTH = 11
    private const val REPRINT_LENGTH = 22

    /** STAN "000000" = storna l'ultima transazione senza controllo dello STAN (A§4.3.9). */
    private const val LAST_TRANSACTION_STAN = "000000"

    // ─── API tipizzata ──────────────────────────────────────────────────────────

    /**
     * Tutti gli errori di validazione della richiesta (lista vuota = valida). Pura. È l'unica fonte delle regole
     * su cifre, larghezze e ASCII: la usa anche domain/util/RequestFactory (§5.8), ammesso da §2.10.
     */
    fun validate(request: Ecr17Request, ids: TerminalIds): List<ValidationError> = writerFor(request, ids).errors

    /** Payload ASCII (senza STX/ETX/LRC). Lancia Ecr17ValidationException se validate() non è vuota. */
    fun encode(request: Ecr17Request, ids: TerminalIds): ByteArray = writerFor(request, ids).toByteArray()

    /**
     * Smistamento richiesta → layout. Il flag GT (`additionalGtData`) è sempre false: la tokenizzazione (`U`) è
     * fuori perimetro v1 (§1.4) e un '1' farebbe attendere al terminale un messaggio `U` che non arriverà mai.
     */
    private fun writerFor(request: Ecr17Request, ids: TerminalIds): FieldWriter {
        val tid = ids.terminalId
        fun cashRegister(override: String?) = override ?: ids.cashRegisterId
        return when (request) {
            is StatusRequest -> statusWriter(tid)
            is PaymentRequest -> paymentLikeWriter(
                code = if (request.extended) 'X' else 'P',
                terminalId = tid,
                cashRegisterId = cashRegister(request.cashRegisterId),
                amountCents = request.amountCents,
                paymentType = request.paymentType,
                cardAlreadyPresent = request.cardAlreadyPresent,
                additionalGtData = false,
                receiptText = request.receiptText,
            )
            is ReversalRequest -> reversalWriter(
                terminalId = tid,
                cashRegisterId = cashRegister(request.cashRegisterId),
                stan = request.stan,
            )
            is PreAuthRequest -> paymentLikeWriter(
                code = 'p',
                terminalId = tid,
                cashRegisterId = cashRegister(request.cashRegisterId),
                amountCents = request.amountCents,
                paymentType = request.paymentType,
                cardAlreadyPresent = request.cardAlreadyPresent,
                additionalGtData = false,
                receiptText = request.receiptText,
            )
            is IncrementalAuthRequest -> incrementalLikeWriter(
                code = 'i',
                terminalId = tid,
                cashRegisterId = cashRegister(request.cashRegisterId),
                amountCents = request.amountCents,
                originalPreAuthCode = request.originalPreAuthCode,
                receiptText = request.receiptText,
            )
            is PreAuthClosureRequest -> incrementalLikeWriter(
                code = 'c',
                terminalId = tid,
                cashRegisterId = cashRegister(request.cashRegisterId),
                amountCents = request.amountCents,
                originalPreAuthCode = request.originalPreAuthCode,
                receiptText = request.receiptText,
            )
            is CardVerificationRequest -> cardVerificationWriter(
                terminalId = tid,
                cashRegisterId = cashRegister(request.cashRegisterId),
                paymentType = request.paymentType,
                additionalGtData = false,
            )
            is CloseSessionRequest -> closeOrTotalsWriter(code = 'C', terminalId = tid, cashRegisterId = cashRegister(request.cashRegisterId))
            is TotalsRequest -> closeOrTotalsWriter(code = 'T', terminalId = tid, cashRegisterId = cashRegister(request.cashRegisterId))
            is LastResultRequest -> lastResultWriter(tid, cashRegister(request.cashRegisterId))
            is EcrPrintingRequest -> ecrPrintingWriter(tid, request.enabled)
            is ReprintRequest -> reprintWriter(terminalId = tid, target = request.target, ticketType = request.ticketType)
        }
    }

    // ─── Builder di basso livello (firme usate dai test vector A§9.4) ───────────

    /** `s` (10 B): T(1-8) '0'(9) 's'(10). Lancia Ecr17ValidationException su TID non valido. */
    fun buildStatus(terminalId: String): ByteArray = statusWriter(terminalId).toByteArray()

    /**
     * `P`/`X`/`p` (167 B, A§4.3.1). [code] fuori da {'P','X','p'} è un bug del chiamante (IllegalArgumentException);
     * i valori non validi producono Ecr17ValidationException con tutti gli errori.
     */
    fun buildPaymentLike(
        code: Char, terminalId: String, cashRegisterId: String, amountCents: Long,
        paymentType: PaymentType, cardAlreadyPresent: Boolean, additionalGtData: Boolean, receiptText: String,
    ): ByteArray = paymentLikeWriter(
        code = code,
        terminalId = terminalId,
        cashRegisterId = cashRegisterId,
        amountCents = amountCents,
        paymentType = paymentType,
        cardAlreadyPresent = cardAlreadyPresent,
        additionalGtData = additionalGtData,
        receiptText = receiptText,
    ).toByteArray()                                               // code ∈ {'P','X','p'}

    /** `i`/`c` (176 B, A§4.3.2): senza tipo pagamento né flag carta presente, con il codice di preautorizzazione. */
    fun buildIncrementalLike(
        code: Char, terminalId: String, cashRegisterId: String, amountCents: Long,
        originalPreAuthCode: String, receiptText: String,
    ): ByteArray = incrementalLikeWriter(
        code = code,
        terminalId = terminalId,
        cashRegisterId = cashRegisterId,
        amountCents = amountCents,
        originalPreAuthCode = originalPreAuthCode,
        receiptText = receiptText,
    ).toByteArray()                                               // code ∈ {'i','c'}

    /** `H` (39 B, A§4.3.3): tipo verifica '0' (unico valore ufficiale). */
    fun buildCardVerification(terminalId: String, cashRegisterId: String, paymentType: PaymentType, additionalGtData: Boolean = false): ByteArray =
        cardVerificationWriter(
            terminalId = terminalId,
            cashRegisterId = cashRegisterId,
            paymentType = paymentType,
            additionalGtData = additionalGtData,
        ).toByteArray()

    /** `S` (26 B, A§4.3.9): stan null o vuoto ⇒ "000000" (ultima transazione). */
    fun buildReversal(terminalId: String, cashRegisterId: String, stan: String?): ByteArray =
        reversalWriter(terminalId = terminalId, cashRegisterId = cashRegisterId, stan = stan).toByteArray()

    /** `C`/`T` (26 B, A§4.3.4). */
    fun buildCloseOrTotals(code: Char, terminalId: String, cashRegisterId: String): ByteArray =
        closeOrTotalsWriter(code = code, terminalId = terminalId, cashRegisterId = cashRegisterId).toByteArray()   // 'C' | 'T'

    /** `G` (22 B, A§4.3.5). */
    fun buildSendLastResult(terminalId: String, cashRegisterId: String): ByteArray =
        lastResultWriter(terminalId, cashRegisterId).toByteArray()

    /** `E` (11 B, A§4.3.6): nessun cash register; impostazione non persistente sul terminale. */
    fun buildEcrPrinting(terminalId: String, enabled: Boolean): ByteArray =
        ecrPrintingWriter(terminalId, enabled).toByteArray()

    /** `R` (22 B, A§4.3.7): destinazione e tipo ticket sempre '0'/'1', mai NUL come la DLL. */
    fun buildReprint(terminalId: String, target: ReprintTarget, ticketType: TicketType): ByteArray =
        reprintWriter(terminalId = terminalId, target = target, ticketType = ticketType).toByteArray()

    // ─── Layout (un FieldWriter per comando) ────────────────────────────────────

    /** Intestazione comune (A§4.1): TID left-pad '0' (1-8), '0' riservato (9), codice (10). */
    private fun FieldWriter.header(terminalId: String, code: Char) {
        digits(field = RequestField.TERMINAL_ID, value = terminalId, width = ID_WIDTH)
        char('0')
        char(code)
    }

    private fun FieldWriter.cashRegister(cashRegisterId: String) =
        digits(field = RequestField.CASH_REGISTER_ID, value = cashRegisterId, width = ID_WIDTH)

    private fun flag(value: Boolean): Char = if (value) '1' else '0'

    private fun statusWriter(terminalId: String) = FieldWriter(STATUS_LENGTH).apply {
        header(terminalId, 's')
    }

    private fun paymentLikeWriter(
        code: Char, terminalId: String, cashRegisterId: String, amountCents: Long,
        paymentType: PaymentType, cardAlreadyPresent: Boolean, additionalGtData: Boolean, receiptText: String,
    ): FieldWriter {
        require(code == 'P' || code == 'X' || code == 'p') { "ECR17: codice '$code' non è un pagamento (P, X, p)" }
        return FieldWriter(PAYMENT_LIKE_LENGTH).apply {
            header(terminalId, code)                      // 1-10
            cashRegister(cashRegisterId)                  // 11-18
            char(flag(additionalGtData))                  // 19 dati aggiuntivi GT
            literal("00")                                 // 20-21 riservato
            char(flag(cardAlreadyPresent))                // 22 avvio con carta già presente
            char(paymentType.wire)                        // 23 tipo pagamento
            amount(amountCents)                           // 24-31
            receiptText(receiptText)                      // 32-159 allineato a destra
            literal("00000000")                           // 160-167 riservato
        }
    }

    private fun incrementalLikeWriter(
        code: Char, terminalId: String, cashRegisterId: String, amountCents: Long,
        originalPreAuthCode: String, receiptText: String,
    ): FieldWriter {
        require(code == 'i' || code == 'c') { "ECR17: codice '$code' non è un'integrazione o chiusura (i, c)" }
        return FieldWriter(INCREMENTAL_LIKE_LENGTH).apply {
            header(terminalId, code)                      // 1-10
            cashRegister(cashRegisterId)                  // 11-18
            char('0')                                     // 19 dati aggiuntivi GT (sempre '0', A§4.3.2)
            literal("0000")                               // 20-23 riservato
            amount(amountCents)                           // 24-31
            receiptText(receiptText)                      // 32-159
            preAuthCode(originalPreAuthCode)              // 160-168
            literal("00000000")                           // 169-176 riservato
        }
    }

    /**
     * Codice di preautorizzazione originale: obbligatorio. Vuoto (o di soli spazi) ⇒ Required e non NotDigits:
     * il campo manca, non è scritto male. Senza questa regola un codice vuoto diventerebbe "000000000", cioè una
     * richiesta valida per il terminale riferita a una preautorizzazione inesistente.
     */
    private fun FieldWriter.preAuthCode(value: String) {
        if (value.isBlank()) {
            required(RequestField.PRE_AUTH_CODE, width = PRE_AUTH_CODE_WIDTH)
        } else {
            digits(field = RequestField.PRE_AUTH_CODE, value = value, width = PRE_AUTH_CODE_WIDTH)
        }
    }

    private fun cardVerificationWriter(
        terminalId: String, cashRegisterId: String, paymentType: PaymentType, additionalGtData: Boolean,
    ) = FieldWriter(CARD_VERIFICATION_LENGTH).apply {
        header(terminalId, 'H')                           // 1-10
        cashRegister(cashRegisterId)                      // 11-18
        char(flag(additionalGtData))                      // 19 dati aggiuntivi GT
        literal("00")                                     // 20-21 riservato
        char('0')                                         // 22 tipo verifica standard
        char(paymentType.wire)                            // 23 tipo pagamento
        literal("0000000000000000")                       // 24-39 riservato
    }

    private fun reversalWriter(terminalId: String, cashRegisterId: String, stan: String?) =
        FieldWriter(REVERSAL_LENGTH).apply {
            header(terminalId, 'S')                       // 1-10
            cashRegister(cashRegisterId)                  // 11-18
            digits(
                field = RequestField.STAN,
                value = if (stan.isNullOrEmpty()) LAST_TRANSACTION_STAN else stan,
                width = STAN_WIDTH,
            )                                             // 19-24
            char('0')                                     // 25 dati aggiuntivi GT
            char('0')                                     // 26 riservato
        }

    private fun closeOrTotalsWriter(code: Char, terminalId: String, cashRegisterId: String): FieldWriter {
        require(code == 'C' || code == 'T') { "ECR17: codice '$code' non è chiusura o totali (C, T)" }
        return FieldWriter(CLOSE_OR_TOTALS_LENGTH).apply {
            header(terminalId, code)                      // 1-10
            cashRegister(cashRegisterId)                  // 11-18
            char('0')                                     // 19 dati aggiuntivi GT
            literal("0000000")                            // 20-26 riservato
        }
    }

    private fun lastResultWriter(terminalId: String, cashRegisterId: String) = FieldWriter(LAST_RESULT_LENGTH).apply {
        header(terminalId, 'G')                           // 1-10
        cashRegister(cashRegisterId)                      // 11-18
        char('0')                                         // 19 dati aggiuntivi GT
        literal("000")                                    // 20-22 riservato
    }

    private fun ecrPrintingWriter(terminalId: String, enabled: Boolean) = FieldWriter(ECR_PRINTING_LENGTH).apply {
        header(terminalId, 'E')                           // 1-10
        char(flag(enabled))                               // 11
    }

    private fun reprintWriter(terminalId: String, target: ReprintTarget, ticketType: TicketType) =
        FieldWriter(REPRINT_LENGTH).apply {
            header(terminalId, 'R')                       // 1-10
            char(target.wire)                             // 11 destinazione
            char(ticketType.wire)                         // 12 tipo ticket
            literal("0000000000")                         // 13-22 riservato
        }
}
