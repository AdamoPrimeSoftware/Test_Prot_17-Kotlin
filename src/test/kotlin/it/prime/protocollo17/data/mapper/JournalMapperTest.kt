package it.prime.protocollo17.data.mapper

import it.prime.protocollo17.data.testing.OutcomeFixtures
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.JournalRecord
import it.prime.protocollo17.domain.model.journal.KnownResultSource
import it.prime.protocollo17.domain.model.journal.ResolutionKind
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.ReversalRequest
import it.prime.protocollo17.ecr17.testing.Frames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JournalMapperTest {

    private fun terminal(outcome: CommandOutcome) = JournalMapper.terminalRecord("a", 3, 99, outcome)

    @Test
    fun `Answered diventa RESULT con i campi di riconciliazione`() {
        val r = terminal(OutcomeFixtures.answered(naks = 1, retx = 0))

        assertEquals(JournalPhase.RESULT, r.phase, "fase")
        assertEquals("a", r.attemptId, "attemptId")
        assertEquals(3, r.seq, "seq")
        assertEquals(99L, r.at, "at")
        assertEquals("APPROVED", r.outcome, "outcome")
        assertEquals("E", r.replyCode, "replyCode")
        assertEquals("00", r.resultCode, "resultCode")
        assertEquals("APPROVED", r.verdict, "verdict")
        assertEquals("000004", r.stan, "stan")
        assertEquals("000055", r.onlineId, "onlineId")
        assertEquals("R48432", r.authCode, "authCode")
        assertEquals("0770947", r.hostDateTime, "hostDateTime")
        assertEquals(Frames.E22, r.rawResult, "rawResult mascherato")
        assertEquals("EXPLICIT", r.ackMode, "ackMode")
        assertEquals(1, r.naksReceived, "naksReceived")
        assertEquals(0, r.timeoutRetransmissions, "timeoutRetransmissions")
        assertEquals(2, r.sends, "sends")
        assertNull(r.needsReview, "non da verificare")
        assertNull(r.reviewReason, "nessun motivo")
    }

    /** M6: un esito dopo un reinvio per ACK mancante è un possibile doppio addebito e deve restare bloccante. */
    @Test
    fun `Answered con POSSIBLE_DUPLICATE su P e da verificare`() {
        val r = terminal(OutcomeFixtures.answered(warnings = listOf(OutcomeFixtures.duplicateWarning()), retx = 1))

        assertEquals(true, r.needsReview, "needsReview")
        assertEquals("POSSIBLE_DUPLICATE", r.reviewReason, "reviewReason")
        assertEquals(1, r.timeoutRetransmissions, "timeoutRetransmissions")
    }

    @Test
    fun `POSSIBLE_DUPLICATE su H non rende l'esito da verificare`() {
        val r = terminal(OutcomeFixtures.answered(CommandCode.CARD_VERIFICATION, warnings = listOf(OutcomeFixtures.duplicateWarning())))
        assertNull(r.needsReview, "H non muove denaro")
    }

    @Test
    fun `NotSent diventa NOT_SENT`() {
        val r = terminal(OutcomeFixtures.notSent())
        assertEquals(JournalPhase.NOT_SENT, r.phase, "fase")
        assertEquals("NOT_SENT", r.outcome, "outcome")
        assertEquals("ECR17: connection to 127.0.0.1:5577 failed: Connection refused", r.failure, "failure")
        assertNull(r.sends, "nessuna trace")
    }

    @Test
    fun `Refused diventa REFUSED`() {
        val r = terminal(OutcomeFixtures.refused())
        assertEquals(JournalPhase.REFUSED, r.phase, "fase")
        assertEquals("ECR17: NAK after 4 attempt(s)", r.failure, "failure")
        assertEquals(4, r.naksReceived, "naksReceived")
        assertEquals(4, r.sends, "sends")
        assertEquals("AWAITING_ACK", r.failurePhase, "failurePhase dall'ultima fase della trace")
    }

    @Test
    fun `Unknown diventa UNKNOWN anche senza trace`() {
        val withTrace = terminal(OutcomeFixtures.unknown())
        assertEquals(JournalPhase.UNKNOWN, withTrace.phase, "fase")
        assertEquals("AWAITING_RESULT", withTrace.failurePhase, "failurePhase")
        assertEquals("ECR17: no application response before timeout (120000 ms)", withTrace.failure, "failure")
        assertEquals(1, withTrace.sends, "sends dalla trace")

        val synthesized = terminal(OutcomeFixtures.unknown(withTrace = false))
        assertEquals(JournalPhase.UNKNOWN, synthesized.phase, "fase senza trace")
        assertNull(synthesized.sends, "nessun contatore senza trace")
        assertEquals("UNKNOWN", synthesized.outcome, "outcome")
    }

    @Test
    fun `Failed diventa UNKNOWN perche il mapper e totale`() {
        val r = terminal(OutcomeFixtures.failed(CommandCode.PAYMENT))
        assertEquals(JournalPhase.UNKNOWN, r.phase, "fase")
        assertEquals("FAILED", r.outcome, "outcome")
    }

    @Test
    fun `Acknowledged diventa RESULT`() {
        val r = terminal(CommandOutcome.Acknowledged(CommandCode.REPRINT, OutcomeFixtures.trace(CommandCode.REPRINT)))
        assertEquals(JournalPhase.RESULT, r.phase, "fase")
        assertEquals("ACKNOWLEDGED", r.outcome, "outcome")
    }

    @Test
    fun `CREATED con riepilogo, endpoint, profilo, id e opzioni`() {
        val request = PaymentRequest(amountCents = 10, cashRegisterId = "00000077")
        val r = JournalMapper.createdRecord("a", 0, 5, request, OutcomeFixtures.config)

        assertEquals(JournalPhase.CREATED, r.phase, "fase")
        assertEquals("P", r.command, "lettera del comando")
        assertEquals(10L, r.amountCents, "importo")
        assertTrue(r.requestSummary!!.startsWith("Pagamento 0,10 €"), "riepilogo: ${r.requestSummary}")
        assertEquals("INGENICO", r.profile, "profilo")
        assertEquals("127.0.0.1:5577", r.endpoint, "endpoint")
        assertEquals("12345678", r.terminalId, "TID")
        assertEquals("00000077", r.cashRegisterId, "ECR id della richiesta")
        assertEquals(OutcomeFixtures.config.options.summary(), r.optionsSummary, "opzioni")

        val reversal = JournalMapper.createdRecord("b", 0, 5, ReversalRequest(), OutcomeFixtures.config)
        assertEquals("00000042", reversal.cashRegisterId, "ECR id della configurazione")
        assertEquals("Storno · ultima transazione", reversal.requestSummary, "riepilogo storno")
    }

    @Test
    fun `RESOLVED con i campi dell'esito di G`() {
        val g = CommandOutcome.Answered(CommandCode.LAST_RESULT, OutcomeFixtures.approvedPayment(), OutcomeFixtures.trace(CommandCode.LAST_RESULT))
        val r = JournalMapper.resolvedRecord("a", 4, 7, ResolutionKind.CONFIRMED_BY_G, "G coincide", g)

        assertEquals(JournalPhase.RESOLVED, r.phase, "fase")
        assertEquals(ResolutionKind.CONFIRMED_BY_G, r.resolution, "resolution")
        assertEquals("G coincide", r.note, "nota")
        assertEquals("APPROVED", r.gVerdict, "gVerdict")
        assertEquals(Frames.E22, r.gRawResult, "gRawResult")
        assertEquals("000004", r.gStan, "gStan")
        assertEquals("000055", r.gOnlineId, "gOnlineId")
        assertEquals("0770947", r.gHostDateTime, "gHostDateTime")

        val dismissed = JournalMapper.resolvedRecord("a", 4, 7, ResolutionKind.DISMISSED, "scontrino", null)
        assertNull(dismissed.gRawResult, "senza G nessun campo g*")
    }

    /** M6: una riga CREATED persa non deve far sparire il tentativo né il suo blocco. */
    @Test
    fun `fold senza CREATED produce un'entry incompleta e bloccante`() {
        val entry = JournalMapper.fold(
            "a",
            listOf(
                JournalRecord("a", 1, 20, JournalPhase.SENDING, sends = 1),
                JournalRecord("a", 2, 30, JournalPhase.UNKNOWN),
            ),
        )

        assertNull(entry.command, "command")
        assertTrue(entry.incomplete, "incomplete")
        assertTrue(entry.blocksPayments, "blocksPayments")
        assertEquals(20L, entry.createdAt, "createdAt dal primo record")
        assertEquals("", entry.requestSummary, "campi descrittivi vuoti")
        assertEquals(1, entry.sends, "sends")
    }

    @Test
    fun `fold usa l'ultimo record diverso da EVIDENCE e l'ordine di seq`() {
        val entry = JournalMapper.fold(
            "a",
            listOf(
                JournalRecord("a", 2, 30, JournalPhase.UNKNOWN),
                JournalRecord("a", 0, 10, JournalPhase.CREATED, command = "P", amountCents = 10),
                JournalRecord("a", 3, 40, JournalPhase.EVIDENCE, rawResult = Frames.E22),
                JournalRecord("a", 1, 20, JournalPhase.SENDING, sends = 1),
            ),
        )

        assertEquals(CommandCode.PAYMENT, entry.command, "command")
        assertEquals(JournalPhase.UNKNOWN, entry.phase, "EVIDENCE non cambia la fase")
        assertEquals(listOf(0, 1, 2, 3), entry.records.map { it.seq }, "ordine di seq")
        assertEquals(40L, entry.lastUpdate, "lastUpdate")
        assertEquals(1, entry.evidence.size, "prova")
    }

    @Test
    fun `fold non persistito forza UNKNOWN`() {
        val entry = JournalMapper.fold(
            "a",
            listOf(JournalRecord("a", 0, 10, JournalPhase.CREATED, command = "P"), JournalRecord("a", 1, 20, JournalPhase.SENDING, sends = 1)),
            persisted = false,
        )
        assertEquals(JournalPhase.UNKNOWN, entry.phase, "fase forzata")
        assertFalse(entry.persisted, "persisted")
        assertTrue(entry.blocksPayments, "blocca")
    }

    @Test
    fun `knownResults raccoglie RESULT, RECEIVED, EVIDENCE e RESOLVED con G`() {
        val entry = JournalMapper.fold(
            "a",
            listOf(
                JournalRecord("a", 0, 10, JournalPhase.CREATED, command = "P"),
                JournalRecord("a", 1, 20, JournalPhase.RECEIVED, rawResult = "R1"),
                JournalRecord("a", 2, 30, JournalPhase.EVIDENCE, rawResult = "EV"),
                JournalRecord("a", 3, 40, JournalPhase.RESULT, rawResult = "RES", stan = "1", onlineId = "2", hostDateTime = "3"),
                JournalRecord("a", 4, 50, JournalPhase.RESOLVED, gRawResult = "G", gStan = "4", gOnlineId = "5", gHostDateTime = "6"),
            ),
        )
        val known = JournalMapper.knownResults(entry)

        assertEquals(
            listOf(KnownResultSource.RECEIVED, KnownResultSource.EVIDENCE, KnownResultSource.RESULT, KnownResultSource.G_RESOLUTION),
            known.map { it.source },
            "fonti",
        )
        val result = known.first { it.source == KnownResultSource.RESULT }
        assertEquals(Triple("1", "2", "3"), Triple(result.stan, result.onlineId, result.hostDateTime), "campi del RESULT")
        val g = known.first { it.source == KnownResultSource.G_RESOLUTION }
        assertEquals(listOf("G", "4", "5", "6"), listOf(g.raw, g.stan, g.onlineId, g.hostDateTime), "campi g*")
        assertNull(known.first { it.source == KnownResultSource.RECEIVED }.stan, "RECEIVED senza campi")
    }

    @Test
    fun `commandOf accetta lettera e nome`() {
        assertEquals(CommandCode.PAYMENT, JournalMapper.commandOf("P"), "lettera")
        assertEquals(CommandCode.PRE_AUTH, JournalMapper.commandOf("p"), "lettera minuscola distinta")
        assertEquals(CommandCode.REVERSAL, JournalMapper.commandOf("REVERSAL"), "nome")
        assertNull(JournalMapper.commandOf("Z"), "sconosciuta")
    }
}
