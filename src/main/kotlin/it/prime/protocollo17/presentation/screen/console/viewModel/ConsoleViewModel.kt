package it.prime.protocollo17.presentation.screen.console.viewModel

import cafe.adriel.voyager.core.model.ScreenModel
import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.domain.repository.PosTerminalRepository
import it.prime.protocollo17.domain.repository.SimulatorController
import it.prime.protocollo17.presentation.state.CommandRunner

// STUB di WP0 (§8.2): costruttore definitivo di §6.5 (usato da AppModule), corpo a carico di WP6.
// uiState / events / onAction NON sono dichiarati qui: i loro tipi (ConsoleUiState, ConsoleAction, ConsoleEvent)
// stanno in ConsoleContract.kt, che è di WP6 e dipende da altri tipi di WP6 (ResultView, ResolveDialogModel…).
// WP6 li aggiunge con le firme di §6.5:
//   val uiState: StateFlow<ConsoleUiState>; val events: Flow<ConsoleEvent>; fun onAction(action: ConsoleAction)

/**
 * ViewModel della finestra principale (Console dei comandi). Osserva lo stato condiviso (runner, collegamento,
 * journal, simulatore) e non esegue mai comandi da sé: passa sempre da [CommandRunner], che rifiuta se occupato.
 */
class ConsoleViewModel(
    private val runner: CommandRunner,
    private val pos: PosTerminalRepository,
    private val journal: JournalRepository,
    private val settings: SettingsRepository,
    private val simulator: SimulatorController,
) : ScreenModel {

    override fun onDispose(): Unit = TODO("WP6: ConsoleViewModel.kt")
}
