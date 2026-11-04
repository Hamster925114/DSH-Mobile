package com.dshmobile.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6FED),
    onPrimary = Color.White,
    secondary = Color(0xFF00796B),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF002B5C),
    secondary = Color(0xFF4DD0C4),
    background = Color(0xFF0F1115),
    surface = Color(0xFF171A21),
)

@Composable
fun DshMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
