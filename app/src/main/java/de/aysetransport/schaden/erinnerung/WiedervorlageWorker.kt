package de.aysetransport.schaden.erinnerung

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.aysetransport.schaden.SchadensfallApp
import java.time.LocalDate

/** Läuft einmal täglich und meldet, bei welchen Fällen nachgehakt werden muss. */
class WiedervorlageWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as SchadensfallApp).container
        val heute = LocalDate.now()
        Benachrichtigungen.zeigeFaellige(applicationContext, container.repository.faelligeFaelle(heute), heute)

        // Nächsten Lauf wieder auf die eingestellte Uhrzeit legen.
        val einstellungen = container.einstellungen.aktuell()
        ErinnerungsPlaner.plane(
            applicationContext,
            einstellungen.erinnerungAktiv,
            einstellungen.erinnerungUhrzeit,
            bestehendenPlanErsetzen = true,
        )
        return Result.success()
    }
}
