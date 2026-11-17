package de.aysetransport.schaden.ui.crm

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.aysetransport.schaden.AppContainer
import de.aysetransport.schaden.data.CrmRepository
import de.aysetransport.schaden.data.model.Adresse
import de.aysetransport.schaden.data.model.Aktivitaet
import de.aysetransport.schaden.data.model.Aufgabe
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.CrmEintrag
import de.aysetransport.schaden.data.model.Dokument
import de.aysetransport.schaden.data.model.Kontaktperson
import de.aysetransport.schaden.data.model.MailNachricht
import de.aysetransport.schaden.data.model.Preis
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.domain.Crm
import de.aysetransport.schaden.domain.FirmenKennzahlen
import de.aysetransport.schaden.data.model.Chance
import de.aysetransport.schaden.domain.Datum
import de.aysetransport.schaden.domain.Geld
import de.aysetransport.schaden.domain.faelligkeit
import de.aysetransport.schaden.ui.komponenten.FaelligkeitsMarke
import de.aysetransport.schaden.ui.komponenten.StatusMarke
import de.aysetransport.schaden.ui.theme.LocalZusatzfarben
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Ein Eintrag der Kontakthistorie: festgehaltener Kontakt oder E-Mail aus einem Fall. */
sealed interface Historie {
    val datum: LocalDate

    data class Eintrag(val aktivitaet: Aktivitaet, val mailId: Long?) : Historie {
        override val datum: LocalDate get() = aktivitaet.datum
    }

    data class Mail(val mail: MailNachricht, val fallnummer: String) : Historie {
        override val datum: LocalDate get() = mail.zeitpunkt.atZone(ZoneId.systemDefault()).toLocalDate()
    }
}

