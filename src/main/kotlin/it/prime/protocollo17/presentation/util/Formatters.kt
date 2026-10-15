package it.prime.protocollo17.presentation.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Formattatori di date e durate per la UI. Gli importi NON stanno qui: si usa solo domain.util.formatCents, così
// cronologia, notifiche, journal ed esiti mostrano lo stesso importo nello stesso modo.

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val TIME_MILLIS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")
private val FULL_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

private fun Long.zoned() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())

/** "09:35:05" (ora locale). */
fun formatTime(epochMs: Long): String = TIME.format(epochMs.zoned())

/** "09:35:05.399": log traffico e fasi dello scambio, dove contano i millisecondi. */
fun formatTimeMillis(epochMs: Long): String = TIME_MILLIS.format(epochMs.zoned())

/** "14/10 09:35:05": banner e journal (un tentativo sconosciuto può essere di ieri). */
fun formatDateTime(epochMs: Long): String = DATE_TIME.format(epochMs.zoned())

/** "14/10/2026 09:35:05": dettaglio del journal. */
fun formatFullDateTime(epochMs: Long): String = FULL_DATE_TIME.format(epochMs.zoned())

/** Cronometro "00:23" (minuti:secondi), "1:02:03" oltre l'ora. Valori negativi = 00:00. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1_000)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

/** Secondi leggibili da millisecondi: 120000 ⇒ "120", 2500 ⇒ "2,5" (virgola italiana, niente zeri inutili). */
fun formatSeconds(ms: Long): String {
    val whole = ms / 1_000
    val tenths = (ms % 1_000) / 100
    return if (ms % 1_000 == 0L || tenths == 0L) whole.toString() else "$whole,$tenths"
}
