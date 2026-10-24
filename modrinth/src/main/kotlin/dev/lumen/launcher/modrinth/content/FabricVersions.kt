package dev.lumen.launcher.modrinth.content

/**
 * Fabric's version predicates, as used in `fabric.mod.json` `depends`/`breaks`:
 * `*`, `1.2.x` / `1.*`, `=`, `>=`, `>`, `<=`, `<`, `~1.2` (same minor), `^1.2` (same major),
 * several space-separated terms = AND, a JSON array of predicates = OR.
 *
 * Versions that aren't SemVer (e.g. "build.12") are accepted leniently: we'd rather miss a
 * conflict than invent one, since Fabric itself is the final judge at launch.
 */
object FabricVersions {
    data class SemVer(val parts: List<Int>, val pre: List<String>) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int {
            for (i in 0 until maxOf(parts.size, other.parts.size)) {
                val c = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            if (pre.isEmpty() || other.pre.isEmpty()) return other.pre.size.coerceAtMost(1) - pre.size.coerceAtMost(1)
            for (i in 0 until minOf(pre.size, other.pre.size)) {
                val a = pre[i]
                val b = other.pre[i]
                val an = a.toIntOrNull()
                val bn = b.toIntOrNull()
                val c = when {
                    an != null && bn != null -> an.compareTo(bn)
                    an != null -> -1
                    bn != null -> 1
                    else -> a.compareTo(b)
                }
                if (c != 0) return c
            }
            return pre.size.compareTo(other.pre.size)
        }
    }

    /** "0.6.13+mc1.21.1" → [0,6,13]; "1.0.0-beta.2" → [1,0,0] pre [beta,2]; non-SemVer → null. */
    fun parse(version: String): SemVer? {
        val core = version.trim().substringBefore('+')
        if (core.isEmpty()) return null
        val main = core.substringBefore('-')
        val pre = if ('-' in core) core.substringAfter('-').split('.') else emptyList()
        val parts = main.split('.').map { it.toIntOrNull() ?: return null }
        return SemVer(parts, pre)
    }

    /** True when [version] satisfies at least one alternative (empty list = any version). */
    fun satisfiesAny(version: String, alternatives: List<String>): Boolean =
        alternatives.isEmpty() || alternatives.any { satisfies(version, it) }

    fun satisfies(version: String, predicate: String): Boolean {
        val v = parse(version) ?: return true
        return predicate.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { term(v, it) }
    }

    /**
     * For Modrinth `version_number`s like "mc1.21.1-0.6.13-fabric", which aren't SemVer: true when any
     * dotted number in it (other than the game version itself) satisfies the alternatives.
     */
    fun anyTokenSatisfies(versionNumber: String, alternatives: List<String>, ignore: Set<String> = emptySet()): Boolean {
        if (alternatives.isEmpty()) return true
        return Regex("""\d+(?:\.\d+)+""").findAll(versionNumber).map { it.value }
            .filter { it !in ignore }
            .any { satisfiesAny(it, alternatives) }
    }

    /** Human form: ["0.6.x"] → "0.6.x"; ["1.0", "2.0"] → "1.0 or 2.0"; [] → "any version". */
    fun describe(alternatives: List<String>): String =
        if (alternatives.isEmpty() || alternatives == listOf("*")) "any version" else alternatives.joinToString(" or ")

    private val operators = listOf(">=", "<=", ">", "<", "=", "~", "^")

    private fun term(v: SemVer, raw: String): Boolean {
        val op = operators.firstOrNull { raw.startsWith(it) } ?: ""
        val rest = raw.removePrefix(op).trim()
        if (rest.isEmpty() || rest == "*") return true

        val comps = rest.substringBefore('+').substringBefore('-').split('.')
        val wildcard = comps.indexOfFirst { it == "x" || it == "X" || it == "*" }
        if (wildcard >= 0) {
            val prefix = comps.take(wildcard).map { it.toIntOrNull() ?: return true }
            val bound = SemVer(prefix, emptyList())
            return when (op) {
                ">=", ">" -> v >= bound
                "<", "<=" -> v < bound
                else -> prefix.indices.all { v.parts.getOrElse(it) { 0 } == prefix[it] } // "0.6.x"
            }
        }

        val target = parse(rest) ?: return true
        return when (op) {
            "", "=" -> v.compareTo(target) == 0
            ">=" -> v >= target
            ">" -> v > target
            "<=" -> v <= target
            "<" -> v < target
            "~" -> v >= target && v < next(target, if (target.parts.size >= 2) 1 else 0)
            "^" -> v >= target && v < next(target, 0)
            else -> true
        }
    }

    /** Smallest version that bumps component [index]: next(1.2.3, 1) = 1.3. */
    private fun next(v: SemVer, index: Int): SemVer =
        SemVer(v.parts.take(index) + (v.parts.getOrElse(index) { 0 } + 1), emptyList())
}
