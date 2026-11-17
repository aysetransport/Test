package de.aysetransport.schaden.ui.crm

import androidx.compose.runtime.Composable
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
import de.aysetransport.schaden.domain.Geld
import de.aysetransport.schaden.ui.komponenten.Feld
import de.aysetransport.schaden.ui.komponenten.Formulardialog
import de.aysetransport.schaden.ui.komponenten.Textart
import java.time.LocalDate

/** Vorschläge für die Zuständigkeit eines Ansprechpartners. */
val THEMEN = listOf("Disposition", "Abrechnung", "Schäden", "Geschäftsführung", "Einkauf", "Lager", "Sonstiges")

/** Vorschläge für Preise und Konditionen. */
val PREIS_BEZEICHNUNGEN = listOf("Tourpauschale", "Montage", "Extra-km", "Etage", "Maut", "Wartezeit", "Entsorgung")

private fun datumText(datum: LocalDate?) = datum?.toString().orEmpty()
private fun datum(text: String?): LocalDate? = text?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

@Composable
fun FirmaDialog(firma: AuftraggeberEintrag?, onSchliessen: () -> Unit, onSpeichern: (AuftraggeberEintrag) -> Unit, onLoeschen: (() -> Unit)? = null) {
    val f = firma ?: AuftraggeberEintrag(name = "")
    Formulardialog(
        titel = if (firma == null) "Neue Firma" else "Stammdaten bearbeiten",
        felder = listOf(
            Feld.Text("name", "Firma", Textart.NAME, pflicht = true),
            Feld.Text("rechtsform", "Rechtsform (z. B. GmbH)", Textart.NAME),
            Feld.Auswahl("status", "Status", FirmenStatus.entries.map { it.name to it.label }),
            Feld.Text("telefon", "Telefon", Textart.TELEFON),
            Feld.Text("email", "E-Mail", Textart.EMAIL, hinweis = "Mails von dieser Adresse oder Domain werden der Firma zugeordnet."),
            Feld.Text("website", "Website", Textart.LINK),
            Feld.Text("notizen", "Notizen", Textart.MEHRZEILIG),
        ),
        werte = mapOf(
            "name" to f.name, "rechtsform" to f.rechtsform, "status" to f.status.name, "telefon" to f.telefon,
            "email" to f.email, "website" to f.website, "notizen" to f.notizen,
        ),
        onSchliessen = onSchliessen,
        onSpeichern = { w ->
            onSpeichern(
                f.copy(
                    name = w["name"].orEmpty(), rechtsform = w["rechtsform"].orEmpty(),
                    status = FirmenStatus.entries.firstOrNull { it.name == w["status"] } ?: FirmenStatus.AKTIV,
                    telefon = w["telefon"].orEmpty(), email = w["email"].orEmpty(), website = w["website"].orEmpty(), notizen = w["notizen"].orEmpty(),
                ),
            )
        },
        onLoeschen = onLoeschen,
        loeschFrage = "„${f.name}“ mit Ansprechpartnern, Adressen, Preisen, Kontakthistorie und Dokumenten löschen? Schadensfälle bleiben erhalten.",
    )
}

@Composable
fun KontaktDialog(firma: String, kontakt: Kontaktperson?, onSchliessen: () -> Unit, onSpeichern: (Kontaktperson) -> Unit, onLoeschen: (() -> Unit)?) {
    val k = kontakt ?: Kontaktperson(auftraggeberUuid = firma, name = "")
    Formulardialog(
        titel = if (kontakt == null) "Neuer Ansprechpartner" else "Ansprechpartner",
        felder = listOf(
            Feld.Text("name", "Name", Textart.NAME, pflicht = true),
            Feld.Text("thema", "Zuständig für", hinweis = THEMEN.joinToString(", ")),
            Feld.Text("telefon", "Telefon", Textart.TELEFON),
            Feld.Text("email", "E-Mail", Textart.EMAIL),
            Feld.Text("notiz", "Notiz", Textart.MEHRZEILIG),
        ),
        werte = mapOf("name" to k.name, "thema" to k.thema, "telefon" to k.telefon, "email" to k.email, "notiz" to k.notiz),
        onSchliessen = onSchliessen,
        onSpeichern = { w -> onSpeichern(k.copy(name = w["name"].orEmpty(), thema = w["thema"].orEmpty(), telefon = w["telefon"].orEmpty(), email = w["email"].orEmpty(), notiz = w["notiz"].orEmpty())) },
        onLoeschen = onLoeschen,
        loeschFrage = "Ansprechpartner „${k.name}“ löschen?",
    )
}

