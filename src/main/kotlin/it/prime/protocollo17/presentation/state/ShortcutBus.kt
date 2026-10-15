package it.prime.protocollo17.presentation.state

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Scorciatoie dalla finestra ai ViewModel.
 *
 * Le scorciatoie si intercettano a livello di finestra (`Window.onKeyEvent`) e il MenuBar sta sopra il
 * Navigator: nessuno dei due vede il ConsoleViewModel. Pubblicano qui e il Root della Console le inoltra come
 * ConsoleAction, decidendo lui se un dialog aperto le deve ignorare (§6.5, §6.15).
 */
sealed interface Shortcut {
    data object RunSelected : Shortcut
    data object StopCurrentJob : Shortcut
    data object SelectNext : Shortcut
    data object SelectPrevious : Shortcut
    data object PingStatus : Shortcut

    // Voci di menu di MainWindow (sopra il Navigator): il Root della Console le inoltra come ConsoleAction
    data object TestConnection : Shortcut
    data object StartSimulator : Shortcut
    data object StopSimulator : Shortcut
    data object UseSimulatorAsTarget : Shortcut
    data object RestoreTerminal : Shortcut
}

/** Canale delle scorciatoie: send non sospende mai, così si può chiamare dal gestore dei tasti dell'EDT. */
class ShortcutBus {
    private val _events = MutableSharedFlow<Shortcut>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Shortcut> = _events.asSharedFlow()

    fun send(shortcut: Shortcut) {
        _events.tryEmit(shortcut)
    }
}
