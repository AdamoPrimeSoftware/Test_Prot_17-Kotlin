import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

group = "it.prime"
// Unica fonte della versione: ne derivano packageVersion e app-version.properties (Aiuto > Informazioni).
version = "1.0.0"

// Moduli JDK del runtime jlink di MSI ed EXE, oltre ai default del plugin Compose (java.base, java.desktop,
// java.logging, jdk.crypto.ec). Prima c'era solo l'esito di `suggestModules` (java.instrument, jdk.unsupported), che
// vede solo i riferimenti statici: l'app installata non partiva, perché logback, caricato da slf4j via ServiceLoader,
// legge logback.xml solo se c'è java.naming (NoClassDefFoundError: javax/naming/NamingException prima del banner).
// jdk.accessibility evita l'AWTError all'avvio sui PC con Java Access Bridge attivo (lettori di schermo).
// java.instrument e jdk.unsupported vengono da suggestModules. PackagedRuntimeTest avvia una JVM limitata all'elenco
// finale del plugin e verifica logback (java.naming), il provider AccessBridge (jdk.accessibility), la presenza degli
// altri due e formatCents: togliere uno di questi moduli lo fa fallire. Un modulo nuovo si prova con runDistributable.
val extraRuntimeModules = listOf("java.instrument", "jdk.unsupported", "java.naming", "jdk.accessibility")

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Voyager
    implementation(libs.voyager.navigator)
    implementation(libs.voyager.screenmodel)
    implementation(libs.voyager.koin)

    // Koin DI
    implementation(libs.koin.core)
    implementation(libs.koin.compose)

    // Serialization (settings, journal) + Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    // Esplicito: silenzia la deprecation "automatic loading of test framework" (errore in Gradle 9)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    // Prima la versione mostrata dall'app era una costante in MainWindow e poteva divergere dal pacchetto.
    val appVersion = project.version.toString()
    inputs.property("appVersion", appVersion)
    filesMatching("app-version.properties") { expand("version" to appVersion) }
}

tasks.test {
    useJUnitPlatform {
        // I test contro un terminale reale partono solo con -PrealTerminal (vedi §7.10)
        if (!project.hasProperty("realTerminal")) excludeTags("real-terminal")
    }
    // AppVersionTest confronta la risorsa generata con la versione di Gradle.
    systemProperty("app.version", project.version.toString())
}

compose.desktop {
    application {
        mainClass = "it.prime.protocollo17.MainKt"
        // Senza, `run` usa la JVM di `org.gradle.java.home` del gradle.properties GLOBALE
        // (una 17 su questo PC): l'app compilata per la 21 morirebbe all'avvio senza log.
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            // Vedi extraRuntimeModules: `suggestModules` è un punto di partenza, non l'elenco completo.
            modules(*extraRuntimeModules.toTypedArray())
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Protocollo17ComposeDesktop"
            packageVersion = project.version.toString()   // MSI rifiuta MAJOR = 0
            description = "Test app Protocollo 17 (ECR17) per terminali Ingenico/CCV"
            vendor = "Prime Software"
            windows {
                menuGroup = "Protocollo17"
                upgradeUuid = "0e99d6a1-1ceb-4d07-8b01-6d9f27d4a11b"   // fisso per sempre
                // Prima l'installer usava l'icona di default di Compose. Stesso disegno di rememberAppIcon() (§6.1),
                // renderizzato una volta a 16/32/48/256 px: se cambia AppIcon.kt va rigenerato.
                iconFile.set(project.file("src/main/packaging/icon.ico"))
            }
        }
    }
}

// Dopo compose.desktop: PackagedRuntimeTest riceve l'elenco FINALE che il plugin passa a jlink (i suoi default più
// extraRuntimeModules), non una copia a mano dei default che un aggiornamento del plugin farebbe divergere.
tasks.test {
    systemProperty("p17.runtimeModules", compose.desktop.application.nativeDistributions.modules.joinToString(","))
}
