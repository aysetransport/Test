package de.aysetransport.schaden.mail

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
import de.aysetransport.schaden.erinnerung.Benachrichtigungen
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Ruft das Postfach im Hintergrund ab: alle 15 Minuten und beim App-Start, sofern Netz da ist. */
object MailPlaner {
    const val SOFORT = "mail-abruf"
    const val REGELMAESSIG = "mail-abruf-regelmaessig"
    private val mitNetz = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun jetzt(context: Context) {
        val auftrag = OneTimeWorkRequestBuilder<MailWorker>().setConstraints(mitNetz).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOFORT, ExistingWorkPolicy.KEEP, auftrag)
    }

    fun regelmaessig(context: Context, aktiv: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!aktiv) {
            wm.cancelUniqueWork(REGELMAESSIG)
            return
        }
        val auftrag = PeriodicWorkRequestBuilder<MailWorker>(15, TimeUnit.MINUTES).setConstraints(mitNetz).build()
        wm.enqueueUniquePeriodicWork(REGELMAESSIG, ExistingPeriodicWorkPolicy.KEEP, auftrag)
    }
}

class MailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SchadensfallApp).container
        if (container.mailSpeicher.aktuell().konfig == null && container.mailSpeicherAllgemein.aktuell().konfig == null) return Result.success()
        return try {
            val neu = container.postfach.abrufen()
            Benachrichtigungen.zeigeNeueMails(applicationContext, neu)
            Result.success()
        } catch (e: MailAnmeldungFehler) {
            Result.failure() // steht in den Einstellungen
        } catch (e: IOException) {
            Result.retry()
        }
    }
}
