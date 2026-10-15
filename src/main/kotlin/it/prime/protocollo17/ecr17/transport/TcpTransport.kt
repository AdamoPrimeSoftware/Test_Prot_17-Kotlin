package it.prime.protocollo17.ecr17.transport

import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.LinkState
import kotlinx.coroutines.flow.StateFlow

// STUB di WP0 (§8.2): firme definitive di §3.18, corpi a carico di WP3.
// Requisiti di implementazione in §3.18: scope IO proprio, lettore unico "ecr17-reader", nessun probe.

/**
 * Trasporto TCP reale (java.net.Socket). È l'unico pezzo di `ecr17` con un proprio scope su Dispatchers.IO:
 * client e sessione girano sul dispatcher del chiamante (§2.10), così i test in tempo virtuale restano esatti.
 */
class TcpTransport(
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val readBufferSize: Int = 4096,
) : Ecr17Transport {

    override val linkState: StateFlow<LinkState>
        get() = TODO("WP3: TcpTransport.kt")

    override val rx: RxBuffer
        get() = TODO("WP3: TcpTransport.kt")

    override var linkListener: ((LinkState) -> Unit)?
        get() = TODO("WP3: TcpTransport.kt")
        set(@Suppress("UNUSED_PARAMETER") value) = TODO("WP3: TcpTransport.kt")

    override suspend fun connect(endpoint: Endpoint, connectTimeoutMs: Long): Unit = TODO("WP3: TcpTransport.kt")

    override suspend fun send(bytes: ByteArray, writeTimeoutMs: Long): Unit = TODO("WP3: TcpTransport.kt")

    override suspend fun close(): Unit = TODO("WP3: TcpTransport.kt")

    override fun abort(): Unit = TODO("WP3: TcpTransport.kt")

    override fun shutdown(): Unit = TODO("WP3: TcpTransport.kt")
}
