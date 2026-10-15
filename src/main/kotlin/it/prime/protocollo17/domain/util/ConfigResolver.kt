package it.prime.protocollo17.domain.util

import it.prime.protocollo17.domain.model.command.ConfigIssue
import it.prime.protocollo17.domain.model.command.ConfigResolution
import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.model.settings.OptionKey
import it.prime.protocollo17.domain.model.settings.ProtocolSettings
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.TerminalIds
import it.prime.protocollo17.ecr17.profile.AppLrcRxPolicy
import it.prime.protocollo17.ecr17.profile.ConnectionMode
import it.prime.protocollo17.ecr17.profile.ControlLrcRxPolicy
import it.prime.protocollo17.ecr17.profile.Ecr17Config
import it.prime.protocollo17.ecr17.profile.Evidence
import it.prime.protocollo17.ecr17.profile.FinancialAckPolicy
import it.prime.protocollo17.ecr17.profile.ProtocolOptions
import it.prime.protocollo17.ecr17.profile.TerminalProfile
import it.prime.protocollo17.ecr17.profile.TerminalProfileId
import it.prime.protocollo17.ecr17.profile.TerminalProfiles
import it.prime.protocollo17.ecr17.wire.ControlLrcRule
import it.prime.protocollo17.ecr17.wire.LrcMode

/**
 * Traduce le impostazioni persistite (testi) nella configurazione tipizzata del protocollo.
 *
 * Un valore illeggibile nel file non viene mai sostituito in silenzio (L§9.2 L1): diventa un ConfigIssue che
 * rende la configurazione non utilizzabile finché l'operatore non lo corregge. Prima i repo ricadevano su `std`
 * con un `appLrcMode` scritto male, e il terminale rispondeva NAK a ogni comando senza una spiegazione.
 *
 * Le chiavi di [ConfigIssue] sono i nomi dei campi (`appLrcMode`, `ackTimeoutMs`, `port`, `terminalId`…), le
 * stesse di [it.prime.protocollo17.ecr17.error.ConfigProblem]: la UI li traduce con un'unica tabella.
 */
object ConfigResolver {

    /** Larghezza dei campi TID ed ECR id sul filo (RequestEncoder li allinea a destra con zeri). */
    private const val DIGITS_MAX = 8

    fun resolve(settings: AppSettings): ConfigResolution {
        val issues = mutableListOf<ConfigIssue>()
        val warnings = mutableListOf<ConfigIssue>()

        // ─── Profilo ───────────────────────────────────────────────────────────
        val profileId = parseProfileId(settings.terminal.profile)
        if (profileId == null) {
            issues += ConfigIssue(
                "profile",
                "Profilo '${settings.terminal.profile}' non riconosciuto: scegli Ingenico o CCV in Opzioni",
            )
        }
        // Profilo sconosciuto: INGENICO serve solo a costruire una config, che resta comunque non utilizzabile.
        val profile = TerminalProfiles.of(profileId ?: TerminalProfileId.INGENICO)
        if (profileId != null && profile.evidence == Evidence.ASSUMPTION) {
            warnings += ConfigIssue("profile", "Profilo ${profile.displayName} non verificato: valori copiati da Ingenico")
        }

        // ─── Opzioni di protocollo ─────────────────────────────────────────────
        val options = toOptions(settings.protocol, profile.defaults, issues)
        for ((key, range) in OptionRanges.ranges) {
            val value = numericValue(settings.protocol, key) ?: continue
            if (value !in range) {
                warnings += ConfigIssue(
                    fieldName(key),
                    "${label(key)} $value${unitSuffix(key)} fuori dal range consigliato ${range.first}..${range.last}",
                )
            }
        }

        // ─── Terminale ─────────────────────────────────────────────────────────
        val port = settings.terminal.port ?: profile.defaultPort ?: 0
        if (port in TerminalProfiles.ZVT_OPI_PORTS) {
            warnings += ConfigIssue("port", "Porta tipica ZVT/OPI: probabilmente non è Protocollo 17")
        }
        // TID ed ECR id della configurazione li valida qui (RequestFactory valida solo i campi dei form, §5.8):
        // un TID illeggibile farebbe fallire OGNI comando con un errore di validazione poco chiaro.
        checkDigits("terminalId", "Terminal ID", settings.terminal.terminalId, issues)
        checkDigits("cashRegisterId", "ECR id", settings.terminal.cashRegisterId, issues)

        val config = Ecr17Config(
            profile = profile.id,
            endpoint = Endpoint(settings.terminal.host.trim(), port),
            ids = TerminalIds(
                terminalId = settings.terminal.terminalId.trim(),
                cashRegisterId = settings.terminal.cashRegisterId.trim(),
            ),
            options = options,
        )
        return ConfigResolution(profile = profile, config = config, issues = issues, warnings = warnings)
    }

