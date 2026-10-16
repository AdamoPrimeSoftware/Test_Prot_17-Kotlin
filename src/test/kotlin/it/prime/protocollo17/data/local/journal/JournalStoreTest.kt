package it.prime.protocollo17.data.local.journal

import it.prime.protocollo17.data.repository.JournalRepositoryImpl
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.JournalRecord
import it.prime.protocollo17.domain.model.settings.JournalSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class JournalStoreTest {

    @TempDir
    lateinit var dir: File

    private val day = 24L * 60 * 60 * 1000

    private fun rec(id: String, seq: Int, phase: JournalPhase, at: Long = 1_000L + seq, command: String? = null, needsReview: Boolean? = null) =
        JournalRecord(attemptId = id, seq = seq, at = at, phase = phase, command = command, needsReview = needsReview)

    @Test
    fun `append e rilettura`(): Unit = runBlocking {
        val store = JournalStore(dir)
        val records = listOf(
            rec("a", 0, JournalPhase.CREATED, command = "P").copy(amountCents = 10, requestSummary = "Pagamento 0,10 €"),
            rec("a", 1, JournalPhase.SENDING).copy(sends = 1),
            rec("a", 2, JournalPhase.RESULT).copy(rawResult = "025757330E00000558278******4768CLI", stan = "000004"),
        )
        records.forEach { store.append(it) }
        store.close()

        assertEquals(records, JournalStore(dir).readAll(), "rilettura identica")
        assertEquals(File(dir, "journal.jsonl"), store.file, "file")
        assertEquals(store.file.length(), store.sizeBytes(), "sizeBytes")
        val text = store.file.readText()
        assertFalse(text.contains("null"), "i campi null non si scrivono: $text")
        assertEquals(3, text.lines().count { it.isNotBlank() }, "una riga per record")
    }

    /** Un crash durante l'append lascia l'ultima riga troncata: va saltata, conservata, e non deve rovinare la successiva. */
    @Test
    fun `ultima riga troncata saltata e salvata in corrupt`(): Unit = runBlocking {
        val store = JournalStore(dir)
        store.append(rec("a", 0, JournalPhase.CREATED, command = "P"))
        store.close()
        store.file.appendText("{\"attemptId\":\"a\",\"seq\":1,\"at\":5,\"pha")

        val reopened = JournalStore(dir)
        assertEquals(listOf(rec("a", 0, JournalPhase.CREATED, command = "P")), reopened.readAll(), "solo il record valido")
        val corrupt = dir.listFiles { f -> f.name.startsWith("journal.jsonl.corrupt-") }.orEmpty()
        assertEquals(1, corrupt.size, "un file corrupt")
        assertTrue(corrupt.single().readText().contains("\"pha"), "riga troncata conservata")

        // L'append successivo parte su una riga nuova: il record non si incolla alla riga troncata.
        reopened.append(rec("a", 2, JournalPhase.UNKNOWN))
        reopened.close()
        val again = JournalStore(dir).readAll()
        assertEquals(listOf(JournalPhase.CREATED, JournalPhase.UNKNOWN), again.map { it.phase }, "record nuovo leggibile")
    }

    /**
     * Una riga troncata resta nel journal finché una compattazione non lo riscrive. Prima ogni avvio (readAll) creava un
     * nuovo `.corrupt-*` con le stesse righe; ora una riga già conservata non si duplica, e una nuova si conserva da sola.
     */
    @Test
    fun `righe illeggibili gia conservate non si duplicano`(): Unit = runBlocking {
        val store = JournalStore(dir)
        store.append(rec("a", 0, JournalPhase.CREATED, command = "P"))
        store.close()
        val first = "{\"attemptId\":\"a\",\"seq\":1,\"at\":5,\"pha"
        store.file.appendText(first)

        repeat(3) { JournalStore(dir).also { it.readAll() }.close() }
        val corrupt = { dir.listFiles { f -> f.name.startsWith("journal.jsonl.corrupt-") }.orEmpty().toList() }
        assertEquals(1, corrupt().size, "un solo file corrupt dopo tre avvii: ${corrupt().map { it.name }}")

        val second = "{\"attemptId\":\"b\",\"seq\""
        store.file.appendText("\n$second")
        JournalStore(dir).also { it.readAll() }.close()
        val lines = corrupt().flatMap { it.readLines() }.filter { it.isNotBlank() }
        assertEquals(listOf(first, second).sorted(), lines.sorted(), "ogni riga illeggibile conservata una volta")
    }

    /** M6: una riga CREATED rovinata non deve far sparire i record SENDING/UNKNOWN dello stesso tentativo. */
    @Test
    fun `riga CREATED corrotta con SENDING e UNKNOWN integri`(): Unit = runBlocking {
        val store = JournalStore(dir)
        store.append(rec("a", 0, JournalPhase.CREATED, command = "P"))
        store.append(rec("a", 1, JournalPhase.SENDING).copy(sends = 1))
        store.append(rec("a", 2, JournalPhase.UNKNOWN).copy(failure = "ECR17: no application response"))
        store.close()
        val lines = store.file.readLines().filter { it.isNotBlank() }.toMutableList()
        lines[0] = lines[0].substring(0, 20) + "#rovinata#"
        store.file.writeText(lines.joinToString("\n", postfix = "\n"))

        val read = JournalStore(dir).readAll()

        assertEquals(listOf(JournalPhase.SENDING, JournalPhase.UNKNOWN), read.map { it.phase }, "record validi letti")
        assertTrue(read.all { it.attemptId == "a" }, "stesso tentativo")
    }

    @Test
    fun `rewrite sostituisce atomicamente e crea il bak`(): Unit = runBlocking {
        val store = JournalStore(dir)
        val all = (0 until 5).map { rec("a$it", 0, JournalPhase.CREATED, command = "P") }
        all.forEach { store.append(it) }
        val before = store.file.readText()

        store.rewrite(all.take(2))
        // Dopo un rewrite gli append continuano sul file nuovo.
        store.append(rec("z", 0, JournalPhase.CREATED, command = "s"))
        store.close()

        assertEquals(all.take(2).map { it.attemptId } + "z", JournalStore(dir).readAll().map { it.attemptId }, "contenuto nuovo")
        assertEquals(before, File(dir, "journal.jsonl.bak").readText(), "bak = file precedente")
        assertFalse(File(dir, "journal.jsonl.part").exists(), "nessun .part residuo")
    }

    @Test
    fun `append dopo close lancia IOException`() {
        val store = JournalStore(dir)
        store.close()
        assertThrows(IOException::class.java) { runBlocking { store.append(rec("a", 0, JournalPhase.CREATED)) } }
    }

    @Test
    fun `cartella non scrivibile fa lanciare append`() {
        // Un FILE al posto della cartella: mkdirs e l'apertura falliscono, come un disco non scrivibile.
        val notADir = File(dir, "occupato").apply { writeText("x") }
        val store = JournalStore(notADir)
        assertThrows(IOException::class.java) { runBlocking { store.append(rec("a", 0, JournalPhase.CREATED)) } }
    }

    @Test
    fun `file assente letto come vuoto`(): Unit = runBlocking {
        assertEquals(emptyList<JournalRecord>(), JournalStore(dir).readAll(), "vuoto")
    }

    /**
     * Retention del journal (skill data-layer): la compattazione all'avvio elimina solo i tentativi chiusi e vecchi,
     * mai un UNKNOWN, un esito da verificare o un tentativo recente.
     */
    @Test
    fun `compattazione conserva UNKNOWN, da verificare e recenti`(): Unit = runBlocking {
        val now = 1_000L * day
        val old = now - 200 * day
        val store = JournalStore(dir)
        // Chiuso e vecchio: va eliminato.
        store.append(rec("oldClosed", 0, JournalPhase.CREATED, at = old, command = "P"))
        store.append(rec("oldClosed", 1, JournalPhase.RESULT, at = old))
        // UNKNOWN vecchio: resta.
        store.append(rec("oldUnknown", 0, JournalPhase.CREATED, at = old, command = "P"))
        store.append(rec("oldUnknown", 1, JournalPhase.UNKNOWN, at = old))
        // Da verificare vecchio: resta.
        store.append(rec("oldReview", 0, JournalPhase.CREATED, at = old, command = "P"))
        store.append(rec("oldReview", 1, JournalPhase.RESULT, at = old, needsReview = true))
        // Chiuso recente: resta.
        store.append(rec("recent", 0, JournalPhase.CREATED, at = now - day, command = "P"))
        store.append(rec("recent", 1, JournalPhase.NOT_SENT, at = now - day))
        store.close()

        val repo = JournalRepositoryImpl(
            store = JournalStore(dir),
            settingsProvider = { JournalSettings(compactAboveKb = 0, keepClosedDays = 180) },
            wallClock = { now },
        )
        repo.recoverOnStartup()
        repo.close()

        val kept = JournalStore(dir).readAll().map { it.attemptId }.distinct()
        assertEquals(listOf("oldUnknown", "oldReview", "recent"), kept, "tentativi conservati")
        assertTrue(File(dir, "journal.jsonl.bak").readText().contains("oldClosed"), "bak con il file precedente")
        assertFalse(File(dir, "journal.jsonl.part").exists(), "nessun .part residuo")
    }
}
