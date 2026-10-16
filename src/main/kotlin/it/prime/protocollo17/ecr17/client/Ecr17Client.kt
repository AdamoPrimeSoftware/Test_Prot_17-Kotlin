package it.prime.protocollo17.ecr17.client

import it.prime.protocollo17.ecr17.error.ConfigProblem
import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.event.ExchangeListener
import it.prime.protocollo17.ecr17.event.ProtocolEvent
import it.prime.protocollo17.ecr17.event.ProtocolEventSink
import it.prime.protocollo17.ecr17.event.ProtocolWarning
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.message.RequestEncoder
import it.prime.protocollo17.ecr17.message.ResponseParser
import it.prime.protocollo17.ecr17.model.CardVerificationRequest
import it.prime.protocollo17.ecr17.model.CloseSessionRequest
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.ConnectionTestResult
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.EcrPrintingRequest
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.ExchangeKind
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.IncrementalAuthRequest
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.ecr17.model.LinkState
import it.prime.protocollo17.ecr17.model.OutcomeClass
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.PreAuthClosureRequest
import it.prime.protocollo17.ecr17.model.PreAuthRequest
import it.prime.protocollo17.ecr17.model.ReprintRequest
import it.prime.protocollo17.ecr17.model.ReprintTarget
import it.prime.protocollo17.ecr17.model.ReversalRequest
import it.prime.protocollo17.ecr17.model.StatusRequest
import it.prime.protocollo17.ecr17.model.TotalsRequest
import it.prime.protocollo17.ecr17.model.outcomeClass
import it.prime.protocollo17.ecr17.profile.ConnectionMode
import it.prime.protocollo17.ecr17.profile.Ecr17Config
import it.prime.protocollo17.ecr17.profile.FinancialAckPolicy
import it.prime.protocollo17.ecr17.profile.ProtocolOptions
import it.prime.protocollo17.ecr17.session.CancelToken
import it.prime.protocollo17.ecr17.session.Ecr17Session
import it.prime.protocollo17.ecr17.session.EventEmitter
import it.prime.protocollo17.ecr17.session.ExchangeResult
import it.prime.protocollo17.ecr17.session.ExchangeSpec
import it.prime.protocollo17.ecr17.session.RetryPolicy
import it.prime.protocollo17.ecr17.session.TraceRecorder
import it.prime.protocollo17.ecr17.transport.Ecr17Transport
import it.prime.protocollo17.ecr17.transport.TransportErrorKind
import it.prime.protocollo17.ecr17.transport.TransportException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * Facciata del protocollo. Un comando alla volta (Mutex.tryLock: un secondo comando riceve NotSent(Busy), M7).
 * configProvider è letto UNA volta all'inizio di ogni comando: le modifiche alle opzioni valgono dal comando successivo.
 * Gira sul dispatcher del chiamante (§2.10).
 *
 * Qui vive la mappatura money-safety di §3.23: la sessione restituisce un risultato grezzo, il client decide se il
 * comando è NotSent, Refused, Failed o Unknown guardando il flag appiccicoso del [TraceRecorder] (M10) e non il
 * risultato. Prima (nei repo) un'eccezione dopo la scrittura del frame, un errore del journal o una cancellazione
 * diventavano "non inviato": un pagamento forse eseguito spariva senza bloccare i successivi.
 *
 * Il secondo comando concorrente è rifiutato e mai accodato (M7): un pagamento accodato partirebbe minuti dopo,
 * quando l'operatore non se lo aspetta più. Nessun timeout esterno attorno al comando (M8): i tempi sono tutti
 * della sessione.
 */
