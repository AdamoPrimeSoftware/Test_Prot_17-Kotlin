package it.prime.protocollo17.ecr17.message

import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.ecr17.testing.PosMockReceipts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Righe di scontrino 'S' (A§4.5.9): caratteri in banda 0x7D (a capo) e 0x7F/0x7E (doppia altezza), fine scontrino
 * con l'ESC finale (sentinella Nexi 7D×6 + 1B compresa), e mascheramento del PAN che alcuni terminali stampano in
 * chiaro (M9).
 *
 * Protegge la fine dello scontrino (senza `isEnd` la sessione non sa quando smettere di raccogliere righe, §3.21) e
 * il fatto che la coda di fine non diventi righe vuote nel testo mostrato.
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

    /** Solo l'ULTIMO byte conta: un ESC (anche dopo la coda Nexi) in mezzo alla riga non chiude lo scontrino. */
    @Test
    fun `ESC non in coda non chiude`() {
        val middle = ResponseParser.parseReceiptLine(p("123456780SA}}}}}}\u001BB"), maskPan = false)
        assertFalse(middle.isEnd)
        assertEquals("A\n\n\n\n\n\n\u001BB", middle.text)
    }

    /**
     * Prima la fine era solo la coda Nexi 7D×6 + 1B, e "}}" + ESC (l'ultima riga dei terminali Ingenico del 2025,
     * catture PosMock) non chiudeva nulla: la sessione aspettava tutto il drain e segnalava RECEIPT_END_MISSING. Ora
     * basta l'ESC finale, con qualsiasi numero di 0x7D prima.
     */
    @Test
    fun `ESC finale chiude con qualsiasi coda`() {
        val ingenico = ResponseParser.parseReceiptLine(p("100029120SCONTRATTO #12345        }}\u001B"), maskPan = false)
        assertTrue(ingenico.isEnd)
        assertEquals("CONTRATTO #12345", ingenico.text, "ESC, a capo e spazi finali tolti dal testo")
        assertEquals("CONTRATTO #12345        }}\u001B", ingenico.raw, "il grezzo resta com'è sul filo")

        val five = ResponseParser.parseReceiptLine(p("123456780SA}}}}}\u001B"), maskPan = false)
        assertTrue(five.isEnd)
        assertEquals("A", five.text)
    }

    /** L'ultima riga del formato a 24 colonne (Ingenico 2025) è solo avanzamento carta: sei "}" + 24 spazi, poi ESC. */
    @Test
    fun `riga finale di soli avanzamenti`() {
        val feed = "}" + " ".repeat(24)
        val line = ResponseParser.parseReceiptLine(p("100029120S" + feed.repeat(6) + "\u001B"), maskPan = false)
        assertTrue(line.isEnd)
        assertEquals("", line.text)
    }

    /** 0x7F imposta la doppia altezza ed è rimosso dal testo. */
    @Test
    fun `7F imposta doppia altezza`() {
        val line = ResponseParser.parseReceiptLine(p("123456780S\u007FTOTALE 1,00}"), maskPan = false)
        assertTrue(line.doubleHeight)
        assertEquals("TOTALE 1,00\n", line.text)
        assertEquals("\u007FTOTALE 1,00}", line.raw)
    }

    /**
     * Nel formato a righe da 24 colonne (Ingenico 2025, catture PosMock) 0x7E prende il posto del separatore 0x7D
     * della riga in doppia altezza: a capo + doppia altezza. Prima era solo tolto e le due righe si fondevano in una.
     */
    @Test
    fun `7E e' a capo e doppia altezza`() {
        val blank = " ".repeat(24)
        val line = ResponseParser.parseReceiptLine(p("100029120S}$blank~IMPORTO EUR         0,05}"), maskPan = false)
        assertTrue(line.doubleHeight)
        assertEquals("\n$blank\nIMPORTO EUR         0,05\n", line.text)
        assertEquals("}$blank~IMPORTO EUR         0,05}", line.raw)
    }

    /**
     * 0x7E separa due righe da 24 colonne come lo 0x7D, e un PAN non scavalca una riga: le cifre ai due lati non formano
     * una sequenza. Trattarlo come trasparente mascherava lo STAN della riga prima e spostava le "ultime 4" del PAN.
     */
    @Test
    fun `7E separa le righe anche nel mascheramento`() {
        val stan = ResponseParser.parseReceiptLine(p("100029120S}TML 10002912 STAN 000004~" + "*".repeat(24) + "}"), maskPan = true)
        assertTrue(stan.text.contains("STAN 000004\n"), stan.text)
        val pan = ResponseParser.parseReceiptLine(p("100029120S}PAN     ************1017~0,05 EUR               }"), maskPan = true)
        assertTrue(pan.text.contains("PAN     ************1017\n0,05 EUR"), pan.text)
    }

    /** M9: il PAN troncato con il BIN in chiaro che i terminali Ingenico stampano ("438746******9993") perde anche il BIN. */
    @Test
    fun `maskPan maschera il BIN del PAN troncato`() {
        val line = ResponseParser.parseReceiptLine(p("100029120SPAN     438746******9993SCAD                ****"), maskPan = true)
        assertEquals("PAN     ************9993SCAD                ****", line.text)
        assertEquals(line.text, line.raw)
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

    // ─── Righe S come pezzi di un flusso (A§9.11) ────────────────────────────────

    /**
     * Una riga piena (200 caratteri) che non chiude lo scontrino continua nella successiva: è il taglio per dimensione
     * dei due formati Ingenico (quello a colonne anche a metà campo). Le righe più corte, come quelle del simulatore,
     * e l'ultima riga restano a sé: la vista non le unisce alla successiva.
     */
    @Test
    fun `continuesInNext solo per le righe piene non finali`() {
        for (payload in listOf(PosMockReceipts.PAGAMENTI_83, PosMockReceipts.PAGAMENTI_117, PosMockReceipts.PAGAMENTI_123)) {
            assertTrue(ResponseParser.parseReceiptLine(p(payload)).continuesInNext, payload)
        }
        for (payload in listOf(PosMockReceipts.PAGAMENTI_95, PosMockReceipts.PAGAMENTI_126, Frames.RECEIPT, Frames.RECEIPT_END)) {
            assertFalse(ResponseParser.parseReceiptLine(p(payload)).continuesInNext, payload)
        }
        assertFalse(ResponseParser.parseReceiptLine(p("123456780S" + "X".repeat(199) + "\u001B")).continuesInNext, "piena ma finale")
        assertFalse(ResponseParser.parseReceiptLine(p("123456780S" + "X".repeat(199))).continuesInNext, "199 caratteri")
    }

    /**
     * M9 a cavallo dei frame: con la coda lasciata aperta dalla riga precedente (`carry`, tenuta dalla sessione) la testa
     * di questa riga è il seguito dello stesso PAN, e ne restano solo le ultime 4. Grezzo e testo derivano dallo stesso
     * payload mascherato, e la fine scontrino si riconosce anche dopo il mascheramento.
     */
    @Test
    fun `carry maschera la testa come seguito del PAN`() {
        val payload = p("123456780S11111111 FINE}}\u001B")
        val line = ResponseParser.parseReceiptLine(payload, maskPan = true, carry = 8)
        assertEquals("****1111 FINE}}\u001B", line.raw)
        assertEquals("****1111 FINE", line.text)
        assertTrue(line.isEnd)
        assertEquals("11111111 FINE", ResponseParser.parseReceiptLine(payload, maskPan = true).text, "senza coda: 8 cifre, sotto la soglia")
        assertEquals("11111111 FINE", ResponseParser.parseReceiptLine(payload, maskPan = false, carry = 8).text, "maskPan = false")
    }

    /** Riga piena con il PAN in coda (anche spezzato da 0x7F): può continuare, quindi tutte le cifre spariscono. */
    @Test
    fun `riga piena con il PAN in coda`() {
        val line = ResponseParser.parseReceiptLine(p("123456780S" + "X".repeat(183) + "41111111\u007F11111111"), maskPan = true)
        assertTrue(line.continuesInNext)
        assertEquals("X".repeat(183) + "********\u007F********", line.raw)
        assertEquals("X".repeat(183) + "*".repeat(16), line.text)
        assertTrue(line.doubleHeight)
    }
}
