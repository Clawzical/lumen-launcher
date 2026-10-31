package dev.lumen.launcher.bedrock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A Bedrock channel Lumen knows how to find in the Microsoft Store. */
enum class BedrockEdition(
    val en: String,
    val zh: String,
    val packageName: String,
    val storeProductId: String,
    /** Folder name under %APPDATA% used by the GDK builds (1.21.120+). */
    val roamingFolder: String,
) {
    Release("Minecraft for Windows", "Minecraft 基岩版", "Microsoft.MinecraftUWP", "9NBLGGH2JHXJ", "Minecraft Bedrock"),
    Preview("Minecraft Preview", "Minecraft 预览版", "Microsoft.MinecraftWindowsBeta", "9P5X4QVLC2XR", "Minecraft Bedrock Preview"),
}

data class BedrockInstall(
    val edition: BedrockEdition,
    /** Store package version, e.g. 1.26.6005.0. */
    val packageVersion: String,
    val packageFamily: String,
    val appId: String,
) {
    /** 1.26.6005.0 → 1.26.60: the patch and build share the third field. */
    val gameVersion: String
        get() {
            val parts = packageVersion.split('.')
            val third = parts.getOrNull(2)?.toIntOrNull() ?: return packageVersion
            return "${parts[0]}.${parts[1]}.${third / 100}"
        }
}

/**
 * Bedrock is a Store app, so Lumen doesn't install or patch anything: it finds the installed
 * package, starts it through the shell (which keeps the Store's licence check), and opens its folders.
 */
class BedrockLauncher {
    val supported: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /** Installed editions; empty when none are installed or not on Windows. */
    suspend fun detect(): Map<BedrockEdition, BedrockInstall> = withContext(Dispatchers.IO) {
        if (!supported) return@withContext emptyMap()
        val names = BedrockEdition.entries.joinToString(",") { "'${it.packageName}'" }
        // One PowerShell run for both editions (each start costs ~0.5 s). Output: name|version|family|appId
        val script = "\$ProgressPreference = 'SilentlyContinue'; foreach (\$n in $names) { \$p = Get-AppxPackage -Name \$n | Select-Object -First 1; " +
            "if (\$p) { \$id = (Get-AppxPackageManifest \$p).Package.Applications.Application | Select-Object -First 1 -ExpandProperty Id; " +
            "\"\$n|\$(\$p.Version)|\$(\$p.PackageFamilyName)|\$id\" } }"
        val output = runPowerShell(script) ?: return@withContext emptyMap()
        output.lineSequence().mapNotNull { line ->
            val (name, version, family, appId) = line.trim().split('|').takeIf { it.size == 4 } ?: return@mapNotNull null
            val edition = BedrockEdition.entries.firstOrNull { it.packageName == name } ?: return@mapNotNull null
            edition to BedrockInstall(edition, version, family, appId.ifBlank { "App" })
        }.toMap()
    }

    /** Starts the game the same way the Start menu does. */
    suspend fun launch(install: BedrockInstall) = withContext(Dispatchers.IO) {
        ProcessBuilder("explorer.exe", "shell:AppsFolder\\${install.packageFamily}!${install.appId}").start()
        Unit
    }

    suspend fun openStore(edition: BedrockEdition) = withContext(Dispatchers.IO) {
        Desktop.getDesktop().browse(URI("ms-windows-store://pdp/?ProductId=${edition.storeProductId}"))
    }

    /** Where worlds, packs and options live: the GDK roaming folder, or the older UWP LocalState one. */
    fun dataFolder(install: BedrockInstall): Path? {
        val env = System.getenv()
        val candidates = listOfNotNull(
            env["APPDATA"]?.let { Path.of(it, install.edition.roamingFolder) },
            env["LOCALAPPDATA"]?.let { Path.of(it, "Packages", install.packageFamily, "LocalState", "games", "com.mojang") },
        )
        return candidates.firstOrNull { Files.isDirectory(it) }
    }

    suspend fun openFolder(path: Path) = withContext(Dispatchers.IO) { Desktop.getDesktop().open(path.toFile()) }

    private fun runPowerShell(script: String): String? = runCatching {
        // Base64 UTF-16LE: Java's Windows argument quoting mangles the script's own double quotes.
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        text
    }.getOrNull()
}
