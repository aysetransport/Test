package de.aysetransport.schaden

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import de.aysetransport.schaden.cloud.CloudSpeicher
import de.aysetransport.schaden.data.EinstellungenSpeicher
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.UUID

/**
 * App-Variante für Tests: Jeder Test startet mit leerer Datenbank und Standard-Einstellungen,
 * und es werden keine echten Hintergrundaufträge eingeplant.
 */
class TestApp : SchadensfallApp() {

    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        super.onCreate()
    }

    override fun erstelleContainer(): AppContainer = TestContainer(this)

    override fun planeErinnerung() = Unit
}

class TestContainer(context: Context) : AppContainer(context) {

    override val datenbank: SchadensfallDatenbank by lazy {
        Room.inMemoryDatabaseBuilder(appContext, SchadensfallDatenbank::class.java).build()
    }

    override val cloudSpeicher: CloudSpeicher by lazy {
        val datei = File(appContext.cacheDir, "cloud-${UUID.randomUUID()}.preferences_pb")
        CloudSpeicher(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { datei })
    }

    /** Tests stoßen den Abgleich selbst an. */
    override fun nachAenderung() = Unit

    override val einstellungen: EinstellungenSpeicher by lazy {
        val datei = File(appContext.cacheDir, "einstellungen-${UUID.randomUUID()}.preferences_pb")
        EinstellungenSpeicher(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { datei },
        )
    }
}
