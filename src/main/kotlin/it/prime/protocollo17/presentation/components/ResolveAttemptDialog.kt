package it.prime.protocollo17.presentation.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.domain.model.journal.ResolutionKind
import it.prime.protocollo17.domain.util.GAssessmentResult
import it.prime.protocollo17.domain.util.GVerdict
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.presentation.model.FieldRow
import it.prime.protocollo17.presentation.model.ResultTone
import it.prime.protocollo17.presentation.model.ResultView
import it.prime.protocollo17.presentation.theme.PreviewTheme
import it.prime.protocollo17.presentation.theme.SuccessGreen
import it.prime.protocollo17.presentation.theme.WarningAmber
import it.prime.protocollo17.presentation.util.formatTime

/**
 * Contenuto del dialog di risoluzione di un tentativo (§6.8), costruito dal ViewModel (Console o Journal).
 *
 * [evidence] e [otherUnresolved] sono un'aggiunta alla firma di §6.3 (con default, compatibile): servono al
 * riepilogo richiesto da §6.8 ("con le prove allegate" e la spiegazione che G riporta solo l'ultima transazione).
 */
data class ResolveDialogModel(
    val attemptId: String, val attemptTitle: String, val attemptTime: String, val reason: String,
    /** true: esito ricevuto ma da verificare (possibile doppio addebito, esito prima dell'ACK). */
    val reviewOnly: Boolean,
    val gResult: ResultView?,                  // null = chiusura senza G
    val assessment: GAssessmentResult?,
    /** Payload RECEIVED/EVIDENCE allegati al tentativo (già mascherati), mostrati come prove. */
    val evidence: List<String> = emptyList(),
    /** Altri tentativi non risolti oltre a questo: G riporta solo l'ultima transazione, i più vecchi vanno chiusi a mano. */
    val otherUnresolved: Int = 0,
)

/**
 * Chiusura di un tentativo con esito sconosciuto o da verificare: l'unico modo di sbloccare i pagamenti (U7, M6).
 *
 * La decisione è SEMPRE dell'operatore: il verdetto di GAssessment è solo un suggerimento (il pulsante coerente è
 * pieno, l'altro contornato) e la nota è obbligatoria, perché il journal deve dire chi ha deciso cosa e in base a
 * cosa. Senza G serve anche la spunta "ho verificato", così un clic distratto non chiude un pagamento sconosciuto.
 * Esc e "Lascia aperto" chiudono il dialog senza toccare il journal.
 */
