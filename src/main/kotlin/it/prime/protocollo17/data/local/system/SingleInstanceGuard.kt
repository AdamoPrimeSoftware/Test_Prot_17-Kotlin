package it.prime.protocollo17.data.local.system

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

// STUB di WP0 (§8.2): firme definitive di §5.7, corpi a carico di WP5.

/** Due istanze condividerebbero journal e terminale: si impedisce con un FileLock su app.lock. */
object SingleInstanceGuard {

    /** Esito del tentativo di lock. Solo [HeldByOther] ferma l'avvio: un lock non ottenibile per altri motivi non deve impedire di usare l'app. */
    sealed interface Attempt {
        class Held(val channel: FileChannel, val lock: FileLock) : Attempt
        data object HeldByOther : Attempt
        class Unavailable(val reason: String?) : Attempt        // lock non ottenibile per altri motivi: si prosegue con warning
    }

    fun acquire(lockFile: File): Attempt = TODO("WP5: SingleInstanceGuard.kt")

    fun release(): Unit = TODO("WP5: SingleInstanceGuard.kt")
}
