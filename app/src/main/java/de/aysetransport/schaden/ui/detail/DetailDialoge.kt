package de.aysetransport.schaden.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import de.aysetransport.schaden.data.model.Ansprechpartner
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufTyp
import de.aysetransport.schaden.domain.Datum
import de.aysetransport.schaden.domain.Vorlage
import de.aysetransport.schaden.ui.komponenten.DatumAuswahlDialog
import java.time.LocalDate

enum class WiedervorlageModus { NACHGEHAKT, TERMIN }

private val KANAELE = listOf(
    VerlaufTyp.ANRUF to "Telefonat",
    VerlaufTyp.EMAIL to "E-Mail",
    VerlaufTyp.NACHRICHT to "Nachricht",
    VerlaufTyp.BRIEF to "Schreiben",
    VerlaufTyp.NOTIZ to "Sonstiges",
)

private val SCHNELLWAHL_TAGE = listOf(1L to "Morgen", 3L to "3 Tage", 7L to "1 Woche", 14L to "2 Wochen")

/**
 * Das zentrale Blatt der App: festhalten, dass nachgehakt wurde ([WiedervorlageModus.NACHGEHAKT]),
 * und in einem Zug den nächsten Termin planen. Im Modus [WiedervorlageModus.TERMIN] wird nur
 * der Termin geändert.
 */
