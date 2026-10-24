package dev.lumen.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.produceState
import dev.lumen.launcher.AppState
import dev.lumen.launcher.core.download.DownloadProgress
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.jvm.JvmOptimizer
import dev.lumen.launcher.ui.theme.LumenColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
fun ScreenHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun InstanceAvatar(instance: Instance, size: Dp) {
    val tint = LumenColors.avatarPalette[abs(instance.id.hashCode()) % LumenColors.avatarPalette.size]
    Box(
        Modifier.size(size).clip(MaterialTheme.shapes.medium).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            instance.name.take(1).uppercase(),
            color = tint,
            fontWeight = FontWeight.Bold,
            style = if (size > 48.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
fun Tag(text: String, accent: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.clip(MaterialTheme.shapes.extraSmall)
            .background(if (accent) cs.primaryContainer else cs.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = if (accent) cs.primary else cs.onSurfaceVariant)
    }
}

@Composable
fun DownloadsPanel(items: List<DownloadProgress>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth().border(1.dp, cs.outline, MaterialTheme.shapes.large),
        color = cs.surface,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (items.isNotEmpty()) {
                    Text(
                        "${items.size} active · ${formatSpeed(items.sumOf { it.bytesPerSecond })}",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.primary,
                    )
                }
            }
            if (items.isEmpty()) {
                Text("Nothing downloading.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            items.sortedBy { it.label }.forEach { DownloadRow(it) }
        }
    }
}

@Composable
private fun DownloadRow(p: DownloadProgress) {
    val cs = MaterialTheme.colorScheme
    val animated by animateFloatAsState(p.fraction, tween(200), label = "progress")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(p.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val sizeText = if (p.bytesTotal > 0) "${formatBytes(p.bytesDone)} / ${formatBytes(p.bytesTotal)}" else formatBytes(p.bytesDone)
            Text("$sizeText · ${formatSpeed(p.bytesPerSecond)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        val barModifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.extraSmall)
        if (p.bytesTotal > 0) {
            LinearProgressIndicator(
                progress = { animated },
                modifier = barModifier,
                color = cs.primary,
                trackColor = cs.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        } else {
            LinearProgressIndicator(modifier = barModifier, color = cs.primary, trackColor = cs.surfaceContainerHighest)
        }
    }
}

@Composable
fun PlaceholderScreen(title: String, description: String) {
    Box(Modifier.fillMaxSize().padding(32.dp)) {
        ScreenHeader(title, description)
    }
}

data class MemoryEstimate(val modCount: Int, val heapMb: Int, val auto: Boolean) {
    val label: String get() = "${if (auto) "Auto" else "Fixed"} · ${formatHeap(heapMb)}"
}

/**
 * Counts the instance's enabled mods off the UI thread and derives the heap the game would get.
 * Recomputes when the instance or its content changes ([AppState.contentRevision]).
 */
@Composable
fun rememberMemoryEstimate(state: AppState, instance: Instance): MemoryEstimate {
    val fallback = MemoryEstimate(0, instance.maxMemoryMb ?: JvmOptimizer.recommendHeapMb(0, instance.loader), instance.maxMemoryMb == null)
    val estimate by produceState(fallback, instance.id, instance.loader, instance.maxMemoryMb, state.contentRevision) {
        val mods = state.instances.countMods(instance)
        value = MemoryEstimate(
            modCount = mods,
            heapMb = instance.maxMemoryMb ?: withContext(Dispatchers.Default) { JvmOptimizer.recommendHeapMb(mods, instance.loader) },
            auto = instance.maxMemoryMb == null,
        )
    }
    return estimate
}

fun formatHeap(mb: Int): String = if (mb % 1024 == 0) "${mb / 1024} GB" else "%.1f GB".format(mb / 1024.0)

fun modCountLabel(count: Int): String = when (count) {
    0 -> "No mods"
    1 -> "1 mod"
    else -> "$count mods"
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

fun timeAgo(epochMs: Long?): String {
    if (epochMs == null) return "Never played"
    val minutes = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        minutes < 1 -> "Played just now"
        minutes < 60 -> "Played ${minutes}m ago"
        minutes < 60 * 24 -> "Played ${minutes / 60}h ago"
        else -> "Played ${minutes / (60 * 24)}d ago"
    }
}
