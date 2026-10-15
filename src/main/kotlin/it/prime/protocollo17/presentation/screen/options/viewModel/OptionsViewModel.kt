package it.prime.protocollo17.presentation.screen.options.viewModel

import cafe.adriel.voyager.core.model.ScreenModel
import it.prime.protocollo17.data.local.AppDirs
import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.presentation.state.CommandRunner

// STUB di WP0 (§8.2): costruttore definitivo di §6.11 (usato da AppModule), corpo a carico di WP6.
// uiState / events / onAction NON sono dichiarati qui: i loro tipi (OptionsUiState, OptionsAction, OptionsEvent)
// stanno in OptionsContract.kt, che è di WP6. WP6 li aggiunge con le firme di §6.11.

/**
 * ViewModel della finestra Opzioni: valida i testi con i range dell'operatore (ConfigResolver) e salva subito
 * solo i valori validi, lasciando visibile accanto al campo il testo sbagliato e il suo errore.
 */
class OptionsViewModel(
    private val settings: SettingsRepository,
    private val dirs: AppDirs,
    private val runner: CommandRunner,
) : ScreenModel
