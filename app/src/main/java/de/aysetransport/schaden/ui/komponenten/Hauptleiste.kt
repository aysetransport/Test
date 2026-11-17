package de.aysetransport.schaden.ui.komponenten

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag

/** Die vier Hauptbereiche der App. */
enum class Bereich(val label: String, val symbol: ImageVector, val tag: String) {
    FIRMEN("Firmen", Icons.Filled.Home, "nav-firmen"),
    FAELLE("Schäden", Icons.Filled.Warning, "nav-faelle"),
    WIEDERVORLAGEN("Termine", Icons.Filled.DateRange, "nav-wiedervorlagen"),
    POSTFACH("Postfach", Icons.Filled.Email, "nav-postfach"),
}

/** Zähler an den Symbolen: fällige Termine und nicht zugeordnete Mails. */
data class Hinweiszahlen(val faellig: Int = 0, val nichtZugeordnet: Int = 0)

@Composable
fun Hauptleiste(aktiv: Bereich, zahlen: Hinweiszahlen, onWechsel: (Bereich) -> Unit) {
    NavigationBar {
        for (b in Bereich.entries) {
            val zahl = when (b) {
                Bereich.WIEDERVORLAGEN -> zahlen.faellig
                Bereich.POSTFACH -> zahlen.nichtZugeordnet
                else -> 0
            }
            NavigationBarItem(
                selected = b == aktiv,
                onClick = { if (b != aktiv) onWechsel(b) },
                icon = {
                    if (zahl > 0) BadgedBox(badge = { Badge { Text(zahl.toString()) } }) { Icon(b.symbol, contentDescription = null) }
                    else Icon(b.symbol, contentDescription = null)
                },
                label = { Text(b.label) },
                modifier = Modifier.testTag(b.tag),
            )
        }
    }
}
