package dev.lumen.launcher.installer

import dev.lumen.launcher.core.LauncherPaths
import dev.lumen.launcher.core.download.DownloadManager
import dev.lumen.launcher.core.download.DownloadTask
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.net.Http
import dev.lumen.launcher.core.version.VersionRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readText

data class InstallProgress(val stage: String, val done: Int = 0, val total: Int = 0) {
    val fraction: Float? get() = if (total > 0) done.toFloat() / total else null
}

/** Everything the launch step needs, all files present and verified. */
data class PreparedGame(
    val version: ResolvedVersion,
    val java: JavaRuntime,
    val loaderVersion: String?,
    /** Libraries in classpath order, then the game jar last. */
    val classpath: List<Path>,
    val gameJar: Path,
    val nativesDir: Path,
    val librariesDir: Path,
    val assetsRoot: Path,
    val logConfig: Path?,
)

private data class LibraryFile(
    val path: Path,
    val url: String?,
    val sha1: String?,
    val size: Long?,
    val native: Boolean,
    val onClasspath: Boolean,
    val extractExclude: List<String> = emptyList(),
)

/**
 * Makes an instance launchable: version JSON chain, game jar, libraries, natives, assets,
 * log config and a matching Java. Idempotent — the first run verifies every hash, later
 * runs only check presence + size (a ~1 s check instead of re-hashing ~600 MB).
 */
