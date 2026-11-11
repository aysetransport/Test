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

/** An wen sich eine Nachricht richtet. Jeder bekommt nur die Angaben, die ihn betreffen. */
enum class Empfaenger(val label: String, val ueberschrift: String) {
    GESCHAEDIGTER("Geschädigter", "An den Geschädigten"),
    AUFTRAGGEBER("Auftraggeber", "An den Auftraggeber"),
    DRITTE("Versicherung / Dritte", "An Versicherung oder Dritte");

    fun email(fall: Schadensfall): String = when (this) {
        GESCHAEDIGTER -> fall.kundeEmail.trim()
        AUFTRAGGEBER -> fall.auftraggeberEmail.trim()
        DRITTE -> ""
    }

    fun telefon(fall: Schadensfall): String = when (this) {
        GESCHAEDIGTER -> fall.kundeTelefon.trim()
        AUFTRAGGEBER -> fall.auftraggeberTelefon.trim()
        DRITTE -> ""
    }
}

enum class Vorlage(val titel: String, val beschreibung: String, val empfaenger: Empfaenger) {
    EINGANGSBESTAETIGUNG("Eingangsbestätigung", "Bestätigt nur den Eingang der Meldung", Empfaenger.GESCHAEDIGTER),
    UNTERLAGEN_ANFORDERN("Unterlagen anfordern", "Fotos, Kaufbeleg und Lieferschein nachfordern", Empfaenger.GESCHAEDIGTER),
    ERINNERUNG("Erinnerung", "Nachhaken, wenn eine Rückmeldung aussteht", Empfaenger.GESCHAEDIGTER),
    ZWISCHENBESCHEID("Zwischenbescheid", "Mitteilen, dass die Prüfung noch läuft", Empfaenger.GESCHAEDIGTER),

    SCHADENSMITTEILUNG("Schadensmitteilung", "Auftraggeber über den gemeldeten Schaden informieren", Empfaenger.AUFTRAGGEBER),
    AUFTRAGSUNTERLAGEN("Auftragsunterlagen anfordern", "Lieferschein und Angaben zur Ware erbitten", Empfaenger.AUFTRAGGEBER),
    SACHSTAND_AUFTRAGGEBER("Sachstand mitteilen", "Auftraggeber über den Stand informieren", Empfaenger.AUFTRAGGEBER),
    ABSCHLUSS_AUFTRAGGEBER("Abschluss mitteilen", "Auftraggeber über den Abschluss informieren", Empfaenger.AUFTRAGGEBER),

    SACHSTANDSANFRAGE("Sachstandsanfrage", "Stand bei der Versicherung erfragen", Empfaenger.DRITTE),
}

