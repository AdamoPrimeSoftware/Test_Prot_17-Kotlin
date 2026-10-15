package it.prime.protocollo17.presentation.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.prime.protocollo17.presentation.theme.PreviewTheme

/** Larghezza della scrollbar: sta nel padding orizzontale dei pannelli, si sovrappone al margine e non al testo. */
private val ScrollbarWidth = 8.dp

/**
 * Scrollbar verticale ancorata al bordo destro del `Box` che la contiene.
 *
 * Su desktop un pannello scrollabile senza scrollbar tronca il contenuto in silenzio: chi non prova la rotella non
 * sa che sotto c'è altro (un campo dell'esito, l'ultima riga del log). Sempre visibile: quando tutto entra, il
 * pollice a tutta altezza dice "non c'è altro".
 */
@Composable
fun BoxScope.AppVerticalScrollbar(scrollState: ScrollState) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(ScrollbarWidth),
    )
}

/** Variante per le `LazyColumn`; [reverseLayout] uguale a quello della lista, o il pollice va al contrario. */
@Composable
fun BoxScope.AppVerticalScrollbar(listState: LazyListState, reverseLayout: Boolean = false) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(listState),
        reverseLayout = reverseLayout,
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(ScrollbarWidth),
    )
}

/**
 * `Column` scrollabile con [AppVerticalScrollbar] già agganciata: sostituisce `Column(Modifier.verticalScroll(…))`,
 * che scrolla ma non lo dice a nessuno. [contentPadding] è interno al viewport, così la scrollbar resta sul bordo.
 */
@Composable
fun ScrollableColumn(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
        AppVerticalScrollbar(scrollState)
    }
}

@Suppress("unused")
@Preview
@Composable
private fun ScrollableColumnPreview() {
    PreviewTheme {
        ScrollableColumn(
            modifier = Modifier.height(160.dp).width(260.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(20) { Text("Riga di prova numero ${it + 1}") }
        }
    }
}
