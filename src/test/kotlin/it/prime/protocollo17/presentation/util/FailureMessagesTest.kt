package it.prime.protocollo17.presentation.util

import it.prime.protocollo17.ecr17.error.ConfigProblem
import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.error.RequestField
import it.prime.protocollo17.ecr17.error.ValidationError
import it.prime.protocollo17.ecr17.event.ProtocolWarning
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.model.CloseCause
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.presentation.UiFixtures
import it.prime.protocollo17.presentation.state.RunStart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Testi per l'operatore (§6.19). Prima lo stub mostrava "ECR17: no ACK after 3 attempt(s)": vero ma non dice cosa
 * fare. Con un esito sconosciuto il testo deve SEMPRE indicare Ultimo esito (G), unica via per sbloccare (U7).
 */
class FailureMessagesTest {

    private val endpoint = Endpoint("192.168.1.131", 5577)

    private val allFailures: List<Ecr17Failure> = listOf(
        Ecr17Failure.Validation(listOf(ValidationError.NotDigits(RequestField.CASH_REGISTER_ID, "12AB"), ValidationError.NonAscii(RequestField.RECEIPT_TEXT, 4))),
        Ecr17Failure.ConfigInvalid(listOf(ConfigProblem("port", "is required"), ConfigProblem("ackTimeoutMs", "must be greater than 0 (was 0)"))),
        Ecr17Failure.Busy,
        Ecr17Failure.ConnectFailed(endpoint, "Connection refused"),
        Ecr17Failure.ConnectTimeout(endpoint, 5_000),
        Ecr17Failure.PrintingSetupFailed(Ecr17Failure.AckTimeout(3)),
        Ecr17Failure.Nak(4),
        Ecr17Failure.AckTimeout(3),
        Ecr17Failure.ResponseTimeout(120_000),
        Ecr17Failure.Disconnected(CloseCause.PEER_CLOSED, "EOF"),
        Ecr17Failure.WriteFailed("Broken pipe"),
        Ecr17Failure.Cancelled(byUser = true),
        Ecr17Failure.Internal("boom"),
    )

    /** Ogni variante ha un testo italiano, mai il messaggio tecnico con il prefisso dei log. */
    @Test
    fun `ogni failure ha un testo italiano senza prefisso ECR17`() {
        for (failure in allFailures) for (command in listOf(CommandCode.PAYMENT, CommandCode.STATUS)) {
            val text = FailureMessages.forFailure(failure, command, unknown = false)
            assertTrue(text.isNotBlank(), "testo vuoto per $failure")
            assertFalse(text.contains("ECR17:"), "prefisso tecnico in '$text'")
            assertFalse(text.contains(failure.message), "messaggio inglese grezzo in '$text'")
        }
    }

    /** Esito sconosciuto ⇒ l'indicazione su G c'è sempre (U7), per i pagamenti con il blocco dei pagamenti. */
    @Test
    fun `con unknown il testo indica Ultimo esito G`() {
        for (failure in allFailures) {
            val payment = FailureMessages.forFailure(failure, CommandCode.PAYMENT, unknown = true)
            assertTrue(payment.contains("Ultimo esito (G)"), "manca G in '$payment'")
            assertTrue(payment.contains("ESITO SCONOSCIUTO"), "manca l'avviso di blocco in '$payment'")
            val verify = FailureMessages.forFailure(failure, CommandCode.CARD_VERIFICATION, unknown = true)
            assertTrue(verify.contains("Ultimo esito (G)"), "manca G in '$verify'")
            assertFalse(verify.contains("nuovo pagamento"), "H blocca solo la sua famiglia: '$verify'")
        }
    }

