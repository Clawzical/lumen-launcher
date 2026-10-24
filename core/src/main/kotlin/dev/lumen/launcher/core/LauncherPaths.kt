package dev.lumen.launcher.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * On-disk layout. Game libraries/assets/versions are shared and content-addressed so
 * ten instances on the same Minecraft version don't download 10x the same 600 MB.
 *
 * <root>/
 *   shared/libraries | assets | versions | runtimes
 *   instances/<id>/instance.json
 *   instances/<id>/minecraft/{mods,config,saves,resourcepacks,shaderpacks}
 *   cache/
 */
class LauncherPaths(val root: Path = defaultRoot()) {
    val shared: Path = root.resolve("shared")
    val libraries: Path = shared.resolve("libraries")
    val assets: Path = shared.resolve("assets")
    val versions: Path = shared.resolve("versions")
    val runtimes: Path = shared.resolve("runtimes")
    val instances: Path = root.resolve("instances")
    val cache: Path = root.resolve("cache")

    fun ensure(): LauncherPaths = apply {
        listOf(libraries, assets, versions, runtimes, instances, cache).forEach(Files::createDirectories)
    }

    companion object {
        fun defaultRoot(): Path {
            val os = System.getProperty("os.name").lowercase()
            val home = System.getProperty("user.home")
            return when {
                "win" in os -> Path(System.getenv("APPDATA") ?: home, "Lumen")
                "mac" in os -> Path(home, "Library", "Application Support", "Lumen")
                else -> Path(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "lumen")
            }
        }
    }
}
