package it.prime.protocollo17.ecr17.wire

import it.prime.protocollo17.ecr17.testing.Frames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Vettori LRC dei frame applicativi nelle 4 modalità (A§9.1, A§3.6a, L§13.4, L§13.7).
 *
 * Protegge la semantica dei due booleani: prima un commit dei repo aveva invertito il significato di `noext`
 * ("il terminale NAKa tutto", A§3.3); ora ogni modalità è fissata da vettori indipendenti e dalle relazioni
 * XOR con `std`, così un'inversione di `foldStx`/`foldEtx` fa fallire più test insieme.
 */
class LrcModeTest {

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun hex(value: Int): String = "%02X".format(value)

    /** Confronta le 4 modalità in un colpo solo, con un messaggio che mostra tutti i valori in hex. */
    private fun assertModes(payload: ByteArray, std: Int, stx: Int, noext: Int, stxNoext: Int) {
        val expected = listOf(std, stx, noext, stxNoext).map(::hex)
        val actual = listOf(LrcMode.STD, LrcMode.STX, LrcMode.NOEXT, LrcMode.STX_NOEXT).map { hex(it.compute(payload)) }
        assertEquals(expected, actual, "LRC std/stx/noext/stx_noext del payload ${Hex.spaced(payload)}")
    }

    // ─── Vettori ─────────────────────────────────────────────────────────────

    /** Payload vuoto: resta solo la base 0x7F con gli eventuali STX/ETX ripiegati (A§9.1, derivato). */
    @Test
    fun `stringa vuota nelle 4 modalita`() {
        assertModes(ByteArray(0), std = 0x7F, stx = 0x7E, noext = 0x7C, stxNoext = 0x7D)
    }

    /** "A" dai test dei repo (rust lrc.rs:94-101). */
    @Test
    fun `carattere A nelle 4 modalita`() {
        assertModes(ascii("A"), std = 0x3E, stx = 0x3F, noext = 0x3D, stxNoext = 0x3C)
    }

    /** "AB" derivato (A§9.1). */
    @Test
    fun `stringa AB nelle 4 modalita`() {
        assertModes(ascii("AB"), std = 0x7C, stx = 0x7D, noext = 0x7F, stxNoext = 0x7E)
    }

    /** Byte di riferimento con 0x7F, 0xAA e 0x10: nessun problema di segno nei byte alti. */
    @Test
    fun `vettore di riferimento con byte alti`() {
        val payload = Hex.parse("00 7F 55 AA 5A 10")
        assertModes(payload, std = 0xB5, stx = 0xB4, noext = 0xB6, stxNoext = 0xB7)

        // Funzione di riferimento scritta in modo diverso (fold su Int non mascherati): stesso risultato.
        for (mode in LrcMode.entries) {
            var reference = 0x7F
            if (mode.foldStx) reference = reference xor 0x02
            reference = payload.fold(reference) { acc, b -> acc xor (b.toInt() and 0xFF) }
            if (mode.foldEtx) reference = reference xor 0x03
            assertEquals(reference, mode.compute(payload), "riferimento in $mode")
        }
    }

    /** "12345678P0" (rust lrc.rs:116-130). */
    @Test
    fun `payload 12345678P0`() {
        assertModes(ascii("12345678P0"), std = 0x17, stx = 0x16, noext = 0x14, stxNoext = 0x15)
    }

    /** "123456780P0000065000" (rn test_packet_codec.cpp:34-43). */
    @Test
    fun `payload 123456780P0000065000`() {
        assertModes(ascii("123456780P0000065000"), std = 0x14, stx = 0x15, noext = 0x17, stxNoext = 0x16)
    }

    /** Status con TID 12345678: il calcolo passo passo di A§3.6a. */
    @Test
    fun `status 123456780s come nel calcolo passo passo`() {
        assertModes(ascii("123456780s"), std = 0x34, stx = 0x35, noext = 0x37, stxNoext = 0x36)
    }

