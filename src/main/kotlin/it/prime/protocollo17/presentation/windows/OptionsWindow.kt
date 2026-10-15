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
import it.prime.protocollo17.presentation.screen.options.view.OptionsScreenRoot
import it.prime.protocollo17.presentation.screen.options.viewModel.OptionsViewModel
import it.prime.protocollo17.presentation.state.AppWindowId
import it.prime.protocollo17.presentation.state.AppWindows
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.util.WindowSpec

/** Misure della finestra Opzioni (§6.1): default 760×720, minimo 560×420 dp. */
val OptionsWindowSpec = WindowSpec(defaultWidth = 760f, defaultHeight = 720f, minWidth = 560f, minHeight = 420f)

/** Finestra Opzioni: aperta da menu Finestre, Ctrl+, o dall'icona ingranaggio (anche cliccando un chip dell'intestazione). */
@Composable
fun OptionsWindow(windows: AppWindows) {
    SecondaryWindow(
        id = AppWindowId.OPTIONS,
        title = "Opzioni — Protocollo 17",
        spec = OptionsWindowSpec,
        windows = windows,
        navigatorKey = "nav-options",
        screen = { OptionsWindowScreen() },
    )
}

/** Schermata radice della finestra Opzioni: chiave unica tra le finestre (§6.17). */
class OptionsWindowScreen : Screen {
    override val key: ScreenKey = "screen-options"

    @Composable
    override fun Content() {
        val viewModel: OptionsViewModel = koinScreenModel()
        OptionsScreenRoot(viewModel)
    }
}

@Suppress("unused")
@Preview
@Composable
private fun OptionsWindowPreview() {
    PreviewTheme { Box(Modifier.size(320.dp, 60.dp)) { Text("Opzioni — Protocollo 17 (vedi OptionsScreenPreview)") } }
}