    /** Valori di "Ripristina valori del profilo". */
    fun profileDefaults(profile: TerminalProfile): ProtocolSettings = fromOptions(profile.defaults)

    /** Testo del valore per la UI ("stx", "WIRE", "2000"…). */
    fun textOf(protocol: ProtocolSettings, key: OptionKey): String = when (key) {
        OptionKey.APP_LRC_MODE -> protocol.appLrcMode
        OptionKey.CONTROL_LRC_TX -> protocol.controlLrcTx
        OptionKey.CONTROL_LRC_RX -> protocol.controlLrcRx
        OptionKey.APP_LRC_RX -> protocol.appLrcRx
        OptionKey.FINANCIAL_ACK_POLICY -> protocol.financialAckPolicy
        OptionKey.CONNECTION_MODE -> protocol.connectionMode
        OptionKey.CONNECT_TIMEOUT_MS -> protocol.connectTimeoutMs.toString()
        OptionKey.ACK_TIMEOUT_MS -> protocol.ackTimeoutMs.toString()
        OptionKey.RESPONSE_TIMEOUT_MS -> protocol.responseTimeoutMs.toString()
        OptionKey.NAK_RETRY_COUNT -> protocol.nakRetryCount.toString()
        OptionKey.ACK_TIMEOUT_RETRANSMIT_COUNT -> protocol.ackTimeoutRetransmitCount.toString()
        OptionKey.RETRY_DELAY_MS -> protocol.retryDelayMs.toString()
        OptionKey.POST_RESULT_DRAIN_MS -> protocol.postResultDrainMs.toString()
        OptionKey.RECEIPT_DRAIN_MS -> protocol.receiptDrainMs.toString()
        OptionKey.ECR_PRINTING -> protocol.ecrPrinting.toString()
        OptionKey.PRE_SEND_QUIET_MS -> protocol.preSendQuietMs.toString()
        OptionKey.WRITE_TIMEOUT_MS -> protocol.writeTimeoutMs.toString()
        OptionKey.AUTO_RECONNECT_SAFE_COMMANDS -> protocol.autoReconnectSafeCommands.toString()
    }

    /** Valida un testo inserito con i range dell'operatore: success(testo normalizzato) o failure(IllegalArgumentException, messaggio italiano). */
    fun parseInput(key: OptionKey, text: String): Result<String> {
        val value = text.trim()
        val normalized: String? = when (key) {
            OptionKey.APP_LRC_MODE -> LrcMode.fromWireName(value)?.wireName
            OptionKey.CONTROL_LRC_TX -> enumName<ControlLrcRule>(value)
            OptionKey.CONTROL_LRC_RX -> enumName<ControlLrcRxPolicy>(value)
            OptionKey.APP_LRC_RX -> enumName<AppLrcRxPolicy>(value)
            OptionKey.FINANCIAL_ACK_POLICY -> enumName<FinancialAckPolicy>(value)
            OptionKey.CONNECTION_MODE -> enumName<ConnectionMode>(value)
            OptionKey.ECR_PRINTING, OptionKey.AUTO_RECONNECT_SAFE_COMMANDS -> parseBoolean(value)?.toString()
            else -> {
                val range = OptionRanges.ranges.getValue(key)
                val number = value.toLongOrNull()
                if (number == null || number !in range) {
                    return failure("${label(key)}: valore tra ${range.first} e ${range.last}${unitSuffix(key)}")
                }
                number.toString()
            }
        }
        return if (normalized != null) {
            Result.success(normalized)
        } else {
            failure("Valore '$value' non valido per ${label(key)}: valori ammessi ${allowedValues(key)}")
        }
    }

