package it.prime.protocollo17.presentation.screen.traffic.viewModel

import cafe.adriel.voyager.core.model.ScreenModel
import it.prime.protocollo17.domain.repository.TrafficRepository

// STUB di WP0 (§8.2): costruttore definitivo di §6.12 (usato da AppModule), corpo a carico di WP6.
// uiState / events / onAction NON sono dichiarati qui: i loro tipi (TrafficLogUiState, TrafficLogAction,
// TrafficLogEvent) stanno in TrafficLogContract.kt, che è di WP6. WP6 li aggiunge con le firme di §6.12.

/**
 * ViewModel della finestra Log traffico: filtra il ring buffer del registratore fuori dall'EDT (fino a 5 000
 * voci pubblicate a 10 Hz) e non ferma mai la registrazione, nemmeno in pausa.
 */
class TrafficLogViewModel(private val traffic: TrafficRepository) : ScreenModel
