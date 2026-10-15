package it.prime.protocollo17.ecr17.wire

import it.prime.protocollo17.ecr17.profile.ControlLrcRxPolicy
import it.prime.protocollo17.ecr17.testing.Frames
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

/**
 * Splitter di stream (§3.7) contro le regole vincolanti della tabella, L§13.3 e i byte reali Ingenico (A§9.8).
 *
 * Protegge le regressioni dei repo che toccano la money-safety: rust scartava l'ACK reale `06 03 7A` in `stx`
 * e faceva ritrasmettere un pagamento; C++ prendeva qualsiasi terna `06 xx xx` e un ACK nudo si mangiava i
 * primi byte dell'esito. In più l'asimmetria del NAK (U3, M2): solo un `15 03` con LRC di una convenzione
 * diventa Control(NAK), l'unico elemento che può far ritrasmettere.
 */
class FrameSplitterTest {

    // ─── Supporto ────────────────────────────────────────────────────────────

    private val tolerant = ControlLrcRxPolicy.TOLERANT
    private val strict = ControlLrcRxPolicy.STRICT

    /** Esito reale [4] di A§9.8: 74 byte, LRC stx 46. */
    private val e4: ByteArray get() = Frames.app(Frames.E4)

    private fun splitter(
        mode: LrcMode = LrcMode.STX,
        policy: ControlLrcRxPolicy = tolerant,
        maxFrameBytes: Int = 4096,
    ) = FrameSplitter(appMode = mode, controlRxPolicy = policy, maxFrameBytes = maxFrameBytes)

    /** Tutti gli elementi estraibili adesso, fino al primo null. */
    private fun FrameSplitter.drain(): List<SplitItem> = buildList { while (true) add(next() ?: break) }

    /** Un solo chunk, poi tutti gli elementi estraibili. */
    private fun split(
        bytes: ByteArray,
        mode: LrcMode = LrcMode.STX,
        policy: ControlLrcRxPolicy = tolerant,
        maxFrameBytes: Int = 4096,
    ): List<SplitItem> = splitter(mode, policy, maxFrameBytes).run { feed(bytes); drain() }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun hex(value: Int?) = value?.let { "%02X".format(it) }

    /** Byte del filo coperti da un elemento: raw per i frame, bytes per gli scarti. */
    private fun SplitItem.wireBytes(): ByteArray = when (this) {
        is SplitItem.Parsed -> frame.raw
        is SplitItem.Discarded -> bytes
    }

    /** Descrizione testuale: confronti leggibili nei messaggi e nei test di equivalenza. */
    private fun describe(item: SplitItem): String = when (item) {
        is SplitItem.Discarded -> "DISCARD ${item.reason} [${Hex.spaced(item.bytes)}]"
        is SplitItem.Parsed -> when (val f = item.frame) {
            is WireFrame.Control -> "${f.kind} ${f.match} lrc=${hex(f.lrc)} [${Hex.spaced(f.raw)}]"
            is WireFrame.Application -> "APP lrc=${hex(f.lrc)} modes=${f.check.matchingModes.sorted()} [${Hex.spaced(f.raw)}]"
            is WireFrame.Progress -> "PRG [${Hex.spaced(f.text)}] [${Hex.spaced(f.raw)}]"
        }
    }

    private fun assertControl(item: SplitItem?, kind: ControlKind, match: ControlLrcMatch, lrc: Int?, raw: ByteArray) {
        val parsed = assertInstanceOf(SplitItem.Parsed::class.java, item, "atteso Parsed(Control), era ${item?.let(::describe)}")
        val control = assertInstanceOf(WireFrame.Control::class.java, parsed.frame, "atteso Control, era ${describe(parsed)}")
        assertEquals(kind, control.kind, "kind di ${describe(parsed)}")
        assertEquals(match, control.match, "match di ${describe(parsed)}")
        assertEquals(hex(lrc), hex(control.lrc), "lrc di ${describe(parsed)}")
        assertArrayEquals(raw, control.raw, "raw di ${describe(parsed)}")
    }

