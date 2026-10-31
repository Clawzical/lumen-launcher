package dev.lumen.launcher.core.version

import dev.lumen.launcher.core.instance.ModLoader
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionManifestTest {
    private fun v(id: String, time: String, type: String = "release") = GameVersion(id, type, "https://example.invalid/$id.json", time)

    // Newest first, like Mojang's manifest.
    private val manifest = VersionManifest(
        LatestVersions("26.1", "26w44a"),
        listOf(
            v("26w44a", "2026-10-28T12:00:00+00:00", "snapshot"),
            v("26.1", "2026-03-24T12:00:00+00:00"),
            v("1.20.1", "2023-06-12T13:25:51+00:00"),
            v("1.20", "2023-06-02T08:36:17+00:00"),
            v("1.14", "2019-04-23T14:52:44+00:00"),
            v("1.8.9", "2015-12-03T09:24:39+00:00"),
            v("1.7.10", "2014-05-14T17:29:23+00:00"),
            v("1.7.2", "2013-10-25T13:00:00+00:00"),
        ),
    )

    @Test
    fun `old versions run vanilla and forge only`() {
        assertTrue(manifest.supports(ModLoader.VANILLA, "1.8.9"))
        assertTrue(manifest.supports(ModLoader.FORGE, "1.8.9"))
        listOf(ModLoader.FABRIC, ModLoader.QUILT, ModLoader.NEOFORGE).forEach { assertFalse(manifest.supports(it, "1.8.9"), it.name) }
    }

    @Test
    fun `forge starts at 1_7_10`() {
        assertTrue(manifest.supports(ModLoader.FORGE, "1.7.10"))
        assertFalse(manifest.supports(ModLoader.FORGE, "1.7.2"))
    }

    @Test
    fun `each loader starts at its first supported release`() {
        assertTrue(manifest.supports(ModLoader.FABRIC, "1.14"))
        assertTrue(manifest.supports(ModLoader.QUILT, "1.14"))
        assertFalse(manifest.supports(ModLoader.NEOFORGE, "1.20"))
        assertTrue(manifest.supports(ModLoader.NEOFORGE, "1.20.1"))
    }

    @Test
    fun `year-numbered releases and snapshots compare by date`() {
        ModLoader.entries.forEach {
            assertTrue(manifest.supports(it, "26.1"), it.name)
            assertTrue(manifest.supports(it, "26w44a"), it.name)
        }
    }

    @Test
    fun `unknown versions are not blocked`() {
        assertTrue(manifest.supports(ModLoader.FABRIC, "27.0-custom"))
    }
}
