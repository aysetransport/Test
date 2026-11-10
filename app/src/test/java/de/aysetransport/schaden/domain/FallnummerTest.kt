package de.aysetransport.schaden.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FallnummerTest {

    @Test
    fun `erste Nummer des Jahres`() {
        assertEquals("2026-001", Fallnummer.naechste(2026, emptyList()))
    }

    @Test
    fun `zaehlt nach der hoechsten vergebenen Nummer weiter, auch bei Luecken`() {
        assertEquals("2026-008", Fallnummer.naechste(2026, listOf("2026-001", "2026-007", "2026-003")))
    }

    @Test
    fun `jedes Jahr beginnt neu und fremde Nummern stoeren nicht`() {
        assertEquals("2027-001", Fallnummer.naechste(2027, listOf("2026-041", "2026-042")))
        assertEquals("2026-002", Fallnummer.naechste(2026, listOf("2026-001", "2026-abc", "", "2025-099")))
    }

    @Test
    fun `mehr als 999 Faelle im Jahr sind moeglich`() {
        assertEquals("2026-1000", Fallnummer.naechste(2026, listOf("2026-999")))
    }
}
