package de.aysetransport.schaden.ui.mail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.aysetransport.schaden.AppContainer
import de.aysetransport.schaden.data.db.CrmDao
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.MailNachricht
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.domain.Datum
import de.aysetransport.schaden.mail.MailAnhangSpeicher
import de.aysetransport.schaden.mail.MailPostfach
import de.aysetransport.schaden.mail.MailZuordnung
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

enum class PostfachFilter(val label: String) { OFFEN("Nicht zugeordnet"), ALLE("Alle"), FAELLE("Zu Fällen"), FIRMEN("Zu Firmen") }

data class PostfachZustand(
    val mails: List<MailNachricht> = emptyList(),
    val filter: PostfachFilter = PostfachFilter.OFFEN,
    val suche: String = "",
    val anzahlOffen: Int = 0,
    val firmen: Map<String, AuftraggeberEintrag> = emptyMap(),
    val faelle: Map<Long, Schadensfall> = emptyMap(),
    val laedt: Boolean = true,
)

/** Beschriftung der Zuordnung einer Mail, z. B. „Fall 2026-003“ oder „Seats and Sofas“. */
fun zuordnungText(m: MailNachricht, firmen: Map<String, AuftraggeberEintrag>, faelle: Map<Long, Schadensfall>): String =
    listOfNotNull(
        m.fallId?.let { faelle[it]?.let { f -> "Fall ${f.fallnummer}" } ?: "Fall" },
        m.auftraggeberUuid?.let { firmen[it]?.name },
        m.kundeRef.takeIf { it.isNotBlank() }?.let { "Kunde: $it" },
    ).joinToString(" · ").ifEmpty { "Nicht zugeordnet" }

