package de.aysetransport.schaden.ui.crm

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.aysetransport.schaden.AppContainer
import de.aysetransport.schaden.data.CrmRepository
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.FirmenStatus
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.domain.Crm
import de.aysetransport.schaden.domain.CrmBestand
import de.aysetransport.schaden.domain.Datum
import de.aysetransport.schaden.domain.Fallliste
import de.aysetransport.schaden.domain.FallFilter
import de.aysetransport.schaden.domain.FirmenZeile
import de.aysetransport.schaden.domain.Termin
import de.aysetransport.schaden.ui.komponenten.StatusMarke
import de.aysetransport.schaden.ui.theme.LocalZusatzfarben
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class FirmenZustand(
    val laedt: Boolean = true,
    val zeilen: List<FirmenZeile> = emptyList(),
    /** Bei einer Suche: passende Schadensfälle. */
    val faelle: List<Schadensfall> = emptyList(),
    val ganzLeer: Boolean = false,
    val status: FirmenStatus? = null,
    val suchtext: String = "",
    val heute: LocalDate = LocalDate.now(),
)

/** Gemeinsamer Datenbestand für Firmenliste, Suche und Wiedervorlagen. */
fun bestand(crm: CrmRepository, faelle: Flow<List<Schadensfall>>): Flow<CrmBestand> =
    combine(
        combine(crm.beobachteFirmen(), crm.beobachteAlleKontakte(), crm.beobachteAlleAdressen(), crm.beobachteAlleAktivitaeten()) { f, k, a, ak -> CrmBestand(f, k, a, ak) },
        crm.beobachteAlleDokumente(),
        crm.beobachteOffeneAufgaben(),
        faelle,
    ) { b, d, au, fa -> b.copy(dokumente = d, offeneAufgaben = au, faelle = fa) }

class FirmenViewModel(private val crm: CrmRepository, repository: SchadensfallRepository) : ViewModel() {
    private val status = MutableStateFlow<FirmenStatus?>(null)
    private val suchtext = MutableStateFlow("")
    private val heute = MutableStateFlow(LocalDate.now())

    val zustand: StateFlow<FirmenZustand> = combine(bestand(crm, repository.beobachteFaelle()), status, suchtext, heute) { b, st, such, tag ->
        FirmenZustand(
            laedt = false,
            zeilen = Crm.firmenliste(b, st, such, tag),
            faelle = if (such.isBlank()) emptyList() else Fallliste.filtereUndSortiere(b.faelle, FallFilter.ALLE, such, tag),
            ganzLeer = b.firmen.isEmpty(),
            status = st,
            suchtext = such,
            heute = tag,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FirmenZustand())

    fun setzeStatus(neu: FirmenStatus?) { status.value = neu }
    fun setzeSuchtext(neu: String) { suchtext.value = neu }
    fun aktualisiereHeute() { heute.value = LocalDate.now() }

    fun anlegen(firma: AuftraggeberEintrag, danach: (String) -> Unit) = viewModelScope.launch { danach(crm.firmaSpeichern(firma)) }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { FirmenViewModel(container.crm, container.repository) } }
    }
}

