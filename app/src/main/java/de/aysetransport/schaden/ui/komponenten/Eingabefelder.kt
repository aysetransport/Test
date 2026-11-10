package de.aysetransport.schaden.ui.komponenten

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import de.aysetransport.schaden.R
import de.aysetransport.schaden.domain.Datum
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Ruft [beiTipp] auf, sobald das (schreibgeschützte) Textfeld angetippt wurde. */
@Composable
private fun rememberTippQuelle(beiTipp: () -> Unit): MutableInteractionSource {
    val quelle = remember { MutableInteractionSource() }
    LaunchedEffect(quelle) {
        quelle.interactions.collect { if (it is PressInteraction.Release) beiTipp() }
    }
    return quelle
}

/** Datumsfeld: Antippen öffnet den Kalender, das Kreuz leert das Feld wieder. */
@Composable
fun DatumFeld(
    label: String,
    datum: LocalDate?,
    onDatumChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    loeschbar: Boolean = true,
    hinweis: String? = null,
) {
    var kalenderOffen by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = datum?.let(Datum::format).orEmpty(),
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text(label) },
        supportingText = hinweis?.let { { Text(it) } },
        leadingIcon = { Icon(painterResource(R.drawable.ic_kalender), contentDescription = null) },
        trailingIcon = if (loeschbar && datum != null) {
            {
                IconButton(onClick = { onDatumChange(null) }) {
                    Icon(Icons.Filled.Clear, contentDescription = "$label entfernen")
                }
            }
        } else {
            null
        },
        interactionSource = rememberTippQuelle { kalenderOffen = true },
        modifier = modifier.fillMaxWidth(),
    )
    if (kalenderOffen) {
        DatumAuswahlDialog(
            start = datum,
            onAbbrechen = { kalenderOffen = false },
            onAuswahl = {
                kalenderOffen = false
                onDatumChange(it)
            },
        )
    }
}

@Composable
fun DatumAuswahlDialog(
    start: LocalDate?,
    onAbbrechen: () -> Unit,
    onAuswahl: (LocalDate) -> Unit,
) {
    val zustand = rememberDatePickerState(
        initialSelectedDateMillis = start?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onAbbrechen,
        confirmButton = {
            TextButton(
                enabled = zustand.selectedDateMillis != null,
                onClick = {
                    zustand.selectedDateMillis?.let { millis ->
                        onAuswahl(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
            ) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onAbbrechen) { Text("Abbrechen") } },
    ) {
        DatePicker(state = zustand)
    }
}

/** Auswahlfeld mit aufklappender Liste. [optionen] enthält Wert und Beschriftung. */
@Composable
fun <T> AuswahlFeld(
    label: String,
    auswahl: T,
    optionen: List<Pair<T, String>>,
    onAuswahl: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var offen by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = optionen.firstOrNull { it.first == auswahl }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            interactionSource = rememberTippQuelle { offen = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = offen, onDismissRequest = { offen = false }) {
            optionen.forEach { (wert, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        offen = false
                        onAuswahl(wert)
                    },
                )
            }
        }
    }
}