data class FirmaZustand(
    val firma: AuftraggeberEintrag? = null,
    val geloescht: Boolean = false,
    val kontakte: List<Kontaktperson> = emptyList(),
    val adressen: List<Adresse> = emptyList(),
    val preise: List<Preis> = emptyList(),
    val aufgaben: List<Aufgabe> = emptyList(),
    val historie: List<Historie> = emptyList(),
    val dokumente: List<Dokument> = emptyList(),
    val faelle: List<Schadensfall> = emptyList(),
    val chancen: List<Chance> = emptyList(),
    val kennzahlen: FirmenKennzahlen = FirmenKennzahlen(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class FirmaViewModel(private val uuid: String, private val crm: CrmRepository, mailEingerichtet: Flow<Boolean>) : ViewModel() {

    val mailEingerichtet: StateFlow<Boolean> = mailEingerichtet.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Alle anderen Firmen – zum Zusammenführen doppelt angelegter Auftraggeber. */
    val andereFirmen: StateFlow<List<AuftraggeberEintrag>> = crm.beobachteFirmen()
        .map { liste -> liste.filter { it.uuid != uuid } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val zustand: StateFlow<FirmaZustand?> = crm.beobachteFirma(uuid).flatMapLatest { firma ->
        if (firma == null) return@flatMapLatest flowOf(FirmaZustand(geloescht = true))
        val teil1 = combine(crm.beobachteKontakte(uuid), crm.beobachteAdressen(uuid), crm.beobachtePreise(uuid), crm.beobachteAufgaben(uuid), crm.beobachteChancen(uuid)) { k, a, p, au, ch ->
            FirmaZustand(firma, kontakte = k, adressen = a, preise = p, aufgaben = au, chancen = ch)
        }
        combine(teil1, crm.beobachteAktivitaeten(uuid), crm.beobachteMails(firma), crm.beobachteDokumente(uuid), crm.beobachteFaelle(firma)) { z, akt, mails, dok, faelle ->
            val nummern = faelle.associate { it.id to it.fallnummer }
            val mailZuAktivitaet = mails.filter { it.aktivitaetUuid.isNotEmpty() }.associate { it.aktivitaetUuid to it.id }
            val historie = akt.map { Historie.Eintrag(it, mailZuAktivitaet[it.uuid]) } +
                mails.filter { it.fallId != null }.map { Historie.Mail(it, nummern[it.fallId].orEmpty()) }
            z.copy(
                historie = historie.sortedByDescending { h ->
                    when (h) {
                        is Historie.Eintrag -> h.aktivitaet.datum.atStartOfDay(ZoneId.systemDefault()).toInstant().plusMillis(h.aktivitaet.geaendertAm.toEpochMilli() % 86_400_000)
                        is Historie.Mail -> h.mail.zeitpunkt
                    }
                },
                dokumente = dok,
                faelle = faelle,
                kennzahlen = Crm.kennzahlen(faelle, z.chancen, akt.maxOfOrNull { it.datum }),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun firmaSpeichern(f: AuftraggeberEintrag) = viewModelScope.launch { crm.firmaSpeichern(f) }
    fun firmaLoeschen(f: AuftraggeberEintrag) = viewModelScope.launch { crm.firmaLoeschen(f) }
    fun zusammenfuehren(weg: AuftraggeberEintrag, ziel: AuftraggeberEintrag, danach: (String) -> Unit) =
        viewModelScope.launch { crm.zusammenfuehren(weg, ziel); danach(ziel.uuid) }
    fun speichern(e: CrmEintrag) = viewModelScope.launch { crm.speichern(e) }
    fun loeschen(e: CrmEintrag) = viewModelScope.launch { crm.loeschen(e) }
    fun erledigt(a: Aufgabe, wert: Boolean) = viewModelScope.launch { crm.aufgabeErledigt(a, wert) }

    companion object {
        fun factory(container: AppContainer, uuid: String) = viewModelFactory {
            initializer { FirmaViewModel(uuid, container.crm, container.mailEingerichtet) }
        }
    }
}

/** Was gerade bearbeitet wird (ein Dialog gleichzeitig). */
private sealed interface Bearbeitung {
    data object Stammdaten : Bearbeitung
    data class Kontakt(val k: Kontaktperson?) : Bearbeitung
    data class Ort(val a: Adresse?) : Bearbeitung
    data class Kondition(val p: Preis?) : Bearbeitung
    data class Termin(val a: Aufgabe?) : Bearbeitung
    data class Kontaktvermerk(val a: Aktivitaet?) : Bearbeitung
    data class Unterlage(val d: Dokument?) : Bearbeitung
    data class Geschaeft(val c: Chance?) : Bearbeitung
}

@Composable
fun FirmaScreen(
    viewModel: FirmaViewModel,
    onZurueck: () -> Unit,
    onFallOeffnen: (Long) -> Unit,
    onNeuerFall: () -> Unit,
    /** Schreibfenster öffnen; leer = Adresse der Firma. */
    onMailSchreiben: (an: String) -> Unit,
    onMailOeffnen: (Long) -> Unit,
    /** Nach dem Zusammenführen zur verbleibenden Firma wechseln. */
    onFirmaOeffnen: (String) -> Unit = {},
) {
    val zustand by viewModel.zustand.collectAsStateWithLifecycle()
    val andereFirmen by viewModel.andereFirmen.collectAsStateWithLifecycle()
    var zusammengefuehrt by remember { mutableStateOf(false) }
    val mailEingerichtet by viewModel.mailEingerichtet.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var bearbeitung by remember { mutableStateOf<Bearbeitung?>(null) }
    var zeigeErledigte by remember { mutableStateOf(false) }
    val heute = LocalDate.now()

    LaunchedEffect(zustand?.geloescht) { if (zustand?.geloescht == true && !zusammengefuehrt) onZurueck() }
    val z = zustand
    val firma = z?.firma

    fun melde(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun mail(an: String) {
        if (mailEingerichtet) onMailSchreiben(an)
        else oeffne(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(an))), ::melde)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(firma?.anzeigeName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onZurueck) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") } },
                actions = {
                    if (firma != null) {
                        IconButton(onClick = { mail(firma.email) }) { Icon(Icons.Filled.Email, contentDescription = "E-Mail an die Firma") }
                        IconButton(onClick = { bearbeitung = Bearbeitung.Stammdaten }) { Icon(Icons.Filled.Edit, contentDescription = "Stammdaten bearbeiten") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innen ->
        if (z == null || firma == null) return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innen).testTag("firma"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "stammdaten") { Stammdaten(firma, onTelefon = { anrufen(context, it, ::melde) }, onMail = { mail(it) }, onWebsite = { link(context, it, ::melde) }) }
            item(key = "kennzahlen") { KennzahlenKarte(z.kennzahlen) }

            bereich("Verkaufschancen", "chancen", onNeu = { bearbeitung = Bearbeitung.Geschaeft(null) }, leer = z.chancen.isEmpty()) {
                z.chancen.forEach { c ->
                    item(key = "c-${c.uuid}") {
                        Zeile(
                            titel = listOfNotNull(c.titel, c.wertCent?.let(Geld::format)).joinToString(" · "),
                            unter = listOfNotNull(c.phase.label, c.erwartetAm?.let { "Entscheidung bis ${Datum.format(it)}" }, c.notiz.takeIf { it.isNotBlank() }).joinToString(" · "),
                            onClick = { bearbeitung = Bearbeitung.Geschaeft(c) },
                        )
                    }
                }
            }

            bereich("Ansprechpartner", "kontakte", onNeu = { bearbeitung = Bearbeitung.Kontakt(null) }, leer = z.kontakte.isEmpty()) {
                z.kontakte.forEach { k ->
                    item(key = "k-${k.uuid}") {
                        Zeile(
                            titel = k.name,
                            unter = listOf(k.thema, k.telefon, k.email).filter { it.isNotBlank() }.joinToString(" · "),
                            onClick = { bearbeitung = Bearbeitung.Kontakt(k) },
                        ) {
                            IconButton(onClick = { insTelefonbuch(context, k, firma, ::melde) }) { Icon(Icons.Filled.Person, contentDescription = "${k.name} im Telefonbuch speichern") }
                            if (k.telefon.isNotBlank()) IconButton(onClick = { anrufen(context, k.telefon, ::melde) }) { Icon(Icons.Filled.Call, contentDescription = "${k.name} anrufen") }
                            if (k.email.isNotBlank()) IconButton(onClick = { mail(k.email) }) { Icon(Icons.Filled.Email, contentDescription = "E-Mail an ${k.name}") }
                        }
                    }
                }
            }

            bereich("Adressen", "adressen", onNeu = { bearbeitung = Bearbeitung.Ort(null) }, leer = z.adressen.isEmpty()) {
                z.adressen.forEach { a ->
                    item(key = "a-${a.uuid}") {
                        Zeile(a.art.label, listOf(a.einzeilig, a.notiz).filter { it.isNotBlank() }.joinToString("\n"), onClick = { bearbeitung = Bearbeitung.Ort(a) }) {
                            IconButton(onClick = { oeffne(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.einzeilig))), ::melde) }) {
                                Icon(Icons.Filled.LocationOn, contentDescription = "In Karten öffnen")
                            }
                        }
                    }
                }
            }

            bereich("Preise und Konditionen", "preise", onNeu = { bearbeitung = Bearbeitung.Kondition(null) }, leer = z.preise.isEmpty()) {
                item(key = "preise-teilen") {
                    TextButton(onClick = { teile(context, "Preise ${firma.name}", Crm.preislisteText(firma, z.preise), ::melde) }, modifier = Modifier.testTag("preise-teilen")) {
                        Text("Preisliste teilen")
                    }
                }
                z.preise.forEach { p ->
                    item(key = "p-${p.uuid}") {
                        val betrag = listOfNotNull(p.betragCent?.let(Geld::format), p.einheit.takeIf { it.isNotBlank() }).joinToString(" ")
                        Zeile(
                            titel = listOf(p.bezeichnung, betrag).filter { it.isNotBlank() }.joinToString(": "),
                            unter = listOfNotNull(p.gueltigAb?.let { "gültig ab ${Datum.format(it)}" }, p.notiz.takeIf { it.isNotBlank() }).joinToString(" · "),
                            onClick = { bearbeitung = Bearbeitung.Kondition(p) },
                        )
                    }
                }
            }

            val offen = z.aufgaben.filter { !it.erledigt }
            val erledigt = z.aufgaben.filter { it.erledigt }
            bereich("Wiedervorlagen", "aufgaben", onNeu = { bearbeitung = Bearbeitung.Termin(null) }, leer = offen.isEmpty() && erledigt.isEmpty()) {
                (offen + if (zeigeErledigte) erledigt else emptyList()).forEach { a ->
                    item(key = "w-${a.uuid}") {
                        AufgabenZeile(
                            a, heute, onErledigt = { viewModel.erledigt(a, it) }, onClick = { bearbeitung = Bearbeitung.Termin(a) },
                            onKalender = { inKalender(context, a, firma, ::melde) },
                        )
                    }
                }
                if (erledigt.isNotEmpty()) {
                    item(key = "w-erledigt") {
                        TextButton(onClick = { zeigeErledigte = !zeigeErledigte }) { Text(if (zeigeErledigte) "Erledigte ausblenden" else "${erledigt.size} erledigte anzeigen") }
                    }
                }
            }

            bereich("Kontakthistorie", "historie", onNeu = { bearbeitung = Bearbeitung.Kontaktvermerk(null) }, leer = z.historie.isEmpty(), neuText = "Kontakt festhalten") {
                z.historie.take(50).forEach { h ->
                    when (h) {
                        is Historie.Eintrag -> item(key = "h-${h.aktivitaet.uuid}") {
                            Zeile(
                                titel = "${Datum.format(h.datum)} · ${h.aktivitaet.art.label}" + (h.aktivitaet.kontakt.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                unter = h.aktivitaet.text,
                                maxZeilen = 4,
                                onClick = { if (h.mailId != null) onMailOeffnen(h.mailId) else bearbeitung = Bearbeitung.Kontaktvermerk(h.aktivitaet) },
                            )
                        }
                        is Historie.Mail -> item(key = "hm-${h.mail.id}") {
                            Zeile(
                                titel = "${Datum.format(h.datum)} · " + (if (h.mail.eingehend) "E-Mail erhalten" else "E-Mail gesendet") + " · Fall ${h.fallnummer}",
                                unter = (if (h.mail.eingehend) "Von: ${h.mail.von}" else "An: ${h.mail.an}") + "\n" + h.mail.betreff,
                                onClick = { onMailOeffnen(h.mail.id) },
                            )
                        }
                    }
                }
            }

            bereich("Dokumente", "dokumente", onNeu = { bearbeitung = Bearbeitung.Unterlage(null) }, leer = z.dokumente.isEmpty()) {
                z.dokumente.forEach { d ->
                    item(key = "d-${d.uuid}") {
                        Zeile(d.titel, listOf(d.link, d.notiz).filter { it.isNotBlank() }.joinToString("\n"), onClick = { if (d.link.isNotBlank()) link(context, d.link, ::melde) else bearbeitung = Bearbeitung.Unterlage(d) }) {
                            IconButton(onClick = { bearbeitung = Bearbeitung.Unterlage(d) }) { Icon(Icons.Filled.Edit, contentDescription = "${d.titel} bearbeiten") }
                        }
                    }
                }
            }

            bereich("Schadensfälle", "faelle", onNeu = onNeuerFall, leer = z.faelle.isEmpty(), neuText = "Neuer Fall") {
                z.faelle.forEach { f ->
                    item(key = "f-${f.id}") {
                        Card(
                            onClick = { onFallOeffnen(f.id) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${f.fallnummer} · ${f.anzeigeName}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    StatusMarke(f.status)
                                }
                                val fk = f.faelligkeit(heute)
                                if (f.status.offen && f.wiedervorlageAm != null) FaelligkeitsMarke(fk)
                            }
                        }
                    }
                }
            }
        }
    }

    val id = firma?.uuid ?: return
    when (val b = bearbeitung) {
        null -> Unit
        Bearbeitung.Stammdaten -> FirmaDialog(
            firma, onSchliessen = { bearbeitung = null },
            onSpeichern = { viewModel.firmaSpeichern(it); bearbeitung = null },
            onLoeschen = { bearbeitung = null; viewModel.firmaLoeschen(firma) },
            andereFirmen = andereFirmen,
            onZusammenfuehren = { ziel ->
                bearbeitung = null
                zusammengefuehrt = true
                viewModel.zusammenfuehren(firma, ziel) { onFirmaOeffnen(it) }
            },
        )
        is Bearbeitung.Kontakt -> KontaktDialog(id, b.k, { bearbeitung = null }, { viewModel.speichern(it); bearbeitung = null }, b.k?.let { k -> { viewModel.loeschen(k); bearbeitung = null } })
        is Bearbeitung.Ort -> AdresseDialog(id, b.a, { bearbeitung = null }, { viewModel.speichern(it); bearbeitung = null }, b.a?.let { a -> { viewModel.loeschen(a); bearbeitung = null } })
        is Bearbeitung.Kondition -> PreisDialog(id, b.p, { bearbeitung = null }, { viewModel.speichern(it); bearbeitung = null }, b.p?.let { p -> { viewModel.loeschen(p); bearbeitung = null } })
        is Bearbeitung.Termin -> AufgabeDialog(id, b.a, z.kontakte, heute, { bearbeitung = null }, { viewModel.speichern(it); bearbeitung = null }, b.a?.let { a -> { viewModel.loeschen(a); bearbeitung = null } })
        is Bearbeitung.Kontaktvermerk -> AktivitaetDialog(
            id, b.a, z.kontakte, heute, { bearbeitung = null }, { viewModel.speichern(it); bearbeitung = null },
            b.a?.let { a -> { viewModel.loeschen(a); bearbeitung = null } },
            onFolgetermin = { viewModel.speichern(it) },
        )
        is Bearbeitung.Geschaeft -> ChanceDialog(id, b.c, { bearbeitung = null }, { viewModel.speichern(it); bearbeitung = null }, b.c?.let { c -> { viewModel.loeschen(c); bearbeitung = null } })
        is Bearbeitung.Unterlage -> DokumentDialog(id, b.d, { bearbeitung = null }, { viewModel.speichern(it); bearbeitung = null }, b.d?.let { d -> { viewModel.loeschen(d); bearbeitung = null } })
    }
}

/** Überschrift eines Bereichs mit „＋“, darunter die Einträge oder ein Hinweis. */
private fun LazyListScope.bereich(titel: String, tag: String, onNeu: () -> Unit, leer: Boolean, neuText: String = "Hinzufügen", inhalt: LazyListScope.() -> Unit) {
    item(key = "titel-$tag") {
        Column(Modifier.padding(top = 12.dp)) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(titel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = onNeu, modifier = Modifier.testTag("neu-$tag")) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(neuText)
                }
            }
        }
    }
    if (leer) item(key = "leer-$tag") { Text("Noch nichts eingetragen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    else inhalt()
}

@Composable
private fun Stammdaten(f: AuftraggeberEintrag, onTelefon: (String) -> Unit, onMail: (String) -> Unit, onWebsite: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth().testTag("stammdaten")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.anzeigeName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FirmenStatusMarke(f.status)
            }
            if (f.telefon.isNotBlank()) Text("☎ ${f.telefon}", modifier = Modifier.clickable { onTelefon(f.telefon) })
            if (f.email.isNotBlank()) Text("✉ ${f.email}", modifier = Modifier.clickable { onMail(f.email) })
            if (f.website.isNotBlank()) Text("🌐 ${f.website}", modifier = Modifier.clickable { onWebsite(f.website) })
            if (f.notizen.isNotBlank()) Text(f.notizen, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (f.telefon.isBlank() && f.email.isBlank() && f.website.isBlank() && f.notizen.isBlank()) {
                Text("Telefon, E-Mail, Website und Notizen über ✎ oben eintragen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Zeile(titel: String, unter: String, onClick: () -> Unit, maxZeilen: Int = 3, aktionen: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(titel, style = MaterialTheme.typography.bodyLarge)
            if (unter.isNotBlank()) Text(unter, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = maxZeilen, overflow = TextOverflow.Ellipsis)
        }
        aktionen()
    }
}

@Composable
private fun AufgabenZeile(a: Aufgabe, heute: LocalDate, onErledigt: (Boolean) -> Unit, onClick: () -> Unit, onKalender: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Checkbox(checked = a.erledigt, onCheckedChange = onErledigt)
        Column(Modifier.weight(1f)) {
            Text(a.text, style = MaterialTheme.typography.bodyLarge, textDecoration = if (a.erledigt) TextDecoration.LineThrough else null)
            val faellig = a.faelligAm != null && !a.faelligAm.isAfter(heute) && !a.erledigt
            val zeile = listOfNotNull(a.faelligAm?.let { if (it == heute) "heute" else Datum.format(it) }, a.kontakt.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (zeile.isNotEmpty()) Text(zeile, style = MaterialTheme.typography.bodySmall, color = if (faellig) LocalZusatzfarben.current.rot.schrift else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (a.faelligAm != null && !a.erledigt) {
            IconButton(onClick = onKalender) { Icon(Icons.Filled.DateRange, contentDescription = "In den Kalender eintragen") }
        }
    }
}

@Composable
private fun KennzahlenKarte(k: FirmenKennzahlen) {
    val werte = listOfNotNull(
        "Schadensfälle" to "${k.faelleOffen} offen / ${k.faelleGesamt}",
        ("Offene Forderungen" to Geld.format(k.forderungenOffenCent)).takeIf { k.forderungenOffenCent > 0 },
        ("Gezahlt / verglichen" to Geld.format(k.vergleicheCent)).takeIf { k.vergleicheCent > 0 },
        ("Offene Chancen" to "${k.chancenOffen} · ${Geld.format(k.chancenWertCent)}").takeIf { k.chancenOffen > 0 },
        ("Gewonnen" to Geld.format(k.gewonnenWertCent)).takeIf { k.gewonnenWertCent > 0 },
        "Letzter Kontakt" to (k.letzterKontakt?.let(Datum::format) ?: "noch keiner"),
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth().testTag("kennzahlen")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            werte.forEach { (l, w) ->
                Row {
                    Text(l, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(w, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Trägt eine Wiedervorlage in den Kalender des Geräts ein (ganztägig). */
fun inKalender(context: Context, a: Aufgabe, firma: AuftraggeberEintrag, melde: (String) -> Unit) {
    val tag = a.faelligAm ?: return
    val beginn = tag.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    val intent = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
        .putExtra(android.provider.CalendarContract.Events.TITLE, "${firma.name}: ${a.text}")
        .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, listOfNotNull(a.text, a.kontakt.takeIf { it.isNotBlank() }?.let { "Ansprechpartner: $it" }).joinToString("\n"))
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginn)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, beginn + 86_400_000L)
    oeffne(context, intent, melde)
}

private fun oeffne(context: Context, intent: Intent, melde: (String) -> Unit) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        melde("Auf diesem Gerät gibt es keine passende App.")
    }
}

/** Öffnet „Kontakt anlegen“ der Telefonbuch-App mit Name, Firma, Telefon und E-Mail. */
fun insTelefonbuch(context: Context, k: Kontaktperson, firma: AuftraggeberEintrag, melde: (String) -> Unit) {
    val intent = Intent(android.provider.ContactsContract.Intents.Insert.ACTION).apply {
        type = android.provider.ContactsContract.RawContacts.CONTENT_TYPE
        putExtra(android.provider.ContactsContract.Intents.Insert.NAME, k.name)
        putExtra(android.provider.ContactsContract.Intents.Insert.COMPANY, firma.anzeigeName)
        if (k.thema.isNotBlank()) putExtra(android.provider.ContactsContract.Intents.Insert.JOB_TITLE, k.thema)
        if (k.telefon.isNotBlank()) putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, k.telefon)
        if (k.email.isNotBlank()) putExtra(android.provider.ContactsContract.Intents.Insert.EMAIL, k.email)
    }
    oeffne(context, intent, melde)
}

/** Text über die Teilen-Funktion von Android weitergeben (WhatsApp, Mail, Notizen …). */
fun teile(context: Context, betreff: String, text: String, melde: (String) -> Unit) {
    val senden = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, betreff).putExtra(Intent.EXTRA_TEXT, text)
    oeffne(context, Intent.createChooser(senden, betreff), melde)
}

fun anrufen(context: Context, nummer: String, melde: (String) -> Unit) =
    oeffne(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + nummer.filter { it.isDigit() || it == '+' })), melde)

fun link(context: Context, link: String, melde: (String) -> Unit) {
    val ziel = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(link)) link else "https://$link"
    oeffne(context, Intent(Intent.ACTION_VIEW, Uri.parse(ziel)), melde)
}
