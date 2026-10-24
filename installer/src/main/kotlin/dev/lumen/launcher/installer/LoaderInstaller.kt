package dev.lumen.launcher.installer

import dev.lumen.launcher.core.LauncherPaths
import dev.lumen.launcher.core.download.DownloadManager
import dev.lumen.launcher.core.download.DownloadTask
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.instance.ModLoader
import dev.lumen.launcher.core.net.Http
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readLines

data class LoaderResult(val versionId: String, val loaderVersion: String?)

@Serializable private data class LoaderListEntry(val loader: LoaderInfo)
@Serializable private data class LoaderInfo(val version: String, val stable: Boolean? = null)

/**
 * Turns (game version, loader) into an installed version JSON that inherits from vanilla.
 *  - Fabric / Quilt: their meta servers serve a ready-made profile JSON — just save it.
 *  - NeoForge: patches the game with processors, so its own installer must run headless.
 */
class LoaderInstaller(
    private val http: HttpClient,
    private val paths: LauncherPaths,
    private val downloads: DownloadManager,
    private val resolver: VersionResolver,
) {
    suspend fun ensure(instance: Instance, java: JavaRuntime, onStage: (String) -> Unit): LoaderResult {
        val game = instance.gameVersion
        return when (instance.loader) {
            ModLoader.VANILLA -> LoaderResult(game, null)
            ModLoader.FABRIC -> {
                val lv = instance.loaderVersion ?: latestFromMeta("$FABRIC_META/versions/loader/$game", "Fabric", game)
                LoaderResult(installProfile("fabric-loader-$lv-$game", "$FABRIC_META/versions/loader/$game/$lv/profile/json", "Fabric $lv", onStage), lv)
            }
            ModLoader.QUILT -> {
                val lv = instance.loaderVersion ?: latestFromMeta("$QUILT_META/versions/loader/$game", "Quilt", game)
                LoaderResult(installProfile("quilt-loader-$lv-$game", "$QUILT_META/versions/loader/$game/$lv/profile/json", "Quilt $lv", onStage), lv)
            }
            ModLoader.NEOFORGE -> {
                val lv = instance.loaderVersion ?: latestNeoForge(game)
                LoaderResult(installNeoForge(lv, java, onStage), lv)
            }
        }
    }

    private suspend fun latestFromMeta(url: String, name: String, game: String): String {
        val entries = http.get(url).body<List<LoaderListEntry>>()
        val stable = entries.firstOrNull { e ->
            e.loader.stable ?: listOf("beta", "pre", "rc").none { it in e.loader.version }
        } ?: entries.firstOrNull()
        return stable?.loader?.version ?: error("$name has no loader for Minecraft $game yet")
    }

    private suspend fun installProfile(expectedId: String, url: String, label: String, onStage: (String) -> Unit): String {
        if (resolver.hasLocal(expectedId)) return expectedId
        onStage("Installing $label")
        val raw = http.get(url).bodyAsText()
        val id = Http.json.decodeFromString(VersionJson.serializer(), raw).id
        resolver.save(id, raw)
        return id
    }

    /**
     * NeoForge numbers releases after the game: MC 1.21.1 → 21.1.x, 1.21 → 21.0.x,
     * year-based 26.1 → 26.1.x. Picks the newest non-beta build for that prefix.
     */
    private suspend fun latestNeoForge(game: String): String {
        val prefix = if (game.startsWith("1.")) {
            game.removePrefix("1.").let { if (it.count { c -> c == '.' } == 0) "$it.0." else "$it." }
        } else "$game."
        val all = Regex("<version>([^<]+)</version>")
            .findAll(http.get(NEOFORGE_METADATA).bodyAsText())
            .map { it.groupValues[1] }
            .filter { it.startsWith(prefix) }
            .toList()
        return all.lastOrNull { "beta" !in it } ?: all.lastOrNull()
            ?: error("NeoForge has no release for Minecraft $game")
    }

    private suspend fun installNeoForge(version: String, java: JavaRuntime, onStage: (String) -> Unit): String {
        val id = "neoforge-$version"
        if (resolver.hasLocal(id)) return id

        onStage("Downloading NeoForge $version installer")
        val workDir = paths.cache.resolve("neoforge")
        val installer = workDir.resolve("neoforge-$version-installer.jar")
        downloads.download(DownloadTask("$NEOFORGE_MAVEN/$version/neoforge-$version-installer.jar", installer, label = "NeoForge installer"))

        onStage("Running NeoForge $version installer")
        val log = workDir.resolve("install-$version.log")
        val exit = withContext(Dispatchers.IO) {
            // The installer refuses to run unless it sees a "Minecraft launcher" profile file.
            val profiles = paths.shared.resolve("launcher_profiles.json")
            if (!profiles.exists()) Files.writeString(profiles, """{"profiles":{}}""")
            // Its own console java (not javaw) so the process doesn't detach from our stdout redirect.
            val javaCli = java.executable.resolveSibling(if (java.executable.fileName.toString().endsWith(".exe")) "java.exe" else "java")
            ProcessBuilder(javaCli.toString(), "-jar", installer.toString(), "--install-client", paths.shared.toString())
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()
        }.onExit().await().exitValue()

        if (exit != 0 || !resolver.hasLocal(id)) {
            val tail = withContext(Dispatchers.IO) { runCatching { log.readLines().takeLast(3).joinToString(" | ") }.getOrDefault("") }
            error("NeoForge installer failed (exit $exit): $tail")
        }
        return id
    }

    private companion object {
        const val FABRIC_META = "https://meta.fabricmc.net/v2"
        const val QUILT_META = "https://meta.quiltmc.org/v3"
        const val NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge"
        const val NEOFORGE_METADATA = "$NEOFORGE_MAVEN/maven-metadata.xml"
    }
}
