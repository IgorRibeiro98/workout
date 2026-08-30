package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SophisticatedDarkColorScheme =
  darkColorScheme(
    primary = Lime400,
    onPrimary = BackgroundDark,
    secondary = Emerald500,
    onSecondary = BackgroundDark,
    tertiary = Lime500,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = TextSecondary,
    outline = BorderLight
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force dark theme for Sophisticated Dark
  dynamicColor: Boolean = false, // Disable dynamic colors to enforce the theme
  content: @Composable () -> Unit,
) {
  val colorScheme = SophisticatedDarkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
