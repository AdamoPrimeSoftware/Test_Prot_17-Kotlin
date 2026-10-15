package it.prime.protocollo17.domain.util

import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.ecr17.model.CommandCode

// STUB di WP0 (§8.2): firme definitive di §5.8, corpi a carico di WP5.

/**
 * Quali comandi sono bloccati dai tentativi non risolti (U7, M6). Pura: la usano il servizio applicativo (che
 * rifiuta) e la Console (che disabilita Esegui), così le due difese applicano la stessa regola.
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
    fun blocking(command: CommandCode, unresolved: List<JournalEntry>): List<JournalEntry> =
        TODO("WP5: PaymentBlockPolicy.kt")

    fun isBlocked(command: CommandCode, unresolved: List<JournalEntry>): Boolean = blocking(command, unresolved).isNotEmpty()

    /** Insieme dei comandi bloccati (per sidebar e banner). */
    fun blockedCommands(unresolved: List<JournalEntry>): Set<CommandCode> = TODO("WP5: PaymentBlockPolicy.kt")
}
