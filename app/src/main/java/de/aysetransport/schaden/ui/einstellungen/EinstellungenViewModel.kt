package de.aysetransport.schaden.ui.einstellungen

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.aysetransport.schaden.AppContainer
import de.aysetransport.schaden.cloud.CloudAbgleich
import de.aysetransport.schaden.cloud.CloudFehler
import de.aysetransport.schaden.cloud.CloudPlaner
import de.aysetransport.schaden.cloud.CloudSpeicher
import de.aysetransport.schaden.cloud.CloudZustand
import de.aysetransport.schaden.data.AnlagenSpeicher
import de.aysetransport.schaden.data.BackupErgebnis
import de.aysetransport.schaden.data.BackupManager
import de.aysetransport.schaden.data.BackupUngueltigException
import de.aysetransport.schaden.data.Einstellungen
import de.aysetransport.schaden.data.EinstellungenSpeicher
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.domain.CsvExport
import de.aysetransport.schaden.erinnerung.ErinnerungsPlaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

class EinstellungenViewModel(
    private val appContext: Context,
    private val speicher: EinstellungenSpeicher,
    private val repository: SchadensfallRepository,
    private val backup: BackupManager,
    private val anlagenSpeicher: AnlagenSpeicher,
    private val cloud: CloudAbgleich,
    private val cloudSpeicher: CloudSpeicher,
    offeneAenderungen: Flow<Int>,
) : ViewModel() {

    val cloudZustand: StateFlow<CloudZustand?> = cloudSpeicher.zustand
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val offeneAenderungen: StateFlow<Int> = offeneAenderungen.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Nach erfolgreicher Prüfung der Adresse: welche PIN-Abfrage gezeigt wird. */
    var pinAbfrage by mutableStateOf<PinAbfrage?>(null)
        private set

    var cloudBeschaeftigt by mutableStateOf(false)
        private set

    var pinFehler by mutableStateOf<String?>(null)
        private set

    /** `null`, solange die gespeicherten Einstellungen noch geladen werden. */
    var einstellungen by mutableStateOf<Einstellungen?>(null)
        private set

    /** `true`, während ein Export oder eine Wiederherstellung läuft. */
    var beschaeftigt by mutableStateOf(false)
        private set

    private val meldungsKanal = Channel<String>(Channel.BUFFERED)
    val meldungen: Flow<String> = meldungsKanal.receiveAsFlow()

    init {
        viewModelScope.launch { einstellungen = speicher.aktuell() }
    }

    fun aendere(aenderung: Einstellungen.() -> Einstellungen) {
        val alt = einstellungen ?: return
        val neu = alt.aenderung()
        if (neu == alt) return
        einstellungen = neu // sofort anzeigen; gespeichert wird im Hintergrund
        viewModelScope.launch {
            speicher.aendere { neu }
            val erinnerungGeaendert = alt.erinnerungAktiv != neu.erinnerungAktiv || alt.erinnerungUhrzeit != neu.erinnerungUhrzeit
            if (erinnerungGeaendert) {
                ErinnerungsPlaner.plane(appContext, neu.erinnerungAktiv, neu.erinnerungUhrzeit, bestehendenPlanErsetzen = true)
            }
        }
    }

    /** Schreibt die Fallliste als CSV in den Cache und liefert die Adresse zum Teilen. */
    fun exportiereCsv(danach: (Uri) -> Unit) = ausfuehren {
        val faelle = repository.ladeAlleFaelle()
        if (faelle.isEmpty()) {
            meldungsKanal.send("Es gibt noch keine Fälle zum Exportieren")
            return@ausfuehren
        }
        val csv = CsvExport.erstelle(faelle, repository.ladeAnlagenAnzahl())
        val datei = anlagenSpeicher.exportDatei("Schadensfaelle_${LocalDate.now()}.csv")
        withContext(Dispatchers.IO) { datei.writeText(csv, Charsets.UTF_8) }
        danach(anlagenSpeicher.freigabeUri(datei))
    }

    fun erstelleBackup(ziel: Uri) = ausfuehren {
        try {
            val ausgang = appContext.contentResolver.openOutputStream(ziel) ?: throw IOException("Ziel nicht beschreibbar")
            val ergebnis = ausgang.use { backup.exportiere(it) }
            meldungsKanal.send("Backup erstellt: ${ergebnis.inWorten()}")
        } catch (e: IOException) {
            meldungsKanal.send("Das Backup konnte nicht geschrieben werden")
        }
    }

    fun stelleBackupWiederHer(quelle: Uri) = ausfuehren {
        try {
            val eingang = appContext.contentResolver.openInputStream(quelle) ?: throw IOException("Quelle nicht lesbar")
            val ergebnis = eingang.use { backup.stelleWiederHer(it) }
            // Mit Cloud: wiederhergestellte Fälle abgleichen und den Stand der Cloud neu holen.
            cloudSpeicher.vonVorn()
            if (cloudSpeicher.aktuell().angemeldet) CloudPlaner.bald(appContext, verzoegerungSekunden = 0)
            meldungsKanal.send("Wiederhergestellt: ${ergebnis.inWorten()}")
        } catch (e: BackupUngueltigException) {
            meldungsKanal.send(e.message ?: "Die Datei ist kein gültiges Backup")
        } catch (e: IOException) {
            meldungsKanal.send("Die Datei konnte nicht gelesen werden")
        }
    }

    /** Schritt 1: Adresse prüfen. Danach fragt die Oberfläche nach der PIN. */
    fun cloudPruefen(eingabe: String) {
        val url = eingabe.trim()
        if (!url.startsWith("https://")) {
            melde("Bitte die vollständige Adresse eintragen (beginnt mit https://).")
            return
        }
        cloudAufgabe {
            val eingerichtet = cloud.pruefeAdresse(url)
            pinFehler = null
            pinAbfrage = PinAbfrage(url, neu = !eingerichtet)
        }
    }

    /** Schritt 2: PIN festlegen bzw. prüfen, verbinden und alles abgleichen. */
    fun cloudVerbinden(pin: String, wiederholung: String?) {
        val abfrage = pinAbfrage ?: return
        if (abfrage.neu && pin.length < 4) { pinFehler = "Die PIN braucht mindestens 4 Zeichen."; return }
        if (abfrage.neu && pin != wiederholung) { pinFehler = "Die beiden Eingaben stimmen nicht überein."; return }
        if (cloudBeschaeftigt) return
        cloudBeschaeftigt = true
        viewModelScope.launch {
            try {
                cloud.verbinde(abfrage.url, pin, abfrage.neu)
                pinAbfrage = null
                CloudPlaner.regelmaessig(appContext, true)
                val ergebnis = cloud.abgleichen()
                meldungsKanal.send("Verbunden – ${ergebnis.gesendet} Änderungen hochgeladen, ${ergebnis.empfangen} empfangen")
            } catch (e: CloudFehler) {
                pinFehler = if (e.pinFalsch && !e.gesperrt) "Die PIN war falsch." else e.anzeige
            } catch (e: IOException) {
                if (pinAbfrage != null) pinFehler = "Keine Verbindung zur Cloud." else meldungsKanal.send("Verbunden. Der erste Abgleich folgt, sobald Netz da ist.")
            } finally {
                cloudBeschaeftigt = false
            }
        }
    }

    /** Die PIN ist nicht mehr gültig: neu eingeben, Adresse bleibt. */
    fun pinNeuEingeben() {
        viewModelScope.launch { cloudZustand.value?.url?.takeIf { it.isNotEmpty() }?.let { cloudPruefen(it) } }
    }

    fun pinAbfrageSchliessen() {
        pinAbfrage = null
        pinFehler = null
    }

    fun jetztAbgleichen() = cloudAufgabe {
        val ergebnis = cloud.abgleichen()
        meldungsKanal.send(
            if (ergebnis.gesendet + ergebnis.empfangen == 0) "Alles aktuell"
            else "Abgeglichen: ${ergebnis.gesendet} gesendet, ${ergebnis.empfangen} empfangen",
        )
    }

    /** Trennt die Verbindung. Die Fälle bleiben auf dem Gerät und in der Cloud erhalten. */
    fun cloudTrennen() = viewModelScope.launch {
        cloudSpeicher.trenne()
        CloudPlaner.regelmaessig(appContext, false)
        meldungsKanal.send("Verbindung getrennt. Die Fälle bleiben auf diesem Gerät.")
    }

    private fun cloudAufgabe(aufgabe: suspend () -> Unit) {
        if (cloudBeschaeftigt) return
        cloudBeschaeftigt = true
        viewModelScope.launch {
            try {
                aufgabe()
            } catch (e: CloudFehler) {
                meldungsKanal.send(e.anzeige)
            } catch (e: IOException) {
                meldungsKanal.send(e.message?.takeIf { "Cloud-Skript" in it } ?: "Keine Verbindung zur Cloud. Bitte Adresse und Netz prüfen.")
            } finally {
                cloudBeschaeftigt = false
            }
        }
    }

    fun melde(text: String) {
        meldungsKanal.trySend(text)
    }

    private fun BackupErgebnis.inWorten(): String {
        val faelleText = if (faelle == 1) "1 Fall" else "$faelle Fälle"
        val anlagenText = if (anlagen == 1) "1 Anlage" else "$anlagen Anlagen"
        return "$faelleText, $anlagenText"
    }

    private fun ausfuehren(aufgabe: suspend () -> Unit) {
        if (beschaeftigt) return
        beschaeftigt = true
        viewModelScope.launch {
            try {
                aufgabe()
            } finally {
                beschaeftigt = false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, context: Context) = viewModelFactory {
            initializer {
                EinstellungenViewModel(
                    appContext = context.applicationContext,
                    speicher = container.einstellungen,
                    repository = container.repository,
                    backup = container.backup,
                    anlagenSpeicher = container.anlagenSpeicher,
                    cloud = container.cloud,
                    cloudSpeicher = container.cloudSpeicher,
                    offeneAenderungen = container.datenbank.dao().beobachteOffeneAenderungen(),
                )
            }
        }
    }
}

/** PIN-Abfrage beim Verbinden: neu festlegen oder vorhandene eingeben. */
data class PinAbfrage(val url: String, val neu: Boolean)