class Ecr17Client(
    private val configProvider: () -> Ecr17Config,
    private val transport: Ecr17Transport,
    private val eventSink: ProtocolEventSink = ProtocolEventSink.NONE,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val mutableEvents = MutableSharedFlow<ProtocolEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Copia degli eventi per test e osservatori non critici (extraBufferCapacity 256, DROP_OLDEST). */
    val events: SharedFlow<ProtocolEvent> = mutableEvents.asSharedFlow()

    /** = transport.linkState */
    val linkState: StateFlow<LinkState> get() = transport.linkState

    private val mutableBusy = MutableStateFlow<CommandCode?>(null)
    val busy: StateFlow<CommandCode?> = mutableBusy.asStateFlow()

    private val mutex = Mutex()
    private val seq = AtomicLong(0)
    private val exchangeIds = AtomicLong(0)
    private val emitLock = Any()

    /** Token del comando (o dell'operazione di connessione) in corso: letto da cancelCurrent() su qualsiasi thread. */
    @Volatile
    private var current: CancelToken? = null

    /**
     * Unico punto di emissione: seq e at assegnati sotto lock, così l'ordine visto dal sink è quello dei seq anche
     * con gli eventi di link che arrivano dal thread del lettore. Sink sincrono PRIMA dello SharedFlow (§3.23).
     */
    private val emitter = EventEmitter { build ->
        synchronized(emitLock) {
            val event = build(seq.incrementAndGet(), wallClock())
            try {
                eventSink.onEvent(event)
            } catch (e: Exception) {
                // Il sink (log traffico) non deve mai interrompere un pagamento: l'evento resta nello SharedFlow.
            }
            mutableEvents.tryEmit(event)
        }
    }

    init {
        transport.linkListener = { state -> emitter.emit { s, at -> ProtocolEvent.Link(s, at, state) } }
    }

    suspend fun execute(request: Ecr17Request, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome {
        val command = request.command
        // Punto 1: rifiuto immediato, mai accodato (M7).
        if (!mutex.tryLock()) return CommandOutcome.NotSent(command, Ecr17Failure.Busy, null)
        val run = CommandRun(
            exchangeId = exchangeIds.incrementAndGet(),
            request = request,
            listener = listener,
            token = CancelToken(),
        )
        current = run.token   // da qui un cancelCurrent() vale per QUESTO comando, anche prima della connessione
        mutableBusy.value = command
        try {
            val outcome = try {
                perform(run)
            } catch (e: CancellationException) {
                // Punto 11: la coroutine chiamante è stata cancellata. Socket chiuso subito, poi si rilancia.
                transport.abort()
                val written = run.trace?.anyMainFrameWriteAttempted == true
                run.cancelledClass = when {
                    !written -> OutcomeClass.NOT_SENT
                    command.journaled -> OutcomeClass.UNKNOWN
                    else -> OutcomeClass.FAILED
                }
                throw e
            } catch (e: Throwable) {
                // Bug, errore del parser, eccezione non di trasporto: mai propagata, mappata con lo stesso flag (M10).
                internalOutcome(run, e)
            }
            run.outcome = outcome
            return outcome
        } finally {
            // La chiusura è best effort; lock e stato busy si rilasciano comunque. Una CancellationException dalla
            // chiusura non si inghiotte (§2.12): per questo il rilascio sta in un finally a parte, che la lascia passare.
            try {
                withContext(NonCancellable) {
                    try {
                        applyClosePolicy(run)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Errore di chiusura: il socket è già perso, l'esito resta quello calcolato.
                    }
                }
            } finally {
                emitFinished(run)
                mutableBusy.value = null
                current = null
                mutex.unlock()
            }
        }
    }

    // ─── Scorciatoie tipizzate (delegano a execute) ───────────────────────────

    suspend fun status(listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        execute(StatusRequest, listener)

    suspend fun pay(request: PaymentRequest, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        execute(request, listener)

    suspend fun reverse(request: ReversalRequest, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        execute(request, listener)

    suspend fun preAuth(request: PreAuthRequest, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        execute(request, listener)

    suspend fun incrementalAuth(request: IncrementalAuthRequest, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        execute(request, listener)

    suspend fun preAuthClosure(request: PreAuthClosureRequest, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        execute(request, listener)

    suspend fun verifyCard(request: CardVerificationRequest, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        execute(request, listener)

    suspend fun closeSession(request: CloseSessionRequest = CloseSessionRequest(), listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        execute(request, listener)

    suspend fun totals(request: TotalsRequest = TotalsRequest(), listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        execute(request, listener)

    suspend fun sendLastResult(request: LastResultRequest = LastResultRequest(), listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        execute(request, listener)

    suspend fun setEcrPrinting(enabled: Boolean, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        execute(EcrPrintingRequest(enabled), listener)

    suspend fun reprint(request: ReprintRequest, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        execute(request, listener)

    /** Apre e (in PER_COMMAND) richiude la connessione misurando il tempo. Busy se un comando è in corso. */
    suspend fun testConnection(): ConnectionTestResult {
        val cfg = configProvider()
        if (!mutex.tryLock()) return ConnectionTestResult(cfg.endpoint, ok = false, millis = 0, failure = Ecr17Failure.Busy)
        val token = CancelToken()
        current = token
        var connected = false
        try {
            val problems = cfg.validate()
            if (problems.isNotEmpty()) {
                return ConnectionTestResult(cfg.endpoint, ok = false, millis = 0, failure = Ecr17Failure.ConfigInvalid(problems))
            }
            val options = cfg.options
            if (options.connectionMode == ConnectionMode.PERSISTENT && reusable(cfg.endpoint)) {
                return ConnectionTestResult(cfg.endpoint, ok = true, millis = 0, failure = null)
            }
            val started = timeSource.markNow()
            val failure = try {
                transport.connect(cfg.endpoint, options.connectTimeoutMs)
                connected = true
                null
            } catch (e: TransportException) {
                connectFailure(e, cfg, token)
            }
            val millis = started.elapsedNow().inWholeMilliseconds
            return ConnectionTestResult(cfg.endpoint, ok = failure == null, millis = millis, failure = failure)
        } catch (e: CancellationException) {
            transport.abort()
            throw e
        } finally {
            withContext(NonCancellable) {
                if (connected && cfg.options.connectionMode == ConnectionMode.PER_COMMAND) transport.close()
            }
            current = null
            mutex.unlock()
        }
    }

    /** Solo PERSISTENT: apre il collegamento. null = ok. */
    suspend fun connect(): Ecr17Failure? {
        if (!mutex.tryLock()) return Ecr17Failure.Busy
        val token = CancelToken()
        current = token
        try {
            val cfg = configProvider()
            val problems = cfg.validate()
            if (problems.isNotEmpty()) return Ecr17Failure.ConfigInvalid(problems)
            if (cfg.options.connectionMode != ConnectionMode.PERSISTENT) {
                return Ecr17Failure.ConfigInvalid(
                    listOf(ConfigProblem("connectionMode", "connect() requires PERSISTENT mode (was ${cfg.options.connectionMode})")),
                )
            }
            if (reusable(cfg.endpoint)) return null
            return try {
                transport.connect(cfg.endpoint, cfg.options.connectTimeoutMs)
                null
            } catch (e: TransportException) {
                connectFailure(e, cfg, token)
            }
        } catch (e: CancellationException) {
            transport.abort()
            throw e
        } finally {
            current = null
            mutex.unlock()
        }
    }

    /** Chiusura intenzionale (rifiutata con Busy durante un comando). */
    suspend fun disconnect(): Ecr17Failure? {
        if (!mutex.tryLock()) return Ecr17Failure.Busy
        try {
            withContext(NonCancellable) { transport.close() }
            return null
        } finally {
            mutex.unlock()
        }
    }

    /** Thread-safe, non sospende: token.cancel() + transport.abort() del comando corrente. true se c'era un comando. */
    fun cancelCurrent(): Boolean {
        val token = current ?: return false
        token.cancel()
        transport.abort()
        return true
    }

    /** transport.shutdown(). */
    override fun close() {
        transport.shutdown()
    }

    // ─── Algoritmo di execute (§3.23, punti 2-9) ──────────────────────────────

    private suspend fun perform(run: CommandRun): CommandOutcome {
        val request = run.request
        val command = request.command

        // Punto 2: una sola lettura della configurazione per comando.
        val cfg = configProvider()
        run.config = cfg
        val problems = cfg.validate()
        if (problems.isNotEmpty()) return CommandOutcome.NotSent(command, Ecr17Failure.ConfigInvalid(problems), null)

        // Punto 3: validazione PRIMA di qualsiasi connessione (A§8.2).
        val errors = RequestEncoder.validate(request, cfg.ids)
        if (errors.isNotEmpty()) return CommandOutcome.NotSent(command, Ecr17Failure.Validation(errors), null)
        val payload = RequestEncoder.encode(request, cfg.ids)
        val options = cfg.options
        val summary = options.summary()

        // Punto 4.
        emitter.emit { seq, at ->
            ProtocolEvent.CommandStarted(seq, at, run.exchangeId, command, cfg.endpoint, cfg.profile, summary)
        }
        val trace = TraceRecorder(
            exchangeId = run.exchangeId,
            command = command,
            endpoint = cfg.endpoint,
            profile = cfg.profile,
            optionsSummary = summary,
            listener = run.listener,
            emitter = emitter,
            wallClock = wallClock,
        )
        run.trace = trace
        trace.phase(ExchangePhase.PREPARING, sends = 0)
        trace.phase(ExchangePhase.CONNECTING, sends = 0)

        // Punto 5.
        if (run.token.cancelled) return notSent(run, Ecr17Failure.Cancelled(byUser = true))
        val opened = openConnection(cfg, run)
        if (opened is Opened.Failed) return notSent(run, opened.failure)
        if (run.token.cancelled) return notSent(run, Ecr17Failure.Cancelled(byUser = true))
        val fresh = opened == Opened.Fresh

        // Punto 6: E1 prima del comando; se fallisce il comando principale NON parte.
        if (options.ecrPrinting && command.wantsEcrPrintingSetup) {
            trace.phase(ExchangePhase.PRINTING_SETUP, sends = 0)
            val setup = printingSetup(run, cfg, trace, fresh)
            if (setup is ExchangeResult.Failure) return notSent(run, Ecr17Failure.PrintingSetupFailed(setup.failure))
        }

        // Punto 7.
        val collectReceipt = request is ReprintRequest && request.target == ReprintTarget.ECR
        val spec = ExchangeSpec(
            exchangeId = run.exchangeId,
            command = command,
            payload = payload,
            expectedReplies = command.expectedReplies,
            kind = command.kind,
            ackPolicy = if (command.usesFinancialAckPolicy) options.financialAckPolicy else FinancialAckPolicy.RETRANSMIT,
            collectReceiptAfterAck = collectReceipt,
            expectReceipt = (options.ecrPrinting && command.wantsEcrPrintingSetup) || collectReceipt,
            strictResultLrc = command.journaled || command == CommandCode.LAST_RESULT,
            freshConnection = fresh,
        )
        var result = session(options, trace, run.token).run(spec)

        // Punto 8: un solo replay, solo per s/T/G/E dopo una caduta (M1).
        if (shouldReplay(result, options, command, run.token)) {
            trace.phase(ExchangePhase.CONNECTING, sends = trace.sends)
            val reconnected = try {
                transport.connect(cfg.endpoint, options.connectTimeoutMs)
                true
            } catch (e: TransportException) {
                false   // si resta con la caduta originale
            }
            if (reconnected && !run.token.cancelled) {
                trace.replayedAfterReconnect = true
                result = session(options, trace, run.token).run(spec.copy(freshConnection = true))
            }
        }

        // Punto 9.
        return mapResult(command, result, trace)
    }

    private sealed interface Opened {
        data object Fresh : Opened
        data object Reused : Opened
        class Failed(val failure: Ecr17Failure) : Opened
    }

    private suspend fun openConnection(cfg: Ecr17Config, run: CommandRun): Opened {
        val options = cfg.options
        if (options.connectionMode == ConnectionMode.PERSISTENT && reusable(cfg.endpoint)) {
            run.connectionTouched = true
            return Opened.Reused
        }
        run.connectionTouched = true
        return try {
            transport.connect(cfg.endpoint, options.connectTimeoutMs)
            Opened.Fresh
        } catch (e: TransportException) {
            Opened.Failed(connectFailure(e, cfg, run.token))
        }
    }

    /** Collegamento persistente riusabile: aperto, verso lo stesso endpoint, senza una caduta già vista. */
    private fun reusable(endpoint: Endpoint): Boolean {
        val state = transport.linkState.value
        return state is LinkState.Connected && state.endpoint == endpoint && transport.rx.closedCause == null
    }

    private fun connectFailure(e: TransportException, cfg: Ecr17Config, token: CancelToken): Ecr17Failure = when {
        token.cancelled -> Ecr17Failure.Cancelled(byUser = true)
        e.kind == TransportErrorKind.CONNECT_TIMEOUT ->
            Ecr17Failure.ConnectTimeout(cfg.endpoint, cfg.options.connectTimeoutMs)
        else -> Ecr17Failure.ConnectFailed(cfg.endpoint, e.detail ?: e.kind.name)
    }

    /**
     * Sessione solo-ACK di E1 con un TraceRecorder interno: le sue scritture non toccano il flag del comando
     * principale (un E1 scritto non rende "forse eseguito" il pagamento) e le sue fasi non finiscono nel journal.
     *
     * Prima il recorder interno aveva ExchangeListener.NONE. Ma E1 gira per prima sulla connessione e fa lei la
     * raccolta degli orfani prima dell'invio (§3.21 punto 1): un 'E' tardivo di un pagamento rimasto senza esito
     * trovato lì (o arrivato durante l'handshake di E1) produceva POSSIBLE_LOST_RESULT verso nessuno, quindi
     * nessuna prova EVIDENCE sul tentativo non risolto (M4, §5.5), nessun warning nella trace del pagamento e
     * nessuna chiusura del link persistente (§3.23 punto 10). Ora i soli warning vanno al listener del comando
     * ([WarningsOnlyListener]) e, a fine E1, nella trace principale (TraceRecorder.adopt).
     */
    private suspend fun printingSetup(
        run: CommandRun,
        cfg: Ecr17Config,
        trace: TraceRecorder,
        fresh: Boolean,
    ): ExchangeResult {
        val setupCommand = CommandCode.ECR_PRINTING
        val inner = TraceRecorder(
            exchangeId = run.exchangeId,
            command = setupCommand,
            endpoint = cfg.endpoint,
            profile = cfg.profile,
            optionsSummary = cfg.options.summary(),
            listener = WarningsOnlyListener(run.listener),
            emitter = emitter,
            wallClock = wallClock,
        )
        val spec = ExchangeSpec(
            exchangeId = run.exchangeId,
            command = setupCommand,
            payload = RequestEncoder.encode(EcrPrintingRequest(enabled = true), cfg.ids),
            expectedReplies = setupCommand.expectedReplies,
            kind = ExchangeKind.ACK_ONLY,
            ackPolicy = FinancialAckPolicy.RETRANSMIT,
            collectReceiptAfterAck = false,
            expectReceipt = false,
            strictResultLrc = false,
            freshConnection = fresh,
        )
        return try {
            session(cfg.options, inner, run.token).run(spec)
        } finally {
            // Anche se E1 esce con un'eccezione: i warning già visti restano nella trace del comando.
            trace.printingSetupSent = inner.anyMainFrameWriteAttempted
            trace.adopt(inner.warnings)
        }
    }

    private fun session(options: ProtocolOptions, trace: TraceRecorder, token: CancelToken) = Ecr17Session(
        transport = transport,
        options = options,
        emitter = emitter,
        trace = trace,
        cancel = token,
        timeSource = timeSource,
    )

    private fun shouldReplay(
        result: ExchangeResult,
        options: ProtocolOptions,
        command: CommandCode,
        token: CancelToken,
    ): Boolean = result is ExchangeResult.Failure &&
        result.failure is Ecr17Failure.Disconnected &&
        RetryPolicy.shouldRetryAfterReconnect(
            autoReconnect = options.autoReconnectSafeCommands,
            dropped = true,
            safeToRetry = command.safeToRetryAfterReconnect,
        ) &&
        !token.cancelled

    /** Tabella di §3.23 punto 9 (vincolante): `w` letto dal recorder, mai dal risultato. */
    private fun mapResult(command: CommandCode, result: ExchangeResult, trace: TraceRecorder): CommandOutcome {
        val written = trace.anyMainFrameWriteAttempted
        val built = trace.build(finishedAt = wallClock())
        return when (result) {
            is ExchangeResult.Success -> {
                val frame = result.result
                when {
                    frame != null -> {
                        val response = checkNotNull(ResponseParser.parse(frame.payload, command, maskPan = true)) {
                            "nessun parser per la risposta '${frame.replyCode}' a '${command.wire}'"
                        }
                        CommandOutcome.Answered(command, response, built)
                    }
                    command.kind == ExchangeKind.ACK_ONLY -> CommandOutcome.Acknowledged(command, built)
                    else -> error("scambio '${command.wire}' concluso senza esito")
                }
            }
            is ExchangeResult.Failure -> {
                val failure = result.failure
                when {
                    !written -> CommandOutcome.NotSent(command, failure, built)
                    failure is Ecr17Failure.Nak && trace.timeoutRetransmissions == 0 && trace.naksReceived >= trace.sends ->
                        CommandOutcome.Refused(command, failure, built)
                    command.journaled -> CommandOutcome.Unknown(command, failure, result.phase, built)
                    else -> CommandOutcome.Failed(command, failure, result.phase, built)
                }
            }
        }
    }

    /** Punto 11: eccezione inattesa ⇒ Internal con lo stesso `w` (mai NotSent dopo una scrittura, M10). */
    private fun internalOutcome(run: CommandRun, error: Throwable): CommandOutcome {
        val command = run.request.command
        val trace = run.trace
        val failure = Ecr17Failure.Internal(
            listOfNotNull(error::class.simpleName, error.message).joinToString(": ").ifEmpty { "errore sconosciuto" },
        )
        val built = trace?.build(finishedAt = wallClock())
        val phase = trace?.lastPhase ?: ExchangePhase.PREPARING
        return when {
            trace?.anyMainFrameWriteAttempted != true -> CommandOutcome.NotSent(command, failure, built)
            command.journaled -> CommandOutcome.Unknown(command, failure, phase, built)
            else -> CommandOutcome.Failed(command, failure, phase, built)
        }
    }

    private fun notSent(run: CommandRun, failure: Ecr17Failure): CommandOutcome =
        CommandOutcome.NotSent(run.request.command, failure, run.trace?.build(finishedAt = wallClock()))

    // ─── Punto 10: chiusura ──────────────────────────────────────────────────

    /**
     * PER_COMMAND chiude sempre. PERSISTENT chiude dopo un esito mancante o sospetto: così un esito tardivo di un
     * comando rimasto senza risposta arriva su un socket morto e non può essere letto come esito del successivo.
     */
    private suspend fun applyClosePolicy(run: CommandRun) {
        val cfg = run.config ?: return
        when (cfg.options.connectionMode) {
            ConnectionMode.PER_COMMAND -> if (run.connectionTouched || transport.isOpen) transport.close()
            ConnectionMode.PERSISTENT -> if (run.connectionTouched && persistentLinkMustClose(run, cfg)) transport.close()
        }
    }

    private fun persistentLinkMustClose(run: CommandRun, cfg: Ecr17Config): Boolean {
        val outcome = run.outcome
        val written = run.trace?.anyMainFrameWriteAttempted == true
        val clean = written && (outcome is CommandOutcome.Answered || outcome is CommandOutcome.Acknowledged ||
            outcome is CommandOutcome.Refused)
        if (!clean) return true
        if (run.trace?.warnings.orEmpty().any { it.code in SUSPICIOUS_WARNINGS }) return true
        // Configurazione cambiata durante il comando (passata a PER_COMMAND o altro endpoint): il link non serve più.
        val now = try {
            configProvider()
        } catch (e: Exception) {
            null
        }
        return now == null || now.options.connectionMode != ConnectionMode.PERSISTENT || now.endpoint != cfg.endpoint
    }

    private fun emitFinished(run: CommandRun) {
        val command = run.request.command
        val outcome = run.outcome
        val outcomeClass = outcome?.outcomeClass() ?: run.cancelledClass ?: OutcomeClass.NOT_SENT
        val failure = when (outcome) {
            null -> Ecr17Failure.Cancelled(byUser = run.token.cancelled)
            is CommandOutcome.NotSent -> outcome.failure
            is CommandOutcome.Refused -> outcome.failure
            is CommandOutcome.Failed -> outcome.failure
            is CommandOutcome.Unknown -> outcome.failure
            is CommandOutcome.Answered, is CommandOutcome.Acknowledged -> null
        }
        emitter.emit { seq, at ->
            ProtocolEvent.CommandFinished(seq, at, run.exchangeId, command, outcomeClass, failure)
        }
    }

    /**
     * Listener della sessione di E1: inoltra SOLO i warning al listener del comando. onPhase resta vuota (E1 non
     * scrive SENDING nel journal e non tocca il flag M10), così come progress e righe S; un esito orfano visto da
     * E1 diventa invece prova EVIDENCE sull'ultimo tentativo non risolto, come se l'avesse visto il pagamento (M4).
     */
    private class WarningsOnlyListener(private val target: ExchangeListener) : ExchangeListener {
        override fun onWarning(warning: ProtocolWarning) = target.onWarning(warning)
    }

    /** Stato di UN execute, condiviso tra il corpo e i rami catch/finally. */
    private class CommandRun(
        val exchangeId: Long,
        val request: Ecr17Request,
        val listener: ExchangeListener,
        val token: CancelToken,
    ) {
        var config: Ecr17Config? = null
        var trace: TraceRecorder? = null
        var connectionTouched: Boolean = false
        var outcome: CommandOutcome? = null
        var cancelledClass: OutcomeClass? = null
    }

    private companion object {
        const val EVENT_BUFFER = 256

        /** Warning che in PERSISTENT fanno chiudere il link: qualcosa sul filo non torna (§3.23 punto 10). */
        val SUSPICIOUS_WARNINGS: Set<WarningCode> = setOf(
            WarningCode.STALE_BYTES,
            WarningCode.POSSIBLE_LOST_RESULT,
            WarningCode.UNEXPECTED_REPLY,
            WarningCode.RESULT_RETRANSMITTED,
            WarningCode.EARLY_RESULT,
        )
    }
}
