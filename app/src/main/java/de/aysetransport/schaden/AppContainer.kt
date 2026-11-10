package de.aysetransport.schaden

import android.content.Context
import de.aysetransport.schaden.cloud.CloudAbgleich
import de.aysetransport.schaden.cloud.CloudPlaner
import de.aysetransport.schaden.cloud.CloudSpeicher
import de.aysetransport.schaden.data.AnlagenSpeicher
import de.aysetransport.schaden.data.BackupManager
import de.aysetransport.schaden.data.EinstellungenSpeicher
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hält die langlebigen Objekte der App an einer Stelle zusammen.
 *
 * Datenbank und Einstellungen sind überschreibbar, damit Tests sie durch flüchtige
 * Varianten ersetzen können.
 */
open class AppContainer(context: Context) {
    protected val appContext: Context = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    open val datenbank: SchadensfallDatenbank by lazy { SchadensfallDatenbank.erstelle(appContext) }

    open val einstellungen: EinstellungenSpeicher by lazy { EinstellungenSpeicher(appContext) }

    val anlagenSpeicher: AnlagenSpeicher by lazy { AnlagenSpeicher(appContext) }

    val repository: SchadensfallRepository by lazy {
        SchadensfallRepository(datenbank, anlagenSpeicher, nachAenderung = ::nachAenderung)
    }

    open val cloudSpeicher: CloudSpeicher by lazy { CloudSpeicher(appContext) }

    val cloud: CloudAbgleich by lazy { CloudAbgleich(datenbank, anlagenSpeicher, cloudSpeicher) }

    /** Nach jeder Änderung den Abgleich anstoßen – sofern eine Cloud verbunden ist. */
    protected open fun nachAenderung() {
        appScope.launch { if (cloudSpeicher.aktuell().angemeldet) CloudPlaner.bald(appContext) }
    }

    val backup: BackupManager by lazy { BackupManager(datenbank, anlagenSpeicher, appContext.cacheDir) }
}
