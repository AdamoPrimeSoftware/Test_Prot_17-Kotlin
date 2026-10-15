package it.prime.protocollo17.presentation

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.core.screen.Screen
import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.domain.model.settings.AppSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Il `navigator.pop` per un ViewModel creato nei test fuori da una schermata: Voyager lo smonta e ne cancella lo
 * `screenModelScope`.
 *
 * Fuori da una schermata lo scope è quello «standalone», UNO per tutta la JVM dei test: `screenModelScope.cancel()`
 * lo lascerebbe morto nella mappa di Voyager, e ogni ViewModel creato dopo (anche dagli altri test) riceverebbe uno
 * scope già cancellato. Smontarlo dal ScreenModelStore lo cancella E lo toglie (stesso schema di LocalDesktopAi).
 */
internal fun popStandaloneScreenModels() {
    ScreenModelStore.onDispose(StandaloneHolder)
}

/** Il «proprietario» degli scope standalone: la chiave è il loro prefisso. */
private object StandaloneHolder : Screen {
    override val key: String = "standalone"

    @Composable
    override fun Content() = Unit
}

/**
 * SettingsRepository in memoria (la classe vera è di WP5): `flow`, `getSnapshot` e `update` lavorano su uno
 * StateFlow, così i test vedono subito ciò che il ViewModel salva, senza file né debounce.
 */
internal class FakeSettings(initial: AppSettings = AppSettings()) {
    val state = MutableStateFlow(initial)
    var updates = 0
        private set

    val repository: SettingsRepository = mockk {
        every { flow } returns state
        every { getSnapshot() } answers { state.value }
        every { update(any()) } answers {
            updates++
            val transform = firstArg<(AppSettings) -> AppSettings>()
            state.update(transform)
        }
    }

    val value: AppSettings get() = state.value
}

/**
 * Attesa di una condizione reale con un tetto, per il lavoro che salta su un dispatcher vero (filtro del Log
 * traffico su Dispatchers.Default): il tempo virtuale non lo vede, `advanceUntilIdle` nemmeno (skill testing).
 */
internal suspend fun awaitTrue(timeout: Duration = 2.seconds, message: () -> String = { "condizione" }, predicate: () -> Boolean) {
    val reached = withContext(Dispatchers.Default) {
        withTimeoutOrNull(timeout) {
            while (!predicate()) delay(10)
            true
        }
    }
    if (reached == null) throw AssertionError("Timeout in attesa di: ${message()}")
}
