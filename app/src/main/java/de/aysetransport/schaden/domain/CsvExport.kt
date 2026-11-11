package de.aysetransport.schaden.domain

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
