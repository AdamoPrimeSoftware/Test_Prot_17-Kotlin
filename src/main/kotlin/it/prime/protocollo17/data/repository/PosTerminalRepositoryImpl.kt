package it.prime.protocollo17.data.repository

import it.prime.protocollo17.domain.model.command.CommandProgress
import it.prime.protocollo17.domain.model.command.ConfigResolution
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.repository.JournalRepository
import it.prime.protocollo17.domain.repository.PosTerminalRepository
import it.prime.protocollo17.domain.util.ConfigResolver
import it.prime.protocollo17.ecr17.client.Ecr17Client
import it.prime.protocollo17.ecr17.error.ConfigProblem
import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.event.ExchangeListener
import it.prime.protocollo17.ecr17.event.ProtocolWarning
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.ConnectionTestResult
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.LinkState
import it.prime.protocollo17.ecr17.model.PhaseChange
import it.prime.protocollo17.ecr17.model.ReceiptLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Unico punto in cui client ECR17 e journal si incontrano: CREATED prima della connessione, SENDING prima di ogni
 * scrittura (M3), chiusura del tentativo sempre scritta in NonCancellable e mai al posto dell'esito vero (R7).
 *
 * L'algoritmo di [execute] è vincolante (§5.5): l'attemptId nasce PRIMA di begin, così il `finally` può chiudere
 * il tentativo anche se begin è stato annullato; un journal non scrivibile prima del primo invio ferma il comando
 * (NotSent(Internal)); un errore del journal DOPO l'invio viaggia accanto all'esito (Finished.journalError) e
 * non lo sostituisce mai: un disco pieno non trasforma un APPROVATO in un errore generico.
 */
