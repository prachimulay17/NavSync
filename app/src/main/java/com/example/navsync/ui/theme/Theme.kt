package com.example.navsync.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// NavSync uses a consistent dark navigation theme
private val NavSyncColorScheme = darkColorScheme(
    primary = ElectricBlue,
    secondary = BrightBlue,
    tertiary = AccentBlue,
    background = NavyDark,
    surface = NavyMedium,
    onPrimary = NavyDark,
    onSecondary = NavyDark,
    onTertiary = NavyDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun NavSyncTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = NavyDark.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = NavSyncColorScheme,
        typography = Typography,
        content = content
    )
}