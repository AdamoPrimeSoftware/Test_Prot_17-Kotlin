package it.prime.protocollo17.presentation.screen.console.view.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.presentation.components.CopyIconButton
import it.prime.protocollo17.presentation.components.KeyValueRow
import it.prime.protocollo17.presentation.components.KeyValueTable
import it.prime.protocollo17.presentation.components.ScrollableColumn
import it.prime.protocollo17.presentation.components.StatusDot
import it.prime.protocollo17.presentation.components.toneColor
import it.prime.protocollo17.presentation.model.FieldRow
import it.prime.protocollo17.presentation.model.QuickAction
import it.prime.protocollo17.presentation.model.ResultTone
import it.prime.protocollo17.presentation.model.ResultView
import it.prime.protocollo17.presentation.screen.console.viewModel.ConsoleAction
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.theme.WarningAmber
import it.prime.protocollo17.domain.model.command.FieldKey
import it.prime.protocollo17.presentation.util.formatTime

/**
 * Scheda Esito (§6.7): titolo colorato, avviso di revisione, campi con posizione, azioni rapide e sezioni
 * richiudibili (scambio, fasi, payload grezzo, scontrino, avvisi). Testi e valori sono selezionabili; i pulsanti
 * stanno fuori dalla selezione (il gesto di selezione mangerebbe il clic). Le sezioni si aprono dall'intestazione.
 * [scrollable] = false quando il pannello sta in una colonna già scrollabile (layout impilato).
 */
@Composable
fun ResultPanel(
    result: ResultView?,
    viewingHistory: Boolean,
    onAction: (ConsoleAction) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    if (result == null) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Nessun esito per questo comando. Premi Esegui (Ctrl+Invio).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val body: @Composable ColumnScope.() -> Unit = { ResultBody(result, viewingHistory, onAction) }
    if (scrollable) {
        ScrollableColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = body,
        )
    } else {
        Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = body)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ResultBody(result: ResultView, viewingHistory: Boolean, onAction: (ConsoleAction) -> Unit) {
    if (viewingHistory) {
        TextButton(onClick = { onAction(ConsoleAction.ShowHistoryItem(null)) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Torna all'ultimo esito")
        }
    }
    result.journalError?.let {
        Text("Journal non scritto: $it", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(toneColor(result.tone))
        Spacer(Modifier.width(10.dp))
        SelectionContainer(Modifier.weight(1f)) {
            Text(result.headline, style = MaterialTheme.typography.titleMedium, color = toneColor(result.tone), fontWeight = FontWeight.Bold)
        }
        Text(formatTime(result.finishedAt), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        CopyIconButton(text = result.toClipboardText(), contentDescription = "Copia l'esito completo")
    }
    result.detail?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) } }
    result.reviewNotice?.let { notice ->
        Surface(color = WarningAmber.copy(alpha = 0.15f), border = BorderStroke(1.dp, WarningAmber), shape = RoundedCornerShape(8.dp)) {
            SelectionContainer { Text(notice, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium) }
        }
    }
    if (result.fields.isNotEmpty()) KeyValueTable(rows = result.fields.toRows(emphasizeFirst = true))
    if (result.quickActions.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            result.quickActions.forEach { action ->
                OutlinedButton(onClick = { onAction(ConsoleAction.CopyToForm(action.target, action.key, action.value)) }) {
                    Text(action.label)
                }
            }
        }
    }
    if (result.exchange.isNotEmpty()) {
        val sends = result.exchange.firstOrNull { it.label.startsWith("Invii") }?.value
        ExpandableSection(title = "Dettagli scambio" + (sends?.let { " (invii $it)" }.orEmpty())) {
            KeyValueTable(rows = result.exchange.toRows())
        }
    }
    if (result.timeline.isNotEmpty()) {
        ExpandableSection(title = "Fasi (${result.timeline.size})") { KeyValueTable(rows = result.timeline.toRows()) }
    }
    result.rawPayload?.let { raw ->
        ExpandableSection(title = "Payload grezzo (${raw.length} caratteri)") {
            Row(verticalAlignment = Alignment.Top) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(raw, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                CopyIconButton(text = raw, contentDescription = "Copia il payload")
            }
        }
    }
    ExpandableSection(title = "Righe scontrino (${result.receipt.size})") {
        if (result.receipt.isEmpty()) {
            Text("Nessuna riga ricevuta.", style = MaterialTheme.typography.bodySmall)
        } else {
            SelectionContainer { Text(result.receipt.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (result.warnings.isNotEmpty()) {
        ExpandableSection(title = "Avvisi (${result.warnings.size})", titleColor = WarningAmber, initiallyExpanded = true) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    result.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    titleColor: androidx.compose.ui.graphics.Color? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        // Si apre e chiude dall'intestazione, non da tutta la sezione: il clic non deve interferire con la selezione.
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, color = titleColor ?: MaterialTheme.colorScheme.onSurface)
        }
        if (expanded) {
            Column(Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp), content = content)
        }
    }
}

