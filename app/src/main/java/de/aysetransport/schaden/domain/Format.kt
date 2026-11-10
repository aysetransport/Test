package de.aysetransport.schaden.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Datumsformate in deutscher Schreibweise. */
object Datum {
    private val kurz = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)
    private val mitWochentag = DateTimeFormatter.ofPattern("EEE, dd.MM.yyyy", Locale.GERMANY)
    private val mitUhrzeit = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.GERMANY)

    fun format(datum: LocalDate): String = kurz.format(datum)

    fun formatMitWochentag(datum: LocalDate): String = mitWochentag.format(datum)

    fun formatMitUhrzeit(zeitpunkt: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        mitUhrzeit.format(zeitpunkt.atZone(zone))
}

/** Geldbeträge in Cent, Ein- und Ausgabe in deutscher Schreibweise (`1.234,56 €`). */
object Geld {
    private val symbole = DecimalFormatSymbols(Locale.GERMANY)

    /** `123456` → `1.234,56 €` */
    fun format(cent: Long): String = DecimalFormat("#,##0.00", symbole).format(BigDecimal(cent).movePointLeft(2)) + "\u00A0€"

    /** `123456` → `1234,56` (für Eingabefelder und den CSV-Export) */
    fun formatOhneSymbol(cent: Long): String = DecimalFormat("0.00", symbole).format(BigDecimal(cent).movePointLeft(2))

    /**
     * Liest einen eingegebenen Betrag. Akzeptiert deutsche (`1.234,56`) und englische
     * (`1,234.56`) Schreibweise sowie ein angehängtes `€`/`EUR`.
     *
     * @return Betrag in Cent oder `null`, wenn die Eingabe kein gültiger, nicht negativer Betrag ist.
     */
    fun parse(eingabe: String): Long? {
        val roh = eingabe
            .replace("€", "")
            .replace("EUR", "", ignoreCase = true)
            .filterNot { it.isWhitespace() || it == '\u00A0' }
        if (roh.isEmpty() || !roh.all { it.isDigit() || it == '.' || it == ',' }) return null

        val letzterPunkt = roh.lastIndexOf('.')
        val letztesKomma = roh.lastIndexOf(',')
        val normalisiert = when {
            // Beide Zeichen: das spätere ist das Dezimaltrennzeichen.
            letzterPunkt >= 0 && letztesKomma >= 0 ->
                if (letztesKomma > letzterPunkt) roh.replace(".", "").replace(',', '.')
                else roh.replace(",", "")
            letztesKomma >= 0 ->
                if (roh.count { it == ',' } > 1) return null else roh.replace(',', '.')
            letzterPunkt >= 0 -> {
                // Nur Punkte: `1.234` und `1.234.567` sind Tausenderpunkte, `12.50` ist ein Dezimalpunkt.
                val gruppen = roh.split('.')
                val tausender = gruppen.drop(1).all { it.length == 3 } && gruppen.first().length in 1..3
                when {
                    tausender -> roh.replace(".", "")
                    gruppen.size == 2 -> roh
                    else -> return null
                }
            }
            else -> roh
        }
        val wert = normalisiert.toBigDecimalOrNull() ?: return null
        if (wert.scale() > 2) return null
        return try {
            wert.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            null // Betrag sprengt den Wertebereich
        }
    }
}
