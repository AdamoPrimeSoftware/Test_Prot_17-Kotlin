package it.prime.protocollo17.data.local.traffic

import it.prime.protocollo17.domain.model.settings.TrafficLogSettings
import it.prime.protocollo17.domain.model.traffic.TrafficEntry
import it.prime.protocollo17.domain.model.traffic.TrafficKind
import it.prime.protocollo17.domain.model.traffic.TrafficSeverity
import it.prime.protocollo17.ecr17.event.FrameDirection
import it.prime.protocollo17.ecr17.event.FrameKind
import it.prime.protocollo17.ecr17.event.ProtocolEvent
import it.prime.protocollo17.ecr17.message.ResponseParser
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.profile.TerminalProfileId
import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.ecr17.wire.DiscardReason
import it.prime.protocollo17.ecr17.wire.PanMasker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TrafficRecorderTest {

    @TempDir
    lateinit var dir: File

    private val zone = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.of(2026, 10, 14)
    private val noon = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private var settings = TrafficLogSettings()
    private var seq = 0L

    private fun recorder() = TrafficRecorder(dir, settingsProvider = { settings }, wallClock = { noon })

    private fun progress(text: String, at: Long = noon) = ProtocolEvent.Progress(++seq, at, 1, text)

    private fun fileName(date: LocalDate) = TrafficFormatter.fileNameFor(date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), zone)

    /** Attesa di una condizione su thread reali (il consumatore gira su Dispatchers.IO), mai uno sleep fisso. */
    private fun awaitTrue(message: String, timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            check(System.currentTimeMillis() < deadline) { "timeout: $message" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `file del giorno creato e scritto`() {
        val rec = recorder()
        rec.onEvent(ProtocolEvent.CommandStarted(++seq, noon, 1, CommandCode.STATUS, Endpoint("127.0.0.1", 5577), TerminalProfileId.INGENICO, "opts"))
        rec.onEvent(progress("OPERAZIONE IN CORSO "))
        rec.close()

        val file = File(dir, fileName(today))
        assertEquals(file, rec.currentFile.value, "currentFile")
        val text = file.readText()
        assertTrue(text.contains("#1 s avviato verso 127.0.0.1:5577 (INGENICO opts)"), "riga di avvio: $text")
        assertTrue(text.contains("#1 progress \"OPERAZIONE IN CORSO \""), "riga di progress: $text")
        assertEquals(dir, rec.directory, "directory")
    }

    @Test
    fun `ring limitato a memoryEntries`() {
        settings = TrafficLogSettings(memoryEntries = 3)
        val rec = recorder()
        for (i in 1..10) rec.onEvent(progress("riga $i"))

        awaitTrue("ultime 3 voci pubblicate") { rec.entries.value.lastOrNull()?.summary == "progress \"riga 10\"" }
        assertEquals(listOf("riga 8", "riga 9", "riga 10").map { "progress \"$it\"" }, rec.entries.value.map { it.summary }, "dal più vecchio")
        assertTrue(rec.entries.value.all { it.command == null && it.kind == TrafficKind.PROGRESS }, "voci di progress")

        rec.clearMemory()
        assertTrue(rec.entries.value.isEmpty(), "clearMemory")
        rec.close()
    }

    @Test
    fun `file disattivato non scrive e currentFile null`() {
        settings = TrafficLogSettings(fileEnabled = false)
        val rec = recorder()
        rec.onEvent(progress("x"))
        rec.close()

        assertEquals(null, rec.currentFile.value, "currentFile")
        assertFalse(File(dir, fileName(today)).exists(), "nessun file")
    }

    @Test
    fun `rotazione al cambio di data dell'evento`() {
        val rec = recorder()
        rec.onEvent(progress("ieri", at = noon - 24L * 60 * 60 * 1000))
        rec.onEvent(progress("oggi", at = noon))
        rec.close()

        assertTrue(File(dir, fileName(today.minusDays(1))).readText().contains("ieri"), "file di ieri")
        assertTrue(File(dir, fileName(today)).readText().contains("oggi"), "file di oggi")
        assertFalse(File(dir, fileName(today)).readText().contains("ieri"), "nessuna riga di ieri nel file di oggi")
    }

    private fun make(date: LocalDate, bytes: Int) = File(dir, fileName(date)).apply { writeBytes(ByteArray(bytes)) }

    /** Retention (skill data-layer): un'app accesa per anni non deve riempire il disco, ma il file di oggi resta sempre. */
    @Test
    fun `retention cancella i file oltre i giorni, mai quello di oggi ne altri file`() {
        settings = TrafficLogSettings(retentionDays = 30, maxTotalMb = 200)
        val todayFile = make(today, 10)
        val tooOld = make(today.minusDays(31), 10)
        val keptByDays = make(today.minusDays(30), 10)
        val unrelated = File(dir, "note.txt").apply { writeText("non è un log") }

        val rec = recorder()
        awaitTrue("retention eseguita") { !tooOld.exists() }
        rec.close()

        assertTrue(todayFile.exists(), "mai il file di oggi")
        assertTrue(keptByDays.exists(), "30 giorni esatti: resta")
        assertTrue(unrelated.exists(), "mai file che non sono log traffico")
    }

    @Test
    fun `retention cancella i piu vecchi finche il totale supera i MB, mai quello di oggi`() {
        settings = TrafficLogSettings(retentionDays = 365, maxTotalMb = 1)
        val todayFile = make(today, 700 * 1024)
        val older = make(today.minusDays(5), 600 * 1024)
        val recent = make(today.minusDays(1), 200 * 1024)

        val rec = recorder()
        awaitTrue("retention eseguita") { !older.exists() }
        rec.close()

        assertTrue(todayFile.exists(), "mai il file di oggi")
        assertTrue(recent.exists(), "sotto il tetto dopo aver tolto il più vecchio")
    }

    @Test
    fun `retentionVictims non elimina il file protetto anche oltre il tetto`() {
        val big = make(today, 2048)
        val yesterday = make(today.minusDays(1), 10)
        val victims = TrafficRecorder.retentionVictims(
            listOf(big, yesterday), today, retentionDays = 30, maxTotalBytes = 10, protectedNames = setOf(big.name),
        )
        assertEquals(listOf(yesterday), victims, "solo il file non protetto")
    }

    @Test
    fun `export scrive le voci nel formato file con scrittura atomica`(): Unit = runBlocking {
        val rec = recorder()
        val entries = listOf(
            TrafficEntry(1, noon, 12, TrafficKind.TX, 'P', "02 30 03 2F", null, "Frame 'P'", TrafficSeverity.NORMAL),
            TrafficEntry(2, noon, 12, TrafficKind.WARNING, 'P', null, null, "IMPLICIT_GO_AHEAD attendo", TrafficSeverity.WARNING),
        )
        val target = File(dir, "export/traccia.txt")

        val result = rec.export(target, entries)
        rec.close()

        assertEquals(2, result.getOrNull(), "righe esportate")
        val lines = target.readLines()
        assertTrue(lines[0].endsWith(": Data Tx:02 30 03 2f "), "frame in forma DLL: ${lines[0]}")
        assertTrue(lines[1].endsWith(": Warn   :#12 IMPLICIT_GO_AHEAD attendo"), "avviso: ${lines[1]}")
        assertFalse(File(dir, "export/traccia.txt.part").exists(), "nessun .part residuo")
    }

    /**
     * Prima: con un arretrato oltre l'attesa di 1 s di close(), il consumatore continuava (un canale chiuso ma non
     * vuoto non sospende e non vede la cancellazione) e la write successiva riapriva il file DOPO close(): un handle
     * mai chiuso, con il file del giorno bloccato su Windows. Qui il consumatore resta fermo nella retention iniziale
     * (settingsProvider bloccato fuori dal thread del test) finché close() non è tornata.
     */
    @Test
    fun `close con arretrato oltre l'attesa non riapre mai il file`() {
        val testThread = Thread.currentThread()
        val release = CountDownLatch(1)
        val rec = TrafficRecorder(
            dir,
            settingsProvider = {
                if (Thread.currentThread() !== testThread) release.await(10, TimeUnit.SECONDS)
                settings
            },
            wallClock = { noon },
        )
        repeat(3) { rec.onEvent(progress("arretrato $it")) }

        rec.close() // attesa massima di 1 s scaduta: il consumatore è ancora fermo
        release.countDown()
        runBlocking { withTimeout(5_000) { rec.consumer.join() } }

        assertFalse(File(dir, fileName(today)).exists(), "nessun file riaperto dopo close()")
    }

    @Test
    fun `close idempotente e onEvent dopo close non lancia`() {
        val rec = recorder()
        rec.close()
        rec.close()
        rec.onEvent(progress("dopo la chiusura"))
    }

    /**
     * M9: nessun PAN in chiaro nel file, né nei frame, né negli scarti, né nelle righe S. Gli eventi sono costruiti
     * come li costruisce la sessione (maskFrame, maskDigitRuns, parser con maskPan).
     */
    @Test
    fun `nessun PAN in chiaro nel file`() {
        val rec = recorder()
        val clearFrame = Frames.app(Frames.E22_CLEAR)
        rec.onEvent(ProtocolEvent.Wire(++seq, noon, 1, FrameDirection.RX, FrameKind.APPLICATION, 'E', PanMasker.maskFrame(clearFrame), null, "LRC ok"))
        val fragment = Frames.E22_CLEAR.substring(5, 40).toByteArray(Charsets.ISO_8859_1)
        rec.onEvent(ProtocolEvent.Discarded(++seq, noon, 1, PanMasker.maskDigitRuns(fragment), DiscardReason.NOISE))
        // Frammento di riga S con il PAN spezzato dalla doppia altezza (0x7F): mascherato anche così.
        val splitFragment = "SPAN 41111111\u007F11111111".toByteArray(Charsets.ISO_8859_1)
        rec.onEvent(ProtocolEvent.Discarded(++seq, noon, 1, PanMasker.maskDigitRuns(splitFragment), DiscardReason.RESYNC_ON_NEW_START))
        val receiptPayload = Frames.RECEIPT_PAN.toByteArray(Charsets.ISO_8859_1)
        rec.onEvent(ProtocolEvent.Receipt(++seq, noon, 1, ResponseParser.parseReceiptLine(receiptPayload, maskPan = true)))
        rec.close()

        val text = File(dir, fileName(today)).readText()
        assertFalse(text.contains("4111111111111111"), "PAN in chiaro nel file:\n$text")
        val clearHex = "4111111111111111".toByteArray().joinToString(" ") { "%02x".format(it) }
        assertFalse(text.contains(clearHex), "PAN in chiaro come hex nel file")
        val halfHex = "41111111".toByteArray().joinToString(" ") { "%02x".format(it) }
        assertFalse(text.contains(halfHex), "metà del PAN spezzato da 0x7F in chiaro come hex nel file:\n$text")
    }
}
