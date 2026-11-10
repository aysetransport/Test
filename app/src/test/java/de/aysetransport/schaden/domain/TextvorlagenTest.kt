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

        assertFalse(Vorlage.SACHSTANDSANFRAGE.anAnspruchsteller)
        assertTrue(nachricht.text.startsWith("Sehr geehrte Damen und Herren,"))
        assertTrue("Anspruchsteller: Anna Becker" in nachricht.text)
        assertTrue("Schadennummer: VS-99" in nachricht.text)
        assertTrue("Ihr Zeichen VS-99" in nachricht.betreff)
    }
}
