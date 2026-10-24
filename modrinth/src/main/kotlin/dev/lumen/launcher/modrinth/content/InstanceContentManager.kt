package dev.lumen.launcher.modrinth.content

import dev.lumen.launcher.core.download.DownloadManager
import dev.lumen.launcher.core.download.DownloadTask
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.instance.InstancePaths
import dev.lumen.launcher.core.instance.ModLoader
import dev.lumen.launcher.modrinth.ContentIndexStore
import dev.lumen.launcher.modrinth.DependencyType
import dev.lumen.launcher.modrinth.InstalledContent
import dev.lumen.launcher.modrinth.ModInstaller
import dev.lumen.launcher.modrinth.ModrinthClient
import dev.lumen.launcher.modrinth.ModrinthVersion
import dev.lumen.launcher.modrinth.ProjectType
import dev.lumen.launcher.modrinth.VersionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

enum class ContentKind(val folder: String, val projectType: ProjectType, val extension: String) {
    MOD("mods", ProjectType.MOD, ".jar"),
    RESOURCE_PACK("resourcepacks", ProjectType.RESOURCE_PACK, ".zip"),
    SHADER("shaderpacks", ProjectType.SHADER, ".zip"),
}

/** One file in an instance's mods / resourcepacks / shaderpacks folder. */
data class ContentItem(
    val kind: ContentKind,
    /** Enabled file name (a disabled file is this plus `.disabled`). */
    val fileName: String,
    val enabled: Boolean,
    val sha1: String,
    val sizeBytes: Long,
    val projectId: String?,
    val versionId: String?,
    val title: String,
    val iconUrl: String?,
    val versionNumber: String?,
    /** Release channel of the installed version; updates never move to a less stable one. */
    val versionType: VersionType? = null,
    /** Fabric/Quilt descriptor for mod jars; null for packs, NeoForge mods and non-mod jars. */
    val meta: FabricModMeta?,
) {
    val key: String get() = "${kind.folder}/$fileName"
    val currentName: String get() = if (enabled) fileName else "$fileName$DISABLED"
}

/** [heldBack] explains why updating would break another mod (e.g. "Iris needs Sodium 0.6.x"). */
data class UpdateInfo(val version: ModrinthVersion, val heldBack: String?)

const val DISABLED = ".disabled"

/**
 * Everything the instance detail screen does to an instance's content. Modrinth is optional:
 * offline, files still list, toggle and delete; only names/icons, updates and swaps need it.
 */
