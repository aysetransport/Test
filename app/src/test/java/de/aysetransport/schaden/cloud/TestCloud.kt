package de.aysetransport.schaden.cloud

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Startet das echte Cloud-Skript (cloud/Code.gs) in einer nachgebildeten Google-Umgebung
 * (cloud/test/mock-server.js, benötigt Node.js). So prüfen die Tests den Abgleich gegen genau
 * den Code, der später in Google läuft.
 */
class TestCloud : AutoCloseable {
    private val prozess: Process
    val url: String

    init {
        val skript = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, "cloud/test/mock-server.js") }
            .firstOrNull { it.isFile } ?: error("cloud/test/mock-server.js nicht gefunden")
        prozess = ProcessBuilder("node", skript.path).redirectErrorStream(true).start()
        val zeilen = BufferedReader(InputStreamReader(prozess.inputStream))
        url = zeilen.readLine()?.removePrefix("CLOUD ")?.takeIf { it.startsWith("http") }
            ?: error("Die Test-Cloud ist nicht gestartet")
    }

    override fun close() {
        prozess.destroy()
        prozess.waitFor(5, TimeUnit.SECONDS)
    }
}
