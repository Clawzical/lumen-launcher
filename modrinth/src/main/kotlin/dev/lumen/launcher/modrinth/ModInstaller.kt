package dev.lumen.launcher.modrinth

import dev.lumen.launcher.core.download.DownloadManager
import dev.lumen.launcher.core.download.DownloadTask
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.instance.InstancePaths
import dev.lumen.launcher.core.instance.ModLoader
import dev.lumen.launcher.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class ResolvedFile(val version: ModrinthVersion, val file: VersionFile, val requiredBy: String?)
data class UnresolvedDependency(val requiredBy: String, val dependency: Dependency, val reason: String)
data class Conflict(val projectA: String, val projectB: String)

data class InstallPlan(
    val files: List<ResolvedFile>,
    val unresolved: List<UnresolvedDependency>,
    val conflicts: List<Conflict>,
) {
    val totalBytes: Long get() = files.sumOf { it.file.size }
}

/** Per-instance record of what we installed, so re-resolution skips what's already there. */
@Serializable
data class InstalledContent(val projectId: String, val versionId: String, val filename: String, val folder: String)

@Serializable
data class ContentIndex(val entries: Map<String, InstalledContent> = emptyMap())

/**
 * Resolves the full required-dependency graph for a version, then downloads everything
 * in parallel through [DownloadManager]. Resolution is breadth-first and each BFS level is
 * fetched concurrently, so a mod with 6 deps costs ~1 round-trip per depth, not 6.
 */
class ModInstaller(
    private val api: ModrinthClient,
    private val downloads: DownloadManager,
) {
    private val indexLock = Mutex()

    suspend fun installLatest(projectIdOrSlug: String, type: ProjectType, instance: Instance, paths: InstancePaths): InstallPlan {
        val loader = instance.loader.takeIf { type == ProjectType.MOD }
        val root = api.bestVersion(projectIdOrSlug, loader, instance.gameVersion)
            ?: error("No version of '$projectIdOrSlug' supports ${instance.loader.displayName} ${instance.gameVersion}")
        return install(root, type, instance, paths)
    }

    suspend fun install(root: ModrinthVersion, type: ProjectType, instance: Instance, paths: InstancePaths): InstallPlan {
        require(type != ProjectType.MODPACK) { "Modpacks go through the .mrpack importer" }
        val index = readIndex(paths)
        val plan = resolve(root, instance.loader, instance.gameVersion, index.entries.keys)
        val targetDir = paths.gameDir.resolve(type.folder)

        downloads.downloadAll(plan.files.map { r ->
            DownloadTask(
                url = r.file.url,
                target = targetDir.resolve(r.file.filename),
                sha512 = r.file.hashes.sha512,
                size = r.file.size,
                label = r.version.name,
            )
        })

        // Parallel installs into one instance would otherwise race read-modify-write on the index.
        indexLock.withLock {
            val fresh = readIndex(paths)
            writeIndex(paths, fresh.copy(entries = fresh.entries + plan.files.associate { r ->
                r.version.projectId to InstalledContent(r.version.projectId, r.version.id, r.file.filename, type.folder)
            }))
        }
        return plan
    }

    /** Modrinth project ids already installed in this instance (mods, resource packs and shaders). */
    suspend fun installedProjectIds(paths: InstancePaths): Set<String> = readIndex(paths).entries.keys

    suspend fun resolve(
        root: ModrinthVersion,
        loader: ModLoader,
        gameVersion: String,
        alreadyInstalled: Set<String> = emptySet(),
    ): InstallPlan {
        val resolved = LinkedHashMap<String, ResolvedFile>()
        val unresolved = mutableListOf<UnresolvedDependency>()
        root.primaryFile?.let { resolved[root.projectId] = ResolvedFile(root, it, requiredBy = null) }

        var frontier = listOf(root)
        while (frontier.isNotEmpty()) {
            val pending = frontier
                .flatMap { parent -> parent.dependencies.filter { it.type == DependencyType.REQUIRED }.map { parent to it } }
                .filter { (_, dep) -> dep.projectId == null || (dep.projectId !in resolved && dep.projectId !in alreadyInstalled) }
                .distinctBy { (_, dep) -> dep.projectId ?: dep.versionId ?: dep.fileName }

            val fetched = coroutineScope {
                pending.map { (parent, dep) ->
                    async { Triple(parent, dep, runCatching { fetchDependency(parent, dep, loader, gameVersion) }) }
                }.awaitAll()
            }

            frontier = fetched.mapNotNull { (parent, dep, result) ->
                val version = result.getOrNull()
                val file = version?.primaryFile
                when {
                    version == null || file == null -> {
                        val reason = result.exceptionOrNull()?.message
                            ?: if (dep.projectId == null && dep.versionId == null) "external file '${dep.fileName}' (not hosted on Modrinth)"
                            else "no compatible version for ${loader.displayName} $gameVersion"
                        unresolved += UnresolvedDependency(parent.projectId, dep, reason)
                        null
                    }
                    version.projectId in resolved || version.projectId in alreadyInstalled -> null
                    else -> {
                        resolved[version.projectId] = ResolvedFile(version, file, requiredBy = parent.projectId)
                        version
                    }
                }
            }
        }

        val present = resolved.keys + alreadyInstalled
        val conflicts = resolved.values.flatMap { r ->
            r.version.dependencies
                .filter { it.type == DependencyType.INCOMPATIBLE && it.projectId in present }
                .map { Conflict(r.version.projectId, it.projectId!!) }
        }
        return InstallPlan(resolved.values.toList(), unresolved, conflicts)
    }

    private suspend fun fetchDependency(parent: ModrinthVersion, dep: Dependency, loader: ModLoader, gameVersion: String): ModrinthVersion? = when {
        dep.versionId != null -> api.getVersion(dep.versionId) // author pinned an exact version
        // Unpinned: take the newest release that existed when the parent shipped — the one its author
        // could have tested against. E.g. Iris 1.8.8 (Feb 2025) needs Sodium 0.6.x, not 0.8.x (2026).
        dep.projectId != null -> api.bestVersion(
            dep.projectId, loader, gameVersion,
            publishedBy = runCatching { Instant.parse(parent.datePublished) }.getOrNull(),
        )
        else -> null
    }

    private fun indexFile(paths: InstancePaths): Path = paths.root.resolve("content-index.json")

    private suspend fun readIndex(paths: InstancePaths): ContentIndex = withContext(Dispatchers.IO) {
        val f = indexFile(paths)
        if (f.exists()) Http.json.decodeFromString(ContentIndex.serializer(), f.readText()) else ContentIndex()
    }

    private suspend fun writeIndex(paths: InstancePaths, index: ContentIndex) = withContext(Dispatchers.IO) {
        val f = indexFile(paths)
        val tmp = f.resolveSibling("${f.fileName}.tmp")
        tmp.writeText(Http.json.encodeToString(ContentIndex.serializer(), index))
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
