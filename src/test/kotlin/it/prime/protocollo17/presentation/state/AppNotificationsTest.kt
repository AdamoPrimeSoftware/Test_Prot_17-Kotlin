package it.prime.protocollo17.presentation.state

import it.prime.protocollo17.presentation.state.AppNotifications.Kind
import it.prime.protocollo17.presentation.state.AppNotifications.Notice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Testo dello snackbar (Notice.displayText, §6.4): la fonte davanti al messaggio, mai ripetuta. */
class AppNotificationsTest {

    private fun text(source: String, message: String) = Notice(Kind.INFO, source, message).displayText

    /** Prima: "Pagamento: Pagamento 1,00 €: APPROVATO", "Stato terminale: Stato terminale: risposta ricevuta". */
    @Test
    fun `messaggio che comincia con la fonte non la ripete`() {
        assertEquals("Pagamento 1,00 €: APPROVATO", text("Pagamento", "Pagamento 1,00 €: APPROVATO"), "esito con importo")
        assertEquals("Stato terminale: risposta ricevuta", text("Stato terminale", "Stato terminale: risposta ricevuta"), "titolo seguito da ':'")
        assertEquals("Ultimo esito (G): APPROVATO", text("Ultimo esito (G)", "Ultimo esito (G): APPROVATO"), "titolo con parentesi")
        assertEquals(
            "Journal non scritto: pagamenti bloccati. disco pieno",
            text("Journal", "Journal non scritto: pagamenti bloccati. disco pieno"),
            "fonte come prima parola",
        )
        assertEquals("Totali", text("Totali", "Totali"), "messaggio uguale alla fonte")
    }

    @Test
    fun `messaggio che non comincia con la fonte la mantiene davanti`() {
        assertEquals(
            "Simulatore: Porta 15577 già in uso: cambiala nella finestra Simulatore",
            text("Simulatore", "Porta 15577 già in uso: cambiala nella finestra Simulatore"),
            "simulatore",
        )
        assertEquals(
            "Journal: All'ultimo avvio 2 operazioni sono rimaste senza esito: pagamenti bloccati finché non le risolvi",
            text("Journal", "All'ultimo avvio 2 operazioni sono rimaste senza esito: pagamenti bloccati finché non le risolvi"),
            "recupero all'avvio",
        )
    }

    /** La fonte va confrontata come parola intera: "Totali" non è il prefisso di "Totalizzatore …". */
    @Test
    fun `fonte prefisso di una parola piu lunga resta davanti`() {
        assertEquals("Totali: Totalizzatore azzerato", text("Totali", "Totalizzatore azzerato"), "non è la stessa parola")
        assertEquals("Pagamento: pagamento 1,00 €", text("Pagamento", "pagamento 1,00 €"), "maiuscole diverse")
    }

    @Test
    fun `fonte vuota mostra il solo messaggio`() {
        assertEquals("Porta 15577 già in uso", text("", "Porta 15577 già in uso"), "senza ': ' iniziale")
    }
}
