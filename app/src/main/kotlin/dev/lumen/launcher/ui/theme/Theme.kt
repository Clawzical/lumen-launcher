package dev.lumen.launcher.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.launcher.core.settings.ThemeMode

object LumenColors {
    /** Deterministic avatar tints for instances without an icon. */
    val avatarPalette = listOf(
        Color(0xFF2F7DF6), Color(0xFF22B573), Color(0xFF8B5CF6),
        Color(0xFFF59E0B), Color(0xFFEC4899), Color(0xFF14B8A6),
    )
}

/** Colours outside Material's scheme: the title bar and what sits on it. */
@Immutable
data class ChromeColors(
    val titleBar: Brush,
    val onTitleBar: Color,
    val tabSelected: Color,
    val onTabSelected: Color,
    /** Default page background when no custom image is set. */
    val pageBackground: Brush,
    /** Frosted panel fill drawn over the background (translucent so a custom image shows through). */
    val panel: Color,
)

val LocalChrome = staticCompositionLocalOf<ChromeColors> { error("LumenTheme not applied") }

// Light: the PCL-style look — pale blue page, white frosted panels, blue title bar.
private val lightScheme = lightColorScheme(
    primary = Color(0xFF1F6FE0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF0F4EA8),
    secondaryContainer = Color(0xFFE4EEFA),
    onSecondaryContainer = Color(0xFF1B2430),
    background = Color(0xFFEAF2FC),
    onBackground = Color(0xFF1B2430),
    surface = Color(0xD9FFFFFF),
    onSurface = Color(0xFF1B2430),
    surfaceVariant = Color(0xFFEEF4FB),
    onSurfaceVariant = Color(0xFF5C6B7E),
    surfaceContainerLowest = Color(0xFFF7FAFE),
    surfaceContainerLow = Color(0xFFF4F8FD),
    surfaceContainer = Color(0xFFF7FAFE),
    surfaceContainerHigh = Color(0xFFFAFCFF),
    surfaceContainerHighest = Color(0xFFE6EEF8),
    outline = Color(0xFFD3DFEE),
    outlineVariant = Color(0xFFDCE6F2),
    error = Color(0xFFD93636),
)

private val lightChrome = ChromeColors(
    titleBar = Brush.horizontalGradient(listOf(Color(0xFF1A5FCF), Color(0xFF2E83F2))),
    onTitleBar = Color.White,
    tabSelected = Color.White,
    onTabSelected = Color(0xFF1A5FCF),
    pageBackground = Brush.linearGradient(listOf(Color(0xFFF3F8FF), Color(0xFFDDEAFB))),
    panel = Color(0xD9FFFFFF),
)

// Dark: Lumen's original look.
private val darkScheme = darkColorScheme(
    primary = Color(0xFF5BA8FF),
    onPrimary = Color(0xFF041A33),
    primaryContainer = Color(0xFF14304F),
    onPrimaryContainer = Color(0xFF7DBBFF),
    secondaryContainer = Color(0xFF242934),
    onSecondaryContainer = Color(0xFFE8EAED),
    background = Color(0xFF0E1014),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xE615181E),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF1C2028),
    onSurfaceVariant = Color(0xFF8B93A1),
    surfaceContainerLowest = Color(0xFF0E1014),
    surfaceContainerLow = Color(0xFF15181E),
    surfaceContainer = Color(0xFF15181E),
    surfaceContainerHigh = Color(0xFF1C2028),
    surfaceContainerHighest = Color(0xFF242934),
    outline = Color(0xFF262B35),
    outlineVariant = Color(0xFF262B35),
    error = Color(0xFFFF6B6B),
)

private val darkChrome = ChromeColors(
    titleBar = Brush.horizontalGradient(listOf(Color(0xFF12161D), Color(0xFF151A22))),
    onTitleBar = Color(0xFFE8EAED),
    tabSelected = Color(0xFF14304F),
    onTabSelected = Color(0xFF7DBBFF),
    pageBackground = Brush.linearGradient(listOf(Color(0xFF0E1014), Color(0xFF121722))),
    panel = Color(0xE615181E),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

private val typography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun LumenTheme(mode: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
    val (scheme, chrome) = if (mode == ThemeMode.DARK) darkScheme to darkChrome else lightScheme to lightChrome
    CompositionLocalProvider(LocalChrome provides chrome) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography, content = content)
    }
}
