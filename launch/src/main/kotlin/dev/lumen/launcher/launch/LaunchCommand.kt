package dev.lumen.launcher.launch

import dev.lumen.launcher.core.jvm.JvmPlan
import dev.lumen.launcher.core.net.Http
import dev.lumen.launcher.installer.PlatformInfo
import dev.lumen.launcher.installer.PreparedGame
import dev.lumen.launcher.installer.Rule
import dev.lumen.launcher.installer.Rules
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Path
import java.util.UUID

/** Who the game runs as. Until the :auth module exists, only offline accounts are possible. */
data class LaunchAccount(
    val name: String,
    val uuid: String,
    val accessToken: String,
    val userType: String,
    val xuid: String = "",
    val clientId: String = "",
) {
    companion object {
        /** Same UUID derivation vanilla servers use for offline players, so worlds keep your inventory. */
        fun offline(name: String): LaunchAccount {
            val safe = name.filter { it.isLetterOrDigit() || it == '_' }.take(16).ifEmpty { "Player" }.let { if (it.length < 3) "Player" else it }
            val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$safe".toByteArray()).toString().replace("-", "")
            return LaunchAccount(safe, uuid, accessToken = "0", userType = "legacy")
        }
    }
}

object LaunchCommand {
    private val placeholder = Regex("""\$\{([^}]+)}""")

    /** Full argv after the java executable: tuned JVM flags, version JVM args, main class, game args. */
    fun arguments(
        game: PreparedGame,
        gameDir: Path,
        account: LaunchAccount,
        jvm: JvmPlan,
        launcherName: String = "Lumen",
        launcherVersion: String = "1.0.0",
        platform: PlatformInfo = PlatformInfo.current,
    ): List<String> {
        val v = game.version
        val vars = mapOf(
            "auth_player_name" to account.name,
            "version_name" to v.id,
            "game_directory" to gameDir.toString(),
            "assets_root" to game.assetsRoot.toString(),
            "game_assets" to game.assetsRoot.toString(),
            "assets_index_name" to v.assetIndex.id,
            "auth_uuid" to account.uuid,
            "auth_access_token" to account.accessToken,
            "auth_session" to account.accessToken,
            "clientid" to account.clientId,
            "auth_xuid" to account.xuid,
            "user_type" to account.userType,
            "user_properties" to "{}",
            "version_type" to v.type,
            "natives_directory" to game.nativesDir.toString(),
            "launcher_name" to launcherName,
            "launcher_version" to launcherVersion,
            "classpath" to game.classpath.joinToString(File.pathSeparator),
            "classpath_separator" to File.pathSeparator,
            "library_directory" to game.librariesDir.toString(),
            "primary_jar" to game.gameJar.toString(),
        )

        return buildList {
            addAll(jvm.args) // heap + GC profile from JvmOptimizer come first
            if (v.jvmArgs.isEmpty()) {
                // Pre-1.13 JSONs have no JVM arguments; supply the two the game can't start without.
                add("-Djava.library.path=${game.nativesDir}")
                add("-cp")
                add(vars.getValue("classpath"))
            } else {
                addAll(expand(v.jvmArgs, vars, platform))
            }
            // As a file: URI — NeoForge's log4j rejects a bare Windows path ("Error parsing URI C:\...").
            val logging = v.logging
            val logConfig = game.logConfig
            if (logging != null && logConfig != null) add(logging.argument.replace("\${path}", logConfig.toUri().toString()))
            add(v.mainClass)
            val gameArgs = if (v.gameArgs.isNotEmpty()) expand(v.gameArgs, vars, platform)
            else v.legacyArguments.orEmpty().split(' ').filter { it.isNotBlank() }.map { substitute(it, vars) }
            addAll(dropEmptyOptions(gameArgs))
        }
    }

    /** Applies each conditional argument's rules (OS + features; we advertise no features). */
    fun expand(args: List<JsonElement>, vars: Map<String, String>, platform: PlatformInfo): List<String> = args.flatMap { el ->
        when (el) {
            is JsonPrimitive -> listOf(el.content)
            is JsonObject -> {
                val rules = el["rules"]?.let { Http.json.decodeFromJsonElement(ListSerializer(Rule.serializer()), it) }.orEmpty()
                if (!Rules.allowed(rules, platform)) emptyList()
                else when (val value = el["value"]) {
                    is JsonPrimitive -> listOf(value.content)
                    is JsonArray -> value.map { (it as JsonPrimitive).content }
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }.map { substitute(it, vars) }

    fun substitute(arg: String, vars: Map<String, String>): String =
        placeholder.replace(arg) { m -> vars[m.groupValues[1]] ?: m.value }

    /** `--xuid ""` or an unresolved `${...}` value confuses the game's option parser — drop the pair. */
    fun dropEmptyOptions(args: List<String>): List<String> {
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val next = args.getOrNull(i + 1)
            if (arg.startsWith("--") && next != null && !next.startsWith("--") && (next.isBlank() || placeholder.containsMatchIn(next))) {
                i += 2
                continue
            }
            out += arg
            i++
        }
        return out
    }

    /**
     * Splits out every `--flag value` pair whose value contains [token] (e.g. `--accessToken`, legacy
     * `--session token:...`). Offline tokens ("0") aren't secret and stay put.
     */
    fun splitSecrets(args: List<String>, token: String): Pair<List<String>, List<String>> {
        if (token.length < 8) return args to emptyList()
        val keep = ArrayList<String>(args.size)
        val secret = ArrayList<String>(4)
        var i = 0
        while (i < args.size) {
            val next = args.getOrNull(i + 1)
            if (args[i].startsWith("--") && next != null && token in next) {
                secret += args[i]
                secret += next
                i += 2
            } else {
                keep += args[i]
                i++
            }
        }
        return keep to secret
    }

    /**
     * Java @argfile body. Windows caps a command line at 32,767 chars and a NeoForge classpath
     * alone can approach that, so on Java 9+ everything after `java` goes in a file.
     * Inside quotes, backslash is the escape character.
     */
    fun toArgFile(args: List<String>): String =
        args.joinToString("\n") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
}
