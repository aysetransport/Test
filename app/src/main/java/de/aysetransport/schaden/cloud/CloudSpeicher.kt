package de.aysetransport.schaden.cloud

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Verbindung zur Cloud und Stand des letzten Abgleichs. */
data class CloudZustand(
    val url: String = "",
    val pin: String = "",
    /** Kennung des Datenbestands in der Cloud; ändert sie sich, wurde dort neu begonnen. */
    val kennung: String = "",
    /** Bis zu diesem Stand hat dieses Gerät alle Änderungen der Cloud übernommen. */
    val seit: Long = 0,
    val letzterAbgleich: Long = 0,
    val fehler: String = "",
) {
    val verbunden: Boolean get() = url.isNotBlank()
    val angemeldet: Boolean get() = verbunden && pin.isNotEmpty()
}

private val Context.cloudStore: DataStore<Preferences> by preferencesDataStore(name = "cloud")

class CloudSpeicher(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.cloudStore)

    val zustand: Flow<CloudZustand> = store.data.map { p ->
        CloudZustand(
            url = p[URL] ?: "",
            pin = p[PIN] ?: "",
            kennung = p[KENNUNG] ?: "",
            seit = p[SEIT] ?: 0,
            letzterAbgleich = p[LETZTER] ?: 0,
            fehler = p[FEHLER] ?: "",
        )
    }

    suspend fun aktuell(): CloudZustand = zustand.first()

    suspend fun verbinde(url: String, pin: String) = store.edit {
        if (it[URL] != url) {
            it.remove(KENNUNG)
            it.remove(SEIT)
        }
        it[URL] = url
        it[PIN] = pin
        it.remove(FEHLER)
    }

    /** Trennt die Verbindung. Die Daten auf dem Gerät bleiben erhalten. */
    suspend fun trenne() = store.edit { it.clear() }

    suspend fun pinVergessen(fehler: String) = store.edit {
        it.remove(PIN)
        it[FEHLER] = fehler
    }

    suspend fun abgeglichen(kennung: String, seit: Long, zeitpunkt: Long) = store.edit {
        it[KENNUNG] = kennung
        it[SEIT] = seit
        it[LETZTER] = zeitpunkt
        it.remove(FEHLER)
    }

    suspend fun fehler(text: String) = store.edit { it[FEHLER] = text }

    /** Beim nächsten Abgleich alles aus der Cloud holen (z. B. nach einer Wiederherstellung). */
    suspend fun vonVorn() = store.edit { it.remove(SEIT) }

    private companion object {
        val URL = stringPreferencesKey("url")
        val PIN = stringPreferencesKey("pin")
        val KENNUNG = stringPreferencesKey("kennung")
        val SEIT = longPreferencesKey("seit")
        val LETZTER = longPreferencesKey("letzter_abgleich")
        val FEHLER = stringPreferencesKey("fehler")
    }
}
