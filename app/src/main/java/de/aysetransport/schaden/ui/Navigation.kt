package de.aysetransport.schaden.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import de.aysetransport.schaden.AppContainer
import de.aysetransport.schaden.domain.FallFilter
import de.aysetransport.schaden.ui.auftraggeber.AuftraggeberScreen
import de.aysetransport.schaden.ui.auftraggeber.AuftraggeberViewModel
import de.aysetransport.schaden.ui.mail.MailSchreibenScreen
import de.aysetransport.schaden.ui.mail.MailSchreibenViewModel
import de.aysetransport.schaden.ui.bearbeiten.BearbeitenScreen
import de.aysetransport.schaden.ui.bearbeiten.BearbeitenViewModel
import de.aysetransport.schaden.ui.detail.DetailScreen
import de.aysetransport.schaden.ui.detail.DetailViewModel
import de.aysetransport.schaden.ui.einstellungen.EinstellungenScreen
import de.aysetransport.schaden.ui.einstellungen.EinstellungenViewModel
import de.aysetransport.schaden.ui.uebersicht.UebersichtScreen
import de.aysetransport.schaden.ui.uebersicht.UebersichtViewModel
import kotlinx.serialization.Serializable

@Serializable
data object UebersichtZiel

@Serializable
data class DetailZiel(val fallId: Long)

/** `fallId = 0` legt einen neuen Fall an. */
@Serializable
data class BearbeitenZiel(val fallId: Long = 0)

@Serializable
data object EinstellungenZiel

@Serializable
data object AuftraggeberZiel

@Serializable
data class MailZiel(val fallId: Long, val vorlage: String = "", val empfaenger: String = "", val antwortAuf: String = "")

/** Wohin die App springen soll, wenn sie über eine Benachrichtigung geöffnet wurde. */
sealed interface Startauftrag {
    data class Fall(val fallId: Long) : Startauftrag
    data object FaelligeListe : Startauftrag
}

@Composable
fun SchadensfallNavigation(
    container: AppContainer,
    startauftrag: Startauftrag?,
    onStartauftragErledigt: () -> Unit,
) {
    val nav = rememberNavController()
    var gewuenschterFilter by remember { mutableStateOf<FallFilter?>(null) }

    LaunchedEffect(startauftrag) {
        when (startauftrag) {
            null -> return@LaunchedEffect
            is Startauftrag.Fall -> nav.navigate(DetailZiel(startauftrag.fallId)) {
                popUpTo<UebersichtZiel>()
                launchSingleTop = true
            }
            Startauftrag.FaelligeListe -> {
                nav.popBackStack<UebersichtZiel>(inclusive = false)
                gewuenschterFilter = FallFilter.FAELLIG
            }
        }
        onStartauftragErledigt()
    }

    // Jeder Bildschirm entfernt beim Schließen gezielt sich selbst. Ein doppelter Aufruf
    // (z. B. Zurück-Taste während des Löschens) kann so nie die Übersicht mit abräumen.
    NavHost(navController = nav, startDestination = UebersichtZiel) {
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
                onFallOeffnen = { nav.navigate(DetailZiel(it)) { launchSingleTop = true } },
                onNeuerFall = { nav.navigate(BearbeitenZiel()) { launchSingleTop = true } },
                onEinstellungen = { nav.navigate(EinstellungenZiel) { launchSingleTop = true } },
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
            )
        }
        composable<MailZiel> { eintrag ->
            val ziel = eintrag.toRoute<MailZiel>()
            val viewModel: MailSchreibenViewModel = viewModel(
                factory = MailSchreibenViewModel.factory(container, ziel.fallId, ziel.vorlage, ziel.empfaenger, ziel.antwortAuf),
            )
            MailSchreibenScreen(
                viewModel = viewModel,
                onSchliessen = { nav.popBackStack<MailZiel>(inclusive = true) },
                onGesendet = { nav.popBackStack<MailZiel>(inclusive = true) },
            )
        }
        composable<BearbeitenZiel> { eintrag ->
            val ziel = eintrag.toRoute<BearbeitenZiel>()
            val viewModel: BearbeitenViewModel = viewModel(factory = BearbeitenViewModel.factory(container, ziel.fallId))
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
                onAuftraggeber = { nav.navigate(AuftraggeberZiel) { launchSingleTop = true } },
            )
        }
        composable<AuftraggeberZiel> {
            val viewModel: AuftraggeberViewModel = viewModel(factory = AuftraggeberViewModel.factory(container))
            AuftraggeberScreen(viewModel = viewModel, onZurueck = { nav.popBackStack<AuftraggeberZiel>(inclusive = true) })
        }
    }
}
