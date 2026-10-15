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
import it.prime.protocollo17.presentation.screen.traffic.view.TrafficLogScreenRoot
import it.prime.protocollo17.presentation.screen.traffic.viewModel.TrafficLogViewModel
import it.prime.protocollo17.presentation.state.AppWindowId
import it.prime.protocollo17.presentation.state.AppWindows
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.util.WindowSpec

/** Misure della finestra Log traffico (§6.1): default 1100×680, minimo 640×420 dp. */
val TrafficLogWindowSpec = WindowSpec(defaultWidth = 1100f, defaultHeight = 680f, minWidth = 640f, minHeight = 420f)

/** Finestra Log traffico TX/RX: una finestra vera, da tenere su un secondo monitor durante le prove. */
@Composable
fun TrafficLogWindow(windows: AppWindows) {
    SecondaryWindow(
        id = AppWindowId.TRAFFIC_LOG,
        title = "Log traffico TX/RX — Protocollo 17",
        spec = TrafficLogWindowSpec,
        windows = windows,
        navigatorKey = "nav-traffic",
        screen = { TrafficLogWindowScreen() },
    )
}

/** Schermata radice della finestra Log traffico: chiave unica tra le finestre (§6.17). */
class TrafficLogWindowScreen : Screen {
    override val key: ScreenKey = "screen-traffic"

    @Composable
    override fun Content() {
        val viewModel: TrafficLogViewModel = koinScreenModel()
        TrafficLogScreenRoot(viewModel)
    }
}

@Suppress("unused")
@Preview
@Composable
private fun TrafficLogWindowPreview() {
    PreviewTheme { Box(Modifier.size(360.dp, 60.dp)) { Text("Log traffico TX/RX — Protocollo 17 (vedi TrafficLogScreenPreview)") } }
}
