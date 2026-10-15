package it.prime.protocollo17.presentation.screen.console.model

import it.prime.protocollo17.domain.model.command.CommandTitles
import it.prime.protocollo17.domain.model.command.FieldKey
import it.prime.protocollo17.ecr17.model.CommandCode
import it.prime.protocollo17.ecr17.model.PaymentType
import it.prime.protocollo17.ecr17.model.ReprintTarget
import it.prime.protocollo17.ecr17.model.TicketType

/** Gruppi della barra laterale (§6.2), gli stessi dell'app WPF: Pagamenti, Preautorizzazioni, Terminale. */
enum class CommandGroup(val title: String) { PAYMENTS("Pagamenti"), PRE_AUTH("Preautorizzazioni"), TERMINAL("Terminale") }

/** Tipo di un campo del form: decide il controllo (testo, menu, interruttore) e il contatore. */
sealed interface FieldType {
    /** Euro, virgola o punto (AmountParser). */
    data object Amount : FieldType

    data class Digits(val maxLength: Int, val required: Boolean) : FieldType

    /** ASCII stampabile, contatore n/maxLength. */
    data class Text(val maxLength: Int) : FieldType

    /** (Enum.name, etichetta). */
    data class Choice(val options: List<Pair<String, String>>) : FieldType

    data object Toggle : FieldType
}

data class FieldDef(val key: FieldKey, val label: String, val type: FieldType, val hint: String? = null)

/** Un comando della sidebar con il suo form. [title] e [danger] vengono da CommandTitles: una sola fonte. */
data class CommandDef(
    val code: CommandCode,
    val title: String,                        // = CommandTitles.title(code)
    val description: String,
    val group: CommandGroup,
    val fields: List<FieldDef>,
    val danger: Boolean,                      // = CommandTitles.isDanger(code): pulsante rosso "Esegui (finanziario)", chip rosso
    val confirmText: String? = null,
)

/**
 * Catalogo dei 13 comandi v1 (tabella §6.6): ordine della sidebar, campi dei form, conferme.
 *
 * Solo la parte di presentazione: titoli e "danger" da CommandTitles (dominio), default e validazione da
 * RequestFactory (che delega le regole a RequestEncoder). Così la sidebar, le notifiche e la cronologia dicono la
 * stessa cosa, e il form non può accettare un valore che il protocollo rifiuterebbe.
 */
object CommandCatalog {

    // ─── Campi ricorrenti ─────────────────────────────────────────────────────────

    private val paymentTypes = FieldType.Choice(
        listOf(
            PaymentType.AUTO.name to "Automatico",
            PaymentType.DEBIT.name to "Debito",
            PaymentType.CREDIT.name to "Credito",
            PaymentType.OTHER.name to "Altro",
        ),
    )

    private val sharedAmount = FieldDef(
        FieldKey.AMOUNT, "Importo (€)", FieldType.Amount,
        hint = "Condiviso con Pagamento, Pagamento esteso e Preautorizzazione",
    )
    private val ownAmount = FieldDef(FieldKey.AMOUNT, "Importo (€)", FieldType.Amount)
    private val paymentType = FieldDef(FieldKey.PAYMENT_TYPE, "Tipo carta", paymentTypes)
    private val cardPresent = FieldDef(FieldKey.CARD_PRESENT, "Carta già inserita", FieldType.Toggle)
    private val receiptText = FieldDef(
        FieldKey.RECEIPT_TEXT, "Testo scontrino (allineato a destra)", FieldType.Text(128),
        hint = "Solo caratteri ASCII: le lettere accentate sposterebbero i campi del messaggio",
    )
    private val cashRegister = FieldDef(FieldKey.CASH_REGISTER_OVERRIDE, "ECR id (vuoto = Opzioni)", FieldType.Digits(8, required = false))
    private val preAuthCode = FieldDef(FieldKey.PRE_AUTH_CODE, "Codice preautorizzazione", FieldType.Digits(9, required = true))

    private val paymentFields = listOf(sharedAmount, paymentType, cardPresent, receiptText, cashRegister)
    private val preAuthFollowUpFields = listOf(ownAmount, preAuthCode, receiptText, cashRegister)

