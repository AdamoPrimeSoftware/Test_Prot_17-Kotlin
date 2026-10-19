package it.prime.protocollo17.presentation.state

import it.prime.protocollo17.domain.model.command.CommandProgress
import it.prime.protocollo17.domain.model.command.CommandTitles
import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.domain.repository.PosTerminalRepository
import it.prime.protocollo17.domain.util.PaymentBlockPolicy
import it.prime.protocollo17.domain.util.formatCents
import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.model.CloseSessionResponse
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.Ecr17Response
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.HasResult
import it.prime.protocollo17.ecr17.model.PaymentResponse
import it.prime.protocollo17.ecr17.model.PreAuthClosureResponse
import it.prime.protocollo17.ecr17.model.PreAuthResponse
import it.prime.protocollo17.ecr17.model.Verdict
import it.prime.protocollo17.ecr17.model.amountCents
import it.prime.protocollo17.presentation.util.FailureMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-scoped: i comandi sopravvivono alla chiusura delle finestre secondarie. Nessun import Compose.
 *
 * Applica la seconda difesa del blocco pagamenti (PaymentBlockPolicy, oltre al pulsante disabilitato), rifiuta
 * un secondo comando invece di accodarlo (M7) e pubblica le notifiche di fine comando per tutta l'app (tranne
 * l'esito dello Stato F5, [RunOrigin.CONSOLE_PING], che la Console mostra da sé).
 *
 * Ogni comando termina con esattamente un [FinishedRun], anche quando il flusso del repository si interrompe
 * senza `Finished` (annullamento o bug): l'esito si sintetizza dal journal, che il `finally` del repository ha
 * già chiuso in NonCancellable prima che il job termini. La sintesi non dice mai "non inviato" per un comando
 * di cui il journal registra l'invio (M10).
 */
