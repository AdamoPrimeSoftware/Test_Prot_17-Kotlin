package it.prime.protocollo17.presentation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import it.prime.protocollo17.presentation.state.Shortcut
import it.prime.protocollo17.presentation.state.ShortcutBus
import org.koin.compose.koinInject

/**
 * Aggancia Esc all'annullamento del comando in corso.
 *
 * Esc arriva dalla finestra (ShortcutBus), non dal focus: un `onKeyEvent` dentro la schermata funzionerebbe solo
 * col focus sul componente giusto, e durante un comando il form è coperto dall'overlay. [enabled] deve essere lo
 * stesso booleano del pulsante Annulla e falso con un dialog aperto (`escapeCancels`): un Esc premuto per chiudere
 * un dialog non deve mai annullare un pagamento.
 */
@Composable
fun StopOnEscape(enabled: Boolean, onStop: () -> Unit) {
    val shortcutBus: ShortcutBus = koinInject()
    // Il collect vive quanto `enabled`, la lambda cambia a ogni ricomposizione: senza questo resterebbe stantia.
    val currentStop by rememberUpdatedState(onStop)
    LaunchedEffect(enabled, shortcutBus) {
        if (!enabled) return@LaunchedEffect
        shortcutBus.events.collect { shortcut ->
            if (shortcut == Shortcut.StopCurrentJob) currentStop()
        }
    }
}
