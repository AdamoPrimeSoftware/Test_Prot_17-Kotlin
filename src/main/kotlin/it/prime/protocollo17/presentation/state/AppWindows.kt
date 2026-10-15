package it.prime.protocollo17.presentation.state

import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.domain.model.settings.WindowGeometry
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Finestre secondarie aperte e geometrie. Nessun import Compose.
 *
 * Lo stato delle finestre è app-scoped e testabile senza UI: chi le apre (menu, scorciatoie, azione "Apri" di una
 * notifica) chiama solo [open], e una finestra già aperta riceve una richiesta di primo piano su [focusRequests].
 * Prima di [focusRequests] Ctrl+L su un Log minimizzato o coperto non faceva nulla di visibile: la finestra era
 * "aperta" per lo stato ma l'operatore non la vedeva (R23).
 *
 * L'insieme delle finestre aperte è persistito in `ui.openWindows` a ogni apertura e chiusura esplicita: quando la
 * chiusura della principale distrugge le secondarie senza passare dal loro `onCloseRequest`, il valore salvato
 * resta quello giusto e all'avvio si riaprono (§6.1).
 */
class AppWindows(private val settings: SettingsRepository) {

    private val _open = MutableStateFlow(
        settings.getSnapshot().ui.openWindows.mapNotNull { name -> AppWindowId.entries.firstOrNull { it.name == name } }.toSet(),
    )

    private val _focusRequests = MutableSharedFlow<AppWindowId>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Inizializzato da ui.openWindows. */
    val open: StateFlow<Set<AppWindowId>>
        get() = _open.asStateFlow()

    /** Emesso da open(id) quando la finestra è GIÀ aperta: la finestra si porta in primo piano (§6.1). */
    val focusRequests: SharedFlow<AppWindowId>
        get() = _focusRequests.asSharedFlow()

    fun open(id: AppWindowId) {
        var alreadyOpen = false
        _open.update { current ->
            alreadyOpen = id in current
            if (alreadyOpen) current else current + id
        }
        if (alreadyOpen) _focusRequests.tryEmit(id) else persistOpen()
    }

    /** Aggiorna anche ui.openWindows. */
    fun close(id: AppWindowId) {
        var wasOpen = false
        _open.update { current ->
            wasOpen = id in current
            current - id
        }
        if (wasOpen) persistOpen()
    }

    fun toggle(id: AppWindowId) {
        if (id in _open.value) close(id) else open(id)
    }

    fun geometry(key: String): WindowGeometry? = settings.getSnapshot().ui.windows[key]

    fun saveGeometry(key: String, geometry: WindowGeometry) {
        if (settings.getSnapshot().ui.windows[key] == geometry) return
        settings.update { s -> s.copy(ui = s.ui.copy(windows = s.ui.windows + (key to geometry))) }
    }

    private fun persistOpen() {
        // Il nome dell'enum è il valore persistito (AppWindowId: rinominare una voce fa perdere la riapertura).
        val names = _open.value.map { it.name }.toSet()
        settings.update { s -> if (s.ui.openWindows == names) s else s.copy(ui = s.ui.copy(openWindows = names)) }
    }
}
