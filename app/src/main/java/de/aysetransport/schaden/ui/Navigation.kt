package de.aysetransport.schaden.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import de.aysetransport.schaden.AppContainer
import de.aysetransport.schaden.domain.FallFilter
import de.aysetransport.schaden.ui.bearbeiten.BearbeitenScreen
import de.aysetransport.schaden.ui.bearbeiten.BearbeitenViewModel
import de.aysetransport.schaden.ui.crm.FirmaScreen
import de.aysetransport.schaden.ui.crm.FirmaViewModel
import de.aysetransport.schaden.ui.crm.FirmenScreen
import de.aysetransport.schaden.ui.crm.FirmenViewModel
import de.aysetransport.schaden.ui.crm.WiedervorlagenScreen
import de.aysetransport.schaden.ui.crm.WiedervorlagenViewModel
import de.aysetransport.schaden.ui.detail.DetailScreen
import de.aysetransport.schaden.ui.detail.DetailViewModel
import de.aysetransport.schaden.ui.einstellungen.EinstellungenScreen
import de.aysetransport.schaden.ui.einstellungen.EinstellungenViewModel
import de.aysetransport.schaden.ui.komponenten.Bereich
import de.aysetransport.schaden.ui.komponenten.Hauptleiste
import de.aysetransport.schaden.ui.komponenten.Hinweiszahlen
import de.aysetransport.schaden.ui.mail.MailAnsichtScreen
import de.aysetransport.schaden.ui.mail.MailAnsichtViewModel
import de.aysetransport.schaden.ui.mail.MailSchreibenScreen
import de.aysetransport.schaden.ui.mail.MailSchreibenViewModel
import de.aysetransport.schaden.ui.mail.PostfachScreen
import de.aysetransport.schaden.ui.mail.PostfachViewModel
import de.aysetransport.schaden.ui.uebersicht.UebersichtScreen
import de.aysetransport.schaden.ui.uebersicht.UebersichtViewModel
import kotlinx.serialization.Serializable

/** Startseite: die Firmen (Auftraggeber). */
@Serializable
data object FirmenZiel

/** Die bisherige Fallliste der Schadensfälle. */
@Serializable
data object UebersichtZiel

@Serializable
data object WiedervorlagenZiel

@Serializable
data object PostfachZiel

@Serializable
data class FirmaZiel(val uuid: String)

@Serializable
data class DetailZiel(val fallId: Long)

/** `fallId = 0` legt einen neuen Fall an; [firma] belegt dann den Auftraggeber vor. */
@Serializable
data class BearbeitenZiel(val fallId: Long = 0, val firma: String = "")

@Serializable
data object EinstellungenZiel

/**
 * Schreibfenster. Mit [fallId] zu einem Fall (Vorlagen, Anlagen, Fallnummer im Betreff), sonst an eine
 * Firma ([firma]) bzw. an [an] oder als Antwort auf eine Mail aus dem Postfach ([antwortAufMail]).
 */
@Serializable
data class MailZiel(
    val fallId: Long = 0,
    val vorlage: String = "",
    val empfaenger: String = "",
    val antwortAuf: String = "",
    val firma: String = "",
    val an: String = "",
    val antwortAufMail: Long = 0,
)

@Serializable
data class MailAnsichtZiel(val mailId: Long)

/** Wohin die App springen soll, wenn sie über eine Benachrichtigung geöffnet wurde. */
sealed interface Startauftrag {
    data class Fall(val fallId: Long) : Startauftrag
    data object FaelligeListe : Startauftrag
    data class Firma(val uuid: String) : Startauftrag
    data object Postfach : Startauftrag
}

