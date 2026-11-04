package com.sharfo.myplans.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val LightColors = lightColorScheme(
    primary = BlueprintBlue,
    secondary = BlueprintLightBlue,
    tertiary = BlueprintAccent,
    background = SurfaceLight,
    surface = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF64B5F6),
    secondary = Color(0xFF42A5F5),
    tertiary = BlueprintAccent,
    background = SurfaceDark,
    surface = Color(0xFF1A242F)
)

@Composable
fun MyPlansTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content
        )
    }
}
