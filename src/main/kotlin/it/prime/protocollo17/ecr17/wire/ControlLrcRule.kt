package it.prime.protocollo17.ecr17.wire

/**
 * Convenzione dell'LRC dei frame di controllo [ctrl, ETX, LRC] (A§3.3, L§3.2, decisione U3).
 * - WIRE: 0x7F ^ ctrl ^ ETX = XOR di tutti i byte sul filo prima dell'LRC → ACK 06 03 7A, NAK 15 03 69.
 *   È ciò che fanno DLL e terminale Ingenico (13/13 ACK catturati).
 * - SAME_AS_APP: appMode.compute([ctrl]) come i repo padosoft → in STX: ACK 06 03 78, NAK 15 03 6B.
 *
 * È una regola separata dall'LRC applicativo perché la cattura reale le smentisce entrambe se le si
 * lega: i frame applicativi Ingenico usano `stx`, ma il suo ACK è `7A`, cioè l'LRC senza STX (U2).
 */
enum class ControlLrcRule {
    WIRE, SAME_AS_APP;

    fun lrcFor(ctrl: Int, appMode: LrcMode): Int = when (this) {
        WIRE -> (ControlChars.LRC_BASE xor ctrl xor ControlChars.ETX) and 0xFF
        SAME_AS_APP -> appMode.compute(byteArrayOf(ctrl.toByte()))
    }

    fun encode(ctrl: Int, appMode: LrcMode): ByteArray =
        byteArrayOf(ctrl.toByte(), ControlChars.ETX.toByte(), lrcFor(ctrl, appMode).toByte())
}