    private fun assertApplication(item: SplitItem?, raw: ByteArray): WireFrame.Application {
        val parsed = assertInstanceOf(SplitItem.Parsed::class.java, item, "atteso Parsed(Application), era ${item?.let(::describe)}")
        val app = assertInstanceOf(WireFrame.Application::class.java, parsed.frame, "atteso Application, era ${describe(parsed)}")
        assertArrayEquals(raw, app.raw, "raw dell'esito: deve arrivare integro")
        assertArrayEquals(raw.copyOfRange(1, raw.size - 2), app.payload, "payload = byte tra STX ed ETX")
        assertEquals(raw.last().toInt() and 0xFF, app.lrc, "lrc = byte dopo l'ETX")
        return app
    }

    private fun assertProgress(item: SplitItem?, text: String) {
        val parsed = assertInstanceOf(SplitItem.Parsed::class.java, item, "atteso Parsed(Progress), era ${item?.let(::describe)}")
        val progress = assertInstanceOf(WireFrame.Progress::class.java, parsed.frame, "atteso Progress, era ${describe(parsed)}")
        assertEquals(text, String(progress.text, Charsets.ISO_8859_1))
        assertArrayEquals(bytes(0x01) + ascii(text) + bytes(0x04), progress.raw)
    }

    private fun assertDiscarded(item: SplitItem?, reason: DiscardReason, discarded: ByteArray) {
        val d = assertInstanceOf(SplitItem.Discarded::class.java, item, "atteso Discarded($reason), era ${item?.let(::describe)}")
        assertEquals(reason, d.reason, "motivo di ${describe(d)}")
        assertArrayEquals(discarded, d.bytes, "byte scartati di ${describe(d)}")
    }

    // ─── ACK (L§13.3) ────────────────────────────────────────────────────────

    /** L'ACK reale dell'Ingenico in stx: prima rust lo scartava (A§3.3), ora è un match WIRE. */
    @Test
    fun `ACK 06 03 7A in stx e WIRE`() {
        val s = splitter()
        s.feed(Frames.ACKW)
        assertControl(s.next(), ControlKind.ACK, ControlLrcMatch.WIRE, 0x7A, Frames.ACKW)
        assertNull(s.next())
        assertEquals(0, s.pendingSize())
    }

    /** L'ACK dei repo in stx (06 03 78) è riconosciuto come SAME_AS_APP. */
    @Test
    fun `ACK 06 03 78 in stx e SAME_AS_APP`() {
        val items = split(Frames.ACK78)
        assertEquals(1, items.size)
        assertControl(items[0], ControlKind.ACK, ControlLrcMatch.SAME_AS_APP, 0x78, Frames.ACK78)
    }

    /** In std WIRE resta 7A e SAME_AS_APP diventa 79; in noext coincidono e vince WIRE. */
    @Test
    fun `match dell'ACK nelle altre modalita`() {
        assertControl(split(Frames.ACKW, mode = LrcMode.STD).single(), ControlKind.ACK, ControlLrcMatch.WIRE, 0x7A, Frames.ACKW)
        assertControl(split(Frames.ACK_STD, mode = LrcMode.STD).single(), ControlKind.ACK, ControlLrcMatch.SAME_AS_APP, 0x79, Frames.ACK_STD)
        assertControl(split(Frames.ACKW, mode = LrcMode.NOEXT).single(), ControlKind.ACK, ControlLrcMatch.WIRE, 0x7A, Frames.ACKW)
    }

    /** `06 03 00`: ACK con LRC di nessuna convenzione, accettato (con match esplicito) solo in TOLERANT. */
    @Test
    fun `ACK con LRC sconosciuto MISMATCH in tollerante e scartato in rigido`() {
        val ackBadLrc = bytes(0x06, 0x03, 0x00)

        val tolerantItems = split(ackBadLrc, policy = tolerant)
        assertEquals(1, tolerantItems.size)
        assertControl(tolerantItems[0], ControlKind.ACK, ControlLrcMatch.MISMATCH, 0x00, ackBadLrc)

        // Rigido: si scarta solo il lead e si risincronizza; 03 00 diventano rumore chiuso dall'esito.
        val strictItems = split(ackBadLrc + e4, policy = strict)
        assertEquals(3, strictItems.size, strictItems.map(::describe).toString())
        assertDiscarded(strictItems[0], DiscardReason.CONTROL_LRC_STRICT, bytes(0x06))
        assertDiscarded(strictItems[1], DiscardReason.NOISE, bytes(0x03, 0x00))
        assertApplication(strictItems[2], e4)
    }