    /** NAK dopo invii senza risposta: non è un rifiuto certo, un invio precedente può essere passato. */
    @Test
    fun `Nak dentro Unknown parla di invii rimasti senza risposta`() {
        val outcome = CommandOutcome.Unknown(CommandCode.PAYMENT, Ecr17Failure.Nak(2), ExchangePhase.AWAITING_ACK, trace = null)
        val text = FailureMessages.forOutcome(outcome)!!
        assertTrue(text.contains("invii rimasti senza risposta"), text)
        val refused = CommandOutcome.Refused(CommandCode.PAYMENT, Ecr17Failure.Nak(4), UiFixtures.trace(CommandCode.PAYMENT, sends = 4, naksReceived = 4))
        assertTrue(FailureMessages.forOutcome(refused)!!.contains("NAK × 4"))
    }

    /** "port: is required" diventa una frase italiana che dice dove correggere. */
    @Test
    fun `forConfigProblem della porta in italiano`() {
        val text = FailureMessages.forConfigProblem(ConfigProblem("port", "is required"))
        assertTrue(text.contains("Porta"), text)
        assertTrue(text.contains("Opzioni"), text)
        assertFalse(text.contains("required"), text)
        assertEquals(
            "Timeout ACK: deve essere maggiore di 0 (ora 0)",
            FailureMessages.forConfigProblem(ConfigProblem("ackTimeoutMs", "must be greater than 0 (was 0)")),
        )
        // Un ConfigIssue di ConfigResolver è già italiano: passa così com'è.
        assertEquals("Valore 'sxt' non valido per LRC applicativo",
            FailureMessages.forConfigProblem(ConfigProblem("appLrcMode", "Valore 'sxt' non valido per LRC applicativo")))
    }

    /** Errore interno prima dell'invio ⇒ "NON è partito"; dopo (M10) ⇒ esito sconosciuto con G. */
    @Test
    fun `Internal distingue prima e dopo l'invio`() {
        val notSent = FailureMessages.forOutcome(CommandOutcome.NotSent(CommandCode.PAYMENT, Ecr17Failure.Internal("disco pieno"), null))!!
        assertTrue(notSent.contains("NON è partito"), notSent)
        val unknown = FailureMessages.forOutcome(CommandOutcome.Unknown(CommandCode.PAYMENT, Ecr17Failure.Internal("bug"), ExchangePhase.AWAITING_RESULT, null))!!
        assertTrue(unknown.contains("dopo l'invio") && unknown.contains("Ultimo esito (G)"), unknown)
        val cancelledBeforeSend = FailureMessages.forOutcome(CommandOutcome.NotSent(CommandCode.PAYMENT, Ecr17Failure.Cancelled(true), null))!!
        assertFalse(cancelledBeforeSend.contains("Ultimo esito (G)"), "annullato prima dell'invio: nessun G")
    }

    /** Esiti positivi senza revisione non hanno testo d'errore; quelli da verificare sì (M6). */
    @Test
    fun `forOutcome null per gli esiti positivi e testo per quelli da verificare`() {
        assertNull(FailureMessages.forOutcome(UiFixtures.answered(CommandCode.PAYMENT, UiFixtures.payment())))
        val duplicate = UiFixtures.answered(
            CommandCode.PAYMENT,
            UiFixtures.payment(),
            UiFixtures.trace(CommandCode.PAYMENT, sends = 2, timeoutRetransmissions = 1, warnings = listOf(ProtocolWarning(WarningCode.POSSIBLE_DUPLICATE, "dup", 1_500))),
        )
        assertTrue(FailureMessages.forOutcome(duplicate)!!.contains("doppio addebito"))
    }

    @Test
    fun `forRunStart e forCloseCause`() {
        assertNull(FailureMessages.forRunStart(RunStart.Started))
        assertTrue(FailureMessages.forRunStart(RunStart.Busy)!!.contains("già in corso"))
        assertTrue(FailureMessages.forRunStart(RunStart.Blocked(listOf(UiFixtures.entry())))!!.contains("Ultimo esito (G)"))
        assertTrue(FailureMessages.forRunStart(RunStart.InvalidConfig(listOf("porta mancante")))!!.contains("porta mancante"))
        CloseCause.entries.forEach { assertTrue(FailureMessages.forCloseCause(it).isNotBlank()) }
    }
}
