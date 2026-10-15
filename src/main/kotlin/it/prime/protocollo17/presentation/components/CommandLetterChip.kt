package it.prime.protocollo17.presentation.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.theme.PreviewTheme

/**
 * La lettera del comando ECR17 in un quadrato arrotondato (sidebar, cronologia, journal, log).
 *
 * La lettera è il nome con cui chi conosce il protocollo pensa ai comandi ('P', 'G', 's'): metterla ovunque in
 * evidenza collega a colpo d'occhio la UI al log traffico. [danger] (comandi che muovono denaro e chiusura
 * contabile, CommandTitles.isDanger) usa il contenitore d'errore: il colore avvisa prima del clic.
 */
@Composable
fun CommandLetterChip(letter: Char, danger: Boolean, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    val container = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val content = if (danger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier = modifier.size(size).background(container, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            color = content,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Suppress("unused")
@Preview
@Composable
private fun CommandLetterChipPreview() {
    PreviewTheme {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CommandLetterChip('P', danger = true)
            CommandLetterChip('s', danger = false)
            CommandLetterChip('G', danger = false, size = 20.dp)
        }
    }
}
