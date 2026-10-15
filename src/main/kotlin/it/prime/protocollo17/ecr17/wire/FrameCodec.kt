package it.prime.protocollo17.ecr17.wire

/** Classificazione permissiva di un singolo frame (semantica dei repo, A§3.5 livello 2). */
enum class PacketType { ACK, NAK, APPLICATION, PROGRESS, UNKNOWN }

/** Esito di [FrameCodec.decode]: tipo, payload (vuoto per ACK/NAK) e validità dell'LRC applicativo. */
class DecodedPacket(val type: PacketType, val payload: ByteArray, val validLrc: Boolean)

/**
 * Codec di un **singolo** frame, con la semantica permissiva dei repo (A§3.5 livello 2).
 * Serve ai test vector di A§9.3 e al simulatore; la sessione usa invece lo splitter, che regge
 * stream spezzati e frame concatenati.
 *
 * Perché due livelli: il decoder riceve un frame già ritagliato e deve solo dire "che cos'è". I controlli
 * stretti (ETX obbligatorio nei frame di controllo, LRC dell'ACK nelle due convenzioni, asimmetria del NAK)
 * stanno nel [FrameSplitter]. Restringere qui ACK/NAK a 1 byte farebbe diventare `UNKNOWN` tutti gli ACK reali
 * a 3 byte (lezione dei repo, A§3.5).
 */
class FrameCodec(val appMode: LrcMode, val controlRule: ControlLrcRule) {

    /** STX + payload + ETX + appMode.compute(payload). Non valida il contenuto (lo fa RequestEncoder). */
    fun encodeApplication(payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 3)
        out[0] = ControlChars.STX.toByte()
        payload.copyInto(out, destinationOffset = 1)
        out[payload.size + 1] = ControlChars.ETX.toByte()
        out[payload.size + 2] = appMode.compute(payload).toByte()
        return out
    }

    /** [ctrl, ETX, controlRule.lrcFor(ctrl, appMode)]. */
    fun encodeControl(ctrl: Int): ByteArray = controlRule.encode(ctrl, appMode)

    /**
     * Decodifica permissiva: primo byte 06/15 ⇒ ACK/NAK (validLrc=true, coda ignorata);
     * 01…04 ⇒ PROGRESS; 02 + primo 03 all'indice i con i+2 == size ⇒ APPLICATION (validLrc confrontato
     * con appMode, anche false); tutto il resto (vuoto, troncato, byte in coda, concatenati) ⇒ UNKNOWN.
     *
     * Il payload restituito è sempre una copia: per ACK/NAK e UNKNOWN è vuoto, per PROGRESS sono i byte tra
     * SOH ed EOT, per APPLICATION i byte tra STX e il primo ETX.
     */
    fun decode(frame: ByteArray): DecodedPacket {
        if (frame.isEmpty()) return unknown()
        return when (frame[0].toInt() and 0xFF) {
            ControlChars.ACK -> DecodedPacket(PacketType.ACK, EMPTY, validLrc = true)
            ControlChars.NAK -> DecodedPacket(PacketType.NAK, EMPTY, validLrc = true)
            ControlChars.SOH -> decodeProgress(frame)
            ControlChars.STX -> decodeApplication(frame)
            else -> unknown()
        }
    }

    // ─── Dettagli ────────────────────────────────────────────────────────────

    /** SOH … EOT: nessun LRC; la lunghezza del testo non è verificata (il progress Ingenico ne ha 20). */
    private fun decodeProgress(frame: ByteArray): DecodedPacket {
        if (frame.size < 2 || (frame[frame.size - 1].toInt() and 0xFF) != ControlChars.EOT) return unknown()
        return DecodedPacket(PacketType.PROGRESS, frame.copyOfRange(1, frame.size - 1), validLrc = true)
    }

    /**
     * STX payload ETX LRC: il payload finisce al PRIMO ETX e dopo l'ETX deve esserci esattamente un byte (l'LRC).
     * LRC mancante, byte in coda o due frame concatenati ⇒ UNKNOWN: il codec non sceglie per conto del chiamante.
     */
    private fun decodeApplication(frame: ByteArray): DecodedPacket {
        var etx = -1
        for (i in 1 until frame.size) {
            if ((frame[i].toInt() and 0xFF) == ControlChars.ETX) {
                etx = i
                break
            }
        }
        if (etx < 0 || etx + 2 != frame.size) return unknown()
        val payload = frame.copyOfRange(1, etx)
        val received = frame[etx + 1].toInt() and 0xFF
        return DecodedPacket(PacketType.APPLICATION, payload, validLrc = received == appMode.compute(payload))
    }

    private fun unknown() = DecodedPacket(PacketType.UNKNOWN, EMPTY, validLrc = false)

    private companion object {
        /** Mai esposto direttamente: [DecodedPacket.payload] di ACK/NAK/UNKNOWN è sempre un array nuovo. */
        val EMPTY: ByteArray get() = ByteArray(0)
    }
}