/** Startseite: alle Auftraggeber mit Suche, Statusfilter und nächster Wiedervorlage. */
@Composable
fun FirmenScreen(
    viewModel: FirmenViewModel,
    unten: @Composable () -> Unit,
    onFirmaOeffnen: (String) -> Unit,
    onFallOeffnen: (Long) -> Unit,
    onEinstellungen: () -> Unit,
) {
    val zustand by viewModel.zustand.collectAsStateWithLifecycle()
    var neueFirma by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        viewModel.aktualisiereHeute()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auftraggeber") },
                actions = { IconButton(onClick = onEinstellungen) { Icon(Icons.Filled.Settings, contentDescription = "Einstellungen") } },
            )
        },
        bottomBar = unten,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { neueFirma = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = "Neue Firma") },
                text = { Text("Neue Firma") },
            )
        },
    ) { innen ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innen).testTag("firmenliste"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "suche") {
                OutlinedTextField(
                    value = zustand.suchtext,
                    onValueChange = viewModel::setzeSuchtext,
                    singleLine = true,
                    placeholder = { Text("Firma, Ansprechpartner, Notiz, Fall …") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = if (zustand.suchtext.isNotEmpty()) {
                        { IconButton(onClick = { viewModel.setzeSuchtext("") }) { Icon(Icons.Filled.Clear, contentDescription = "Suche leeren") } }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().testTag("firmen-suche"),
                )
            }
            item(key = "status") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(selected = zustand.status == null, onClick = { viewModel.setzeStatus(null) }, label = { Text("Alle") })
                    FirmenStatus.entries.forEach { s ->
                        FilterChip(selected = zustand.status == s, onClick = { viewModel.setzeStatus(s) }, label = { Text(s.label) })
                    }
                }
            }
            when {
                zustand.laedt -> Unit
                zustand.ganzLeer -> item(key = "leer") {
                    Leer(
                        "Noch keine Auftraggeber",
                        "Lege die erste Firma an oder importiere die Sicherung des Browser-CRM unter Einstellungen → Daten. " +
                            "Auftraggeber aus Schadensfällen kommen automatisch dazu.",
                    )
                }
                zustand.zeilen.isEmpty() && zustand.faelle.isEmpty() -> item(key = "nichts") {
                    Leer("Keine Treffer", if (zustand.suchtext.isNotBlank()) "Zu „${zustand.suchtext.trim()}“ wurde nichts gefunden." else "In dieser Ansicht gibt es keine Firmen.")
                }
                else -> {
                    items(zustand.zeilen, key = { it.firma.uuid }) { z -> FirmenKarte(z, zustand.heute, onClick = { onFirmaOeffnen(z.firma.uuid) }) }
                    if (zustand.faelle.isNotEmpty()) {
                        item(key = "faelle-titel") {
                            Text("Schadensfälle", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                        }
                        items(zustand.faelle, key = { "fall-${it.id}" }) { f ->
                            Card(
                                onClick = { onFallOeffnen(f.id) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(f.anzeigeName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOf(f.fallnummer, f.auftraggeber).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    StatusMarke(f.status)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (neueFirma) {
        FirmaDialog(
            firma = null,
            onSchliessen = { neueFirma = false },
            onSpeichern = { f -> neueFirma = false; viewModel.anlegen(f, onFirmaOeffnen) },
        )
    }
}

@Composable
private fun FirmenKarte(z: FirmenZeile, heute: LocalDate, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(z.firma.anzeigeName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                FirmenStatusMarke(z.firma.status)
            }
            z.treffer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val zeile = listOfNotNull(
                z.offeneFaelle.takeIf { it > 0 }?.let { if (it == 1) "1 offener Schadensfall" else "$it offene Schadensfälle" },
                z.firma.telefon.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (zeile.isNotEmpty()) Text(zeile, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            z.naechsterTermin?.let { TerminZeile(it, heute) }
        }
    }
}

/** „Nächste Wiedervorlage: …“ – rot, wenn überfällig oder heute. */
@Composable
fun TerminZeile(t: Termin, heute: LocalDate) {
    val faellig = !t.datum.isAfter(heute)
    val text = when {
        t.datum.isBefore(heute) -> "Überfällig seit ${Datum.format(t.datum)}"
        t.datum == heute -> "Heute"
        else -> Datum.format(t.datum)
    }
    Text(
        "⏱ $text: ${t.titel}",
        style = MaterialTheme.typography.labelMedium,
        color = if (faellig) LocalZusatzfarben.current.rot.schrift else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun FirmenStatusMarke(status: FirmenStatus) {
    val farbe = when (status) {
        FirmenStatus.AKTIV -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        FirmenStatus.INTERESSENT -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = farbe.first, contentColor = farbe.second)) {
        Text(status.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
fun Leer(titel: String, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp)) {
        Text(titel, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}
