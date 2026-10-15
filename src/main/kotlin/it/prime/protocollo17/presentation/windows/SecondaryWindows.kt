package it.prime.protocollo17.presentation.windows

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import it.prime.protocollo17.presentation.components.AppSnackbarHost
import it.prime.protocollo17.presentation.components.LocalSnackbarHostState
import it.prime.protocollo17.presentation.state.AppWindowId
import it.prime.protocollo17.presentation.state.AppWindows
import it.prime.protocollo17.presentation.theme.AppTheme
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.util.ApplyMinimumSize
import it.prime.protocollo17.presentation.util.BringToFrontOn
import it.prime.protocollo17.presentation.util.TrackPickerOwner
import it.prime.protocollo17.presentation.util.WindowSpec
import it.prime.protocollo17.presentation.util.rememberAppIcon
import it.prime.protocollo17.presentation.util.rememberPersistedWindowState
import org.koin.compose.koinInject

/**
 * Le finestre secondarie (U10, §6.1) dichiarate nello scope `application {}`: mai annidate nel contenuto della
 * principale, così si spostano su un altro monitor e restano aperte indipendentemente. Ognuna appare finché il
 * suo id è in AppWindows.open; chiuderla toglie solo il suo id. Chiudere la principale chiude l'app e le
 * distrugge senza passare dal loro onCloseRequest: per questo l'insieme delle aperte resta salvato e all'avvio
 * si riaprono, con la geometria salvata in continuo.
 */
@Composable
fun ApplicationScope.SecondaryWindows() {
    val windows: AppWindows = koinInject()
    val open by windows.open.collectAsState()
    if (AppWindowId.OPTIONS in open) OptionsWindow(windows)
    if (AppWindowId.TRAFFIC_LOG in open) TrafficLogWindow(windows)
    if (AppWindowId.JOURNAL in open) JournalWindow(windows)
    if (AppWindowId.SIMULATOR in open) SimulatorWindow(windows)
}

/**
 * Una finestra secondaria (§6.1): geometria persistita e limitata all'area di lavoro, minimo imposto, primo piano
 * a ogni AppWindows.open sulla finestra già aperta, proprietaria dei dialog di file quando ha il focus, e un
 * Navigator radice con chiave propria che smaltisce i ScreenModel alla chiusura della finestra.
 */
@Composable
internal fun SecondaryWindow(
    id: AppWindowId,
    title: String,
    spec: WindowSpec,
    windows: AppWindows,
    navigatorKey: String,
    screen: () -> Screen,
) {
    val state = rememberPersistedWindowState(id.name, spec, windows)
    Window(
        onCloseRequest = { windows.close(id) },
        title = title,
        icon = rememberAppIcon(),
        state = state,
        onKeyEvent = { handleWindowShortcut(it, windows) },
    ) {
        ApplyMinimumSize(spec)
        BringToFrontOn(id, state, windows)
        TrackPickerOwner()
        WindowFrame {
            Navigator(remember { screen() }, key = navigatorKey) { CurrentScreen() }
        }
    }
}

/**
 * Cornice comune del contenuto di ogni finestra: il tema (che legge `ui.themeMode`) e il proprio SnackbarHost,
 * esposto con LocalSnackbarHostState ai Root della finestra (un solo host per finestra, niente sovrapposizioni).
 */
@Composable
internal fun WindowFrame(snackbar: SnackbarHostState = remember { SnackbarHostState() }, content: @Composable () -> Unit) {
    AppTheme {
        CompositionLocalProvider(LocalSnackbarHostState provides snackbar) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    content()
                    AppSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

/**
 * Ctrl+, / Ctrl+L / Ctrl+J / Ctrl+Shift+S anche dalle finestre secondarie (§6.15): nella principale sono
 * acceleratori del MenuBar, qui un onKeyEvent della finestra. Riaprire una finestra già aperta la porta davanti.
 */
internal fun handleWindowShortcut(event: KeyEvent, windows: AppWindows): Boolean {
    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return false
    val target = when {
        event.key == Key.Comma -> AppWindowId.OPTIONS
        event.key == Key.L -> AppWindowId.TRAFFIC_LOG
        event.key == Key.J -> AppWindowId.JOURNAL
        event.key == Key.S && event.isShiftPressed -> AppWindowId.SIMULATOR
        else -> return false
    }
    windows.open(target)
    return true
}

@Suppress("unused")
@Preview
@Composable
private fun SecondaryWindowsPreview() {
    // Le finestre vere richiedono Koin e un ApplicationScope: l'anteprima mostra solo la cornice.
    PreviewTheme { Box(Modifier.size(360.dp, 80.dp)) { Text("Finestre secondarie: Opzioni, Log traffico, Journal, Simulatore") } }
}
