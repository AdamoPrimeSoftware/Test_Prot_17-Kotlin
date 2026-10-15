package it.prime.protocollo17.ecr17.client

import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.event.ExchangeListener
import it.prime.protocollo17.ecr17.event.ProtocolEvent
import it.prime.protocollo17.ecr17.event.ProtocolEventSink
import it.prime.protocollo17.ecr17.model.CardVerificationRequest
import it.prime.protocollo17.ecr17.model.CloseSessionRequest
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.ConnectionTestResult
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.IncrementalAuthRequest
import it.prime.protocollo17.ecr17.model.LastResultRequest
import it.prime.protocollo17.ecr17.model.LinkState
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.PreAuthClosureRequest
import it.prime.protocollo17.ecr17.model.PreAuthRequest
import it.prime.protocollo17.ecr17.model.ReprintRequest
import it.prime.protocollo17.ecr17.model.ReversalRequest
import it.prime.protocollo17.ecr17.model.TotalsRequest
import it.prime.protocollo17.ecr17.profile.Ecr17Config
import it.prime.protocollo17.ecr17.transport.Ecr17Transport
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

// STUB di WP0 (§8.2): firme definitive di §3.23, corpi a carico di WP3 (algoritmo vincolante di §3.23).

/**
 * Facciata del protocollo. Un comando alla volta (Mutex.tryLock: un secondo comando riceve NotSent(Busy), M7).
 * configProvider è letto UNA volta all'inizio di ogni comando: le modifiche alle opzioni valgono dal comando successivo.
 * Gira sul dispatcher del chiamante (§2.10).
 */
class Ecr17Client(
    private val configProvider: () -> Ecr17Config,
    private val transport: Ecr17Transport,
    private val eventSink: ProtocolEventSink = ProtocolEventSink.NONE,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    /** Copia degli eventi per test e osservatori non critici (extraBufferCapacity 256, DROP_OLDEST). */
    val events: SharedFlow<ProtocolEvent>
        get() = TODO("WP3: Ecr17Client.kt")

    /** = transport.linkState */
    val linkState: StateFlow<LinkState>
        get() = TODO("WP3: Ecr17Client.kt")

    val busy: StateFlow<CommandCode?>
        get() = TODO("WP3: Ecr17Client.kt")

    suspend fun execute(request: Ecr17Request, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        TODO("WP3: Ecr17Client.kt")

    // ─── Scorciatoie tipizzate (delegano a execute) ───────────────────────────

    suspend fun status(listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        TODO("WP3: Ecr17Client.kt")

    suspend fun pay(request: PaymentRequest, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        TODO("WP3: Ecr17Client.kt")

    suspend fun reverse(request: ReversalRequest, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        TODO("WP3: Ecr17Client.kt")

    suspend fun preAuth(request: PreAuthRequest, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        TODO("WP3: Ecr17Client.kt")

    suspend fun incrementalAuth(request: IncrementalAuthRequest, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        TODO("WP3: Ecr17Client.kt")

    suspend fun preAuthClosure(request: PreAuthClosureRequest, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        TODO("WP3: Ecr17Client.kt")

    suspend fun verifyCard(request: CardVerificationRequest, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        TODO("WP3: Ecr17Client.kt")

    suspend fun closeSession(request: CloseSessionRequest = CloseSessionRequest(), listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        TODO("WP3: Ecr17Client.kt")

    suspend fun totals(request: TotalsRequest = TotalsRequest(), listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        TODO("WP3: Ecr17Client.kt")

    suspend fun sendLastResult(request: LastResultRequest = LastResultRequest(), listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        TODO("WP3: Ecr17Client.kt")

    suspend fun setEcrPrinting(enabled: Boolean, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        TODO("WP3: Ecr17Client.kt")

    suspend fun reprint(request: ReprintRequest, listener: ExchangeListener = ExchangeListener.NONE): CommandOutcome =
        TODO("WP3: Ecr17Client.kt")

    /** Apre e (in PER_COMMAND) richiude la connessione misurando il tempo. Busy se un comando è in corso. */
    suspend fun testConnection(): ConnectionTestResult = TODO("WP3: Ecr17Client.kt")

    /** Solo PERSISTENT: apre il collegamento. null = ok. */
    suspend fun connect(): Ecr17Failure? = TODO("WP3: Ecr17Client.kt")

    /** Chiusura intenzionale (rifiutata con Busy durante un comando). */
    suspend fun disconnect(): Ecr17Failure? = TODO("WP3: Ecr17Client.kt")

    /** Thread-safe, non sospende: token.cancel() + transport.abort() del comando corrente. true se c'era un comando. */
    fun cancelCurrent(): Boolean = TODO("WP3: Ecr17Client.kt")

    /** transport.shutdown(). */
    override fun close(): Unit = TODO("WP3: Ecr17Client.kt")
}
