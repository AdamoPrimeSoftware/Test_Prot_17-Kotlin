package it.prime.protocollo17.domain.util

import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.JournalRecord
import it.prime.protocollo17.domain.model.journal.KnownResult
import it.prime.protocollo17.domain.model.journal.KnownResultSource
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.PaymentResponse
import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.presentation.UiFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Suggerimento dopo `G` (§5.3.4, R06): l'operatore decide se chiudere un pagamento sconosciuto come eseguito o no
 * guardando questo verdetto. Un "probabilmente questo tentativo" sbagliato porta a non rifare un pagamento mai
 * avvenuto, o a rifarne uno già addebitato.
 */
class GAssessmentTest {

    private val unknownPayment = UiFixtures.entry(attemptId = "attempt-new", command = CommandCode.PAYMENT)

    private fun g(response: PaymentResponse) = UiFixtures.answered(CommandCode.LAST_RESULT, response)

    private fun known(
        raw: String,
        source: KnownResultSource = KnownResultSource.RESULT,
        stan: String? = null,
        online: String? = null,
        dateTime: String? = null,
    ) = KnownResult("attempt-old", source, raw, stan, online, dateTime, at = 500)

    /**
     * Prima si confrontava solo con l'ultimo RESULT: un tentativo precedente chiuso CONFIRMED_BY_G (esito presente
     * solo come gRawResult) dava LIKELY_THIS_ATTEMPT a un G che ripeteva proprio quell'esito. Ora vince il payload.
     */
    @Test
    fun `G uguale all'esito di un tentativo chiuso con G e' un esito precedente`() {
        val earlier = listOf(known(Frames.E22, source = KnownResultSource.G_RESOLUTION))
        val result = GAssessment.assess(unknownPayment, earlier, g(UiFixtures.payment(Frames.E22)))
        assertEquals(GVerdict.LIKELY_PREVIOUS_RESULT, result.verdict, result.explanation)
        assertTrue(result.explanation.contains("NON"), "la spiegazione deve dire che il tentativo non è stato eseguito")
    }

    /** Payload identico a un RESULT registrato prima: G ripete la transazione precedente. */
    @Test
    fun `payload uguale a un RESULT precedente e' un esito precedente`() {
        val earlier = listOf(known(Frames.E4), known(Frames.E22))
        val result = GAssessment.assess(unknownPayment, earlier, g(UiFixtures.payment(Frames.E22)))
        assertEquals(GVerdict.LIKELY_PREVIOUS_RESULT, result.verdict)
    }

    /** Payload diverso ma STAN, online id e data/ora host tutti uguali: stessa transazione. */
    @Test
    fun `STAN online e data ora tutti uguali danno un esito precedente`() {
        val otherRaw = Frames.E22.replace("R48432", "R00000")
        val earlier = listOf(known(otherRaw, stan = "000004", online = "000055", dateTime = "0770947"))
        val result = GAssessment.assess(unknownPayment, earlier, g(UiFixtures.payment(Frames.E22)))
        assertEquals(GVerdict.LIKELY_PREVIOUS_RESULT, result.verdict)
    }

    /** Un campo assente da entrambe le parti non è "uguale": null ≠ null. */
    @Test
    fun `data ora null da entrambe le parti non rende uguali due esiti`() {
        val gResponse = UiFixtures.payment(Frames.E22).copy(hostDateTimeRaw = null)
        val earlier = listOf(known(Frames.E4, stan = "000004", online = "000055", dateTime = null))
        val result = GAssessment.assess(unknownPayment, earlier, g(gResponse))
        assertEquals(GVerdict.LIKELY_THIS_ATTEMPT, result.verdict, "STAN e online uguali non bastano senza data/ora")
    }

    /** KO senza data/ora: lo STAN di un rifiuto non è univoco (A§4.5.1), nessuna attribuzione. */
    @Test
    fun `rifiuto senza data ora con payload diverso e' inconcludente`() {
        val earlier = listOf(known(Frames.E22, stan = "000004", online = "000055", dateTime = "0770947"))
        val result = GAssessment.assess(unknownPayment, earlier, g(UiFixtures.payment(Frames.E4)))
        assertEquals(GVerdict.INCONCLUSIVE, result.verdict)
    }

    /** Il payload arrivato in ritardo per questo tentativo (EVIDENCE) vince anche sulla regola del KO. */
    @Test
    fun `payload uguale all'EVIDENCE del tentativo e' questo tentativo`() {
        val attempt = UiFixtures.entry(
            attemptId = "attempt-new",
            extra = listOf(JournalRecord("attempt-new", 5, 1_200, JournalPhase.EVIDENCE, rawResult = Frames.E4)),
        )
        val result = GAssessment.assess(attempt, emptyList(), g(UiFixtures.payment(Frames.E4)))
        assertEquals(GVerdict.LIKELY_THIS_ATTEMPT, result.verdict)
    }

    /** Esito del tipo atteso per il comando e mai visto prima: probabilmente è questo tentativo. */
    @Test
    fun `famiglia coerente e' questo tentativo`() {
        val result = GAssessment.assess(unknownPayment, listOf(known(Frames.E4)), g(UiFixtures.payment(Frames.E22)))
        assertEquals(GVerdict.LIKELY_THIS_ATTEMPT, result.verdict)
    }

    /** Una 'e' (preautorizzazione) non può essere l'esito di un P. */
    @Test
    fun `una e per un tentativo P e' inconcludente`() {
        val gPreAuth = UiFixtures.answered(CommandCode.LAST_RESULT, UiFixtures.preAuth())
        val result = GAssessment.assess(unknownPayment, emptyList(), gPreAuth)
        assertEquals(GVerdict.INCONCLUSIVE, result.verdict)
    }

    /** Tentativo con dati incompleti (CREATED illeggibile): impossibile dire se l'esito gli appartiene. */
    @Test
    fun `tentativo senza comando e' inconcludente`() {
        val incomplete = UiFixtures.entry(attemptId = "attempt-new", command = null)
        val result = GAssessment.assess(incomplete, emptyList(), g(UiFixtures.payment(Frames.E22)))
        assertEquals(GVerdict.INCONCLUSIVE, result.verdict)
    }

    /** Una risposta che non è di transazione (stato) non si attribuisce a nessun tentativo. */
    @Test
    fun `risposta non di transazione e' inconcludente`() {
        val result = GAssessment.assess(unknownPayment, emptyList(), UiFixtures.answered(CommandCode.LAST_RESULT, UiFixtures.status()))
        assertEquals(GVerdict.INCONCLUSIVE, result.verdict)
    }
}
