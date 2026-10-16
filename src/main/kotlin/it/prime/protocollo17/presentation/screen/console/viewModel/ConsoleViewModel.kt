package it.prime.protocollo17.presentation.screen.console.viewModel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.domain.model.command.CommandTitles
import it.prime.protocollo17.domain.model.command.FieldKey
import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.simulator.SimulatorFailure
import it.prime.protocollo17.domain.model.simulator.SimulatorStatus
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.domain.repository.PosTerminalRepository
import it.prime.protocollo17.domain.repository.SimulatorController
import it.prime.protocollo17.domain.util.GAssessment
import it.prime.protocollo17.domain.util.PaymentBlockPolicy
import it.prime.protocollo17.domain.util.RequestBuild
import it.prime.protocollo17.domain.util.RequestFactory
import it.prime.protocollo17.ecr17.model.CloseCause
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.ecr17.model.LinkState
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.ecr17.model.StatusResponse
import it.prime.protocollo17.ecr17.wire.Hex
import it.prime.protocollo17.presentation.components.ResolveDialogModel
import it.prime.protocollo17.presentation.model.AttemptTexts
import it.prime.protocollo17.presentation.model.FieldLabels
import it.prime.protocollo17.presentation.model.HistoryRowView
import it.prime.protocollo17.presentation.model.ResultView
import it.prime.protocollo17.presentation.model.ResultViewMapper
import it.prime.protocollo17.presentation.screen.console.model.CommandCatalog
import it.prime.protocollo17.presentation.state.CancelResult
import it.prime.protocollo17.presentation.state.CommandRunner
import it.prime.protocollo17.presentation.state.FinishedRun
import it.prime.protocollo17.presentation.state.RunOrigin
import it.prime.protocollo17.presentation.state.RunStart
import it.prime.protocollo17.presentation.state.RunnerState
import it.prime.protocollo17.presentation.util.FailureMessages
import it.prime.protocollo17.presentation.util.disposeSafely
import it.prime.protocollo17.presentation.util.formatDateTime
import it.prime.protocollo17.presentation.util.formatTime
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
import org.slf4j.LoggerFactory

/**
 * ViewModel della finestra principale (Console dei comandi). Osserva lo stato condiviso (runner, collegamento,
 * journal, simulatore) e non esegue mai comandi da sé: passa sempre da [CommandRunner], che rifiuta se occupato.
 *
 * Regole di money-safety che passano di qui: un comando non si accoda e non si riavvia mai (M7: `run` rifiuta,
 * nessun `cancelAndJoin` per avviarne un altro); nessun timeout attorno a un comando (M8); l'annullamento di un
 * comando journaled già inviato chiede conferma perché lascia l'esito sconosciuto (U7); `G` parte solo da
 * un'azione dell'operatore e il dialog di risoluzione si apre dopo, con il suggerimento di GAssessment (M6).
 * I form leggono le bozze dalle impostazioni UNA volta e poi le aggiornano in modo sincrono: rileggerle dal flow
 * a ogni tasto farebbe saltare il cursore o perdere caratteri nel TextField.
 */
