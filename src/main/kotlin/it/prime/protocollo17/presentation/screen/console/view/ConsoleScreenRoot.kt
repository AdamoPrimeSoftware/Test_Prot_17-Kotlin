package it.prime.protocollo17.presentation.screen.console.view

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.components.LocalNoticePresenter
import it.prime.protocollo17.presentation.components.NoticeTone
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleAction
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleEvent
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleUiState
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleViewModel
import it.prime.protocollo17.presentation.state.AppWindows
import it.prime.protocollo17.presentation.state.Shortcut
import it.prime.protocollo17.presentation.state.ShortcutBus
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.util.ObserveAsEvents
import it.prime.protocollo17.presentation.util.StopOnEscape
import org.koin.compose.koinInject

/**
 * Collegamento della Console (§6.5): stato, eventi (snackbar della finestra), Esc e scorciatoie.
 *
 * Un solo collector di ShortcutBus inoltra Ctrl+Invio, F5, Ctrl+frecce, Test connessione e le voci del
 * simulatore, ma solo senza dialog aperti (`shortcutsEnabled`, letto con rememberUpdatedState: il collect vive
 * più a lungo della ricomposizione). Esc annulla solo con `escapeCancels`: con un dialog aperto chiude il dialog
 * e basta. Le finestre si aprono qui (AppWindows), mai dal ViewModel.
 */
@Composable
fun ConsoleScreenRoot(viewModel: ConsoleViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val windows: AppWindows = koinInject()
    val shortcutBus: ShortcutBus = koinInject()
    val notices = LocalNoticePresenter.current

    ObserveAsEvents(viewModel.events) { event ->
        val (tone, message) = when (event) {
            is ConsoleEvent.ShowError -> NoticeTone.ERROR to event.message
            is ConsoleEvent.ShowInfo -> NoticeTone.INFO to event.message
            is ConsoleEvent.ShowSuccess -> NoticeTone.SUCCESS to event.message
        }
        notices?.show(tone, message)
    }

    StopOnEscape(enabled = state.escapeCancels) { viewModel.onAction(ConsoleAction.RequestCancel) }

    val shortcutsEnabled by rememberUpdatedState(state.shortcutsEnabled)
    LaunchedEffect(shortcutBus, viewModel) {
        shortcutBus.events.collect { shortcut ->
            if (!shortcutsEnabled) return@collect
            val action = when (shortcut) {
                Shortcut.RunSelected -> ConsoleAction.RunSelected
                Shortcut.PingStatus -> ConsoleAction.PingStatus
                Shortcut.SelectNext -> ConsoleAction.SelectNext
                Shortcut.SelectPrevious -> ConsoleAction.SelectPrevious
                Shortcut.TestConnection -> ConsoleAction.TestConnection
                Shortcut.StartSimulator -> ConsoleAction.StartSimulator
                Shortcut.StopSimulator -> ConsoleAction.StopSimulator
                Shortcut.UseSimulatorAsTarget -> ConsoleAction.UseSimulatorAsTarget
                Shortcut.RestoreTerminal -> ConsoleAction.RestoreTerminal
                Shortcut.StopCurrentJob -> null          // gestito da StopOnEscape
            }
            action?.let(viewModel::onAction)
        }
    }

    ConsoleScreen(state = state, onAction = viewModel::onAction, onOpenWindow = windows::open, modifier = modifier)
}

@Suppress("unused")
@Preview
@Composable
private fun ConsoleScreenRootPreview() {
    // Il Root richiede Koin: l'anteprima mostra la Screen pura con lo stato iniziale.
    PreviewTheme {
        Box(Modifier.size(1024.dp, 640.dp)) { ConsoleScreen(state = ConsoleUiState(), onAction = {}, onOpenWindow = {}) }
    }
}
