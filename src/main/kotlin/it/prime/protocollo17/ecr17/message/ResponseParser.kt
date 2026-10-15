package it.prime.protocollo17.ecr17.message

import it.prime.protocollo17.ecr17.model.CloseSessionResponse
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.Ecr17Response
import it.prime.protocollo17.ecr17.model.IncrementalAuthResponse
import it.prime.protocollo17.ecr17.model.PaymentLayout
import it.prime.protocollo17.ecr17.model.PaymentResponse
import it.prime.protocollo17.ecr17.model.PreAuthClosureResponse
import it.prime.protocollo17.ecr17.model.PreAuthResponse
import it.prime.protocollo17.ecr17.model.ReceiptLine
import it.prime.protocollo17.ecr17.model.StatusResponse
import it.prime.protocollo17.ecr17.model.TotalsResponse

// STUB di WP0 (§8.2): firme definitive di §3.14, corpi a carico di WP2.

/**
 * Decodifica delle risposte applicative (A§4.5). ISO-8859-1 sui byte, letture tolleranti ai payload corti,
 * numeri convertiti solo se composti esclusivamente da cifre: un payload malformato non lancia mai.
 */
object ResponseParser {
    /**
     * Smistamento per codice in pos 10 con il contesto del comando inviato. Restituisce null per codici
     * senza parser v1 ('S' riga scontrino, 'U', 'K', altro): la sessione li tratta prima di chiamare parse.
     */
    fun parse(payload: ByteArray, sent: CommandCode, maskPan: Boolean = true): Ecr17Response? = TODO("WP2: ResponseParser.kt")

    /** Layout di una 'E'/'V' in base al comando inviato (per G: in base alla lunghezza). */
    fun paymentLayoutFor(sent: CommandCode, replyCode: Char, length: Int): PaymentLayout = TODO("WP2: ResponseParser.kt")

    fun parsePayment(payload: ByteArray, layout: PaymentLayout, maskPan: Boolean = true): PaymentResponse = TODO("WP2: ResponseParser.kt")

    fun parsePreAuth(payload: ByteArray, maskPan: Boolean = true): PreAuthResponse = TODO("WP2: ResponseParser.kt")

    fun parseIncrementalAuth(payload: ByteArray, maskPan: Boolean = true): IncrementalAuthResponse = TODO("WP2: ResponseParser.kt")

    fun parsePreAuthClosure(payload: ByteArray, maskPan: Boolean = true): PreAuthClosureResponse = TODO("WP2: ResponseParser.kt")

    fun parseStatus(payload: ByteArray): StatusResponse = TODO("WP2: ResponseParser.kt")

    fun parseTotals(payload: ByteArray): TotalsResponse = TODO("WP2: ResponseParser.kt")

    fun parseCloseSession(payload: ByteArray): CloseSessionResponse = TODO("WP2: ResponseParser.kt")

    /** maskPan = true: PanMasker.maskDigitRuns su raw e text (alcuni terminali stampano il PAN in chiaro). */
    fun parseReceiptLine(payload: ByteArray, maskPan: Boolean = true): ReceiptLine = TODO("WP2: ResponseParser.kt")
}