class ConsoleViewModel(
    private val runner: CommandRunner,
    private val pos: PosTerminalRepository,
    private val journal: JournalRepository,
    private val settings: SettingsRepository,
    private val simulator: SimulatorController,
) : ScreenModel {

    private val log = LoggerFactory.getLogger(ConsoleViewModel::class.java)

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    private val _events = Channel<ConsoleEvent>(Channel.BUFFERED)
    val events: Flow<ConsoleEvent> = _events.receiveAsFlow()

    /** Richiesta in attesa del dialog ConfirmRun (chiusura contabile). */
    private var pendingRun: Ecr17Request? = null

    /** F5/Stato lanciato dalla barra: il suo esito diventa un evento (§6.5). */
    private var pingPending = false

    /** Esito di G mostrato nel dialog di risoluzione, per tentativo: va scritto nel journal con la risoluzione. */
    private var gForDialog: Pair<String, CommandOutcome.Answered>? = null

    /** Comando (lettera e istante di avvio) per cui è aperto ConfirmCancelAfterSend: la conferma vale solo per lui. */
    private var cancelTarget: Pair<CommandCode, Long>? = null

    /** Chiusura di un tentativo in scrittura nel journal: un secondo Resolve (doppio clic) è ignorato. */
    private var resolveJob: Job? = null

    // Cache delle viste per FinishedRun.id: lo stato del runner cambia a ogni fase, gli esiti conclusi no.
    private val viewCache = HashMap<Long, ResultView>()
    private val rowCache = HashMap<Long, HistoryRowView>()
    private var lastHistory: List<FinishedRun>? = null
    private var lastHistoryText = ""

    init {
        observeSettings()
        observeRunner()
        observeLink()
        observeJournal()
        observeSimulator()
        observeFinished()
    }

    fun onAction(action: ConsoleAction) {
        when (action) {
            is ConsoleAction.Select -> select(action.command)
            ConsoleAction.SelectNext -> selectRelative(+1)
            ConsoleAction.SelectPrevious -> selectRelative(-1)
            is ConsoleAction.UpdateField -> updateField(action.command, action.key, action.value)
            is ConsoleAction.ResetForm -> resetForm(action.command)
            is ConsoleAction.CopyToForm -> copyToForm(action.target, action.key, action.value)
            ConsoleAction.RunSelected -> runSelected()
            ConsoleAction.ConfirmRun -> confirmRun()
            ConsoleAction.PingStatus -> pingStatus()
            ConsoleAction.RequestCancel -> requestCancel()
            ConsoleAction.ConfirmCancel -> confirmCancel()
            ConsoleAction.RecoverWithG -> if (_uiState.value.canRecoverWithG) start(LastResultRequest())
            is ConsoleAction.OpenResolve -> openResolve(action.attemptId)
            is ConsoleAction.Resolve -> resolve(action)
            ConsoleAction.DismissDialog -> closeDialog()
            ConsoleAction.TestConnection -> testConnection()
            ConsoleAction.Connect -> connection(connect = true)
            ConsoleAction.Disconnect -> connection(connect = false)
            is ConsoleAction.SetResultTab -> _uiState.update { it.copy(resultTab = action.tab) }
            is ConsoleAction.ShowHistoryItem -> showHistoryItem(action.id)
            ConsoleAction.ClearHistory -> {
                runner.clearHistory()
                _uiState.update { it.copy(viewedHistory = null) }
            }
            ConsoleAction.StartSimulator -> screenModelScope.launch { simulator.start() }
            ConsoleAction.StopSimulator -> screenModelScope.launch { simulator.stop() }
            ConsoleAction.UseSimulatorAsTarget -> {
                if (simulator.useAsTarget()) {
                    emit(ConsoleEvent.ShowSuccess("Il terminale ora è il simulatore (127.0.0.1)"))
                } else {
                    emit(ConsoleEvent.ShowError("Avvia prima il simulatore"))
                }
            }
            ConsoleAction.RestoreTerminal -> {
                if (simulator.restoreTarget()) {
                    emit(ConsoleEvent.ShowInfo("Destinazione del terminale ripristinata"))
                } else {
                    emit(ConsoleEvent.ShowError("Nessuna destinazione precedente da ripristinare"))
                }
            }
        }
    }

    override fun onDispose() = disposeSafely(this) { }

    // ─── Stato iniziale e osservazione dello stato condiviso ─────────────────────

    private fun initialState(): ConsoleUiState {
        val snapshot = settings.getSnapshot()
        val persisted = decodeDrafts(snapshot.drafts)
        val drafts = CommandCode.entries.associateWith { code ->
            val defaults = RequestFactory.defaults(code)
            defaults + persisted[code].orEmpty().filterKeys { it in defaults }
        }
        val selected = snapshot.ui.lastCommand.singleOrNull()?.let(CommandCode::fromWire) ?: CommandCode.PAYMENT
        return ConsoleUiState(selected = selected, drafts = drafts)
    }

    private fun observeSettings() {
        screenModelScope.launch {
            settings.flow.map { it.terminal to it.protocol }.distinctUntilChanged().collect {
                val view = connectionFromConfig()
                _uiState.update { state ->
                    state.copy(
                        connection = view.copy(
                            link = state.connection.link,
                            lastSeenTerminalId = state.connection.lastSeenTerminalId,
                            testing = state.connection.testing,
                            switching = state.connection.switching,
                        ),
                    )
                }
            }
        }
    }

    /** Dalla configurazione effettiva (via repository: il VM non chiama ConfigResolver ed è testabile con un mock). */
    private fun connectionFromConfig(): ConnectionView {
        val resolution = try {
            pos.effectiveConfig()
        } catch (e: Exception) {
            log.error("Configurazione effettiva non leggibile", e)
            return ConnectionView(configUsable = false, configMessages = listOf("Configurazione non leggibile: ${e.message}"))
        }
        val options = resolution.config.options
        val ack = options.controlLrcTx.encode(ACK_BYTE, options.appLrcMode)
        return ConnectionView(
            profileName = resolution.profile.displayName,
            evidence = resolution.profile.evidence,
            endpoint = resolution.config.endpoint.toString(),
            connectionMode = options.connectionMode,
            ackPolicy = options.financialAckPolicy,
            lrcSummary = "${options.appLrcMode.wireName} · ACK ${Hex.spaced(ack)}",
            configUsable = resolution.usable,
            configMessages = resolution.issues.map { it.message } +
                resolution.problems.map(FailureMessages::forConfigProblem) +
                resolution.warnings.map { it.message },
        )
    }

    private fun observeRunner() {
        screenModelScope.launch {
            runner.state.collect { state ->
                val results = state.lastByCommand.mapValues { (_, run) -> viewOf(run) }
                val rows = state.history.map(::rowOf)
                val text = historyTextOf(state.history)
                trimCaches(state)
                _uiState.update {
                    // Il comando è finito mentre si decideva se interromperlo: la domanda non ha più senso.
                    val staleCancel = state.running == null && it.dialog is ConsoleDialog.ConfirmCancelAfterSend
                    it.copy(
                        running = state.running,
                        results = results,
                        history = rows,
                        historyText = text,
                        connection = it.connection.copy(lastSeenTerminalId = state.lastSeenTerminalId),
                        dialog = if (staleCancel) null else it.dialog,
                    )
                }
            }
        }
    }

    private fun observeLink() {
        screenModelScope.launch {
            pos.linkState.collect { link -> _uiState.update { it.copy(connection = it.connection.copy(link = linkView(link))) } }
        }
    }

    private fun observeJournal() {
        screenModelScope.launch {
            journal.unresolved.collect { unresolved ->
                val views = unresolved.sortedByDescending { it.createdAt }.map(::unresolvedView)
                val blocked = PaymentBlockPolicy.blockedCommands(unresolved)
                _uiState.update { it.copy(unresolved = views, blockedCommands = blocked) }
            }
        }
    }

    private fun observeSimulator() {
        screenModelScope.launch {
            combine(simulator.status, simulator.isTarget) { status, isTarget ->
                SimulatorView(status = status, isTarget = isTarget, label = simulatorLabel(status, isTarget))
            }.collect { view -> _uiState.update { it.copy(simulator = view) } }
        }
    }

    private fun observeFinished() {
        screenModelScope.launch {
            runner.finished.collect { onFinished(it) }
        }
    }

    private fun onFinished(run: FinishedRun) {
        if (run.origin != RunOrigin.CONSOLE) return
        if (run.command == CommandCode.STATUS && pingPending) {
            pingPending = false
            emit(pingEvent(run))
        }
        if (run.command == CommandCode.LAST_RESULT) {
            // G mai automatico (U7): qui si apre solo il dialog; la decisione resta all'operatore.
            val g = run.outcome as? CommandOutcome.Answered ?: return
            val unresolved = journal.unresolved.value
            val attempt = unresolved.maxByOrNull { it.createdAt } ?: return
            gForDialog = attempt.attemptId to g
            val model = resolveModel(attempt, unresolved.size, gRun = run, g = g)
            _uiState.update { it.copy(dialog = ConsoleDialog.Resolve(model)) }
        }
    }

    // ─── Form ─────────────────────────────────────────────────────────────────────

    private fun select(command: CommandCode) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(selected = command, fieldErrors = emptyMap(), viewedHistory = null) }
        settings.update { s -> s.copy(ui = s.ui.copy(lastCommand = command.wire.toString())) }
    }

    private fun selectRelative(delta: Int) {
        val order = CommandCatalog.all.map { it.code }
        val index = order.indexOf(_uiState.value.selected).coerceAtLeast(0)
        select(order[(index + delta + order.size) % order.size])
    }

    private fun updateField(command: CommandCode, key: FieldKey, value: String) {
        // Importo condiviso tra P, X e p (come i campi condivisi dell'app WPF); i e c hanno importi propri.
        val targets = if (key == FieldKey.AMOUNT && command in SHARED_AMOUNT) SHARED_AMOUNT else setOf(command)
        _uiState.update { state ->
            val drafts = state.drafts.toMutableMap()
            targets.forEach { target -> drafts[target] = drafts[target].orEmpty() + (key to value) }
            state.copy(drafts = drafts)
        }
        settings.update { s ->
            var drafts = s.drafts
            targets.forEach { target ->
                val letter = target.wire.toString()
                drafts = drafts + (letter to drafts[letter].orEmpty() + (key.name to value))
            }
            s.copy(drafts = drafts)
        }
        if (command == _uiState.value.selected) {
            val error = (RequestFactory.build(command, valuesOf(command)) as? RequestBuild.Invalid)?.errors?.get(key)
            _uiState.update { it.copy(fieldErrors = if (error == null) it.fieldErrors - key else it.fieldErrors + (key to error)) }
        }
    }

    private fun resetForm(command: CommandCode) {
        val defaults = RequestFactory.defaults(command)
        val sharedAmount = defaults[FieldKey.AMOUNT]?.takeIf { command in SHARED_AMOUNT }
        _uiState.update { state ->
            val drafts = state.drafts.toMutableMap()
            drafts[command] = defaults
            if (sharedAmount != null) SHARED_AMOUNT.forEach { drafts[it] = drafts[it].orEmpty() + (FieldKey.AMOUNT to sharedAmount) }
            state.copy(drafts = drafts, fieldErrors = if (command == state.selected) emptyMap() else state.fieldErrors)
        }
        settings.update { s ->
            var drafts = s.drafts - command.wire.toString()
            if (sharedAmount != null) {
                SHARED_AMOUNT.filter { it != command }.forEach { other ->
                    val letter = other.wire.toString()
                    drafts = drafts + (letter to drafts[letter].orEmpty() + (FieldKey.AMOUNT.name to sharedAmount))
                }
            }
            s.copy(drafts = drafts)
        }
    }

    private fun copyToForm(target: CommandCode, key: FieldKey, value: String) {
        if (_uiState.value.busy) return
        updateField(target, key, value)
        select(target)
        val what = when (key) {
            FieldKey.PRE_AUTH_CODE -> "Codice"
            FieldKey.STAN -> "STAN"
            else -> "Valore"
        }
        emit(ConsoleEvent.ShowInfo("$what copiato in ${shortTitle(target)} (${target.wire})"))
    }

    private fun valuesOf(command: CommandCode): Map<FieldKey, String> =
        _uiState.value.drafts[command] ?: RequestFactory.defaults(command)

    // ─── Esecuzione ───────────────────────────────────────────────────────────────

    private fun runSelected() {
        val state = _uiState.value
        if (state.busy) return
        val command = state.selected
        if (!state.canRunSelected) {
            if (state.isBlocked(command)) openBlockedInfo(PaymentBlockPolicy.blocking(command, journal.unresolved.value))
            return
        }
        when (val built = RequestFactory.build(command, valuesOf(command))) {
            is RequestBuild.Invalid -> {
                _uiState.update { it.copy(fieldErrors = built.errors) }
                emit(ConsoleEvent.ShowError("Correggi i campi evidenziati"))
            }
            is RequestBuild.Ok -> {
                _uiState.update { it.copy(fieldErrors = emptyMap()) }
                val confirm = CommandCatalog.of(command).confirmText
                if (command == CommandCode.CLOSE_SESSION && confirm != null && settings.getSnapshot().ui.confirmCloseSession) {
                    pendingRun = built.request
                    _uiState.update { it.copy(dialog = ConsoleDialog.ConfirmRun(command, confirm)) }
                } else {
                    start(built.request)
                }
            }
        }
    }

    private fun confirmRun() {
        val request = pendingRun
        val dialog = _uiState.value.dialog
        pendingRun = null
        _uiState.update { it.copy(dialog = null) }
        if (request != null && dialog is ConsoleDialog.ConfirmRun) start(request)
    }

    private fun pingStatus() {
        val state = _uiState.value
        if (state.busy || state.linkBusy) return
        if (start(StatusRequest, showResult = false)) pingPending = true
    }

    /**
     * Un solo punto verso il runner: rifiuti tradotti in evento o dialog (M7: mai accodare). true = partito.
     * [showResult]: il pannello torna alla scheda Esito (e lascia la voce di cronologia che si stava guardando),
     * così l'esito del comando appena lanciato è quello visibile; F5 no, il suo esito arriva come avviso.
     *
     * Con un'operazione di collegamento in corso ([ConsoleUiState.linkBusy]) il comando non parte: il client è
     * occupato e lo rifiuterebbe con `NotSent(Busy)` dopo che il repository ha già scritto il tentativo nel journal.
     * Le azioni lo controllano già prima; qui resta la difesa per ConfirmRun di un dialog aperto prima del test.
     */
    private fun start(request: Ecr17Request, showResult: Boolean = true): Boolean {
        if (_uiState.value.linkBusy) {
            emit(ConsoleEvent.ShowError(LINK_BUSY_MESSAGE))
            return false
        }
        return when (val result = runner.run(request, RunOrigin.CONSOLE)) {
            RunStart.Started -> {
                if (showResult) _uiState.update { it.copy(viewedHistory = null, resultTab = ResultTab.RESULT) }
                true
            }
            RunStart.Busy, is RunStart.InvalidConfig -> {
                FailureMessages.forRunStart(result)?.let { emit(ConsoleEvent.ShowError(it)) }
                false
            }
            is RunStart.Blocked -> {
                openBlockedInfo(result.blocking)
                false
            }
        }
    }

    private fun openBlockedInfo(blocking: List<JournalEntry>) {
        _uiState.update { it.copy(dialog = ConsoleDialog.BlockedInfo(blocking.sortedByDescending { e -> e.createdAt }.map(::unresolvedView))) }
    }

    /**
     * Annulla (pulsante o Esc). La scelta "conferma o annulla subito" si fa sullo stato CORRENTE del runner, non
     * sulla copia in `_uiState`: quella arriva con un dispatch di ritardo, e un Esc premuto proprio mentre il
     * servizio passa a SENDING vedrebbe ancora `requestSent == false` e interromperebbe un pagamento già inviato
     * senza la conferma di §6.5. Resta una finestra minima (il servizio gira su Dispatchers.Default): se il runner
     * risponde comunque ABORTED_AFTER_SEND per un comando journaled, l'operatore lo sa subito.
     */
    private fun requestCancel() {
        val running = runner.state.value.running ?: return
        if (running.cancelRequested) return
        if (running.command.journaled && running.requestSent) {
            val message = if (running.command.movesMoney) {
                "Interrompere l'attesa? L'esito resterà SCONOSCIUTO e i pagamenti saranno bloccati finché non esegui " +
                    "Ultimo esito (G)."
            } else {
                "Interrompere l'attesa? L'esito resterà sconosciuto e un nuovo comando «${CommandTitles.title(running.command)}» " +
                    "sarà bloccato finché non lo verifichi."
            }
            cancelTarget = running.command to running.startedAt
            _uiState.update { it.copy(dialog = ConsoleDialog.ConfirmCancelAfterSend(running.command, message)) }
        } else {
            val result = runner.requestCancel()
            if (result == CancelResult.ABORTED_AFTER_SEND && running.command.journaled) {
                emit(ConsoleEvent.ShowError(abortedAfterSendMessage(running.command)))
            }
        }
    }

    /**
     * Conferma dell'annullamento dopo l'invio: annulla solo se il comando in corso è ancora quello per cui il
     * dialog è stato aperto (stesso comando, stesso avvio). Se nel frattempo è finito, o ne è partito un altro, la
     * domanda non vale più: si chiude il dialog e non si annulla nulla.
     */
    private fun confirmCancel() {
        val dialog = _uiState.value.dialog as? ConsoleDialog.ConfirmCancelAfterSend
        val target = cancelTarget
        closeDialog()
        if (dialog == null || target == null) return
        val running = runner.state.value.running
        if (running == null || dialog.command != target.first || (running.command to running.startedAt) != target) {
            emit(ConsoleEvent.ShowInfo("Il comando è già terminato: nulla da annullare"))
            return
        }
        if (running.cancelRequested) return
        if (runner.requestCancel() == CancelResult.NOT_RUNNING) {
            emit(ConsoleEvent.ShowInfo("Il comando è già terminato: nulla da annullare"))
        }
    }

    private fun abortedAfterSendMessage(command: CommandCode): String =
        if (command.movesMoney) {
            "La richiesta era già stata inviata: l'esito di «${CommandTitles.title(command)}» resterà SCONOSCIUTO e i " +
                "pagamenti saranno bloccati finché non esegui Ultimo esito (G)."
        } else {
            "La richiesta era già stata inviata: l'esito di «${CommandTitles.title(command)}» resterà sconosciuto finché " +
                "non lo verifichi."
        }

    // ─── Risoluzione dei tentativi ────────────────────────────────────────────────

    private fun openResolve(attemptId: String) {
        val unresolved = journal.unresolved.value
        val attempt = unresolved.firstOrNull { it.attemptId == attemptId } ?: run {
            emit(ConsoleEvent.ShowInfo("Il tentativo non è più da risolvere"))
            return
        }
        gForDialog = null
        _uiState.update { it.copy(dialog = ConsoleDialog.Resolve(resolveModel(attempt, unresolved.size, gRun = null, g = null))) }
    }

    /**
     * Chiusura del tentativo. Una sola scrittura alla volta: mentre `journal.resolve` lavora il dialog ha le
     * decisioni disabilitate (`resolving`) e un secondo Resolve è ignorato. Prima un doppio clic avviava una seconda
     * chiusura sullo stesso tentativo, che il repository rifiutava: l'operatore leggeva "Il tentativo resta
     * bloccante" subito dopo una chiusura riuscita.
     */
    private fun resolve(action: ConsoleAction.Resolve) {
        if (resolveJob?.isActive == true) return
        val note = action.note.trim()
        if (note.isEmpty()) {
            emit(ConsoleEvent.ShowError("La nota è obbligatoria: scrivi come hai verificato l'esito"))
            return
        }
        val gOutcome = gForDialog?.takeIf { it.first == action.attemptId }?.second
        setResolving(action.attemptId, resolving = true)
        resolveJob = screenModelScope.launch {
            try {
                journal.resolve(action.attemptId, action.kind, note, gOutcome)
                if (gForDialog?.first == action.attemptId) gForDialog = null
                // Chiude solo il dialog di QUESTO tentativo: nel frattempo l'operatore può averne aperto un altro.
                _uiState.update { if (it.resolveDialogAttempt() == action.attemptId) it.copy(dialog = null) else it }
                emit(ConsoleEvent.ShowSuccess("Tentativo chiuso: ${AttemptTexts.resolutionLabel(action.kind)}"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Risoluzione del tentativo {} non riuscita", action.attemptId, e)
                setResolving(action.attemptId, resolving = false)
                emit(ConsoleEvent.ShowError("Chiusura del tentativo non riuscita: ${e.message}. Il tentativo resta bloccante."))
            }
        }
    }

    private fun setResolving(attemptId: String, resolving: Boolean) {
        _uiState.update { state ->
            val dialog = state.dialog as? ConsoleDialog.Resolve
            if (dialog?.model?.attemptId == attemptId) {
                state.copy(dialog = ConsoleDialog.Resolve(dialog.model.copy(resolving = resolving)))
            } else {
                state
            }
        }
    }

    private fun ConsoleUiState.resolveDialogAttempt(): String? = (dialog as? ConsoleDialog.Resolve)?.model?.attemptId

    private fun resolveModel(attempt: JournalEntry, unresolvedCount: Int, gRun: FinishedRun?, g: CommandOutcome.Answered?) =
        ResolveDialogModel(
            attemptId = attempt.attemptId,
            attemptTitle = AttemptTexts.title(attempt),
            attemptTime = formatDateTime(attempt.createdAt),
            reason = AttemptTexts.reason(attempt),
            reviewOnly = attempt.needsReview,
            gResult = gRun?.let(::viewOf),
            assessment = g?.let { GAssessment.assess(attempt, journal.knownResultsBefore(attempt.attemptId), it) },
            evidence = AttemptTexts.evidence(attempt),
            otherUnresolved = (unresolvedCount - 1).coerceAtLeast(0),
        )

    private fun closeDialog() {
        pendingRun = null
        gForDialog = null
        cancelTarget = null
        _uiState.update { it.copy(dialog = null) }
    }

    // ─── Barra: test connessione, connetti, cronologia ────────────────────────────

    private fun testConnection() {
        val state = _uiState.value
        if (state.busy || state.linkBusy) return
        setTesting(true)
        screenModelScope.launch {
            try {
                val result = pos.testConnection()
                if (result.ok) {
                    emit(ConsoleEvent.ShowSuccess("Connessione riuscita in ${result.millis} ms"))
                } else {
                    val text = result.failure?.let { FailureMessages.forFailure(it, CommandCode.STATUS, unknown = false) }
                        ?: "Connessione a ${result.endpoint} non riuscita"
                    emit(ConsoleEvent.ShowError(text))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Test connessione fallito", e)
                emit(ConsoleEvent.ShowError("Test connessione non riuscito: ${e.message}"))
            } finally {
                setTesting(false)
            }
        }
    }

    private fun setTesting(testing: Boolean) {
        _uiState.update { it.copy(connection = it.connection.copy(testing = testing)) }
    }

    /** Connetti/Disconnetti: `switching` durante l'operazione, così i comandi restano disabilitati (vedi linkBusy). */
    private fun connection(connect: Boolean) {
        val state = _uiState.value
        if (state.busy || state.linkBusy) return
        setSwitching(true)
        screenModelScope.launch {
            try {
                val failure = if (connect) pos.connect() else pos.disconnect()
                when {
                    failure != null -> emit(ConsoleEvent.ShowError(FailureMessages.forFailure(failure, CommandCode.STATUS, unknown = false)))
                    connect -> emit(ConsoleEvent.ShowInfo("Connesso a ${_uiState.value.connection.endpoint}"))
                    else -> emit(ConsoleEvent.ShowInfo("Connessione chiusa"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Connessione/disconnessione fallita", e)
                emit(ConsoleEvent.ShowError("Operazione non riuscita: ${e.message}"))
            } finally {
                setSwitching(false)
            }
        }
    }

    private fun setSwitching(switching: Boolean) {
        _uiState.update { it.copy(connection = it.connection.copy(switching = switching)) }
    }

    private fun showHistoryItem(id: Long?) {
        if (id == null) {
            _uiState.update { it.copy(viewedHistory = null) }
            return
        }
        val run = runner.state.value.history.firstOrNull { it.id == id } ?: return
        _uiState.update { it.copy(viewedHistory = viewOf(run), resultTab = ResultTab.RESULT) }
    }

    // ─── Mappature ────────────────────────────────────────────────────────────────

    private fun viewOf(run: FinishedRun): ResultView = viewCache.getOrPut(run.id) { ResultViewMapper.map(run) }

    private fun rowOf(run: FinishedRun): HistoryRowView = rowCache.getOrPut(run.id) { ResultViewMapper.historyRow(run) }

    private fun trimCaches(state: RunnerState) {
        val alive = state.history.mapTo(HashSet()) { it.id } + state.lastByCommand.values.map { it.id }
        viewCache.keys.retainAll(alive)
        rowCache.keys.retainAll(alive)
    }

    private fun historyTextOf(history: List<FinishedRun>): String {
        if (history === lastHistory) return lastHistoryText
        val text = history.joinToString("\n") { run ->
            val view = viewOf(run)
            val amount = rowOf(run).amount?.let { " $it" }.orEmpty()
            val detail = view.detail?.let { " — $it" }.orEmpty()
            "${formatTime(run.finishedAt)}  ${run.command.wire}  ${CommandTitles.title(run.command)}$amount: ${view.headline}$detail"
        }
        lastHistory = history
        lastHistoryText = text
        return text
    }

    private fun unresolvedView(entry: JournalEntry) = UnresolvedView(
        attemptId = entry.attemptId,
        title = AttemptTexts.title(entry),
        time = formatDateTime(entry.createdAt),
        reason = AttemptTexts.reason(entry),
        needsReview = entry.needsReview,
        blocksPayments = entry.blocksPayments,
    )

    private fun linkView(link: LinkState): LinkView = when (link) {
        LinkState.Idle -> LinkView(LinkTone.IDLE, "Inattivo")
        is LinkState.Connecting -> LinkView(LinkTone.CONNECTING, "Connessione…")
        is LinkState.Connected -> LinkView(LinkTone.CONNECTED, "Connesso")
        is LinkState.Closed -> when (link.cause) {
            CloseCause.LOCAL -> LinkView(LinkTone.IDLE, "Chiuso (fine comando)")
            CloseCause.PEER_CLOSED -> LinkView(LinkTone.CLOSED, "Chiuso dal terminale")
            CloseCause.ABORTED -> LinkView(LinkTone.IDLE, "Annullato")
            CloseCause.RESET, CloseCause.IO_ERROR, CloseCause.WRITE_TIMEOUT -> LinkView(LinkTone.ERROR, "Connessione interrotta")
        }
        is LinkState.ConnectFailed -> LinkView(LinkTone.ERROR, "Connessione fallita")
    }

    private fun simulatorLabel(status: SimulatorStatus, isTarget: Boolean): String = when (status) {
        SimulatorStatus.Stopped -> "Simulatore fermo"
        SimulatorStatus.Starting -> "Avvio del simulatore…"
        is SimulatorStatus.Running ->
            "Simulatore attivo su 127.0.0.1:${status.port}" + if (isTarget) " (è la destinazione del terminale)" else ""
        is SimulatorStatus.Failed -> when (val failure = status.failure) {
            is SimulatorFailure.PortInUse -> "Porta ${failure.port} in uso: cambiala nel Simulatore"
            is SimulatorFailure.Unexpected -> "Simulatore non avviato: ${failure.message}"
        }
    }

    private fun pingEvent(run: FinishedRun): ConsoleEvent {
        val outcome = run.outcome
        val response = (outcome as? CommandOutcome.Answered)?.response as? StatusResponse
        return if (response != null) {
            val status = FieldLabels.statusText(response.statusDigit).replaceFirstChar { it.lowercase() }
            ConsoleEvent.ShowInfo("Terminale: $status in ${(run.finishedAt - run.startedAt).coerceAtLeast(0)} ms")
        } else {
            ConsoleEvent.ShowError("Stato terminale: " + (FailureMessages.forOutcome(outcome) ?: viewOf(run).headline))
        }
    }

    private fun emit(event: ConsoleEvent) {
        _events.trySend(event)
    }

    private companion object {
        /** 0x06: costante locale perché la presentation non può importare ControlChars (§2.10). */
        const val ACK_BYTE = 0x06

        const val LINK_BUSY_MESSAGE = "Operazione di collegamento in corso: attendi che finisca"

        val SHARED_AMOUNT = setOf(CommandCode.PAYMENT, CommandCode.PAYMENT_EXTENDED, CommandCode.PRE_AUTH)

        fun shortTitle(code: CommandCode): String = when (code) {
            CommandCode.INCREMENTAL_AUTH -> "Integrazione"
            CommandCode.PRE_AUTH_CLOSURE -> "Chiusura"
            else -> CommandTitles.title(code)
        }

        /** Bozze persistite: lettera del comando → FieldKey.name → testo. Voci sconosciute ignorate. */
        fun decodeDrafts(raw: Map<String, Map<String, String>>): Map<CommandCode, Map<FieldKey, String>> =
            raw.mapNotNull { (letter, fields) ->
                val code = letter.singleOrNull()?.let(CommandCode::fromWire) ?: return@mapNotNull null
                code to fields.mapNotNull { (name, value) ->
                    FieldKey.entries.firstOrNull { it.name == name }?.let { it to value }
                }.toMap()
            }.toMap()
    }
}
