package de.aysetransport.schaden.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.aysetransport.schaden.data.model.Adresse
import de.aysetransport.schaden.data.model.Aktivitaet
import de.aysetransport.schaden.data.model.Anlage
import de.aysetransport.schaden.data.model.Aufgabe
import de.aysetransport.schaden.data.model.Chance
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.Dokument
import de.aysetransport.schaden.data.model.Kontaktperson
import de.aysetransport.schaden.data.model.Preis
import de.aysetransport.schaden.data.model.Loeschung
import de.aysetransport.schaden.data.model.MailNachricht
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag

@Database(
    entities = [
        Schadensfall::class, VerlaufEintrag::class, Anlage::class, Loeschung::class, AuftraggeberEintrag::class, MailNachricht::class,
        Adresse::class, Kontaktperson::class, Preis::class, Aktivitaet::class, Aufgabe::class, Dokument::class, Chance::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Konverter::class)
abstract class SchadensfallDatenbank : RoomDatabase() {

    abstract fun dao(): SchadensfallDao

    abstract fun crm(): CrmDao

    companion object {
        const val DATEINAME = "schadensfaelle.db"

        fun erstelle(context: Context): SchadensfallDatenbank =
            Room.databaseBuilder(context, SchadensfallDatenbank::class.java, DATEINAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()

        /** Version 3: eigene Kontaktdaten für den Auftraggeber und eine Auftraggeber-Liste zum Auswählen. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (spalte in listOf("auftraggeberKontakt", "auftraggeberTelefon", "auftraggeberEmail")) {
                    db.execSQL("ALTER TABLE `schadensfall` ADD COLUMN `$spalte` TEXT NOT NULL DEFAULT ''")
                }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `auftraggeber` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, `kontakt` TEXT NOT NULL, " +
                        "`telefon` TEXT NOT NULL, `email` TEXT NOT NULL, `geaendertAm` INTEGER NOT NULL, `syncOffen` INTEGER NOT NULL, PRIMARY KEY(`uuid`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_auftraggeber_name` ON `auftraggeber` (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_auftraggeber_syncOffen` ON `auftraggeber` (`syncOffen`)")
                // Aus den vorhandenen Fällen eine erste Liste bilden – je Firma der zuletzt bearbeitete Fall.
                db.query(
                    "SELECT auftraggeber, auftraggeberKontakt, auftraggeberTelefon, auftraggeberEmail FROM schadensfall " +
                        "WHERE trim(auftraggeber) != '' ORDER BY geaendertAm DESC",
                ).use { c ->
                    val gesehen = mutableSetOf<String>()
                    while (c.moveToNext()) {
                        val name = c.getString(0).trim()
                        if (!gesehen.add(name.lowercase())) continue
                        db.execSQL(
                            "INSERT INTO `auftraggeber` (uuid, name, kontakt, telefon, email, geaendertAm, syncOffen) VALUES (?, ?, ?, ?, ?, ?, 1)",
                            arrayOf<Any>(java.util.UUID.randomUUID().toString(), name, c.getString(1), c.getString(2), c.getString(3), System.currentTimeMillis()),
                        )
                    }
                }
            }
        }

        /** Version 4: E-Mails zu den Fällen. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mail` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fallId` INTEGER NOT NULL, " +
                        "`eingehend` INTEGER NOT NULL, `messageId` TEXT NOT NULL, `antwortAuf` TEXT NOT NULL, `von` TEXT NOT NULL, " +
                        "`an` TEXT NOT NULL, `betreff` TEXT NOT NULL, `text` TEXT NOT NULL, `zeitpunkt` INTEGER NOT NULL, " +
                        "`verlaufUuid` TEXT NOT NULL, FOREIGN KEY(`fallId`) REFERENCES `schadensfall`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mail_fallId` ON `mail` (`fallId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_mail_messageId` ON `mail` (`messageId`)")
            }
        }

        /** Version 5: Zeichen des Auftraggebers. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `schadensfall` ADD COLUMN `auftraggeberZeichen` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Version 6: CRM für die Auftraggeber, E-Mails ohne Fall, zwei Postfächer. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = MigrationCrm.migriere(db)
        }

        /** Version 7: Verkaufschancen und Schlagworte für Firmen. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `auftraggeber` ADD COLUMN `schlagworte` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chance` (`uuid` TEXT NOT NULL, `auftraggeberUuid` TEXT NOT NULL, `titel` TEXT NOT NULL, " +
                        "`wertCent` INTEGER, `phase` TEXT NOT NULL, `erwartetAm` INTEGER, `notiz` TEXT NOT NULL, `geaendertAm` INTEGER NOT NULL, " +
                        "`syncOffen` INTEGER NOT NULL, PRIMARY KEY(`uuid`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chance_auftraggeberUuid` ON `chance` (`auftraggeberUuid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chance_syncOffen` ON `chance` (`syncOffen`)")
            }
        }

        /** Zufällige UUID (Version 4) in SQL – wird für jede Zeile neu ausgewertet. */
        private const val NEUE_UUID = "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
            "substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', 1 + (abs(random()) % 4), 1) || " +
            "substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))"

        /**
         * Version 2: bis zu zwei Beifahrer sowie Kennungen und Merker für den Abgleich mit der Cloud. Alle vorhandenen Daten
         * gelten als „noch nicht hochgeladen“, damit sie beim ersten Verbinden in die Cloud wandern.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (tabelle in listOf("schadensfall", "verlauf", "anlage")) {
                    db.execSQL("ALTER TABLE `$tabelle` ADD COLUMN `uuid` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE `$tabelle` ADD COLUMN `syncOffen` INTEGER NOT NULL DEFAULT 1")
                    db.execSQL("UPDATE `$tabelle` SET `uuid` = $NEUE_UUID")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${tabelle}_uuid` ON `$tabelle` (`uuid`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_${tabelle}_syncOffen` ON `$tabelle` (`syncOffen`)")
                }
                db.execSQL("ALTER TABLE `anlage` ADD COLUMN `inCloud` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `schadensfall` ADD COLUMN `beifahrer1` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `schadensfall` ADD COLUMN `beifahrer2` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `loeschung` (`uuid` TEXT NOT NULL, `art` TEXT NOT NULL, " +
                        "`fallUuid` TEXT NOT NULL, `zeitpunkt` INTEGER NOT NULL, PRIMARY KEY(`uuid`))",
                )
            }
        }
    }
}
