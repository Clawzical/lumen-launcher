package dev.lumen.launcher.core.version

import dev.lumen.launcher.core.instance.ModLoader
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class VersionManifest(val latest: LatestVersions, val versions: List<GameVersion>) {
    /**
     * Whether [loader] exists for [versionId]: compared by release time against the loader's
     * oldest version, which also covers snapshots and the year-based 26.x numbering.
     * Unknown versions get the benefit of the doubt.
     */
    fun supports(loader: ModLoader, versionId: String): Boolean {
        val oldest = loader.oldestGameVersion ?: return true
        val version = versions.firstOrNull { it.id == versionId } ?: return true
        val floor = versions.firstOrNull { it.id == oldest } ?: return true
        return version.releaseTime >= floor.releaseTime
    }
}

@Serializable
data class LatestVersions(val release: String, val snapshot: String)

@Serializable
data class GameVersion(
    val id: String,
    val type: String, // release | snapshot | old_beta | old_alpha
    val url: String,
    val releaseTime: String,
    val sha1: String? = null,
) {
    val isRelease: Boolean get() = type == "release"
    val releaseDate: String get() = releaseTime.take(10)
}

/** Mojang's official version list (newest first). Fetched once per session and memoised. */
class VersionRepository(
    private val http: HttpClient,
    private val manifestUrl: String = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
) {
    private val mutex = Mutex()
    private var cached: VersionManifest? = null

    suspend fun manifest(): VersionManifest = mutex.withLock {
        cached ?: http.get(manifestUrl).body<VersionManifest>().also { cached = it }
    }
}
