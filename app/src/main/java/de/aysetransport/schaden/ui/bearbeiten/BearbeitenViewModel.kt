package de.aysetransport.schaden.ui.bearbeiten

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.aysetransport.schaden.AppContainer
import de.aysetransport.schaden.data.EinstellungenSpeicher
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.model.Ansprechpartner
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensart
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.domain.Geld
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

/** Inhalt des Formulars. Beträge bleiben Text, bis gespeichert wird. */
data class Formular(
    val kundeName: String = "",
    val kundeFirma: String = "",
    val kundeTelefon: String = "",
    val kundeEmail: String = "",
    val kundeAnschrift: String = "",
    val kundenAktenzeichen: String = "",
    val auftraggeber: String = "",
    val auftraggeberKontakt: String = "",
    val auftraggeberTelefon: String = "",
    val auftraggeberEmail: String = "",
    val auftraggeberZeichen: String = "",
    val auftragsReferenz: String = "",
    val tour: String = "",
    val fahrzeug: String = "",
    val fahrer: String = "",
    val beifahrer1: String = "",
    val beifahrer2: String = "",
    val schadensort: String = "",
    val schadensdatum: LocalDate? = null,
    val meldedatum: LocalDate? = null,
    val schadensart: Schadensart = Schadensart.WARE,
    val beschreibung: String = "",
    val forderung: String = "",
    val status: FallStatus = FallStatus.NEU,
    val wartetAuf: Ansprechpartner? = null,
    val wiedervorlageAm: LocalDate? = null,
    val wiedervorlageNotiz: String = "",
    val vergleich: String = "",
    val zahlungsfrist: LocalDate? = null,
    val versicherungsNummer: String = "",
) {
    companion object {
        fun von(fall: Schadensfall) = Formular(
            kundeName = fall.kundeName,
            kundeFirma = fall.kundeFirma,
            kundeTelefon = fall.kundeTelefon,
            kundeEmail = fall.kundeEmail,
            kundeAnschrift = fall.kundeAnschrift,
            kundenAktenzeichen = fall.kundenAktenzeichen,
            auftraggeber = fall.auftraggeber,
            auftraggeberKontakt = fall.auftraggeberKontakt,
            auftraggeberTelefon = fall.auftraggeberTelefon,
            auftraggeberEmail = fall.auftraggeberEmail,
            auftraggeberZeichen = fall.auftraggeberZeichen,
            auftragsReferenz = fall.auftragsReferenz,
            tour = fall.tour,
            fahrzeug = fall.fahrzeug,
            fahrer = fall.fahrer,
            beifahrer1 = fall.beifahrer1,
            beifahrer2 = fall.beifahrer2,
            schadensort = fall.schadensort,
            schadensdatum = fall.schadensdatum,
            meldedatum = fall.meldedatum,
            schadensart = fall.schadensart,
            beschreibung = fall.beschreibung,
            forderung = fall.forderungCent?.let(Geld::formatOhneSymbol).orEmpty(),
            status = fall.status,
            wartetAuf = fall.wartetAuf,
            wiedervorlageAm = fall.wiedervorlageAm,
            wiedervorlageNotiz = fall.wiedervorlageNotiz,
            vergleich = fall.vergleichCent?.let(Geld::formatOhneSymbol).orEmpty(),
            zahlungsfrist = fall.zahlungsfrist,
            versicherungsNummer = fall.versicherungsNummer,
        )
    }
}

enum class Formularfeld { ALLGEMEIN, FORDERUNG, VERGLEICH, EMAIL, AUFTRAGGEBER_EMAIL }

