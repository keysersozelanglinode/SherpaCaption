package com.sherpacaption.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Blue40,
    secondary = Green40,
    background = Neutral95,
    surface = Neutral95,
    onBackground = Neutral20,
    onSurface = Neutral20
)

private val DarkColorScheme = darkColorScheme(
    primary = Blue80,
    secondary = Green80,
    background = Neutral20,
    surface = Neutral20,
    onBackground = Neutral95,
    onSurface = Neutral95
)

@Composable
fun SherpaCaptionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content
    )
}
