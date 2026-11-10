package de.aysetransport.schaden.cloud

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.aysetransport.schaden.data.AnlagenSpeicher
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.Schadensfall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Prüft den Abgleich gegen die echte, bereitgestellte Cloud. Läuft nur, wenn sie ausdrücklich
 * angegeben wird:  LIVE_CLOUD_URL=… LIVE_CLOUD_PIN=… ./gradlew testDebugUnitTest --tests '*LiveCloudTest*'
 * Legt einen Testfall an und löscht ihn wieder; zurück bleibt nur ein inhaltsleerer Löschvermerk.
 */
@RunWith(AndroidJUnit4::class)
class LiveCloudTest {

    @Test
    fun `Testfall hin und zurueck ueber die echte Cloud`() = runBlocking {
        val url = System.getenv("LIVE_CLOUD_URL").orEmpty()
        val pin = System.getenv("LIVE_CLOUD_PIN").orEmpty()
        assumeTrue("nur mit LIVE_CLOUD_URL und LIVE_CLOUD_PIN", url.isNotEmpty() && pin.isNotEmpty())

        val context: Context = ApplicationProvider.getApplicationContext()
        fun geraet(): Triple<SchadensfallRepository, CloudAbgleich, SchadensfallDatenbank> {
            val db = Room.inMemoryDatabaseBuilder(context, SchadensfallDatenbank::class.java).build()
            val speicher = AnlagenSpeicher(context, File(context.filesDir, "live-${UUID.randomUUID()}"))
            val cloudSpeicher = CloudSpeicher(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
                File(context.cacheDir, "live-${UUID.randomUUID()}.preferences_pb")
            })
            return Triple(SchadensfallRepository(db, speicher), CloudAbgleich(db, speicher, cloudSpeicher), db)
        }
        val (repoA, cloudA, _) = geraet()
        assertTrue("PIN ist festgelegt", cloudA.pruefeAdresse(url))
        cloudA.verbinde(url, pin, neuePin = false)
        val name = "TEST Android – bitte ignorieren ${UUID.randomUUID().toString().take(8)}"
        val id = repoA.anlegen(Schadensfall(kundeName = name, beifahrer1 = "Testbeifahrer"))
        cloudA.abgleichen()

        val (repoB, cloudB, _) = geraet()
        cloudB.verbinde(url, pin, neuePin = false)
        cloudB.abgleichen()
        val angekommen = repoB.ladeAlleFaelle().single { it.kundeName == name }
        assertEquals("Testbeifahrer", angekommen.beifahrer1)

        repoA.fallLoeschen(id)
        cloudA.abgleichen()
        cloudB.abgleichen()
        assertTrue(repoB.ladeAlleFaelle().none { it.kundeName == name })
    }
}