    /** ACK spurio `06 FF` (rust session.rs:462-476): scartato e loggato, poi ACK ed esito regolari. */
    @Test
    fun `06 FF prima di un ACK e dell'esito`() {
        val items = split(bytes(0x06, 0xFF) + Frames.ACKW + e4)
        assertEquals(4, items.size, items.map(::describe).toString())
        assertDiscarded(items[0], DiscardReason.CONTROL_WITHOUT_ETX, bytes(0x06))
        assertDiscarded(items[1], DiscardReason.NOISE, bytes(0xFF))
        assertControl(items[2], ControlKind.ACK, ControlLrcMatch.WIRE, 0x7A, Frames.ACKW)
        assertApplication(items[3], e4)
    }

    /** ACK nudo seguito dall'esito: prima C++ prendeva `06 02 p0` come ACK e l'esito era perso. */
    @Test
    fun `ACK nudo seguito da STX lascia l'esito integro`() {
        val items = split(bytes(0x06) + e4)
        assertEquals(2, items.size, items.map(::describe).toString())
        assertControl(items[0], ControlKind.ACK, ControlLrcMatch.BARE, null, bytes(0x06))
        assertApplication(items[1], e4)
    }

    /**
     * `06 03` seguito dall'esito: Control(ACK, BARE) di 2 byte ed esito integro.
     * Prima: MISMATCH che consumava lo STX come LRC, e l'esito diventava rumore (esito perso dopo un ACK).
     */
    @Test
    fun `06 03 seguito da STX e un ACK nudo di 2 byte con esito integro`() {
        val items = split(bytes(0x06, 0x03) + e4, policy = strict)
        assertEquals(2, items.size, items.map(::describe).toString())
        assertControl(items[0], ControlKind.ACK, ControlLrcMatch.BARE, null, bytes(0x06, 0x03))
        assertApplication(items[1], e4)

        // Lo stesso con un progress subito dopo (SOH).
        val withProgress = split(bytes(0x06, 0x03) + Frames.PRG)
        assertEquals(2, withProgress.size, withProgress.map(::describe).toString())
        assertControl(withProgress[0], ControlKind.ACK, ControlLrcMatch.BARE, null, bytes(0x06, 0x03))
        assertProgress(withProgress[1], "OPERAZIONE IN CORSO ")
    }

    /** `06` e `06 03` da soli non bastano per decidere: lo splitter attende il byte successivo. */
    @Test
    fun `06 e 06 03 da soli attendono`() {
        val s = splitter()
        s.feed(bytes(0x06))
        assertNull(s.next())
        assertEquals(1, s.pendingSize())
        s.feed(bytes(0x03))
        assertNull(s.next())
        assertEquals(2, s.pendingSize())
        s.feed(bytes(0x7A))
        assertControl(s.next(), ControlKind.ACK, ControlLrcMatch.WIRE, 0x7A, Frames.ACKW)
        assertEquals(0, s.pendingSize())
    }

    // ─── flushBareControl ────────────────────────────────────────────────────

    /** Alla scadenza dell'attesa ACK un `06` o `06 03` isolato diventa ACK BARE e il buffer si svuota. */
    @Test
    fun `flushBareControl con solo 06 o 06 03 da un ACK BARE`() {
        for (pending in listOf(bytes(0x06), bytes(0x06, 0x03))) {
            val s = splitter()
            s.feed(pending)
            assertNull(s.next())
            assertControl(s.flushBareControl(), ControlKind.ACK, ControlLrcMatch.BARE, null, pending)
            assertEquals(0, s.pendingSize(), "buffer svuotato dopo il flush di ${Hex.spaced(pending)}")
        }
    }

    /** Il rumore davanti a un `06` isolato esce con next(); poi il flush vede solo `06`. */
    @Test
    fun `flushBareControl dopo rumore gia estratto`() {
        val s = splitter()
        s.feed(bytes(0xFF, 0x06))
        assertDiscarded(s.next(), DiscardReason.NOISE, bytes(0xFF))
        assertNull(s.next())
        assertControl(s.flushBareControl(), ControlKind.ACK, ControlLrcMatch.BARE, null, bytes(0x06))
    }

