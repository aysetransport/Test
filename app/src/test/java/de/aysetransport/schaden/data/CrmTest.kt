package de.aysetransport.schaden.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.AdressArt
import de.aysetransport.schaden.data.model.Adresse
import de.aysetransport.schaden.data.model.Aktivitaet
import de.aysetransport.schaden.data.model.AktivitaetArt
import de.aysetransport.schaden.data.model.Aufgabe
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.Dokument
import de.aysetransport.schaden.data.model.FirmenStatus
import de.aysetransport.schaden.data.model.Kontaktperson
import de.aysetransport.schaden.data.model.Loeschung
import de.aysetransport.schaden.data.model.MailNachricht
import de.aysetransport.schaden.data.model.Preis
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.domain.Crm
import de.aysetransport.schaden.domain.CrmBestand
import de.aysetransport.schaden.domain.CsvExport
import de.aysetransport.schaden.domain.Termin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class CrmTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SchadensfallDatenbank
    private lateinit var repository: SchadensfallRepository
    private lateinit var crm: CrmRepository
    private val heute = LocalDate.of(2026, 11, 17)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SchadensfallDatenbank::class.java).build()
        repository = SchadensfallRepository(db, AnlagenSpeicher(context))
        crm = CrmRepository(db)
    }

    @After
    fun tearDown() = db.close()

    /** Aufbau wie in der JSON-Sicherung des Browser-CRM. */
    private val sicherung = """
        {"version":1,"firmen":[
          {"id":"f1","name":"Seats and Sofas Krefeld","rechtsform":"GmbH","status":"aktiv","telefon":"02151 123","email":"info@seats.example",
           "website":"www.seats.example","notizen":"Rahmenvertrag bis 2027",
           "adressen":[{"id":"a1","art":"Lager","strasse":"Industriestr. 5","plz":"47803","ort":"Krefeld","notiz":"Rampe 3"}],
           "kontakte":[{"id":"k1","name":"Jana Krüger","thema":"Disposition","telefon":"02151 124","email":"jana@seats.example","notiz":""},
                       {"id":"k2","name":"Tim Berg","thema":"Schäden","telefon":"","email":"schaden@seats.example","notiz":""}],
           "preise":[{"id":"p1","bez":"Tourpauschale","betrag":189.5,"einheit":"pro Tour","ab":"2026-01-01","notiz":""},
                     {"id":"p2","bez":"Etage","betrag":"12,50","einheit":"pro Etage","ab":"","notiz":"ab 3. OG"}],
           "aktivitaeten":[{"id":"x1","datum":"2026-11-02","art":"Anruf","kontakt":"Jana Krüger","text":"Preise für 2027 besprochen"}],
           "aufgaben":[{"id":"t1","faellig":"2026-11-20","text":"Angebot schicken","kontakt":"Jana Krüger","erledigt":false},
                       {"id":"t2","faellig":"2026-10-01","text":"Alt","kontakt":"","erledigt":true}],
           "dokumente":[{"id":"d1","titel":"Rahmenvertrag","link":"https://drive.example/vertrag","notiz":""}]},
          {"id":"f2","name":"Neue Möbel AG","status":"interessent","kontakte":[],"adressen":[],"preise":[],"aktivitaeten":[],"aufgaben":[],"dokumente":[]}
        ]}
    """.trimIndent()

    @Test
    fun `Import der Browser-Sicherung fuehrt gleichnamige Firmen zusammen und laesst sich wiederholen`() = runBlocking {
        // Seats and Sofas gibt es schon aus einem Schadensfall – mit Tim Berg als Ansprechpartner.
        val fallId = repository.anlegen(Schadensfall(kundeName = "Anna Becker", auftraggeber = "seats and sofas krefeld", auftraggeberKontakt = "Tim Berg", auftraggeberTelefon = "02151 999"))
        val ergebnis = CrmImport(db).importiere(sicherung)

        assertEquals(ImportErgebnis(neueFirmen = 1, zusammengefuehrt = 1, eintraege = 8), ergebnis)
        val firmen = crm.ladeFirmen()
        assertEquals(listOf("Neue Möbel AG", "seats and sofas krefeld"), firmen.map { it.name })
        val seats = firmen.single { it.name.startsWith("seats") }
        assertEquals(listOf("GmbH", "02151 123", "www.seats.example", "Rahmenvertrag bis 2027"), listOf(seats.rechtsform, seats.telefon, seats.website, seats.notizen))
        assertEquals(FirmenStatus.INTERESSENT, firmen.single { it.name == "Neue Möbel AG" }.status)
        assertEquals(seats.uuid, repository.ladeFall(fallId)!!.auftraggeberUuid)

        val kontakte = crm.ladeKontakte(seats.uuid)
        assertEquals(listOf("Jana Krüger", "Tim Berg"), kontakte.map { it.name })
        assertEquals("02151 999", kontakte.single { it.name == "Tim Berg" }.telefon) // vorhandener bleibt, nichts doppelt
        val d = crm.alleDaten()
        assertEquals(listOf(18950L, 1250L), d.preise.sortedBy { it.bezeichnung }.reversed().map { it.betragCent })
        assertEquals(LocalDate.of(2026, 1, 1), d.preise.single { it.bezeichnung == "Tourpauschale" }.gueltigAb)
        assertEquals(AdressArt.LAGER, d.adressen.single().art)
        assertEquals(AktivitaetArt.ANRUF, d.aktivitaeten.single().art)
        assertEquals(listOf(false, true), d.aufgaben.sortedBy { it.erledigt }.map { it.erledigt })
        assertEquals("https://drive.example/vertrag", d.dokumente.single().link)

        // Ein zweiter Import ändert nichts mehr.
        assertEquals(ImportErgebnis(0, 2, 0), CrmImport(db).importiere(sicherung))
        assertEquals(2, crm.ladeFirmen().size)
        assertThrows(ImportUngueltigException::class.java) { runBlocking { CrmImport(db).importiere("""{"faelle":[]}""") } }
        Unit
    }

    @Test
    fun `doppelte Firmen von zwei Geraeten werden zusammengefuehrt`() = runBlocking {
        val a = AuftraggeberEintrag(uuid = "aaaaaaaa-0000-4000-8000-000000000001", name = "Media Markt", telefon = "02191 1")
        val b = AuftraggeberEintrag(uuid = "bbbbbbbb-0000-4000-8000-000000000001", name = "media markt ", website = "mediamarkt.example", notizen = "Filiale Remscheid")
        db.dao().speichereAuftraggeber(a); db.dao().speichereAuftraggeber(b)
        crm.speichern(Kontaktperson(auftraggeberUuid = b.uuid, name = "Herr Ott"))
        crm.speichern(Kontaktperson(auftraggeberUuid = a.uuid, name = "herr ott", email = "ott@mm.example"))
        db.crm().speichereMail(MailNachricht(auftraggeberUuid = b.uuid, eingehend = true, messageId = "m1", von = "x@mm.example", an = "", betreff = "", text = "", zeitpunkt = Instant.EPOCH))

        assertEquals(1, crm.aufraeumen())

        val firma = crm.ladeFirmen().single()
        assertEquals(a.uuid, firma.uuid)
        assertEquals(listOf("02191 1", "mediamarkt.example", "Filiale Remscheid"), listOf(firma.telefon, firma.website, firma.notizen))
        assertEquals("gleichnamige Ansprechpartner nur einmal", 1, crm.ladeKontakte(a.uuid).size)
        assertEquals("ott@mm.example", crm.ladeKontakte(a.uuid).single().email)
        assertEquals(a.uuid, db.crm().ladeAlleMails().single().auftraggeberUuid)
        assertTrue(db.dao().loeschungen().any { it.art == Loeschung.Art.AUFTRAGGEBER && it.uuid == b.uuid })
    }

    @Test
    fun `Firma loeschen nimmt alles mit, was an ihr haengt, der Fall bleibt`() = runBlocking {
        val id = crm.firmaSpeichern(AuftraggeberEintrag(name = "Meda Küchen"))
        crm.speichern(Adresse(auftraggeberUuid = id, ort = "Remscheid"))
        crm.speichern(Preis(auftraggeberUuid = id, bezeichnung = "Montage", betragCent = 4500))
        crm.speichern(Aufgabe(auftraggeberUuid = id, text = "Anrufen", faelligAm = heute))
        crm.speichern(Dokument(auftraggeberUuid = id, titel = "Vertrag"))
        crm.speichern(Aktivitaet(auftraggeberUuid = id, datum = heute, text = "Treffen"))
        val fallId = repository.anlegen(Schadensfall(kundeName = "Carl", auftraggeber = "Meda Küchen"))
        assertEquals("gleicher Name: keine zweite Firma", id, crm.firmaSpeichern(AuftraggeberEintrag(name = "MEDA KÜCHEN")))

        crm.firmaLoeschen(crm.ladeFirma(id)!!)

        val d = crm.alleDaten()
        assertTrue(listOf(d.adressen, d.preise, d.aufgaben, d.dokumente, d.aktivitaeten, d.kontakte).all { it.isEmpty() })
        assertEquals("Meda Küchen", repository.ladeFall(fallId)!!.auftraggeber)
        assertNull(repository.ladeFall(fallId)!!.auftraggeberUuid)
    }

    @Test
    fun `Backup enthaelt das CRM, ein Backup aus Version 1 bildet die Firmen aus den Faellen`() = runBlocking {
        val id = crm.firmaSpeichern(AuftraggeberEintrag(name = "Seats and Sofas", status = FirmenStatus.PAUSIERT))
        crm.speichern(Preis(auftraggeberUuid = id, bezeichnung = "Maut", betragCent = 1990, gueltigAb = heute))
        crm.speichern(Aufgabe(auftraggeberUuid = id, text = "Rückruf", faelligAm = heute))
        db.crm().speichereMail(MailNachricht(eingehend = true, messageId = "offen@x", von = "a@b.example", an = "", betreff = "Ohne Fall", text = "", zeitpunkt = Instant.EPOCH))
        val backup = BackupManager(db, AnlagenSpeicher(context), context.cacheDir)
        val zip = ByteArrayOutputStream().also { backup.exportiere(it) }.toByteArray()

        crm.firmaLoeschen(crm.ladeFirma(id)!!)
        val ergebnis = backup.stelleWiederHer(ByteArrayInputStream(zip))
        assertEquals(1, ergebnis.firmen)
        val firma = crm.ladeFirmen().single()
        assertEquals(FirmenStatus.PAUSIERT, firma.status)
        assertEquals(1990L, crm.alleDaten().preise.single().betragCent)
        assertEquals("Rückruf", crm.alleDaten().aufgaben.single().text)
        assertEquals("Ohne Fall", db.crm().ladeAlleMails().single().betreff)

        // Backup aus App 1.4 (Format 1, ohne CRM): Firmen und Ansprechpartner entstehen aus den Fällen.
        val alt = """{"version":1,"faelle":[{"id":7,"uuid":"fall-7","fallnummer":"2026-007","kundeName":"Anna","auftraggeber":"Möbelhaus Nordpark",
            "auftraggeberKontakt":"Frau Kaya","auftraggeberEmail":"dispo@nordpark.example","status":"NEU","erstelltAm":"2026-11-01T08:00:00Z","geaendertAm":"2026-11-01T08:00:00Z"}]}"""
        val altZip = ByteArrayOutputStream().also { z -> ZipOutputStream(z).use { it.putNextEntry(ZipEntry("daten.json")); it.write(alt.toByteArray()); it.closeEntry() } }.toByteArray()
        backup.stelleWiederHer(ByteArrayInputStream(altZip))
        val nordpark = crm.ladeFirmen().single()
        assertEquals("Möbelhaus Nordpark", nordpark.name)
        assertEquals(nordpark.uuid, repository.ladeAlleFaelle().single().auftraggeberUuid)
        assertEquals(listOf("Frau Kaya" to "dispo@nordpark.example"), crm.ladeKontakte(nordpark.uuid).map { it.name to it.email })
    }

    @Test
    fun `Wiedervorlagen vereinen CRM-Aufgaben und Faelle, Suche findet Ansprechpartner und Faelle`() {
        val seats = AuftraggeberEintrag(uuid = "s", name = "Seats and Sofas")
        val media = AuftraggeberEintrag(uuid = "m", name = "Media Markt", status = FirmenStatus.INTERESSENT)
        val b = CrmBestand(
            firmen = listOf(seats, media),
            kontakte = listOf(Kontaktperson(auftraggeberUuid = "m", name = "Jana Krüger", thema = "Abrechnung")),
            offeneAufgaben = listOf(
                Aufgabe(auftraggeberUuid = "s", text = "Angebot", faelligAm = heute.minusDays(2)),
                Aufgabe(auftraggeberUuid = "m", text = "Erstgespräch", faelligAm = heute.plusDays(3)),
                Aufgabe(auftraggeberUuid = "m", text = "Irgendwann"),
            ),
            faelle = listOf(
                Schadensfall(id = 1, fallnummer = "2026-001", kundeName = "Anna", auftraggeber = "seats and sofas", wiedervorlageAm = heute, beschreibung = "Glastisch zerkratzt"),
                Schadensfall(id = 2, fallnummer = "2026-002", kundeName = "Bert", wiedervorlageAm = heute.minusDays(1), status = de.aysetransport.schaden.data.model.FallStatus.ERLEDIGT),
            ),
        )
        val w = Crm.wiedervorlagen(b, heute)
        assertEquals(listOf("Angebot"), w.ueberfaellig.map { it.titel })
        assertEquals(listOf("Fall 2026-001 · Anna"), w.heute.map { it.titel })
        assertEquals(listOf("Erstgespräch"), w.demnaechst.map { it.titel })
        assertEquals(listOf("Irgendwann"), w.ohneDatum.map { it.text })

        val liste = Crm.firmenliste(b, null, "", heute)
        assertEquals(listOf("Media Markt", "Seats and Sofas"), liste.map { it.firma.name })
        assertEquals("Angebot", (liste.last().naechsterTermin as Termin.CrmAufgabe).aufgabe.text)
        assertEquals("Fall über den Namen verknüpft", 1, liste.last().offeneFaelle)
        assertEquals(listOf("Media Markt"), Crm.firmenliste(b, FirmenStatus.INTERESSENT, "", heute).map { it.firma.name })
        assertEquals("Ansprechpartner: Jana Krüger", Crm.firmenliste(b, null, "krüger", heute).single().treffer)
        assertEquals("Fall 2026-001", Crm.firmenliste(b, null, "glastisch", heute).single().treffer)
    }

    @Test
    fun `CSV-Export des CRM mit Semikolon und BOM`() {
        val f = AuftraggeberEintrag(uuid = "s", name = "Seats; Sofas", status = FirmenStatus.AKTIV)
        val csv = CsvExport.preise(listOf(f), listOf(Preis(auftraggeberUuid = "s", bezeichnung = "Tourpauschale", betragCent = 18950, einheit = "pro Tour", gueltigAb = heute)))
        assertTrue(csv.startsWith("﻿Firma;Bezeichnung;Betrag (EUR);Einheit;Gültig ab;Notiz\r\n"))
        assertTrue(csv, csv.contains("\"Seats; Sofas\";Tourpauschale;189,50;pro Tour;17.11.2026;\r\n"))
        val firmen = CsvExport.firmen(listOf(f), listOf(Adresse(auftraggeberUuid = "s", art = AdressArt.HAUPTSITZ, strasse = "Weg 1", plz = "42853", ort = "Remscheid")))
        assertTrue(firmen, firmen.contains("Aktiv;;;;Weg 1, 42853 Remscheid;"))
        val aufgaben = CsvExport.wiedervorlagen(listOf(f), listOf(Aufgabe(auftraggeberUuid = "s", text = "=SUMME()", faelligAm = heute)))
        assertTrue("Formeln werden entschärft", aufgaben.contains("'=SUMME()"))
    }

    @Test
    fun `letzter Kontakt, lange kein Kontakt und Preisliste zum Teilen`() {
        val aktiv = AuftraggeberEintrag(uuid = "a", name = "Aktiv GmbH")
        val ruhig = AuftraggeberEintrag(uuid = "r", name = "Ruhig AG")
        val interessent = AuftraggeberEintrag(uuid = "i", name = "Neu KG", status = FirmenStatus.INTERESSENT)
        val b = CrmBestand(
            firmen = listOf(aktiv, ruhig, interessent),
            aktivitaeten = listOf(
                Aktivitaet(auftraggeberUuid = "a", datum = heute.minusDays(5), text = "Anruf"),
                Aktivitaet(auftraggeberUuid = "a", datum = heute.minusDays(90), text = "alt"),
                Aktivitaet(auftraggeberUuid = "r", datum = heute.minusDays(61), text = "Treffen"),
            ),
        )
        val liste = Crm.firmenliste(b, null, "", heute)
        assertEquals(heute.minusDays(5), liste.single { it.firma.uuid == "a" }.letzterKontakt)
        assertEquals("nur aktive Firmen ohne Kontakt seit 60 Tagen", listOf("Ruhig AG"), liste.filter { Crm.langeKeinKontakt(it, heute) }.map { it.firma.name })

        val text = Crm.preislisteText(aktiv.copy(name = "Aktiv", rechtsform = "GmbH"), listOf(
            Preis(auftraggeberUuid = "a", bezeichnung = "Tourpauschale", betragCent = 18950, einheit = "pro Tour", gueltigAb = LocalDate.of(2026, 1, 1)),
            Preis(auftraggeberUuid = "a", bezeichnung = "Etage", betragCent = 1250, einheit = "pro Etage", notiz = "ab 3. OG"),
        ))
        assertEquals("Preise und Konditionen – Aktiv GmbH\n• Etage: 12,50\u00A0€ pro Etage – ab 3. OG\n• Tourpauschale: 189,50\u00A0€ pro Tour (gültig ab 01.01.2026)", text)
    }

    @Test
    fun `Kennzahlen, Schlagworte und Angebots-Vorlage mit Preisliste`() {
        val faelle = listOf(
            Schadensfall(id = 1, forderungCent = 45000, vergleichCent = 20000, status = de.aysetransport.schaden.data.model.FallStatus.ERLEDIGT),
            Schadensfall(id = 2, forderungCent = 12000),
        )
        val chancen = listOf(
            de.aysetransport.schaden.data.model.Chance(auftraggeberUuid = "a", titel = "Neue Filiale", wertCent = 500000),
            de.aysetransport.schaden.data.model.Chance(auftraggeberUuid = "a", titel = "Montage", wertCent = 100000, phase = de.aysetransport.schaden.data.model.ChancenPhase.GEWONNEN),
        )
        val k = Crm.kennzahlen(faelle, chancen, heute)
        assertEquals("2 1 12000 20000 1 500000 100000", listOf(k.faelleGesamt, k.faelleOffen, k.forderungenOffenCent, k.vergleicheCent, k.chancenOffen, k.chancenWertCent, k.gewonnenWertCent).joinToString(" "))

        val firmen = listOf(AuftraggeberEintrag(name = "A", schlagworte = "Möbel, montage"), AuftraggeberEintrag(name = "B", schlagworte = "Elektro,  Montage ,"))
        assertEquals(listOf("Elektro", "Möbel", "montage"), Crm.schlagworte(firmen))

        val n = de.aysetransport.schaden.domain.Textvorlagen.firmenVorlage(
            de.aysetransport.schaden.domain.FirmenVorlage.ANGEBOT, "Jana Krüger", de.aysetransport.schaden.domain.Absender(firma = "Ayse Transport UG", name = "Niclas"), "• Montage: 45,00 €",
        )
        assertEquals("Angebot Auslieferung und Montage", n.betreff)
        assertTrue(n.text, n.text.startsWith("Guten Tag Jana Krüger,") && "• Montage: 45,00 €" in n.text && n.text.endsWith("Ayse Transport UG"))
    }

    @Test
    fun `Verkaufschancen und Schlagworte im Backup und beim Zusammenfuehren`() = runBlocking {
        val a = crm.firmaSpeichern(AuftraggeberEintrag(name = "Doppelt", schlagworte = "Möbel"))
        val b = crm.firmaSpeichern(AuftraggeberEintrag(name = "Richtig", schlagworte = "möbel, Küchen"))
        crm.speichern(de.aysetransport.schaden.data.model.Chance(auftraggeberUuid = a, titel = "Mehr Touren", wertCent = 250000, erwartetAm = heute))
        crm.zusammenfuehren(crm.ladeFirma(a)!!, crm.ladeFirma(b)!!)
        val firma = crm.ladeFirmen().single()
        assertEquals("möbel, Küchen", firma.schlagworte)
        assertEquals(listOf("Mehr Touren"), crm.alleDaten().chancen.filter { it.auftraggeberUuid == b }.map { it.titel })

        val backup = BackupManager(db, AnlagenSpeicher(context), context.cacheDir)
        val zip = ByteArrayOutputStream().also { backup.exportiere(it) }.toByteArray()
        crm.firmaLoeschen(firma)
        assertTrue(crm.alleDaten().chancen.isEmpty())
        backup.stelleWiederHer(ByteArrayInputStream(zip))
        assertEquals(250000L, crm.alleDaten().chancen.single().wertCent)
    }

    @Test
    fun `Firmen von Hand zusammenfuehren`() = runBlocking {
        val doppelt = crm.firmaSpeichern(AuftraggeberEintrag(name = "MediaMarkt RS"))
        val richtig = crm.firmaSpeichern(AuftraggeberEintrag(name = "Media Markt Remscheid"))
        crm.speichern(Kontaktperson(auftraggeberUuid = doppelt, name = "Herr Ott"))
        val fallId = repository.anlegen(Schadensfall(kundeName = "Anna", auftraggeber = "MediaMarkt RS"))

        crm.zusammenfuehren(crm.ladeFirma(doppelt)!!, crm.ladeFirma(richtig)!!)

        assertEquals(listOf("Media Markt Remscheid"), crm.ladeFirmen().map { it.name })
        assertEquals(listOf("Herr Ott"), crm.ladeKontakte(richtig).map { it.name })
        val fall = repository.ladeFall(fallId)!!
        assertEquals(richtig, fall.auftraggeberUuid)
        assertEquals("Name im Fall wird angepasst", "Media Markt Remscheid", fall.auftraggeber)
    }
}
