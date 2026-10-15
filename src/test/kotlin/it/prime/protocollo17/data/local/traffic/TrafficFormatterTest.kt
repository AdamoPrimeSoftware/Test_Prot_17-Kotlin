package it.prime.protocollo17.data.local.traffic

import it.prime.protocollo17.domain.model.traffic.TrafficKind
import it.prime.protocollo17.domain.model.traffic.TrafficSeverity
import it.prime.protocollo17.ecr17.error.Ecr17Failure
import it.prime.protocollo17.ecr17.event.FrameDirection
import it.prime.protocollo17.ecr17.event.FrameKind
import it.prime.protocollo17.ecr17.event.ProtocolEvent
import it.prime.protocollo17.ecr17.event.ProtocolWarning
import it.prime.protocollo17.ecr17.event.WarningCode
import it.prime.protocollo17.ecr17.model.CloseCause
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.ExchangePhase
import it.prime.protocollo17.ecr17.model.LinkState
import it.prime.protocollo17.ecr17.model.OutcomeClass
import it.prime.protocollo17.ecr17.model.PhaseChange
import it.prime.protocollo17.ecr17.model.ReceiptLine
import it.prime.protocollo17.ecr17.profile.ProtocolOptions
import it.prime.protocollo17.ecr17.profile.TerminalProfileId
import it.prime.protocollo17.ecr17.testing.Frames
import it.prime.protocollo17.ecr17.wire.DiscardReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Formato del log traffico: le righe dei frame devono combaciare byte per byte con il log della DLL Ingenico,
 * così le tracce si confrontano con diff dopo aver tolto il timestamp (§5.4.2).
 *
 * I casi con frame e scarti usano Hex (WP1): girano dopo il merge di WP1.
 */
class TrafficFormatterTest {

    private val utc = ZoneOffset.UTC

    /** 14/10/2026 09:35:05.399 UTC, l'istante della traccia di esempio di §5.4.2. */
    private val at = LocalDateTime.of(2026, 10, 14, 9, 35, 5, 399_000_000).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val ts = "14/10/2026-09:35:05-399"
    private val endpoint = Endpoint("192.168.1.131", 5577)

    private fun lines(event: ProtocolEvent) = TrafficFormatter.toFileLines(event, utc)

    // ─── Frame (richiedono Hex, WP1) ───────────────────────────────────────────

    @Test
    fun `riga Data Tx del pagamento P1 identica al log della DLL`() {
        val frame = Frames.app(Frames.P1)
        val event = ProtocolEvent.Wire(1, at, 12, FrameDirection.TX, FrameKind.APPLICATION, 'P', frame, attempt = 1, lrcNote = "LRC 2F (stx)")

        val expectedHex = frame.joinToString(separator = "") { "%02x ".format(it.toInt() and 0xFF) }
        assertEquals(listOf("$ts: Data Tx:$expectedHex"), lines(event), "riga Data Tx")
        assertTrue(expectedHex.startsWith("02 30 30 ") && expectedHex.endsWith("03 2f "), "forma DLL: $expectedHex")
    }

    @Test
    fun `riga Data Rx dell'ACK`() {
        val event = ProtocolEvent.Wire(2, at, 12, FrameDirection.RX, FrameKind.ACK, null, Frames.ACKW, attempt = null, lrcNote = "LRC 7A, WIRE")
        assertEquals(listOf("$ts: Data Rx:06 03 7a "), lines(event), "ACK ricevuto")
    }

    @Test
    fun `esito ricevuto con nota LRC aggiunge una riga Info`() {
        val event = ProtocolEvent.Wire(3, at, 12, FrameDirection.RX, FrameKind.APPLICATION, 'E', Frames.app(Frames.E22), null, "LRC 21 ok (stx)")
        val out = lines(event)
        assertEquals(2, out.size, "Data Rx + Info")
        assertTrue(out[0].startsWith("$ts: Data Rx:02 30 32 "), "riga dati: ${out[0]}")
        assertEquals("$ts: Info   :#12 Frame 'E', LRC 21 ok (stx)", out[1], "riga Info")
    }

    @Test
    fun `byte scartati nella forma Discard`() {
        val event = ProtocolEvent.Discarded(4, at, null, byteArrayOf(0xFF.toByte(), 0x00), DiscardReason.NOISE)
        assertEquals(listOf("$ts: Discard:ff 00 | NOISE"), lines(event), "scarto")
    }

    @Test
    fun `voce della finestra con hex maiuscolo e lettera del comando`() {
        val event = ProtocolEvent.Wire(5, at, 12, FrameDirection.RX, FrameKind.ACK, null, Frames.ACKW, null, "LRC 7A, WIRE")
        val entry = TrafficFormatter.toEntry(event) { if (it == 12L) 'P' else null }!!

        assertEquals(TrafficKind.RX, entry.kind, "kind")
        assertEquals('P', entry.command, "comando dallo scambio")
        assertEquals("06 03 7A", entry.hex, "hex maiuscolo")
        assertEquals("ACK (LRC 7A, WIRE)", entry.summary, "summary")
        assertEquals(5L, entry.id, "id = seq")
    }

    // ─── Righe di evento (nessuna dipendenza) ──────────────────────────────────

