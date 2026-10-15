package it.prime.protocollo17.domain.util

import it.prime.protocollo17.domain.model.command.FieldKey
import it.prime.protocollo17.ecr17.error.RequestField
import it.prime.protocollo17.ecr17.error.ValidationError
import it.prime.protocollo17.ecr17.message.RequestEncoder
import it.prime.protocollo17.ecr17.model.CardVerificationRequest
import it.prime.protocollo17.ecr17.model.CloseSessionRequest
import it.prime.protocollo17.ecr17.model.CommandCode
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

/** Esito della costruzione di una richiesta dai testi del form. */
sealed interface RequestBuild {
    data class Ok(val request: Ecr17Request) : RequestBuild

    /** Messaggi in italiano, uno per campo (il primo errore trovato su quel campo). */
    data class Invalid(val errors: Map<FieldKey, String>) : RequestBuild
}

/**
 * Dai testi del form (FieldKey → testo) alla richiesta tipizzata, con i default della tabella §6.6.
 *
 * Le regole su cifre, larghezze e ASCII NON sono ripetute qui: si costruisce la richiesta e la si passa a
 * RequestEncoder.validate, l'unica fonte di quelle regole (§2.10). Così il form non può accettare un valore che
 * il protocollo poi rifiuterebbe (NotSent a comando già lanciato), né rifiutarne uno valido. Qui restano solo
 * le regole che il protocollo non conosce: l'importo scritto in euro (AmountParser, > 0) e la lettura delle scelte.
 * TID ed ECR id della configurazione non si validano qui (si usa TerminalIds() di default): sono compito di
 * ConfigResolver, che li segnala in Opzioni.
 */
object RequestFactory {

    /** Valore iniziale dell'importo nei form: il centesimo più piccolo, come la prova sul terminale reale. */
    private const val DEFAULT_AMOUNT = "0,01"

    /** Valori iniziali dei campi per il comando (tabella §6.6). */
    fun defaults(command: CommandCode): Map<FieldKey, String> = when (command) {
        CommandCode.PAYMENT, CommandCode.PAYMENT_EXTENDED, CommandCode.PRE_AUTH -> linkedMapOf(
            FieldKey.AMOUNT to DEFAULT_AMOUNT,
            FieldKey.PAYMENT_TYPE to PaymentType.AUTO.name,
            FieldKey.CARD_PRESENT to "false",
            FieldKey.RECEIPT_TEXT to "",
            FieldKey.CASH_REGISTER_OVERRIDE to "",
        )
        CommandCode.REVERSAL -> linkedMapOf(
            FieldKey.STAN to "",
            FieldKey.CASH_REGISTER_OVERRIDE to "",
        )
        CommandCode.CARD_VERIFICATION -> linkedMapOf(
            FieldKey.PAYMENT_TYPE to PaymentType.AUTO.name,
            FieldKey.CASH_REGISTER_OVERRIDE to "",
        )
        CommandCode.INCREMENTAL_AUTH, CommandCode.PRE_AUTH_CLOSURE -> linkedMapOf(
            FieldKey.AMOUNT to DEFAULT_AMOUNT,
            FieldKey.PRE_AUTH_CODE to "",
            FieldKey.RECEIPT_TEXT to "",
            FieldKey.CASH_REGISTER_OVERRIDE to "",
        )
        CommandCode.STATUS -> emptyMap()
        CommandCode.TOTALS, CommandCode.CLOSE_SESSION, CommandCode.LAST_RESULT -> linkedMapOf(
            FieldKey.CASH_REGISTER_OVERRIDE to "",
        )
        CommandCode.ECR_PRINTING -> linkedMapOf(FieldKey.ENABLED to "true")
        CommandCode.REPRINT -> linkedMapOf(
            FieldKey.REPRINT_TARGET to ReprintTarget.TERMINAL.name,
            FieldKey.TICKET_TYPE to TicketType.LAST_FINANCIAL.name,
        )
    }