@Composable
fun AdresseDialog(firma: String, adresse: Adresse?, onSchliessen: () -> Unit, onSpeichern: (Adresse) -> Unit, onLoeschen: (() -> Unit)?) {
    val a = adresse ?: Adresse(auftraggeberUuid = firma)
    Formulardialog(
        titel = if (adresse == null) "Neue Adresse" else "Adresse",
        felder = listOf(
            Feld.Auswahl("art", "Art", AdressArt.entries.map { it.name to it.label }),
            Feld.Text("strasse", "Straße und Hausnummer", Textart.NAME),
            Feld.Text("plz", "PLZ", Textart.TELEFON),
            Feld.Text("ort", "Ort", Textart.NAME, pflicht = true),
            Feld.Text("notiz", "Notiz (z. B. Rampe, Öffnungszeiten)", Textart.MEHRZEILIG),
        ),
        werte = mapOf("art" to a.art.name, "strasse" to a.strasse, "plz" to a.plz, "ort" to a.ort, "notiz" to a.notiz),
        onSchliessen = onSchliessen,
        onSpeichern = { w ->
            onSpeichern(
                a.copy(
                    art = AdressArt.entries.firstOrNull { it.name == w["art"] } ?: AdressArt.SONSTIGE,
                    strasse = w["strasse"].orEmpty(), plz = w["plz"].orEmpty(), ort = w["ort"].orEmpty(), notiz = w["notiz"].orEmpty(),
                ),
            )
        },
        onLoeschen = onLoeschen,
        loeschFrage = "Adresse löschen?",
    )
}

@Composable
fun PreisDialog(firma: String, preis: Preis?, onSchliessen: () -> Unit, onSpeichern: (Preis) -> Unit, onLoeschen: (() -> Unit)?) {
    val p = preis ?: Preis(auftraggeberUuid = firma, bezeichnung = "")
    Formulardialog(
        titel = if (preis == null) "Neuer Preis" else "Preis",
        felder = listOf(
            Feld.Text("bezeichnung", "Bezeichnung", pflicht = true, hinweis = PREIS_BEZEICHNUNGEN.joinToString(", ")),
            Feld.Text("betrag", "Betrag in Euro", Textart.BETRAG),
            Feld.Text("einheit", "Einheit (z. B. pro Tour, pro km)"),
            Feld.Datum("gueltigAb", "Gültig ab"),
            Feld.Text("notiz", "Notiz", Textart.MEHRZEILIG),
        ),
        werte = mapOf(
            "bezeichnung" to p.bezeichnung, "betrag" to p.betragCent?.let(Geld::formatOhneSymbol).orEmpty(), "einheit" to p.einheit,
            "gueltigAb" to datumText(p.gueltigAb), "notiz" to p.notiz,
        ),
        onSchliessen = onSchliessen,
        onSpeichern = { w ->
            onSpeichern(
                p.copy(
                    bezeichnung = w["bezeichnung"].orEmpty(), betragCent = w["betrag"].orEmpty().takeIf { it.isNotEmpty() }?.let(Geld::parse),
                    einheit = w["einheit"].orEmpty(), gueltigAb = datum(w["gueltigAb"]), notiz = w["notiz"].orEmpty(),
                ),
            )
        },
        onLoeschen = onLoeschen,
        loeschFrage = "Preis „${p.bezeichnung}“ löschen?",
    )
}

