package it.prime.protocollo17.data.local.settings

import it.prime.protocollo17.domain.model.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow
import java.io.File

// STUB di WP0 (§8.2): firme definitive di §5.2.3, corpi a carico di WP5 (replica di LocalDesktopAi: caricamento
// difensivo con copia dei file illeggibili, scrittura atomica, debounce 400 ms, flush in close e nello shutdown hook).

/**
 * Unica fonte delle impostazioni, come StateFlow: le finestre la osservano e una modifica in Opzioni vale subito
 * ovunque (niente istantanee lette una sola volta in `init`). Il file è un parametro per i test.
 */
class SettingsRepository(private val file: File) : AutoCloseable {
    val flow: StateFlow<AppSettings>
        get() = TODO("WP5: SettingsRepository.kt")

    fun getSnapshot(): AppSettings = TODO("WP5: SettingsRepository.kt")

    /** CAS su MutableStateFlow + segnale di salvataggio con debounce 400 ms. */
    fun update(transform: (AppSettings) -> AppSettings): Unit = TODO("WP5: SettingsRepository.kt")

    /** Annulla lo scope e salva subito se ci sono modifiche. */
    override fun close(): Unit = TODO("WP5: SettingsRepository.kt")
}
