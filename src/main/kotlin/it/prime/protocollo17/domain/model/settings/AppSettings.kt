package it.prime.protocollo17.domain.model.settings

import kotlinx.serialization.Serializable

// Impostazioni persistite in ~/.protocollo17/settings.json (§5.2). Ogni campo ha un default: un file vecchio o
// parziale si legge sempre, e un campo nuovo prende il suo valore iniziale senza migrazioni.

/**
 * Radice delle impostazioni dell'app.
 *
 * Le opzioni di protocollo sono persistite **per intero** in [protocol], non come scostamenti dal profilo:
 * i due profili v1 hanno gli stessi default (CCV = clone di Ingenico), quindi un meccanismo di override con
 * diff per campo non darebbe nulla di visibile. Il profilo fornisce solo i valori di "Ripristina valori del
 * profilo" (§6.11).
 */
@Serializable
data class AppSettings(
    val schemaVersion: Int = 1,
    val terminal: TerminalSettings = TerminalSettings(),
    val protocol: ProtocolSettings = ProtocolSettings(),
    val ui: UiSettings = UiSettings(),
    val traffic: TrafficLogSettings = TrafficLogSettings(),
    val journal: JournalSettings = JournalSettings(),
    val simulator: SimulatorSettings = SimulatorSettings(),
    /** Ultimi valori dei form: chiave esterna = lettera del comando ("P"), interna = FieldKey.name. */
    val drafts: Map<String, Map<String, String>> = emptyMap(),
)

/** Destinazione del terminale e identificativi usati nelle richieste. */
@Serializable
data class TerminalSettings(
    val profile: String = "INGENICO",            // TerminalProfileId.name
    val host: String = "192.168.1.131",
    val port: Int? = null,                       // null = porta del profilo (CCV: obbligatoria)
    val terminalId: String = "00000000",
    val cashRegisterId: String = "00000000",
)

/**
 * Opzioni di protocollo complete. Gli enum sono stringhe (wireName per l'LRC, name per gli altri): un valore
 * illeggibile diventa un ConfigIssue visibile invece di un file di impostazioni scartato (L§9.2 L1).
 * I default coincidono con ProtocolOptions() (verificato da ConfigResolverTest).
 */
@Serializable
data class ProtocolSettings(
    val appLrcMode: String = "stx",
    val controlLrcTx: String = "WIRE",
    val controlLrcRx: String = "TOLERANT",
    val appLrcRx: String = "ACCEPT_ANY_MODE",
    val financialAckPolicy: String = "IMPLICIT_GO_AHEAD",
    val connectionMode: String = "PER_COMMAND",
    val connectTimeoutMs: Long = 5_000,
    val ackTimeoutMs: Long = 2_000,
    val responseTimeoutMs: Long = 120_000,
    val nakRetryCount: Int = 3,
    val ackTimeoutRetransmitCount: Int = 3,
    val retryDelayMs: Long = 200,
    val postResultDrainMs: Long = 100,
    val receiptDrainMs: Long = 1_500,
    val ecrPrinting: Boolean = false,
    val preSendQuietMs: Long = 50,
    val writeTimeoutMs: Long = 5_000,
    val autoReconnectSafeCommands: Boolean = true,
)

/** Tema scelto dall'operatore; System segue il tema di Windows. */
@Serializable
enum class ThemeMode { Light, Dark, System }

/**
 * Geometria di una finestra in dp. Le coordinate sono opzionali: senza, la finestra si apre centrata (e una
 * posizione salvata su un monitor scollegato viene scartata da coerceGeometry, §6.18).
 */
@Serializable
data class WindowGeometry(
    val widthDp: Float, val heightDp: Float,
    val xDp: Float? = null, val yDp: Float? = null,
    val maximized: Boolean = false,
)

/** Preferenze dell'interfaccia: tema, ultimo comando, geometrie e finestre secondarie da riaprire. */
@Serializable
data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val lastCommand: String = "P",
    val windows: Map<String, WindowGeometry> = emptyMap(),   // chiave: "MAIN" o AppWindowId.name
    val openWindows: Set<String> = emptySet(),               // finestre secondarie riaperte all'avvio
    val confirmCloseSession: Boolean = true,
)

/** Log traffico hex (§5.4): file giornaliero e ring buffer in memoria per la finestra Log. */
@Serializable
data class TrafficLogSettings(
    val fileEnabled: Boolean = true,
    val retentionDays: Int = 30,
    val maxTotalMb: Int = 200,
    val memoryEntries: Int = 5_000,
)

/** Journal dei tentativi (§5.3): soglia di compattazione e conservazione dei tentativi chiusi. */
@Serializable
data class JournalSettings(
    val compactAboveKb: Int = 2_048,
    val keepClosedDays: Int = 180,
    val memoryAttempts: Int = 500,
)

/**
 * Impostazioni del simulatore (§4, §6.14). Gli enum sono stringhe come in [ProtocolSettings]: il mapper li
 * converte e un valore sconosciuto torna al default con un avviso nel log del simulatore.
 * La porta di default è 15577 e non 5577, per non confondersi con un terminale vero sulla LAN.
 */
@Serializable
data class SimulatorSettings(
    val port: Int = 15577,
    val terminalId: String = "02575733",
    val appLrcMode: String = "stx",              // LrcMode.wireName
    val ackRule: String = "WIRE",
    val ackDelayMs: Long = 40,
    val progressDelayMs: Long = 400,
    val resultDelayMs: Long = 2_500,
    val decision: String = "APPROVE",
    val fault: String = "NONE",
    val faultOnlyFinancial: Boolean = true,
    val closeAfterTransaction: Boolean = false,
    val retransmitResultIfNoValidAck: Boolean = false,
    val receiptAfterResult: Boolean = true,
    val autoStart: Boolean = false,
    val previousHost: String? = null,
    val previousPort: Int? = null,
)