    /** Con qualsiasi altro contenuto flushBareControl non fa nulla e non tocca il buffer. */
    @Test
    fun `flushBareControl restituisce null e non tocca il buffer negli altri casi`() {
        val empty = splitter()
        assertNull(empty.flushBareControl())

        val partialFrame = splitter()
        partialFrame.feed(bytes(0x02, 0x41))
        assertNull(partialFrame.next())
        assertNull(partialFrame.flushBareControl())
        assertEquals(2, partialFrame.pendingSize())

        val noise = splitter()
        noise.feed(bytes(0x41, 0x42))
        assertNull(noise.next())
        assertNull(noise.flushBareControl())
        assertArrayEquals(bytes(0x41, 0x42), noise.clear())
    }

    // ─── NAK: asimmetria (U3, M2) ────────────────────────────────────────────

    /** `15` seguito dall'esito: NAK ambiguo (mai un reinvio) ed esito integro. */
    @Test
    fun `NAK nudo seguito da STX e ambiguo e lascia l'esito integro`() {
        val items = split(bytes(0x15) + e4)
        assertEquals(2, items.size, items.map(::describe).toString())
        assertDiscarded(items[0], DiscardReason.AMBIGUOUS_NAK, bytes(0x15))
        assertApplication(items[1], e4)
    }

    /** `15 03` seguito dall'esito: 2 byte ambigui ed esito integro. */
    @Test
    fun `15 03 seguito da STX e ambiguo di 2 byte con esito integro`() {
        val items = split(bytes(0x15, 0x03) + e4)
        assertEquals(2, items.size, items.map(::describe).toString())
        assertDiscarded(items[0], DiscardReason.AMBIGUOUS_NAK, bytes(0x15, 0x03))
        assertApplication(items[1], e4)
    }

    /**
     * `15 03 00` è scartato come AMBIGUOUS_NAK anche in TOLERANT: la tolleranza vale solo per l'ACK, perché un
     * NAK accettato per errore farebbe ritrasmettere un pagamento (M2). Lo stesso per il NAK std `15 03 6A`
     * ricevuto in stx (nessuna delle due convenzioni attive).
     */
    @Test
    fun `NAK con LRC di nessuna convenzione e ambiguo con qualsiasi policy`() {
        for (policy in ControlLrcRxPolicy.entries) {
            for (nak in listOf(bytes(0x15, 0x03, 0x00), bytes(0x15, 0x03, 0x6A))) {
                val items = split(nak, policy = policy)
                assertEquals(1, items.size, "$policy ${Hex.spaced(nak)}: ${items.map(::describe)}")
                assertDiscarded(items[0], DiscardReason.AMBIGUOUS_NAK, nak)
            }
        }
    }

    /** Solo `15 03` con LRC WIRE (69) o SAME_AS_APP (6B in stx) diventa Control(NAK), in entrambe le policy. */
    @Test
    fun `NAK con LRC valido diventa Control NAK`() {
        for (policy in ControlLrcRxPolicy.entries) {
            assertControl(split(Frames.NAKW, policy = policy).single(), ControlKind.NAK, ControlLrcMatch.WIRE, 0x69, Frames.NAKW)
            val nak6b = bytes(0x15, 0x03, 0x6B)
            assertControl(split(nak6b, policy = policy).single(), ControlKind.NAK, ControlLrcMatch.SAME_AS_APP, 0x6B, nak6b)
        }
        val nakStd = bytes(0x15, 0x03, 0x6A)
        assertControl(split(nakStd, mode = LrcMode.STD).single(), ControlKind.NAK, ControlLrcMatch.SAME_AS_APP, 0x6A, nakStd)
    }

    /** `15` seguito da un byte che non è ETX né un inizio frame: scartato come controllo senza ETX. */
    @Test
    fun `15 seguito da un byte qualsiasi e un controllo senza ETX`() {
        val items = split(bytes(0x15, 0xFF) + Frames.ACKW)
        assertEquals(3, items.size, items.map(::describe).toString())
        assertDiscarded(items[0], DiscardReason.CONTROL_WITHOUT_ETX, bytes(0x15))
        assertDiscarded(items[1], DiscardReason.NOISE, bytes(0xFF))
        assertControl(items[2], ControlKind.ACK, ControlLrcMatch.WIRE, 0x7A, Frames.ACKW)
    }

