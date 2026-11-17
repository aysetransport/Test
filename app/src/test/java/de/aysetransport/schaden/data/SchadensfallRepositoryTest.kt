package de.aysetransport.schaden.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.Ansprechpartner
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufTyp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class SchadensfallRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Europe/Berlin")
    private val heute = LocalDate.of(2026, 11, 10)
    private val uhr = Clock.fixed(Instant.parse("2026-11-10T09:00:00Z"), zone)

    private lateinit var db: SchadensfallDatenbank
    private lateinit var speicher: AnlagenSpeicher
    private lateinit var repository: SchadensfallRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SchadensfallDatenbank::class.java).build()
        speicher = AnlagenSpeicher(context)
        repository = SchadensfallRepository(db, speicher, uhr)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun verlauf(fallId: Long) = repository.beobachteVerlauf(fallId).first()

    @Test
    fun `anlegen vergibt fortlaufende Fallnummern und vermerkt die Anlage im Verlauf`() = runBlocking {
        val erste = repository.anlegen(Schadensfall(kundeName = "Anna Becker"))
        val zweite = repository.anlegen(Schadensfall(kundeName = "Carl Demir"))

        assertEquals("2026-001", repository.ladeFall(erste)!!.fallnummer)
        assertEquals("2026-002", repository.ladeFall(zweite)!!.fallnummer)
        assertEquals(uhr.instant(), repository.ladeFall(erste)!!.erstelltAm)

        val eintraege = verlauf(erste)
        assertEquals(1, eintraege.size)
        assertEquals(VerlaufTyp.ANGELEGT, eintraege.single().typ)
        assertEquals("Fall 2026-001 angelegt", eintraege.single().text)
    }

    @Test
    fun `geloeschte Nummern werden nicht neu vergeben, solange eine hoehere existiert`() = runBlocking {
        val erste = repository.anlegen(Schadensfall(kundeName = "A"))
        repository.anlegen(Schadensfall(kundeName = "B"))
        repository.fallLoeschen(erste)

        val dritte = repository.anlegen(Schadensfall(kundeName = "C"))
        assertEquals("2026-003", repository.ladeFall(dritte)!!.fallnummer)
    }

    @Test
    fun `speichern behaelt Fallnummer und Anlagedatum und protokolliert den Statuswechsel`() = runBlocking {
        val id = repository.anlegen(Schadensfall(kundeName = "Anna Becker"))
        val gespeichert = repository.ladeFall(id)!!

        repository.speichern(
            gespeichert.copy(
                kundeName = "Anna Becker-Schmidt",
                status = FallStatus.IN_PRUEFUNG,
                fallnummer = "manipuliert",
                erstelltAm = Instant.EPOCH,
            ),
        )

        val danach = repository.ladeFall(id)!!
        assertEquals("Anna Becker-Schmidt", danach.kundeName)
        assertEquals("2026-001", danach.fallnummer)
        assertEquals(uhr.instant(), danach.erstelltAm)
        assertEquals("Status: Neu → In Prüfung", verlauf(id).first { it.typ == VerlaufTyp.STATUS }.text)
    }

    @Test
    fun `nachhaken vermerkt Kanal und Ergebnis und plant den naechsten Termin`() = runBlocking {
        val id = repository.anlegen(Schadensfall(kundeName = "Anna Becker", wiedervorlageAm = heute.minusDays(3)))

        repository.nachhakenErfassen(
            fallId = id,
            kanal = VerlaufTyp.ANRUF,
            ergebnis = "Kundin schickt Fotos bis Freitag",
            naechsteWiedervorlage = heute.plusDays(7),
            wartetAuf = Ansprechpartner.ANSPRUCHSTELLER,
            naechsterSchritt = "Fotos prüfen",
        )

        val fall = repository.ladeFall(id)!!
        assertEquals(heute.plusDays(7), fall.wiedervorlageAm)
        assertEquals(Ansprechpartner.ANSPRUCHSTELLER, fall.wartetAuf)
        assertEquals("Fotos prüfen", fall.wiedervorlageNotiz)

        val eintrag = verlauf(id).first { it.typ == VerlaufTyp.ANRUF }
        assertEquals(
            "Kundin schickt Fotos bis Freitag\nWiedervorlage am 17.11.2026 · wartet auf Geschädigter · Fotos prüfen",
            eintrag.text,
        )
        assertTrue(repository.faelligeFaelle(heute).isEmpty())
    }

    @Test
    fun `nachhaken ohne Text und ohne neuen Termin wird trotzdem nachvollziehbar vermerkt`() = runBlocking {
        val id = repository.anlegen(Schadensfall(kundeName = "Anna Becker", wiedervorlageAm = heute))

        repository.nachhakenErfassen(id, VerlaufTyp.EMAIL, "  ", null, null, "")

        assertNull(repository.ladeFall(id)!!.wiedervorlageAm)
        assertEquals("Nachgehakt\nKeine Wiedervorlage geplant", verlauf(id).first { it.typ == VerlaufTyp.EMAIL }.text)
    }

    @Test
    fun `beim Abschliessen entfaellt die Wiedervorlage`() = runBlocking {
        val id = repository.anlegen(
            Schadensfall(kundeName = "Anna Becker", wiedervorlageAm = heute, wartetAuf = Ansprechpartner.VERSICHERUNG, wiedervorlageNotiz = "x"),
        )

        repository.statusAendern(id, FallStatus.ERLEDIGT, "Vergleich gezahlt")

        val fall = repository.ladeFall(id)!!
        assertEquals(FallStatus.ERLEDIGT, fall.status)
        assertNull(fall.wiedervorlageAm)
        assertNull(fall.wartetAuf)
        assertEquals("", fall.wiedervorlageNotiz)
        assertEquals("Status: Neu → Erledigt\nVergleich gezahlt", verlauf(id).first { it.typ == VerlaufTyp.STATUS }.text)
    }

    @Test
    fun `unveraenderter Status ohne Anmerkung erzeugt keinen Verlaufseintrag`() = runBlocking {
        val id = repository.anlegen(Schadensfall(kundeName = "Anna Becker"))
        repository.statusAendern(id, FallStatus.NEU)
        assertEquals(1, verlauf(id).size)
    }

    @Test
    fun `Wiedervorlage setzen vermerkt nur echte Aenderungen`() = runBlocking {
        val id = repository.anlegen(Schadensfall(kundeName = "Anna Becker"))

        repository.wiedervorlageSetzen(id, heute.plusDays(3), Ansprechpartner.FAHRER, "Stellungnahme")
        repository.wiedervorlageSetzen(id, heute.plusDays(3), Ansprechpartner.FAHRER, "Stellungnahme")

        val eintraege = verlauf(id).filter { it.typ == VerlaufTyp.WIEDERVORLAGE }
        assertEquals(1, eintraege.size)
        assertEquals("Wiedervorlage am 13.11.2026 · wartet auf Fahrer · Stellungnahme", eintraege.single().text)
    }

    @Test
    fun `faellig sind nur offene Faelle mit Termin bis heute, aelteste zuerst`() = runBlocking {
        val gestern = repository.anlegen(Schadensfall(kundeName = "Gestern", wiedervorlageAm = heute.minusDays(1)))
        val vorWoche = repository.anlegen(Schadensfall(kundeName = "Vorwoche", wiedervorlageAm = heute.minusDays(7)))
        val heutig = repository.anlegen(Schadensfall(kundeName = "Heute", wiedervorlageAm = heute))
        repository.anlegen(Schadensfall(kundeName = "Morgen", wiedervorlageAm = heute.plusDays(1)))
        repository.anlegen(Schadensfall(kundeName = "Ohne Termin"))
        val erledigt = repository.anlegen(Schadensfall(kundeName = "Erledigt", wiedervorlageAm = heute.minusDays(2)))
        repository.statusAendern(erledigt, FallStatus.ERLEDIGT)

        assertEquals(listOf(vorWoche, gestern, heutig), repository.faelligeFaelle(heute).map { it.id })
    }

    @Test
    fun `Anlage wird in den App-Speicher kopiert und beim Entfernen wieder geloescht`() = runBlocking {
        val id = repository.anlegen(Schadensfall(kundeName = "Anna Becker"))
        val quelle = File(context.cacheDir, "Lieferschein 4711.pdf").apply { writeBytes(ByteArray(2048) { 7 }) }

        val anlage = repository.anlageHinzufuegen(id, Uri.fromFile(quelle))

        val kopie = speicher.datei(anlage)
        assertTrue(kopie.isFile)
        assertEquals(2048L, kopie.length())
        assertEquals("Lieferschein 4711.pdf", anlage.anzeigename)
        assertEquals("application/pdf", anlage.mimeType)
        assertTrue(anlage.dateiname.endsWith(".pdf"))
        assertFalse("Dateiname darf den Originalnamen nicht enthalten", "Lieferschein" in anlage.dateiname)
        assertEquals(listOf(anlage), repository.beobachteAnlagen(id).first())
        assertEquals(mapOf(id to 1), repository.ladeAnlagenAnzahl())

        repository.anlageLoeschen(anlage)

        assertFalse(kopie.exists())
        assertTrue(repository.beobachteAnlagen(id).first().isEmpty())
        assertEquals(
            listOf("Anlage entfernt: Lieferschein 4711.pdf", "Anlage hinzugefügt: Lieferschein 4711.pdf"),
            verlauf(id).filter { it.typ == VerlaufTyp.ANLAGE }.map { it.text },
        )
    }

    @Test
    fun `nicht lesbare Quelle hinterlaesst weder Datei noch Datenbankeintrag`() = runBlocking {
        val id = repository.anlegen(Schadensfall(kundeName = "Anna Becker"))

        val fehler = runCatching { repository.anlageHinzufuegen(id, Uri.fromFile(File(context.cacheDir, "gibt-es-nicht.jpg"))) }

        assertTrue(fehler.isFailure)
        assertTrue(repository.beobachteAnlagen(id).first().isEmpty())
        assertTrue(speicher.ordner(id).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `Fall loeschen entfernt Verlauf, Anlagen und Dateien`() = runBlocking {
        val id = repository.anlegen(Schadensfall(kundeName = "Anna Becker"))
        val quelle = File(context.cacheDir, "foto.jpg").apply { writeBytes(ByteArray(10)) }
        val anlage = repository.anlageHinzufuegen(id, Uri.fromFile(quelle))
        assertNotNull(repository.ladeFall(id))

        repository.fallLoeschen(id)

        assertNull(repository.ladeFall(id))
        assertTrue(verlauf(id).isEmpty())
        assertTrue(repository.beobachteAnlagen(id).first().isEmpty())
        assertFalse(speicher.datei(anlage).exists())
        assertFalse(speicher.ordner(id).exists())
    }

    @Test
    fun `neue Auftraggeber aus Faellen werden Firmen mit Ansprechpartner, bekannte nicht doppelt`() = runBlocking {
        val erster = repository.anlegen(Schadensfall(kundeName = "A", auftraggeber = "Möbelhaus Nordpark", auftraggeberKontakt = "Frau Kaya", auftraggeberEmail = "dispo@nordpark.example"))
        val id = repository.anlegen(Schadensfall(kundeName = "B", auftraggeber = "  möbelhaus NORDPARK "))
        val nordpark = repository.beobachteAuftraggeber().first().single()
        assertEquals("beide Fälle hängen an derselben Firma", nordpark.uuid, repository.ladeFall(id)!!.auftraggeberUuid)
        assertEquals(nordpark.uuid, repository.ladeFall(erster)!!.auftraggeberUuid)
        val kontakt = db.crm().ladeKontaktpersonen(nordpark.uuid).single()
        assertEquals(listOf("Frau Kaya", "dispo@nordpark.example", "Schäden"), listOf(kontakt.name, kontakt.email, kontakt.thema))

        repository.speichern(repository.ladeFall(id)!!.copy(auftraggeber = "Küchenstudio Süd"))
        val liste = repository.beobachteAuftraggeber().first()
        assertEquals(listOf("Küchenstudio Süd", "Möbelhaus Nordpark"), liste.map { it.name })
        assertEquals("umgehängt", liste.first().uuid, repository.ladeFall(id)!!.auftraggeberUuid)

        CrmRepository(db).firmaLoeschen(liste.first())
        assertEquals(listOf("Möbelhaus Nordpark"), repository.beobachteAuftraggeber().first().map { it.name })
        assertEquals("Fall behält seinen Auftraggeber", "Küchenstudio Süd", repository.ladeFall(id)!!.auftraggeber)
        assertNull(repository.ladeFall(id)!!.auftraggeberUuid)
    }
}
