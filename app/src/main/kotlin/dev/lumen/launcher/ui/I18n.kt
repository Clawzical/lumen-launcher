package dev.lumen.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.lumen.launcher.core.settings.Language

/** Current UI language; provided at the root of the window from the saved settings. */
val LocalLanguage = staticCompositionLocalOf { Language.EN }

/**
 * Bilingual text, written inline at the call site: `tr("Play", "启动")`.
 * Keeping both strings side by side makes it obvious when one is missing or out of date.
 */
@Composable
@ReadOnlyComposable
fun tr(en: String, zh: String): String = if (LocalLanguage.current == Language.ZH) zh else en

/** Same, for code outside composition (status messages): pass the language explicitly. */
fun tr(language: Language, en: String, zh: String): String = if (language == Language.ZH) zh else en
