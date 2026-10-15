package it.prime.protocollo17.data.local.traffic

import it.prime.protocollo17.domain.model.traffic.TrafficEntry
import it.prime.protocollo17.domain.model.traffic.TrafficKind
import it.prime.protocollo17.domain.model.traffic.TrafficSeverity
import it.prime.protocollo17.ecr17.event.FrameDirection
import it.prime.protocollo17.ecr17.event.FrameKind
import it.prime.protocollo17.ecr17.event.ProtocolEvent
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.model.CloseCause
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.LinkState
import it.prime.protocollo17.ecr17.model.OutcomeClass
import it.prime.protocollo17.ecr17.wire.Hex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Formato del log traffico (§5.4.2): voci per la finestra Log e righe del file giornaliero.
 *
 * I frame usano ESATTAMENTE la forma del log della DLL Ingenico (hex minuscolo, ogni byte seguito da uno spazio,
 * prefisso `Data Tx:`/`Data Rx:`), così una traccia dell'app e una della DLL si confrontano con `diff` dopo aver
 * tolto il timestamp. Le altre righe usano un prefisso a 7 caratteri (`Info   `, `Warn   `, `Error  `, `Discard`).
 *
 * Il PAN è già mascherato negli eventi (M9): il formatter non vede mai un PAN in chiaro e non lo cerca.
 */
object TrafficFormatter {
    const val TIMESTAMP_PATTERN = "dd/MM/yyyy-HH:mm:ss-SSS"