private fun List<FieldRow>.toRows(emphasizeFirst: Boolean = false): List<KeyValueRow> =
    map { row -> KeyValueRow(label = row.label, value = row.value, trailing = row.position, emphasis = emphasizeFirst && row.label == "PAN") }

/** Testo completo per "Copia": quello che si incolla in una segnalazione o in un confronto con il log. */
private fun ResultView.toClipboardText(): String = buildString {
    appendLine("${command.wire} ${formatTime(finishedAt)} $headline")
    detail?.let { appendLine(it) }
    reviewNotice?.let { appendLine(it) }
    journalError?.let { appendLine("Journal: $it") }
    if (fields.isNotEmpty()) appendLine().append(fields.joinToString("\n") { "${it.label}: ${it.value}${it.position?.let { p -> " [$p]" }.orEmpty()}" }).appendLine()
    if (exchange.isNotEmpty()) appendLine().append(exchange.joinToString("\n") { "${it.label}: ${it.value}" }).appendLine()
    if (timeline.isNotEmpty()) appendLine().append(timeline.joinToString("\n") { "${it.value} ${it.label} ${it.position.orEmpty()}" }).appendLine()
    rawPayload?.let { appendLine().appendLine("Payload: $it") }
    if (receipt.isNotEmpty()) appendLine().appendLine("Scontrino:").appendLine(receipt.joinToString("\n"))
    if (warnings.isNotEmpty()) appendLine().appendLine("Avvisi:").appendLine(warnings.joinToString("\n"))
}.trimEnd()

// ─── Preview ──────────────────────────────────────────────────────────────────────

private fun previewResult(tone: ResultTone, headline: String, review: String? = null) = ResultView(
    command = CommandCode.PAYMENT, tone = tone, headline = headline,
    detail = if (tone == ResultTone.WARNING) "Nessun esito entro 120 s. ESITO SCONOSCIUTO: esegui Ultimo esito (G) prima di un nuovo pagamento." else null,
    fields = listOf(
        FieldRow("PAN", "***************4768", "13/19"), FieldRow("Modalità lettura", "Contactless chip (CLI)", "32/3"),
        FieldRow("Codice autorizzazione", "R48432", "35/6"), FieldRow("STAN", "000004", "60/6"),
    ),
    exchange = listOf(FieldRow("Invii della richiesta", "1"), FieldRow("Modalità ACK", "ACK esplicito")),
    timeline = listOf(FieldRow("Invio richiesta", "09:35:05.453", "+54 ms")),
    rawPayload = "025757330E00***************4768CLIR484320770947200000000023000004000055",
    receipt = emptyList(), warnings = listOfNotNull(review?.let { "Possibile doppio addebito: esito dopo un reinvio" }),
    reviewNotice = review,
    quickActions = listOf(QuickAction("Usa STAN in Storno (S)", CommandCode.REVERSAL, FieldKey.STAN, "000004")),
    journalError = null, finishedAt = 1_760_000_000_000,
)

@Suppress("unused")
@Preview
@Composable
private fun ResultPanelPreview() {
    PreviewTheme {
        Row(Modifier.height(560.dp)) {
            ResultPanel(previewResult(ResultTone.SUCCESS, "APPROVATO"), viewingHistory = false, onAction = {}, modifier = Modifier.width(420.dp))
            ResultPanel(
                previewResult(ResultTone.WARNING, "APPROVATO — DA VERIFICARE", review = "Esito arrivato dopo 1 reinvii per ACK mancante: possibile doppio addebito."),
                viewingHistory = true, onAction = {}, modifier = Modifier.width(420.dp),
            )
            ResultPanel(null, viewingHistory = false, onAction = {}, modifier = Modifier.width(300.dp))
        }
    }
}
