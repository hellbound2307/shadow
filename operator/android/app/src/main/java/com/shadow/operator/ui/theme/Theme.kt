package com.shadow.operator.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark C2 color palette — black, terminal green, red accents
val BgBlack = Color(0xFF0A0A0A)
val SurfaceDark = Color(0xFF111111)
val SurfaceElevated = Color(0xFF1A1A1A)
val AccentGreen = Color(0xFF00E676)
val AccentRed = Color(0xFFFF1744)
val AccentAmber = Color(0xFFFFAB00)
val AccentBlue = Color(0xFF2979FF)
val AccentPurple = Color(0xFFAA00FF)
val TextPrimary = Color(0xFFE0E0E0)
val TextSecondary = Color(0xFF888888)
val OnlineGreen = Color(0xFF00C853)
val OfflineRed = Color(0xFFD50000)

@Composable
fun ShadowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AccentGreen,
            onPrimary = Color.Black,
            secondary = AccentBlue,
            onSecondary = Color.White,
            tertiary = AccentPurple,
            background = BgBlack,
            onBackground = TextPrimary,
            surface = SurfaceDark,
            onSurface = TextPrimary,
            surfaceVariant = SurfaceElevated,
            onSurfaceVariant = TextSecondary,
            error = AccentRed,
            outline = Color(0xFF333333),
        ),
        content = content
    )
}
