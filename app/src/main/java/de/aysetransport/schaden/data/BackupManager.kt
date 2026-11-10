package de.aysetransport.schaden.data

import androidx.room.withTransaction
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.Anlage
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupDaten(
    val version: Int = BackupManager.FORMAT_VERSION,
    val erstelltAm: String = "",
    val faelle: List<Schadensfall> = emptyList(),
    val verlauf: List<VerlaufEintrag> = emptyList(),
    val anlagen: List<Anlage> = emptyList(),
)

data class BackupErgebnis(val faelle: Int, val anlagen: Int)

class BackupUngueltigException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Sichert alle Fälle samt Verlauf und Anlagen in eine ZIP-Datei und stellt sie daraus
 * wieder her. Aufbau der Datei: `daten.json` plus `anlagen/<fallId>/<dateiname>`.
 */
class BackupManager(
    private val db: SchadensfallDatenbank,
    private val anlagenSpeicher: AnlagenSpeicher,
    private val arbeitsOrdner: File,
    private val uhr: Clock = Clock.systemDefaultZone(),
) {
    private val dao = db.dao()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportiere(ziel: OutputStream): BackupErgebnis = withContext(Dispatchers.IO) {
        val daten = db.withTransaction {
            BackupDaten(
                erstelltAm = uhr.instant().toString(),
                faelle = dao.ladeAlle(),
                verlauf = dao.ladeGesamtenVerlauf(),
                anlagen = dao.ladeAlleAnlagen(),
            )
        }
        ZipOutputStream(ziel.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(DATEN_DATEI))
            zip.write(json.encodeToString(BackupDaten.serializer(), daten).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (anlage in daten.anlagen) {
                val datei = anlagenSpeicher.datei(anlage)
                if (!datei.isFile) continue // Datei fehlt – Eintrag bleibt in daten.json erhalten
                zip.putNextEntry(ZipEntry("$ANLAGEN_PRAEFIX${anlage.fallId}/${anlage.dateiname}"))
                datei.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        BackupErgebnis(faelle = daten.faelle.size, anlagen = daten.anlagen.size)
    }

    /**
     * Ersetzt den gesamten Datenbestand durch den Inhalt des Backups.
     *
     * Die ZIP-Datei wird zuerst vollständig in einen Zwischenordner entpackt und geprüft.
     * Erst wenn das gelungen ist, werden die vorhandenen Daten ausgetauscht – ein defektes
     * Backup lässt den aktuellen Bestand also unangetastet.
     *
     * @throws BackupUngueltigException wenn die Datei kein lesbares Backup ist
     */
    suspend fun stelleWiederHer(quelle: InputStream): BackupErgebnis = withContext(Dispatchers.IO) {
        val zwischen = File(arbeitsOrdner, "wiederherstellung-${System.nanoTime()}")
        val zwischenAnlagen = File(zwischen, AnlagenSpeicher.ORDNER)
        try {
            zwischenAnlagen.mkdirs()
            var datenText: String? = null
            try {
                ZipInputStream(quelle.buffered()).use { zip ->
                    while (true) {
                        val eintrag = zip.nextEntry ?: break
                        if (eintrag.isDirectory) continue
                        when {
                            eintrag.name == DATEN_DATEI -> datenText = zip.readBytes().toString(Charsets.UTF_8)
                            eintrag.name.startsWith(ANLAGEN_PRAEFIX) -> {
                                val ziel = sichererPfad(zwischenAnlagen, eintrag.name.removePrefix(ANLAGEN_PRAEFIX)) ?: continue
                                ziel.parentFile?.mkdirs()
                                ziel.outputStream().use { zip.copyTo(it) }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                throw BackupUngueltigException(KEIN_BACKUP, e)
            }

            // Auch eine beliebige andere Datei landet hier: Sie enthält schlicht keine Falldaten.
            val text = datenText ?: throw BackupUngueltigException(KEIN_BACKUP)
            val daten = try {
                json.decodeFromString(BackupDaten.serializer(), text)
            } catch (e: SerializationException) {
                throw BackupUngueltigException(BESCHAEDIGT, e)
            } catch (e: IllegalArgumentException) {
                throw BackupUngueltigException(BESCHAEDIGT, e)
            }
            if (daten.version > FORMAT_VERSION) {
                throw BackupUngueltigException("Das Backup stammt aus einer neueren App-Version. Bitte zuerst die App aktualisieren.")
            }
            val fallIds = daten.faelle.map { it.id }.toSet()
            val verlauf = daten.verlauf.filter { it.fallId in fallIds }
            val anlagen = daten.anlagen.filter { it.fallId in fallIds && istSichererName(it.dateiname) }

            db.withTransaction {
                dao.loescheAlleFaelle() // Verlauf und Anlagen hängen per Fremdschlüssel daran
                dao.einfuegenFaelle(daten.faelle)
                dao.einfuegenVerlauf(verlauf)
                dao.einfuegenAnlagen(anlagen)
            }
            val wurzel = anlagenSpeicher.wurzel
            wurzel.deleteRecursively()
            wurzel.parentFile?.mkdirs()
            if (!zwischenAnlagen.renameTo(wurzel)) {
                zwischenAnlagen.copyRecursively(wurzel, overwrite = true)
            }
            BackupErgebnis(faelle = daten.faelle.size, anlagen = anlagen.size)
        } finally {
            zwischen.deleteRecursively()
        }
    }

    /**
     * Lässt nur Einträge der Form `<fallId>/<dateiname>` zu. Alles andere – insbesondere
     * Pfade mit `..`, die aus dem Zielordner herausführen würden – wird übersprungen.
     */
    private fun sichererPfad(basis: File, relativ: String): File? {
        val teile = relativ.split('/')
        if (teile.size != 2) return null
        val (fallId, dateiname) = teile
        if (fallId.isEmpty() || !fallId.all(Char::isDigit) || !istSichererName(dateiname)) return null
        val ziel = File(File(basis, fallId), dateiname)
        return ziel.takeIf { it.canonicalPath.startsWith(basis.canonicalPath + File.separator) }
    }

    private fun istSichererName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 120 && name != "." && name != ".." &&
            name.all { it.isLetterOrDigit() || it in "._-" }

    companion object {
        const val FORMAT_VERSION = 1
        const val DATEN_DATEI = "daten.json"
        const val ANLAGEN_PRAEFIX = "anlagen/"

        const val KEIN_BACKUP = "Die gewählte Datei ist kein Backup dieser App."
        const val BESCHAEDIGT = "Das Backup ist beschädigt und kann nicht gelesen werden."
    }
}
