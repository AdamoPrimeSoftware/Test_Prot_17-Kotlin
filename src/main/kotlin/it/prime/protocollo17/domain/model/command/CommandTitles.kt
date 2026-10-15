package it.prime.protocollo17.domain.model.command

import it.prime.protocollo17.ecr17.model.CommandCode

/**
 * Titoli italiani dei comandi: li usano TerminalSessionService (notifiche), i mapper e CommandCatalog.
 *
 * Stanno nel dominio (e non nel catalogo della Console, WP6) perché servono anche al servizio applicativo di
 * WP5 per i testi delle notifiche: una sola fonte, così sidebar, cronologia e snackbar dicono la stessa cosa.
 */
object CommandTitles {

    fun title(code: CommandCode): String = when (code) {
        CommandCode.STATUS -> "Stato terminale"
        CommandCode.PAYMENT -> "Pagamento"
        CommandCode.PAYMENT_EXTENDED -> "Pagamento esteso"
        CommandCode.REVERSAL -> "Storno"
        CommandCode.PRE_AUTH -> "Preautorizzazione"
        CommandCode.INCREMENTAL_AUTH -> "Integrazione preautorizzazione"
        CommandCode.PRE_AUTH_CLOSURE -> "Chiusura preautorizzazione"
        CommandCode.CARD_VERIFICATION -> "Verifica carta"
        CommandCode.CLOSE_SESSION -> "Chiusura contabile"
        CommandCode.TOTALS -> "Totali"
        CommandCode.LAST_RESULT -> "Ultimo esito (G)"
        CommandCode.ECR_PRINTING -> "Stampa su ECR"
        CommandCode.REPRINT -> "Ristampa"
    }

    /**
     * true per i comandi con pulsante rosso: movesMoney || code == CLOSE_SESSION.
     * La chiusura contabile non muove denaro ma non è ripetibile e azzera i totali: merita lo stesso avviso visivo.
     */
    fun isDanger(code: CommandCode): Boolean = code.movesMoney || code == CommandCode.CLOSE_SESSION
}
