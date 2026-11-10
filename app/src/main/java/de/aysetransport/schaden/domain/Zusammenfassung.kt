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
            if (fall.fahrer.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append("(Fahrer: ${fall.fahrer.trim()})")
            }
        }
        val auftrag = listOf(fall.auftraggeber, fall.auftragsReferenz).filter { it.isNotBlank() }.joinToString(" · ")

        return buildString {
            appendLine("Schadensfall ${fall.fallnummer}")
            appendLine("Status: ${fall.status.label}")
            appendLine("Kunde/Absender: ${kunde.oderUnbekannt()}")
            appendLine("Kontakt: ${kontakt.oderUnbekannt()}")
            if (fall.kundenAktenzeichen.isNotBlank()) appendLine("Zeichen des Kunden: ${fall.kundenAktenzeichen.trim()}")
            appendLine("Schadensdatum: ${fall.schadensdatum?.let(Datum::format) ?: UNBEKANNT}")
            appendLine("Tour/Fahrzeug: ${tour.oderUnbekannt()}")
            if (auftrag.isNotBlank()) appendLine("Auftraggeber/Auftrag: $auftrag")
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
