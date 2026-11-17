package de.aysetransport.schaden.ui.crm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
import de.aysetransport.schaden.data.model.Aufgabe
import de.aysetransport.schaden.domain.Crm
import de.aysetransport.schaden.domain.Datum
import de.aysetransport.schaden.domain.Termin
import de.aysetransport.schaden.domain.Wiedervorlagen
import de.aysetransport.schaden.ui.theme.LocalZusatzfarben
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class WiedervorlagenViewModel(private val crm: CrmRepository, repository: SchadensfallRepository) : ViewModel() {
    private val heute = MutableStateFlow(LocalDate.now())
    val zustand: StateFlow<Pair<Wiedervorlagen, LocalDate>?> = combine(bestand(crm, repository.beobachteFaelle()), heute) { b, tag -> Crm.wiedervorlagen(b, tag) to tag }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun aktualisiereHeute() { heute.value = LocalDate.now() }
    fun erledigt(a: Aufgabe) = viewModelScope.launch { crm.aufgabeErledigt(a, true) }

    /** Wiedervorlage verschieben – ab heute gerechnet, auch wenn sie überfällig war. */
    fun verschieben(a: Aufgabe, tage: Long) = viewModelScope.launch { crm.speichern(a.copy(faelligAm = LocalDate.now().plusDays(tage))) }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { WiedervorlagenViewModel(container.crm, container.repository) } }
    }
}

/** Alle Wiedervorlagen auf einen Blick: Aufgaben bei Firmen und Wiedervorlagen der Schadensfälle. */
@Composable
fun WiedervorlagenScreen(
    viewModel: WiedervorlagenViewModel,
    unten: @Composable () -> Unit,
    onFirmaOeffnen: (String) -> Unit,
    onFallOeffnen: (Long) -> Unit,
    onEinstellungen: () -> Unit,
) {
    val zustand by viewModel.zustand.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.aktualisiereHeute()
        onPauseOrDispose { }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wiedervorlagen") },
                actions = { IconButton(onClick = onEinstellungen) { Icon(Icons.Filled.Settings, contentDescription = "Einstellungen") } },
            )
        },
        bottomBar = unten,
    ) { innen ->
        val (w, heute) = zustand ?: return@Scaffold
        val rot = LocalZusatzfarben.current.rot.schrift
        val blau = MaterialTheme.colorScheme.primary
        val grau = MaterialTheme.colorScheme.onSurfaceVariant
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innen).testTag("wiedervorlagen"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (w.ueberfaellig.isEmpty() && w.heute.isEmpty() && w.demnaechst.isEmpty() && w.ohneDatum.isEmpty()) {
                item { Leer("Nichts zu tun", "Hier erscheinen Wiedervorlagen bei Firmen und die Wiedervorlagen der Schadensfälle.") }
            }
            gruppe("Überfällig", "ueberfaellig", w.ueberfaellig, heute, rot, viewModel, onFirmaOeffnen, onFallOeffnen)
            gruppe("Heute", "heute", w.heute, heute, blau, viewModel, onFirmaOeffnen, onFallOeffnen)
            gruppe("Demnächst", "demnaechst", w.demnaechst, heute, grau, viewModel, onFirmaOeffnen, onFallOeffnen)
            if (w.ohneDatum.isNotEmpty()) {
                item(key = "ohne") { Ueberschrift("Ohne Datum", grau) }
                w.ohneDatum.forEach { a ->
                    item(key = "o-${a.uuid}") {
                        TerminEintrag(a.text, "", null, aufgabe = a, viewModel = viewModel, onClick = { onFirmaOeffnen(a.auftraggeberUuid) })
                    }
                }
            }
        }
    }
}

private fun LazyListScope.gruppe(
    titel: String, tag: String, termine: List<Termin>, heute: LocalDate, farbe: Color,
    viewModel: WiedervorlagenViewModel, onFirma: (String) -> Unit, onFall: (Long) -> Unit,
) {
    if (termine.isEmpty()) return
    item(key = "g-$tag") { Ueberschrift("$titel (${termine.size})", farbe) }
    termine.forEach { t ->
        when (t) {
            is Termin.CrmAufgabe -> item(key = "a-${t.aufgabe.uuid}") {
                TerminEintrag(t.titel, t.zusatz, t.datum.takeIf { it != heute }, aufgabe = t.aufgabe, viewModel = viewModel, onClick = { onFirma(t.aufgabe.auftraggeberUuid) })
            }
            is Termin.Fall -> item(key = "f-${t.fall.id}") {
                TerminEintrag(t.titel, t.zusatz, t.datum.takeIf { it != heute }, aufgabe = null, viewModel = viewModel, onClick = { onFall(t.fall.id) })
            }
        }
    }
}

@Composable
private fun Ueberschrift(text: String, farbe: Color) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = farbe, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun TerminEintrag(titel: String, zusatz: String, datum: LocalDate?, aufgabe: Aufgabe?, viewModel: WiedervorlagenViewModel, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        if (aufgabe != null) {
            Checkbox(checked = false, onCheckedChange = { viewModel.erledigt(aufgabe) }, modifier = Modifier.testTag("erledigt-${aufgabe.uuid}"))
        } else {
            Text("🚚", modifier = Modifier.padding(horizontal = 14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(titel, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val zeile = listOfNotNull(datum?.let(Datum::format), zusatz.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (zeile.isNotEmpty()) Text(zeile, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (aufgabe != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.verschieben(aufgabe, 1) }, modifier = Modifier.testTag("morgen-${aufgabe.uuid}")) { Text("Morgen") }
                    TextButton(onClick = { viewModel.verschieben(aufgabe, 7) }, modifier = Modifier.testTag("woche-${aufgabe.uuid}")) { Text("+1 Woche") }
                }
            }
        }
    }
}
