package de.aysetransport.schaden

import android.app.Application
import de.aysetransport.schaden.erinnerung.Benachrichtigungen
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
    }
}
