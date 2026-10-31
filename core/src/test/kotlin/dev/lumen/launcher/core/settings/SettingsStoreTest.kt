package dev.lumen.launcher.core.settings

import dev.lumen.launcher.core.LauncherPaths
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStoreTest {
    @Test
    fun `settings survive a restart`() = runTest {
        val root = Files.createTempDirectory("lumen-settings")
        SettingsStore(LauncherPaths(root)).update { it.copy(language = Language.ZH, offlineName = "Steve_2", accountMode = AccountMode.MICROSOFT) }

        val reloaded = SettingsStore(LauncherPaths(root)).apply { load() }.settings.value
        assertEquals(Language.ZH, reloaded.language)
        assertEquals("Steve_2", reloaded.offlineName)
        assertEquals(AccountMode.MICROSOFT, reloaded.accountMode)
    }

    @Test
    fun `a broken file falls back to defaults`() = runTest {
        val root = Files.createTempDirectory("lumen-settings")
        root.resolve("settings.json").writeText("{ not json")
        assertEquals(LauncherSettings(), SettingsStore(LauncherPaths(root)).apply { load() }.settings.value)
    }

    @Test
    fun `preview changes memory but not disk`() = runTest {
        val root = Files.createTempDirectory("lumen-settings")
        val s = SettingsStore(LauncherPaths(root))
        s.preview { it.copy(backgroundDim = 0.8f) }
        assertEquals(0.8f, s.settings.value.backgroundDim)
        assertFalse(Files.exists(root.resolve("settings.json")))
    }

    @Test
    fun `player names follow vanilla's rules`() {
        listOf("Steve", "abc", "Player_123", "ABCDEFGHIJKLMNOP").forEach { assertTrue(LauncherSettings.isValidName(it), it) }
        listOf("", "ab", "ABCDEFGHIJKLMNOPQ", "has space", "名字名字", "dash-name").forEach { assertFalse(LauncherSettings.isValidName(it), it) }
    }
}
