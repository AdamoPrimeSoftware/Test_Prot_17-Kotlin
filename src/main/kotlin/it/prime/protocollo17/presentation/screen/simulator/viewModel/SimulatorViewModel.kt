package it.prime.protocollo17.presentation.screen.simulator.viewModel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.domain.model.settings.SimulatorSettings
import it.prime.protocollo17.domain.model.simulator.SimCatalog
import it.prime.protocollo17.domain.model.simulator.SimOption
import it.prime.protocollo17.domain.repository.SimulatorController
import it.prime.protocollo17.presentation.util.disposeSafely
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel della finestra Simulatore: scrive le impostazioni del simulatore e lascia al controller avvio,
 * riavvio al cambio porta e traduzione della porta occupata in stato utente (§5.6).
 *
 * Le scelte (guasti, decisioni, LRC) vengono da SimCatalog: la presentation non può importare `ecr17.sim` (§2.10)
 * e un valore fuori catalogo non arriva mai nelle impostazioni. La porta occupata non è un'eccezione ma uno stato
 * (`statusError` accanto al campo porta): è un caso normale, un'altra istanza o un altro programma.
 */
class SimulatorViewModel(private val simulator: SimulatorController, private val settings: SettingsRepository) : ScreenModel {

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<SimulatorUiState> = _uiState.asStateFlow()

    private val _events = Channel<SimulatorEvent>(Channel.BUFFERED)
    val events: Flow<SimulatorEvent> = _events.receiveAsFlow()

    /** Porta valida digitata ma non ancora salvata (debounce di [PORT_COMMIT_DELAY_MS]). */
    private var pendingPort: Int? = null
    private var portCommit: Job? = null

    init {
        screenModelScope.launch {
            settings.flow.map { it.simulator }.distinctUntilChanged().collect { sim ->
                _uiState.update { state ->
                    // I campi senza errore seguono le impostazioni (anche se cambiate altrove); un testo sbagliato resta,
                    // e anche una porta digitata ma non ancora salvata (altrimenti tornerebbe al valore vecchio).
                    val synced = NUMBER_FIELDS
                        .filter { it !in state.fieldErrors && !(it == FIELD_PORT && pendingPort != null) }
                        .associateWith { numberText(sim, it) }
                    state.copy(settings = sim, texts = state.texts + synced)
                }
            }
        }
        screenModelScope.launch { simulator.status.collect { s -> _uiState.update { it.copy(status = s) } } }
        screenModelScope.launch { simulator.stats.collect { s -> _uiState.update { it.copy(stats = s) } } }
        screenModelScope.launch { simulator.log.collect { l -> _uiState.update { it.copy(log = l) } } }
        screenModelScope.launch { simulator.isTarget.collect { t -> _uiState.update { it.copy(isTarget = t) } } }
    }

    fun onAction(action: SimulatorAction) {
        when (action) {
            SimulatorAction.Start -> {
                // Una porta appena digitata vale per questo avvio, senza aspettare il debounce.
                flushPendingPort()
                screenModelScope.launch { simulator.start() }
            }
            SimulatorAction.Stop -> screenModelScope.launch { simulator.stop() }
            is SimulatorAction.SetNumber -> setNumber(action.field, action.text)
            is SimulatorAction.SetAppLrcMode -> setChoice(action.wireName, SimCatalog.lrcModes, "LRC applicativo") { s, v -> s.copy(appLrcMode = v) }
            is SimulatorAction.SetAckRule -> setChoice(action.rule, SimCatalog.ackRules, "ACK") { s, v -> s.copy(ackRule = v) }
            is SimulatorAction.SetDecision -> setChoice(action.decision, SimCatalog.decisions, "Decisione") { s, v -> s.copy(decision = v) }
            is SimulatorAction.SetFault -> setChoice(action.fault, SimCatalog.faults, "Guasto") { s, v -> s.copy(fault = v) }
            is SimulatorAction.SetFaultOnlyFinancial -> updateSim { it.copy(faultOnlyFinancial = action.enabled) }
            is SimulatorAction.SetCloseAfterTransaction -> updateSim { it.copy(closeAfterTransaction = action.enabled) }
            is SimulatorAction.SetRetransmitResult -> updateSim { it.copy(retransmitResultIfNoValidAck = action.enabled) }
            is SimulatorAction.SetReceiptAfterResult -> updateSim { it.copy(receiptAfterResult = action.enabled) }
            is SimulatorAction.SetAutoStart -> updateSim { it.copy(autoStart = action.enabled) }
            SimulatorAction.UseAsTarget -> if (simulator.useAsTarget()) {
                _events.trySend(SimulatorEvent.ShowInfo("Il terminale ora è il simulatore: i comandi vanno a 127.0.0.1"))
            } else {
                _events.trySend(SimulatorEvent.ShowError("Avvia prima il simulatore"))
            }
            SimulatorAction.RestoreTarget -> if (simulator.restoreTarget()) {
                _events.trySend(SimulatorEvent.ShowInfo("Destinazione del terminale ripristinata"))
            } else {
                _events.trySend(SimulatorEvent.ShowError("Nessuna destinazione precedente da ripristinare"))
            }
            SimulatorAction.ClearLog -> simulator.clearLog()
            SimulatorAction.ResetState -> {
                simulator.resetState()
                _events.trySend(SimulatorEvent.ShowInfo("Stato del simulatore azzerato (totali ed esito trattenuto)"))
            }
        }
    }

