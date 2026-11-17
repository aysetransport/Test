package de.aysetransport.schaden.data

import androidx.room.withTransaction
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.AdressArt
import de.aysetransport.schaden.data.model.Adresse
import de.aysetransport.schaden.data.model.Aktivitaet
import de.aysetransport.schaden.data.model.AktivitaetArt
import de.aysetransport.schaden.data.model.Aufgabe
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.Dokument
import de.aysetransport.schaden.data.model.FirmenStatus
import de.aysetransport.schaden.data.model.Kontaktperson
import de.aysetransport.schaden.data.model.Preis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate

data class ImportErgebnis(val neueFirmen: Int, val zusammengefuehrt: Int, val eintraege: Int)

class ImportUngueltigException(message: String) : IOException(message)

/**
 * Liest die JSON-Sicherung des bisherigen Browser-CRM (`{firmen: [...]}`) ein.
 *
 * Firmen mit gleichem Namen werden zusammengeführt statt doppelt angelegt: leere Felder werden
 * ergänzt, Notizen angehängt. Einträge (Ansprechpartner, Adressen, Preise …), die es bei der Firma
 * schon gibt, kommen nicht doppelt hinzu – der Import lässt sich also gefahrlos wiederholen.
 */
class CrmImport(private val db: SchadensfallDatenbank, private val uhr: Clock = Clock.systemDefaultZone(), private val nachAenderung: () -> Unit = {}) {
    private val dao = db.dao()
    private val crm = db.crm()

