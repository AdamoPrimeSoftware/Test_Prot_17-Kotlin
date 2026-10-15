package it.prime.protocollo17.data.local.traffic

import it.prime.protocollo17.domain.model.settings.TrafficLogSettings
import it.prime.protocollo17.domain.model.traffic.TrafficEntry
import it.prime.protocollo17.domain.repository.TrafficRepository
import it.prime.protocollo17.ecr17.event.ProtocolEvent
import it.prime.protocollo17.ecr17.event.ProtocolEventSink
import kotlinx.coroutines.flow.StateFlow
import java.io.File

// STUB di WP0 (§8.2): firme definitive di §5.4.3, corpi a carico di WP5.

/**
 * Sink degli eventi ECR17: memoria per la UI + file giornaliero. onEvent non blocca mai (contratto di
 * ProtocolEventSink, §3.19): la sessione lo chiama in linea durante lo scambio, e un disco lento non deve mai
 * ritardare un ACK.
 */
class TrafficRecorder(
    private val dir: File,
    private val settingsProvider: () -> TrafficLogSettings,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ProtocolEventSink, TrafficRepository, AutoCloseable {

    override fun onEvent(event: ProtocolEvent): Unit = TODO("WP5: TrafficRecorder.kt")

    override val entries: StateFlow<List<TrafficEntry>>
        get() = TODO("WP5: TrafficRecorder.kt")

    override val currentFile: StateFlow<File?>
        get() = TODO("WP5: TrafficRecorder.kt")

    override val directory: File
        get() = TODO("WP5: TrafficRecorder.kt")

    override fun clearMemory(): Unit = TODO("WP5: TrafficRecorder.kt")

    override suspend fun export(target: File, entries: List<TrafficEntry>): Result<Int> = TODO("WP5: TrafficRecorder.kt")

    override fun close(): Unit = TODO("WP5: TrafficRecorder.kt")
}
