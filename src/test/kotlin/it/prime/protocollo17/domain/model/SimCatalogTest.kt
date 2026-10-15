package it.prime.protocollo17.domain.model

import it.prime.protocollo17.domain.model.simulator.SimCatalog
import it.prime.protocollo17.ecr17.sim.SimDecision
import it.prime.protocollo17.ecr17.sim.SimFault
import it.prime.protocollo17.ecr17.wire.ControlLrcRule
import it.prime.protocollo17.ecr17.wire.LrcMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Il catalogo è scritto a mano perché il dominio non può importare `ecr17.sim` (§2.10): senza questo test un
 * guasto aggiunto a SimFault sparirebbe in silenzio dal menu del simulatore.
 */
class SimCatalogTest {

    @Test
    fun `guasti allineati a SimFault nello stesso ordine`() {
        assertEquals(SimFault.entries.map { it.name }, SimCatalog.faults.map { it.name }, "faults")
    }

    @Test
    fun `decisioni allineate a SimDecision`() {
        assertEquals(SimDecision.entries.map { it.name }, SimCatalog.decisions.map { it.name }, "decisions")
    }

    @Test
    fun `regole ACK allineate a ControlLrcRule`() {
        assertEquals(ControlLrcRule.entries.map { it.name }, SimCatalog.ackRules.map { it.name }, "ackRules")
    }

    @Test
    fun `modalita LRC allineate a LrcMode wireName`() {
        assertEquals(LrcMode.entries.map { it.wireName }, SimCatalog.lrcModes.map { it.name }, "lrcModes")
    }

    @Test
    fun `ogni voce ha etichetta e descrizione`() {
        val all = SimCatalog.faults + SimCatalog.decisions + SimCatalog.ackRules + SimCatalog.lrcModes
        for (option in all) {
            assertTrue(option.label.isNotBlank() && option.description.isNotBlank(), "voce incompleta: $option")
        }
    }
}
