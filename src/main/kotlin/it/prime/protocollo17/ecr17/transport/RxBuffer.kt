package it.prime.protocollo17.ecr17.transport

import it.prime.protocollo17.ecr17.model.CloseCause
import kotlinx.coroutines.channels.Channel

/**
 * Buffer di ricezione condiviso tra lettore (append) e sessione (takeAll), più un segnale CONFLATED.
 * Perdere un segnale è innocuo: i dati restano nel buffer (A§12.4). Le "epoche" impediscono che un lettore
 * di una connessione precedente scriva nel buffer della nuova.
 *
 * Prima (nei repo) i byte passavano da un `Channel<ByteArray>`: con la cancellazione pronta di
 * `receive` un chunk già tolto dal canale poteva andare perso proprio sull'esito. Qui i byte non
 * viaggiano mai nel canale: il canale porta solo "c'è qualcosa", e i dati si prendono dal buffer.
 */
class RxBuffer(private val maxBytes: Int = 1 shl 20) {

    init {
        require(maxBytes > 0) { "maxBytes deve essere > 0 (era $maxBytes)" }
    }

    private val lock = Any()

    // ─── Stato protetto da lock ───────────────────────────────────────────────
    private var buffer = ByteArray(minOf(INITIAL_CAPACITY, maxBytes))
    private var size = 0
    private var epoch = 0L

    /** Segnale "dati o chiusura disponibili": conflated, quindi un segnale perso o doppio non cambia nulla. */
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** Causa della chiusura dell'epoca corrente, null se aperta. Letta senza lock dalla sessione. */
    @Volatile
    var closedCause: CloseCause? = null
        private set

    @Volatile
    var closedDetail: String? = null
        private set

    /** true se dall'ultimo reset() sono stati scartati byte per overflow (warning RX_OVERFLOW). */
    @Volatile
    var overflowed: Boolean = false
        private set

    /** Nuova connessione: svuota, azzera la chiusura, incrementa e restituisce l'epoca. */
    fun reset(): Long {
        val current = synchronized(lock) {
            size = 0
            closedCause = null
            closedDetail = null
            overflowed = false
            epoch += 1
            epoch
        }
        signal.trySend(Unit)
        return current
    }

    /** Dal lettore. Ignorato se epoch non è quella corrente. Oltre maxBytes scarta i più vecchi e imposta overflowed. */
    fun append(epoch: Long, src: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= src.size) {
            "intervallo non valido: offset=$offset length=$length size=${src.size}"
        }
        if (length == 0) return
        synchronized(lock) {
            if (epoch != this.epoch) return
            val total = size + length
            if (total <= maxBytes) {
                ensureCapacity(total)
                System.arraycopy(src, offset, buffer, size, length)
                size = total
            } else {
                // Si tengono gli ULTIMI maxBytes byte (vecchi + nuovi): l'esito arriva in coda.
                overflowed = true
                val drop = total - maxBytes
                if (drop >= size) {
                    val srcSkip = drop - size
                    ensureCapacity(maxBytes)
                    System.arraycopy(src, offset + srcSkip, buffer, 0, length - srcSkip)
                    size = length - srcSkip
                } else {
                    System.arraycopy(buffer, drop, buffer, 0, size - drop)
                    size -= drop
                    ensureCapacity(size + length)
                    System.arraycopy(src, offset, buffer, size, length)
                    size += length
                }
            }
        }
        signal.trySend(Unit)
    }

    /** Dal lettore (EOF/IOException). Ignorato se epoch non corrente o già chiuso. */
    fun markClosed(epoch: Long, cause: CloseCause, detail: String?) {
        synchronized(lock) {
            if (epoch != this.epoch || closedCause != null) return
            closedDetail = detail
            closedCause = cause
        }
        signal.trySend(Unit)
    }

    /**
     * Da abort()/close(): chiude l'epoca corrente. Se l'epoca è già chiusa (es. PEER_CLOSED visto dal
     * lettore) la prima causa resta: è quella che spiega cosa è successo durante lo scambio.
     */
    fun markClosedCurrent(cause: CloseCause, detail: String?) {
        synchronized(lock) {
            if (closedCause != null) return
            closedDetail = detail
            closedCause = cause
        }
        signal.trySend(Unit)
    }

    /** Rimuove e restituisce tutti i byte (anche vuoto). */
    fun takeAll(): ByteArray = synchronized(lock) {
        val out = buffer.copyOf(size)
        size = 0
        out
    }

    fun isEmpty(): Boolean = synchronized(lock) { size == 0 }

    /** Ritorna appena ci sono byte o l'epoca è chiusa. Da usare dentro withTimeoutOrNull. */
    suspend fun awaitDataOrClose() {
        while (isEmpty() && closedCause == null) signal.receive()
    }

    /** Cresce a raddoppi fino a maxBytes; chiamata solo sotto lock. */
    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var capacity = buffer.size.coerceAtLeast(1)
        while (capacity < needed) capacity = (capacity * 2).coerceAtMost(maxBytes).coerceAtLeast(needed)
        buffer = buffer.copyOf(capacity)
    }

    private companion object {
        const val INITIAL_CAPACITY = 4096
    }
}
