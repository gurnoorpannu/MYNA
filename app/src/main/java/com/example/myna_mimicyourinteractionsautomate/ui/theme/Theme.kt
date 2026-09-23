package com.example.myna_mimicyourinteractionsautomate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Always light (the design is white); no dynamic colour so the demo looks the same on every phone. */
private val Scheme = lightColorScheme(
    primary = Ink, onPrimary = Color.White,
    secondary = Beak, onSecondary = Ink,
    secondaryContainer = BeakSoft, onSecondaryContainer = Ink,
    background = Canvas, onBackground = Ink,
    surface = Canvas, onSurface = Ink,
    surfaceVariant = Card, onSurfaceVariant = InkSoft,
    surfaceContainer = Canvas, surfaceContainerLow = Card, surfaceContainerHigh = Card,
    outline = Line, outlineVariant = Line,
    error = Bad,
)

@Composable
fun MYNAMimicYourINteractionsAutomateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography, content = content)
}
