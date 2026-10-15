package it.prime.protocollo17.presentation.screen.options.viewModel

import it.prime.protocollo17.domain.model.command.ConfigResolution
import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.model.settings.OptionKey
import it.prime.protocollo17.domain.model.settings.ThemeMode
import it.prime.protocollo17.ecr17.profile.TerminalProfileId

// Contratto MVI della finestra Opzioni (§6.11).

data class OptionsUiState(
    val settings: AppSettings = AppSettings(),
    val resolution: ConfigResolution? = null,
    /** Testo in modifica dei campi numerici (valido o no): l'errore resta accanto al testo sbagliato. */
    val texts: Map<OptionKey, String> = emptyMap(),
    val fieldErrors: Map<OptionKey, String> = emptyMap(),
    val hostText: String = "",
    val hostError: String? = null,
    val portText: String = "",
    val portError: String? = null,
    /** Testo in modifica di TID ed ECR id: chiavi "terminalId" | "cashRegisterId". */
    val idTexts: Map<String, String> = emptyMap(),
    val idErrors: Map<String, String> = emptyMap(),
    val filter: String = "",
    val settingsPath: String = "",
    val dataFolder: String = "",                         // aperta dalla view con openInExplorer
    val commandRunning: Boolean = false,                 // mostra "le modifiche valgono dal prossimo comando"
    /** Anteprima dei frame di controllo in uscita, calcolata dal VM con ControlLrcRule.encode + Hex.spaced: "ACK 06 03 7A · NAK 15 03 69". */
    val controlPreview: String = "ACK 06 03 7A · NAK 15 03 69",
)

sealed interface OptionsAction {
    data class SetProfile(val id: TerminalProfileId) : OptionsAction
    data class SetHost(val text: String) : OptionsAction
    data class SetPort(val text: String) : OptionsAction          // vuoto = porta del profilo
    data class SetTerminalId(val text: String) : OptionsAction
    data class SetCashRegisterId(val text: String) : OptionsAction
    data class SetOption(val key: OptionKey, val text: String) : OptionsAction
    data object ResetToProfile : OptionsAction                     // "Ripristina valori del profilo"
    data class SetTheme(val mode: ThemeMode) : OptionsAction
    data class SetConfirmCloseSession(val enabled: Boolean) : OptionsAction
    data class SetTrafficFileEnabled(val enabled: Boolean) : OptionsAction
    data class SetTrafficRetentionDays(val text: String) : OptionsAction
    data class SetFilter(val text: String) : OptionsAction
}

sealed interface OptionsEvent {
    data class ShowError(val message: String) : OptionsEvent
    data class ShowInfo(val message: String) : OptionsEvent
}
