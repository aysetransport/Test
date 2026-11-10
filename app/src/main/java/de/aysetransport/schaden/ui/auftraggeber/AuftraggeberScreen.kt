package de.aysetransport.schaden.ui.auftraggeber

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.aysetransport.schaden.AppContainer
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AuftraggeberViewModel(private val repository: SchadensfallRepository) : ViewModel() {
    val liste: StateFlow<List<AuftraggeberEintrag>?> = repository.beobachteAuftraggeber()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun speichern(eintrag: AuftraggeberEintrag) = viewModelScope.launch { repository.auftraggeberSpeichern(eintrag) }
    fun loeschen(eintrag: AuftraggeberEintrag) = viewModelScope.launch { repository.auftraggeberLoeschen(eintrag) }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { AuftraggeberViewModel(container.repository) } }
    }
}

/** Liste der Auftraggeber zum Auswählen im Formular – anlegen, bearbeiten, löschen. */
@Composable
fun AuftraggeberScreen(viewModel: AuftraggeberViewModel, onZurueck: () -> Unit) {
    val liste by viewModel.liste.collectAsStateWithLifecycle()
    var bearbeitet by remember { mutableStateOf<AuftraggeberEintrag?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auftraggeber") },
                navigationIcon = { IconButton(onClick = onZurueck) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { bearbeitet = AuftraggeberEintrag(name = "") },
                icon = { Icon(Icons.Filled.Add, contentDescription = "Neuer Auftraggeber") },
                text = { Text("Neuer Auftraggeber") },
            )
        },
    ) { innen ->
        val eintraege = liste ?: return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innen),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Im Fall-Formular lassen sich diese Auftraggeber auswählen. Neue Auftraggeber aus Fällen kommen automatisch hinzu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (eintraege.isEmpty()) {
                item {
                    Text("Noch keine Auftraggeber gespeichert.", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
                }
            }
            items(eintraege, key = { it.uuid }) { e ->
                Card(
                    onClick = { bearbeitet = e },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(e.name, style = MaterialTheme.typography.titleMedium)
                        val zeile = listOf(e.kontakt, e.telefon, e.email).filter { it.isNotBlank() }.joinToString(" · ")
                        if (zeile.isNotEmpty()) Text(zeile, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    bearbeitet?.let { e ->
        AuftraggeberDialog(
            eintrag = e,
            neu = liste.orEmpty().none { it.uuid == e.uuid },
            onSchliessen = { bearbeitet = null },
            onSpeichern = { viewModel.speichern(it); bearbeitet = null },
            onLoeschen = { viewModel.loeschen(it); bearbeitet = null },
        )
    }
}

@Composable
private fun AuftraggeberDialog(
    eintrag: AuftraggeberEintrag,
    neu: Boolean,
    onSchliessen: () -> Unit,
    onSpeichern: (AuftraggeberEintrag) -> Unit,
    onLoeschen: (AuftraggeberEintrag) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(eintrag.name) }
    var kontakt by rememberSaveable { mutableStateOf(eintrag.kontakt) }
    var telefon by rememberSaveable { mutableStateOf(eintrag.telefon) }
    var email by rememberSaveable { mutableStateOf(eintrag.email) }
    var loeschenFragen by remember { mutableStateOf(false) }
    val emailFehler = email.isNotBlank() && !Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+").matches(email.trim())

    AlertDialog(
        onDismissRequest = onSchliessen,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        title = { Text(if (neu) "Neuer Auftraggeber" else "Auftraggeber bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Feld("Firma", name, { name = it }, KeyboardType.Text, "ag-name")
                Feld("Ansprechpartner", kontakt, { kontakt = it }, KeyboardType.Text, "ag-kontakt")
                Feld("Telefon", telefon, { telefon = it }, KeyboardType.Phone, "ag-telefon")
                Feld("E-Mail", email, { email = it }, KeyboardType.Email, "ag-email", if (emailFehler) "Die E-Mail-Adresse sieht unvollständig aus." else null)
                if (!neu) {
                    Text(
                        "Änderungen gelten für neue Fälle; bereits erfasste Fälle behalten ihre Angaben.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { loeschenFragen = true }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !emailFehler,
                onClick = { onSpeichern(eintrag.copy(name = name, kontakt = kontakt, telefon = telefon, email = email)) },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onSchliessen) { Text("Abbrechen") } },
    )
    if (loeschenFragen) {
        AlertDialog(
            onDismissRequest = { loeschenFragen = false },
            title = { Text("„${eintrag.name}“ löschen?") },
            text = { Text("Der Auftraggeber verschwindet aus der Auswahlliste. Bereits erfasste Fälle bleiben unverändert.") },
            confirmButton = { TextButton(onClick = { onLoeschen(eintrag) }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { loeschenFragen = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Feld(label: String, wert: String, onChange: (String) -> Unit, tastatur: KeyboardType, tag: String, fehler: String? = null) {
    OutlinedTextField(
        value = wert,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = fehler != null,
        supportingText = fehler?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = tastatur,
            capitalization = if (tastatur == KeyboardType.Text) KeyboardCapitalization.Words else KeyboardCapitalization.None,
        ),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
