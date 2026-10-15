package it.prime.protocollo17.ecr17.transport

import it.prime.protocollo17.ecr17.model.CloseCause
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contratto del buffer di ricezione condiviso tra lettore e sessione (§3.17).
 *
 * Protegge le tre regole da cui dipende la money-safety della ricezione: nessun byte perso (prima i repo
 * passavano i chunk in un Channel e un `receive` cancellato poteva perdere proprio l'esito), nessun byte di
 * una connessione vecchia nel buffer di quella nuova (epoche), e i dati arrivati prima di una chiusura sempre
 * leggibili prima che la chiusura venga vista.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RxBufferTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun RxBuffer.append(epoch: Long, src: ByteArray) = append(epoch, src, 0, src.size)

    /** Append e takeAll restituiscono i byte nell'ordine di arrivo e svuotano il buffer. */
    @Test
    fun `append accoda e takeAll restituisce tutto e svuota`() {
        val rx = RxBuffer()
        val epoch = rx.reset()
        assertTrue(rx.isEmpty(), "dopo reset il buffer deve essere vuoto")

        rx.append(epoch, bytes(0x06, 0x03))
        rx.append(epoch, bytes(0x7A, 0x02, 0x30))
        assertFalse(rx.isEmpty())

        assertArrayEquals(bytes(0x06, 0x03, 0x7A, 0x02, 0x30), rx.takeAll(), "ordine di arrivo conservato")
        assertTrue(rx.isEmpty(), "takeAll deve svuotare")
        assertArrayEquals(ByteArray(0), rx.takeAll(), "takeAll su buffer vuoto restituisce un array vuoto")
    }

    /** offset e length di append delimitano esattamente la porzione copiata del buffer di lettura. */
    @Test
    fun `append rispetta offset e length del buffer del lettore`() {
        val rx = RxBuffer()
        val epoch = rx.reset()
        val readBuffer = bytes(0xFF, 0x02, 0x41, 0x03, 0xEE)

        rx.append(epoch, readBuffer, 1, 3)

        assertArrayEquals(bytes(0x02, 0x41, 0x03), rx.takeAll())
    }

    /** Un lettore della connessione precedente non scrive né chiude il buffer di quella nuova. */
    @Test
    fun `un'epoca vecchia viene ignorata da append e markClosed`() {
        val rx = RxBuffer()
        val old = rx.reset()
        val current = rx.reset()
        assertTrue(current > old, "reset deve incrementare l'epoca")

        rx.append(old, bytes(0x02, 0x45))
        rx.markClosed(old, CloseCause.PEER_CLOSED, "vecchio lettore")

        assertTrue(rx.isEmpty(), "byte di un'epoca vecchia non devono entrare")
        assertNull(rx.closedCause, "la chiusura di un'epoca vecchia non deve chiudere quella corrente")

        rx.append(current, bytes(0x06))
        assertArrayEquals(bytes(0x06), rx.takeAll())
    }

    /** Contratto C++ (A§6.2): i byte arrivati prima della caduta restano leggibili anche dopo markClosed. */
    @Test
    fun `markClosed dopo i dati lascia i dati leggibili`() {
        val rx = RxBuffer()
        val epoch = rx.reset()
        rx.append(epoch, bytes(0x02, 0x30, 0x03, 0x33))

        rx.markClosed(epoch, CloseCause.PEER_CLOSED, "EOF")

        assertEquals(CloseCause.PEER_CLOSED, rx.closedCause)
        assertEquals("EOF", rx.closedDetail)
        assertFalse(rx.isEmpty(), "i dati devono restare dopo la chiusura")
        assertArrayEquals(bytes(0x02, 0x30, 0x03, 0x33), rx.takeAll())
    }

    /** La prima causa di chiusura spiega cosa è successo: un abort successivo non la sovrascrive. */
    @Test
    fun `la prima causa di chiusura resta e reset la azzera`() {
        val rx = RxBuffer()
        val epoch = rx.reset()

        rx.markClosed(epoch, CloseCause.RESET, "Connection reset")
        rx.markClosedCurrent(CloseCause.ABORTED, null)
        rx.markClosed(epoch, CloseCause.IO_ERROR, null)

        assertEquals(CloseCause.RESET, rx.closedCause)
        assertEquals("Connection reset", rx.closedDetail)

        rx.reset()
        assertNull(rx.closedCause, "una nuova connessione riparte aperta")
        assertNull(rx.closedDetail)

        rx.markClosedCurrent(CloseCause.LOCAL, "fine comando")
        assertEquals(CloseCause.LOCAL, rx.closedCause)
    }

    /** awaitDataOrClose si sveglia all'arrivo di byte (la sessione aspetta solo il segnale, mai i dati). */
    @Test
    fun `awaitDataOrClose si sveglia su append`() = runTest {
        val rx = RxBuffer()
        val epoch = rx.reset()
        rx.takeAll()
        var woke = false

        launch { rx.awaitDataOrClose(); woke = true }
        runCurrent()
        assertFalse(woke, "senza dati né chiusura deve restare in attesa")

        rx.append(epoch, bytes(0x06, 0x03, 0x7A))
        runCurrent()

        assertTrue(woke, "l'append deve svegliare chi aspetta")
        assertArrayEquals(bytes(0x06, 0x03, 0x7A), rx.takeAll())
    }

    /** awaitDataOrClose si sveglia anche alla chiusura, senza dati: la sessione vede LinkClosed. */
    @Test
    fun `awaitDataOrClose si sveglia su chiusura`() = runTest {
        val rx = RxBuffer()
        rx.reset()
        var woke = false

        launch { rx.awaitDataOrClose(); woke = true }
        runCurrent()
        assertFalse(woke)

        rx.markClosedCurrent(CloseCause.ABORTED, null)
        runCurrent()

        assertTrue(woke, "la chiusura deve svegliare chi aspetta")
        assertEquals(CloseCause.ABORTED, rx.closedCause)
    }

    /** Un segnale perso o già consumato è innocuo: con dati già presenti l'attesa ritorna subito. */
    @Test
    fun `awaitDataOrClose ritorna subito se i dati sono gia presenti`() = runTest {
        val rx = RxBuffer()
        val epoch = rx.reset()
        rx.append(epoch, bytes(0x01))
        rx.takeAll()
        rx.append(epoch, bytes(0x02))
        var woke = false

        launch { rx.awaitDataOrClose(); woke = true }
        runCurrent()

        assertTrue(woke, "con byte nel buffer l'attesa non deve sospendere")
    }

    /** Oltre maxBytes si scartano i byte più vecchi (l'esito arriva in coda) e si segnala RX_OVERFLOW. */
    @Test
    fun `overflow scarta i byte piu vecchi e imposta overflowed`() {
        val rx = RxBuffer(maxBytes = 8)
        val epoch = rx.reset()

        rx.append(epoch, bytes(1, 2, 3, 4, 5))
        assertFalse(rx.overflowed)
        rx.append(epoch, bytes(6, 7, 8, 9, 10, 11))

        assertTrue(rx.overflowed, "deve segnalare l'overflow")
        assertArrayEquals(bytes(4, 5, 6, 7, 8, 9, 10, 11), rx.takeAll(), "restano gli ultimi maxBytes byte")

        rx.reset()
        assertFalse(rx.overflowed, "reset azzera il flag di overflow")
    }

    /** Un solo chunk più grande del buffer conserva la sua coda. */
    @Test
    fun `un chunk piu grande di maxBytes conserva la coda`() {
        val rx = RxBuffer(maxBytes = 4)
        val epoch = rx.reset()
        rx.append(epoch, bytes(9, 9))

        rx.append(epoch, bytes(1, 2, 3, 4, 5, 6))

        assertTrue(rx.overflowed)
        assertArrayEquals(bytes(3, 4, 5, 6), rx.takeAll())
    }

    /** Il buffer cresce oltre la capacità iniziale senza perdere byte finché resta sotto maxBytes. */
    @Test
    fun `molti append sotto il limite non perdono byte`() {
        val rx = RxBuffer()
        val epoch = rx.reset()
        val chunk = ByteArray(1000) { (it % 251).toByte() }

        repeat(10) { rx.append(epoch, chunk) }

        val all = rx.takeAll()
        assertEquals(10_000, all.size)
        assertFalse(rx.overflowed)
        for (i in 0 until 10) {
            assertArrayEquals(chunk, all.copyOfRange(i * 1000, (i + 1) * 1000), "chunk $i alterato")
        }
    }
}
