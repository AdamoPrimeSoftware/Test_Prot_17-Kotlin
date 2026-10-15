package it.prime.protocollo17.ecr17.transport

import it.prime.protocollo17.ecr17.model.CloseCause
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.LinkState
import it.prime.protocollo17.ecr17.profile.ControlLrcRxPolicy
import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.ecr17.wire.ControlKind
import it.prime.protocollo17.ecr17.wire.ControlLrcMatch
import it.prime.protocollo17.ecr17.wire.FrameSplitter
import it.prime.protocollo17.ecr17.wire.LrcMode
import it.prime.protocollo17.ecr17.wire.SplitItem
import it.prime.protocollo17.ecr17.wire.WireFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Trasporto TCP con socket reali su loopback (§7.5, TT-01…TT-11). Tempo reale: timeout brevi e un `withTimeout` di
 * sicurezza attorno a ogni test; si osservano stati (`linkState.first { … }`), mai `delay` per "aspettare che succeda".
 *
 * Protegge il contratto del trasporto su cui poggia la sessione: nessun byte perso tra due write del peer, i dati
 * ricevuti prima di una chiusura leggibili PRIMA della chiusura, cause di chiusura distinte (peer, reset, annullo),
 * un lettore vecchio che non tocca la connessione nuova, abort() che sblocca subito lettore e connect.
 * Porta sempre 0 (effimera) su 127.0.0.1: i worktree paralleli non si pestano i piedi.
 */
class TcpTransportTest {

    private lateinit var server: ServerSocket
    private val transports = mutableListOf<TcpTransport>()
    private val peers = mutableListOf<Socket>()

