package it.prime.protocollo17.ecr17.sim

import it.prime.protocollo17.ecr17.wire.Hex
import it.prime.protocollo17.ecr17.wire.PanMasker
import it.prime.protocollo17.ecr17.wire.WireFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Terminale finto su TCP loopback che parla il filo Ingenico osservato: serve ai test di integrazione reali
 * (socket, lettore, timeout) e all'operatore per provare comandi e guasti senza hardware (§4.1).
 *
 * Ciclo di vita (skill embedded-server §1 e §6): `ServerSocket` su `bindHost` (di default 127.0.0.1: nessun prompt del
 * firewall, nulla di esposto sulla LAN) in uno scope `SupervisorJob + Dispatchers.IO`, una coroutine per connessione
 * ([SimConnection]) e una sola connessione servita alla volta (la seconda attende, come un terminale vero). Porta
 * occupata ⇒ [start] lancia [BindException], che SimulatorControllerImpl mostra come stato `PortInUse`.
 *
 * L'ordine di [stop] non è reversibile: prima il `ServerSocket` (accept lancia), poi ogni socket client (le read
 * bloccanti lanciano), poi `cancelAndJoin` dello scope con un tetto di 2 s, infine `boundPort = null` ed evento
 * `Stopped`. Al contrario, stop() tornerebbe con la porta ancora legata e un riavvio immediato fallirebbe.
 *
 * Le risposte sono di [SimResponder] (puro); lo stato condiviso tra connessioni (contatori, esito trattenuto,
 * NAK_ONCE) sta in [SimCore] sotto lock, perché alcuni guasti coinvolgono due richieste su due connessioni.
 */
