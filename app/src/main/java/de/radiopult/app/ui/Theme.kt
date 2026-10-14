package de.radiopult.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB68A),
    onPrimary = Color(0xFF522300),
    primaryContainer = Color(0xFF743400),
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondaryContainer = Color(0xFF3A2E27),
    onSecondaryContainer = Color(0xFFF5DED3),
    background = Color(0xFF16181D),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF16181D),
    onSurface = Color(0xFFE6E1E5),
    surfaceContainer = Color(0xFF20232A),
    surfaceContainerHigh = Color(0xFF2A2D35),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFA04100),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC8),
    onPrimaryContainer = Color(0xFF341100),
    secondaryContainer = Color(0xFFF5DED3),
    onSecondaryContainer = Color(0xFF2B160C),
)

@Composable
fun RadioPultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
