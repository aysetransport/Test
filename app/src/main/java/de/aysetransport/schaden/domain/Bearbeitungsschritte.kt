package de.aysetransport.schaden.domain

import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag
import de.aysetransport.schaden.data.model.VerlaufTyp
import java.time.ZoneId

/**
 * Teilt dem Auftraggeber mit, was im Fall passiert ist („E-Mail an den Geschädigten gesendet“,
 * „Telefonat: …“). Aus den gewählten Verlaufseinträgen wird ein Absatz für die Mail.
 *
 * Adressen, Telefonnummern und Wiedervorlagen bleiben draußen; der Text ist vor dem Senden
 * frei bearbeitbar.
 */
object Bearbeitungsschritte {

    /** Diese Einträge lassen sich mitteilen – in dieser Reihenfolge erscheinen sie auch als Filter. */
    val ARTEN = listOf(
        VerlaufTyp.ANRUF, VerlaufTyp.EMAIL_AUSGANG, VerlaufTyp.EMAIL_EINGANG, VerlaufTyp.EMAIL, VerlaufTyp.NACHRICHT,
        VerlaufTyp.BRIEF, VerlaufTyp.STATUS, VerlaufTyp.NOTIZ, VerlaufTyp.ANLAGE,
    )

    /** Interne Notizen und Anlagen sind wählbar, aber nicht vorausgewählt. */
    private val VORAUSGEWAEHLT = ARTEN.toSet() - VerlaufTyp.NOTIZ - VerlaufTyp.ANLAGE

    const val UEBERSCHRIFT = "Bisherige Bearbeitung:"
    private const val MAX_ZEICHEN = 300

    /** Die wählbaren Einträge, neueste zuerst. */
    fun auswaehlbar(verlauf: List<VerlaufEintrag>): List<VerlaufEintrag> =
        verlauf.filter { it.typ in ARTEN }.sortedByDescending { it.zeitpunkt }

    /** Vorauswahl: alles seit der letzten Mail, die an den Auftraggeber ging (auch in BCC). */
    fun vorauswahl(fall: Schadensfall, verlauf: List<VerlaufEintrag>): Set<String> {
        val ag = fall.auftraggeberEmail.trim().lowercase()
        val letzte = if (ag.isEmpty()) {
            null
        } else {
            verlauf.filter { it.typ == VerlaufTyp.EMAIL_AUSGANG && (kopf(it.text, "An") + " " + kopf(it.text, "Bcc")).lowercase().contains(ag) }
                .maxOfOrNull { it.zeitpunkt }
        }
        return verlauf.filter { it.typ in VORAUSGEWAEHLT && (letzte == null || it.zeitpunkt > letzte) }.map { it.uuid }.toSet()
    }

    /** Eine Zeile pro Eintrag, älteste zuerst. */
    fun alsText(eintraege: List<VerlaufEintrag>, fall: Schadensfall, zone: ZoneId = ZoneId.systemDefault()): String =
        UEBERSCHRIFT + "\n" + eintraege.sortedBy { it.zeitpunkt }.joinToString("\n") {
            "– ${Datum.format(it.zeitpunkt.atZone(zone).toLocalDate())}: ${beschreibe(it, fall)}"
        }

    /**
     * Setzt den Absatz vor den Schlusssatz der Sachstandsvorlage bzw. vor die Grußformel
     * (oder ans Ende, wenn es beides nicht gibt).
     */
    fun einfuegen(text: String, absatz: String): String {
        val stelle = text.indexOf(SCHLUSSSATZ).takeIf { it >= 0 } ?: text.lastIndexOf("Mit freundlichen Grüßen")
        return if (stelle < 0) {
            text.trimEnd() + "\n\n" + absatz
        } else {
            text.substring(0, stelle).trimEnd() + "\n\n" + absatz + "\n\n" + text.substring(stelle)
        }
    }

    private const val SCHLUSSSATZ = "Wir melden uns, sobald es Neuigkeiten gibt."

    fun beschreibe(e: VerlaufEintrag, fall: Schadensfall): String {
        val mitAnlagen = if (e.text.lineSequence().any { it.startsWith("Anhänge") }) " (mit Anlagen)" else ""
        return when (e.typ) {
            VerlaufTyp.EMAIL_AUSGANG -> "E-Mail ${an(kopf(e.text, "An"), fall)} gesendet$mitAnlagen"
            VerlaufTyp.EMAIL_EINGANG -> "${von(kopf(e.text, "Von"), fall)} erhalten$mitAnlagen"
            VerlaufTyp.STATUS -> e.text.lineSequence().first().trim()
            else -> {
                val inhalt = inhalt(e.text)
                when {
                    e.typ == VerlaufTyp.ANRUF && (inhalt.isEmpty() || inhalt == "Nachgehakt") -> "Telefonat geführt"
                    e.typ == VerlaufTyp.ANLAGE -> inhalt.ifEmpty { "Anlage hinzugefügt" }
                    inhalt.isEmpty() || inhalt == "Nachgehakt" -> "${e.typ.label}: nachgehakt"
                    else -> "${e.typ.label}: $inhalt"
                }
            }
        }
    }

    /** Text ohne Wiedervorlage-Zeilen, auf eine Zeile gebracht und gekürzt. */
    private fun inhalt(text: String): String {
        val zeilen = text.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Wiedervorlage am") && it != "Keine Wiedervorlage geplant" }
        val einzeilig = zeilen.joinToString(" ")
        return if (einzeilig.length <= MAX_ZEICHEN) einzeilig else einzeilig.take(MAX_ZEICHEN - 1).trimEnd() + "…"
    }

    private fun kopf(text: String, name: String): String =
        text.lineSequence().takeWhile { it.isNotBlank() }.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim().orEmpty()

    private fun enthaelt(adressen: String, email: String) = email.isNotBlank() && adressen.lowercase().contains(email.trim().lowercase())

    private fun an(adressen: String, fall: Schadensfall) = when {
        enthaelt(adressen, fall.auftraggeberEmail) -> "an Sie"
        enthaelt(adressen, fall.kundeEmail) -> "an den Geschädigten"
        else -> "an Dritte"
    }

    private fun von(absender: String, fall: Schadensfall) = when {
        enthaelt(absender, fall.auftraggeberEmail) -> "Ihre E-Mail"
        enthaelt(absender, fall.kundeEmail) -> "E-Mail des Geschädigten"
        else -> "E-Mail von Dritten"
    }
}
