package de.aysetransport.schaden.ui.mail

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.aysetransport.schaden.AppContainer
import de.aysetransport.schaden.data.AnlagenSpeicher
import de.aysetransport.schaden.data.DateiZuGrossException
import de.aysetransport.schaden.data.EinstellungenSpeicher
import de.aysetransport.schaden.data.CrmRepository
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.model.MailKontoArt
import de.aysetransport.schaden.data.model.MailNachricht
import de.aysetransport.schaden.mail.MailZuordnung
import androidx.compose.material3.FilterChip
import de.aysetransport.schaden.data.db.SchadensfallDao
import de.aysetransport.schaden.data.model.Anlage
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag
import de.aysetransport.schaden.domain.Bearbeitungsschritte
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
    /** Ohne Fall: Mail an diese Firma (Kontakthistorie). */
    private val firmaUuid: String = "",
    /** Vorausgefüllte Empfängeradresse (z. B. eines Ansprechpartners). */
    private val anVorgabe: String = "",
    /** Antwort auf eine Mail aus dem Postfach. */
    private val antwortAufMail: Long = 0,
    private val crm: CrmRepository? = null,
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
    var fall by mutableStateOf<Schadensfall?>(null); private set
    /** Verlaufseinträge, die sich dem Auftraggeber mitteilen lassen (neueste zuerst). */
    var schritte by mutableStateOf<List<VerlaufEintrag>>(emptyList()); private set
    var schritteVorauswahl by mutableStateOf<Set<String>>(emptySet()); private set
    var bccAuftraggeber by mutableStateOf(false); private set
    var laedtDateien by mutableStateOf(false); private set
    /** Ohne Fall: Name der Firma für die Titelzeile. */
    var firmenName by mutableStateOf(""); private set
    /** Über welches Postfach gesendet wird; die Auswahl erscheint nur, wenn beide eingerichtet sind. */
    var konto by mutableStateOf(MailKontoArt.SCHADEN); private set
    var konten by mutableStateOf<List<Pair<MailKontoArt, String>>>(emptyList()); private set
    private var zuordnung = MailZuordnung(fallId = fallId.takeIf { it > 0 })
    private var antwortAufId = ""
    private var ausgangsstand = Triple("", "", "")

    val hatAenderungen: Boolean get() = !laedt && Triple(an, betreff, text) != ausgangsstand

    /** Adresse des Auftraggebers, wenn er in BCC genommen werden kann (er ist nicht ohnehin Empfänger). */
    val auftraggeberFuerBcc: String?
        get() = fall?.auftraggeberEmail?.trim()?.takeIf { it.isNotEmpty() && !an.contains(it, ignoreCase = true) }

    init {
        viewModelScope.launch {
            konten = postfach.eingerichteteKonten().map { it.first to it.second.adresse }
            val antwortMail = antwortAufMail.takeIf { it > 0 }?.let { dao.mailPerId(it) }
            val fallZiel = fallId.takeIf { it > 0 } ?: antwortMail?.fallId ?: 0L
            konto = postfach.kontoFuer(antwortMail?.konto ?: if (fallZiel > 0) MailKontoArt.SCHADEN else MailKontoArt.ALLGEMEIN) ?: MailKontoArt.SCHADEN
            if (fallZiel == 0L) {
                ohneFall(antwortMail)
                return@launch
            }
            zuordnung = MailZuordnung(fallId = fallZiel)
            val fall = repository.ladeFall(fallZiel) ?: return@launch
            this@MailSchreibenViewModel.fall = fall
            fallnummer = fall.fallnummer
            val absender = einstellungen.aktuell().absender
            anlagen = repository.beobachteAnlagen(fallId).first().filter { it.istLokalVorhanden }
            val verlauf = repository.beobachteVerlauf(fallId).first()
            schritte = Bearbeitungsschritte.auswaehlbar(verlauf)
            schritteVorauswahl = Bearbeitungsschritte.vorauswahl(fall, verlauf)
            var empfaengerArt = Empfaenger.GESCHAEDIGTER
            val antwort = antwortMail ?: antwortAufVerlauf.takeIf { it.isNotEmpty() }?.let { dao.mailZuVerlauf(it) }
            when {
                antwort != null -> {
                    antwortAufId = antwort.messageId
                    an = antwort.von
                    betreff = if (antwort.betreff.startsWith("Re:", ignoreCase = true) || antwort.betreff.startsWith("AW:", ignoreCase = true)) antwort.betreff else "Re: ${antwort.betreff}"
                    val zitat = antwort.text.lines().joinToString("\n") { "> $it" }
                    // Anrede passend zum Absender, wenn er als Geschädigter oder Auftraggeber eingetragen ist
                    val e = listOf(Empfaenger.GESCHAEDIGTER, Empfaenger.AUFTRAGGEBER).firstOrNull { it.email(fall).equals(antwort.von, ignoreCase = true) }
                    empfaengerArt = e ?: Empfaenger.DRITTE
                    text = Textvorlagen.ohneVorlage(e ?: Empfaenger.DRITTE, fall, absender).text +
                        "\n\nAm ${Datum.formatMitUhrzeit(antwort.zeitpunkt)} schrieb ${antwort.von}:\n$zitat"
                }
                vorlage.isNotEmpty() -> {
                    val v = Vorlage.valueOf(vorlage)
                    val n = Textvorlagen.erstelle(v, fall, absender, LocalDate.now())
                    an = v.empfaenger.email(fall); betreff = n.betreff; text = n.text
                    empfaengerArt = v.empfaenger
                    // Beim Sachstand gleich mitteilen, was seit der letzten Mail an den Auftraggeber passiert ist.
                    val neu = schritte.filter { it.uuid in schritteVorauswahl }
                    if (v == Vorlage.SACHSTAND_AUFTRAGGEBER && neu.isNotEmpty()) text = Bearbeitungsschritte.einfuegen(text, Bearbeitungsschritte.alsText(neu, fall))
                }
                else -> {
                    val e = Empfaenger.entries.firstOrNull { it.name == empfaenger } ?: Empfaenger.GESCHAEDIGTER
                    val n = Textvorlagen.ohneVorlage(e, fall, absender)
                    an = e.email(fall); betreff = n.betreff; text = n.text
                    empfaengerArt = e
                }
            }
            betreff = Textvorlagen.mitZeichen(betreff, empfaengerArt, fall)
            // „[Fall …]“ ersetzt beim Versand aus der App den Zusatz „unser Vorgang …“.
            betreff = MailPostfach.mitFallnummer(betreff.replace(" – unser Vorgang ${fall.fallnummer}", ""), fall.fallnummer)
            ausgangsstand = Triple(an, betreff, text)
            laedt = false
        }
    }

    /** Mail an eine Firma, einen Ansprechpartner oder als Antwort auf eine Mail ohne Fall. */
    private suspend fun ohneFall(antwort: MailNachricht?) {
        val absender = einstellungen.aktuell().absender
        val firmaId = firmaUuid.ifEmpty { antwort?.auftraggeberUuid.orEmpty() }
        val firma = firmaId.takeIf { it.isNotEmpty() }?.let { crm?.ladeFirma(it) }
        firmenName = firma?.name.orEmpty()
        zuordnung = MailZuordnung(auftraggeberUuid = firma?.uuid, kundeRef = antwort?.kundeRef.orEmpty())
        val kontakte = firma?.let { crm?.ladeKontakte(it.uuid) }.orEmpty()
        if (antwort != null) {
            antwortAufId = antwort.messageId
            an = if (antwort.eingehend) antwort.von else antwort.an
            betreff = if (antwort.betreff.startsWith("Re:", ignoreCase = true) || antwort.betreff.startsWith("AW:", ignoreCase = true)) antwort.betreff else "Re: ${antwort.betreff}"
            val name = kontakte.firstOrNull { it.email.equals(an, ignoreCase = true) }?.name.orEmpty()
            text = Textvorlagen.freieNachricht(name, absender) + "\n\nAm ${Datum.formatMitUhrzeit(antwort.zeitpunkt)} schrieb ${antwort.von}:\n" +
                antwort.text.lines().joinToString("\n") { "> $it" }
        } else {
            an = anVorgabe.ifEmpty { firma?.email.orEmpty() }
            val name = kontakte.firstOrNull { an.isNotBlank() && it.email.equals(an, ignoreCase = true) }?.name.orEmpty()
            text = Textvorlagen.freieNachricht(name, absender)
        }
        ausgangsstand = Triple(an, betreff, text)
        laedt = false
    }

    fun setzeKonto(wert: MailKontoArt) { konto = wert }

    fun setzeAn(wert: String) { an = wert; fehler = null }
    fun setzeBetreff(wert: String) { betreff = wert }
    fun setzeText(wert: String) { text = wert }
    fun umschalten(anlage: Anlage) { gewaehlt = if (anlage.id in gewaehlt) gewaehlt - anlage.id else gewaehlt + anlage.id }
    fun setzeBccAuftraggeber(wert: Boolean) { bccAuftraggeber = wert }

    /** Fügt die gewählten Bearbeitungsschritte als Absatz in die Nachricht ein. */
    fun schritteEinfuegen(auswahl: Set<String>) {
        val f = fall ?: return
        val eintraege = schritte.filter { it.uuid in auswahl }
        if (eintraege.isNotEmpty()) text = Bearbeitungsschritte.einfuegen(text, Bearbeitungsschritte.alsText(eintraege, f))
    }

    /** Nimmt Dateien vom Gerät zum Fall und hängt sie gleich an die Mail. */
    fun dateienAnhaengen(uris: List<Uri>) {
        if (uris.isEmpty()) return
        laedtDateien = true
        viewModelScope.launch {
            val neu = mutableListOf<Long>()
            for (uri in uris) {
                try {
                    neu += repository.anlageHinzufuegen(fallId, uri).id
                } catch (e: DateiZuGrossException) {
                    fehler = "„${e.anzeigename}“ ist zu groß."
                } catch (e: IOException) {
                    fehler = "Eine Datei konnte nicht gelesen werden."
                }
            }
            anlagen = repository.beobachteAnlagen(fallId).first().filter { it.istLokalVorhanden }
            gewaehlt = gewaehlt + neu
            laedtDateien = false
        }
    }

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
                val bcc = auftraggeberFuerBcc?.takeIf { bccAuftraggeber }.orEmpty()
                postfach.senden(MailEntwurf(adressen.joinToString(", "), betreff, text, antwortAufId, anhaenge, bcc), zuordnung, konto)
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
        fun factory(
            container: AppContainer, fallId: Long, vorlage: String, empfaenger: String, antwortAuf: String,
            firma: String = "", an: String = "", antwortAufMail: Long = 0,
        ) = viewModelFactory {
            initializer {
                MailSchreibenViewModel(
                    fallId, vorlage, empfaenger, antwortAuf, container.repository, container.datenbank.dao(),
                    container.einstellungen, container.anlagenSpeicher, container.postfach,
                    firmaUuid = firma, anVorgabe = an, antwortAufMail = antwortAufMail, crm = container.crm,
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
    var schritteWaehlen by remember { mutableStateOf(false) }
    val dateiAuswahl = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> viewModel.dateienAnhaengen(uris) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            viewModel.fallnummer.isNotEmpty() -> "E-Mail · Fall ${viewModel.fallnummer}"
                            viewModel.firmenName.isNotEmpty() -> "E-Mail · ${viewModel.firmenName}"
                            else -> "E-Mail"
                        },
                    )
                },
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
            if (viewModel.konten.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Von:", style = MaterialTheme.typography.bodyMedium)
                    viewModel.konten.forEach { (art, adresse) ->
                        FilterChip(
                            selected = viewModel.konto == art, onClick = { viewModel.setzeKonto(art) },
                            label = { Text(adresse, maxLines = 1) }, modifier = Modifier.testTag("mail-konto-${art.name}"),
                        )
                    }
                }
            } else {
                viewModel.konten.firstOrNull()?.let { Text("Von: ${it.second}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            OutlinedTextField(
                value = viewModel.an, onValueChange = viewModel::setzeAn, label = { Text("An") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth().testTag("mail-an"),
            )
            OutlinedTextField(
                value = viewModel.betreff, onValueChange = viewModel::setzeBetreff, label = { Text("Betreff") },
                supportingText = { if (viewModel.fallnummer.isNotEmpty()) Text("Die Fallnummer im Betreff ordnet Antworten automatisch diesem Fall zu.") },
                modifier = Modifier.fillMaxWidth().testTag("mail-betreff"),
            )
            OutlinedTextField(
                value = viewModel.text, onValueChange = viewModel::setzeText, label = { Text("Nachricht") }, minLines = 10,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().testTag("mail-text"),
            )
            if (viewModel.schritte.isNotEmpty()) {
                OutlinedButton(onClick = { schritteWaehlen = true }, modifier = Modifier.fillMaxWidth().testTag("mail-schritte")) {
                    Text("Bearbeitungsschritte einfügen")
                }
            }
            viewModel.auftraggeberFuerBcc?.let { adresse ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().testTag("mail-bcc")
                        .toggleable(value = viewModel.bccAuftraggeber, role = Role.Checkbox, onValueChange = viewModel::setzeBccAuftraggeber),
                ) {
                    Checkbox(checked = viewModel.bccAuftraggeber, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                    Column {
                        Text("Auftraggeber in BCC", style = MaterialTheme.typography.bodyLarge)
                        Text(adresse, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (viewModel.fallnummer.isNotEmpty()) {
            Text("Anhänge", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            viewModel.anlagen.forEach { a ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().toggleable(value = a.id in viewModel.gewaehlt, role = Role.Checkbox, onValueChange = { viewModel.umschalten(a) }),
                ) {
                    Checkbox(checked = a.id in viewModel.gewaehlt, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                    Text(a.anzeigename, style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedButton(
                onClick = { dateiAuswahl.launch(arrayOf("*/*")) },
                enabled = !viewModel.laedtDateien,
                modifier = Modifier.fillMaxWidth().testTag("mail-datei"),
            ) { Text(if (viewModel.laedtDateien) "Datei wird übernommen …" else "Datei vom Gerät anhängen") }
            Text(
                "Neue Dateien werden auch beim Fall abgelegt.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
        }
    }
    if (schritteWaehlen) {
        SchritteDialog(viewModel, onFertig = { auswahl ->
            schritteWaehlen = false
            auswahl?.let(viewModel::schritteEinfuegen)
        })
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


/** Auswahl der Verlaufseinträge, die dem Empfänger mitgeteilt werden. `null` = abgebrochen. */
@Composable
private fun SchritteDialog(viewModel: MailSchreibenViewModel, onFertig: (Set<String>?) -> Unit) {
    var auswahl by remember { mutableStateOf(viewModel.schritteVorauswahl) }
    val f = viewModel.fall ?: return
    AlertDialog(
        onDismissRequest = { onFertig(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        title = { Text("Bearbeitungsschritte einfügen") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("schritte-liste")) {
                Text(
                    "Vorausgewählt ist alles seit der letzten Mail an den Auftraggeber. Adressen und Wiedervorlagen werden nicht übernommen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                viewModel.schritte.forEach { e ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = e.uuid in auswahl, role = Role.Checkbox,
                            onValueChange = { auswahl = if (e.uuid in auswahl) auswahl - e.uuid else auswahl + e.uuid },
                        ),
                    ) {
                        Checkbox(checked = e.uuid in auswahl, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                        Column {
                            Text(Datum.formatMitUhrzeit(e.zeitpunkt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Bearbeitungsschritte.beschreibe(e, f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onFertig(auswahl) }, enabled = auswahl.isNotEmpty()) { Text("Einfügen") } },
        dismissButton = { TextButton(onClick = { onFertig(null) }) { Text("Abbrechen") } },
    )
}
