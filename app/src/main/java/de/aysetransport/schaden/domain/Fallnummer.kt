package de.aysetransport.schaden.domain

/** Vergibt laufende Fallnummern je Kalenderjahr: `2026-001`, `2026-002`, … */
object Fallnummer {

    fun praefix(jahr: Int): String = "$jahr-"

    /**
     * @param vorhandene bereits vergebene Nummern (beliebige Jahre erlaubt, fremde werden ignoriert)
     */
    fun naechste(jahr: Int, vorhandene: Collection<String>): String {
        val praefix = praefix(jahr)
        val hoechste = vorhandene
            .filter { it.startsWith(praefix) }
            .mapNotNull { it.removePrefix(praefix).toIntOrNull() }
            .maxOrNull() ?: 0
        return praefix + (hoechste + 1).toString().padStart(3, '0')
    }
}
