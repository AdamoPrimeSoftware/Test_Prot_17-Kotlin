package it.prime.protocollo17.data.local.system

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread daemon: dopo graceMs dalla richiesta di chiusura forza Runtime.halt(0) (stile LocalDesktopAi). Solo Main.kt lo vede (§2.10).
 *
 * Un'app che non si chiude (un thread non daemon appeso, un socket bloccato) è peggio di una che perde qualcosa
 * alla chiusura: il journal è già scritto con fsync prima di arrivare qui.
 *
 * [arm] è idempotente: Main lo chiama sia prima di uscire dalla UI sia nel `finally`, e un solo conto alla
 * rovescia deve partire (il primo).
 */
class ShutdownWatchdog(private val graceMs: Long = 10_000, private val halt: () -> Unit = { Runtime.getRuntime().halt(0) }) {

    private val log = LoggerFactory.getLogger(ShutdownWatchdog::class.java)
    private val armed = AtomicBoolean(false)

    fun arm() {
        if (!armed.compareAndSet(false, true)) return
        val thread = Thread({
            try {
                Thread.sleep(graceMs)
            } catch (e: InterruptedException) {
                return@Thread
            }
            log.warn("Chiusura non completata entro {} ms: arresto forzato della JVM", graceMs)
            halt()
        }, "shutdown-watchdog")
        thread.isDaemon = true
        thread.start()
    }
}
