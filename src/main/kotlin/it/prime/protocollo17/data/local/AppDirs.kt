package it.prime.protocollo17.data.local

import java.io.File

/**
 * Cartelle dell'app. Stessa radice di logback.xml (§2.7).
 *
 * Una sola radice fissa (`~/.protocollo17`), senza varianti per variabile d'ambiente: quando si chiede un log o
 * il journal all'operatore c'è un solo posto da indicare. La radice è un parametro solo per i test (@TempDir).
 */
class AppDirs(val root: File) {
    val settingsFile: File get() = File(root, "settings.json")
    val journalDir: File get() = File(root, "journal")
    val trafficDir: File get() = File(root, "traffic")
    val logsDir: File get() = File(root, "logs")
    val lockFile: File get() = File(root, "app.lock")

    /** Crea le cartelle mancanti; restituisce this. */
    fun ensure(): AppDirs {
        // mkdirs() restituisce false anche se la cartella esiste già: l'errore vero si scopre al primo uso
        // (scrittura di settings o journal), dove viene gestito e mostrato.
        listOf(root, journalDir, trafficDir, logsDir).forEach { it.mkdirs() }
        return this
    }

    companion object {
        /** File(System.getProperty("user.home"), ".protocollo17"). */
        fun default(): AppDirs = AppDirs(File(System.getProperty("user.home"), ".protocollo17"))
    }
}