    /**
     * Costruisce la richiesta tipizzata; un campo assente usa il default. Importo con AmountParser; poi
     * RequestEncoder.validate(request, TerminalIds()) (UNICA fonte delle regole su cifre/larghezze/ASCII, §2.10)
     * e mappatura RequestField → FieldKey (CASH_REGISTER_ID → CASH_REGISTER_OVERRIDE, AMOUNT, RECEIPT_TEXT,
     * STAN, PRE_AUTH_CODE) con messaggi italiani. TID ed ECR id della configurazione li valida ConfigResolver.
     */
    fun build(command: CommandCode, values: Map<FieldKey, String>): RequestBuild {
        val defaults = defaults(command)
        fun text(key: FieldKey): String = values[key] ?: defaults[key].orEmpty()
        val errors = linkedMapOf<FieldKey, String>()

        // ─── Campi che il protocollo non conosce come testo ───────────────────────
        val cents: Long = if (FieldKey.AMOUNT in defaults) {
            when (val parsed = AmountParser.parseEuroToCents(text(FieldKey.AMOUNT))) {
                is AmountParse.Ok -> parsed.cents
                is AmountParse.Invalid -> {
                    errors[FieldKey.AMOUNT] = parsed.message
                    // Segnaposto valido per il protocollo: si validano comunque gli altri campi, così l'operatore
                    // vede tutti gli errori in una volta invece di correggerli uno per esecuzione.
                    0L
                }
            }
        } else {
            0L
        }
        val paymentType = enumOrError(text(FieldKey.PAYMENT_TYPE), PaymentType.AUTO, FieldKey.PAYMENT_TYPE, errors)
        val cardPresent = text(FieldKey.CARD_PRESENT).trim().equals("true", ignoreCase = true)
        val receiptText = text(FieldKey.RECEIPT_TEXT)
        val cashRegister = text(FieldKey.CASH_REGISTER_OVERRIDE).trim().ifEmpty { null }
        val stan = text(FieldKey.STAN).trim().ifEmpty { null }
        val preAuthCode = text(FieldKey.PRE_AUTH_CODE).trim()
        val enabled = !text(FieldKey.ENABLED).trim().equals("false", ignoreCase = true)
        val target = enumOrError(text(FieldKey.REPRINT_TARGET), ReprintTarget.TERMINAL, FieldKey.REPRINT_TARGET, errors)
        val ticket = enumOrError(text(FieldKey.TICKET_TYPE), TicketType.LAST_FINANCIAL, FieldKey.TICKET_TYPE, errors)

        val request: Ecr17Request = when (command) {
            CommandCode.STATUS -> StatusRequest
            CommandCode.PAYMENT, CommandCode.PAYMENT_EXTENDED -> PaymentRequest(
                amountCents = cents,
                paymentType = paymentType,
                cardAlreadyPresent = cardPresent,
                receiptText = receiptText,
                cashRegisterId = cashRegister,
                extended = command == CommandCode.PAYMENT_EXTENDED,
            )
            CommandCode.REVERSAL -> ReversalRequest(stan = stan, cashRegisterId = cashRegister)
            CommandCode.PRE_AUTH -> PreAuthRequest(
                amountCents = cents,
                paymentType = paymentType,
                cardAlreadyPresent = cardPresent,
                receiptText = receiptText,
                cashRegisterId = cashRegister,
            )
            CommandCode.INCREMENTAL_AUTH -> IncrementalAuthRequest(
                amountCents = cents,
                originalPreAuthCode = preAuthCode,
                receiptText = receiptText,
                cashRegisterId = cashRegister,
            )
            CommandCode.PRE_AUTH_CLOSURE -> PreAuthClosureRequest(
                amountCents = cents,
                originalPreAuthCode = preAuthCode,
                receiptText = receiptText,
                cashRegisterId = cashRegister,
            )
            CommandCode.CARD_VERIFICATION -> CardVerificationRequest(paymentType = paymentType, cashRegisterId = cashRegister)
            CommandCode.CLOSE_SESSION -> CloseSessionRequest(cashRegisterId = cashRegister)
            CommandCode.TOTALS -> TotalsRequest(cashRegisterId = cashRegister)
            CommandCode.LAST_RESULT -> LastResultRequest(cashRegisterId = cashRegister)
            CommandCode.ECR_PRINTING -> EcrPrintingRequest(enabled = enabled)
            CommandCode.REPRINT -> ReprintRequest(target = target, ticketType = ticket)
        }

        // ─── Regole del protocollo: una sola fonte ───────────────────────────────
        for (error in RequestEncoder.validate(request, TerminalIds())) {
            val key = fieldKeyOf(error.field) ?: continue
            // putIfAbsent: l'errore dell'importo in euro (più comprensibile) vince su quello del layout.
            errors.putIfAbsent(key, messageFor(error, fieldText = text(key)))
        }
        return if (errors.isEmpty()) RequestBuild.Ok(request) else RequestBuild.Invalid(errors)
    }

    // ─── Supporto ─────────────────────────────────────────────────────────────────

    private inline fun <reified T : Enum<T>> enumOrError(
        value: String,
        fallback: T,
        key: FieldKey,
        errors: MutableMap<FieldKey, String>,
    ): T {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return fallback
        return enumValues<T>().firstOrNull { it.name == trimmed } ?: run {
            errors[key] = "Scelta non valida: '$trimmed'"
            fallback
        }
    }

    /** TERMINAL_ID non ha un campo nel form: è un valore della configurazione (Opzioni). */
    private fun fieldKeyOf(field: RequestField): FieldKey? = when (field) {
        RequestField.TERMINAL_ID -> null
        RequestField.CASH_REGISTER_ID -> FieldKey.CASH_REGISTER_OVERRIDE
        RequestField.AMOUNT -> FieldKey.AMOUNT
        RequestField.RECEIPT_TEXT -> FieldKey.RECEIPT_TEXT
        RequestField.STAN -> FieldKey.STAN
        RequestField.PRE_AUTH_CODE -> FieldKey.PRE_AUTH_CODE
    }

    /** Messaggio italiano accanto al campo; per un carattere non ASCII mostra il carattere colpevole. */
    private fun messageFor(error: ValidationError, fieldText: String): String = when (error) {
        is ValidationError.FieldOverflow -> when (error.field) {
            RequestField.AMOUNT -> "L'importo massimo è ${formatCents(99_999_999L)}"
            RequestField.RECEIPT_TEXT -> "Al massimo ${error.width} caratteri"
            else -> "Al massimo ${error.width} cifre"
        }
        is ValidationError.NotDigits -> "Solo cifre (0-9)"
        is ValidationError.NonAscii -> {
            val bad = fieldText.getOrNull(error.index)
            if (bad != null) "Carattere '$bad' non ammesso (solo ASCII)" else "Carattere non ammesso (solo ASCII)"
        }
        is ValidationError.NegativeAmount -> "L'importo deve essere maggiore di zero"
        is ValidationError.Required -> "Campo obbligatorio"
    }
}
