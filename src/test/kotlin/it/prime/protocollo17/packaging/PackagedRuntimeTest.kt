package it.prime.protocollo17.packaging

import it.prime.protocollo17.domain.util.formatCents
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File
import java.util.ServiceLoader
import java.util.concurrent.TimeUnit
import javax.accessibility.AccessibilityProvider

/**
 * Il runtime jlink di MSI ed EXE (§2.5, §8.10) contiene solo i default del plugin Compose più `extraRuntimeModules`,
 * mentre gli altri test girano sul JDK completo e non vedono cosa manca. Prima, con i test tutti verdi, l'MSI
 * installato non partiva (logback non legge logback.xml senza `java.naming`: NoClassDefFoundError prima del banner)
 * e mostrava "0.01 €" (`Locale.ITALY` senza `jdk.localedata`). Ora una JVM figlia con `--limit-modules` uguale
 * all'elenco finale del plugin passato da Gradle (`p17.runtimeModules`) esegue [PackagedRuntimeProbe]: logback deve
 * configurarsi da logback.xml dell'app, con il FILE appender (`java.naming`), il provider AccessBridge di Java Access
 * Bridge deve esserci (`jdk.accessibility`: senza, AWT lancia AWTError all'avvio sui PC con JAB attivo) e formatCents
 * deve dare i simboli italiani; `jdk.unsupported` e `java.instrument` (da suggestModules) devono esserci.
 */
class PackagedRuntimeTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun `logback, AccessBridge e formatCents funzionano con i soli moduli del pacchetto`() {
        val modules = System.getProperty("p17.runtimeModules")
        assertNotNull(modules, "p17.runtimeModules arriva da tasks.test in build.gradle.kts")
        // Esplicito: senza, sul classpath dei test logback sceglierebbe logback-test.xml (solo console).
        val logbackXml = checkNotNull(javaClass.getResource("/logback.xml")) { "logback.xml dell'app sul classpath" }
        val home = File(tmp, "home").apply { mkdirs() }
        val output = File(tmp, "probe-output.txt")

        // user.home temporanea: il FILE appender di logback.xml scrive sotto tmp, mai nella vera ~/.protocollo17.
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "--limit-modules", modules,
            "-Duser.home=${home.absolutePath}",
            "-Dlogback.configurationFile=$logbackXml",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-cp", System.getProperty("java.class.path"),
            PackagedRuntimeProbe::class.java.name,
        ).redirectErrorStream(true).redirectOutput(output).start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        // TerminateProcess è asincrono: si aspetta l'uscita prima che JUnit cancelli tmp con i file ancora aperti.
        if (!finished) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        val text = if (output.exists()) output.readText(Charsets.UTF_8) else ""

        assertTrue(finished, "la JVM figlia non è terminata entro 60 s:\n$text")
        assertEquals(0, process.exitValue(), "la JVM figlia con i moduli del pacchetto è fallita:\n$text")
        val loaded = text.lineSequence().firstOrNull { it.startsWith("MODULES=") }.orEmpty()
        assertFalse("jdk.localedata" in loaded, "--limit-modules non applicato, il test non proverebbe nulla:\n$text")
        assertTrue("FORMAT=1.234,56 €" in text, "simboli italiani anche senza dati di locale:\n$text")
        assertTrue("ACCESS_BRIDGE=true" in text, "provider di Java Access Bridge (jdk.accessibility):\n$text")
        assertTrue("SUGGESTED_MODULES=true" in text, "jdk.unsupported e java.instrument nel runtime:\n$text")
        val appLog = File(home, ".protocollo17/logs/app.log")
        assertTrue(
            appLog.exists() && PackagedRuntimeProbe.LOG_LINE in appLog.readText(Charsets.UTF_8),
            "logback configurato da logback.xml con il FILE appender:\n$text",
        )
    }
}

/** Eseguito dalla JVM figlia di [PackagedRuntimeTest]: stampa su stdout ciò che il test controlla. */
object PackagedRuntimeProbe {
    const val LOG_LINE = "sonda del runtime del pacchetto"

    @JvmStatic
    fun main(args: Array<String>) {
        // Come Main.kt: il primo logger fa configurare logback da logback.xml.
        LoggerFactory.getLogger("Protocollo17.PackagedRuntimeProbe").info(LOG_LINE)
        println("FORMAT=" + formatCents(123_456))
        // È il nome che AWT cerca in assistive_technologies quando JAB è attivo; i provider si istanziano senza
        // inizializzare il Toolkit né attivarli.
        val accessBridge = ServiceLoader.load(AccessibilityProvider::class.java)
            .any { it.name == "com.sun.java.accessibility.AccessBridge" }
        println("ACCESS_BRIDGE=$accessBridge")
        // jdk.unsupported (sun.misc.Unsafe) e java.instrument: li chiede suggestModules per le dipendenze; qui si
        // controlla solo che ci siano, così toglierli dall'elenco fa fallire il test invece di passare inosservato.
        val classes = listOf("sun.misc.Unsafe", "java.lang.instrument.Instrumentation")
            .all { runCatching { Class.forName(it, false, ClassLoader.getSystemClassLoader()) }.isSuccess }
        println("SUGGESTED_MODULES=$classes")
        println("MODULES=" + ModuleLayer.boot().modules().map { it.name }.sorted().joinToString(","))
    }
}
