package dev.shizukumcp.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF10110E)
val InkRaised = Color(0xFF1A1C16)
val Paper = Color(0xFFF4F1E6)
val Citron = Color(0xFFD6F25C)
val CitronDeep = Color(0xFFC3E24A)
val Coral = Color(0xFFFF5D3A)
val Mist = Color(0xFFB7BBA8)
val Line = Color(0xFF2C2F26)

private val Colors = darkColorScheme(
    primary = Citron,
    onPrimary = Ink,
    secondary = CitronDeep,
    background = Ink,
    surface = InkRaised,
    onBackground = Paper,
    onSurface = Paper,
    error = Coral,
    outline = Line,
)

@Composable
fun ShizukuMcpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
