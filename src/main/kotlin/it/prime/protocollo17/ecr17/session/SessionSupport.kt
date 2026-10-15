package it.prime.protocollo17.ecr17.session

import it.prime.protocollo17.ecr17.event.ProtocolEvent

/**
 * Emettitore degli eventi del client: assegna `seq` (contatore monotono) e `at` (wallClock), poi consegna l'evento
 * al sink sincrono e allo SharedFlow.
 *
 * La sessione costruisce solo il contenuto dell'evento: numerazione e orologio restano un'unica fonte nel client,
 * così gli eventi di sessione, trasporto (link) e client escono in un solo ordine coerente.
 */
internal fun interface EventEmitter {
    fun emit(build: (seq: Long, at: Long) -> ProtocolEvent)
}

/**
 * Annullamento richiesto dall'operatore per UN execute: creato al punto 1 di §3.23, mai riusato né azzerato.
 *
 * Prima (nei repo) un flag di annullamento condiviso veniva azzerato all'inizio del comando successivo, e un
 * "Annulla" arrivato tra il lock e la connessione andava perso. Un token per comando, pubblicato subito dopo il
 * lock, rende valido qualsiasi annullamento arrivato da quel momento in poi.
 */
internal class CancelToken {
    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() {
        cancelled = true
    }
}

/**
 * Lanciata da [TraceRecorder.phase] SOLO per la fase SENDING prima del primo tentativo di scrittura del frame
 * principale (§3.19): il listener (journal) non è riuscito a registrare l'invio, quindi il frame non parte (M3).
 * Dopo il primo tentativo di scrittura nessun errore del listener interrompe più lo scambio (M10).
 */
internal class ListenerAbortException(cause: Throwable) : Exception(cause.message, cause)