    /** Chiudendo la finestra subito dopo aver scritto la porta, la porta non va persa: si salva ora. */
    override fun onDispose() = disposeSafely(this) { flushPendingPort() }

    private fun initialState(): SimulatorUiState {
        val sim = settings.getSnapshot().simulator
        return SimulatorUiState(settings = sim, texts = NUMBER_FIELDS.associateWith { numberText(sim, it) })
    }

    private fun updateSim(transform: (SimulatorSettings) -> SimulatorSettings) {
        settings.update { s -> s.copy(simulator = transform(s.simulator)) }
    }

    private fun setChoice(value: String, options: List<SimOption>, label: String, apply: (SimulatorSettings, String) -> SimulatorSettings) {
        if (options.none { it.name == value }) {
            _events.trySend(SimulatorEvent.ShowError("$label: valore '$value' non previsto"))
            return
        }
        updateSim { apply(it, value) }
    }

    /**
     * Porta 1..65535; ritardi 0..600 000 ms. I ritardi si salvano subito (valgono dalla prossima richiesta); la porta
     * no: il controller riavvia il simulatore a ogni cambio di porta (§5.6), e salvarla a ogni tasto lo riavvierebbe
     * su ogni valore intermedio (scrivendo 16000: 1, 16, 160, 1600, 16000). Una porta intermedia occupata darebbe un
     * errore di "porta in uso" nella finestra principale e un comando in corso verso il simulatore verrebbe troncato
     * con esito sconosciuto. La porta si salva [PORT_COMMIT_DELAY_MS] dopo l'ultimo tasto, o subito con Avvia e alla
     * chiusura della finestra.
     */
    private fun setNumber(field: String, text: String) {
        _uiState.update { it.copy(texts = it.texts + (field to text)) }
        val value = text.trim().toLongOrNull()
        val error = when (field) {
            FIELD_PORT -> if (value == null || value !in 1L..65_535L) "Porta tra 1 e 65535" else null
            FIELD_ACK_DELAY, FIELD_PROGRESS_DELAY, FIELD_RESULT_DELAY ->
                if (value == null || value !in 0L..MAX_DELAY_MS) "Ritardo tra 0 e $MAX_DELAY_MS ms" else null
            else -> "Campo sconosciuto"
        }
        if (error != null || value == null) {
            // Un testo di porta sbagliato annulla anche il salvataggio in attesa del valore precedente.
            if (field == FIELD_PORT) cancelPendingPort()
            _uiState.update { it.copy(fieldErrors = it.fieldErrors + (field to (error ?: "Valore non valido"))) }
            return
        }
        _uiState.update { it.copy(fieldErrors = it.fieldErrors - field) }
        if (field == FIELD_PORT) {
            schedulePortCommit(value.toInt())
            return
        }
        updateSim { sim ->
            when (field) {
                FIELD_ACK_DELAY -> sim.copy(ackDelayMs = value)
                FIELD_PROGRESS_DELAY -> sim.copy(progressDelayMs = value)
                else -> sim.copy(resultDelayMs = value)
            }
        }
    }

    private fun schedulePortCommit(port: Int) {
        portCommit?.cancel()
        pendingPort = port
        portCommit = screenModelScope.launch {
            delay(PORT_COMMIT_DELAY_MS)
            commitPendingPort()
        }
    }

    private fun flushPendingPort() {
        portCommit?.cancel()
        portCommit = null
        commitPendingPort()
    }

    private fun cancelPendingPort() {
        portCommit?.cancel()
        portCommit = null
        pendingPort = null
    }

    private fun commitPendingPort() {
        val port = pendingPort ?: return
        pendingPort = null
        if (settings.getSnapshot().simulator.port != port) updateSim { it.copy(port = port) }
    }

    companion object {
        const val FIELD_PORT = "port"
        const val FIELD_ACK_DELAY = "ackDelayMs"
        const val FIELD_PROGRESS_DELAY = "progressDelayMs"
        const val FIELD_RESULT_DELAY = "resultDelayMs"
        private const val MAX_DELAY_MS = 600_000L

        /** Pausa di digitazione dopo cui la porta si salva (e il simulatore attivo si riavvia). */
        const val PORT_COMMIT_DELAY_MS = 800L
        private val NUMBER_FIELDS = listOf(FIELD_PORT, FIELD_ACK_DELAY, FIELD_PROGRESS_DELAY, FIELD_RESULT_DELAY)

        private fun numberText(sim: SimulatorSettings, field: String): String = when (field) {
            FIELD_PORT -> sim.port.toString()
            FIELD_ACK_DELAY -> sim.ackDelayMs.toString()
            FIELD_PROGRESS_DELAY -> sim.progressDelayMs.toString()
            else -> sim.resultDelayMs.toString()
        }
    }
}
