package de.aysetransport.schaden.domain

import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag
import de.aysetransport.schaden.data.model.VerlaufTyp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class BearbeitungsschritteTest {

    private val fall = Schadensfall(
        id = 1,
        fallnummer = "2026-007",
        kundeName = "Anna Becker",
        kundeEmail = "anna@kunde.example",
        auftraggeberEmail = "dispo@moebelhaus.example",
    )

    private fun eintrag(tag: Int, typ: VerlaufTyp, text: String) =
        VerlaufEintrag(uuid = "e$tag-$typ", fallId = 1, zeitpunkt = Instant.parse("2026-11-%02dT09:00:00Z".format(tag)), typ = typ, text = text)

    private val verlauf = listOf(
        eintrag(1, VerlaufTyp.ANGELEGT, "Fall angelegt"),
        eintrag(2, VerlaufTyp.EMAIL_AUSGANG, "An: anna@kunde.example\nBetreff: Fotos [Fall 2026-007]\nAnhänge: Merkblatt.pdf\n\nGuten Tag"),
        eintrag(3, VerlaufTyp.EMAIL_AUSGANG, "An: dispo@moebelhaus.example\nBetreff: Schadensfall [Fall 2026-007]\n\nGuten Tag"),
        eintrag(4, VerlaufTyp.ANRUF, "Kundin erreicht, Fotos kommen morgen\nWiedervorlage am 06.11.2026 · wartet auf Geschädigter"),
        eintrag(5, VerlaufTyp.EMAIL_EINGANG, "Von: Anna Becker <anna@kunde.example>\nBetreff: Re: Fotos\nAnhänge (zum Fall genommen): 1.jpg\n\nAnbei"),
        eintrag(6, VerlaufTyp.NOTIZ, "intern: Fahrer befragen"),
        eintrag(7, VerlaufTyp.STATUS, "Status: Neu → In Prüfung\nRückfrage beim Fahrer"),
        eintrag(8, VerlaufTyp.WIEDERVORLAGE, "Wiedervorlage am 12.11.2026"),
    )

    @Test
    fun `vorausgewaehlt ist alles Mitteilbare seit der letzten Mail an den Auftraggeber`() {
        val gewaehlt = Bearbeitungsschritte.vorauswahl(fall, verlauf)
        assertEquals(setOf("e4-ANRUF", "e5-EMAIL_EINGANG", "e7-STATUS"), gewaehlt)
        // Notizen sind wählbar, interne Einträge wie Wiedervorlagen gar nicht.
        val waehlbar = Bearbeitungsschritte.auswaehlbar(verlauf).map { it.uuid }
        assertTrue("e6-NOTIZ" in waehlbar)
        assertFalse("e8-WIEDERVORLAGE" in waehlbar || "e1-ANGELEGT" in waehlbar)
        assertEquals("neueste zuerst", "e7-STATUS", waehlbar.first())
    }

    @Test
    fun `eine Blindkopie an den Auftraggeber zaehlt als Mail an ihn`() {
        val mitBcc = verlauf + eintrag(9, VerlaufTyp.EMAIL_AUSGANG, "An: anna@kunde.example\nBcc: dispo@moebelhaus.example\nBetreff: x\n\ny")
        assertEquals(emptySet<String>(), Bearbeitungsschritte.vorauswahl(fall, mitBcc))
    }

    @Test
    fun `Text nennt keine Adressen und keine Wiedervorlagen`() {
        val text = Bearbeitungsschritte.alsText(verlauf.filter { it.typ != VerlaufTyp.ANGELEGT && it.typ != VerlaufTyp.WIEDERVORLAGE }, fall, ZoneOffset.UTC)
        assertEquals(
            """
            Bisherige Bearbeitung:
            – 02.11.2026: E-Mail an den Geschädigten gesendet (mit Anlagen)
            – 03.11.2026: E-Mail an Sie gesendet
            – 04.11.2026: Telefonat: Kundin erreicht, Fotos kommen morgen
            – 05.11.2026: E-Mail des Geschädigten erhalten (mit Anlagen)
            – 06.11.2026: Notiz: intern: Fahrer befragen
            – 07.11.2026: Status: Neu → In Prüfung
            """.trimIndent(),
            text,
        )
        assertFalse("@" in text)
    }

    @Test
    fun `Absatz kommt vor den Schlusssatz bzw die Grussformel`() {
        val absatz = "Bisherige Bearbeitung:\n– x"
        assertEquals(
            "Guten Tag,\n\nStand: offen.\n\n$absatz\n\nWir melden uns, sobald es Neuigkeiten gibt.\n\nMit freundlichen Grüßen",
            Bearbeitungsschritte.einfuegen("Guten Tag,\n\nStand: offen.\n\nWir melden uns, sobald es Neuigkeiten gibt.\n\nMit freundlichen Grüßen", absatz),
        )
        assertEquals("Hallo,\n\n\n\n$absatz\n\nMit freundlichen Grüßen".replace("\n\n\n\n", "\n\n"), Bearbeitungsschritte.einfuegen("Hallo,\n\n\n\nMit freundlichen Grüßen", absatz))
        assertEquals("Hallo\n\n$absatz", Bearbeitungsschritte.einfuegen("Hallo\n", absatz))
    }
}
