package dev.lumen.launcher.modrinth

import dev.lumen.launcher.core.instance.ModLoader
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/**
 * Thin, fully suspending wrapper over Modrinth API v2 (https://docs.modrinth.com/api/).
 *
 * The shared [HttpClient] must send a unique User-Agent (e.g. `you/lumen-launcher/1.0.0 (you@mail)`):
 * Modrinth blocks generic agents. Limit is 300 req/min per IP; on 429 we wait for the window
 * reported in `X-Ratelimit-Reset` and retry instead of surfacing an error to the user.
 */
class ModrinthClient(
    private val http: HttpClient,
    private val baseUrl: String = "https://api.modrinth.com/v2",
) {
    suspend fun search(query: SearchQuery): SearchResponse = call {
        http.get("$baseUrl/search") {
            if (query.text.isNotBlank()) parameter("query", query.text)
            parameter("facets", query.facetsJson())
            parameter("index", query.sort.apiId)
            parameter("offset", query.offset)
            parameter("limit", query.limit.coerceIn(1, 100))
        }.body()
    }

    /** Versions of a project, newest first, optionally filtered to what an instance can run. */
    suspend fun getProjectVersions(
        projectIdOrSlug: String,
        loader: ModLoader? = null,
        gameVersion: String? = null,
    ): List<ModrinthVersion> = call {
        http.get("$baseUrl/project/$projectIdOrSlug/version") {
            if (loader != null && loader != ModLoader.VANILLA) parameter("loaders", jsonArrayOf(loader.apiId))
            if (gameVersion != null) parameter("game_versions", jsonArrayOf(gameVersion))
            parameter("include_changelog", false) // changelogs can be most of the payload
        }.body()
    }

    suspend fun getVersion(versionId: String): ModrinthVersion = call {
        http.get("$baseUrl/version/$versionId").body()
    }

    /** One round trip for many versions — used when a modpack/lockfile pins exact versions. */
    suspend fun getVersions(versionIds: Collection<String>): List<ModrinthVersion> {
        if (versionIds.isEmpty()) return emptyList()
        return call {
            http.get("$baseUrl/versions") { parameter("ids", jsonArrayOf(*versionIds.toTypedArray())) }.body()
        }
    }

    /** Title/icon for many projects in one request (instance content lists). */
    suspend fun getProjects(ids: Collection<String>): List<ModrinthProject> {
        if (ids.isEmpty()) return emptyList()
        return call { http.get("$baseUrl/projects") { parameter("ids", jsonArrayOf(*ids.toTypedArray())) }.body() }
    }

    /**
     * Identifies files by SHA-1: which Modrinth version each one is. Used to recognise mods that were
     * added by hand (or before Lumen kept an index) so they can be updated and swapped too.
     */
    suspend fun versionsByHashes(sha1s: Collection<String>): Map<String, ModrinthVersion> {
        if (sha1s.isEmpty()) return emptyMap()
        return call {
            http.post("$baseUrl/version_files") {
                setBody(json(buildJsonObject {
                    putJsonArray("hashes") { sha1s.forEach { add(it) } }
                    put("algorithm", "sha1")
                }))
            }.body()
        }
    }

    /** Newest version compatible with the given loaders/game versions for each file hash, in one request. */
    suspend fun latestVersionsByHashes(
        sha1s: Collection<String>,
        loaders: List<String>?,
        gameVersions: List<String>?,
    ): Map<String, ModrinthVersion> {
        if (sha1s.isEmpty()) return emptyMap()
        return call {
            http.post("$baseUrl/version_files/update") {
                setBody(json(buildJsonObject {
                    putJsonArray("hashes") { sha1s.forEach { add(it) } }
                    put("algorithm", "sha1")
                    loaders?.let { l -> putJsonArray("loaders") { l.forEach { add(it) } } }
                    gameVersions?.let { g -> putJsonArray("game_versions") { g.forEach { add(it) } } }
                }))
            }.body()
        }
    }

    /**
     * Prefer the newest stable release; fall back to beta, then alpha.
     * With [publishedBy], only versions released at or before that instant are considered (when any exist).
     */
    suspend fun bestVersion(
        projectIdOrSlug: String,
        loader: ModLoader?,
        gameVersion: String?,
        publishedBy: Instant? = null,
    ): ModrinthVersion? {
        val versions = getProjectVersions(projectIdOrSlug, loader, gameVersion)
        val pool = publishedBy?.let { cutoff ->
            versions.filter { runCatching { Instant.parse(it.datePublished) <= cutoff }.getOrDefault(false) }.ifEmpty { null }
        } ?: versions
        return VersionType.entries.firstNotNullOfOrNull { type -> pool.firstOrNull { it.versionType == type } }
    }

    private suspend fun <T> call(maxAttempts: Int = 4, block: suspend () -> T): T {
        repeat(maxAttempts - 1) {
            try {
                return block()
            } catch (e: ClientRequestException) {
                if (e.response.status != HttpStatusCode.TooManyRequests) throw e
                val resetSeconds = e.response.headers["X-Ratelimit-Reset"]?.toLongOrNull() ?: 5
                delay(resetSeconds.coerceIn(1, 60) * 1000)
            }
        }
        return block()
    }

    private fun jsonArrayOf(vararg values: String) = JsonArray(values.map(::JsonPrimitive)).toString()

    /** Pre-serialized JSON body; bypasses ContentNegotiation so it isn't re-encoded as a string. */
    private fun json(obj: JsonObject) = TextContent(obj.toString(), ContentType.Application.Json)
}
