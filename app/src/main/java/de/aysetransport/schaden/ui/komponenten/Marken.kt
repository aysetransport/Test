package de.aysetransport.schaden.ui.komponenten

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.aysetransport.schaden.R
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.domain.Faelligkeit
import de.aysetransport.schaden.ui.theme.LocalZusatzfarben
import de.aysetransport.schaden.ui.theme.Markenfarbe

@Composable
fun FallStatus.farbe(): Markenfarbe {
    val farben = LocalZusatzfarben.current
    return when (this) {
        FallStatus.NEU -> farben.blau
        FallStatus.IN_PRUEFUNG -> farben.violett
        FallStatus.WARTET_AUF_RUECKMELDUNG -> farben.gelb
        FallStatus.BEI_VERSICHERUNG -> farben.lila
        FallStatus.VERGLEICH_ANGEBOTEN -> farben.tuerkis
        FallStatus.ZAHLUNG_OFFEN -> farben.orange
        FallStatus.ERLEDIGT -> farben.gruen
        FallStatus.ABGELEHNT -> farben.grau
    }
}

@Composable
fun Faelligkeit.farbe(): Markenfarbe {
    val farben = LocalZusatzfarben.current
    return when (this) {
        is Faelligkeit.Ueberfaellig -> farben.rot
        Faelligkeit.Heute -> farben.gelb
        is Faelligkeit.Kommend, Faelligkeit.Keine -> farben.grau
    }
}

/** Kleine farbige Marke mit dem Bearbeitungsstand. */
@Composable
fun StatusMarke(status: FallStatus, modifier: Modifier = Modifier) {
    val farbe = status.farbe()
    Text(
        text = status.label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = farbe.schrift,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(farbe.flaeche)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Marke mit Uhr-Symbol: wann die Wiedervorlage fällig ist. */
@Composable
fun FaelligkeitsMarke(faelligkeit: Faelligkeit, modifier: Modifier = Modifier, text: String = faelligkeit.text()) {
    val farbe = faelligkeit.farbe()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(farbe.flaeche)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_uhr),
            contentDescription = null,
            tint = farbe.schrift,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = farbe.schrift,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
