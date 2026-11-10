package de.aysetransport.schaden.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.aysetransport.schaden.data.model.Anlage
import de.aysetransport.schaden.data.model.Loeschung
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag

@Database(
    entities = [Schadensfall::class, VerlaufEintrag::class, Anlage::class, Loeschung::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Konverter::class)
abstract class SchadensfallDatenbank : RoomDatabase() {

    abstract fun dao(): SchadensfallDao

    companion object {
        const val DATEINAME = "schadensfaelle.db"

        fun erstelle(context: Context): SchadensfallDatenbank =
            Room.databaseBuilder(context, SchadensfallDatenbank::class.java, DATEINAME)
                .addMigrations(MIGRATION_1_2)
                .build()

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