    private const val FILE_SUFFIX = "-p17-traffic.txt"
    private val timestamp: DateTimeFormatter = DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN)
    private val fileDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy_MM_dd")

    /** Avvisi che toccano la money-safety: in rosso nella finestra Log e con prefisso `Error` nel file. */
    private val SEVERE_WARNINGS = setOf(
        WarningCode.JOURNAL_WRITE_FAILED,
        WarningCode.POSSIBLE_LOST_RESULT,
        WarningCode.POSSIBLE_DUPLICATE,
        WarningCode.EARLY_RESULT,
    )

    /** Voce per la finestra Log; null per eventi non mostrati. commandOf risolve exchangeId → lettera. */
    fun toEntry(event: ProtocolEvent, commandOf: (Long?) -> Char?): TrafficEntry? {
        fun entry(exchangeId: Long?, kind: TrafficKind, command: Char?, summary: String, severity: TrafficSeverity, bytes: ByteArray? = null) =
            TrafficEntry(
                id = event.seq,
                at = event.at,
                exchangeId = exchangeId,
                kind = kind,
                command = command ?: commandOf(exchangeId),
                hex = bytes?.let { Hex.spaced(it) },
                ascii = bytes?.let { Hex.printable(it) },
                summary = summary,
                severity = severity,
            )
        return when (event) {
            is ProtocolEvent.CommandStarted ->
                entry(event.exchangeId, TrafficKind.COMMAND, event.command.wire, describe(event), TrafficSeverity.INFO)
            is ProtocolEvent.CommandFinished ->
                entry(event.exchangeId, TrafficKind.COMMAND, event.command.wire, describe(event), severityOf(event.outcome))
            is ProtocolEvent.PhaseChanged ->
                entry(event.change.exchangeId, TrafficKind.PHASE, event.change.command.wire, describe(event), TrafficSeverity.NORMAL)
            is ProtocolEvent.Link ->
                entry(null, TrafficKind.LINK, null, describe(event.state), severityOf(event.state))
            is ProtocolEvent.Wire -> entry(
                event.exchangeId,
                if (event.direction == FrameDirection.TX) TrafficKind.TX else TrafficKind.RX,
                null,
                describe(event),
                if (event.kind == FrameKind.NAK) TrafficSeverity.WARNING else TrafficSeverity.NORMAL,
                event.bytes,
            )
            is ProtocolEvent.Discarded ->
                entry(event.exchangeId, TrafficKind.DISCARDED, null, describe(event), TrafficSeverity.WARNING, event.bytes)
            is ProtocolEvent.Progress ->
                entry(event.exchangeId, TrafficKind.PROGRESS, null, describe(event), TrafficSeverity.NORMAL)
            is ProtocolEvent.Receipt ->
                entry(event.exchangeId, TrafficKind.RECEIPT, null, describe(event), TrafficSeverity.NORMAL)
            is ProtocolEvent.Warning -> entry(
                event.exchangeId, TrafficKind.WARNING, null, describe(event),
                if (event.warning.code in SEVERE_WARNINGS) TrafficSeverity.ERROR else TrafficSeverity.WARNING,
            )
        }
    }

    /** Righe per il file (formato sopra), in ora locale. */
    fun toFileLines(event: ProtocolEvent, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val ts = formatTimestamp(event.at, zone)
        fun line(prefix: String, text: String) = "$ts: $prefix:$text"
        fun tagged(exchangeId: Long?, text: String) = (exchangeId?.let { "#$it " } ?: "") + text
        return when (event) {
            is ProtocolEvent.Wire -> buildList {
                add(line(if (event.direction == FrameDirection.TX) "Data Tx" else "Data Rx", dllHex(event.bytes)))
                // Come nella traccia di esempio: sotto un frame applicativo ricevuto, l'esito della verifica LRC.
                if (event.direction == FrameDirection.RX && event.kind == FrameKind.APPLICATION && event.lrcNote != null) {
                    add(line(INFO, tagged(event.exchangeId, describe(event))))
                }
            }
            is ProtocolEvent.Discarded -> listOf(line("Discard", dllHex(event.bytes) + "| " + event.reason.name))
            is ProtocolEvent.CommandStarted -> listOf(line(INFO, tagged(event.exchangeId, describe(event))))
            is ProtocolEvent.CommandFinished -> listOf(line(prefixOf(severityOf(event.outcome)), tagged(event.exchangeId, describe(event))))
            is ProtocolEvent.PhaseChanged -> listOf(line(INFO, tagged(event.change.exchangeId, describe(event))))
            is ProtocolEvent.Link -> listOf(line(prefixOf(severityOf(event.state)), describe(event.state)))
            is ProtocolEvent.Progress -> listOf(line(INFO, tagged(event.exchangeId, describe(event))))
            is ProtocolEvent.Receipt -> listOf(line(INFO, tagged(event.exchangeId, describe(event))))
            is ProtocolEvent.Warning -> {
                val severity = if (event.warning.code in SEVERE_WARNINGS) TrafficSeverity.ERROR else TrafficSeverity.WARNING
                listOf(line(prefixOf(severity), tagged(event.exchangeId, describe(event))))
            }
        }
    }

    /** "yyyy_MM_dd-p17-traffic.txt". */
    fun fileNameFor(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        fileDate.format(Instant.ofEpochMilli(epochMs).atZone(zone)) + FILE_SUFFIX

    // ─── Uso interno (recorder ed export) ──────────────────────────────────────

    internal fun isTrafficFile(name: String): Boolean = name.endsWith(FILE_SUFFIX)

    /** Data nel nome del file ("2026_10_14-p17-traffic.txt"), null se il nome non è nella forma attesa. */
    internal fun dateOfFile(name: String): LocalDate? = runCatching {
        LocalDate.parse(name.removeSuffix(FILE_SUFFIX), fileDate)
    }.getOrNull()

    internal fun formatTimestamp(epochMs: Long, zone: ZoneId): String = timestamp.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /**
     * Riga di file da una voce già formattata (export della finestra Log): i frame tornano nella forma della DLL
     * (hex minuscolo con spazio finale), le altre voci usano il prefisso del loro tono.
     */
    internal fun toFileLine(entry: TrafficEntry, zone: ZoneId): String {
        val ts = formatTimestamp(entry.at, zone)
        val dllHex = entry.hex?.let { it.trim().lowercase() + " " }.orEmpty()
        val tag = entry.exchangeId?.let { "#$it " }.orEmpty()
        return when (entry.kind) {
            TrafficKind.TX -> "$ts: Data Tx:$dllHex"
            TrafficKind.RX -> "$ts: Data Rx:$dllHex"
            TrafficKind.DISCARDED -> "$ts: Discard:$dllHex| ${entry.summary}"
            else -> "$ts: ${prefixOf(entry.severity)}:$tag${entry.summary}"
        }
    }

    // ─── Testi ─────────────────────────────────────────────────────────────────

    private const val INFO = "Info   "

    private fun prefixOf(severity: TrafficSeverity): String = when (severity) {
        TrafficSeverity.NORMAL, TrafficSeverity.INFO -> INFO
        TrafficSeverity.WARNING -> "Warn   "
        TrafficSeverity.ERROR -> "Error  "
    }

    /** Hex nella forma della DLL: minuscolo, ogni byte seguito da uno spazio. */
    private fun dllHex(bytes: ByteArray): String = Hex.spaced(bytes, upper = false, trailingSpace = true)

    private fun severityOf(outcome: OutcomeClass): TrafficSeverity = when (outcome) {
        OutcomeClass.APPROVED, OutcomeClass.ANSWERED_OTHER, OutcomeClass.ACKNOWLEDGED -> TrafficSeverity.INFO
        OutcomeClass.DECLINED, OutcomeClass.NOT_SENT, OutcomeClass.REFUSED -> TrafficSeverity.WARNING
        OutcomeClass.FAILED, OutcomeClass.UNKNOWN -> TrafficSeverity.ERROR
    }

    private fun severityOf(state: LinkState): TrafficSeverity = when (state) {
        is LinkState.ConnectFailed -> TrafficSeverity.ERROR
        is LinkState.Closed -> if (state.cause == CloseCause.LOCAL) TrafficSeverity.INFO else TrafficSeverity.WARNING
        else -> TrafficSeverity.INFO
    }

    private fun describe(event: ProtocolEvent.CommandStarted): String =
        "${event.command.wire} avviato verso ${event.endpoint} (${event.profile.name} ${event.optionsSummary})"

    private fun describe(event: ProtocolEvent.CommandFinished): String =
        "${event.command.wire} terminato ${event.outcome.name}" + (event.failure?.let { ": ${it.message}" } ?: "")

    private fun describe(event: ProtocolEvent.PhaseChanged): String {
        val change = event.change
        return when (change.phase) {
            ExchangePhase.SENDING -> "fase SENDING invio ${change.sends}"
            else -> "fase ${change.phase.name}"
        }
    }

    private fun describe(state: LinkState): String = when (state) {
        LinkState.Idle -> "link Idle"
        is LinkState.Connecting -> "link Connecting ${state.endpoint}"
        is LinkState.Connected -> "link Connected ${state.endpoint}"
        is LinkState.Closed -> "link Closed ${state.cause.name}" + (state.detail?.let { ": $it" } ?: "")
        is LinkState.ConnectFailed ->
            "link ConnectFailed ${state.endpoint}" + (if (state.timeout) " (timeout)" else "") + (state.detail?.let { ": $it" } ?: "")
    }

    private fun describe(event: ProtocolEvent.Wire): String {
        val note = event.lrcNote
        return when (event.kind) {
            FrameKind.APPLICATION -> {
                val code = event.code?.let { "'$it'" } ?: "senza codice"
                val attempt = event.attempt?.let { " invio $it" }.orEmpty()
                "Frame $code$attempt" + (note?.let { ", $it" } ?: "")
            }
            FrameKind.ACK -> "ACK" + (note?.let { " ($it)" } ?: "")
            FrameKind.NAK -> "NAK" + (note?.let { " ($it)" } ?: "")
            FrameKind.PROGRESS -> "Progress" + (note?.let { " ($it)" } ?: "")
        }
    }

    private fun describe(event: ProtocolEvent.Discarded): String = "Scartati ${event.bytes.size} byte: ${event.reason.name}"

    private fun describe(event: ProtocolEvent.Progress): String = "progress \"${event.text}\""

    private fun describe(event: ProtocolEvent.Receipt): String {
        val line = event.line
        // Una riga di file non può contenere a capo: 0x7D (già tradotto in '\n' dal parser) torna visibile come \n.
        val text = line.text.replace("\n", "\\n")
        return "riga S \"$text\"" + (if (line.doubleHeight) " [doppia altezza]" else "") + (if (line.isEnd) " (fine scontrino)" else "")
    }

    private fun describe(event: ProtocolEvent.Warning): String {
        val w = event.warning
        return "${w.code.name} ${w.detail}" + (w.payload?.let { " | payload: $it" } ?: "")
    }
}
