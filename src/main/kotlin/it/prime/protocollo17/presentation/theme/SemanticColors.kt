package it.prime.protocollo17.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Colori semantici di stato (approvato, da verificare). Material 3 non ha slot success/warning: un solo posto
 * evita che lo stesso esadecimale si moltiplichi tra esiti, banner, journal e log. Il rifiuto usa
 * `colorScheme.error`. Entrambi hanno contrasto sufficiente sul tema chiaro e su quello scuro.
 */
val SuccessGreen = Color(0xFF22C55E)
val WarningAmber = Color(0xFFEAB308)
