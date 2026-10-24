package dev.lumen.launcher.modrinth

import dev.lumen.launcher.core.instance.ModLoader
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
}