    suspend fun importiere(text: String): ImportErgebnis {
        val wurzel = try {
            Json.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw ImportUngueltigException(KEINE_SICHERUNG)
        val firmen = (wurzel["firmen"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: throw ImportUngueltigException(KEINE_SICHERUNG)

        var neu = 0
        var zusammen = 0
        var eintraege = 0
        db.withTransaction {
            val jetzt = uhr.instant()
            for (f in firmen) {
                val name = f.text("name")
                if (name.isEmpty()) continue
                val vorhanden = dao.ladeAlleAuftraggeber().filter { CrmVerknuepfer.normal(it.name) == CrmVerknuepfer.normal(name) }.minByOrNull { it.uuid }
                val firma = if (vorhanden == null) {
                    neu++
                    AuftraggeberEintrag(
                        name = name, rechtsform = f.text("rechtsform"), status = status(f.text("status")), telefon = f.text("telefon"),
                        email = f.text("email"), website = f.text("website"), notizen = f.text("notizen"), geaendertAm = jetzt,
                    ).also { dao.speichereAuftraggeber(it) }
                } else {
                    zusammen++
                    val notiz = f.text("notizen")
                    val ergaenzt = vorhanden.copy(
                        rechtsform = vorhanden.rechtsform.ifBlank { f.text("rechtsform") },
                        telefon = vorhanden.telefon.ifBlank { f.text("telefon") },
                        email = vorhanden.email.ifBlank { f.text("email") },
                        website = vorhanden.website.ifBlank { f.text("website") },
                        notizen = if (notiz.isEmpty() || vorhanden.notizen.contains(notiz)) vorhanden.notizen else listOf(vorhanden.notizen, notiz).filter { it.isNotBlank() }.joinToString("\n\n"),
                        // „Aktiv“ ist die Vorgabe der App – ein anderer Status aus dem Browser-CRM ist aussagekräftiger.
                        status = if (vorhanden.status == FirmenStatus.AKTIV && f.text("status").isNotEmpty()) status(f.text("status")) else vorhanden.status,
                    )
                    if (ergaenzt != vorhanden) dao.speichereAuftraggeber(ergaenzt.copy(geaendertAm = jetzt, syncOffen = true))
                    vorhanden
                }
                val id = firma.uuid

                val kontakte = crm.ladeKontaktpersonen(id)
                for (k in f.liste("kontakte")) {
                    val kName = k.text("name").ifEmpty { k.text("email") }.ifEmpty { continue }
                    if (kontakte.any { CrmVerknuepfer.normal(it.name) == CrmVerknuepfer.normal(kName) }) continue
                    crm.speichere(Kontaktperson(auftraggeberUuid = id, name = kName, thema = k.text("thema"), telefon = k.text("telefon"), email = k.text("email"), notiz = k.text("notiz"), geaendertAm = jetzt))
                    eintraege++
                }
                val adressen = crm.ladeAdressen(id)
                for (a in f.liste("adressen")) {
                    val strasse = a.text("strasse"); val ort = a.text("ort"); val plz = a.text("plz")
                    if (strasse.isEmpty() && ort.isEmpty() && plz.isEmpty()) continue
                    if (adressen.any { CrmVerknuepfer.normal(it.strasse) == CrmVerknuepfer.normal(strasse) && CrmVerknuepfer.normal(it.ort) == CrmVerknuepfer.normal(ort) }) continue
                    crm.speichere(Adresse(auftraggeberUuid = id, art = adressArt(a.text("art")), strasse = strasse, plz = plz, ort = ort, notiz = a.text("notiz"), geaendertAm = jetzt))
                    eintraege++
                }
                val preise = crm.ladePreise(id)
                for (p in f.liste("preise")) {
                    val bez = p.text("bez").ifEmpty { p.text("bezeichnung") }.ifEmpty { continue }
                    val ab = datum(p.text("ab"))
                    val cent = euroZuCent(p["betrag"])
                    if (preise.any { CrmVerknuepfer.normal(it.bezeichnung) == CrmVerknuepfer.normal(bez) && it.gueltigAb == ab && it.betragCent == cent }) continue
                    crm.speichere(Preis(auftraggeberUuid = id, bezeichnung = bez, betragCent = cent, einheit = p.text("einheit"), gueltigAb = ab, notiz = p.text("notiz"), geaendertAm = jetzt))
                    eintraege++
                }
                val aktivitaeten = crm.ladeAktivitaeten(id)
                for (a in f.liste("aktivitaeten")) {
                    val text = a.text("text")
                    val tag = datum(a.text("datum")) ?: LocalDate.now(uhr)
                    if (text.isEmpty()) continue
                    if (aktivitaeten.any { it.datum == tag && it.text == text }) continue
                    crm.speichere(Aktivitaet(auftraggeberUuid = id, datum = tag, art = aktivitaetArt(a.text("art")), text = text, kontakt = a.text("kontakt"), geaendertAm = jetzt))
                    eintraege++
                }
                val aufgaben = crm.ladeAufgaben(id)
                for (a in f.liste("aufgaben")) {
                    val text = a.text("text").ifEmpty { continue }
                    val faellig = datum(a.text("faellig"))
                    if (aufgaben.any { it.text == text && it.faelligAm == faellig }) continue
                    crm.speichere(Aufgabe(auftraggeberUuid = id, faelligAm = faellig, text = text, kontakt = a.text("kontakt"), erledigt = (a["erledigt"] as? JsonPrimitive)?.booleanOrNull == true, geaendertAm = jetzt))
                    eintraege++
                }
                val dokumente = crm.ladeDokumente(id)
                for (d in f.liste("dokumente")) {
                    val titel = d.text("titel").ifEmpty { d.text("link") }.ifEmpty { continue }
                    val link = d.text("link")
                    if (dokumente.any { it.titel == titel && it.link == link }) continue
                    crm.speichere(Dokument(auftraggeberUuid = id, titel = titel, link = link, notiz = d.text("notiz"), geaendertAm = jetzt))
                    eintraege++
                }
            }
            // Fälle, deren Auftraggeber jetzt als Firma existiert, verbinden.
            CrmVerknuepfer(db, uhr).verknuepfeAlle()
        }
        nachAenderung()
        return ImportErgebnis(neu, zusammen, eintraege)
    }

    private fun JsonObject.text(name: String): String = when (val w = this[name]) {
        null, JsonNull -> ""
        is JsonPrimitive -> w.content.trim()
        else -> ""
    }

    private fun JsonObject.liste(name: String): List<JsonObject> = (this[name] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun datum(text: String): LocalDate? = runCatching { LocalDate.parse(text.take(10)) }.getOrNull()

    /** Betrag in Euro (Zahl oder Text mit Komma) → Cent. */
    private fun euroZuCent(wert: kotlinx.serialization.json.JsonElement?): Long? {
        val p = wert as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        p.doubleOrNull?.let { if (p.isString.not()) return BigDecimal(p.content).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong() }
        return de.aysetransport.schaden.domain.Geld.parse(p.content)
    }

    private fun status(text: String): FirmenStatus = when (text.trim().lowercase()) {
        "interessent", "lead", "potenziell" -> FirmenStatus.INTERESSENT
        "pausiert", "ruhend" -> FirmenStatus.PAUSIERT
        "ehemalig", "inaktiv", "verloren" -> FirmenStatus.EHEMALIG
        else -> FirmenStatus.entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) } ?: FirmenStatus.AKTIV
    }

    private fun adressArt(text: String): AdressArt {
        val t = text.trim().lowercase()
        return when {
            t.startsWith("haupt") || t == "sitz" -> AdressArt.HAUPTSITZ
            t.startsWith("lager") -> AdressArt.LAGER
            t.startsWith("filial") || t.startsWith("markt") -> AdressArt.FILIALE
            t.startsWith("rechnung") -> AdressArt.RECHNUNG
            else -> AdressArt.entries.firstOrNull { it.name.equals(t, ignoreCase = true) } ?: AdressArt.SONSTIGE
        }
    }

    private fun aktivitaetArt(text: String): AktivitaetArt {
        val t = text.trim().lowercase()
        return when {
            t.startsWith("anruf") || t.startsWith("telefon") -> AktivitaetArt.ANRUF
            t.contains("mail") -> AktivitaetArt.EMAIL
            t.startsWith("treffen") || t.startsWith("termin") || t.startsWith("besuch") -> AktivitaetArt.TREFFEN
            t.startsWith("angebot") -> AktivitaetArt.ANGEBOT
            else -> AktivitaetArt.entries.firstOrNull { it.name.equals(t, ignoreCase = true) } ?: AktivitaetArt.SONSTIGES
        }
    }

    companion object {
        const val KEINE_SICHERUNG = "Die Datei ist keine Sicherung des Browser-CRM (es fehlt die Liste „firmen“)."
    }
}
