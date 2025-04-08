package com.yourusername.arcoredetection.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Define all colors directly to avoid any reference issues
private val Purple80 = Color(0xFFD0BCFF)
private val PurpleGrey80 = Color(0xFFCCC2DC)
private val Pink80 = Color(0xFFEFB8C8)
private val Purple40 = Color(0xFF6650a4)
private val PurpleGrey40 = Color(0xFF625b71)
private val Pink40 = Color(0xFF7D5260)
private val DarkBackground = Color(0xFF1E293B)
private val LightBackground = Color(0xFFF8FAFC)
private val White = Color(0xFFFFFFFF)
private val Black = Color(0xFF000000)

// Define dark color palette
private val DarkColorPalette = androidx.compose.material.darkColors(
    primary = Purple80,
    primaryVariant = PurpleGrey80,
    secondary = Pink80,
    background = DarkBackground,
    surface = DarkBackground,
    onPrimary = White,
    onSecondary = White,
    onBackground = White,
    onSurface = White
)

// Define light color palette
private val LightColorPalette = androidx.compose.material.lightColors(
    primary = Purple40,
    primaryVariant = PurpleGrey40,
    secondary = Pink40,
    background = LightBackground,
    surface = LightBackground,
    onPrimary = Black,
    onSecondary = Black,
    onBackground = Black,
    onSurface = Black
)

@Composable
fun ARCoreDetectionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) {
        DarkColorPalette
    } else {
        LightColorPalette
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colors = colors,
        typography = Typography,
        content = content
    )
}