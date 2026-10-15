package it.prime.protocollo17.presentation.screen.traffic.viewModel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import it.prime.protocollo17.domain.model.traffic.TrafficEntry
import it.prime.protocollo17.domain.model.traffic.TrafficKind
import it.prime.protocollo17.domain.model.traffic.TrafficSeverity
import it.prime.protocollo17.domain.repository.TrafficRepository
import it.prime.protocollo17.presentation.util.disposeSafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * ViewModel della finestra Log traffico: filtra il ring buffer del registratore fuori dall'EDT (fino a 5 000
 * voci pubblicate a 10 Hz) e non ferma mai la registrazione, nemmeno in pausa.
 *
 * Il filtro gira su Dispatchers.Default con `conflate`: rifiltrare 5 000 voci sul thread della UI a ogni
 * pubblicazione bloccherebbe la finestra durante un comando, proprio quando si guarda il log (R30). La pausa
 * congela l'istantanea mostrata (le nuove voci continuano ad arrivare nel registratore e su file).
 */
class TrafficLogViewModel(private val traffic: TrafficRepository) : ScreenModel {

    private val log = LoggerFactory.getLogger(TrafficLogViewModel::class.java)

    private val _uiState = MutableStateFlow(TrafficLogUiState(directory = traffic.directory.absolutePath))
    val uiState: StateFlow<TrafficLogUiState> = _uiState.asStateFlow()

    private val _events = Channel<TrafficLogEvent>(Channel.BUFFERED)
    val events: Flow<TrafficLogEvent> = _events.receiveAsFlow()

    private val filterFlow = MutableStateFlow(TrafficFilter())

    /** Istantanea congelata dalla pausa; null = vista in diretta. */
    private val frozenFlow = MutableStateFlow<List<TrafficEntry>?>(null)

    init {
        observeEntries()
        screenModelScope.launch {
            traffic.currentFile.collect { file -> _uiState.update { it.copy(currentFile = file?.absolutePath) } }
        }
    }

    fun onAction(action: TrafficLogAction) {
        when (action) {
            is TrafficLogAction.SetText -> updateFilter { it.copy(text = action.text) }
            TrafficLogAction.ToggleFrames -> updateFilter { it.copy(showFrames = !it.showFrames) }
            TrafficLogAction.ToggleEvents -> updateFilter { it.copy(showEvents = !it.showEvents) }
            TrafficLogAction.ToggleOnlyWarnings -> updateFilter { it.copy(onlyWarnings = !it.onlyWarnings) }
            TrafficLogAction.ToggleAscii -> updateFilter { it.copy(showAscii = !it.showAscii) }
            TrafficLogAction.TogglePause -> {
                val pause = frozenFlow.value == null
                frozenFlow.value = if (pause) traffic.entries.value else null
                _uiState.update { it.copy(paused = pause) }
            }
            is TrafficLogAction.SetAutoScroll -> _uiState.update { it.copy(autoScroll = action.enabled) }
            TrafficLogAction.ClearMemory -> {
                traffic.clearMemory()
                if (frozenFlow.value != null) frozenFlow.value = emptyList()
            }
            is TrafficLogAction.Export -> export(action)
        }
    }

    override fun onDispose() = disposeSafely(this) { }

    private fun updateFilter(transform: (TrafficFilter) -> TrafficFilter) {
        val filter = transform(filterFlow.value)
        filterFlow.value = filter
        // Il filtro si vede subito nei controlli; le righe arrivano dal calcolo fuori dall'EDT.
        _uiState.update { it.copy(filter = filter) }
    }

    private fun observeEntries() {
        screenModelScope.launch {
            combine(traffic.entries, filterFlow, frozenFlow) { live, filter, frozen -> Triple(live, filter, frozen) }
                .conflate()
                .map { (live, filter, frozen) -> applyFilter(frozen ?: live, filter) to live.size }
                .flowOn(Dispatchers.Default)
                .collect { (visible, total) -> _uiState.update { it.copy(visible = visible, totalInMemory = total) } }
        }
    }

    private fun export(action: TrafficLogAction.Export) {
        val entries = _uiState.value.visible
        screenModelScope.launch {
            try {
                traffic.export(action.target, entries)
                    .onSuccess { lines -> _events.trySend(TrafficLogEvent.ShowInfo("Esportate $lines righe in ${action.target.name}")) }
                    .onFailure { e -> _events.trySend(TrafficLogEvent.ShowError("Esportazione non riuscita: ${e.message}")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Esportazione del log traffico fallita", e)
                _events.trySend(TrafficLogEvent.ShowError("Esportazione non riuscita: ${e.message}"))
            }
        }
    }

    companion object {
        private val FRAME_KINDS = setOf(TrafficKind.TX, TrafficKind.RX, TrafficKind.DISCARDED)

        /** Pura: frame/eventi, solo avvisi e testo (case-insensitive su riepilogo, hex, ASCII e lettera). */
        fun applyFilter(entries: List<TrafficEntry>, filter: TrafficFilter): List<TrafficEntry> {
            val query = filter.text.trim()
            return entries.filter { entry ->
                val isFrame = entry.kind in FRAME_KINDS
                if (isFrame && !filter.showFrames) return@filter false
                if (!isFrame && !filter.showEvents) return@filter false
                if (filter.onlyWarnings && !isWarning(entry)) return@filter false
                query.isEmpty() ||
                    entry.summary.contains(query, ignoreCase = true) ||
                    entry.hex?.contains(query, ignoreCase = true) == true ||
                    entry.ascii?.contains(query, ignoreCase = true) == true ||
                    entry.command?.toString() == query
            }
        }

        fun isWarning(entry: TrafficEntry): Boolean =
            entry.kind == TrafficKind.DISCARDED || entry.kind == TrafficKind.WARNING ||
                entry.severity == TrafficSeverity.WARNING || entry.severity == TrafficSeverity.ERROR
    }
}
