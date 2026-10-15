package it.prime.protocollo17.domain.model.settings

/**
 * Una chiave per ogni campo di ProtocolSettings/ProtocolOptions (stesso ordine).
 *
 * La finestra Opzioni e ConfigResolver lavorano per chiave (testo in modifica, errore di campo, range
 * dell'operatore) invece di un `when` per proprietà sparso in più file: un'opzione nuova si aggiunge qui.
 */
enum class OptionKey {
    APP_LRC_MODE, CONTROL_LRC_TX, CONTROL_LRC_RX, APP_LRC_RX, FINANCIAL_ACK_POLICY, CONNECTION_MODE,
    CONNECT_TIMEOUT_MS, ACK_TIMEOUT_MS, RESPONSE_TIMEOUT_MS, NAK_RETRY_COUNT, ACK_TIMEOUT_RETRANSMIT_COUNT,
    RETRY_DELAY_MS, POST_RESULT_DRAIN_MS, RECEIPT_DRAIN_MS, ECR_PRINTING, PRE_SEND_QUIET_MS, WRITE_TIMEOUT_MS,
    AUTO_RECONNECT_SAFE_COMMANDS,
}