    /** Applica un valore già normalizzato da parseInput. */
    fun withValue(protocol: ProtocolSettings, key: OptionKey, normalized: String): ProtocolSettings = when (key) {
        OptionKey.APP_LRC_MODE -> protocol.copy(appLrcMode = normalized)
        OptionKey.CONTROL_LRC_TX -> protocol.copy(controlLrcTx = normalized)
        OptionKey.CONTROL_LRC_RX -> protocol.copy(controlLrcRx = normalized)
        OptionKey.APP_LRC_RX -> protocol.copy(appLrcRx = normalized)
        OptionKey.FINANCIAL_ACK_POLICY -> protocol.copy(financialAckPolicy = normalized)
        OptionKey.CONNECTION_MODE -> protocol.copy(connectionMode = normalized)
        OptionKey.CONNECT_TIMEOUT_MS -> protocol.copy(connectTimeoutMs = normalized.toLong())
        OptionKey.ACK_TIMEOUT_MS -> protocol.copy(ackTimeoutMs = normalized.toLong())
        OptionKey.RESPONSE_TIMEOUT_MS -> protocol.copy(responseTimeoutMs = normalized.toLong())
        OptionKey.NAK_RETRY_COUNT -> protocol.copy(nakRetryCount = normalized.toInt())
        OptionKey.ACK_TIMEOUT_RETRANSMIT_COUNT -> protocol.copy(ackTimeoutRetransmitCount = normalized.toInt())
        OptionKey.RETRY_DELAY_MS -> protocol.copy(retryDelayMs = normalized.toLong())
        OptionKey.POST_RESULT_DRAIN_MS -> protocol.copy(postResultDrainMs = normalized.toLong())
        OptionKey.RECEIPT_DRAIN_MS -> protocol.copy(receiptDrainMs = normalized.toLong())
        OptionKey.ECR_PRINTING -> protocol.copy(ecrPrinting = normalized.toBooleanStrict())
        OptionKey.PRE_SEND_QUIET_MS -> protocol.copy(preSendQuietMs = normalized.toLong())
        OptionKey.WRITE_TIMEOUT_MS -> protocol.copy(writeTimeoutMs = normalized.toLong())
        OptionKey.AUTO_RECONNECT_SAFE_COMMANDS -> protocol.copy(autoReconnectSafeCommands = normalized.toBooleanStrict())
    }

    // ─── Conversioni ───────────────────────────────────────────────────────────

    /** Ogni enum illeggibile ⇒ issue + valore del profilo (la config resta comunque non utilizzabile). */
    private fun toOptions(p: ProtocolSettings, defaults: ProtocolOptions, issues: MutableList<ConfigIssue>): ProtocolOptions {
        fun <T> read(key: OptionKey, raw: String, parsed: T?, fallback: T): T {
            if (parsed != null) return parsed
            issues += ConfigIssue(fieldName(key), "Valore '$raw' non valido per ${label(key)}")
            return fallback
        }
        return ProtocolOptions(
            appLrcMode = read(OptionKey.APP_LRC_MODE, p.appLrcMode, LrcMode.fromWireName(p.appLrcMode), defaults.appLrcMode),
            controlLrcTx = read(OptionKey.CONTROL_LRC_TX, p.controlLrcTx, enumOf<ControlLrcRule>(p.controlLrcTx), defaults.controlLrcTx),
            controlLrcRx = read(OptionKey.CONTROL_LRC_RX, p.controlLrcRx, enumOf<ControlLrcRxPolicy>(p.controlLrcRx), defaults.controlLrcRx),
            appLrcRx = read(OptionKey.APP_LRC_RX, p.appLrcRx, enumOf<AppLrcRxPolicy>(p.appLrcRx), defaults.appLrcRx),
            financialAckPolicy = read(
                OptionKey.FINANCIAL_ACK_POLICY, p.financialAckPolicy,
                enumOf<FinancialAckPolicy>(p.financialAckPolicy), defaults.financialAckPolicy,
            ),
            connectionMode = read(OptionKey.CONNECTION_MODE, p.connectionMode, enumOf<ConnectionMode>(p.connectionMode), defaults.connectionMode),
            connectTimeoutMs = p.connectTimeoutMs,
            ackTimeoutMs = p.ackTimeoutMs,
            responseTimeoutMs = p.responseTimeoutMs,
            nakRetryCount = p.nakRetryCount,
            ackTimeoutRetransmitCount = p.ackTimeoutRetransmitCount,
            retryDelayMs = p.retryDelayMs,
            postResultDrainMs = p.postResultDrainMs,
            receiptDrainMs = p.receiptDrainMs,
            ecrPrinting = p.ecrPrinting,
            preSendQuietMs = p.preSendQuietMs,
            writeTimeoutMs = p.writeTimeoutMs,
            autoReconnectSafeCommands = p.autoReconnectSafeCommands,
        )
    }