    /** Alla scadenza dell'attesa ACK un `15` o `15 03` isolato resta ambiguo: mai un Control(NAK). */
    @Test
    fun `flushBareControl con solo 15 o 15 03 da AMBIGUOUS_NAK`() {
        for (pending in listOf(bytes(0x15), bytes(0x15, 0x03))) {
            val s = splitter()
            s.feed(pending)
            assertNull(s.next())
            assertDiscarded(s.flushBareControl(), DiscardReason.AMBIGUOUS_NAK, pending)
            assertEquals(0, s.pendingSize(), "buffer svuotato dopo il flush di ${Hex.spaced(pending)}")
        }
    }

    // ─── Frame applicativi ───────────────────────────────────────────────────

    /**
     * Il byte dopo l'ETX è l'LRC e non viene mai interpretato (A§3.1): `"123456780E00"+"0"*60` ha LRC std 02
     * (sembra uno STX) e LRC stx 03 (sembra un ETX). In entrambi i casi un solo esito, poi l'ACK che segue.
     */
    @Test
    fun `LRC uguale a 02 o 03 non viene interpretato`() {
        val payload = "123456780E00" + "0".repeat(60)

        val std = Frames.app(payload, LrcMode.STD)
        assertEquals(0x02, std.last().toInt() and 0xFF)
        val stdItems = split(std + Frames.ACK_STD, mode = LrcMode.STD)
        assertEquals(2, stdItems.size, stdItems.map(::describe).toString())
        assertTrue(assertApplication(stdItems[0], std).check.validInConfiguredMode)
        assertControl(stdItems[1], ControlKind.ACK, ControlLrcMatch.SAME_AS_APP, 0x79, Frames.ACK_STD)

        val stx = Frames.app(payload, LrcMode.STX)
        assertEquals(0x03, stx.last().toInt() and 0xFF)
        val stxItems = split(stx + Frames.ACKW)
        assertEquals(2, stxItems.size, stxItems.map(::describe).toString())
        assertTrue(assertApplication(stxItems[0], stx).check.validInConfiguredMode)
        assertControl(stxItems[1], ControlKind.ACK, ControlLrcMatch.WIRE, 0x7A, Frames.ACKW)
    }

    /** Righe reali [2][3][4] di A§9.8 concatenate: ACK, progress ed esito valido SOLO in stx. */
    @Test
    fun `righe reali Ingenico concatenate`() {
        val items = split(Frames.ACKW + Frames.PRG + e4)
        assertEquals(3, items.size, items.map(::describe).toString())
        assertControl(items[0], ControlKind.ACK, ControlLrcMatch.WIRE, 0x7A, Frames.ACKW)
        assertProgress(items[1], "OPERAZIONE IN CORSO ")
        val app = assertApplication(items[2], e4)
        assertEquals(0x46, app.lrc)
        assertEquals(setOf(LrcMode.STX), app.check.matchingModes)
        assertTrue(app.check.validInConfiguredMode)
        assertEquals('E', app.replyCode)
        assertFalse(app.isReceiptLine)
    }

    /**
     * E4 con il byte 12 alterato e l'LRC stx originale (46): l'LRC torna solo in std. La sessione deve vedere
     * che il frame NON è valido nella modalità configurata (SE-48: con ACCEPT_ANY_MODE sarebbe un KO diventato OK).
     */
    @Test
    fun `frame valido solo in un'altra modalita`() {
        val flipped = bytes(0x02) + ascii(Frames.E4_FLIPPED_12) + bytes(0x03, 0x46)
        val app = assertApplication(split(flipped).single(), flipped)
        assertEquals(setOf(LrcMode.STD), app.check.matchingModes)
        assertFalse(app.check.validInConfiguredMode)
        assertTrue(app.check.validInSomeMode)
        assertEquals(LrcMode.STX, app.check.configuredMode)
    }

    /** LRC corrotto in tutte le modalità: il frame è comunque consegnato, con matchingModes vuoto. */
    @Test
    fun `frame con LRC di nessuna modalita`() {
        val corrupted = e4.also { it[it.size - 1] = (it.last().toInt() xor 0xFF).toByte() }
        val app = assertApplication(split(corrupted).single(), corrupted)
        assertTrue(app.check.matchingModes.isEmpty())
        assertFalse(app.check.validInSomeMode)
    }

