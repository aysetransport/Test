package de.aysetransport.schaden.ui.uebersicht

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.aysetransport.schaden.R
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.domain.Datum
import de.aysetransport.schaden.domain.Faelligkeit
import de.aysetransport.schaden.domain.FallFilter
import de.aysetransport.schaden.domain.Geld
import de.aysetransport.schaden.domain.Kennzahlen
import de.aysetransport.schaden.domain.faelligkeit
import de.aysetransport.schaden.erinnerung.Benachrichtigungen
import de.aysetransport.schaden.ui.komponenten.FaelligkeitsMarke
import de.aysetransport.schaden.ui.komponenten.StatusMarke
import de.aysetransport.schaden.ui.theme.LocalZusatzfarben
import java.time.LocalDate

@Composable
fun UebersichtScreen(
    viewModel: UebersichtViewModel,
    onFallOeffnen: (Long) -> Unit,
    onNeuerFall: () -> Unit,
    onEinstellungen: () -> Unit,
) {
    val zustand by viewModel.zustand.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var benachrichtigungenErlaubt by remember { mutableStateOf(Benachrichtigungen.duerfenAngezeigtWerden(context)) }
    LifecycleResumeEffect(Unit) {
        viewModel.aktualisiereHeute()
        benachrichtigungenErlaubt = Benachrichtigungen.duerfenAngezeigtWerden(context)
        onPauseOrDispose { }
    }
    val berechtigungAnfragen = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { erteilt ->
        benachrichtigungenErlaubt = erteilt && Benachrichtigungen.duerfenAngezeigtWerden(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schadensfälle") },
                actions = {
                    IconButton(onClick = onEinstellungen) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNeuerFall,
                // Material blendet den Text des Knopfs für Screenreader aus – die Beschriftung
                // muss deshalb am Symbol hängen.
                icon = { Icon(Icons.Filled.Add, contentDescription = "Neuer Fall") },
                text = { Text("Neuer Fall") },
            )
        },
    ) { innen ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innen)
                .testTag("fallliste"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "kennzahlen") {
                KennzahlenZeile(zustand.kennzahlen, onFilter = viewModel::setzeFilter)
            }
            if (!benachrichtigungenErlaubt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !zustand.ganzLeer) {
                item(key = "berechtigung") {
                    BenachrichtigungsHinweis(onErlauben = { berechtigungAnfragen.launch(Manifest.permission.POST_NOTIFICATIONS) })
                }
            }
            item(key = "suche") {
                Suchfeld(zustand.suchtext, viewModel::setzeSuchtext)
            }
            item(key = "filter") {
                FilterZeile(zustand.filter, zustand.kennzahlen, viewModel::setzeFilter)
            }
            when {
                zustand.laedt -> Unit
                zustand.ganzLeer -> item(key = "leer") {
                    Leerzustand(
                        titel = "Noch keine Schadensfälle",
                        text = "Lege den ersten Fall an – die App erinnert dann an jede Wiedervorlage.",
                        aktion = "Ersten Fall anlegen",
                        onAktion = onNeuerFall,
                    )
                }
                zustand.faelle.isEmpty() -> item(key = "nichts") {
                    Leerzustand(
                        titel = if (zustand.suchtext.isNotBlank()) "Keine Treffer" else when (zustand.filter) {
                            FallFilter.FAELLIG -> "Nichts fällig"
                            FallFilter.OFFEN -> "Keine offenen Fälle"
                            FallFilter.ABGESCHLOSSEN -> "Noch nichts abgeschlossen"
                            FallFilter.ALLE -> "Keine Fälle"
                        },
                        text = when {
                            zustand.suchtext.isNotBlank() -> "Zu „${zustand.suchtext.trim()}“ wurde in dieser Ansicht nichts gefunden."
                            zustand.filter == FallFilter.FAELLIG -> "Heute muss bei keinem Fall nachgehakt werden."
                            else -> "In dieser Ansicht gibt es derzeit keine Fälle."
                        },
                    )
                }
                else -> items(zustand.faelle, key = { it.id }) { fall ->
                    FallKarte(fall, zustand.heute, onClick = { onFallOeffnen(fall.id) })
                }
            }
        }
    }
}

