package it.prime.protocollo17.presentation.screen.options.viewModel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import it.prime.protocollo17.data.local.AppDirs
import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.domain.model.settings.OptionKey
import it.prime.protocollo17.domain.model.settings.ProtocolSettings
import it.prime.protocollo17.domain.util.ConfigResolver
import it.prime.protocollo17.ecr17.profile.TerminalProfiles
import it.prime.protocollo17.ecr17.wire.ControlLrcRule
import it.prime.protocollo17.ecr17.wire.Hex
import it.prime.protocollo17.ecr17.wire.LrcMode
import it.prime.protocollo17.presentation.state.CommandRunner
import it.prime.protocollo17.presentation.util.disposeSafely
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * ViewModel della finestra Opzioni: valida i testi con i range dell'operatore (ConfigResolver) e salva subito
 * solo i valori validi, lasciando visibile accanto al campo il testo sbagliato e il suo errore.
 *
 * Prima di `texts`/`hostText`/`idTexts` un valore non valido veniva scartato e il campo tornava al valore
 * precedente: sembrava che il campo non accettasse l'input (R31). Le modifiche valgono dal comando successivo:
 * il client legge un'istantanea della configurazione per ogni comando, mai a metà di uno scambio.
 */
class OptionsViewModel(
    private val settings: SettingsRepository,
    private val dirs: AppDirs,
    private val runner: CommandRunner,
) : ScreenModel {

    private val log = LoggerFactory.getLogger(OptionsViewModel::class.java)

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<OptionsUiState> = _uiState.asStateFlow()

    private val _events = Channel<OptionsEvent>(Channel.BUFFERED)
    val events: Flow<OptionsEvent> = _events.receiveAsFlow()

    init {
        observeSettings()
        observeRunner()
    }

    fun onAction(action: OptionsAction) {
        when (action) {
            is OptionsAction.SetProfile -> settings.update { s -> s.copy(terminal = s.terminal.copy(profile = action.id.name)) }
            is OptionsAction.SetHost -> setHost(action.text)
            is OptionsAction.SetPort -> setPort(action.text)
            is OptionsAction.SetTerminalId -> setId(KEY_TERMINAL_ID, action.text)
            is OptionsAction.SetCashRegisterId -> setId(KEY_CASH_REGISTER_ID, action.text)
            is OptionsAction.SetOption -> setOption(action.key, action.text)
            OptionsAction.ResetToProfile -> resetToProfile()
            is OptionsAction.SetTheme -> settings.update { s -> s.copy(ui = s.ui.copy(themeMode = action.mode)) }
            is OptionsAction.SetConfirmCloseSession ->
                settings.update { s -> s.copy(ui = s.ui.copy(confirmCloseSession = action.enabled)) }
            is OptionsAction.SetTrafficFileEnabled ->
                settings.update { s -> s.copy(traffic = s.traffic.copy(fileEnabled = action.enabled)) }
            is OptionsAction.SetTrafficRetentionDays -> setRetention(action.text)
            is OptionsAction.SetFilter -> _uiState.update { it.copy(filter = action.text) }
        }
    }

    override fun onDispose() = disposeSafely(this) { }

    // ─── Osservazione ─────────────────────────────────────────────────────────────

    private fun initialState(): OptionsUiState {
        val s = settings.getSnapshot()
        return OptionsUiState(
            settings = s,
            hostText = s.terminal.host,
            portText = s.terminal.port?.toString().orEmpty(),
            idTexts = mapOf(KEY_TERMINAL_ID to s.terminal.terminalId, KEY_CASH_REGISTER_ID to s.terminal.cashRegisterId),
            settingsPath = dirs.settingsFile.absolutePath,
            dataFolder = dirs.root.absolutePath,
            controlPreview = controlPreviewOf(s.protocol),
        )
    }

    private fun observeSettings() {
        screenModelScope.launch {
            settings.flow.collect { s ->
                val resolution = ConfigResolver.resolve(s)
                _uiState.update { state ->
                    state.copy(
                        settings = s,
                        resolution = resolution,
                        controlPreview = controlPreviewOf(s.protocol),
                        // Un cambio da fuori (Usa simulatore come terminale) si vede, ma non sovrascrive un testo
                        // sbagliato che l'operatore sta ancora correggendo.
                        hostText = if (state.hostError == null) s.terminal.host else state.hostText,
                        portText = if (state.portError == null) s.terminal.port?.toString().orEmpty() else state.portText,
                        idTexts = state.idTexts + listOfNotNull(
                            (KEY_TERMINAL_ID to s.terminal.terminalId).takeIf { KEY_TERMINAL_ID !in state.idErrors },
                            (KEY_CASH_REGISTER_ID to s.terminal.cashRegisterId).takeIf { KEY_CASH_REGISTER_ID !in state.idErrors },
                        ),
                    )
                }
            }
        }
    }

    private fun observeRunner() {
        screenModelScope.launch {
            runner.state.map { it.running != null }.distinctUntilChanged().collect { running ->
                _uiState.update { it.copy(commandRunning = running) }
            }
        }
    }

    // ─── Terminale ────────────────────────────────────────────────────────────────

    private fun setHost(text: String) {
        val host = text.trim()
        if (host.isEmpty()) {
            _uiState.update { it.copy(hostText = text, hostError = "Inserisci l'indirizzo IP o il nome del terminale") }
            return
        }
        _uiState.update { it.copy(hostText = text, hostError = null) }
        settings.update { s -> s.copy(terminal = s.terminal.copy(host = host)) }
    }

    private fun setPort(text: String) {
        val trimmed = text.trim()
        val port = trimmed.toIntOrNull()
        when {
            trimmed.isEmpty() -> {
                _uiState.update { it.copy(portText = text, portError = null) }
                settings.update { s -> s.copy(terminal = s.terminal.copy(port = null)) }
            }
            port != null && port in 1..65_535 -> {
                _uiState.update { it.copy(portText = text, portError = null) }
                settings.update { s -> s.copy(terminal = s.terminal.copy(port = port)) }
            }
            else -> _uiState.update { it.copy(portText = text, portError = "Porta tra 1 e 65535 (vuoto = porta del profilo)") }
        }
    }

    private fun setId(key: String, text: String) {
        val value = text.trim()
        val valid = value.isNotEmpty() && value.length <= 8 && value.all { it in '0'..'9' }
        if (!valid) {
            val label = if (key == KEY_TERMINAL_ID) "Terminal ID" else "ECR id"
            _uiState.update {
                it.copy(idTexts = it.idTexts + (key to text), idErrors = it.idErrors + (key to "$label: da 1 a 8 cifre (es. 00000000)"))
            }
            return
        }
        _uiState.update { it.copy(idTexts = it.idTexts + (key to text), idErrors = it.idErrors - key) }
        settings.update { s ->
            val terminal = if (key == KEY_TERMINAL_ID) s.terminal.copy(terminalId = value) else s.terminal.copy(cashRegisterId = value)
            s.copy(terminal = terminal)
        }
    }

    // ─── Opzioni di protocollo ────────────────────────────────────────────────────

    private fun setOption(key: OptionKey, text: String) {
        _uiState.update { it.copy(texts = it.texts + (key to text)) }
        ConfigResolver.parseInput(key, text)
            .onSuccess { normalized ->
                settings.update { s -> s.copy(protocol = ConfigResolver.withValue(s.protocol, key, normalized)) }
                _uiState.update { it.copy(fieldErrors = it.fieldErrors - key) }
            }
            .onFailure { e ->
                _uiState.update { it.copy(fieldErrors = it.fieldErrors + (key to (e.message ?: "Valore non valido"))) }
            }
    }

    private fun resetToProfile() {
        val profileName = settings.getSnapshot().terminal.profile
        val profile = TerminalProfiles.all.firstOrNull { it.id.name == profileName } ?: TerminalProfiles.INGENICO
        settings.update { s -> s.copy(protocol = ConfigResolver.profileDefaults(profile)) }
        _uiState.update { it.copy(texts = emptyMap(), fieldErrors = emptyMap()) }
        _events.trySend(OptionsEvent.ShowInfo("Valori del profilo ${profile.displayName} ripristinati"))
    }

    private fun setRetention(text: String) {
        val days = text.trim().toIntOrNull()
        if (days == null || days !in 1..3_650) {
            _events.trySend(OptionsEvent.ShowError("Giorni di conservazione del log: un numero tra 1 e 3650"))
            return
        }
        settings.update { s -> s.copy(traffic = s.traffic.copy(retentionDays = days)) }
    }

    /** "ACK 06 03 7A · NAK 15 03 69": frame di controllo che l'app invierà con le impostazioni correnti. */
    private fun controlPreviewOf(protocol: ProtocolSettings): String {
        val mode = LrcMode.fromWireName(protocol.appLrcMode)
        val rule = ControlLrcRule.entries.firstOrNull { it.name == protocol.controlLrcTx }
        if (mode == null || rule == null) return "Valori LRC non validi nel file: correggili qui sotto"
        return try {
            "ACK ${Hex.spaced(rule.encode(ACK_BYTE, mode))} · NAK ${Hex.spaced(rule.encode(NAK_BYTE, mode))}"
        } catch (e: Exception) {
            log.warn("Anteprima dei frame di controllo non calcolabile", e)
            "Anteprima non disponibile"
        }
    }

    private companion object {
        const val KEY_TERMINAL_ID = "terminalId"
        const val KEY_CASH_REGISTER_ID = "cashRegisterId"

        /** Costanti locali: la presentation non può importare ControlChars (§2.10). */
        const val ACK_BYTE = 0x06
        const val NAK_BYTE = 0x15
    }
}
