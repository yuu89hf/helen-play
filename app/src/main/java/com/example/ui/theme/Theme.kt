package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme =
  lightColorScheme(
    primary = HelenPrimary,
    secondary = HelenBlue,
    tertiary = HelenPink,
    background = HelenBgStart,
    surface = HelenSurface,
    surfaceVariant = HelenSurfaceVariant,
    onPrimary = Color.White,
    onSecondary = HelenTextPrimary,
    onBackground = HelenTextPrimary,
    onSurface = HelenTextPrimary,
    onSurfaceVariant = HelenTextSecondary
  )

private val DarkColorScheme = LightColorScheme

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = false,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = LightColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

