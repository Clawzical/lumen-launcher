package dev.lumen.launcher.modrinth.content

import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.instance.InstancePaths
import dev.lumen.launcher.core.instance.ModLoader
import dev.lumen.launcher.modrinth.DependencyType
import dev.lumen.launcher.modrinth.ModInstaller
import dev.lumen.launcher.modrinth.ModrinthClient
import dev.lumen.launcher.modrinth.ModrinthVersion
import dev.lumen.launcher.modrinth.ProjectType
import dev.lumen.launcher.modrinth.VersionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** What happens to one file when the instance moves to another Minecraft version. */
sealed interface MigrationStep {
    val item: ContentItem

    /** Swap to [version], the build of this project made for the target. */
    data class Switch(override val item: ContentItem, val version: ModrinthVersion) : MigrationStep

    /** Already fine on the target (or a pack, which loads on any version): left as it is. */
    data class Keep(override val item: ContentItem) : MigrationStep

    /** Nothing for the target, so it's turned off (not deleted); [reason] says why. */
    data class TurnOff(override val item: ContentItem, val reason: Reason) : MigrationStep

    enum class Reason { NO_VERSION, NOT_ON_MODRINTH, NEEDS_TURNED_OFF_MOD }
}

data class MigrationPlan(
    val from: String,
    val to: String,
    val loader: ModLoader,
    val steps: List<MigrationStep>,
    /** For [MigrationStep.Reason.NEEDS_TURNED_OFF_MOD]: item key → title of the mod it needs. */
    val neededBy: Map<String, String> = emptyMap(),
) {
    val switches get() = steps.filterIsInstance<MigrationStep.Switch>()
    val turnOffs get() = steps.filterIsInstance<MigrationStep.TurnOff>()
    val keeps get() = steps.filterIsInstance<MigrationStep.Keep>()
}

data class MigrationResult(
    val switched: Int,
    /** Mods that were on and are now off, with why. */
    val turnedOff: List<MigrationStep.TurnOff>,
    /** Dependencies the new builds need that weren't installed yet. */
    val addedDependencies: List<String>,
    /** Where the previous mods (and worlds, if asked) were copied before anything changed. */
    val backup: Path,
)

/**
 * Moves an instance's content to another Minecraft version: every mod is matched to its build
 * for the target in one Modrinth request (by file hash), mods with no such build are turned off
 * rather than deleted, and worlds, configs and settings stay where they are. A copy of the
 * current mods goes to `backups/` first, so the change can always be undone by hand.
 */
