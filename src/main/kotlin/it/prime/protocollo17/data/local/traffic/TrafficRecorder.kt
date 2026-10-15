package it.prime.protocollo17.data.local.traffic

import it.prime.protocollo17.domain.model.settings.TrafficLogSettings
import it.prime.protocollo17.domain.model.traffic.TrafficEntry
import it.prime.protocollo17.domain.repository.TrafficRepository
import it.prime.protocollo17.ecr17.event.ProtocolEvent
import it.prime.protocollo17.ecr17.event.ProtocolEventSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sink degli eventi ECR17: memoria per la UI + file giornaliero. onEvent non blocca mai (contratto di
 * ProtocolEventSink, §3.19): la sessione lo chiama in linea durante lo scambio, e un disco lento non deve mai
 * ritardare un ACK.
 *
 * Per questo onEvent è un solo `trySend` su un canale illimitato; un consumatore su Dispatchers.IO formatta
 * (TrafficFormatter), aggiorna il ring buffer e scrive il file con un flush per lotto. La finestra Log riceve
 * una copia del ring al massimo 10 volte al secondo: a ogni byte una lista nuova farebbe ricomporre la UI per
 * nulla durante un drain rumoroso.
 *
 * Il file ruota al cambio di data dell'evento; la retention (giorni, poi MB totali, mai il file di oggi) gira
 * all'avvio e a ogni rotazione (skill data-layer: niente cresce senza un tetto).
 */
