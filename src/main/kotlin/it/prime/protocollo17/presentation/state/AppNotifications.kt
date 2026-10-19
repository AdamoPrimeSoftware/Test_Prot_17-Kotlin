package it.prime.protocollo17.presentation.state

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Avvisi globali (snackbar della finestra principale).
 *
 * L'esito di un comando deve arrivare all'operatore anche se l'ha lanciato dalla finestra Journal o se nel
 * frattempo ha spostato il focus altrove: un evento nel Channel del singolo ViewModel lo vedrebbe solo quella
 * schermata. Qui l'avviso è pubblicato una volta e mostrato dallo snackbar della finestra principale, con
 * un'azione "Apri" verso la finestra indicata in [Notice.openWindow] e il testo di [Notice.displayText].
 */
class AppNotifications {

    /** Tono dell'avviso: cambia colore e icona dello snackbar. */
    enum class Kind { SUCCESS, WARNING, ERROR, INFO }

    /** source = chi ha prodotto l'avviso ("Pagamento", "Journal", "Simulatore"); openWindow = finestra da aprire con "Apri". */
    data class Notice(val kind: Kind, val source: String, val message: String, val openWindow: AppWindowId? = null) {
        /**
         * Testo mostrato dallo snackbar: "source: message", senza ripetere la fonte quando il messaggio comincia già
         * con essa.
         *
         * Prima la finestra principale anteponeva sempre la fonte, e gli esiti dei comandi, il cui messaggio comincia
         * con il titolo del comando, si leggevano "Pagamento: Pagamento 1,00 €: APPROVATO". Ora la fonte si omette
         * quando il messaggio comincia con la stessa fonte come parola intera ("Pagamento 1,00 €: APPROVATO",
         * "Journal non scritto: …") e resta negli altri casi ("Simulatore: Porta 15577 già in uso: …").
         */
        val displayText: String
            get() = if (source.isBlank() || message.startsWithWord(source)) message else "$source: $message"

        private fun String.startsWithWord(prefix: String): Boolean =
            startsWith(prefix) && (length == prefix.length || !this[prefix.length].isLetterOrDigit())
    }

    // DROP_OLDEST con buffer: notify non sospende mai (si chiama anche da coroutine di servizio) e un avviso
    // vecchio non ancora mostrato vale meno di quello appena arrivato.
    private val _events = MutableSharedFlow<Notice>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Notice> = _events.asSharedFlow()

    fun notify(notice: Notice) {
        _events.tryEmit(notice)
    }

    fun success(source: String, message: String, openWindow: AppWindowId? = null) =
        notify(Notice(Kind.SUCCESS, source, message, openWindow))

    fun warning(source: String, message: String, openWindow: AppWindowId? = null) =
        notify(Notice(Kind.WARNING, source, message, openWindow))

    fun error(source: String, message: String, openWindow: AppWindowId? = null) =
        notify(Notice(Kind.ERROR, source, message, openWindow))

    fun info(source: String, message: String, openWindow: AppWindowId? = null) =
        notify(Notice(Kind.INFO, source, message, openWindow))
}
