package it.prime.protocollo17.ecr17.wire

import it.prime.protocollo17.ecr17.testing.Frames
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Resa esadecimale condivisa da log traffico, UI e test (§3.8).
 *
 * La forma DLL (minuscolo, spazio dopo ogni byte) deve combaciare con il log `iaedll` per poter confrontare le
 * tracce senza normalizzarle a mano; `parse` deve rifiutare un vettore scritto male invece di produrre in
 * silenzio un frame diverso.
 */
class HexTest {

    /** Maiuscolo senza spazio finale: la forma di UI e test. */
    @Test
    fun `spaced maiuscolo`() {
        assertEquals("02 30 7A", Hex.spaced(Hex.parse("02 30 7A")))
    }

    /** Stile DLL: minuscolo con spazio finale, come le righe del log iaedll. */
    @Test
    fun `spaced minuscolo con spazio finale stile DLL`() {
        assertEquals("06 03 7a ", Hex.spaced(Frames.ACKW, upper = false, trailingSpace = true))
        assertEquals("06 03 7a", Hex.spaced(Frames.ACKW, upper = false))
        assertEquals("06 03 7A ", Hex.spaced(Frames.ACKW, trailingSpace = true))
    }

    /** Byte alti senza problemi di segno, array vuoto senza separatori. */
    @Test
    fun `spaced con byte alti e array vuoto`() {
        assertEquals("FF 80 00 7F", Hex.spaced(byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x00, 0x7F)))
        assertEquals("", Hex.spaced(ByteArray(0)))
        assertEquals("", Hex.spaced(ByteArray(0), upper = false, trailingSpace = true))
    }

    /** parse ignora spazi bianchi e maiuscole/minuscole. */
    @Test
    fun `parse tollerante a spazi e maiuscole`() {
        val expected = byteArrayOf(0x06, 0x03, 0x7A)
        assertArrayEquals(expected, Hex.parse("06 03 7A"))
        assertArrayEquals(expected, Hex.parse("06 03 7a "))
        assertArrayEquals(expected, Hex.parse("06037a"))
        assertArrayEquals(expected, Hex.parse("  06\t03\r\n7A  "))
        assertArrayEquals(ByteArray(0), Hex.parse(""))
        assertArrayEquals(ByteArray(0), Hex.parse("   "))
    }

    /** Un vettore scritto male è un errore esplicito, non un frame diverso. */
    @Test
    fun `parse rifiuta caratteri non esadecimali e cifre dispari`() {
        assertThrows<IllegalArgumentException> { Hex.parse("0G") }
        assertThrows<IllegalArgumentException> { Hex.parse("06 03 7") }
        assertThrows<IllegalArgumentException> { Hex.parse("0x06") }
    }

    /** Round trip su tutti i 256 valori, in entrambe le forme. */
    @Test
    fun `round trip su tutti i byte`() {
        val all = ByteArray(256) { it.toByte() }
        assertArrayEquals(all, Hex.parse(Hex.spaced(all)))
        assertArrayEquals(all, Hex.parse(Hex.spaced(all, upper = false, trailingSpace = true)))
    }

    /** Controlli di frame per nome; ESC, 0x7F e byte alti come <xx> maiuscolo; ASCII stampabile così com'è. */
    @Test
    fun `printable con nomi dei controlli e byte non stampabili`() {
        assertEquals("<STX>AB<ETX><1B>", Hex.printable(Hex.parse("02 41 42 03 1B")))
        assertEquals(
            "<SOH>OK ~<EOT><ACK><NAK><7F><80><FF><00>",
            Hex.printable(Hex.parse("01 4F 4B 20 7E 04 06 15 7F 80 FF 00")),
        )
        assertEquals("", Hex.printable(ByteArray(0)))
    }
}
