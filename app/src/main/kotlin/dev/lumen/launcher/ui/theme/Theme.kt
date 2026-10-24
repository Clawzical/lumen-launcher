package dev.lumen.launcher.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object LumenColors {
    val Background = Color(0xFF0E1014)
    val Surface = Color(0xFF15181E)
    val SurfaceHigh = Color(0xFF1C2028)
    val SurfaceHighest = Color(0xFF242934)
    val Border = Color(0xFF262B35)
    val Accent = Color(0xFF5BE49B)
    val AccentContainer = Color(0xFF173226)
    val TextPrimary = Color(0xFFE8EAED)
    val TextMuted = Color(0xFF8B93A1)
    val Danger = Color(0xFFFF6B6B)

    /** Deterministic avatar tints for instances without an icon. */
    val avatarPalette = listOf(
        Color(0xFF5BE49B), Color(0xFF6EA8FE), Color(0xFFB794F6),
        Color(0xFFF6AD55), Color(0xFFF687B3), Color(0xFF4FD1C5),
    )
}

private val colors = darkColorScheme(
    primary = LumenColors.Accent,
    onPrimary = Color(0xFF06140C),
    primaryContainer = LumenColors.AccentContainer,
    onPrimaryContainer = LumenColors.Accent,
    secondaryContainer = LumenColors.SurfaceHighest,
    onSecondaryContainer = LumenColors.TextPrimary,
    background = LumenColors.Background,
    onBackground = LumenColors.TextPrimary,
    surface = LumenColors.Surface,
    onSurface = LumenColors.TextPrimary,
    surfaceVariant = LumenColors.SurfaceHigh,
    onSurfaceVariant = LumenColors.TextMuted,
    surfaceContainerLowest = LumenColors.Background,
    surfaceContainerLow = LumenColors.Surface,
    surfaceContainer = LumenColors.Surface,
    surfaceContainerHigh = LumenColors.SurfaceHigh,
    surfaceContainerHighest = LumenColors.SurfaceHighest,
    outline = LumenColors.Border,
    outlineVariant = LumenColors.Border,
    error = LumenColors.Danger,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val typography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = shapes, typography = typography, content = content)
}
