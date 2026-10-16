package de.radiopult.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Vollständiges Material-3-Schema aus dem Markenorange (Seed #A04100, Tonal Spot).
// Primärtöne bleiben die bisherigen kräftigen Orangetöne, alle neutralen Flächen sind warm statt lila.
private val LightColors = lightColorScheme(
    primary = Color(0xFFA04100),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF703718),
    secondary = Color(0xFF765749),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCC),
    onSecondaryContainer = Color(0xFF5C4033),
    tertiary = Color(0xFF655F31),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDE4A9),
    onTertiaryContainer = Color(0xFF4D481C),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF221A16),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF221A16),
    surfaceVariant = Color(0xFFF4DED5),
    onSurfaceVariant = Color(0xFF52443D),
    outline = Color(0xFF85736C),
    outlineVariant = Color(0xFFD7C2B9),
    inverseSurface = Color(0xFF382E2A),
    inverseOnSurface = Color(0xFFFFEDE6),
    inversePrimary = Color(0xFFFFB693),
    surfaceDim = Color(0xFFE8D7D0),
    surfaceBright = Color(0xFFFFF8F6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainer = Color(0xFFFCEAE3),
    surfaceContainerHigh = Color(0xFFF6E5DE),
    surfaceContainerHighest = Color(0xFFF0DFD8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB693),
    onPrimary = Color(0xFF542104),
    primaryContainer = Color(0xFF703718),
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFE6BEAC),
    onSecondary = Color(0xFF432A1E),
    secondaryContainer = Color(0xFF5C4033),
    onSecondaryContainer = Color(0xFFFFDBCC),
    tertiary = Color(0xFFD0C890),
    onTertiary = Color(0xFF363107),
    tertiaryContainer = Color(0xFF4D481C),
    onTertiaryContainer = Color(0xFFEDE4A9),
    background = Color(0xFF1A120E),
    onBackground = Color(0xFFF0DFD8),
    surface = Color(0xFF1A120E),
    onSurface = Color(0xFFF0DFD8),
    surfaceVariant = Color(0xFF52443D),
    onSurfaceVariant = Color(0xFFD7C2B9),
    outline = Color(0xFFA08D85),
    outlineVariant = Color(0xFF52443D),
    inverseSurface = Color(0xFFF0DFD8),
    inverseOnSurface = Color(0xFF382E2A),
    inversePrimary = Color(0xFF8D4D2C),
    surfaceDim = Color(0xFF1A120E),
    surfaceBright = Color(0xFF423732),
    surfaceContainerLowest = Color(0xFF140C09),
    surfaceContainerLow = Color(0xFF221A16),
    surfaceContainer = Color(0xFF271E1A),
    surfaceContainerHigh = Color(0xFF322824),
    surfaceContainerHighest = Color(0xFF3D332E),
)

@Composable
fun RadioPultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
