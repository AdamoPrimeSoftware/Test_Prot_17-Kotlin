package it.prime.protocollo17.ecr17.testing

import it.prime.protocollo17.ecr17.model.CloseCause
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.LinkState
import it.prime.protocollo17.ecr17.transport.Ecr17Transport
import it.prime.protocollo17.ecr17.transport.RxBuffer
import it.prime.protocollo17.ecr17.transport.TransportErrorKind
import it.prime.protocollo17.ecr17.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [WP3] Trasporto scriptato in tempo virtuale (semantica Rust, A§8 D19): una risposta per ogni frame applicativo scritto.
 *
 * Serve a provare sessione e client senza socket: i ritardi sono `delay()` nello [scope] (il `backgroundScope` di
 * `runTest`), quindi 120 s di timeout durano microsecondi e ogni interleaving è deterministico (§7.1).
 *
 * Regole:
 * - ogni `send` che inizia con STX consuma il prossimo script registrato con [onApplicationFrame] (ritrasmissioni ed
 *   E1 comprese); script esaurito = silenzio. ACK e NAK dell'ECR non consumano script;
 * - i ritardi di uno script sono offset dall'istante della scrittura; gli step con ritardo 0 sono applicati in modo
 *   SINCRONO dentro `send` (o dentro `connect` per [onConnect]), prima che la chiamata ritorni;
 * - uno step programmato per una connessione non tocca mai quella successiva (stessa regola delle epoche di RxBuffer).
 */
class ScriptedTransport(private val scope: CoroutineScope, private val wallClock: () -> Long = { 0L }) : Ecr17Transport {

    sealed interface Step {
        val delayMs: Long

        class Bytes(override val delayMs: Long, val bytes: ByteArray) : Step
        class Close(override val delayMs: Long, val cause: CloseCause = CloseCause.PEER_CLOSED) : Step
    }

    private val scripts = ArrayDeque<List<Step>>()
    private var connectSteps: List<Step> = emptyList()

    private val mutableLinkState = MutableStateFlow<LinkState>(LinkState.Idle)
    override val linkState: StateFlow<LinkState> = mutableLinkState.asStateFlow()
    override val rx: RxBuffer = RxBuffer()
    override var linkListener: ((LinkState) -> Unit)? = null

    private var epoch = 0L
    private var open = false
    private var endpoint = Endpoint("scripted", 1)

    private var nextConnectFailure: TransportErrorKind? = null
    private var failWritesFrom: Int? = null
    private val throwOnWrites = mutableMapOf<Int, Throwable>()
    private var writeCalls = 0
    private val writeLog = mutableListOf<ByteArray>()
    private var pendingConnectAbort: CompletableDeferred<Unit>? = null

    /** Extra per i test di annullamento (CL-15): durata di una connect, interrompibile da abort(). */
    var connectDelayMs: Long = 0

    /** Extra per i test d'ordine (SE-34): chiamato a ogni scrittura riuscita, con una copia dei byte. */
    var onWrite: ((ByteArray) -> Unit)? = null

    /** Script consumato in ordine: uno per ogni write che inizia con STX (ritrasmissioni comprese). Script esaurito = silenzio. */
    fun onApplicationFrame(vararg steps: Step) {
        scripts.addLast(steps.toList())
    }

    /**
     * Byte spontanei alla connessione (frame stantii). Gli step con delayMs == 0 sono aggiunti a rx in modo
     * SINCRONO dentro connect(), prima che connect() ritorni: con preSendQuietMs = 0 il punto 1 di §3.21 li vede
     * comunque (SE-17 deterministico). Gli step con ritardo partono nel backgroundScope.
     * Valgono per la sola connessione successiva.
     */
    fun onConnect(vararg steps: Step) {
        connectSteps = steps.toList()
    }

    /** Iniezione libera, relativa all'istante corrente. */
    fun push(delayMs: Long, bytes: ByteArray) {
        schedule(listOf(Step.Bytes(delayMs, bytes)))
    }

    fun failNextConnect(kind: TransportErrorKind) {
        nextConnectFailure = kind
    }

    /** Dalla (n+1)-esima write in poi: TransportException(WRITE_FAILED). */
    fun failWritesAfter(n: Int) {
        failWritesFrom = n
    }