class GameInstaller(
    http: HttpClient,
    private val paths: LauncherPaths,
    private val downloads: DownloadManager,
    versions: VersionRepository,
    private val platform: PlatformInfo = PlatformInfo.current,
) {
    private val resolver = VersionResolver(paths, downloads, versions, platform)
    private val runtimes = JavaRuntimes(http, paths, downloads, platform)
    private val loaders = LoaderInstaller(http, paths, downloads, resolver)

    suspend fun prepare(instance: Instance, onProgress: (InstallProgress) -> Unit = {}): PreparedGame {
        onProgress(InstallProgress("Resolving Minecraft ${instance.gameVersion}"))
        val vanilla = resolver.ensureVanilla(instance.gameVersion)
        val javaRef = vanilla.javaVersion ?: JavaVersionRef()
        val java = runtimes.ensure(javaRef) { d, t -> onProgress(InstallProgress("Java ${javaRef.majorVersion} runtime", d, t)) }

        val loader = loaders.ensure(instance, java) { onProgress(InstallProgress(it)) }
        val version = resolver.resolve(loader.versionId)
        val marker = paths.versions.resolve(version.id).resolve(VERIFIED_MARKER)
        val verified = marker.exists()

        // Game jar, libraries and log config.
        val rootJar = paths.versions.resolve(version.rootId).resolve("${version.rootId}.jar")
        val libs = libraryFiles(version)
        val logConfig = version.logging?.let { paths.assets.resolve("log_configs").resolve(it.file.id) }
        val coreTasks = buildList {
            add(DownloadTask(version.clientJar.url, rootJar, sha1 = version.clientJar.sha1, size = version.clientJar.size, label = "Minecraft ${version.rootId}"))
            libs.filter { it.url != null }.forEach { add(DownloadTask(it.url!!, it.path, sha1 = it.sha1, size = it.size, label = it.path.fileName.toString())) }
            version.logging?.let { add(DownloadTask(it.file.url, logConfig!!, sha1 = it.file.sha1, size = it.file.size, label = "Log config")) }
        }
        downloadCounting("Game libraries", coreTasks, verified, onProgress)

        val missing = libs.filter { it.onClasspath && !it.path.exists() }
        check(missing.isEmpty()) { "Missing ${missing.first().path.fileName} — reinstall the loader" }

        val gameJar = withContext(Dispatchers.IO) { gameJarFor(version, rootJar) }
        val nativesDir = withContext(Dispatchers.IO) { extractNatives(version.id, libs.filter { it.native }, fresh = !verified) }

        // Assets: ~4,000 small content-addressed files shared by every version.
        val index = version.assetIndex
        val indexPath = paths.assets.resolve("indexes").resolve("${index.id}.json")
        downloads.download(DownloadTask(index.url, indexPath, sha1 = index.sha1, size = index.size, label = "Asset index"))
        val objects = withContext(Dispatchers.IO) {
            Http.json.decodeFromString(AssetIndex.serializer(), indexPath.readText()).objects.values.distinctBy { it.hash }
        }
        val assetTasks = objects.map { o ->
            val sub = "${o.hash.take(2)}/${o.hash}"
            DownloadTask("$ASSETS_URL/$sub", paths.assets.resolve("objects").resolve(sub), sha1 = o.hash, size = o.size, label = "Assets")
        }
        downloadCounting("Assets", assetTasks, verified, onProgress)

        withContext(Dispatchers.IO) {
            Files.createDirectories(marker.parent)
            Files.writeString(marker, index.id)
        }
        return PreparedGame(
            version = version,
            java = java,
            loaderVersion = loader.loaderVersion,
            // listOf(): Path is itself Iterable<Path>, so `list + path` would append each path segment.
            classpath = libs.filter { it.onClasspath }.map { it.path } + listOf(gameJar),
            gameJar = gameJar,
            nativesDir = nativesDir,
            librariesDir = paths.libraries,
            assetsRoot = paths.assets,
            logConfig = logConfig,
        )
    }

    private fun libraryFiles(version: ResolvedVersion): List<LibraryFile> = version.libraries.flatMap { lib ->
        val coord = MavenCoordinate.parse(lib.name)
        val isNativeJar = coord.classifier?.startsWith("natives") == true
        val exclude = lib.extract?.exclude.orEmpty()
        buildList {
            val artifact = lib.downloads?.artifact
            when {
                artifact != null -> add(
                    LibraryFile(
                        path = paths.libraries.resolve(artifact.path ?: coord.path),
                        url = artifact.url.ifBlank { null }, // blank = generated locally by an installer
                        sha1 = artifact.sha1, size = artifact.size,
                        native = isNativeJar, onClasspath = true, extractExclude = exclude,
                    ),
                )
                lib.downloads == null -> add(
                    LibraryFile(
                        path = paths.libraries.resolve(coord.path),
                        url = lib.url?.let { "${it.trimEnd('/')}/${coord.path}" },
                        sha1 = lib.sha1, size = lib.size,
                        native = isNativeJar, onClasspath = true, extractExclude = exclude,
                    ),
                )
            }
            // Pre-1.19 style: natives live in a classifier and are extracted, never put on the classpath.
            lib.natives[platform.osName]?.let { template ->
                val classifier = template.replace("\${arch}", if (platform.is64Bit) "64" else "32")
                lib.downloads?.classifiers?.get(classifier)?.let { a ->
                    add(
                        LibraryFile(
                            path = paths.libraries.resolve(a.path ?: coord.copy(classifier = classifier).path),
                            url = a.url.ifBlank { null }, sha1 = a.sha1, size = a.size,
                            native = true, onClasspath = false, extractExclude = exclude,
                        ),
                    )
                }
            }
        }
    }
        .filter { !it.native || platform.acceptsNativeClassifier(it.path.fileName.toString().removeSuffix(".jar")) }
        .distinctBy { it.path }

    /**
     * Loader versions get their own copy of the vanilla jar at versions/<id>/<id>.jar —
     * what the official launcher does, and what NeoForge's `-DignoreList=${version_name}.jar` expects.
     */
    private fun gameJarFor(version: ResolvedVersion, rootJar: Path): Path {
        if (version.id == version.rootId) return rootJar
        val jar = paths.versions.resolve(version.id).resolve("${version.id}.jar")
        if (!jar.exists() || jar.fileSize() != rootJar.fileSize()) {
            Files.createDirectories(jar.parent)
            Files.copy(rootJar, jar, StandardCopyOption.REPLACE_EXISTING)
        }
        return jar
    }

    private fun extractNatives(versionId: String, natives: List<LibraryFile>, fresh: Boolean): Path {
        val dir = paths.shared.resolve("natives").resolve(versionId)
        if (!fresh && dir.exists()) return dir
        Files.createDirectories(dir)
        for (lib in natives) {
            if (!lib.path.exists()) continue
            ZipFile(lib.path.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (entry.isDirectory || name.startsWith("META-INF/")) continue
                    if (lib.extractExclude.any { name.startsWith(it) }) continue
                    if (platform.nativeExtensions.none { name.endsWith(it) }) continue
                    // Modern LWJGL nests natives (windows/x64/org/lwjgl/lwjgl.dll); java.library.path wants them flat.
                    val target = dir.resolve(name.substringAfterLast('/'))
                    zip.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
        }
        return dir
    }

    private suspend fun downloadCounting(stage: String, tasks: List<DownloadTask>, quick: Boolean, onProgress: (InstallProgress) -> Unit) {
        val todo = if (quick) {
            withContext(Dispatchers.IO) { tasks.filter { t -> !t.target.exists() || (t.size != null && t.target.fileSize() != t.size) } }
        } else tasks
        if (todo.isEmpty()) return
        val done = AtomicInteger()
        onProgress(InstallProgress(stage, 0, todo.size))
        coroutineScope {
            todo.map { t ->
                async {
                    downloads.download(t)
                    val d = done.incrementAndGet()
                    if (d % 25 == 0 || d == todo.size) onProgress(InstallProgress(stage, d, todo.size))
                }
            }.awaitAll()
        }
    }

    private companion object {
        const val VERIFIED_MARKER = ".lumen-verified"
        const val ASSETS_URL = "https://resources.download.minecraft.net"
    }
}
