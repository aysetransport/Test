package de.aysetransport.schaden.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Anhänge von Mails, die (noch) keinem Fall gehören. Sie liegen je Mail in einem eigenen Ordner, bis die
 * Mail einem Fall zugeordnet wird – dann werden sie zu Anlagen des Falls.
 */
class MailAnhangSpeicher(val wurzel: File) {

    fun ordner(mailId: Long): File = File(wurzel, mailId.toString())

    fun dateien(mailId: Long): List<File> = ordner(mailId).listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    /** Ursprünglicher Dateiname (ohne die laufende Nummer davor). */
    fun anzeigename(datei: File): String = datei.name.substringAfter('_')

    suspend fun speichere(mailId: Long, anhaenge: List<MailAnhang>) = withContext(Dispatchers.IO) {
        if (anhaenge.isEmpty()) return@withContext
        val ordner = ordner(mailId).apply { mkdirs() }
        anhaenge.forEachIndexed { i, a -> File(ordner, "${i + 1}_${sichererName(a.name)}").writeBytes(a.bytes) }
    }

    suspend fun loesche(mailId: Long) = withContext(Dispatchers.IO) { ordner(mailId).deleteRecursively() }

    suspend fun loescheAlle() = withContext(Dispatchers.IO) { wurzel.deleteRecursively() }

    private fun sichererName(name: String): String =
        name.trim().map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").take(100).ifEmpty { "anhang" }

    companion object {
        const val ORDNER = "mail-anhaenge"
    }
}
