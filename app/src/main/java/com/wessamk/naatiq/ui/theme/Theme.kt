package com.wessamk.naatiq.ui.theme

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

private val Teal = Color(0xFF0E6E77)
private val TealLight = Color(0xFF7FDBDA)
private val Sand = Color(0xFF8A6A2F)

private val LightScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6EBEB),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Sand,
    onSecondary = Color.White,
    tertiary = Color(0xFF4B5F86),
    background = Color(0xFFFAFDFC),
    surface = Color(0xFFFAFDFC),
    surfaceVariant = Color(0xFFDCE5E4),
)

private val DarkScheme = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00373A),
    primaryContainer = Color(0xFF005055),
    onPrimaryContainer = Color(0xFFB6EBEB),
    secondary = Color(0xFFE7C48A),
    onSecondary = Color(0xFF3F2E04),
    tertiary = Color(0xFFB4C5F0),
    background = Color(0xFF0E1414),
    surface = Color(0xFF0E1414),
    surfaceVariant = Color(0xFF3F4948),
)

/** Colours used to mark the sentence and the word being spoken. */
data class HighlightColors(val sentence: Color, val word: Color)

@Composable
fun rememberHighlightColors(dark: Boolean = isSystemInDarkTheme()): HighlightColors =
    if (dark) {
        HighlightColors(sentence = Color(0x33FFE082), word = Color(0x99FFC107))
    } else {
        HighlightColors(sentence = Color(0x33FFC107), word = Color(0x88FFB300))
    }

@Composable
fun NaatiqTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = colors, content = content)
}
