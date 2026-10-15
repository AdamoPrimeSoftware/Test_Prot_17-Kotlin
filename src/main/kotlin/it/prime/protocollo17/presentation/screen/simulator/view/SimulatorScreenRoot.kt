package it.prime.protocollo17.presentation.screen.simulator.view

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
import it.prime.protocollo17.presentation.screen.simulator.viewModel.SimulatorEvent
import it.prime.protocollo17.presentation.screen.simulator.viewModel.SimulatorViewModel
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.util.ObserveAsEvents
import kotlinx.coroutines.launch

/** Collegamento della finestra Simulatore: stato ed eventi sullo snackbar della finestra. */
@Composable
fun SimulatorScreenRoot(viewModel: SimulatorViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is SimulatorEvent.ShowError -> scope.launch { snackbar?.showNotice(NoticeTone.ERROR, event.message) }
            is SimulatorEvent.ShowInfo -> scope.launch { snackbar?.showNotice(NoticeTone.INFO, event.message) }
        }
    }
    SimulatorScreen(state = state, onAction = viewModel::onAction, modifier = modifier)
}

@Suppress("unused")
@Preview
@Composable
private fun SimulatorScreenRootPreview() {
    // Il Root richiede Koin e il ViewModel: le anteprime complete sono in SimulatorScreen.
    PreviewTheme { Box(Modifier.size(320.dp, 80.dp)) { Text("SimulatorScreenRoot: vedi SimulatorScreen") } }
}
