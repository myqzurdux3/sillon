package fr.appprepa.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Palette de l'icone : nuit d'hiver et ambre de phares. Sombre par principe — l'ecran est
 * regarde d'un coup d'oeil, dans une voiture, souvent avant le lever du jour.
 */
private val Night = Color(0xFF121C33)
private val NightRaised = Color(0xFF1B2A4A)
private val Paper = Color(0xFFF4F0E6)
private val Amber = Color(0xFFF2A65A)
private val Muted = Color(0xFF9AA8C4)

private val KholleColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF231603),
    secondary = Amber,
    onSecondary = Color(0xFF231603),
    background = Night,
    onBackground = Paper,
    surface = NightRaised,
    onSurface = Paper,
    surfaceVariant = NightRaised,
    onSurfaceVariant = Muted,
    // Material3 tire les Cards de surfaceContainer, pas de surface : sans ces lignes
    // elles virent au gris neutre et cassent la palette.
    surfaceContainerLowest = Color(0xFF0D1526),
    surfaceContainerLow = Color(0xFF16233D),
    surfaceContainer = NightRaised,
    surfaceContainerHigh = Color(0xFF213257),
    surfaceContainerHighest = Color(0xFF283C68),
    outline = Muted,
    error = Color(0xFFE57373),
)

private val KholleTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 40.sp,
        lineHeight = 46.sp,
        fontWeight = FontWeight.Medium,
    ),
)

@Composable
fun KholleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KholleColors,
        typography = KholleTypography,
        content = content,
    )
}
