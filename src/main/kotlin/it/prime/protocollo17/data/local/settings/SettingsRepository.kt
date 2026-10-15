package it.prime.protocollo17.data.local.settings

import it.prime.protocollo17.domain.model.settings.AppSettings
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unica fonte delle impostazioni, come StateFlow: le finestre la osservano e una modifica in Opzioni vale subito
 * ovunque (niente istantanee lette una sola volta in `init`). Il file è un parametro per i test.
 *
 * Replica di LocalDesktopAi (§5.2.3): caricamento difensivo (BOM rimosso; un file illeggibile viene copiato in
 * `settings.json.broken-<millis>` e mai sovrascritto senza copia), scrittura atomica `.part` + move, debounce di
 * 400 ms (una frase digitata in un campo diventa UNA scrittura), flush in [close] e nello shutdown hook.
 */
class SettingsRepository(private val file: File) : AutoCloseable {

    private val log = LoggerFactory.getLogger(SettingsRepository::class.java)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _flow = MutableStateFlow(loadFromDisk())
    val flow: StateFlow<AppSettings> = _flow.asStateFlow()

    /** Serializza le scritture su disco (gli aggiornamenti in memoria sono già atomici via CAS). */
    private val diskLock = Any()

    /** Scope della scrittura differita: mai sul thread della UI. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("settings-save"))

    /** CONFLATED: durante il debounce i segnali extra collassano in uno, conta solo l'ultimo stato. */
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)

    /** Ultimo stato scritto con successo: evita riscritture identiche. Avanza solo se la scrittura riesce. */
    @Volatile
    private var lastSaved: AppSettings = _flow.value

    private val closed = AtomicBoolean(false)

    /** Scritture effettivamente eseguite (per i test: più update ravvicinati ⇒ una scrittura). */
    internal val saveCount = AtomicInteger(0)

    // ALT+F4 o la chiusura della JVM non passano sempre da close(): senza l'hook si perderebbero le modifiche
    // degli ultimi 400 ms, cioè proprio quelle appena fatte (geometria della finestra, ultimo comando).
    private val shutdownHook = Thread({ flushIfDirty() }, "settings-flush")

    init {
        scope.launch {
            for (ignored in saveSignal) {
                delay(DEBOUNCE_MILLIS)
                flushIfDirty()
            }
        }
        runCatching { Runtime.getRuntime().addShutdownHook(shutdownHook) }
            .onFailure { log.warn("Shutdown hook delle impostazioni non registrato: {}", it.message) }
    }

    fun getSnapshot(): AppSettings = _flow.value

    /** CAS su MutableStateFlow + segnale di salvataggio con debounce 400 ms. */
    fun update(transform: (AppSettings) -> AppSettings) {
        // updateAndGet ripete la transform in un loop CAS: niente read-modify-write perso tra UI e servizi.
        _flow.updateAndGet(transform)
        saveSignal.trySend(Unit)
    }

    /** Annulla lo scope e salva subito se ci sono modifiche. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        flushIfDirty()
        // Durante lo spegnimento della JVM removeShutdownHook lancia: l'hook farà comunque un flush innocuo.
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }

    /**
     * Scrive solo se lo stato corrente differisce dall'ultimo salvato. Tutto sotto [diskLock]: il consumatore del
     * debounce e close() possono sovrapporsi, e senza il lucchetto il più lento riscriverebbe uno stato vecchio.
     */
    private fun flushIfDirty() {
        synchronized(diskLock) {
            val current = _flow.value
            if (current == lastSaved) return
            if (saveToDisk(current)) lastSaved = current
        }
    }

    private fun loadFromDisk(): AppSettings {
        if (!file.exists()) {
            log.info("{} non esiste: impostazioni di default (primo avvio?)", file.name)
            return AppSettings()
        }
        val raw = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            log.error("Lettura di {} fallita ({}): impostazioni di default", file.absolutePath, e.message)
            return AppSettings()
        }
        // BOM UTF-8: lo scrivono alcuni editor e Set-Content -Encoding UTF8 di PowerShell 5.1.
        val clean = raw.removePrefix(BOM)
        return try {
            json.decodeFromString(AppSettings.serializer(), clean)
        } catch (e: Exception) {
            val backup = File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}")
            log.error(
                "Parsing di {} fallito ({}): copia in {} e impostazioni di default. Recupera i valori da quel file.",
                file.name, e.message, backup.name,
            )
            try {
                file.copyTo(backup, overwrite = false)
            } catch (copyError: Exception) {
                log.error("Copia di sicurezza di {} non riuscita: {}", file.name, copyError.message)
            }
            AppSettings()
        }
    }

    /** @return true se il file è stato scritto. */
    private fun saveToDisk(settings: AppSettings): Boolean = synchronized(diskLock) {
        val part = File(file.parentFile, "${file.name}.part")
        try {
            file.parentFile?.mkdirs()
            // .part nella STESSA cartella: ATOMIC_MOVE funziona solo nello stesso filesystem. Un crash a metà
            // scrittura lascia al massimo un .part orfano, mai un settings.json troncato (app che non parte).
            part.writeText(json.encodeToString(AppSettings.serializer(), settings), Charsets.UTF_8)
            try {
                Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            saveCount.incrementAndGet()
            true
        } catch (e: Exception) {
            log.error("Salvataggio di {} fallito: {}", file.absolutePath, e.message)
            part.delete()
            false
        }
    }

    private companion object {
        /** Abbastanza corto da non perdere nulla in uso reale, abbastanza lungo da fare di una frase una scrittura. */
        const val DEBOUNCE_MILLIS = 400L
        const val BOM = "﻿"
    }
}