    /** Riga di scontrino S (A§4.5.9) riconosciuta dal codice in pos 10. */
    @Test
    fun `riga di scontrino`() {
        val receipt = Frames.app(Frames.RECEIPT_END)
        val app = assertApplication(split(receipt).single(), receipt)
        assertEquals('S', app.replyCode)
        assertTrue(app.isReceiptLine)
    }

    // ─── Risincronizzazione e limiti ─────────────────────────────────────────

    /** STX o SOH annidati prima del terminatore: si scartano i byte fino al nuovo inizio e si riparte da lì. */
    @Test
    fun `inizio annidato provoca RESYNC_ON_NEW_START`() {
        val cd = Frames.app("CD")
        val stxInStx = split(bytes(0x02, 0x41, 0x42) + cd)
        assertEquals(2, stxInStx.size, stxInStx.map(::describe).toString())
        assertDiscarded(stxInStx[0], DiscardReason.RESYNC_ON_NEW_START, bytes(0x02, 0x41, 0x42))
        assertApplication(stxInStx[1], cd)

        val sohInStx = split(bytes(0x02, 0x41) + Frames.PRG)
        assertEquals(2, sohInStx.size, sohInStx.map(::describe).toString())
        assertDiscarded(sohInStx[0], DiscardReason.RESYNC_ON_NEW_START, bytes(0x02, 0x41))
        assertProgress(sohInStx[1], "OPERAZIONE IN CORSO ")

        val stxInSoh = split(bytes(0x01, 0x41, 0x42) + e4)
        assertEquals(2, stxInSoh.size, stxInSoh.map(::describe).toString())
        assertDiscarded(stxInSoh[0], DiscardReason.RESYNC_ON_NEW_START, bytes(0x01, 0x41, 0x42))
        assertApplication(stxInSoh[1], e4)
    }

    /** Più byte non riconosciuti consecutivi diventano un solo Discarded(NOISE), chiuso dal prossimo inizio. */
    @Test
    fun `rumore consecutivo accorpato`() {
        val noise = bytes(0xFF, 0xFE, 0x7A, 0x00, 0x03, 0x04)
        val items = split(noise + Frames.ACKW)
        assertEquals(2, items.size, items.map(::describe).toString())
        assertDiscarded(items[0], DiscardReason.NOISE, noise)
        assertControl(items[1], ControlKind.ACK, ControlLrcMatch.WIRE, 0x7A, Frames.ACKW)
    }

    /** Rumore in coda senza un inizio frame: resta in attesa (potrebbe continuare) e clear() lo restituisce. */
    @Test
    fun `rumore in coda resta pendente e clear lo restituisce`() {
        val s = splitter()
        s.feed(bytes(0xFF, 0xFE))
        assertNull(s.next())
        assertEquals(2, s.pendingSize())
        assertArrayEquals(bytes(0xFF, 0xFE), s.clear())
        assertEquals(0, s.pendingSize())
        assertArrayEquals(ByteArray(0), s.clear())
    }

    /**
     * Frame applicativo oltre maxFrameBytes: il limite vale per il frame intero (STX…ETX LRC) e anche quando
     * l'ETX è già nel buffer, così l'esito non dipende da come arrivano i chunk.
     */
    @Test
    fun `frame applicativo oltre maxFrameBytes e OVERSIZED`() {
        val atLimit = Frames.app("A".repeat(13))                  // 16 byte
        assertApplication(split(atLimit, maxFrameBytes = 16).single(), atLimit)

        val overLimit = Frames.app("A".repeat(14))                // 17 byte
        val items = split(overLimit + Frames.ACKW, maxFrameBytes = 16)
        assertDiscarded(items.first(), DiscardReason.OVERSIZED, bytes(0x02))
        assertControl(items.last(), ControlKind.ACK, ControlLrcMatch.WIRE, 0x7A, Frames.ACKW)
        assertTrue(items.none { it is SplitItem.Parsed && it.frame is WireFrame.Application }, items.map(::describe).toString())

        // Senza ETX: attesa finché l'ETX potrebbe ancora stare nel limite, poi OVERSIZED.
        val s = splitter(maxFrameBytes = 16)
        s.feed(bytes(0x02) + ascii("A".repeat(13)))              // ETX possibile all'indice 14
        assertNull(s.next())
        s.feed(ascii("A"))                                         // ETX al più all'indice 15: frame di 17 byte
        assertDiscarded(s.next(), DiscardReason.OVERSIZED, bytes(0x02))
    }

