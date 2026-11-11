package de.aysetransport.schaden.domain

import de.aysetransport.schaden.data.model.Schadensfall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TextvorlagenTest {

    private val heute = LocalDate.of(2026, 11, 10)
    private val absender = Absender(firma = "Muster Transport GmbH", name = "Kim Beispiel", funktion = "Schadensbearbeitung")
    private val fall = Schadensfall(
        fallnummer = "2026-007",
        kundeName = "Anna Becker",
        kundenAktenzeichen = "AB-4711",
        schadensdatum = LocalDate.of(2026, 11, 3),
        meldedatum = LocalDate.of(2026, 11, 5),
        versicherungsNummer = "VS-99",
        tour = "Tour 3",
    )

    @Test
    fun `Eingangsbestaetigung nennt Vorfall, Zeichen und Absender`() {
        val nachricht = Textvorlagen.erstelle(Vorlage.EINGANGSBESTAETIGUNG, fall, absender, heute)

        assertEquals(
            "Ihre Schadensmeldung vom 05.11.2026 – Eingangsbestätigung – Ihr Zeichen AB-4711 – unser Vorgang 2026-007",
            nachricht.betreff,
        )
        assertTrue(nachricht.text.startsWith("Guten Tag Anna Becker,\n\n"))
        assertTrue("zum Vorfall vom 03.11.2026" in nachricht.text)
        assertTrue(nachricht.text.endsWith("Mit freundlichen Grüßen\n\ni.A. Kim Beispiel\nSchadensbearbeitung\nMuster Transport GmbH"))
    }

    @Test
    fun `keine Vorlage erkennt Haftung an oder lehnt sie ab`() {
        val verboten = listOf(
            "wir haften", "haften wir", "übernehmen die kosten", "erstatten", "entschuldig", "unser verschulden",
            "lehnen ab", "ablehnung", "zurückweis", "anerkenn", "schuld",
        )
        for (vorlage in Vorlage.entries) {
            val text = Textvorlagen.erstelle(vorlage, fall, absender, heute).text.lowercase()
            for (wort in verboten) {
                assertFalse("${vorlage.name} enthält „$wort“", wort in text)
            }
        }
    }

    @Test
    fun `Frist liegt standardmaessig 14 Tage in der Zukunft`() {
        val text = Textvorlagen.erstelle(Vorlage.UNTERLAGEN_ANFORDERN, fall, absender, heute).text
        assertTrue("bis zum 24.11.2026" in text)
        assertTrue("Kaufbeleg" in text)
    }

    @Test
    fun `ohne Namen wird neutral angeredet und ohne Absender schlicht gegruesst`() {
        val nachricht = Textvorlagen.erstelle(Vorlage.ERINNERUNG, Schadensfall(fallnummer = "2026-001"), Absender(), heute)

        assertTrue(nachricht.text.startsWith("Sehr geehrte Damen und Herren,"))
        assertTrue("zu dem gemeldeten Vorfall" in nachricht.text)
        assertTrue(nachricht.text.endsWith("Mit freundlichen Grüßen"))
        assertEquals("Ihre Schadensmeldung – Erinnerung – unser Vorgang 2026-001", nachricht.betreff)
    }

    @Test
    fun `Sachstandsanfrage richtet sich an Dritte und nennt deren Schadennummer`() {
        val nachricht = Textvorlagen.erstelle(Vorlage.SACHSTANDSANFRAGE, fall, absender, heute)

        assertEquals(Empfaenger.DRITTE, Vorlage.SACHSTANDSANFRAGE.empfaenger)
        assertTrue(nachricht.text.startsWith("Sehr geehrte Damen und Herren,"))
        assertTrue("Geschädigter: Anna Becker" in nachricht.text)
        assertTrue("Schadennummer: VS-99" in nachricht.text)
        assertTrue("Ihr Zeichen VS-99" in nachricht.betreff)
    }

    private val mitAuftraggeber = fall.copy(
        kundeTelefon = "0171 5550142",
        kundeEmail = "a.becker@example.org",
        forderungCent = 45_000,
        beschreibung = "Sofa: Naht gerissen",
        auftraggeber = "Möbelhaus Nordpark",
        auftraggeberKontakt = "Frau Kaya",
        auftraggeberEmail = "dispo@moebelhaus.example",
        auftragsReferenz = "LS-48213",
    )

    @Test
    fun `jede Vorlage hat genau einen Empfaenger und jeder Empfaenger eigene Vorlagen`() {
        val jeEmpfaenger = Vorlage.entries.groupBy { it.empfaenger }
        assertEquals(setOf(Empfaenger.GESCHAEDIGTER, Empfaenger.AUFTRAGGEBER, Empfaenger.DRITTE), jeEmpfaenger.keys)
        assertEquals("a.becker@example.org", Empfaenger.GESCHAEDIGTER.email(mitAuftraggeber))
        assertEquals("dispo@moebelhaus.example", Empfaenger.AUFTRAGGEBER.email(mitAuftraggeber))
        assertEquals("", Empfaenger.DRITTE.email(mitAuftraggeber))
    }

    @Test
    fun `Auftraggeber bekommt eigene Anrede und Bezug, aber keine Kontaktdaten oder Forderung des Geschaedigten`() {
        for (vorlage in Vorlage.entries.filter { it.empfaenger == Empfaenger.AUFTRAGGEBER }) {
            val n = Textvorlagen.erstelle(vorlage, mitAuftraggeber, absender, heute)
            assertTrue(n.text.startsWith("Guten Tag Frau Kaya,"))
            assertTrue(vorlage.name, "Auftrag: LS-48213" in n.text)
            assertTrue(vorlage.name, "Empfänger der Lieferung: Anna Becker" in n.text)
            assertTrue(n.betreff, "Ihr Auftrag LS-48213" in n.betreff && "unser Vorgang 2026-007" in n.betreff)
            for (vertraulich in listOf("0171 5550142", "a.becker@example.org", "450", "AB-4711")) {
                assertFalse("${vorlage.name} verrät „$vertraulich“", vertraulich in n.text || vertraulich in n.betreff)
            }
        }
    }

    @Test
    fun `Geschaedigter erfaehrt nichts ueber den Auftraggeber`() {
        for (vorlage in Vorlage.entries.filter { it.empfaenger == Empfaenger.GESCHAEDIGTER }) {
            val n = Textvorlagen.erstelle(vorlage, mitAuftraggeber, absender, heute)
            assertTrue(n.text.startsWith("Guten Tag Anna Becker,"))
            for (intern in listOf("Möbelhaus", "Kaya", "LS-48213", "dispo@")) {
                assertFalse("${vorlage.name} verrät „$intern“", intern in n.text || intern in n.betreff)
            }
        }
    }

    @Test
    fun `Zeichen des Auftraggebers steht in jedem Betreff`() {
        val mitZeichen = mitAuftraggeber.copy(auftraggeberZeichen = "RK-2291")
        for (vorlage in Vorlage.entries) {
            val betreff = Textvorlagen.erstelle(vorlage, mitZeichen, absender, heute).betreff
            val erwartet = if (vorlage.empfaenger == Empfaenger.AUFTRAGGEBER) "Ihr Zeichen RK-2291" else "Bezug RK-2291"
            assertTrue("${vorlage.name}: $betreff", erwartet in betreff)
        }
        for (e in Empfaenger.entries) assertTrue(e.name, "RK-2291" in Textvorlagen.ohneVorlage(e, mitZeichen, absender).betreff)
        assertEquals(
            "Schadensfall vom 03.11.2026 – Abschluss mitteilen – Ihr Zeichen RK-2291 – Ihr Auftrag LS-48213 – unser Vorgang 2026-007",
            Textvorlagen.erstelle(Vorlage.ABSCHLUSS_AUFTRAGGEBER, mitZeichen, absender, heute).betreff,
        )
    }

    @Test
    fun `Zeichen wird in Antworten vor der Fallnummer ergaenzt, aber nie doppelt`() {
        val f = fall.copy(auftraggeberZeichen = "RK-2291")
        assertEquals("Re: Fotos – Bezug RK-2291 [Fall 2026-007]", Textvorlagen.mitZeichen("Re: Fotos [Fall 2026-007]", Empfaenger.GESCHAEDIGTER, f))
        assertEquals("Re: Frage – Ihr Zeichen RK-2291", Textvorlagen.mitZeichen("Re: Frage", Empfaenger.AUFTRAGGEBER, f))
        assertEquals("Re: RK-2291 [Fall 2026-007]", Textvorlagen.mitZeichen("Re: RK-2291 [Fall 2026-007]", Empfaenger.AUFTRAGGEBER, f))
        assertEquals("Re: Frage", Textvorlagen.mitZeichen("Re: Frage", Empfaenger.AUFTRAGGEBER, fall))
    }
}
