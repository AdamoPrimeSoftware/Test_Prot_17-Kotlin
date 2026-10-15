package it.prime.protocollo17.ecr17.transport

import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.LinkState
import kotlinx.coroutines.flow.StateFlow

/** Categoria di un errore di trasporto: il client la mappa su ConnectFailed/ConnectTimeout/WriteFailed/Disconnected. */
enum class TransportErrorKind { DNS_FAILED, CONNECT_TIMEOUT, CONNECT_FAILED, NOT_CONNECTED, WRITE_FAILED, WRITE_TIMEOUT, ABORTED }

/**
 * Unica eccezione del trasporto. Estende IOException perché lo è; il client la distingue da qualsiasi altra
 * eccezione (bug) che invece diventa Ecr17Failure.Internal (§3.23 punto 11).
 */
class TransportException(val kind: TransportErrorKind, val detail: String?, cause: Throwable? = null) :
    java.io.IOException("ECR17: transport $kind${detail?.let { ": $it" } ?: ""}", cause)

/**
 * Trasporto byte-oriented. Un'istanza vive quanto il client e può connettersi più volte.
 * Contratto: tutti i byte ricevuti prima di una chiusura sono in `rx` PRIMA che rx risulti chiuso.
 *
 * È un'interfaccia perché la sessione si prova in tempo virtuale con un trasporto scriptato (§7.1):
 * il TCP vero serve solo ai test di trasporto e di integrazione.
 */
interface Ecr17Transport {
    val linkState: StateFlow<LinkState>
    val rx: RxBuffer
    val isOpen: Boolean get() = linkState.value is LinkState.Connected

    /** Notificato a ogni cambio di stato, anche dal thread del lettore. Impostato dal client. */
    var linkListener: ((LinkState) -> Unit)?

    /** Chiude un'eventuale connessione precedente, poi connette (TCP_NODELAY). Lancia TransportException. Cancellabile. */
    suspend fun connect(endpoint: Endpoint, connectTimeoutMs: Long)

    /** Una sola write+flush del frame sotto lock di scrittura. Lancia TransportException (NOT_CONNECTED, WRITE_*). */
    suspend fun send(bytes: ByteArray, writeTimeoutMs: Long)

    /** Chiusura intenzionale (CloseCause.LOCAL). Idempotente. */
    suspend fun close()

    /** Da qualsiasi thread, non sospende: chiude subito socket e connect in corso; rx chiuso con ABORTED. */
    fun abort()

    /** Fine vita: abort + cancellazione dello scope interno. */
    fun shutdown()
}
