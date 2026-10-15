package it.prime.protocollo17.ecr17.transport

import it.prime.protocollo17.ecr17.model.CloseCause
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.LinkState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Trasporto TCP reale (java.net.Socket). È l'unico pezzo di `ecr17` con un proprio scope su Dispatchers.IO:
 * client e sessione girano sul dispatcher del chiamante (§2.10), così i test in tempo virtuale restano esatti.
 *
 * Regole che vengono dagli errori dei repo (§3.18, A§5.6, A§8 D13):
 * - **un solo lettore** del socket (`ecr17-reader`), che accoda in [rx] e poi, alla fine, chiude l'epoca: nessun
 *   probe e nessuna `PushbackInputStream` che legge "per controllare" la connessione e ruba byte all'esito;
 * - lo stato del collegamento deriva dal lettore (EOF/IOException ⇒ Closed), mai da un flag azzerato a ogni
 *   transazione: una caduta tra due comandi è visibile prima di scrivere il successivo;
 * - le **epoche** dell'RxBuffer e il riferimento alla connessione impediscono che il lettore di una connessione
 *   vecchia scriva nel buffer o nello stato di quella nuova;
 * - una `write` su socket ignora l'interrupt: un watchdog chiude il socket allo scadere di writeTimeoutMs, e
 *   [abort] chiude subito socket e connect in corso da qualsiasi thread (Annulla dell'operatore).
 */
class TcpTransport(
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val readBufferSize: Int = 4096,
) : Ecr17Transport {

    init {
        require(readBufferSize > 0) { "readBufferSize deve essere > 0 (era $readBufferSize)" }
    }

    /** Una connessione aperta: il lettore e la chiusura lavorano sempre sulla LORO connessione, mai su "quella corrente". */
    private class Connection(
        val endpoint: Endpoint,
        val socket: Socket,
        val input: InputStream,
        val output: OutputStream,
        val epoch: Long,
    ) {
        /** Causa della chiusura decisa da noi (LOCAL, ABORTED, WRITE_TIMEOUT), impostata PRIMA di chiudere il socket. */
        @Volatile
        var closingCause: CloseCause? = null

        /** Closed pubblicato una sola volta per connessione, chiunque arrivi prima (lettore, close, abort). */
        @Volatile
        var closedPublished: Boolean = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("ecr17-io"))
    private val stateLock = Any()
    private val writeMutex = Mutex()

    private val mutableLinkState = MutableStateFlow<LinkState>(LinkState.Idle)
    override val linkState: StateFlow<LinkState> = mutableLinkState.asStateFlow()

    override val rx: RxBuffer = RxBuffer()

    @Volatile
    override var linkListener: ((LinkState) -> Unit)? = null

    @Volatile
    private var connection: Connection? = null

    /** Socket in costruzione: abort() lo chiude per sbloccare subito una connect() in corso. */
    @Volatile
    private var pendingSocket: Socket? = null

    /** Incrementato da abort(): una connect() iniziata prima di un abort non pubblica Connected. */
    @Volatile
    private var abortGeneration: Long = 0

    /** Solo per i test (stesso modulo): il socket della connessione corrente. */
    internal val currentSocket: Socket? get() = connection?.socket

    override suspend fun connect(endpoint: Endpoint, connectTimeoutMs: Long) {
        // Come close(): una connessione precedente viene chiusa con causa LOCAL.
        connection?.let { closeConnection(it, CloseCause.LOCAL, null) }
        if (!scope.isActive) throw TransportException(TransportErrorKind.NOT_CONNECTED, "trasporto terminato")
        val generation = abortGeneration
        publish(LinkState.Connecting(endpoint, wallClock()))
        withContext(Dispatchers.IO) {
            val started = TimeSource.Monotonic.markNow()
            val address = try {
                resolve(endpoint.host, connectTimeoutMs)
            } catch (e: TransportException) {
                publish(LinkState.ConnectFailed(endpoint, timeout = false, detail = e.detail, at = wallClock()))
                throw e
            }
            val remaining = (connectTimeoutMs - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(1)
            val socket = Socket().apply {
                tcpNoDelay = true
                keepAlive = false
                soTimeout = 0
            }
            pendingSocket = socket
            try {
                openSocket(socket, InetSocketAddress(address, endpoint.port), remaining)
            } catch (e: CancellationException) {
                closeQuietly(socket)
                publish(LinkState.ConnectFailed(endpoint, timeout = false, detail = "connessione annullata", at = wallClock()))
                throw e
            } catch (e: SocketTimeoutException) {
                closeQuietly(socket)
                publish(LinkState.ConnectFailed(endpoint, timeout = true, detail = e.message, at = wallClock()))
                throw TransportException(TransportErrorKind.CONNECT_TIMEOUT, "nessuna risposta entro $connectTimeoutMs ms", e)
            } catch (e: IOException) {
                closeQuietly(socket)
                val aborted = abortGeneration != generation
                val detail = if (aborted) "connessione annullata" else e.message
                publish(LinkState.ConnectFailed(endpoint, timeout = false, detail = detail, at = wallClock()))
                throw TransportException(
                    if (aborted) TransportErrorKind.ABORTED else TransportErrorKind.CONNECT_FAILED, detail, e,
                )
            } finally {
                pendingSocket = null
            }
            val opened = synchronized(stateLock) {
                if (abortGeneration != generation) {
                    null
                } else {
                    val c = Connection(
                        endpoint = endpoint,
                        socket = socket,
                        input = socket.getInputStream(),
                        output = socket.getOutputStream(),
                        epoch = rx.reset(),
                    )
                    connection = c
                    // Connected PRIMA di avviare il lettore: un EOF immediato non può essere scavalcato da Connected.
                    publishLocked(LinkState.Connected(endpoint, wallClock()))
                    c
                }
            }
            if (opened == null) {
                closeQuietly(socket)
                publish(LinkState.ConnectFailed(endpoint, timeout = false, detail = "connessione annullata", at = wallClock()))
                throw TransportException(TransportErrorKind.ABORTED, "connessione annullata")
            }
            startReader(opened)
        }
    }

    override suspend fun send(bytes: ByteArray, writeTimeoutMs: Long) {
        val c = connection
        // Una caduta già vista (rx chiuso) non deve diventare una scrittura persa nel vuoto.
        if (c == null || c.closedPublished || linkState.value !is LinkState.Connected || rx.closedCause != null) {
            throw TransportException(TransportErrorKind.NOT_CONNECTED, "collegamento non aperto")
        }
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val watchdog = scope.launch(CoroutineName("ecr17-write-watchdog")) {
                    delay(writeTimeoutMs)
                    closeConnection(c, CloseCause.WRITE_TIMEOUT, "scrittura oltre $writeTimeoutMs ms")
                }
                try {
                    c.output.write(bytes)
                    c.output.flush()
                } catch (e: IOException) {
                    val kind = when (c.closingCause) {
                        CloseCause.WRITE_TIMEOUT -> TransportErrorKind.WRITE_TIMEOUT
                        CloseCause.ABORTED -> TransportErrorKind.ABORTED
                        CloseCause.LOCAL -> TransportErrorKind.NOT_CONNECTED
                        else -> TransportErrorKind.WRITE_FAILED
                    }
                    throw TransportException(kind, e.message, e)
                } finally {
                    watchdog.cancel()
                }
            }
        }
    }

    override suspend fun close() {
        val c = connection ?: return
        closeConnection(c, CloseCause.LOCAL, null)
    }

    override fun abort() {
        synchronized(stateLock) { abortGeneration++ }
        pendingSocket?.let(::closeQuietly)
        val c = connection
        if (c != null) {
            closeConnection(c, CloseCause.ABORTED, "annullato")
        } else {
            rx.markClosedCurrent(CloseCause.ABORTED, "annullato")
        }
    }

    override fun shutdown() {
        abort()
        scope.cancel()
    }

    // ─── Dettagli ────────────────────────────────────────────────────────────

    /**
     * DNS in una coroutine separata con un tetto (A§6.3 punto 3): `InetAddress.getByName` non ha un timeout suo e
     * può bloccare per decine di secondi. Un IP letterale non passa dal resolver.
     */
    private suspend fun resolve(host: String, timeoutMs: Long): InetAddress {
        if (isIpLiteral(host)) {
            return try {
                InetAddress.getByName(host)
            } catch (e: IOException) {
                throw TransportException(TransportErrorKind.DNS_FAILED, "indirizzo non valido: $host", e)
            }
        }
        val lookup = scope.async(CoroutineName("ecr17-dns")) { InetAddress.getByName(host) }
        val address = try {
            withTimeoutOrNull(timeoutMs.milliseconds) { lookup.await() }
        } catch (e: CancellationException) {
            lookup.cancel()
            throw e
        } catch (e: Exception) {
            throw TransportException(TransportErrorKind.DNS_FAILED, "risoluzione di '$host' fallita: ${e.message}", e)
        }
        if (address == null) {
            lookup.cancel()
            throw TransportException(TransportErrorKind.DNS_FAILED, "risoluzione di '$host' oltre $timeoutMs ms")
        }
        return address
    }

    /**
     * `Socket.connect` è bloccante e ignora l'interrupt: gira nello scope IO e il chiamante attende un deferred.
     * Se il chiamante viene cancellato, il socket si chiude e la connect bloccata esce subito con un'eccezione.
     */
    private suspend fun openSocket(socket: Socket, address: InetSocketAddress, timeoutMs: Long) {
        val done = CompletableDeferred<Unit>()
        val job = scope.launch(CoroutineName("ecr17-connect")) {
            try {
                socket.connect(address, timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                done.complete(Unit)
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }
        // Scope già cancellato (shutdown): il job non parte mai, il deferred va chiuso comunque.
        job.invokeOnCompletion { cause ->
            if (cause != null) done.completeExceptionally(IOException("trasporto terminato", cause))
        }
        try {
            done.await()
        } catch (e: CancellationException) {
            closeQuietly(socket)
            throw e
        }
    }

    private fun startReader(c: Connection) {
        scope.launch(CoroutineName("ecr17-reader")) {
            val buffer = ByteArray(readBufferSize)
            try {
                while (true) {
                    val n = c.input.read(buffer)
                    if (n < 0) {
                        finish(c, CloseCause.PEER_CLOSED, null)
                        break
                    }
                    if (n > 0) rx.append(c.epoch, buffer, 0, n)
                }
            } catch (e: Exception) {
                // Exception e non solo IOException: qualsiasi uscita del lettore deve chiudere l'epoca, altrimenti
                // la sessione aspetterebbe fino alla deadline un collegamento che nessuno legge più.
                val cause = c.closingCause ?: when {
                    e.message?.contains("reset", ignoreCase = true) == true -> CloseCause.RESET
                    else -> CloseCause.IO_ERROR
                }
                finish(c, cause, e.message)
            }
        }
    }

    /** Fine del lettore: i dati sono già in rx, ora si chiude l'epoca (contratto di Ecr17Transport). */
    private fun finish(c: Connection, cause: CloseCause, detail: String?) {
        rx.markClosed(c.epoch, cause, detail)
        publishClosed(c, cause, detail)
        closeQuietly(c.socket)
    }

    private fun closeConnection(c: Connection, cause: CloseCause, detail: String?) {
        if (c.closingCause == null) c.closingCause = cause
        closeQuietly(c.socket)
        rx.markClosed(c.epoch, cause, detail)
        publishClosed(c, cause, detail)
    }

    private fun publishClosed(c: Connection, cause: CloseCause, detail: String?) {
        synchronized(stateLock) {
            if (c.closedPublished) return
            c.closedPublished = true
            // Solo la connessione corrente decide lo stato: un lettore vecchio non chiude quella nuova.
            if (connection === c) publishLocked(LinkState.Closed(c.endpoint, cause, detail, wallClock()))
        }
    }

    private fun publish(state: LinkState) {
        synchronized(stateLock) { publishLocked(state) }
    }

    /** linkListener invocato dopo ogni aggiornamento, sotto lo stesso lock: l'ordine degli eventi è quello degli stati. */
    private fun publishLocked(state: LinkState) {
        mutableLinkState.value = state
        try {
            linkListener?.invoke(state)
        } catch (e: Exception) {
            // Il listener (client) non deve poter uccidere il lettore né una connect.
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: IOException) {
            // Chiusura best effort: il socket è comunque inutilizzabile.
        }
    }

    private companion object {
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        fun isIpLiteral(host: String): Boolean = IPV4.matches(host) || host.contains(':')
    }
}