class BearbeitenViewModel(
    private val fallId: Long,
    private val repository: SchadensfallRepository,
    private val einstellungen: EinstellungenSpeicher,
    private val uhr: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    val istNeu: Boolean get() = fallId == 0L

    /** Gespeicherte Auftraggeber zum Auswählen. */
    val auftraggeberListe: StateFlow<List<AuftraggeberEintrag>> = repository.beobachteAuftraggeber()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Übernimmt Firma und Kontaktdaten eines gespeicherten Auftraggebers ins Formular. */
    fun auftraggeberUebernehmen(eintrag: AuftraggeberEintrag) = aendere {
        copy(
            auftraggeber = eintrag.name,
            auftraggeberKontakt = eintrag.kontakt,
            auftraggeberTelefon = eintrag.telefon,
            auftraggeberEmail = eintrag.email,
        )
    }

    var laedt by mutableStateOf(true)
        private set
    var formular by mutableStateOf(Formular())
        private set
    var fehler by mutableStateOf<Map<Formularfeld, String>>(emptyMap())
        private set
    var speichert by mutableStateOf(false)
        private set

    private var original: Schadensfall? = null
    private var ausgangsstand = Formular()

    /** `true`, sobald etwas eingegeben wurde, das beim Verlassen verloren ginge. */
    val hatAenderungen: Boolean get() = !laedt && formular != ausgangsstand

    init {
        viewModelScope.launch {
            val vorhanden = if (istNeu) null else repository.ladeFall(fallId)
            original = vorhanden
            val start = if (vorhanden != null) {
                Formular.von(vorhanden)
            } else {
                val heute = LocalDate.now(uhr)
                Formular(
                    meldedatum = heute,
                    wiedervorlageAm = heute.plusDays(einstellungen.aktuell().standardWiedervorlageTage.toLong()),
                )
            }
            formular = start
            ausgangsstand = start
            laedt = false
        }
    }

    fun aendere(aenderung: Formular.() -> Formular) {
        formular = formular.aenderung()
        if (fehler.isNotEmpty()) fehler = emptyMap()
    }

    fun speichern(danach: (fallId: Long) -> Unit) {
        if (speichert || laedt) return
        val f = formular
        val gefunden = buildMap {
            if (f.kundeName.isBlank() && f.kundeFirma.isBlank() && f.beschreibung.isBlank()) {
                put(Formularfeld.ALLGEMEIN, "Bitte mindestens Name, Firma oder Schadensbeschreibung angeben.")
            }
            if (f.forderung.isNotBlank() && Geld.parse(f.forderung) == null) {
                put(Formularfeld.FORDERUNG, "Kein gültiger Betrag, z. B. 1.250,00")
            }
            if (f.vergleich.isNotBlank() && Geld.parse(f.vergleich) == null) {
                put(Formularfeld.VERGLEICH, "Kein gültiger Betrag, z. B. 1.250,00")
            }
            if (f.kundeEmail.isNotBlank() && !EMAIL_MUSTER.matches(f.kundeEmail.trim())) {
                put(Formularfeld.EMAIL, "Die E-Mail-Adresse sieht unvollständig aus.")
            }
            if (f.auftraggeberEmail.isNotBlank() && !EMAIL_MUSTER.matches(f.auftraggeberEmail.trim())) {
                put(Formularfeld.AUFTRAGGEBER_EMAIL, "Die E-Mail-Adresse sieht unvollständig aus.")
            }
        }
        if (gefunden.isNotEmpty()) {
            fehler = gefunden
            return
        }

        speichert = true
        viewModelScope.launch {
            val basis = original ?: Schadensfall()
            val fall = basis.copy(
                kundeName = f.kundeName.trim(),
                kundeFirma = f.kundeFirma.trim(),
                kundeTelefon = f.kundeTelefon.trim(),
                kundeEmail = f.kundeEmail.trim(),
                kundeAnschrift = f.kundeAnschrift.trim(),
                kundenAktenzeichen = f.kundenAktenzeichen.trim(),
                auftraggeber = f.auftraggeber.trim(),
                auftraggeberKontakt = f.auftraggeberKontakt.trim(),
                auftraggeberTelefon = f.auftraggeberTelefon.trim(),
                auftraggeberEmail = f.auftraggeberEmail.trim(),
                auftraggeberZeichen = f.auftraggeberZeichen.trim(),
                auftragsReferenz = f.auftragsReferenz.trim(),
                tour = f.tour.trim(),
                fahrzeug = f.fahrzeug.trim(),
                fahrer = f.fahrer.trim(),
                beifahrer1 = f.beifahrer1.trim(),
                beifahrer2 = f.beifahrer2.trim(),
                schadensort = f.schadensort.trim(),
                schadensdatum = f.schadensdatum,
                meldedatum = f.meldedatum,
                schadensart = f.schadensart,
                beschreibung = f.beschreibung.trim(),
                forderungCent = Geld.parse(f.forderung),
                status = f.status,
                wartetAuf = f.wartetAuf,
                wiedervorlageAm = f.wiedervorlageAm,
                wiedervorlageNotiz = f.wiedervorlageNotiz.trim(),
                vergleichCent = Geld.parse(f.vergleich),
                zahlungsfrist = f.zahlungsfrist,
                versicherungsNummer = f.versicherungsNummer.trim(),
            )
            val id = if (original == null) {
                repository.anlegen(fall)
            } else {
                repository.speichern(fall)
                fall.id
            }
            ausgangsstand = formular
            speichert = false
            danach(id)
        }
    }

    companion object {
        private val EMAIL_MUSTER = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

        fun factory(container: AppContainer, fallId: Long) = viewModelFactory {
            initializer { BearbeitenViewModel(fallId, container.repository, container.einstellungen) }
        }
    }
}
