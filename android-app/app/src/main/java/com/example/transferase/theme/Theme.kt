package com.example.transferase.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val TransferaseColorScheme = darkColorScheme(
    primary = CopperPrimary,
    onPrimary = DarkBg,
    primaryContainer = DarkCardElevated,
    onPrimaryContainer = CopperLight,
    secondary = AmberAccent,
    onSecondary = DarkBg,
    secondaryContainer = DarkCard,
    onSecondaryContainer = AmberGlow,
    tertiary = EmeraldOnline,
    onTertiary = DarkBg,
    background = DarkBg,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkCard,
    onSurfaceVariant = TextSecondary,
    outline = BorderCopper,
    outlineVariant = BorderSubtle,
    error = ErrorRed,
    onError = TextPrimary
)

@Composable
fun TransferaseTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = TransferaseColorScheme,
        typography = Typography,
        content = content
    )
}
