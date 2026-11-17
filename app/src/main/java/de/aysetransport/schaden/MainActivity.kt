package de.aysetransport.schaden

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.aysetransport.schaden.erinnerung.Benachrichtigungen
import de.aysetransport.schaden.ui.SchadensfallNavigation
import de.aysetransport.schaden.ui.Startauftrag
import de.aysetransport.schaden.ui.theme.SchadensfallTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val startauftrag = MutableStateFlow<Startauftrag?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Nur beim echten Start auswerten – nach einer Drehung soll nicht erneut gesprungen werden.
        if (savedInstanceState == null) liesStartauftrag(intent)

        val container = (application as SchadensfallApp).container
        setContent {
            SchadensfallTheme {
                val auftrag by startauftrag.collectAsStateWithLifecycle()
                SchadensfallNavigation(
                    container = container,
                    startauftrag = auftrag,
                    onStartauftragErledigt = { startauftrag.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        liesStartauftrag(intent)
    }

    /** Wertet aus, ob die App über eine Erinnerung geöffnet wurde. */
    private fun liesStartauftrag(intent: Intent?) {
        if (intent == null) return
        val fallId = intent.getLongExtra(Benachrichtigungen.EXTRA_FALL_ID, 0L)
        val firma = intent.getStringExtra(Benachrichtigungen.EXTRA_FIRMA).orEmpty()
        when {
            fallId > 0L -> startauftrag.value = Startauftrag.Fall(fallId)
            firma.isNotEmpty() -> startauftrag.value = Startauftrag.Firma(firma)
            intent.getBooleanExtra(Benachrichtigungen.EXTRA_POSTFACH, false) -> startauftrag.value = Startauftrag.Postfach
            intent.getBooleanExtra(Benachrichtigungen.EXTRA_ZEIGE_FAELLIGE, false) -> startauftrag.value = Startauftrag.FaelligeListe
        }
    }
}
