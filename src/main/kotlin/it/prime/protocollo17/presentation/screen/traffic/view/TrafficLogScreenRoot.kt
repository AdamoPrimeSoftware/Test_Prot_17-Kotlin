package it.prime.protocollo17.presentation.screen.traffic.view

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.components.LocalSnackbarHostState
import it.prime.protocollo17.presentation.components.NoticeTone
import it.prime.protocollo17.presentation.components.showNotice
import it.prime.protocollo17.presentation.screen.traffic.viewModel.TrafficLogAction
import it.prime.protocollo17.presentation.screen.traffic.viewModel.TrafficLogEvent
import it.prime.protocollo17.presentation.screen.traffic.viewModel.TrafficLogViewModel
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.util.ObserveAsEvents
import it.prime.protocollo17.presentation.util.openInExplorer
import it.prime.protocollo17.presentation.util.openSaveDialog
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Collegamento della finestra Log traffico. Il dialog "Esporta…" (AWT, bloccante) parte qui dall'onClick: il
 * ViewModel riceve solo il file scelto (skill MVI: il ViewModel non conosce AWT).
 */
@Composable
fun TrafficLogScreenRoot(viewModel: TrafficLogViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is TrafficLogEvent.ShowError -> scope.launch { snackbar?.showNotice(NoticeTone.ERROR, event.message) }
            is TrafficLogEvent.ShowInfo -> scope.launch { snackbar?.showNotice(NoticeTone.SUCCESS, event.message) }
        }
    }
    TrafficLogScreen(
        state = state,
        onAction = viewModel::onAction,
        onExport = {
            val name = "p17-traffic-export-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt"
            openSaveDialog(title = "Esporta log traffico", suggestedName = name, extension = "txt")
                ?.let { viewModel.onAction(TrafficLogAction.Export(it)) }
        },
        onOpenFolder = { openInExplorer(File(state.directory)) },
        modifier = modifier,
    )
}

@Suppress("unused")
@Preview
@Composable
private fun TrafficLogScreenRootPreview() {
    // Il Root richiede Koin e il ViewModel: l'anteprima completa è in TrafficLogScreen.
    PreviewTheme { Box(Modifier.size(320.dp, 80.dp)) { Text("TrafficLogScreenRoot: vedi TrafficLogScreenPreview") } }
}