    @BeforeEach
    fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    }

    @AfterEach
    fun tearDown() {
        transports.forEach { it.shutdown() }
        peers.forEach { runCatching { it.close() } }
        server.close()
    }

    private val endpoint: Endpoint get() = Endpoint(InetAddress.getLoopbackAddress().hostAddress, server.localPort)

    private fun transport(): TcpTransport = TcpTransport().also { transports += it }

    /** accept() bloccante su un thread a parte: il test resta libero di connettere. */
    private fun acceptAsync(): CompletableFuture<Socket> {
        val future = CompletableFuture<Socket>()
        thread(isDaemon = true, name = "tt-accept") {
            try {
                future.complete(server.accept().also { synchronized(peers) { peers += it } })
            } catch (e: IOException) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    private fun CompletableFuture<Socket>.peer(): Socket = get(5, TimeUnit.SECONDS)

    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) { block() }
    }

    /** Legge da rx finché i byte accumulati soddisfano [done] (o rx si chiude). */
    private suspend fun RxBuffer.collect(done: (ByteArray) -> Boolean): ByteArray {
        var acc = ByteArray(0)
        while (!done(acc)) {
            awaitDataOrClose()
            acc += takeAll()
            if (closedCause != null) {
                acc += takeAll()
                break
            }
        }
        return acc
    }

    private suspend fun TcpTransport.awaitClosed(): LinkState.Closed =
        linkState.first { it is LinkState.Closed } as LinkState.Closed

    @Test
    fun `TT-01 andata e ritorno`() = realTime {
        val accepted = acceptAsync()
        val t = transport()
        t.connect(endpoint, 2_000)
        val peer = accepted.peer()
        thread(isDaemon = true) {
            val buf = ByteArray(256)
            val n = peer.getInputStream().read(buf)
            peer.getOutputStream().write(buf, 0, n)
            peer.getOutputStream().flush()
        }
        val frame = Frames.app("000000000s")

        t.send(frame, 2_000)
        val echoed = t.rx.collect { it.size >= frame.size }

        assertArrayEquals(frame, echoed)
        assertInstanceOf(LinkState.Connected::class.java, t.linkState.value)
    }

    @Test
    fun `TT-02 ACK spezzato in due write ricomposto dallo splitter`() = realTime {
        val accepted = acceptAsync()
        val t = transport()
        t.connect(endpoint, 2_000)
        val peer = accepted.peer()
        thread(isDaemon = true) {
            peer.getOutputStream().apply { write(byteArrayOf(0x06)); flush() }
            Thread.sleep(50)
            peer.getOutputStream().apply { write(byteArrayOf(0x03, 0x7A)); flush() }
        }
        val splitter = FrameSplitter(LrcMode.STX, ControlLrcRxPolicy.TOLERANT)
        val items = mutableListOf<SplitItem>()

        while (items.none { it is SplitItem.Parsed }) {
            t.rx.awaitDataOrClose()
            splitter.feed(t.rx.takeAll())
            while (true) items += splitter.next() ?: break
        }

        val ack = (items.single() as SplitItem.Parsed).frame as WireFrame.Control
        assertEquals(ControlKind.ACK, ack.kind)
        assertEquals(ControlLrcMatch.WIRE, ack.match)
        assertArrayEquals(Frames.ACKW, ack.raw)
        assertEquals(0, splitter.pendingSize(), "nessun byte perso o pendente")
    }

    @Test
    fun `TT-03 i dati del peer sono in rx prima della chiusura`() = realTime {
        val accepted = acceptAsync()
        val t = transport()
        t.connect(endpoint, 2_000)
        accepted.peer().apply {
            getOutputStream().write("HELLO".toByteArray(Charsets.US_ASCII))
            getOutputStream().flush()
            close()
        }

        val closed = t.awaitClosed()

        assertEquals(CloseCause.PEER_CLOSED, closed.cause)
        assertEquals(CloseCause.PEER_CLOSED, t.rx.closedCause)
        assertEquals("HELLO", String(t.rx.takeAll(), Charsets.US_ASCII), "dati leggibili dopo la chiusura")
    }

    @Test
    fun `TT-04 reset del peer, Closed(RESET)`() = realTime {
        val accepted = acceptAsync()
        val t = transport()
        t.connect(endpoint, 2_000)
        accepted.peer().apply {
            setSoLinger(true, 0)
            close()
        }

        assertEquals(CloseCause.RESET, t.awaitClosed().cause)
    }

    @Test
    fun `TT-05 porta chiusa, CONNECT_FAILED`() = realTime {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val t = transport()

        val e = assertThrows(TransportException::class.java) {
            runBlocking { t.connect(Endpoint(InetAddress.getLoopbackAddress().hostAddress, closedPort), 5_000) }
        }

        assertEquals(TransportErrorKind.CONNECT_FAILED, e.kind)
        val state = assertInstanceOf(LinkState.ConnectFailed::class.java, t.linkState.value)
        assertFalse(state.timeout)
    }

    @Tag("network")
    @Test
    fun `TT-06 indirizzo non instradabile, CONNECT_TIMEOUT`() = realTime {
        val t = transport()

        val e = try {
            t.connect(Endpoint(BLACKHOLE, 9), 300)
            null
        } catch (e: TransportException) {
            e
        }

        Assumptions.assumeTrue(
            e?.kind == TransportErrorKind.CONNECT_TIMEOUT,
            "la rete di questa macchina non lascia scadere la connect verso $BLACKHOLE (${e?.kind})",
        )
        assertTrue((t.linkState.value as LinkState.ConnectFailed).timeout)
    }

    @Test
    fun `TT-07 abort sblocca subito il lettore`() = realTime {
        val accepted = acceptAsync()
        val t = transport()
        t.connect(endpoint, 2_000)
        val peer = accepted.peer()

        t.abort()

        assertEquals(CloseCause.ABORTED, t.awaitClosed().cause)
        assertEquals(CloseCause.ABORTED, t.rx.closedCause)
        peer.soTimeout = 2_000
        val eof = try {
            peer.getInputStream().read()
        } catch (e: IOException) {
            -1
        }
        assertEquals(-1, eof, "il socket lato ECR è stato chiuso")
    }

    @Tag("network")
    @Test
    fun `TT-07b abort sblocca una connect in corso`() = realTime {
        val t = transport()
        val pending = async(Dispatchers.Default) {
            try {
                t.connect(Endpoint(BLACKHOLE, 9), 10_000)
                null
            } catch (e: TransportException) {
                e
            }
        }
        t.linkState.first { it is LinkState.Connecting }
        delay(200)   // la connect deve essere davvero in corso (SYN senza risposta)
        Assumptions.assumeTrue(pending.isActive, "la connect verso $BLACKHOLE è fallita da sola: rete non adatta")

        val started = System.nanoTime()
        t.abort()
        val e = pending.await()

        assertEquals(TransportErrorKind.ABORTED, e?.kind)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "abort deve sbloccare subito la connect")
    }

    @Test
    fun `TT-08 socket con TCP_NODELAY e senza keep-alive`() = realTime {
        acceptAsync()
        val t = transport()
        t.connect(endpoint, 2_000)

        val socket = t.currentSocket!!
        assertTrue(socket.tcpNoDelay)
        assertFalse(socket.keepAlive)
    }

    @Test
    fun `TT-09 send dopo la chiusura del peer, NOT_CONNECTED`() = realTime {
        val accepted = acceptAsync()
        val t = transport()
        t.connect(endpoint, 2_000)
        accepted.peer().close()
        t.awaitClosed()

        val e = assertThrows(TransportException::class.java) {
            runBlocking { t.send(Frames.app("000000000s"), 1_000) }
        }

        assertEquals(TransportErrorKind.NOT_CONNECTED, e.kind)
    }

    @Test
    fun `TT-10 riconnessione, il lettore vecchio non tocca la nuova epoca`() = realTime {
        val firstAccept = acceptAsync()
        val t = transport()
        t.connect(endpoint, 2_000)
        val oldPeer = firstAccept.peer()

        val secondAccept = acceptAsync()
        t.connect(endpoint, 2_000)   // chiude la prima connessione (LOCAL)
        val newPeer = secondAccept.peer()

        // Il lato ECR della prima connessione è chiuso: il lettore vecchio è uscito o sta uscendo.
        oldPeer.soTimeout = 2_000
        val oldEof = try {
            oldPeer.getInputStream().read()
        } catch (e: IOException) {
            -1
        }
        assertEquals(-1, oldEof)
        runCatching { oldPeer.getOutputStream().write("OLD".toByteArray(Charsets.US_ASCII)) }
        newPeer.getOutputStream().apply { write("NEW".toByteArray(Charsets.US_ASCII)); flush() }

        val received = t.rx.collect { String(it, Charsets.US_ASCII).contains("NEW") }

        assertEquals("NEW", String(received, Charsets.US_ASCII))
        // Finestra breve e limitata per dare al lettore vecchio il tempo di chiudere (se sbagliasse epoca).
        repeat(10) {
            assertNull(t.rx.closedCause, "il lettore vecchio ha chiuso l'epoca nuova")
            assertInstanceOf(LinkState.Connected::class.java, t.linkState.value)
            delay(20)
        }
        assertFalse(String(t.rx.takeAll(), Charsets.US_ASCII).contains("OLD"))
    }

    @Test
    fun `TT-11 close idempotente, Closed(LOCAL)`() = realTime {
        acceptAsync()
        val t = transport()
        val states = mutableListOf<LinkState>()
        t.linkListener = { synchronized(states) { states += it } }
        t.connect(endpoint, 2_000)

        t.close()
        t.close()

        val closed = assertInstanceOf(LinkState.Closed::class.java, t.linkState.value)
        assertEquals(CloseCause.LOCAL, closed.cause)
        assertEquals(1, synchronized(states) { states.count { it is LinkState.Closed } }, "un solo Closed: $states")
        assertEquals(CloseCause.LOCAL, t.rx.closedCause)
    }

    @Test
    fun `host irrisolvibile, DNS_FAILED`() = realTime {
        val t = transport()

        val e = assertThrows(TransportException::class.java) {
            runBlocking { t.connect(Endpoint("ecr17-host-inesistente.invalid", 5577), 3_000) }
        }

        assertEquals(TransportErrorKind.DNS_FAILED, e.kind)
        assertInstanceOf(LinkState.ConnectFailed::class.java, t.linkState.value)
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000L

        /** Indirizzo privato senza host: un SYN senza risposta (la connect scade invece di fallire). */
        const val BLACKHOLE = "10.255.255.1"
    }
}