    /** Progress oltre 64 byte senza EOT: OVERSIZED; un progress di 64 byte esatti è valido. */
    @Test
    fun `progress oltre 64 byte e OVERSIZED`() {
        val longText = "P".repeat(62)
        assertProgress(split(bytes(0x01) + ascii(longText) + bytes(0x04)).single(), longText)

        val s = splitter()
        s.feed(bytes(0x01) + ascii("P".repeat(62)))                // 63 byte: l'EOT può ancora arrivare
        assertNull(s.next())
        s.feed(ascii("P"))                                          // 64 byte senza EOT
        assertDiscarded(s.next(), DiscardReason.OVERSIZED, bytes(0x01))
    }

    /** Rumore senza fine: consegnato a blocchi di maxFrameBytes, mai accumulato all'infinito. */
    @Test
    fun `rumore lungo consegnato a blocchi di maxFrameBytes`() {
        val s = splitter(maxFrameBytes = 16)
        s.feed(ascii("N".repeat(15)))
        assertNull(s.next())
        s.feed(ascii("N".repeat(20)))
        assertDiscarded(s.next(), DiscardReason.NOISE, ascii("N".repeat(16)))
        assertDiscarded(s.next(), DiscardReason.NOISE, ascii("N".repeat(16)))
        assertNull(s.next())
        assertEquals(3, s.pendingSize())
    }

    /** maxFrameBytes sotto il frame minimo (STX ETX LRC) è un errore di programmazione. */
    @Test
    fun `maxFrameBytes sotto 3 e rifiutato`() {
        assertThrows<IllegalArgumentException> { splitter(maxFrameBytes = 2) }
    }

    // ─── Buffer ──────────────────────────────────────────────────────────────

    /** feed copia i byte: il buffer di lettura del chiamante può essere riusato subito. */
    @Test
    fun `feed copia i byte ricevuti`() {
        val readBuffer = Frames.ACKW
        val s = splitter()
        s.feed(readBuffer)
        readBuffer.fill(0)
        assertControl(s.next(), ControlKind.ACK, ControlLrcMatch.WIRE, 0x7A, Frames.ACKW)
    }

    /** I frame estratti sono copie: crescita e compattazione del buffer interno non li alterano. */
    @Test
    fun `frame estratti indipendenti dal buffer interno`() {
        val s = splitter()
        s.feed(e4)
        val first = assertApplication(s.next(), e4)
        val big = Frames.app("B".repeat(3000))
        repeat(5) {
            s.feed(Frames.ACKW + big)
            s.drain()
        }
        s.feed(big)
        assertApplication(s.next(), big)
        assertArrayEquals(e4, first.raw, "raw del primo esito invariato")
        assertArrayEquals(ascii(Frames.E4), first.payload, "payload del primo esito invariato")
    }

    // ─── Equivalenza rispetto ai chunk ───────────────────────────────────────

    /** Sequenza con tutti i casi delle regole, comprese le nuove righe BARE e NAK ambigui. */
    private fun mixedStream(): ByteArray =
        bytes(0xFF, 0xFE) +                                        // rumore iniziale
            Frames.ACKW + Frames.PRG + e4 +                        // righe reali [2][3][4]
            bytes(0x06, 0xFF) + Frames.ACK78 +                     // ACK spurio, poi ACK SAME_AS_APP
            bytes(0x06) + e4 +                                     // ACK nudo + esito
            bytes(0x06, 0x03) + e4 +                               // ACK nudo di 2 byte + esito
            bytes(0x15) + e4 +                                     // NAK ambiguo + esito
            bytes(0x15, 0x03) + Frames.PRG +                       // NAK ambiguo di 2 byte + progress
            bytes(0x15, 0x03, 0x00) + Frames.NAKW + bytes(0x15, 0x03, 0x6B) +
            bytes(0x06, 0x03, 0x00) +                              // MISMATCH / CONTROL_LRC_STRICT
            bytes(0x02, 0x41, 0x42) + Frames.app("CD") +           // RESYNC
            bytes(0x01, 0x41) + e4 +                               // RESYNC da SOH
            Frames.app("123456780E00" + "0".repeat(60), LrcMode.STD) + // LRC 02
            Frames.app("123456780E00" + "0".repeat(60), LrcMode.STX) + // LRC 03
            Frames.app(Frames.RECEIPT_END) +
            bytes(0x7A, 0x00) + Frames.ACKW +
            bytes(0x06, 0x03)                                      // in coda: resta pendente

