package de.aysetransport.schaden.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import de.aysetransport.schaden.MainActivity
import de.aysetransport.schaden.SchadensfallApp
import de.aysetransport.schaden.data.model.Ansprechpartner
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufTyp
import de.aysetransport.schaden.domain.Geld
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** Bedient die echte App von der Liste bis zum Verlauf – so, wie es ein Schadensbearbeiter täte. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class AppAblaufTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** Die App startet bei den Firmen – diese Tests arbeiten in der Fallliste. */
    @Before
    fun zuDenFaellenWechseln() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("nav-faelle").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-faelle").performClick()
    }

    private val app: SchadensfallApp get() = ApplicationProvider.getApplicationContext()
    private val repository get() = app.container.repository
    private val heute: LocalDate = LocalDate.now()
    private val jahr = heute.year

    private fun warteAufText(text: String, substring: Boolean = false) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun warteAufTag(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Wartet auf einen Zustand in Datenbank oder Einstellungen und lässt dabei den Hauptthread weiterarbeiten. */
    private fun warteBis(bedingung: suspend () -> Boolean) {
        rule.waitUntil(10_000) {
            rule.waitForIdle()
            runBlocking { bedingung() }
        }
    }

    private fun legeAn(fall: Schadensfall): Long = runBlocking { repository.anlegen(fall) }

    @Test
    fun `neuen Fall anlegen und in der Liste wiederfinden`() {
        warteAufText("Noch keine Schadensfälle")
        rule.onNodeWithText("Ersten Fall anlegen").performClick()

        warteAufTag("feld-name")
        rule.onNodeWithText("Neuer Schadensfall").assertIsDisplayed()
        rule.onNodeWithTag("feld-name").performTextInput("Anna Becker")
        rule.onNodeWithTag("feld-telefon").performTextInput("0171 1234567")
        rule.onNodeWithTag("feld-beschreibung").performScrollTo().performTextInput("Sofa beim Eintragen beschädigt")
        rule.onNodeWithTag("feld-forderung").performScrollTo().performTextInput("450")
        rule.onNodeWithTag("feld-tour").performScrollTo().performTextInput("Tour 3")
        rule.onNodeWithTag("feld-fahrer").performScrollTo().performTextInput("M. Yilmaz")
        rule.onNodeWithTag("feld-beifahrer1").performScrollTo().performTextInput("Ali Kaya")
        rule.onNodeWithTag("feld-beifahrer2").performScrollTo().performTextInput("Ben Ott")
        rule.onNodeWithText("Speichern").performClick()

        // Nach dem Speichern landet man direkt im neuen Fall.
        warteAufText("Fall $jahr-001")
        rule.onAllNodesWithText("Anna Becker").onFirst().assertIsDisplayed()
        rule.onNodeWithText(Geld.format(45_000)).assertIsDisplayed()
        rule.onNodeWithText("in 7 Tagen fällig", substring = true).assertIsDisplayed()

        val gespeichert = runBlocking { repository.ladeAlleFaelle() }.single()
        assertEquals("Anna Becker", gespeichert.kundeName)
        assertEquals("0171 1234567", gespeichert.kundeTelefon)
        assertEquals("Tour 3", gespeichert.tour)
        assertEquals(listOf("M. Yilmaz", "Ali Kaya", "Ben Ott"), listOf(gespeichert.fahrer, gespeichert.beifahrer1, gespeichert.beifahrer2))
        assertEquals(45_000L, gespeichert.forderungCent)
        assertEquals(FallStatus.NEU, gespeichert.status)
        assertEquals(heute, gespeichert.meldedatum)
        assertEquals(heute.plusDays(7), gespeichert.wiedervorlageAm)

        rule.onNodeWithContentDescription("Zurück").performClick()
        warteAufText("Offen (1)")
        rule.onNodeWithText("Anna Becker").assertIsDisplayed()
        rule.onNodeWithText("Sofa beim Eintragen beschädigt").assertIsDisplayed()
    }

    @Test
    fun `leeres Formular und ungueltiger Betrag werden nicht gespeichert`() {
        warteAufText("Noch keine Schadensfälle")
        rule.onNodeWithContentDescription("Neuer Fall").performClick()
        warteAufTag("feld-name")

        rule.onNodeWithText("Speichern").performClick()
        rule.onNodeWithText("Bitte mindestens Name, Firma oder Schadensbeschreibung angeben.").assertIsDisplayed()

        rule.onNodeWithTag("feld-name").performTextInput("Anna Becker")
        rule.onNodeWithTag("feld-forderung").performScrollTo().performTextInput("12,345")
        rule.onNodeWithText("Speichern").performClick()
        rule.onNodeWithText("Kein gültiger Betrag, z. B. 1.250,00").assertIsDisplayed()

        assertTrue(runBlocking { repository.ladeAlleFaelle() }.isEmpty())
    }

    @Test
    fun `ueberfaelligen Fall nachhaken und naechsten Termin planen`() {
        val id = legeAn(
            Schadensfall(
                kundeName = "Anna Becker",
                beschreibung = "Sofa: Naht aufgerissen",
                wiedervorlageAm = heute.minusDays(3),
                status = FallStatus.WARTET_AUF_RUECKMELDUNG,
            ),
        )

        warteAufText("Fällig (1)")
        rule.onNodeWithText("seit 3 Tagen überfällig").assertIsDisplayed()
        rule.onNodeWithText("Anna Becker").performClick()

        warteAufText("Nachgehakt")
        rule.onNodeWithText("seit 3 Tagen überfällig", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Nachgehakt").performClick()

        warteAufTag("ergebnis")
        rule.onNodeWithTag("ergebnis").performTextInput("Kundin schickt die Fotos bis Freitag")
        rule.onNodeWithText("2 Wochen").performScrollTo().performClick()
        // „Geschädigter“ steht auch als Überschrift im Fall dahinter – gemeint ist die Auswahl im Blatt.
        rule.onNode(hasText(Ansprechpartner.ANSPRUCHSTELLER.label) and hasClickAction()).performScrollTo().performClick()
        rule.onNodeWithTag("naechster-schritt").performScrollTo().performTextInput("Fotos prüfen")
        rule.onNodeWithText("Speichern").performScrollTo().performClick()

        warteBis { repository.ladeFall(id)?.wiedervorlageAm == heute.plusDays(14) }
        val fall = runBlocking { repository.ladeFall(id)!! }
        assertEquals(Ansprechpartner.ANSPRUCHSTELLER, fall.wartetAuf)
        assertEquals("Fotos prüfen", fall.wiedervorlageNotiz)
        val eintrag = runBlocking { repository.beobachteVerlauf(id).first() }.first { it.typ == VerlaufTyp.ANRUF }
        assertTrue(eintrag.text.startsWith("Kundin schickt die Fotos bis Freitag\nWiedervorlage am "))

        // Die Karte zeigt den neuen Termin, der Verlauf den Anruf.
        warteAufText("in 14 Tagen fällig", substring = true)
        rule.onNodeWithText("Wartet auf Geschädigter · Fotos prüfen").assertIsDisplayed()
        rule.onNodeWithTag("falldetails").performScrollToNode(hasText("Kundin schickt die Fotos bis Freitag", substring = true))
        rule.onNodeWithText("Kundin schickt die Fotos bis Freitag", substring = true).assertIsDisplayed()

        rule.onNodeWithContentDescription("Zurück").performClick()
        warteAufText("Fällig (0)")
    }

    @Test
    fun `Fall abschliessen nimmt ihn aus den offenen Faellen`() {
        val id = legeAn(Schadensfall(kundeName = "Carl Demir", wiedervorlageAm = heute))

        warteAufText("Carl Demir")
        rule.onNodeWithText("Carl Demir").performClick()
        warteAufText("Status ändern")
        rule.onNodeWithText("Status ändern").performClick()
        warteAufText("Übernehmen")
        rule.onNodeWithText(FallStatus.ERLEDIGT.label).performScrollTo().performClick()
        rule.onNodeWithText("Übernehmen").performClick()

        warteAufText("Der Fall ist abgeschlossen", substring = true)
        val fall = runBlocking { repository.ladeFall(id)!! }
        assertEquals(FallStatus.ERLEDIGT, fall.status)
        assertNull(fall.wiedervorlageAm)

        rule.onNodeWithContentDescription("Zurück").performClick()
        warteAufText("Keine offenen Fälle")
        rule.onNodeWithText("Abgeschlossen").performClick()
        warteAufText("Carl Demir")
    }

    @Test
    fun `Notiz erscheint im Verlauf`() {
        val id = legeAn(Schadensfall(kundeName = "Dora Engel"))

        warteAufText("Dora Engel")
        rule.onNodeWithText("Dora Engel").performClick()
        warteAufText("Fall $jahr-001")
        rule.onNodeWithTag("falldetails").performScrollToNode(hasText("Verlauf"))
        rule.onNodeWithTag("notizfeld").performTextInput("Fahrer bestätigt den Schaden am Lieferschein")
        rule.onNodeWithContentDescription("Notiz speichern").performClick()

        warteBis { repository.beobachteVerlauf(id).first().any { it.typ == VerlaufTyp.NOTIZ } }
        rule.onNodeWithTag("falldetails").performScrollToNode(hasText("Fahrer bestätigt den Schaden am Lieferschein"))
        rule.onNodeWithText("Fahrer bestätigt den Schaden am Lieferschein").assertIsDisplayed()
    }

    @Test
    fun `Suche und Reiter grenzen die Liste ein`() {
        legeAn(Schadensfall(kundeName = "Anna Becker", tour = "Tour 3", wiedervorlageAm = heute.minusDays(1)))
        legeAn(Schadensfall(kundeName = "Carl Demir", tour = "Tour 5", wiedervorlageAm = heute.plusDays(4)))
        legeAn(Schadensfall(kundeFirma = "Möbelhaus Nordpark", beschreibung = "Schrankwand beschädigt"))

        warteAufText("Offen (3)")
        rule.onNodeWithTag("suchfeld").performTextInput("tour 5")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Anna Becker").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Carl Demir").assertIsDisplayed()

        rule.onNodeWithContentDescription("Suche leeren").performClick()
        warteAufText("Anna Becker")
        rule.onNodeWithText("Fällig (1)").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Carl Demir").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Anna Becker").assertIsDisplayed()
        rule.onNodeWithText("seit gestern überfällig").assertIsDisplayed()
    }

    @Test
    fun `Fall loeschen fuehrt zurueck zur leeren Liste`() {
        legeAn(Schadensfall(kundeName = "Emil Frank"))

        warteAufText("Emil Frank")
        rule.onNodeWithText("Emil Frank").performClick()
        warteAufText("Fall $jahr-001")
        rule.onNodeWithContentDescription("Weitere Aktionen").performClick()
        rule.onNodeWithText("Fall löschen").performClick()
        rule.onNodeWithText("Löschen").performClick()

        warteAufText("Noch keine Schadensfälle")
        assertTrue(runBlocking { repository.ladeAlleFaelle() }.isEmpty())
    }

    @Test
    fun `Einstellungen werden gespeichert`() {
        warteAufText("Noch keine Schadensfälle")
        rule.onNodeWithContentDescription("Einstellungen").performClick()
        warteAufText("Absender")

        rule.onNodeWithText("Firma").performTextInput("Muster Transport GmbH")
        rule.onNodeWithText("2 Wochen").performScrollTo().performClick()

        warteBis {
            val e = app.container.einstellungen.aktuell()
            e.firma == "Muster Transport GmbH" && e.standardWiedervorlageTage == 14
        }
    }
}
