package it.prime.protocollo17.presentation.state

import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.domain.model.settings.WindowGeometry
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// STUB di WP0 (§8.2): firme definitive di §6.4, corpi a carico di WP6.

/**
 * Finestre secondarie aperte e geometrie. Nessun import Compose.
 *
 * Lo stato delle finestre è app-scoped e testabile senza UI: chi le apre (menu, scorciatoie, azione "Apri" di una
 * notifica) chiama solo [open], e una finestra già aperta riceve una richiesta di primo piano su [focusRequests].
 */
class AppWindows(private val settings: SettingsRepository) {

    /** Inizializzato da ui.openWindows. */
    val open: StateFlow<Set<AppWindowId>>
        get() = TODO("WP6: AppWindows.kt")

    /** Emesso da open(id) quando la finestra è GIÀ aperta: la finestra si porta in primo piano (§6.1). */
    val focusRequests: SharedFlow<AppWindowId>
        get() = TODO("WP6: AppWindows.kt")

    fun open(id: AppWindowId): Unit = TODO("WP6: AppWindows.kt")

    /** Aggiorna anche ui.openWindows. */
    fun close(id: AppWindowId): Unit = TODO("WP6: AppWindows.kt")

    fun toggle(id: AppWindowId): Unit = TODO("WP6: AppWindows.kt")

    fun geometry(key: String): WindowGeometry? = TODO("WP6: AppWindows.kt")

    fun saveGeometry(key: String, geometry: WindowGeometry): Unit = TODO("WP6: AppWindows.kt")
}
