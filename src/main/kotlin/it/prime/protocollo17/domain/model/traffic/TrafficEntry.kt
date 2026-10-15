package it.prime.protocollo17.domain.model.traffic

/** Tipo di voce del log traffico: frame sul filo, scarti, oppure eventi di diagnostica dello scambio. */
enum class TrafficKind { TX, RX, DISCARDED, LINK, PHASE, PROGRESS, RECEIPT, WARNING, COMMAND }

/** Tono della voce nella finestra Log (badge e colore). */
enum class TrafficSeverity { NORMAL, INFO, WARNING, ERROR }

/**
 * Voce della finestra Log traffico, già pronta da mostrare.
 *
 * È un modello di dominio e non un ProtocolEvent perché la presentation non può importare i tipi di
 * `ecr17.wire` (§2.10) e non deve rifare la formattazione: hex, resa ASCII e riepilogo italiano sono calcolati
 * una sola volta dal registratore, fuori dall'EDT.
 */
data class TrafficEntry(
    val id: Long,                 // = ProtocolEvent.seq
    val at: Long,
    val exchangeId: Long?,
    val kind: TrafficKind,
    val command: Char?,           // lettera del comando dello scambio, se nota
    val hex: String?,             // "02 30 30 …" maiuscolo (solo TX/RX/DISCARDED), PAN già mascherato
    val ascii: String?,           // Hex.printable
    val summary: String,          // italiano, es. "ACK (LRC 7A, WIRE)", "Esito 'E' atteso, LRC 21 ok (stx)"
    val severity: TrafficSeverity,
)
