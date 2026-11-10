package de.aysetransport.schaden.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.aysetransport.schaden.domain.Absender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

data class Einstellungen(
    val firma: String = "",
    val bearbeiter: String = "",
    val funktion: String = "",
    /** Vorschlag für die erste Wiedervorlage eines neuen Falls, in Tagen. */
    val standardWiedervorlageTage: Int = 7,
    val erinnerungAktiv: Boolean = true,
    val erinnerungStunde: Int = 8,
    val erinnerungMinute: Int = 0,
) {
    val absender: Absender get() = Absender(firma = firma, name = bearbeiter, funktion = funktion)
    val erinnerungUhrzeit: LocalTime get() = LocalTime.of(erinnerungStunde, erinnerungMinute)
}

private val Context.einstellungenStore: DataStore<Preferences> by preferencesDataStore(name = "einstellungen")

class EinstellungenSpeicher(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.einstellungenStore)

    val einstellungen: Flow<Einstellungen> = store.data.map(::lese)

    suspend fun aktuell(): Einstellungen = einstellungen.first()

    /** Ändert die Einstellungen in einem Schritt und gibt den neuen Stand zurück. */
    suspend fun aendere(aenderung: (Einstellungen) -> Einstellungen): Einstellungen {
        var ergebnis = Einstellungen()
        store.edit { p ->
            val neu = aenderung(lese(p))
            p[FIRMA] = neu.firma
            p[BEARBEITER] = neu.bearbeiter
            p[FUNKTION] = neu.funktion
            p[WIEDERVORLAGE_TAGE] = neu.standardWiedervorlageTage
            p[ERINNERUNG_AKTIV] = neu.erinnerungAktiv
            p[ERINNERUNG_STUNDE] = neu.erinnerungStunde
            p[ERINNERUNG_MINUTE] = neu.erinnerungMinute
            ergebnis = neu
        }
        return ergebnis
    }

    private fun lese(p: Preferences): Einstellungen {
        val standard = Einstellungen()
        return Einstellungen(
            firma = p[FIRMA] ?: standard.firma,
            bearbeiter = p[BEARBEITER] ?: standard.bearbeiter,
            funktion = p[FUNKTION] ?: standard.funktion,
            standardWiedervorlageTage = (p[WIEDERVORLAGE_TAGE] ?: standard.standardWiedervorlageTage).coerceIn(1, 90),
            erinnerungAktiv = p[ERINNERUNG_AKTIV] ?: standard.erinnerungAktiv,
            erinnerungStunde = (p[ERINNERUNG_STUNDE] ?: standard.erinnerungStunde).coerceIn(0, 23),
            erinnerungMinute = (p[ERINNERUNG_MINUTE] ?: standard.erinnerungMinute).coerceIn(0, 59),
        )
    }

    private companion object {
        val FIRMA = stringPreferencesKey("firma")
        val BEARBEITER = stringPreferencesKey("bearbeiter")
        val FUNKTION = stringPreferencesKey("funktion")
        val WIEDERVORLAGE_TAGE = intPreferencesKey("wiedervorlage_tage")
        val ERINNERUNG_AKTIV = booleanPreferencesKey("erinnerung_aktiv")
        val ERINNERUNG_STUNDE = intPreferencesKey("erinnerung_stunde")
        val ERINNERUNG_MINUTE = intPreferencesKey("erinnerung_minute")
    }
}
