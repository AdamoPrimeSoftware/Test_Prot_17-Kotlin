package it.prime.protocollo17.ecr17.wire

import it.prime.protocollo17.ecr17.profile.ControlLrcRxPolicy

// STUB di WP0 (§8.2): firme definitive di §3.7, corpi a carico di WP1.

/**
 * Splitter di stream **puro e con stato** (buffer interno), alimentato con chunk qualsiasi (A§8 D1, L§13.3).
 *
 * Non conosce il comando inviato: la classificazione esito/orfano/scontrino è della sessione. Dare i byte
 * in un solo chunk o uno alla volta deve produrre la stessa sequenza di elementi.
 */
class FrameSplitter(
    private val appMode: LrcMode,
    private val controlRxPolicy: ControlLrcRxPolicy,    // da ecr17.profile
    private val maxFrameBytes: Int = 4096,
) {
    /** Accoda byte ricevuti (copia). */
    fun feed(bytes: ByteArray): Unit = TODO("WP1: FrameSplitter.kt")

    /** Prossimo elemento estraibile dalla testa, oppure null se servono altri byte. */
    fun next(): SplitItem? = TODO("WP1: FrameSplitter.kt")

    /** Byte in attesa nel buffer interno. */
    fun pendingSize(): Int = TODO("WP1: FrameSplitter.kt")

    /**
     * Da chiamare alla scadenza dell'attesa ACK. Buffer = SOLO [06] o [06 03] ⇒ Parsed(Control(ACK, BARE)) e svuota;
     * buffer = SOLO [15] o [15 03] ⇒ Discarded(AMBIGUOUS_NAK) e svuota; altrimenti null.
     */
    fun flushBareControl(): SplitItem? = TODO("WP1: FrameSplitter.kt")

    /** Svuota e restituisce i byte pendenti (per log). */
    fun clear(): ByteArray = TODO("WP1: FrameSplitter.kt")
}