    private fun def(
        code: CommandCode,
        group: CommandGroup,
        description: String,
        fields: List<FieldDef>,
        confirmText: String? = null,
    ) = CommandDef(
        code = code,
        title = CommandTitles.title(code),
        description = description,
        group = group,
        fields = fields,
        danger = CommandTitles.isDanger(code),
        confirmText = confirmText,
    )

    /** Ordine della sidebar (tabella §6.6). */
    val all: List<CommandDef> = listOf(
        def(CommandCode.PAYMENT, CommandGroup.PAYMENTS, "Pagamento con carta dell'importo indicato. Risposta 'E' (o 'V' con DCC).", paymentFields),
        def(
            CommandCode.PAYMENT_EXTENDED, CommandGroup.PAYMENTS,
            "Pagamento con esito esteso: la risposta 'E' da 92 byte porta anche action code e importo dell'host.", paymentFields,
        ),
        def(
            CommandCode.REVERSAL, CommandGroup.PAYMENTS, "Storno di una transazione. STAN vuoto = ultima transazione.",
            listOf(FieldDef(FieldKey.STAN, "STAN (vuoto = ultima transazione)", FieldType.Digits(6, required = false)), cashRegister),
        ),
        def(
            CommandCode.CARD_VERIFICATION, CommandGroup.PAYMENTS,
            "Verifica della carta senza addebito. Un esito sconosciuto blocca Pagamento, Pagamento esteso, Storno e Verifica carta.",
            listOf(paymentType, cashRegister),
        ),
        def(
            CommandCode.PRE_AUTH, CommandGroup.PRE_AUTH,
            "Preautorizzazione dell'importo: la risposta 'e' porta il codice da usare in Integrazione e Chiusura.", paymentFields,
        ),
        def(CommandCode.INCREMENTAL_AUTH, CommandGroup.PRE_AUTH, "Aumenta l'importo di una preautorizzazione esistente.", preAuthFollowUpFields),
        def(CommandCode.PRE_AUTH_CLOSURE, CommandGroup.PRE_AUTH, "Chiude una preautorizzazione incassando l'importo indicato.", preAuthFollowUpFields),
        def(
            CommandCode.STATUS, CommandGroup.TERMINAL,
            "Stato del terminale: verifica collegamento, LRC e parametri ECR. Non muove denaro (F5).", emptyList(),
        ),
        def(CommandCode.TOTALS, CommandGroup.TERMINAL, "Totali del terminale, in sola lettura: non chiude la sessione contabile.", listOf(cashRegister)),
        def(
            CommandCode.CLOSE_SESSION, CommandGroup.TERMINAL, "Chiusura contabile: azzera i totali del terminale. Non è ripetibile.",
            listOf(cashRegister),
            confirmText = "Eseguire la chiusura contabile sul terminale? L'operazione non è ripetibile.",
        ),
        def(
            CommandCode.LAST_RESULT, CommandGroup.TERMINAL,
            "Chiede al terminale l'esito dell'ultima transazione: serve a risolvere un esito sconosciuto. Mai automatico.",
            listOf(cashRegister),
        ),
        def(
            CommandCode.ECR_PRINTING, CommandGroup.TERMINAL, "Abilita o disabilita la stampa dello scontrino sull'ECR (solo ACK).",
            listOf(
                FieldDef(
                    FieldKey.ENABLED, "Abilita", FieldType.Toggle,
                    hint = "Vale solo per la prossima procedura. Per riceverla sempre attiva 'Stampa su ECR' in Opzioni",
                ),
            ),
        ),
        def(
            CommandCode.REPRINT, CommandGroup.TERMINAL, "Ristampa dell'ultimo scontrino, sul terminale o verso l'ECR (righe 'S').",
            listOf(
                FieldDef(
                    FieldKey.REPRINT_TARGET, "Destinazione",
                    FieldType.Choice(listOf(ReprintTarget.TERMINAL.name to "Stampa sul terminale", ReprintTarget.ECR.name to "Invia all'ECR")),
                ),
                FieldDef(
                    FieldKey.TICKET_TYPE, "Scontrino",
                    FieldType.Choice(listOf(TicketType.LAST_FINANCIAL.name to "Ultimo finanziario", TicketType.LAST_SERVICE.name to "Ultimo di servizio")),
                ),
            ),
        ),
    )

    private val byCode: Map<CommandCode, CommandDef> = all.associateBy { it.code }

    fun of(code: CommandCode): CommandDef = byCode.getValue(code)
}