    @Test
    fun `riga Info di avvio comando come nella traccia di esempio`() {
        val event = ProtocolEvent.CommandStarted(6, at, 12, CommandCode.PAYMENT, endpoint, TerminalProfileId.INGENICO, ProtocolOptions().summary())
        assertEquals(
            listOf(
                "$ts: Info   :#12 P avviato verso 192.168.1.131:5577 (INGENICO stx/WIRE rx=TOLERANT/ANY ack=IMPLICIT_GO_AHEAD " +
                    "conn=PER_COMMAND to=2000/120000/5000 nak=3 ackRetx=3 delay=200 drain=100/1500 print=off)",
            ),
            lines(event),
            "CommandStarted",
        )
    }

    @Test
    fun `righe Info di fase, progress, link e fine comando`() {
        val sending = ProtocolEvent.PhaseChanged(7, at, PhaseChange(12, CommandCode.PAYMENT, ExchangePhase.SENDING, at, sends = 1))
        assertEquals(listOf("$ts: Info   :#12 fase SENDING invio 1"), lines(sending), "fase SENDING")

        val progress = ProtocolEvent.Progress(8, at, 12, "OPERAZIONE IN CORSO ")
        assertEquals(listOf("$ts: Info   :#12 progress \"OPERAZIONE IN CORSO \""), lines(progress), "progress")

        val connected = ProtocolEvent.Link(9, at, LinkState.Connected(endpoint, at))
        assertEquals(listOf("$ts: Info   :link Connected 192.168.1.131:5577"), lines(connected), "link Connected")

        val closed = ProtocolEvent.Link(10, at, LinkState.Closed(endpoint, CloseCause.LOCAL, null, at))
        assertEquals(listOf("$ts: Info   :link Closed LOCAL"), lines(closed), "link Closed LOCAL")

        val finished = ProtocolEvent.CommandFinished(11, at, 12, CommandCode.PAYMENT, OutcomeClass.APPROVED, null)
        assertEquals(listOf("$ts: Info   :#12 P terminato APPROVED"), lines(finished), "fine comando")
    }

    @Test
    fun `righe Warn ed Error`() {
        val goAhead = ProtocolEvent.Warning(
            12, at, 12,
            ProtocolWarning(WarningCode.IMPLICIT_GO_AHEAD, "ACK non ricevuto entro 2000 ms: attendo l'esito senza reinviare", at),
        )
        assertEquals(
            listOf("$ts: Warn   :#12 IMPLICIT_GO_AHEAD ACK non ricevuto entro 2000 ms: attendo l'esito senza reinviare"),
            lines(goAhead),
            "warning",
        )

        val unknown = ProtocolEvent.CommandFinished(13, at, 12, CommandCode.PAYMENT, OutcomeClass.UNKNOWN, Ecr17Failure.ResponseTimeout(120_000))
        assertEquals(
            listOf("$ts: Error  :#12 P terminato UNKNOWN: ECR17: no application response before timeout (120000 ms)"),
            lines(unknown),
            "esito sconosciuto",
        )

        val dropped = ProtocolEvent.Link(14, at, LinkState.Closed(endpoint, CloseCause.PEER_CLOSED, "EOF", at))
        assertEquals(listOf("$ts: Warn   :link Closed PEER_CLOSED: EOF"), lines(dropped), "caduta")
    }

    @Test
    fun `riga di scontrino su una sola riga di file`() {
        val receipt = ProtocolEvent.Receipt(15, at, 12, ReceiptLine(raw = "RIGA}ALTRA", text = "RIGA\nALTRA", doubleHeight = false, isEnd = true))
        assertEquals(listOf("$ts: Info   :#12 riga S \"RIGA\\nALTRA\" (fine scontrino)"), lines(receipt), "scontrino")
    }

    @Test
    fun `nome del file giornaliero`() {
        assertEquals("2026_10_14-p17-traffic.txt", TrafficFormatter.fileNameFor(at, utc), "nome file")
    }

    @Test
    fun `voci della finestra per gli eventi senza byte`() {
        val commandOf: (Long?) -> Char? = { if (it == 12L) 'P' else null }

        val warning = TrafficFormatter.toEntry(
            ProtocolEvent.Warning(16, at, 12, ProtocolWarning(WarningCode.POSSIBLE_LOST_RESULT, "orfano", at, payload = Frames.E22)),
            commandOf,
        )!!
        assertEquals(TrafficKind.WARNING, warning.kind, "kind")
        assertEquals(TrafficSeverity.ERROR, warning.severity, "avviso di money-safety in rosso")
        assertEquals('P', warning.command, "comando")
        assertTrue(warning.summary.contains(Frames.E22), "payload mascherato nel riepilogo")

        val started = TrafficFormatter.toEntry(
            ProtocolEvent.CommandStarted(17, at, 99, CommandCode.STATUS, endpoint, TerminalProfileId.CCV, "x"),
            commandOf,
        )!!
        assertEquals(TrafficKind.COMMAND, started.kind, "kind")
        assertEquals('s', started.command, "lettera dal CommandStarted")
        assertEquals(null, started.hex, "nessun hex")

        val progress = TrafficFormatter.toEntry(ProtocolEvent.Progress(18, at, 12, "ELABORAZIONE...     "), commandOf)!!
        assertEquals(TrafficKind.PROGRESS, progress.kind, "kind")
        assertEquals(TrafficSeverity.NORMAL, progress.severity, "severity")
    }
}
