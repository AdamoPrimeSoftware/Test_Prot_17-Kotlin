package it.prime.protocollo17.data.local.settings

import it.prime.protocollo17.domain.model.settings.AppSettings

/**
 * Migrazioni di settings.json da una `schemaVersion` alla successiva (§5.2.3), applicate da [SettingsRepository] dopo
 * la lettura. Pure: stesso ingresso, stessa uscita, nessun effetto.
 *
 * Servono solo quando cambia il DEFAULT di un campo che esiste già: il file salva anche i valori di default
 * (`encodeDefaults`), quindi un default nuovo non arriverebbe mai a chi ha già un settings.json. Un campo nuovo
 * invece non ha bisogno di migrazioni, prende il suo default alla lettura. Una versione più recente di quella
 * corrente (file scritto da un'app più nuova) resta com'è.
 */
internal object SettingsMigration {

    /** Default del timeout esito nella versione 1 e nella versione 2. */
    private const val V1_RESPONSE_TIMEOUT_MS = 120_000L
    private const val V2_RESPONSE_TIMEOUT_MS = 180_000L

    fun migrate(settings: AppSettings): AppSettings {
        var current = settings
        if (current.schemaVersion < 2) current = toV2(current)
        return current
    }

    /**
     * v1 → v2 (2026-10-16): il timeout esito passa da 120 a 180 s (un rifiuto reale è arrivato a 128,7 s, A§9.11), ma
     * SOLO se era ancora il vecchio default: un valore scelto dall'operatore in Opzioni resta il suo.
     */
    private fun toV2(settings: AppSettings): AppSettings {
        val protocol = settings.protocol
        val migratedProtocol = if (protocol.responseTimeoutMs == V1_RESPONSE_TIMEOUT_MS) {
            protocol.copy(responseTimeoutMs = V2_RESPONSE_TIMEOUT_MS)
        } else {
            protocol
        }
        return settings.copy(schemaVersion = 2, protocol = migratedProtocol)
    }
}
