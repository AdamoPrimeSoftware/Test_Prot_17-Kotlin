package it.prime.protocollo17.domain.util

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

// Un solo formattatore di importi per tutta l'app (notifiche, cronologia, journal, esiti): due implementazioni
// diverse finirebbero per mostrare "0.1 €" in un posto e "0,10 €" in un altro per lo stesso pagamento.

/** Simboli italiani fissi: separatore decimale ',' e migliaia '.', indipendenti dal Locale del sistema. */
private val ITALIAN_SYMBOLS: DecimalFormatSymbols = DecimalFormatSymbols.getInstance(Locale.ITALY)

/** L'UNICO formattatore di importi: "0,10 €", "1.234,56 €" (Locale.ITALY, sempre 2 decimali). */
fun formatCents(cents: Long): String {
    // DecimalFormat non è thread-safe: un'istanza per chiamata (costo trascurabile rispetto all'uso in UI).
    val format = DecimalFormat("#,##0.00", ITALIAN_SYMBOLS)
    // BigDecimal evita qualsiasi passaggio da Double: 1 centesimo resta esattamente 0,01.
    return format.format(BigDecimal.valueOf(cents, 2)) + " €"
}
