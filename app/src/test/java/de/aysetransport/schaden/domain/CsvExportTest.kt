package de.aysetransport.schaden.domain

import de.aysetransport.schaden.data.model.Ansprechpartner
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensfall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CsvExportTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private val fall = Schadensfall(
        id = 7,
        fallnummer = "2026-007",
        kundeName = "Anna Becker",
        kundeTelefon = "+49 171 1234567",
        tour = "Tour 3",
        schadensdatum = LocalDate.of(2026, 11, 3),
        beschreibung = "Sofa; Naht gerissen,\n\"Ecke\" eingedrückt",
        forderungCent = 123_456,
        status = FallStatus.WARTET_AUF_RUECKMELDUNG,
        wartetAuf = Ansprechpartner.VERSICHERUNG,
        wiedervorlageAm = LocalDate.of(2026, 11, 17),
        erstelltAm = Instant.parse("2026-11-05T09:00:00Z"),
        geaendertAm = Instant.parse("2026-11-09T23:30:00Z"),
    )

    @Test
    fun `beginnt mit BOM und Kopfzeile, Zeilen enden mit CRLF`() {
        val csv = CsvExport.erstelle(listOf(fall), zone = berlin)

        assertTrue(csv.startsWith("\uFEFFFall-Nr.;Status;Tour;"))
        assertTrue(csv.endsWith("\r\n"))
        assertEquals(CsvExport.KOPFZEILE.size, csv.lineSequence().first().split(';').size)
    }

    @Test
    fun `Zeile enthaelt die Falldaten in deutscher Schreibweise`() {
        val csv = CsvExport.erstelle(listOf(fall), mapOf(7L to 3), berlin)
        val zeile = csv.substringAfter("\r\n")

        assertTrue(zeile.startsWith("2026-007;Wartet auf Rückmeldung;Tour 3;"))
        assertTrue(";03.11.2026;" in zeile)
        assertTrue(";1234,56;" in zeile)
        assertTrue(";Versicherung;17.11.2026;" in zeile)
        // 23:30 UTC ist in Berlin bereits der 10.11.
        assertTrue(zeile.endsWith(";3;05.11.2026;10.11.2026\r\n"))
    }

    @Test
    fun `Trennzeichen, Anfuehrungszeichen und Zeilenumbrueche werden maskiert`() {
        assertEquals("\"Sofa; Naht gerissen,\n\"\"Ecke\"\" eingedrückt\"", CsvExport.zelle(fall.beschreibung))
        assertEquals("einfach", CsvExport.zelle("einfach"))
    }

    @Test
    fun `Formeln aus Fremdtexten werden entschaerft`() {
        assertEquals("'=SUMME(A1:A9)", CsvExport.zelle("=SUMME(A1:A9)"))
        assertEquals("'@cmd", CsvExport.zelle("@cmd"))
        assertEquals("'+cmd|' /C calc'!A0", CsvExport.zelle("+cmd|' /C calc'!A0"))
        assertEquals("'-2+3+cmd", CsvExport.zelle("-2+3+cmd"))
    }

    @Test
    fun `Telefonnummern und negative Zahlen bleiben unveraendert`() {
        assertEquals("+49 171 1234567", CsvExport.zelle("+49 171 1234567"))
        assertEquals("+49 (0)2191 / 12-34", CsvExport.zelle("+49 (0)2191 / 12-34"))
        assertEquals("-12,50", CsvExport.zelle("-12,50"))
    }

    @Test
    fun `Faelle werden nach Fallnummer sortiert`() {
        val csv = CsvExport.erstelle(listOf(fall, fall.copy(id = 2, fallnummer = "2026-002")), zone = berlin)
        val nummern = csv.split("\r\n").drop(1).filter { it.isNotEmpty() && it.startsWith("2026") }.map { it.substringBefore(';') }
        assertEquals(listOf("2026-002", "2026-007"), nummern)
    }
}
