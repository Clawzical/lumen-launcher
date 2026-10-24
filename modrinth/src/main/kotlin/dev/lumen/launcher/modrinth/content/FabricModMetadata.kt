package dev.lumen.launcher.modrinth.content

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** What a Fabric/Quilt mod jar declares about itself in `fabric.mod.json`. */
data class FabricModMeta(
    val id: String,
    val version: String,
    val name: String?,
    val provides: List<String> = emptyList(),
    /** mod id → alternatives (OR). Each alternative may hold several space-separated terms (AND). */
    val depends: Map<String, List<String>> = emptyMap(),
    val breaks: Map<String, List<String>> = emptyMap(),
    /** Jar-in-jar mods bundled inside this one (e.g. Fabric API's ~50 modules). */
    val bundled: List<FabricModMeta> = emptyList(),
) {
    /** This mod plus everything bundled inside it, recursively. */
    fun flatten(): List<FabricModMeta> = listOf(this) + bundled.flatMap { it.flatten() }
}

object FabricModReader {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowComments = true // some mods ship comments in fabric.mod.json; Fabric tolerates them
        allowTrailingComma = true
    }

    private const val DESCRIPTOR = "fabric.mod.json"
    private const val MAX_DEPTH = 3

    /** Null when the jar isn't a Fabric mod (or can't be read). */
    fun read(jar: Path): FabricModMeta? = runCatching {
        ZipFile(jar.toFile()).use { zip ->
            val entry = zip.getEntry(DESCRIPTOR) ?: return@use null
            parse(zip.getInputStream(entry).readBytes(), depth = 0) { path ->
                zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
            }
        }
    }.getOrNull()

    private fun parse(descriptor: ByteArray, depth: Int, open: (String) -> ByteArray?): FabricModMeta? {
        val obj = runCatching { json.parseToJsonElement(descriptor.decodeToString()).jsonObject }.getOrNull() ?: return null
        val id = obj.string("id") ?: return null
        val bundled = if (depth >= MAX_DEPTH) emptyList() else {
            (obj["jars"] as? JsonArray).orEmpty().mapNotNull { j ->
                val path = (j as? JsonObject)?.string("file") ?: return@mapNotNull null
                open(path)?.let { readNested(it, depth + 1) }
            }
        }
        return FabricModMeta(
            id = id,
            version = obj.string("version") ?: "0",
            name = obj.string("name"),
            provides = (obj["provides"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            depends = predicates(obj["depends"]),
            breaks = predicates(obj["breaks"]),
            bundled = bundled,
        )
    }

    /** Nested jars are read from memory: only the descriptor and further nested jars are kept. */
    private fun readNested(bytes: ByteArray, depth: Int): FabricModMeta? {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.name == DESCRIPTOR || (e.name.startsWith("META-INF/jars/") && e.name.endsWith(".jar"))) {
                    entries[e.name] = zin.readBytes()
                }
            }
        }
        val descriptor = entries[DESCRIPTOR] ?: return null
        return parse(descriptor, depth) { entries[it] }
    }

    private fun predicates(el: JsonElement?): Map<String, List<String>> =
        (el as? JsonObject)?.mapValues { (_, v) ->
            when (v) {
                is JsonPrimitive -> listOfNotNull(v.contentOrNull)
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> emptyList()
            }
        }.orEmpty()

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
