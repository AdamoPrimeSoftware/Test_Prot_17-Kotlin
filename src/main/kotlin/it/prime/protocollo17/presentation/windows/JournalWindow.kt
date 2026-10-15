package it.prime.protocollo17.presentation.windows

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.koin.koinScreenModel
import it.prime.protocollo17.presentation.screen.journal.view.JournalScreenRoot
import it.prime.protocollo17.presentation.screen.journal.viewModel.JournalViewModel
import it.prime.protocollo17.presentation.state.AppWindowId
import it.prime.protocollo17.presentation.state.AppWindows
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.util.WindowSpec

/** Misure della finestra Journal (§6.1): default 1100×680, minimo 720×420 dp. */
val JournalWindowSpec = WindowSpec(defaultWidth = 1100f, defaultHeight = 680f, minWidth = 720f, minHeight = 420f)

/** Finestra Journal dei tentativi: aperta da menu, Ctrl+J, dall'icona con il badge o dall'azione "Apri" di un avviso. */
@Composable
fun JournalWindow(windows: AppWindows) {
    SecondaryWindow(
        id = AppWindowId.JOURNAL,
        title = "Journal tentativi — Protocollo 17",
        spec = JournalWindowSpec,
        windows = windows,
        navigatorKey = "nav-journal",
        screen = { JournalWindowScreen() },
    )
}

/** Schermata radice della finestra Journal: chiave unica tra le finestre (§6.17). */
class JournalWindowScreen : Screen {
    override val key: ScreenKey = "screen-journal"

    @Composable
    override fun Content() {
        val viewModel: JournalViewModel = koinScreenModel()
        JournalScreenRoot(viewModel)
    }
}

@Suppress("unused")
@Preview
@Composable
private fun JournalWindowPreview() {
    PreviewTheme { Box(Modifier.size(360.dp, 60.dp)) { Text("Journal tentativi — Protocollo 17 (vedi JournalScreenPreview)") } }
}
