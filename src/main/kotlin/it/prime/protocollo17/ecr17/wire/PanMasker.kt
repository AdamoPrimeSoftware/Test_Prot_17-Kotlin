package it.prime.protocollo17.ecr17.wire

// STUB di WP0 (§8.2): firme definitive di §3.8, corpi a carico di WP1.

/**
 * Mascheramento del PAN (A§11 punto 17, invariante M9).
 *
 * Dove si applica: `ProtocolEvent.Wire` RX ⇒ [maskFrame]; `ProtocolEvent.Discarded` ⇒ [maskDigitRuns];
 * righe S con `maskPan = true` ⇒ [maskDigitRuns] su raw e text; `SimEvent.FrameOut` ⇒ [maskFrame].
 */
object PanMasker {
    /** Vista UI (semantica rust maskPan): solo cifre, ultime 4 visibili; ≤ 4 cifre ⇒ "****". */
    fun maskForDisplay(pan: String): String = TODO("WP1: PanMasker.kt")

    /** Larghezza fissa: ogni cifra tranne le ultime 4 diventa '*', gli altri caratteri restano. */
    fun maskFixedWidth(field: String): String = TODO("WP1: PanMasker.kt")

    /**
     * Copia del payload applicativo mascherato secondo il codice in pos 10:
     * - 'i': PAN (pos 13 len 19) SEMPRE mascherato (la risposta 75 B non ha ramo KO, A§4.5.4);
     * - E, V, e, c, v: PAN (pos 13 len 19) mascherato se l'esito (pos 11-12) è diverso da "01";
     * - 'S' (riga di scontrino) e codici fuori dalle risposte v1 (U, K, altro): maskDigitRuns dalla pos 11;
     * - s, T, C: copia identica (nessun PAN; contengono lunghe sequenze di cifre che NON vanno toccate).
     */
    fun maskApplicationPayload(payload: ByteArray): ByteArray = TODO("WP1: PanMasker.kt")

    /** Come sopra ma su un frame STX…ETX LRC completo; l'LRC resta quello originale. */
    fun maskFrame(raw: ByteArray): ByteArray = TODO("WP1: PanMasker.kt")

    /**
     * Rete di sicurezza senza posizioni: ogni sequenza di ≥ 13 cifre ASCII consecutive diventa '*' tranne le
     * ultime 4. Per byte scartati (ProtocolEvent.Discarded), testo delle righe S, byte non applicativi del
     * log del simulatore. Può mascherare anche STAN/online in un frammento scartato: accettato.
     */
    fun maskDigitRuns(bytes: ByteArray): ByteArray = TODO("WP1: PanMasker.kt")

    fun maskDigitRuns(text: String): String = TODO("WP1: PanMasker.kt")
}
