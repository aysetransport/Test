package de.aysetransport.schaden.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/**
 * Datenbank-Version 6: Aus der Auftraggeber-Liste wird das CRM.
 *
 * - `auftraggeber` wird zur Firma (Rechtsform, Status, Website, Notizen). Der bisherige Ansprechpartner
 *   der Liste wird ein Eintrag in der neuen Tabelle `ansprechpartner`.
 * - Neue Tabellen: Adressen, Ansprechpartner, Preise, Aktivitäten, Aufgaben (Wiedervorlagen), Dokumente.
 * - Jeder Fall bekommt einen Verweis auf seine Firma (`auftraggeberUuid`). Fehlende Firmen werden aus dem
 *   Freitext angelegt; Ansprechpartner, Telefon und E-Mail des Auftraggebers aus dem Fall werden zu
 *   Ansprechpartnern der Firma (Zuständigkeit „Schäden“).
 * - `mail` erlaubt Mails ohne Fall (Zuordnung zu Firma oder Kunde, Eingang „Nicht zugeordnet“) und merkt
 *   sich das Postfach. Alle bisherigen Mails liefen über das Schadens-Postfach.
 *
 * Es geht nichts verloren: Die Freitextfelder der Fälle bleiben unverändert erhalten.
 */
object MigrationCrm {

    fun migriere(db: SupportSQLiteDatabase) {
        val jetzt = System.currentTimeMillis()

        // --- 1. Firmen ---
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `auftraggeber_neu` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, `rechtsform` TEXT NOT NULL DEFAULT '', " +
                "`status` TEXT NOT NULL DEFAULT 'AKTIV', `telefon` TEXT NOT NULL, `email` TEXT NOT NULL, `website` TEXT NOT NULL DEFAULT '', " +
                "`notizen` TEXT NOT NULL DEFAULT '', `geaendertAm` INTEGER NOT NULL, `syncOffen` INTEGER NOT NULL, PRIMARY KEY(`uuid`))",
        )
        db.execSQL(
            "INSERT INTO `auftraggeber_neu` (uuid, name, rechtsform, status, telefon, email, website, notizen, geaendertAm, syncOffen) " +
                "SELECT uuid, name, '', 'AKTIV', telefon, email, '', '', geaendertAm, 1 FROM `auftraggeber`",
        )
        val alteKontakte = mutableListOf<Array<String>>()
        db.query("SELECT uuid, kontakt, telefon, email FROM `auftraggeber` WHERE trim(kontakt) != ''").use { c ->
            while (c.moveToNext()) alteKontakte += arrayOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
        }
        db.execSQL("DROP TABLE `auftraggeber`")
        db.execSQL("ALTER TABLE `auftraggeber_neu` RENAME TO `auftraggeber`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_auftraggeber_name` ON `auftraggeber` (`name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_auftraggeber_syncOffen` ON `auftraggeber` (`syncOffen`)")

