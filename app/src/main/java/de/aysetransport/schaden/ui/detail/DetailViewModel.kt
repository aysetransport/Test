package de.aysetransport.schaden.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.aysetransport.schaden.AppContainer
import de.aysetransport.schaden.data.AnlagenSpeicher
import de.aysetransport.schaden.data.DateiZuGrossException
import de.aysetransport.schaden.data.Einstellungen
import de.aysetransport.schaden.data.EinstellungenSpeicher
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.model.Anlage
import de.aysetransport.schaden.data.model.Ansprechpartner
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag
import de.aysetransport.schaden.data.model.VerlaufTyp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class DetailZustand(
    val laedt: Boolean = true,
    /** `null` nach dem Laden bedeutet: Der Fall existiert nicht (mehr). */
    val fall: Schadensfall? = null,
    val verlauf: List<VerlaufEintrag> = emptyList(),
    val anlagen: List<Anlage> = emptyList(),
    val einstellungen: Einstellungen = Einstellungen(),
    val heute: LocalDate,
)

class DetailViewModel(
    private val fallId: Long,
    private val repository: SchadensfallRepository,
    einstellungenSpeicher: EinstellungenSpeicher,
    private val anlagenSpeicher: AnlagenSpeicher,
    private val uhr: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val heute = MutableStateFlow(LocalDate.now(uhr))

    val zustand: StateFlow<DetailZustand> = combine(
        repository.beobachteFall(fallId),
        repository.beobachteVerlauf(fallId),
        repository.beobachteAnlagen(fallId),
        einstellungenSpeicher.einstellungen,
        heute,
    ) { fall, verlauf, anlagen, einstellungen, heute ->
        DetailZustand(laedt = false, fall = fall, verlauf = verlauf, anlagen = anlagen, einstellungen = einstellungen, heute = heute)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailZustand(heute = heute.value))

    private val meldungsKanal = Channel<String>(Channel.BUFFERED)

    /** Kurze Rückmeldungen für die Snackbar. */
    val meldungen: Flow<String> = meldungsKanal.receiveAsFlow()

    fun aktualisiereHeute() {
        heute.value = LocalDate.now(uhr)
    }

    fun statusAendern(status: FallStatus, notiz: String) = viewModelScope.launch {
        repository.statusAendern(fallId, status, notiz)
    }

    fun nachhaken(
        kanal: VerlaufTyp,
        ergebnis: String,
        naechsteWiedervorlage: LocalDate?,
        wartetAuf: Ansprechpartner?,
        naechsterSchritt: String,
    ) = viewModelScope.launch {
        repository.nachhakenErfassen(fallId, kanal, ergebnis, naechsteWiedervorlage, wartetAuf, naechsterSchritt)
        meldungsKanal.send("Im Verlauf vermerkt")
    }

    fun wiedervorlageSetzen(datum: LocalDate?, wartetAuf: Ansprechpartner?, naechsterSchritt: String) = viewModelScope.launch {
        repository.wiedervorlageSetzen(fallId, datum, wartetAuf, naechsterSchritt)
    }

    fun verlaufHinzufuegen(typ: VerlaufTyp, text: String) = viewModelScope.launch {
        repository.verlaufHinzufuegen(fallId, typ, text)
    }

    fun verlaufLoeschen(eintrag: VerlaufEintrag) = viewModelScope.launch {
        repository.verlaufLoeschen(eintrag.id)
    }

    fun anlagenHinzufuegen(quellen: List<Uri>) = viewModelScope.launch {
        var hinzugefuegt = 0
        for (quelle in quellen) {
            try {
                repository.anlageHinzufuegen(fallId, quelle)
                hinzugefuegt++
            } catch (e: DateiZuGrossException) {
                meldungsKanal.send("„${e.anzeigename}“ ist größer als 50 MB und wurde nicht übernommen")
            } catch (e: Exception) {
                meldungsKanal.send("Eine Datei konnte nicht gelesen werden")
            }
        }
        if (hinzugefuegt > 1) meldungsKanal.send("$hinzugefuegt Anlagen hinzugefügt")
    }

    fun neueKameraDatei(): File = anlagenSpeicher.neueKameraDatei()

    fun freigabeUri(datei: File): Uri = anlagenSpeicher.freigabeUri(datei)

    fun freigabeUri(anlage: Anlage): Uri = anlagenSpeicher.freigabeUri(anlagenSpeicher.datei(anlage))

    fun datei(anlage: Anlage): File = anlagenSpeicher.datei(anlage)

    /** Übernimmt die Kameraaufnahme als Anlage und räumt die Zwischendatei weg. */
    fun kamerafotoUebernehmen(datei: File, aufgenommen: Boolean) = viewModelScope.launch {
        try {
            if (aufgenommen && datei.length() > 0) {
                val name = "Foto " + LocalDateTime.now(uhr).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH.mm.ss")) + ".jpg"
                repository.anlageHinzufuegen(fallId, Uri.fromFile(datei), name)
            }
        } catch (e: Exception) {
            meldungsKanal.send("Das Foto konnte nicht gespeichert werden")
        } finally {
            datei.delete()
        }
    }

    fun anlageLoeschen(anlage: Anlage) = viewModelScope.launch {
        repository.anlageLoeschen(anlage)
    }

    fun fallLoeschen(danach: () -> Unit) = viewModelScope.launch {
        repository.fallLoeschen(fallId)
        danach()
    }

    fun melde(text: String) {
        meldungsKanal.trySend(text)
    }

    companion object {
        fun factory(container: AppContainer, fallId: Long) = viewModelFactory {
            initializer {
                DetailViewModel(fallId, container.repository, container.einstellungen, container.anlagenSpeicher)
            }
        }
    }
}