private fun NavHostController.zuBereich(b: Bereich) {
    val ziel: Any = when (b) {
        Bereich.FIRMEN -> FirmenZiel
        Bereich.FAELLE -> UebersichtZiel
        Bereich.WIEDERVORLAGEN -> WiedervorlagenZiel
        Bereich.POSTFACH -> PostfachZiel
    }
    navigate(ziel) {
        popUpTo<FirmenZiel> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun SchadensfallNavigation(
    container: AppContainer,
    startauftrag: Startauftrag?,
    onStartauftragErledigt: () -> Unit,
) {
    val nav = rememberNavController()
    var gewuenschterFilter by remember { mutableStateOf<FallFilter?>(null) }
    val zahlen by container.hinweiszahlen.collectAsStateWithLifecycle(Hinweiszahlen())
    val leiste: @Composable (Bereich) -> Unit = { aktiv -> Hauptleiste(aktiv, zahlen) { nav.zuBereich(it) } }

    LaunchedEffect(startauftrag) {
        when (startauftrag) {
            null -> return@LaunchedEffect
            is Startauftrag.Fall -> nav.navigate(DetailZiel(startauftrag.fallId)) {
                popUpTo<FirmenZiel>()
                launchSingleTop = true
            }
            is Startauftrag.Firma -> nav.navigate(FirmaZiel(startauftrag.uuid)) {
                popUpTo<FirmenZiel>()
                launchSingleTop = true
            }
            Startauftrag.FaelligeListe -> nav.zuBereich(Bereich.WIEDERVORLAGEN)
            Startauftrag.Postfach -> nav.zuBereich(Bereich.POSTFACH)
        }
        onStartauftragErledigt()
    }

    val zumFall: (Long) -> Unit = { nav.navigate(DetailZiel(it)) { launchSingleTop = true } }
    val zurFirma: (String) -> Unit = { nav.navigate(FirmaZiel(it)) { launchSingleTop = true } }
    val zurMail: (Long) -> Unit = { nav.navigate(MailAnsichtZiel(it)) { launchSingleTop = true } }
    val zuEinstellungen: () -> Unit = { nav.navigate(EinstellungenZiel) { launchSingleTop = true } }

    // Jeder Bildschirm entfernt beim Schließen gezielt sich selbst. Ein doppelter Aufruf
    // (z. B. Zurück-Taste während des Löschens) kann so nie die Startseite mit abräumen.
    NavHost(navController = nav, startDestination = FirmenZiel) {
        composable<FirmenZiel> {
            val viewModel: FirmenViewModel = viewModel(factory = FirmenViewModel.factory(container))
            FirmenScreen(
                viewModel = viewModel,
                unten = { leiste(Bereich.FIRMEN) },
                onFirmaOeffnen = zurFirma,
                onFallOeffnen = zumFall,
                onEinstellungen = zuEinstellungen,
            )
        }
        composable<UebersichtZiel> {
            val viewModel: UebersichtViewModel = viewModel(factory = UebersichtViewModel.factory(container))
            LaunchedEffect(gewuenschterFilter) {
                gewuenschterFilter?.let {
                    viewModel.setzeFilter(it)
                    gewuenschterFilter = null
                }
            }
            UebersichtScreen(
                viewModel = viewModel,
                onFallOeffnen = zumFall,
                onNeuerFall = { nav.navigate(BearbeitenZiel()) { launchSingleTop = true } },
                onEinstellungen = zuEinstellungen,
                unten = { leiste(Bereich.FAELLE) },
            )
        }
        composable<WiedervorlagenZiel> {
            val viewModel: WiedervorlagenViewModel = viewModel(factory = WiedervorlagenViewModel.factory(container))
            WiedervorlagenScreen(
                viewModel = viewModel,
                unten = { leiste(Bereich.WIEDERVORLAGEN) },
                onFirmaOeffnen = zurFirma,
                onFallOeffnen = zumFall,
                onEinstellungen = zuEinstellungen,
            )
        }
        composable<PostfachZiel> {
            val viewModel: PostfachViewModel = viewModel(factory = PostfachViewModel.factory(container))
            PostfachScreen(
                viewModel = viewModel,
                unten = { leiste(Bereich.POSTFACH) },
                onMailOeffnen = zurMail,
                onNeueMail = { nav.navigate(MailZiel()) { launchSingleTop = true } },
                onEinstellungen = zuEinstellungen,
            )
        }
        composable<FirmaZiel> { eintrag ->
            val ziel = eintrag.toRoute<FirmaZiel>()
            val viewModel: FirmaViewModel = viewModel(factory = FirmaViewModel.factory(container, ziel.uuid))
            FirmaScreen(
                viewModel = viewModel,
                onZurueck = { nav.popBackStack<FirmaZiel>(inclusive = true) },
                onFallOeffnen = zumFall,
                onNeuerFall = { nav.navigate(BearbeitenZiel(firma = ziel.uuid)) { launchSingleTop = true } },
                onMailSchreiben = { an -> nav.navigate(MailZiel(firma = ziel.uuid, an = an)) { launchSingleTop = true } },
                onMailOeffnen = zurMail,
                onFirmaOeffnen = { uuid -> nav.navigate(FirmaZiel(uuid)) { popUpTo<FirmaZiel> { inclusive = true }; launchSingleTop = true } },
            )
        }
        composable<MailAnsichtZiel> { eintrag ->
            val ziel = eintrag.toRoute<MailAnsichtZiel>()
            val viewModel: MailAnsichtViewModel = viewModel(factory = MailAnsichtViewModel.factory(container, ziel.mailId))
            MailAnsichtScreen(
                viewModel = viewModel,
                onZurueck = { nav.popBackStack<MailAnsichtZiel>(inclusive = true) },
                onAntworten = { m -> nav.navigate(MailZiel(antwortAufMail = m.id)) { launchSingleTop = true } },
                onFallOeffnen = zumFall,
                onFirmaOeffnen = zurFirma,
            )
        }
        composable<DetailZiel> { eintrag ->
            val ziel = eintrag.toRoute<DetailZiel>()
            val viewModel: DetailViewModel = viewModel(factory = DetailViewModel.factory(container, ziel.fallId))
            DetailScreen(
                viewModel = viewModel,
                onZurueck = { nav.popBackStack<DetailZiel>(inclusive = true) },
                onBearbeiten = { nav.navigate(BearbeitenZiel(ziel.fallId)) { launchSingleTop = true } },
                onMailSchreiben = { vorlage, empfaenger, antwortAuf ->
                    nav.navigate(MailZiel(ziel.fallId, vorlage, empfaenger, antwortAuf)) { launchSingleTop = true }
                },
                onFirmaOeffnen = zurFirma,
            )
        }
        composable<MailZiel> { eintrag ->
            val ziel = eintrag.toRoute<MailZiel>()
            val viewModel: MailSchreibenViewModel = viewModel(
                factory = MailSchreibenViewModel.factory(
                    container, ziel.fallId, ziel.vorlage, ziel.empfaenger, ziel.antwortAuf, ziel.firma, ziel.an, ziel.antwortAufMail,
                ),
            )
            MailSchreibenScreen(
                viewModel = viewModel,
                onSchliessen = { nav.popBackStack<MailZiel>(inclusive = true) },
                onGesendet = { nav.popBackStack<MailZiel>(inclusive = true) },
            )
        }
        composable<BearbeitenZiel> { eintrag ->
            val ziel = eintrag.toRoute<BearbeitenZiel>()
            val viewModel: BearbeitenViewModel = viewModel(factory = BearbeitenViewModel.factory(container, ziel.fallId, ziel.firma))
            BearbeitenScreen(
                viewModel = viewModel,
                onSchliessen = { nav.popBackStack<BearbeitenZiel>(inclusive = true) },
                onGespeichert = { fallId ->
                    if (ziel.fallId == 0L) {
                        // Neuer Fall: Formular durch die Detailansicht ersetzen.
                        nav.navigate(DetailZiel(fallId)) { popUpTo<BearbeitenZiel> { inclusive = true } }
                    } else {
                        nav.popBackStack<BearbeitenZiel>(inclusive = true)
                    }
                },
            )
        }
        composable<EinstellungenZiel> {
            val context = LocalContext.current
            val viewModel: EinstellungenViewModel = viewModel(factory = EinstellungenViewModel.factory(container, context))
            EinstellungenScreen(
                viewModel = viewModel,
                onZurueck = { nav.popBackStack<EinstellungenZiel>(inclusive = true) },
                onAuftraggeber = { nav.zuBereich(Bereich.FIRMEN) },
            )
        }
    }
}
