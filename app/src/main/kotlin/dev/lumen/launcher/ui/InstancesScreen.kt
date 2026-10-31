package dev.lumen.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Grass
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lumen.launcher.AppState
import dev.lumen.launcher.InstanceTemplate
import dev.lumen.launcher.LaunchPhase
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.instance.ModLoader
import dev.lumen.launcher.core.jvm.JvmOptimizer
import dev.lumen.launcher.defaultTemplates

/** Fixed so a two-line name in one tile doesn't make its row ragged. */
private val TileHeight = 140.dp

/** What the create dialog is pre-filled with: blank, or one of [defaultTemplates]. */
private data class CreateRequest(val template: InstanceTemplate?)

@Composable
fun InstancesScreen(state: AppState, instances: List<Instance>) {
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf<CreateRequest?>(null) }
    var renaming by remember { mutableStateOf<Instance?>(null) }
    var deleting by remember { mutableStateOf<Instance?>(null) }

    val filtered = remember(instances, query) {
        instances.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.gameVersion.startsWith(query) }
    }
    val selectedId = state.selected(instances)?.id
    val searching = query.isNotBlank()

    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenHeader(
                tr("Instances", "实例"),
                tr(
                    "${instances.size} isolated ${if (instances.size == 1) "instance" else "instances"}",
                    "${instances.size} 个独立实例",
                ),
                Modifier.weight(1f),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(tr("Search", "搜索")) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.width(240.dp),
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = { creating = CreateRequest(null) }, shape = MaterialTheme.shapes.medium, modifier = Modifier.height(52.dp)) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(6.dp))
                Text(tr("New instance", "新建实例"))
            }
        }
        Spacer(Modifier.height(24.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 240.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(filtered, key = { it.id }) { inst ->
                InstanceTile(
                    state = state,
                    instance = inst,
                    selected = inst.id == selectedId,
                    onSelect = { state.manage(inst) }, // clicking a tile opens its content
                    onPlay = { state.play(inst) },
                    onRename = { renaming = inst },
                    onOpenFolder = { state.openFolder(inst) },
                    onDelete = { deleting = inst },
                    modifier = Modifier.animateItem(),
                )
            }

            if (searching) {
                if (filtered.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(tr("No instances match “$query”.", "没有与“$query”匹配的实例。"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                item(key = "new-tile") {
                    NewInstanceTile(onClick = { creating = CreateRequest(null) }, modifier = Modifier.animateItem())
                }
                item(key = "templates-header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = 20.dp)) {
                        Text(tr("Start from a template", "从模板开始"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            tr(
                                "Creates an instance and installs the mods with their dependencies from Modrinth.",
                                "创建一个实例，并从 Modrinth 安装模组及其前置。",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(defaultTemplates, key = { "template-${it.title}" }) { t ->
                    TemplateCard(t, onClick = { creating = CreateRequest(t) })
                }
            }
        }
    }

    creating?.let { request ->
        CreateInstanceDialog(
            state = state,
            template = request.template,
            onDismiss = { creating = null },
            onCreate = { name, version, loader ->
                state.createInstance(name, version, loader, request.template?.mods.orEmpty())
                creating = null
            },
        )
    }
    renaming?.let { inst ->
        RenameDialog(inst, onDismiss = { renaming = null }, onRename = { newName ->
            state.rename(inst, newName)
            renaming = null
        })
    }
    deleting?.let { inst ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Delete ${inst.name}?", "删除 ${inst.name}？")) },
            text = { Text(tr("This permanently removes its mods, configs and worlds.", "这将永久删除它的模组、配置文件和存档。")) },
            confirmButton = {
                Button(
                    onClick = { state.delete(inst); deleting = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(tr("Delete", "删除")) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Cancel", "取消")) } },
        )
    }
}

@Composable
private fun InstanceTile(
    state: AppState,
    instance: Instance,
    selected: Boolean,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(
        when {
            selected -> cs.primary
            hovered -> cs.onSurfaceVariant.copy(alpha = 0.5f)
            else -> cs.outline
        },
        label = "tileBorder",
    )
    val bg by animateColorAsState(if (hovered || selected) cs.surfaceVariant else cs.surface, label = "tileBg")
    val memory = rememberMemoryEstimate(state, instance)
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier
            .height(TileHeight)
            .clip(MaterialTheme.shapes.large)
            .background(bg)
            .border(1.dp, border, MaterialTheme.shapes.large)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            InstanceAvatar(instance, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // Two lines so names like "Create Above & Beyond" aren't cut mid-word at default width.
                Text(instance.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(subtitleOf(instance))
                        if (instance.loader != ModLoader.VANILLA) append(" · ").append(modCountLabel(memory.modCount))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = tr("More", "更多"), tint = cs.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Manage content", "管理内容")) },
                        leadingIcon = { Icon(Icons.Rounded.Extension, null) },
                        onClick = { menuOpen = false; onSelect() },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Rename", "重命名")) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Open folder", "打开文件夹")) },
                        leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                        onClick = { menuOpen = false; onOpenFolder() },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Delete", "删除"), color = cs.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = cs.error) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
        val phase = state.launchPhases[instance.id]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (phase) {
                    null -> timeAgo(instance.lastPlayedAt)
                    is LaunchPhase.Preparing -> phase.stage + (phase.fraction?.let { " · ${(it * 100).toInt()}%" } ?: "")
                    LaunchPhase.Starting -> tr("Starting…", "正在启动…")
                    LaunchPhase.Running -> tr("Running", "运行中")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (phase != null) cs.primary else cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = if (phase == LaunchPhase.Running) ({ state.stop(instance) }) else onPlay,
                enabled = phase == null || phase == LaunchPhase.Running,
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp).pointerHoverIcon(PointerIcon.Hand),
            ) {
                when (phase) {
                    null -> {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Play", "启动"), style = MaterialTheme.typography.labelLarge)
                    }
                    LaunchPhase.Running -> {
                        Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Stop", "停止"), style = MaterialTheme.typography.labelLarge)
                    }
                    else -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** Dashed "add" tile that sits at the end of the grid, so the create action lives where instances do. */
@Composable
private fun NewInstanceTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val stroke by animateColorAsState(if (hovered) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.35f), label = "ghostStroke")
    val fg by animateColorAsState(if (hovered) cs.primary else cs.onSurfaceVariant, label = "ghostFg")

    Column(
        modifier
            .fillMaxWidth()
            .height(TileHeight)
            .clip(MaterialTheme.shapes.large)
            .drawBehind {
                val r = 20.dp.toPx()
                drawRoundRect(
                    color = stroke,
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Add, null, tint = fg, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(6.dp))
        Text(tr("New instance", "新建实例"), style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun TemplateCard(template: InstanceTemplate, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(if (hovered) cs.primary.copy(alpha = 0.6f) else cs.outline, label = "templateBorder")
    val icon: ImageVector = when (template.title) {
        "Performance" -> Icons.Rounded.Bolt
        "Vanilla+" -> Icons.Rounded.Spa
        else -> Icons.Rounded.Grass
    }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .clip(MaterialTheme.shapes.large)
            .background(cs.surface)
            .border(1.dp, border, MaterialTheme.shapes.large)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(MaterialTheme.shapes.small).background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = cs.primary, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(10.dp))
            Text(tr(template.title, template.titleZh), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Tag(template.loader.displayName)
        }
        Text(tr(template.description, template.descriptionZh), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        if (template.mods.isNotEmpty()) Text(
            tr("${template.mods.size} mods + dependencies", "${template.mods.size} 个模组 + 前置"),
            style = MaterialTheme.typography.labelSmall,
            color = cs.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CreateInstanceDialog(
    state: AppState,
    template: InstanceTemplate?,
    onDismiss: () -> Unit,
    onCreate: (String, String, ModLoader) -> Unit,
) {
    val manifest = state.versionManifest
    var name by remember { mutableStateOf(template?.title.orEmpty()) }
    var version by remember(manifest != null) { mutableStateOf(manifest?.latest?.release ?: "") }
    var loader by remember { mutableStateOf(template?.loader ?: ModLoader.FABRIC) }

    val versionValid = version.isNotBlank() && (manifest == null || manifest.versions.any { it.id == version })
    val loaderLocked = template != null && template.mods.isNotEmpty() // a template's mods only exist for its loader
    fun supported(l: ModLoader) = manifest?.supports(l, version) != false
    // Picking an old version (say 1.8.9) moves a free choice to Vanilla instead of failing at install time.
    LaunchedEffect(version) { if (!loaderLocked && !supported(loader)) loader = ModLoader.VANILLA }
    val expectedMods = template?.mods?.size ?: 0
    val heapPreview = remember(loader, expectedMods) { JvmOptimizer.recommendHeapMb(expectedMods, loader) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (template != null) tr("New ${template.title} instance", "新建${template.titleZh}实例")
                else tr("New instance", "新建实例"),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Name", "名称")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                VersionPicker(manifest, version, { version = it.trim() }, Modifier.fillMaxWidth())
                Text(tr("Loader", "加载器"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModLoader.entries.forEach { l ->
                        FilterChip(
                            selected = loader == l,
                            enabled = (!loaderLocked || l == template?.loader) && supported(l),
                            onClick = { loader = l },
                            label = { Text(l.displayName) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = loader == l,
                                selectedBorderColor = MaterialTheme.colorScheme.primary,
                                selectedBorderWidth = 1.dp,
                            ),
                        )
                    }
                }
                val unsupported = ModLoader.entries.filter { !supported(it) }
                if (unsupported.isNotEmpty()) {
                    val language = LocalLanguage.current
                    Text(
                        unsupported.joinToString("  ·  ") { l ->
                            tr(language, "${l.displayName} needs ${l.oldestGameVersion}+", "${l.displayName} 需要 ${l.oldestGameVersion} 及以上")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (supported(loader)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    tr(
                        "Memory: ${formatHeap(heapPreview)} to start (auto). It grows as you add mods.",
                        "内存：初始 ${formatHeap(heapPreview)}（自动），会随模组增加而提高。",
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, version, loader) }, enabled = name.isNotBlank() && versionValid && supported(loader)) {
                Text(if (expectedMods > 0) tr("Create & install", "创建并安装") else tr("Create", "创建"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "取消")) } },
    )
}

@Composable
private fun RenameDialog(instance: Instance, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(instance.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Rename instance", "重命名实例")) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text(tr("Save", "保存")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "取消")) } },
    )
}
