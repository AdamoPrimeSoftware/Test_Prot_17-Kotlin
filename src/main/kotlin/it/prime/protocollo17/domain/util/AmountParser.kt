package it.prime.protocollo17.domain.util

import java.math.BigDecimal

/** Esito della lettura di un importo digitato nel form: centesimi oppure il motivo (italiano) del rifiuto. */
sealed interface AmountParse {
    data class Ok(val cents: Long) : AmountParse
    data class Invalid(val message: String) : AmountParse
}

/**
 * Importo in euro → centesimi (BigDecimal): cifre, un separatore decimale ',' o '.', max 2 decimali, > 0,
 * ≤ 999 999,99. Per la resa usa formatCents (Money.kt).
 *
 * Perché un parser proprio e non `toDouble()`: "6.50" in Double è 6.4999…, e un arrotondamento sbagliato su un
 * importo inviato al terminale è un addebito sbagliato. Perché rifiutare "1.234,56": il punto sarebbe ambiguo
 * (migliaia o decimali?) e su un pagamento si preferisce chiedere di riscrivere piuttosto che indovinare.
 * Lo zero è ammesso dal protocollo (RequestEncoder) ma non dal form: un pagamento da 0 € non ha senso in un test.
 */
object AmountParser {

    /** 999 999,99 €: il campo importo della richiesta è di 8 cifre in centesimi (A§4.3.1). */
    private const val MAX_CENTS = 99_999_999L

    private val pattern = Regex("""^(\d+)(?:[.,](\d{1,2}))?$""")
    private val tooManyDecimals = Regex("""^\d+[.,]\d{3,}$""")

    fun parseEuroToCents(text: String): AmountParse {
        val value = text.trim()
        if (value.isEmpty()) return AmountParse.Invalid("Inserisci un importo (es. 6,50)")
        if (value.startsWith("-")) return AmountParse.Invalid("L'importo deve essere maggiore di zero")
        val match = pattern.matchEntire(value) ?: return AmountParse.Invalid(
            when {
                tooManyDecimals.matches(value) -> "Al massimo 2 decimali (es. 6,50)"
                value.count { it == '.' || it == ',' } > 1 ->
                    "Usa un solo separatore decimale, senza punti delle migliaia (es. 1234,56)"
                else -> "Importo non valido: solo cifre e una virgola (es. 6,50)"
            },
        )
        val integer = match.groupValues[1]
        val decimals = match.groupValues[2]
        // BigDecimal: nessun passaggio da Double, "0,1" resta esattamente 10 centesimi.
        val cents = BigDecimal(if (decimals.isEmpty()) integer else "$integer.$decimals").movePointRight(2)
        return when {
            cents.signum() <= 0 -> AmountParse.Invalid("L'importo deve essere maggiore di zero")
            cents > BigDecimal.valueOf(MAX_CENTS) -> AmountParse.Invalid("L'importo massimo è ${formatCents(MAX_CENTS)}")
            else -> AmountParse.Ok(cents.longValueExact())
        }
    }
}
