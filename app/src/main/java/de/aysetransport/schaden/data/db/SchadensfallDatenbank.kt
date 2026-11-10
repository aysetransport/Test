package de.aysetransport.schaden.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.aysetransport.schaden.data.model.Anlage
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.Loeschung
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag

@Database(
    entities = [Schadensfall::class, VerlaufEintrag::class, Anlage::class, Loeschung::class, AuftraggeberEintrag::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Konverter::class)
abstract class SchadensfallDatenbank : RoomDatabase() {

    abstract fun dao(): SchadensfallDao

    companion object {
        const val DATEINAME = "schadensfaelle.db"

        fun erstelle(context: Context): SchadensfallDatenbank =
            Room.databaseBuilder(context, SchadensfallDatenbank::class.java, DATEINAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