@Composable
fun WiedervorlageBlatt(
    modus: WiedervorlageModus,
    fall: Schadensfall,
    heute: LocalDate,
    standardTage: Int,
    onSchliessen: () -> Unit,
    onSpeichern: (
        kanal: VerlaufTyp,
        ergebnis: String,
        datum: LocalDate?,
        wartetAuf: Ansprechpartner?,
        naechsterSchritt: String,
    ) -> Unit,
) {
    var kanal by rememberSaveable { mutableStateOf(VerlaufTyp.ANRUF) }
    var ergebnis by rememberSaveable { mutableStateOf("") }
    var datumTag by rememberSaveable {
        val start = when (modus) {
            WiedervorlageModus.NACHGEHAKT -> heute.plusDays(standardTage.toLong())
            WiedervorlageModus.TERMIN -> fall.wiedervorlageAm ?: heute.plusDays(standardTage.toLong())
        }
        mutableStateOf<Long?>(start.toEpochDay())
    }
    val datum = datumTag?.let(LocalDate::ofEpochDay)
    var wartetAuf by rememberSaveable { mutableStateOf(fall.wartetAuf) }
    var naechsterSchritt by rememberSaveable { mutableStateOf(fall.wiedervorlageNotiz) }
    var kalenderOffen by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onSchliessen,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding()
                .testTag("wiedervorlage-blatt"),
        ) {
            Text(
                text = if (modus == WiedervorlageModus.NACHGEHAKT) "Nachgehakt" else "Wiedervorlage ändern",
                style = MaterialTheme.typography.titleLarge,
            )

            if (modus == WiedervorlageModus.NACHGEHAKT) {
                Abschnittstitel("Wie?")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KANAELE.forEach { (typ, text) ->
                        FilterChip(selected = kanal == typ, onClick = { kanal = typ }, label = { Text(text) })
                    }
                }
                OutlinedTextField(
                    value = ergebnis,
                    onValueChange = { ergebnis = it },
                    label = { Text("Was kam dabei heraus?") },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ergebnis"),
                )
            }

            Abschnittstitel("Nächste Wiedervorlage")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SCHNELLWAHL_TAGE.forEach { (tage, text) ->
                    val ziel = heute.plusDays(tage)
                    FilterChip(selected = datum == ziel, onClick = { datumTag = ziel.toEpochDay() }, label = { Text(text) })
                }
                FilterChip(
                    selected = datum != null && SCHNELLWAHL_TAGE.none { heute.plusDays(it.first) == datum },
                    onClick = { kalenderOffen = true },
                    label = { Text("Datum …") },
                )
                FilterChip(selected = datum == null, onClick = { datumTag = null }, label = { Text("Keine") })
            }
            Text(
                text = datum?.let { "Am ${Datum.formatMitWochentag(it)}" } ?: "Der Fall taucht nicht mehr in „Fällig“ auf.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (datum != null) {
                Abschnittstitel("Auf wen wird gewartet?")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ansprechpartner.entries.forEach { partner ->
                        FilterChip(
                            selected = wartetAuf == partner,
                            onClick = { wartetAuf = if (wartetAuf == partner) null else partner },
                            label = { Text(partner.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = naechsterSchritt,
                    onValueChange = { naechsterSchritt = it },
                    label = { Text("Nächster Schritt") },
                    placeholder = { Text("z. B. Fotos nachfordern") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("naechster-schritt"),
                )
            }

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onSchliessen) { Text("Abbrechen") }
                Spacer(Modifier.padding(4.dp))
                Button(
                    onClick = {
                        onSpeichern(
                            kanal,
                            ergebnis,
                            datum,
                            wartetAuf.takeIf { datum != null },
                            naechsterSchritt.takeIf { datum != null }.orEmpty(),
                        )
                    },
                ) { Text("Speichern") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (kalenderOffen) {
        DatumAuswahlDialog(
            start = datum ?: heute,
            onAbbrechen = { kalenderOffen = false },
            onAuswahl = {
                kalenderOffen = false
                datumTag = it.toEpochDay()
            },
        )
    }
}

/**
 * Dialoge mit Eingabefeldern oder Listen bekommen eine feste Breite (Bildschirmbreite abzüglich
 * Rand, höchstens 560 dp) statt der schmalen Android-Standardbreite. Das ist auf dem Telefon
 * besser lesbar und hält die Breite stabil, auch wenn der Inhalt die ganze Zeile füllt.
 */
private val BREITER_DIALOG = DialogProperties(usePlatformDefaultWidth = false)

private val BreiterDialogModifier = Modifier
    .padding(horizontal = 24.dp)
    .widthIn(max = 560.dp)
    .fillMaxWidth()

@Composable
private fun Abschnittstitel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun StatusDialog(
    aktuell: FallStatus,
    onAbbrechen: () -> Unit,
    onBestaetigen: (FallStatus, String) -> Unit,
) {
    var auswahl by rememberSaveable { mutableStateOf(aktuell) }
    var notiz by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onAbbrechen,
        properties = BREITER_DIALOG,
        modifier = BreiterDialogModifier,
        title = { Text("Status ändern") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                FallStatus.entries.forEach { status ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = auswahl == status, role = Role.RadioButton, onClick = { auswahl = status })
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(selected = auswahl == status, onClick = null, modifier = Modifier.padding(8.dp))
                        Text(status.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (!auswahl.offen) {
                    Text(
                        "Der Fall wird abgeschlossen; eine geplante Wiedervorlage entfällt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = notiz,
                    onValueChange = { notiz = it },
                    label = { Text("Anmerkung (optional)") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onBestaetigen(auswahl, notiz) }) { Text("Übernehmen") } },
        dismissButton = { TextButton(onClick = onAbbrechen) { Text("Abbrechen") } },
    )
}

@Composable
fun VorlagenDialog(
    titel: String,
    onAbbrechen: () -> Unit,
    onAuswahl: (Vorlage) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAbbrechen,
        properties = BREITER_DIALOG,
        modifier = BreiterDialogModifier,
        title = { Text(titel) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Der Text wird vorbereitet und kann vor dem Senden angepasst werden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Vorlage.entries.forEach { vorlage ->
                    ListItem(
                        headlineContent = { Text(vorlage.titel) },
                        supportingContent = { Text(vorlage.beschreibung) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onAuswahl(vorlage) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onAbbrechen) { Text("Abbrechen") } },
    )
}

@Composable
fun BestaetigenDialog(
    titel: String,
    text: String,
    bestaetigen: String,
    onAbbrechen: () -> Unit,
    onBestaetigen: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAbbrechen,
        title = { Text(titel) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onBestaetigen) { Text(bestaetigen, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onAbbrechen) { Text("Abbrechen") } },
    )
}
