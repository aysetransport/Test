package de.aysetransport.schaden.ui.uebersicht

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.aysetransport.schaden.AppContainer
import de.aysetransport.schaden.cloud.CloudZustand
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.domain.FallFilter
import de.aysetransport.schaden.domain.Fallliste
import de.aysetransport.schaden.domain.Kennzahlen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate

data class UebersichtZustand(
    val laedt: Boolean = true,
    /** Gefilterte und sortierte Fälle der aktuellen Ansicht. */
    val faelle: List<Schadensfall> = emptyList(),
    /** `true`, wenn überhaupt noch kein Fall angelegt wurde. */
    val ganzLeer: Boolean = false,
    val filter: FallFilter = FallFilter.OFFEN,
    val suchtext: String = "",
    val kennzahlen: Kennzahlen = Kennzahlen(),
    val heute: LocalDate,
)

class UebersichtViewModel(
    repository: SchadensfallRepository,
    cloudZustand: Flow<CloudZustand> = flowOf(CloudZustand()),
    private val uhr: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    /** Hinweis, wenn die Cloud verbunden ist, aber nicht abgeglichen werden kann (z. B. PIN ungültig). */
    val cloudHinweis: StateFlow<String?> = cloudZustand
        .map { z -> if (z.verbunden && !z.angemeldet) "Cloud: ${z.fehler.ifEmpty { "PIN erforderlich" }}" else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val filter = MutableStateFlow(FallFilter.OFFEN)
    private val suchtext = MutableStateFlow("")
    private val heute = MutableStateFlow(LocalDate.now(uhr))

    val zustand: StateFlow<UebersichtZustand> =
        combine(repository.beobachteFaelle(), filter, suchtext, heute) { faelle, filter, suchtext, heute ->
            UebersichtZustand(
                laedt = false,
                faelle = Fallliste.filtereUndSortiere(faelle, filter, suchtext, heute),
                ganzLeer = faelle.isEmpty(),
                filter = filter,
                suchtext = suchtext,
                kennzahlen = Fallliste.kennzahlen(faelle, heute),
                heute = heute,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UebersichtZustand(heute = heute.value))

    fun setzeFilter(neu: FallFilter) {
        filter.value = neu
    }

    fun setzeSuchtext(neu: String) {
        suchtext.value = neu
    }

    /** Nach Mitternacht bzw. beim Zurückkehren in die App das Tagesdatum auffrischen. */
    fun aktualisiereHeute() {
        heute.value = LocalDate.now(uhr)
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { UebersichtViewModel(container.repository, container.cloudSpeicher.zustand) }
        }
    }
}
