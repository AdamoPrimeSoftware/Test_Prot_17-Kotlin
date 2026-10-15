package it.prime.protocollo17.domain.util

import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.model.settings.OptionKey
import it.prime.protocollo17.domain.model.settings.ProtocolSettings
import it.prime.protocollo17.domain.model.settings.TerminalSettings
import it.prime.protocollo17.ecr17.profile.ProtocolOptions
import it.prime.protocollo17.ecr17.profile.TerminalProfileId
import it.prime.protocollo17.ecr17.profile.TerminalProfiles
import it.prime.protocollo17.ecr17.wire.LrcMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigResolverTest {

    private fun settings(terminal: TerminalSettings = TerminalSettings(), protocol: ProtocolSettings = ProtocolSettings()) =
        AppSettings(terminal = terminal, protocol = protocol)

    /** Le impostazioni di default devono produrre esattamente il filo Ingenico osservato, senza avvisi. */
    @Test
    fun `Ingenico di default usa la porta 5577 e le opzioni di default`() {
        val r = ConfigResolver.resolve(AppSettings())

        assertEquals(TerminalProfileId.INGENICO, r.config.profile, "profilo")
        assertEquals(5577, r.config.endpoint.port, "porta del profilo")
        assertEquals("192.168.1.131", r.config.endpoint.host, "host")
        assertEquals(ProtocolOptions(), r.config.options, "opzioni")
        assertTrue(r.usable, "config utilizzabile: issues=${r.issues} problems=${r.problems}")
        assertTrue(r.warnings.isEmpty(), "nessun avviso con i default: ${r.warnings}")
    }

    /** Un default di ProtocolSettings diverso da ProtocolOptions cambierebbe il filo a ogni primo avvio. */
    @Test
    fun `ProtocolSettings di default coincide con ProtocolOptions e con i valori del profilo Ingenico`() {
        val resolved = ConfigResolver.resolve(settings(protocol = ProtocolSettings())).config.options
        assertEquals(ProtocolOptions(), resolved, "ProtocolSettings() risolto")
        assertEquals(ProtocolSettings(), ConfigResolver.profileDefaults(TerminalProfiles.INGENICO), "profileDefaults(INGENICO)")
        // Campo per campo: textOf di ogni chiave uguale tra i default persistiti e quelli del profilo.
        for (key in OptionKey.entries) {
            assertEquals(
                ConfigResolver.textOf(ProtocolSettings(), key),
                ConfigResolver.textOf(ConfigResolver.profileDefaults(TerminalProfiles.INGENICO), key),
                "chiave $key",
            )
        }
    }

    @Test
    fun `withValue applica il valore normalizzato`() {
        val updated = ConfigResolver.withValue(ProtocolSettings(), OptionKey.ACK_TIMEOUT_MS, "3000")
        assertEquals(3_000L, updated.ackTimeoutMs, "ackTimeoutMs")
        assertEquals(ProtocolSettings().copy(ackTimeoutMs = 3_000), updated, "nessun altro campo cambia")
    }

    @Test
    fun `withValue di textOf lascia invariato ogni campo`() {
        val custom = ProtocolSettings(appLrcMode = "std", controlLrcTx = "SAME_AS_APP", ecrPrinting = true, nakRetryCount = 1)
        for (key in OptionKey.entries) {
            assertEquals(custom, ConfigResolver.withValue(custom, key, ConfigResolver.textOf(custom, key)), "chiave $key")
        }
    }

    /** L1: prima un LRC scritto male ricadeva in silenzio su std e ogni comando riceveva NAK senza spiegazione. */
    @Test
    fun `appLrcMode illeggibile produce un issue e rende la config non utilizzabile`() {
        val r = ConfigResolver.resolve(settings(protocol = ProtocolSettings(appLrcMode = "sxt")))

        assertFalse(r.usable, "config non utilizzabile")
        val issue = r.issues.single()
        assertEquals("appLrcMode", issue.key, "chiave")
        assertEquals("Valore 'sxt' non valido per LRC applicativo", issue.message, "messaggio")
        assertEquals(LrcMode.STX, r.config.options.appLrcMode, "valore del profilo nel config, mai una sostituzione usabile")
    }

    @Test
    fun `ogni enum illeggibile produce il proprio issue`() {
        val r = ConfigResolver.resolve(
            settings(
                protocol = ProtocolSettings(
                    controlLrcTx = "WIRED", controlLrcRx = "x", appLrcRx = "y", financialAckPolicy = "z", connectionMode = "w",
                ),
            ),
        )
        assertEquals(
            setOf("controlLrcTx", "controlLrcRx", "appLrcRx", "financialAckPolicy", "connectionMode"),
            r.issues.map { it.key }.toSet(),
            "issues: ${r.issues}",
        )
        assertFalse(r.usable, "non utilizzabile")
    }

    @Test
    fun `enum letti senza badare a maiuscole e spazi`() {
        val r = ConfigResolver.resolve(settings(protocol = ProtocolSettings(appLrcMode = " STX ", controlLrcTx = "same_as_app")))
        assertTrue(r.issues.isEmpty(), "nessun issue: ${r.issues}")
        assertEquals(LrcMode.STX, r.config.options.appLrcMode, "appLrcMode")
    }

    @Test
    fun `CCV senza porta produce il problema porta`() {
        val r = ConfigResolver.resolve(settings(terminal = TerminalSettings(profile = "CCV", port = null)))

        assertEquals(0, r.config.endpoint.port, "porta assente")
        assertTrue(r.problems.any { it.key == "port" }, "problema porta: ${r.problems}")
        assertFalse(r.usable, "non utilizzabile")
    }

    @Test
    fun `CCV produce l'avviso di profilo non verificato`() {
        val r = ConfigResolver.resolve(settings(terminal = TerminalSettings(profile = "CCV", port = 5577)))

        assertTrue(
            r.warnings.any { it.message == "Profilo CCV non verificato: valori copiati da Ingenico" },
            "avviso ASSUMPTION: ${r.warnings}",
        )
        assertTrue(r.usable, "un avviso non blocca")
    }

    @Test
    fun `porta ZVT OPI produce un avviso`() {
        val r = ConfigResolver.resolve(settings(terminal = TerminalSettings(port = 20007)))

        assertTrue(
            r.warnings.any { it.key == "port" && it.message == "Porta tipica ZVT/OPI: probabilmente non è Protocollo 17" },
            "avviso ZVT/OPI: ${r.warnings}",
        )
        assertTrue(r.usable, "un avviso non blocca")
    }

    @Test
    fun `profilo sconosciuto produce un issue`() {
        val r = ConfigResolver.resolve(settings(terminal = TerminalSettings(profile = "VERIFONE")))

        assertEquals("Profilo 'VERIFONE' non riconosciuto: scegli Ingenico o CCV in Opzioni", r.issues.single().message, "issue")
        assertFalse(r.usable, "non utilizzabile")
    }

    @Test
    fun `terminal id non numerico produce un issue`() {
        val r = ConfigResolver.resolve(settings(terminal = TerminalSettings(terminalId = "12AB", cashRegisterId = "123456789")))

        assertEquals(setOf("terminalId", "cashRegisterId"), r.issues.map { it.key }.toSet(), "issues: ${r.issues}")
        assertFalse(r.usable, "non utilizzabile")
    }

    @Test
    fun `parseInput fuori dal range dell'operatore fallisce con messaggio italiano`() {
        val result = ConfigResolver.parseInput(OptionKey.ACK_TIMEOUT_MS, "40")

        assertTrue(result.isFailure, "40 ms non ammesso")
        val error = result.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "IllegalArgumentException")
        assertEquals("Timeout ACK: valore tra 100 e 30000 ms", error?.message, "messaggio")
    }

    @Test
    fun `parseInput normalizza testi validi`() {
        assertEquals("3000", ConfigResolver.parseInput(OptionKey.ACK_TIMEOUT_MS, " 3000 ").getOrNull(), "numero")
        assertEquals("stx", ConfigResolver.parseInput(OptionKey.APP_LRC_MODE, "STX").getOrNull(), "LRC per wireName")
        assertEquals("RETRANSMIT", ConfigResolver.parseInput(OptionKey.FINANCIAL_ACK_POLICY, "retransmit").getOrNull(), "enum")
        assertEquals("true", ConfigResolver.parseInput(OptionKey.ECR_PRINTING, "TRUE").getOrNull(), "booleano")
        assertTrue(ConfigResolver.parseInput(OptionKey.ACK_TIMEOUT_MS, "abc").isFailure, "non numerico")
        assertTrue(ConfigResolver.parseInput(OptionKey.APP_LRC_MODE, "stx-noext").isFailure, "LRC sconosciuto")
        assertTrue(ConfigResolver.parseInput(OptionKey.NAK_RETRY_COUNT, "6").isFailure, "reinvii oltre 5")
    }

    /** Le config rapide dei test (ACK 40 ms) devono restare valide per il protocollo: solo un avviso. */
    @Test
    fun `valore fuori range nel file produce un avviso ma la config resta utilizzabile`() {
        val r = ConfigResolver.resolve(settings(protocol = ProtocolSettings(ackTimeoutMs = 40)))

        assertTrue(r.usable, "utilizzabile: ${r.issues} ${r.problems}")
        assertEquals(40L, r.config.options.ackTimeoutMs, "valore usato così com'è")
        assertTrue(
            r.warnings.any { it.key == "ackTimeoutMs" && it.message == "Timeout ACK 40 ms fuori dal range consigliato 100..30000" },
            "avviso: ${r.warnings}",
        )
    }

    @Test
    fun `range dell'operatore definiti per ogni opzione numerica`() {
        val numeric = OptionKey.entries.filter { ConfigResolver.textOf(ProtocolSettings(), it).toLongOrNull() != null }
        assertEquals(numeric.toSet(), OptionRanges.ranges.keys, "chiavi dei range")
    }
}
