package dev.lumen.launcher.installer

import dev.lumen.launcher.core.LauncherPaths
import dev.lumen.launcher.core.download.DownloadManager
import dev.lumen.launcher.core.download.DownloadTask
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines

data class JavaRuntime(val home: Path, val executable: Path, val major: Int)

@Serializable private data class RuntimeEntry(val manifest: RuntimeManifestRef)
@Serializable private data class RuntimeManifestRef(val url: String, val sha1: String? = null)
@Serializable private data class RuntimeManifest(val files: Map<String, RuntimeFile>)
@Serializable private data class RuntimeFile(
    val type: String,
    val executable: Boolean = false,
    val downloads: RuntimeDownloads? = null,
    val target: String? = null,
)
@Serializable private data class RuntimeDownloads(val raw: Artifact)

/**
 * Finds a Java matching the version's required major (exact match: older Minecraft breaks on
 * newer Java), preferring what's already on the machine, else downloads Mojang's own runtime
 * for that component — the same builds the official launcher ships.
 */
class JavaRuntimes(
    private val http: HttpClient,
    private val paths: LauncherPaths,
    private val downloads: DownloadManager,
    private val platform: PlatformInfo = PlatformInfo.current,
) {
    suspend fun ensure(ref: JavaVersionRef, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): JavaRuntime =
        withContext(Dispatchers.IO) { findLocal(ref) } ?: download(ref, onProgress)

    private fun findLocal(ref: JavaVersionRef): JavaRuntime? {
        val managed = paths.runtimes.resolve(ref.component)
        if (managed.resolve(COMPLETE_MARKER).exists()) runtimeAt(managed, ref.majorVersion)?.let { return it }
        return candidateHomes().asSequence()
            .mapNotNull { home -> runtimeAt(home, expectedMajor = null) }
            .firstOrNull { it.major == ref.majorVersion }
    }

    private fun candidateHomes(): List<Path> = buildList {
        System.getenv("JAVA_HOME")?.let { add(Path(it)) }
        System.getProperty("java.home")?.let { add(Path(it)) }
        val roots = when (platform.osName) {
            "windows" -> listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramW6432")).distinct().flatMap { pf ->
                listOf("Eclipse Adoptium", "Java", "Zulu", "Microsoft", "BellSoft", "Amazon Corretto", "Semeru").map { Path(pf, it) }
            }
            "osx" -> listOf(Path("/Library/Java/JavaVirtualMachines"))
            else -> listOf(Path("/usr/lib/jvm"))
        }
        roots.filter { it.isDirectory() }.forEach { root ->
            val children = runCatching { root.listDirectoryEntries() }.getOrDefault(emptyList())
            children.filter { it.isDirectory() }.forEach { dir ->
                add(if (platform.osName == "osx") dir.resolve("Contents/Home") else dir)
            }
        }
    }

    /**
     * Reads `<home>/release`. Rejects jlink-trimmed runtimes, such as the one bundled inside the packaged
     * Lumen app, which lack modules Minecraft or its loaders use (e.g. NeoForge needs jdk.zipfs).
     */
    private fun runtimeAt(home: Path, expectedMajor: Int?): JavaRuntime? {
        val actualHome = listOf(home, home.resolve("jre.bundle/Contents/Home")).firstOrNull { it.resolve("release").exists() } ?: return null
        val release = runCatching { actualHome.resolve("release").readLines() }.getOrNull() ?: return null
        fun value(key: String) = release.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim('"')
        val modules = value("MODULES")?.split(' ')?.toSet()
        if (modules != null && !modules.containsAll(GAME_MODULES)) return null
        val major = expectedMajor ?: value("JAVA_VERSION")?.let(::parseMajor) ?: return null
        val exe = actualHome.resolve("bin").resolve(if (platform.osName == "windows") "javaw.exe" else "java")
        return if (exe.exists()) JavaRuntime(actualHome, exe, major) else null
    }

    private suspend fun download(ref: JavaVersionRef, onProgress: (Int, Int) -> Unit): JavaRuntime {
        val index = http.get(ALL_RUNTIMES_URL).body<Map<String, Map<String, List<RuntimeEntry>>>>()
        val entry = index[platform.runtimeKey]?.get(ref.component)?.firstOrNull()
            ?: error("Mojang has no '${ref.component}' Java runtime for ${platform.runtimeKey}")
        val manifest = http.get(entry.manifest.url).body<RuntimeManifest>()
        val home = paths.runtimes.resolve(ref.component)

        withContext(Dispatchers.IO) {
            manifest.files.filterValues { it.type == "directory" }.keys.forEach { Files.createDirectories(home.resolve(it)) }
        }
        val files = manifest.files.filterValues { it.type == "file" && it.downloads != null }
        val done = AtomicInteger()
        onProgress(0, files.size)
        coroutineScope {
            files.map { (path, file) ->
                async {
                    val raw = file.downloads!!.raw
                    val target = home.resolve(path)
                    downloads.download(DownloadTask(raw.url, target, sha1 = raw.sha1, size = raw.size, label = "Java ${ref.majorVersion}"))
                    if (file.executable) target.toFile().setExecutable(true)
                    onProgress(done.incrementAndGet(), files.size)
                }
            }.awaitAll()
        }
        withContext(Dispatchers.IO) {
            manifest.files.filterValues { it.type == "link" && it.target != null }.forEach { (path, link) ->
                val linkPath = home.resolve(path)
                if (!linkPath.exists()) runCatching { Files.createSymbolicLink(linkPath, Path(link.target!!)) }
            }
            Files.writeString(home.resolve(COMPLETE_MARKER), ref.component)
        }
        return runtimeAt(home, ref.majorVersion) ?: error("Downloaded Java runtime at $home has no executable")
    }

    companion object {
        private const val COMPLETE_MARKER = ".lumen-complete"
        private val GAME_MODULES = setOf(
            "java.desktop", "jdk.unsupported", "java.sql", "java.naming", "java.management", "jdk.zipfs",
        )
        private const val ALL_RUNTIMES_URL =
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b0c0bf9bb5c4f1d39b65/all.json"

        /** "21.0.2" → 21, "1.8.0_402" → 8, "25" → 25. */
        fun parseMajor(version: String): Int? {
            val parts = version.split('.', '_', '-', '+')
            val first = parts.firstOrNull()?.toIntOrNull() ?: return null
            return if (first == 1) parts.getOrNull(1)?.toIntOrNull() else first
        }
    }
}