class TerminalSessionService(
    private val repository: PosTerminalRepository,
    private val journal: JournalRepository,
    private val notifications: AppNotifications,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : CommandRunner, AutoCloseable {

    private val log = LoggerFactory.getLogger(TerminalSessionService::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("terminal-session"))

    /** Protegge running, runJob, il token del comando corrente e il contatore della cronologia. */
    private val lock = Any()
    private var runJob: Job? = null
    private var currentToken = 0L
    private var nextId = 1L
    private val closed = AtomicBoolean(false)

    private val _state = MutableStateFlow(RunnerState())
    override val state: StateFlow<RunnerState> = _state.asStateFlow()

    private val _finished = MutableSharedFlow<FinishedRun>(replay = 0, extraBufferCapacity = 16)
    override val finished: SharedFlow<FinishedRun> = _finished.asSharedFlow()

    // ─── CommandRunner ─────────────────────────────────────────────────────────

    /** Non sospende. Rifiuta (mai accoda) se occupato, bloccato o con configurazione non utilizzabile. */
    override fun run(request: Ecr17Request, origin: RunOrigin): RunStart {
        synchronized(lock) {
            if (_state.value.running != null || closed.get()) return RunStart.Busy
            val blocking = PaymentBlockPolicy.blocking(request.command, journal.unresolved.value)
            if (blocking.isNotEmpty()) return RunStart.Blocked(blocking)
            val resolution = try {
                repository.effectiveConfig()
            } catch (e: Exception) {
                log.error("Configurazione non leggibile", e)
                return RunStart.InvalidConfig(listOf("Configurazione non leggibile: ${e.message}"))
            }
            if (!resolution.usable) {
                return RunStart.InvalidConfig(resolution.issues.map { it.message } + resolution.problems.map(FailureMessages::forConfigProblem))
            }
            val now = wallClock()
            val running = RunningCommand(
                command = request.command,
                request = request,
                origin = origin,
                attemptId = null,
                startedAt = now,
                phase = ExchangePhase.PREPARING,
                phaseAt = now,
                sends = 0,
                implicitGoAhead = false,
                resultWaitStartedAt = null,
                lastProgress = null,
                receipt = emptyList(),
                warnings = emptyList(),
                cancelRequested = false,
                ackTimeoutMs = resolution.config.options.ackTimeoutMs,
                responseTimeoutMs = resolution.config.options.responseTimeoutMs,
            )
            val token = ++currentToken
            // Tentativi già noti prima del comando: se il flusso si interrompe prima di consegnare Started, il
            // tentativo nuovo si riconosce come quello comparso nel journal durante questo comando.
            val knownIds = if (request.command.journaled) knownAttemptIds() else emptySet()
            _state.update { it.copy(running = running) }
            runJob = scope.launch { execute(token, request, knownIds) }
            return RunStart.Started
        }
    }

    override fun requestCancel(): CancelResult {
        synchronized(lock) {
            val running = _state.value.running ?: return CancelResult.NOT_RUNNING
            _state.update { s -> s.copy(running = s.running?.copy(cancelRequested = true)) }
            return if (!running.requestSent) {
                // Annullare il job copre anche la finestra prima del Mutex del client (configurazione, begin con
                // fsync): lì cancelCurrent() non troverebbe nulla da annullare e il pagamento partirebbe.
                runJob?.cancel()
                cancelClient()
                CancelResult.CANCELLED_BEFORE_SEND
            } else {
                // Dopo l'invio: abort del socket, l'esito resta sconosciuto (Unknown(Cancelled) dal client).
                cancelClient()
                CancelResult.ABORTED_AFTER_SEND
            }
        }
    }

    override fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
    }

    /** Chiusura app (fuori dall'EDT): annulla il comando in corso e attende al massimo timeoutMs che il journal sia scritto. */
    fun shutdown(timeoutMs: Long = 3_000) {
        val job = synchronized(lock) {
            _state.value.running ?: return
            _state.update { s -> s.copy(running = s.running?.copy(cancelRequested = true)) }
            runJob
        }
        job?.cancel()
        cancelClient()
        // Unico withTimeoutOrNull attorno a un comando (M8): il comando è già annullato, serve solo a non bloccare
        // la chiusura se il journal non risponde.
        runBlocking { withTimeoutOrNull(timeoutMs) { job?.join() } }
        synchronized(lock) {
            currentToken++
            _state.update { it.copy(running = null) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        shutdown()
        scope.cancel()
    }

    // ─── Esecuzione ────────────────────────────────────────────────────────────

    private suspend fun execute(token: Long, request: Ecr17Request, knownIds: Set<String>) {
        var finishedSeen = false
        var failure: Throwable? = null
        try {
            repository.execute(request).collect { progress ->
                if (progress is CommandProgress.Finished) finishedSeen = true
                reduce(token, progress)
            }
        } catch (e: CancellationException) {
            failure = e
            throw e
        } catch (e: Exception) {
            failure = e
            log.error("Comando '{}' interrotto da un errore inatteso (bug)", request.command.wire, e)
        } finally {
            // Nessun Finished: annullamento, eccezione o flusso chiuso in anticipo. Il journal è già chiuso.
            if (!finishedSeen) synthesizeAndComplete(token, failure, knownIds)
        }
    }

    private fun reduce(token: Long, progress: CommandProgress) {
        if (progress is CommandProgress.Finished) {
            complete(token, progress.outcome, progress.attemptId, progress.journalError)
            return
        }
        synchronized(lock) {
            if (token != currentToken) return
            _state.update { s ->
                val r = s.running ?: return@update s
                val updated = when (progress) {
                    is CommandProgress.Started -> r.copy(attemptId = progress.attemptId)
                    is CommandProgress.Phase -> {
                        val c = progress.change
                        r.copy(
                            phase = c.phase,
                            phaseAt = c.at,
                            sends = c.sends,
                            implicitGoAhead = r.implicitGoAhead || c.phase == ExchangePhase.IMPLICIT_GO_AHEAD,
                            resultWaitStartedAt = when (c.phase) {
                                ExchangePhase.SENDING -> null
                                ExchangePhase.AWAITING_RESULT -> r.resultWaitStartedAt ?: c.at
                                else -> r.resultWaitStartedAt
                            },
                        )
                    }
                    is CommandProgress.Progress -> r.copy(lastProgress = progress.text)
                    is CommandProgress.Receipt -> r.copy(receipt = r.receipt + progress.line)
                    is CommandProgress.Warning -> r.copy(warnings = r.warnings + progress.warning)
                    is CommandProgress.Finished -> r
                }
                s.copy(running = updated)
            }
        }
    }

    /**
     * Esito sintetizzato quando manca Finished (§6.4): entry del tentativo UNKNOWN o con un invio registrato ⇒
     * Unknown; comando non journaled già inviato ⇒ Failed; altrimenti NotSent. Sempre trace = null.
     */
    private fun synthesizeAndComplete(token: Long, failure: Throwable?, knownIds: Set<String>) {
        val running = synchronized(lock) { _state.value.running.takeIf { token == currentToken } } ?: return
        val command = running.command
        val reason: Ecr17Failure = when (failure) {
            is CancellationException -> Ecr17Failure.Cancelled(byUser = true)
            null -> Ecr17Failure.Internal("flusso del comando terminato senza esito")
            else -> Ecr17Failure.Internal(failure.message ?: failure::class.java.simpleName)
        }
        val attemptId = running.attemptId ?: if (command.journaled) newAttemptSince(command, knownIds) else null
        val entry = attemptId?.let(::findEntry)
        val sentPerJournal = entry != null && (entry.phase == JournalPhase.UNKNOWN || entry.records.any { it.phase in SENT_PHASES })
        val outcome = when {
            command.journaled && (sentPerJournal || running.requestSent) ->
                CommandOutcome.Unknown(command, reason, running.phase, trace = null)
            !command.journaled && running.requestSent ->
                CommandOutcome.Failed(command, reason, running.phase, trace = null)
            else -> CommandOutcome.NotSent(command, reason, trace = null)
        }
        complete(token, outcome, attemptId, journalError = null)
    }

    private fun complete(token: Long, outcome: CommandOutcome, attemptId: String?, journalError: String?) {
        val run = synchronized(lock) {
            if (token != currentToken) return
            val r = _state.value.running ?: return
            val run = FinishedRun(
                id = nextId++,
                command = r.command,
                request = r.request,
                origin = r.origin,
                outcome = outcome,
                attemptId = attemptId ?: r.attemptId,
                startedAt = r.startedAt,
                finishedAt = wallClock(),
                journalError = journalError,
            )
            _state.update { s ->
                s.copy(
                    running = null,
                    lastByCommand = s.lastByCommand + (run.command to run),
                    history = (listOf(run) + s.history).take(RunnerState.HISTORY_CAP),
                    lastSeenTerminalId = (outcome as? CommandOutcome.Answered)?.response?.terminalId ?: s.lastSeenTerminalId,
                )
            }
            run
        }
        val delivered = _finished.tryEmit(run)
        if (!delivered) log.warn("Esito di '{}' non consegnato agli osservatori (buffer pieno)", run.command.wire)
        notifyFinished(run, delivered)
    }

    // ─── Notifiche ─────────────────────────────────────────────────────────────

    /**
     * Un avviso per comando concluso, con fonte = titolo del comando e messaggio che comincia con lo stesso titolo
     * (più l'importo): lo snackbar mostra [AppNotifications.Notice.displayText], cioè il solo messaggio.
     *
     * Prima l'esito sconosciuto aveva fonte "Esito sconosciuto" e si leggeva "Esito sconosciuto: Pagamento 0,10 €:
     * esito sconosciuto, …"; ora ha la fonte del comando come gli altri rami.
     *
     * Lo Stato (F5) della barra ([RunOrigin.CONSOLE_PING] con `s`) non ha l'avviso dell'esito: lo pubblica la
     * Console ("Terminale: operativo (2) in 140 ms"). Prima uscivano entrambi sullo stesso snackbar e l'uno
     * sostituiva l'altro in ordine non deterministico. L'eccezione vale solo per `s` e solo se [finished] ha
     * consegnato l'esito ([delivered]): altrimenti la Console non lo vede e l'avviso resta qui. L'avviso del journal
     * resta per ogni origine (`s` non è journaled, ma un journal non scritto blocca i pagamenti e l'operatore deve
     * saperlo comunque: con quell'avviso la Console non pubblica il suo).
     */
    private fun notifyFinished(run: FinishedRun, delivered: Boolean) {
        val consoleNotifies = delivered && run.origin == RunOrigin.CONSOLE_PING && run.command == CommandCode.STATUS
        if (!consoleNotifies) notifyOutcome(run)
        run.journalError?.let {
            notifications.error("Journal", "Journal non scritto: pagamenti bloccati. $it", openWindow = AppWindowId.JOURNAL)
        }
    }

    private fun notifyOutcome(run: FinishedRun) {
        val title = CommandTitles.title(run.command)
        val head = title + (run.request.amountCents?.let { " " + formatCents(it) } ?: "")
        when (val outcome = run.outcome) {
            is CommandOutcome.Answered -> notifyAnswered(title, head, outcome)
            is CommandOutcome.Acknowledged -> notifications.success(title, "$head: confermato dal terminale")
            is CommandOutcome.Unknown -> notifications.error(title, "$head: ${unknownText(run.command)}", openWindow = AppWindowId.JOURNAL)
            is CommandOutcome.NotSent, is CommandOutcome.Refused, is CommandOutcome.Failed ->
                notifications.error(title, "$head: ${FailureMessages.forOutcome(outcome) ?: "comando non riuscito"}")
        }
    }

    private fun notifyAnswered(title: String, head: String, outcome: CommandOutcome.Answered) {
        val result = outcome.response as? HasResult
        val command = outcome.command
        when {
            outcome.needsReview -> notifications.warning(
                title,
                "$head: ${verdictText(command, result)} ma da verificare: possibile doppio addebito. Esegui Ultimo esito (G) o Totali",
                openWindow = AppWindowId.JOURNAL,
            )
            result == null -> notifications.success(title, "$head: risposta ricevuta")
            result.verdict == Verdict.APPROVED -> notifications.success(title, "$head: ${CommandTitles.approvedWord(command)}")
            result.verdict == Verdict.DECLINED -> notifications.warning(title, "$head: RIFIUTATO: ${declineDescription(outcome.response, result)}")
            else -> notifications.warning(title, "$head: ${verdictText(command, result)}")
        }
    }

    private fun verdictText(command: CommandCode, result: HasResult?): String = when (result?.verdict) {
        null -> "risposta ricevuta"
        Verdict.APPROVED -> CommandTitles.approvedWord(command)
        Verdict.DECLINED -> "RIFIUTATO"
        Verdict.CARD_NOT_PRESENT -> "carta non presente (05)"
        Verdict.UNKNOWN_TAG -> "tag sconosciuto (09)"
        Verdict.UNRECOGNIZED -> "esito non riconosciuto '${result.resultCode}'"
    }

    private fun declineDescription(response: Ecr17Response, result: HasResult): String {
        val description = when (response) {
            is PaymentResponse -> response.errorDescription
            is PreAuthResponse -> response.errorDescription
            is PreAuthClosureResponse -> response.errorDescription
            is CloseSessionResponse -> response.errorDescription
            else -> null
        }?.trim()
        return description?.takeIf { it.isNotEmpty() } ?: "esito ${result.resultCode}"
    }

    /**
     * Cosa resta bloccato dopo un esito sconosciuto, con la regola di PaymentBlockPolicy: un comando che muove denaro
     * blocca tutti i journaled, un H solo la famiglia E V (P X S H), un C solo un'altra C.
     *
     * Prima un H sconosciuto diceva "pagamenti bloccati" mentre p, i e c restavano ammessi (e il banner diceva
     * giusto); ora il testo elenca i comandi bloccati.
     */
    private fun unknownText(command: CommandCode): String = when {
        command.movesMoney -> "esito sconosciuto, pagamenti bloccati: esegui Ultimo esito (G)"
        command == CommandCode.CARD_VERIFICATION -> "esito sconosciuto, bloccati P, X, S e H: esegui Ultimo esito (G)"
        command == CommandCode.CLOSE_SESSION ->
            "esito sconosciuto, nuova chiusura contabile bloccata: verifica con Ultimo esito (G) o Totali"
        else -> "esito sconosciuto: esegui Ultimo esito (G)"
    }

    // ─── Supporto ──────────────────────────────────────────────────────────────

    private fun cancelClient() {
        try {
            repository.cancelCurrent()
        } catch (e: Exception) {
            log.warn("Annullamento del comando in corso non riuscito: {}", e.message)
        }
    }

    private fun knownAttemptIds(): Set<String> =
        (journal.entries.value.asSequence() + journal.unresolved.value.asSequence()).mapTo(HashSet()) { it.attemptId }

    private fun allEntries(): List<JournalEntry> = journal.unresolved.value + journal.entries.value

    private fun findEntry(attemptId: String): JournalEntry? = allEntries().firstOrNull { it.attemptId == attemptId }

    /** Un comando alla volta (M7): un tentativo nuovo dello stesso comando comparso durante il run è il suo. */
    private fun newAttemptSince(command: CommandCode, knownIds: Set<String>): String? =
        allEntries().filter { it.attemptId !in knownIds && it.command == command }.maxByOrNull { it.createdAt }?.attemptId

    private companion object {
        /** Fasi del journal che provano almeno un tentativo di scrittura del frame principale. */
        val SENT_PHASES = setOf(
            JournalPhase.SENDING, JournalPhase.ACKED, JournalPhase.GO_AHEAD, JournalPhase.RECEIVED,
            JournalPhase.RESULT, JournalPhase.REFUSED, JournalPhase.UNKNOWN,
        )
    }
}