class TerminalSimulator(
    initial: SimulatorConfig = SimulatorConfig(),
    private val wallClock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val core = SimCore(initial, wallClock)

    val config: StateFlow<SimulatorConfig> = core.config.asStateFlow()

    /** Porta in ascolto, null se fermo. */
    val boundPort: StateFlow<Int?> = core.boundPort.asStateFlow()

    /** Log del simulatore (extraBufferCapacity 512, DROP_OLDEST). */
    val events: SharedFlow<SimEvent> = core.events.asSharedFlow()

    /** Serializza start/stop: due start concorrenti farebbero due bind. */
    private val lifecycle = Mutex()

    @Volatile
    private var running: Running? = null

    @Volatile
    private var closed = false

    /** Server in esecuzione: socket in ascolto, scope delle connessioni e socket client aperti. */
    private class Running(val server: ServerSocket, val scope: CoroutineScope, val port: Int) {
        private val lock = Any()
        private val sockets = mutableSetOf<Socket>()
        private var closing = false

        /** false se lo stop è già iniziato: il socket appena accettato va chiuso subito, non registrato. */
        fun register(socket: Socket): Boolean = synchronized(lock) {
            if (closing) false else sockets.add(socket).let { true }
        }

        fun unregister(socket: Socket) {
            synchronized(lock) { sockets.remove(socket) }
        }

        fun closeAll() {
            val toClose = synchronized(lock) {
                closing = true
                sockets.toList().also { sockets.clear() }
            }
            toClose.forEach(::closeQuietly)
        }
    }

    /** Vale dalla prossima richiesta ricevuta. bindHost e port valgono solo dal prossimo start(). */
    fun updateConfig(transform: (SimulatorConfig) -> SimulatorConfig) {
        core.config.update(transform)
    }

    /** Bind su bindHost:port; restituisce la porta effettiva. java.net.BindException se la porta è occupata; IllegalStateException se già avviato. */
    suspend fun start(): Int = lifecycle.withLock {
        check(!closed) { "Simulatore chiuso: crearne uno nuovo" }
        check(running == null) { "Simulatore già avviato sulla porta ${running?.port}" }
        val cfg = core.config.value
        // NonCancellable: con withContext cancellabile un annullamento a bind riuscito scarterebbe il ServerSocket
        // senza chiuderlo, e la porta resterebbe occupata fino alla fine del processo.
        val server = withContext(NonCancellable + Dispatchers.IO) { bind(cfg.bindHost, cfg.port) }
        try {
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            closeQuietly(server)
            throw e
        }
        val port = server.localPort
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("p17-sim"))
        val run = Running(server, scope, port)
        core.stopping = false
        running = run
        scope.launch(CoroutineName("p17-sim-accept")) { acceptLoop(run) }
        core.boundPort.value = port
        core.emit(SimEvent.Started(at = core.now(), port = port))
        port
    }

    /** Chiude server e connessioni e libera la porta; idempotente. */
    suspend fun stop() {
        // NonCancellable: uno stop interrotto a metà lascerebbe la porta legata (skill embedded-server §6).
        withContext(NonCancellable) {
            lifecycle.withLock {
                val run = running ?: return@withLock
                running = null
                core.stopping = true
                closeQuietly(run.server)                 // 1. accept() lancia
                run.closeAll()                           // 2. le read bloccanti lanciano
                val joined = withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) {
                    run.scope.coroutineContext[Job]?.cancelAndJoin()   // 3. ora il join è immediato
                    true
                }
                if (joined == null) core.note("Arresto non completato entro $STOP_JOIN_TIMEOUT_MS ms: la porta potrebbe restare occupata")
                core.boundPort.value = null
                core.emit(SimEvent.Stopped(at = core.now()))
            }
        }
    }

    /** Contatori e ultimo esito, per i test. */
    fun snapshot(): SimState = core.snapshot()

    /** Azzera contatori, STAN, totali, ultimo esito, esito trattenuto. */
    fun resetState() {
        core.reset()
        core.note("Stato del simulatore azzerato")
    }

    /** stop() bloccante; dopo close() il simulatore non si riavvia. Idempotente. */
    override fun close() {
        closed = true
        runBlocking { stop() }
    }

    // ─── Server ─────────────────────────────────────────────────────────────────

    /**
     * Bind esplicito su [host] (mai 0.0.0.0 per default). `reuseAddress` come da §4.3; su Windows la JVM usa il bind
     * esclusivo, quindi una porta già in ascolto dà comunque BindException invece di essere "rubata".
     */
    private fun bind(host: String, port: Int): ServerSocket {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName(host), port), BACKLOG)
            return server
        } catch (e: IOException) {
            closeQuietly(server)
            if (e is BindException) core.note("Porta $port già in uso su $host: simulatore non avviato")
            throw e
        }
    }

    /** Bloccante sul thread IO dello scope: termina quando stop() chiude il ServerSocket. */
    private fun acceptLoop(run: Running) {
        val scope = run.scope
        while (scope.isActive) {
            val socket = try {
                run.server.accept()
            } catch (e: IOException) {
                // stop() chiude il ServerSocket: è l'uscita normale. Altrimenti l'ascolto è morto da solo.
                if (!core.stopping) core.note("Ascolto interrotto: ${e.message ?: e::class.java.simpleName}")
                return
            }
            if (!run.register(socket)) {
                closeQuietly(socket)
                return
            }
            try {
                socket.tcpNoDelay = true
            } catch (e: IOException) {
                // opzione non applicabile su un socket già chiuso: la connessione finirà da sola
            }
            core.connectionOpened()
            val remote = socket.remoteSocketAddress?.toString()?.removePrefix("/") ?: "?"
            core.emit(SimEvent.ClientConnected(at = core.now(), remote = remote))
            scope.launch(CoroutineName("p17-sim-connection")) { serveConnection(run, socket, remote) }
        }
    }

    /** Una connessione: attende il turno (una sola servita alla volta), la serve, poi la chiude e lo annota. */
    private suspend fun serveConnection(run: Running, socket: Socket, remote: String) {
        var byPeer = false
        try {
            core.serving.withLock { byPeer = SimConnection(socket, core).serve() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Un bug nel simulatore chiude la connessione, mai il server: lo si vede nel log.
            core.note("Errore del simulatore sulla connessione $remote: ${e.message ?: e::class.java.simpleName}")
        } finally {
            run.unregister(socket)
            closeQuietly(socket)
            core.emit(SimEvent.ClientDisconnected(at = core.now(), remote = remote, byPeer = byPeer))
        }
    }

    private companion object {
        const val BACKLOG = 50
        const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}

/** Chiusura senza eccezioni: usata solo in pulizia, dove un errore non ha nessuno a cui essere riportato. */
private fun closeQuietly(closeable: AutoCloseable) {
    try {
        closeable.close()
    } catch (e: Exception) {
        // già chiuso o non chiudibile: in pulizia non c'è altro da fare
    }
}

// ─── Stato condiviso ────────────────────────────────────────────────────────────

/**
 * Stato condiviso tra le connessioni del simulatore: configurazione, memoria (sotto lock), eventi e turno di
 * servizio. Separato da [TerminalSimulator] perché [SimConnection] (internal) ci lavora direttamente.
 */
internal class SimCore(initial: SimulatorConfig, private val wallClock: () -> Long) {

    val config = MutableStateFlow(initial)
    val boundPort = MutableStateFlow<Int?>(null)
    val events = MutableSharedFlow<SimEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Una sola connessione servita alla volta: la seconda attende il turno (§4.3). */
    val serving = Mutex()

    /** true durante stop(): le chiusure dei socket non sono "dell'ECR". */
    @Volatile
    var stopping: Boolean = false

    private val lock = Any()
    private var memory = SimMemory()

    fun now(): Long = wallClock()

    fun currentConfig(): SimulatorConfig = config.value

    fun emit(event: SimEvent) {
        events.tryEmit(event)
    }

    fun note(text: String) = emit(SimEvent.Note(at = now(), text = text))

    fun snapshot(): SimState = synchronized(lock) { memory.toState() }

    fun reset() {
        synchronized(lock) { memory = SimMemory() }
    }

    fun connectionOpened() {
        synchronized(lock) { memory = memory.copy(connections = memory.connections + 1) }
    }

    /** Piano per una richiesta: SimResponder sotto lock, così due connessioni non si contendono STAN ed esito trattenuto. */
    fun plan(request: WireFrame.Application): SimPlan {
        val cfg = config.value
        val plan = synchronized(lock) {
            SimResponder.respond(request, cfg, memory, localNow()).also { memory = it.memory }
        }
        plan.notes.forEach(::note)
        return plan
    }

    fun connectSteps(): List<SimStep> {
        val cfg = config.value
        return synchronized(lock) { SimResponder.onConnect(cfg, memory) }
    }

    /** Ogni frame applicativo ricevuto, ritrasmissioni e richieste rifiutate comprese (il doppio invio si vede qui). */
    fun recordApplication(frame: WireFrame.Application) {
        val code = frame.replyCode ?: '?'
        synchronized(lock) {
            val counts = memory.applicationFramesReceived
            memory = memory.copy(applicationFramesReceived = counts + (code to (counts[code] ?: 0) + 1))
        }
        val mode = config.value.appLrcMode
        val lrc = "LRC %02X %s in %s".format(frame.lrc, if (mode in frame.check.matchingModes) "ok" else "ERRATO", mode.wireName)
        // Le richieste dell'ECR non contengono PAN: i byte restano quelli del filo (§3.8).
        emit(SimEvent.FrameIn(at = now(), bytes = frame.raw.copyOf(), description = "Richiesta '$code' (${frame.payload.size} B), $lrc"))
    }

    fun recordControl(frame: WireFrame.Control) {
        val hex = Hex.spaced(frame.raw)
        synchronized(lock) {
            memory = memory.copy(controlFramesReceived = (memory.controlFramesReceived + hex).takeLast(CONTROL_HISTORY))
        }
        emit(SimEvent.FrameIn(at = now(), bytes = frame.raw.copyOf(), description = "${frame.kind} dall'ECR (${frame.match})"))
    }

    /** Byte scartati dallo splitter: nel log con maskDigitRuns, un frammento potrebbe contenere un PAN (M9). */
    fun noteDiscarded(bytes: ByteArray, reason: String) {
        note("Byte scartati ($reason): ${Hex.spaced(PanMasker.maskDigitRuns(bytes))}")
    }

    /** Ora locale per data/ora negli esiti (DDDHHMM, DDMMYYhhmm), dal wallClock iniettato. */
    private fun localNow(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(wallClock()), ZoneId.systemDefault())

    private companion object {
        const val EVENT_BUFFER = 512
        /** Tetto della cronologia degli ACK/NAK ricevuti: in app il simulatore può restare acceso a lungo. */
        const val CONTROL_HISTORY = 500
    }
}
