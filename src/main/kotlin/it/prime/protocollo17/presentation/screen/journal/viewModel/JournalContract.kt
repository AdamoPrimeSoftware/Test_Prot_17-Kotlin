package it.prime.protocollo17.presentation.screen.journal.viewModel

import it.prime.protocollo17.domain.model.journal.ResolutionKind
import it.prime.protocollo17.presentation.components.ResolveDialogModel
import it.prime.protocollo17.presentation.model.FieldRow
import it.prime.protocollo17.presentation.model.ResultTone

// Contratto MVI della finestra Journal (§6.13).

enum class JournalFilter { ALL, UNRESOLVED, TODAY }

/**
 * Riga della lista. [resolved] e [persisted] sono un'aggiunta alla firma di §6.13 (con default, compatibile):
 * servono alle icone della lista richieste dallo stesso paragrafo (spunta per RESOLVED, disco barrato per un
 * tentativo non scritto su disco), che le altre proprietà non permettono di ricavare.
 */
data class JournalRowView(
    val attemptId: String, val time: String, val letter: Char?, val title: String,
    val phaseLabel: String, val tone: ResultTone, val sends: Int,
    val resolved: Boolean = false,
    val persisted: Boolean = true,
)

data class JournalDetailView(
    val attemptId: String,
    val title: String,                    // "Tentativo con dati incompleti" se command == null
    val summary: List<FieldRow>,          // comando, importo, endpoint, profilo, opzioni, invii, NAK, reinvii per timeout, esito, STAN…
    val timeline: List<FieldRow>,         // un record per riga: fase, ora, dettagli
    val rawResult: String?,
    val evidence: List<String>,           // payload RECEIVED/EVIDENCE allegati
    val isUnresolved: Boolean,
    val needsReview: Boolean,
    val blocksPayments: Boolean,
    val persisted: Boolean,               // false: "non scritto su disco"
    val json: String,                     // record JSONL del tentativo (per Copia)
)

sealed interface JournalDialog {
    data class Resolve(val model: ResolveDialogModel) : JournalDialog
}

data class JournalUiState(
    val rows: List<JournalRowView> = emptyList(),
    val filter: JournalFilter = JournalFilter.ALL,
    val selectedId: String? = null,
    val detail: JournalDetailView? = null,
    val unresolvedCount: Int = 0,
    val runnerBusy: Boolean = false,
    val dialog: JournalDialog? = null,
    val filePath: String = "",            // la cartella si apre dalla view con openInExplorer
) {
    val canRunG: Boolean get() = !runnerBusy
}

sealed interface JournalAction {
    data class Select(val attemptId: String) : JournalAction
    data class SetFilter(val filter: JournalFilter) : JournalAction
    data object RunLastResult : JournalAction
    data class OpenResolve(val attemptId: String) : JournalAction
    data class Resolve(val attemptId: String, val kind: ResolutionKind, val note: String) : JournalAction
    data object DismissDialog : JournalAction
}

sealed interface JournalEvent {
    data class ShowError(val message: String) : JournalEvent
    data class ShowInfo(val message: String) : JournalEvent
}
