package it.prime.protocollo17.ecr17.sim

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Ciclo di vita del simulatore (§7.6 SL-01…SL-06, skill embedded-server §8) con socket reali su 127.0.0.1 e porta 0
 * (effimera: i worktree paralleli non si pestano i piedi). Tempo reale con un `withTimeout` di sicurezza; si osservano
 * stati, mai `delay` per "aspettare che succeda".
 *
 * Protegge l'ordine di stop(): prima il ServerSocket, poi i client, poi lo scope. Al contrario, stop() tornerebbe con
 * la porta ancora legata (riavvio con "Address already in use") o resterebbe appeso su una read bloccante.
 */
class TerminalSimulatorLifecycleTest {

    private val simulators = mutableListOf<TerminalSimulator>()
    private val sockets = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        simulators.forEach { it.close() }
    }

    private fun simulator(config: SimulatorConfig = SimulatorConfig(port = 0)): TerminalSimulator =
        TerminalSimulator(config).also { simulators += it }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) { block() }
    }

    private val loopback: InetAddress get() = InetAddress.getByName("127.0.0.1")

    /** Attende una condizione reale su Dispatchers.Default (il simulatore lavora su IO, il tempo virtuale non vale). */
    private suspend fun awaitTrue(what: () -> String, predicate: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeoutOrNull(AWAIT_MS) {
            while (!predicate()) delay(POLL_MS)
            true
        } ?: fail<Unit>(what())
    }

    /** Thread fermi dentro codice del simulatore (tipicamente una read bloccante rimasta viva); il test non conta. */
    private fun simulatorThreads(): List<String> {
        fun StackTraceElement.inSimulator() =
            className.startsWith("it.prime.protocollo17.ecr17.sim.") && !className.contains("LifecycleTest")
        return Thread.getAllStackTraces()
            .filter { (_, stack) -> stack.any { it.inSimulator() } }
            .map { (thread, stack) -> thread.name + " @ " + stack.first { it.inSimulator() } }
    }

    /**
     * Il ServerSocket del simulatore, letto per riflessione: è privato di proposito, e un accept() che fallisce da solo
     * (EMFILE) non si riproduce con l'API pubblica. Serve solo a SL-08.
     */
    private fun listenerOf(sim: TerminalSimulator): ServerSocket {
        val running = TerminalSimulator::class.java.getDeclaredField("running").apply { isAccessible = true }.get(sim)
            ?: fail<Any>("simulatore non avviato")
        return running.javaClass.getDeclaredField("server").apply { isAccessible = true }.get(running) as ServerSocket
    }

    /** Prima: nessuna porta restituita da start(port = 0). Ora la porta effettiva è sia il ritorno sia boundPort. */
    @Test
    fun `SL-01 start su porta 0 restituisce una porta effimera e la pubblica`() = realTime {
        val sim = simulator()
        val started = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            sim.events.first { it is SimEvent.Started } as SimEvent.Started
        }
        val port = sim.start()

        assertTrue(port > 0, "porta effettiva: $port")
        assertEquals(port, sim.boundPort.value, "boundPort valorizzata")
        assertEquals(port, started.await().port, "evento Started con la porta")
        Socket(loopback, port).use { assertTrue(it.isConnected, "il simulatore accetta su 127.0.0.1") }
    }

    /** Una stop() che non libera la porta fa fallire il riavvio con "Address already in use". */
    @Test
    fun `SL-02 start, stop e di nuovo start sulla stessa porta`() = realTime {
        val sim = simulator()
        val port = sim.start()
        sim.stop()
        assertNull(sim.boundPort.value, "boundPort azzerata dopo stop")

        sim.updateConfig { it.copy(port = port) }
        assertEquals(port, sim.start(), "riavvio sulla stessa porta")
    }

    /** La porta occupata è un errore esplicito (BindException), mai un bind "rubato" né uno stato a metà. */
    @Test
    fun `SL-03 un secondo simulatore sulla porta occupata lancia BindException`() = realTime {
        val first = simulator()
        val port = first.start()
        val second = simulator(SimulatorConfig(port = port))

        assertThrows(BindException::class.java) { runBlocking { second.start() } }
        assertNull(second.boundPort.value, "nessuna porta pubblicata dopo il fallimento")
        second.updateConfig { it.copy(port = 0) }
        assertTrue(second.start() > 0, "dopo il fallimento il simulatore si avvia su un'altra porta")
        Socket(loopback, port).use { assertTrue(it.isConnected, "il primo simulatore è ancora in ascolto") }
    }

    /** Dopo stop() la porta appartiene di nuovo al sistema: un altro programma ci fa bind subito. */
    @Test
    fun `SL-04 dopo stop un ServerSocket del test fa bind sulla stessa porta`() = realTime {
        val sim = simulator()
        val port = sim.start()
        sim.stop()

        ServerSocket(port, 50, loopback).use { assertEquals(port, it.localPort, "porta liberata") }
    }

    /**
     * Prima (nessun ordine): la join attendeva una read bloccante che la cancellazione non interrompe. Ora il socket
     * client è chiuso prima della join: il client vede EOF, stop() torna entro 2 s e nessun thread resta nel simulatore.
     */
    @Test
    fun `SL-05 stop con un client connesso chiude il client e termina entro 2 s`() = realTime {
        val sim = simulator()
        val port = sim.start()
        val client = Socket(loopback, port).also { sockets += it }
        awaitTrue({ "connessione non registrata" }) { sim.snapshot().connections == 1 }

        val startedAt = System.nanoTime()
        sim.stop()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(elapsedMs < STOP_BUDGET_MS, "stop() in $elapsedMs ms")
        client.soTimeout = 2_000
        assertEquals(-1, client.getInputStream().read(), "il client vede EOF")
        awaitTrue({ "thread del simulatore ancora vivi: ${simulatorThreads()}" }) { simulatorThreads().isEmpty() }
    }

    /**
     * Il caso dell'app: il simulatore ha chiuso per primo una connessione (stop con un ECR collegato), quindi la sua
     * parte resta in TIME_WAIT sulla porta d'ascolto. Il riavvio sulla stessa porta deve riuscire comunque (§4.3).
     */
    @Test
    fun `SL-07 riavvio sulla stessa porta dopo uno stop con un client collegato`() = realTime {
        val sim = simulator()
        val port = sim.start()
        val client = Socket(loopback, port).also { sockets += it }
        awaitTrue({ "connessione non registrata" }) { sim.snapshot().connections == 1 }
        sim.stop()
        client.soTimeout = 2_000
        assertEquals(-1, client.getInputStream().read(), "il client vede EOF")
        client.close()

        sim.updateConfig { it.copy(port = port) }
        assertEquals(port, sim.start(), "riavvio sulla stessa porta")
        Socket(loopback, port).use { assertTrue(it.isConnected, "di nuovo in ascolto") }
    }

    /** stop()/close() sono chiamati da più punti (controller, shutdown): ripeterli non deve lanciare né bloccare. */
    @Test
    fun `SL-06 stop e close idempotenti`() = realTime {
        val sim = simulator()
        sim.stop()                         // mai avviato
        val port = sim.start()
        assertThrows(IllegalStateException::class.java) { runBlocking { sim.start() } }
        sim.stop()
        sim.stop()
        sim.close()
        sim.close()

        assertNull(sim.boundPort.value, "fermo")
        assertThrows(IllegalStateException::class.java) { runBlocking { sim.start() } }
        ServerSocket(port, 50, loopback).use { assertEquals(port, it.localPort, "porta liberata") }
    }

    /**
     * Prima: un accept() fallito da solo (non per stop()) lasciava il ServerSocket aperto e boundPort valorizzata; il
     * backlog del sistema completava la connect, il pagamento finiva in un socket che nessuno serviva e l'ECR arrivava a
     * Unknown(ResponseTimeout) con i pagamenti bloccati (M6). Ora la porta rifiuta le connessioni (l'ECR ottiene
     * ConnectFailed, cioè NotSent), boundPort torna null con una nota, e stop()/start() ripartono puliti.
     *
     * Il guasto si provoca con un soTimeout di 1 ms impostato a server aperto: la accept() in corso non lo vede, una
     * connessione la sblocca e la successiva scade subito, con il ServerSocket ancora aperto come in un EMFILE.
     */
    @Test
    fun `SL-08 ascolto morto da solo chiude la porta e azzera boundPort`() = realTime {
        val sim = simulator()
        val note = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            sim.events.first { it is SimEvent.Note && it.text.startsWith("Ascolto interrotto") }
        }
        val port = sim.start()
        listenerOf(sim).soTimeout = 1
        Socket(loopback, port).also { sockets += it }

        awaitTrue({ "boundPort ancora ${sim.boundPort.value}" }) { sim.boundPort.value == null }
        note.await()
        assertThrows(IOException::class.java, {
            Socket().use { it.connect(InetSocketAddress(loopback, port), CONNECT_TIMEOUT_MS) }
        }, "la porta non accetta più connessioni (prima il backlog le completava)")

        sim.stop()
        val restarted = sim.start()
        Socket(loopback, restarted).use { assertTrue(it.isConnected, "di nuovo in ascolto dopo stop() e start()") }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000L
        const val AWAIT_MS = 3_000L
        const val POLL_MS = 10L
        /**
         * Il tetto della join è 2 s: con l'ordine giusto la join è immediata, quindi uno stop vicino ai 2 s vuol dire
         * che la join ha aspettato una read bloccante (ordine sbagliato). 1,5 s lascia margine al carico della macchina.
         */
        const val STOP_BUDGET_MS = 1_500L
        /** Margine per i sistemi in cui una connect verso una porta chiusa di loopback è rifiutata solo dopo i ritentativi del SYN. */
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
