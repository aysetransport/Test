package de.aysetransport.schaden.ui.komponenten

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import de.aysetransport.schaden.domain.Geld
import java.time.LocalDate

/** Ein Eingabefeld im [Formulardialog]. Werte sind immer Text; ein Datum steht als `JJJJ-MM-TT` darin. */
sealed interface Feld {
    val schluessel: String
    val label: String

    data class Text(
        override val schluessel: String,
        override val label: String,
        val art: Textart = Textart.NORMAL,
        val pflicht: Boolean = false,
        val hinweis: String? = null,
    ) : Feld

    data class Datum(override val schluessel: String, override val label: String) : Feld

    data class Auswahl(override val schluessel: String, override val label: String, val optionen: List<Pair<String, String>>) : Feld
}

enum class Textart { NORMAL, NAME, MEHRZEILIG, TELEFON, EMAIL, BETRAG, LINK }

private val EMAIL_MUSTER = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

/**
 * Einfacher Bearbeiten-Dialog für CRM-Einträge: Felder untereinander, Speichern, Abbrechen und – bei
 * vorhandenen Einträgen – Löschen (mit Rückfrage).
 */
@Composable
fun Formulardialog(
    titel: String,
    felder: List<Feld>,
    werte: Map<String, String>,
    onSchliessen: () -> Unit,
    onSpeichern: (Map<String, String>) -> Unit,
    onLoeschen: (() -> Unit)? = null,
    loeschFrage: String = "Wirklich löschen?",
    zusatz: (@Composable () -> Unit)? = null,
) {
    val stand = remember { mutableStateMapOf<String, String>().apply { putAll(werte) } }
    var loeschenFragen by remember { mutableStateOf(false) }
    fun fehler(f: Feld): String? {
        val w = stand[f.schluessel].orEmpty().trim()
        return when {
            f is Feld.Text && f.pflicht && w.isEmpty() -> "Bitte ausfüllen."
            f is Feld.Text && f.art == Textart.EMAIL && w.isNotEmpty() && !w.split(',', ';').all { EMAIL_MUSTER.matches(it.trim()) } -> "Die E-Mail-Adresse sieht unvollständig aus."
            f is Feld.Text && f.art == Textart.BETRAG && w.isNotEmpty() && Geld.parse(w) == null -> "Bitte einen Betrag wie 89,50 eingeben."
            else -> null
        }
    }
    val gueltig = felder.all { fehler(it) == null }

    AlertDialog(
        onDismissRequest = onSchliessen,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        title = { Text(titel) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                for (f in felder) {
                    val wert = stand[f.schluessel].orEmpty()
                    when (f) {
                        is Feld.Text -> {
                            val fehlertext = fehler(f)?.takeIf { wert.isNotEmpty() || f.art != Textart.NORMAL }
                            OutlinedTextField(
                                value = wert,
                                onValueChange = { stand[f.schluessel] = it },
                                label = { Text(f.label) },
                                singleLine = f.art != Textart.MEHRZEILIG,
                                minLines = if (f.art == Textart.MEHRZEILIG) 3 else 1,
                                isError = fehlertext != null && wert.isNotEmpty(),
                                supportingText = (fehlertext?.takeIf { wert.isNotEmpty() } ?: f.hinweis)?.let { { Text(it) } },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = when (f.art) {
                                        Textart.TELEFON -> KeyboardType.Phone
                                        Textart.EMAIL -> KeyboardType.Email
                                        Textart.BETRAG -> KeyboardType.Decimal
                                        Textart.LINK -> KeyboardType.Uri
                                        else -> KeyboardType.Text
                                    },
                                    capitalization = when (f.art) {
                                        Textart.NAME -> KeyboardCapitalization.Words
                                        Textart.NORMAL, Textart.MEHRZEILIG -> KeyboardCapitalization.Sentences
                                        else -> KeyboardCapitalization.None
                                    },
                                ),
                                modifier = Modifier.fillMaxWidth().testTag("feld-${f.schluessel}"),
                            )
                        }
                        is Feld.Datum -> DatumFeld(
                            label = f.label,
                            datum = wert.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                            onDatumChange = { stand[f.schluessel] = it?.toString().orEmpty() },
                            modifier = Modifier.testTag("feld-${f.schluessel}"),
                        )
                        is Feld.Auswahl -> AuswahlFeld(
                            label = f.label,
                            auswahl = wert,
                            optionen = f.optionen,
                            onAuswahl = { stand[f.schluessel] = it },
                            modifier = Modifier.testTag("feld-${f.schluessel}"),
                        )
                    }
                }
                zusatz?.invoke()
                if (onLoeschen != null) {
                    TextButton(onClick = { loeschenFragen = true }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = gueltig, onClick = { onSpeichern(stand.mapValues { it.value.trim() }) }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onSchliessen) { Text("Abbrechen") } },
    )
    if (loeschenFragen && onLoeschen != null) {
        AlertDialog(
            onDismissRequest = { loeschenFragen = false },
            title = { Text(loeschFrage) },
            confirmButton = { TextButton(onClick = { loeschenFragen = false; onLoeschen() }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { loeschenFragen = false }) { Text("Abbrechen") } },
        )
    }
}
