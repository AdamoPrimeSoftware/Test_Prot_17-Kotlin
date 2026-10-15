package it.prime.protocollo17.presentation.state

/**
 * La chiusura dell'app chiede conferma se un comando journaled ha già raggiunto SENDING (R11). Pura e testata.
 *
 * Prima di SENDING chiudere è innocuo (il comando non è partito, il journal scrive NOT_SENT); da SENDING in poi
 * l'esito resterebbe sconosciuto e i pagamenti bloccati al prossimo avvio, quindi l'operatore deve saperlo.
 * I comandi non journaled (s T G E R) non chiedono mai conferma: non muovono denaro.
 */
fun closeNeedsConfirmation(state: RunnerState): Boolean =
    state.running?.let { it.command.journaled && it.requestSent } == true
