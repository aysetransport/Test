package de.aysetransport.schaden.domain

import de.aysetransport.schaden.data.model.Schadensfall
import java.time.LocalDate

enum class FallFilter(val label: String) {
    FAELLIG("Fällig"),
    OFFEN("Offen"),
    ABGESCHLOSSEN("Abgeschlossen"),
    ALLE("Alle"),
}

data class Kennzahlen(
    val faellig: Int = 0,
    val offen: Int = 0,
    val offeneForderungCent: Long = 0,
)

object Fallliste {

    fun kennzahlen(faelle: List<Schadensfall>, heute: LocalDate): Kennzahlen {
        val offene = faelle.filter { it.status.offen }
        return Kennzahlen(
            faellig = offene.count { it.faelligkeit(heute).istFaellig },
            offen = offene.size,
            offeneForderungCent = offene.sumOf { it.forderungCent ?: 0L },
        )
    }

    /**
     * Filtert nach Reiter und Suchtext und sortiert so, dass oben steht, was zuerst
     * bearbeitet werden muss: offene Fälle nach Wiedervorlage (älteste zuerst), danach offene
     * Fälle ohne Termin, zuletzt abgeschlossene.
     */
    fun filtereUndSortiere(
        faelle: List<Schadensfall>,
        filter: FallFilter,
        suchtext: String,
        heute: LocalDate,
    ): List<Schadensfall> {
        val begriffe = suchtext.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return faelle
            .asSequence()
            .filter { fall ->
                when (filter) {
                    FallFilter.FAELLIG -> fall.faelligkeit(heute).istFaellig
                    FallFilter.OFFEN -> fall.status.offen
                    FallFilter.ABGESCHLOSSEN -> !fall.status.offen
                    FallFilter.ALLE -> true
                }
            }
            .filter { fall -> begriffe.isEmpty() || fall.passtZu(begriffe) }
            .sortedWith(reihenfolge)
            .toList()
    }

    private val reihenfolge: Comparator<Schadensfall> =
        compareBy<Schadensfall> { if (it.status.offen) 0 else 1 }
            .thenBy { if (it.status.offen && it.wiedervorlageAm != null) 0 else 1 }
            .thenBy { if (it.status.offen) it.wiedervorlageAm else null }
            .thenByDescending { it.geaendertAm }

    private fun Schadensfall.passtZu(begriffe: List<String>): Boolean {
        val heuhaufen = listOf(
            fallnummer, kundeName, kundeFirma, kundeTelefon, kundeEmail, kundenAktenzeichen,
            auftraggeber, auftraggeberKontakt, auftraggeberTelefon, auftraggeberEmail, auftraggeberZeichen, auftragsReferenz, tour, fahrzeug, fahrer, beifahrer1, beifahrer2, schadensort,
            beschreibung, versicherungsNummer, status.label,
        ).joinToString("\n").lowercase()
        return begriffe.all { it in heuhaufen }
    }
}
