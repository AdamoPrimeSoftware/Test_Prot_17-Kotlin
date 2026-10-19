package it.prime.protocollo17.di

import it.prime.protocollo17.data.local.AppDirs
import it.prime.protocollo17.data.local.journal.JournalStore
import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.data.local.simulator.SimulatorControllerImpl
import it.prime.protocollo17.data.local.traffic.TrafficRecorder
import it.prime.protocollo17.data.repository.JournalRepositoryImpl
import it.prime.protocollo17.data.repository.PosTerminalRepositoryImpl
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.domain.repository.PosTerminalRepository
import it.prime.protocollo17.domain.repository.SimulatorController
import it.prime.protocollo17.domain.repository.TrafficRepository
import it.prime.protocollo17.domain.util.ConfigResolver
import it.prime.protocollo17.ecr17.client.Ecr17Client
import it.prime.protocollo17.ecr17.transport.TcpTransport
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
import org.koin.dsl.module

/**
 * Unico modulo Koin: è tutta la composition root dell'app (skill di-koin), a blocchi nell'ordine
 * fondamenta → log → protocollo → persistenza → repository → stato app → ViewModel.
 *
 * I valori configurabili arrivano come provider (`{ settings.getSnapshot()… }`) e non come istantanee: una
 * modifica in Opzioni vale dal comando successivo senza riavvio. Il package `ecr17` non conosce Koin: qui si
 * costruisce e basta. Modifiche successive a questo file solo in integrazione (§8.10).
 */
val appModule = module {
    // ── fondamenta ──
    single { AppDirs.default().ensure() }
    single { SettingsRepository(file = get<AppDirs>().settingsFile) }

    // ── log traffico: sink degli eventi ECR17 ──
    single {
        TrafficRecorder(
            dir = get<AppDirs>().trafficDir,
            settingsProvider = { get<SettingsRepository>().getSnapshot().traffic },
        )
    }
    single<TrafficRepository> { get<TrafficRecorder>() }

    // ── comunicazione ECR17 (package puro: nessuna dipendenza da Koin al suo interno) ──
    single { TcpTransport() }
    single {
        val settings = get<SettingsRepository>()
        Ecr17Client(
            configProvider = { ConfigResolver.resolve(settings.getSnapshot()).config },
            transport = get<TcpTransport>(),
            eventSink = get<TrafficRecorder>(),
        )
    }

    // ── persistenza ──
    single { JournalStore(dir = get<AppDirs>().journalDir) }
    single<JournalRepository> {
        JournalRepositoryImpl(store = get(), settingsProvider = { get<SettingsRepository>().getSnapshot().journal })
    }

    // ── repository ──
    single<PosTerminalRepository> {
        PosTerminalRepositoryImpl(client = get(), journal = get(), settingsProvider = { get<SettingsRepository>().getSnapshot() })
    }
    single<SimulatorController> { SimulatorControllerImpl(settings = get()) }

    // ── stato app ──
    single { AppNotifications() }
    single { ShortcutBus() }
    single { AppWindows(settings = get()) }
    single { TerminalSessionService(repository = get(), journal = get(), notifications = get()) }
    single<CommandRunner> { get<TerminalSessionService>() }

    // ── ViewModel (ScreenModel Voyager) ──
    factory { ConsoleViewModel(runner = get(), pos = get(), journal = get(), settings = get(), simulator = get()) }
    factory { OptionsViewModel(settings = get(), dirs = get(), runner = get()) }
    factory { TrafficLogViewModel(traffic = get()) }
    factory { JournalViewModel(journal = get(), runner = get()) }
    factory { SimulatorViewModel(simulator = get(), settings = get(), runner = get(), notifications = get()) }
}
