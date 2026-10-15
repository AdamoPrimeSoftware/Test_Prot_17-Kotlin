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
import it.prime.protocollo17.presentation.screen.simulator.view.SimulatorScreenRoot
import it.prime.protocollo17.presentation.screen.simulator.viewModel.SimulatorViewModel
import it.prime.protocollo17.presentation.state.AppWindowId
import it.prime.protocollo17.presentation.state.AppWindows
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.util.WindowSpec

/** Misure della finestra Simulatore (§6.1): default 680×720, minimo 520×420 dp. */
val SimulatorWindowSpec = WindowSpec(defaultWidth = 680f, defaultHeight = 720f, minWidth = 520f, minHeight = 420f)

/**
 * Finestra Simulatore. Chiuderla non ferma il simulatore: è app-scoped (SimulatorController), come i comandi che
 * sopravvivono alla chiusura delle finestre secondarie.
 */
@Composable
fun SimulatorWindow(windows: AppWindows) {
    SecondaryWindow(
        id = AppWindowId.SIMULATOR,
        title = "Simulatore terminale — Protocollo 17",
        spec = SimulatorWindowSpec,
        windows = windows,
        navigatorKey = "nav-simulator",
        screen = { SimulatorWindowScreen() },
    )
}

/** Schermata radice della finestra Simulatore: chiave unica tra le finestre (§6.17). */
class SimulatorWindowScreen : Screen {
    override val key: ScreenKey = "screen-simulator"

    @Composable
    override fun Content() {
        val viewModel: SimulatorViewModel = koinScreenModel()
        SimulatorScreenRoot(viewModel)
    }
}

@Suppress("unused")
@Preview
@Composable
private fun SimulatorWindowPreview() {
    PreviewTheme { Box(Modifier.size(360.dp, 60.dp)) { Text("Simulatore terminale — Protocollo 17 (vedi SimulatorScreen)") } }
}
