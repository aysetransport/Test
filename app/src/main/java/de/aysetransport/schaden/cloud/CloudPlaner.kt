package de.aysetransport.schaden.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.aysetransport.schaden.SchadensfallApp
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Plant den Abgleich im Hintergrund: kurz nach jeder Änderung, sobald Netz da ist, und zur
 * Sicherheit alle 15 Minuten. Ohne Netz wartet Android, bis wieder eine Verbindung besteht.
 */
object CloudPlaner {
    const val SOFORT = "cloud-abgleich"
    const val REGELMAESSIG = "cloud-abgleich-regelmaessig"

    private val mitNetz = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Abgleich in wenigen Sekunden – mehrere Änderungen kurz hintereinander werden zusammengefasst. */
    fun bald(context: Context, verzoegerungSekunden: Long = 3) {
        val auftrag = OneTimeWorkRequestBuilder<CloudWorker>()
            .setConstraints(mitNetz)
            .setInitialDelay(verzoegerungSekunden, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOFORT, ExistingWorkPolicy.KEEP, auftrag)
    }

    fun regelmaessig(context: Context, aktiv: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!aktiv) {
            workManager.cancelUniqueWork(REGELMAESSIG)
            workManager.cancelUniqueWork(SOFORT)
            return
        }
        val auftrag = PeriodicWorkRequestBuilder<CloudWorker>(15, TimeUnit.MINUTES).setConstraints(mitNetz).build()
        workManager.enqueueUniquePeriodicWork(REGELMAESSIG, ExistingPeriodicWorkPolicy.KEEP, auftrag)
    }
}

class CloudWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val abgleich = (applicationContext as SchadensfallApp).container.cloud
        return try {
            abgleich.abgleichen()
            Result.success()
        } catch (e: IOException) {
            Result.retry() // Netz oder Cloud gestört
        } catch (e: CloudFehler) {
            Result.failure() // z. B. PIN falsch – steht in den Einstellungen
        }
    }
}
