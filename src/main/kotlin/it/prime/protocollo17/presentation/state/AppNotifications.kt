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
 * un'azione "Apri" verso la finestra indicata in [Notice.openWindow].
 */
class AppNotifications {

    /** Tono dell'avviso: cambia colore e icona dello snackbar. */
    enum class Kind { SUCCESS, WARNING, ERROR, INFO }

    /** source = chi ha prodotto l'avviso ("Pagamento", "Journal", "Simulatore"); openWindow = finestra da aprire con "Apri". */
    data class Notice(val kind: Kind, val source: String, val message: String, val openWindow: AppWindowId? = null)

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
