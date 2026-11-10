package de.aysetransport.schaden.domain

import de.aysetransport.schaden.data.model.Schadensfall
import java.time.LocalDate

/** Wer unterschreibt – kommt aus den Einstellungen. */
data class Absender(
    val firma: String = "",
    val name: String = "",
    val funktion: String = "",
)

data class Nachricht(val betreff: String, val text: String)

enum class Vorlage(
    val titel: String,
    val beschreibung: String,
    /** `false`, wenn sich die Nachricht nicht an den Anspruchsteller richtet. */
    val anAnspruchsteller: Boolean = true,
) {
    EINGANGSBESTAETIGUNG("Eingangsbestätigung", "Bestätigt nur den Eingang der Meldung"),
    UNTERLAGEN_ANFORDERN("Unterlagen anfordern", "Fotos, Kaufbeleg und Lieferschein nachfordern"),
    ERINNERUNG("Erinnerung", "Nachhaken, wenn eine Rückmeldung aussteht"),
    ZWISCHENBESCHEID("Zwischenbescheid", "Mitteilen, dass die Prüfung noch läuft"),
    SACHSTANDSANFRAGE(
        "Sachstandsanfrage",
        "Stand bei Versicherung oder Auftraggeber erfragen",
        anAnspruchsteller = false,
    ),
}

/**
 * Kurze, sachliche Nachrichten zum Nachhaken.
 *
 * Alle Texte sind bewusst haftungsneutral: Sie erkennen keine Haftung an und lehnen sie
 * auch nicht ab. Ablehnungs- und Vergleichsschreiben gehören nicht hierher.
 */
object Textvorlagen {

    const val STANDARD_FRIST_TAGE = 14L

    fun erstelle(
        vorlage: Vorlage,
        fall: Schadensfall,
        absender: Absender,
        heute: LocalDate,
        fristTage: Long = STANDARD_FRIST_TAGE,
    ): Nachricht {
        val vorfall = fall.schadensdatum?.let { "zum Vorfall vom ${Datum.format(it)}" } ?: "zu dem gemeldeten Vorfall"
        val frist = Datum.format(heute.plusDays(fristTage))

        val absaetze: List<String> = when (vorlage) {
            Vorlage.EINGANGSBESTAETIGUNG -> listOf(
                "wir bestätigen den Eingang Ihrer Schadensmeldung $vorfall.",
                "Wir prüfen den Sachverhalt und melden uns unaufgefordert bei Ihnen, sobald die Prüfung abgeschlossen ist.",
                "Diese Nachricht bestätigt ausschließlich den Eingang Ihrer Meldung. Eine Aussage zur Haftung ist damit nicht verbunden.",
            )
            Vorlage.UNTERLAGEN_ANFORDERN -> listOf(
                "für die weitere Prüfung Ihrer Schadensmeldung $vorfall benötigen wir noch folgende Unterlagen:",
                "– Fotos des Schadens (Gesamtansicht und Detailaufnahmen)\n" +
                    "– Kaufbeleg oder Rechnung\n" +
                    "– Lieferschein, gegebenenfalls mit Schadensvermerk",
                "Bitte senden Sie uns die Unterlagen bis zum $frist zu. Sobald sie vorliegen, setzen wir die Prüfung fort.",
            )
            Vorlage.ERINNERUNG -> listOf(
                "wir kommen zurück auf unsere Nachricht $vorfall. Bislang liegt uns dazu keine Rückmeldung vor.",
                "Bitte melden Sie sich bis zum $frist bei uns, damit wir den Vorgang weiter bearbeiten können.",
            )
            Vorlage.ZWISCHENBESCHEID -> listOf(
                "die Prüfung Ihrer Schadensmeldung $vorfall dauert noch an.",
                "Wir melden uns voraussichtlich bis zum $frist wieder bei Ihnen. Sollten sich in der Zwischenzeit neue Informationen ergeben, teilen Sie uns diese gern mit.",
            )
            Vorlage.SACHSTANDSANFRAGE -> listOf(
                "wir bitten um Mitteilung des aktuellen Bearbeitungsstands zu folgendem Schadensfall:",
                sachstandsBezug(fall),
                "Für eine Rückmeldung bis zum $frist wären wir dankbar.",
            )
        }

        val anrede = if (vorlage.anAnspruchsteller && fall.kundeName.isNotBlank()) {
            "Guten Tag ${fall.kundeName.trim()},"
        } else {
            "Sehr geehrte Damen und Herren,"
        }

        val text = buildString {
            append(anrede).append("\n\n")
            append(absaetze.joinToString("\n\n"))
            append("\n\n").append(grussformel(absender))
        }
        return Nachricht(betreff = betreff(vorlage, fall), text = text)
    }

    private fun betreff(vorlage: Vorlage, fall: Schadensfall): String {
        val bezugsdatum = fall.meldedatum ?: fall.schadensdatum
        val was = when (vorlage) {
            Vorlage.SACHSTANDSANFRAGE -> "Sachstandsanfrage Schadensfall" +
                (fall.schadensdatum?.let { " vom ${Datum.format(it)}" } ?: "")
            else -> "Ihre Schadensmeldung" + (bezugsdatum?.let { " vom ${Datum.format(it)}" } ?: "")
        }
        val zeichen = buildList {
            val fremdesZeichen = when (vorlage) {
                Vorlage.SACHSTANDSANFRAGE -> fall.versicherungsNummer.ifBlank { fall.kundenAktenzeichen }
                else -> fall.kundenAktenzeichen
            }
            if (fremdesZeichen.isNotBlank()) add("Ihr Zeichen ${fremdesZeichen.trim()}")
            if (fall.fallnummer.isNotBlank()) add("unser Vorgang ${fall.fallnummer}")
        }
        return listOf(was, vorlage.titel).plus(zeichen).joinToString(" – ")
    }

    private fun sachstandsBezug(fall: Schadensfall): String = buildList {
        if (fall.kundeName.isNotBlank() || fall.kundeFirma.isNotBlank()) {
            add("Anspruchsteller: " + listOf(fall.kundeName, fall.kundeFirma).filter { it.isNotBlank() }.joinToString(", "))
        }
        fall.schadensdatum?.let { add("Schadensdatum: ${Datum.format(it)}") }
        if (fall.versicherungsNummer.isNotBlank()) add("Schadennummer: ${fall.versicherungsNummer.trim()}")
        if (fall.auftragsReferenz.isNotBlank()) add("Auftrag: ${fall.auftragsReferenz.trim()}")
        if (fall.tour.isNotBlank()) add("Tour: ${fall.tour.trim()}")
        if (isEmpty()) add("Vorgang ${fall.fallnummer}")
    }.joinToString("\n")

    private fun grussformel(absender: Absender): String = buildString {
        append("Mit freundlichen Grüßen")
        if (absender.name.isNotBlank()) append("\n\ni.A. ${absender.name.trim()}")
        if (absender.funktion.isNotBlank()) append("\n${absender.funktion.trim()}")
        if (absender.firma.isNotBlank()) append("\n${absender.firma.trim()}")
    }
}
