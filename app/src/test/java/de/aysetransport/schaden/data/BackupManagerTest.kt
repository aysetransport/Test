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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SchadensfallDatenbank
    private lateinit var speicher: AnlagenSpeicher
    private lateinit var repository: SchadensfallRepository
    private lateinit var backup: BackupManager

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SchadensfallDatenbank::class.java).build()
        speicher = AnlagenSpeicher(context)
        repository = SchadensfallRepository(db, speicher)
        backup = BackupManager(db, speicher, context.cacheDir)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun legeBeispielAn(): Long {
        val id = repository.anlegen(
            Schadensfall(
                kundeName = "Anna Becker",
                beschreibung = "Sofa „Milano“: Naht gerissen",
                schadensdatum = LocalDate.of(2026, 11, 3),
                forderungCent = 45_000,
                status = FallStatus.BEI_VERSICHERUNG,
                wartetAuf = Ansprechpartner.VERSICHERUNG,
                wiedervorlageAm = LocalDate.of(2026, 11, 17),
            ),
        )
        val foto = File(context.cacheDir, "schaden.jpg").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
        repository.anlageHinzufuegen(id, Uri.fromFile(foto))
        repository.verlaufHinzufuegen(id, de.aysetransport.schaden.data.model.VerlaufTyp.NOTIZ, "Kundin angerufen")
        return id
    }

    private suspend fun exportiere(): ByteArray = ByteArrayOutputStream().also { backup.exportiere(it) }.toByteArray()

    private fun zip(vararg eintraege: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { ziel ->
        ZipOutputStream(ziel).use { zip ->
            for ((name, inhalt) in eintraege) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(inhalt)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun `Backup und Wiederherstellung liefern denselben Bestand samt Dateien`() = runBlocking {
        val id = legeBeispielAn()
        val fallVorher = repository.ladeFall(id)!!
        val verlaufVorher = repository.beobachteVerlauf(id).first()
        val anlageVorher = repository.beobachteAnlagen(id).first().single()
        val inhaltVorher = speicher.datei(anlageVorher).readBytes()

        val archiv = exportiere()

        // Bestand danach verändern: Der alte Fall verschwindet, ein fremder kommt hinzu.
        repository.fallLoeschen(id)
        repository.anlegen(Schadensfall(kundeName = "Soll verschwinden"))

        val ergebnis = backup.stelleWiederHer(ByteArrayInputStream(archiv))

        assertEquals(BackupErgebnis(faelle = 1, anlagen = 1), ergebnis)
        assertEquals(listOf(fallVorher), repository.ladeAlleFaelle())
        assertEquals(verlaufVorher, repository.beobachteVerlauf(id).first())
        assertEquals(listOf(anlageVorher), repository.beobachteAnlagen(id).first())
        assertTrue(inhaltVorher.contentEquals(speicher.datei(anlageVorher).readBytes()))
    }

    @Test
    fun `nach der Wiederherstellung laeuft die Nummernvergabe korrekt weiter`() = runBlocking {
        legeBeispielAn()
        val archiv = exportiere()
        backup.stelleWiederHer(ByteArrayInputStream(archiv))

        val neu = repository.anlegen(Schadensfall(kundeName = "Neu"))
        assertEquals(LocalDate.now().year.toString() + "-002", repository.ladeFall(neu)!!.fallnummer)
    }

    @Test
    fun `defekte Datei laesst den vorhandenen Bestand unangetastet`() = runBlocking {
        val id = legeBeispielAn()
        val anlage = repository.beobachteAnlagen(id).first().single()

        val keinZip = runCatching { backup.stelleWiederHer(ByteArrayInputStream("das ist kein Backup".toByteArray())) }
        val ohneDaten = runCatching { backup.stelleWiederHer(ByteArrayInputStream(zip("anderes.txt" to ByteArray(3)))) }
        val kaputtesJson = runCatching { backup.stelleWiederHer(ByteArrayInputStream(zip("daten.json" to "{ kaputt".toByteArray()))) }

        assertEquals(BackupManager.KEIN_BACKUP, (keinZip.exceptionOrNull() as BackupUngueltigException).message)
        assertEquals(BackupManager.KEIN_BACKUP, (ohneDaten.exceptionOrNull() as BackupUngueltigException).message)
        assertEquals(BackupManager.BESCHAEDIGT, (kaputtesJson.exceptionOrNull() as BackupUngueltigException).message)
        assertEquals(1, repository.ladeAlleFaelle().size)
        assertTrue(speicher.datei(anlage).isFile)
    }

    @Test
    fun `Backup einer neueren App-Version wird abgelehnt`() = runBlocking {
        legeBeispielAn()
        val neuer = zip("daten.json" to """{"version": 99, "faelle": []}""".toByteArray())

        val fehler = runCatching { backup.stelleWiederHer(ByteArrayInputStream(neuer)) }.exceptionOrNull()

        assertTrue(fehler is BackupUngueltigException)
        assertTrue("neueren App-Version" in fehler!!.message!!)
        assertEquals(1, repository.ladeAlleFaelle().size)
    }

    @Test
    fun `Archiv-Eintraege koennen nicht aus dem Anlagen-Ordner ausbrechen`() = runBlocking {
        val boese = zip(
            "daten.json" to """{"version": 1, "faelle": []}""".toByteArray(),
            "anlagen/../../ausbruch.txt" to "x".toByteArray(),
            "anlagen/1/../../ausbruch2.txt" to "x".toByteArray(),
            "anlagen/1/unter/ordner.txt" to "x".toByteArray(),
            "anlagen/abc/datei.txt" to "x".toByteArray(),
            "anlagen/1/erlaubt.txt" to "ok".toByteArray(),
        )

        backup.stelleWiederHer(ByteArrayInputStream(boese))

        val alleDateien = context.filesDir.parentFile!!.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertFalse("ausbruch.txt" in alleDateien)
        assertFalse("ausbruch2.txt" in alleDateien)
        assertFalse("ordner.txt" in alleDateien)
        assertFalse("datei.txt" in alleDateien)
        assertEquals("ok", File(speicher.wurzel, "1/erlaubt.txt").readText())
    }

    @Test
    fun `leerer Bestand laesst sich sichern und wiederherstellen`() = runBlocking {
        val archiv = exportiere()
        legeBeispielAn()

        val ergebnis = backup.stelleWiederHer(ByteArrayInputStream(archiv))

        assertEquals(BackupErgebnis(0, 0), ergebnis)
        assertTrue(repository.ladeAlleFaelle().isEmpty())
        assertTrue(speicher.wurzel.walkTopDown().none { it.isFile })
    }
}
