package it.prime.protocollo17.presentation

import it.prime.protocollo17.domain.model.journal.JournalEntry
import it.prime.protocollo17.domain.util.PaymentBlockPolicy
import it.prime.protocollo17.ecr17.error.ValidationError
import it.prime.protocollo17.ecr17.message.RequestEncoder
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.wire.ControlChars
import it.prime.protocollo17.ecr17.wire.Hex
import io.mockk.MockKAnswerScope
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject

/**
 * Ponte verso gli stub degli altri pacchetti di lavoro usati dai ViewModel di WP6: `Hex` (WP1),
 * `RequestEncoder.validate` (WP2) e `PaymentBlockPolicy` (WP5).
 *
 * Ogni chiamata prova PRIMA l'implementazione vera (`callOriginal`): dopo il merge i test esercitano il codice
 * reale e questo file non cambia nulla. Solo finché l'originale è ancora uno stub (`NotImplementedError` di
 * `TODO`) si usa un sostituto minimo scritto dalla spec (§3.8, §5.8): così i test dei ViewModel girano già
 * adesso senza dipendere dall'ordine di merge. Il sostituto di `validate` non applica regole (lista vuota):
 * le regole di validazione si provano in RequestFactoryTest, che NON usa questo ponte.
 */
internal object StubFallbacks {

    fun install() {
        mockkObject(Hex, RequestEncoder, PaymentBlockPolicy)
        every { Hex.spaced(any(), any(), any()) } answers {
            orFallback { fakeSpaced(firstArg(), secondArg(), thirdArg()) }
        }
        every { Hex.printable(any()) } answers { orFallback { fakePrintable(firstArg()) } }
        every { RequestEncoder.validate(any(), any()) } answers { orFallback { emptyList<ValidationError>() } }
        every { PaymentBlockPolicy.blocking(any(), any()) } answers { orFallback { fakeBlocking(firstArg(), secondArg()) } }
        every { PaymentBlockPolicy.blockedCommands(any()) } answers {
            orFallback {
                val unresolved = firstArg<List<JournalEntry>>()
                CommandCode.entries.filter { fakeBlocking(it, unresolved).isNotEmpty() }.toSet()
            }
        }
    }

    fun uninstall() {
        unmockkObject(Hex, RequestEncoder, PaymentBlockPolicy)
    }

    private fun <T, B> MockKAnswerScope<T, B>.orFallback(fallback: () -> T): T = try {
        callOriginal()
    } catch (e: NotImplementedError) {
        fallback()
    }

    private fun fakeSpaced(bytes: ByteArray, upper: Boolean, trailingSpace: Boolean): String {
        val text = bytes.joinToString(" ") { b -> String.format(if (upper) "%02X" else "%02x", b.toInt() and 0xFF) }
        return if (trailingSpace && bytes.isNotEmpty()) "$text " else text
    }

    private fun fakePrintable(bytes: ByteArray): String = buildString {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            when {
                v in 0x20..0x7E -> append(v.toChar())
                ControlChars.nameOf(v) != null -> append("<").append(ControlChars.nameOf(v)).append(">")
                else -> append("<").append(String.format("%02X", v)).append(">")
            }
        }
    }

    /** Regole di PaymentBlockPolicy (§5.8): blocksPayments ⇒ tutti i journaled; H/C ⇒ la famiglia di risposta. */
    private fun fakeBlocking(command: CommandCode, unresolved: List<JournalEntry>): List<JournalEntry> {
        if (!command.journaled) return emptyList()
        return unresolved.filter { entry ->
            val entryCommand = entry.command
            entry.blocksPayments ||
                (entryCommand != null && entryCommand.journaled && entryCommand.expectedReplies.intersect(command.expectedReplies).isNotEmpty())
        }
    }
}
