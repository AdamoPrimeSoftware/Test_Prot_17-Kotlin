package it.prime.protocollo17.presentation.screen.traffic.viewModel

import it.prime.protocollo17.domain.model.traffic.TrafficEntry
import java.io.File

// Contratto MVI della finestra Log traffico (§6.12).

data class TrafficFilter(
    val text: String = "",
    val showFrames: Boolean = true,      // TX/RX/DISCARDED
    val showEvents: Boolean = true,      // fasi, link, progress, righe S, comandi
    val onlyWarnings: Boolean = false,
    val showAscii: Boolean = true,
)

data class TrafficLogUiState(
    val visible: List<TrafficEntry> = emptyList(),
    val totalInMemory: Int = 0,
    val filter: TrafficFilter = TrafficFilter(),
    val paused: Boolean = false,         // vista congelata; il registratore continua
    val autoScroll: Boolean = true,
    val currentFile: String? = null,
    val directory: String = "",          // aperta dalla view con openInExplorer
)

sealed interface TrafficLogAction {
    data class SetText(val text: String) : TrafficLogAction
    data object ToggleFrames : TrafficLogAction
    data object ToggleEvents : TrafficLogAction
    data object ToggleOnlyWarnings : TrafficLogAction
    data object ToggleAscii : TrafficLogAction
    data object TogglePause : TrafficLogAction
    data class SetAutoScroll(val enabled: Boolean) : TrafficLogAction
    data object ClearMemory : TrafficLogAction
    data class Export(val target: File) : TrafficLogAction     // file scelto dalla view con openSaveDialog
}

sealed interface TrafficLogEvent {
    data class ShowError(val message: String) : TrafficLogEvent
    data class ShowInfo(val message: String) : TrafficLogEvent
}
