package dev.lumen.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lumen.launcher.core.version.GameVersion
import dev.lumen.launcher.core.version.VersionManifest

// Every release fits (~100, back to 1.0); snapshots number in the hundreds, so those are capped.
private const val MAX_WITH_SNAPSHOTS = 200

/**
 * Type-to-filter picker over Mojang's version manifest. Releases only by default; snapshots
 * are opt-in because they're rarely what people want and outnumber releases ~8:1.
 * When the manifest couldn't load (offline), it degrades to a plain text field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionPicker(
    manifest: VersionManifest?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var showSnapshots by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) } // only filter after the user types

    val matches: List<GameVersion> = remember(manifest, value, showSnapshots, filtering) {
        manifest?.versions.orEmpty()
            .asSequence()
            .filter { showSnapshots || it.isRelease }
            .filter { !filtering || it.id.contains(value.trim(), ignoreCase = true) }
            .take(if (showSnapshots) MAX_WITH_SNAPSHOTS else Int.MAX_VALUE)
            .toList()
    }
    val isKnown = manifest == null || manifest.versions.any { it.id == value }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ExposedDropdownMenuBox(expanded = expanded && matches.isNotEmpty(), onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    onValueChange(it)
                    filtering = true
                    expanded = true
                },
                label = { Text(tr("Minecraft version", "Minecraft 版本")) },
                singleLine = true,
                isError = !isKnown,
                supportingText = {
                    Text(
                        when {
                            manifest == null -> tr("Couldn't reach Mojang — type a version manually", "无法连接到 Mojang，请手动输入版本号")
                            !isKnown -> tr("Not a known Minecraft version", "不是已知的 Minecraft 版本")
                            value == manifest.latest.release -> tr("Latest release", "最新正式版")
                            else -> manifest.versions.firstOrNull { it.id == value }?.let {
                                tr("Released ${it.releaseDate}", "发布于 ${it.releaseDate}")
                            } ?: ""
                        },
                    )
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded && matches.isNotEmpty(),
                onDismissRequest = { expanded = false; filtering = false },
                modifier = Modifier.heightIn(max = 280.dp),
            ) {
                matches.forEach { v ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(v.id, Modifier.weight(1f))
                                if (v.id == manifest?.latest?.release) Tag(tr("latest", "最新"), accent = true)
                                else if (!v.isRelease) Tag(
                                    when (v.type) {
                                        "snapshot" -> tr("snapshot", "快照")
                                        "old_beta" -> tr("old beta", "远古 Beta")
                                        "old_alpha" -> tr("old alpha", "远古 Alpha")
                                        else -> v.type.replace('_', ' ')
                                    },
                                )
                            }
                        },
                        onClick = {
                            onValueChange(v.id)
                            expanded = false
                            filtering = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
        if (manifest != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showSnapshots, onCheckedChange = { showSnapshots = it })
                Text(tr("Show snapshots", "显示快照版"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
