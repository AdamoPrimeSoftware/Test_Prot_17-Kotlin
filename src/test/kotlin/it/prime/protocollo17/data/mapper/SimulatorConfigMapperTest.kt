package it.prime.protocollo17.data.mapper

import it.prime.protocollo17.domain.model.settings.SimulatorSettings
import it.prime.protocollo17.ecr17.sim.SimDecision
import it.prime.protocollo17.ecr17.sim.SimFault
import it.prime.protocollo17.ecr17.sim.SimulatorConfig
import it.prime.protocollo17.ecr17.wire.ControlLrcRule
import it.prime.protocollo17.ecr17.wire.LrcMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimulatorConfigMapperTest {

    @Test
    fun `impostazioni di default producono la config del simulatore senza avvisi`() {
        val mapping = SimulatorConfigMapper.toConfig(SimulatorSettings())

        assertTrue(mapping.warnings.isEmpty(), "nessun avviso: ${mapping.warnings}")
        val c = mapping.config
        assertEquals(15577, c.port, "porta")
        assertEquals("127.0.0.1", c.bindHost, "solo loopback")
        assertEquals("02575733", c.terminalId, "TID")
        assertEquals(LrcMode.STX, c.appLrcMode, "LRC")
        assertEquals(ControlLrcRule.WIRE, c.ackRule, "ACK")
        assertEquals(SimDecision.APPROVE, c.decision, "decisione")
        assertEquals(SimFault.NONE, c.fault, "guasto")
        assertEquals(SimulatorConfig().copy(port = 15577), c, "resto dei campi ai default dell'engine")
    }

    @Test
    fun `appLrcMode letto da wireName`() {
        val c = SimulatorConfigMapper.toConfig(SimulatorSettings(appLrcMode = "stx_noext")).config
        assertEquals(LrcMode.STX_NOEXT, c.appLrcMode, "stx_noext")
        assertEquals(LrcMode.STD, SimulatorConfigMapper.toConfig(SimulatorSettings(appLrcMode = " STD ")).config.appLrcMode, "trim e maiuscole")
    }

    /** Il simulatore è uno strumento di sviluppo: un valore illeggibile non lo blocca, diventa default + avviso. */
    @Test
    fun `valori sconosciuti producono default e avvisi`() {
        val mapping = SimulatorConfigMapper.toConfig(
            SimulatorSettings(appLrcMode = "sxt", ackRule = "WIRED", decision = "MAYBE", fault = "EXPLODE", ackDelayMs = -5, terminalId = "12AB"),
        )

        val c = mapping.config
        assertEquals(LrcMode.STX, c.appLrcMode, "LRC di default")
        assertEquals(ControlLrcRule.WIRE, c.ackRule, "ACK di default")
        assertEquals(SimDecision.APPROVE, c.decision, "decisione di default")
        assertEquals(SimFault.NONE, c.fault, "guasto di default")
        assertEquals(SimulatorConfig().ackDelayMs, c.ackDelayMs, "ritardo di default")
        assertEquals(SimulatorConfig().terminalId, c.terminalId, "TID di default")
        assertEquals(6, mapping.warnings.size, "un avviso per valore: ${mapping.warnings}")
        assertTrue(mapping.warnings.any { it.contains("'sxt'") }, "avviso con il valore letto")
    }

    @Test
    fun `campi booleani e guasti riportati`() {
        val c = SimulatorConfigMapper.toConfig(
            SimulatorSettings(
                fault = "silent", decision = "CARD_NOT_PRESENT", ackRule = "SAME_AS_APP", faultOnlyFinancial = false,
                closeAfterTransaction = true, retransmitResultIfNoValidAck = true, receiptAfterResult = false, resultDelayMs = 10,
            ),
        ).config
        assertEquals(SimFault.SILENT, c.fault, "guasto")
        assertEquals(SimDecision.CARD_NOT_PRESENT, c.decision, "decisione")
        assertEquals(ControlLrcRule.SAME_AS_APP, c.ackRule, "regola ACK")
        assertEquals(false, c.faultOnlyFinancial, "faultOnlyFinancial")
        assertEquals(true, c.closeAfterTransaction, "closeAfterTransaction")
        assertEquals(true, c.retransmitResultIfNoValidAck, "retransmitResultIfNoValidAck")
        assertEquals(false, c.receiptAfterResult, "receiptAfterResult")
        assertEquals(10L, c.resultDelayMs, "resultDelayMs")
    }
}
