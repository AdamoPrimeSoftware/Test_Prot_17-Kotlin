package it.prime.protocollo17.presentation.screen.journal.viewModel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import it.prime.protocollo17.domain.model.command.CommandTitles
import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.JournalRecord
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.domain.util.GAssessment
import it.prime.protocollo17.domain.util.GAssessmentResult
import it.prime.protocollo17.domain.util.formatCents
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.presentation.components.ResolveDialogModel
import it.prime.protocollo17.presentation.model.AttemptTexts
import it.prime.protocollo17.presentation.model.FieldLabels
import it.prime.protocollo17.presentation.model.FieldRow
import it.prime.protocollo17.presentation.model.ResultView
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
import kotlinx.coroutines.Job
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
 * L'esito di G si lega solo al più recente non risolto (G riporta l'ultima transazione): un tentativo più vecchio
 * si chiude a mano (§6.8).
 */
class JournalViewModel(private val journal: JournalRepository, private val runner: CommandRunner) : ScreenModel {

    private val log = LoggerFactory.getLogger(JournalViewModel::class.java)

    private val _uiState = MutableStateFlow(JournalUiState(filePath = journalPath()))
    val uiState: StateFlow<JournalUiState> = _uiState.asStateFlow()

    private val _events = Channel<JournalEvent>(Channel.BUFFERED)
    val events: Flow<JournalEvent> = _events.receiveAsFlow()

    private val filterFlow = MutableStateFlow(JournalFilter.ALL)
    private val selectedFlow = MutableStateFlow<String?>(null)

    /** Tentativo selezionato quando è partito G: il dialog si apre per lui (con G solo se è il più recente). */
    private var gTarget: String? = null
    private var gForDialog: Pair<String, CommandOutcome.Answered>? = null

    /** Chiusura di un tentativo in scrittura nel journal: un secondo Resolve (doppio clic) è ignorato. */
    private var resolveJob: Job? = null

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

    /**
     * G concluso: l'esito si lega SOLO al tentativo non risolto più recente, come nella Console, perché G riporta
     * soltanto l'ultima transazione del terminale (§6.8). Se l'operatore aveva selezionato un tentativo più vecchio,
     * il dialog si apre per quello ma senza G (chiusura a mano con la spunta "ho verificato") e spiega a quale
     * tentativo appartiene l'esito: offrirgli "L'esito G appartiene al tentativo" (con un suggerimento calcolato su
     * prove che escludono i tentativi successivi) inviterebbe a registrare su un pagamento l'esito di un altro.
     */
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
        val latest = unresolved.maxByOrNull { it.createdAt }
        if (latest == null) {
            _events.trySend(JournalEvent.ShowInfo("Ultimo esito (G) ricevuto: nessun tentativo da risolvere"))
            return
        }
        val selected = unresolved.firstOrNull { it.attemptId == target }
        if (selected != null && selected.attemptId != latest.attemptId) {
            gForDialog = null
            val notice = "L'esito di Ultimo esito (G) riguarda il tentativo più recente (${AttemptTexts.title(latest)} " +
                "delle ${formatDateTime(latest.createdAt)}), non questo: G riporta solo l'ultima transazione del " +
                "terminale. Chiudi questo tentativo a mano dopo averlo verificato sullo scontrino o sul terminale."
            val model = dialogModel(selected, unresolved.size, gResult = null, assessment = null, notice = notice)
            _uiState.update { it.copy(dialog = JournalDialog.Resolve(model)) }
            return
        }
        gForDialog = latest.attemptId to g
        val model = dialogModel(
            attempt = latest,
            unresolvedCount = unresolved.size,
            gResult = ResultViewMapper.map(run),
            assessment = GAssessment.assess(latest, journal.knownResultsBefore(latest.attemptId), g),
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
        _uiState.update { it.copy(dialog = JournalDialog.Resolve(dialogModel(attempt, unresolved.size, gResult = null, assessment = null))) }
    }

    private fun dialogModel(
        attempt: JournalEntry,
        unresolvedCount: Int,
        gResult: ResultView?,
        assessment: GAssessmentResult?,
        notice: String? = null,
    ) = ResolveDialogModel(
        attemptId = attempt.attemptId,
        attemptTitle = AttemptTexts.title(attempt),
        attemptTime = formatDateTime(attempt.createdAt),
        reason = AttemptTexts.reason(attempt),
        reviewOnly = attempt.needsReview,
        gResult = gResult,
        assessment = assessment,
        evidence = AttemptTexts.evidence(attempt),
        otherUnresolved = (unresolvedCount - 1).coerceAtLeast(0),
        notice = notice,
    )

    /**
     * Chiusura del tentativo: una sola scrittura alla volta (decisioni disabilitate con `resolving`, un secondo
     * Resolve ignorato), come nella Console. Un doppio clic non tenta più una seconda chiusura già rifiutata.
     */
    private fun resolve(action: JournalAction.Resolve) {
        if (resolveJob?.isActive == true) return
        val note = action.note.trim()
        if (note.isEmpty()) {
            _events.trySend(JournalEvent.ShowError("La nota è obbligatoria: scrivi come hai verificato l'esito"))
            return
        }
        val gOutcome = gForDialog?.takeIf { it.first == action.attemptId }?.second
        setResolving(action.attemptId, resolving = true)
        resolveJob = screenModelScope.launch {
            try {
                journal.resolve(action.attemptId, action.kind, note, gOutcome)
                if (gForDialog?.first == action.attemptId) gForDialog = null
                _uiState.update { state ->
                    val open = (state.dialog as? JournalDialog.Resolve)?.model?.attemptId
                    if (open == action.attemptId) state.copy(dialog = null) else state
                }
                _events.trySend(JournalEvent.ShowInfo("Tentativo chiuso: ${AttemptTexts.resolutionLabel(action.kind)}"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Risoluzione del tentativo {} non riuscita", action.attemptId, e)
                setResolving(action.attemptId, resolving = false)
                _events.trySend(JournalEvent.ShowError("Chiusura del tentativo non riuscita: ${e.message}. Il tentativo resta bloccante."))
            }
        }
    }

    private fun setResolving(attemptId: String, resolving: Boolean) {
        _uiState.update { state ->
            val dialog = state.dialog as? JournalDialog.Resolve
            if (dialog?.model?.attemptId == attemptId) {
                state.copy(dialog = JournalDialog.Resolve(dialog.model.copy(resolving = resolving)))
            } else {
                state
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
