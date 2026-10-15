package it.prime.protocollo17.domain.repository

import it.prime.protocollo17.domain.model.traffic.TrafficEntry
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Log traffico hex TX/RX visto dalla finestra Log (§5.4, §6.12).
 *
 * La finestra legge solo questa interfaccia: il registratore concreto è anche il sink degli eventi del client,
 * ma la presentation non deve poterlo alimentare né vedere i tipi di `ecr17` (§2.10).
 */
interface TrafficRepository {
    /** Ring buffer in memoria (TrafficLogSettings.memoryEntries), dal più vecchio al più recente. */
    val entries: StateFlow<List<TrafficEntry>>

    /** File di oggi, null se la scrittura su file è disattivata. */
    val currentFile: StateFlow<File?>

    val directory: File

    fun clearMemory()

    /** Esporta le voci date nel formato file (§5.4.2), scrittura atomica. Result = numero di righe. */
    suspend fun export(target: File, entries: List<TrafficEntry>): Result<Int>
}
