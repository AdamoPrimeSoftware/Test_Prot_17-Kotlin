package it.prime.protocollo17.domain.util

import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.ecr17.model.CommandCode

/**
 * Quali comandi sono bloccati dai tentativi non risolti (U7, M6). Pura: la usano il servizio applicativo (che
 * rifiuta) e la Console (che disabilita Esegui), così le due difese applicano la stessa regola.
 *
 * Prima di questa regola per famiglia un `H` sconosciuto non bloccava nulla (non muove denaro): ma un suo 'E'
 * tardivo, arrivato durante il pagamento successivo, sarebbe stato letto come esito di quel pagamento (§3.9).
 */
object PaymentBlockPolicy {
    /**
     * Tentativi non risolti che bloccano command (vuoto ⇒ consentito). Regole (U7, M6):
     * - command non journaled (s T G E R) ⇒ mai bloccato;
     * - entry.blocksPayments (RECORD_AND_BLOCK, da verificare, dati incompleti) ⇒ blocca ogni comando journaled;
     * - entry H/C (RECORD) non risolta ⇒ blocca i comandi la cui famiglia di risposta interseca la sua
     *   (expectedReplies): un H blocca P X S H, un C blocca C. Motivo: un 'E' tardivo dell'H potrebbe essere letto
     *   come esito di un P (§3.9).
     */
    fun blocking(command: CommandCode, unresolved: List<JournalEntry>): List<JournalEntry> {
        // G è sempre ammesso: è proprio il comando che serve a risolvere un esito sconosciuto.
        if (!command.journaled) return emptyList()
        return unresolved.filter { entry ->
            // Difensivo: la lista arriva da JournalRepository.unresolved, ma una entry già chiusa non blocca mai.
            entry.isUnresolved && (entry.blocksPayments || sharesReplyFamily(entry.command, command))
        }
    }

    fun isBlocked(command: CommandCode, unresolved: List<JournalEntry>): Boolean = blocking(command, unresolved).isNotEmpty()

    /** Insieme dei comandi bloccati (per sidebar e banner). */
    fun blockedCommands(unresolved: List<JournalEntry>): Set<CommandCode> =
        CommandCode.entries.filterTo(LinkedHashSet()) { isBlocked(it, unresolved) }

    /** true se un esito tardivo del comando dell'entry potrebbe essere scambiato per l'esito di [command]. */
    private fun sharesReplyFamily(entryCommand: CommandCode?, command: CommandCode): Boolean =
        entryCommand != null && entryCommand.expectedReplies.any { it in command.expectedReplies }
}
