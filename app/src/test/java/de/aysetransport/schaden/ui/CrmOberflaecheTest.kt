package de.aysetransport.schaden.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.aysetransport.schaden.MainActivity
import de.aysetransport.schaden.SchadensfallApp
import de.aysetransport.schaden.data.model.Aktivitaet
import de.aysetransport.schaden.data.model.AktivitaetArt
import de.aysetransport.schaden.data.model.Adresse
import de.aysetransport.schaden.data.model.AdressArt
import de.aysetransport.schaden.data.model.Aufgabe
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.FirmenStatus
import de.aysetransport.schaden.data.model.Kontaktperson
import de.aysetransport.schaden.data.model.MailNachricht
import de.aysetransport.schaden.data.model.Preis
import de.aysetransport.schaden.data.model.Schadensfall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate

/** Bedient das CRM wie ein Disponent: Firma anlegen, Ansprechpartner, Fall aus der Firma, Wiedervorlagen, Postfach. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class CrmOberflaecheTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: SchadensfallApp get() = ApplicationProvider.getApplicationContext()
    private val heute = LocalDate.now()

    private fun warteAufText(text: String, substring: Boolean = true) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun warteAufTag(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun warteBis(bedingung: suspend () -> Boolean) {
        rule.waitUntil(10_000) { rule.waitForIdle(); runBlocking { bedingung() } }
    }

    private fun foto(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun `Firma anlegen, Ansprechpartner eintragen und daraus einen Fall anlegen`() {
        warteAufText("Noch keine Auftraggeber")
        rule.onNodeWithContentDescription("Neue Firma").performClick()
        warteAufTag("feld-name")
        rule.onNodeWithTag("feld-name").performTextInput("Seats and Sofas Krefeld")
        rule.onNodeWithTag("feld-telefon").performTextInput("02151 123")
        rule.onNodeWithText("Speichern").performClick()

        warteAufTag("stammdaten")
        rule.onNodeWithTag("firma").performScrollToNode(hasTestTag("neu-kontakte"))
        rule.onNodeWithTag("neu-kontakte").performClick()
        warteAufText("Neuer Ansprechpartner")
        rule.onNodeWithTag("feld-name").performTextInput("Jana Krüger")
        rule.onNodeWithTag("feld-thema").performTextInput("Schäden")
        rule.onNodeWithTag("feld-email").performTextInput("jana@seats.example")
        rule.onNodeWithText("Speichern").performClick()
        warteAufText("Schäden · jana@seats.example")

        rule.onNodeWithTag("firma").performScrollToNode(hasTestTag("neu-faelle"))
        rule.onNodeWithTag("neu-faelle").performClick()
        warteAufText("Neuer Schadensfall")
        warteAufTag("feld-auftraggeber")
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("feld-auftraggeber") and hasText("Seats and Sofas Krefeld", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("feld-auftraggeber").assertTextContains("Seats and Sofas Krefeld")
        rule.onNodeWithTag("feld-auftraggeber-email").assertTextContains("jana@seats.example")
        rule.onNodeWithTag("feld-name").performTextInput("Anna Becker")
        rule.onNodeWithText("Speichern").performClick()

        warteBis { app.container.repository.ladeAlleFaelle().isNotEmpty() }
        val firma = runBlocking { app.container.crm.ladeFirmen() }.single()
        val fall = runBlocking { app.container.repository.ladeAlleFaelle() }.single()
        assertEquals(firma.uuid, fall.auftraggeberUuid)
        assertEquals("Jana Krüger", fall.auftraggeberKontakt)
        assertEquals("keine zweite Kontaktperson", 1, runBlocking { app.container.crm.ladeKontakte(firma.uuid) }.size)
    }

    @Test
    fun `Startseite, Firma, Wiedervorlagen und Postfach mit Beispieldaten`() {
        runBlocking {
            val crm = app.container.crm
            val seats = crm.firmaSpeichern(AuftraggeberEintrag(name = "Seats and Sofas Krefeld", rechtsform = "GmbH", telefon = "02151 555 0", email = "dispo@seats.example", website = "seats.example", notizen = "Rahmenvertrag bis Ende 2027, Abrechnung monatlich."))
            crm.speichern(Kontaktperson(auftraggeberUuid = seats, name = "Jana Krüger", thema = "Disposition", telefon = "02151 555 12", email = "jana@seats.example"))
            crm.speichern(Kontaktperson(auftraggeberUuid = seats, name = "Tim Berg", thema = "Schäden", email = "schaden@seats.example"))
            crm.speichern(Adresse(auftraggeberUuid = seats, art = AdressArt.LAGER, strasse = "Industriestr. 5", plz = "47803", ort = "Krefeld", notiz = "Rampe 3, ab 7 Uhr"))
            crm.speichern(Preis(auftraggeberUuid = seats, bezeichnung = "Tourpauschale", betragCent = 18950, einheit = "pro Tour", gueltigAb = heute.withDayOfYear(1)))
            crm.speichern(Preis(auftraggeberUuid = seats, bezeichnung = "Etage", betragCent = 1250, einheit = "pro Etage ab 3. OG"))
            crm.speichern(Aufgabe(auftraggeberUuid = seats, text = "Preise 2027 verhandeln", kontakt = "Jana Krüger", faelligAm = heute.minusDays(1)))
            crm.speichern(Aktivitaet(auftraggeberUuid = seats, datum = heute.minusDays(3), art = AktivitaetArt.ANRUF, kontakt = "Jana Krüger", text = "Mehr Touren ab Dezember angekündigt."))
            val media = crm.firmaSpeichern(AuftraggeberEintrag(name = "Media Markt Remscheid", status = FirmenStatus.AKTIV, telefon = "02191 600"))
            crm.speichern(Aufgabe(auftraggeberUuid = media, text = "Monatsabrechnung prüfen", faelligAm = heute.plusDays(4)))
            crm.firmaSpeichern(AuftraggeberEintrag(name = "Neue Möbel AG", status = FirmenStatus.INTERESSENT))
            app.container.repository.anlegen(Schadensfall(kundeName = "Anna Becker", auftraggeber = "Seats and Sofas Krefeld", beschreibung = "Sofa beschädigt", wiedervorlageAm = heute))
            app.container.datenbank.crm().speichereMail(
                MailNachricht(eingehend = true, messageId = "beispiel@x", von = "kunde@gmail.com", an = "info@ayse.example", betreff = "Anfrage Umzug", text = "Guten Tag, …", zeitpunkt = Instant.now()),
            )
        }
        warteAufText("Neue Möbel AG")
        rule.onNodeWithText("Media Markt Remscheid").assertIsDisplayed()
        foto("crm_firmen")

        rule.onNodeWithTag("firmen-suche").performTextInput("Krüger")
        warteAufText("Ansprechpartner: Jana Krüger")
        rule.onNodeWithText("Ansprechpartner: Jana Krüger").performClick()
        warteAufTag("stammdaten")
        foto("crm_firma")

        rule.onNodeWithContentDescription("Zurück").performClick()
        rule.onNodeWithTag("nav-wiedervorlagen").performClick()
        warteAufText("Überfällig (1)")
        rule.onNodeWithText("Heute (1)").assertIsDisplayed()
        rule.onNodeWithText("Monatsabrechnung prüfen").assertIsDisplayed()
        foto("crm_wiedervorlagen")

        rule.onNodeWithTag("nav-postfach").performClick()
        warteAufText("Anfrage Umzug")
        rule.onNodeWithText("Nicht zugeordnet (1)").assertIsDisplayed()
        rule.onNodeWithText("Anfrage Umzug").performClick()
        warteAufTag("mail-zuordnen")
        rule.onNodeWithTag("mail-zuordnen").performClick()
        warteAufText("E-Mail zuordnen")
        rule.onNodeWithTag("zuordnen-kunde").performTextInput("Familie Weber")
        rule.onNodeWithTag("zuordnen-ok").performClick()
        warteAufText("Kunde: Familie Weber")
        warteBis { app.container.datenbank.crm().ladeAlleMails().single().kundeRef == "Familie Weber" }
    }
}
