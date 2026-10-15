package it.prime.protocollo17.presentation.util

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import it.prime.protocollo17.presentation.theme.PreviewTheme
import kotlinx.coroutines.flow.Flow

/**
 * Raccoglie gli eventi one-shot di un ViewModel (Channel ⇒ receiveAsFlow).
 *
 * `onEvent` è volutamente NON `suspend`: deve ritornare subito, così il collect continua a consumare il canale.
 * Il lavoro che sospende (mostrare uno snackbar) va delegato (NoticePresenter, `rememberCoroutineScope()`),
 * altrimenti un handler lento blocca il consumo e gli eventi successivi arrivano fuori contesto.
 */
@Composable
fun <T> ObserveAsEvents(events: Flow<T>, onEvent: (T) -> Unit) {
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(events) {
        events.collect { currentOnEvent(it) }
    }
}

// ─── Preview ──────────────────────────────────────────────────────────────────────

@Suppress("unused")
@Preview
@Composable
private fun ObserveAsEventsPreview() {
    // Effetto senza UI: l'anteprima spiega solo il contratto.
    PreviewTheme { Text("ObserveAsEvents: effetto senza UI (eventi one-shot del ViewModel → snackbar della finestra)") }
}
