package it.prime.protocollo17.presentation.util

import org.slf4j.LoggerFactory

/**
 * Corpo di uno `ScreenModel.onDispose()` che non può far fallire la chiusura dell'app.
 *
 * Alla chiusura di una finestra Voyager chiama `onDispose` di ogni ScreenModel in fila, sul thread della finestra
 * e senza protezioni: un'eccezione in uno solo interrompe lo smontaggio degli altri e lascia la finestra bianca
 * (successo in LocalDesktopAi con un `NoClassDefFoundError`, per questo si prende `Throwable`). Non è codice in
 * una coroutine: non c'è nessuna `CancellationException` da rilanciare.
 */
inline fun disposeSafely(owner: Any, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        LoggerFactory.getLogger(owner::class.java)
            .warn("Smontaggio di {} fallito, continuo la chiusura", owner::class.simpleName, t)
    }
}
