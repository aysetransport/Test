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
import kotlinx.coroutines.flow.receiveAsFlow
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
) : ViewModel() {

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
            meldungsKanal.send("Wiederhergestellt: ${ergebnis.inWorten()}")
        } catch (e: BackupUngueltigException) {
            meldungsKanal.send(e.message ?: "Die Datei ist kein gültiges Backup")
        } catch (e: IOException) {
            meldungsKanal.send("Die Datei konnte nicht gelesen werden")
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
                )
            }
        }
    }
}
