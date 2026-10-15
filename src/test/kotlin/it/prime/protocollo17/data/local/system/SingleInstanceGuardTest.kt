package it.prime.protocollo17.data.local.system

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SingleInstanceGuardTest {

    @TempDir
    lateinit var dir: File

    @AfterEach
    fun tearDown() {
        SingleInstanceGuard.release()
    }

    /** Due istanze condividerebbero journal e terminale: la seconda deve fermarsi. */
    @Test
    fun `secondo acquire sullo stesso file restituisce HeldByOther`() {
        val lock = File(dir, "app.lock")

        val first = SingleInstanceGuard.acquire(lock)
        val second = SingleInstanceGuard.acquire(lock)

        assertTrue(first is SingleInstanceGuard.Attempt.Held, "primo: $first")
        assertEquals(SingleInstanceGuard.Attempt.HeldByOther, second, "secondo")
    }

    @Test
    fun `dopo release il lock si riottiene`() {
        val lock = File(dir, "sub/app.lock")

        assertTrue(SingleInstanceGuard.acquire(lock) is SingleInstanceGuard.Attempt.Held, "primo")
        SingleInstanceGuard.release()

        assertTrue(SingleInstanceGuard.acquire(lock) is SingleInstanceGuard.Attempt.Held, "di nuovo dopo release")
    }

    /** Un lock non ottenibile per altri motivi non deve impedire di usare l'app: Unavailable, non HeldByOther. */
    @Test
    fun `cartella non utilizzabile restituisce Unavailable`() {
        val notADir = File(dir, "file").apply { writeText("x") }

        val attempt = SingleInstanceGuard.acquire(File(notADir, "app.lock"))

        assertTrue(attempt is SingleInstanceGuard.Attempt.Unavailable, "Unavailable: $attempt")
    }
}
