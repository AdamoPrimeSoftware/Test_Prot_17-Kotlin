package it.prime.protocollo17.data.local.system

// STUB di WP0 (§8.2): firme definitive di §5.7, corpi a carico di WP5.

/**
 * Thread daemon: dopo graceMs dalla richiesta di chiusura forza Runtime.halt(0) (stile LocalDesktopAi). Solo Main.kt lo vede (§2.10).
 *
 * Un'app che non si chiude (un thread non daemon appeso, un socket bloccato) è peggio di una che perde qualcosa
 * alla chiusura: il journal è già scritto con fsync prima di arrivare qui.
 */
class ShutdownWatchdog(private val graceMs: Long = 10_000, private val halt: () -> Unit = { Runtime.getRuntime().halt(0) }) {
    fun arm(): Unit = TODO("WP5: ShutdownWatchdog.kt")
}
