package it.prime.protocollo17.domain.model.command

/**
 * Chiavi dei campi dei form; i valori sono testi ("true"/"false" per i toggle, Enum.name per le scelte).
 *
 * I form tengono testi e non valori tipizzati perché devono conservare anche ciò che l'operatore sta ancora
 * scrivendo (un importo "0," non è un numero ma non va perso): la conversione avviene solo in RequestFactory.
 * Le stesse chiavi indicizzano le bozze persistite in AppSettings.drafts.
 */
enum class FieldKey { AMOUNT, PAYMENT_TYPE, CARD_PRESENT, RECEIPT_TEXT, CASH_REGISTER_OVERRIDE, STAN, PRE_AUTH_CODE, ENABLED, REPRINT_TARGET, TICKET_TYPE }
