package it.prime.protocollo17.presentation.screen.options.view

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
import it.prime.protocollo17.presentation.screen.options.viewModel.OptionsEvent
import it.prime.protocollo17.presentation.screen.options.viewModel.OptionsViewModel
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.util.ObserveAsEvents
import it.prime.protocollo17.presentation.util.openInExplorer
import java.io.File

/** Collegamento della finestra Opzioni: stato, eventi sullo snackbar della finestra e "Apri cartella dati" (AWT). */
@Composable
fun OptionsScreenRoot(viewModel: OptionsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val notices = LocalNoticePresenter.current
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is OptionsEvent.ShowError -> notices?.show(NoticeTone.ERROR, event.message)
            is OptionsEvent.ShowInfo -> notices?.show(NoticeTone.INFO, event.message)
        }
    }
    OptionsScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenDataFolder = { openInExplorer(File(state.dataFolder)) },
        modifier = modifier,
    )
}

@Suppress("unused")
@Preview
@Composable
private fun OptionsScreenRootPreview() {
    // Il Root richiede Koin e il ViewModel: l'anteprima completa è in OptionsScreen.
    PreviewTheme { Box(Modifier.size(320.dp, 80.dp)) { Text("OptionsScreenRoot: vedi OptionsScreenPreview") } }
}
