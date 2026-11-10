package de.aysetransport.schaden.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import de.aysetransport.schaden.data.model.Anlage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Angaben zu einer in den App-Speicher kopierten Datei. */
data class ImportierteDatei(
    val dateiname: String,
    val anzeigename: String,
    val mimeType: String,
    val groesseBytes: Long,
)

class DateiZuGrossException(val anzeigename: String) : IOException("Datei zu groß: $anzeigename")

/**
 * Verwaltet die Dateien der Anlagen im privaten App-Speicher:
 * `files/anlagen/<fallId>/<zufallsname>.<endung>`.
 */
class AnlagenSpeicher(
    private val context: Context,
    /** Ablageort der Anlagen; Tests geben jedem simulierten Gerät einen eigenen. */
    val wurzel: File = File(context.filesDir, ORDNER),
) {

    fun ordner(fallId: Long): File = File(wurzel, fallId.toString())

    fun datei(anlage: Anlage): File = File(ordner(anlage.fallId), anlage.dateiname)

    /** `content://`-Adresse, über die andere Apps (Bildbetrachter, PDF-Anzeige) die Datei lesen dürfen. */
    fun freigabeUri(datei: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", datei)

    /** Leere Zieldatei für eine Kameraaufnahme. Wird nach dem Import wieder gelöscht. */
    fun neueKameraDatei(): File {
        val ordner = File(context.cacheDir, KAMERA_ORDNER).apply { mkdirs() }
        return File(ordner, "foto_${System.currentTimeMillis()}.jpg")
    }

    fun exportDatei(name: String): File {
        val ordner = File(context.cacheDir, EXPORT_ORDNER).apply { mkdirs() }
        return File(ordner, name)
    }

    /**
     * Kopiert den Inhalt von [quelle] in den Ordner des Falls.
     *
     * @throws DateiZuGrossException wenn die Datei größer als [MAX_BYTES] ist
     * @throws IOException wenn die Quelle nicht gelesen werden kann
     */
    suspend fun importiere(fallId: Long, quelle: Uri, vorgabeName: String? = null): ImportierteDatei =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val anzeigename = vorgabeName ?: anzeigename(quelle) ?: "Anlage"
            val mimeType = resolver.getType(quelle)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(anzeigename.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            val endung = endung(anzeigename, mimeType)

            val zielOrdner = ordner(fallId).apply { mkdirs() }
            val ziel = File(zielOrdner, UUID.randomUUID().toString() + endung)
            try {
                val eingang = resolver.openInputStream(quelle) ?: throw IOException("Quelle nicht lesbar: $quelle")
                var kopiert = 0L
                eingang.use { ein ->
                    ziel.outputStream().use { aus ->
                        val puffer = ByteArray(64 * 1024)
                        while (true) {
                            val gelesen = ein.read(puffer)
                            if (gelesen < 0) break
                            kopiert += gelesen
                            if (kopiert > MAX_BYTES) throw DateiZuGrossException(anzeigename)
                            aus.write(puffer, 0, gelesen)
                        }
                    }
                }
                ImportierteDatei(ziel.name, anzeigename, mimeType, kopiert)
            } catch (e: Exception) {
                ziel.delete()
                throw e
            }
        }

    /** Legt den Inhalt einer aus der Cloud geladenen Anlage ab und gibt den Dateinamen zurück. */
    suspend fun speichere(fallId: Long, bytes: ByteArray, anzeigename: String, mimeType: String): String = withContext(Dispatchers.IO) {
        val ziel = File(ordner(fallId).apply { mkdirs() }, UUID.randomUUID().toString() + endung(anzeigename, mimeType))
        ziel.writeBytes(bytes)
        ziel.name
    }

    suspend fun loesche(anlage: Anlage) = withContext(Dispatchers.IO) {
        if (anlage.istLokalVorhanden) datei(anlage).delete()
        Unit
    }

    suspend fun loescheOrdner(fallId: Long) = withContext(Dispatchers.IO) {
        ordner(fallId).deleteRecursively()
        Unit
    }

    private fun anzeigename(uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun endung(anzeigename: String, mimeType: String): String {
        val ausName = anzeigename.substringAfterLast('.', "").lowercase()
        val kandidat = ausName.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return if (kandidat.isNullOrEmpty()) "" else ".$kandidat"
    }

    companion object {
        const val ORDNER = "anlagen"
        private const val KAMERA_ORDNER = "kamera"
        private const val EXPORT_ORDNER = "export"

        /**
         * Obergrenze je Datei, damit ein versehentlich gewähltes Video nicht den Speicher füllt.
         * Entspricht der Grenze des Cloud-Skripts.
         */
        const val MAX_BYTES = 25L * 1024 * 1024
    }
}
