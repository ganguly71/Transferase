package com.example.transferase.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TransferaseColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = DarkBg,
    primaryContainer = CardBgElevated,
    onPrimaryContainer = TextPrimary,
    secondary = AccentIndigo,
    onSecondary = DarkBg,
    secondaryContainer = CardBgSolid,
    onSecondaryContainer = TextSecondary,
    tertiary = EmeraldOnline,
    onTertiary = DarkBg,
    background = DarkBg,
    onBackground = TextPrimary,
    surface = CardBgSolid,
    onSurface = TextPrimary,
    surfaceVariant = CardBgElevated,
    onSurfaceVariant = TextSecondary,
    outline = CardBorder,
    outlineVariant = CardBorderGlow,
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
