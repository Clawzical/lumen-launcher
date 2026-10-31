package dev.lumen.launcher.core.jvm

import dev.lumen.launcher.core.instance.ModLoader
import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory
import kotlin.math.max
import kotlin.math.min

@Serializable
enum class GcProfile { AUTO, ZGC, SHENANDOAH, G1 }

data class JvmPlan(val heapMb: Int, val gc: GcProfile, val args: List<String>)

/**
 * Builds the game's JVM arguments. Rules of thumb:
 *  - Generational ZGC (Java 21+) gives sub-millisecond pauses → no GC stutter on big modpacks.
 *  - G1 with Aikar-style tuning is still best for small heaps and Java 8/17 (pre-1.20.5 versions).
 *  - Shenandoah is opt-in: Temurin/Red Hat builds ship it, Oracle builds don't.
 *  - Never hand the game more than half the machine's RAM — the OS swapping hurts more than GC.
 */
object JvmOptimizer {

    fun totalSystemMemoryMb(): Long {
        val bean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        return bean?.totalMemorySize?.div(1024 * 1024) ?: 8192
    }

    /**
     * Baseline per loader (NeoForge's own runtime and registry snapshots cost more than Fabric's),
     * plus a per-mod allowance, rounded up to 512 MB and capped by what the machine can spare.
     */
    fun recommendHeapMb(
        modCount: Int,
        loader: ModLoader = ModLoader.VANILLA,
        systemMb: Long = totalSystemMemoryMb(),
    ): Int {
        val (baseMb, perModMb) = when (loader) {
            ModLoader.VANILLA -> 2048 to 0
            ModLoader.FABRIC, ModLoader.QUILT -> 2560 to 20
            ModLoader.FORGE, ModLoader.NEOFORGE -> 3584 to 28
        }
        val wanted = min(baseMb + modCount * perModMb, 12_288)
        val ceiling = max(1024L, min(systemMb / 2, systemMb - 3072)).toInt()
        return ((min(wanted, ceiling) + 511) / 512) * 512
    }

    fun plan(
        javaMajor: Int,
        modCount: Int,
        loader: ModLoader = ModLoader.VANILLA,
        requestedHeapMb: Int? = null,
        profile: GcProfile = GcProfile.AUTO,
        extra: List<String> = emptyList(),
    ): JvmPlan {
        val heap = requestedHeapMb ?: recommendHeapMb(modCount, loader)
        val gc = resolveGc(profile, javaMajor, heap)
        val args = buildList {
            add("-Xms${heap}m")
            add("-Xmx${heap}m")
            addAll(gcArgs(gc, javaMajor))
            add("-XX:+DisableExplicitGC")   // some mods call System.gc() every few seconds
            add("-XX:+PerfDisableSharedMem") // avoids hsperfdata disk writes stalling GC on slow disks
            if (javaMajor >= 17) add("-XX:+UseStringDeduplication")
            add("-Dfile.encoding=UTF-8")
            addAll(extra)
        }
        return JvmPlan(heap, gc, args)
    }

    private fun resolveGc(profile: GcProfile, javaMajor: Int, heapMb: Int): GcProfile = when (profile) {
        GcProfile.AUTO -> if (javaMajor >= 21 && heapMb >= 4096) GcProfile.ZGC else GcProfile.G1
        GcProfile.ZGC -> if (javaMajor >= 21) GcProfile.ZGC else GcProfile.G1 // only generational ZGC is worth it
        else -> profile
    }

    private fun gcArgs(gc: GcProfile, javaMajor: Int): List<String> = when (gc) {
        GcProfile.ZGC -> buildList {
            add("-XX:+UseZGC")
            // Generational is default (and the flag deprecated) from JDK 23 onwards.
            if (javaMajor in 21..22) add("-XX:+ZGenerational")
        }
        GcProfile.SHENANDOAH -> listOf("-XX:+UseShenandoahGC", "-XX:ShenandoahGCHeuristics=adaptive")
        GcProfile.G1, GcProfile.AUTO -> listOf(
            "-XX:+UseG1GC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=200",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:G1NewSizePercent=30",
            "-XX:G1MaxNewSizePercent=40",
            "-XX:G1HeapRegionSize=8M",
            "-XX:G1ReservePercent=20",
            "-XX:G1HeapWastePercent=5",
            "-XX:G1MixedGCCountTarget=4",
            "-XX:InitiatingHeapOccupancyPercent=15",
            "-XX:G1MixedGCLiveThresholdPercent=90",
            "-XX:G1RSetUpdatingPauseTimePercent=5",
            "-XX:SurvivorRatio=32",
            "-XX:MaxTenuringThreshold=1",
        )
    }
}
