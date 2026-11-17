package de.aysetransport.schaden.mail

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.aysetransport.schaden.KlartextTresor
import de.aysetransport.schaden.data.AnlagenSpeicher
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufTyp
import jakarta.mail.Folder
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MailPostfachTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var postfachServer: TestPostfach
    private lateinit var db: SchadensfallDatenbank
    private lateinit var repository: SchadensfallRepository
    private lateinit var anlagen: AnlagenSpeicher
    private lateinit var speicher: MailSpeicher
    private lateinit var postfach: MailPostfach
    private val heute = LocalDate.now()

    @Before
    fun setUp() {
        postfachServer = TestPostfach()
        db = Room.inMemoryDatabaseBuilder(context, SchadensfallDatenbank::class.java).build()
        anlagen = AnlagenSpeicher(context, File(context.filesDir, "mail-${UUID.randomUUID()}"))
        repository = SchadensfallRepository(db, anlagen)
        speicher = MailSpeicher(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { File(context.cacheDir, "m-${UUID.randomUUID()}.preferences_pb") },
            KlartextTresor,
        )
        postfach = MailPostfach(db, repository, speicher, MailDienst(), context.cacheDir)
        runBlocking { postfach.einrichten(postfachServer.konfig, postfachServer.passwort) }
    }

    @After
    fun tearDown() {
        db.close()
        postfachServer.close()
    }

    private fun verlauf(fallId: Long) = runBlocking { repository.beobachteVerlauf(fallId).first() }
    private fun neuerFall(fall: Schadensfall) = runBlocking { repository.anlegen(fall) }

    @Test
    fun `falsches Passwort wird beim Einrichten abgelehnt und nicht gespeichert`() = runBlocking {
        val anderer = MailSpeicher(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { File(context.cacheDir, "m-${UUID.randomUUID()}.preferences_pb") },
            KlartextTresor,
        )
        val p = MailPostfach(db, repository, anderer, MailDienst(), context.cacheDir)
        assertThrows(MailAnmeldungFehler::class.java) { runBlocking { p.einrichten(postfachServer.konfig, "falsch") } }
        assertNull(anderer.aktuell().konfig)
        assertEquals(postfachServer.passwort, speicher.passwort())
    }

    @Test
    fun `Senden geht an den Empfaenger, traegt die Fallnummer und landet im Verlauf und unter Gesendet`() = runBlocking {
        postfachServer.server.imap.createStore().apply { connect(postfachServer.adresse, postfachServer.passwort) }.use { it.getFolder("Sent").create(Folder.HOLDS_MESSAGES) }
        val id = neuerFall(Schadensfall(kundeName = "Anna Becker", kundeEmail = "anna@kunde.example"))
        val foto = File(context.cacheDir, "Sofa.jpg").apply { writeBytes(ByteArray(3000) { 7 }) }
        postfach.senden(id, MailEntwurf("anna@kunde.example", "Ihre Schadensmeldung", "Guten Tag Anna Becker,\n\nbitte Fotos.", anhaenge = listOf(MailAnhang("Sofa.jpg", "image/jpeg", foto.readBytes()))))

        // GreenMail zählt auch die Kopie unter „Gesendet“ mit (gleicher Empfänger) – beide sind dieselbe Mail.
        val angekommen = postfachServer.server.getReceivedMessagesForDomain("kunde.example").first()
        assertEquals("Ihre Schadensmeldung [Fall ${heute.year}-001]", angekommen.subject)
        assertEquals("anna@kunde.example", angekommen.allRecipients.single().toString())
        assertEquals(2, (angekommen.content as MimeMultipart).count)

        val eintrag = verlauf(id).first { it.typ == VerlaufTyp.EMAIL_AUSGANG }
        assertTrue(eintrag.text, eintrag.text.startsWith("An: anna@kunde.example\nBetreff: Ihre Schadensmeldung [Fall ${heute.year}-001]\nAnhänge: Sofa.jpg"))
        val gesendet = postfachServer.server.imap.createStore().apply { connect(postfachServer.adresse, postfachServer.passwort) }.use { s ->
            s.getFolder("Sent").apply { open(Folder.READ_ONLY) }.messageCount
        }
        assertEquals("Kopie im Ordner Gesendet", 1, gesendet)
    }

    @Test
    fun `Auftraggeber in BCC bekommt die Mail, der Geschaedigte sieht ihn nicht`() = runBlocking {
        val id = neuerFall(Schadensfall(kundeName = "Anna Becker", kundeEmail = "anna@kunde.example", auftraggeberEmail = "dispo@moebel.example"))
        postfach.senden(id, MailEntwurf("anna@kunde.example", "Ihre Schadensmeldung", "Guten Tag", bcc = "dispo@moebel.example"))

        val beimKunden = postfachServer.server.getReceivedMessagesForDomain("kunde.example").first()
        assertNull("keine Bcc-Kopfzeile beim Empfänger", beimKunden.getHeader("Bcc"))
        assertEquals("anna@kunde.example", beimKunden.getRecipients(jakarta.mail.Message.RecipientType.TO).single().toString())
        // Je Empfänger eine Zustellung – die an den Auftraggeber ist dieselbe Mail ohne sichtbaren Hinweis auf ihn.
        val alle = postfachServer.server.receivedMessages
        assertEquals(2, alle.size)
        assertTrue(alle.all { it.messageID == beimKunden.messageID && it.getHeader("Bcc") == null })
        val agPostfach = postfachServer.server.userManager.getUserByEmail("dispo@moebel.example")
        assertTrue("Auftraggeber hat die Mail im Postfach", agPostfach != null)

        val eintrag = verlauf(id).first { it.typ == VerlaufTyp.EMAIL_AUSGANG }
        assertTrue(eintrag.text, eintrag.text.startsWith("An: anna@kunde.example\nBcc: dispo@moebel.example\nBetreff:"))
    }

    @Test
    fun `Antwort mit Fallnummer wird zugeordnet, Anhang wird Anlage, Wiedervorlage rueckt auf heute`() = runBlocking {
        val id = neuerFall(Schadensfall(kundeName = "Anna Becker", kundeEmail = "anna@kunde.example", status = FallStatus.WARTET_AUF_RUECKMELDUNG, wiedervorlageAm = heute.plusDays(9)))
        postfachServer.empfange("Anna Becker <anna@kunde.example>", "Re: Ihre Schadensmeldung [Fall ${heute.year}-001]", "Hier die Fotos.", anhang = "Armlehne.jpg" to ByteArray(5000) { 3 })

        val neu = postfach.abrufen()

        assertEquals(1, neu.size)
        val eintrag = verlauf(id).first { it.typ == VerlaufTyp.EMAIL_EINGANG }
        assertTrue(eintrag.text, eintrag.text.startsWith("Von: Anna Becker <anna@kunde.example>\nBetreff: Re: Ihre Schadensmeldung [Fall ${heute.year}-001]\nAnhänge (zum Fall genommen): Armlehne.jpg\n\nHier die Fotos."))
        val anlage = repository.beobachteAnlagen(id).first().single()
        assertEquals("Armlehne.jpg", anlage.anzeigename)
        assertEquals(5000L, anlage.groesseBytes)
        val fall = repository.ladeFall(id)!!
        assertEquals(heute, fall.wiedervorlageAm)
        assertEquals("Neue E-Mail beantworten", fall.wiedervorlageNotiz)

        assertTrue("nichts doppelt", postfach.abrufen().isEmpty())
        assertEquals(1, verlauf(id).count { it.typ == VerlaufTyp.EMAIL_EINGANG })
        assertTrue(speicher.aktuell().letzterAbruf > 0)
    }

    @Test
    fun `Zuordnung ueber Antwortbezug und ueber eindeutigen Absender`() = runBlocking {
        val becker = neuerFall(Schadensfall(kundeName = "Anna Becker", kundeEmail = "anna@kunde.example"))
        val demir = neuerFall(Schadensfall(kundeName = "Carl Demir", auftraggeber = "Möbelhaus", auftraggeberEmail = "dispo@moebel.example"))
        neuerFall(Schadensfall(kundeName = "Dora Engel", auftraggeber = "Möbelhaus", auftraggeberEmail = "dispo@moebel.example"))
        postfach.senden(becker, MailEntwurf("anna@kunde.example", "Unterlagen", "Bitte senden."))
        val gesendeteId = MailDienst.ohneKlammern(postfachServer.server.getReceivedMessagesForDomain("kunde.example").first().messageID)

        postfachServer.empfange("anna@other.example", "AW: ohne Nummer", "Antwort aus anderem Konto", antwortAuf = gesendeteId)
        postfachServer.empfange("dispo@moebel.example", "Rückfrage", "Zu welchem Auftrag?") // zwei offene Fälle: nicht eindeutig
        postfachServer.empfange("anna@kunde.example", "Noch etwas", "Ohne Nummer, aber bekannter Absender")
        postfachServer.empfange("werbung@shop.example", "Angebot der Woche", "Nichts für die Fälle")

        val neu = postfach.abrufen()

        assertEquals(listOf("AW: ohne Nummer", "Noch etwas"), neu.filter { it.fall != null }.map { it.betreff })
        assertEquals(2, verlauf(becker).count { it.typ == VerlaufTyp.EMAIL_EINGANG })
        assertFalse(verlauf(demir).any { it.typ == VerlaufTyp.EMAIL_EINGANG })
        // Nicht eindeutig für einen Fall, aber die Adresse gehört zum Ansprechpartner der Firma: landet bei der Firma.
        assertEquals("Möbelhaus", neu.single { it.betreff == "Rückfrage" }.firma?.name)
        // Alles andere landet im Eingang „Nicht zugeordnet“.
        val werbung = neu.single { it.betreff == "Angebot der Woche" }
        assertTrue(werbung.fall == null && werbung.firma == null)
    }

    @Test
    fun `nach dem Schliessen ordnet die Absenderadresse nicht mehr zu, die Fallnummer schon`() = runBlocking {
        val id = neuerFall(Schadensfall(kundeName = "Anna Becker", kundeEmail = "anna@kunde.example"))
        repository.statusAendern(id, FallStatus.ERLEDIGT)
        postfachServer.empfange("anna@kunde.example", "Danke", "Danke für die Regulierung.")
        postfachServer.empfange("anna@kunde.example", "Nachtrag [Fall ${heute.year}-001]", "Noch ein Foto.")

        val neu = postfach.abrufen()

        assertEquals(listOf("Nachtrag [Fall ${heute.year}-001]"), neu.filter { it.fall != null }.map { it.betreff })
        assertNull("„Danke“ bleibt im Eingang „Nicht zugeordnet“", neu.single { it.betreff == "Danke" }.fall)
        assertNull("abgeschlossene Fälle bekommen keine Wiedervorlage", repository.ladeFall(id)!!.wiedervorlageAm)
    }

    @Test
    fun `Betreff bekommt die Fallnummer genau einmal`() {
        assertEquals("Hallo [Fall 2026-004]", MailPostfach.mitFallnummer("Hallo", "2026-004"))
        assertEquals("Re: Hallo [Fall 2026-004]", MailPostfach.mitFallnummer("Re: Hallo [Fall 2026-004]", "2026-009"))
    }

    @Test
    fun `HTML-Mails werden als Text uebernommen`() {
        assertEquals("Guten Tag,\nhier die Fotos & Belege.", MailDienst.htmlAlsText("<p>Guten Tag,</p><p>hier die <b>Fotos</b> &amp; Belege.</p><style>p{}</style>"))
    }
}
