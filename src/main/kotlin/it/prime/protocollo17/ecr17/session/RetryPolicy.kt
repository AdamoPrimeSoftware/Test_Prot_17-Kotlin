package it.prime.protocollo17.ecr17.session

/**
 * Unica regola di replay dopo una caduta (A§5.3, tabella di verità A§9.7).
 *
 * È un oggetto a sé perché è la regola più pericolosa del protocollo: un replay di un pagamento dopo una caduta
 * può addebitare due volte (M1). Isolata qui, è testata con tutte le 8 combinazioni e il client non può
 * "allargarla" per un singolo comando: solo s, T, G ed E hanno safeToRetryAfterReconnect.
 */
object RetryPolicy {
    /** Tabella di verità A§9.7: true solo per (true, true, true). */
    fun shouldRetryAfterReconnect(autoReconnect: Boolean, dropped: Boolean, safeToRetry: Boolean): Boolean =
        autoReconnect && dropped && safeToRetry
}
