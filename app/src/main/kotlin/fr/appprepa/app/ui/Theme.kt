package fr.appprepa.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Noir, blanc, et une seule couleur. L'accent ne sert qu'a une chose : signaler que
 * l'application ecoute. Tout le reste est typographie.
 */
private val Ink = Color(0xFF0B0B0C)
private val Paper = Color(0xFFF5F5F4)
private val Faint = Color(0xFF6E6E73)
private val Rule = Color(0xFF26262A)
private val Accent = Color(0xFFFF4D2E)

private val SillonColors = darkColorScheme(
    primary = Paper,
    onPrimary = Ink,
    secondary = Accent,
    onSecondary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Ink,
    surfaceContainer = Ink,
    surfaceContainerHigh = Rule,
    surfaceContainerHighest = Rule,
    surfaceVariant = Ink,
    onSurfaceVariant = Faint,
    outline = Rule,
    outlineVariant = Rule,
    error = Accent,
    onError = Ink,
)

/** Un seul niveau compte : le mot d'etat. Le reste s'efface. */
private val SillonType = Typography(
    displayLarge = TextStyle(
        fontSize = 56.sp,
        lineHeight = 60.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = (-1).sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.5.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
)

object SillonPalette {
    val accent = Accent
    val faint = Faint
    val rule = Rule
}

@Composable
fun SillonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SillonColors, typography = SillonType) {
        // Hors d'un Surface, LocalContentColor reste au noir : peindre le fond a la main
        // ne suffit pas, les textes sortent illisibles sur fond sombre.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = SillonColors.background,
            contentColor = SillonColors.onBackground,
            content = content,
        )
    }
}
