import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

group = "it.prime"
version = "1.0.0"

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

tasks.test {
    useJUnitPlatform {
        // I test contro un terminale reale partono solo con -PrealTerminal (vedi §7.10)
        if (!project.hasProperty("realTerminal")) excludeTags("real-terminal")
    }
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
            // Da riverificare con `.\gradlew.bat suggestModules` prima del primo pacchetto.
            modules("java.instrument", "java.management", "jdk.unsupported")
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Protocollo17ComposeDesktop"
            packageVersion = "1.0.0"          // MSI rifiuta MAJOR = 0
            description = "Test app Protocollo 17 (ECR17) per terminali Ingenico/CCV"
            vendor = "Prime Software"
            windows {
                menuGroup = "Protocollo17"
                upgradeUuid = "0e99d6a1-1ceb-4d07-8b01-6d9f27d4a11b"   // fisso per sempre
            }
        }
    }
}
