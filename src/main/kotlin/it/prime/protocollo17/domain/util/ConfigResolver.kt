package it.prime.protocollo17.domain.util

import it.prime.protocollo17.domain.model.command.ConfigResolution
import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.model.settings.OptionKey
import it.prime.protocollo17.domain.model.settings.ProtocolSettings
import it.prime.protocollo17.ecr17.profile.TerminalProfile

// STUB di WP0 (§8.2): firme definitive di §5.2.2, corpi a carico di WP5 (regole di resolve vincolanti in §5.2.2).

/**
 * Traduce le impostazioni persistite (testi) nella configurazione tipizzata del protocollo.
 *
 * Un valore illeggibile nel file non viene mai sostituito in silenzio (L§9.2 L1): diventa un ConfigIssue che
 * rende la configurazione non utilizzabile finché l'operatore non lo corregge.
 */
object ConfigResolver {
    fun resolve(settings: AppSettings): ConfigResolution = TODO("WP5: ConfigResolver.kt")

    /** Valori di "Ripristina valori del profilo". */
    fun profileDefaults(profile: TerminalProfile): ProtocolSettings = TODO("WP5: ConfigResolver.kt")

    /** Testo del valore per la UI ("stx", "WIRE", "2000"…). */
    fun textOf(protocol: ProtocolSettings, key: OptionKey): String = TODO("WP5: ConfigResolver.kt")

    /** Valida un testo inserito con i range dell'operatore: success(testo normalizzato) o failure(IllegalArgumentException, messaggio italiano). */
    fun parseInput(key: OptionKey, text: String): Result<String> = TODO("WP5: ConfigResolver.kt")

    /** Applica un valore già normalizzato da parseInput. */
    fun withValue(protocol: ProtocolSettings, key: OptionKey, normalized: String): ProtocolSettings =
        TODO("WP5: ConfigResolver.kt")
}

/**
 * Range dell'operatore (non del protocollo, §3.16). Usati da parseInput e, come avvisi, da resolve.
 * Sono separati da ProtocolOptions.validate() perché le configurazioni rapide dei test (ACK 40 ms) devono
 * restare valide per il protocollo pur essendo fuori dai valori sensati per un operatore.
 */
internal object OptionRanges {
    val ranges: Map<OptionKey, LongRange>
        get() = TODO("WP5: ConfigResolver.kt")
}
