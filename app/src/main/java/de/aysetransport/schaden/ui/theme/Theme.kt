package de.aysetransport.schaden.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val HellesSchema = lightColorScheme(
    primary = Color(0xFF36618E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF194975),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF3B4858),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2DAFF),
    onTertiaryContainer = Color(0xFF523F5F),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2E3135),
    inverseOnSurface = Color(0xFFEFF0F7),
    inversePrimary = Color(0xFFA0CAFD),
    surfaceDim = Color(0xFFD8DAE0),
    surfaceBright = Color(0xFFF8F9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E8),
)

private val DunklesSchema = darkColorScheme(
    primary = Color(0xFFA0CAFD),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF194975),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F),
    onTertiaryContainer = Color(0xFFF2DAFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF111418),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE1E2E8),
    inverseOnSurface = Color(0xFF2E3135),
    inversePrimary = Color(0xFF36618E),
    surfaceDim = Color(0xFF111418),
    surfaceBright = Color(0xFF36393E),
    surfaceContainerLowest = Color(0xFF0B0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
)

/** Farbpaar für farbige Marken (Status, Fälligkeit): Fläche und Schrift darauf. */
data class Markenfarbe(val flaeche: Color, val schrift: Color)

/** Farben, die über das Material-Schema hinausgehen und im hellen wie dunklen Design passen müssen. */
data class Zusatzfarben(
    val blau: Markenfarbe,
    val violett: Markenfarbe,
    val gelb: Markenfarbe,
    val lila: Markenfarbe,
    val tuerkis: Markenfarbe,
    val orange: Markenfarbe,
    val gruen: Markenfarbe,
    val grau: Markenfarbe,
    val rot: Markenfarbe,
)

private val HelleZusatzfarben = Zusatzfarben(
    blau = Markenfarbe(Color(0xFFD1E4FF), Color(0xFF0B3A63)),
    violett = Markenfarbe(Color(0xFFE3DFFF), Color(0xFF2F2A7A)),
    gelb = Markenfarbe(Color(0xFFFFDF9E), Color(0xFF4F3A00)),
    lila = Markenfarbe(Color(0xFFF2DAFF), Color(0xFF4A3657)),
    tuerkis = Markenfarbe(Color(0xFFBFE9EC), Color(0xFF00363A)),
    orange = Markenfarbe(Color(0xFFFFDBC8), Color(0xFF6A2D00)),
    gruen = Markenfarbe(Color(0xFFBDEFC2), Color(0xFF0C3A18)),
    grau = Markenfarbe(Color(0xFFE1E2E8), Color(0xFF43474E)),
    rot = Markenfarbe(Color(0xFFFFDAD6), Color(0xFF93000A)),
)

private val DunkleZusatzfarben = Zusatzfarben(
    blau = Markenfarbe(Color(0xFF194975), Color(0xFFD1E4FF)),
    violett = Markenfarbe(Color(0xFF454093), Color(0xFFE3DFFF)),
    gelb = Markenfarbe(Color(0xFF5B4300), Color(0xFFFFDF9E)),
    lila = Markenfarbe(Color(0xFF523F5F), Color(0xFFF2DAFF)),
    tuerkis = Markenfarbe(Color(0xFF1E4D52), Color(0xFFBFE9EC)),
    orange = Markenfarbe(Color(0xFF75390D), Color(0xFFFFDBC8)),
    gruen = Markenfarbe(Color(0xFF1F4F2A), Color(0xFFBDEFC2)),
    grau = Markenfarbe(Color(0xFF43474E), Color(0xFFE1E2E8)),
    rot = Markenfarbe(Color(0xFF93000A), Color(0xFFFFDAD6)),
)

val LocalZusatzfarben = staticCompositionLocalOf { HelleZusatzfarben }

@Composable
fun SchadensfallTheme(
    dunkel: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalZusatzfarben provides if (dunkel) DunkleZusatzfarben else HelleZusatzfarben) {
        MaterialTheme(
            colorScheme = if (dunkel) DunklesSchema else HellesSchema,
            content = content,
        )
    }
}