    private fun fromOptions(o: ProtocolOptions): ProtocolSettings = ProtocolSettings(
        appLrcMode = o.appLrcMode.wireName,
        controlLrcTx = o.controlLrcTx.name,
        controlLrcRx = o.controlLrcRx.name,
        appLrcRx = o.appLrcRx.name,
        financialAckPolicy = o.financialAckPolicy.name,
        connectionMode = o.connectionMode.name,
        connectTimeoutMs = o.connectTimeoutMs,
        ackTimeoutMs = o.ackTimeoutMs,
        responseTimeoutMs = o.responseTimeoutMs,
        nakRetryCount = o.nakRetryCount,
        ackTimeoutRetransmitCount = o.ackTimeoutRetransmitCount,
        retryDelayMs = o.retryDelayMs,
        postResultDrainMs = o.postResultDrainMs,
        receiptDrainMs = o.receiptDrainMs,
        ecrPrinting = o.ecrPrinting,
        preSendQuietMs = o.preSendQuietMs,
        writeTimeoutMs = o.writeTimeoutMs,
        autoReconnectSafeCommands = o.autoReconnectSafeCommands,
    )

    private fun numericValue(p: ProtocolSettings, key: OptionKey): Long? = when (key) {
        OptionKey.CONNECT_TIMEOUT_MS -> p.connectTimeoutMs
        OptionKey.ACK_TIMEOUT_MS -> p.ackTimeoutMs
        OptionKey.RESPONSE_TIMEOUT_MS -> p.responseTimeoutMs
        OptionKey.NAK_RETRY_COUNT -> p.nakRetryCount.toLong()
        OptionKey.ACK_TIMEOUT_RETRANSMIT_COUNT -> p.ackTimeoutRetransmitCount.toLong()
        OptionKey.RETRY_DELAY_MS -> p.retryDelayMs
        OptionKey.POST_RESULT_DRAIN_MS -> p.postResultDrainMs
        OptionKey.RECEIPT_DRAIN_MS -> p.receiptDrainMs
        OptionKey.PRE_SEND_QUIET_MS -> p.preSendQuietMs
        OptionKey.WRITE_TIMEOUT_MS -> p.writeTimeoutMs
        else -> null
    }

    // ─── Parsing rigoroso ──────────────────────────────────────────────────────

