package de.aysetransport.schaden.cloud

import android.util.Base64
import androidx.room.withTransaction
import de.aysetransport.schaden.data.AnlagenSpeicher
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.Anlage
import de.aysetransport.schaden.data.model.Ansprechpartner
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Loeschung
import de.aysetransport.schaden.data.model.Schadensart
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag
import de.aysetransport.schaden.data.model.VerlaufTyp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Ergebnis eines Abgleichs. */
data class AbgleichErgebnis(val gesendet: Int, val empfangen: Int)

/**
 * Gleicht die Datenbank des Geräts mit der Cloud ab.
 *
 * Ablauf: Alle lokal geänderten Fälle, Verlaufseinträge, Anlagen und Löschungen gehen in einem
 * Aufruf an die Cloud; zurück kommt alles, was sich dort seit dem letzten Abgleich geändert hat.
 * Danach werden neue Anlagen-Dateien hoch- und fremde heruntergeladen.
 *
 * Regeln (wie im Cloud-Skript): Bei einem Fall gewinnt die spätere Änderung, Gelöschtes bleibt
 * gelöscht, Verlaufseinträge und Anlagen werden nur hinzugefügt oder gelöscht.
 */
class CloudAbgleich(
    private val db: SchadensfallDatenbank,
    private val anlagenSpeicher: AnlagenSpeicher,
    private val speicher: CloudSpeicher,
    private val uhr: Clock = Clock.systemDefaultZone(),
    private val apiFuer: (String) -> CloudApi = ::CloudApi,
) {
    private val dao = db.dao()
    private val sperre = Mutex()

    /** Prüft eine Adresse und meldet, ob dort schon eine PIN festgelegt ist. */
    suspend fun pruefeAdresse(url: String): Boolean {
        val antwort = apiFuer(url).aufruf("status").jsonObject
        if (antwort["name"]?.jsonPrimitive?.content != "Schadensfälle") {
            throw IOException("Die Adresse gehört nicht zum Schadensfälle-Cloud-Skript.")
        }
        return antwort["eingerichtet"]?.jsonPrimitive?.booleanOrNull == true
    }

    /** Legt bei Bedarf die PIN fest, prüft sie und speichert die Verbindung. */
    suspend fun verbinde(url: String, pin: String, neuePin: Boolean) {
        val api = apiFuer(url)
        if (neuePin) api.aufruf("pinFestlegen", JsonPrimitive(pin))
        api.aufruf("anmelden", JsonPrimitive(pin))
        speicher.verbinde(url, pin)
    }

    /**
     * Führt einen vollständigen Abgleich aus. Läuft bereits einer, wird gewartet.
     *
     * @throws IOException ohne Netz oder bei Störungen der Cloud – später erneut versuchen
     * @throws CloudFehler wenn die Cloud ablehnt (z. B. falsche PIN); die PIN wird dann vergessen
     */
    suspend fun abgleichen(): AbgleichErgebnis = sperre.withLock {
        var summe = AbgleichErgebnis(0, 0)
        speicher.aktuell().takeIf { it.angemeldet }?.let { pruefeVersion(it.url) }
        // Ein zweiter Durchlauf holt nach, was während des ersten geändert wurde.
        repeat(MAX_DURCHLAEUFE) {
            val z = speicher.aktuell()
            if (!z.angemeldet) return@withLock summe
            val ergebnis = try {
                durchlauf(z, apiFuer(z.url))
            } catch (e: CloudFehler) {
                if (e.pinFalsch && !e.gesperrt) speicher.pinVergessen("Die PIN ist nicht mehr gültig. Bitte neu eingeben.")
                else speicher.fehler(e.anzeige)
                throw e
            } catch (e: IOException) {
                speicher.fehler(e.message ?: "Keine Verbindung zur Cloud")
                throw e
            }
            summe = AbgleichErgebnis(summe.gesendet + ergebnis.gesendet, summe.empfangen + ergebnis.empfangen)
            if (!ergebnis.nochmal) return@withLock summe
        }
        summe
    }

    /**
     * Ein veraltetes Cloud-Skript würde Felder, die es nicht kennt, beim Speichern verwerfen und
     * den gekürzten Stand zurückschicken. Deshalb wird dann gar nicht erst abgeglichen.
     */
    private suspend fun pruefeVersion(url: String) {
        val version = try {
            apiFuer(url).aufruf("status").jsonObject["api"]?.jsonPrimitive?.longOrNull ?: 0
        } catch (e: IOException) {
            speicher.fehler(e.message ?: "Keine Verbindung zur Cloud")
            throw e
        }
        if (version < MIN_API) {
            speicher.fehler(VERALTET)
            throw CloudFehler(VERALTET)
        }
    }

    private class Durchlauf(val gesendet: Int, val empfangen: Int, val nochmal: Boolean)

    private suspend fun durchlauf(z: CloudZustand, api: CloudApi): Durchlauf {
        val faelle = dao.offeneFaelle()
        val verlauf = dao.offenerVerlauf()
        val anlagen = dao.offeneAnlagen()
        val auftraggeber = dao.offeneAuftraggeber()
        val loeschungen = dao.loeschungen()
        val fallKennung = (faelle.map { it.id to it.uuid } + dao.ladeAlle().map { it.id to it.uuid }).toMap()

        val anfrage = buildJsonObject {
            put("seit", z.seit)
            put("kennung", z.kennung)
            put("faelle", JsonArray(faelle.map { it.alsJson() } +
                loeschungen.filter { it.art == Loeschung.Art.FALL }.map { geloescht(it.uuid, it.fallUuid, it.zeitpunkt) }))
            put("verlauf", JsonArray(verlauf.mapNotNull { v -> fallKennung[v.fallId]?.let { v.alsJson(it) } } +
                loeschungen.filter { it.art == Loeschung.Art.VERLAUF }.map { geloescht(it.uuid, it.fallUuid, it.zeitpunkt) }))
            put("auftraggeber", JsonArray(auftraggeber.map { it.alsJson() } +
                loeschungen.filter { it.art == Loeschung.Art.AUFTRAGGEBER }.map { geloescht(it.uuid, it.uuid, it.zeitpunkt) }))
            put("anlagen", JsonArray(anlagen.mapNotNull { a -> fallKennung[a.fallId]?.let { a.alsJson(it) } } +
                loeschungen.filter { it.art == Loeschung.Art.ANLAGE }.map { geloescht(it.uuid, it.fallUuid, it.zeitpunkt) }))
        }
        val antwort = api.aufruf("sync", JsonPrimitive(z.pin), anfrage).jsonObject
        val kennung = antwort.text("kennung")
        val rev = antwort["rev"]?.jsonPrimitive?.longOrNull ?: 0

        val zuLoeschendeOrdner = mutableListOf<Long>()
        val zuLoeschendeDateien = mutableListOf<Anlage>()
        var empfangen = 0
        db.withTransaction {
            // Gesendetes gilt als angekommen, sofern es seit dem Absenden nicht erneut geändert wurde.
            faelle.forEach { dao.fallIstSynchron(it.uuid, it.geaendertAm.toEpochMilli()) }
            auftraggeber.forEach { dao.auftraggeberIstSynchron(it.uuid, it.geaendertAm.toEpochMilli()) }
            if (verlauf.isNotEmpty()) dao.verlaufIstSynchron(verlauf.map { it.uuid })
            if (anlagen.isNotEmpty()) dao.anlagenSindSynchron(anlagen.map { it.uuid })
            if (loeschungen.isNotEmpty()) dao.vergissLoeschungen(loeschungen.map { it.uuid })

            // Fälle zuerst: Verlauf und Anlagen verweisen auf sie.
            for (f in antwort.liste("faelle")) if (uebernimmFall(f, zuLoeschendeOrdner)) empfangen++
            for (v in antwort.liste("verlauf")) if (uebernimmVerlauf(v)) empfangen++
            for (a in antwort.liste("anlagen")) if (uebernimmAnlage(a, zuLoeschendeDateien)) empfangen++
            for (a in antwort.liste("auftraggeber")) if (uebernimmAuftraggeber(a)) empfangen++
        }
        zuLoeschendeOrdner.forEach { anlagenSpeicher.loescheOrdner(it) }
        zuLoeschendeDateien.forEach { anlagenSpeicher.loesche(it) }

        if (z.kennung.isNotEmpty() && kennung != z.kennung) {
            // Der Bestand in der Cloud wurde neu angelegt: alles von diesem Gerät erneut hochladen.
            db.withTransaction { dao.alleFaelleOffen(); dao.allerVerlaufOffen(); dao.alleAnlagenOffen(); dao.alleAuftraggeberOffen() }
            speicher.abgeglichen(kennung, 0, uhr.millis())
            return Durchlauf(faelle.size + verlauf.size + anlagen.size + auftraggeber.size, empfangen, nochmal = true)
        }
        speicher.abgeglichen(kennung, rev, uhr.millis())

        dateienHochladen(api, z.pin)
        empfangen += dateienHerunterladen(api, z.pin)

        val gesendet = faelle.size + verlauf.size + anlagen.size + auftraggeber.size + loeschungen.size
        val nochOffen = dao.offeneFaelle().isNotEmpty() || dao.offenerVerlauf().isNotEmpty() || dao.offeneAuftraggeber().isNotEmpty() ||
            dao.offeneAnlagen().isNotEmpty() || dao.loeschungen().isNotEmpty()
        return Durchlauf(gesendet, empfangen, nochmal = nochOffen && gesendet > 0)
    }

    // ------------------------------ Empfangenes übernehmen ------------------------------

    private suspend fun uebernimmFall(json: JsonObject, ordner: MutableList<Long>): Boolean {
        val uuid = json.text("id")
        val lokal = dao.fallPerKennung(uuid)
        if (json.wahr("geloescht")) {
            if (lokal == null) return false
            dao.loescheFall(lokal.id) // Verlauf und Anlagen-Einträge hängen per Fremdschlüssel daran
            ordner += lokal.id
            return true
        }
        if (lokal != null && lokal.syncOffen) return false // inzwischen hier geändert – geht beim nächsten Mal hoch
        val fall = Schadensfall(
            id = lokal?.id ?: 0,
            uuid = uuid,
            syncOffen = false,
            fallnummer = json.text("fallnummer"),
            kundeName = json.text("kundeName"),
            kundeFirma = json.text("kundeFirma"),
            kundeTelefon = json.text("kundeTelefon"),
            kundeEmail = json.text("kundeEmail"),
            kundeAnschrift = json.text("kundeAnschrift"),
            kundenAktenzeichen = json.text("kundenAktenzeichen"),
            auftraggeber = json.text("auftraggeber"),
            auftraggeberKontakt = json.text("auftraggeberKontakt"),
            auftraggeberTelefon = json.text("auftraggeberTelefon"),
            auftraggeberEmail = json.text("auftraggeberEmail"),
            auftraggeberZeichen = json.text("auftraggeberZeichen"),
            auftragsReferenz = json.text("auftragsReferenz"),
            tour = json.text("tour"),
            fahrzeug = json.text("fahrzeug"),
            fahrer = json.text("fahrer"),
            beifahrer1 = json.text("beifahrer1"),
            beifahrer2 = json.text("beifahrer2"),
            schadensort = json.text("schadensort"),
            schadensdatum = json.datum("schadensdatum"),
            meldedatum = json.datum("meldedatum"),
            schadensart = aufzaehlung(json.text("schadensart"), Schadensart.SONSTIGES),
            beschreibung = json.text("beschreibung"),
            forderungCent = json["forderungCent"]?.jsonPrimitive?.longOrNull,
            status = aufzaehlung(json.text("status"), FallStatus.NEU),
            wartetAuf = Ansprechpartner.entries.firstOrNull { it.name == json.text("wartetAuf") },
            wiedervorlageAm = json.datum("wiedervorlageAm"),
            wiedervorlageNotiz = json.text("wiedervorlageNotiz"),
            vergleichCent = json["vergleichCent"]?.jsonPrimitive?.longOrNull,
            zahlungsfrist = json.datum("zahlungsfrist"),
            versicherungsNummer = json.text("versicherungsNummer"),
            erstelltAm = json.zeitpunkt("erstelltAm"),
            geaendertAm = json.zeitpunkt("geaendertAm"),
        )
        if (lokal == null) dao.einfuegen(fall) else if (fall != lokal) dao.aktualisieren(fall) else return false
        return true
    }

    private suspend fun uebernimmVerlauf(json: JsonObject): Boolean {
        val uuid = json.text("id")
        val lokal = dao.verlaufPerKennung(uuid)
        if (json.wahr("geloescht")) {
            if (lokal == null) return false
            dao.loescheVerlaufPerKennung(uuid)
            return true
        }
        if (lokal != null) return false // Einträge ändern sich nicht
        val fall = dao.fallPerKennung(json.text("fallId")) ?: return false
        dao.einfuegen(
            VerlaufEintrag(
                uuid = uuid,
                syncOffen = false,
                fallId = fall.id,
                zeitpunkt = json.zeitpunkt("zeitpunkt"),
                typ = aufzaehlung(json.text("typ"), VerlaufTyp.NOTIZ),
                text = json.text("text"),
            ),
        )
        return true
    }

    private suspend fun uebernimmAnlage(json: JsonObject, dateien: MutableList<Anlage>): Boolean {
        val uuid = json.text("id")
        val lokal = dao.anlagePerKennung(uuid)
        if (json.wahr("geloescht")) {
            if (lokal == null) return false
            dao.loescheAnlagePerKennung(uuid)
            dateien += lokal
            return true
        }
        val inCloud = json.wahr("vorhanden")
        if (lokal != null) {
            if (inCloud && !lokal.inCloud) dao.anlageIstInCloud(uuid)
            return false
        }
        val fall = dao.fallPerKennung(json.text("fallId")) ?: return false
        dao.einfuegen(
            Anlage(
                uuid = uuid,
                syncOffen = false,
                inCloud = inCloud,
                fallId = fall.id,
                dateiname = "", // wird heruntergeladen, sobald die Datei in der Cloud liegt
                anzeigename = json.text("anzeigename"),
                mimeType = json.text("mimeType").ifEmpty { "application/octet-stream" },
                groesseBytes = json["groesseBytes"]?.jsonPrimitive?.longOrNull ?: 0,
                hinzugefuegtAm = json.zeitpunkt("hinzugefuegtAm"),
            ),
        )
        return true
    }

    private suspend fun uebernimmAuftraggeber(json: JsonObject): Boolean {
        val uuid = json.text("id")
        val lokal = dao.auftraggeberPerKennung(uuid)
        if (json.wahr("geloescht")) {
            if (lokal == null) return false
            dao.loescheAuftraggeber(uuid)
            return true
        }
        if (lokal != null && lokal.syncOffen) return false
        val eintrag = AuftraggeberEintrag(
            uuid = uuid,
            name = json.text("name"),
            kontakt = json.text("kontakt"),
            telefon = json.text("telefon"),
            email = json.text("email"),
            geaendertAm = json.zeitpunkt("geaendertAm"),
            syncOffen = false,
        )
        if (eintrag == lokal) return false
        dao.speichereAuftraggeber(eintrag)
        return true
    }

    // ------------------------------ Dateien ------------------------------

    private suspend fun dateienHochladen(api: CloudApi, pin: String) {
        for (anlage in dao.anlagenZumHochladen()) {
            val datei = anlagenSpeicher.datei(anlage)
            if (!datei.isFile) continue
            try {
                api.aufruf("anlageHochladen", JsonPrimitive(pin), JsonPrimitive(anlage.uuid), JsonPrimitive(Base64.encodeToString(datei.readBytes(), Base64.NO_WRAP)))
                dao.anlageIstInCloud(anlage.uuid)
            } catch (e: CloudFehler) {
                if (e.message == "Anlage unbekannt") continue // inzwischen gelöscht
                throw e
            }
        }
    }

    private suspend fun dateienHerunterladen(api: CloudApi, pin: String): Int {
        var anzahl = 0
        for (anlage in dao.anlagenZumHerunterladen()) {
            val antwort = try {
                api.aufruf("anlageLaden", JsonPrimitive(pin), JsonPrimitive(anlage.uuid)).jsonObject
            } catch (e: CloudFehler) {
                if (e.pinFalsch) throw e
                continue // inzwischen gelöscht oder noch nicht vollständig – beim nächsten Abgleich erneut
            }
            val bytes = Base64.decode(antwort.text("base64"), Base64.DEFAULT)
            val name = anlagenSpeicher.speichere(anlage.fallId, bytes, anlage.anzeigename, anlage.mimeType)
            dao.anlageHeruntergeladen(anlage.uuid, name, bytes.size.toLong())
            anzahl++
        }
        return anzahl
    }

    // ------------------------------ Umwandlung ------------------------------

    private fun Schadensfall.alsJson() = buildJsonObject {
        put("id", uuid)
        put("fallnummer", fallnummer)
        put("kundeName", kundeName)
        put("kundeFirma", kundeFirma)
        put("kundeTelefon", kundeTelefon)
        put("kundeEmail", kundeEmail)
        put("kundeAnschrift", kundeAnschrift)
        put("kundenAktenzeichen", kundenAktenzeichen)
        put("auftraggeber", auftraggeber)
        put("auftraggeberKontakt", auftraggeberKontakt)
        put("auftraggeberTelefon", auftraggeberTelefon)
        put("auftraggeberEmail", auftraggeberEmail)
        put("auftraggeberZeichen", auftraggeberZeichen)
        put("auftragsReferenz", auftragsReferenz)
        put("tour", tour)
        put("fahrzeug", fahrzeug)
        put("fahrer", fahrer)
        put("beifahrer1", beifahrer1)
        put("beifahrer2", beifahrer2)
        put("schadensort", schadensort)
        put("schadensdatum", CloudApi.text(schadensdatum?.toString()))
        put("meldedatum", CloudApi.text(meldedatum?.toString()))
        put("schadensart", schadensart.name)
        put("beschreibung", beschreibung)
        put("forderungCent", CloudApi.zahl(forderungCent))
        put("status", status.name)
        put("wartetAuf", CloudApi.text(wartetAuf?.name))
        put("wiedervorlageAm", CloudApi.text(wiedervorlageAm?.toString()))
        put("wiedervorlageNotiz", wiedervorlageNotiz)
        put("vergleichCent", CloudApi.zahl(vergleichCent))
        put("zahlungsfrist", CloudApi.text(zahlungsfrist?.toString()))
        put("versicherungsNummer", versicherungsNummer)
        put("erstelltAm", erstelltAm.toString())
        put("geaendertAm", geaendertAm.toString())
    }

    private fun AuftraggeberEintrag.alsJson() = buildJsonObject {
        put("id", uuid)
        put("name", name)
        put("kontakt", kontakt)
        put("telefon", telefon)
        put("email", email)
        put("geaendertAm", geaendertAm.toString())
    }

    private fun VerlaufEintrag.alsJson(fallUuid: String) = buildJsonObject {
        put("id", uuid)
        put("fallId", fallUuid)
        put("zeitpunkt", zeitpunkt.toString())
        put("typ", typ.name)
        put("text", text)
    }

    private fun Anlage.alsJson(fallUuid: String) = buildJsonObject {
        put("id", uuid)
        put("fallId", fallUuid)
        put("anzeigename", anzeigename)
        put("mimeType", mimeType)
        put("groesseBytes", groesseBytes)
        put("hinzugefuegtAm", hinzugefuegtAm.toString())
    }

    private fun geloescht(uuid: String, fallUuid: String, zeitpunkt: Instant) = buildJsonObject {
        put("id", uuid)
        put("fallId", fallUuid)
        put("geloescht", true)
        put("geaendertAm", zeitpunkt.toString())
    }

    private fun JsonObject.liste(name: String): List<JsonObject> =
        (this[name] as? JsonArray)?.map { it.jsonObject }.orEmpty()

    private fun JsonObject.text(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()

    private fun JsonObject.wahr(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.datum(name: String): LocalDate? =
        text(name).takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun JsonObject.zeitpunkt(name: String): Instant =
        runCatching { Instant.parse(text(name)) }.getOrElse { uhr.instant() }

    private inline fun <reified T : Enum<T>> aufzaehlung(name: String, standard: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: standard

    companion object {
        private const val MAX_DURCHLAEUFE = 3
        const val MIN_API = 3L
        const val VERALTET = "Das Cloud-Skript ist veraltet – bitte die neue Code.gs bereitstellen."
    }
}
