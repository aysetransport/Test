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
    fun `Version 1 laesst sich in einem Zug auf 3 bringen`() {
        helfer.createDatabase("migration13.db", 1).close()
        helfer.runMigrationsAndValidate("migration13.db", 3, true, SchadensfallDatenbank.MIGRATION_1_2, SchadensfallDatenbank.MIGRATION_2_3).close()
    }
}
