package de.aysetransport.schaden.domain

import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensfall
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class FalllisteTest {

    private val heute = LocalDate.of(2026, 11, 10)
    private fun zeit(n: Long) = Instant.parse("2026-11-01T08:00:00Z").plusSeconds(n * 3600)

    private val ueberfaellig = Schadensfall(
        id = 1, fallnummer = "2026-001", kundeName = "Anna Becker", tour = "Tour 3",
        status = FallStatus.WARTET_AUF_RUECKMELDUNG, wiedervorlageAm = heute.minusDays(4),
        forderungCent = 45_000, geaendertAm = zeit(1),
    )
    private val heuteFaellig = Schadensfall(
        id = 2, fallnummer = "2026-002", kundeFirma = "Möbelhaus Nord", beschreibung = "Kratzer an der Schrankwand",
        status = FallStatus.IN_PRUEFUNG, wiedervorlageAm = heute, forderungCent = 120_000, geaendertAm = zeit(2),
    )
    private val spaeter = Schadensfall(
        id = 3, fallnummer = "2026-003", kundeName = "Carl Demir", fahrer = "Yilmaz",
        status = FallStatus.BEI_VERSICHERUNG, wiedervorlageAm = heute.plusDays(9), geaendertAm = zeit(3),
    )
    private val ohneTermin = Schadensfall(
        id = 4, fallnummer = "2026-004", kundeName = "Dora Engel", status = FallStatus.NEU, geaendertAm = zeit(4),
    )
    private val erledigt = Schadensfall(
        id = 5, fallnummer = "2026-005", kundeName = "Emil Frank", status = FallStatus.ERLEDIGT,
        wiedervorlageAm = heute.minusDays(20), forderungCent = 99_900, geaendertAm = zeit(5),
    )
    private val alle = listOf(erledigt, ohneTermin, spaeter, heuteFaellig, ueberfaellig)

    private fun ids(filter: FallFilter, suche: String = "") =
        Fallliste.filtereUndSortiere(alle, filter, suche, heute).map { it.id }

    @Test
    fun `Faellig zeigt nur offene Faelle mit Termin bis heute, aelteste zuerst`() {
        assertEquals(listOf(1L, 2L), ids(FallFilter.FAELLIG))
    }

    @Test
    fun `Offen sortiert nach Wiedervorlage und stellt Faelle ohne Termin ans Ende`() {
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(FallFilter.OFFEN))
    }

    @Test
    fun `Alle haengt die abgeschlossenen hinten an`() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(FallFilter.ALLE))
        assertEquals(listOf(5L), ids(FallFilter.ABGESCHLOSSEN))
    }

    @Test
    fun `Suche findet ueber mehrere Felder und ignoriert Gross- und Kleinschreibung`() {
        assertEquals(listOf(1L), ids(FallFilter.ALLE, "tour 3"))
        assertEquals(listOf(2L), ids(FallFilter.ALLE, "SCHRANKWAND"))
        assertEquals(listOf(3L), ids(FallFilter.ALLE, "yilmaz"))
        assertEquals(listOf(5L), ids(FallFilter.ALLE, "2026-005"))
        assertEquals(listOf(3L), ids(FallFilter.ALLE, "versicherung"))
    }

    @Test
    fun `mehrere Suchbegriffe muessen alle passen`() {
        assertEquals(listOf(2L), ids(FallFilter.ALLE, "nord kratzer"))
        assertEquals(emptyList<Long>(), ids(FallFilter.ALLE, "nord yilmaz"))
    }

    @Test
    fun `Suche bleibt innerhalb des gewaehlten Reiters`() {
        assertEquals(emptyList<Long>(), ids(FallFilter.OFFEN, "Emil"))
        assertEquals(listOf(5L), ids(FallFilter.ABGESCHLOSSEN, "Emil"))
    }

    @Test
    fun `Kennzahlen zaehlen nur offene Faelle`() {
        val kennzahlen = Fallliste.kennzahlen(alle, heute)
        assertEquals(2, kennzahlen.faellig)
        assertEquals(4, kennzahlen.offen)
        assertEquals(165_000L, kennzahlen.offeneForderungCent)
    }
}
