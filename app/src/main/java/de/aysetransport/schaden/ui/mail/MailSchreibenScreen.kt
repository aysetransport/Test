package de.aysetransport.schaden.ui.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.aysetransport.schaden.AppContainer
import de.aysetransport.schaden.data.AnlagenSpeicher
import de.aysetransport.schaden.data.EinstellungenSpeicher
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.db.SchadensfallDao
import de.aysetransport.schaden.data.model.Anlage
import de.aysetransport.schaden.domain.Datum
import de.aysetransport.schaden.domain.Empfaenger
import de.aysetransport.schaden.domain.Textvorlagen
import de.aysetransport.schaden.domain.Vorlage
import de.aysetransport.schaden.mail.MailAnhang
import de.aysetransport.schaden.mail.MailEntwurf
import de.aysetransport.schaden.mail.MailPostfach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

/**
 * Inhalt des Schreibfensters. Vorbefüllt aus einer Vorlage, als leere Mail an einen Empfänger
 * oder als Antwort auf eine empfangene Mail.
 */
class MailSchreibenViewModel(
    private val fallId: Long,
    private val vorlage: String,
    private val empfaenger: String,
    private val antwortAufVerlauf: String,
    private val repository: SchadensfallRepository,
    private val dao: SchadensfallDao,
    private val einstellungen: EinstellungenSpeicher,
    private val anlagenSpeicher: AnlagenSpeicher,
    private val postfach: MailPostfach,
) : ViewModel() {
    var laedt by mutableStateOf(true); private set
    var an by mutableStateOf(""); private set
    var betreff by mutableStateOf(""); private set
    var text by mutableStateOf(""); private set
    var anlagen by mutableStateOf<List<Anlage>>(emptyList()); private set
    var gewaehlt by mutableStateOf<Set<Long>>(emptySet()); private set
    var sendet by mutableStateOf(false); private set
    var fehler by mutableStateOf<String?>(null); private set
    var fallnummer by mutableStateOf(""); private set
    private var antwortAufId = ""
    private var ausgangsstand = Triple("", "", "")

    val hatAenderungen: Boolean get() = !laedt && Triple(an, betreff, text) != ausgangsstand

    init {
        viewModelScope.launch {
            val fall = repository.ladeFall(fallId) ?: return@launch
            fallnummer = fall.fallnummer
            val absender = einstellungen.aktuell().absender
            anlagen = repository.beobachteAnlagen(fallId).first().filter { it.istLokalVorhanden }
            val antwort = antwortAufVerlauf.takeIf { it.isNotEmpty() }?.let { dao.mailZuVerlauf(it) }
            when {
                antwort != null -> {
                    antwortAufId = antwort.messageId
                    an = antwort.von
                    betreff = if (antwort.betreff.startsWith("Re:", ignoreCase = true) || antwort.betreff.startsWith("AW:", ignoreCase = true)) antwort.betreff else "Re: ${antwort.betreff}"
                    val zitat = antwort.text.lines().joinToString("\n") { "> $it" }
                    // Anrede passend zum Absender, wenn er als Geschädigter oder Auftraggeber eingetragen ist
                    val e = listOf(Empfaenger.GESCHAEDIGTER, Empfaenger.AUFTRAGGEBER).firstOrNull { it.email(fall).equals(antwort.von, ignoreCase = true) }
                    text = Textvorlagen.ohneVorlage(e ?: Empfaenger.DRITTE, fall, absender).text +
                        "\n\nAm ${Datum.formatMitUhrzeit(antwort.zeitpunkt)} schrieb ${antwort.von}:\n$zitat"
                }
                vorlage.isNotEmpty() -> {
                    val v = Vorlage.valueOf(vorlage)
                    val n = Textvorlagen.erstelle(v, fall, absender, LocalDate.now())
                    an = v.empfaenger.email(fall); betreff = n.betreff; text = n.text
                }
                else -> {
                    val e = Empfaenger.entries.firstOrNull { it.name == empfaenger } ?: Empfaenger.GESCHAEDIGTER
                    val n = Textvorlagen.ohneVorlage(e, fall, absender)
                    an = e.email(fall); betreff = n.betreff; text = n.text
                }
            }
            // „[Fall …]“ ersetzt beim Versand aus der App den Zusatz „unser Vorgang …“.
            betreff = MailPostfach.mitFallnummer(betreff.replace(" – unser Vorgang ${fall.fallnummer}", ""), fall.fallnummer)
            ausgangsstand = Triple(an, betreff, text)
            laedt = false
        }
    }

    fun setzeAn(wert: String) { an = wert; fehler = null }
    fun setzeBetreff(wert: String) { betreff = wert }
    fun setzeText(wert: String) { text = wert }
    fun umschalten(anlage: Anlage) { gewaehlt = if (anlage.id in gewaehlt) gewaehlt - anlage.id else gewaehlt + anlage.id }

    fun senden(danach: () -> Unit) {
        if (sendet) return
        val adressen = an.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (adressen.isEmpty() || adressen.any { !Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+").matches(it) }) {
            fehler = "Bitte eine gültige Empfängeradresse eintragen."
            return
        }
        val auswahl = anlagen.filter { it.id in gewaehlt }
        if (auswahl.sumOf { it.groesseBytes } > 20L * 1024 * 1024) {
            fehler = "Die Anhänge sind zusammen größer als 20 MB – das nehmen viele Postfächer nicht an."
            return
        }
        sendet = true
        fehler = null
        viewModelScope.launch {
            try {
                val anhaenge = withContext(Dispatchers.IO) {
                    auswahl.map { MailAnhang(it.anzeigename, it.mimeType, anlagenSpeicher.datei(it).readBytes()) }
                }
                postfach.senden(fallId, MailEntwurf(adressen.joinToString(", "), betreff, text, antwortAufId, anhaenge))
                ausgangsstand = Triple(an, betreff, text)
                danach()
            } catch (e: IOException) {
                fehler = e.message ?: "Die E-Mail konnte nicht gesendet werden."
            } finally {
                sendet = false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, fallId: Long, vorlage: String, empfaenger: String, antwortAuf: String) = viewModelFactory {
            initializer {
                MailSchreibenViewModel(
                    fallId, vorlage, empfaenger, antwortAuf, container.repository, container.datenbank.dao(),
                    container.einstellungen, container.anlagenSpeicher, container.postfach,
                )
            }
        }
    }
}

@Composable
fun MailSchreibenScreen(viewModel: MailSchreibenViewModel, onSchliessen: () -> Unit, onGesendet: () -> Unit) {
    var verwerfenFragen by remember { mutableStateOf(false) }
    val schliessen = { if (viewModel.hatAenderungen) verwerfenFragen = true else onSchliessen() }
    BackHandler(enabled = viewModel.hatAenderungen && !viewModel.sendet) { verwerfenFragen = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.fallnummer.isEmpty()) "E-Mail" else "E-Mail · Fall ${viewModel.fallnummer}") },
                navigationIcon = { IconButton(onClick = schliessen) { Icon(Icons.Filled.Close, contentDescription = "Schließen") } },
                actions = {
                    IconButton(onClick = { viewModel.senden(onGesendet) }, enabled = !viewModel.laedt && !viewModel.sendet) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Senden")
                    }
                },
            )
        },
    ) { innen ->
        if (viewModel.laedt) {
            Box(Modifier.fillMaxSize().padding(innen), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(innen).imePadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("mail-schreiben"),
        ) {
            if (viewModel.sendet) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            viewModel.fehler?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(
                value = viewModel.an, onValueChange = viewModel::setzeAn, label = { Text("An") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth().testTag("mail-an"),
            )
            OutlinedTextField(
                value = viewModel.betreff, onValueChange = viewModel::setzeBetreff, label = { Text("Betreff") },
                supportingText = { Text("Die Fallnummer im Betreff ordnet Antworten automatisch diesem Fall zu.") },
                modifier = Modifier.fillMaxWidth().testTag("mail-betreff"),
            )
            OutlinedTextField(
                value = viewModel.text, onValueChange = viewModel::setzeText, label = { Text("Nachricht") }, minLines = 10,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().testTag("mail-text"),
            )
            if (viewModel.anlagen.isNotEmpty()) {
                Text("Anlagen des Falls mitschicken", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                viewModel.anlagen.forEach { a ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().toggleable(value = a.id in viewModel.gewaehlt, role = Role.Checkbox, onValueChange = { viewModel.umschalten(a) }),
                    ) {
                        Checkbox(checked = a.id in viewModel.gewaehlt, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                        Text(a.anzeigename, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    if (verwerfenFragen) {
        AlertDialog(
            onDismissRequest = { verwerfenFragen = false },
            title = { Text("E-Mail verwerfen?") },
            text = { Text("Die E-Mail wurde noch nicht gesendet.") },
            confirmButton = { TextButton(onClick = { verwerfenFragen = false; onSchliessen() }) { Text("Verwerfen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { verwerfenFragen = false }) { Text("Weiter schreiben") } },
        )
    }
}
