package de.aysetransport.schaden.data.db

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate

/** Speichert Datumswerte als Zahlen, damit die Datenbank danach sortieren und filtern kann. */
class Konverter {
    @TypeConverter
    fun datumZuZahl(datum: LocalDate?): Long? = datum?.toEpochDay()

    @TypeConverter
    fun zahlZuDatum(tag: Long?): LocalDate? = tag?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun zeitpunktZuZahl(zeitpunkt: Instant?): Long? = zeitpunkt?.toEpochMilli()

    @TypeConverter
    fun zahlZuZeitpunkt(millis: Long?): Instant? = millis?.let(Instant::ofEpochMilli)
}
