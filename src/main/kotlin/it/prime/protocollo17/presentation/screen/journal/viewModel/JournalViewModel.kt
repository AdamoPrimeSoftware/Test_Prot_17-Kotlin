package it.prime.protocollo17.presentation.screen.journal.viewModel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import it.prime.protocollo17.domain.model.command.CommandTitles
import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.JournalRecord
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.domain.util.GAssessment
import it.prime.protocollo17.domain.util.formatCents
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.presentation.components.ResolveDialogModel
import it.prime.protocollo17.presentation.model.AttemptTexts
import it.prime.protocollo17.presentation.model.FieldLabels
import it.prime.protocollo17.presentation.model.FieldRow
import it.prime.protocollo17.presentation.model.ResultViewMapper
import it.prime.protocollo17.presentation.state.CommandRunner
import it.prime.protocollo17.presentation.state.FinishedRun
import it.prime.protocollo17.presentation.state.RunOrigin
import it.prime.protocollo17.presentation.state.RunStart
import it.prime.protocollo17.presentation.util.FailureMessages
import it.prime.protocollo17.presentation.util.disposeSafely
import it.prime.protocollo17.presentation.util.formatDateTime
import it.prime.protocollo17.presentation.util.formatFullDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * ViewModel della finestra Journal: mostra i tentativi e permette di risolverli (G o chiusura con nota). G passa
 * da [CommandRunner] come qualsiasi altro comando: mai automatico (U7) e mai in parallelo a un pagamento (R12).
 *
 * Il dialog dopo G si apre per il tentativo selezionato quando G è partito (o, se non era da risolvere, per il
 * più recente non risolto) e solo per un G lanciato da questa finestra: quello della Console apre il proprio.
 */
class JournalViewModel(private val journal: JournalRepository, private val runner: CommandRunner) : ScreenModel {

    private val log = LoggerFactory.getLogger(JournalViewModel::class.java)

    private val _uiState = MutableStateFlow(JournalUiState(filePath = journalPath()))
    val uiState: StateFlow<JournalUiState> = _uiState.asStateFlow()

    private val _events = Channel<JournalEvent>(Channel.BUFFERED)
    val events: Flow<JournalEvent> = _events.receiveAsFlow()

    private val filterFlow = MutableStateFlow(JournalFilter.ALL)
    private val selectedFlow = MutableStateFlow<String?>(null)

    /** Tentativo selezionato quando è partito G: il dialog si apre per lui. */
    private var gTarget: String? = null
    private var gForDialog: Pair<String, CommandOutcome.Answered>? = null

    init {
        observeJournal()
        screenModelScope.launch {
            runner.state.map { it.running != null }.distinctUntilChanged().collect { busy ->
                _uiState.update { it.copy(runnerBusy = busy) }
            }
        }
        screenModelScope.launch { runner.finished.collect { onFinished(it) } }
    }

    fun onAction(action: JournalAction) {
        when (action) {
            is JournalAction.Select -> selectedFlow.value = action.attemptId
            is JournalAction.SetFilter -> filterFlow.value = action.filter
            JournalAction.RunLastResult -> runLastResult()
            is JournalAction.OpenResolve -> openResolve(action.attemptId)
            is JournalAction.Resolve -> resolve(action)
            JournalAction.DismissDialog -> {
                gForDialog = null
                _uiState.update { it.copy(dialog = null) }
            }
        }
    }

    override fun onDispose() = disposeSafely(this) { }

    // ─── Lista e dettaglio ────────────────────────────────────────────────────────

    private fun observeJournal() {
        screenModelScope.launch {
            combine(journal.entries, journal.unresolved, filterFlow, selectedFlow) { entries, unresolved, filter, selected ->
                // entries contiene già tutti i non risolti (§5.3.2); l'unione è una rete se così non fosse.
                val all = (entries + unresolved.filter { u -> entries.none { it.attemptId == u.attemptId } })
                    .sortedByDescending { it.createdAt }
                val filtered = when (filter) {
                    JournalFilter.ALL -> all
                    JournalFilter.UNRESOLVED -> all.filter { it.isUnresolved }
                    JournalFilter.TODAY -> all.filter { isToday(it.createdAt) }
                }
                val detail = all.firstOrNull { it.attemptId == selected }?.let(::detailOf)
                JournalUiStatePart(filtered.map(::rowOf), filter, selected, detail, unresolved.size)
            }.collect { part ->
                _uiState.update {
                    it.copy(
                        rows = part.rows, filter = part.filter, selectedId = part.selectedId,
                        detail = part.detail, unresolvedCount = part.unresolvedCount,
                    )
                }
            }
        }
    }

