package dev.lumen.launcher.installer

import dev.lumen.launcher.core.LauncherPaths
import dev.lumen.launcher.core.download.DownloadManager
import dev.lumen.launcher.core.download.DownloadTask
import dev.lumen.launcher.core.net.Http
import dev.lumen.launcher.core.version.VersionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** A version with its whole `inheritsFrom` chain flattened into what the launcher needs. */
data class ResolvedVersion(
    val id: String,
    /** The vanilla version at the bottom of the chain — owner of the client jar and assets. */
    val rootId: String,
    val type: String,
    val mainClass: String,
    /** Already filtered by OS rules and de-duplicated, child (loader) entries first. */
    val libraries: List<Library>,
    val jvmArgs: List<JsonElement>,
    val gameArgs: List<JsonElement>,
    val legacyArguments: String?,
    val assetIndex: AssetIndexRef,
    val clientJar: Artifact,
    val javaVersion: JavaVersionRef,
    val logging: LoggingConfig?,
)

/** Version JSONs live at `shared/versions/<id>/<id>.json`, the same layout the official launcher uses. */
class VersionResolver(
    private val paths: LauncherPaths,
    private val downloads: DownloadManager,
    private val versions: VersionRepository,
    private val platform: PlatformInfo = PlatformInfo.current,
) {
    fun jsonPath(id: String): Path = paths.versions.resolve(id).resolve("$id.json")

    fun hasLocal(id: String): Boolean = jsonPath(id).exists()

    suspend fun save(id: String, rawJson: String) = withContext(Dispatchers.IO) {
        val target = jsonPath(id)
        Files.createDirectories(target.parent)
        target.writeText(rawJson)
    }

    /** Fetches (sha1-verified) and caches a vanilla version JSON from Mojang's manifest. */
    suspend fun ensureVanilla(id: String): VersionJson {
        if (!hasLocal(id)) {
            val entry = versions.manifest().versions.firstOrNull { it.id == id }
                ?: error("Minecraft $id isn't in Mojang's version list")
            downloads.download(DownloadTask(entry.url, jsonPath(id), sha1 = entry.sha1, label = "Minecraft $id metadata"))
        }
        return read(id)
    }

    suspend fun resolve(id: String): ResolvedVersion {
        val chain = mutableListOf<VersionJson>()
        var next: String? = id
        while (next != null) {
            check(chain.size < 8) { "inheritsFrom chain too deep at $next" }
            val json = if (hasLocal(next)) read(next) else ensureVanilla(next)
            chain += json
            next = json.inheritsFrom
        }
        val root = chain.last()

        return ResolvedVersion(
            id = id,
            rootId = root.id,
            type = chain.firstNotNullOfOrNull { it.type } ?: "release",
            mainClass = chain.firstNotNullOfOrNull { it.mainClass } ?: error("$id has no mainClass"),
            // Filter by OS first: vanilla lists the same artifact several times with different OS rules.
            libraries = chain.flatMap { it.libraries }
                .filter { Rules.allowed(it.rules, platform) }
                .distinctBy { MavenCoordinate.parse(it.name).dedupeKey },
            jvmArgs = chain.asReversed().flatMap { it.arguments?.jvm.orEmpty() },
            gameArgs = chain.asReversed().flatMap { it.arguments?.game.orEmpty() },
            legacyArguments = chain.firstNotNullOfOrNull { it.minecraftArguments },
            assetIndex = chain.firstNotNullOfOrNull { it.assetIndex } ?: error("$id has no asset index"),
            clientJar = root.downloads["client"] ?: error("${root.id} has no client download"),
            javaVersion = chain.firstNotNullOfOrNull { it.javaVersion } ?: JavaVersionRef(),
            logging = chain.firstNotNullOfOrNull { it.logging?.client },
        )
    }

    private suspend fun read(id: String): VersionJson = withContext(Dispatchers.IO) {
        Http.json.decodeFromString(VersionJson.serializer(), jsonPath(id).readText())
    }
}
