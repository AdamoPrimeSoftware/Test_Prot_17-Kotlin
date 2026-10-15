package it.prime.protocollo17.data.local.settings

import it.prime.protocollo17.domain.model.settings.AppSettings
import it.prime.protocollo17.domain.model.settings.TerminalSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsRepositoryTest {

    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "settings.json")

    /** Attesa di una condizione su thread reali (il salvataggio gira su Dispatchers.IO), mai uno sleep fisso. */
    private fun awaitTrue(timeoutMs: Long = 5_000, message: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            check(System.currentTimeMillis() < deadline) { "timeout: $message" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `file assente produce i default`() {
        SettingsRepository(file).use { repo ->
            assertEquals(AppSettings(), repo.getSnapshot(), "default")
            assertEquals(AppSettings(), repo.flow.value, "flow")
        }
    }

    /** PowerShell 5.1 (Set-Content -Encoding UTF8) scrive il BOM: prima il parsing falliva e si perdevano le impostazioni. */
    @Test
    fun `file con BOM viene letto`() {
        file.writeText("﻿{\"terminal\":{\"host\":\"10.0.0.5\"}}", Charsets.UTF_8)

        SettingsRepository(file).use { repo ->
            assertEquals("10.0.0.5", repo.getSnapshot().terminal.host, "host letto nonostante il BOM")
        }
    }

    @Test
    fun `JSON rotto viene copiato in broken e si usano i default`() {
        val broken = "{\"terminal\": {\"host\": "
        file.writeText(broken, Charsets.UTF_8)

        SettingsRepository(file).use { repo ->
            assertEquals(AppSettings(), repo.getSnapshot(), "default")
        }
        val backups = dir.listFiles { f -> f.name.startsWith("settings.json.broken-") }.orEmpty()
        assertEquals(1, backups.size, "una copia di sicurezza")
        assertEquals(broken, backups.single().readText(), "contenuto originale conservato")
        assertEquals(broken, file.readText(), "file originale non sovrascritto senza modifiche")
    }

    @Test
    fun `chiavi sconosciute ignorate`() {
        file.writeText("{\"futureKey\": 42, \"terminal\": {\"host\": \"h\", \"altro\": true}}", Charsets.UTF_8)

        SettingsRepository(file).use { repo ->
            assertEquals("h", repo.getSnapshot().terminal.host, "host")
        }
        assertTrue(dir.listFiles { f -> f.name.contains("broken") }.isNullOrEmpty(), "nessuna copia broken")
    }

    /** Un campo di testo genera un update per carattere: prima ognuno era una scrittura atomica su disco. */
    @Test
    fun `piu update ravvicinati producono una sola scrittura`() {
        val repo = SettingsRepository(file)
        for (i in 1..20) repo.update { it.copy(terminal = it.terminal.copy(host = "10.0.0.$i")) }

        awaitTrue(message = "prima scrittura") { repo.saveCount.get() >= 1 }
        repo.close()

        assertEquals(1, repo.saveCount.get(), "una sola scrittura")
        assertTrue(file.readText().contains("10.0.0.20"), "ultimo valore su disco")
        assertTrue(!File(dir, "settings.json.part").exists(), "nessun .part residuo")
    }

    @Test
    fun `close salva subito senza attendere il debounce`() {
        val repo = SettingsRepository(file)
        repo.update { it.copy(terminal = TerminalSettings(host = "chiusura", port = 1234)) }
        repo.close()

        val reloaded = SettingsRepository(file)
        try {
            assertEquals("chiusura", reloaded.getSnapshot().terminal.host, "host salvato in close")
            assertEquals(1234, reloaded.getSnapshot().terminal.port, "porta salvata in close")
        } finally {
            reloaded.close()
        }
        assertTrue(dir.listFiles { f -> f.name.endsWith(".part") }.isNullOrEmpty(), "nessun .part residuo")
    }

    @Test
    fun `close senza modifiche non scrive`() {
        val repo = SettingsRepository(file)
        repo.close()
        assertEquals(0, repo.saveCount.get(), "nessuna scrittura")
        assertTrue(!file.exists(), "nessun file creato")
    }

    @Test
    fun `update aggiorna subito snapshot e flow`() {
        SettingsRepository(file).use { repo ->
            repo.update { it.copy(schemaVersion = 7) }
            assertEquals(7, repo.getSnapshot().schemaVersion, "snapshot")
            assertEquals(7, repo.flow.value.schemaVersion, "flow")
        }
    }
}
