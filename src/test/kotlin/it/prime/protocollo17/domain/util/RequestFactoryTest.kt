package it.prime.protocollo17.domain.util

import it.prime.protocollo17.domain.model.command.FieldKey
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.EcrPrintingRequest
import it.prime.protocollo17.ecr17.model.IncrementalAuthRequest
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.PaymentType
import it.prime.protocollo17.ecr17.model.PreAuthRequest
import it.prime.protocollo17.ecr17.model.ReprintRequest
import it.prime.protocollo17.ecr17.model.ReprintTarget
import it.prime.protocollo17.ecr17.model.ReversalRequest
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.ecr17.model.TicketType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Dai testi del form alla richiesta tipizzata (§5.8, tabella §6.6). Le regole di cifre, larghezze e ASCII sono SOLO
 * in RequestEncoder.validate, e questi test verificano che il form le mostri sul campo giusto invece di lasciarle
 * esplodere come NotSent a comando lanciato.
 */
class RequestFactoryTest {

    private fun ok(command: CommandCode, values: Map<FieldKey, String> = emptyMap()) =
        (RequestFactory.build(command, values) as? RequestBuild.Ok)?.request
            ?: error("attesa una richiesta valida per $command, invece: ${RequestFactory.build(command, values)}")

    private fun errors(command: CommandCode, values: Map<FieldKey, String>): Map<FieldKey, String> {
        val built = RequestFactory.build(command, values)
        assertTrue(built is RequestBuild.Invalid, "attesi errori per $command $values, invece: $built")
        return (built as RequestBuild.Invalid).errors
    }

    /** I default della tabella §6.6: importo 0,01 condiviso, scelte sul primo valore, stampa su ECR attiva. */
    @Test
    fun `default della tabella per ogni comando`() {
        val payment = mapOf(
            FieldKey.AMOUNT to "0,01", FieldKey.PAYMENT_TYPE to "AUTO", FieldKey.CARD_PRESENT to "false",
            FieldKey.RECEIPT_TEXT to "", FieldKey.CASH_REGISTER_OVERRIDE to "",
        )
        assertEquals(payment, RequestFactory.defaults(CommandCode.PAYMENT))
        assertEquals(payment, RequestFactory.defaults(CommandCode.PAYMENT_EXTENDED))
        assertEquals(payment, RequestFactory.defaults(CommandCode.PRE_AUTH))
        assertEquals(mapOf(FieldKey.STAN to "", FieldKey.CASH_REGISTER_OVERRIDE to ""), RequestFactory.defaults(CommandCode.REVERSAL))
        assertEquals(mapOf(FieldKey.PAYMENT_TYPE to "AUTO", FieldKey.CASH_REGISTER_OVERRIDE to ""), RequestFactory.defaults(CommandCode.CARD_VERIFICATION))
        val preAuthFollowUp = mapOf(
            FieldKey.AMOUNT to "0,01", FieldKey.PRE_AUTH_CODE to "", FieldKey.RECEIPT_TEXT to "", FieldKey.CASH_REGISTER_OVERRIDE to "",
        )
        assertEquals(preAuthFollowUp, RequestFactory.defaults(CommandCode.INCREMENTAL_AUTH))
        assertEquals(preAuthFollowUp, RequestFactory.defaults(CommandCode.PRE_AUTH_CLOSURE))
        assertEquals(emptyMap<FieldKey, String>(), RequestFactory.defaults(CommandCode.STATUS))
        listOf(CommandCode.TOTALS, CommandCode.CLOSE_SESSION, CommandCode.LAST_RESULT).forEach {
            assertEquals(mapOf(FieldKey.CASH_REGISTER_OVERRIDE to ""), RequestFactory.defaults(it), "$it")
        }
        assertEquals(mapOf(FieldKey.ENABLED to "true"), RequestFactory.defaults(CommandCode.ECR_PRINTING))
        assertEquals(
            mapOf(FieldKey.REPRINT_TARGET to "TERMINAL", FieldKey.TICKET_TYPE to "LAST_FINANCIAL"),
            RequestFactory.defaults(CommandCode.REPRINT),
        )
        // Ogni default deve produrre una richiesta valida, tranne i/c che richiedono il codice di preautorizzazione.
        CommandCode.entries.filter { it != CommandCode.INCREMENTAL_AUTH && it != CommandCode.PRE_AUTH_CLOSURE }.forEach { ok(it) }
    }