    /** Trim + maiuscole/minuscole indifferenti sul nome dell'enum; null se sconosciuto (mai un fallback silenzioso). */
    private inline fun <reified E : Enum<E>> enumOf(value: String): E? =
        enumValues<E>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }

    private inline fun <reified E : Enum<E>> enumName(value: String): String? = enumOf<E>(value)?.name

    private fun parseProfileId(value: String): TerminalProfileId? = enumOf<TerminalProfileId>(value)

    private fun parseBoolean(value: String): Boolean? = when (value.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun checkDigits(key: String, label: String, value: String, issues: MutableList<ConfigIssue>) {
        val v = value.trim()
        if (v.length > DIGITS_MAX || v.any { it !in '0'..'9' }) {
            issues += ConfigIssue(key, "$label '$value' non valido: solo cifre, al massimo $DIGITS_MAX")
        }
    }

    private fun failure(message: String): Result<String> = Result.failure(IllegalArgumentException(message))

    // ─── Testi ─────────────────────────────────────────────────────────────────

    /** Nome del campo in ProtocolSettings/ProtocolOptions (stessa chiave dei ConfigProblem di validate()). */
    private fun fieldName(key: OptionKey): String = when (key) {
        OptionKey.APP_LRC_MODE -> "appLrcMode"
        OptionKey.CONTROL_LRC_TX -> "controlLrcTx"
        OptionKey.CONTROL_LRC_RX -> "controlLrcRx"
        OptionKey.APP_LRC_RX -> "appLrcRx"
        OptionKey.FINANCIAL_ACK_POLICY -> "financialAckPolicy"
        OptionKey.CONNECTION_MODE -> "connectionMode"
        OptionKey.CONNECT_TIMEOUT_MS -> "connectTimeoutMs"
        OptionKey.ACK_TIMEOUT_MS -> "ackTimeoutMs"
        OptionKey.RESPONSE_TIMEOUT_MS -> "responseTimeoutMs"
        OptionKey.NAK_RETRY_COUNT -> "nakRetryCount"
        OptionKey.ACK_TIMEOUT_RETRANSMIT_COUNT -> "ackTimeoutRetransmitCount"
        OptionKey.RETRY_DELAY_MS -> "retryDelayMs"
        OptionKey.POST_RESULT_DRAIN_MS -> "postResultDrainMs"
        OptionKey.RECEIPT_DRAIN_MS -> "receiptDrainMs"
        OptionKey.ECR_PRINTING -> "ecrPrinting"
        OptionKey.PRE_SEND_QUIET_MS -> "preSendQuietMs"
        OptionKey.WRITE_TIMEOUT_MS -> "writeTimeoutMs"
        OptionKey.AUTO_RECONNECT_SAFE_COMMANDS -> "autoReconnectSafeCommands"
    }

    /** Etichetta italiana dell'opzione, la stessa della finestra Opzioni. */
    private fun label(key: OptionKey): String = when (key) {
        OptionKey.APP_LRC_MODE -> "LRC applicativo"
        OptionKey.CONTROL_LRC_TX -> "LRC di ACK/NAK inviati"
        OptionKey.CONTROL_LRC_RX -> "LRC di ACK/NAK ricevuti"
        OptionKey.APP_LRC_RX -> "LRC dei frame ricevuti"
        OptionKey.FINANCIAL_ACK_POLICY -> "ACK mancante su comando finanziario"
        OptionKey.CONNECTION_MODE -> "Modalità di connessione"
        OptionKey.CONNECT_TIMEOUT_MS -> "Timeout connessione"
        OptionKey.ACK_TIMEOUT_MS -> "Timeout ACK"
        OptionKey.RESPONSE_TIMEOUT_MS -> "Timeout esito"
        OptionKey.NAK_RETRY_COUNT -> "Reinvii su NAK"
        OptionKey.ACK_TIMEOUT_RETRANSMIT_COUNT -> "Reinvii su ACK mancante"
        OptionKey.RETRY_DELAY_MS -> "Attesa tra i reinvii"
        OptionKey.POST_RESULT_DRAIN_MS -> "Drain dopo l'esito"
        OptionKey.RECEIPT_DRAIN_MS -> "Drain con scontrino"
        OptionKey.ECR_PRINTING -> "Stampa su ECR"
        OptionKey.PRE_SEND_QUIET_MS -> "Silenzio prima dell'invio"
        OptionKey.WRITE_TIMEOUT_MS -> "Timeout scrittura"
        OptionKey.AUTO_RECONNECT_SAFE_COMMANDS -> "Riconnessione automatica (s T G E)"
    }

    private fun unitSuffix(key: OptionKey): String = when (key) {
        OptionKey.NAK_RETRY_COUNT, OptionKey.ACK_TIMEOUT_RETRANSMIT_COUNT -> ""
        else -> " ms"
    }

    private fun allowedValues(key: OptionKey): String = when (key) {
        OptionKey.APP_LRC_MODE -> LrcMode.entries.joinToString { it.wireName }
        OptionKey.CONTROL_LRC_TX -> ControlLrcRule.entries.joinToString()
        OptionKey.CONTROL_LRC_RX -> ControlLrcRxPolicy.entries.joinToString()
        OptionKey.APP_LRC_RX -> AppLrcRxPolicy.entries.joinToString()
        OptionKey.FINANCIAL_ACK_POLICY -> FinancialAckPolicy.entries.joinToString()
        OptionKey.CONNECTION_MODE -> ConnectionMode.entries.joinToString()
        else -> "true, false"
    }
}

/**
 * Range dell'operatore (non del protocollo, §3.16). Usati da parseInput e, come avvisi, da resolve.
 * Sono separati da ProtocolOptions.validate() perché le configurazioni rapide dei test (ACK 40 ms) devono
 * restare valide per il protocollo pur essendo fuori dai valori sensati per un operatore.
 */
internal object OptionRanges {
    val ranges: Map<OptionKey, LongRange> = mapOf(
        OptionKey.CONNECT_TIMEOUT_MS to 500L..60_000L,
        OptionKey.ACK_TIMEOUT_MS to 100L..30_000L,
        OptionKey.RESPONSE_TIMEOUT_MS to 5_000L..600_000L,
        OptionKey.NAK_RETRY_COUNT to 0L..5L,
        OptionKey.ACK_TIMEOUT_RETRANSMIT_COUNT to 0L..5L,
        OptionKey.RETRY_DELAY_MS to 0L..5_000L,
        OptionKey.POST_RESULT_DRAIN_MS to 0L..10_000L,
        OptionKey.RECEIPT_DRAIN_MS to 0L..10_000L,
        OptionKey.PRE_SEND_QUIET_MS to 0L..2_000L,
        OptionKey.WRITE_TIMEOUT_MS to 500L..60_000L,
    )
}
