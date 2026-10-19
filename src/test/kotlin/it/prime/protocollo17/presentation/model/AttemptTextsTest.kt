package it.prime.protocollo17.presentation.model

import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.presentation.UiFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AttemptTextsTest {

    @Test
    fun `avvio senza tentativi rimasti in volo non ha avviso`() {
        assertNull(AttemptTexts.startupRecoveryText(emptyList()))
        assertNull(AttemptTexts.startupRecoveryText(listOf(UiFixtures.entry(phase = JournalPhase.RESULT))))
    }

    @Test
    fun `un pagamento senza esito blocca i pagamenti`() {
        assertEquals(
            "All'ultimo avvio 1 operazione è rimasta senza esito: pagamenti bloccati finché non la risolvi",
            AttemptTexts.startupRecoveryText(listOf(UiFixtures.entry(command = CommandCode.PAYMENT))),
        )
    }

    @Test
    fun `una sola C blocca solo la chiusura contabile`() {
        assertEquals(
            "All'ultimo avvio 1 operazione è rimasta senza esito: nuova chiusura contabile bloccata finché non la risolvi",
            AttemptTexts.startupRecoveryText(listOf(UiFixtures.entry(command = CommandCode.CLOSE_SESSION, amountCents = null))),
        )
    }

    @Test
    fun `H e C elencano i comandi bloccati per famiglia`() {
        val recovered = listOf(
            UiFixtures.entry(attemptId = "h", command = CommandCode.CARD_VERIFICATION, amountCents = null),
            UiFixtures.entry(attemptId = "c", command = CommandCode.CLOSE_SESSION, amountCents = null),
        )
        assertEquals(
            "All'ultimo avvio 2 operazioni sono rimaste senza esito: bloccati P, X, S, H e C finché non le risolvi",
            AttemptTexts.startupRecoveryText(recovered),
        )
    }

    @Test
    fun `dati incompleti bloccano i pagamenti`() {
        assertEquals(
            "All'ultimo avvio 1 operazione è rimasta senza esito: pagamenti bloccati finché non la risolvi",
            AttemptTexts.startupRecoveryText(listOf(UiFixtures.entry(command = null))),
        )
    }
}