class InstanceContentManager(
    private val api: ModrinthClient,
    private val downloads: DownloadManager,
    private val index: ContentIndexStore,
    private val installer: ModInstaller,
) {
    private data class ScannedFile(val kind: ContentKind, val path: Path, val baseName: String, val enabled: Boolean, val sha1: String, val size: Long)

    suspend fun list(instance: Instance, paths: InstancePaths): List<ContentItem> {
        val files = withContext(Dispatchers.IO) { ContentKind.entries.flatMap { scan(paths, it) } }
        val onDisk = files.map { "${it.kind.folder}/${it.baseName}" }.toSet()

        // Recognise hand-added files by hash, and forget entries whose file was deleted outside Lumen.
        val known = index.read(paths).entries.values.map { "${it.folder}/${it.filename}" }.toSet()
        val unknown = files.filter { "${it.kind.folder}/${it.baseName}" !in known }
        val identified = runCatching { api.versionsByHashes(unknown.map { it.sha1 }) }.getOrDefault(emptyMap())
        val idx = index.update(paths) { cur ->
            val kept = cur.entries.filterValues { "${it.folder}/${it.filename}" in onDisk }
            val adopted = identified.mapNotNull { (sha, v) ->
                val f = unknown.firstOrNull { it.sha1 == sha } ?: return@mapNotNull null
                v.projectId to InstalledContent(v.projectId, v.id, f.baseName, f.kind.folder)
            }
            cur.copy(entries = kept + adopted)
        }
        val entryByFile = idx.entries.values.associateBy { "${it.folder}/${it.filename}" }

        val projects = runCatching { api.getProjects(entryByFile.values.map { it.projectId }.distinct()) }
            .getOrDefault(emptyList()).associateBy { it.id }
        val versions = identified.values.associateBy { it.id } +
            runCatching { api.getVersions(entryByFile.values.map { it.versionId }.filter { it !in identified.values.map { v -> v.id } }) }
                .getOrDefault(emptyList()).associateBy { it.id }

        val readMeta = instance.loader == ModLoader.FABRIC || instance.loader == ModLoader.QUILT
        return withContext(Dispatchers.IO) {
            files.map { f ->
                val entry = entryByFile["${f.kind.folder}/${f.baseName}"]
                val meta = if (readMeta && f.kind == ContentKind.MOD) FabricModReader.read(f.path) else null
                val project = entry?.let { projects[it.projectId] }
                ContentItem(
                    kind = f.kind,
                    fileName = f.baseName,
                    enabled = f.enabled,
                    sha1 = f.sha1,
                    sizeBytes = f.size,
                    projectId = entry?.projectId,
                    versionId = entry?.versionId,
                    title = project?.title ?: meta?.name ?: f.baseName.substringBeforeLast('.'),
                    iconUrl = project?.iconUrl,
                    versionNumber = entry?.let { versions[it.versionId]?.versionNumber } ?: meta?.version,
                    versionType = entry?.let { versions[it.versionId]?.versionType },
                    meta = meta,
                )
            }.sortedWith(compareBy({ it.kind.ordinal }, { it.title.lowercase() }))
        }
    }

    suspend fun setEnabled(paths: InstancePaths, item: ContentItem, enabled: Boolean): ContentItem = withContext(Dispatchers.IO) {
        if (item.enabled == enabled) return@withContext item
        val dir = folder(paths, item.kind)
        val target = if (enabled) item.fileName else item.fileName + DISABLED
        Files.move(dir.resolve(item.currentName), dir.resolve(target), StandardCopyOption.REPLACE_EXISTING)
        item.copy(enabled = enabled)
    }

    suspend fun remove(paths: InstancePaths, item: ContentItem) {
        withContext(Dispatchers.IO) { Files.deleteIfExists(folder(paths, item.kind).resolve(item.currentName)) }
        index.update(paths) { cur -> cur.copy(entries = cur.entries.filterValues { !(it.folder == item.kind.folder && it.filename == item.fileName) }) }
    }

    /** Newest compatible version per item (by key), with a reason when taking it would break a dependent. */
    suspend fun checkUpdates(instance: Instance, items: List<ContentItem>): Map<String, UpdateInfo> {
        val mods = modEntries(items)
        val result = HashMap<String, UpdateInfo>()
        for (kind in ContentKind.entries) {
            val group = items.filter { it.kind == kind && it.projectId != null }
            if (group.isEmpty()) continue
            val loaders = when (kind) {
                ContentKind.MOD -> loaderIds(instance.loader)
                ContentKind.SHADER -> listOf("iris", "optifine", "canvas")
                ContentKind.RESOURCE_PACK -> listOf("minecraft")
            }
            val latest = api.latestVersionsByHashes(group.map { it.sha1 }, loaders, listOf(instance.gameVersion))
            for (item in group) {
                var v = latest[item.sha1] ?: continue
                // Modrinth's "latest" includes betas. A stable install only updates to stable (and so on).
                val channel = item.versionType ?: VersionType.RELEASE
                if (v.versionType.ordinal > channel.ordinal) {
                    v = candidateVersions(instance, item).firstOrNull { it.versionType.ordinal <= channel.ordinal } ?: continue
                }
                if (v.id == item.versionId) continue
                val blocker = (if (kind == ContentKind.MOD) blockerAmong(item, v, mods, instance.gameVersion) else null)
                    ?: pinnedConflict(v, items)
                result[item.key] = UpdateInfo(v, blocker)
            }
        }
        return result
    }

    /** Versions to offer in "Change version", newest first. */
    suspend fun candidateVersions(instance: Instance, item: ContentItem): List<ModrinthVersion> {
        val projectId = item.projectId ?: return emptyList()
        val loader = instance.loader.takeIf { item.kind == ContentKind.MOD }
        return api.getProjectVersions(projectId, loader, instance.gameVersion)
    }

    /** Why [version] of [item] would break another enabled mod, or null if it wouldn't. */
    fun blockingRequirement(item: ContentItem, version: ModrinthVersion, items: List<ContentItem>, gameVersion: String): String? =
        blockerAmong(item, version, modEntries(items), gameVersion)

    private fun blockerAmong(item: ContentItem, version: ModrinthVersion, mods: List<ModEntry>, gameVersion: String): String? =
        Compatibility.requirementsOn(item.key, mods)
            .firstOrNull { (_, alts) -> !FabricVersions.anyTokenSatisfies(version.versionNumber, alts, setOf(gameVersion)) }
            ?.let { (dependent, alts) -> "${dependent.displayName} needs ${item.title} ${FabricVersions.describe(alts)}" }

    /** [version] requires an exact version of something installed here, and a different one is installed. */
    private fun pinnedConflict(version: ModrinthVersion, items: List<ContentItem>): String? =
        version.dependencies
            .filter { it.type == DependencyType.REQUIRED && it.projectId != null && it.versionId != null }
            .firstNotNullOfOrNull { dep ->
                items.firstOrNull { it.projectId == dep.projectId && it.versionId != dep.versionId }
                    ?.let { "${version.versionNumber} needs a different ${it.title} version" }
            }

    /** Replaces [item]'s file with [version], keeping its enabled/disabled state. */
    suspend fun swap(paths: InstancePaths, item: ContentItem, version: ModrinthVersion): ContentItem {
        val file = version.primaryFile ?: error("${version.name} has no downloadable file")
        val dir = folder(paths, item.kind)
        downloads.download(DownloadTask(file.url, dir.resolve(file.filename), sha512 = file.hashes.sha512, size = file.size, label = version.name))
        withContext(Dispatchers.IO) {
            if (file.filename != item.fileName) {
                Files.deleteIfExists(dir.resolve(item.fileName))
                Files.deleteIfExists(dir.resolve(item.fileName + DISABLED))
            }
            if (!item.enabled) Files.move(dir.resolve(file.filename), dir.resolve(file.filename + DISABLED), StandardCopyOption.REPLACE_EXISTING)
        }
        index.update(paths) { cur ->
            val others = cur.entries.filterValues { !(it.folder == item.kind.folder && it.filename == item.fileName) }
            cur.copy(entries = others + (version.projectId to InstalledContent(version.projectId, version.id, file.filename, item.kind.folder)))
        }
        return item.copy(fileName = file.filename, versionId = version.id, versionNumber = version.versionNumber, versionType = version.versionType, sha1 = file.hashes.sha1)
    }

    fun issues(instance: Instance, items: List<ContentItem>): List<CompatIssue> =
        if (instance.loader == ModLoader.FABRIC || instance.loader == ModLoader.QUILT) {
            Compatibility.check(modEntries(items), instance.gameVersion, instance.loaderVersion)
        } else emptyList()

    /**
     * Applies the automatic fix for [issue] and returns a message for the player:
     *  - wrong version → switch the provider to the newest Modrinth version every dependent accepts
     *  - missing → install the dependency from Modrinth (mod id used as the project slug)
     *  - wrong game / breaks → disable the mod that declares it
     */
    suspend fun fix(instance: Instance, paths: InstancePaths, items: List<ContentItem>, issue: CompatIssue): String = when (issue) {
        is CompatIssue.WrongVersion -> {
            val provider = items.first { it.key == issue.providerKey }
            val projectId = provider.projectId ?: error("${provider.title} isn't from Modrinth, so Lumen can't pick another version")
            val ownIds = provider.meta?.let { listOf(it.id) + it.provides }.orEmpty()
            check(issue.depId in ownIds) { "${issue.depName} is bundled inside ${provider.title}; update ${provider.title} instead" }
            val requirements = Compatibility.requirementsOn(provider.key, modEntries(items)).map { it.second }
            val candidates = api.getProjectVersions(projectId, instance.loader, instance.gameVersion)
            val pick = VersionType.entries.firstNotNullOfOrNull { type ->
                candidates.firstOrNull { v ->
                    v.versionType == type && requirements.all { FabricVersions.anyTokenSatisfies(v.versionNumber, it, setOf(instance.gameVersion)) }
                }
            } ?: error("No ${provider.title} version for ${instance.gameVersion} matches ${FabricVersions.describe(issue.alternatives)}")
            val swapped = swap(paths, provider, pick)
            verifyAgainst(paths, swapped, requirements)
            "Switched ${provider.title} to ${pick.versionNumber}"
        }
        is CompatIssue.Missing -> {
            val candidates = runCatching { api.getProjectVersions(issue.depId, instance.loader, instance.gameVersion) }.getOrNull()
                ?: error("Couldn't find \"${issue.depId}\" on Modrinth. Install it from the Mod Browser")
            val pick = VersionType.entries.firstNotNullOfOrNull { type ->
                candidates.firstOrNull { it.versionType == type && FabricVersions.anyTokenSatisfies(it.versionNumber, issue.alternatives, setOf(instance.gameVersion)) }
            } ?: error("No ${issue.depId} version for ${instance.gameVersion} matches ${FabricVersions.describe(issue.alternatives)}")
            installer.install(pick, ProjectType.MOD, instance, paths)
            "Installed ${pick.name}"
        }
        is CompatIssue.WrongGame, is CompatIssue.Breaks -> {
            val mod = items.first { it.key == issue.modKey }
            setEnabled(paths, mod, enabled = false)
            "Disabled ${mod.title}"
        }
    }

    /** The token match on Modrinth's version number is a heuristic; confirm with the jar's own version. */
    private suspend fun verifyAgainst(paths: InstancePaths, item: ContentItem, requirements: List<List<String>>) {
        val meta = withContext(Dispatchers.IO) { FabricModReader.read(folder(paths, item.kind).resolve(item.currentName)) } ?: return
        check(requirements.all { FabricVersions.satisfiesAny(meta.version, it) }) {
            "Installed ${item.title} ${meta.version}, but it still doesn't match what other mods need"
        }
    }

    private fun modEntries(items: List<ContentItem>) =
        items.filter { it.kind == ContentKind.MOD && it.enabled && it.meta != null }.map { ModEntry(it.key, it.title, it.meta!!) }

    private fun loaderIds(loader: ModLoader) = when (loader) {
        ModLoader.QUILT -> listOf("quilt", "fabric")
        ModLoader.VANILLA -> emptyList()
        else -> listOf(loader.apiId)
    }

    private fun folder(paths: InstancePaths, kind: ContentKind): Path = paths.gameDir.resolve(kind.folder)

    private fun scan(paths: InstancePaths, kind: ContentKind): List<ScannedFile> {
        val dir = folder(paths, kind)
        if (!dir.isDirectory()) return emptyList()
        return dir.listDirectoryEntries().filter { it.isRegularFile() }.mapNotNull { p ->
            val name = p.name
            val (base, enabled) = when {
                name.endsWith(kind.extension) -> name to true
                name.endsWith(kind.extension + DISABLED) -> name.removeSuffix(DISABLED) to false
                else -> return@mapNotNull null
            }
            ScannedFile(kind, p, base, enabled, sha1(p), Files.size(p))
        }
    }

    private fun sha1(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
