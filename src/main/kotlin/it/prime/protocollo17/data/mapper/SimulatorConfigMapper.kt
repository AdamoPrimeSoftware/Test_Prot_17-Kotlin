package it.prime.protocollo17.data.mapper

import it.prime.protocollo17.domain.model.settings.SimulatorSettings
import it.prime.protocollo17.ecr17.sim.SimDecision
import it.prime.protocollo17.ecr17.sim.SimFault
import it.prime.protocollo17.ecr17.sim.SimulatorConfig
import it.prime.protocollo17.ecr17.wire.ControlLrcRule
import it.prime.protocollo17.ecr17.wire.LrcMode

/** Configurazione del simulatore ricavata dalle impostazioni, con gli avvisi per i valori illeggibili. */
data class SimulatorConfigMapping(val config: SimulatorConfig, val warnings: List<String>)

/**
 * Impostazioni del simulatore (testi) → [SimulatorConfig] dell'engine.
 *
 * Al contrario di ConfigResolver, un valore illeggibile qui NON blocca: il simulatore è uno strumento di sviluppo,
 * quindi si usa il default e si lascia un avviso nel log del simulatore. Bloccarlo per un guasto scritto male
 * renderebbe impossibile proprio la prova che l'operatore stava cercando di fare.
 */
object SimulatorConfigMapper {

    fun toConfig(settings: SimulatorSettings): SimulatorConfigMapping {
        val warnings = mutableListOf<String>()
        val defaults = SimulatorConfig()

        fun <T> read(label: String, raw: String, parsed: T?, fallback: T, fallbackText: String): T {
            if (parsed != null) return parsed
            warnings += "Valore '$raw' non valido per $label: uso $fallbackText"
            return fallback
        }

        fun delay(label: String, value: Long, fallback: Long): Long {
            if (value >= 0) return value
            warnings += "$label negativo ($value ms): uso $fallback ms"
            return fallback
        }

        val appLrcMode = read("LRC applicativo", settings.appLrcMode, LrcMode.fromWireName(settings.appLrcMode), defaults.appLrcMode, defaults.appLrcMode.wireName)
        val ackRule = read("regola dell'ACK", settings.ackRule, enumOf<ControlLrcRule>(settings.ackRule), defaults.ackRule, defaults.ackRule.name)
        val decision = read("decisione", settings.decision, enumOf<SimDecision>(settings.decision), defaults.decision, defaults.decision.name)
        val fault = read("guasto", settings.fault, enumOf<SimFault>(settings.fault), defaults.fault, defaults.fault.name)
        val port = if (settings.port in 0..65_535) settings.port else {
            warnings += "Porta ${settings.port} non valida: uso 0 (porta libera)"
            0
        }
        val terminalId = settings.terminalId.trim().takeIf { id -> id.length <= 8 && id.all { it in '0'..'9' } } ?: run {
            warnings += "Terminal ID '${settings.terminalId}' non valido: uso ${defaults.terminalId}"
            defaults.terminalId
        }

        val config = defaults.copy(
            port = port,
            terminalId = terminalId.padStart(8, '0'),
            appLrcMode = appLrcMode,
            ackRule = ackRule,
            ackDelayMs = delay("Ritardo ACK", settings.ackDelayMs, defaults.ackDelayMs),
            progressDelayMs = delay("Ritardo progress", settings.progressDelayMs, defaults.progressDelayMs),
            resultDelayMs = delay("Ritardo esito", settings.resultDelayMs, defaults.resultDelayMs),
            decision = decision,
            fault = fault,
            faultOnlyFinancial = settings.faultOnlyFinancial,
            closeAfterTransaction = settings.closeAfterTransaction,
            retransmitResultIfNoValidAck = settings.retransmitResultIfNoValidAck,
            receiptAfterResult = settings.receiptAfterResult,
        )
        return SimulatorConfigMapping(config, warnings)
    }

    private inline fun <reified E : Enum<E>> enumOf(value: String): E? =
        enumValues<E>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
}
