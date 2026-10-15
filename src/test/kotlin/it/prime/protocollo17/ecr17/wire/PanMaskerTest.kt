package it.prime.protocollo17.ecr17.wire

import it.prime.protocollo17.ecr17.testing.Frames
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Mascheramento del PAN (§3.8, invariante M9, A§11 punto 17).
 *
 * L'Ingenico maschera già il PAN, ma altri terminali no (Nexi, A§9.9: 19 cifre in chiaro). Due regressioni da
 * evitare in direzioni opposte: un PAN in chiaro nel log traffico o nel journal (anche in una risposta `i` KO,
 * che non ha ramo KO, o in una riga di scontrino), e il mascheramento delle lunghe sequenze di cifre che NON
 * sono un PAN (totali di `T`/`C`, data/ora di `s`, STAN e online id) e che servono al confronto con lo scontrino.
 */
class PanMaskerTest {

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    private fun maskPayload(payload: String): String = text(PanMasker.maskApplicationPayload(ascii(payload)))

    private val longDigitRun = Regex("[0-9]{13,}")

    /** PAN in chiaro dei vettori sintetici (L§13.6). */
    private val clearPanField = "0000000004111111111"
    private val maskedPanField = "***************1111"

    // ─── maskForDisplay / maskFixedWidth ─────────────────────────────────────

    /** Vista UI con la semantica rust maskPan (A§9.10): solo cifre, ultime 4 visibili. */
    @Test
    fun `maskForDisplay come rust maskPan`() {
        assertEquals("************1111", PanMasker.maskForDisplay("4111111111111111"))
        assertEquals("****", PanMasker.maskForDisplay("123"))
        assertEquals("******0114", PanMasker.maskForDisplay("492917******0114"))
        assertEquals("****", PanMasker.maskForDisplay(""))
        assertEquals("****", PanMasker.maskForDisplay("1234"))
        assertEquals("*2345", PanMasker.maskForDisplay("12345"))
        assertEquals("************1111", PanMasker.maskForDisplay("4111 1111 1111 1111"))
    }

    /** Larghezza fissa: le posizioni del campo restano valide, gli altri caratteri restano. */
    @Test
    fun `maskFixedWidth lascia le ultime 4 cifre e i non numerici`() {
        assertEquals("***************4768", PanMasker.maskFixedWidth("000558278******4768"))
        assertEquals(maskedPanField, PanMasker.maskFixedWidth(clearPanField))
        assertEquals("1234", PanMasker.maskFixedWidth("1234"))
        assertEquals("AB-12", PanMasker.maskFixedWidth("AB-12"))
        assertEquals("****-****-1111 ", PanMasker.maskFixedWidth("4111-1111-1111 "))
        assertEquals("", PanMasker.maskFixedWidth(""))
    }

    // ─── maskApplicationPayload ──────────────────────────────────────────────

    /** Esito E approvato: mascherato SOLO il campo PAN (pos 13-31); STAN, online e acquirer restano leggibili. */
    @Test
    fun `E approvato maschera solo le posizioni 13-31`() {
        val masked = maskPayload(Frames.E22_CLEAR)
        assertEquals(Frames.E22_CLEAR.length, masked.length)
        assertEquals(Frames.E22_CLEAR.substring(0, 12), masked.substring(0, 12), "intestazione ed esito invariati")
        assertEquals("***************1111", masked.substring(12, 31), "PAN mascherato")
        assertEquals(Frames.E22_CLEAR.substring(31), masked.substring(31), "coda (auth, data, acquirer, STAN, online) invariata")
        assertFalse("4111111111111111" in masked)
    }

    /** Esito KO ("01"): in pos 13 c'è la descrizione, non il PAN. Payload invariato. */
    @Test
    fun `E KO invariato`() {
        assertEquals(Frames.E4, maskPayload(Frames.E4))
        val preAuthKo = "123456780e01NEGATO                  10000000000300000000000000000000000ACQ        000055000066"
        assertEquals(preAuthKo, maskPayload(preAuthKo), "e KO invariato anche con 35 cifre consecutive")
    }

