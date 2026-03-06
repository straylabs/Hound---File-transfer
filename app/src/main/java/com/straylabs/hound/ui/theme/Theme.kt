package com.straylabs.hound.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary            = Teal700,
    onPrimary          = LightSurface,
    primaryContainer   = Teal50,
    onPrimaryContainer = Teal700,
    secondary          = Teal500,
    onSecondary        = LightSurface,
    background         = LightBackground,
    onBackground       = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
    surface            = LightSurface,
    onSurface          = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
    surfaceVariant     = androidx.compose.ui.graphics.Color(0xFFF0F0F0),
    onSurfaceVariant   = androidx.compose.ui.graphics.Color(0xFF616161),
    outline            = androidx.compose.ui.graphics.Color(0xFFBDBDBD),
)

private val DarkColors = darkColorScheme(
    primary            = Teal500,
    onPrimary          = DarkBackground,
    primaryContainer   = Teal700,
    onPrimaryContainer = Teal100,
    secondary          = Teal200,
    onSecondary        = DarkBackground,
    background         = DarkBackground,
    onBackground       = androidx.compose.ui.graphics.Color(0xFFE6E1E5),
    surface            = DarkSurface,
    onSurface          = androidx.compose.ui.graphics.Color(0xFFE6E1E5),
    surfaceVariant     = DarkSurfaceVar,
    onSurfaceVariant   = androidx.compose.ui.graphics.Color(0xFFAAAAAA),
    outline            = androidx.compose.ui.graphics.Color(0xFF5A5A5A),
)

@Composable
fun LANFileServerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content,
    )
}
