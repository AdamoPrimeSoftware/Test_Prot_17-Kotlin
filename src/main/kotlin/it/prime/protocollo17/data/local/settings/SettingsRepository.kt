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
import kotlinx.serialization.json.jsonObject
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
 *
 * "Mai sovrascrivere senza copia" vale anche quando la copia non riesce o il file non si legge affatto (es. una
 * violazione di condivisione di antivirus o OneDrive): prima si partiva dai default e il primo salvataggio (la
 * geometria della finestra, quasi subito) cancellava host, porta e TID veri. Ora ogni salvataggio prova prima la
 * copia e scrive solo se è riuscita.
 */
class SettingsRepository internal constructor(
    private val file: File,
    /** Solo per il nome della copia `broken-<millis>`: i test lo fissano per occuparne il percorso. */
    private val wallClock: () -> Long,
) : AutoCloseable {

    /** Il costruttore pubblico di §5.2.3. */
    constructor(file: File) : this(file, System::currentTimeMillis)

    private val log = LoggerFactory.getLogger(SettingsRepository::class.java)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * true se settings.json esiste ma non è stato letto (errore di I/O) o, rotto, non è stato copiato: in memoria ci
     * sono i default e il file contiene ancora i valori veri. Finché resta true ogni salvataggio prova prima la copia
     * in `broken-<millis>` e, se non riesce, non scrive. Dichiarata PRIMA di [_flow]: loadFromDisk la imposta
     * durante l'inizializzazione, e un inizializzatore successivo la riporterebbe a false.
     */
    @Volatile
    private var writeBlocked = false

    /**
     * Il contenuto letto da disco PRIMA di [SettingsMigration], se la migrazione lo ha cambiato: diventa [lastSaved],
     * così il primo salvataggio (subito, dall'init) porta il file alla versione corrente invece di rimigrarlo a ogni
     * avvio. Dichiarata prima di [_flow] per lo stesso motivo di [writeBlocked].
     */
    private var unmigratedOnDisk: AppSettings? = null

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
    private var lastSaved: AppSettings = unmigratedOnDisk ?: _flow.value

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
        if (unmigratedOnDisk != null) saveSignal.trySend(Unit)
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
            writeBlocked = true
            log.error(
                "Lettura di {} fallita ({}): impostazioni di default; il file non verrà sovrascritto finché non se ne fa una copia",
                file.absolutePath, e.message,
            )
            return AppSettings()
        }
        // BOM UTF-8: lo scrivono alcuni editor e Set-Content -Encoding UTF8 di PowerShell 5.1.
        val clean = raw.removePrefix(BOM)
        val decoded = try {
            json.decodeFromString(AppSettings.serializer(), clean)
        } catch (e: Exception) {
            log.error("Parsing di {} fallito ({}): impostazioni di default. Recupera i valori dalla copia broken.", file.name, e.message)
            if (!copyAside()) writeBlocked = true
            return AppSettings()
        }
        // Una chiave assente vale la versione più vecchia, non la corrente: un file scritto a mano (o parziale) senza
        // schemaVersion deve passare da tutte le migrazioni, e il default della proprietà è quello di un oggetto nuovo.
        val versioned = if (hasSchemaVersion(clean)) decoded else decoded.copy(schemaVersion = 1)
        val migrated = SettingsMigration.migrate(versioned)
        if (migrated != decoded) {
            unmigratedOnDisk = decoded
            log.info(
                "{}: formato {} aggiornato a {} (timeout esito {} ms)",
                file.name, versioned.schemaVersion, migrated.schemaVersion, migrated.protocol.responseTimeoutMs,
            )
        }
        return migrated
    }

    /** true se il JSON (già decodificato senza errori) ha la chiave schemaVersion alla radice. */
    private fun hasSchemaVersion(clean: String): Boolean =
        runCatching { "schemaVersion" in json.parseToJsonElement(clean).jsonObject }.getOrDefault(true)

    /**
     * Copia settings.json in `settings.json.broken-<millis>`. true se la copia esiste (o se il file non c'è più e
     * non resta nulla da proteggere). Una copia parziale viene cancellata: una copia vuota sembrerebbe buona.
     */
    private fun copyAside(): Boolean {
        if (!file.exists()) return true
        val backup = File(file.parentFile, "${file.name}.broken-${wallClock()}")
        if (backup.exists()) {
            log.error("Copia di sicurezza di {} non riuscita: {} esiste già", file.name, backup.name)
            return false
        }
        return try {
            file.copyTo(backup, overwrite = false)
            log.warn("Copia di sicurezza di {} in {}", file.name, backup.name)
            true
        } catch (e: Exception) {
            backup.delete()
            log.error("Copia di sicurezza di {} in {} non riuscita: {}", file.name, backup.name, e.message)
            false
        }
    }

    /** @return true se il file è stato scritto. */
    private fun saveToDisk(settings: AppSettings): Boolean = synchronized(diskLock) {
        // Il file su disco ha valori che non sono in memoria: si scrive solo dopo averne fatto una copia.
        if (writeBlocked) {
            if (!copyAside()) {
                log.error("{} non salvato: senza una copia del file esistente non lo sovrascrivo", file.absolutePath)
                return@synchronized false
            }
            writeBlocked = false
        }
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
