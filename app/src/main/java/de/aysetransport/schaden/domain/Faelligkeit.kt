package de.aysetransport.schaden.domain

import de.aysetransport.schaden.data.model.Schadensfall
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Wie dringend bei einem Fall nachgehakt werden muss. */
sealed interface Faelligkeit {
    /** Keine Wiedervorlage gesetzt oder Fall abgeschlossen. */
    data object Keine : Faelligkeit

    data class Ueberfaellig(val tage: Long) : Faelligkeit

    data object Heute : Faelligkeit

    data class Kommend(val tage: Long) : Faelligkeit

    /** Der Fall gehört heute auf den Tisch. */
    val istFaellig: Boolean get() = this is Ueberfaellig || this is Heute

    fun text(): String = when (this) {
        Keine -> "Keine Wiedervorlage"
        is Ueberfaellig -> if (tage == 1L) "seit gestern überfällig" else "seit $tage Tagen überfällig"
        Heute -> "heute fällig"
        is Kommend -> if (tage == 1L) "morgen fällig" else "in $tage Tagen fällig"
    }

    companion object {
        fun von(wiedervorlageAm: LocalDate?, heute: LocalDate): Faelligkeit {
            if (wiedervorlageAm == null) return Keine
            val tage = ChronoUnit.DAYS.between(heute, wiedervorlageAm)
            return when {
                tage < 0 -> Ueberfaellig(-tage)
                tage == 0L -> Heute
                else -> Kommend(tage)
            }
        }
    }
}

/** Abgeschlossene Fälle sind nie fällig – auch wenn noch ein altes Datum eingetragen ist. */
fun Schadensfall.faelligkeit(heute: LocalDate): Faelligkeit =
    if (status.offen) Faelligkeit.von(wiedervorlageAm, heute) else Faelligkeit.Keine
