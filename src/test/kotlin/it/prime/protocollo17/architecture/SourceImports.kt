package it.prime.protocollo17.architecture

import java.io.File

/**
 * Lettura dei sorgenti per i test architetturali (stile `SelectionSafeImportsTest` di LocalDesktopAi): il
 * compilatore non vede le regole di dipendenza tra package di un modulo unico (§2.10, U12), questi test sì.
 */
internal object SourceImports {

    /** Radice dei sorgenti main: la cartella di lavoro dei test Gradle è la radice del progetto. */
    val mainRoot: File = File("src/main/kotlin/it/prime/protocollo17")

    private val importRegex = Regex("""^import\s+([\w.]+)""")
    private val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val lineComment = Regex("""//[^\n]*""")

    /** Un import colpevole: file relativo, numero di riga e percorso importato. */
    data class Import(val file: String, val line: Int, val path: String) {
        override fun toString(): String = "$file:$line  import $path"
    }

    /** Tutti i file .kt sotto `mainRoot/<relativeDir>` (vuota = tutto main). */
    fun sources(relativeDir: String): List<File> {
        val dir = if (relativeDir.isEmpty()) mainRoot else File(mainRoot, relativeDir)
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
    }

    fun imports(files: List<File>): List<Import> = files.flatMap { file ->
        file.readLines().mapIndexedNotNull { index, line ->
            importRegex.find(line.trim())?.let { Import(rel(file), index + 1, it.groupValues[1]) }
        }
    }

    /**
     * Testo del file senza commenti, per i controlli testuali (Dispatchers.Main/IO): una KDoc che spiega
     * "mai Dispatchers.IO qui" non deve far fallire il test, il codice che lo usa sì.
     */
    fun codeWithoutComments(file: File): String =
        file.readText().replace(blockComment, " ").replace(lineComment, " ")

    fun rel(file: File): String = file.invariantSeparatorsPath.substringAfter("src/main/kotlin/")

    /** true se path è il package/tipo indicato o qualcosa al suo interno (prefix seguito da '.'). */
    fun String.isUnder(prefix: String): Boolean = this == prefix || startsWith("$prefix.")
}
