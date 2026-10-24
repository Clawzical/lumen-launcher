package dev.lumen.launcher.modrinth.content

/** One enabled mod file, as far as compatibility is concerned. [key] identifies the file. */
data class ModEntry(val key: String, val displayName: String, val meta: FabricModMeta)

/** A problem Fabric would refuse to start with. */
sealed interface CompatIssue {
    val modKey: String
    val modName: String

    /** [modName] needs [depName] in [alternatives], but [installed] is present (in file [providerKey]). */
    data class WrongVersion(
        override val modKey: String, override val modName: String,
        val depId: String, val depName: String, val alternatives: List<String>,
        val installed: String, val providerKey: String,
    ) : CompatIssue

    data class Missing(
        override val modKey: String, override val modName: String,
        val depId: String, val alternatives: List<String>,
    ) : CompatIssue

    data class Breaks(
        override val modKey: String, override val modName: String,
        val otherName: String, val alternatives: List<String>, val providerKey: String,
    ) : CompatIssue

    data class WrongGame(
        override val modKey: String, override val modName: String,
        val alternatives: List<String>, val gameVersion: String,
    ) : CompatIssue
}

/**
 * Mirrors Fabric Loader's dependency check: every enabled mod's `depends` must be satisfied by some
 * provider (a mod, something it bundles or `provides`, or Minecraft / the loader itself), and no
 * `breaks` may match. Runs on file metadata only, so it can warn before a 30-second failed launch.
 */
object Compatibility {
    private data class Provider(val version: String, val key: String, val name: String)

    fun check(mods: List<ModEntry>, gameVersion: String, loaderVersion: String?): List<CompatIssue> {
        val providers = HashMap<String, Provider>()
        providers["minecraft"] = Provider(gameVersion, "minecraft", "Minecraft")
        loaderVersion?.let {
            providers["fabricloader"] = Provider(it, "loader", "Fabric Loader")
            providers["quilt_loader"] = Provider(it, "loader", "Quilt Loader")
        }
        for (mod in mods) {
            for (m in mod.meta.flatten()) {
                val p = Provider(m.version, mod.key, if (m === mod.meta) mod.displayName else (m.name ?: m.id))
                providers.putIfAbsent(m.id, p)
                m.provides.forEach { providers.putIfAbsent(it, p) }
            }
        }

        val unchecked = buildSet {
            add("java") // the runtime is picked per version by the installer
            if (loaderVersion == null) { add("fabricloader"); add("quilt_loader") }
        }

        val issues = LinkedHashSet<CompatIssue>()
        for (mod in mods) {
            // Bundled modules' own requirements are the parent's responsibility, like in Fabric.
            for ((depId, alternatives) in mod.meta.depends) {
                if (depId in unchecked) continue
                val p = providers[depId]
                when {
                    p == null -> issues += CompatIssue.Missing(mod.key, mod.displayName, depId, alternatives)
                    FabricVersions.satisfiesAny(p.version, alternatives) -> Unit
                    depId == "minecraft" -> issues += CompatIssue.WrongGame(mod.key, mod.displayName, alternatives, gameVersion)
                    else -> issues += CompatIssue.WrongVersion(mod.key, mod.displayName, depId, p.name, alternatives, p.version, p.key)
                }
            }
            for ((otherId, alternatives) in mod.meta.breaks) {
                val p = providers[otherId] ?: continue
                if (p.key == mod.key) continue
                if (FabricVersions.satisfiesAny(p.version, alternatives)) {
                    issues += CompatIssue.Breaks(mod.key, mod.displayName, p.name, alternatives, p.key)
                }
            }
        }
        return issues.toList()
    }

    /** Requirements other enabled mods place on whatever [providerKey]'s file provides. */
    fun requirementsOn(providerKey: String, mods: List<ModEntry>): List<Pair<ModEntry, List<String>>> {
        val provider = mods.firstOrNull { it.key == providerKey } ?: return emptyList()
        val ids = provider.meta.flatten().flatMap { listOf(it.id) + it.provides }.toSet()
        return mods.filter { it.key != providerKey }.flatMap { dependent ->
            dependent.meta.depends.filterKeys { it in ids }.values.map { dependent to it }
        }
    }
}
