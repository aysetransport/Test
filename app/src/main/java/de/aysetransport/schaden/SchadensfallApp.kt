package de.aysetransport.schaden

import android.app.Application
import de.aysetransport.schaden.cloud.CloudPlaner
import de.aysetransport.schaden.erinnerung.Benachrichtigungen
import de.aysetransport.schaden.mail.MailPlaner
import de.aysetransport.schaden.erinnerung.ErinnerungsPlaner
import kotlinx.coroutines.launch

open class SchadensfallApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = erstelleContainer()
        Benachrichtigungen.kanalAnlegen(this)
        planeErinnerung()
    }

    protected open fun erstelleContainer(): AppContainer = AppContainer(this)

    /**
     * Stellt bei jedem App-Start sicher, dass die tägliche Erinnerung geplant ist. Ein bereits
     * bestehender Plan bleibt unberührt (siehe [ErinnerungsPlaner.plane]).
     */
    protected open fun planeErinnerung() {
        container.appScope.launch {
            val einstellungen = container.einstellungen.aktuell()
            ErinnerungsPlaner.plane(
                this@SchadensfallApp,
                einstellungen.erinnerungAktiv,
                einstellungen.erinnerungUhrzeit,
                bestehendenPlanErsetzen = false,
            )
        }
        container.appScope.launch {
            val mitKonto = container.mailSpeicher.aktuell().konfig != null
            MailPlaner.regelmaessig(this@SchadensfallApp, mitKonto)
            if (mitKonto) MailPlaner.jetzt(this@SchadensfallApp)
        }
        container.appScope.launch {
            val cloud = container.cloudSpeicher.aktuell()
            CloudPlaner.regelmaessig(this@SchadensfallApp, cloud.angemeldet)
            if (cloud.angemeldet) CloudPlaner.bald(this@SchadensfallApp, verzoegerungSekunden = 0)
        }
    }
}
