package de.aysetransport.schaden.domain

import de.aysetransport.schaden.data.model.Ansprechpartner
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensart
import de.aysetransport.schaden.data.model.Schadensfall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ZusammenfassungTest {

    @Test
    fun `vollstaendiger Fall`() {
        val fall = Schadensfall(
            fallnummer = "2026-007",
            kundeName = "Anna Becker",
            kundeFirma = "Becker GmbH",
            kundeTelefon = "0171 1234567",
            tour = "Tour 3",
            fahrzeug = "RS-AT 123",
            fahrer = "M. Yilmaz",
            schadensdatum = LocalDate.of(2026, 11, 3),
            schadensart = Schadensart.GEBAEUDE,
            beschreibung = "Türrahmen beim Eintragen beschädigt",
            forderungCent = 45_000,
            status = FallStatus.IN_PRUEFUNG,
            wiedervorlageAm = LocalDate.of(2026, 11, 17),
            wartetAuf = Ansprechpartner.FAHRER,
            wiedervorlageNotiz = "Stellungnahme einholen",
        )

        assertEquals(
            """
            Schadensfall 2026-007
            Status: In Prüfung
            Geschädigter: Anna Becker, Becker GmbH
            Kontakt Geschädigter: 0171 1234567
            Schadensdatum: 03.11.2026
            Tour/Fahrzeug: Tour 3 / RS-AT 123 (Fahrer: M. Yilmaz)
            Schaden: Schaden an Gebäude / Einrichtung – Türrahmen beim Eintragen beschädigt
            Schadenshöhe: 450,00${'\u00A0'}€
            Anlagen: 2
            Wiedervorlage: 17.11.2026 (wartet auf Fahrer) – Stellungnahme einholen
            """.trimIndent(),
            Zusammenfassung.erstelle(fall, anzahlAnlagen = 2),
        )
    }

    @Test
    fun `fehlende Angaben werden als unbekannt ausgewiesen`() {
        val text = Zusammenfassung.erstelle(Schadensfall(fallnummer = "2026-001"), anzahlAnlagen = 0)

        assertTrue("Geschädigter: unbekannt" in text)
        assertTrue("Schadensdatum: unbekannt" in text)
        assertTrue("Tour/Fahrzeug: unbekannt" in text)
        assertTrue("Schadenshöhe: unbekannt" in text)
        assertFalse("Wiedervorlage" in text)
    }

    @Test
    fun `abgeschlossene Faelle nennen keine Wiedervorlage`() {
        val fall = Schadensfall(fallnummer = "2026-002", status = FallStatus.ERLEDIGT, wiedervorlageAm = LocalDate.of(2026, 11, 17))
        assertFalse("Wiedervorlage" in Zusammenfassung.erstelle(fall, 0))
    }

    @Test
    fun `Beifahrer erscheinen bei Tour und Fahrzeug`() {
        val fall = Schadensfall(fallnummer = "2026-003", tour = "Tour 5", fahrer = "M. Yilmaz", beifahrer1 = "Ali Kaya", beifahrer2 = "Ben Ott")
        assertTrue("Tour/Fahrzeug: Tour 5 (Fahrer: M. Yilmaz, Beifahrer: Ali Kaya, Ben Ott)" in Zusammenfassung.erstelle(fall, 0))
        val nurZweiter = Schadensfall(fallnummer = "2026-004", beifahrer2 = "Ben Ott")
        assertTrue("Tour/Fahrzeug: (Beifahrer: Ben Ott)" in Zusammenfassung.erstelle(nurZweiter, 0))
    }
}
