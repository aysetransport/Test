package de.aysetransport.schaden.domain

import de.aysetransport.schaden.data.model.AdressArt
import de.aysetransport.schaden.data.model.Adresse
import de.aysetransport.schaden.data.model.Aktivitaet
import de.aysetransport.schaden.data.model.Aufgabe
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.Kontaktperson
import de.aysetransport.schaden.data.model.Preis
import de.aysetransport.schaden.data.model.Schadensfall
import java.time.ZoneId

/**
 * Exportiert die Fallliste als CSV, die Excel in deutscher Einstellung direkt öffnet
 * (Semikolon als Trennzeichen, Komma als Dezimalzeichen, UTF-8 mit BOM).
 */
object CsvExport {

    private const val TRENNER = ";"
    private const val ZEILENENDE = "\r\n"
    private const val BOM = "\uFEFF"

    val KOPFZEILE = listOf(
        "Fall-Nr.", "Status", "Tour", "Fahrzeug", "Fahrer", "Beifahrer 1", "Beifahrer 2", "Schadensdatum", "Gemeldet am",
        "Geschädigter", "Firma Geschädigter", "Telefon Geschädigter", "E-Mail Geschädigter", "Anschrift Geschädigter", "Zeichen des Geschädigten",
        "Auftraggeber", "Ansprechpartner Auftraggeber", "Telefon Auftraggeber", "E-Mail Auftraggeber", "Zeichen Auftraggeber", "Auftrags-Nr.", "Schadensart", "Schadensbeschreibung", "Schadensort",
        "Forderung (EUR)", "Vergleich (EUR)", "Zahlungsfrist", "Schadennr. Versicherung",
        "Wartet auf", "Wiedervorlage", "Nächster Schritt", "Anlagen", "Angelegt am", "Geändert am",
    )