    private data class JournalUiStatePart(
        val rows: List<JournalRowView>,
        val filter: JournalFilter,
        val selectedId: String?,
        val detail: JournalDetailView?,
        val unresolvedCount: Int,
    )

    private fun rowOf(entry: JournalEntry) = JournalRowView(
        attemptId = entry.attemptId,
        time = formatDateTime(entry.createdAt),
        letter = entry.command?.wire,
        title = AttemptTexts.title(entry),
        phaseLabel = if (entry.persisted) AttemptTexts.phaseLabel(entry) else "${AttemptTexts.phaseLabel(entry)} (non scritto su disco)",
        tone = AttemptTexts.tone(entry),
        sends = entry.sends,
        resolved = entry.phase == JournalPhase.RESOLVED,
        persisted = entry.persisted,
    )

    private fun detailOf(entry: JournalEntry): JournalDetailView {
        val created = entry.records.firstOrNull { it.phase == JournalPhase.CREATED }
        val result = entry.resultRecord
        val failure = entry.failureRecord
        val resolution = entry.resolutionRecord
        val summary = buildList {
            add(FieldRow("Stato", AttemptTexts.phaseLabel(entry)))
            if (entry.isUnresolved) add(FieldRow("Da risolvere", AttemptTexts.reason(entry)))
            add(FieldRow("Comando", entry.command?.let { "${it.wire} · ${CommandTitles.title(it)}" } ?: "illeggibile (record CREATED mancante)"))
            entry.amountCents?.let { add(FieldRow("Importo", formatCents(it))) }
            entry.requestSummary.takeIf { it.isNotBlank() }?.let { add(FieldRow("Richiesta", it)) }
            entry.endpoint.takeIf { it.isNotBlank() }?.let { add(FieldRow("Terminale", it)) }
            entry.profile.takeIf { it.isNotBlank() }?.let { add(FieldRow("Profilo", it)) }
            created?.terminalId?.let { add(FieldRow("Terminal ID", it)) }
            created?.cashRegisterId?.let { add(FieldRow("ECR id", it)) }
            created?.optionsSummary?.let { add(FieldRow("Opzioni", it)) }
            add(FieldRow("Invii", entry.sends.toString()))
            result?.let { r ->
                r.outcome?.let { add(FieldRow("Esito", it + (r.verdict?.let { v -> " ($v)" } ?: ""))) }
                r.replyCode?.let { add(FieldRow("Codice risposta", it)) }
                r.resultCode?.let { add(FieldRow("Codice esito", it)) }
                r.stan?.let { add(FieldRow("STAN", it)) }
                r.onlineId?.let { add(FieldRow("Online id", it)) }
                r.authCode?.let { add(FieldRow("Codice autorizzazione", it)) }
                r.hostDateTime?.let { add(FieldRow("Data/ora host", FieldLabels.hostDateTime(it))) }
                r.ackMode?.let { add(FieldRow("Modalità ACK", it)) }
                r.naksReceived?.let { add(FieldRow("NAK ricevuti", it.toString())) }
                r.timeoutRetransmissions?.let { add(FieldRow("Reinvii per ACK mancante", it.toString())) }
                if (r.needsReview == true) add(FieldRow("Da verificare", AttemptTexts.reviewReason(r.reviewReason)))
            }
            failure?.let { f ->
                add(FieldRow("Motivo", AttemptTexts.failureText(f.failure)))
                f.failurePhase?.let { add(FieldRow("Fase dell'interruzione", it)) }
            }
            resolution?.let { r ->
                add(FieldRow("Risoluzione", AttemptTexts.resolutionLabel(r.resolution)))
                r.note?.let { add(FieldRow("Nota", it)) }
                r.gVerdict?.let { add(FieldRow("Verdetto di G", it)) }
                r.gStan?.let { add(FieldRow("STAN di G", it)) }
            }
            if (!entry.persisted) add(FieldRow("Journal", "ultimo record NON scritto su disco: tentativo bloccante in memoria"))
        }
        return JournalDetailView(
            attemptId = entry.attemptId,
            title = AttemptTexts.title(entry),
            summary = summary,
            timeline = entry.records.map(::timelineRow),
            rawResult = result?.rawResult ?: entry.receivedRecord?.rawResult,
            evidence = AttemptTexts.evidence(entry),
            isUnresolved = entry.isUnresolved,
            needsReview = entry.needsReview,
            blocksPayments = entry.blocksPayments,
            persisted = entry.persisted,
            json = entry.records.joinToString("\n") { JSON.encodeToString(JournalRecord.serializer(), it) },
        )
    }

