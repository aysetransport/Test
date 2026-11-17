package de.aysetransport.schaden.mail

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import de.aysetransport.schaden.MainActivity
import de.aysetransport.schaden.SchadensfallApp
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufTyp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onAllNodesWithTag
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** Mails schreiben und beantworten über die echte Oberfläche, gegen einen Test-Mailserver. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class MailOberflaecheTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** Die App startet bei den Firmen – diese Tests arbeiten in der Fallliste. */
    @Before
    fun zuDenFaellenWechseln() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("nav-faelle").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-faelle").performClick()
    }

    private val app: SchadensfallApp get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: TestPostfach
    private val jahr = LocalDate.now().year

    @Before
    fun setUp() {
        server = TestPostfach()
        runBlocking { app.container.postfach.einrichten(server.konfig, server.passwort) }
    }

    @After
    fun tearDown() = server.close()

    private fun warteAufText(text: String) {
        rule.waitUntil(15_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun warteBis(bedingung: suspend () -> Boolean) {
        rule.waitUntil(15_000) {
            rule.waitForIdle()
            runBlocking { bedingung() }
        }
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun foto(name: String) {
        rule.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun verlauf(id: Long, typ: VerlaufTyp) = runBlocking { app.container.repository.beobachteVerlauf(id).first() }.filter { it.typ == typ }

    @Test
    fun `Vorlage im Fall waehlen, in der App senden, Antwort abrufen und beantworten`() {
        val id = runBlocking {
            app.container.repository.anlegen(Schadensfall(kundeName = "Anna Becker", kundeEmail = "anna@kunde.example", schadensdatum = LocalDate.of(jahr, 11, 3)))
        }
        warteAufText("Anna Becker")
        rule.onNodeWithText("Anna Becker").performClick()
        warteAufText("Fall $jahr-001")

        rule.onNode(hasText("E-Mail") and hasClickAction()).performClick()
        warteAufText("E-Mail schreiben")
        rule.onNodeWithTag("vorlage-ERINNERUNG").performScrollTo().performClick()

        warteAufText("E-Mail · Fall $jahr-001")
        warteAufText("anna@kunde.example")
        rule.onNodeWithText("anna@kunde.example").assertExists()
        rule.onNodeWithText("Ihre Schadensmeldung vom 03.11.$jahr – Erinnerung [Fall $jahr-001]").assertExists()
        foto("14_mail_schreiben")
        rule.onNodeWithContentDescription("Senden").performClick()

        warteBis { verlauf(id, VerlaufTyp.EMAIL_AUSGANG).isNotEmpty() }
        val gesendet = server.server.getReceivedMessagesForDomain("kunde.example").first()
        assertTrue(gesendet.subject, gesendet.subject.endsWith("[Fall $jahr-001]"))
        assertTrue((gesendet.content as String).startsWith("Guten Tag Anna Becker,"))
        warteAufText("Fall $jahr-001")

        // Die Kundin antwortet; abrufen über das Menü.
        server.empfange("Anna Becker <anna@kunde.example>", "Re: ${gesendet.subject}", "Die Fotos kommen morgen.")
        rule.onNodeWithContentDescription("Weitere Aktionen").performClick()
        rule.onNodeWithText("Postfach abrufen").performClick()
        warteBis { verlauf(id, VerlaufTyp.EMAIL_EINGANG).isNotEmpty() }
        warteAufText("1 neue E-Mail zu diesem Fall")

        rule.onNodeWithTag("falldetails").performScrollToNode(hasText("Antworten"))
        foto("15_mail_verlauf")
        rule.onNodeWithText("Antworten").performClick()
        warteAufText("E-Mail · Fall $jahr-001")
        warteAufText("anna@kunde.example")
        rule.onNodeWithText("anna@kunde.example").assertExists()
        rule.onNodeWithTag("mail-text").performTextClearance()
        rule.onNodeWithTag("mail-text").performTextInput("Danke, wir warten darauf.")
        rule.onNodeWithContentDescription("Senden").performClick()

        warteBis { verlauf(id, VerlaufTyp.EMAIL_AUSGANG).size == 2 }
        val antwort = server.server.getReceivedMessagesForDomain("kunde.example").last { it.subject.startsWith("Re:") }
        val eingang = runBlocking { app.container.datenbank.dao().beobachteMails(id).first() }.first { it.eingehend }
        assertEquals("<${eingang.messageId}>", antwort.getHeader("In-Reply-To").single())
        assertTrue(antwort.subject, antwort.subject.startsWith("Re: ") && antwort.subject.endsWith("[Fall $jahr-001]"))
    }

    @Test
    fun `Bearbeitungsschritte an den Auftraggeber melden und ihn bei der Kundin in BCC nehmen`() {
        val id = runBlocking {
            val r = app.container.repository
            val id = r.anlegen(
                Schadensfall(
                    kundeName = "Anna Becker", kundeEmail = "anna@kunde.example", auftraggeber = "Möbelhaus Nordpark",
                    auftraggeberEmail = "dispo@moebel.example", auftraggeberZeichen = "RK-2291", schadensdatum = LocalDate.of(jahr, 11, 3),
                ),
            )
            r.nachhakenErfassen(id, VerlaufTyp.ANRUF, "Kundin erreicht, Fotos kommen", null, null, "")
            id
        }
        warteAufText("Anna Becker")
        rule.onNodeWithText("Anna Becker").performClick()
        warteAufText("Fall $jahr-001")
        rule.onNodeWithTag("falldetails").performScrollToNode(hasTestTag("verlauf-an-auftraggeber"))
        rule.onNodeWithTag("verlauf-an-auftraggeber").performClick()

        warteAufText("E-Mail · Fall $jahr-001")
        warteAufText("Ihr Zeichen RK-2291")
        rule.onNodeWithText("dispo@moebel.example").assertExists()
        rule.onNodeWithTag("mail-text").assert(hasText("Telefonat: Kundin erreicht, Fotos kommen", substring = true))
        foto("16_mail_bearbeitungsschritte")
        rule.onNodeWithContentDescription("Senden").performClick()
        warteBis { verlauf(id, VerlaufTyp.EMAIL_AUSGANG).isNotEmpty() }
        val anAg = server.server.getReceivedMessagesForDomain("moebel.example").single()
        assertTrue(anAg.subject, "Ihr Zeichen RK-2291" in anAg.subject)
        assertTrue(anAg.content as String, "Bisherige Bearbeitung:" in (anAg.content as String))
        warteAufText("Fall $jahr-001")

        // An die Kundin, mit dem Auftraggeber in BCC
        rule.onNodeWithTag("falldetails").performScrollToIndex(0)
        rule.onNode(hasText("E-Mail") and hasClickAction()).performClick()
        warteAufText("E-Mail schreiben")
        rule.onNodeWithTag("vorlage-ZWISCHENBESCHEID").performScrollTo().performClick()
        warteAufText("Bezug RK-2291")
        rule.onNodeWithTag("mail-bcc").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Senden").performClick()
        warteBis { verlauf(id, VerlaufTyp.EMAIL_AUSGANG).size == 2 }
        // Die Blindkopie trägt keine Bcc-Zeile, also zählt nur die Gesamtzahl: 1× Sachstand, 2× Zwischenbescheid
        assertEquals(3, server.server.receivedMessages.size)
        val zwischenbescheid = server.server.receivedMessages.filter { "Zwischenbescheid" in it.subject }
        assertEquals("Kundin und Auftraggeber bekommen dieselbe Mail", 1, zwischenbescheid.map { it.messageID }.distinct().size)
        assertTrue(zwischenbescheid.all { it.getHeader("Bcc") == null && "RK-2291" in it.subject })
        assertTrue(verlauf(id, VerlaufTyp.EMAIL_AUSGANG).any { "Bcc: dispo@moebel.example" in it.text })
    }
}