class PostfachViewModel(
    crmDao: CrmDao,
    firmen: Flow<List<AuftraggeberEintrag>>,
    faelle: Flow<List<Schadensfall>>,
    private val postfach: MailPostfach,
    mailEingerichtet: Flow<Boolean>,
) : ViewModel() {
    private val filter = MutableStateFlow(PostfachFilter.OFFEN)
    private val suche = MutableStateFlow("")
    val eingerichtet: StateFlow<Boolean> = mailEingerichtet.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    var ruftAb by mutableStateOf(false)
        private set
    private val meldungsKanal = Channel<String>(Channel.BUFFERED)
    val meldungen: Flow<String> = meldungsKanal.receiveAsFlow()

    val zustand: StateFlow<PostfachZustand> = combine(crmDao.beobachteAlleMails(), filter, firmen, faelle, suche) { mails, f, fi, fa, such ->
        val firmenNamen = fi.associate { it.uuid to it.name }
        val begriffe = such.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        PostfachZustand(
            mails = mails.filter {
                // Mit Suchbegriff wird in allen Mails gesucht, sonst im gewählten Reiter.
                if (begriffe.isNotEmpty()) {
                    val text = listOf(it.von, it.an, it.betreff, it.text, it.kundeRef, it.anhaenge, firmenNamen[it.auftraggeberUuid].orEmpty()).joinToString("\n").lowercase()
                    return@filter begriffe.all { b -> b in text }
                }
                when (f) {
                    PostfachFilter.OFFEN -> !it.istZugeordnet
                    PostfachFilter.ALLE -> true
                    PostfachFilter.FAELLE -> it.fallId != null
                    PostfachFilter.FIRMEN -> it.auftraggeberUuid != null
                }
            },
            filter = f,
            suche = such,
            anzahlOffen = mails.count { !it.istZugeordnet },
            firmen = fi.associateBy { it.uuid },
            faelle = fa.associateBy { it.id },
            laedt = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PostfachZustand())

    fun setzeFilter(f: PostfachFilter) { filter.value = f }
    fun setzeSuche(text: String) { suche.value = text }

    fun abrufen() {
        if (ruftAb) return
        ruftAb = true
        viewModelScope.launch {
            try {
                val neu = postfach.abrufen()
                val offen = neu.count { it.fall == null && it.firma == null }
                meldungsKanal.send(
                    when {
                        neu.isEmpty() -> "Keine neuen E-Mails"
                        offen == 0 -> "${neu.size} neue E-Mail(s), alle zugeordnet"
                        else -> "${neu.size} neue E-Mail(s), davon $offen nicht zugeordnet"
                    },
                )
            } catch (e: IOException) {
                meldungsKanal.send(e.message ?: "Postfach nicht erreichbar")
            } finally {
                ruftAb = false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                PostfachViewModel(container.datenbank.crm(), container.crm.beobachteFirmen(), container.repository.beobachteFaelle(), container.postfach, container.mailEingerichtet)
            }
        }
    }
}

/** Alle Mails beider Postfächer; der Reiter „Nicht zugeordnet“ ist der Eingang für alles ohne Fall oder Firma. */
@Composable
fun PostfachScreen(
    viewModel: PostfachViewModel,
    unten: @Composable () -> Unit,
    onMailOeffnen: (Long) -> Unit,
    onNeueMail: () -> Unit,
    onEinstellungen: () -> Unit,
) {
    val zustand by viewModel.zustand.collectAsStateWithLifecycle()
    val eingerichtet by viewModel.eingerichtet.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.meldungen.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Postfach") },
                actions = {
                    if (eingerichtet) IconButton(onClick = viewModel::abrufen, enabled = !viewModel.ruftAb) { Icon(Icons.Filled.Refresh, contentDescription = "Postfach abrufen") }
                    IconButton(onClick = onEinstellungen) { Icon(Icons.Filled.Settings, contentDescription = "Einstellungen") }
                },
            )
        },
        bottomBar = unten,
        floatingActionButton = {
            if (eingerichtet) {
                ExtendedFloatingActionButton(onClick = onNeueMail, icon = { Icon(Icons.Filled.Add, contentDescription = "Neue E-Mail") }, text = { Text("Neue E-Mail") })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innen ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innen).testTag("postfach"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (viewModel.ruftAb) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!eingerichtet) {
                item {
                    Card(onClick = onEinstellungen, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Noch kein E-Mail-Konto eingerichtet. Unter Einstellungen → E-Mail lassen sich ein Postfach für Schadensfälle und eines für alles andere einrichten.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = zustand.suche, onValueChange = viewModel::setzeSuche, singleLine = true,
                    placeholder = { Text("Mails durchsuchen (Absender, Betreff, Text …)") },
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().testTag("postfach-suche"),
                )
            }
            if (zustand.suche.isBlank()) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    PostfachFilter.entries.forEach { f ->
                        FilterChip(
                            selected = zustand.filter == f, onClick = { viewModel.setzeFilter(f) },
                            label = { Text(if (f == PostfachFilter.OFFEN) "${f.label} (${zustand.anzahlOffen})" else f.label) },
                        )
                    }
                }
            }
            if (!zustand.laedt && zustand.mails.isEmpty()) {
                item {
                    Text(
                        if (zustand.filter == PostfachFilter.OFFEN) "Alle E-Mails sind zugeordnet." else "Keine E-Mails in dieser Ansicht.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(zustand.mails, key = { it.id }) { m ->
                Card(
                    onClick = { onMailOeffnen(m.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row {
                            Text(
                                (if (m.eingehend) m.von else "An: ${m.an}"),
                                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Text(Datum.formatMitUhrzeit(m.zeitpunkt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(m.betreff.ifBlank { "(ohne Betreff)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${m.konto.label} · ${zuordnungText(m, zustand.firmen, zustand.faelle)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (m.istZugeordnet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------ Eine Mail ------------------------------

class MailAnsichtViewModel(
    private val mailId: Long,
    crmDao: CrmDao,
    firmen: Flow<List<AuftraggeberEintrag>>,
    faelle: Flow<List<Schadensfall>>,
    private val postfach: MailPostfach,
    val anhaenge: MailAnhangSpeicher,
) : ViewModel() {
    data class Zustand(val mail: MailNachricht?, val firmen: List<AuftraggeberEintrag>, val faelle: List<Schadensfall>)

    val zustand: StateFlow<Zustand?> = combine(crmDao.beobachteMail(mailId), firmen, faelle) { m, fi, fa -> Zustand(m, fi, fa) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var fehler by mutableStateOf<String?>(null)
        private set

    fun zuordnen(ziel: MailZuordnung) = viewModelScope.launch {
        try {
            postfach.zuordnen(mailId, ziel)
        } catch (e: IOException) {
            fehler = e.message
        }
    }

    companion object {
        fun factory(container: AppContainer, mailId: Long) = viewModelFactory {
            initializer {
                MailAnsichtViewModel(mailId, container.datenbank.crm(), container.crm.beobachteFirmen(), container.repository.beobachteFaelle(), container.postfach, container.mailAnhaenge)
            }
        }
    }
}

@Composable
fun MailAnsichtScreen(
    viewModel: MailAnsichtViewModel,
    onZurueck: () -> Unit,
    onAntworten: (MailNachricht) -> Unit,
    onFallOeffnen: (Long) -> Unit,
    onFirmaOeffnen: (String) -> Unit,
) {
    val zustand by viewModel.zustand.collectAsStateWithLifecycle()
    var zuordnen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("E-Mail") },
                navigationIcon = { IconButton(onClick = onZurueck) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") } },
                actions = {
                    zustand?.mail?.let { m -> IconButton(onClick = { onAntworten(m) }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Antworten") } }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innen ->
        val z = zustand ?: return@Scaffold
        val m = z.mail ?: run { Text("Die E-Mail gibt es nicht mehr.", modifier = Modifier.padding(innen).padding(16.dp)); return@Scaffold }
        val firmen = z.firmen.associateBy { it.uuid }
        val faelle = z.faelle.associateBy { it.id }
        Column(Modifier.fillMaxSize().padding(innen).verticalScroll(rememberScrollState()).padding(16.dp).testTag("mail-ansicht"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(m.betreff.ifBlank { "(ohne Betreff)" }, style = MaterialTheme.typography.titleLarge)
            Text((if (m.eingehend) "Von: ${m.von}" else "Von: ${m.von}\nAn: ${m.an}"), style = MaterialTheme.typography.bodyMedium)
            Text("${Datum.formatMitUhrzeit(m.zeitpunkt)} · Postfach ${m.konto.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(colors = CardDefaults.cardColors(containerColor = if (m.istZugeordnet) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Zugeordnet: ${zuordnungText(m, firmen, faelle)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("mail-zuordnung"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Button(onClick = { zuordnen = true }, modifier = Modifier.testTag("mail-zuordnen")) { Text(if (m.istZugeordnet) "Umhängen" else "Zuordnen") }
                        m.fallId?.let { id -> OutlinedButton(onClick = { onFallOeffnen(id) }) { Text("Zum Fall") } }
                        m.auftraggeberUuid?.let { id -> OutlinedButton(onClick = { onFirmaOeffnen(id) }) { Text("Zur Firma") } }
                    }
                }
            }
            val dateien = viewModel.anhaenge.dateien(m.id)
            if (dateien.isNotEmpty()) {
                Text("Anhänge", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                dateien.forEach { d -> Text("📎 ${viewModel.anhaenge.anzeigename(d)}", modifier = Modifier.clickable { oeffneDatei(context, d) }.padding(vertical = 4.dp)) }
            } else if (m.anhaenge.isNotBlank()) {
                Text("Anhänge: " + m.anhaenge.lines().joinToString(", ") + if (m.fallId != null) " (beim Fall abgelegt)" else "", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text(m.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
    val z = zustand
    if (zuordnen && z?.mail != null) {
        ZuordnenDialog(z.mail, z.firmen, z.faelle, onFertig = { ziel ->
            zuordnen = false
            ziel?.let(viewModel::zuordnen)
        })
    }
}

private fun oeffneDatei(context: android.content.Context, datei: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", datei)
    val typ = MimeTypeMap.getSingleton().getMimeTypeFromExtension(datei.extension.lowercase()) ?: "*/*"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, typ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: ActivityNotFoundException) {
        // keine App für diesen Dateityp
    }
}

/** Zuordnung wählen: Fall (der seine Firma mitbringt), sonst Firma, dazu optional ein Endkunde. `null` = abgebrochen. */
@Composable
fun ZuordnenDialog(mail: MailNachricht, firmen: List<AuftraggeberEintrag>, faelle: List<Schadensfall>, onFertig: (MailZuordnung?) -> Unit) {
    var fallId by remember { mutableStateOf(mail.fallId) }
    var firma by remember { mutableStateOf(mail.auftraggeberUuid) }
    var kunde by remember { mutableStateOf(mail.kundeRef) }
    var suche by remember { mutableStateOf("") }
    val such = suche.trim()
    val passendeFirmen = firmen.filter { such.isEmpty() || it.name.contains(such, ignoreCase = true) }.take(30)
    val passendeFaelle = faelle.filter { such.isEmpty() || listOf(it.fallnummer, it.anzeigeName, it.auftraggeber).any { t -> t.contains(such, ignoreCase = true) } }
        .sortedWith(compareBy({ !it.status.offen }, { it.fallnummer })).take(30)
    AlertDialog(
        onDismissRequest = { onFertig(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        title = { Text("E-Mail zuordnen") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(suche, { suche = it }, placeholder = { Text("Firma oder Fall suchen …") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("zuordnen-suche"))
                Text("Schadensfall", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Auswahlzeile("Kein Fall", fallId == null) { fallId = null }
                passendeFaelle.forEach { f -> Auswahlzeile("${f.fallnummer} · ${f.anzeigeName}" + (f.auftraggeber.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), fallId == f.id) { fallId = f.id; firma = null } }
                Text("Firma", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (fallId != null) Text("Bei einem Fall gehört die Mail automatisch auch zu dessen Firma.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    Auswahlzeile("Keine Firma", firma == null) { firma = null }
                    passendeFirmen.forEach { f -> Auswahlzeile(f.anzeigeName, firma == f.uuid) { firma = f.uuid } }
                }
                Text("Kunde", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    kunde, { kunde = it }, label = { Text("Name oder E-Mail des Endkunden") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("zuordnen-kunde"),
                )
                if (mail.eingehend && kunde.isBlank()) TextButton(onClick = { kunde = mail.von }) { Text("Absender übernehmen") }
            }
        },
        confirmButton = { TextButton(onClick = { onFertig(MailZuordnung(fallId, if (fallId == null) firma else null, kunde)) }, modifier = Modifier.testTag("zuordnen-ok")) { Text("Übernehmen") } },
        dismissButton = { TextButton(onClick = { onFertig(null) }) { Text("Abbrechen") } },
    )
}

@Composable
private fun Auswahlzeile(text: String, gewaehlt: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        androidx.compose.material3.RadioButton(selected = gewaehlt, onClick = onClick)
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
