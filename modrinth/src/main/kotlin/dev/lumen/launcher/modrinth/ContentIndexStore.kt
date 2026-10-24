package dev.lumen.launcher.modrinth

import dev.lumen.launcher.core.instance.InstancePaths
import dev.lumen.launcher.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** One Modrinth-sourced file in an instance. [filename] is always the enabled name (no `.disabled`). */
@Serializable
data class InstalledContent(val projectId: String, val versionId: String, val filename: String, val folder: String)

/** Per-instance record of what came from Modrinth, keyed by project id. */
@Serializable
data class ContentIndex(val entries: Map<String, InstalledContent> = emptyMap())

/**
 * Reads and writes `<instance>/content-index.json`. Every writer (installs, toggles, swaps, removals)
 * goes through [update], which serialises read-modify-write per instance so concurrent edits can't
 * drop each other's entries.
 */
class ContentIndexStore {
    private val locks = ConcurrentHashMap<Path, Mutex>()

    suspend fun read(paths: InstancePaths): ContentIndex = withContext(Dispatchers.IO) {
        val f = file(paths)
        if (f.exists()) Http.json.decodeFromString(ContentIndex.serializer(), f.readText()) else ContentIndex()
    }

    suspend fun update(paths: InstancePaths, transform: (ContentIndex) -> ContentIndex): ContentIndex =
        lock(paths).withLock {
            val next = transform(read(paths))
            withContext(Dispatchers.IO) {
                val f = file(paths)
                val tmp = f.resolveSibling("${f.fileName}.tmp")
                tmp.writeText(Http.json.encodeToString(ContentIndex.serializer(), next))
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            next
        }

    private fun lock(paths: InstancePaths) = locks.computeIfAbsent(paths.root.toAbsolutePath().normalize()) { Mutex() }

    private fun file(paths: InstancePaths): Path = paths.root.resolve("content-index.json")
}
