package com.example.pdfconverter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = TealGlow,
    onPrimary = Color(0xFF00382F),
    secondary = IndigoGlow,
    onSecondary = Color(0xFF10162A),
    background = NightInk,
    onBackground = SoftIvory,
    surface = NightCard,
    onSurface = SoftIvory,
    surfaceVariant = Color(0xFF1B2740),
    onSurfaceVariant = Color(0xFFC5D0E3),
    outline = NightStroke,
    error = Danger,
    tertiary = WarmAmber
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    secondary = Color(0xFF4338CA),
    onSecondary = Color.White,
    background = Color(0xFFF3F6FB),
    onBackground = LightInk,
    surface = LightCard,
    onSurface = LightInk,
    surfaceVariant = Color(0xFFE7EEF8),
    onSurfaceVariant = Color(0xFF44546B),
    outline = Color(0xFFCDD7E6),
    error = Color(0xFFB42318),
    tertiary = Color(0xFFB45309)
)

@Composable
fun PdfConverterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
