package it.prime.protocollo17.presentation.screen.journal.viewModel

import cafe.adriel.voyager.core.model.ScreenModel
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.presentation.state.CommandRunner

// STUB di WP0 (§8.2): costruttore definitivo di §6.13 (usato da AppModule), corpo a carico di WP6.
// uiState / events / onAction NON sono dichiarati qui: i loro tipi (JournalUiState, JournalAction, JournalEvent)
// stanno in JournalContract.kt, che è di WP6 e dipende da altri tipi di WP6 (ResultTone, FieldRow,
// ResolveDialogModel). WP6 li aggiunge con le firme di §6.13.

/**
 * ViewModel della finestra Journal: mostra i tentativi e permette di risolverli (G o chiusura con nota). G passa
 * da [CommandRunner] come qualsiasi altro comando: mai automatico (U7) e mai in parallelo a un pagamento (R12).
 */
class JournalViewModel(private val journal: JournalRepository, private val runner: CommandRunner) : ScreenModel
