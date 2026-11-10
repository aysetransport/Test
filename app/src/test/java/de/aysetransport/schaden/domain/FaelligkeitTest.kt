package de.aysetransport.schaden.domain

import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensfall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FaelligkeitTest {

    private val heute = LocalDate.of(2026, 11, 10)

    @Test
    fun `ohne Termin gibt es keine Faelligkeit`() {
        assertEquals(Faelligkeit.Keine, Faelligkeit.von(null, heute))
    }

    @Test
    fun `vergangene Termine sind ueberfaellig`() {
        assertEquals(Faelligkeit.Ueberfaellig(1), Faelligkeit.von(heute.minusDays(1), heute))
        assertEquals(Faelligkeit.Ueberfaellig(12), Faelligkeit.von(heute.minusDays(12), heute))
        assertTrue(Faelligkeit.von(heute.minusDays(1), heute).istFaellig)
    }

    @Test
    fun `heutiger Termin ist faellig, morgiger noch nicht`() {
        assertEquals(Faelligkeit.Heute, Faelligkeit.von(heute, heute))
        assertTrue(Faelligkeit.Heute.istFaellig)
        assertEquals(Faelligkeit.Kommend(1), Faelligkeit.von(heute.plusDays(1), heute))
        assertFalse(Faelligkeit.Kommend(1).istFaellig)
    }

    @Test
    fun `Texte sind auf Deutsch und in Ein- und Mehrzahl richtig`() {
        assertEquals("seit gestern überfällig", Faelligkeit.Ueberfaellig(1).text())
        assertEquals("seit 3 Tagen überfällig", Faelligkeit.Ueberfaellig(3).text())
        assertEquals("heute fällig", Faelligkeit.Heute.text())
        assertEquals("morgen fällig", Faelligkeit.Kommend(1).text())
        assertEquals("in 5 Tagen fällig", Faelligkeit.Kommend(5).text())
    }

    @Test
    fun `abgeschlossene Faelle sind nie faellig`() {
        val ueberfaellig = heute.minusDays(30)
        assertTrue(Schadensfall(status = FallStatus.IN_PRUEFUNG, wiedervorlageAm = ueberfaellig).faelligkeit(heute).istFaellig)
        assertEquals(Faelligkeit.Keine, Schadensfall(status = FallStatus.ERLEDIGT, wiedervorlageAm = ueberfaellig).faelligkeit(heute))
        assertEquals(Faelligkeit.Keine, Schadensfall(status = FallStatus.ABGELEHNT, wiedervorlageAm = ueberfaellig).faelligkeit(heute))
    }
}