    /** "0,10" ⇒ 10 centesimi, tipo AUTO, cash register della configurazione (null). */
    @Test
    fun `pagamento valido`() {
        assertEquals(
            PaymentRequest(amountCents = 10, paymentType = PaymentType.AUTO, cardAlreadyPresent = false, receiptText = "", cashRegisterId = null),
            ok(CommandCode.PAYMENT, mapOf(FieldKey.AMOUNT to "0,10")),
        )
        assertEquals(true, (ok(CommandCode.PAYMENT_EXTENDED) as PaymentRequest).extended)
        assertEquals(PreAuthRequest(amountCents = 650, paymentType = PaymentType.DEBIT),
            ok(CommandCode.PRE_AUTH, mapOf(FieldKey.AMOUNT to "6.50", FieldKey.PAYMENT_TYPE to "DEBIT")))
        assertEquals(StatusRequest, ok(CommandCode.STATUS))
        assertEquals(EcrPrintingRequest(enabled = false), ok(CommandCode.ECR_PRINTING, mapOf(FieldKey.ENABLED to "false")))
    }

    /** STAN vuoto = ultima transazione (A§4.3.9): null, non stringa vuota. */
    @Test
    fun `STAN vuoto diventa null`() {
        assertEquals(ReversalRequest(stan = null, cashRegisterId = null), ok(CommandCode.REVERSAL, mapOf(FieldKey.STAN to "  ")))
        assertEquals(ReversalRequest(stan = "000042", cashRegisterId = "12"),
            ok(CommandCode.REVERSAL, mapOf(FieldKey.STAN to "000042", FieldKey.CASH_REGISTER_OVERRIDE to "12")))
    }

    /** Il codice di preautorizzazione obbligatorio arriva da RequestEncoder.validate (Required). */
    @Test
    fun `codice preautorizzazione mancante e' un errore sul suo campo`() {
        val errors = errors(CommandCode.INCREMENTAL_AUTH, mapOf(FieldKey.AMOUNT to "5,00"))
        assertTrue(FieldKey.PRE_AUTH_CODE in errors, "errori: $errors")
        assertEquals(
            IncrementalAuthRequest(amountCents = 500, originalPreAuthCode = "123456789"),
            ok(CommandCode.INCREMENTAL_AUTH, mapOf(FieldKey.AMOUNT to "5,00", FieldKey.PRE_AUTH_CODE to "123456789")),
        )
    }

    /** CASH_REGISTER_ID del protocollo ⇒ campo "ECR id" del form. */
    @Test
    fun `ECR id non numerico e' un errore sul campo override`() {
        val errors = errors(CommandCode.TOTALS, mapOf(FieldKey.CASH_REGISTER_OVERRIDE to "12AB"))
        assertTrue(FieldKey.CASH_REGISTER_OVERRIDE in errors, "errori: $errors")
    }

    /** Un carattere non ASCII sposterebbe i campi a larghezza fissa (R5): errore che mostra il carattere. */
    @Test
    fun `testo non ASCII e' un errore sul testo scontrino`() {
        val errors = errors(CommandCode.PAYMENT, mapOf(FieldKey.RECEIPT_TEXT to "caffè"))
        assertEquals("Carattere 'è' non ammesso (solo ASCII)", errors[FieldKey.RECEIPT_TEXT])
    }

    /** Importo e altri campi sbagliati insieme: l'operatore li vede tutti in una volta. */
    @Test
    fun `errori di importo e di testo sono riportati insieme`() {
        val errors = errors(CommandCode.PAYMENT, mapOf(FieldKey.AMOUNT to "0", FieldKey.RECEIPT_TEXT to "caffè"))
        assertEquals(setOf(FieldKey.AMOUNT, FieldKey.RECEIPT_TEXT), errors.keys)
    }

    /** Le scelte della ristampa arrivano come Enum.name. */
    @Test
    fun `ristampa verso ECR dell'ultimo di servizio`() {
        assertEquals(
            ReprintRequest(target = ReprintTarget.ECR, ticketType = TicketType.LAST_SERVICE),
            ok(CommandCode.REPRINT, mapOf(FieldKey.REPRINT_TARGET to "ECR", FieldKey.TICKET_TYPE to "LAST_SERVICE")),
        )
    }
}
