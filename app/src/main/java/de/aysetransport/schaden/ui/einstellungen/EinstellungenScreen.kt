package de.aysetransport.schaden.ui.einstellungen

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.aysetransport.schaden.BuildConfig
import de.aysetransport.schaden.data.Einstellungen
import de.aysetransport.schaden.mail.Sicherheit
import de.aysetransport.schaden.mail.MailServer
import de.aysetransport.schaden.mail.MailKonfig
import de.aysetransport.schaden.mail.Anbieter
import de.aysetransport.schaden.data.model.MailKontoArt
import de.aysetransport.schaden.domain.Datum
import de.aysetransport.schaden.erinnerung.Benachrichtigungen
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val WIEDERVORLAGE_OPTIONEN = listOf(3 to "3 Tage", 7 to "1 Woche", 14 to "2 Wochen", 30 to "30 Tage")

@Composable
fun EinstellungenScreen(viewModel: EinstellungenViewModel, onZurueck: () -> Unit, onAuftraggeber: () -> Unit = {}) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var uhrzeitOffen by remember { mutableStateOf(false) }
    var wiederherstellenVon by remember { mutableStateOf<android.net.Uri?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.meldungen.collect { snackbar.showSnackbar(it) }
    }

    var benachrichtigungenErlaubt by remember { mutableStateOf(Benachrichtigungen.duerfenAngezeigtWerden(context)) }
    LifecycleResumeEffect(Unit) {
        benachrichtigungenErlaubt = Benachrichtigungen.duerfenAngezeigtWerden(context)
        onPauseOrDispose { }
    }
    val berechtigungAnfragen = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { erteilt ->
        benachrichtigungenErlaubt = erteilt && Benachrichtigungen.duerfenAngezeigtWerden(context)
    }
    val backupSpeichern = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.erstelleBackup(uri)
    }
    val backupOeffnen = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) wiederherstellenVon = uri
    }
    val crmOeffnen = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importiereCrm(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onZurueck) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innen ->
        val e = viewModel.einstellungen
        if (e == null) {
            Box(Modifier.fillMaxSize().padding(innen), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innen)
                .consumeWindowInsets(innen)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        ) {
            if (viewModel.beschaeftigt) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            Bereich("Absender", "Erscheint als Grußformel in den Textvorlagen.") {
                Eingabe("Firma", e.firma) { viewModel.aendere { copy(firma = it) } }
                Eingabe("Bearbeiter/in", e.bearbeiter) { viewModel.aendere { copy(bearbeiter = it) } }
                Eingabe("Funktion", e.funktion, platzhalter = "z. B. Schadensbearbeitung") { viewModel.aendere { copy(funktion = it) } }
            }

            Bereich("Wiedervorlage", "Vorschlag für den ersten Termin eines neuen Falls und nach dem Nachhaken.") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WIEDERVORLAGE_OPTIONEN.forEach { (tage, text) ->
                        FilterChip(
                            selected = e.standardWiedervorlageTage == tage,
                            onClick = { viewModel.aendere { copy(standardWiedervorlageTage = tage) } },
                            label = { Text(text) },
                        )
                    }
                }
            }

            Bereich("Erinnerung", "Einmal täglich meldet die App, bei welchen Fällen nachgehakt werden muss.") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Täglich erinnern", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = e.erinnerungAktiv, onCheckedChange = { viewModel.aendere { copy(erinnerungAktiv = it) } })
                }
                if (e.erinnerungAktiv) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uhrzeitOffen = true }
                            .padding(vertical = 8.dp),
                    ) {
                        Text("Uhrzeit", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            e.erinnerungUhrzeit.format(DateTimeFormatter.ofPattern("HH:mm")) + " Uhr",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (!benachrichtigungenErlaubt) {
                        Text(
                            "Benachrichtigungen sind für diese App ausgeschaltet – es kommt keine Erinnerung an.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            OutlinedButton(onClick = { berechtigungAnfragen.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                Text("Benachrichtigungen erlauben")
                            }
                        }
                    }
                }
            }

            Bereich("Auftraggeber (CRM)", "Firmen mit Ansprechpartnern, Adressen, Preisen, Wiedervorlagen und Kontakthistorie. Die Sicherung des bisherigen Browser-CRM lässt sich hier einlesen – gleichnamige Firmen werden zusammengeführt.") {
                OutlinedButton(onClick = onAuftraggeber, modifier = Modifier.fillMaxWidth()) { Text("Zu den Firmen") }
                OutlinedButton(
                    enabled = !viewModel.beschaeftigt,
                    onClick = {
                        try {
                            crmOeffnen.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        } catch (ex: ActivityNotFoundException) {
                            viewModel.melde("Keine Dateiauswahl verfügbar")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("crm-import"),
                ) { Text("Sicherung des Browser-CRM importieren (JSON)") }
                OutlinedButton(
                    enabled = !viewModel.beschaeftigt,
                    onClick = {
                        viewModel.exportiereCrmCsv { uris ->
                            val senden = Intent(Intent.ACTION_SEND_MULTIPLE)
                                .setType("text/csv")
                                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                .putExtra(Intent.EXTRA_SUBJECT, "CRM Ayse Transport")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try {
                                context.startActivity(Intent.createChooser(senden, "CRM-Listen teilen"))
                            } catch (ex: ActivityNotFoundException) {
                                viewModel.melde("Keine App zum Teilen gefunden")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("crm-csv"),
                ) { Text("CRM für Excel exportieren (6 CSV-Dateien)") }
            }

            MailBereich(viewModel, MailKontoArt.SCHADEN)
            MailBereich(viewModel, MailKontoArt.ALLGEMEIN)

            CloudBereich(viewModel)

            Bereich("Daten", "Ein Backup enthält alle Fälle mit Verlauf und Anlagen sowie das ganze CRM und die E-Mails dieses Geräts.") {
                OutlinedButton(
                    enabled = !viewModel.beschaeftigt,
                    onClick = {
                        viewModel.exportiereCsv { uri ->
                            val senden = Intent(Intent.ACTION_SEND)
                                .setType("text/csv")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_SUBJECT, "Schadensfälle")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try {
                                context.startActivity(Intent.createChooser(senden, "Fallliste teilen"))
                            } catch (ex: ActivityNotFoundException) {
                                viewModel.melde("Keine App zum Teilen gefunden")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Fallliste für Excel exportieren (CSV)") }
                OutlinedButton(
                    enabled = !viewModel.beschaeftigt,
                    onClick = {
                        try {
                            backupSpeichern.launch("Schadensfaelle-Backup_${LocalDate.now()}.zip")
                        } catch (ex: ActivityNotFoundException) {
                            viewModel.melde("Kein Speicherort verfügbar")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Backup erstellen") }
                OutlinedButton(
                    enabled = !viewModel.beschaeftigt,
                    onClick = {
                        try {
                            backupOeffnen.launch(arrayOf("application/zip", "application/octet-stream"))
                        } catch (ex: ActivityNotFoundException) {
                            viewModel.melde("Keine Dateiauswahl verfügbar")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Backup wiederherstellen") }
            }

            Text(
                "Ayse Transport CRM · Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        if (uhrzeitOffen) {
            UhrzeitDialog(
                start = e.erinnerungUhrzeit,
                onAbbrechen = { uhrzeitOffen = false },
                onAuswahl = { zeit ->
                    uhrzeitOffen = false
                    viewModel.aendere { copy(erinnerungStunde = zeit.hour, erinnerungMinute = zeit.minute) }
                },
            )
        }
    }

    viewModel.pinAbfrage?.let { abfrage ->
        PinDialog(
            abfrage = abfrage,
            fehler = viewModel.pinFehler,
            beschaeftigt = viewModel.cloudBeschaeftigt,
            onAbbrechen = viewModel::pinAbfrageSchliessen,
            onBestaetigen = viewModel::cloudVerbinden,
        )
    }

    wiederherstellenVon?.let { quelle ->
        AlertDialog(
            onDismissRequest = { wiederherstellenVon = null },
            title = { Text("Backup wiederherstellen?") },
            text = { Text("Alle Fälle auf diesem Gerät werden durch den Inhalt des Backups ersetzt. Das lässt sich nicht rückgängig machen.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        wiederherstellenVon = null
                        viewModel.stelleBackupWiederHer(quelle)
                    },
                ) { Text("Ersetzen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { wiederherstellenVon = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Bereich(titel: String, erklaerung: String, inhalt: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(titel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(erklaerung, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            inhalt()
        }
    }
}

@Composable
private fun Eingabe(label: String, wert: String, platzhalter: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = wert,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = platzhalter?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun UhrzeitDialog(start: LocalTime, onAbbrechen: () -> Unit, onAuswahl: (LocalTime) -> Unit) {
    val zustand = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onAbbrechen,
        title = { Text("Uhrzeit der Erinnerung") },
        text = { TimePicker(state = zustand) },
        confirmButton = {
            TextButton(onClick = { onAuswahl(LocalTime.of(zustand.hour, zustand.minute)) }) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onAbbrechen) { Text("Abbrechen") } },
    )
}

@Composable
private fun CloudBereich(viewModel: EinstellungenViewModel) {
    val zustand by viewModel.cloudZustand.collectAsStateWithLifecycle()
    val offen by viewModel.offeneAenderungen.collectAsStateWithLifecycle()
    val z = zustand ?: return
    var adresse by rememberSaveable { mutableStateOf(BuildConfig.CLOUD_URL) }

    Bereich(
        "Cloud",
        if (z.verbunden) {
            "Die Fälle liegen zusätzlich in Google Drive (Ordner „Schadensfaelle“) und werden mit der Web-App und anderen Geräten abgeglichen."
        } else {
            "Derzeit liegen die Fälle nur auf diesem Gerät. Mit der Adresse des Cloud-Skripts werden sie in Google Drive gespeichert und mit der Web-App geteilt."
        },
    ) {
        if (!z.verbunden) {
            OutlinedTextField(
                value = adresse,
                onValueChange = { adresse = it },
                label = { Text("Cloud-Adresse (…/exec)") },
                placeholder = { Text("https://script.google.com/macros/s/…/exec") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cloud-adresse"),
            )
            Button(
                onClick = { viewModel.cloudPruefen(adresse) },
                enabled = adresse.isNotBlank() && !viewModel.cloudBeschaeftigt,
            ) { Text("Verbinden") }
            return@Bereich
        }

        val status = when {
            !z.angemeldet -> z.fehler.ifEmpty { "PIN erforderlich" }
            viewModel.cloudBeschaeftigt -> "Gleiche ab …"
            z.fehler.isNotEmpty() -> "Letzter Versuch fehlgeschlagen: ${z.fehler}" + if (offen > 0) " · $offen Änderungen warten" else ""
            offen > 0 -> "$offen Änderungen warten auf den Abgleich"
            z.letzterAbgleich > 0 -> "Aktuell · zuletzt abgeglichen ${Datum.formatMitUhrzeit(Instant.ofEpochMilli(z.letzterAbgleich))}"
            else -> "Noch nicht abgeglichen"
        }
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (!z.angemeldet || z.fehler.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("cloud-status"),
        )
        Text(z.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (z.angemeldet) {
                Button(onClick = viewModel::jetztAbgleichen, enabled = !viewModel.cloudBeschaeftigt) { Text("Jetzt abgleichen") }
            } else {
                Button(onClick = viewModel::pinNeuEingeben, enabled = !viewModel.cloudBeschaeftigt) { Text("PIN eingeben") }
            }
            OutlinedButton(onClick = viewModel::cloudTrennen, enabled = !viewModel.cloudBeschaeftigt) { Text("Trennen") }
        }
    }
}

@Composable
private fun PinDialog(
    abfrage: PinAbfrage,
    fehler: String?,
    beschaeftigt: Boolean,
    onAbbrechen: () -> Unit,
    onBestaetigen: (String, String?) -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var wiederholung by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onAbbrechen,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
        title = { Text(if (abfrage.neu) "PIN festlegen" else "PIN eingeben") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (abfrage.neu) {
                        "Diese PIN schützt die Daten in der Cloud. Sie wird auf jedem Gerät einmal abgefragt (mindestens 4 Zeichen)."
                    } else {
                        "Die PIN der Schadensfälle-Cloud – dieselbe wie in der Web-App."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pin"),
                )
                if (abfrage.neu) {
                    OutlinedTextField(
                        value = wiederholung,
                        onValueChange = { wiederholung = it },
                        label = { Text("PIN wiederholen") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pin-wiederholung"),
                    )
                }
                if (fehler != null) Text(fehler, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onBestaetigen(pin, if (abfrage.neu) wiederholung else null) },
                enabled = pin.isNotEmpty() && !beschaeftigt,
            ) { Text(if (abfrage.neu) "Festlegen" else "Anmelden") }
        },
        dismissButton = { TextButton(onClick = onAbbrechen) { Text("Abbrechen") } },
    )
}

@Composable
private fun MailBereich(viewModel: EinstellungenViewModel, art: MailKontoArt) {
    val zustand by viewModel.mailZustand(art).collectAsStateWithLifecycle()
    val z = zustand ?: return
    var einrichten by rememberSaveable { mutableStateOf(false) }
    val konfig = z.konfig
    val schaden = art == MailKontoArt.SCHADEN
    Bereich(
        if (schaden) "E-Mail für Schadensfälle" else "E-Mail für alles andere",
        when {
            schaden && konfig == null -> "Postfach für die Schadensbearbeitung (z. B. schaden@…). Mails aus einem Fall gehen darüber; Antworten werden über die Fallnummer im Betreff dem Fall zugeordnet."
            konfig == null -> "Postfach für Disposition, Angebote und Abrechnung (z. B. info@…). Mails an Firmen und Ansprechpartner gehen darüber. Ohne eigenes Konto wird das Schadens-Postfach verwendet."
            schaden -> "Wird alle 15 Minuten abgerufen. Mails zu einem Fall landen im Verlauf (Anhänge werden Anlagen), andere bei der passenden Firma oder im Postfach unter „Nicht zugeordnet“."
            else -> "Wird alle 15 Minuten abgerufen. Mails landen bei der Firma, deren Adresse oder Domain passt, sonst im Postfach unter „Nicht zugeordnet“."
        },
    ) {
        if (konfig == null) {
            Button(onClick = { einrichten = true }, modifier = Modifier.testTag("mail-einrichten-${art.name}")) { Text("E-Mail-Konto einrichten") }
        } else {
            Text(konfig.adresse, style = MaterialTheme.typography.bodyLarge)
            val status = when {
                viewModel.mailBeschaeftigt -> "Wird abgerufen …"
                z.fehler.isNotEmpty() -> "Letzter Abruf fehlgeschlagen: ${z.fehler}"
                z.letzterAbruf > 0 -> "Zuletzt abgerufen ${Datum.formatMitUhrzeit(Instant.ofEpochMilli(z.letzterAbruf))}"
                else -> "Noch nicht abgerufen"
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (z.fehler.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::mailAbrufen, enabled = !viewModel.mailBeschaeftigt) { Text("Jetzt abrufen") }
                OutlinedButton(onClick = { einrichten = true }) { Text("Ändern") }
                OutlinedButton(onClick = { viewModel.mailTrennen(art) }) { Text("Entfernen") }
            }
        }
    }
    if (einrichten) {
        MailKontoDialog(
            vorher = konfig,
            absendername = viewModel.einstellungen?.firma.orEmpty(),
            fehler = viewModel.mailFehler,
            beschaeftigt = viewModel.mailBeschaeftigt,
            onAbbrechen = { einrichten = false },
            onVerbinden = { k, p -> viewModel.mailEinrichten(art, k, p) { einrichten = false } },
        )
    }
}

@Composable
private fun MailKontoDialog(
    vorher: MailKonfig?,
    absendername: String,
    fehler: String?,
    beschaeftigt: Boolean,
    onAbbrechen: () -> Unit,
    onVerbinden: (MailKonfig, String) -> Unit,
) {
    val start = vorher?.server
    val vorgabe = Anbieter.entries.firstOrNull { it.server != null && it.server.imapHost == start?.imapHost } ?: if (start != null) Anbieter.EIGENER else Anbieter.IONOS
    var anbieter by rememberSaveable { mutableStateOf(vorgabe) }
    var adresse by rememberSaveable { mutableStateOf(vorher?.adresse.orEmpty()) }
    var benutzer by rememberSaveable { mutableStateOf(vorher?.benutzer.orEmpty()) }
    var passwort by rememberSaveable { mutableStateOf("") }
    var imapHost by rememberSaveable { mutableStateOf(start?.imapHost.orEmpty()) }
    var imapPort by rememberSaveable { mutableStateOf((start?.imapPort ?: 993).toString()) }
    var smtpHost by rememberSaveable { mutableStateOf(start?.smtpHost.orEmpty()) }
    var smtpPort by rememberSaveable { mutableStateOf((start?.smtpPort ?: 465).toString()) }
    var smtpStarttls by rememberSaveable { mutableStateOf(start?.smtpSicherheit == Sicherheit.STARTTLS) }

    val server = anbieter.server ?: MailServer(
        imapHost = imapHost.trim(),
        imapPort = imapPort.toIntOrNull() ?: 993,
        smtpHost = smtpHost.trim(),
        smtpPort = smtpPort.toIntOrNull() ?: 465,
        smtpSicherheit = if (smtpStarttls) Sicherheit.STARTTLS else Sicherheit.SSL,
    )
    val gueltig = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+").matches(adresse.trim()) && passwort.isNotEmpty() &&
        server.imapHost.isNotBlank() && server.smtpHost.isNotBlank()

    AlertDialog(
        onDismissRequest = onAbbrechen,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        title = { Text("E-Mail-Konto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Anbieter.entries.forEach { a ->
                        FilterChip(selected = anbieter == a, onClick = { anbieter = a }, label = { Text(a.label) })
                    }
                }
                OutlinedTextField(
                    value = adresse, onValueChange = { adresse = it }, label = { Text("E-Mail-Adresse") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("mail-adresse"),
                )
                OutlinedTextField(
                    value = passwort, onValueChange = { passwort = it }, label = { Text("Passwort") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("mail-passwort"),
                )
                OutlinedTextField(
                    value = benutzer, onValueChange = { benutzer = it }, label = { Text("Benutzername (falls abweichend)") }, singleLine = true,
                    placeholder = { Text("meist die E-Mail-Adresse") }, modifier = Modifier.fillMaxWidth(),
                )
                if (anbieter == Anbieter.EIGENER) {
                    OutlinedTextField(value = imapHost, onValueChange = { imapHost = it }, label = { Text("IMAP-Server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = imapPort, onValueChange = { imapPort = it }, label = { Text("IMAP-Port (SSL)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = smtpHost, onValueChange = { smtpHost = it }, label = { Text("SMTP-Server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = smtpPort, onValueChange = { smtpPort = it }, label = { Text("SMTP-Port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SMTP mit STARTTLS (sonst SSL)", modifier = Modifier.weight(1f))
                        Switch(checked = smtpStarttls, onCheckedChange = { smtpStarttls = it })
                    }
                } else {
                    Text(
                        "Server: ${server.imapHost} (IMAP) · ${server.smtpHost} (SMTP)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Das Passwort wird verschlüsselt nur auf diesem Gerät gespeichert. Die App liest den Posteingang und verändert im Postfach nichts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (beschaeftigt) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (fehler != null) Text(fehler, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = gueltig && !beschaeftigt,
                onClick = {
                    onVerbinden(
                        MailKonfig(adresse = adresse.trim(), benutzer = benutzer.trim().ifEmpty { adresse.trim() }, server = server, name = absendername),
                        passwort,
                    )
                },
            ) { Text("Verbinden") }
        },
        dismissButton = { TextButton(onClick = onAbbrechen) { Text("Abbrechen") } },
    )
}
