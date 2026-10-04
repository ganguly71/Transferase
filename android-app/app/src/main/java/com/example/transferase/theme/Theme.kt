package com.example.transferase.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val TransferaseColorScheme = lightColorScheme(
    primary = WebAccentDark,
    onPrimary = WebTextLight,
    primaryContainer = WebCardBg,
    onPrimaryContainer = WebTextPrimary,
    secondary = WebCopper,
    onSecondary = WebTextLight,
    secondaryContainer = WebCardInnerBg,
    onSecondaryContainer = WebTextPrimary,
    tertiary = WebSuccess,
    onTertiary = WebTextLight,
    background = WebBgColor,
    onBackground = WebTextLight,
    surface = WebCardBgSolid,
    onSurface = WebTextPrimary,
    surfaceVariant = WebCardInnerBg,
    onSurfaceVariant = WebTextSecondary,
    outline = WebCardInsetBorder,
    outlineVariant = WebCardBorder,
    error = WebError,
    onError = WebTextLight
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
