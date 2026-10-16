package it.prime.protocollo17.presentation.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * La versione di Aiuto > Informazioni è quella di Gradle (§8.10 punto 5): se processResources smettesse di espandere
 * la risorsa, l'app mostrerebbe "sconosciuta" o il segnaposto e il pacchetto non sarebbe più riconoscibile.
 */
class AppVersionTest {

    @Test
    fun `la versione letta e' quella di build gradle`() {
        val expected = System.getProperty("app.version")
        assertNotNull(expected, "manca la system property app.version (tasks.test in build.gradle.kts)")
        assertEquals(expected, AppVersion.read())
        assertEquals(expected, AppVersion.current)
    }
}