@Composable
fun AktivitaetDialog(firma: String, aktivitaet: Aktivitaet?, kontakte: List<Kontaktperson>, heute: LocalDate, onSchliessen: () -> Unit, onSpeichern: (Aktivitaet) -> Unit, onLoeschen: (() -> Unit)?) {
    val a = aktivitaet ?: Aktivitaet(auftraggeberUuid = firma, datum = heute, art = AktivitaetArt.ANRUF)
    Formulardialog(
        titel = if (aktivitaet == null) "Kontakt festhalten" else "Kontakt",
        felder = listOf(
            Feld.Datum("datum", "Datum"),
            Feld.Auswahl("art", "Art", AktivitaetArt.entries.map { it.name to it.label }),
            kontaktFeld(kontakte, a.kontakt),
            Feld.Text("text", "Was wurde besprochen?", Textart.MEHRZEILIG, pflicht = true),
        ),
        werte = mapOf("datum" to a.datum.toString(), "art" to a.art.name, "kontakt" to a.kontakt, "text" to a.text),
        onSchliessen = onSchliessen,
        onSpeichern = { w ->
            onSpeichern(a.copy(datum = datum(w["datum"]) ?: heute, art = AktivitaetArt.entries.firstOrNull { it.name == w["art"] } ?: AktivitaetArt.SONSTIGES, kontakt = w["kontakt"].orEmpty(), text = w["text"].orEmpty()))
        },
        onLoeschen = onLoeschen,
        loeschFrage = "Eintrag aus der Kontakthistorie löschen?",
    )
}

@Composable
fun AufgabeDialog(firma: String, aufgabe: Aufgabe?, kontakte: List<Kontaktperson>, heute: LocalDate, onSchliessen: () -> Unit, onSpeichern: (Aufgabe) -> Unit, onLoeschen: (() -> Unit)?) {
    val a = aufgabe ?: Aufgabe(auftraggeberUuid = firma, text = "", faelligAm = heute.plusDays(7))
    Formulardialog(
        titel = if (aufgabe == null) "Neue Wiedervorlage" else "Wiedervorlage",
        felder = listOf(
            Feld.Text("text", "Was ist zu tun?", Textart.MEHRZEILIG, pflicht = true),
            Feld.Datum("faelligAm", "Fällig am"),
            kontaktFeld(kontakte, a.kontakt),
        ),
        werte = mapOf("text" to a.text, "faelligAm" to datumText(a.faelligAm), "kontakt" to a.kontakt),
        onSchliessen = onSchliessen,
        onSpeichern = { w -> onSpeichern(a.copy(text = w["text"].orEmpty(), faelligAm = datum(w["faelligAm"]), kontakt = w["kontakt"].orEmpty())) },
        onLoeschen = onLoeschen,
        loeschFrage = "Wiedervorlage löschen?",
    )
}

@Composable
fun DokumentDialog(firma: String, dokument: Dokument?, onSchliessen: () -> Unit, onSpeichern: (Dokument) -> Unit, onLoeschen: (() -> Unit)?) {
    val d = dokument ?: Dokument(auftraggeberUuid = firma, titel = "")
    Formulardialog(
        titel = if (dokument == null) "Neues Dokument" else "Dokument",
        felder = listOf(
            Feld.Text("titel", "Titel (z. B. Rahmenvertrag 2026)", pflicht = true),
            Feld.Text("link", "Link oder Pfad", Textart.LINK, hinweis = "z. B. Google-Drive-Link oder \\\\server\\ordner\\datei.pdf"),
            Feld.Text("notiz", "Notiz", Textart.MEHRZEILIG),
        ),
        werte = mapOf("titel" to d.titel, "link" to d.link, "notiz" to d.notiz),
        onSchliessen = onSchliessen,
        onSpeichern = { w -> onSpeichern(d.copy(titel = w["titel"].orEmpty(), link = w["link"].orEmpty(), notiz = w["notiz"].orEmpty())) },
        onLoeschen = onLoeschen,
        loeschFrage = "Dokument „${d.titel}“ löschen? Die Datei selbst bleibt unberührt.",
    )
}

/** Ansprechpartner als Auswahl, wenn es welche gibt – sonst als freies Feld. */
private fun kontaktFeld(kontakte: List<Kontaktperson>, aktuell: String): Feld =
    if (kontakte.isEmpty()) {
        Feld.Text("kontakt", "Ansprechpartner", Textart.NAME)
    } else {
        val namen = (kontakte.map { it.name } + aktuell).filter { it.isNotBlank() }.distinct()
        Feld.Auswahl("kontakt", "Ansprechpartner", listOf("" to "– keiner –") + namen.map { it to it })
    }
