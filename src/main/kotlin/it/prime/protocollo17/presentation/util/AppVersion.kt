package it.prime.protocollo17.presentation.util

import java.io.IOException
import java.util.Properties

/**
 * Versione dell'app mostrata in Aiuto > Informazioni.
 *
 * Prima era una costante in MainWindow, che poteva divergere da `version` di build.gradle.kts (da cui deriva il
 * `packageVersion` dell'MSI). Ora `processResources` scrive la versione di Gradle in [RESOURCE] e qui la si legge:
 * c'è un solo numero da cambiare. Senza la risorsa (classpath anomalo) si mostra [UNKNOWN] invece di fallire.
 */
object AppVersion {
    const val RESOURCE = "/app-version.properties"
    const val UNKNOWN = "sconosciuta"

    val current: String by lazy { read() ?: UNKNOWN }

    /** null se la risorsa manca, è illeggibile o non è stata espansa (segnaposto `${version}` ancora presente). */
    internal fun read(): String? = try {
        AppVersion::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")?.trim()
                ?.takeIf { it.isNotEmpty() && !it.contains('$') }
        }
    } catch (e: IOException) {
        null
    }
}
