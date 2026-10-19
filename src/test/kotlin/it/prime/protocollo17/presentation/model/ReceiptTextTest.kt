package it.prime.protocollo17.presentation.model

import it.prime.protocollo17.ecr17.message.ResponseParser
import it.prime.protocollo17.ecr17.model.ReceiptLine
import it.prime.protocollo17.ecr17.testing.PosMockReceipts
import it.prime.protocollo17.ecr17.wire.PanMasker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Scontrino ricomposto dalle righe S (§6.7, §6.9), sulle catture reali Ingenico (A§9.11).
 *
 * Prima i testi delle righe S erano uniti con '\n': nel formato a righe ogni confine aggiungeva una riga vuota, nel
 * formato a colonne l'operatore vedeva righe da ~200 caratteri. Le righe una per frame del simulatore non devono
 * cambiare.
 */
class ReceiptTextTest {

    private fun parse(payloads: List<String>, maskPan: Boolean = false): List<ReceiptLine> {
        var carry = 0
        return payloads.map {
            val bytes = it.toByteArray(Charsets.ISO_8859_1)
            val line = ResponseParser.parseReceiptLine(bytes, maskPan = maskPan, carry = carry)
            carry = PanMasker.receiptCarryAfter(bytes, carry)
            line
        }
    }

    /** Formato a righe da 24 colonne (pagamenti:83-95): 4 frame da 8 righe, nessuna riga vuota ai confini. */
    @Test
    fun `formato a righe, nessuna riga vuota ai confini`() {
        val lines = ReceiptText.lines(parse(PosMockReceipts.FORMAT_A))
        assertEquals(32, lines.size, lines.joinToString("\n"))
        assertTrue(lines.all { it.length == ReceiptText.COLUMNS }, lines.joinToString("\n"))
        assertEquals("       MASTERCARD       ", lines.first())
        assertEquals("        PAGAMENTO       ", lines[2], "0x7E a capo")
        assertEquals(listOf("A.I.I.C.     88105100004", "Data 08/07/25  Ora 11:21"), lines.subList(7, 9))
        assertEquals(listOf("A.ID      A0000000041010", "APPL            MC DEBIT"), lines.subList(15, 17))
        assertEquals("  ARRIVEDERCI E GRAZIE  ", lines[30])
    }

    /**
     * Formato a colonne senza separatori (pagamenti:117-126): il flusso unito va a capo ogni 24 colonne, lo 0x7F non
     * occupa colonne, e i campi tagliati tra due frame (`Data 08` | `/07/25`, `A0000` | `000031010`) tornano interi.
     */
    @Test
    fun `formato a colonne, a capo ogni 24`() {
        val lines = ReceiptText.lines(parse(PosMockReceipts.FORMAT_B))
        assertEquals(31, lines.size, lines.joinToString("\n"))
        assertTrue(lines.dropLast(1).all { it.length == ReceiptText.COLUMNS }, lines.joinToString("\n"))
        assertEquals("          VISA          ", lines.first())
        assertTrue("        ACQUISTO        " in lines, lines.toString())
        assertTrue("Data 08/07/25  Ora 12:28" in lines, lines.toString())
        assertTrue("PAN     438746******9993" in lines, lines.toString())
        assertTrue("A.ID      A0000000031010" in lines, lines.toString())
        assertTrue("IMPORTO EUR         0,05" in lines, lines.toString())
        assertEquals("CONTRATTO #12345", lines.last(), "spazi prima di \"}}\" + ESC tolti con la fine scontrino")
    }

    /**
     * Con il PAN mascherato come fa la sessione (coda della riga precedente): il PAN esce come sull'esito, l'AID tagliato
     * come se fosse intero, e la cifra al taglio della data sparisce (il prezzo di M9 a cavallo dei frame, §3.8).
     */
    @Test
    fun `formato a colonne mascherato con la coda`() {
        val lines = ReceiptText.lines(parse(PosMockReceipts.FORMAT_B, maskPan = true))
        assertTrue("PAN     ************9993" in lines, lines.toString())
        assertTrue("A.ID      A*********1010" in lines, lines.toString())
        assertTrue("Data **/07/25  Ora 12:28" in lines, lines.toString())
    }

    /** Vista dal vivo (overlay): con il solo primo frame arrivato le righe sono già da 24, l'ultima a metà. */
    @Test
    fun `vista parziale durante la ricezione`() {
        val lines = ReceiptText.lines(parse(listOf(PosMockReceipts.PAGAMENTI_117)))
        assertEquals(9, lines.size, lines.joinToString("\n"))
        assertEquals("Data 08", lines.last())
    }

    /**
     * Righe una per frame (simulatore, spec Nexi): più corte del massimo, restano righe a sé anche oltre le 24 colonne,
     * come prima. La sentinella di fine (7D×6 + ESC) è già tolta dal testo.
     */
    @Test
    fun `righe una per frame del simulatore invariate`() {
        val sim = listOf(
            "000000000SSIMULATORE P17",
            "000000000SPAGAMENTO CARTA 4111111111111111",
            "000000000SIMPORTO EUR 10,00",
            "000000000SESITO OK}}}}}}\u001B",
        )
        assertEquals(
            listOf("SIMULATORE P17", "PAGAMENTO CARTA ************1111", "IMPORTO EUR 10,00", "ESITO OK"),
            ReceiptText.lines(parse(sim, maskPan = true)),
        )
    }

    /** 0x7D a fine riga (spec Nexi: "a capo") non diventa una riga vuota prima della riga successiva. */
    @Test
    fun `separatore in coda non raddoppia l'a capo`() {
        val lines = listOf("123456780SRIGA 1}", "123456780SRIGA 2}SECONDA}", "123456780SFINE}}}}}}\u001B")
        assertEquals(listOf("RIGA 1", "RIGA 2", "SECONDA", "FINE"), ReceiptText.lines(parse(lines)))
    }

    /** Tante righe quanti i separatori: una riga S di soli `}}` sono due righe vuote (prima ne usciva una). */
    @Test
    fun `riga di soli separatori`() {
        val lines = listOf("123456780SRIGA", "123456780S}}", "123456780S}", "123456780SFINE}}}}}}\u001B")
        assertEquals(listOf("RIGA", "", "", "", "FINE"), ReceiptText.lines(parse(lines)))
    }

    /** Nessuna riga, o righe senza testo (solo avanzamenti carta): nessuna riga da mostrare. */
    @Test
    fun `scontrino vuoto`() {
        assertEquals(emptyList<String>(), ReceiptText.lines(emptyList()))
        assertEquals(emptyList<String>(), ReceiptText.lines(parse(listOf(PosMockReceipts.PAGAMENTI_95))))
    }
}
