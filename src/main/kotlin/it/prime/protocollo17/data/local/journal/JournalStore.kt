package it.prime.protocollo17.data.local.journal

import it.prime.protocollo17.domain.model.journal.JournalRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Accesso al file JSONL. Thread-safe; tutte le funzioni sospese girano su Dispatchers.IO.
 *
 * Append-only con fsync a ogni riga: la garanzia di M3 (nessun pagamento inviato senza traccia durevole) è
 * proprio che [append] ritorni solo quando il record è su disco.
 *
 * Una riga è scritta per intero o per niente dal punto di vista del chiamante: la scrittura gira in
 * NonCancellable sotto [mutex]. Se una scrittura fallisce a metà (disco pieno) la riga successiva parte comunque
 * su una riga nuova ([needsNewline]): così il troncamento rovina al massimo la riga fallita, mai la successiva.
 */
class JournalStore(private val dir: File) : AutoCloseable {

    private val log = LoggerFactory.getLogger(JournalStore::class.java)

    /** dir/journal.jsonl */
    val file: File
        get() = File(dir, FILE_NAME)

    private val mutex = Mutex()

    /** Canale tenuto aperto tra un append e l'altro (CREATE, WRITE, APPEND); riaperto dopo un errore o un rewrite. */
    private var channel: FileChannel? = null
    private val channelLock = Any()

    /** true se il file può terminare senza '\n' (riga troncata trovata in lettura o scrittura fallita a metà). */
    @Volatile
    private var needsNewline = false

    @Volatile
    private var closed = false

    /** Legge tutti i record validi in ordine di file; le righe illeggibili finiscono in journal.jsonl.corrupt-<millis>. */
    suspend fun readAll(): List<JournalRecord> = withContext(Dispatchers.IO) {
        mutex.withLock { readAllLocked() }
    }

    /** Append di una riga + force(true). Ritorna solo a dato durevole; IOException se non scrivibile. */
    suspend fun append(record: JournalRecord) {
        val line = json.encodeToString(JournalRecord.serializer(), record)
        // NonCancellable: il chiamante (sessione, finally del repository) deve sapere con certezza se la riga è
        // durevole; una cancellazione a metà lascerebbe una riga scritta senza che il chiamante lo sappia.
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock { appendLocked(line) }
        }
    }

    /** Riscrive atomicamente il file con i soli record dati (backup in journal.jsonl.bak). */
    suspend fun rewrite(records: List<JournalRecord>) {
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock { rewriteLocked(records) }
        }
    }

    fun sizeBytes(): Long = file.length()

    override fun close() {
        closed = true
        closeChannel()
    }

    // ─── Implementazione (sempre sotto mutex) ──────────────────────────────────

    private fun readAllLocked(): List<JournalRecord> {
        val f = file
        if (!f.exists()) return emptyList()
        val bytes = f.readBytes()
        if (bytes.isNotEmpty() && bytes.last() != NEWLINE) needsNewline = true
        val text = String(bytes, Charsets.UTF_8).removePrefix(BOM)
        val records = ArrayList<JournalRecord>()
        val corrupt = ArrayList<String>()
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            try {
                records += json.decodeFromString(JournalRecord.serializer(), line)
            } catch (e: Exception) {
                corrupt += line
            }
        }
        if (corrupt.isNotEmpty()) saveCorrupt(corrupt)
        return records
    }

    /**
     * Le righe illeggibili (tipicamente l'ultima, troncata) non si perdono: si conservano per l'analisi.
     *
     * Prima ogni avvio creava un nuovo `.corrupt-<millis>` con le stesse righe, che restano nel journal finché una
     * compattazione non lo riscrive. Ora si salvano solo le righe che nessun file corrupt esistente contiene già: a
     * contenuto identico non si crea nulla. Se un file esistente non è leggibile si salva comunque (meglio un
     * duplicato che una riga persa).
     */
    private fun saveCorrupt(lines: List<String>) {
        val saved = savedCorruptLines()
        val fresh = lines.filter { it !in saved }
        if (fresh.isEmpty()) {
            log.warn("Journal: {} righe illeggibili saltate, già conservate in {}.corrupt-*", lines.size, FILE_NAME)
            return
        }
        val target = File(dir, "$FILE_NAME.corrupt-${System.currentTimeMillis()}")
        log.warn("Journal: {} righe illeggibili saltate, {} nuove conservate in {}", lines.size, fresh.size, target.name)
        try {
            target.appendText(fresh.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
        } catch (e: Exception) {
            log.error("Journal: copia delle righe illeggibili in {} non riuscita: {}", target.name, e.message)
        }
    }

    /** Righe già conservate nei file `journal.jsonl.corrupt-*` della cartella. */
    private fun savedCorruptLines(): Set<String> {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("$FILE_NAME.corrupt-") }.orEmpty()
        val saved = HashSet<String>()
        for (f in files) {
            try {
                f.readLines(Charsets.UTF_8).forEach { saved += it.trimEnd('\r') }
            } catch (e: IOException) {
                log.warn("Journal: {} non leggibile, le righe illeggibili si salvano comunque: {}", f.name, e.message)
            }
        }
        return saved
    }

    private fun appendLocked(line: String) {
        if (closed) throw IOException("journal chiuso")
        val prefix = if (needsNewline) "\n" else ""
        val buffer = ByteBuffer.wrap("$prefix$line\n".toByteArray(Charsets.UTF_8))
        try {
            val ch = openChannel()
            while (buffer.hasRemaining()) ch.write(buffer)
            ch.force(true)
            needsNewline = false
        } catch (e: IOException) {
            // Scrittura parziale possibile: la prossima riga partirà da capo, e il canale si riapre (un errore
            // transitorio, come un file bloccato da un antivirus, non deve rendere il journal inutilizzabile).
            if (buffer.position() > 0) needsNewline = true
            closeChannel()
            throw e
        }
    }

    private fun rewriteLocked(records: List<JournalRecord>) {
        if (closed) throw IOException("journal chiuso")
        dir.mkdirs()
        // Il file va sostituito: su Windows un canale aperto può impedire il move.
        closeChannel()
        val target = file
        val part = File(dir, "$FILE_NAME.part")
        try {
            FileOutputStream(part).use { out ->
                for (record in records) {
                    out.write(json.encodeToString(JournalRecord.serializer(), record).toByteArray(Charsets.UTF_8))
                    out.write(NEWLINE.toInt())
                }
                out.fd.sync()
            }
            if (target.exists()) {
                Files.copy(target.toPath(), File(dir, "$FILE_NAME.bak").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            needsNewline = false
        } catch (e: IOException) {
            part.delete()
            throw e
        }
    }

    private fun openChannel(): FileChannel = synchronized(channelLock) {
        channel?.takeIf { it.isOpen } ?: run {
            dir.mkdirs()
            FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                .also { channel = it }
        }
    }

    private fun closeChannel() {
        synchronized(channelLock) {
            try {
                channel?.close()
            } catch (e: IOException) {
                log.warn("Journal: chiusura del file non riuscita: {}", e.message)
            }
            channel = null
        }
    }

    private companion object {
        const val FILE_NAME = "journal.jsonl"
        const val NEWLINE: Byte = 0x0A
        const val BOM = "﻿"

        /** I campi null non si scrivono: righe corte e leggibili a occhio. */
        val json = Json {
            explicitNulls = false
            encodeDefaults = false
            ignoreUnknownKeys = true
        }
    }
}
