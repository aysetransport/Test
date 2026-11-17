package de.aysetransport.schaden.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import de.aysetransport.schaden.MainActivity
import de.aysetransport.schaden.SchadensfallApp
import de.aysetransport.schaden.data.BackupManager
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufTyp
import de.aysetransport.schaden.domain.CsvExport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import androidx.compose.ui.test.onAllNodesWithTag
import org.junit.Test
import androidx.compose.ui.test.performTextInput
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipFile

/**
 * Prüft die Übergänge zu anderen Apps: Kamera, Dateiauswahl, E-Mail, Telefon, Teilen, Export
 * und Backup. Hier würden Fehler in der Freigabe-Konfiguration erst zur Laufzeit auffallen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class AppAnbindungTest {

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
    private val jahr = LocalDate.now().year
    private val freigabe = "de.aysetransport.schaden.fileprovider"

    /**
     * `FileProvider` merkt sich die freigegebenen Ordner in einer statischen Tabelle. Im Test
     * bekommt jeder Lauf eigene Ordner – ohne Zurücksetzen würden die des Vorgängers gelten.
     */
    @Before
    fun freigabeZuruecksetzen() {
        val tabelle = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>
        synchronized(tabelle) { tabelle.clear() }
    }

    private fun warteAufText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun warteBis(bedingung: suspend () -> Boolean) {
        rule.waitUntil(10_000) {
            rule.waitForIdle()
            runBlocking { bedingung() }
        }
    }

    private fun oeffneBeispielfall(): Long {
        val id = runBlocking {
            repository.anlegen(
                Schadensfall(
                    kundeName = "Anna Becker",
                    kundeTelefon = "0171 5550142",
                    kundeEmail = "a.becker@example.org",
                    schadensdatum = LocalDate.of(jahr, 11, 3),
                    versicherungsNummer = "VH-88104",
                ),
            )
        }
        warteAufText("Anna Becker")
        rule.onNodeWithText("Anna Becker").performClick()
        warteAufText("Fall $jahr-001")
        return id
    }

    private fun naechsterStart(): Intent {
        rule.waitForIdle()
        return checkNotNull(shadowOf(app).nextStartedActivity) { "Es wurde keine andere App gestartet" }
    }

    private fun naechsteAnfrage(): Intent {
        rule.waitForIdle()
        return checkNotNull(shadowOf(rule.activity).nextStartedActivityForResult) { "Es wurde nichts angefragt" }.intent
    }

    private fun antworte(anfrage: Intent, ergebnis: Intent? = null, code: Int = Activity.RESULT_OK) {
        rule.runOnUiThread { shadowOf(rule.activity).receiveResult(anfrage, code, ergebnis) }
    }

    private fun verlaufTexte(id: Long, typ: VerlaufTyp) = runBlocking { repository.beobachteVerlauf(id).first() }.filter { it.typ == typ }.map { it.text }

    @Test
    fun `Anrufen oeffnet die Telefon-App mit der Nummer`() {
        oeffneBeispielfall()
        rule.onNodeWithText("Anrufen").performClick()

        val start = naechsterStart()
        assertEquals(Intent.ACTION_DIAL, start.action)
        assertEquals("tel:0171%205550142", start.dataString)
    }

    @Test
    fun `E-Mail-Vorlage oeffnet einen Entwurf an den Geschaedigten und wird vermerkt`() {
        val id = oeffneBeispielfall()
        rule.onNode(hasText("E-Mail") and hasClickAction()).performClick()
        warteAufText("E-Mail vorbereiten")
        rule.onNodeWithText("Erinnerung").performClick()

        val start = naechsterStart()
        assertEquals(Intent.ACTION_SENDTO, start.action)
        val adresse = start.data!!
        assertEquals("mailto", adresse.scheme)
        assertTrue(start.dataString!!.startsWith("mailto:a.becker@example.org?subject="))
        val teile = adresse.schemeSpecificPart.substringAfter('?').split('&').associate { it.substringBefore('=') to Uri.decode(it.substringAfter('=')) }
        assertEquals("Ihre Schadensmeldung vom 03.11.$jahr – Erinnerung – unser Vorgang $jahr-001", teile["subject"])
        assertTrue(teile.getValue("body").startsWith("Guten Tag Anna Becker,\n\nwir kommen zurück auf unsere Nachricht zum Vorfall vom 03.11.$jahr."))

        warteBis { verlaufTexte(id, VerlaufTyp.EMAIL).isNotEmpty() }
        assertEquals(listOf("E-Mail an Geschädigter vorbereitet: Erinnerung"), verlaufTexte(id, VerlaufTyp.EMAIL))
    }

    @Test
    fun `Sachstandsanfrage geht nicht an den Geschaedigten`() {
        oeffneBeispielfall()
        rule.onNode(hasText("E-Mail") and hasClickAction()).performClick()
        warteAufText("E-Mail vorbereiten")
        rule.onNodeWithText("Sachstandsanfrage").performScrollTo().performClick()

        val start = naechsterStart()
        assertTrue(start.dataString!!.startsWith("mailto:?subject="))
        assertTrue("VH-88104" in Uri.decode(start.dataString))
    }

    @Test
    fun `Nachricht und Zusammenfassung werden als Text geteilt`() {
        val id = oeffneBeispielfall()
        rule.onNodeWithText("Nachricht").performClick()
        warteAufText("Nachricht vorbereiten")
        rule.onNodeWithText("Eingangsbestätigung").performClick()

        val nachricht = naechsterStart()
        assertEquals(Intent.ACTION_CHOOSER, nachricht.action)
        val senden = nachricht.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, senden.action)
        assertEquals("text/plain", senden.type)
        assertTrue(senden.getStringExtra(Intent.EXTRA_TEXT)!!.startsWith("Guten Tag Anna Becker,\n\nwir bestätigen den Eingang"))
        warteBis { verlaufTexte(id, VerlaufTyp.NACHRICHT) == listOf("Nachricht an Geschädigter vorbereitet: Eingangsbestätigung") }

        rule.onNodeWithContentDescription("Weitere Aktionen").performClick()
        rule.onNodeWithText("Zusammenfassung teilen").performClick()
        val zusammenfassung = naechsterStart().getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        val text = zusammenfassung.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text.startsWith("Schadensfall $jahr-001\nStatus: Neu\nGeschädigter: Anna Becker"))
    }

    @Test
    fun `Kamerafoto wird als Anlage uebernommen und die Zwischendatei entfernt`() {
        val id = oeffneBeispielfall()
        rule.onNodeWithTag("falldetails").performScrollToNode(hasText("Foto aufnehmen"))
        rule.onNodeWithText("Foto aufnehmen").performClick()

        val anfrage = naechsteAnfrage()
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, anfrage.action)
        val ziel = anfrage.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)!!
        assertEquals("content", ziel.scheme)
        assertEquals(freigabe, ziel.authority)

        // Die Kamera-App schreibt das Bild über die freigegebene Adresse.
        val bild = ByteArray(5000) { (it % 97).toByte() }
        app.contentResolver.openOutputStream(ziel)!!.use { it.write(bild) }
        val zwischenordner = File(app.cacheDir, "kamera")
        assertEquals(1, zwischenordner.listFiles()!!.size)
        antworte(anfrage)

        warteBis { repository.beobachteAnlagen(id).first().isNotEmpty() }
        val anlage = runBlocking { repository.beobachteAnlagen(id).first() }.single()
        assertTrue(anlage.anzeigename.startsWith("Foto "))
        assertEquals("image/jpeg", anlage.mimeType)
        assertTrue(bild.contentEquals(app.container.anlagenSpeicher.datei(anlage).readBytes()))
        warteBis { zwischenordner.listFiles().orEmpty().isEmpty() }
    }

    @Test
    fun `abgebrochene Kameraaufnahme hinterlaesst nichts`() {
        val id = oeffneBeispielfall()
        rule.onNodeWithTag("falldetails").performScrollToNode(hasText("Foto aufnehmen"))
        rule.onNodeWithText("Foto aufnehmen").performClick()

        antworte(naechsteAnfrage(), code = Activity.RESULT_CANCELED)

        warteBis { File(app.cacheDir, "kamera").listFiles().orEmpty().isEmpty() }
        assertTrue(runBlocking { repository.beobachteAnlagen(id).first() }.isEmpty())
    }

    @Test
    fun `gewaehlte Datei wird angehaengt und laesst sich in einer anderen App oeffnen`() {
        val id = oeffneBeispielfall()
        rule.onNodeWithTag("falldetails").performScrollToNode(hasText("Datei hinzufügen"))
        rule.onNodeWithText("Datei hinzufügen").performClick()

        val anfrage = naechsteAnfrage()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, anfrage.action)
        assertTrue(anfrage.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        val beleg = File(app.cacheDir, "Rechnung 2291.pdf").apply { writeBytes(ByteArray(3000) { 5 }) }
        antworte(anfrage, Intent().setData(Uri.fromFile(beleg)))

        warteBis { repository.beobachteAnlagen(id).first().isNotEmpty() }
        warteAufText("Rechnung 2291.pdf")
        rule.onNodeWithTag("falldetails").performScrollToNode(hasText("Anlagen (1)"))
        rule.onNode(hasText("Rechnung 2291.pdf") and hasClickAction()).performClick()

        val ansehen = naechsterStart()
        assertEquals(Intent.ACTION_VIEW, ansehen.action)
        assertEquals("application/pdf", ansehen.type)
        assertEquals(freigabe, ansehen.data!!.authority)
        assertTrue(ansehen.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(3000, app.contentResolver.openInputStream(ansehen.data!!)!!.use { it.readBytes() }.size)
    }

    @Test
    fun `Fallliste wird als CSV-Datei geteilt`() {
        runBlocking { repository.anlegen(Schadensfall(kundeName = "Anna Becker", tour = "Tour 3", forderungCent = 45_000)) }
        warteAufText("Anna Becker")
        rule.onNodeWithContentDescription("Einstellungen").performClick()
        warteAufText("Absender")
        rule.onNodeWithText("Fallliste für Excel exportieren (CSV)").performScrollTo().performClick()

        rule.waitUntil(10_000) { rule.waitForIdle(); shadowOf(app).peekNextStartedActivity() != null }
        val senden = naechsterStart().getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, senden.action)
        assertEquals("text/csv", senden.type)
        assertTrue(senden.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val datei = senden.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals(freigabe, datei.authority)
        val csv = app.contentResolver.openInputStream(datei)!!.use { it.readBytes() }.toString(Charsets.UTF_8)
        assertTrue(csv.startsWith("\uFEFF" + CsvExport.KOPFZEILE.first()))
        assertTrue("$jahr-001;Neu;Tour 3;" in csv)
        assertTrue(";450,00;" in csv)
    }

    @Test
    fun `Backup erstellen und nach Datenverlust wiederherstellen`() {
        val id = runBlocking { repository.anlegen(Schadensfall(kundeName = "Anna Becker", beschreibung = "Sofa beschädigt")) }
        warteAufText("Anna Becker")
        rule.onNodeWithContentDescription("Einstellungen").performClick()
        warteAufText("Absender")

        rule.onNodeWithText("Backup erstellen").performScrollTo().performClick()
        val speichern = naechsteAnfrage()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, speichern.action)
        assertTrue(speichern.getStringExtra(Intent.EXTRA_TITLE)!!.matches(Regex("Schadensfaelle-Backup_\\d{4}-\\d{2}-\\d{2}\\.zip")))
        val archiv = File(app.cacheDir, "sicherung.zip")
        antworte(speichern, Intent().setData(Uri.fromFile(archiv)))
        warteAufText("Backup erstellt: 1 Fall, 0 Anlagen")
        ZipFile(archiv).use { assertNotNull(it.getEntry(BackupManager.DATEN_DATEI)) }
        // Die Erfolgsmeldung liegt kurz über dem untersten Knopf – abwarten, bis sie verschwunden ist.
        rule.mainClock.advanceTimeBy(10_000)
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Backup erstellt", substring = true).fetchSemanticsNodes().isEmpty() }

        // Datenverlust: Der Fall ist weg.
        runBlocking { repository.fallLoeschen(id) }
        assertTrue(runBlocking { repository.ladeAlleFaelle() }.isEmpty())

        rule.onNodeWithText("Backup wiederherstellen").performScrollTo().performClick()
        val oeffnen = naechsteAnfrage()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, oeffnen.action)
        antworte(oeffnen, Intent().setData(Uri.fromFile(archiv)))
        warteAufText("Backup wiederherstellen?")
        assertTrue("Vor der Bestätigung darf nichts ersetzt werden", runBlocking { repository.ladeAlleFaelle() }.isEmpty())
        rule.onNodeWithText("Ersetzen").performClick()

        warteBis { repository.ladeAlleFaelle().size == 1 }
        val fall = runBlocking { repository.ladeFall(id)!! }
        assertEquals("Anna Becker", fall.kundeName)
        assertEquals("Sofa beschädigt", fall.beschreibung)
        assertFalse(verlaufTexte(id, VerlaufTyp.ANGELEGT).isEmpty())
    }

    @Test
    fun `ungueltige Backup-Datei wird abgelehnt und aendert nichts`() {
        runBlocking { repository.anlegen(Schadensfall(kundeName = "Anna Becker")) }
        warteAufText("Anna Becker")
        rule.onNodeWithContentDescription("Einstellungen").performClick()
        warteAufText("Absender")

        rule.onNodeWithText("Backup wiederherstellen").performScrollTo().performClick()
        val falscheDatei = File(app.cacheDir, "urlaub.zip").apply { writeText("kein Archiv") }
        antworte(naechsteAnfrage(), Intent().setData(Uri.fromFile(falscheDatei)))
        warteAufText("Backup wiederherstellen?")
        rule.onNodeWithText("Ersetzen").performClick()

        warteAufText(BackupManager.KEIN_BACKUP)
        assertEquals(1, runBlocking { repository.ladeAlleFaelle() }.size)
    }

    @Test
    fun `Vorlage fuer den Auftraggeber geht an dessen Adresse`() {
        val id = runBlocking {
            repository.anlegen(
                Schadensfall(
                    kundeName = "Anna Becker", kundeEmail = "a.becker@example.org", auftraggeber = "Möbelhaus Nordpark",
                    auftraggeberKontakt = "Frau Kaya", auftraggeberEmail = "dispo@nordpark.example", auftragsReferenz = "LS-48213",
                ),
            )
        }
        warteAufText("Anna Becker")
        rule.onNodeWithText("Anna Becker").performClick()
        warteAufText("Fall $jahr-001")
        rule.onNode(hasText("E-Mail") and hasClickAction()).performClick()
        warteAufText("An den Auftraggeber")
        rule.onNodeWithTag("vorlage-SCHADENSMITTEILUNG").performScrollTo().performClick()

        val start = naechsterStart()
        assertTrue(start.dataString!!, start.dataString!!.startsWith("mailto:dispo@nordpark.example?subject="))
        val text = Uri.decode(start.dataString)
        assertTrue(text, "Guten Tag Frau Kaya," in text && "Ihr Auftrag LS-48213" in text)
        assertFalse("a.becker@example.org" in text.substringAfter("?"))
        warteBis { verlaufTexte(id, VerlaufTyp.EMAIL) == listOf("E-Mail an Auftraggeber vorbereitet: Schadensmitteilung") }
    }

    @Test
    fun `Auftraggeber wird im Formular aus der Liste gewaehlt`() {
        runBlocking {
            val crm = app.container.crm
            val firma = crm.firmaSpeichern(de.aysetransport.schaden.data.model.AuftraggeberEintrag(name = "Möbelhaus Nordpark", telefon = "02191 999"))
            crm.speichern(de.aysetransport.schaden.data.model.Kontaktperson(auftraggeberUuid = firma, name = "Herr Ott", thema = "Disposition", email = "ott@nordpark.example"))
            crm.speichern(de.aysetransport.schaden.data.model.Kontaktperson(auftraggeberUuid = firma, name = "Frau Kaya", thema = "Schäden", telefon = "02191 123", email = "dispo@nordpark.example"))
        }
        warteAufText("Noch keine Schadensfälle")
        rule.onNodeWithContentDescription("Neuer Fall").performClick()
        warteAufText("Neuer Schadensfall")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("feld-name").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("feld-name").performTextInput("Anna Becker")
        rule.onNodeWithTag("auftraggeber-auswahl").performScrollTo().performClick()
        warteAufText("Auftraggeber wählen")
        rule.onNodeWithText("Möbelhaus Nordpark").performClick()
        rule.onNodeWithText("Speichern").performClick()

        warteBis { repository.ladeAlleFaelle().isNotEmpty() }
        val fall = runBlocking { repository.ladeAlleFaelle() }.single()
        assertEquals(listOf("Möbelhaus Nordpark", "Frau Kaya", "02191 123", "dispo@nordpark.example"),
            listOf(fall.auftraggeber, fall.auftraggeberKontakt, fall.auftraggeberTelefon, fall.auftraggeberEmail))
    }
}