/**
 * Kurze, sachliche Nachrichten zum Nachhaken – getrennt nach Empfänger.
 *
 * Alle Texte sind bewusst haftungsneutral: Sie erkennen keine Haftung an und lehnen sie
 * auch nicht ab. Ablehnungs- und Vergleichsschreiben gehören nicht hierher.
 *
 * Der Auftraggeber erfährt Name des Geschädigten und Art des Schadens, aber keine Kontaktdaten
 * und keine Forderungshöhe; der Geschädigte erfährt nichts über den Auftraggeber.
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
            Vorlage.SCHADENSMITTEILUNG -> listOf(
                "wir möchten Sie darüber informieren, dass uns zu einer Lieferung aus Ihrem Auftrag eine Schadensmeldung vorliegt:",
                auftragsBezug(fall),
                "Wir prüfen den Sachverhalt und halten Sie über den weiteren Verlauf auf dem Laufenden. Eine Aussage zur Haftung ist mit dieser Mitteilung nicht verbunden.",
            )
            Vorlage.AUFTRAGSUNTERLAGEN -> listOf(
                "für die Prüfung des folgenden Schadensfalls aus Ihrem Auftrag bitten wir um Unterstützung:",
                auftragsBezug(fall),
                "Bitte senden Sie uns, soweit vorhanden:\n" +
                    "– Lieferschein bzw. Auftragsbestätigung\n" +
                    "– Angaben zur gelieferten Ware (Artikel, Zustand bei Übergabe an uns)\n" +
                    "– Hinweise, die Ihnen der Kunde zu dem Schaden gegeben hat",
                "Für eine Rückmeldung bis zum $frist wären wir dankbar.",
            )
            Vorlage.SACHSTAND_AUFTRAGGEBER -> listOf(
                "zum folgenden Schadensfall aus Ihrem Auftrag möchten wir Sie über den aktuellen Stand informieren:",
                auftragsBezug(fall),
                "Stand der Bearbeitung: ${fall.status.label}.",
                "Wir melden uns, sobald es Neuigkeiten gibt.",
            )
            Vorlage.ABSCHLUSS_AUFTRAGGEBER -> listOf(
                "der folgende Schadensfall aus Ihrem Auftrag ist abgeschlossen:",
                auftragsBezug(fall),
                "Stand: ${fall.status.label}. Von unserer Seite sind keine weiteren Schritte erforderlich.",
                "Vielen Dank für Ihre Unterstützung.",
            )
            Vorlage.SACHSTANDSANFRAGE -> listOf(
                "wir bitten um Mitteilung des aktuellen Bearbeitungsstands zu folgendem Schadensfall:",
                sachstandsBezug(fall),
                "Für eine Rückmeldung bis zum $frist wären wir dankbar.",
            )
        }

        val ansprechpartner = when (vorlage.empfaenger) {
            Empfaenger.GESCHAEDIGTER -> fall.kundeName
            Empfaenger.AUFTRAGGEBER -> fall.auftraggeberKontakt
            Empfaenger.DRITTE -> ""
        }
        val anrede = if (ansprechpartner.isNotBlank()) "Guten Tag ${ansprechpartner.trim()}," else "Sehr geehrte Damen und Herren,"

        val text = buildString {
            append(anrede).append("\n\n")
            append(absaetze.joinToString("\n\n"))
            append("\n\n").append(grussformel(absender))
        }
        return Nachricht(betreff = betreff(vorlage, fall), text = text)
    }

    /** Nur Anrede und Grußformel – für eine Mail ohne Vorlage. */
    fun ohneVorlage(empfaenger: Empfaenger, fall: Schadensfall, absender: Absender): Nachricht {
        val ansprechpartner = when (empfaenger) {
            Empfaenger.GESCHAEDIGTER -> fall.kundeName
            Empfaenger.AUFTRAGGEBER -> fall.auftraggeberKontakt
            Empfaenger.DRITTE -> ""
        }
        val anrede = if (ansprechpartner.isNotBlank()) "Guten Tag ${ansprechpartner.trim()}," else "Sehr geehrte Damen und Herren,"
        return Nachricht(betreff = betreff(empfaenger, null, fall), text = "$anrede\n\n\n\n${grussformel(absender)}")
    }

    private fun betreff(vorlage: Vorlage, fall: Schadensfall): String = betreff(vorlage.empfaenger, vorlage, fall)

    private fun betreff(empfaenger: Empfaenger, vorlage: Vorlage?, fall: Schadensfall): String {
        val was = when (empfaenger) {
            Empfaenger.GESCHAEDIGTER -> "Ihre Schadensmeldung" + ((fall.meldedatum ?: fall.schadensdatum)?.let { " vom ${Datum.format(it)}" } ?: "")
            Empfaenger.AUFTRAGGEBER -> "Schadensfall" + (fall.schadensdatum?.let { " vom ${Datum.format(it)}" } ?: "")
            Empfaenger.DRITTE -> "Sachstandsanfrage Schadensfall" + (fall.schadensdatum?.let { " vom ${Datum.format(it)}" } ?: "")
        }
        val fremdesZeichen = when (empfaenger) {
            Empfaenger.GESCHAEDIGTER -> fall.kundenAktenzeichen.takeIf { it.isNotBlank() }?.let { "Ihr Zeichen ${it.trim()}" }
            Empfaenger.AUFTRAGGEBER -> fall.auftragsReferenz.takeIf { it.isNotBlank() }?.let { "Ihr Auftrag ${it.trim()}" }
            Empfaenger.DRITTE -> fall.versicherungsNummer.ifBlank { fall.kundenAktenzeichen }.takeIf { it.isNotBlank() }?.let { "Ihr Zeichen ${it.trim()}" }
        }
        val titel = if (vorlage == null || vorlage == Vorlage.SACHSTANDSANFRAGE) null else vorlage.titel
        return listOfNotNull(
            was, titel, zeichenDesAuftraggebers(empfaenger, fall), fremdesZeichen,
            fall.fallnummer.takeIf { it.isNotBlank() }?.let { "unser Vorgang $it" },
        ).joinToString(" – ")
    }

    /**
     * Das Zeichen des Auftraggebers – es steht in jedem Betreff. Der Auftraggeber liest es als „Ihr Zeichen“,
     * alle anderen als neutralen „Bezug“.
     */
    fun zeichenDesAuftraggebers(empfaenger: Empfaenger, fall: Schadensfall): String? =
        fall.auftraggeberZeichen.trim().takeIf { it.isNotEmpty() }?.let { if (empfaenger == Empfaenger.AUFTRAGGEBER) "Ihr Zeichen $it" else "Bezug $it" }

    /** Ergänzt das Zeichen des Auftraggebers, falls es (etwa in einer Antwort) noch fehlt – vor der Fallnummer. */
    fun mitZeichen(betreff: String, empfaenger: Empfaenger, fall: Schadensfall): String {
        val zusatz = zeichenDesAuftraggebers(empfaenger, fall) ?: return betreff
        if (betreff.contains(fall.auftraggeberZeichen.trim())) return betreff
        val stelle = betreff.indexOf(" [Fall ")
        return if (stelle < 0) "${betreff.trimEnd()} – $zusatz" else "${betreff.substring(0, stelle)} – $zusatz${betreff.substring(stelle)}"
    }

    /** Was der Auftraggeber zum Fall erfährt: Auftrag, Lieferung, Geschädigter (nur Name) und Art des Schadens. */
    private fun auftragsBezug(fall: Schadensfall): String = buildList {
        if (fall.auftragsReferenz.isNotBlank()) add("Auftrag: ${fall.auftragsReferenz.trim()}")
        fall.schadensdatum?.let { add("Lieferung vom: ${Datum.format(it)}") }
        val kunde = listOf(fall.kundeName, fall.kundeFirma).filter { it.isNotBlank() }.joinToString(", ")
        if (kunde.isNotEmpty()) add("Empfänger der Lieferung: $kunde")
        add("Schadensart: ${fall.schadensart.label}")
        if (fall.beschreibung.isNotBlank()) add("Gemeldeter Schaden: ${fall.beschreibung.trim()}")
    }.joinToString("\n")

    private fun sachstandsBezug(fall: Schadensfall): String = buildList {
        if (fall.kundeName.isNotBlank() || fall.kundeFirma.isNotBlank()) {
            add("Geschädigter: " + listOf(fall.kundeName, fall.kundeFirma).filter { it.isNotBlank() }.joinToString(", "))
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
