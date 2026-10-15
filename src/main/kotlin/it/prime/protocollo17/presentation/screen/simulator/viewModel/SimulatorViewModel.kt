package it.prime.protocollo17.presentation.screen.simulator.viewModel

import cafe.adriel.voyager.core.model.ScreenModel
import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.domain.repository.SimulatorController

// STUB di WP0 (§8.2): costruttore definitivo di §6.14 (usato da AppModule), corpo a carico di WP6.
// uiState / events / onAction NON sono dichiarati qui: i loro tipi (SimulatorUiState, SimulatorAction,
// SimulatorEvent) stanno in SimulatorContract.kt, che è di WP6. WP6 li aggiunge con le firme di §6.14.

/**
 * ViewModel della finestra Simulatore: scrive le impostazioni del simulatore e lascia al controller avvio,
 * riavvio al cambio porta e traduzione della porta occupata in stato utente (§5.6).
 */
class SimulatorViewModel(private val simulator: SimulatorController, private val settings: SettingsRepository) : ScreenModel