        // --- 2. neue Tabellen ---
        val crmTabellen = mapOf(
            "adresse" to "`art` TEXT NOT NULL, `strasse` TEXT NOT NULL, `plz` TEXT NOT NULL, `ort` TEXT NOT NULL, `notiz` TEXT NOT NULL",
            "ansprechpartner" to "`name` TEXT NOT NULL, `thema` TEXT NOT NULL, `telefon` TEXT NOT NULL, `email` TEXT NOT NULL, `notiz` TEXT NOT NULL",
            "preis" to "`bezeichnung` TEXT NOT NULL, `betragCent` INTEGER, `einheit` TEXT NOT NULL, `gueltigAb` INTEGER, `notiz` TEXT NOT NULL",
            "aktivitaet" to "`datum` INTEGER NOT NULL, `art` TEXT NOT NULL, `text` TEXT NOT NULL, `kontakt` TEXT NOT NULL",
            "aufgabe" to "`faelligAm` INTEGER, `text` TEXT NOT NULL, `kontakt` TEXT NOT NULL, `erledigt` INTEGER NOT NULL",
            "dokument" to "`titel` TEXT NOT NULL, `link` TEXT NOT NULL, `notiz` TEXT NOT NULL",
        )
        for ((tabelle, spalten) in crmTabellen) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `$tabelle` (`uuid` TEXT NOT NULL, `auftraggeberUuid` TEXT NOT NULL, $spalten, " +
                    "`geaendertAm` INTEGER NOT NULL, `syncOffen` INTEGER NOT NULL, PRIMARY KEY(`uuid`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_${tabelle}_auftraggeberUuid` ON `$tabelle` (`auftraggeberUuid`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_${tabelle}_syncOffen` ON `$tabelle` (`syncOffen`)")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_aufgabe_faelligAm` ON `aufgabe` (`faelligAm`)")

        // Ansprechpartner aus der bisherigen Auftraggeber-Liste
        val kontakte = mutableMapOf<String, MutableList<Kontakt>>()
        for ((firma, name, telefon, email) in alteKontakte) {
            neuerKontakt(db, kontakte, firma, name.trim(), telefon.trim(), email.trim(), "", jetzt)
        }

        // --- 3. Fälle mit ihrer Firma verbinden ---
        db.execSQL("ALTER TABLE `schadensfall` ADD COLUMN `auftraggeberUuid` TEXT")
        val firmen = mutableMapOf<String, String>() // Vergleichsname → Kennung
        db.query("SELECT uuid, name FROM `auftraggeber` ORDER BY uuid").use { c ->
            while (c.moveToNext()) firmen.putIfAbsent(normal(c.getString(1)), c.getString(0))
        }
        data class Fall(val id: Long, val name: String, val kontakt: String, val telefon: String, val email: String, val geaendert: Long)
        val faelle = mutableListOf<Fall>()
        db.query(
            "SELECT id, auftraggeber, auftraggeberKontakt, auftraggeberTelefon, auftraggeberEmail, geaendertAm FROM `schadensfall` " +
                "WHERE trim(auftraggeber) != '' ORDER BY geaendertAm DESC",
        ).use { c ->
            while (c.moveToNext()) faelle += Fall(c.getLong(0), c.getString(1).trim(), c.getString(2).trim(), c.getString(3).trim(), c.getString(4).trim(), c.getLong(5))
        }
        for (fall in faelle) {
            val firma = firmen.getOrPut(normal(fall.name)) {
                val uuid = UUID.randomUUID().toString()
                db.execSQL(
                    "INSERT INTO `auftraggeber` (uuid, name, rechtsform, status, telefon, email, website, notizen, geaendertAm, syncOffen) " +
                        "VALUES (?, ?, '', 'AKTIV', '', '', '', '', ?, 1)",
                    arrayOf<Any>(uuid, fall.name, jetzt),
                )
                uuid
            }
            // Eine Millisekunde später: der Verweis gilt beim nächsten Abgleich als neuerer Stand.
            db.execSQL(
                "UPDATE `schadensfall` SET auftraggeberUuid = ?, syncOffen = 1, geaendertAm = ? WHERE id = ?",
                arrayOf<Any>(firma, fall.geaendert + 1, fall.id),
            )
            if (fall.kontakt.isNotEmpty() || fall.telefon.isNotEmpty() || fall.email.isNotEmpty()) {
                neuerKontakt(db, kontakte, firma, fall.kontakt, fall.telefon, fall.email, "Schäden", jetzt)
            }
        }

        // --- 4. Mails ---
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mail_neu` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fallId` INTEGER, `auftraggeberUuid` TEXT, " +
                "`kundeRef` TEXT NOT NULL DEFAULT '', `konto` TEXT NOT NULL DEFAULT 'SCHADEN', `eingehend` INTEGER NOT NULL, `messageId` TEXT NOT NULL, " +
                "`antwortAuf` TEXT NOT NULL, `von` TEXT NOT NULL, `an` TEXT NOT NULL, `betreff` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`zeitpunkt` INTEGER NOT NULL, `verlaufUuid` TEXT NOT NULL, `aktivitaetUuid` TEXT NOT NULL DEFAULT '', `anhaenge` TEXT NOT NULL DEFAULT '', " +
                "FOREIGN KEY(`fallId`) REFERENCES `schadensfall`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL(
            "INSERT INTO `mail_neu` (id, fallId, auftraggeberUuid, kundeRef, konto, eingehend, messageId, antwortAuf, von, an, betreff, text, " +
                "zeitpunkt, verlaufUuid, aktivitaetUuid, anhaenge) SELECT id, fallId, NULL, '', 'SCHADEN', eingehend, messageId, antwortAuf, von, an, " +
                "betreff, text, zeitpunkt, verlaufUuid, '', '' FROM `mail`",
        )
        db.execSQL("DROP TABLE `mail`")
        db.execSQL("ALTER TABLE `mail_neu` RENAME TO `mail`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mail_fallId` ON `mail` (`fallId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_mail_messageId` ON `mail` (`messageId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mail_auftraggeberUuid` ON `mail` (`auftraggeberUuid`)")
    }

    private class Kontakt(val uuid: String, val name: String, var telefon: String, var email: String)

    /** Legt einen Ansprechpartner an – oder ergänzt beim schon bekannten nur, was fehlt. */
    private fun neuerKontakt(
        db: SupportSQLiteDatabase,
        kontakte: MutableMap<String, MutableList<Kontakt>>,
        firma: String,
        name: String,
        telefon: String,
        email: String,
        thema: String,
        jetzt: Long,
    ) {
        val liste = kontakte.getOrPut(firma) { mutableListOf() }
        val treffer = when {
            name.isNotEmpty() -> liste.firstOrNull { normal(it.name) == normal(name) }
            email.isNotEmpty() -> liste.firstOrNull { it.email.equals(email, ignoreCase = true) }
            else -> liste.firstOrNull { it.telefon.filter(Char::isDigit) == telefon.filter(Char::isDigit) }
        }
        if (treffer == null) {
            val k = Kontakt(UUID.randomUUID().toString(), name.ifEmpty { email.ifEmpty { telefon } }, telefon, email)
            liste += k
            db.execSQL(
                "INSERT INTO `ansprechpartner` (uuid, auftraggeberUuid, name, thema, telefon, email, notiz, geaendertAm, syncOffen) " +
                    "VALUES (?, ?, ?, ?, ?, ?, '', ?, 1)",
                arrayOf<Any>(k.uuid, firma, k.name, thema, telefon, email, jetzt),
            )
        } else if ((treffer.telefon.isEmpty() && telefon.isNotEmpty()) || (treffer.email.isEmpty() && email.isNotEmpty())) {
            treffer.telefon = treffer.telefon.ifEmpty { telefon }
            treffer.email = treffer.email.ifEmpty { email }
            db.execSQL(
                "UPDATE `ansprechpartner` SET telefon = ?, email = ? WHERE uuid = ?",
                arrayOf<Any>(treffer.telefon, treffer.email, treffer.uuid),
            )
        }
    }

    private fun normal(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), " ")
}
