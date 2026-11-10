package de.aysetransport.schaden.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import de.aysetransport.schaden.R
import de.aysetransport.schaden.data.model.Anlage
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag
import de.aysetransport.schaden.data.model.VerlaufTyp
import de.aysetransport.schaden.domain.Datum
import de.aysetransport.schaden.domain.Empfaenger
import de.aysetransport.schaden.domain.Geld
import de.aysetransport.schaden.domain.Textvorlagen
import de.aysetransport.schaden.domain.Zusammenfassung
import de.aysetransport.schaden.domain.beifahrerText
import de.aysetransport.schaden.domain.faelligkeit
import de.aysetransport.schaden.ui.komponenten.StatusMarke
import de.aysetransport.schaden.ui.komponenten.farbe
import java.io.File
import java.time.LocalDate

private sealed interface OffenerDialog {
    data object Nachgehakt : OffenerDialog
    data object Termin : OffenerDialog
    data object Status : OffenerDialog
    data object EmailVorlage : OffenerDialog
    data object NachrichtVorlage : OffenerDialog
    data object Anruf : OffenerDialog
    data object FallLoeschen : OffenerDialog
    data class AnlageLoeschen(val anlage: Anlage) : OffenerDialog
    data class VerlaufLoeschen(val eintrag: VerlaufEintrag) : OffenerDialog
}

