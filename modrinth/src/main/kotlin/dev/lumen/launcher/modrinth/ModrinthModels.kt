package dev.lumen.launcher.modrinth

import dev.lumen.launcher.core.instance.ModLoader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray

/** Modrinth project types and the instance sub-folder their files install into. */
enum class ProjectType(val apiId: String, val folder: String) {
    MOD("mod", "mods"),
    MODPACK("modpack", ""),            // handled by the .mrpack importer, not dropped in a folder
    RESOURCE_PACK("resourcepack", "resourcepacks"),
    SHADER("shader", "shaderpacks"),
}

enum class SortIndex(val apiId: String) {
    RELEVANCE("relevance"), DOWNLOADS("downloads"), FOLLOWS("follows"), NEWEST("newest"), UPDATED("updated")
}

data class SearchQuery(
    val text: String = "",
    val type: ProjectType = ProjectType.MOD,
    val gameVersion: String? = null,
    val loader: ModLoader? = null,
    val categories: List<String> = emptyList(),
    val sort: SortIndex = SortIndex.RELEVANCE,
    val offset: Int = 0,
    val limit: Int = 20,
) {
    /**
     * Facets are AND-ed across inner arrays, OR-ed within one:
     * `[["project_type:mod"],["versions:1.21.1"],["categories:fabric"]]`
     */
    fun facetsJson(): String = buildJsonArray {
        addJsonArray { add("project_type:${type.apiId}") }
        gameVersion?.let { v -> addJsonArray { add("versions:$v") } }
        // Loaders are categories on Modrinth; only meaningful for mods/modpacks (shaders use iris/optifine).
        if (loader != null && loader != ModLoader.VANILLA && type in setOf(ProjectType.MOD, ProjectType.MODPACK)) {
            addJsonArray { add("categories:${loader.apiId}") }
        }
        // Selected categories are OR-ed (one inner array): "optimization OR utility", not both at once.
        if (categories.isNotEmpty()) addJsonArray { categories.forEach { add("categories:$it") } }
    }.toString()
}

@Serializable
data class SearchResponse(
    val hits: List<SearchHit>,
    val offset: Int,
    val limit: Int,
    @SerialName("total_hits") val totalHits: Int,
)

@Serializable
data class SearchHit(
    @SerialName("project_id") val projectId: String,
    @SerialName("project_type") val projectType: String,
    val slug: String,
    val title: String,
    val description: String,
    val author: String,
    val downloads: Long = 0,
    val follows: Int = 0,
    @SerialName("icon_url") val iconUrl: String? = null,
    val categories: List<String> = emptyList(),
    val versions: List<String> = emptyList(),
    @SerialName("latest_version") val latestVersion: String? = null,
)

@Serializable
data class ModrinthVersion(
    val id: String,
    @SerialName("project_id") val projectId: String,
    val name: String,
    @SerialName("version_number") val versionNumber: String,
    @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    @SerialName("version_type") val versionType: VersionType = VersionType.RELEASE,
    @SerialName("date_published") val datePublished: String,
    val files: List<VersionFile> = emptyList(),
    val dependencies: List<Dependency> = emptyList(),
) {
    val primaryFile: VersionFile? get() = files.firstOrNull { it.primary } ?: files.firstOrNull()
}

@Serializable
enum class VersionType {
    @SerialName("release") RELEASE,
    @SerialName("beta") BETA,
    @SerialName("alpha") ALPHA,
}

@Serializable
data class VersionFile(
    val url: String,
    val filename: String,
    val primary: Boolean = false,
    val size: Long,
    val hashes: Hashes,
)

@Serializable
data class Hashes(val sha1: String, val sha512: String)

@Serializable
data class Dependency(
    @SerialName("version_id") val versionId: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("dependency_type") val type: DependencyType,
)

@Serializable
enum class DependencyType {
    @SerialName("required") REQUIRED,
    @SerialName("optional") OPTIONAL,
    @SerialName("incompatible") INCOMPATIBLE,
    @SerialName("embedded") EMBEDDED,
}
