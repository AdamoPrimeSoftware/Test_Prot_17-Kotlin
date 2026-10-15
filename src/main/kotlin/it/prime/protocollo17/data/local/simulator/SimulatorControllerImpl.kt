package it.prime.protocollo17.data.local.simulator

import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.data.mapper.SimulatorConfigMapper
import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.model.settings.SimulatorSettings
import it.prime.protocollo17.domain.model.simulator.SimLogEntry
import it.prime.protocollo17.domain.model.simulator.SimStats
import it.prime.protocollo17.domain.model.simulator.SimulatorFailure
import it.prime.protocollo17.domain.model.simulator.SimulatorStatus
import it.prime.protocollo17.domain.model.simulator.runningPort
import it.prime.protocollo17.domain.repository.SimulatorController
import it.prime.protocollo17.domain.util.ConfigResolver
import it.prime.protocollo17.ecr17.sim.SimEvent
import it.prime.protocollo17.ecr17.sim.TerminalSimulator
import it.prime.protocollo17.ecr17.wire.ControlChars
import it.prime.protocollo17.ecr17.wire.Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.BindException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ponte tra impostazioni e TerminalSimulator: mappa AppSettings.simulator sulla configurazione dell'engine,
 * lo riavvia al cambio di porta e trasforma i fallimenti (porta occupata) in stato utente, mai in eccezioni.
 *
 * Skill embedded-server §1: la UI vede solo [status], [log] e [stats], mai l'engine; una porta occupata è uno
 * stato da spiegare accanto al campo porta, non un crash. Le impostazioni sono un StateFlow e valgono subito:
 * un cambio della sezione simulatore aggiorna l'engine, un cambio di porta lo riavvia se è in esecuzione.
 */
