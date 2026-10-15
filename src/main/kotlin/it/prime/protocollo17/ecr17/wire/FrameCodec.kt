package it.prime.protocollo17.ecr17.wire

// STUB di WP0 (§8.2): firme definitive di §3.6, corpi a carico di WP1.

/** Classificazione permissiva di un singolo frame (semantica dei repo, A§3.5 livello 2). */
enum class PacketType { ACK, NAK, APPLICATION, PROGRESS, UNKNOWN }

/** Esito di [FrameCodec.decode]: tipo, payload (vuoto per ACK/NAK) e validità dell'LRC applicativo. */
class DecodedPacket(val type: PacketType, val payload: ByteArray, val validLrc: Boolean)

/**
 * Codec di un **singolo** frame, con la semantica permissiva dei repo (A§3.5 livello 2).
 * Serve ai test vector di A§9.3 e al simulatore; la sessione usa invece lo splitter, che regge
 * stream spezzati e frame concatenati.
 */
class FrameCodec(val appMode: LrcMode, val controlRule: ControlLrcRule) {
    /** STX + payload + ETX + appMode.compute(payload). Non valida il contenuto (lo fa RequestEncoder). */
    fun encodeApplication(payload: ByteArray): ByteArray = TODO("WP1: FrameCodec.kt")

    /** [ctrl, ETX, controlRule.lrcFor(ctrl, appMode)]. */
    fun encodeControl(ctrl: Int): ByteArray = TODO("WP1: FrameCodec.kt")

    /**
     * Decodifica permissiva: primo byte 06/15 ⇒ ACK/NAK (validLrc=true, coda ignorata);
     * 01…04 ⇒ PROGRESS; 02 + primo 03 all'indice i con i+2 == size ⇒ APPLICATION (validLrc confrontato
     * con appMode, anche false); tutto il resto (vuoto, troncato, byte in coda, concatenati) ⇒ UNKNOWN.
     */
    fun decode(frame: ByteArray): DecodedPacket = TODO("WP1: FrameCodec.kt")
}
