package de.aysetransport.schaden.domain

import de.aysetransport.schaden.data.model.Schadensfall

/**
 * Kompakter Textblock eines Falls zum Teilen oder Einfügen in Notizen und die Falltabelle.
 * Fehlende Angaben werden als „unbekannt“ ausgewiesen statt weggelassen – so fällt auf,
 * was noch zu klären ist.
 */
object Zusammenfassung {

    private const val UNBEKANNT = "unbekannt"

    fun erstelle(fall: Schadensfall, anzahlAnlagen: Int): String {
        val kunde = listOf(fall.kundeName, fall.kundeFirma).filter { it.isNotBlank() }.joinToString(", ")
        val kontakt = listOf(fall.kundeTelefon, fall.kundeEmail).filter { it.isNotBlank() }.joinToString(" · ")
        val tour = buildString {
            append(listOf(fall.tour, fall.fahrzeug).filter { it.isNotBlank() }.joinToString(" / "))
            val besatzung = listOfNotNull(
                fall.fahrer.takeIf { it.isNotBlank() }?.let { "Fahrer: ${it.trim()}" },
                beifahrerText(fall)?.let { "Beifahrer: $it" },
            )
            if (besatzung.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("(${besatzung.joinToString(", ")})")
            }
        }
        val auftrag = listOf(fall.auftraggeber, fall.auftragsReferenz).filter { it.isNotBlank() }.joinToString(" · ")

        return buildString {
            appendLine("Schadensfall ${fall.fallnummer}")
            appendLine("Status: ${fall.status.label}")
            appendLine("Geschädigter: ${kunde.oderUnbekannt()}")
            appendLine("Kontakt Geschädigter: ${kontakt.oderUnbekannt()}")
            if (fall.kundenAktenzeichen.isNotBlank()) appendLine("Zeichen des Geschädigten: ${fall.kundenAktenzeichen.trim()}")
            appendLine("Schadensdatum: ${fall.schadensdatum?.let(Datum::format) ?: UNBEKANNT}")
            appendLine("Tour/Fahrzeug: ${tour.oderUnbekannt()}")
            if (auftrag.isNotBlank()) appendLine("Auftraggeber/Auftrag: $auftrag")
            val agKontakt = listOf(fall.auftraggeberKontakt, fall.auftraggeberTelefon, fall.auftraggeberEmail).filter { it.isNotBlank() }.joinToString(" · ")
            if (agKontakt.isNotEmpty()) appendLine("Kontakt Auftraggeber: $agKontakt")
            if (fall.schadensort.isNotBlank()) appendLine("Schadensort: ${fall.schadensort.trim()}")
            appendLine("Schaden: ${fall.schadensart.label} – ${fall.beschreibung.trim().oderUnbekannt()}")
            appendLine("Schadenshöhe: ${fall.forderungCent?.let(Geld::format) ?: UNBEKANNT}")
            fall.vergleichCent?.let { appendLine("Vergleich/Regulierung: ${Geld.format(it)}") }
            fall.zahlungsfrist?.let { appendLine("Zahlungsfrist: ${Datum.format(it)}") }
            if (fall.versicherungsNummer.isNotBlank()) appendLine("Schadennummer Versicherung: ${fall.versicherungsNummer.trim()}")
            appendLine("Anlagen: $anzahlAnlagen")
            if (fall.status.offen && fall.wiedervorlageAm != null) {
                append("Wiedervorlage: ${Datum.format(fall.wiedervorlageAm)}")
                fall.wartetAuf?.let { append(" (wartet auf ${it.label})") }
                if (fall.wiedervorlageNotiz.isNotBlank()) append(" – ${fall.wiedervorlageNotiz.trim()}")
                appendLine()
            }
        }.trimEnd()
    }

    private fun String.oderUnbekannt() = ifBlank { UNBEKANNT }
}

/** „Ali Kaya, Ben Ott“ – oder `null`, wenn kein Beifahrer eingetragen ist. */
fun beifahrerText(fall: Schadensfall): String? =
    listOf(fall.beifahrer1, fall.beifahrer2).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
