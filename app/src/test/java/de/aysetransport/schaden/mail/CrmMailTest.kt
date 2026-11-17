package de.aysetransport.schaden.mail

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.aysetransport.schaden.KlartextTresor
import de.aysetransport.schaden.data.AnlagenSpeicher
import de.aysetransport.schaden.data.CrmRepository
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.AktivitaetArt
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.Kontaktperson
import de.aysetransport.schaden.data.model.Loeschung
import de.aysetransport.schaden.data.model.MailKontoArt
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufTyp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Zwei Postfächer (Schäden und Allgemein), Zuordnung zu Firmen und das Umhängen von Mails. */
@RunWith(AndroidJUnit4::class)
class CrmMailTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var schaden: TestPostfach
    private lateinit var allgemein: TestPostfach
    private lateinit var db: SchadensfallDatenbank
    private lateinit var repository: SchadensfallRepository
    private lateinit var crm: CrmRepository
    private lateinit var postfach: MailPostfach
    private lateinit var konten: Map<MailKontoArt, MailSpeicher>
    private lateinit var seats: String
    private var fallId = 0L
    private val jahr = LocalDate.now().year

    private fun speicher() = MailSpeicher(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { File(context.cacheDir, "m-${UUID.randomUUID()}.preferences_pb") },
        KlartextTresor,
    )

    @Before
    fun setUp() {
        schaden = TestPostfach("schaden@ayse.example")
        allgemein = TestPostfach("info@ayse.example")
        db = Room.inMemoryDatabaseBuilder(context, SchadensfallDatenbank::class.java).build()
        val anlagen = AnlagenSpeicher(context, File(context.filesDir, "crm-mail-${UUID.randomUUID()}"))
        repository = SchadensfallRepository(db, anlagen)
        crm = CrmRepository(db)
        konten = mapOf(MailKontoArt.SCHADEN to speicher(), MailKontoArt.ALLGEMEIN to speicher())
        postfach = MailPostfach(db, repository, konten, MailDienst(), context.cacheDir, MailAnhangSpeicher(File(context.filesDir, "anh-${UUID.randomUUID()}")))
        runBlocking {
            postfach.einrichten(MailKontoArt.SCHADEN, schaden.konfig, schaden.passwort)
            postfach.einrichten(MailKontoArt.ALLGEMEIN, allgemein.konfig, allgemein.passwort)
            seats = crm.firmaSpeichern(AuftraggeberEintrag(name = "Seats and Sofas", website = "https://www.seats-sofas.example/krefeld"))
            crm.speichern(Kontaktperson(auftraggeberUuid = seats, name = "Jana Krüger", thema = "Disposition", email = "jana.krueger@seats-sofas.example"))
            crm.firmaSpeichern(AuftraggeberEintrag(name = "Kunde mit Gmail", email = "chef@gmail.com"))
            fallId = repository.anlegen(Schadensfall(kundeName = "Anna Becker", kundeEmail = "anna@kunde.example", auftraggeber = "Seats and Sofas"))
        }
    }

    @After
    fun tearDown() {
        db.close()
        schaden.close()
        allgemein.close()
    }

    private fun mails() = runBlocking { db.crm().ladeAlleMails() }
    private fun mail(betreff: String) = mails().single { it.betreff == betreff }

    @Test
    fun `beide Postfaecher werden abgerufen und nach Fall, Firma oder gar nicht zugeordnet`() = runBlocking {
        allgemein.empfange("einkauf@seats-sofas.example", "Touren nächste Woche", "Bitte 3 Touren einplanen.") // Domain der Website
        allgemein.empfange("jana.krueger@seats-sofas.example", "Rückruf", "Bitte zurückrufen.") // E-Mail des Ansprechpartners
        allgemein.empfange("kunde@kunde.example", "Re: Lieferung [Fall $jahr-001]", "Zum Fall") // Fallnummer schlägt jedes Postfach
        schaden.empfange("irgendwer@gmail.com", "Frage", "Hallo", anhang = "Foto.jpg" to ByteArray(2000) { 3 }) // Freemail: keine Firma

        val neu = postfach.abrufen()

        assertEquals(4, neu.size)
        assertEquals(seats, mail("Touren nächste Woche").auftraggeberUuid)
        assertEquals(MailKontoArt.ALLGEMEIN, mail("Touren nächste Woche").konto)
        assertEquals(seats, mail("Rückruf").auftraggeberUuid)
        assertEquals(fallId, mail("Re: Lieferung [Fall $jahr-001]").fallId)
        val offen = mail("Frage")
        assertTrue("nicht zugeordnet", !offen.istZugeordnet)
        assertEquals(MailKontoArt.SCHADEN, offen.konto)
        assertEquals(listOf("Foto.jpg"), postfach.anhangSpeicher.dateien(offen.id).map { postfach.anhangSpeicher.anzeigename(it) })

        // Kontakthistorie der Firma: je Mail ein Eintrag, mit Ansprechpartner, wenn die Adresse bekannt ist.
        val historie = crm.beobachteAktivitaeten(seats).first()
        assertEquals(2, historie.size)
        assertTrue(historie.all { it.art == AktivitaetArt.EMAIL })
        assertEquals("Jana Krüger", historie.single { "Rückruf" in it.text }.kontakt)
        // Die Mail zum Fall steht im Verlauf und über den Fall auch bei der Firma.
        assertEquals(1, repository.beobachteVerlauf(fallId).first().count { it.typ == VerlaufTyp.EMAIL_EINGANG })
        val firma = crm.ladeFirma(seats)!!
        assertEquals(3, crm.beobachteMails(firma).first().size)
    }

    @Test
    fun `eine Mail laesst sich von Hand zuordnen und umhaengen`() = runBlocking {
        schaden.empfange("irgendwer@gmail.com", "Frage", "Hallo", anhang = "Foto.jpg" to ByteArray(2000) { 3 })
        postfach.abrufen()
        val id = mail("Frage").id

        // Zum Fall: Verlaufseintrag, Anhang wird Anlage des Falls
        postfach.zuordnen(id, MailZuordnung(fallId = fallId))
        val verlauf = repository.beobachteVerlauf(fallId).first().filter { it.typ == VerlaufTyp.EMAIL_EINGANG }
        assertEquals(1, verlauf.size)
        assertEquals(listOf("Foto.jpg"), repository.beobachteAnlagen(fallId).first().map { it.anzeigename })
        assertTrue(postfach.anhangSpeicher.dateien(id).isEmpty())

        // Zur Firma: der Verlaufseintrag verschwindet (auch in der Cloud), die Kontakthistorie bekommt die Mail
        postfach.zuordnen(id, MailZuordnung(auftraggeberUuid = seats))
        assertTrue(repository.beobachteVerlauf(fallId).first().none { it.typ == VerlaufTyp.EMAIL_EINGANG })
        assertTrue(db.dao().loeschungen().any { it.art == Loeschung.Art.VERLAUF && it.uuid == verlauf.single().uuid })
        val aktivitaet = crm.beobachteAktivitaeten(seats).first().single()
        assertEquals(aktivitaet.uuid, mail("Frage").aktivitaetUuid)

        // Nur noch einem Endkunden zugeordnet: auch der Eintrag bei der Firma geht
        postfach.zuordnen(id, MailZuordnung(kundeRef = "irgendwer@gmail.com"))
        assertTrue(crm.beobachteAktivitaeten(seats).first().isEmpty())
        assertEquals("irgendwer@gmail.com", mail("Frage").kundeRef)
        assertTrue(mail("Frage").istZugeordnet)

        // Ganz lösen: zurück in „Nicht zugeordnet“
        postfach.zuordnen(id, MailZuordnung.KEINE)
        assertTrue(!mail("Frage").istZugeordnet)
    }

    @Test
    fun `Mails an Firmen gehen ueber das allgemeine Postfach, Mails aus Faellen ueber das Schadens-Postfach`() = runBlocking {
        postfach.senden(MailEntwurf("jana.krueger@seats-sofas.example", "Angebot 2027", "Guten Tag Jana Krüger,\n\nanbei das Angebot."), MailZuordnung(auftraggeberUuid = seats), MailKontoArt.ALLGEMEIN)
        postfach.senden(fallId, MailEntwurf("anna@kunde.example", "Ihre Schadensmeldung", "Guten Tag"))

        val anFirma = allgemein.server.getReceivedMessagesForDomain("seats-sofas.example").first()
        assertEquals("info@ayse.example", anFirma.from.single().toString().substringAfter('<').substringBefore('>'))
        assertEquals("Angebot 2027", anFirma.subject)
        val anKundin = schaden.server.getReceivedMessagesForDomain("kunde.example").first()
        assertEquals("schaden@ayse.example", anKundin.from.single().toString().substringAfter('<').substringBefore('>'))
        assertEquals("Ihre Schadensmeldung [Fall $jahr-001]", anKundin.subject)

        val a = crm.beobachteAktivitaeten(seats).first().single()
        assertEquals("Jana Krüger", a.kontakt)
        assertTrue(a.text, a.text.startsWith("E-Mail gesendet\nAn: jana.krueger@seats-sofas.example\nBetreff: Angebot 2027"))
        assertEquals(MailKontoArt.ALLGEMEIN, mail("Angebot 2027").konto)

        // Ohne allgemeines Postfach läuft alles über das Schadens-Postfach.
        konten.getValue(MailKontoArt.ALLGEMEIN).trenne()
        assertEquals(MailKontoArt.SCHADEN, postfach.kontoFuer(MailKontoArt.ALLGEMEIN))
        postfach.senden(MailEntwurf("jana.krueger@seats-sofas.example", "Nachtrag", "Text"), MailZuordnung(auftraggeberUuid = seats), MailKontoArt.ALLGEMEIN)
        assertEquals(MailKontoArt.SCHADEN, mail("Nachtrag").konto)
        assertNull(mail("Nachtrag").fallId)
    }

    @Test
    fun `Firmenzuordnung ueber Adresse und Domain`() = runBlocking {
        assertEquals(seats, postfach.firmaZuAdresse("Einkauf <einkauf@seats-sofas.example>")?.uuid)
        assertEquals(seats, postfach.firmaZuAdresse("JANA.KRUEGER@seats-sofas.example")?.uuid)
        assertEquals("genaue Adresse zählt auch bei Freemail", "Kunde mit Gmail", postfach.firmaZuAdresse("chef@gmail.com")?.name)
        assertNull("Freemail-Domain ordnet nicht zu", postfach.firmaZuAdresse("jemand@gmail.com"))
        assertNull(postfach.firmaZuAdresse("fremd@andere.example"))
        assertEquals("seats-sofas.example", MailPostfach.websiteDomain("https://www.seats-sofas.example/krefeld"))
    }
}
