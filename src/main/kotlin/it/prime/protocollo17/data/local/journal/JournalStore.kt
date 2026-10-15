package it.prime.protocollo17.data.local.journal

import it.prime.protocollo17.domain.model.journal.JournalRecord
import java.io.File

// STUB di WP0 (§8.2): firme definitive di §5.3.3, corpi a carico di WP5. Esiste già in WP0 perché AppModule
// lo costruisce (§6.16).

/**
 * Accesso al file JSONL. Thread-safe; tutte le funzioni sospese girano su Dispatchers.IO.
 *
 * Append-only con fsync a ogni riga: la garanzia di M3 (nessun pagamento inviato senza traccia durevole) è
 * proprio che [append] ritorni solo quando il record è su disco.
 */
class JournalStore(private val dir: File) : AutoCloseable {
    /** dir/journal.jsonl */
    val file: File
        get() = TODO("WP5: JournalStore.kt")

    /** Legge tutti i record validi in ordine di file; le righe illeggibili finiscono in journal.jsonl.corrupt-<millis>. */
    suspend fun readAll(): List<JournalRecord> = TODO("WP5: JournalStore.kt")

    /** Append di una riga + force(true). Ritorna solo a dato durevole; IOException se non scrivibile. */
    suspend fun append(record: JournalRecord): Unit = TODO("WP5: JournalStore.kt")

    /** Riscrive atomicamente il file con i soli record dati (backup in journal.jsonl.bak). */
    suspend fun rewrite(records: List<JournalRecord>): Unit = TODO("WP5: JournalStore.kt")

    fun sizeBytes(): Long = TODO("WP5: JournalStore.kt")

    override fun close(): Unit = TODO("WP5: JournalStore.kt")
}
