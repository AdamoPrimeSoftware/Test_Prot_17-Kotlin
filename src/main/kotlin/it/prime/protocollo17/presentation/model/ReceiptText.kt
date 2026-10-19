package it.prime.protocollo17.presentation.model

import it.prime.protocollo17.ecr17.model.ReceiptLine

/**
 * Scontrino da mostrare, ricomposto dalle righe S: una riga S è un frame, non una riga di stampa.
 *
 * Prima la scheda Esito e l'overlay di attesa univano i testi delle righe S con '\n'. Sulle catture reali Ingenico
 * (A§9.11) non funziona in nessuno dei due formati: nel formato a righe da 24 colonne ogni frame inizia con 0x7D, e il
 * '\n' in più lasciava una riga vuota a ogni confine; nel formato a colonne senza separatori i frame sono da 200
 * caratteri tagliati anche a metà campo, e l'operatore vedeva righe da ~200 caratteri spezzate a caso.
 *
 * Ora una riga S che continua nella successiva ([ReceiptLine.continuesInNext], testo pieno) si concatena alla
 * successiva senza andare a capo; il pezzo così ottenuto, se non ha separatori (0x7D/0x7E, cioè '\n' nel testo), va a
 * capo ogni [COLUMNS] colonne (lo 0x7F, già tolto dal testo, non occupa colonne). Le righe più corte del massimo
 * (simulatore, spec Nexi: una riga di stampa per frame) restano righe a sé, anche se più larghe di 24 colonne, come
 * prima. Il log traffico continua a mostrare una "riga S" per frame.
 */
object ReceiptText {

    /** Colonne della carta dei terminali Ingenico (righe da 24 nei due formati, A§9.11). */
    const val COLUMNS = 24

    /** Righe di stampa nell'ordine di arrivo; lista vuota se non c'è testo. */
    fun lines(receipt: List<ReceiptLine>): List<String> {
        val out = mutableListOf<String>()
        val piece = StringBuilder()
        var cut = false
        receipt.forEachIndexed { index, line ->
            if (index > 0 && !receipt[index - 1].continuesInNext) {
                out += layout(piece.toString(), wrap = cut)
                piece.clear()
                cut = false
            }
            piece.append(line.text)
            if (line.continuesInNext) cut = true
        }
        out += layout(piece.toString(), wrap = cut)
        return out
    }

    /**
     * Un pezzo (righe S unite) in righe di stampa: tante righe quanti i separatori. Nel formato a righe lo 0x7D precede
     * ogni riga (si toglie il '\n' in testa), nella spec Nexi la chiude (si toglie quello in coda): mai tutti e due,
     * altrimenti un pezzo di soli `}}` (due righe vuote) ne darebbe una.
     */
    private fun layout(piece: String, wrap: Boolean): List<String> = when {
        piece.isEmpty() -> emptyList()
        wrap && '\n' !in piece -> piece.chunked(COLUMNS)
        piece.startsWith('\n') -> piece.substring(1).split('\n')
        piece.endsWith('\n') -> piece.dropLast(1).split('\n')
        else -> piece.split('\n')
    }
}
