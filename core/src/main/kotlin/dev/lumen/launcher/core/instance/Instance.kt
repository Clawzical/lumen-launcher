package dev.lumen.launcher.core.instance

import dev.lumen.launcher.core.jvm.GcProfile
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
enum class ModLoader(val displayName: String) {
    VANILLA("Vanilla"),
    FABRIC("Fabric"),
    QUILT("Quilt"),
    NEOFORGE("NeoForge");

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
