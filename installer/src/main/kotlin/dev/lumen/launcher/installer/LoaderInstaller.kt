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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText

data class LoaderResult(val versionId: String, val loaderVersion: String?)

@Serializable private data class LoaderListEntry(val loader: LoaderInfo)
@Serializable private data class LoaderInfo(val version: String, val stable: Boolean? = null)

/**
 * Turns (game version, loader) into an installed version JSON that inherits from vanilla.
 *  - Fabric / Quilt: their meta servers serve a ready-made profile JSON — just save it.
 *  - NeoForge: patches the game with processors, so its own installer must run headless.
 *  - Forge: the same for 1.13+, and a hand-done legacy install for 1.7.10–1.12.2 (see [installForge]).
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
            ModLoader.FORGE -> {
                val lv = instance.loaderVersion ?: latestForge(game)
                LoaderResult(installForge(lv, java, onStage), lv)
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
        runHeadlessInstaller(installer, "--install-client", java, workDir.resolve("install-$version.log"), id, "NeoForge")
        return id
    }

    /**
     * Forge's own recommended build for [game] (else its latest), as the full Maven version.
     * Old builds carry the game version twice: 1.8.9 → `1.8.9-11.15.1.2318-1.8.9`.
     */
    private suspend fun latestForge(game: String): String {
        val promos = http.get(FORGE_PROMOTIONS).body<ForgePromotions>().promos
        val build = promos["$game-recommended"] ?: promos["$game-latest"] ?: error("Forge has no build for Minecraft $game")
        return Regex("<version>([^<]+)</version>")
            .findAll(http.get(FORGE_METADATA).bodyAsText())
            .map { it.groupValues[1] }
            .firstOrNull { it == "$game-$build" || it.startsWith("$game-$build-") }
            ?: "$game-$build"
    }

    /**
     * Forge's installer comes in two shapes, told apart by its install_profile.json:
     *  - 1.13+ (and late 1.12.2): processors patch the game, so the installer runs headless like NeoForge's;
     *  - 1.7.10 to early 1.12.2: the profile embeds the version JSON (`versionInfo`) and the Forge
     *    jar ships inside the installer, which has no headless mode — Lumen does those steps itself.
     * The version id differs between eras, so it's remembered next to the installer once known.
     */
    private suspend fun installForge(version: String, java: JavaRuntime, onStage: (String) -> Unit): String {
        val workDir = paths.cache.resolve("forge")
        val idFile = workDir.resolve("$version.id")
        withContext(Dispatchers.IO) { idFile.takeIf { it.exists() }?.readText()?.trim() }?.let { if (resolver.hasLocal(it)) return it }

        onStage("Downloading Forge $version installer")
        val installer = workDir.resolve("forge-$version-installer.jar")
        downloads.download(DownloadTask("$FORGE_MAVEN/$version/forge-$version-installer.jar", installer, label = "Forge installer"))
        val profile = withContext(Dispatchers.IO) {
            ZipFile(installer.toFile()).use { zip ->
                val entry = zip.getEntry("install_profile.json") ?: error("Forge $version installer has no install profile")
                Http.json.parseToJsonElement(zip.getInputStream(entry).bufferedReader().readText()).jsonObject
            }
        }

        onStage("Installing Forge $version")
        val id = if ("versionInfo" in profile) {
            installLegacyForge(installer, profile)
        } else {
            val id = profile["version"]?.jsonPrimitive?.content ?: error("Forge $version install profile has no version id")
            if (!resolver.hasLocal(id)) runHeadlessInstaller(installer, "--installClient", java, workDir.resolve("install-$version.log"), id, "Forge")
            id
        }
        withContext(Dispatchers.IO) { Files.writeString(idFile, id) }
        return id
    }

    /**
     * What the old installer's "Install client" button did: unpack the Forge jar into libraries/
     * and save `versionInfo` as the version JSON. Its libraries are made explicit on the way:
     * no URL meant Mojang's server, `clientreq: false` meant server-only, and the Forge jar itself
     * must not be downloaded (Maven only has it under the `universal` classifier).
     */
    private suspend fun installLegacyForge(installer: Path, profile: JsonObject): String {
        val install = profile.getValue("install").jsonObject
        val info = profile.getValue("versionInfo").jsonObject
        val id = info.getValue("id").jsonPrimitive.content
        val forgeName = install.getValue("path").jsonPrimitive.content

        val forgeJar = paths.libraries.resolve(MavenCoordinate.parse(forgeName).path)
        withContext(Dispatchers.IO) {
            ZipFile(installer.toFile()).use { zip ->
                val entry = zip.getEntry(install.getValue("filePath").jsonPrimitive.content) ?: error("Forge installer has no ${forgeJar.fileName}")
                Files.createDirectories(forgeJar.parent)
                zip.getInputStream(entry).use { Files.copy(it, forgeJar, StandardCopyOption.REPLACE_EXISTING) }
            }
        }

        val libraries = info["libraries"]?.jsonArray.orEmpty().mapNotNull { element ->
            val lib = element.jsonObject
            if (lib["clientreq"]?.jsonPrimitive?.booleanOrNull == false) return@mapNotNull null
            val name = lib.getValue("name").jsonPrimitive.content
            val url = when (name) {
                forgeName -> null
                else -> (lib["url"]?.jsonPrimitive?.content ?: MOJANG_LIBRARIES)
                    .replace(Regex("^https?://files\\.minecraftforge\\.net/maven/?"), "$FORGE_MAVEN_ROOT/")
            }
            JsonObject(buildMap {
                put("name", JsonPrimitive(name))
                url?.let { put("url", JsonPrimitive(it)) }
            })
        }
        resolver.save(id, JsonObject(info + ("libraries" to JsonArray(libraries))).toString())
        return id
    }

    /** Runs a Forge-family installer's client install into the shared folder and checks it produced [id]. */
    private suspend fun runHeadlessInstaller(installer: Path, flag: String, java: JavaRuntime, log: Path, id: String, name: String) {
        val exit = withContext(Dispatchers.IO) {
            // The installer refuses to run unless it sees a "Minecraft launcher" profile file.
            val profiles = paths.shared.resolve("launcher_profiles.json")
            if (!profiles.exists()) Files.writeString(profiles, """{"profiles":{}}""")
            // Its own console java (not javaw) so the process doesn't detach from our stdout redirect.
            val javaCli = java.executable.resolveSibling(if (java.executable.fileName.toString().endsWith(".exe")) "java.exe" else "java")
            ProcessBuilder(javaCli.toString(), "-jar", installer.toString(), flag, paths.shared.toString())
                .directory(installer.parent.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()
        }.onExit().await().exitValue()

        if (exit != 0 || !resolver.hasLocal(id)) {
            val tail = withContext(Dispatchers.IO) { runCatching { log.readLines().takeLast(3).joinToString(" | ") }.getOrDefault("") }
            error("$name installer failed (exit $exit): $tail")
        }
    }

    private companion object {
        const val FABRIC_META = "https://meta.fabricmc.net/v2"
        const val QUILT_META = "https://meta.quiltmc.org/v3"
        const val NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge"
        const val NEOFORGE_METADATA = "$NEOFORGE_MAVEN/maven-metadata.xml"
        const val FORGE_MAVEN_ROOT = "https://maven.minecraftforge.net"
        const val FORGE_MAVEN = "$FORGE_MAVEN_ROOT/net/minecraftforge/forge"
        const val FORGE_METADATA = "$FORGE_MAVEN/maven-metadata.xml"
        const val FORGE_PROMOTIONS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
        const val MOJANG_LIBRARIES = "https://libraries.minecraft.net/"
    }
}

@Serializable private data class ForgePromotions(val promos: Map<String, String> = emptyMap())
