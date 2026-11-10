package de.aysetransport.schaden.cloud

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.aysetransport.schaden.data.AnlagenSpeicher
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.Ansprechpartner
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufTyp
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
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CloudAbgleichTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pin = "geheim-2026"
    private lateinit var cloud: TestCloud
    private val geraete = mutableListOf<Geraet>()

    /** Ein simuliertes Handy mit eigener Datenbank, eigenem Anlagen-Ordner und eigener Uhr. */
    inner class Geraet {
        val db: SchadensfallDatenbank = Room.inMemoryDatabaseBuilder(context, SchadensfallDatenbank::class.java).build()
        val speicher = AnlagenSpeicher(context, File(context.filesDir, "geraet-${UUID.randomUUID()}"))
        var jetzt: Instant = Instant.parse("2026-11-10T08:00:00Z")
        val uhr = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("Europe/Berlin")
            override fun withZone(zone: ZoneId?): Clock = this
            override fun instant(): Instant = jetzt
        }
        val repo = SchadensfallRepository(db, speicher, uhr)
        val cloudSpeicher = CloudSpeicher(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
                File(context.cacheDir, "cloud-${UUID.randomUUID()}.preferences_pb")
            },
        )
        val abgleich = CloudAbgleich(db, speicher, cloudSpeicher, uhr)

        fun spaeter(minuten: Long) { jetzt = jetzt.plusSeconds(minuten * 60) }
        fun abgleichen() = runBlocking { abgleich.abgleichen() }
        fun faelle() = runBlocking { repo.ladeAlleFaelle() }
        fun fall(name: String) = faelle().single { it.kundeName == name }
        fun verlauf(fallId: Long) = runBlocking { repo.beobachteVerlauf(fallId).first() }
        fun anlagen(fallId: Long) = runBlocking { repo.beobachteAnlagen(fallId).first() }
        fun zustand() = runBlocking { cloudSpeicher.aktuell() }
    }

    private fun geraet(verbinden: Boolean = true, neuePin: Boolean = false): Geraet = Geraet().also { g ->
        geraete += g
        if (verbinden) runBlocking { g.abgleich.verbinde(cloud.url, pin, neuePin) }
    }

    @Before
    fun setUp() {
        cloud = TestCloud()
    }

    @After
    fun tearDown() {
        geraete.forEach { it.db.close() }
        cloud.close()
    }

    @Test
    fun `Adresse pruefen, PIN festlegen und anmelden`() = runBlocking {
        val g = geraet(verbinden = false)
        assertFalse("noch keine PIN festgelegt", g.abgleich.pruefeAdresse(cloud.url))
        g.abgleich.verbinde(cloud.url, pin, neuePin = true)
        assertTrue(g.abgleich.pruefeAdresse(cloud.url))
        assertTrue(g.zustand().angemeldet)

        val zweites = geraet(verbinden = false)
        val falsch = assertThrows(CloudFehler::class.java) { runBlocking { zweites.abgleich.verbinde(cloud.url, "falsch!", neuePin = false) } }
        assertTrue(falsch.pinFalsch)
        assertFalse("falsche PIN wird nicht gespeichert", zweites.zustand().verbunden)

        val fremd = assertThrows(IOException::class.java) { runBlocking { g.abgleich.pruefeAdresse(cloud.url.replace("/exec", "/gibtsnicht")) } }
        assertTrue(fremd.message.orEmpty().isNotEmpty())
    }

    @Test
    fun `ein Fall wandert mit Verlauf von einem Handy zum anderen`() {
        val a = geraet(neuePin = true)
        val id = runBlocking {
            a.repo.anlegen(
                Schadensfall(
                    kundeName = "Anna Becker", tour = "Tour 3", fahrer = "M. Yilmaz", beifahrer1 = "Ali Kaya", beifahrer2 = "Ben Ott", forderungCent = 45_000, schadensdatum = LocalDate.of(2026, 11, 3),
                    wiedervorlageAm = LocalDate.of(2026, 11, 17), wartetAuf = Ansprechpartner.VERSICHERUNG,
                ),
            )
        }
        runBlocking { a.repo.verlaufHinzufuegen(id, VerlaufTyp.ANRUF, "Kundin angerufen") }
        assertEquals(3, a.abgleichen().gesendet)
        assertTrue("nach dem Abgleich ist nichts mehr offen", runBlocking { a.db.dao().offeneFaelle().isEmpty() })

        val b = geraet()
        assertEquals(AbgleichErgebnis(gesendet = 0, empfangen = 3), b.abgleichen())
        val fall = b.fall("Anna Becker")
        assertEquals(a.fall("Anna Becker").copy(id = fall.id, syncOffen = false), fall)
        assertEquals(listOf("Ali Kaya", "Ben Ott"), listOf(fall.beifahrer1, fall.beifahrer2))
        assertEquals(listOf("Kundin angerufen", "Fall 2026-001 angelegt"), b.verlauf(fall.id).map { it.text })
        assertEquals(AbgleichErgebnis(0, 0), b.abgleichen())
    }

    @Test
    fun `bei gleichzeitiger Bearbeitung gewinnt die spaetere Aenderung auf beiden Geraeten`() {
        val a = geraet(neuePin = true)
        runBlocking { a.repo.anlegen(Schadensfall(kundeName = "Carl Demir")) }
        a.abgleichen()
        val b = geraet()
        b.abgleichen()

        a.spaeter(5)
        runBlocking { a.repo.statusAendern(a.fall("Carl Demir").id, FallStatus.BEI_VERSICHERUNG) }
        b.spaeter(10) // B ändert später
        runBlocking { b.repo.speichern(b.fall("Carl Demir").copy(kundeTelefon = "0171 5550142", status = FallStatus.IN_PRUEFUNG)) }

        a.abgleichen()
        b.abgleichen()
        a.abgleichen()
        for (g in listOf(a, b)) {
            val f = g.fall("Carl Demir")
            assertEquals(FallStatus.IN_PRUEFUNG, f.status)
            assertEquals("0171 5550142", f.kundeTelefon)
            assertFalse(f.syncOffen)
        }
        // Beide Verlaufseinträge (Statuswechsel auf A und B) bleiben erhalten.
        val texte = a.verlauf(a.fall("Carl Demir").id).map { it.text }
        assertTrue(texte.toString(), texte.contains("Status: Neu → Bei Versicherung") && texte.contains("Status: Neu → In Prüfung"))
    }

    @Test
    fun `offline vergebene gleiche Fallnummern werden aufgeloest`() {
        val a = geraet(neuePin = true)
        val b = geraet()
        runBlocking { a.repo.anlegen(Schadensfall(kundeName = "Gerät A")) }
        runBlocking { b.repo.anlegen(Schadensfall(kundeName = "Gerät B")) }
        assertEquals("2026-001", b.fall("Gerät B").fallnummer)

        a.abgleichen()
        b.abgleichen()
        a.abgleichen()
        for (g in listOf(a, b)) {
            assertEquals("2026-001", g.fall("Gerät A").fallnummer)
            assertEquals("2026-002", g.fall("Gerät B").fallnummer)
        }
        // Der nächste Fall auf A bekommt eine freie Nummer.
        runBlocking { a.repo.anlegen(Schadensfall(kundeName = "Dritter")) }
        assertEquals("2026-003", a.fall("Dritter").fallnummer)
    }

    @Test
    fun `Loeschungen wirken auf allen Geraeten`() {
        val a = geraet(neuePin = true)
        val id = runBlocking { a.repo.anlegen(Schadensfall(kundeName = "Emil Frank")) }
        runBlocking { a.repo.anlegen(Schadensfall(kundeName = "Bleibt")) }
        runBlocking { a.repo.verlaufHinzufuegen(id, VerlaufTyp.NOTIZ, "Weg damit") }
        a.abgleichen()
        val b = geraet()
        b.abgleichen()

        val notiz = b.verlauf(b.fall("Emil Frank").id).first { it.text == "Weg damit" }
        runBlocking { b.repo.verlaufLoeschen(notiz.id) }
        b.abgleichen(); a.abgleichen()
        assertFalse(a.verlauf(id).any { it.text == "Weg damit" })

        runBlocking { a.repo.fallLoeschen(id) }
        a.abgleichen(); b.abgleichen()
        assertEquals(listOf("Bleibt"), b.faelle().map { it.kundeName })
        assertTrue(runBlocking { a.db.dao().loeschungen().isEmpty() })
    }

    @Test
    fun `Anlagen werden hoch- und auf dem anderen Geraet heruntergeladen`() {
        val a = geraet(neuePin = true)
        val id = runBlocking { a.repo.anlegen(Schadensfall(kundeName = "Anna Becker")) }
        val inhalt = ByteArray(70_000) { (it % 251).toByte() }
        val quelle = File(context.cacheDir, "Lieferschein.pdf").apply { writeBytes(inhalt) }
        runBlocking { a.repo.anlageHinzufuegen(id, Uri.fromFile(quelle)) }
        a.abgleichen()
        assertTrue(a.anlagen(id).single().inCloud)

        val b = geraet()
        b.abgleichen()
        val anlage = b.anlagen(b.fall("Anna Becker").id).single()
        assertEquals("Lieferschein.pdf", anlage.anzeigename)
        assertEquals("application/pdf", anlage.mimeType)
        assertTrue(anlage.istLokalVorhanden)
        assertTrue(inhalt.contentEquals(b.speicher.datei(anlage).readBytes()))

        runBlocking { b.repo.anlageLoeschen(anlage) }
        b.abgleichen(); a.abgleichen()
        assertTrue(a.anlagen(id).isEmpty())
        assertTrue(a.speicher.ordner(id).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `ohne Netz bleiben Aenderungen erhalten und gehen spaeter hoch`() {
        val a = geraet(neuePin = true)
        runBlocking { a.repo.anlegen(Schadensfall(kundeName = "Dora Engel")) }
        a.abgleichen()
        cloud.close() // Netz weg

        runBlocking { a.repo.statusAendern(a.fall("Dora Engel").id, FallStatus.ZAHLUNG_OFFEN) }
        assertThrows(IOException::class.java) { a.abgleichen() }
        assertTrue(a.fall("Dora Engel").syncOffen)
        assertTrue(a.zustand().fehler.isNotEmpty())
        assertTrue("PIN bleibt bei Netzproblemen erhalten", a.zustand().angemeldet)
    }

    @Test
    fun `ungueltige PIN wird vergessen und gemeldet, Daten bleiben`() {
        val a = geraet(neuePin = true)
        runBlocking { a.repo.anlegen(Schadensfall(kundeName = "Anna Becker")) }
        runBlocking { a.cloudSpeicher.verbinde(cloud.url, "veraltet") }

        assertThrows(CloudFehler::class.java) { a.abgleichen() }
        assertFalse(a.zustand().angemeldet)
        assertTrue(a.zustand().verbunden)
        assertEquals("Die PIN ist nicht mehr gültig. Bitte neu eingeben.", a.zustand().fehler)
        assertTrue(a.fall("Anna Becker").syncOffen)
        assertEquals("ohne PIN wird nicht abgeglichen", AbgleichErgebnis(0, 0), a.abgleichen())
    }

    @Test
    fun `vorhandene Faelle werden beim ersten Verbinden hochgeladen`() {
        val a = geraet(verbinden = false)
        runBlocking { a.repo.anlegen(Schadensfall(kundeName = "Schon da")) }
        runBlocking { a.abgleich.verbinde(cloud.url, pin, neuePin = true) }
        a.abgleichen()
        val b = geraet()
        b.abgleichen()
        assertEquals(listOf("Schon da"), b.faelle().map { it.kundeName })
    }

    @Test
    fun `Trennen behaelt die Daten auf dem Geraet`() {
        val a = geraet(neuePin = true)
        runBlocking { a.repo.anlegen(Schadensfall(kundeName = "Anna Becker")) }
        a.abgleichen()
        runBlocking { a.cloudSpeicher.trenne() }
        assertFalse(a.zustand().verbunden)
        assertEquals(1, a.faelle().size)
        assertNull(a.zustand().url.takeIf { it.isNotEmpty() })
    }
}