    /** Esegue lo splitter consegnando i byte a chunk delle dimensioni date; in coda il residuo di clear(). */
    private fun runChunked(stream: ByteArray, mode: LrcMode, policy: ControlLrcRxPolicy, chunkSizes: (Int) -> Int): List<String> {
        val s = splitter(mode, policy)
        val out = mutableListOf<SplitItem>()
        var offset = 0
        while (offset < stream.size) {
            val size = minOf(chunkSizes(offset), stream.size - offset)
            s.feed(stream.copyOfRange(offset, offset + size))
            out += s.drain()
            offset += size
        }
        val pending = s.clear()
        // Nessun byte perso o duplicato: gli elementi più il residuo ricompongono esattamente lo stream.
        val recomposed = out.fold(ByteArray(0)) { acc, item -> acc + item.wireBytes() } + pending
        assertArrayEquals(stream, recomposed, "elementi + residuo devono ricomporre lo stream ($mode/$policy)")
        return out.map(::describe) + "PENDING [${Hex.spaced(pending)}]"
    }

    /**
     * Proprietà di §3.7: un solo chunk, un byte alla volta e chunk casuali producono la stessa sequenza.
     * Prima (repo) le decisioni dipendevano da quanti byte c'erano nel buffer: un ACK spezzato in due chunk
     * poteva essere letto diversamente da uno intero.
     */
    @Test
    fun `chunk unico e byte per byte producono la stessa sequenza`() {
        val stream = mixedStream()
        val random = Random(42)
        for (mode in listOf(LrcMode.STX, LrcMode.STD)) {
            for (policy in ControlLrcRxPolicy.entries) {
                val whole = runChunked(stream, mode, policy) { stream.size }
                val oneByOne = runChunked(stream, mode, policy) { 1 }
                assertEquals(whole, oneByOne, "byte per byte in $mode/$policy")
                repeat(20) { round ->
                    val randomChunks = runChunked(stream, mode, policy) { random.nextInt(1, 9) }
                    assertEquals(whole, randomChunks, "chunk casuali (giro $round) in $mode/$policy")
                }
            }
        }
    }

    /** Controllo del contenuto della sequenza mista in stx tollerante (ancora la proprietà a valori concreti). */
    @Test
    fun `sequenza mista in stx tollerante`() {
        val items = runChunked(mixedStream(), LrcMode.STX, tolerant) { 1 }
        val e4Hex = Hex.spaced(e4)
        val prgHex = Hex.spaced(Frames.PRG)
        val expectedHead = listOf(
            "DISCARD NOISE [FF FE]",
            "ACK WIRE lrc=7A [06 03 7A]",
            "PRG [${Hex.spaced(ascii("OPERAZIONE IN CORSO "))}] [$prgHex]",
            "APP lrc=46 modes=[STX] [$e4Hex]",
            "DISCARD CONTROL_WITHOUT_ETX [06]",
            "DISCARD NOISE [FF]",
            "ACK SAME_AS_APP lrc=78 [06 03 78]",
            "ACK BARE lrc=null [06]",
            "APP lrc=46 modes=[STX] [$e4Hex]",
            "ACK BARE lrc=null [06 03]",
            "APP lrc=46 modes=[STX] [$e4Hex]",
            "DISCARD AMBIGUOUS_NAK [15]",
            "APP lrc=46 modes=[STX] [$e4Hex]",
            "DISCARD AMBIGUOUS_NAK [15 03]",
            "PRG [${Hex.spaced(ascii("OPERAZIONE IN CORSO "))}] [$prgHex]",
            "DISCARD AMBIGUOUS_NAK [15 03 00]",
            "NAK WIRE lrc=69 [15 03 69]",
            "NAK SAME_AS_APP lrc=6B [15 03 6B]",
            "ACK MISMATCH lrc=00 [06 03 00]",
            "DISCARD RESYNC_ON_NEW_START [02 41 42]",
        )
        assertEquals(expectedHead, items.take(expectedHead.size))
        assertEquals("PENDING [06 03]", items.last())
    }
}