    fun erstelle(
        faelle: List<Schadensfall>,
        anlagenJeFall: Map<Long, Int> = emptyMap(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        append(BOM)
        append(KOPFZEILE.joinToString(TRENNER) { zelle(it) }).append(ZEILENENDE)
        for (fall in faelle.sortedBy { it.fallnummer }) {
            val spalten = listOf(
                fall.fallnummer,
                fall.status.label,
                fall.tour,
                fall.fahrzeug,
                fall.fahrer,
                fall.beifahrer1,
                fall.beifahrer2,
                fall.schadensdatum?.let(Datum::format).orEmpty(),
                fall.meldedatum?.let(Datum::format).orEmpty(),
                fall.kundeName,
                fall.kundeFirma,
                fall.kundeTelefon,
                fall.kundeEmail,
                fall.kundeAnschrift,
                fall.kundenAktenzeichen,
                fall.auftraggeber,
                fall.auftraggeberKontakt,
                fall.auftraggeberTelefon,
                fall.auftraggeberEmail,
                fall.auftraggeberZeichen,
                fall.auftragsReferenz,
                fall.schadensart.label,
                fall.beschreibung,
                fall.schadensort,
                fall.forderungCent?.let(Geld::formatOhneSymbol).orEmpty(),
                fall.vergleichCent?.let(Geld::formatOhneSymbol).orEmpty(),
                fall.zahlungsfrist?.let(Datum::format).orEmpty(),
                fall.versicherungsNummer,
                fall.wartetAuf?.label.orEmpty(),
                fall.wiedervorlageAm?.let(Datum::format).orEmpty(),
                fall.wiedervorlageNotiz,
                (anlagenJeFall[fall.id] ?: 0).toString(),
                Datum.format(fall.erstelltAm.atZone(zone).toLocalDate()),
                Datum.format(fall.geaendertAm.atZone(zone).toLocalDate()),
            )
            append(spalten.joinToString(TRENNER) { zelle(it) }).append(ZEILENENDE)
        }
    }

    /** Eine beliebige Tabelle im selben Format (Semikolon, BOM, CRLF). */
    fun tabelle(kopf: List<String>, zeilen: List<List<String>>): String = buildString {
        append(BOM)
        append(kopf.joinToString(TRENNER) { zelle(it) }).append(ZEILENENDE)
        for (z in zeilen) append(z.joinToString(TRENNER) { zelle(it) }).append(ZEILENENDE)
    }

    private fun firmenName(firmen: Map<String, AuftraggeberEintrag>, uuid: String) = firmen[uuid]?.name.orEmpty()

    fun firmen(firmen: List<AuftraggeberEintrag>, adressen: List<Adresse>): String = tabelle(
        listOf("Firma", "Rechtsform", "Status", "Telefon", "E-Mail", "Website", "Hauptsitz", "Notizen", "Schlagworte"),
        firmen.sortedBy { it.name.lowercase() }.map { f ->
            val sitz = adressen.filter { it.auftraggeberUuid == f.uuid }.let { l -> l.firstOrNull { it.art == AdressArt.HAUPTSITZ } ?: l.firstOrNull() }
            listOf(f.name, f.rechtsform, f.status.label, f.telefon, f.email, f.website, sitz?.einzeilig.orEmpty(), f.notizen, f.schlagworte)
        },
    )

    fun ansprechpartner(firmen: List<AuftraggeberEintrag>, kontakte: List<Kontaktperson>): String {
        val nachKennung = firmen.associateBy { it.uuid }
        return tabelle(
            listOf("Firma", "Name", "Zuständigkeit", "Telefon", "E-Mail", "Notiz"),
            kontakte.sortedWith(compareBy({ firmenName(nachKennung, it.auftraggeberUuid).lowercase() }, { it.name.lowercase() }))
                .map { listOf(firmenName(nachKennung, it.auftraggeberUuid), it.name, it.thema, it.telefon, it.email, it.notiz) },
        )
    }

    fun preise(firmen: List<AuftraggeberEintrag>, preise: List<Preis>): String {
        val nachKennung = firmen.associateBy { it.uuid }
        return tabelle(
            listOf("Firma", "Bezeichnung", "Betrag (EUR)", "Einheit", "Gültig ab", "Notiz"),
            preise.sortedWith(compareBy({ firmenName(nachKennung, it.auftraggeberUuid).lowercase() }, { it.bezeichnung.lowercase() }))
                .map {
                    listOf(
                        firmenName(nachKennung, it.auftraggeberUuid), it.bezeichnung, it.betragCent?.let(Geld::formatOhneSymbol).orEmpty(),
                        it.einheit, it.gueltigAb?.let(Datum::format).orEmpty(), it.notiz,
                    )
                },
        )
    }

    fun aktivitaeten(firmen: List<AuftraggeberEintrag>, aktivitaeten: List<Aktivitaet>): String {
        val nachKennung = firmen.associateBy { it.uuid }
        return tabelle(
            listOf("Datum", "Firma", "Art", "Ansprechpartner", "Text"),
            aktivitaeten.sortedByDescending { it.datum }
                .map { listOf(Datum.format(it.datum), firmenName(nachKennung, it.auftraggeberUuid), it.art.label, it.kontakt, it.text) },
        )
    }

    fun wiedervorlagen(firmen: List<AuftraggeberEintrag>, aufgaben: List<Aufgabe>): String {
        val nachKennung = firmen.associateBy { it.uuid }
        return tabelle(
            listOf("Fällig am", "Firma", "Aufgabe", "Ansprechpartner", "Erledigt"),
            aufgaben.sortedWith(compareBy({ it.erledigt }, { it.faelligAm ?: java.time.LocalDate.MAX }))
                .map { listOf(it.faelligAm?.let(Datum::format).orEmpty(), firmenName(nachKennung, it.auftraggeberUuid), it.text, it.kontakt, if (it.erledigt) "ja" else "nein") },
        )
    }

    fun chancen(firmen: List<AuftraggeberEintrag>, chancen: List<de.aysetransport.schaden.data.model.Chance>): String {
        val nachKennung = firmen.associateBy { it.uuid }
        return tabelle(
            listOf("Firma", "Verkaufschance", "Phase", "Wert (EUR)", "Entscheidung bis", "Notiz"),
            chancen.sortedWith(compareBy({ it.phase.ordinal }, { firmenName(nachKennung, it.auftraggeberUuid).lowercase() }))
                .map { listOf(firmenName(nachKennung, it.auftraggeberUuid), it.titel, it.phase.label, it.wertCent?.let(Geld::formatOhneSymbol).orEmpty(), it.erwartetAm?.let(Datum::format).orEmpty(), it.notiz) },
        )
    }

    /** Maskiert eine Zelle und entschärft Inhalte, die Excel sonst als Formel ausführen würde. */
    internal fun zelle(wert: String): String {
        val entschaerft = if (wirktWieFormel(wert)) "'$wert" else wert
        val mussInAnfuehrungszeichen = entschaerft.any { it == ';' || it == '"' || it == '\n' || it == '\r' }
        return if (mussInAnfuehrungszeichen) "\"" + entschaerft.replace("\"", "\"\"") + "\"" else entschaerft
    }

    /**
     * Freitext stammt oft aus Kunden-E-Mails. Beginnt er mit `=`, `@` oder einem
     * Steuerzeichen, könnte Excel ihn als Formel auswerten. `+` und `-` am Anfang sind bei
     * Telefonnummern und Beträgen normal und nur dann verdächtig, wenn mehr als Ziffern folgt.
     */
    private fun wirktWieFormel(wert: String): Boolean {
        val erstes = wert.firstOrNull() ?: return false
        return when (erstes) {
            '=', '@', '\t', '\r' -> true
            '+', '-' -> wert.drop(1).any { !(it.isDigit() || it in " /().,-") }
            else -> false
        }
    }
}
