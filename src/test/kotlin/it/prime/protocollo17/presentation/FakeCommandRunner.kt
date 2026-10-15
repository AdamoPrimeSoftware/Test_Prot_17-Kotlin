package it.prime.protocollo17.presentation

import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.presentation.state.CancelResult
import it.prime.protocollo17.presentation.state.CommandRunner
import it.prime.protocollo17.presentation.state.FinishedRun
import it.prime.protocollo17.presentation.state.RunOrigin
import it.prime.protocollo17.presentation.state.RunStart
import it.prime.protocollo17.presentation.state.RunnerState
import it.prime.protocollo17.presentation.state.RunningCommand
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update

/**
 * CommandRunner finto "con gate": un comando avviato resta in corso finché il test non chiama [finish], senza
 * delay né tempo virtuale (skill testing). Registra le chiamate per le verifiche e rispetta il contratto del
 * servizio vero sulle cose che i ViewModel osservano: rifiuta se occupato, `finished` senza replay, cronologia
 * con i più recenti in testa.
 */
internal class FakeCommandRunner : CommandRunner {

    private val _state = MutableStateFlow(RunnerState())
    override val state: StateFlow<RunnerState> = _state

    private val _finished = MutableSharedFlow<FinishedRun>(replay = 0, extraBufferCapacity = 16)
    override val finished: SharedFlow<FinishedRun> = _finished.asSharedFlow()

    /** Richieste ricevute da [run], anche quelle rifiutate. */
    val runs = mutableListOf<Pair<Ecr17Request, RunOrigin>>()

    /** Risposta della prossima [run] (Started di default). */
    var nextStart: RunStart = RunStart.Started

    var cancelCalls = 0
        private set
    var clearHistoryCalls = 0
        private set

    private var nextId = 1L

    override fun run(request: Ecr17Request, origin: RunOrigin): RunStart {
        runs += request to origin
        if (_state.value.running != null) return RunStart.Busy
        val start = nextStart
        if (start == RunStart.Started) {
            _state.update {
                it.copy(
                    running = RunningCommand(
                        command = request.command, request = request, origin = origin,
                        attemptId = if (request.command.journaled) "attempt-$nextId" else null,
                        startedAt = 1_000, phase = ExchangePhase.PREPARING, phaseAt = 1_000, sends = 0,
                        implicitGoAhead = false, resultWaitStartedAt = null, lastProgress = null,
                        receipt = emptyList(), warnings = emptyList(), cancelRequested = false,
                        ackTimeoutMs = 2_000, responseTimeoutMs = 120_000,
                    ),
                )
            }
        }
        return start
    }

    /** Porta il comando in corso alla fase data (SENDING ⇒ requestSent). */
    fun setPhase(phase: ExchangePhase) {
        _state.update { s -> s.copy(running = s.running?.copy(phase = phase, sends = if (phase >= ExchangePhase.SENDING) 1 else 0)) }
    }

    /** Apre il gate: conclude il comando in corso con [outcome] e lo pubblica come farebbe il servizio. */
    fun finish(outcome: CommandOutcome, journalError: String? = null, finishedAt: Long = 2_000): FinishedRun {
        val running = checkNotNull(_state.value.running) { "nessun comando in corso" }
        val run = FinishedRun(
            id = nextId++, command = running.command, request = running.request, origin = running.origin,
            outcome = outcome, attemptId = running.attemptId, startedAt = running.startedAt,
            finishedAt = finishedAt, journalError = journalError,
        )
        _state.update {
            it.copy(
                running = null,
                lastByCommand = it.lastByCommand + (run.command to run),
                history = (listOf(run) + it.history).take(RunnerState.HISTORY_CAP),
                lastSeenTerminalId = (outcome as? CommandOutcome.Answered)?.response?.terminalId ?: it.lastSeenTerminalId,
            )
        }
        check(_finished.tryEmit(run)) { "finished pieno" }
        return run
    }

    /** Stato arbitrario (cronologia precaricata, comando in una certa fase). */
    fun setState(state: RunnerState) {
        _state.value = state
    }

    override fun requestCancel(): CancelResult {
        cancelCalls++
        val running = _state.value.running ?: return CancelResult.NOT_RUNNING
        _state.update { it.copy(running = running.copy(cancelRequested = true)) }
        return if (running.requestSent) CancelResult.ABORTED_AFTER_SEND else CancelResult.CANCELLED_BEFORE_SEND
    }

    override fun clearHistory() {
        clearHistoryCalls++
        _state.update { it.copy(history = emptyList()) }
    }
}
