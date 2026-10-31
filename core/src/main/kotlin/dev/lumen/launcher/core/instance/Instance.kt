package dev.lumen.launcher.core.instance

import dev.lumen.launcher.core.jvm.GcProfile
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
/** [oldestGameVersion]: the first Minecraft release the loader supports (null = every version). */
enum class ModLoader(val displayName: String, val oldestGameVersion: String?) {
    VANILLA("Vanilla", null),
    FABRIC("Fabric", "1.14"),
    QUILT("Quilt", "1.14"),
    // Older Forge exists, but those versions use the pre-1.7.10 asset layouts Lumen doesn't handle yet.
    FORGE("Forge", "1.7.10"),
    NEOFORGE("NeoForge", "1.20.1");

    /** Identifier used by Modrinth facets / version filters. */
    val apiId: String get() = name.lowercase()
}

@Serializable
data class Instance(
    val id: String,
    val name: String,
    val gameVersion: String,
    val loader: ModLoader,
    val loaderVersion: String? = null,
    /** null = let [dev.lumen.launcher.core.jvm.JvmOptimizer] decide from system RAM + mod count. */
    val maxMemoryMb: Int? = null,
    val gcProfile: GcProfile = GcProfile.AUTO,
    val extraJvmArgs: List<String> = emptyList(),
    val createdAt: Long,
    val lastPlayedAt: Long? = null,
)

/** Everything an instance owns lives under its own folder — nothing leaks between instances. */
class InstancePaths(val root: Path) {
    val manifest: Path = root.resolve("instance.json")
    val gameDir: Path = root.resolve("minecraft")
    val mods: Path = gameDir.resolve("mods")
    val config: Path = gameDir.resolve("config")
    val saves: Path = gameDir.resolve("saves")
    val resourcePacks: Path = gameDir.resolve("resourcepacks")
    val shaderPacks: Path = gameDir.resolve("shaderpacks")
    val all: List<Path> get() = listOf(gameDir, mods, config, saves, resourcePacks, shaderPacks)
}
