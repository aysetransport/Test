package de.aysetransport.schaden.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.aysetransport.schaden.MainActivity
import de.aysetransport.schaden.SchadensfallApp
import de.aysetransport.schaden.data.model.Ansprechpartner
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensart
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufTyp
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * Erzeugt Bildschirmfotos der wichtigsten Ansichten mit Beispieldaten
 * (app/build/outputs/roborazzi). Dient der Sichtkontrolle nach Änderungen an der Oberfläche.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class BildschirmfotoTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: SchadensfallApp get() = ApplicationProvider.getApplicationContext()
    private val repository get() = app.container.repository
    private val heute: LocalDate = LocalDate.now()

    private fun foto(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun warteAufText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Sechs erfundene Fälle in unterschiedlichen Bearbeitungsständen. Gibt die Id des ersten zurück. */
    private fun legeBeispieleAn(): Long = runBlocking {
        val sofa = repository.anlegen(
            Schadensfall(
                kundeName = "Anna Becker",
                kundeTelefon = "0171 5550142",
                kundeEmail = "a.becker@example.org",
                kundeAnschrift = "Lindenweg 12, 42853 Remscheid",
                auftraggeber = "Möbelhaus Nordpark",
                auftragsReferenz = "LS-48213",
                tour = "Tour 3",
                fahrzeug = "RS-MT 204",
                fahrer = "M. Yilmaz",
                schadensort = "Lindenweg 12, Remscheid",
                schadensdatum = heute.minusDays(9),
                meldedatum = heute.minusDays(8),
                schadensart = Schadensart.WARE,
                beschreibung = "Ecksofa: Naht an der Armlehne aufgerissen, Kratzer am Holzfuß. Laut Kundin beim Eintragen passiert.",
                forderungCent = 45_000,
                status = FallStatus.WARTET_AUF_RUECKMELDUNG,
                wartetAuf = Ansprechpartner.ANSPRUCHSTELLER,
                wiedervorlageAm = heute.minusDays(2),
                wiedervorlageNotiz = "Fotos nachfordern",
            ),
        )
        repository.verlaufHinzufuegen(sofa, VerlaufTyp.EMAIL, "E-Mail vorbereitet: Eingangsbestätigung")
        repository.verlaufHinzufuegen(sofa, VerlaufTyp.ANRUF, "Fahrer befragt: Sofa war beim Abladen unbeschädigt, Treppenhaus sehr eng.")
        repository.verlaufHinzufuegen(sofa, VerlaufTyp.NOTIZ, "Kundin will Fotos per E-Mail schicken.")
        val lieferschein = File(app.cacheDir, "Lieferschein LS-48213.pdf").apply { writeBytes(ByteArray(1500)) }
        repository.anlageHinzufuegen(sofa, Uri.fromFile(lieferschein))
        repository.anlageHinzufuegen(sofa, Uri.fromFile(beispielfoto("Sofa Armlehne.jpg")))

        repository.anlegen(
            Schadensfall(
                kundeFirma = "Möbelhaus Nordpark GmbH",
                tour = "Tour 1",
                schadensdatum = heute.minusDays(15),
                beschreibung = "Schrankwand bei Anlieferung beschädigt, Rückwand gebrochen.",
                forderungCent = 128_000,
                status = FallStatus.BEI_VERSICHERUNG,
                wartetAuf = Ansprechpartner.VERSICHERUNG,
                wiedervorlageAm = heute,
                wiedervorlageNotiz = "Sachstand erfragen",
                versicherungsNummer = "VH-2026-88104",
            ),
        )
        repository.anlegen(
            Schadensfall(
                kundeName = "Carl Demir",
                tour = "Tour 5",
                schadensdatum = heute.minusDays(4),
                schadensart = Schadensart.GEBAEUDE,
                beschreibung = "Türrahmen im Treppenhaus beim Eintragen der Couch beschädigt.",
                forderungCent = 32_000,
                status = FallStatus.IN_PRUEFUNG,
                wartetAuf = Ansprechpartner.FAHRER,
                wiedervorlageAm = heute.plusDays(3),
                wiedervorlageNotiz = "Stellungnahme Fahrer",
            ),
        )
        repository.anlegen(
            Schadensfall(
                kundeName = "Dora Engel",
                tour = "Tour 3",
                schadensdatum = heute.minusDays(1),
                schadensart = Schadensart.VERLUST,
                beschreibung = "Lieferung unvollständig: zwei Esszimmerstühle fehlen.",
                status = FallStatus.NEU,
                wiedervorlageAm = heute.plusDays(6),
            ),
        )
        repository.anlegen(
            Schadensfall(
                kundeName = "Emil Frank",
                tour = "Tour 2",
                schadensdatum = heute.minusDays(30),
                beschreibung = "Glasplatte des Couchtischs gesprungen.",
                forderungCent = 18_900,
                vergleichCent = 12_000,
                status = FallStatus.VERGLEICH_ANGEBOTEN,
                wartetAuf = Ansprechpartner.ANSPRUCHSTELLER,
                wiedervorlageAm = heute.plusDays(12),
                wiedervorlageNotiz = "Gegenzeichnung abwarten",
            ),
        )
        val erledigt = repository.anlegen(
            Schadensfall(kundeName = "Greta Hahn", tour = "Tour 4", beschreibung = "Matratze verschmutzt geliefert.", forderungCent = 9_500),
        )
        repository.statusAendern(erledigt, FallStatus.ERLEDIGT, "Kulanzgutschrift über den Auftraggeber")
        sofa
    }

    private fun beispielfoto(name: String): File {
        val bild = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bild).apply {
            drawColor(Color.rgb(196, 170, 140))
            drawRect(40f, 150f, 360f, 330f, Paint().apply { color = Color.rgb(120, 96, 78) })
            drawLine(120f, 170f, 250f, 300f, Paint().apply { color = Color.rgb(230, 220, 210); strokeWidth = 6f })
        }
        return File(app.cacheDir, name).apply { outputStream().use { bild.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
    }

    @Test
    fun `Ansichten mit Beispieldaten`() {
        val sofa = legeBeispieleAn()
        warteAufText("Anna Becker")
        foto("01_uebersicht")

        rule.onNodeWithText("Fällig (2)").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Carl Demir").fetchSemanticsNodes().isEmpty() }
        foto("02_faellig")

        rule.onNodeWithText("Anna Becker").performClick()
        warteAufText("Fall ${heute.year}-001")
        foto("03_fall")

        rule.onNodeWithText("Nachgehakt").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("ergebnis").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ergebnis").performTextInput("Kundin erreicht, Fotos kommen bis Freitag.")
        foto("04_nachgehakt")
        rule.onNodeWithText("Abbrechen").performClick()

        rule.onNodeWithText("Status ändern").performClick()
        warteAufText("Übernehmen")
        foto("05_status")
        rule.onNodeWithText("Abbrechen").performClick()

        rule.onNode(hasText("E-Mail") and hasClickAction()).performClick()
        warteAufText("E-Mail vorbereiten")
        foto("06_vorlagen")
        rule.onNodeWithText("Abbrechen").performClick()

        rule.onNodeWithTag("falldetails").performScrollToNode(hasText("Anlagen", substring = true))
        foto("07_fall_anlagen")
        rule.onNodeWithTag("falldetails").performScrollToNode(hasText("Kundin will Fotos per E-Mail schicken."))
        foto("08_fall_verlauf")

        rule.onNodeWithContentDescription("Bearbeiten").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("feld-name").fetchSemanticsNodes().isNotEmpty() }
        foto("09_bearbeiten")
        rule.onNodeWithContentDescription("Schließen").performClick()

        warteAufText("Fall ${heute.year}-001")
        rule.onNodeWithContentDescription("Zurück").performClick()
        warteAufText("Schadensfälle")
        rule.onNodeWithContentDescription("Einstellungen").performClick()
        warteAufText("Absender")
        foto("10_einstellungen")
        rule.onNodeWithText("Verbinden").performScrollTo()
        foto("13_einstellungen_cloud")
        check(sofa > 0)
    }

    @Test
    @Config(qualifiers = "+night")
    fun `dunkles Design`() {
        legeBeispieleAn()
        warteAufText("Anna Becker")
        foto("11_uebersicht_dunkel")
        rule.onNodeWithText("Anna Becker").performClick()
        warteAufText("Fall ${heute.year}-001")
        foto("12_fall_dunkel")
    }

    @Test
    fun `leere App`() {
        warteAufText("Noch keine Schadensfälle")
        foto("00_leer")
    }
}
