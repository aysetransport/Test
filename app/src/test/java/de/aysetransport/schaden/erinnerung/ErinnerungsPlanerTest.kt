package de.aysetransport.schaden.erinnerung

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class ErinnerungsPlanerTest {

    private val achtUhr = LocalTime.of(8, 0)

    @Test
    fun `vor der Uhrzeit ist der naechste Termin noch heute`() {
        assertEquals(
            LocalDateTime.of(2026, 11, 10, 8, 0),
            ErinnerungsPlaner.naechsterTermin(achtUhr, LocalDateTime.of(2026, 11, 10, 7, 59)),
        )
    }

    @Test
    fun `zur oder nach der Uhrzeit ist der naechste Termin morgen`() {
        assertEquals(
            LocalDateTime.of(2026, 11, 11, 8, 0),
            ErinnerungsPlaner.naechsterTermin(achtUhr, LocalDateTime.of(2026, 11, 10, 8, 0)),
        )
        assertEquals(
            LocalDateTime.of(2027, 1, 1, 8, 0),
            ErinnerungsPlaner.naechsterTermin(achtUhr, LocalDateTime.of(2026, 12, 31, 22, 15)),
        )
    }
}
