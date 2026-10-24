package dev.lumen.launcher.core.instance

import dev.lumen.launcher.core.LauncherPaths
import dev.lumen.launcher.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Source of truth for instances. All disk I/O runs on [Dispatchers.IO]; the UI just
 * collects [instances]. Folders are keyed by an immutable id, so renaming is a metadata
 * change only and never moves gigabytes of saves around.
 */
class InstanceManager(private val paths: LauncherPaths) {
    private val mutex = Mutex()
    private val _instances = MutableStateFlow<List<Instance>>(emptyList())
    val instances: StateFlow<List<Instance>> = _instances.asStateFlow()

    fun pathsOf(instance: Instance): InstancePaths = InstancePaths(paths.instances.resolve(instance.id))

    suspend fun load(): List<Instance> = mutex.withLock {
        withContext(Dispatchers.IO) {
            Files.createDirectories(paths.instances)
            val loaded = paths.instances.listDirectoryEntries()
                .filter { it.isDirectory() }
                .mapNotNull { dir -> runCatching { readManifest(InstancePaths(dir).manifest) }.getOrNull() }
            publish(loaded)
        }
    }

    suspend fun create(
        name: String,
        gameVersion: String,
        loader: ModLoader,
        loaderVersion: String? = null,
    ): Instance = mutex.withLock {
        withContext(Dispatchers.IO) {
            val instance = Instance(
                id = newId(name),
                name = name.trim().ifEmpty { "New Instance" },
                gameVersion = gameVersion,
                loader = loader,
                loaderVersion = loaderVersion,
                createdAt = System.currentTimeMillis(),
            )
            val p = pathsOf(instance)
            p.all.forEach(Files::createDirectories)
            writeManifest(p.manifest, instance)
            publish(_instances.value + instance)
            instance
        }
    }

    suspend fun rename(id: String, newName: String): Instance =
        update(id) { it.copy(name = newName.trim().ifEmpty { it.name }) }

    suspend fun markPlayed(id: String): Instance = update(id) { it.copy(lastPlayedAt = System.currentTimeMillis()) }

    suspend fun update(id: String, transform: (Instance) -> Instance): Instance = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _instances.value.first { it.id == id }
            val updated = transform(current).copy(id = current.id) // id is immutable
            writeManifest(pathsOf(updated).manifest, updated)
            publish(_instances.value.map { if (it.id == id) updated else it })
            updated
        }
    }

    suspend fun delete(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val target = _instances.value.firstOrNull { it.id == id } ?: return@withContext
            deleteRecursively(pathsOf(target).root)
            publish(_instances.value.filterNot { it.id == id })
        }
    }

    /** Enabled mods only — `.jar.disabled` files don't load, so they don't cost memory. */
    suspend fun countMods(instance: Instance): Int = withContext(Dispatchers.IO) {
        val dir = pathsOf(instance).mods
        if (!Files.isDirectory(dir)) 0
        else Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".jar") }.count().toInt() }
    }

    private fun publish(list: List<Instance>): List<Instance> {
        val sorted = list.sortedWith(compareByDescending<Instance> { it.lastPlayedAt ?: 0L }.thenBy { it.name.lowercase() })
        _instances.value = sorted
        return sorted
    }

    private fun readManifest(file: Path): Instance = Http.json.decodeFromString(Instance.serializer(), file.readText())

    /** Write-then-rename so a crash mid-write can never corrupt instance.json. */
    private fun writeManifest(file: Path, instance: Instance) {
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        tmp.writeText(Http.json.encodeToString(Instance.serializer(), instance))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun newId(name: String): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { "instance" }
        return "$slug-${UUID.randomUUID().toString().take(6)}"
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
