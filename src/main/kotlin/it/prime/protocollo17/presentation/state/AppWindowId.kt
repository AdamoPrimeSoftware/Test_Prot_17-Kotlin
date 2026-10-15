package it.prime.protocollo17.presentation.state

/**
 * Finestre secondarie (U10). Il name è anche la chiave della geometria in UiSettings.windows e il valore
 * persistito in UiSettings.openWindows: rinominare una voce fa perdere geometria e riapertura di quella finestra.
 */
enum class AppWindowId { OPTIONS, TRAFFIC_LOG, JOURNAL, SIMULATOR }
