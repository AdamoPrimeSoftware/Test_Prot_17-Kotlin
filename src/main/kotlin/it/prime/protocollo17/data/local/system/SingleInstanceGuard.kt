package it.prime.protocollo17.data.local.system

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/**
 * Due istanze condividerebbero journal e terminale: si impedisce con un FileLock su app.lock.
 *
 * Il lock è del sistema operativo e muore con il processo: un'app terminata male non lascia un file "occupato"
 * che blocca l'avvio successivo (al contrario di un file marcatore creato e cancellato).
 */
object SingleInstanceGuard {

    private val log = LoggerFactory.getLogger(SingleInstanceGuard::class.java)

    /** Esito del tentativo di lock. Solo [HeldByOther] ferma l'avvio: un lock non ottenibile per altri motivi non deve impedire di usare l'app. */
    sealed interface Attempt {
        class Held(val channel: FileChannel, val lock: FileLock) : Attempt
        data object HeldByOther : Attempt
        class Unavailable(val reason: String?) : Attempt        // lock non ottenibile per altri motivi: si prosegue con warning
    }

    private val held = mutableListOf<Attempt.Held>()

    @Synchronized
    fun acquire(lockFile: File): Attempt {
        val channel = try {
            lockFile.absoluteFile.parentFile?.mkdirs()
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: IOException) {
            log.warn("Lock di istanza singola non disponibile ({}): si prosegue", e.message)
            return Attempt.Unavailable(e.message)
        }
        return try {
            val lock = channel.tryLock()
            if (lock == null) {
                channel.close()
                Attempt.HeldByOther
            } else {
                Attempt.Held(channel, lock).also { held += it }
            }
        } catch (e: OverlappingFileLockException) {
            // Lo stesso lock è già tenuto da questo processo: per l'avvio è comunque "un'altra istanza".
            channel.close()
            Attempt.HeldByOther
        } catch (e: IOException) {
            channel.close()
            log.warn("Lock di istanza singola non ottenibile ({}): si prosegue", e.message)
            Attempt.Unavailable(e.message)
        }
    }

    @Synchronized
    fun release() {
        for (attempt in held) {
            try {
                attempt.lock.release()
            } catch (e: IOException) {
                log.warn("Rilascio del lock di istanza singola non riuscito: {}", e.message)
            }
            try {
                attempt.channel.close()
            } catch (e: IOException) {
                log.warn("Chiusura del file di lock non riuscita: {}", e.message)
            }
        }
        held.clear()
    }
}
