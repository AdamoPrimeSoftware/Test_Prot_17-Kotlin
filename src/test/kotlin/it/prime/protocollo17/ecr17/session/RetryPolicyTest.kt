package it.prime.protocollo17.ecr17.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * RP-01: tabella di verità del replay dopo una caduta (A§9.7).
 *
 * Protegge M1: basta che una delle tre condizioni manchi (opzione disattivata, nessuna caduta, comando non
 * ripetibile come un pagamento) perché il comando NON venga ripetuto.
 */
class RetryPolicyTest {

    @Test
    fun `RP-01 tabella di verita del replay`() {
        val flags = listOf(false, true)
        for (autoReconnect in flags) for (dropped in flags) for (safeToRetry in flags) {
            val expected = autoReconnect && dropped && safeToRetry
            assertEquals(
                expected,
                RetryPolicy.shouldRetryAfterReconnect(autoReconnect, dropped, safeToRetry),
                "autoReconnect=$autoReconnect dropped=$dropped safeToRetry=$safeToRetry",
            )
        }
        assertEquals(true, RetryPolicy.shouldRetryAfterReconnect(autoReconnect = true, dropped = true, safeToRetry = true))
    }
}
