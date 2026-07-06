package com.nethal.lab.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Design brief exige dark theme by default; sem toggle de tema claro nesta entrega.
private val DarkColors = darkColorScheme(
    primary = NetHalCyan,
    secondary = NetHalTeal,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onBackground = OnBackgroundDark,
    onSurface = OnBackgroundDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    error = ErrorDark,
)

@Composable
fun NetHalLabTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = NetHalTypography,
        content = content,
    )
}