    /** La (n+1)-esima write lancia error (anche non TransportException: simula un bug, CL-19). */
    fun throwOnWrite(n: Int, error: Throwable) {
        throwOnWrites[n] = error
    }

    /** Byte scritti con successo, in ordine (copie). */
    val writes: List<ByteArray> get() = writeLog.map { it.copyOf() }

    /** Solo le scritture che iniziano con STX (frame applicativi dell'ECR). */
    val applicationWrites: List<ByteArray> get() = writes.filter { it.isNotEmpty() && it[0] == STX }

    var connectCount: Int = 0
        private set
    var closeCount: Int = 0
        private set
    var abortCount: Int = 0
        private set

    override suspend fun connect(endpoint: Endpoint, connectTimeoutMs: Long) {
        if (open) drop(CloseCause.LOCAL, null)   // come il TCP reale: la connessione precedente si chiude
        connectCount++
        this.endpoint = endpoint
        publish(LinkState.Connecting(endpoint, wallClock()))
        nextConnectFailure?.let { kind ->
            nextConnectFailure = null
            publish(
                LinkState.ConnectFailed(
                    endpoint, timeout = kind == TransportErrorKind.CONNECT_TIMEOUT, detail = "scripted $kind", at = wallClock(),
                ),
            )
            throw TransportException(kind, "scripted")
        }
        if (connectDelayMs > 0) {
            val abortSignal = CompletableDeferred<Unit>()
            pendingConnectAbort = abortSignal
            val aborted = try {
                withTimeoutOrNull(connectDelayMs) { abortSignal.await() } != null
            } finally {
                pendingConnectAbort = null
            }
            if (aborted) {
                publish(LinkState.ConnectFailed(endpoint, timeout = false, detail = "connessione annullata", at = wallClock()))
                throw TransportException(TransportErrorKind.ABORTED, "scripted abort")
            }
        }
        epoch = rx.reset()
        open = true
        publish(LinkState.Connected(endpoint, wallClock()))
        val steps = connectSteps
        connectSteps = emptyList()
        schedule(steps)
    }

    override suspend fun send(bytes: ByteArray, writeTimeoutMs: Long) {
        val index = writeCalls++
        if (!open || rx.closedCause != null) {
            throw TransportException(TransportErrorKind.NOT_CONNECTED, "scripted: collegamento chiuso")
        }
        throwOnWrites[index]?.let { throw it }
        val failFrom = failWritesFrom
        if (failFrom != null && index >= failFrom) {
            throw TransportException(TransportErrorKind.WRITE_FAILED, "scripted write failure")
        }
        writeLog += bytes.copyOf()
        onWrite?.invoke(bytes.copyOf())
        if (bytes.isNotEmpty() && bytes[0] == STX) scripts.removeFirstOrNull()?.let(::schedule)
    }

    override suspend fun close() {
        closeCount++
        if (open) drop(CloseCause.LOCAL, null)
    }

    override fun abort() {
        abortCount++
        pendingConnectAbort?.complete(Unit)
        if (open) drop(CloseCause.ABORTED, "annullato") else rx.markClosedCurrent(CloseCause.ABORTED, "annullato")
    }

    override fun shutdown() {
        abort()
    }

    // ─── Dettagli ────────────────────────────────────────────────────────────

    private fun schedule(steps: List<Step>) {
        val target = epoch
        for (step in steps) {
            if (step.delayMs <= 0) {
                apply(step, target)
            } else {
                scope.launch {
                    delay(step.delayMs)
                    apply(step, target)
                }
            }
        }
    }

    private fun apply(step: Step, target: Long) {
        if (target != epoch || !open) return   // connessione cambiata o già chiusa
        when (step) {
            is Step.Bytes -> rx.append(target, step.bytes, 0, step.bytes.size)
            is Step.Close -> drop(step.cause, "scripted ${step.cause}")
        }
    }

    private fun drop(cause: CloseCause, detail: String?) {
        rx.markClosedCurrent(cause, detail)
        open = false
        publish(LinkState.Closed(endpoint, cause, detail, wallClock()))
    }

    private fun publish(state: LinkState) {
        mutableLinkState.value = state
        linkListener?.invoke(state)
    }

    private companion object {
        const val STX: Byte = 0x02
    }
}
