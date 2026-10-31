package dev.lumen.launcher.core.settings

import dev.lumen.launcher.core.LauncherPaths
import dev.lumen.launcher.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable enum class Language { EN, ZH }

@Serializable enum class ThemeMode { LIGHT, DARK }

/** How Java Edition is launched: with the signed-in Microsoft account, or offline under [LauncherSettings.offlineName]. */
@Serializable enum class AccountMode { MICROSOFT, OFFLINE }

@Serializable
data class LauncherSettings(
    val language: Language = Language.EN,
    val theme: ThemeMode = ThemeMode.LIGHT,
    /** Absolute path of the user's own background image; null = Lumen's plain default. */
    val backgroundPath: String? = null,
    /** 0 = image as-is, 1 = fully covered by the theme colour. */
    val backgroundDim: Float = 0.25f,
    val backgroundBlur: Float = 0f,
    val accountMode: AccountMode = AccountMode.OFFLINE,
    val offlineName: String = "Player",
) {
    companion object {
        /** What vanilla accepts as a player name: 3–16 letters, digits or underscores. */
        fun isValidName(name: String) = name.length in 3..16 && name.all { it.isLetterOrDigit() && it.code < 128 || it == '_' }
    }
}

/** `%APPDATA%\Lumen\settings.json`. Unknown or broken files fall back to defaults instead of failing. */
class SettingsStore(paths: LauncherPaths) {
    private val file = paths.root.resolve("settings.json")
    private val _settings = MutableStateFlow(LauncherSettings())
    val settings: StateFlow<LauncherSettings> = _settings.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (file.exists()) {
            runCatching { _settings.value = Http.json.decodeFromString(LauncherSettings.serializer(), file.readText()) }
        }
    }

    /** In-memory only, for live previews (slider drags); call [update] to keep the result. */
    fun preview(transform: (LauncherSettings) -> LauncherSettings) {
        _settings.value = transform(_settings.value)
    }

    suspend fun update(transform: (LauncherSettings) -> LauncherSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        withContext(Dispatchers.IO) {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("settings.json.tmp")
            tmp.writeText(Http.json.encodeToString(LauncherSettings.serializer(), next))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
