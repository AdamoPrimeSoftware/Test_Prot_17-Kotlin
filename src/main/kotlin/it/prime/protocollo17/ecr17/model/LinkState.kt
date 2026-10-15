package it.prime.protocollo17.ecr17.model

/** Indirizzo del terminale. `toString()` = "host:porta", la forma usata in log, journal e messaggi. */
data class Endpoint(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"
}

/** Perché un collegamento si è chiuso: distingue la fine voluta (LOCAL) dalle cadute e dall'annullamento. */
enum class CloseCause { LOCAL, PEER_CLOSED, RESET, IO_ERROR, ABORTED, WRITE_TIMEOUT }

/**
 * Stato del collegamento, derivato dal lettore e dalle operazioni esplicite. `at` = epoch ms.
 *
 * Deriva dal lettore del socket (EOF/IOException ⇒ Closed) e mai da un flag azzerato a ogni
 * transazione (A§8 D13): così una caduta tra due comandi è visibile prima di scrivere il successivo.
 */
sealed interface LinkState {
    data object Idle : LinkState
    data class Connecting(val endpoint: Endpoint, val at: Long) : LinkState
    data class Connected(val endpoint: Endpoint, val at: Long) : LinkState
    data class Closed(val endpoint: Endpoint, val cause: CloseCause, val detail: String?, val at: Long) : LinkState
    data class ConnectFailed(val endpoint: Endpoint, val timeout: Boolean, val detail: String?, val at: Long) : LinkState
}
