package it.prime.protocollo17.presentation.model

import it.prime.protocollo17.domain.model.command.FieldKey
import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.event.ProtocolWarning
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.FieldId
import it.prime.protocollo17.ecr17.model.ReceiptLine
import it.prime.protocollo17.ecr17.model.ReprintRequest
import it.prime.protocollo17.ecr17.model.ReprintTarget
import it.prime.protocollo17.ecr17.message.ResponseParser
import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.ecr17.testing.PosMockReceipts
import it.prime.protocollo17.presentation.UiFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Resa degli esiti (tabella §6.7). Il titolo e il colore sono ciò che l'operatore legge per decidere: un esito
 * sconosciuto mostrato come "nessuna risposta", o un "da verificare" mostrato verde, porterebbero a ripetere un
 * pagamento forse già addebitato (U8, M6).
 */
class ResultViewMapperTest {

    private fun view(outcome: CommandOutcome) = ResultViewMapper.map(UiFixtures.finished(outcome))

    private fun paymentWithCode(code: String) =
        UiFixtures.payment(Frames.E22.substring(0, 10) + code + Frames.E22.substring(12))

    @Test
    fun `approvato e' verde, totali e chiusura dicono OK`() {
        val approved = view(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment()))
        assertEquals(ResultTone.SUCCESS, approved.tone)
        assertEquals("APPROVATO", approved.headline)
        assertNull(approved.reviewNotice)
        assertEquals("OK", view(UiFixtures.answered(CommandCode.TOTALS, UiFixtures.totals())).headline)
        assertEquals("OK", view(UiFixtures.answered(CommandCode.CLOSE_SESSION, UiFixtures.closeSession())).headline)
    }

    @Test
    fun `rifiutato e' rosso con la descrizione del terminale`() {
        val declined = view(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment(Frames.E4)))
        assertEquals(ResultTone.DANGER, declined.tone)
        assertEquals("RIFIUTATO", declined.headline)
        assertEquals("OPERAZIONE ANNULLATA", declined.detail)
    }

    @Test
    fun `esiti 05 09 e sconosciuti sono ambra`() {
        val cardNotPresent = view(UiFixtures.answered(CommandCode.PAYMENT, paymentWithCode("05")))
        assertEquals(ResultTone.WARNING, cardNotPresent.tone)
        assertEquals("CARTA NON PRESENTE (05)", cardNotPresent.headline)
        assertEquals("TAG SCONOSCIUTO (09)", view(UiFixtures.answered(CommandCode.PAYMENT, paymentWithCode("09"))).headline)
        assertEquals("ESITO NON RICONOSCIUTO (zz)", view(UiFixtures.answered(CommandCode.PAYMENT, paymentWithCode("zz"))).headline)
    }

    /** Lo stato 2 della tabella Nexi è "Operativo"; la risposta s è informativa, non un'approvazione. */
    @Test
    fun `stato s 2 e' operativo e informativo`() {
        val status = view(UiFixtures.answered(CommandCode.STATUS, UiFixtures.status(2)))
        assertEquals(ResultTone.INFO, status.tone)
        assertTrue(status.headline.startsWith("STATO: ") && status.headline.contains("Operativo"), status.headline)
        assertTrue(status.detail!!.contains("V1.2.3"), status.detail)
    }

    /** Forma testuale Ingenico 2025: prima il titolo era "STATO: sconosciuto (non è una cifra)". */
    @Test
    fun `stato s testuale OPERATIVE e' operativo`() {
        val textual = UiFixtures.status(2).copy(statusDigit = -1, statusWord = "OPERATIVE", softwareRelease = "01.00.14_B2807_2807")
        val status = view(UiFixtures.answered(CommandCode.STATUS, textual))
        assertEquals("STATO: Operativo (OPERATIVE)", status.headline)
        assertTrue(status.detail!!.contains("01.00.14_B2807_2807"), status.detail)
        assertEquals("sconosciuto (BUSY)", FieldLabels.statusText(textual.copy(statusWord = "BUSY")))
    }

    @Test
    fun `solo ACK e ristampa verso ECR con le righe ricevute`() {
        val ack = view(CommandOutcome.Acknowledged(CommandCode.ECR_PRINTING, UiFixtures.trace(CommandCode.ECR_PRINTING)))
        assertEquals(ResultTone.INFO, ack.tone)
        assertEquals("CONFERMATO (ACK)", ack.headline)
        val lines = listOf(ReceiptLine("A", "riga 1", false, false), ReceiptLine("B", "riga 2", false, true))
        val reprint = ResultViewMapper.map(
            UiFixtures.finished(
                CommandOutcome.Acknowledged(CommandCode.REPRINT, UiFixtures.trace(CommandCode.REPRINT, receipt = lines)),
                request = ReprintRequest(target = ReprintTarget.ECR),
            ),
        )
        assertEquals("2 righe S ricevute", reprint.detail)
        assertEquals(listOf("riga 1", "riga 2"), reprint.receipt)
    }

    /**
     * Lo scontrino della vista è ricomposto (ReceiptText): due righe S reali del formato a righe da 24 colonne (A§9.11)
     * diventano 16 righe di stampa, senza la riga vuota che il vecchio join con '\n' metteva a ogni confine.
     */
    @Test
    fun `scontrino ricomposto dalle righe S`() {
        val lines = listOf(PosMockReceipts.PAGAMENTI_83, PosMockReceipts.PAGAMENTI_86)
            .map { ResponseParser.parseReceiptLine(it.toByteArray(Charsets.ISO_8859_1)) }
        val reprint = ResultViewMapper.map(
            UiFixtures.finished(
                CommandOutcome.Acknowledged(CommandCode.REPRINT, UiFixtures.trace(CommandCode.REPRINT, receipt = lines)),
                request = ReprintRequest(target = ReprintTarget.ECR),
            ),
        )
        assertEquals("2 righe S ricevute", reprint.detail)
        assertEquals(16, reprint.receipt.size, reprint.receipt.joinToString("\n"))
        assertTrue(reprint.receipt.all { it.length == 24 }, "nessuna riga vuota ai confini: ${reprint.receipt}")
        assertEquals("A.I.I.C.     88105100004", reprint.receipt[7])
        assertEquals("Data 08/07/25  Ora 11:21", reprint.receipt[8])
    }

    /** Le quattro famiglie negative non si confondono: non inviato, NAK, nessuna risposta, sconosciuto. */
    @Test
    fun `esiti negativi hanno titoli distinti`() {
        val notSent = view(CommandOutcome.NotSent(CommandCode.PAYMENT, Ecr17Failure.ConnectTimeout(UiFixtures.ENDPOINT, 5_000), null))
        assertEquals(ResultTone.NEUTRAL to "NON INVIATO", notSent.tone to notSent.headline)
        val refused = view(CommandOutcome.Refused(CommandCode.PAYMENT, Ecr17Failure.Nak(4), UiFixtures.trace(CommandCode.PAYMENT, sends = 4, naksReceived = 4)))
        assertEquals(ResultTone.DANGER to "RIFIUTATO DAL TERMINALE (NAK)", refused.tone to refused.headline)
        val failed = view(CommandOutcome.Failed(CommandCode.STATUS, Ecr17Failure.ResponseTimeout(120_000), ExchangePhase.AWAITING_RESULT, UiFixtures.trace(CommandCode.STATUS)))
        assertEquals(ResultTone.DANGER to "NESSUNA RISPOSTA", failed.tone to failed.headline)
        val unknown = view(CommandOutcome.Unknown(CommandCode.PAYMENT, Ecr17Failure.ResponseTimeout(120_000), ExchangePhase.AWAITING_RESULT, UiFixtures.trace(CommandCode.PAYMENT)))
        assertEquals(ResultTone.WARNING to "ESITO SCONOSCIUTO", unknown.tone to unknown.headline)
        assertTrue(unknown.detail!!.contains("Ultimo esito (G)"), unknown.detail)
    }

    /** M9: il PAN non compare mai in chiaro, nemmeno se un frame arrivasse non mascherato. */
    @Test
    fun `PAN mascherato nei campi`() {
        val masked = view(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment(Frames.E22)))
        val pan = masked.fields.first { it.label == FieldLabels.label(FieldId.PAN) }
        assertEquals("***************4768", pan.value)
        assertEquals("13/19", pan.position)
        val clear = view(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment(Frames.E22_CLEAR)))
        val panClear = clear.fields.first { it.label == FieldLabels.label(FieldId.PAN) }
        assertFalse(panClear.value.contains("4111111111111111"), panClear.value)
        assertTrue(panClear.value.endsWith("1111"))
    }

    /** Un esito arrivato dopo reinvii per ACK mancante non è verde: ambra con l'avviso di doppio addebito. */
    @Test
    fun `POSSIBLE_DUPLICATE rende l'esito ambra con l'avviso`() {
        val trace = UiFixtures.trace(
            CommandCode.PAYMENT, sends = 2, timeoutRetransmissions = 1,
            warnings = listOf(ProtocolWarning(WarningCode.POSSIBLE_DUPLICATE, "esito dopo un reinvio", 1_500)),
        )
        val duplicate = view(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment(), trace))
        assertEquals(ResultTone.WARNING, duplicate.tone)
        assertEquals("APPROVATO — DA VERIFICARE", duplicate.headline)
        assertNotNull(duplicate.reviewNotice)
        assertTrue(duplicate.reviewNotice!!.contains("doppio addebito"))
    }

    /** Azioni rapide (§6.6): codice della 'e' in i/c, STAN della 'E' di un pagamento in S; mai da un G. */
    @Test
    fun `azioni rapide da e approvata e da E di pagamento`() {
        val preAuth = view(UiFixtures.answered(CommandCode.PRE_AUTH, UiFixtures.preAuth()))
        assertEquals(
            listOf(
                QuickAction("Usa codice in Integrazione (i)", CommandCode.INCREMENTAL_AUTH, FieldKey.PRE_AUTH_CODE, "123456789"),
                QuickAction("Usa codice in Chiusura (c)", CommandCode.PRE_AUTH_CLOSURE, FieldKey.PRE_AUTH_CODE, "123456789"),
            ),
            preAuth.quickActions,
        )
        val payment = view(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment()))
        assertEquals(listOf(QuickAction("Usa STAN in Storno (S)", CommandCode.REVERSAL, FieldKey.STAN, "000004")), payment.quickActions)
        assertTrue(view(UiFixtures.answered(CommandCode.LAST_RESULT, UiFixtures.payment())).quickActions.isEmpty())
        assertTrue(view(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment(Frames.E4))).quickActions.isEmpty())
    }

    /** Valori leggibili di §6.7 con il grezzo sempre accanto (app di test: si confronta con scontrino e log DLL). */
    @Test
    fun `valori leggibili dei campi`() {
        assertEquals("Contactless chip (CLI)", FieldLabels.value(FieldId.ENTRY_MODE, "CLI"))
        assertEquals("Contactless (CLE)", FieldLabels.value(FieldId.ENTRY_MODE, "CLE"))
        assertEquals("Operativo (OPERATIVE)", FieldLabels.value(FieldId.STATUS, "OPERATIVE"))
        assertEquals("Credito (2)", FieldLabels.value(FieldId.CARD_TYPE, "2"))
        assertEquals("giorno 077 · 09:47", FieldLabels.value(FieldId.HOST_DATE_TIME, "0770947"))
        assertEquals("1,2345 (00012345)", FieldLabels.value(FieldId.DCC_RATE, "00012345"))
        assertEquals("1,50 € (0000000000000150)", FieldLabels.value(FieldId.POS_TOTAL, "0000000000000150"))
        assertEquals("Operativo (2)", FieldLabels.value(FieldId.STATUS, "2"))
        assertEquals("(vuoto)", FieldLabels.value(FieldId.AUTH_CODE, "      "))
    }

    @Test
    fun `payload grezzo, scambio e journalError`() {
        val run = UiFixtures.finished(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment()), journalError = "journal non scritto")
        val v = ResultViewMapper.map(run)
        assertEquals(Frames.E22, v.rawPayload)
        assertTrue(v.exchange.any { it.label == "Codice ricevuto / attesi" && it.value == "E / E V" }, v.exchange.toString())
        assertEquals("journal non scritto", v.journalError)
        assertEquals(3, v.timeline.size)
    }

    @Test
    fun `historyRow con ora, lettera e importo formatCents`() {
        val row = ResultViewMapper.historyRow(UiFixtures.finished(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment()), id = 7))
        assertEquals(7L, row.id)
        assertEquals('P', row.letter)
        assertTrue(row.danger)
        assertEquals("APPROVATO", row.headline)
        assertEquals("0,10 €", row.amount)
        assertNull(ResultViewMapper.historyRow(UiFixtures.finished(UiFixtures.answered(CommandCode.STATUS, UiFixtures.status()))).amount)
    }
}
