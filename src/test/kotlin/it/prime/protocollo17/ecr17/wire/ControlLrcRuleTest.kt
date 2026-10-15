package it.prime.protocollo17.ecr17.wire

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * LRC dei frame di controllo nelle due convenzioni (A§9.2, L§13.3, decisione U3).
 *
 * Prima i repo calcolavano l'LRC di ACK/NAK con la stessa modalità dei frame applicativi: in `stx` uscivano
 * `06 03 78`, mentre l'Ingenico (e la DLL) usano sempre `06 03 7A`, e rust scartava l'ACK reale facendo
 * ritrasmettere un pagamento (A§3.3). Ora `WIRE` è indipendente dalla modalità e `SAME_AS_APP` riproduce i repo.
 */
class ControlLrcRuleTest {

    private val ack = ControlChars.ACK
    private val nak = ControlChars.NAK

    /** WIRE = XOR dei byte sul filo: identico in ogni modalità applicativa (13/13 ACK catturati sull'Ingenico). */
    @Test
    fun `WIRE produce 06 03 7A e 15 03 69 in ogni modalita`() {
        for (mode in LrcMode.entries) {
            assertArrayEquals(Hex.parse("06 03 7A"), ControlLrcRule.WIRE.encode(ack, mode), "ACK WIRE in $mode")
            assertArrayEquals(Hex.parse("15 03 69"), ControlLrcRule.WIRE.encode(nak, mode), "NAK WIRE in $mode")
        }
    }

    /** SAME_AS_APP = appMode.compute([ctrl]), i valori dei repo padosoft (A§9.2). */
    @Test
    fun `SAME_AS_APP riproduce i valori dei repo nelle 4 modalita`() {
        val expected = mapOf(
            LrcMode.STD to ("06 03 79" to "15 03 6A"),
            LrcMode.STX to ("06 03 78" to "15 03 6B"),
            LrcMode.NOEXT to ("06 03 7A" to "15 03 69"),
            LrcMode.STX_NOEXT to ("06 03 7B" to "15 03 68"),
        )
        for ((mode, frames) in expected) {
            assertArrayEquals(Hex.parse(frames.first), ControlLrcRule.SAME_AS_APP.encode(ack, mode), "ACK SAME_AS_APP in $mode")
            assertArrayEquals(Hex.parse(frames.second), ControlLrcRule.SAME_AS_APP.encode(nak, mode), "NAK SAME_AS_APP in $mode")
        }
    }

    /** encode è sempre [ctrl, ETX, lrcFor]: tre byte, nessuno STX sul filo. */
    @Test
    fun `encode e lrcFor sono coerenti`() {
        for (rule in ControlLrcRule.entries) {
            for (mode in LrcMode.entries) {
                for (ctrl in listOf(ack, nak)) {
                    val frame = rule.encode(ctrl, mode)
                    assertEquals(3, frame.size, "$rule/$mode: tre byte")
                    assertEquals(ctrl, frame[0].toInt() and 0xFF, "$rule/$mode: lead")
                    assertEquals(ControlChars.ETX, frame[1].toInt() and 0xFF, "$rule/$mode: ETX")
                    assertEquals(rule.lrcFor(ctrl, mode), frame[2].toInt() and 0xFF, "$rule/$mode: LRC")
                }
            }
        }
    }

    /** In noext le due convenzioni coincidono: è il caso in cui lo splitter preferisce il match WIRE. */
    @Test
    fun `in noext WIRE e SAME_AS_APP coincidono`() {
        for (ctrl in listOf(ack, nak)) {
            assertEquals(
                ControlLrcRule.WIRE.lrcFor(ctrl, LrcMode.NOEXT),
                ControlLrcRule.SAME_AS_APP.lrcFor(ctrl, LrcMode.NOEXT),
            )
        }
    }

    /**
     * Lo splitter tratta 01/02 dopo `06`/`15` o dopo `06 03`/`15 03` come inizio del frame successivo (§3.7):
     * è corretto solo perché nessun LRC valido di un controllo vale 01 o 02, in nessuna regola e modalità.
     */
    @Test
    fun `nessun LRC di controllo valido vale 01 o 02`() {
        for (rule in ControlLrcRule.entries) {
            for (mode in LrcMode.entries) {
                for (ctrl in listOf(ack, nak)) {
                    val lrc = rule.lrcFor(ctrl, mode)
                    assertFalse(lrc == ControlChars.SOH || lrc == ControlChars.STX, "$rule/$mode/$ctrl: LRC ${"%02X".format(lrc)}")
                }
            }
        }
    }
}
