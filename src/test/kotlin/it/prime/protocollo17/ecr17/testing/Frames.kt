package it.prime.protocollo17.ecr17.testing

import it.prime.protocollo17.ecr17.profile.ProtocolOptions
import it.prime.protocollo17.ecr17.wire.ControlLrcRule
import it.prime.protocollo17.ecr17.wire.LrcMode

/**
 * [WP0] Solo costanti e LrcMode.compute: compilabile e usabile da WP1, WP2, WP3, WP4 senza dipendenze.
 *
 * Mai `Hex.parse` qui dentro: è uno stub di WP1, e una fixture che lo usasse farebbe fallire i test di tutti
 * gli altri pacchetti prima del merge di WP1. I byte di controllo sono scritti come letterali.
 *
 * Vettori verificati (§7.1, Appendice): P1 167 B LRC stx 2F; E4 71 B LRC stx 46 / std 47; E4_FLIPPED_12 LRC std 46
 * (= LRC stx di E4, SE-48); E22 71 B LRC stx 21; I75_KO_CLEAR 75 B.
 */
object Frames {

    /** Config rapida di sessione (§7.1): passa i controlli STRUTTURALI di validate(), non i range dell'operatore. */
    val fast: ProtocolOptions = ProtocolOptions(
        appLrcMode = LrcMode.STX,
        ackTimeoutMs = 40,
        responseTimeoutMs = 200,
        nakRetryCount = 3,
        ackTimeoutRetransmitCount = 3,
        retryDelayMs = 1,
        postResultDrainMs = 20,
        receiptDrainMs = 60,
        preSendQuietMs = 0,
    )

    /** Variante `std` + `SAME_AS_APP` per i vettori dei repo (A§9.6). */
    val fastStd: ProtocolOptions = fast.copy(appLrcMode = LrcMode.STD, controlLrcTx = ControlLrcRule.SAME_AS_APP)

    /** STX payload ETX LRC, con LrcMode.compute. Payload in ISO-8859-1: 1 carattere = 1 byte. */
    fun app(payload: String, mode: LrcMode = LrcMode.STX): ByteArray {
        val body = payload.toByteArray(Charsets.ISO_8859_1)
        val out = ByteArray(body.size + 3)
        out[0] = STX
        body.copyInto(out, destinationOffset = 1)
        out[body.size + 1] = ETX
        out[body.size + 2] = mode.compute(body).toByte()
        return out
    }

    // Gli array sono creati a ogni accesso: un test che li modificasse non può sporcare quelli successivi.

    /** byteArrayOf(0x06, 0x03, 0x7A): ACK con regola WIRE (DLL e terminale Ingenico). */
    val ACKW: ByteArray get() = byteArrayOf(0x06, 0x03, 0x7A)

    /** 06 03 78: ACK SAME_AS_APP in stx (repo padosoft). */
    val ACK78: ByteArray get() = byteArrayOf(0x06, 0x03, 0x78)

    /** 15 03 69: NAK con regola WIRE. */
    val NAKW: ByteArray get() = byteArrayOf(0x15, 0x03, 0x69)

    /** 06 03 79: ACK SAME_AS_APP in std. */
    val ACK_STD: ByteArray get() = byteArrayOf(0x06, 0x03, 0x79)

    /** 01 "OPERAZIONE IN CORSO " 04: il progress dell'Ingenico (20 caratteri). */
    val PRG: ByteArray get() = byteArrayOf(0x01) + "OPERAZIONE IN CORSO ".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x04)

    /** A§9.8 [1]: 167 byte, LRC stx 2F (verificato). Non const: la coda è costruita. */
    val P1: String = "000000000P000000000000000000001" + " ".repeat(128) + "00000000"

    const val E4 = "025757330E01OPERAZIONE ANNULLATA    00000000000000000000000000000000000"          // 71 B, LRC stx 46

    /** E4 con il byte 12 alterato ('1'→'0', esito "00"): il suo LRC std è 46, cioè l'LRC stx di E4 (SE-48). */
    const val E4_FLIPPED_12 = "025757330E00OPERAZIONE ANNULLATA    00000000000000000000000000000000000"

    const val E22 = "025757330E00000558278******4768CLIR484320770947200000000023000004000055"         // 71 B, LRC stx 21
    const val E22_CLEAR = "025757330E000004111111111111111CLIR484320770947200000000023000004000055"   // PAN in chiaro

    /** i75 di L§13.6 con esito "01": la 'i' non ha ramo KO, il PAN in 13/19 va mascherato comunque (A§4.5.4). */
    const val I75_KO_CLEAR = "123456780i010000000004111111111CLIACQ        AUTH01000042000099211152000000"

    const val RESULT_STD = "123456780E0000DATA"        // A§9.6
    const val RECEIPT = "123456780SLINE 1"             // A§9.6
    const val RECEIPT_PAN = "123456780SCARTA 4111111111111111"   // riga S con PAN in chiaro (M9)
    const val RECEIPT_END = "025757330SFINE}}}}}}\u001B"
    const val U34 = "123456780U000000005HELLO0000000000"   // L§13.6
    const val T34 = "025757330T00" + "0000000000000150" + "000000"   // 'T' 34 B (A§4.5.7), totale 1,50 €

    private const val STX: Byte = 0x02
    private const val ETX: Byte = 0x03
}
