package it.prime.protocollo17.presentation.screen.journal.view

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
import it.prime.protocollo17.presentation.screen.journal.viewModel.JournalEvent
import it.prime.protocollo17.presentation.screen.journal.viewModel.JournalViewModel
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.util.ObserveAsEvents
import it.prime.protocollo17.presentation.util.openInExplorer
import java.io.File

/** Collegamento della finestra Journal: stato, eventi sullo snackbar della finestra e apertura della cartella. */
@Composable
fun JournalScreenRoot(viewModel: JournalViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val notices = LocalNoticePresenter.current
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is JournalEvent.ShowError -> notices?.show(NoticeTone.ERROR, event.message)
            is JournalEvent.ShowInfo -> notices?.show(NoticeTone.INFO, event.message)
        }
    }
    JournalScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenFolder = { openInExplorer(File(state.filePath)) },
        modifier = modifier,
    )
}

@Suppress("unused")
@Preview
@Composable
private fun JournalScreenRootPreview() {
    // Il Root richiede Koin e il ViewModel: l'anteprima completa è in JournalScreen.
    PreviewTheme { Box(Modifier.size(320.dp, 80.dp)) { Text("JournalScreenRoot: vedi JournalScreenPreview") } }
}
