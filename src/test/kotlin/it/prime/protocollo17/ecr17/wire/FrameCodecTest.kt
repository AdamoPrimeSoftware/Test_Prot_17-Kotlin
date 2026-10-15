package it.prime.protocollo17.ecr17.wire

import it.prime.protocollo17.ecr17.testing.Frames
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Codec di un singolo frame (§3.6) contro i vettori dei repo (A§9.3) e i frame reali dell'Ingenico (A§9.8).
 *
 * Il codec è volutamente permissivo su ACK/NAK (conta solo il primo byte): restringerlo a 1 byte farebbe
 * diventare UNKNOWN tutti gli ACK reali a 3 byte (lezione dei repo, A§3.5). I controlli stretti stanno nello
 * splitter e sono coperti da [FrameSplitterTest].
 */
class FrameCodecTest {

    /** Configurazione dei vettori dei repo (A§9.6). */
    private val std = FrameCodec(LrcMode.STD, ControlLrcRule.SAME_AS_APP)

    /** Configurazione dell'Ingenico reale (A§9.8): frame stx, controlli WIRE. */
    private val ingenico = FrameCodec(LrcMode.STX, ControlLrcRule.WIRE)

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    private fun assertUnknown(packet: DecodedPacket, label: String) {
        assertEquals(PacketType.UNKNOWN, packet.type, label)
        assertFalse(packet.validLrc, "$label: validLrc")
        assertEquals(0, packet.payload.size, "$label: payload vuoto")
    }

    // ─── Encode ──────────────────────────────────────────────────────────────

    /** encode AB in std (rust codec.rs:177-182). */
    @Test
    fun `encodeApplication di AB in std`() {
        assertArrayEquals(Hex.parse("02 41 42 03 7C"), std.encodeApplication(ascii("AB")))
    }

    /** encodeControl usa la regola configurata: SAME_AS_APP in std come i repo, WIRE come l'Ingenico. */
    @Test
    fun `encodeControl segue la regola del codec`() {
        assertArrayEquals(Hex.parse("06 03 79"), std.encodeControl(ControlChars.ACK))
        assertArrayEquals(Hex.parse("15 03 6A"), std.encodeControl(ControlChars.NAK))
        assertArrayEquals(Hex.parse("06 03 7A"), ingenico.encodeControl(ControlChars.ACK))
        assertArrayEquals(Hex.parse("15 03 69"), ingenico.encodeControl(ControlChars.NAK))
    }

    /** Frame [1] di A§9.8: pagamento da 0,01 € verso l'Ingenico, 170 byte, LRC stx 2F. */
    @Test
    fun `pagamento reale P1 ha 170 byte e LRC 2F`() {
        val frame = ingenico.encodeApplication(ascii(Frames.P1))
        assertEquals(170, frame.size)
        assertEquals(ControlChars.STX, frame[0].toInt() and 0xFF)
        assertEquals(ControlChars.ETX, frame[168].toInt() and 0xFF)
        assertEquals(0x2F, frame[169].toInt() and 0xFF)
    }

    /** Sonda `s` con TID zero in stx (A§3.6a): il frame esatto che l'Ingenico si aspetta. */
    @Test
    fun `status con TID zero in stx`() {
        assertArrayEquals(
            Hex.parse("02 30 30 30 30 30 30 30 30 30 73 03 3D"),
            ingenico.encodeApplication(ascii("000000000s")),
        )
    }

    // ─── Decode: frame applicativi ───────────────────────────────────────────

    /** Round trip in ogni modalità (rust codec.rs:184-199), con l'LRC atteso di A§9.1. */
    @Test
    fun `round trip in ogni modalita`() {
        val payload = ascii("123456780P0000065000")
        val expectedLrc = mapOf(LrcMode.STD to 0x14, LrcMode.STX to 0x15, LrcMode.NOEXT to 0x17, LrcMode.STX_NOEXT to 0x16)
        for ((mode, lrc) in expectedLrc) {
            val codec = FrameCodec(mode, ControlLrcRule.WIRE)
            val frame = codec.encodeApplication(payload)
            assertEquals(lrc, frame.last().toInt() and 0xFF, "LRC in $mode")
            val decoded = codec.decode(frame)
            assertEquals(PacketType.APPLICATION, decoded.type, "tipo in $mode")
            assertArrayEquals(payload, decoded.payload, "payload in $mode")
            assertTrue(decoded.validLrc, "validLrc in $mode")
        }
    }

    /** Un LRC corrotto resta APPLICATION con validLrc=false: decide il chiamante (NAK), non il codec. */
    @Test
    fun `LRC corrotto resta APPLICATION non valida`() {
        val frame = std.encodeApplication(ascii("HELLO"))
        assertArrayEquals(Hex.parse("02 48 45 4C 4C 4F 03 3D"), frame)
        frame[frame.size - 1] = (frame.last().toInt() xor 0xFF).toByte()
        assertEquals(0xC2, frame.last().toInt() and 0xFF)

        val decoded = std.decode(frame)
        assertEquals(PacketType.APPLICATION, decoded.type)
        assertArrayEquals(ascii("HELLO"), decoded.payload)
        assertFalse(decoded.validLrc)
    }