@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onZurueck: () -> Unit,
    onBearbeiten: () -> Unit,
) {
    val zustand by viewModel.zustand.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<OffenerDialog?>(null) }
    var menueOffen by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.meldungen.collect { snackbar.showSnackbar(it) }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.aktualisiereHeute()
        onPauseOrDispose { }
    }
    // Fall wurde gelöscht (hier oder durch eine Wiederherstellung) – zurück zur Liste.
    LaunchedEffect(zustand.laedt, zustand.fall == null) {
        if (!zustand.laedt && zustand.fall == null) onZurueck()
    }

    // Pfad der laufenden Kameraaufnahme; übersteht auch einen Neustart der App durch das System.
    var kameraPfad by rememberSaveable { mutableStateOf<String?>(null) }
    val kamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { aufgenommen ->
        kameraPfad?.let { viewModel.kamerafotoUebernehmen(File(it), aufgenommen) }
        kameraPfad = null
    }
    val dateiAuswahl = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.anlagenHinzufuegen(uris)
    }

    val fall = zustand.fall
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(fall?.let { "Fall ${it.fallnummer}" }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onZurueck) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (fall != null) {
                        IconButton(onClick = onBearbeiten) { Icon(Icons.Filled.Edit, contentDescription = "Bearbeiten") }
                        IconButton(onClick = { menueOffen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Weitere Aktionen")
                        }
                        DropdownMenu(expanded = menueOffen, onDismissRequest = { menueOffen = false }) {
                            DropdownMenuItem(
                                text = { Text("Zusammenfassung teilen") },
                                onClick = {
                                    menueOffen = false
                                    context.teileText(
                                        betreff = "Schadensfall ${fall.fallnummer}",
                                        text = Zusammenfassung.erstelle(fall, zustand.anlagen.size),
                                        onFehler = viewModel::melde,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Fall löschen") },
                                onClick = {
                                    menueOffen = false
                                    dialog = OffenerDialog.FallLoeschen
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innen ->
        if (fall == null) {
            Box(Modifier.fillMaxSize().padding(innen), contentAlignment = Alignment.Center) {
                if (zustand.laedt) CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innen)
                .consumeWindowInsets(innen)
                .imePadding()
                .testTag("falldetails"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "kopf") { KopfKarte(fall, onStatus = { dialog = OffenerDialog.Status }) }
            item(key = "wiedervorlage") {
                WiedervorlageKarte(
                    fall = fall,
                    heute = zustand.heute,
                    onNachgehakt = { dialog = OffenerDialog.Nachgehakt },
                    onTermin = { dialog = OffenerDialog.Termin },
                )
            }
            item(key = "kontakt") {
                KontaktZeile(
                    fall = fall,
                    onAnrufen = {
                        val mitNummer = listOf(Empfaenger.GESCHAEDIGTER, Empfaenger.AUFTRAGGEBER).filter { it.telefon(fall).isNotEmpty() }
                        if (mitNummer.size > 1) dialog = OffenerDialog.Anruf
                        else mitNummer.firstOrNull()?.let { context.waehle(it.telefon(fall), viewModel::melde) }
                    },
                    onEmail = { dialog = OffenerDialog.EmailVorlage },
                    onNachricht = { dialog = OffenerDialog.NachrichtVorlage },
                )
            }
            item(key = "daten") { DatenKarte(fall) }
            item(key = "anlagen") {
                AnlagenKarte(
                    anlagen = zustand.anlagen,
                    datei = viewModel::datei,
                    onFoto = {
                        val ziel = viewModel.neueKameraDatei()
                        kameraPfad = ziel.path
                        try {
                            kamera.launch(viewModel.freigabeUri(ziel))
                        } catch (e: ActivityNotFoundException) {
                            kameraPfad = null
                            viewModel.melde("Keine Kamera-App gefunden")
                        }
                    },
                    onDatei = {
                        try {
                            dateiAuswahl.launch(arrayOf("*/*"))
                        } catch (e: ActivityNotFoundException) {
                            viewModel.melde("Keine Dateiauswahl verfügbar")
                        }
                    },
                    onOeffnen = { anlage ->
                        val ansehen = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(viewModel.freigabeUri(anlage), anlage.mimeType)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.starte(ansehen, viewModel::melde, fehlertext = "Keine App zum Öffnen dieser Datei gefunden")
                    },
                    onLoeschen = { dialog = OffenerDialog.AnlageLoeschen(it) },
                )
            }
            item(key = "verlauf-kopf") {
                VerlaufKopf(onNotiz = { viewModel.verlaufHinzufuegen(VerlaufTyp.NOTIZ, it) })
            }
            items(zustand.verlauf, key = { "verlauf-${it.id}" }) { eintrag ->
                VerlaufZeile(eintrag, onLangerTipp = { dialog = OffenerDialog.VerlaufLoeschen(eintrag) })
            }
        }
    }

    if (fall != null) {
        when (val offen = dialog) {
            null -> Unit
            OffenerDialog.Nachgehakt, OffenerDialog.Termin -> {
                val modus = if (offen == OffenerDialog.Nachgehakt) WiedervorlageModus.NACHGEHAKT else WiedervorlageModus.TERMIN
                WiedervorlageBlatt(
                    modus = modus,
                    fall = fall,
                    heute = zustand.heute,
                    standardTage = zustand.einstellungen.standardWiedervorlageTage,
                    onSchliessen = { dialog = null },
                    onSpeichern = { kanal, ergebnis, datum, wartetAuf, naechsterSchritt ->
                        dialog = null
                        if (modus == WiedervorlageModus.NACHGEHAKT) {
                            viewModel.nachhaken(kanal, ergebnis, datum, wartetAuf, naechsterSchritt)
                        } else {
                            viewModel.wiedervorlageSetzen(datum, wartetAuf, naechsterSchritt)
                        }
                    },
                )
            }
            OffenerDialog.Status -> StatusDialog(
                aktuell = fall.status,
                onAbbrechen = { dialog = null },
                onBestaetigen = { status, notiz ->
                    dialog = null
                    viewModel.statusAendern(status, notiz)
                },
            )
            OffenerDialog.Anruf -> AnrufDialog(
                fall = fall,
                onAbbrechen = { dialog = null },
                onAuswahl = { e ->
                    dialog = null
                    context.waehle(e.telefon(fall), viewModel::melde)
                },
            )
            OffenerDialog.EmailVorlage -> VorlagenDialog(
                titel = "E-Mail vorbereiten",
                fehlt = { e -> if (e != Empfaenger.DRITTE && e.email(fall).isEmpty()) "keine E-Mail-Adresse hinterlegt" else null },
                onAbbrechen = { dialog = null },
                onAuswahl = { vorlage ->
                    dialog = null
                    val nachricht = Textvorlagen.erstelle(vorlage, fall, zustand.einstellungen.absender, zustand.heute)
                    val empfaenger = vorlage.empfaenger.email(fall)
                    val mailto = (
                        "mailto:" + Uri.encode(empfaenger, "@") +
                            "?subject=" + Uri.encode(nachricht.betreff) +
                            "&body=" + Uri.encode(nachricht.text)
                        ).toUri()
                    if (context.starte(Intent(Intent.ACTION_SENDTO, mailto), viewModel::melde, "Keine E-Mail-App gefunden")) {
                        viewModel.verlaufHinzufuegen(VerlaufTyp.EMAIL, "E-Mail an ${vorlage.empfaenger.label} vorbereitet: ${vorlage.titel}")
                    }
                },
            )
            OffenerDialog.NachrichtVorlage -> VorlagenDialog(
                titel = "Nachricht vorbereiten",
                onAbbrechen = { dialog = null },
                onAuswahl = { vorlage ->
                    dialog = null
                    val nachricht = Textvorlagen.erstelle(vorlage, fall, zustand.einstellungen.absender, zustand.heute)
                    if (context.teileText(betreff = nachricht.betreff, text = nachricht.text, onFehler = viewModel::melde)) {
                        viewModel.verlaufHinzufuegen(VerlaufTyp.NACHRICHT, "Nachricht an ${vorlage.empfaenger.label} vorbereitet: ${vorlage.titel}")
                    }
                },
            )
            OffenerDialog.FallLoeschen -> BestaetigenDialog(
                titel = "Fall ${fall.fallnummer} löschen?",
                text = "Der Fall wird mit Verlauf und allen Anlagen endgültig gelöscht.",
                bestaetigen = "Löschen",
                onAbbrechen = { dialog = null },
                onBestaetigen = {
                    dialog = null
                    viewModel.fallLoeschen(danach = {})
                },
            )
            is OffenerDialog.AnlageLoeschen -> BestaetigenDialog(
                titel = "Anlage entfernen?",
                text = "„${offen.anlage.anzeigename}“ wird aus dem Fall gelöscht.",
                bestaetigen = "Entfernen",
                onAbbrechen = { dialog = null },
                onBestaetigen = {
                    dialog = null
                    viewModel.anlageLoeschen(offen.anlage)
                },
            )
            is OffenerDialog.VerlaufLoeschen -> BestaetigenDialog(
                titel = "Eintrag löschen?",
                text = "Der Verlaufseintrag vom ${Datum.formatMitUhrzeit(offen.eintrag.zeitpunkt)} wird entfernt.",
                bestaetigen = "Löschen",
                onAbbrechen = { dialog = null },
                onBestaetigen = {
                    dialog = null
                    viewModel.verlaufLoeschen(offen.eintrag)
                },
            )
        }
    }
}

// --- Abschnitte ---------------------------------------------------------------------------

@Composable
private fun KopfKarte(fall: Schadensfall, onStatus: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fall.anzeigeName, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = listOfNotNull(
                    fall.schadensart.label,
                    fall.schadensdatum?.let { "am ${Datum.format(it)}" },
                ).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusMarke(fall.status)
                TextButton(onClick = onStatus) { Text("Status ändern") }
                Spacer(Modifier.weight(1f))
                fall.forderungCent?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Forderung", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Geld.format(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun WiedervorlageKarte(
    fall: Schadensfall,
    heute: LocalDate,
    onNachgehakt: () -> Unit,
    onTermin: () -> Unit,
) {
    val faelligkeit = fall.faelligkeit(heute)
    val farbe = if (faelligkeit.istFaellig) faelligkeit.farbe() else null
    Card(
        colors = CardDefaults.cardColors(
            containerColor = farbe?.flaeche ?: MaterialTheme.colorScheme.surfaceContainer,
            contentColor = farbe?.schrift ?: MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wiedervorlage-karte"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_uhr), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Wiedervorlage", style = MaterialTheme.typography.titleMedium)
            }
            if (!fall.status.offen) {
                Text("Der Fall ist abgeschlossen – es wird nicht mehr nachgehakt.", style = MaterialTheme.typography.bodyMedium)
            } else {
                val datum = fall.wiedervorlageAm
                if (datum == null) {
                    Text("Kein Termin geplant.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        text = "${Datum.formatMitWochentag(datum)} · ${faelligkeit.text()}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    val zusatz = listOfNotNull(
                        fall.wartetAuf?.let { "Wartet auf ${it.label}" },
                        fall.wiedervorlageNotiz.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (zusatz.isNotEmpty()) Text(zusatz, style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Button(onClick = onNachgehakt) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Nachgehakt")
                    }
                    // Schrift und Rahmen übernehmen die Schriftfarbe der Karte, damit der Knopf
                    // auch auf roter oder gelber Fläche und im dunklen Design gut erkennbar bleibt.
                    val kartenfarbe = LocalContentColor.current
                    OutlinedButton(
                        onClick = onTermin,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = kartenfarbe),
                        border = BorderStroke(1.dp, kartenfarbe.copy(alpha = 0.6f)),
                    ) {
                        Text(if (datum == null) "Termin setzen" else "Termin ändern")
                    }
                }
            }
        }
    }
}

@Composable
private fun KontaktZeile(
    fall: Schadensfall,
    onAnrufen: () -> Unit,
    onEmail: () -> Unit,
    onNachricht: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = onAnrufen,
            enabled = fall.kundeTelefon.isNotBlank() || fall.auftraggeberTelefon.isNotBlank(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Anrufen", maxLines = 1)
        }
        FilledTonalButton(onClick = onEmail, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("E-Mail", maxLines = 1)
        }
        FilledTonalButton(onClick = onNachricht, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.weight(1f)) {
            Icon(painterResource(R.drawable.ic_nachricht), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Nachricht", maxLines = 1)
        }
    }
}

@Composable
private fun DatenKarte(fall: Schadensfall) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Datenblock(
                titel = "Schaden",
                zeilen = listOf(
                    "Beschreibung" to fall.beschreibung,
                    "Schadensort" to fall.schadensort,
                    "Gemeldet am" to fall.meldedatum?.let(Datum::format).orEmpty(),
                ),
                leerText = "Noch keine Beschreibung erfasst.",
            )
            Datenblock(
                titel = "Auftraggeber",
                zeilen = listOf(
                    "Firma" to fall.auftraggeber,
                    "Ansprechpartner" to fall.auftraggeberKontakt,
                    "Telefon" to fall.auftraggeberTelefon,
                    "E-Mail" to fall.auftraggeberEmail,
                    "Auftrags-Nr." to fall.auftragsReferenz,
                ),
                leerText = "Kein Auftraggeber erfasst.",
            )
            Datenblock(
                titel = "Transport",
                zeilen = listOf(
                    "Tour" to fall.tour,
                    "Fahrzeug" to fall.fahrzeug,
                    "Fahrer" to fall.fahrer,
                    "Beifahrer" to beifahrerText(fall).orEmpty(),
                ),
                leerText = "Keine Angaben zum Transport.",
            )
            Datenblock(
                titel = "Geschädigter",
                zeilen = listOf(
                    "Name" to fall.kundeName,
                    "Firma" to fall.kundeFirma,
                    "Telefon" to fall.kundeTelefon,
                    "E-Mail" to fall.kundeEmail,
                    "Anschrift" to fall.kundeAnschrift,
                    "Zeichen" to fall.kundenAktenzeichen,
                ),
                leerText = "Keine Kontaktdaten erfasst.",
            )
            val regulierung = listOf(
                "Vergleich / Regulierung" to fall.vergleichCent?.let(Geld::format).orEmpty(),
                "Zahlungsfrist" to fall.zahlungsfrist?.let(Datum::format).orEmpty(),
                "Schadennr. Versicherung" to fall.versicherungsNummer,
            )
            if (regulierung.any { it.second.isNotBlank() }) {
                Datenblock(titel = "Regulierung", zeilen = regulierung, leerText = "")
            }
        }
    }
}

@Composable
private fun Datenblock(titel: String, zeilen: List<Pair<String, String>>, leerText: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(titel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        val gefuellt = zeilen.filter { it.second.isNotBlank() }
        if (gefuellt.isEmpty()) {
            Text(leerText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        gefuellt.forEach { (label, wert) ->
            Row {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(128.dp),
                )
                Text(wert.trim(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnlagenKarte(
    anlagen: List<Anlage>,
    datei: (Anlage) -> File,
    onFoto: () -> Unit,
    onDatei: () -> Unit,
    onOeffnen: (Anlage) -> Unit,
    onLoeschen: (Anlage) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (anlagen.isEmpty()) "Anlagen" else "Anlagen (${anlagen.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (anlagen.isEmpty()) {
                Text(
                    "Noch keine Fotos oder Belege.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(anlagen, key = { it.id }) { anlage ->
                        Column(
                            modifier = Modifier
                                .width(96.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = { onOeffnen(anlage) },
                                    onLongClick = { onLoeschen(anlage) },
                                    onLongClickLabel = "Anlage entfernen",
                                ),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            ) {
                                if (anlage.istBild) {
                                    AsyncImage(
                                        model = datei(anlage),
                                        contentDescription = anlage.anzeigename,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(
                                        painterResource(R.drawable.ic_dokument),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(36.dp),
                                    )
                                }
                            }
                            Text(
                                anlage.anzeigename,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                Text(
                    "Antippen zum Öffnen, lange drücken zum Entfernen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                OutlinedButton(onClick = onFoto, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(painterResource(R.drawable.ic_kamera), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Foto aufnehmen", maxLines = 1)
                }
                OutlinedButton(onClick = onDatei, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(painterResource(R.drawable.ic_anhang), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Datei hinzufügen", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun VerlaufKopf(onNotiz: (String) -> Unit) {
    var notiz by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text("Verlauf", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = notiz,
            onValueChange = { notiz = it },
            placeholder = { Text("Notiz hinzufügen …") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            trailingIcon = {
                IconButton(
                    enabled = notiz.isNotBlank(),
                    onClick = {
                        onNotiz(notiz)
                        notiz = ""
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Notiz speichern") }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("notizfeld"),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VerlaufZeile(eintrag: VerlaufEintrag, onLangerTipp: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = {}, onLongClick = onLangerTipp, onLongClickLabel = "Eintrag löschen")
            .padding(vertical = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Icon(
                painter = eintrag.typ.symbol(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${eintrag.typ.label} · ${Datum.formatMitUhrzeit(eintrag.zeitpunkt)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(eintrag.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun VerlaufTyp.symbol(): Painter = when (this) {
    VerlaufTyp.ANRUF -> rememberVectorPainter(Icons.Filled.Call)
    VerlaufTyp.EMAIL -> rememberVectorPainter(Icons.Filled.Email)
    VerlaufTyp.NACHRICHT -> painterResource(R.drawable.ic_nachricht)
    VerlaufTyp.BRIEF -> painterResource(R.drawable.ic_dokument)
    VerlaufTyp.STATUS -> painterResource(R.drawable.ic_flagge)
    VerlaufTyp.WIEDERVORLAGE -> painterResource(R.drawable.ic_uhr)
    VerlaufTyp.ANLAGE -> painterResource(R.drawable.ic_anhang)
    VerlaufTyp.NOTIZ -> painterResource(R.drawable.ic_notiz)
    VerlaufTyp.ANGELEGT -> painterResource(R.drawable.ic_lkw)
}

// --- Aufrufe anderer Apps -----------------------------------------------------------------

/** Startet eine andere App. Gibt `false` zurück und meldet [fehlertext], wenn keine passende installiert ist. */
private fun Context.starte(intent: Intent, onFehler: (String) -> Unit, fehlertext: String = "Keine passende App gefunden"): Boolean =
    try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        onFehler(fehlertext)
        false
    }

private fun Context.waehle(nummer: String, onFehler: (String) -> Unit) {
    starte(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", nummer, null)), onFehler)
}

private fun Context.teileText(betreff: String, text: String, onFehler: (String) -> Unit): Boolean {
    val senden = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, betreff)
        .putExtra(Intent.EXTRA_TEXT, text)
    return starte(Intent.createChooser(senden, null), onFehler)
}
