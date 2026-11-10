package de.aysetransport.schaden.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class FormatTest {

    @Test
    fun `Betraege werden deutsch formatiert`() {
        assertEquals("1.234,56\u00A0€", Geld.format(123_456))
        assertEquals("0,05\u00A0€", Geld.format(5))
        assertEquals("450,00\u00A0€", Geld.format(45_000))
        assertEquals("1234,56", Geld.formatOhneSymbol(123_456))
    }

    @Test
    fun `deutsche und englische Schreibweise werden gelesen`() {
        assertEquals(123_456L, Geld.parse("1.234,56"))
        assertEquals(123_456L, Geld.parse("1,234.56"))
        assertEquals(123_456L, Geld.parse("1234,56 €"))
        assertEquals(123_456L, Geld.parse(" 1 234,56 EUR "))
        assertEquals(45_000L, Geld.parse("450"))
        assertEquals(1_250L, Geld.parse("12.50"))
        assertEquals(150L, Geld.parse("1,5"))
    }

    @Test
    fun `Punkt mit drei Nachstellen gilt als Tausenderpunkt`() {
        assertEquals(123_400L, Geld.parse("1.234"))
        assertEquals(123_456_700L, Geld.parse("1.234.567"))
    }

    @Test
    fun `unklare oder ungueltige Eingaben werden abgelehnt statt geraten`() {
        assertNull(Geld.parse(""))
        assertNull(Geld.parse("abc"))
        assertNull(Geld.parse("-5"))
        assertNull(Geld.parse("1,234"))
        assertNull(Geld.parse("1,2,3"))
        assertNull(Geld.parse("12.5.1"))
        assertNull(Geld.parse(","))
        assertNull(Geld.parse("99999999999999999999"))
    }

    @Test
    fun `formatierter Betrag laesst sich wieder einlesen`() {
        for (cent in listOf(0L, 1L, 99L, 100L, 123_456L, 99_999_999L)) {
            assertEquals(cent, Geld.parse(Geld.format(cent)))
            assertEquals(cent, Geld.parse(Geld.formatOhneSymbol(cent)))
        }
    }

    @Test
    fun `Datum in deutscher Schreibweise`() {
        assertEquals("03.11.2026", Datum.format(LocalDate.of(2026, 11, 3)))
        assertEquals("Di., 03.11.2026", Datum.formatMitWochentag(LocalDate.of(2026, 11, 3)))
    }
}
