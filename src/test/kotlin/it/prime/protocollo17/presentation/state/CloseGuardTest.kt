package it.prime.protocollo17.presentation.state

import it.prime.protocollo17.ecr17.model.CardVerificationRequest
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.Ecr17Request
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.PaymentRequest
import it.prime.protocollo17.ecr17.model.StatusRequest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Conferma alla chiusura dell'app (R11): va chiesta solo quando chiudere lascerebbe un esito sconosciuto, cioè
 * per un comando journaled che ha già raggiunto SENDING. Prima di SENDING il comando non è partito; i comandi
 * non journaled non muovono denaro.
 */
class CloseGuardTest {

    private fun running(command: CommandCode, request: Ecr17Request, phase: ExchangePhase) = RunningCommand(
        command = command,
        request = request,
        origin = RunOrigin.CONSOLE,
        attemptId = if (command.journaled) "attempt-1" else null,
        startedAt = 1_000,
        phase = phase,
        phaseAt = 1_000,
        sends = if (phase.ordinal >= ExchangePhase.SENDING.ordinal) 1 else 0,
        implicitGoAhead = false,
        resultWaitStartedAt = null,
        lastProgress = null,
        receipt = emptyList(),
        warnings = emptyList(),
        cancelRequested = false,
        ackTimeoutMs = 2_000,
        responseTimeoutMs = 120_000,
    )

    /** A runner inattivo la chiusura è immediata: nessun dialogo inutile all'uscita. */
    @Test
    fun `nessun comando in corso non chiede conferma`() {
        assertFalse(closeNeedsConfirmation(RunnerState()))
    }

    /** Un comando non journaled (s) non chiede conferma nemmeno in attesa dell'esito: non muove denaro. */
    @Test
    fun `stato terminale in attesa dell'esito non chiede conferma`() {
        val state = RunnerState(running = running(CommandCode.STATUS, StatusRequest, ExchangePhase.AWAITING_RESULT))
        assertFalse(closeNeedsConfirmation(state), "s non è journaled: chiudere non lascia esiti sconosciuti")
    }

    /** Un pagamento ancora in CONNECTING non è partito: chiudere non lascia un esito sconosciuto. */
    @Test
    fun `pagamento in connessione non chiede conferma`() {
        val state = RunnerState(running = running(CommandCode.PAYMENT, PaymentRequest(amountCents = 10), ExchangePhase.CONNECTING))
        assertFalse(closeNeedsConfirmation(state), "prima di SENDING il pagamento non è partito")
    }

    /** Il confine è SENDING incluso: da lì in poi chiudere lascerebbe il pagamento con esito sconosciuto (R11). */
    @Test
    fun `pagamento in invio chiede conferma`() {
        val state = RunnerState(running = running(CommandCode.PAYMENT, PaymentRequest(amountCents = 10), ExchangePhase.SENDING))
        assertTrue(closeNeedsConfirmation(state), "da SENDING l'esito resterebbe sconosciuto")
    }

    /** Conta journaled, non "finanziario": H non muove denaro ma il suo esito sconosciuto blocca P X S H. */
    @Test
    fun `verifica carta in attesa dell'ACK chiede conferma`() {
        val state = RunnerState(running = running(CommandCode.CARD_VERIFICATION, CardVerificationRequest(), ExchangePhase.AWAITING_ACK))
        assertTrue(closeNeedsConfirmation(state), "H è journaled (RECORD): un esito sconosciuto blocca la sua famiglia")
    }
}
