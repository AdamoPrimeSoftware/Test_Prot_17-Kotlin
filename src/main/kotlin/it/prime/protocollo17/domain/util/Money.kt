package it.prime.protocollo17.domain.util

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

// Un solo formattatore di importi per tutta l'app (notifiche, cronologia, journal, esiti): due implementazioni
// diverse finirebbero per mostrare "0.1 €" in un posto e "0,10 €" in un altro per lo stesso pagamento.

/**
 * Simboli italiani fissi: separatore decimale ',' e migliaia '.', impostati a mano su quelli di Locale.ROOT.
 *
 * Prima erano `DecimalFormatSymbols.getInstance(Locale.ITALY)`: con `gradlew run` (JDK completo) davano "0,01 €", ma
 * il runtime jlink dell'MSI non ha `jdk.localedata`, Locale.ITALY ricadeva in silenzio sui simboli di root e l'app
 * installata mostrava (e scriveva nel journal) "0.01 €" e "1,234.56 €". Così non dipendono dai dati di locale del
 * runtime. DecimalFormat clona i simboli, quindi l'istanza condivisa è sicura.
 */
private val ITALIAN_SYMBOLS: DecimalFormatSymbols = DecimalFormatSymbols(Locale.ROOT).apply {
    decimalSeparator = ','
    groupingSeparator = '.'
}

/** L'UNICO formattatore di importi: "0,10 €", "1.234,56 €" (simboli ',' e '.' fissi, sempre 2 decimali). */
fun formatCents(cents: Long): String {
    // DecimalFormat non è thread-safe: un'istanza per chiamata (costo trascurabile rispetto all'uso in UI).
    val format = DecimalFormat("#,##0.00", ITALIAN_SYMBOLS)
    // BigDecimal evita qualsiasi passaggio da Double: 1 centesimo resta esattamente 0,01.
    return format.format(BigDecimal.valueOf(cents, 2)) + " €"
}
