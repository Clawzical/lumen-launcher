package dev.lumen.launcher.installer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Mojang's version JSON (also the shape Fabric/Quilt profiles and NeoForge's installer emit).
 * Loader versions declare `inheritsFrom` and only list what they add or override.
 */
@Serializable
data class VersionJson(
    val id: String,
    val inheritsFrom: String? = null,
    val type: String? = null,
    val mainClass: String? = null,
    /** Pre-1.13 versions: one space-separated string instead of [arguments]. */
    val minecraftArguments: String? = null,
    val arguments: Arguments? = null,
    val assetIndex: AssetIndexRef? = null,
    val downloads: Map<String, Artifact> = emptyMap(),
    val libraries: List<Library> = emptyList(),
    val javaVersion: JavaVersionRef? = null,
    val logging: Logging? = null,
)

/** Each element is either a plain string or `{"rules": [...], "value": "x" | ["x", "y"]}`. */
@Serializable
data class Arguments(val game: List<JsonElement> = emptyList(), val jvm: List<JsonElement> = emptyList())

@Serializable
data class AssetIndexRef(val id: String, val sha1: String, val size: Long, val totalSize: Long? = null, val url: String)

@Serializable
data class Artifact(val path: String? = null, val sha1: String? = null, val size: Long? = null, val url: String = "")

@Serializable
data class LibraryDownloads(val artifact: Artifact? = null, val classifiers: Map<String, Artifact> = emptyMap())

@Serializable
data class Library(
    val name: String,
    val downloads: LibraryDownloads? = null,
    /** Maven repository base (Fabric/Quilt style libraries have no `downloads`). */
    val url: String? = null,
    val sha1: String? = null,
    val size: Long? = null,
    val rules: List<Rule> = emptyList(),
    /** Legacy (pre-1.19) natives: OS name → classifier, e.g. "windows" → "natives-windows". */
    val natives: Map<String, String> = emptyMap(),
    val extract: ExtractRules? = null,
)

@Serializable
data class ExtractRules(val exclude: List<String> = emptyList())

@Serializable
data class Rule(val action: String, val os: OsRule? = null, val features: Map<String, Boolean> = emptyMap())

@Serializable
data class OsRule(val name: String? = null, val arch: String? = null, val version: String? = null)

@Serializable
data class JavaVersionRef(val component: String = "jre-legacy", val majorVersion: Int = 8)

@Serializable
data class Logging(val client: LoggingConfig? = null)

@Serializable
data class LoggingConfig(val argument: String, val file: LogFile, val type: String? = null)

@Serializable
data class LogFile(val id: String, val sha1: String, val size: Long, val url: String)

@Serializable
data class AssetIndex(
    val objects: Map<String, AssetObject>,
    val virtual: Boolean = false,
    @SerialName("map_to_resources") val mapToResources: Boolean = false,
)

@Serializable
data class AssetObject(val hash: String, val size: Long)

/** `group:artifact:version[:classifier][@ext]` → repository-relative path. */
data class MavenCoordinate(
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String? = null,
    val extension: String = "jar",
) {
    val path: String
        get() = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version${classifier?.let { "-$it" }.orEmpty()}.$extension"

    /** Identity without version: a loader's newer ASM must replace vanilla's, not sit beside it. */
    val dedupeKey: String get() = "$group:$artifact${classifier?.let { ":$it" }.orEmpty()}"

    companion object {
        fun parse(name: String): MavenCoordinate {
            val (coords, ext) = name.split('@', limit = 2).let { it[0] to (it.getOrNull(1) ?: "jar") }
            val parts = coords.split(':')
            require(parts.size >= 3) { "Not a Maven coordinate: $name" }
            return MavenCoordinate(parts[0], parts[1], parts[2], parts.getOrNull(3), ext)
        }
    }
}