    /** Sonda LRC con TID zero (L§13.4): l'Ingenico reale si aspetta 3D, cioè stx. */
    @Test
    fun `sonda status con TID zero`() {
        assertModes(ascii("000000000s"), std = 0x3C, stx = 0x3D, noext = 0x3F, stxNoext = 0x3E)
    }

    /** Il frame P reale [1] di A§9.8 torna solo in stx (2F). */
    @Test
    fun `pagamento reale Ingenico ha LRC 2F solo in stx`() {
        assertModes(
            payload = Frames.P1.toByteArray(Charsets.ISO_8859_1),
            std = 0x2E, stx = 0x2F, noext = 0x2D, stxNoext = 0x2C,
        )
    }

    /** from/to delimitano esattamente la porzione ripiegata: nessun byte fuori intervallo entra nello XOR. */
    @Test
    fun `compute su un intervallo equivale a compute sulla sola porzione`() {
        val frame = Frames.app("123456780s")             // STX payload ETX LRC
        for (mode in LrcMode.entries) {
            assertEquals(
                mode.compute(ascii("123456780s")),
                mode.compute(frame, from = 1, to = frame.size - 2),
                "intervallo [1, size-2) in $mode",
            )
        }
    }

    // ─── Proprietà ───────────────────────────────────────────────────────────

    /** Relazioni XOR con std su payload casuali (seme fisso): stx = std^01, noext = std^03, stx_noext = std^02. */
    @Test
    fun `relazioni xor con std su payload casuali`() {
        val random = Random(17)
        repeat(500) {
            val payload = random.nextBytes(random.nextInt(0, 200))
            val std = LrcMode.STD.compute(payload)
            assertEquals(std xor 0x01, LrcMode.STX.compute(payload), "stx = std^01 su ${Hex.spaced(payload)}")
            assertEquals(std xor 0x03, LrcMode.NOEXT.compute(payload), "noext = std^03 su ${Hex.spaced(payload)}")
            assertEquals(std xor 0x02, LrcMode.STX_NOEXT.compute(payload), "stx_noext = std^02 su ${Hex.spaced(payload)}")
            for (mode in LrcMode.entries) {
                val lrc = mode.compute(payload)
                assertEquals(lrc, lrc and 0xFF, "risultato sempre 0..255 in $mode")
            }
        }
    }

    // ─── Nomi serializzati ───────────────────────────────────────────────────

    /** I valori serializzati restano quelli dei repo (A§3.3): i file di configurazione esistenti si rileggono. */
    @Test
    fun `wireName coincide con i valori dei repo`() {
        assertEquals(
            listOf("std", "stx", "noext", "stx_noext"),
            listOf(LrcMode.STD, LrcMode.STX, LrcMode.NOEXT, LrcMode.STX_NOEXT).map { it.wireName },
        )
        for (mode in LrcMode.entries) {
            assertEquals(mode, LrcMode.fromWireName(mode.wireName), "round trip di ${mode.wireName}")
        }
    }

    /**
     * Prima laravel ripiegava in silenzio su `std` ogni valore sconosciuto (L§13.7): un refuso diventava un
     * terminale che NAKa tutto. Ora il parsing è rigoroso: maiuscole e spazi ammessi, tutto il resto è null.
     */
    @Test
    fun `fromWireName rigoroso senza fallback su STD`() {
        assertEquals(LrcMode.STX, LrcMode.fromWireName("STX"))
        assertEquals(LrcMode.STX, LrcMode.fromWireName(" stx "))
        assertEquals(LrcMode.STX_NOEXT, LrcMode.fromWireName("Stx_NoExt"))
        assertNull(LrcMode.fromWireName("sxt"))
        assertNull(LrcMode.fromWireName("stx-noext"))
        assertNull(LrcMode.fromWireName(""))
        assertNull(LrcMode.fromWireName("   "))
    }
}
