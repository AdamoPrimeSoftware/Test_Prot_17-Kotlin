package it.prime.protocollo17.presentation.state

import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.ecr17.event.ProtocolWarning
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.ReceiptLine
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Da quale finestra è partito il comando: il dialog di risoluzione dopo G si apre solo nella finestra d'origine. */
enum class RunOrigin { CONSOLE, JOURNAL }

/** Comando in corso, come lo mostra l'overlay di occupato (§6.9). */
data class RunningCommand(
    val command: CommandCode,
    val request: Ecr17Request,
    val origin: RunOrigin,
    val attemptId: String?,
    val startedAt: Long,
    val phase: ExchangePhase,
    val phaseAt: Long,
    val sends: Int,
    /** true dopo la fase IMPLICIT_GO_AHEAD (testo dell'overlay). */
    val implicitGoAhead: Boolean,
    /** Istante della prima AWAITING_RESULT dopo l'ultimo SENDING; null durante l'handshake (cronometro §6.9). */
    val resultWaitStartedAt: Long?,
    val lastProgress: String?,
    val receipt: List<ReceiptLine>,
    val warnings: List<ProtocolWarning>,
    val cancelRequested: Boolean,
    val ackTimeoutMs: Long,
    val responseTimeoutMs: Long,
) {
    /** Fase SENDING raggiunta: da qui un annullamento lascia l'esito sconosciuto (per i journaled). */
    val requestSent: Boolean get() = phase.ordinal >= ExchangePhase.SENDING.ordinal
}

/** Comando concluso: voce della cronologia e ultimo esito per comando. */
data class FinishedRun(
    /** Monotono nella sessione dell'app: chiave della cronologia. */
    val id: Long,
    val command: CommandCode,
    val request: Ecr17Request,
    val origin: RunOrigin,
    val outcome: CommandOutcome,
    val attemptId: String?,
    val startedAt: Long,
    val finishedAt: Long,
    /** Da CommandProgress.Finished.journalError: chiusura del tentativo non scritta (pagamenti bloccati in memoria). */
    val journalError: String? = null,
)

/** Stato condiviso dei comandi: lo osservano la Console, il Journal e il menu della finestra principale. */
data class RunnerState(
    val running: RunningCommand? = null,
    val lastByCommand: Map<CommandCode, FinishedRun> = emptyMap(),
    /** Cronologia della sessione, più recenti prima, al massimo HISTORY_CAP voci (solo in memoria). */
    val history: List<FinishedRun> = emptyList(),
    /** TID restituito dal terminale nell'ultima risposta (A§11 punto 19). */
    val lastSeenTerminalId: String? = null,
) {
    companion object { const val HISTORY_CAP = 200 }
}

/** Esito sincrono di [CommandRunner.run]: il comando parte oppure viene rifiutato subito, mai accodato (M7). */
sealed interface RunStart {
    data object Started : RunStart
    data object Busy : RunStart
    data class Blocked(val blocking: List<JournalEntry>) : RunStart
    data class InvalidConfig(val messages: List<String>) : RunStart
}

/**
 * Cosa ha fatto una richiesta di annullamento. La distinzione conta per l'operatore: prima dell'invio il comando
 * semplicemente non parte; dopo l'invio l'esito di un comando journaled resta sconosciuto (U7).
 */
enum class CancelResult { NOT_RUNNING, CANCELLED_BEFORE_SEND, ABORTED_AFTER_SEND }

/**
 * Unico punto di ingresso per eseguire comandi (finestra principale e Journal).
 *
 * Un solo esecutore app-scoped invece di uno per finestra: due finestre non possono lanciare due comandi
 * contemporanei (R12), e un comando sopravvive alla chiusura della finestra che l'ha avviato.
 */
interface CommandRunner {
    val state: StateFlow<RunnerState>

    /** Un elemento per ogni comando concluso (replay 0, extraBufferCapacity 16). */
    val finished: SharedFlow<FinishedRun>

    /** Non sospende. Rifiuta (mai accoda) se occupato, bloccato o con configurazione non utilizzabile. */
    fun run(request: Ecr17Request, origin: RunOrigin): RunStart

    fun requestCancel(): CancelResult
    fun clearHistory()
}
