package de.aysetransport.schaden.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import de.aysetransport.schaden.MainActivity
import de.aysetransport.schaden.SchadensfallApp
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.erinnerung.Benachrichtigungen
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** Das Antippen der Erinnerung muss am richtigen Ort landen. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class StartAusBenachrichtigungTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val app: SchadensfallApp get() = ApplicationProvider.getApplicationContext()
    private val heute = LocalDate.now()

    private fun warteAufText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `Erinnerung zu einem einzelnen Fall oeffnet diesen Fall`() {
        runBlocking { app.container.repository.anlegen(Schadensfall(kundeName = "Erster")) }
        val id = runBlocking { app.container.repository.anlegen(Schadensfall(kundeName = "Anna Becker", wiedervorlageAm = heute)) }

        val intent = Intent(app, MainActivity::class.java).putExtra(Benachrichtigungen.EXTRA_FALL_ID, id)
        ActivityScenario.launch<MainActivity>(intent).use {
            warteAufText("Fall ${heute.year}-002")
            rule.onNodeWithText("heute fällig", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun `Sammelerinnerung oeffnet die Wiedervorlagen`() {
        runBlocking {
            app.container.repository.anlegen(Schadensfall(kundeName = "Fällig Eins", wiedervorlageAm = heute))
            app.container.repository.anlegen(Schadensfall(kundeName = "Fällig Zwei", wiedervorlageAm = heute.minusDays(2)))
            app.container.repository.anlegen(Schadensfall(kundeName = "Noch Zeit", wiedervorlageAm = heute.plusDays(9)))
        }

        val intent = Intent(app, MainActivity::class.java).putExtra(Benachrichtigungen.EXTRA_ZEIGE_FAELLIGE, true)
        ActivityScenario.launch<MainActivity>(intent).use {
            warteAufText("Fällig Eins")
            rule.onNodeWithText("Überfällig (1)").assertIsDisplayed()
            rule.onNodeWithText("Heute (1)").assertIsDisplayed()
            rule.onNodeWithText("Fällig Zwei", substring = true).assertIsDisplayed()
            rule.onNodeWithText("Demnächst (1)").assertExists()
        }
    }
}
