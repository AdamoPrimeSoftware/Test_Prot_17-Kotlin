package it.prime.protocollo17.data.local.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ShutdownWatchdogTest {

    /** Main chiama arm() due volte (prima di uscire dalla UI e nel finally): deve partire un solo arresto forzato. */
    @Test
    fun `arm chiama halt una sola volta dopo graceMs anche se armato due volte`() {
        val calls = AtomicInteger(0)
        val halted = CountDownLatch(1)
        val watchdog = ShutdownWatchdog(graceMs = 50) {
            calls.incrementAndGet()
            halted.countDown()
        }

        watchdog.arm()
        watchdog.arm()

        assertTrue(halted.await(5, TimeUnit.SECONDS), "halt chiamato")
        // Un secondo conto alla rovescia, se partito, chiamerebbe halt entro altri 50 ms.
        Thread.sleep(200)
        assertEquals(1, calls.get(), "un solo halt")
    }

    @Test
    fun `senza arm halt non viene mai chiamato`() {
        val calls = AtomicInteger(0)
        ShutdownWatchdog(graceMs = 10) { calls.incrementAndGet() }
        Thread.sleep(100)
        assertEquals(0, calls.get(), "nessun halt")
    }
}