    /** e, c, v approvati: PAN mascherato come per E (A§4.5.3, A§4.5.5). */
    @Test
    fun `e c v approvati mascherati`() {
        val preAuth = "123456780e00${clearPanField}CLIAUTH01000500000000001230002111520"
        val closure = "123456780c00${clearPanField}ICCABC12321115202ACQ        000042000099000"
        val closureDcc = "123456780v00${clearPanField}ICCABC12321115202ACQ        000042000099" +
            "000" + "1" + "00012345" + "USD" + "000000000650" + "2" + "0000000000"
        val paymentDcc = "123456780V00${clearPanField}ICCABC12321115202ACQ        00004200009900000000650100012345USD0000000006502"
        for (payload in listOf(preAuth, closure, closureDcc, paymentDcc)) {
            val masked = maskPayload(payload)
            assertEquals(payload.length, masked.length, "lunghezza di ${payload[9]}")
            assertEquals(maskedPanField, masked.substring(12, 31), "PAN mascherato in ${payload[9]}")
            assertEquals(payload.substring(31), masked.substring(31), "coda invariata in ${payload[9]}")
        }
    }

    /**
     * La risposta `i` (75 B) non ha ramo KO (A§4.5.4): il PAN in pos 13/19 va mascherato anche con esito "01".
     * Prima (regola "solo se diverso da 01" applicata a tutti gli esiti) il PAN di un `i` KO finiva in chiaro.
     */
    @Test
    fun `i con esito 01 mascherato comunque`() {
        val masked = maskPayload(Frames.I75_KO_CLEAR)
        assertEquals(maskedPanField, masked.substring(12, 31))
        assertEquals(Frames.I75_KO_CLEAR.substring(31), masked.substring(31))
        assertFalse("4111111111" in masked)
    }

    /** Riga di scontrino S con il PAN in chiaro: rete di sicurezza dalla pos 11 (TID intatto). */
    @Test
    fun `riga S con PAN in chiaro`() {
        val masked = maskPayload(Frames.RECEIPT_PAN)
        assertFalse("4111111111111111" in masked)
        assertEquals("123456780SCARTA ************1111", masked)
        assertEquals(Frames.RECEIPT, maskPayload(Frames.RECEIPT), "riga senza PAN invariata")
    }

    /** s, T, C non hanno PAN: le loro lunghe sequenze di cifre (data/ora, totali) restano intatte. */
    @Test
    fun `s T C invariati`() {
        val status = "123456780s000000000001022515302V1.2.3"
        val closePositive = "123456780C0000000000000010000000000000001000"
        for (payload in listOf(status, Frames.T34, closePositive)) {
            assertTrue(longDigitRun.containsMatchIn(payload.substring(10)), "il vettore deve avere 13+ cifre consecutive")
            assertEquals(payload, maskPayload(payload), "${payload[9]} invariato")
        }
    }

    /** Codici fuori dalle risposte v1 (U, K, sconosciuti): rete di sicurezza dalla pos 11. */
    @Test
    fun `codici inattesi passano dalla rete di sicurezza`() {
        assertEquals(Frames.U34, maskPayload(Frames.U34), "U34 non ha sequenze di 13+ cifre")
        assertEquals("123456780U************1111", maskPayload("123456780U4111111111111111"))
        assertEquals("123456780K**************1111", maskPayload("123456780K004111111111111111"))
        assertEquals("123456780?************1111", maskPayload("123456780?4111111111111111"))
    }

    /** Payload corti o troncati: nessuna eccezione; il pezzo di PAN presente viene comunque mascherato. */
    @Test
    fun `payload corti e troncati`() {
        assertEquals("", maskPayload(""))
        assertEquals("12345", maskPayload("12345"))
        assertEquals("123456780E", maskPayload("123456780E"))
        assertEquals("123456780E0", maskPayload("123456780E0"))
        assertEquals("123456780E00******1111", maskPayload("123456780E000004111111"))
    }

    /** Il risultato è sempre una copia: il payload del parser non viene mai modificato. */
    @Test
    fun `maskApplicationPayload restituisce una copia`() {
        for (payload in listOf(Frames.E22_CLEAR, Frames.T34, Frames.RECEIPT_PAN)) {
            val input = ascii(payload)
            val output = PanMasker.maskApplicationPayload(input)
            assertNotSame(input, output)
            assertEquals(payload, text(input), "input invariato per ${payload[9]}")
        }
    }

    // ─── maskFrame ───────────────────────────────────────────────────────────