@Composable
private fun KennzahlenZeile(kennzahlen: Kennzahlen, onFilter: (FallFilter) -> Unit) {
    val rot = LocalZusatzfarben.current.rot
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Kennzahl(
            wert = kennzahlen.faellig.toString(),
            label = "Fällig",
            flaeche = if (kennzahlen.faellig > 0) rot.flaeche else MaterialTheme.colorScheme.surfaceContainer,
            schrift = if (kennzahlen.faellig > 0) rot.schrift else MaterialTheme.colorScheme.onSurface,
            onClick = { onFilter(FallFilter.FAELLIG) },
            modifier = Modifier.weight(1f),
        )
        Kennzahl(
            wert = kennzahlen.offen.toString(),
            label = "Offen",
            flaeche = MaterialTheme.colorScheme.surfaceContainer,
            schrift = MaterialTheme.colorScheme.onSurface,
            onClick = { onFilter(FallFilter.OFFEN) },
            modifier = Modifier.weight(1f),
        )
        Kennzahl(
            wert = Geld.format(kennzahlen.offeneForderungCent),
            label = "Offene Forderungen",
            flaeche = MaterialTheme.colorScheme.surfaceContainer,
            schrift = MaterialTheme.colorScheme.onSurface,
            onClick = { onFilter(FallFilter.OFFEN) },
            modifier = Modifier.weight(1.8f),
        )
    }
}

@Composable
private fun Kennzahl(
    wert: String,
    label: String,
    flaeche: Color,
    schrift: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = flaeche, contentColor = schrift),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = wert,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BenachrichtigungsHinweis(onErlauben: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        ) {
            Icon(Icons.Filled.Notifications, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                "Benachrichtigungen sind aus – die App kann nicht an fällige Wiedervorlagen erinnern.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onErlauben) { Text("Erlauben") }
        }
    }
}

@Composable
private fun Suchfeld(text: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("Name, Tour, Fall-Nr., Stichwort …") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (text.isNotEmpty()) {
            { IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Suche leeren") } }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("suchfeld"),
    )
}

@Composable
private fun FilterZeile(aktiv: FallFilter, kennzahlen: Kennzahlen, onFilter: (FallFilter) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        FallFilter.entries.forEach { filter ->
            val anzahl = when (filter) {
                FallFilter.FAELLIG -> kennzahlen.faellig
                FallFilter.OFFEN -> kennzahlen.offen
                else -> null
            }
            FilterChip(
                selected = filter == aktiv,
                onClick = { onFilter(filter) },
                label = { Text(if (anzahl != null) "${filter.label} ($anzahl)" else filter.label) },
            )
        }
    }
}

@Composable
private fun FallKarte(fall: Schadensfall, heute: LocalDate, onClick: () -> Unit) {
    val faelligkeit = fall.faelligkeit(heute)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = fall.anzeigeName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StatusMarke(fall.status)
            }
            Text(
                text = listOfNotNull(
                    fall.fallnummer,
                    fall.tour.takeIf { it.isNotBlank() },
                    fall.schadensdatum?.let { "Schaden vom ${Datum.format(it)}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (fall.beschreibung.isNotBlank()) {
                Text(
                    text = fall.beschreibung,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val naechstes = listOfNotNull(
                fall.wartetAuf?.let { "Wartet auf ${it.label}" },
                fall.wiedervorlageNotiz.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (fall.status.offen && naechstes.isNotEmpty()) {
                Text(
                    text = naechstes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (faelligkeit != Faelligkeit.Keine) {
                    FaelligkeitsMarke(faelligkeit, text = faelligkeitKurz(faelligkeit, fall.wiedervorlageAm))
                } else if (fall.status.offen) {
                    Text(
                        "Keine Wiedervorlage",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                fall.forderungCent?.let {
                    Text(Geld.format(it), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/** Weit entfernte Termine sind als Datum verständlicher als „in 23 Tagen“. */
private fun faelligkeitKurz(faelligkeit: Faelligkeit, datum: LocalDate?): String =
    if (faelligkeit is Faelligkeit.Kommend && faelligkeit.tage > 7 && datum != null) {
        "Wiedervorlage ${Datum.format(datum)}"
    } else {
        faelligkeit.text()
    }

@Composable
private fun Leerzustand(titel: String, text: String, aktion: String? = null, onAktion: () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lkw),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(titel, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (aktion != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAktion) { Text(aktion) }
        }
    }
}