class SimulatorControllerImpl(
    private val settings: SettingsRepository,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : SimulatorController {

    private val logger = LoggerFactory.getLogger(SimulatorControllerImpl::class.java)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("simulator") +
            CoroutineExceptionHandler { _, e -> logger.error("Simulatore: errore inatteso", e) },
    )

    /** Serializza start/stop/riavvio: due start concorrenti farebbero due bind sulla stessa porta. */
    private val lifecycle = Mutex()
    private val closed = AtomicBoolean(false)

    private val initialMapping = SimulatorConfigMapper.toConfig(settings.getSnapshot().simulator)
    private val simulator = TerminalSimulator(initial = initialMapping.config, wallClock = wallClock)

    private val _status = MutableStateFlow<SimulatorStatus>(SimulatorStatus.Stopped)
    override val status: StateFlow<SimulatorStatus> = _status.asStateFlow()

    private val logLock = Any()
    private val logBuffer = ArrayDeque<SimLogEntry>()
    private var nextLogId = 0L
    private val _log = MutableStateFlow<List<SimLogEntry>>(emptyList())
    override val log: StateFlow<List<SimLogEntry>> = _log.asStateFlow()

    private val _stats = MutableStateFlow(SimStats())
    override val stats: StateFlow<SimStats> = _stats.asStateFlow()

    @Volatile
    private var lastCommand: Char? = null

    /** true se terminal.host/port puntano al simulatore in esecuzione. */
    override val isTarget: StateFlow<Boolean> = combine(settings.flow, _status) { s, st -> pointsToSimulator(s, st) }
        .stateIn(scope, SharingStarted.Eagerly, pointsToSimulator(settings.getSnapshot(), SimulatorStatus.Stopped))

    init {
        initialMapping.warnings.forEach(::note)
        // UNDISPATCHED: la sottoscrizione avviene qui, prima che un avvio automatico possa emettere Started, e
        // prima che un cambio di impostazioni immediato venga scambiato per il valore iniziale da scartare.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { simulator.events.collect(::onSimEvent) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            settings.flow.map { it.simulator }.distinctUntilChanged().drop(1).collect(::applySettings)
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            settings.flow.map { it.simulator.port }.distinctUntilChanged().drop(1).collect { restartIfRunning() }
        }
        // Un fallimento dell'avvio automatico resta nello stato Failed: la finestra principale lo notifica (§6.18).
        if (settings.getSnapshot().simulator.autoStart) scope.launch { start() }
    }

    // ─── Ciclo di vita ─────────────────────────────────────────────────────────

    /** Avvia sulla porta delle impostazioni; restituisce lo stato finale (Running o Failed). Mai un'eccezione. */
    override suspend fun start(): SimulatorStatus = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            val current = _status.value
            if (current is SimulatorStatus.Running || closed.get()) return@withLock current
            val mapping = SimulatorConfigMapper.toConfig(settings.getSnapshot().simulator)
            val port = mapping.config.port
            _status.value = SimulatorStatus.Starting
            try {
                val result = try {
                    simulator.updateConfig { mapping.config }
                    SimulatorStatus.Running(simulator.start())
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { stopEngineQuietly() }
                    _status.value = SimulatorStatus.Stopped
                    throw e
                } catch (e: BindException) {
                    logger.warn("Simulatore: porta {} già in uso ({})", port, e.message)
                    SimulatorStatus.Failed(SimulatorFailure.PortInUse(port))
                } catch (e: Exception) {
                    logger.error("Simulatore: avvio non riuscito sulla porta {}", port, e)
                    SimulatorStatus.Failed(SimulatorFailure.Unexpected(e.message ?: e::class.java.simpleName))
                }
                _status.value = result
                result
            } finally {
                // Mai lasciare "Avvio…" per sempre: anche un Error inatteso finisce in uno stato visibile.
                if (_status.value == SimulatorStatus.Starting) {
                    _status.value = SimulatorStatus.Failed(SimulatorFailure.Unexpected("avvio interrotto"))
                }
            }
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            lifecycle.withLock {
                stopEngineQuietly()
                _status.value = SimulatorStatus.Stopped
            }
        }
    }

    /** terminal.host = 127.0.0.1, terminal.port = porta attiva, salvando la destinazione precedente. false se non è in esecuzione. */
    override fun useAsTarget(): Boolean {
        val port = _status.value.runningPort ?: return false
        settings.update { s ->
            // Già puntato al simulatore (anche su una porta vecchia): la destinazione salvata è quella vera, non
            // va sovrascritta con 127.0.0.1, o "Ripristina terminale" non riporterebbe più al terminale reale.
            val alreadySaved = s.simulator.previousHost != null && isLoopback(s.terminal.host)
            s.copy(
                terminal = s.terminal.copy(host = LOOPBACK, port = port),
                simulator = if (alreadySaved) s.simulator else s.simulator.copy(previousHost = s.terminal.host, previousPort = s.terminal.port),
            )
        }
        return true
    }

    /** Rimette previousHost/previousPort. false se non c'è una destinazione salvata. */
    override fun restoreTarget(): Boolean {
        var restored = false
        settings.update { s ->
            val host = s.simulator.previousHost
            restored = host != null
            if (host == null) {
                s
            } else {
                s.copy(
                    terminal = s.terminal.copy(host = host, port = s.simulator.previousPort),
                    simulator = s.simulator.copy(previousHost = null, previousPort = null),
                )
            }
        }
        return restored
    }

    override fun clearLog() {
        synchronized(logLock) {
            logBuffer.clear()
            _log.value = emptyList()
        }
    }

    override fun resetState() {
        try {
            simulator.resetState()
        } catch (e: Exception) {
            logger.warn("Simulatore: azzeramento non riuscito: {}", e.message)
        }
        lastCommand = null
        refreshStats()
    }

    /** stop() + cancellazione dello scope; idempotente. La porta è liberata al ritorno. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            runBlocking(Dispatchers.IO) { withTimeoutOrNull(STOP_TIMEOUT_MS) { stopEngineQuietly() } }
        } catch (e: Exception) {
            logger.warn("Simulatore: arresto alla chiusura non riuscito: {}", e.message)
        }
        try {
            simulator.close()
        } catch (e: Exception) {
            logger.warn("Simulatore: chiusura non riuscita: {}", e.message)
        }
        _status.value = SimulatorStatus.Stopped
        scope.cancel()
    }

    // ─── Impostazioni ──────────────────────────────────────────────────────────

    private fun applySettings(section: SimulatorSettings) {
        val mapping = SimulatorConfigMapper.toConfig(section)
        mapping.warnings.forEach(::note)
        try {
            // Vale dalla prossima richiesta; la porta vale dal prossimo start() (riavvio in restartIfRunning).
            simulator.updateConfig { mapping.config }
        } catch (e: Exception) {
            logger.warn("Simulatore: configurazione non applicata: {}", e.message)
        }
    }

    private suspend fun restartIfRunning() {
        if (_status.value !is SimulatorStatus.Running) return
        note("Porta cambiata: riavvio del simulatore")
        stop()
        start()
    }

    private suspend fun stopEngineQuietly() {
        try {
            simulator.stop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Simulatore: arresto non riuscito: {}", e.message)
        }
    }

    private fun pointsToSimulator(s: AppSettings, status: SimulatorStatus): Boolean {
        val port = status.runningPort ?: return false
        val endpoint = ConfigResolver.resolve(s).config.endpoint
        return isLoopback(endpoint.host) && endpoint.port == port
    }

    private fun isLoopback(host: String): Boolean = host.trim().lowercase() in LOOPBACK_NAMES

    // ─── Log e contatori ───────────────────────────────────────────────────────

    private fun onSimEvent(event: SimEvent) {
        val (direction, text, bytes) = when (event) {
            is SimEvent.Started -> Triple("", "Simulatore in ascolto su $LOOPBACK:${event.port}", null)
            is SimEvent.Stopped -> Triple("", "Simulatore fermato", null)
            is SimEvent.ClientConnected -> Triple("", "Connessione da ${event.remote}", null)
            is SimEvent.ClientDisconnected ->
                Triple("", "Connessione con ${event.remote} chiusa " + if (event.byPeer) "dall'ECR" else "dal simulatore", null)
            is SimEvent.FrameIn -> {
                commandOf(event.bytes)?.let { lastCommand = it }
                Triple("IN", event.description, event.bytes)
            }
            // I byte di FrameOut sono già mascherati dal simulatore (PanMasker.maskFrame, M9).
            is SimEvent.FrameOut -> Triple("OUT", event.description, event.bytes)
            is SimEvent.Note -> Triple("", event.text, null)
        }
        addLog(event.at, direction, text, bytes?.let { Hex.spaced(it) })
        refreshStats()
    }

    private fun note(text: String) = addLog(wallClock(), "", text, null)

    private fun addLog(at: Long, direction: String, text: String, hex: String?) {
        synchronized(logLock) {
            logBuffer.addLast(SimLogEntry(id = nextLogId++, at = at, direction = direction, text = text, hex = hex))
            while (logBuffer.size > LOG_CAP) logBuffer.removeFirst()
            _log.value = logBuffer.toList()
        }
    }

    private fun refreshStats() {
        val s = try {
            simulator.snapshot()
        } catch (e: Exception) {
            logger.warn("Simulatore: contatori non disponibili: {}", e.message)
            return
        }
        _stats.value = SimStats(
            connections = s.connections,
            requests = s.applicationFramesReceived.values.sum(),
            rejectedForLrc = s.requestsRejectedForLrc,
            lastCommand = lastCommand,
            posTotalCents = s.posTotalCents,
        )
    }

    /** Codice (pos 10) di un frame applicativo STX…: indice 10 del frame (STX + 9 byte di TID e cifra). */
    private fun commandOf(frame: ByteArray): Char? =
        if (frame.size > 10 && (frame[0].toInt() and 0xFF) == ControlChars.STX) (frame[10].toInt() and 0xFF).toChar() else null

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        val LOOPBACK_NAMES = setOf("127.0.0.1", "localhost", "::1")
        const val LOG_CAP = 500
        const val STOP_TIMEOUT_MS = 3_000L
    }
}
