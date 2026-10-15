package it.prime.protocollo17.ecr17.message

import it.prime.protocollo17.ecr17.error.ValidationError
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.PaymentType
import it.prime.protocollo17.ecr17.model.ReprintTarget
import it.prime.protocollo17.ecr17.model.TerminalIds
import it.prime.protocollo17.ecr17.model.TicketType

// STUB di WP0 (§8.2): firme definitive di §3.12, corpi a carico di WP2.

/**
 * Codifica delle richieste in payload ASCII a larghezza fissa (A§4.3). È anche l'unica fonte delle regole di
 * validazione (cifre, larghezze, ASCII): la usa domain/util/RequestFactory, così form e protocollo non divergono.
 */
object RequestEncoder {
    /**
     * Tutti gli errori di validazione della richiesta (lista vuota = valida). Pura. È l'unica fonte delle regole
     * su cifre, larghezze e ASCII: la usa anche domain/util/RequestFactory (§5.8), ammesso da §2.10.
     */
    fun validate(request: Ecr17Request, ids: TerminalIds): List<ValidationError> = TODO("WP2: RequestEncoder.kt")

    /** Payload ASCII (senza STX/ETX/LRC). Lancia Ecr17ValidationException se validate() non è vuota. */
    fun encode(request: Ecr17Request, ids: TerminalIds): ByteArray = TODO("WP2: RequestEncoder.kt")

    // ─── Builder di basso livello (firme usate dai test vector A§9.4) ───────────

    fun buildStatus(terminalId: String): ByteArray = TODO("WP2: RequestEncoder.kt")

    fun buildPaymentLike(
        code: Char, terminalId: String, cashRegisterId: String, amountCents: Long,
        paymentType: PaymentType, cardAlreadyPresent: Boolean, additionalGtData: Boolean, receiptText: String,
    ): ByteArray = TODO("WP2: RequestEncoder.kt")                 // code ∈ {'P','X','p'}

    fun buildIncrementalLike(
        code: Char, terminalId: String, cashRegisterId: String, amountCents: Long,
        originalPreAuthCode: String, receiptText: String,
    ): ByteArray = TODO("WP2: RequestEncoder.kt")                 // code ∈ {'i','c'}

    fun buildCardVerification(terminalId: String, cashRegisterId: String, paymentType: PaymentType, additionalGtData: Boolean = false): ByteArray =
        TODO("WP2: RequestEncoder.kt")

    fun buildReversal(terminalId: String, cashRegisterId: String, stan: String?): ByteArray = TODO("WP2: RequestEncoder.kt")

    fun buildCloseOrTotals(code: Char, terminalId: String, cashRegisterId: String): ByteArray =
        TODO("WP2: RequestEncoder.kt")                             // 'C' | 'T'

    fun buildSendLastResult(terminalId: String, cashRegisterId: String): ByteArray = TODO("WP2: RequestEncoder.kt")

    fun buildEcrPrinting(terminalId: String, enabled: Boolean): ByteArray = TODO("WP2: RequestEncoder.kt")

    fun buildReprint(terminalId: String, target: ReprintTarget, ticketType: TicketType): ByteArray =
        TODO("WP2: RequestEncoder.kt")
}
