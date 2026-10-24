package dev.lumen.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lumen.launcher.AppState
import dev.lumen.launcher.LaunchPhase
import dev.lumen.launcher.Screen
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.instance.ModLoader
import java.time.LocalTime

@Composable
fun DashboardScreen(state: AppState, instances: List<Instance>) {
    val selected = state.selected(instances)
    val downloads by state.downloads.active.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        ScreenHeader(greeting(), "Pick an instance and jump back in.")

        if (selected == null) {
            EmptyHero(onCreate = { state.screen = Screen.Instances })
        } else {
            HeroCard(
                selected,
                memory = rememberMemoryEstimate(state, selected),
                phase = state.launchPhases[selected.id],
                onPlay = { state.play(selected) },
                onStop = { state.stop(selected) },
                onManage = { state.manage(selected) },
            )
        }

        if (instances.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Recent instances", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(instances.take(10), key = { it.id }) { inst ->
                        InstanceMiniCard(inst, selected = inst.id == selected?.id, onClick = { state.selectedInstanceId = inst.id })
                    }
                }
            }
        }

        DownloadsPanel(downloads.values.toList())
    }
}

@Composable
private fun HeroCard(
    instance: Instance,
    memory: MemoryEstimate,
    phase: LaunchPhase?,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onManage: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    Box(
        Modifier.fillMaxWidth().height(232.dp)
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(Color(0xFF173A2A), Color(0xFF14181F), Color(0xFF18203A))))
            .border(1.dp, cs.outline, MaterialTheme.shapes.large)
            .padding(28.dp),
    ) {
        Row(Modifier.align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
            InstanceAvatar(instance, 56.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(instance.name, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(instance.gameVersion, accent = true)
                    Tag(instance.loader.displayName)
                    if (instance.loader != ModLoader.VANILLA) Tag(modCountLabel(memory.modCount))
                    Tag("RAM ${memory.label}")
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlayButton(phase, onPlay, onStop)
            OutlinedButton(onClick = onManage, modifier = Modifier.height(52.dp), shape = MaterialTheme.shapes.medium) {
                Text("Manage")
            }
            Spacer(Modifier.weight(1f))
            if (phase is LaunchPhase.Preparing) {
                LaunchProgress(phase, Modifier.width(300.dp))
            } else {
                Text(
                    if (phase == LaunchPhase.Running) "Running" else timeAgo(instance.lastPlayedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (phase == LaunchPhase.Running) cs.primary else cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** Big primary action with a springy press scale — the one element that should feel "physical". */
@Composable
private fun PlayButton(phase: LaunchPhase?, onPlay: () -> Unit, onStop: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = 600f), label = "playScale")
    val running = phase == LaunchPhase.Running
    Button(
        onClick = if (running) onStop else onPlay,
        enabled = phase == null || running,
        interactionSource = interaction,
        colors = if (running) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface)
        } else ButtonDefaults.buttonColors(),
        modifier = Modifier.height(52.dp).width(160.dp).scale(scale).pointerHoverIcon(PointerIcon.Hand),
        shape = MaterialTheme.shapes.medium,
    ) {
        when (phase) {
            null -> {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Play", style = MaterialTheme.typography.titleMedium)
            }
            LaunchPhase.Running -> {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Stop", style = MaterialTheme.typography.titleMedium)
            }
            else -> {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (phase == LaunchPhase.Starting) "Starting" else "Preparing", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun LaunchProgress(phase: LaunchPhase.Preparing, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            phase.stage + (phase.fraction?.let { " · ${(it * 100).toInt()}%" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val barModifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.extraSmall)
        val fraction = phase.fraction
        if (fraction != null) {
            val animated by animateFloatAsState(fraction, label = "launchProgress")
            LinearProgressIndicator(
                progress = { animated }, modifier = barModifier, color = cs.primary,
                trackColor = cs.surfaceContainerHighest, gapSize = 0.dp, drawStopIndicator = {},
            )
        } else {
            LinearProgressIndicator(modifier = barModifier, color = cs.primary, trackColor = cs.surfaceContainerHighest)
        }
    }
}

@Composable
private fun EmptyHero(onCreate: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().height(232.dp)
            .clip(MaterialTheme.shapes.large)
            .background(cs.surface)
            .border(1.dp, cs.outline, MaterialTheme.shapes.large)
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No instances yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text("Create one to install Minecraft with Fabric, Quilt or NeoForge.", color = cs.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onCreate, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("New instance")
        }
    }
}

@Composable
private fun InstanceMiniCard(instance: Instance, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val border by animateColorAsState(if (selected) cs.primary else cs.outline, label = "miniBorder")
    Row(
        Modifier.width(220.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(cs.surface)
            .border(1.dp, border, MaterialTheme.shapes.medium)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InstanceAvatar(instance, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(instance.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitleOf(instance), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

fun subtitleOf(instance: Instance): String =
    if (instance.loader == ModLoader.VANILLA) instance.gameVersion else "${instance.gameVersion} · ${instance.loader.displayName}"

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
