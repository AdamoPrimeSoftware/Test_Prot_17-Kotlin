package it.prime.protocollo17.di

import cafe.adriel.voyager.core.model.ScreenModel
import it.prime.protocollo17.data.local.AppDirs
import it.prime.protocollo17.data.local.journal.JournalStore
import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.data.local.traffic.TrafficRecorder
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.domain.repository.PosTerminalRepository
import it.prime.protocollo17.domain.repository.SimulatorController
import it.prime.protocollo17.domain.repository.TrafficRepository
import it.prime.protocollo17.ecr17.client.Ecr17Client
import it.prime.protocollo17.ecr17.transport.TcpTransport
import it.prime.protocollo17.presentation.popStandaloneScreenModels
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleViewModel
import it.prime.protocollo17.presentation.screen.journal.viewModel.JournalViewModel
import it.prime.protocollo17.presentation.screen.options.viewModel.OptionsViewModel
import it.prime.protocollo17.presentation.screen.simulator.viewModel.SimulatorViewModel
import it.prime.protocollo17.presentation.screen.traffic.viewModel.TrafficLogViewModel
import it.prime.protocollo17.presentation.state.AppNotifications
import it.prime.protocollo17.presentation.state.AppWindows
import it.prime.protocollo17.presentation.state.CommandRunner
import it.prime.protocollo17.presentation.state.ShortcutBus
import it.prime.protocollo17.presentation.state.TerminalSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File

/**
 * Verifica del cablaggio (§6.16, §8.10): il modulo Koin dell'app risolve ogni singleton e ogni ViewModel.
 *
 * Perché un test e non solo l'avvio: Koin costruisce a richiesta, quindi un parametro sbagliato in `AppModule` o un
 * costruttore che lancia esplodono solo quando qualcuno apre la finestra che li usa (o, peggio, nel `finally` di
 * chiusura di `Main`). Qui si risolve tutto subito, su una cartella temporanea al posto di `~/.protocollo17`: la
 * definizione di [AppDirs] viene sostituita da un secondo modulo caricato dopo, quindi `AppDirs.default()` non viene
 * mai chiamato e i dati dell'operatore non si toccano. La chiusura segue lo stesso ordine di `Main.closeAppResources`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppModuleTest {

    @TempDir
    lateinit var tmp: File

    @BeforeEach
    fun setUp() {
        // I ScreenModel lanciano nel loro scope su Dispatchers.Main: nei test non c'è un EDT da usare.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        popStandaloneScreenModels()
        Dispatchers.resetMain()
    }

    @Test
    fun `appModule risolve ogni singleton e ogni ViewModel su una cartella temporanea`() {
        val dirs = AppDirs(tmp).ensure()
        val app = koinApplication {
            allowOverride(true)
            modules(appModule, module { single { dirs } })
        }
        val koin = app.koin
        try {
            assertSame(dirs, koin.get<AppDirs>(), "AppDirs deve essere quella del test, mai ~/.protocollo17")

            // ── singleton, nell'ordine dei blocchi di AppModule ──
            koin.get<SettingsRepository>()
            koin.get<TrafficRecorder>()
            assertSame(koin.get<TrafficRecorder>(), koin.get<TrafficRepository>(), "TrafficRepository è il TrafficRecorder")
            koin.get<TcpTransport>()
            koin.get<Ecr17Client>()
            koin.get<JournalStore>()
            koin.get<JournalRepository>()
            koin.get<PosTerminalRepository>()
            koin.get<SimulatorController>()
            koin.get<AppNotifications>()
            koin.get<ShortcutBus>()
            koin.get<AppWindows>()
            assertSame(koin.get<TerminalSessionService>(), koin.get<CommandRunner>(), "CommandRunner è il TerminalSessionService")
            assertSame(koin.get<Ecr17Client>(), koin.get<Ecr17Client>(), "Ecr17Client è un singleton")

            // ── ViewModel: factory, un'istanza nuova a ogni richiesta ──
            val viewModels = listOf<ScreenModel>(
                koin.get<ConsoleViewModel>(),
                koin.get<OptionsViewModel>(),
                koin.get<TrafficLogViewModel>(),
                koin.get<JournalViewModel>(),
                koin.get<SimulatorViewModel>(),
            )
            assertEquals(5, viewModels.size)
            val second = koin.get<ConsoleViewModel>()
            assertNotSame(viewModels.first(), second, "i ViewModel sono factory, non singleton")
            (viewModels + second).forEach { it.onDispose() }
        } finally {
            closeLikeMain(koin)
            app.close()
        }
    }

    /** Stesso ordine di `Main.closeAppResources` (§6.18): un errore di chiusura qui è un errore anche all'uscita. */
    private fun closeLikeMain(koin: Koin) {
        koin.get<TerminalSessionService>().shutdown(timeoutMs = 3_000)
        koin.get<SimulatorController>().close()
        koin.get<Ecr17Client>().close()
        koin.get<TrafficRecorder>().close()
        koin.get<JournalRepository>().close()
        koin.get<SettingsRepository>().close()
    }
}
