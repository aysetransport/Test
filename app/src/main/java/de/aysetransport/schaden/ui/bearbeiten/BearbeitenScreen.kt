package de.aysetransport.schaden.ui.bearbeiten

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItem
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.aysetransport.schaden.data.model.Ansprechpartner
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensart
import de.aysetransport.schaden.ui.komponenten.AuswahlFeld
import de.aysetransport.schaden.ui.komponenten.DatumFeld

@Composable
fun BearbeitenScreen(
    viewModel: BearbeitenViewModel,
    onSchliessen: () -> Unit,
    onGespeichert: (fallId: Long) -> Unit,
) {
    var verwerfenFragen by remember { mutableStateOf(false) }
    val schliessen = { if (viewModel.hatAenderungen) verwerfenFragen = true else onSchliessen() }
    BackHandler(enabled = viewModel.hatAenderungen) { verwerfenFragen = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.istNeu) "Neuer Schadensfall" else "Fall bearbeiten") },
                navigationIcon = {
                    IconButton(onClick = schliessen) { Icon(Icons.Filled.Close, contentDescription = "Schließen") }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.speichern(onGespeichert) },
                        enabled = !viewModel.laedt && !viewModel.speichert,
                    ) { Text("Speichern") }
                },
            )
        },
    ) { innen ->
        if (viewModel.laedt) {
            Box(Modifier.fillMaxSize().padding(innen), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val f = viewModel.formular
        val fehler = viewModel.fehler
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innen)
                .consumeWindowInsets(innen)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)
                .testTag("formular"),
        ) {
            fehler[Formularfeld.ALLGEMEIN]?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Abschnitt("Geschädigter") {
                Textfeld("Name", f.kundeName, { viewModel.aendere { copy(kundeName = it) } }, gross = KeyboardCapitalization.Words, tag = "feld-name")
                Textfeld("Firma", f.kundeFirma, { viewModel.aendere { copy(kundeFirma = it) } }, gross = KeyboardCapitalization.Words)
                Textfeld("Telefon", f.kundeTelefon, { viewModel.aendere { copy(kundeTelefon = it) } }, tastatur = KeyboardType.Phone, tag = "feld-telefon")
                Textfeld(
                    "E-Mail",
                    f.kundeEmail,
                    { viewModel.aendere { copy(kundeEmail = it) } },
                    tastatur = KeyboardType.Email,
                    fehler = fehler[Formularfeld.EMAIL],
                )
                Textfeld("Anschrift", f.kundeAnschrift, { viewModel.aendere { copy(kundeAnschrift = it) } }, mehrzeilig = true, gross = KeyboardCapitalization.Words)
                Textfeld("Zeichen / Aktenzeichen des Kunden", f.kundenAktenzeichen, { viewModel.aendere { copy(kundenAktenzeichen = it) } })
            }

            Abschnitt("Schaden") {
                DatumFeld("Schadensdatum", f.schadensdatum, { viewModel.aendere { copy(schadensdatum = it) } })
                DatumFeld("Gemeldet am", f.meldedatum, { viewModel.aendere { copy(meldedatum = it) } })
                AuswahlFeld(
                    label = "Schadensart",
                    auswahl = f.schadensart,
                    optionen = Schadensart.entries.map { it to it.label },
                    onAuswahl = { viewModel.aendere { copy(schadensart = it) } },
                )
                Textfeld(
                    "Was ist passiert?",
                    f.beschreibung,
                    { viewModel.aendere { copy(beschreibung = it) } },
                    mehrzeilig = true,
                    gross = KeyboardCapitalization.Sentences,
                    tag = "feld-beschreibung",
                )
                Textfeld("Schadensort / Lieferadresse", f.schadensort, { viewModel.aendere { copy(schadensort = it) } }, gross = KeyboardCapitalization.Words)
                Textfeld(
                    "Geforderter Betrag (€)",
                    f.forderung,
                    { viewModel.aendere { copy(forderung = it) } },
                    tastatur = KeyboardType.Decimal,
                    fehler = fehler[Formularfeld.FORDERUNG],
                    tag = "feld-forderung",
                )
            }

            Abschnitt("Auftraggeber") {
                AuftraggeberAuswahl(viewModel)
                Textfeld("Firma", f.auftraggeber, { viewModel.aendere { copy(auftraggeber = it) } }, gross = KeyboardCapitalization.Words, tag = "feld-auftraggeber")
                Textfeld("Ansprechpartner", f.auftraggeberKontakt, { viewModel.aendere { copy(auftraggeberKontakt = it) } }, gross = KeyboardCapitalization.Words, tag = "feld-auftraggeber-kontakt")
                Textfeld("Telefon", f.auftraggeberTelefon, { viewModel.aendere { copy(auftraggeberTelefon = it) } }, tastatur = KeyboardType.Phone, tag = "feld-auftraggeber-telefon")
                Textfeld(
                    "E-Mail",
                    f.auftraggeberEmail,
                    { viewModel.aendere { copy(auftraggeberEmail = it) } },
                    tastatur = KeyboardType.Email,
                    fehler = fehler[Formularfeld.AUFTRAGGEBER_EMAIL],
                    tag = "feld-auftraggeber-email",
                )
                Textfeld("Zeichen des Auftraggebers (steht im Mailbetreff)", f.auftraggeberZeichen, { viewModel.aendere { copy(auftraggeberZeichen = it) } }, tag = "feld-auftraggeber-zeichen")
                Textfeld("Auftrags- / Lieferschein-Nr.", f.auftragsReferenz, { viewModel.aendere { copy(auftragsReferenz = it) } })
            }

            Abschnitt("Transport") {
                Textfeld("Tour", f.tour, { viewModel.aendere { copy(tour = it) } }, gross = KeyboardCapitalization.Sentences, tag = "feld-tour")
                Textfeld("Fahrzeug / Kennzeichen", f.fahrzeug, { viewModel.aendere { copy(fahrzeug = it) } }, gross = KeyboardCapitalization.Characters)
                Textfeld("Fahrer", f.fahrer, { viewModel.aendere { copy(fahrer = it) } }, gross = KeyboardCapitalization.Words, tag = "feld-fahrer")
                Textfeld("Beifahrer 1", f.beifahrer1, { viewModel.aendere { copy(beifahrer1 = it) } }, gross = KeyboardCapitalization.Words, tag = "feld-beifahrer1")
                Textfeld("Beifahrer 2", f.beifahrer2, { viewModel.aendere { copy(beifahrer2 = it) } }, gross = KeyboardCapitalization.Words, tag = "feld-beifahrer2")
            }

            Abschnitt("Bearbeitung") {
                AuswahlFeld(
                    label = "Status",
                    auswahl = f.status,
                    optionen = FallStatus.entries.map { it to it.label },
                    onAuswahl = { viewModel.aendere { copy(status = it) } },
                )
                if (f.status.offen) {
                    DatumFeld(
                        "Wiedervorlage am",
                        f.wiedervorlageAm,
                        { viewModel.aendere { copy(wiedervorlageAm = it) } },
                        hinweis = "An diesem Tag erinnert die App ans Nachhaken.",
                    )
                    AuswahlFeld<Ansprechpartner?>(
                        label = "Wartet auf",
                        auswahl = f.wartetAuf,
                        optionen = listOf<Pair<Ansprechpartner?, String>>(null to "–") + Ansprechpartner.entries.map { it to it.label },
                        onAuswahl = { viewModel.aendere { copy(wartetAuf = it) } },
                    )
                    Textfeld(
                        "Nächster Schritt",
                        f.wiedervorlageNotiz,
                        { viewModel.aendere { copy(wiedervorlageNotiz = it) } },
                        gross = KeyboardCapitalization.Sentences,
                    )
                }
            }

            Abschnitt("Regulierung") {
                Textfeld(
                    "Vergleichs- / Regulierungsbetrag (€)",
                    f.vergleich,
                    { viewModel.aendere { copy(vergleich = it) } },
                    tastatur = KeyboardType.Decimal,
                    fehler = fehler[Formularfeld.VERGLEICH],
                )
                DatumFeld("Zahlungsfrist", f.zahlungsfrist, { viewModel.aendere { copy(zahlungsfrist = it) } })
                Textfeld("Schadennummer der Versicherung", f.versicherungsNummer, { viewModel.aendere { copy(versicherungsNummer = it) } })
            }
        }
    }

    if (verwerfenFragen) {
        AlertDialog(
            onDismissRequest = { verwerfenFragen = false },
            title = { Text("Änderungen verwerfen?") },
            text = { Text("Die Eingaben wurden noch nicht gespeichert.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        verwerfenFragen = false
                        onSchliessen()
                    },
                ) { Text("Verwerfen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { verwerfenFragen = false }) { Text("Weiter bearbeiten") } },
        )
    }
}

@Composable
private fun Abschnitt(titel: String, inhalt: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row { Text(titel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            inhalt()
        }
    }
}

@Composable
private fun Textfeld(
    label: String,
    wert: String,
    onChange: (String) -> Unit,
    mehrzeilig: Boolean = false,
    tastatur: KeyboardType = KeyboardType.Text,
    gross: KeyboardCapitalization = KeyboardCapitalization.None,
    fehler: String? = null,
    tag: String? = null,
) {
    OutlinedTextField(
        value = wert,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = !mehrzeilig,
        minLines = if (mehrzeilig) 3 else 1,
        isError = fehler != null,
        supportingText = fehler?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = tastatur,
            capitalization = gross,
            imeAction = if (mehrzeilig) ImeAction.Default else ImeAction.Next,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/** Knopf „Aus Liste wählen“ mit Suchfeld – füllt Firma und Kontaktdaten des Auftraggebers. */
@Composable
private fun AuftraggeberAuswahl(viewModel: BearbeitenViewModel) {
    val liste by viewModel.auftraggeberListe.collectAsStateWithLifecycle()
    var offen by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { offen = true },
        enabled = liste.isNotEmpty(),
        modifier = Modifier.testTag("auftraggeber-auswahl"),
    ) { Text(if (liste.isEmpty()) "Noch keine gespeicherten Auftraggeber" else "Aus Liste wählen (${liste.size})") }
    if (!offen) return

    var suche by remember { mutableStateOf("") }
    val treffer = liste.filter { e ->
        suche.isBlank() || listOf(e.name, e.kontakt, e.telefon, e.email).any { it.contains(suche.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = { offen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
        title = { Text("Auftraggeber wählen") },
        text = {
            Column {
                OutlinedTextField(
                    value = suche,
                    onValueChange = { suche = it },
                    placeholder = { Text("Suchen …") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(treffer, key = { it.uuid }) { e ->
                        ListItem(
                            headlineContent = { Text(e.name) },
                            supportingContent = {
                                val zeile = listOf(e.kontakt, e.telefon, e.email).filter { it.isNotBlank() }.joinToString(" · ")
                                if (zeile.isNotEmpty()) Text(zeile)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                viewModel.auftraggeberUebernehmen(e)
                                offen = false
                            },
                        )
                    }
                }
                if (treffer.isEmpty()) Text("Kein Treffer.", modifier = Modifier.padding(8.dp))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { offen = false }) { Text("Abbrechen") } },
    )
}
