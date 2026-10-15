package it.prime.protocollo17.ecr17.message

import it.prime.protocollo17.ecr17.testing.Frames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Righe di scontrino 'S' (A§4.5.9): caratteri in banda 0x7D (a capo) e 0x7F (doppia altezza), sentinella di fine
 * scontrino 7D×6 + 1B, e mascheramento del PAN che alcuni terminali stampano in chiaro (M9).
 *
 * Protegge la fine dello scontrino (senza `isEnd` la sessione non sa quando smettere di raccogliere righe, §3.21) e
 * il fatto che la sentinella non diventi sei righe vuote nel testo mostrato.
 */
class ReceiptLineTest {

    private fun p(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    /** A§9.6: riga semplice, testo dalla pos 11. */
    @Test
    fun `riga semplice dalla posizione 11`() {
        val line = ResponseParser.parseReceiptLine(p(Frames.RECEIPT), maskPan = false)
        assertEquals("LINE 1", line.raw)
        assertEquals("LINE 1", line.text)
        assertFalse(line.doubleHeight)
        assertFalse(line.isEnd)
    }

    /** 0x7D ⇒ '\n' nel testo, grezzo invariato. */
    @Test
    fun `7D diventa a capo`() {
        val line = ResponseParser.parseReceiptLine(p("123456780SRIGA}SECONDA"), maskPan = false)
        assertEquals("RIGA}SECONDA", line.raw)
        assertEquals("RIGA\nSECONDA", line.text)
        assertFalse(line.isEnd)
    }

    /** La sentinella 7D×6 + 1B segna la fine ed è rimossa dal testo (non sei righe vuote). */
    @Test
    fun `sentinella di fine scontrino`() {
        val line = ResponseParser.parseReceiptLine(p(Frames.RECEIPT_END), maskPan = false)
        assertTrue(line.isEnd)
        assertEquals("FINE", line.text)
        assertEquals("FINE}}}}}}\u001B", line.raw, "il grezzo conserva la sentinella")
    }

    /** Solo la CODA conta: la sentinella in mezzo alla riga non chiude lo scontrino; cinque 7D non bastano. */
    @Test
    fun `sentinella non in coda o incompleta non chiude`() {
        val middle = ResponseParser.parseReceiptLine(p("123456780SA}}}}}}\u001BB"), maskPan = false)
        assertFalse(middle.isEnd)
        assertEquals("A\n\n\n\n\n\n\u001BB", middle.text)

        val five = ResponseParser.parseReceiptLine(p("123456780SA}}}}}\u001B"), maskPan = false)
        assertFalse(five.isEnd)
    }

    /** 0x7F imposta la doppia altezza ed è rimosso dal testo. */
    @Test
    fun `7F imposta doppia altezza`() {
        val line = ResponseParser.parseReceiptLine(p("123456780S\u007FTOTALE 1,00}"), maskPan = false)
        assertTrue(line.doubleHeight)
        assertEquals("TOTALE 1,00\n", line.text)
        assertEquals("\u007FTOTALE 1,00}", line.raw)
    }

    /** Un payload senza testo (o più corto dell'intestazione) non lancia. */
    @Test
    fun `riga vuota o corta`() {
        val empty = ResponseParser.parseReceiptLine(p("123456780S"), maskPan = false)
        assertEquals("", empty.raw)
        assertEquals("", empty.text)
        assertFalse(empty.isEnd)
        assertEquals("", ResponseParser.parseReceiptLine(p("12"), maskPan = false).text)
    }

    // ─── maskPan = true (richiede PanMasker di WP1) ─────────────────────────────

    /** M9: un PAN stampato in chiaro nella riga è mascherato sia nel grezzo sia nel testo. */
    @Test
    fun `maskPan maschera il PAN in raw e text`() {
        val line = ResponseParser.parseReceiptLine(p(Frames.RECEIPT_PAN))
        assertFalse(line.raw.contains("4111111111111111"), line.raw)
        assertFalse(line.text.contains("4111111111111111"), line.text)
        assertEquals("CARTA ************1111", line.text)
        assertEquals("CARTA ************1111", line.raw)
    }

    /**
     * M9: un PAN spezzato da 0x7F nel grezzo torna intero nel testo, e lì va mascherato. Anche il grezzo (che esce
     * dal parser verso trace, eventi e UI) va mascherato: le due metà da 8 cifre, da sole, non raggiungono la soglia
     * di 13 cifre di maskDigitRuns, quindi lo 0x7F va ignorato. Lo 0x7F resta al suo posto nel grezzo.
     */
    @Test
    fun `maskPan maschera il PAN ricomposto nel testo`() {
        val line = ResponseParser.parseReceiptLine(p("123456780SPAN 41111111\u007F11111111"), maskPan = true)
        assertTrue(line.doubleHeight)
        assertFalse(line.text.contains("4111111111111111"), line.text)
        assertTrue(line.text.endsWith("1111"))
        assertFalse(line.raw.filter { it != '\u007F' }.contains("4111111111111111"), line.raw)
        assertFalse(line.raw.contains("41111111"), "nessuna delle due metà resta in chiaro: ${line.raw}")
        assertTrue(line.raw.contains('\u007F'), "il grezzo conserva lo 0x7F")
        assertEquals("PAN ********\u007F****1111", line.raw)
        assertEquals("PAN ************1111", line.text)
    }
}
