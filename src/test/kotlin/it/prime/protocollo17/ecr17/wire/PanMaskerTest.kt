package it.prime.protocollo17.ecr17.wire

import it.prime.protocollo17.ecr17.message.ResponseParser
import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.ecr17.testing.PosMockReceipts
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
     * La regola è "mascherato se l'esito è DIVERSO da 01", non "solo se 00": anche 05 (carta non presente, che il
     * simulatore manda col layout positivo), 09 e un esito sconosciuto hanno il PAN in pos 13. Un controllo
     * ristretto a "00" lascerebbe in chiaro il PAN di quelle risposte nel log traffico e nel journal (M9).
     */
    @Test
    fun `esiti diversi da 00 e 01 mascherati`() {
        val tail = Frames.E22_CLEAR.substring(12)                 // PAN in chiaro + coda di E22
        for (code in listOf('E', 'V', 'e', 'c', 'v')) {
            for (result in listOf("05", "09", "zz", "10")) {
                val payload = "025757330$code$result$tail"
                val masked = maskPayload(payload)
                val label = "$code con esito \"$result\""
                assertEquals(payload.length, masked.length, "lunghezza di $label")
                assertEquals(payload.substring(0, 12), masked.substring(0, 12), "intestazione ed esito invariati in $label")
                assertEquals(maskedPanField, masked.substring(12, 31), "PAN mascherato in $label")
                assertEquals(payload.substring(31), masked.substring(31), "coda invariata in $label")
                assertFalse("4111111111111111" in masked, "PAN in chiaro in $label")
            }
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

    /**
     * Lo 0x7F (doppia altezza) in mezzo al PAN di una riga S è trasparente. Prima `41111111<7F>11111111` contava come
     * due sequenze da 8 cifre, sotto la soglia di 13, e il PAN restava in chiaro nel log traffico e nel log del
     * simulatore mentre il testo della riga lo mascherava (M9). Lo 0x7F resta al suo posto e il grezzo coincide con
     * quello del parser (`ReceiptLine.raw`).
     */
    @Test
    fun `riga S con PAN spezzato da 0x7F`() {
        val split = "123456780SPAN 41111111\u007F11111111"
        val masked = maskPayload(split)
        assertEquals("123456780SPAN ********\u007F****1111", masked)
        assertEquals(split.length, masked.length)
        assertFalse(longDigitRun.containsMatchIn(masked.filter { it != '\u007F' }), "13+ cifre in chiaro in $masked")
        assertEquals(ResponseParser.parseReceiptLine(ascii(split), maskPan = true).raw, masked.substring(10), "stessa maschera del parser")

        // 0x7F in testa alla riga (caso tipico della doppia altezza) e più 0x7F nella stessa sequenza.
        assertEquals("123456780S\u007FCARTA ************1111", maskPayload("123456780S\u007FCARTA 4111111111111111"))
        assertEquals("123456780S**\u007F******\u007F****\u007F1111", maskPayload("123456780S41\u007F111111\u007F1111\u007F1111"))

        // Sotto la soglia anche contando attraverso lo 0x7F: invariato.
        assertEquals("123456780S12345\u007F6789", maskPayload("123456780S12345\u007F6789"))
    }

    /** Lo stesso vale per i codici sconosciuti, che passano dallo stesso ramo. */
    @Test
    fun `codice sconosciuto con PAN spezzato da 0x7F`() {
        assertEquals("123456780U********\u007F****1111", maskPayload("123456780U41111111\u007F11111111"))
        assertEquals("123456780?********\u007F****1111", maskPayload("123456780?41111111\u007F11111111"))
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

    /**
     * Intestazione spostata (frammento di esito dopo uno STX spurio): la pos 10 è una cifra e il payload passa
     * dalla rete di sicurezza. Prima le sequenze si contavano solo dalla pos 11: qui il PAN inizia alla pos 4,
     * dalla pos 11 ne restavano 9 cifre (sotto la soglia) e "4111111111111111" finiva in chiaro, mentre veniva
     * mascherata la sequenza successiva (M9).
     */
    @Test
    fun `pos 10 numerica maschera anche le sequenze iniziate prima della pos 11`() {
        val fragment = Frames.E22_CLEAR.substring(12)             // "0004111111111111111CLIR4843…0055"
        assertTrue(fragment[9].isDigit(), "il vettore deve avere una cifra in pos 10")
        val masked = maskPayload(fragment)
        assertEquals("*".repeat(15) + "1111" + "CLIR" + "*".repeat(32) + "0055", masked)
        assertFalse(longDigitRun.containsMatchIn(masked), "13+ cifre in chiaro in $masked")
        assertFalse("4111111111111111" in masked)

        // Lo stesso frammento in un frame completo (ProtocolEvent.Wire RX): payload mascherato, ETX e LRC originali.
        val frame = Frames.app(fragment)
        val maskedFrame = PanMasker.maskFrame(frame)
        assertEquals("\u0002$masked\u0003", text(maskedFrame.copyOfRange(0, maskedFrame.size - 1)))
        assertEquals(frame.last(), maskedFrame.last(), "LRC originale")
    }

    /**
     * Payload corti o troncati: nessuna eccezione; il pezzo di PAN presente viene mascherato per intero. Prima restavano
     * visibili le ultime 4 cifre del PEZZO ("1111" qui), che non sono le ultime 4 del PAN ma cifre centrali.
     */
    @Test
    fun `payload corti e troncati`() {
        assertEquals("", maskPayload(""))
        assertEquals("12345", maskPayload("12345"))
        assertEquals("123456780E", maskPayload("123456780E"))
        assertEquals("123456780E0", maskPayload("123456780E0"))
        assertEquals("123456780E00**********", maskPayload("123456780E000004111111"))
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

    /**
     * Riga S con il PAN spezzato da 0x7F come arriva sul filo (ProtocolEvent.Wire RX e SimEvent.FrameOut): payload
     * mascherato con lo 0x7F al suo posto, ETX e LRC originali. Prima il frame finiva in chiaro nel log traffico (M9).
     */
    @Test
    fun `maskFrame su riga S con PAN spezzato da 0x7F`() {
        val payload = "123456780SPAN 41111111\u007F11111111"
        val frame = Frames.app(payload)
        val masked = PanMasker.maskFrame(frame)
        assertEquals(frame.size, masked.size)
        assertEquals("\u0002123456780SPAN ********\u007F****1111\u0003", text(masked.copyOfRange(0, masked.size - 1)))
        assertEquals(frame.last(), masked.last(), "LRC originale")
        assertFalse("41111111" in text(masked), "nessuna metà del PAN in chiaro")
        assertTrue(text(frame).contains("41111111\u007F11111111"), "il frame originale non viene modificato")
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
        // Il payload KO di E4 (35 zeri) resta posizionale e invariato; la coda è un frammento senza STX, e la sequenza
        // che lo finisce perde tutte le cifre (§3.8).
        val withTail = Frames.app(Frames.E4) + ascii("4111111111111111")
        val maskedTail = text(PanMasker.maskFrame(withTail))
        assertEquals(text(Frames.app(Frames.E4)) + "****************", maskedTail)

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

    /**
     * Frammento scartato di una riga S con il PAN spezzato da 0x7F (ProtocolEvent.Discarded, residuo dello splitter,
     * log del simulatore). Prima solo il ramo S di maskApplicationPayload saltava lo 0x7F: qui maskDigitRuns contava
     * due sequenze da 8 cifre, sotto la soglia, e il PAN restava in chiaro nel log traffico (M9).
     */
    @Test
    fun `maskDigitRuns con PAN spezzato da 0x7F`() {
        val fragment = "SPAN 41111111\u007F11111111}"
        val expected = "SPAN ********\u007F****1111}"
        assertEquals(expected, text(PanMasker.maskDigitRuns(ascii(fragment))), "byte")
        assertEquals(expected, PanMasker.maskDigitRuns(fragment), "stringa: stesso risultato")

        // Sotto la soglia anche contando attraverso lo 0x7F, e 0x7F fuori dalle cifre: invariati.
        assertEquals("12345\u007F6789 \u007FA", PanMasker.maskDigitRuns("12345\u007F6789 \u007FA"))
        assertEquals("\u007F\u007F", text(PanMasker.maskDigitRuns(ascii("\u007F\u007F"))))
    }

    /**
     * 0x7E è il separatore della riga in doppia altezza nel formato a 24 colonne dei terminali Ingenico del 2025
     * (catture PosMock, A§9.11), quindi spezza le sequenze come lo 0x7D: un PAN non scavalca una riga. Se fosse
     * trasparente, STAN e "ultime 4" delle righe vicine finirebbero nella stessa sequenza e verrebbero mascherati.
     */
    @Test
    fun `0x7E separa le sequenze`() {
        val rows = "STAN 000004~************************"
        assertEquals(rows, PanMasker.maskDigitRuns(rows), "lo STAN resta leggibile")
        assertEquals(rows, text(PanMasker.maskDigitRuns(ascii(rows))), "byte: stesso risultato")
        assertEquals("PAN     ************1017~0,05", PanMasker.maskDigitRuns("PAN     ************1017~0,05"))
    }

    /**
     * Frammento scartato che inizia con STX (frame troncato, residuo a fine scambio): mascheramento posizionale come
     * per il frame intero. Prima passava dalla rete di sicurezza: con le '*' nella sequenza un esito approvato perdeva
     * anche l'esito "00", la prova per risolvere un esito sconosciuto (M6).
     */
    @Test
    fun `maskFragment posizionale sui frammenti con STX`() {
        val approved = "\u0002100029120E00000************1017CLE38066 18711192881051000040000020"
        val masked = text(PanMasker.maskFragment(ascii(approved)))
        assertEquals("\u0002100029120E00***************1017CLE38066 18711192881051000040000020", masked)
        // Senza STX resta la rete di sicurezza.
        assertEquals("E*****************9993CLI", text(PanMasker.maskFragment(ascii("E00000438746******9993CLI"))))
    }

    /** Campo PAN troncato: tutte le cifre presenti, non le "ultime 4" del pezzo (prima `…E0000438746123` ⇒ `…****6123`). */
    @Test
    fun `campo PAN troncato mascherato per intero`() {
        assertEquals("\u0002100029120E00*************", text(PanMasker.maskFragment(ascii("\u0002100029120E000004387461234"))))
        assertEquals("100029120E00*************", maskPayload("100029120E000004387461234"))
    }

    /**
     * PAN già troncato dal terminale con il BIN in chiaro (scontrini Ingenico, catture PosMock): le '*' fanno parte della
     * sequenza, quindi `438746******9993` (16 caratteri) perde il BIN. Prima si contavano solo le cifre e il BIN restava
     * in chiaro nelle righe S, negli scarti e nel log traffico. Una fila di sole '*' o una sequenza corta restano com'erano.
     */
    @Test
    fun `PAN troncato con il BIN in chiaro`() {
        assertEquals("PAN     ************9993SCAD", PanMasker.maskDigitRuns("PAN     438746******9993SCAD"))
        assertEquals("PAN     ************9993SCAD", text(PanMasker.maskDigitRuns(ascii("PAN     438746******9993SCAD"))))
        assertEquals("123456780S***************3224", maskPayload("123456780S679999*********3224"))
        // Frammento scartato di un esito: anche l'esito "00" davanti al PAN finisce nella sequenza (accettato).
        assertEquals("E*****************9993CLI", PanMasker.maskDigitRuns("E00000438746******9993CLI"))
        assertEquals("************************", PanMasker.maskDigitRuns("************************"), "nessuna cifra")
        assertEquals("SCAD                ****", PanMasker.maskDigitRuns("SCAD                ****"))
        assertEquals("1234****5678", PanMasker.maskDigitRuns("1234****5678"), "12 caratteri: sotto la soglia")
        assertEquals("************9993", PanMasker.maskDigitRuns("************9993"), "già mascherato: idempotente")
    }

    /** Un frame senza STX e i byte oltre l'LRC passano dalla stessa rete di sicurezza, con lo 0x7F trasparente. */
    @Test
    fun `maskFrame senza STX e coda con PAN spezzato da 0x7F`() {
        val noStx = ascii("80SPAN 41111111\u007F11111111\u0003")
        assertEquals("80SPAN ********\u007F****1111\u0003", text(PanMasker.maskFrame(noStx)))

        val withTail = Frames.app(Frames.E4) + ascii("41111111\u007F11111111")
        assertEquals(text(Frames.app(Frames.E4)) + "********\u007F********", text(PanMasker.maskFrame(withTail)))
    }

    // ─── A cavallo dei frame: righe S tagliate e frammenti ───────────────────

    private val header = "100029120S"

    /** Testo di una riga S reale (dalla pos 11), 0x7F e ESC compresi. */
    private fun textOf(payload: String) = payload.substring(10)

    /** Una riga S mascherata con la coda della precedente, e la coda che lascia alla successiva. */
    private fun maskChain(payloads: List<String>): List<String> {
        var carry = 0
        return payloads.map { payload ->
            val masked = text(PanMasker.maskReceiptPayload(ascii(payload), carry))
            carry = PanMasker.receiptCarryAfter(ascii(payload), carry)
            masked
        }
    }

    /**
     * Vettore costruito dalla cattura (A§9.11, formato a colonne): il flusso reale pagamenti:117-123 ritagliato in due
     * righe S piene con il taglio in ogni punto del PAN. Prima ogni riga era mascherata da sola: `438746` | `******9993`
     * erano due sequenze sotto la soglia e il BIN restava in chiaro nel testo, nel grezzo e nel log traffico (M9). Lo
     * stesso con un PAN in chiaro (terminali che non lo troncano, A§9.9): nessuna delle prime 12 cifre resta visibile.
     */
    @Test
    fun `PAN tagliato tra due righe S piene, in ogni punto`() {
        val stream = textOf(PosMockReceipts.PAGAMENTI_117) + textOf(PosMockReceipts.PAGAMENTI_120) +
            textOf(PosMockReceipts.PAGAMENTI_123)
        for (pan in listOf("438746******9993", "4387461234569993")) {
            val source = stream.replace("438746******9993", pan)
            val panAt = source.indexOf(pan)
            for (cut in panAt..panAt + pan.length) {
                val first = header + source.substring(cut - 200, cut)
                val second = header + source.substring(cut, cut + 200)
                val joined = maskChain(listOf(first, second)).joinToString("") { textOf(it) }
                val maskedPan = joined.substring(200 - (cut - panAt), 200 - (cut - panAt) + pan.length)
                assertEquals("*".repeat(12), maskedPan.take(12), "taglio a ${cut - panAt} di $pan: $maskedPan")
                assertFalse("438746" in joined, "BIN in chiaro con il taglio a ${cut - panAt}")
            }
        }
        // Senza la coda della riga prima (riga mascherata da sola) la seconda metà resterebbe in chiaro.
        val clear = stream.replace("438746******9993", "4387461234569993")
        val cut = clear.indexOf("4387461234569993") + 6
        val alone = text(PanMasker.maskReceiptPayload(ascii(header + clear.substring(cut, cut + 200))))
        assertTrue("1234569993" in alone, "il vettore deve dimostrare il limite senza coda: $alone")
    }

    /**
     * Formato a righe da 24 colonne (pagamenti:83-95): ogni frame finisce con una riga intera e il successivo inizia
     * con 0x7D, quindi nessuna coda resta aperta e le cifre a fine frame restano leggibili (A.I.I.C., AID).
     */
    @Test
    fun `formato a righe, nessuna coda aperta`() {
        for (payload in PosMockReceipts.FORMAT_A) {
            assertEquals(0, PanMasker.receiptCarryAfter(ascii(payload)), payload)
            assertEquals(maskPayload(payload), text(PanMasker.maskReceiptPayload(ascii(payload), carry = 5)), "testa con 0x7D")
        }
        assertTrue(text(PanMasker.maskReceiptPayload(ascii(PosMockReceipts.PAGAMENTI_83))).endsWith("A.I.I.C.     88105100004"))
        assertTrue(text(PanMasker.maskReceiptPayload(ascii(PosMockReceipts.PAGAMENTI_86))).endsWith("A*********1010"))
    }

    /**
     * Formato a colonne reale (pagamenti:117-126) mascherato riga per riga con la coda: il PAN esce come sull'esito, e
     * l'AID tagliato (`A0000` | `000031010`) come se fosse intero. Il prezzo accettato: le cifre in coda a una riga
     * piena spariscono anche quando non sono un PAN (`Data 08` ⇒ `Data **`), perché la riga esce prima che arrivi la
     * successiva.
     */
    @Test
    fun `formato a colonne reale, coda e testa`() {
        val masked = maskChain(PosMockReceipts.FORMAT_B)
        assertTrue(masked[0].endsWith("A.I.I.C.     88105100003Data **"), masked[0])
        assertTrue(masked[1].startsWith("${header}/07/25  Ora 12:28"), masked[1])
        assertTrue("PAN     ************9993" in masked[1], masked[1])
        assertTrue(masked[1].endsWith("A.ID      A****"), masked[1])
        assertTrue(masked[2].startsWith("$header*****1010APPL"), masked[2])
        assertEquals(PosMockReceipts.PAGAMENTI_126, masked[3], "ultima riga invariata")

        val carries = PosMockReceipts.FORMAT_B.runningFold(0) { carry, p -> PanMasker.receiptCarryAfter(ascii(p), carry) }
        assertEquals(listOf(0, 2, 4, 0, 0), carries, "coda lasciata da ogni riga")
    }

    /**
     * La coda si apre solo su una riga piena (200 caratteri) che non chiude lo scontrino: le righe una per frame
     * (simulatore, spec Nexi) e l'ultima riga non perdono le ultime 4 cifre di un PAN in coda.
     */
    @Test
    fun `righe corte e ultima riga non aprono la coda`() {
        assertEquals(0, PanMasker.receiptCarryAfter(ascii(Frames.RECEIPT_PAN)))
        assertEquals("123456780SCARTA ************1111", text(PanMasker.maskReceiptPayload(ascii(Frames.RECEIPT_PAN))))

        val full = header + "X".repeat(184) + "4111111111111111"
        assertEquals(200, full.length - 10)
        assertEquals(16, PanMasker.receiptCarryAfter(ascii(full)))
        assertEquals(header + "X".repeat(184) + "*".repeat(16), text(PanMasker.maskReceiptPayload(ascii(full))))

        val last = header + "X".repeat(181) + "4111111111111111}}\u001B"
        assertEquals(0, PanMasker.receiptCarryAfter(ascii(last)))
        assertEquals(header + "X".repeat(181) + "************1111}}\u001B", text(PanMasker.maskReceiptPayload(ascii(last))))

        // 0x7F in coda è trasparente: la sequenza tocca comunque la fine.
        val double = header + "X".repeat(187) + "41111111\u007F1111"
        assertEquals(12, PanMasker.receiptCarryAfter(ascii(double)))
        assertTrue(text(PanMasker.maskReceiptPayload(ascii(double))).endsWith("********\u007F****"))
    }

    /** Testa con la coda: sotto la soglia insieme resta invariata; una riga fatta tutta di cifre allunga la coda. */
    @Test
    fun `testa con la coda, soglia e riga di sole cifre`() {
        assertEquals("${header}1234567 TESTO", text(PanMasker.maskReceiptPayload(ascii("${header}1234567 TESTO"), carry = 5)))
        assertEquals("$header****5678 TESTO", text(PanMasker.maskReceiptPayload(ascii("${header}12345678 TESTO"), carry = 5)))
        assertEquals("$header*2345 TESTO", text(PanMasker.maskReceiptPayload(ascii("$header*2345 TESTO"), carry = 12)))

        val digits = header + "4111".repeat(50)
        assertEquals(header + "*".repeat(200), text(PanMasker.maskReceiptPayload(ascii(digits), carry = 3)))
        assertEquals(203, PanMasker.receiptCarryAfter(ascii(digits), carry = 3))
    }

    /**
     * Wire RX, grezzo e testo della stessa riga con la stessa coda: maskFrame (ramo 'S'), maskReceiptPayload e
     * parseReceiptLine danno la stessa maschera; per gli altri codici la coda non conta.
     */
    @Test
    fun `stessa maschera per frame, payload e parser`() {
        val payload = PosMockReceipts.PAGAMENTI_123
        val frame = Frames.app(payload)
        val masked = text(PanMasker.maskReceiptPayload(ascii(payload), carry = 4))
        assertEquals("\u0002$masked\u0003", text(PanMasker.maskFrame(frame, receiptCarry = 4)).dropLast(1))
        assertEquals(masked, text(PanMasker.maskApplicationPayload(ascii(payload), receiptCarry = 4)))
        assertEquals(textOf(masked), ResponseParser.parseReceiptLine(ascii(payload), maskPan = true, carry = 4).raw)
        assertArrayEquals(PanMasker.maskFrame(Frames.app(Frames.E22_CLEAR)), PanMasker.maskFrame(Frames.app(Frames.E22_CLEAR), receiptCarry = 9))
    }

    /** Frame senza ETX (troncato): la coda di una riga S o di un codice sconosciuto può continuare nei byte persi. */
    @Test
    fun `frame troncato, coda mascherata per intero`() {
        assertEquals("\u0002123456780SCARTA ********", text(PanMasker.maskFrame(ascii("\u0002123456780SCARTA 41111111"))))
        assertEquals("\u0002123456780U********", text(PanMasker.maskFragment(ascii("\u0002123456780U41111111"))))
        assertEquals(
            "\u0002123456780S****1111 FINE",
            text(PanMasker.maskFragment(ascii("\u0002123456780S11111111 FINE"), receiptCarry = 8)),
            "testa di una riga S troncata con la coda della precedente",
        )
        assertEquals("\u0002123456780SCARTA 41111111\u0003X", text(PanMasker.maskFrame(ascii("\u0002123456780SCARTA 41111111\u0003X"))), "con ETX: riga corta")
    }

    /**
     * Frammenti senza STX: la coda perde tutte le cifre (può continuare nel pezzo dopo), la testa tutte tranne le ultime
     * 4 (può chiudere un PAN iniziato prima). Prima `CARTA 4111111` | `111111111 X` restavano in chiaro: 7 e 9 cifre.
     */
    @Test
    fun `maskFragment senza STX, testa e coda`() {
        assertEquals("CARTA *******", text(PanMasker.maskFragment(ascii("CARTA 4111111"))))
        assertEquals("*****1111 X", text(PanMasker.maskFragment(ascii("111111111 X"))))
        assertEquals("****1111 TESTO ********", text(PanMasker.maskFragment(ascii("11111111 TESTO 41111111"))))
        assertEquals("********\u007F********", text(PanMasker.maskFragment(ascii("41111111\u007F11111111"))), "tutto una sequenza")
        assertEquals("TESTO 1234 FINE", text(PanMasker.maskFragment(ascii("TESTO 1234 FINE"))), "nessuna cifra ai bordi")
        assertEquals("1234 X", text(PanMasker.maskFragment(ascii("1234 X"))), "4 cifre in testa: nulla da nascondere")
        assertEquals("", text(PanMasker.maskFragment(ByteArray(0))))
        // ETX dentro il frammento (STX perso): lì finiva un frame, la sequenza prima è una coda.
        assertEquals("*****9120SCARTA ********\u0003X", text(PanMasker.maskFragment(ascii("100029120SCARTA 41111111\u0003X"))))
    }

    /**
     * Residuo a fine scambio con più frame (revisione del 2026-10-19): prima i byte dopo il primo LRC passavano solo
     * dalla rete di sicurezza, e in `<riga S corta>` + `<STX>…PAN 41111111` le 8 cifre restavano in chiaro, mentre lo
     * stesso frame troncato da solo le mascherava. Ora ogni frame del residuo è mascherato come tale, con la coda
     * della riga S prima.
     */
    @Test
    fun `residuo con piu frame`() {
        val short = Frames.app("${header}RIGA BREVE")
        val blob = short + ascii("\u0002${header}PAN 41111111")
        assertEquals(text(short) + "\u0002${header}PAN ********", text(PanMasker.maskFragment(blob)))

        val full = Frames.app(header + "X".repeat(186) + "CARTA 41111111")
        val continued = text(PanMasker.maskFragment(full + ascii("\u0002${header}11111111 FINE")))
        assertTrue(continued.endsWith("CARTA ********\u0003${text(full).last()}\u0002${header}****1111 FINE"), continued)
    }

    /**
     * Riga intera solo se il frame è fatto di righe intere (revisione del 2026-10-19): prima bastavano due righe larghe
     * uguali dopo un separatore qualsiasi, e un testo con righe accorciate uguali per caso non apriva la coda.
     */
    @Test
    fun `riga intera solo se il frame inizia con un separatore`() {
        val fooled = header + "A".repeat(174) + "}" + "B".repeat(12) + "}" + "CARTA 411111"
        assertEquals(210, fooled.length)
        assertEquals(6, PanMasker.receiptCarryAfter(ascii(fooled)))
        assertTrue(text(PanMasker.maskReceiptPayload(ascii(fooled))).endsWith("}CARTA ******"))
        for (payload in PosMockReceipts.FORMAT_A) assertEquals(0, PanMasker.receiptCarryAfter(ascii(payload)), "formato a righe")
    }

    /** Coda sconosciuta (connessione riusata, byte scartati): la testa mostra solo le ultime 4 cifre, qualunque sia la lunghezza. */
    @Test
    fun `coda sconosciuta`() {
        val carry = PanMasker.UNKNOWN_RECEIPT_CARRY
        assertEquals("$header***4567 TESTO", text(PanMasker.maskReceiptPayload(ascii("${header}1234567 TESTO"), carry)))
        assertEquals("$header*2345 TESTO", text(PanMasker.maskReceiptPayload(ascii("${header}12345 TESTO"), carry)))
        assertEquals("${header}1234 TESTO", text(PanMasker.maskReceiptPayload(ascii("${header}1234 TESTO"), carry)))
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