@Composable
fun ResolveAttemptDialog(model: ResolveDialogModel, onResolve: (ResolutionKind, String) -> Unit, onDismiss: () -> Unit) {
    val g = model.gResult
    var note by remember(model.attemptId, g?.finishedAt) {
        mutableStateOf(if (g != null) "Verificato con G alle ${formatTime(g.finishedAt).take(5)}" else "")
    }
    var verified by remember(model.attemptId) { mutableStateOf(false) }
    val noteOk = note.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (model.reviewOnly) "Esito da verificare" else "Esito sconosciuto: chiudi il tentativo") },
        text = {
            ScrollableColumn(
                modifier = Modifier.heightIn(max = 440.dp),
                contentPadding = PaddingValues(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AttemptSummary(model)
                if (g != null) {
                    SectionLabel("Esito di Ultimo esito (G)")
                    CompactResult(g)
                    model.assessment?.let { AssessmentBox(it) }
                } else {
                    Text(
                        "Chiusura senza G: verifica l'esito sullo scontrino o sul terminale prima di chiudere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Nota (obbligatoria)") },
                    isError = !noteOk,
                    supportingText = if (!noteOk) ({ Text("Scrivi come hai verificato l'esito") }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (g == null) {
                    Row(
                        modifier = Modifier.toggleable(value = verified, role = Role.Checkbox, onValueChange = { verified = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = verified, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Ho verificato l'esito sul terminale o sullo scontrino", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val verdict = model.assessment?.verdict
                when {
                    model.reviewOnly -> Button(
                        enabled = noteOk && (g != null || verified),
                        onClick = { onResolve(ResolutionKind.REVIEWED, note.trim()) },
                    ) { Text("Verificato: nessun doppio addebito (o già stornato)") }
                    g != null -> {
                        DecisionButton(
                            text = "Il tentativo non è stato eseguito",
                            suggested = verdict == GVerdict.LIKELY_PREVIOUS_RESULT,
                            enabled = noteOk,
                            onClick = { onResolve(ResolutionKind.NOT_EXECUTED, note.trim()) },
                        )
                        DecisionButton(
                            text = "L'esito G appartiene al tentativo",
                            suggested = verdict == GVerdict.LIKELY_THIS_ATTEMPT,
                            enabled = noteOk,
                            onClick = { onResolve(ResolutionKind.CONFIRMED_BY_G, note.trim()) },
                        )
                    }
                    else -> Button(
                        enabled = noteOk && verified,
                        onClick = { onResolve(ResolutionKind.DISMISSED, note.trim()) },
                    ) { Text("Chiudi tentativo") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Lascia aperto") } },
    )
}

@Composable
private fun DecisionButton(text: String, suggested: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (suggested) {
        Button(onClick = onClick, enabled = enabled) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(text) }
    }
}

@Composable
private fun AttemptSummary(model: ResolveDialogModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${model.attemptTitle} delle ${model.attemptTime}", style = MaterialTheme.typography.titleSmall)
        Text(model.reason, style = MaterialTheme.typography.bodyMedium, color = WarningAmber)
        if (model.otherUnresolved > 0) {
            Text(
                "Ci sono altri ${model.otherUnresolved} tentativi da risolvere: G riporta SOLO l'ultima transazione del " +
                    "terminale, i tentativi più vecchi vanno chiusi a mano dopo averli verificati.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (model.evidence.isNotEmpty()) {
            SectionLabel("Prove allegate al tentativo")
            SelectionContainer {
                Column {
                    model.evidence.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/** Esito di G in forma compatta: titolo colorato e i campi principali (la scheda completa è nella Console). */
@Composable
private fun CompactResult(result: ResultView) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(toneColor(result.tone))
            Spacer(Modifier.width(8.dp))
            Text(result.headline, color = toneColor(result.tone), fontWeight = FontWeight.SemiBold)
        }
        result.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        KeyValueTable(rows = result.fields.map { KeyValueRow(label = it.label, value = it.value, trailing = it.position) })
    }
}

@Composable
private fun AssessmentBox(assessment: GAssessmentResult) {
    val color: Color = when (assessment.verdict) {
        GVerdict.LIKELY_THIS_ATTEMPT -> SuccessGreen
        GVerdict.LIKELY_PREVIOUS_RESULT -> MaterialTheme.colorScheme.primary
        GVerdict.INCONCLUSIVE -> WarningAmber
    }
    val title = when (assessment.verdict) {
        GVerdict.LIKELY_THIS_ATTEMPT -> "Probabilmente è l'esito di questo tentativo"
        GVerdict.LIKELY_PREVIOUS_RESULT -> "Probabilmente è l'esito di una transazione precedente"
        GVerdict.INCONCLUSIVE -> "Non si può stabilire a quale tentativo appartenga"
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(assessment.explanation, style = MaterialTheme.typography.bodySmall)
            Text(
                "Suggerimento: la decisione resta tua.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Preview ──────────────────────────────────────────────────────────────────────

private fun previewG() = ResultView(
    command = CommandCode.LAST_RESULT, tone = ResultTone.SUCCESS, headline = "APPROVATO", detail = null,
    fields = listOf(FieldRow("PAN", "***************4768", "13/19"), FieldRow("STAN", "000004", "60/6")),
    exchange = emptyList(), timeline = emptyList(), rawPayload = null, receipt = emptyList(), warnings = emptyList(),
    reviewNotice = null, quickActions = emptyList(), journalError = null, finishedAt = 1_760_000_000_000,
)

@Suppress("unused")
@Preview
@Composable
private fun ResolveAttemptDialogWithGPreview() {
    PreviewTheme {
        ResolveAttemptDialog(
            model = ResolveDialogModel(
                attemptId = "a1", attemptTitle = "Pagamento 0,10 €", attemptTime = "14/10 09:35:05",
                reason = "nessun esito entro 120 s", reviewOnly = false, gResult = previewG(),
                assessment = GAssessmentResult(GVerdict.LIKELY_THIS_ATTEMPT, "L'esito di G ('E') è del tipo atteso per Pagamento."),
                otherUnresolved = 1,
            ),
            onResolve = { _, _ -> },
            onDismiss = {},
        )
    }
}

@Suppress("unused")
@Preview
@Composable
private fun ResolveAttemptDialogWithoutGPreview() {
    PreviewTheme {
        ResolveAttemptDialog(
            model = ResolveDialogModel(
                attemptId = "a2", attemptTitle = "Verifica carta", attemptTime = "14/10 10:01:00",
                reason = "possibile doppio addebito", reviewOnly = true, gResult = null, assessment = null,
                evidence = listOf("025757330E00***************4768CLIR48432…"),
            ),
            onResolve = { _, _ -> },
            onDismiss = {},
        )
    }
}
