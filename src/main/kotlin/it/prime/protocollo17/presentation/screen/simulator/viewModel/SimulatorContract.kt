package it.prime.protocollo17.presentation.screen.simulator.viewModel

import it.prime.protocollo17.domain.model.settings.SimulatorSettings
import it.prime.protocollo17.domain.model.simulator.SimLogEntry
import it.prime.protocollo17.domain.model.simulator.SimStats
import it.prime.protocollo17.domain.model.simulator.SimulatorFailure
import it.prime.protocollo17.domain.model.simulator.SimulatorStatus

// Contratto MVI della finestra Simulatore (§6.14).

data class SimulatorUiState(
    val settings: SimulatorSettings = SimulatorSettings(),
    val status: SimulatorStatus = SimulatorStatus.Stopped,
    val stats: SimStats = SimStats(),
    val log: List<SimLogEntry> = emptyList(),
    /** La destinazione del terminale nelle impostazioni è questo simulatore. */
    val isTarget: Boolean = false,
    val texts: Map<String, String> = emptyMap(),     // campi numerici in modifica: "port", "ackDelayMs", "progressDelayMs", "resultDelayMs"
    val fieldErrors: Map<String, String> = emptyMap(),
    /**
     * Un comando è in corso (CommandRunner): Avvia/Ferma, porta, "Usa come terminale", "Ripristina terminale" e
     * "Azzera stato" sono disabilitati, come le voci del menu Strumenti. Guasti, decisione e ritardi no: valgono
     * dalla richiesta successiva e sono lo scopo della finestra.
     */
    val commandRunning: Boolean = false,
) {
    val running: Boolean get() = status is SimulatorStatus.Running
    val starting: Boolean get() = status is SimulatorStatus.Starting

    /** Messaggio rosso accanto al campo porta; null se il simulatore non è in errore. */
    val statusError: String? get() = when (val f = (status as? SimulatorStatus.Failed)?.failure) {
        is SimulatorFailure.PortInUse -> "La porta ${f.port} è già in uso: scegline un'altra."
        is SimulatorFailure.Unexpected -> "Avvio non riuscito: ${f.message}"
        is SimulatorFailure.ListenerDied -> "L'ascolto sulla porta ${f.port} si è interrotto da solo: avvia di nuovo il simulatore."
        null -> null
    }
}

sealed interface SimulatorAction {
    data object Start : SimulatorAction
    data object Stop : SimulatorAction
    data class SetNumber(val field: String, val text: String) : SimulatorAction
    data class SetAppLrcMode(val wireName: String) : SimulatorAction     // SimCatalog.lrcModes
    data class SetAckRule(val rule: String) : SimulatorAction           // SimCatalog.ackRules
    data class SetDecision(val decision: String) : SimulatorAction      // SimCatalog.decisions
    data class SetFault(val fault: String) : SimulatorAction            // SimCatalog.faults
    data class SetFaultOnlyFinancial(val enabled: Boolean) : SimulatorAction
    data class SetCloseAfterTransaction(val enabled: Boolean) : SimulatorAction
    data class SetRetransmitResult(val enabled: Boolean) : SimulatorAction
    data class SetReceiptAfterResult(val enabled: Boolean) : SimulatorAction
    data class SetAutoStart(val enabled: Boolean) : SimulatorAction
    data object UseAsTarget : SimulatorAction
    data object RestoreTarget : SimulatorAction
    data object ClearLog : SimulatorAction
    data object ResetState : SimulatorAction
}

sealed interface SimulatorEvent {
    data class ShowError(val message: String) : SimulatorEvent
    data class ShowInfo(val message: String) : SimulatorEvent
}
