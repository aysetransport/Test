package de.aysetransport.schaden.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Wer die App schon benutzt, behält beim Update auf die Cloud-Version alle Fälle. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helfer = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SchadensfallDatenbank::class.java)

    @Test
    fun `Version 1 nach 2 behaelt alle Daten und vergibt eindeutige Kennungen`() {
        helfer.createDatabase("migration.db", 1).use { db ->
            db.execSQL(
                "INSERT INTO schadensfall (id, fallnummer, kundeName, kundeFirma, kundeTelefon, kundeEmail, kundeAnschrift, kundenAktenzeichen, " +
                    "auftraggeber, auftragsReferenz, tour, fahrzeug, fahrer, schadensort, schadensart, beschreibung, status, wiedervorlageNotiz, " +
                    "versicherungsNummer, erstelltAm, geaendertAm, forderungCent) VALUES " +
                    "(1, '2026-001', 'Anna Becker', '', '', '', '', '', '', '', 'Tour 3', '', '', '', 'WARE', 'Sofa', 'NEU', '', '', 1, 2, 45000), " +
                    "(2, '2026-002', 'Carl Demir', '', '', '', '', '', '', '', '', '', '', '', 'WARE', '', 'ERLEDIGT', '', '', 1, 2, NULL)",
            )
            db.execSQL("INSERT INTO verlauf (id, fallId, zeitpunkt, typ, text) VALUES (1, 1, 5, 'NOTIZ', 'Kundin angerufen'), (2, 2, 6, 'ANGELEGT', 'x')")
            db.execSQL("INSERT INTO anlage (id, fallId, dateiname, anzeigename, mimeType, groesseBytes, hinzugefuegtAm) VALUES (1, 1, 'a.jpg', 'Foto.jpg', 'image/jpeg', 10, 7)")
        }

        val db = helfer.runMigrationsAndValidate("migration.db", 2, true, SchadensfallDatenbank.MIGRATION_1_2)

        db.query("SELECT kundeName, tour, forderungCent, syncOffen, uuid, beifahrer1, beifahrer2 FROM schadensfall ORDER BY id").use { c ->
            c.moveToFirst()
            assertEquals("", c.getString(5))
            assertEquals("", c.getString(6))
            assertEquals("Anna Becker", c.getString(0))
            assertEquals("Tour 3", c.getString(1))
            assertEquals(45000L, c.getLong(2))
            assertEquals("bestehende Fälle werden beim ersten Verbinden hochgeladen", 1, c.getInt(3))
        }
        val kennungen = buildList {
            for (tabelle in listOf("schadensfall", "verlauf", "anlage")) {
                db.query("SELECT uuid FROM $tabelle").use { c -> while (c.moveToNext()) add(c.getString(0)) }
            }
        }
        assertEquals(5, kennungen.size)
        assertEquals("Kennungen sind eindeutig", 5, kennungen.toSet().size)
        val uuidMuster = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue(kennungen.toString(), kennungen.all { uuidMuster.matches(it) })
        db.query("SELECT inCloud, dateiname FROM anlage").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
            assertEquals("a.jpg", c.getString(1))
        }
        db.query("SELECT COUNT(*) FROM loeschung").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }

    @Test
    fun `Version 2 nach 3 bildet die Auftraggeber-Liste aus den vorhandenen Faellen`() {
        helfer.createDatabase("migration3.db", 2).use { db ->
            fun fall(id: Int, ag: String, zeit: Int) = db.execSQL(
                "INSERT INTO schadensfall (id, uuid, syncOffen, fallnummer, kundeName, kundeFirma, kundeTelefon, kundeEmail, kundeAnschrift, " +
                    "kundenAktenzeichen, auftraggeber, auftragsReferenz, tour, fahrzeug, fahrer, beifahrer1, beifahrer2, schadensort, schadensart, " +
                    "beschreibung, status, wiedervorlageNotiz, versicherungsNummer, erstelltAm, geaendertAm) VALUES " +
                    "($id, 'u$id', 0, '2026-00$id', 'Kunde', '', '', '', '', '', '$ag', '', '', '', '', '', '', '', 'WARE', '', 'NEU', '', '', 1, $zeit)",
            )
            fall(1, "Möbelhaus Nordpark", 5)
            fall(2, " möbelhaus nordpark ", 9)
            fall(3, "Küchenstudio Süd", 3)
            fall(4, "", 4)
        }
        val db = helfer.runMigrationsAndValidate("migration3.db", 3, true, SchadensfallDatenbank.MIGRATION_2_3)
        val namen = buildList { db.query("SELECT name, syncOffen FROM auftraggeber ORDER BY name").use { c -> while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }
        // Gleiche Firma nur einmal (die zuletzt bearbeitete Schreibweise), leere Angaben fallen weg.
        assertEquals(listOf("Küchenstudio Süd" to 1, "möbelhaus nordpark" to 1), namen)
        db.query("SELECT auftraggeberKontakt, auftraggeberTelefon, auftraggeberEmail FROM schadensfall WHERE id = 1").use { c ->
            c.moveToFirst(); assertEquals("", c.getString(0)); assertEquals("", c.getString(1)); assertEquals("", c.getString(2))
        }
    }

    @Test
    fun `Version 5 nach 6 macht aus der Auftraggeber-Liste das CRM ohne Datenverlust`() {
        helfer.createDatabase("migration6.db", 5).use { db ->
            // Auftraggeber-Liste wie in App 1.4: eine Firma mit Ansprechpartner, eine ohne
            db.execSQL(
                "INSERT INTO auftraggeber (uuid, name, kontakt, telefon, email, geaendertAm, syncOffen) VALUES " +
                    "('ag-seats', 'Seats and Sofas Krefeld', 'Jana Krüger', '02151 123', 'dispo@seats.example', 100, 0), " +
                    "('ag-media', 'Media Markt', '', '', '', 100, 0)",
            )
            fun fall(id: Int, ag: String, kontakt: String, tel: String, mail: String, status: String = "NEU") = db.execSQL(
                "INSERT INTO schadensfall (id, uuid, syncOffen, fallnummer, kundeName, kundeFirma, kundeTelefon, kundeEmail, kundeAnschrift, " +
                    "kundenAktenzeichen, auftraggeber, auftraggeberKontakt, auftraggeberTelefon, auftraggeberEmail, auftraggeberZeichen, " +
                    "auftragsReferenz, tour, fahrzeug, fahrer, beifahrer1, beifahrer2, schadensort, schadensart, beschreibung, forderungCent, status, " +
                    "wiedervorlageAm, wiedervorlageNotiz, versicherungsNummer, erstelltAm, geaendertAm) VALUES " +
                    "($id, 'fall-$id', 0, '2026-00$id', 'Kunde $id', '', '0171 $id', 'kunde$id@example.org', 'Hauptstr. $id', '', '$ag', '$kontakt', '$tel', '$mail', " +
                    "'RK-$id', 'LS-$id', 'Tour $id', 'RS-AT $id', 'Fahrer', '', '', '', 'WARE', 'Sofa $id', ${id * 10000}, '$status', 20500, 'nachhaken', '', 1, ${1000 + id})",
            )
            fall(1, "Seats and Sofas Krefeld", "Jana Krüger", "", "jana@seats.example") // bekannter Kontakt, neue E-Mail
            fall(2, " seats and sofas KREFELD ", "Tim Berg", "02151 456", "", "ERLEDIGT") // andere Schreibweise, neuer Kontakt
            fall(3, "Meda Küchen", "", "", "reklamation@meda.example") // Firma fehlt noch, Kontakt nur mit E-Mail
            fall(4, "", "", "", "") // ohne Auftraggeber
            db.execSQL("INSERT INTO verlauf (id, uuid, syncOffen, fallId, zeitpunkt, typ, text) VALUES (1, 'v1', 0, 1, 5, 'EMAIL_EINGANG', 'Von: kunde')")
            db.execSQL("INSERT INTO anlage (id, uuid, syncOffen, inCloud, fallId, dateiname, anzeigename, mimeType, groesseBytes, hinzugefuegtAm) VALUES (1, 'a1', 0, 1, 1, 'f.jpg', 'Foto.jpg', 'image/jpeg', 10, 7)")
            db.execSQL(
                "INSERT INTO mail (id, fallId, eingehend, messageId, antwortAuf, von, an, betreff, text, zeitpunkt, verlaufUuid) VALUES " +
                    "(1, 1, 1, 'm1@example', '', 'kunde1@example.org', 'schaden@ayse.example', 'Re: [Fall 2026-001]', 'Hallo', 5, 'v1')",
            )
        }

        val db = helfer.runMigrationsAndValidate("migration6.db", 6, true, SchadensfallDatenbank.MIGRATION_5_6)

        // Firmen: die beiden bisherigen behalten Kennung und Daten, Meda Küchen kommt aus dem Fall dazu.
        val firmen = buildList {
            db.query("SELECT uuid, name, status, telefon, email, rechtsform, website, notizen, syncOffen FROM auftraggeber ORDER BY name").use { c ->
                while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getInt(8).toString()))
            }
        }
        assertEquals(3, firmen.size)
        assertEquals(listOf("ag-media", "Media Markt", "AKTIV", "", "", "", "", "", "1"), firmen[1])
        assertEquals(listOf("ag-seats", "Seats and Sofas Krefeld", "AKTIV", "02151 123", "dispo@seats.example", "", "", "", "1"), firmen[2])
        assertEquals("Meda Küchen", firmen[0][1])
        val meda = firmen[0][0]

        // Fälle zeigen auf ihre Firma, alle Freitextfelder bleiben unverändert.
        db.query("SELECT id, auftraggeberUuid, auftraggeber, auftraggeberKontakt, auftraggeberEmail, kundeName, forderungCent, status, syncOffen, geaendertAm, wiedervorlageAm FROM schadensfall ORDER BY id").use { c ->
            c.moveToNext(); assertEquals("ag-seats", c.getString(1)); assertEquals("Jana Krüger", c.getString(3)); assertEquals("jana@seats.example", c.getString(4))
            assertEquals("Kunde 1", c.getString(5)); assertEquals(10000L, c.getLong(6)); assertEquals(20500L, c.getLong(10))
            assertEquals("geht beim nächsten Abgleich hoch", 1, c.getInt(8)); assertEquals("gilt als neuerer Stand", 1002L, c.getLong(9))
            c.moveToNext(); assertEquals("ag-seats", c.getString(1)); assertEquals(" seats and sofas KREFELD ", c.getString(2)); assertEquals("ERLEDIGT", c.getString(7))
            c.moveToNext(); assertEquals(meda, c.getString(1))
            c.moveToNext(); assertTrue("ohne Auftraggeber kein Verweis", c.isNull(1)); assertEquals(0, c.getInt(8))
        }

        // Ansprechpartner: aus der Liste und aus den Fällen, ohne Doppelungen
        val kontakte = buildList {
            db.query("SELECT a.name, a.thema, a.telefon, a.email, f.name FROM ansprechpartner a JOIN auftraggeber f ON f.uuid = a.auftraggeberUuid ORDER BY a.name COLLATE NOCASE").use { c ->
                while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)))
            }
        }
        assertEquals(
            listOf(
                listOf("Jana Krüger", "", "02151 123", "dispo@seats.example", "Seats and Sofas Krefeld"),
                listOf("reklamation@meda.example", "Schäden", "", "reklamation@meda.example", "Meda Küchen"),
                listOf("Tim Berg", "Schäden", "02151 456", "", "Seats and Sofas Krefeld"),
            ),
            kontakte,
        )

        // Verlauf, Anlagen und Mails bleiben; Mails liefen bisher alle über das Schadens-Postfach.
        db.query("SELECT COUNT(*) FROM verlauf").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT dateiname, inCloud FROM anlage").use { c -> c.moveToFirst(); assertEquals("f.jpg", c.getString(0)); assertEquals(1, c.getInt(1)) }
        db.query("SELECT fallId, auftraggeberUuid, kundeRef, konto, messageId, verlaufUuid, aktivitaetUuid, anhaenge FROM mail").use { c ->
            c.moveToFirst()
            assertEquals(1L, c.getLong(0)); assertTrue(c.isNull(1)); assertEquals("", c.getString(2)); assertEquals("SCHADEN", c.getString(3))
            assertEquals("m1@example", c.getString(4)); assertEquals("v1", c.getString(5)); assertEquals("", c.getString(6))
        }
        for (tabelle in listOf("adresse", "preis", "aktivitaet", "aufgabe", "dokument")) {
            db.query("SELECT COUNT(*) FROM $tabelle").use { c -> c.moveToFirst(); assertEquals(tabelle, 0, c.getInt(0)) }
        }
        // Eine Mail ohne Fall ist jetzt erlaubt.
        db.execSQL("INSERT INTO mail (fallId, eingehend, messageId, antwortAuf, von, an, betreff, text, zeitpunkt, verlaufUuid) VALUES (NULL, 1, 'm2', '', 'x@y.de', '', 'Angebot', '', 9, '')")
        db.close()
    }

    @Test
    fun `Version 6 nach 7 behaelt die Firmen und ergaenzt Schlagworte und Verkaufschancen`() {
        helfer.createDatabase("migration7.db", 6).use { db ->
            db.execSQL(
                "INSERT INTO auftraggeber (uuid, name, rechtsform, status, telefon, email, website, notizen, geaendertAm, syncOffen) " +
                    "VALUES ('f1', 'Seats and Sofas', 'GmbH', 'AKTIV', '02151', 'a@b.example', '', 'Rahmenvertrag', 5, 0)",
            )
        }
        val db = helfer.runMigrationsAndValidate("migration7.db", 7, true, SchadensfallDatenbank.MIGRATION_6_7)
        db.query("SELECT name, notizen, schlagworte, syncOffen FROM auftraggeber").use { c ->
            c.moveToFirst()
            assertEquals(listOf("Seats and Sofas", "Rahmenvertrag", "", "0"), listOf(c.getString(0), c.getString(1), c.getString(2), c.getInt(3).toString()))
        }
        db.execSQL("INSERT INTO chance (uuid, auftraggeberUuid, titel, wertCent, phase, erwartetAm, notiz, geaendertAm, syncOffen) VALUES ('c1', 'f1', 'Mehr Touren', 250000, 'ANGEBOT', NULL, '', 1, 1)")
        db.close()
    }

    @Test
    fun `Version 1 laesst sich in einem Zug auf den neuesten Stand bringen`() {
        helfer.createDatabase("migration16.db", 1).close()
        helfer.runMigrationsAndValidate(
            "migration16.db", 7, true,
            SchadensfallDatenbank.MIGRATION_1_2, SchadensfallDatenbank.MIGRATION_2_3, SchadensfallDatenbank.MIGRATION_3_4,
            SchadensfallDatenbank.MIGRATION_4_5, SchadensfallDatenbank.MIGRATION_5_6, SchadensfallDatenbank.MIGRATION_6_7,
        ).close()
    }
}