class VersionMigrator(
    private val api: ModrinthClient,
    private val content: InstanceContentManager,
    private val installer: ModInstaller,
) {
    suspend fun plan(instance: Instance, items: List<ContentItem>, target: String): MigrationPlan {
        val steps = HashMap<String, MigrationStep>()
        for (kind in ContentKind.entries) {
            val group = items.filter { it.kind == kind }
            if (group.isEmpty()) continue
            val modsNeedMatch = kind == ContentKind.MOD && instance.loader != ModLoader.VANILLA
            val known = group.filter { it.projectId != null }
            val latest = if (known.isEmpty()) emptyMap() else api.latestVersionsByHashes(
                known.map { it.sha1 },
                when (kind) {
                    ContentKind.MOD -> loaderIds(instance.loader)
                    ContentKind.SHADER -> listOf("iris", "optifine", "canvas")
                    ContentKind.RESOURCE_PACK -> listOf("minecraft")
                },
                listOf(target),
            )
            coroutineScope {
                val lookups = Semaphore(6)
                group.map { item ->
                    async {
                        val found = latest[item.sha1]?.let { lookups.withPermit { sameChannel(instance, item, it, target) } }
                        item.key to when {
                            found == null && !modsNeedMatch -> MigrationStep.Keep(item) // packs still load
                            found == null && !item.enabled -> MigrationStep.Keep(item) // already off
                            found == null -> MigrationStep.TurnOff(
                                item,
                                if (item.projectId == null) MigrationStep.Reason.NOT_ON_MODRINTH else MigrationStep.Reason.NO_VERSION,
                            )
                            found.id == item.versionId -> MigrationStep.Keep(item)
                            else -> MigrationStep.Switch(item, found)
                        }
                    }
                }.awaitAll().toMap(steps)
            }
        }

        // A mod that stays on but needs one that's being turned off would crash the game: turn it off too.
        val neededBy = HashMap<String, String>()
        do {
            val off = steps.values.filterIsInstance<MigrationStep.TurnOff>().mapNotNull { it.item.projectId }.toSet()
            val offTitles = steps.values.filterIsInstance<MigrationStep.TurnOff>().associate { it.item.projectId to it.item.title }
            val cascade = steps.values.filterIsInstance<MigrationStep.Switch>()
                .filter { it.item.enabled && it.version.requiredProjects().any { p -> p in off } }
            cascade.forEach { s ->
                steps[s.item.key] = MigrationStep.TurnOff(s.item, MigrationStep.Reason.NEEDS_TURNED_OFF_MOD)
                neededBy[s.item.key] = s.version.requiredProjects().firstNotNullOf { offTitles[it] }
            }
        } while (cascade.isNotEmpty())

        val ordered = items.mapNotNull { steps[it.key] }
        return MigrationPlan(instance.gameVersion, target, instance.loader, ordered, neededBy)
    }

    /**
     * Applies [plan]. [migrated] is the instance as it will be afterwards (new game version),
     * used to resolve dependencies. Files that fail to download are turned off instead.
     */
    suspend fun apply(
        plan: MigrationPlan,
        migrated: Instance,
        paths: InstancePaths,
        backupWorlds: Boolean,
        onProgress: (String) -> Unit,
    ): MigrationResult {
        onProgress("Backing up")
        val backup = withContext(Dispatchers.IO) { backUp(paths, plan, backupWorlds) }

        val turnedOff = plan.turnOffs.filter { it.item.enabled }.toMutableList()
        val switched = AtomicInteger()
        coroutineScope {
            val done = AtomicInteger()
            plan.switches.map { step ->
                async {
                    try {
                        content.swap(paths, step.item, step.version)
                        switched.incrementAndGet()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (step.item.enabled) {
                            runCatching { content.setEnabled(paths, step.item, false) }
                            synchronized(turnedOff) { turnedOff += MigrationStep.TurnOff(step.item, MigrationStep.Reason.NO_VERSION) }
                        }
                    }
                    onProgress("Switching mods ${done.incrementAndGet()}/${plan.switches.size}")
                }
            }.awaitAll()
        }
        for (step in plan.turnOffs) {
            if (step.item.enabled) content.setEnabled(paths, step.item, false)
        }

        // New builds sometimes need a library the old ones didn't (e.g. a newer Fabric API split).
        val installedProjects = installer.installedProjectIds(paths)
        val missing = plan.switches.flatMap { it.version.requiredProjects() }.toSet() - installedProjects
        val added = mutableListOf<String>()
        for (projectId in missing) {
            onProgress("Adding dependencies")
            runCatching { installer.installLatest(projectId, ProjectType.MOD, migrated, paths) }
                .onSuccess { plan -> plan.files.firstOrNull()?.let { added += it.version.name } }
        }
        return MigrationResult(switched.get(), turnedOff, added, backup)
    }

    /** The newest build for [target], staying on the item's stability channel when one exists. */
    private suspend fun sameChannel(instance: Instance, item: ContentItem, latest: ModrinthVersion, target: String): ModrinthVersion {
        val channel = item.versionType ?: VersionType.RELEASE
        if (latest.versionType.ordinal <= channel.ordinal || item.projectId == null) return latest
        val loader = instance.loader.takeIf { item.kind == ContentKind.MOD }
        return runCatching { api.getProjectVersions(item.projectId, loader, target) }.getOrDefault(emptyList())
            .firstOrNull { it.versionType.ordinal <= channel.ordinal } ?: latest
    }

    private fun backUp(paths: InstancePaths, plan: MigrationPlan, worlds: Boolean): Path {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        // Outside the game folder: old Forge loads jars from mods/<version>/, so nothing may sit under mods/.
        val dir = paths.root.resolve("backups").resolve("$stamp-before-${plan.to}")
        val folders = buildList {
            add(paths.mods)
            add(paths.root.resolve("content-index.json"))
            if (worlds) add(paths.saves)
        }
        for (source in folders) if (source.exists()) copyTree(source, dir.resolve(source.name))
        Files.writeString(
            dir.resolve("README.txt"),
            "Lumen copied these before moving the instance from Minecraft ${plan.from} to ${plan.to}.\n" +
                "To go back: change the version back to ${plan.from} in Lumen, then copy these folders over the ones in minecraft/.\n",
        )
        return dir
    }

    private fun copyTree(source: Path, target: Path) {
        if (!source.isDirectory()) {
            Files.createDirectories(target.parent)
            Files.copy(source, target)
            return
        }
        Files.walk(source).use { stream ->
            stream.forEach { p ->
                val dest = target.resolve(source.relativize(p).toString())
                if (p.isDirectory()) Files.createDirectories(dest) else Files.copy(p, dest)
            }
        }
    }

    private fun ModrinthVersion.requiredProjects(): List<String> =
        dependencies.filter { it.type == DependencyType.REQUIRED }.mapNotNull { it.projectId }

    private fun loaderIds(loader: ModLoader) = when (loader) {
        ModLoader.QUILT -> listOf("quilt", "fabric")
        ModLoader.VANILLA -> emptyList()
        else -> listOf(loader.apiId)
    }
}
