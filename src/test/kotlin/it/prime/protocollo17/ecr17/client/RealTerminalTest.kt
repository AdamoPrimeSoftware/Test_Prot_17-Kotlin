package it.prime.protocollo17.ecr17.client

import it.prime.protocollo17.ecr17.event.FrameDirection
import it.prime.protocollo17.ecr17.event.FrameKind
import it.prime.protocollo17.ecr17.event.ProtocolEvent
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.CommandOutcome
import it.prime.protocollo17.ecr17.model.Endpoint
import it.prime.protocollo17.ecr17.model.StatusResponse
import it.prime.protocollo17.ecr17.model.TerminalIds
import it.prime.protocollo17.ecr17.profile.Ecr17Config
import it.prime.protocollo17.ecr17.profile.TerminalProfiles
import it.prime.protocollo17.ecr17.transport.TcpTransport
import it.prime.protocollo17.ecr17.wire.Hex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.Collections

/**
 * Prova contro il terminale reale (§7.10), esclusa dalla build normale: parte solo con `-PrealTerminal` e con
 * `P17_HOST` impostato (`P17_PORT`, default 5577). Profilo Ingenico.
 *
 * Esegue SOLO comandi di servizio (`s`, `T`, `G`): mai comandi finanziari contro un terminale vero da un test
 * automatico (M1). Verifica che il frame `s` sul filo sia quello della cattura A§9.8 (`02 30…73 03 3D`) e che la
 * risposta sia uno `s`.
 *
 * Avvio: `.\gradlew.bat cleanTest test -PrealTerminal --no-build-cache --tests "*RealTerminalTest"`.
 */
@Tag("real-terminal")
class RealTerminalTest {

    private fun config(): Ecr17Config {
        val host = System.getenv("P17_HOST")
        Assumptions.assumeTrue(!host.isNullOrBlank(), "P17_HOST non impostato: test del terminale reale saltato")
        val port = System.getenv("P17_PORT")?.toIntOrNull() ?: TerminalProfiles.INGENICO.defaultPort ?: DEFAULT_PORT
        return Ecr17Config(
            profile = TerminalProfiles.INGENICO.id,
            endpoint = Endpoint(host!!, port),
            ids = TerminalIds(),
            options = TerminalProfiles.INGENICO.defaults,
        )
    }

    @Test
    fun `stato, totali e ultimo esito sul terminale reale`() = runBlocking {
        val cfg = config()
        val events = Collections.synchronizedList(mutableListOf<ProtocolEvent>())
        val client = Ecr17Client(configProvider = { cfg }, transport = TcpTransport(), eventSink = { events += it })
        try {
            val status = client.status()
            val sent = events.filterIsInstance<ProtocolEvent.Wire>()
                .first { it.direction == FrameDirection.TX && it.kind == FrameKind.APPLICATION }
            val hex = Hex.spaced(sent.bytes)
            assertTrue(hex.startsWith("02 30") && hex.endsWith("73 03 3D"), "frame 's' atteso 02 30…73 03 3D, era $hex")
            val answered = assertInstanceOf(CommandOutcome.Answered::class.java, status, "esito di s: $status")
            assertEquals('s', assertInstanceOf(StatusResponse::class.java, answered.response).replyCode)

            val totals = client.totals()
            assertTrue(totals is CommandOutcome.Answered || totals is CommandOutcome.Failed, "esito di T: $totals")

            val last = client.sendLastResult()
            assertEquals(CommandCode.LAST_RESULT, last.command, "esito di G: $last")
        } finally {
            client.close()
        }
    }

    private companion object {
        const val DEFAULT_PORT = 5577
    }
}
