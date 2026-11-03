package dev.lumen.launcher.modrinth.content

import dev.lumen.launcher.core.download.DownloadManager
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.instance.InstancePaths
import dev.lumen.launcher.core.instance.ModLoader
import dev.lumen.launcher.core.net.Http
import dev.lumen.launcher.modrinth.ContentIndexStore
import dev.lumen.launcher.modrinth.ModInstaller
import dev.lumen.launcher.modrinth.ModrinthClient
import dev.lumen.launcher.modrinth.VersionType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VersionMigratorTest {
    private fun hex(algo: String, bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance(algo).digest(bytes))

    private val newSodium = "sodium for 1.21.1".toByteArray()

    /** Modrinth's version JSON, trimmed to what Lumen reads. */
    private fun version(id: String, project: String, number: String, type: String = "release", requires: List<String> = emptyList(), file: ByteArray? = null) = """
        {"id":"$id","project_id":"$project","name":"$project $number","version_number":"$number","version_type":"$type",
         "date_published":"2026-01-01T00:00:00Z","game_versions":["1.21.1"],"loaders":["fabric"],
         "files":[{"url":"https://cdn.example/$id.jar","filename":"$project-$number.jar","primary":true,"size":${file?.size ?: 10},
                   "hashes":{"sha1":"${file?.let { hex("SHA-1", it) } ?: "x"}","sha512":"${file?.let { hex("SHA-512", it) } ?: "x"}"}}],
         "dependencies":[${requires.joinToString { """{"project_id":"$it","dependency_type":"required"}""" }}]}
    """.trimIndent()

    // What /version_files/update knows about, by installed file hash.
    private val updates = mapOf(
        "sha-sodium" to version("sodium-new", "sodium", "0.6.0", file = newSodium),
        "sha-iris" to version("iris-new", "iris", "1.8.0", requires = listOf("oldlib")), // needs a mod with no 1.21.1 build
        "sha-lithium" to version("lithium-cur", "lithium", "0.13.0"), // already the right build
        "sha-beta" to version("beta-latest", "betamod", "2.0-beta", type = "beta"),
    )
    private val projectVersions = mapOf(
        "betamod" to "[${version("beta-latest", "betamod", "2.0-beta", type = "beta")},${version("beta-stable", "betamod", "1.9")}]",
    )

    private fun client(requests: MutableList<String> = mutableListOf()) = HttpClient(MockEngine { request ->
        requests += "${request.method.value} ${request.url.encodedPath}"
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        when {
            request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/version_files/update") -> {
                val body = Http.json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val asked = body.getValue("hashes").jsonArray.map { it.jsonPrimitive.content }
                respond(asked.mapNotNull { h -> updates[h]?.let { "\"$h\":$it" } }.joinToString(",", "{", "}"), HttpStatusCode.OK, json)
            }
            request.url.encodedPath.contains("/project/") -> {
                val project = request.url.encodedPath.substringAfter("/project/").substringBefore('/')
                respond(projectVersions[project] ?: "[]", HttpStatusCode.OK, json)
            }
            request.url.host == "cdn.example" -> respond(newSodium, HttpStatusCode.OK)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }) { install(ContentNegotiation) { json(Http.json) } }

    private fun migrator(http: HttpClient): VersionMigrator {
        val api = ModrinthClient(http, "https://api.example/v2")
        val downloads = DownloadManager(http)
        val index = ContentIndexStore()
        val installer = ModInstaller(api, downloads, index)
        return VersionMigrator(api, InstanceContentManager(api, downloads, index, installer), installer)
    }

    private fun mod(name: String, sha: String, project: String?, version: String? = "$name-cur", enabled: Boolean = true, kind: ContentKind = ContentKind.MOD) =
        ContentItem(kind, "$name${kind.extension}", enabled, sha, 10, project, version, name.replaceFirstChar(Char::uppercase), null, "1.0", VersionType.RELEASE, null)

    private val instance = Instance("i", "Test", "1.20.1", ModLoader.FABRIC, loaderVersion = "0.15.0", createdAt = 0)

    private val items = listOf(
        mod("sodium", "sha-sodium", "sodium"),
        mod("lithium", "sha-lithium", "lithium"),
        mod("oldlib", "sha-oldlib", "oldlib"), // nothing for 1.21.1
        mod("iris", "sha-iris", "iris"),
        mod("handmade", "sha-handmade", project = null, version = null),
        mod("retired", "sha-retired", "retired", enabled = false),
        mod("betamod", "sha-beta", "betamod"),
        mod("faithful", "sha-pack", "faithful", kind = ContentKind.RESOURCE_PACK),
    )

    @Test
    fun `every mod gets a clear fate`() = runTest {
        val plan = migrator(client()).plan(instance, items, "1.21.1")
        val byName = plan.steps.associateBy { it.item.title }

        assertEquals("0.6.0", assertIs<MigrationStep.Switch>(byName.getValue("Sodium")).version.versionNumber)
        assertIs<MigrationStep.Keep>(byName.getValue("Lithium"), "already the 1.21.1 build")
        assertEquals(MigrationStep.Reason.NO_VERSION, assertIs<MigrationStep.TurnOff>(byName.getValue("Oldlib")).reason)
        assertEquals(MigrationStep.Reason.NOT_ON_MODRINTH, assertIs<MigrationStep.TurnOff>(byName.getValue("Handmade")).reason)
        assertIs<MigrationStep.Keep>(byName.getValue("Retired"), "already off: nothing to report")
        assertIs<MigrationStep.Keep>(byName.getValue("Faithful"), "packs load on any version")
    }

    @Test
    fun `a mod that needs a turned-off mod is turned off too`() = runTest {
        val plan = migrator(client()).plan(instance, items, "1.21.1")
        val iris = assertIs<MigrationStep.TurnOff>(plan.steps.first { it.item.title == "Iris" })
        assertEquals(MigrationStep.Reason.NEEDS_TURNED_OFF_MOD, iris.reason)
        assertEquals("Oldlib", plan.neededBy[iris.item.key])
    }

    @Test
    fun `a stable install moves to a stable build when there is one`() = runTest {
        val plan = migrator(client()).plan(instance, items, "1.21.1")
        assertEquals("1.9", assertIs<MigrationStep.Switch>(plan.steps.first { it.item.title == "Betamod" }).version.versionNumber)
    }

    @Test
    fun `applying swaps files, turns others off and keeps worlds`() = runTest {
        val root = Files.createTempDirectory("lumen-migrate")
        val paths = InstancePaths(root)
        paths.all.forEach { Files.createDirectories(it) }
        paths.mods.resolve("sodium.jar").writeBytes("old sodium".toByteArray())
        paths.mods.resolve("oldlib.jar").writeBytes("old lib".toByteArray())
        Files.createDirectories(paths.saves.resolve("My World"))
        paths.saves.resolve("My World").resolve("level.dat").writeBytes(byteArrayOf(1, 2, 3))
        paths.config.resolve("sodium.json").writeBytes("{}".toByteArray())

        val subset = items.filter { it.title in setOf("Sodium", "Oldlib") }
        val m = migrator(client())
        val plan = m.plan(instance, subset, "1.21.1")
        val result = m.apply(plan, instance.copy(gameVersion = "1.21.1", loaderVersion = null), paths, backupWorlds = true) {}

        assertEquals(1, result.switched)
        assertEquals(listOf("Oldlib"), result.turnedOff.map { it.item.title })
        assertContentEquals(newSodium, paths.mods.resolve("sodium-0.6.0.jar").readBytes())
        assertFalse(paths.mods.resolve("sodium.jar").exists())
        assertTrue(paths.mods.resolve("oldlib.jar$DISABLED").exists(), "turned off, not deleted")

        // Kept in place…
        assertTrue(paths.saves.resolve("My World").resolve("level.dat").exists())
        assertTrue(paths.config.resolve("sodium.json").exists())
        // …and copied first.
        assertTrue(result.backup.resolve("mods").resolve("sodium.jar").exists())
        assertTrue(result.backup.resolve("saves").resolve("My World").resolve("level.dat").exists())
    }
}
