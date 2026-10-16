package it.prime.protocollo17.data.local.simulator

import it.prime.protocollo17.data.local.settings.SettingsRepository
import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.model.simulator.SimulatorFailure
import it.prime.protocollo17.domain.model.simulator.SimulatorStatus
import it.prime.protocollo17.domain.model.simulator.runningPort
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import it.prime.protocollo17.ecr17.sim.TerminalSimulator
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Ciclo di vita del simulatore visto dall'app (skill embedded-server §8), con socket reali su loopback e porte
 * effimere (mai porte fisse: più build girano in parallelo).
 */
class SimulatorControllerImplTest {

    @TempDir
    lateinit var dir: File

    private val toClose = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        toClose.asReversed().forEach { runCatching { it.close() } }
    }

    private fun settings(transform: (AppSettings) -> AppSettings = { it }): SettingsRepository =
        SettingsRepository(File(dir, "settings.json")).also { repo ->
            repo.update(transform)
            toClose += repo
        }

    private fun controller(settings: SettingsRepository) =
        SimulatorControllerImpl(settings).also { c -> toClose += AutoCloseable { c.close() } }

    /** Porta effimera libera su loopback: si apre e si chiude un ServerSocket sulla porta 0. */
    private fun freePort(): Int = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun awaitStatus(controller: SimulatorControllerImpl, timeoutMs: Long = 5_000, predicate: (SimulatorStatus) -> Boolean): SimulatorStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate(controller.status.value)) {
            check(System.currentTimeMillis() < deadline) { "timeout: stato ${controller.status.value}" }
            Thread.sleep(10)
        }
        return controller.status.value
    }

    private fun awaitTrue(message: () -> String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timeout: ${message()}" }
            Thread.sleep(10)
        }
    }

    /** ServerSocket dell'engine dentro il controller (come listenerOf di TerminalSimulatorLifecycleTest). */
    private fun listenerOf(controller: SimulatorControllerImpl): ServerSocket {
        val sim = SimulatorControllerImpl::class.java.getDeclaredField("simulator").apply { isAccessible = true }.get(controller)
        val running = TerminalSimulator::class.java.getDeclaredField("running").apply { isAccessible = true }.get(sim)
            ?: error("simulatore non avviato")
        return running.javaClass.getDeclaredField("server").apply { isAccessible = true }.get(running) as ServerSocket
    }

    private fun canBind(port: Int): Boolean = try {
        ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")).close()
        true
    } catch (e: java.io.IOException) {
        false
    }

    @Test
    fun `autoStart porta il simulatore in Running`() {
        val repo = settings { it.copy(simulator = it.simulator.copy(port = 0, autoStart = true)) }
        val controller = controller(repo)

        val status = awaitStatus(controller) { it is SimulatorStatus.Running }

        assertTrue((status.runningPort ?: 0) > 0, "porta effettiva: $status")
    }

    /** Una porta occupata è uno stato da mostrare all'utente, mai un'eccezione verso il chiamante. */
    @Test
    fun `porta occupata produce Failed PortInUse senza eccezioni`(): Unit = runBlocking {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { busy ->
            val repo = settings { it.copy(simulator = it.simulator.copy(port = busy.localPort)) }
            val controller = controller(repo)

            val status = controller.start()

            assertEquals(SimulatorStatus.Failed(SimulatorFailure.PortInUse(busy.localPort)), status, "stato restituito")
            assertEquals(status, controller.status.value, "stato pubblicato")
        }
    }

    @Test
    fun `cambio di porta mentre e attivo riavvia sulla nuova porta`(): Unit = runBlocking {
        val repo = settings { it.copy(simulator = it.simulator.copy(port = freePort())) }
        val controller = controller(repo)
        val first = controller.start()
        assertTrue(first is SimulatorStatus.Running, "avviato: $first")

        val newPort = freePort()
        repo.update { it.copy(simulator = it.simulator.copy(port = newPort)) }

        awaitStatus(controller) { it == SimulatorStatus.Running(newPort) }
        assertTrue(canBind(first.runningPort!!), "la porta vecchia è stata liberata")
    }

    @Test
    fun `useAsTarget e restoreTarget salvano e ripristinano host e porta`(): Unit = runBlocking {
        val repo = settings {
            it.copy(
                terminal = it.terminal.copy(host = "192.168.1.131", port = 5577),
                simulator = it.simulator.copy(port = 0),
            )
        }
        val controller = controller(repo)
        assertFalse(controller.useAsTarget(), "non in esecuzione: false")
        assertFalse(controller.restoreTarget(), "nessuna destinazione salvata: false")

        val port = controller.start().runningPort!!
        assertTrue(controller.useAsTarget(), "in esecuzione: true")

        val target = repo.getSnapshot()
        assertEquals("127.0.0.1", target.terminal.host, "host del simulatore")
        assertEquals(port, target.terminal.port, "porta del simulatore")
        assertEquals("192.168.1.131", target.simulator.previousHost, "host salvato")
        assertEquals(5577, target.simulator.previousPort, "porta salvata")
        val deadline = System.currentTimeMillis() + 5_000
        while (!controller.isTarget.value) {
            check(System.currentTimeMillis() < deadline) { "isTarget mai vero" }
            Thread.sleep(10)
        }

        // Una seconda chiamata non sovrascrive la destinazione salvata con 127.0.0.1.
        assertTrue(controller.useAsTarget(), "di nuovo")
        assertEquals("192.168.1.131", repo.getSnapshot().simulator.previousHost, "destinazione vera conservata")

        assertTrue(controller.restoreTarget(), "ripristino")
        val restored = repo.getSnapshot()
        assertEquals("192.168.1.131", restored.terminal.host, "host ripristinato")
        assertEquals(5577, restored.terminal.port, "porta ripristinata")
        assertEquals(null, restored.simulator.previousHost, "destinazione salvata consumata")
    }

    @Test
    fun `close idempotente e porta liberata`(): Unit = runBlocking {
        val repo = settings { it.copy(simulator = it.simulator.copy(port = 0)) }
        val controller = controller(repo)
        val port = controller.start().runningPort!!

        controller.close()
        controller.close()

        assertEquals(SimulatorStatus.Stopped, controller.status.value, "fermo")
        assertTrue(canBind(port), "porta liberata")
    }

    /**
     * Prima si osservavano solo gli eventi: se l'ascolto moriva da solo (TerminalSimulator.listenerDied azzera
     * boundPort) lo stato restava Running e isTarget vero, e l'operatore credeva raggiungibile un simulatore che
     * rifiutava ogni connessione. Ora lo stato passa a Failed(ListenerDied), isTarget torna falso e un nuovo start()
     * riparte pulito. Il guasto si provoca come in SL-08: soTimeout di 1 ms sul ServerSocket già in ascolto, poi una
     * connessione che sblocca la accept() in corso; la successiva scade subito, fuori da stop().
     */
    @Test
    fun `ascolto morto da solo porta a Failed e isTarget falso`(): Unit = runBlocking {
        val repo = settings { it.copy(simulator = it.simulator.copy(port = 0)) }
        val controller = controller(repo)
        val port = controller.start().runningPort!!
        assertTrue(controller.useAsTarget(), "destinazione del terminale")
        awaitTrue({ "isTarget mai vero" }) { controller.isTarget.value }

        listenerOf(controller).soTimeout = 1
        toClose += Socket(InetAddress.getByName("127.0.0.1"), port)

        val failed = awaitStatus(controller) { it is SimulatorStatus.Failed }
        assertEquals(SimulatorStatus.Failed(SimulatorFailure.ListenerDied(port)), failed, "stato dopo la morte dell'ascolto")
        awaitTrue({ "isTarget ancora vero" }) { !controller.isTarget.value }
        awaitTrue({ "nota mancante nel log: ${controller.log.value.map { it.text }}" }) {
            controller.log.value.any { it.text.startsWith("Ascolto interrotto") }
        }

        val restarted = controller.start()
        assertTrue(restarted is SimulatorStatus.Running, "riavvio dopo la morte dell'ascolto: $restarted")
    }

    @Test
    fun `stop e start sulla stessa porta`(): Unit = runBlocking {
        val port = freePort()
        val repo = settings { it.copy(simulator = it.simulator.copy(port = port)) }
        val controller = controller(repo)

        assertEquals(SimulatorStatus.Running(port), controller.start(), "primo avvio")
        assertEquals(SimulatorStatus.Running(port), controller.start(), "idempotente se già avviato")
        controller.stop()
        assertEquals(SimulatorStatus.Stopped, controller.status.value, "fermo")
        assertEquals(SimulatorStatus.Running(port), controller.start(), "riavvio sulla stessa porta")
    }
}