class PosTerminalRepositoryImpl(
    private val client: Ecr17Client,
    private val journal: JournalRepository,
    private val settingsProvider: () -> AppSettings,
) : PosTerminalRepository {

    private val log = LoggerFactory.getLogger(PosTerminalRepositoryImpl::class.java)

    // Getter e non valori: il client è costruito da Koin e il repository non deve toccarlo alla costruzione.
    override val linkState: StateFlow<LinkState>
        get() = client.linkState

    override val busy: StateFlow<CommandCode?>
        get() = client.busy

    override fun effectiveConfig(): ConfigResolution = ConfigResolver.resolve(settingsProvider())

    override fun execute(request: Ecr17Request): Flow<CommandProgress> = channelFlow {
        val resolution = ConfigResolver.resolve(settingsProvider())
        if (!resolution.usable) { // valori illeggibili nel file (L1): rifiuto esplicito, mai sostituzione silenziosa
            send(CommandProgress.Started(request.command, null, now()))
            send(CommandProgress.Finished(CommandOutcome.NotSent(request.command, configInvalid(resolution), null), null))
            return@channelFlow
        }
        // attemptId generato PRIMA di begin: il finally può sempre chiudere il tentativo (anche se begin è stato annullato)
        val attemptId = if (request.command.journaled) journal.newAttemptId() else null
        val evidence = ConcurrentLinkedQueue<String>() // payload di POSSIBLE_LOST_RESULT (già mascherati)
        var outcome: CommandOutcome? = null
        var journalError: String? = null
        try {
            if (attemptId != null) {
                try {
                    journal.begin(attemptId, request, resolution.config) // CREATED + fsync
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) { // M3, R9: nulla è partito
                    log.error("Journal non scrivibile prima dell'invio di '{}': comando non inviato", request.command.wire, e)
                    send(CommandProgress.Started(request.command, null, now()))
                    outcome = CommandOutcome.NotSent(request.command, Ecr17Failure.Internal("journal: ${e.message}"), null)
                    send(CommandProgress.Finished(outcome!!, null, journalError = "journal non scrivibile: ${e.message}"))
                    return@channelFlow
                }
            }
            send(CommandProgress.Started(request.command, attemptId, now()))
            val listener = object : ExchangeListener {
                override suspend fun onPhase(change: PhaseChange) {
                    // Può lanciare: la sessione decide (solo SENDING prima del primo invio annulla, §3.19)
                    if (attemptId != null) {
                        journalPhaseOf(change.phase)?.let { journal.recordPhase(attemptId, it, change.sends, change.resultRaw) }
                    }
                    trySend(CommandProgress.Phase(change))
                }

                override fun onProgress(text: String) {
                    trySend(CommandProgress.Progress(text, now()))
                }

                override fun onReceiptLine(line: ReceiptLine) {
                    trySend(CommandProgress.Receipt(line, now()))
                }

                override fun onWarning(warning: ProtocolWarning) {
                    if (warning.code == WarningCode.POSSIBLE_LOST_RESULT) warning.payload?.let(evidence::add)
                    trySend(CommandProgress.Warning(warning))
                }
            }
            outcome = client.execute(request, listener)
        } finally {
            withContext(NonCancellable) {
                journalError = closeJournal(request, attemptId, outcome, evidence) // mai lancia; finish su un tentativo inesistente = no-op
            }
        }
        send(CommandProgress.Finished(outcome!!, attemptId, journalError))
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    override fun cancelCurrent(): Boolean = client.cancelCurrent()

    /** Con una configurazione non utilizzabile non si apre nulla: lo stesso rifiuto esplicito di execute. */
    override suspend fun testConnection(): ConnectionTestResult {
        val resolution = effectiveConfig()
        if (!resolution.usable) return ConnectionTestResult(resolution.config.endpoint, ok = false, millis = 0, failure = configInvalid(resolution))
        return withContext(Dispatchers.IO) { client.testConnection() }
    }

    override suspend fun connect(): Ecr17Failure? {
        val resolution = effectiveConfig()
        if (!resolution.usable) return configInvalid(resolution)
        return withContext(Dispatchers.IO) { client.connect() }
    }

    override suspend fun disconnect(): Ecr17Failure? = withContext(Dispatchers.IO) { client.disconnect() }

    // ─── Journal ───────────────────────────────────────────────────────────────

    /**
     * Non lancia mai. (1) Ogni prova POSSIBLE_LOST_RESULT va al tentativo non risolto più recente diverso da quello
     * corrente (senza tentativi non risolti la prova resta solo nel log traffico); (2) chiusura del tentativo,
     * con outcome null dopo una cancellazione. Un errore torna come messaggio: il journal ha già forzato l'entry
     * a UNKNOWN in memoria (§5.3.3), quindi i pagamenti sono bloccati anche senza il record su disco.
     */
    private suspend fun closeJournal(
        request: Ecr17Request,
        attemptId: String?,
        outcome: CommandOutcome?,
        evidence: Collection<String>,
    ): String? {
        val errors = mutableListOf<String>()
        if (evidence.isNotEmpty()) {
            val target = journal.unresolved.value.filter { it.attemptId != attemptId }.maxByOrNull { it.createdAt }
            if (target == null) {
                log.info("Esito orfano durante '{}' senza tentativi non risolti: prova solo nel log traffico", request.command.wire)
            } else {
                val note = "orfano durante '${request.command.wire}' delle ${timeFormat.format(Instant.ofEpochMilli(now()))}"
                for (payload in evidence) {
                    try {
                        journal.attachEvidence(target.attemptId, payload, note)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("Journal: prova per il tentativo {} non scritta", target.attemptId, e)
                        errors += e.message ?: e::class.java.simpleName
                    }
                }
            }
        }
        if (attemptId != null) {
            try {
                journal.finish(attemptId, outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Journal: chiusura del tentativo {} non scritta", attemptId, e)
                errors += e.message ?: e::class.java.simpleName
            }
        }
        if (errors.isEmpty()) return null
        return "journal non scritto: pagamenti bloccati finché non lo risolvi (${errors.joinToString("; ")})"
    }

    private fun journalPhaseOf(phase: ExchangePhase): JournalPhase? = when (phase) {
        ExchangePhase.SENDING -> JournalPhase.SENDING
        ExchangePhase.ACKNOWLEDGED -> JournalPhase.ACKED
        ExchangePhase.IMPLICIT_GO_AHEAD -> JournalPhase.GO_AHEAD
        ExchangePhase.RESULT_RECEIVED -> JournalPhase.RECEIVED
        else -> null
    }

    private fun configInvalid(resolution: ConfigResolution): Ecr17Failure.ConfigInvalid =
        Ecr17Failure.ConfigInvalid(resolution.problems + resolution.issues.map { ConfigProblem(it.key, it.message) })

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