    /** Frame [37] di A§9.8 (R con pos 11 = 0x00): valido solo con la modalità stx dell'Ingenico. */
    @Test
    fun `ristampa reale R37 valida in stx e non in std`() {
        val frame = Hex.parse("02 30 30 30 30 30 30 30 30 30 52 00 30 30 30 30 30 30 30 30 30 30 30 03 2C")

        val inStx = ingenico.decode(frame)
        assertEquals(PacketType.APPLICATION, inStx.type)
        assertEquals(22, inStx.payload.size)
        assertEquals('R'.code, inStx.payload[9].toInt() and 0xFF)
        assertTrue(inStx.validLrc, "LRC 2C valido in stx")

        val inStd = std.decode(frame)
        assertEquals(PacketType.APPLICATION, inStd.type)
        assertFalse(inStd.validLrc, "LRC 2C non valido in std (std = 2D)")
    }

    /** Esito reale [4] di A§9.8 costruito con LrcMode.compute: il decoder lo riconosce in stx. */
    @Test
    fun `esito reale E4 valido in stx`() {
        val decoded = ingenico.decode(Frames.app(Frames.E4))
        assertEquals(PacketType.APPLICATION, decoded.type)
        assertArrayEquals(ascii(Frames.E4), decoded.payload)
        assertTrue(decoded.validLrc)
    }

    // ─── Decode: controlli e progress ────────────────────────────────────────

    /** ACK e NAK nudi a 1 byte (rust codec.rs:221-233). */
    @Test
    fun `ACK e NAK nudi`() {
        val ack = std.decode(Hex.parse("06"))
        assertEquals(PacketType.ACK, ack.type)
        assertTrue(ack.validLrc)
        val nak = std.decode(Hex.parse("15"))
        assertEquals(PacketType.NAK, nak.type)
        assertTrue(nak.validLrc)
    }

    /** decode(encodeControl(...)) in ogni regola e modalità (rust codec.rs:239-250). */
    @Test
    fun `decode di encodeControl`() {
        for (rule in ControlLrcRule.entries) {
            for (mode in LrcMode.entries) {
                val codec = FrameCodec(mode, rule)
                assertEquals(PacketType.ACK, codec.decode(codec.encodeControl(ControlChars.ACK)).type, "ACK $rule/$mode")
                assertEquals(PacketType.NAK, codec.decode(codec.encodeControl(ControlChars.NAK)).type, "NAK $rule/$mode")
            }
        }
    }

    /** Tolleranza sul lead: conta solo il primo byte, la coda è ignorata (rust codec.rs:255-267, A§3.5). */
    @Test
    fun `ACK e NAK con coda qualsiasi`() {
        assertEquals(PacketType.ACK, std.decode(Hex.parse("06 00")).type)
        assertEquals(PacketType.ACK, std.decode(Hex.parse("06 03 FF")).type)
        assertEquals(PacketType.NAK, std.decode(Hex.parse("15 99 42")).type)
        assertEquals(PacketType.ACK, ingenico.decode(Frames.ACKW).type, "ACK reale 06 03 7A in stx")
    }

    /** Progress di 20 caratteri: payload = byte tra SOH ed EOT (rust codec.rs:284-294). */
    @Test
    fun `progress ELABORAZIONE`() {
        val text = "ELABORAZIONE...     "
        assertEquals(20, text.length)
        val decoded = std.decode(byteArrayOf(0x01) + ascii(text) + byteArrayOf(0x04))
        assertEquals(PacketType.PROGRESS, decoded.type)
        assertArrayEquals(ascii(text), decoded.payload)
    }

    /** Progress reale dell'Ingenico (A§9.8 [3]). */
    @Test
    fun `progress reale OPERAZIONE IN CORSO`() {
        val decoded = ingenico.decode(Frames.PRG)
        assertEquals(PacketType.PROGRESS, decoded.type)
        assertEquals("OPERAZIONE IN CORSO ", String(decoded.payload, Charsets.ISO_8859_1))
    }

    // ─── Decode: casi UNKNOWN ────────────────────────────────────────────────

    /** Tutte le forme malformate di A§9.3: mai un'eccezione, sempre UNKNOWN con validLrc=false. */
    @Test
    fun `forme malformate diventano UNKNOWN`() {
        assertUnknown(std.decode(ByteArray(0)), "vuoto")
        assertUnknown(std.decode(Hex.parse("01")), "SOH isolato")
        assertUnknown(std.decode(Hex.parse("02 41 42")), "STX senza ETX")
        assertUnknown(std.decode(Hex.parse("02 41 03")), "STX ETX senza LRC")
        assertUnknown(std.decode(Hex.parse("99 00")), "lead sconosciuto")
        assertUnknown(std.decode(Hex.parse("02 41 42 03 7C 00")), "byte dopo l'LRC")
        assertUnknown(std.decode(Hex.parse("02 41 42 03 7C 02 43 44 03 78")), "frame concatenati")
        assertUnknown(
            std.decode(byteArrayOf(0x01) + ascii("ELABORAZIONE...     ") + byteArrayOf(0xFF.toByte())),
            "SOH senza EOT",
        )
    }

    /** Il payload restituito è una copia: modificarlo non altera il frame di partenza. */
    @Test
    fun `il payload decodificato e una copia`() {
        val frame = std.encodeApplication(ascii("AB"))
        val decoded = std.decode(frame)
        decoded.payload[0] = 0x5A
        assertArrayEquals(Hex.parse("02 41 42 03 7C"), frame)
    }
}
