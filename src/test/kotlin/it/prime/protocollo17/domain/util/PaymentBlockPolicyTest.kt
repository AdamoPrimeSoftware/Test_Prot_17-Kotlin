package it.prime.protocollo17.domain.util

import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.model.journal.JournalPhase
import it.prime.protocollo17.domain.model.journal.JournalRecord
import it.prime.protocollo17.ecr17.model.CommandCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaymentBlockPolicyTest {

    private val journaled = CommandCode.entries.filter { it.journaled }
    private val notJournaled = listOf(CommandCode.STATUS, CommandCode.TOTALS, CommandCode.LAST_RESULT, CommandCode.ECR_PRINTING, CommandCode.REPRINT)
    private val family = setOf(CommandCode.PAYMENT, CommandCode.PAYMENT_EXTENDED, CommandCode.REVERSAL, CommandCode.CARD_VERIFICATION)

    private fun entry(
        command: CommandCode?,
        phase: JournalPhase,
        needsReview: Boolean = false,
        persisted: Boolean = true,
        id: String = "a-${command?.wire ?: "x"}",
    ): JournalEntry {
        val records = buildList {
            if (command != null) add(JournalRecord(attemptId = id, seq = 0, at = 1, phase = JournalPhase.CREATED, command = command.wire.toString()))
            add(JournalRecord(attemptId = id, seq = 1, at = 2, phase = phase, needsReview = if (needsReview) true else null))
        }
        return JournalEntry(
            attemptId = id, command = command, createdAt = 1, amountCents = null, requestSummary = "", endpoint = "",
            profile = "", phase = phase, lastUpdate = 2, sends = 1, records = records, persisted = persisted,
        )
    }

    @Test
    fun `UNKNOWN di P blocca tutti i comandi journaled`() {
        val unresolved = listOf(entry(CommandCode.PAYMENT, JournalPhase.UNKNOWN))
        for (c in journaled) assertTrue(PaymentBlockPolicy.isBlocked(c, unresolved), "$c bloccato")
        assertEquals(journaled.toSet(), PaymentBlockPolicy.blockedCommands(unresolved), "blockedCommands")
    }

    /** Un 'E' tardivo dell'H potrebbe essere letto come esito di un P: H blocca la sua famiglia, non i p i c C. */
    @Test
    fun `UNKNOWN di H blocca P X S H ma non p i c C`() {
        val unresolved = listOf(entry(CommandCode.CARD_VERIFICATION, JournalPhase.UNKNOWN))

        assertEquals(family, PaymentBlockPolicy.blockedCommands(unresolved), "famiglia E/V")
        for (c in listOf(CommandCode.PRE_AUTH, CommandCode.INCREMENTAL_AUTH, CommandCode.PRE_AUTH_CLOSURE, CommandCode.CLOSE_SESSION)) {
            assertFalse(PaymentBlockPolicy.isBlocked(c, unresolved), "$c non bloccato")
        }
    }

    @Test
    fun `UNKNOWN di C blocca solo C`() {
        val unresolved = listOf(entry(CommandCode.CLOSE_SESSION, JournalPhase.UNKNOWN))
        assertEquals(setOf(CommandCode.CLOSE_SESSION), PaymentBlockPolicy.blockedCommands(unresolved), "solo C")
    }

    @Test
    fun `RESULT da verificare di P blocca i journaled`() {
        val unresolved = listOf(entry(CommandCode.PAYMENT, JournalPhase.RESULT, needsReview = true))
        assertEquals(journaled.toSet(), PaymentBlockPolicy.blockedCommands(unresolved), "da verificare blocca")
        assertEquals(unresolved, PaymentBlockPolicy.blocking(CommandCode.PRE_AUTH, unresolved), "entry bloccante restituita")
    }

    /** Una riga CREATED corrotta non deve far sparire un tentativo bloccante (M6). */
    @Test
    fun `entry con dati incompleti blocca tutti i journaled`() {
        val unresolved = listOf(entry(null, JournalPhase.UNKNOWN))
        assertEquals(journaled.toSet(), PaymentBlockPolicy.blockedCommands(unresolved), "incompleta blocca")
    }

    @Test
    fun `entry non persistita forzata a UNKNOWN blocca`() {
        // In memoria la fase è forzata a UNKNOWN anche se l'ultimo record scritto è SENDING (§5.3.3).
        val forced = entry(CommandCode.PAYMENT, JournalPhase.SENDING).copy(phase = JournalPhase.UNKNOWN, persisted = false)
        assertTrue(PaymentBlockPolicy.isBlocked(CommandCode.PAYMENT, listOf(forced)), "P bloccato")
    }

    @Test
    fun `s T G E R mai bloccati`() {
        val unresolved = listOf(
            entry(CommandCode.PAYMENT, JournalPhase.UNKNOWN, id = "p"),
            entry(null, JournalPhase.UNKNOWN, id = "x"),
            entry(CommandCode.CARD_VERIFICATION, JournalPhase.UNKNOWN, id = "h"),
        )
        for (c in notJournaled) {
            assertTrue(PaymentBlockPolicy.blocking(c, unresolved).isEmpty(), "$c mai bloccato")
        }
    }

    @Test
    fun `entry chiuse non bloccano`() {
        val closed = listOf(entry(CommandCode.PAYMENT, JournalPhase.RESULT), entry(CommandCode.PAYMENT, JournalPhase.RESOLVED, id = "r"))
        assertTrue(PaymentBlockPolicy.blockedCommands(closed).isEmpty(), "nessun blocco")
        assertTrue(PaymentBlockPolicy.blockedCommands(emptyList()).isEmpty(), "lista vuota")
    }
}
