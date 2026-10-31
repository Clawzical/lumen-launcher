package dev.lumen.launcher.installer

import dev.lumen.launcher.core.LauncherPaths
import dev.lumen.launcher.core.download.DownloadManager
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.instance.ModLoader
import dev.lumen.launcher.core.net.Http
import dev.lumen.launcher.core.version.VersionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The 1.7.10–1.12.2 installer shape, trimmed from Forge 1.8.9's real install_profile.json. */
class LegacyForgeTest {
    private val version = "1.8.9-11.15.1.2318-1.8.9"
    private val id = "1.8.9-forge$version"
    private val universal = "forge-$version-universal.jar"
    private val forgeBytes = "pretend forge classes".toByteArray()

    private val profile = """
        {
          "install": {
            "target": "$id",
            "path": "net.minecraftforge:forge:$version",
            "filePath": "$universal",
            "minecraft": "1.8.9"
          },
          "versionInfo": {
            "id": "$id",
            "inheritsFrom": "1.8.9",
            "jar": "1.8.9",
            "mainClass": "net.minecraft.launchwrapper.Launch",
            "minecraftArguments": "--username ${'$'}{auth_player_name} --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker",
            "libraries": [
              { "name": "net.minecraftforge:forge:$version", "url": "https://maven.minecraftforge.net/" },
              { "name": "net.minecraft:launchwrapper:1.12", "serverreq": true },
              { "name": "jline:jline:2.13", "url": "https://maven.minecraftforge.net/", "serverreq": true, "clientreq": false },
              { "name": "org.scala-lang:scala-library:2.11.1", "url": "http://files.minecraftforge.net/maven/", "checksums": ["abc"], "clientreq": true }
            ]
          }
        }
    """.trimIndent()

    private fun installerJar(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("install_profile.json")); zip.write(profile.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(universal)); zip.write(forgeBytes); zip.closeEntry()
        }
    }.toByteArray()

    private fun setup(root: Path, requests: MutableList<String>): LoaderInstaller {
        val jar = installerJar()
        val http = HttpClient(MockEngine { request ->
            requests += request.url.toString()
            if (request.url.encodedPath.endsWith("-installer.jar")) respond(jar, HttpStatusCode.OK) else respond("", HttpStatusCode.NotFound)
        })
        val paths = LauncherPaths(root).ensure()
        val downloads = DownloadManager(http)
        return LoaderInstaller(http, paths, downloads, VersionResolver(paths, downloads, VersionRepository(http)))
    }

    private val instance = Instance("i", "Forge 1.8.9", "1.8.9", ModLoader.FORGE, loaderVersion = version, createdAt = 0)
    private val noJava = JavaRuntime(Path.of("unused"), Path.of("unused"), 8) // the legacy path never runs Java

    @Test
    fun `legacy installer becomes a version JSON plus the Forge jar`() = runTest {
        val root = Files.createTempDirectory("lumen-forge")
        val requests = mutableListOf<String>()
        val result = setup(root, requests).ensure(instance, noJava) {}

        assertEquals(id, result.versionId)
        assertEquals(version, result.loaderVersion)
        val paths = LauncherPaths(root)
        assertContentEquals(forgeBytes, paths.libraries.resolve("net/minecraftforge/forge/$version/forge-$version.jar").readBytes())

        val json = Http.json.parseToJsonElement(paths.versions.resolve(id).resolve("$id.json").readText()).jsonObject
        assertEquals("1.8.9", json.getValue("inheritsFrom").jsonPrimitive.content)
        val libs = json.getValue("libraries").jsonArray.associate { lib ->
            lib.jsonObject.getValue("name").jsonPrimitive.content to lib.jsonObject["url"]?.jsonPrimitive?.content
        }
        assertEquals(setOf("net.minecraftforge:forge:$version", "net.minecraft:launchwrapper:1.12", "org.scala-lang:scala-library:2.11.1"), libs.keys)
        assertNull(libs["net.minecraftforge:forge:$version"], "the Forge jar comes from the installer, not a download")
        assertEquals("https://libraries.minecraft.net/", libs["net.minecraft:launchwrapper:1.12"])
        assertEquals("https://maven.minecraftforge.net/", libs["org.scala-lang:scala-library:2.11.1"])

        // It parses as an ordinary version JSON.
        Http.json.decodeFromString(VersionJson.serializer(), json.toString())
    }

    @Test
    fun `a second launch reuses the install without downloading`() = runTest {
        val root = Files.createTempDirectory("lumen-forge")
        val requests = mutableListOf<String>()
        val installer = setup(root, requests)
        installer.ensure(instance, noJava) {}
        val before = requests.size
        assertEquals(id, installer.ensure(instance, noJava) {}.versionId)
        assertEquals(before, requests.size)
    }
}
