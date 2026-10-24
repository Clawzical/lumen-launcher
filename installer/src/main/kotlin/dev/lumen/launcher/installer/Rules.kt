package dev.lumen.launcher.installer

/** The OS facts Mojang's rules test against, injectable so rule logic is unit-testable. */
data class PlatformInfo(val osName: String, val arch: String, val osVersion: String) {
    val is64Bit: Boolean get() = "64" in arch
    val isArm: Boolean get() = "aarch64" in arch || "arm" in arch

    /** Platform key in Mojang's Java runtime index. */
    val runtimeKey: String
        get() = when (osName) {
            "windows" -> when {
                isArm -> "windows-arm64"
                is64Bit -> "windows-x64"
                else -> "windows-x86"
            }
            "osx" -> if (isArm) "mac-os-arm64" else "mac-os"
            else -> if (is64Bit) "linux" else "linux-i386"
        }

    val nativeExtensions: List<String> get() = listOf(".dll", ".so", ".dylib", ".jnilib")

    /** Modern native jars carry the arch in the classifier; only take the one matching this machine. */
    fun acceptsNativeClassifier(classifier: String): Boolean = when {
        "arm64" in classifier || "aarch64" in classifier -> isArm
        classifier.endsWith("-x86") -> !is64Bit
        else -> !isArm // x64 natives can't load in an ARM JVM
    }

    companion object {
        val current: PlatformInfo by lazy {
            val os = System.getProperty("os.name").lowercase()
            PlatformInfo(
                osName = when {
                    "win" in os -> "windows"
                    "mac" in os || "darwin" in os -> "osx"
                    else -> "linux"
                },
                arch = System.getProperty("os.arch").lowercase(),
                osVersion = System.getProperty("os.version"),
            )
        }
    }
}

/**
 * Mojang rule semantics: no rules → allowed. Otherwise start disallowed and let every
 * matching rule set the outcome, so later rules override earlier ones.
 */
object Rules {
    fun allowed(
        rules: List<Rule>,
        platform: PlatformInfo = PlatformInfo.current,
        features: Map<String, Boolean> = emptyMap(),
    ): Boolean {
        if (rules.isEmpty()) return true
        var allow = false
        for (rule in rules) {
            if (matches(rule, platform, features)) allow = rule.action == "allow"
        }
        return allow
    }

    private fun matches(rule: Rule, platform: PlatformInfo, features: Map<String, Boolean>): Boolean {
        rule.os?.let { os ->
            if (os.name != null && os.name != platform.osName) return false
            if (os.arch != null && !archMatches(os.arch, platform)) return false
            if (os.version != null && !Regex(os.version).containsMatchIn(platform.osVersion)) return false
        }
        // Features the launcher doesn't advertise (demo, quick play, custom resolution) count as false.
        return rule.features.all { (name, expected) -> (features[name] ?: false) == expected }
    }

    private fun archMatches(arch: String, platform: PlatformInfo): Boolean = when (arch) {
        "x86" -> !platform.is64Bit
        "x86_64", "amd64" -> platform.is64Bit && !platform.isArm
        "arm64", "aarch64" -> platform.isArm
        else -> platform.arch == arch
    }
}
