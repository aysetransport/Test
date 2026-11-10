package de.aysetransport.schaden.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import de.aysetransport.schaden.data.model.Anlage
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag

@Database(
    entities = [Schadensfall::class, VerlaufEintrag::class, Anlage::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Konverter::class)
abstract class SchadensfallDatenbank : RoomDatabase() {

    abstract fun dao(): SchadensfallDao

    companion object {
        const val DATEINAME = "schadensfaelle.db"

        fun erstelle(context: Context): SchadensfallDatenbank =
            Room.databaseBuilder(context, SchadensfallDatenbank::class.java, DATEINAME).build()
    }
}