    private fun timelineRow(record: JournalRecord): FieldRow {
        val details = listOfNotNull(
            record.sends?.let { "invio $it" },
            record.outcome?.let { "esito $it" },
            record.failure?.let { AttemptTexts.failureText(it) },
            record.evidenceNote,
            record.resolution?.let { AttemptTexts.resolutionLabel(it) },
            record.note?.let { "nota: $it" },
        )
        val value = formatFullDateTime(record.at) + if (details.isEmpty()) "" else " · " + details.joinToString(" · ")
        return FieldRow(AttemptTexts.recordPhaseLabel(record.phase), value, "#${record.seq}")
    }

    // ─── G e risoluzione ──────────────────────────────────────────────────────────

    private fun runLastResult() {
        if (_uiState.value.runnerBusy) return
        val selected = selectedFlow.value
        val target = selected?.takeIf { id -> journal.unresolved.value.any { it.attemptId == id } }
        when (val start = runner.run(LastResultRequest(), RunOrigin.JOURNAL)) {
            RunStart.Started -> gTarget = target
            else -> FailureMessages.forRunStart(start)?.let { _events.trySend(JournalEvent.ShowError(it)) }
        }
    }

    private fun onFinished(run: FinishedRun) {
        if (run.origin != RunOrigin.JOURNAL || run.command != CommandCode.LAST_RESULT) return
        val target = gTarget
        gTarget = null
        val g = run.outcome as? CommandOutcome.Answered
        if (g == null) {
            val reason = FailureMessages.forOutcome(run.outcome) ?: "nessuna risposta"
            _events.trySend(JournalEvent.ShowError("Ultimo esito (G) senza risposta: $reason"))
            return
        }
        val unresolved = journal.unresolved.value
        val attempt = unresolved.firstOrNull { it.attemptId == target } ?: unresolved.maxByOrNull { it.createdAt }
        if (attempt == null) {
            _events.trySend(JournalEvent.ShowInfo("Ultimo esito (G) ricevuto: nessun tentativo da risolvere"))
            return
        }
        gForDialog = attempt.attemptId to g
        val model = ResolveDialogModel(
            attemptId = attempt.attemptId,
            attemptTitle = AttemptTexts.title(attempt),
            attemptTime = formatDateTime(attempt.createdAt),
            reason = AttemptTexts.reason(attempt),
            reviewOnly = attempt.needsReview,
            gResult = ResultViewMapper.map(run),
            assessment = GAssessment.assess(attempt, journal.knownResultsBefore(attempt.attemptId), g),
            evidence = AttemptTexts.evidence(attempt),
            otherUnresolved = (unresolved.size - 1).coerceAtLeast(0),
        )
        _uiState.update { it.copy(dialog = JournalDialog.Resolve(model)) }
    }

    private fun openResolve(attemptId: String) {
        val unresolved = journal.unresolved.value
        val attempt = unresolved.firstOrNull { it.attemptId == attemptId } ?: run {
            _events.trySend(JournalEvent.ShowInfo("Il tentativo non è da risolvere"))
            return
        }
        gForDialog = null
        val model = ResolveDialogModel(
            attemptId = attempt.attemptId,
            attemptTitle = AttemptTexts.title(attempt),
            attemptTime = formatDateTime(attempt.createdAt),
            reason = AttemptTexts.reason(attempt),
            reviewOnly = attempt.needsReview,
            gResult = null,
            assessment = null,
            evidence = AttemptTexts.evidence(attempt),
            otherUnresolved = (unresolved.size - 1).coerceAtLeast(0),
        )
        _uiState.update { it.copy(dialog = JournalDialog.Resolve(model)) }
    }

    private fun resolve(action: JournalAction.Resolve) {
        val note = action.note.trim()
        if (note.isEmpty()) {
            _events.trySend(JournalEvent.ShowError("La nota è obbligatoria: scrivi come hai verificato l'esito"))
            return
        }
        val gOutcome = gForDialog?.takeIf { it.first == action.attemptId }?.second
        screenModelScope.launch {
            try {
                journal.resolve(action.attemptId, action.kind, note, gOutcome)
                gForDialog = null
                _uiState.update { it.copy(dialog = null) }
                _events.trySend(JournalEvent.ShowInfo("Tentativo chiuso: ${AttemptTexts.resolutionLabel(action.kind)}"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Risoluzione del tentativo {} non riuscita", action.attemptId, e)
                _events.trySend(JournalEvent.ShowError("Chiusura del tentativo non riuscita: ${e.message}. Il tentativo resta bloccante."))
            }
        }
    }

    private fun journalPath(): String = try {
        journal.journalFile().absolutePath
    } catch (e: Exception) {
        log.warn("Percorso del journal non disponibile", e)
        ""
    }

    private companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val JSON = Json {
            explicitNulls = false
            encodeDefaults = false
        }

        fun isToday(epochMs: Long): Boolean =
            Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
    }
}