class TrafficRecorder(
    private val dir: File,
    private val settingsProvider: () -> TrafficLogSettings,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ProtocolEventSink, TrafficRepository, AutoCloseable {

    private val log = LoggerFactory.getLogger(TrafficRecorder::class.java)
    private val zone: ZoneId = ZoneId.systemDefault()

    private val events = Channel<ProtocolEvent>(Channel.UNLIMITED)
    private val publishSignal = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("traffic-recorder"))
    private val closed = AtomicBoolean(false)

    // ─── Memoria (consumatore + clearMemory dalla UI) ──────────────────────────

    private val ringLock = Any()
    private val ring = ArrayDeque<TrafficEntry>()
    private val _entries = MutableStateFlow<List<TrafficEntry>>(emptyList())
    override val entries: StateFlow<List<TrafficEntry>> = _entries.asStateFlow()

    // ─── File (solo consumatore; close() dopo il join, sotto writerLock) ───────

    private val writerLock = Any()
    private var writer: BufferedWriter? = null
    private var writerDate: LocalDate? = null
    private var fileErrorLogged = false

    private val _currentFile = MutableStateFlow(if (settingsProvider().fileEnabled) fileFor(wallClock()) else null)
    override val currentFile: StateFlow<File?> = _currentFile.asStateFlow()

    override val directory: File get() = dir

    /** exchangeId → lettera del comando (dai CommandStarted), per le voci che non la portano. Solo consumatore. */
    private val commands = object : LinkedHashMap<Long, Char>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Char>?): Boolean = size > COMMANDS_CAP
    }

    private val consumer: Job = scope.launch {
        runRetention(protectedName = null)
        for (first in events) {
            process(first)
            var batch = 1
            while (batch < MAX_BATCH) {
                val next = events.tryReceive().getOrNull() ?: break
                process(next)
                batch++
            }
            flushWriter()
            publishSignal.trySend(Unit)
        }
        flushWriter()
        publishNow()
    }

    init {
        scope.launch {
            for (ignored in publishSignal) {
                publishNow()
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    override fun onEvent(event: ProtocolEvent) {
        // Mai bloccante: un canale chiuso (dopo close) scarta l'evento senza eccezioni.
        events.trySend(event)
    }

    override fun clearMemory() {
        synchronized(ringLock) {
            ring.clear()
            _entries.value = emptyList()
        }
    }

    /** Esporta le voci date nel formato file (§5.4.2), scrittura atomica. Result = numero di righe. */
    override suspend fun export(target: File, entries: List<TrafficEntry>): Result<Int> = withContext(Dispatchers.IO) {
        val part = File(target.absoluteFile.parentFile, target.name + ".part")
        try {
            val lines = entries.map { TrafficFormatter.toFileLine(it, zone) }
            target.absoluteFile.parentFile?.mkdirs()
            part.writeText(lines.joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator()), Charsets.UTF_8)
            try {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Result.success(lines.size)
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            log.warn("Esportazione del log traffico in {} fallita: {}", target.absolutePath, e.message)
            Result.failure(e)
        }
    }

    /** Svuota il canale e chiude il file (attesa massima 1 s). Idempotente. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        events.close()
        runBlocking { withTimeoutOrNull(CLOSE_TIMEOUT_MS) { consumer.join() } }
        scope.cancel()
        synchronized(writerLock) { closeWriterLocked() }
    }

    // ─── Consumatore ───────────────────────────────────────────────────────────

    private fun process(event: ProtocolEvent) {
        try {
            if (event is ProtocolEvent.CommandStarted) commands[event.exchangeId] = event.command.wire
            val settings = settingsProvider()
            TrafficFormatter.toEntry(event) { id -> id?.let { commands[it] } }?.let { addToRing(it, settings.memoryEntries) }
            if (settings.fileEnabled) {
                write(event)
            } else {
                synchronized(writerLock) { closeWriterLocked() }
                _currentFile.value = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Un evento che non si formatta non deve fermare il consumatore: il canale crescerebbe all'infinito.
            log.warn("Log traffico: evento {} non registrato: {}", event::class.simpleName, e.message)
        }
    }

    private fun addToRing(entry: TrafficEntry, capacity: Int) {
        synchronized(ringLock) {
            ring.addLast(entry)
            val max = capacity.coerceAtLeast(0)
            while (ring.size > max) ring.removeFirst()
        }
    }

    private fun publishNow() {
        synchronized(ringLock) { _entries.value = ring.toList() }
    }

    private fun write(event: ProtocolEvent) {
        val lines = TrafficFormatter.toFileLines(event, zone)
        synchronized(writerLock) {
            try {
                val date = Instant.ofEpochMilli(event.at).atZone(zone).toLocalDate()
                val w = writer.takeIf { writerDate == date } ?: rotateLocked(event.at, date)
                for (line in lines) {
                    w.write(line)
                    w.newLine()
                }
                fileErrorLogged = false
            } catch (e: IOException) {
                if (!fileErrorLogged) log.warn("Log traffico: scrittura su file non riuscita: {}", e.message)
                fileErrorLogged = true
                closeWriterLocked()
            }
        }
    }

    private fun rotateLocked(at: Long, date: LocalDate): BufferedWriter {
        closeWriterLocked()
        dir.mkdirs()
        val file = fileFor(at)
        val w = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
        writer = w
        writerDate = date
        _currentFile.value = file
        runRetention(protectedName = file.name)
        return w
    }

    private fun flushWriter() {
        synchronized(writerLock) {
            try {
                writer?.flush()
            } catch (e: IOException) {
                if (!fileErrorLogged) log.warn("Log traffico: flush non riuscito: {}", e.message)
                fileErrorLogged = true
                closeWriterLocked()
            }
        }
    }

    private fun closeWriterLocked() {
        try {
            writer?.close()
        } catch (e: IOException) {
            log.warn("Log traffico: chiusura del file non riuscita: {}", e.message)
        }
        writer = null
        writerDate = null
    }

    private fun fileFor(epochMs: Long): File = File(dir, TrafficFormatter.fileNameFor(epochMs, zone))

    /** Retention all'avvio e a ogni rotazione. Mai il file di oggi né quello in scrittura. */
    private fun runRetention(protectedName: String?) {
        try {
            val settings = settingsProvider()
            val today = Instant.ofEpochMilli(wallClock()).atZone(zone).toLocalDate()
            val protectedNames = setOfNotNull(protectedName, TrafficFormatter.fileNameFor(wallClock(), zone))
            val victims = retentionVictims(
                files = dir.listFiles()?.toList().orEmpty(),
                today = today,
                retentionDays = settings.retentionDays,
                maxTotalBytes = settings.maxTotalMb.toLong() * 1024 * 1024,
                protectedNames = protectedNames,
            )
            for (file in victims) {
                if (file.delete()) log.info("Log traffico: eliminato {} (retention)", file.name)
                else log.warn("Log traffico: impossibile eliminare {}", file.name)
            }
        } catch (e: Exception) {
            log.warn("Log traffico: retention non riuscita: {}", e.message)
        }
    }

    internal companion object {
        private const val MAX_BATCH = 512
        private const val PUBLISH_INTERVAL_MS = 100L
        private const val CLOSE_TIMEOUT_MS = 1_000L
        private const val COMMANDS_CAP = 256

        /**
         * File da eliminare: prima quelli più vecchi di [retentionDays], poi i più vecchi finché il totale supera
         * [maxTotalBytes]. Solo file `*-p17-traffic.txt` con una data leggibile nel nome; mai [protectedNames].
         */
        fun retentionVictims(
            files: List<File>,
            today: LocalDate,
            retentionDays: Int,
            maxTotalBytes: Long,
            protectedNames: Set<String>,
        ): List<File> {
            val dated = files
                .filter { it.isFile && TrafficFormatter.isTrafficFile(it.name) }
                .mapNotNull { f -> TrafficFormatter.dateOfFile(f.name)?.let { f to it } }
                .sortedBy { it.second }
            val oldest = today.minusDays(retentionDays.toLong())
            val victims = dated.filter { (f, date) -> date < oldest && f.name !in protectedNames }.map { it.first }.toMutableList()
            val remaining = dated.map { it.first }.filter { it !in victims }
            var total = remaining.sumOf { it.length() }
            for (file in remaining) {
                if (total <= maxTotalBytes) break
                if (file.name in protectedNames) continue
                victims += file
                total -= file.length()
            }
            return victims
        }
    }
}