    /** Frame completo: payload mascherato, ETX e LRC ORIGINALE invariati (il log mostra l'LRC ricevuto). */
    @Test
    fun `maskFrame lascia l'LRC originale`() {
        val frame = Frames.app(Frames.E22_CLEAR)
        val masked = PanMasker.maskFrame(frame)
        assertEquals(frame.size, masked.size)
        assertEquals(ControlChars.STX, masked[0].toInt() and 0xFF)
        assertEquals(ControlChars.ETX, masked[masked.size - 2].toInt() and 0xFF)
        assertEquals(frame.last(), masked.last(), "LRC originale")
        assertArrayEquals(
            PanMasker.maskApplicationPayload(ascii(Frames.E22_CLEAR)),
            masked.copyOfRange(1, masked.size - 2),
        )
        assertFalse("4111111111111111" in text(masked))
        assertTrue(text(frame).contains("4111111111111111"), "il frame originale non viene modificato")
    }

    /** Frame di controllo e progress senza cifre passano invariati; frame KO invariato. */
    @Test
    fun `maskFrame su controlli progress e KO`() {
        assertArrayEquals(Frames.ACKW, PanMasker.maskFrame(Frames.ACKW))
        assertArrayEquals(Frames.NAKW, PanMasker.maskFrame(Frames.NAKW))
        assertArrayEquals(Frames.PRG, PanMasker.maskFrame(Frames.PRG))
        assertArrayEquals(Frames.app(Frames.E4), PanMasker.maskFrame(Frames.app(Frames.E4)))
        assertArrayEquals(ByteArray(0), PanMasker.maskFrame(ByteArray(0)))
    }

    /** Byte oltre l'LRC e frame senza ETX: nessun PAN in chiaro in nessun caso. */
    @Test
    fun `maskFrame con coda o senza ETX`() {
        // Il payload KO di E4 (35 zeri) resta posizionale e invariato; la coda passa dalla rete di sicurezza.
        val withTail = Frames.app(Frames.E4) + ascii("4111111111111111")
        val maskedTail = text(PanMasker.maskFrame(withTail))
        assertEquals(text(Frames.app(Frames.E4)) + "************1111", maskedTail)

        val truncated = byteArrayOf(0x02) + ascii(Frames.E22_CLEAR)
        val maskedTruncated = text(PanMasker.maskFrame(truncated))
        assertEquals("\u0002" + maskPayload(Frames.E22_CLEAR), maskedTruncated)
    }

    // ─── maskDigitRuns ───────────────────────────────────────────────────────

    /** Frammento di un esito con PAN in chiaro scartato dallo splitter: nessuna sequenza di 13+ cifre resta. */
    @Test
    fun `maskDigitRuns su un frammento scartato`() {
        val frame = Frames.app(Frames.E22_CLEAR)
        for (fragment in listOf(frame, frame.copyOfRange(5, frame.size), frame.copyOfRange(13, 40))) {
            val masked = PanMasker.maskDigitRuns(fragment)
            assertEquals(fragment.size, masked.size)
            assertFalse(longDigitRun.containsMatchIn(text(masked)), "13+ cifre in chiaro in ${text(masked)}")
        }
    }

    /** Soglia: 12 cifre invariate, 13 mascherate tranne le ultime 4; più sequenze trattate una per una. */
    @Test
    fun `maskDigitRuns soglia di 13 cifre`() {
        assertEquals("ABC123456789012XYZ", PanMasker.maskDigitRuns("ABC123456789012XYZ"))
        assertEquals("*********0123", PanMasker.maskDigitRuns("1234567890123"))
        assertEquals(
            "CARTA ************1111 E ************0004",
            PanMasker.maskDigitRuns("CARTA 4111111111111111 E 5500000000000004"),
        )
        assertEquals("", PanMasker.maskDigitRuns(""))
    }

    /** Overload su byte e su stringa danno lo stesso risultato, e la versione su byte restituisce una copia. */
    @Test
    fun `maskDigitRuns byte e stringa coerenti`() {
        val source = "x4111111111111111}1234567890123456789y"
        val input = ascii(source)
        val masked = PanMasker.maskDigitRuns(input)
        assertNotSame(input, masked)
        assertEquals(source, text(input), "input invariato")
        assertEquals(PanMasker.maskDigitRuns(source), text(masked))
    }
}
