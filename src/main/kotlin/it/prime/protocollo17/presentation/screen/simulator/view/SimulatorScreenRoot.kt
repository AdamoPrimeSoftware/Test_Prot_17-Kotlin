package it.prime.protocollo17.presentation.screen.simulator.view

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.components.LocalNoticePresenter
import it.prime.protocollo17.presentation.components.NoticeTone
import it.prime.protocollo17.presentation.screen.simulator.viewModel.SimulatorEvent
import it.prime.protocollo17.presentation.screen.simulator.viewModel.SimulatorViewModel
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.util.ObserveAsEvents

/** Collegamento della finestra Simulatore: stato ed eventi sullo snackbar della finestra. */
@Composable
fun SimulatorScreenRoot(viewModel: SimulatorViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val notices = LocalNoticePresenter.current
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is SimulatorEvent.ShowError -> notices?.show(NoticeTone.ERROR, event.message)
            is SimulatorEvent.ShowInfo -> notices?.show(NoticeTone.INFO, event.message)
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
