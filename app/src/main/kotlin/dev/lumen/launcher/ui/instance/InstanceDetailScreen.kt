package dev.lumen.launcher.ui.instance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lumen.launcher.AppState
import dev.lumen.launcher.LaunchPhase
import dev.lumen.launcher.Screen
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.instance.ModLoader
import dev.lumen.launcher.modrinth.ModrinthVersion
import dev.lumen.launcher.modrinth.content.CompatIssue
import dev.lumen.launcher.modrinth.content.Compatibility
import dev.lumen.launcher.modrinth.content.ContentItem
import dev.lumen.launcher.modrinth.content.ContentKind
import dev.lumen.launcher.modrinth.content.FabricVersions
import dev.lumen.launcher.modrinth.content.ModEntry
import dev.lumen.launcher.modrinth.content.UpdateInfo
import dev.lumen.launcher.ui.InstanceAvatar
import dev.lumen.launcher.ui.RemoteIcon
import dev.lumen.launcher.ui.Tag
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private val ContentKind.label: String
    get() = when (this) {
        ContentKind.MOD -> "Mods"
        ContentKind.RESOURCE_PACK -> "Resource packs"
        ContentKind.SHADER -> "Shaders"
    }

/** Installed content of one instance: problems with fixes, enable/disable, updates, version swaps, removal. */
@Composable
fun InstanceDetailScreen(state: AppState, instance: Instance, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val paths = remember(instance.id) { state.instances.pathsOf(instance) }

    var items by remember(instance.id) { mutableStateOf<List<ContentItem>?>(null) }
    var loadError by remember(instance.id) { mutableStateOf<String?>(null) }
    var updates by remember(instance.id) { mutableStateOf<Map<String, UpdateInfo>>(emptyMap()) }
    var checking by remember { mutableStateOf(false) }
    var checked by remember(instance.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tab by remember(instance.id) { mutableStateOf(ContentKind.MOD) }
    var removing by remember { mutableStateOf<ContentItem?>(null) }
    var changing by remember { mutableStateOf<ContentItem?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(instance.id, reload, state.contentRevision) {
        runCatching { state.content.list(instance, paths) }
            .onSuccess { items = it; loadError = null }
            .onFailure { if (it !is CancellationException) loadError = it.message }
    }
    val all = items.orEmpty()
    val issues = remember(all, instance.loaderVersion) { state.content.issues(instance, all) }

    /** Runs a content change, shows its result, and reloads the list (and mod counts elsewhere). */
    fun act(key: String, what: String, block: suspend () -> String?) {
        scope.launch {
            busy = busy + key
            try {
                block()?.let { state.status = it }
                state.contentChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.status = "$what failed: ${e.message?.lineSequence()?.firstOrNull()?.take(110) ?: e::class.simpleName}"
                reload++
            } finally {
                busy = busy - key
            }
        }
    }

    fun checkUpdates() {
        scope.launch {
            checking = true
            try {
                updates = state.content.checkUpdates(instance, all)
                checked = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.status = "Couldn't check for updates: ${e.message?.lineSequence()?.firstOrNull()?.take(100)}"
            } finally {
                checking = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 24.dp)) {
        Header(state, instance, all, onBack)
        Spacer(Modifier.height(16.dp))

        if (issues.isNotEmpty()) {
            IssuesCard(instance, issues, busy) { issue ->
                act("issue:$issue", "Fix") { state.content.fix(instance, paths, all, issue) }
            }
            Spacer(Modifier.height(14.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ContentKind.entries.forEach { kind ->
                val count = all.count { it.kind == kind }
                FilterChip(
                    selected = tab == kind,
                    onClick = { tab = kind },
                    label = { Text("${kind.label}  $count") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = cs.primaryContainer,
                        selectedLabelColor = cs.primary,
                    ),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            val ready = updates.filter { it.value.heldBack == null && all.any { i -> i.key == it.key } }
            if (checking) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Checking Modrinth…", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            } else {
                if (checked && updates.isEmpty()) {
                    Text("Everything is up to date", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = ::checkUpdates, enabled = all.any { it.projectId != null }) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Check for updates")
                }
            }
            if (ready.isNotEmpty() && !checking) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    act("update-all", "Update") {
                        var done = 0
                        for ((key, info) in ready) {
                            val item = all.firstOrNull { it.key == key } ?: continue
                            state.content.swap(paths, item, info.version)
                            done++
                        }
                        updates = updates.filterValues { it.heldBack != null }
                        "Updated $done ${if (done == 1) "item" else "items"} in ${instance.name}"
                    }
                }, enabled = "update-all" !in busy) { Text("Update all (${ready.size})") }
            }
        }
        Spacer(Modifier.height(10.dp))

        val shown = all.filter { it.kind == tab }
        when {
            items == null && loadError == null -> Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Reading ${instance.name}…", color = cs.onSurfaceVariant)
            }
            loadError != null -> Text("Couldn't read this instance's files: $loadError", color = cs.error)
            shown.isEmpty() -> EmptyTab(tab, instance) {
                state.browser.targetInstanceId = instance.id
                state.browser.selectType(tab.projectType)
                state.screen = Screen.ModBrowser
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                items(shown, key = { it.key }) { item ->
                    ContentRow(
                        state = state,
                        item = item,
                        update = updates[item.key],
                        busy = item.key in busy,
                        onToggle = { on ->
                            act(item.key, if (on) "Enable" else "Disable") {
                                state.content.setEnabled(paths, item, on)
                                null
                            }
                        },
                        onUpdate = { info ->
                            act(item.key, "Update") {
                                state.content.swap(paths, item, info.version)
                                updates = updates - item.key
                                "Updated ${item.title} to ${info.version.versionNumber}"
                            }
                        },
                        onChangeVersion = { changing = item },
                        onRemove = { removing = item },
                    )
                }
            }
        }
    }

    removing?.let { item ->
        RemoveDialog(item, all, onDismiss = { removing = null }) {
            removing = null
            act(item.key, "Remove") {
                state.content.remove(paths, item)
                updates = updates - item.key
                "Removed ${item.title}"
            }
        }
    }
    changing?.let { item ->
        ChangeVersionDialog(state, instance, item, all, onDismiss = { changing = null }) { version ->
            changing = null
            act(item.key, "Change version") {
                state.content.swap(paths, item, version)
                updates = updates - item.key
                "Switched ${item.title} to ${version.versionNumber}"
            }
        }
    }
}

@Composable
private fun Header(state: AppState, instance: Instance, items: List<ContentItem>, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val phase = state.launchPhases[instance.id]
    val mods = items.count { it.kind == ContentKind.MOD && it.enabled }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to instances") }
        Spacer(Modifier.width(4.dp))
        InstanceAvatar(instance, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(instance.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(instance.gameVersion).append(" · ").append(instance.loader.displayName)
                    instance.loaderVersion?.let { append(" ").append(it) }
                    if (instance.loader != ModLoader.VANILLA) append(" · ").append(if (mods == 1) "1 mod enabled" else "$mods mods enabled")
                },
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { state.openFolder(instance) }, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open folder")
        }
        Spacer(Modifier.width(10.dp))
        val running = phase == LaunchPhase.Running
        Button(
            onClick = { if (running) state.stop(instance) else state.play(instance) },
            enabled = phase == null || running,
            shape = MaterialTheme.shapes.medium,
        ) {
            when {
                phase == null -> {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play")
                }
                running -> {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
                else -> {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Starting")
                }
            }
        }
    }
}

@Composable
private fun IssuesCard(instance: Instance, issues: List<CompatIssue>, busy: Set<String>, onFix: (CompatIssue) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(cs.error.copy(alpha = 0.08f))
            .border(1.dp, cs.error.copy(alpha = 0.35f), MaterialTheme.shapes.large)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp), tint = cs.error)
            Spacer(Modifier.width(8.dp))
            Text(
                "${if (issues.size == 1) "1 problem" else "${issues.size} problems"} will stop ${instance.name} from starting",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        issues.forEach { issue ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(issue.describe(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                if ("issue:$issue" in busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    FilledTonalButton(onClick = { onFix(issue) }, shape = MaterialTheme.shapes.small) { Text(issue.fixLabel()) }
                }
            }
        }
    }
}

private fun CompatIssue.describe(): String = when (this) {
    is CompatIssue.WrongVersion -> "$modName needs $depName ${FabricVersions.describe(alternatives)}, but $installed is installed."
    is CompatIssue.Missing -> "$modName needs $depId ${FabricVersions.describe(alternatives)}, which isn't installed."
    is CompatIssue.WrongGame -> "$modName is made for Minecraft ${FabricVersions.describe(alternatives)}, not $gameVersion."
    is CompatIssue.Breaks -> "$modName doesn't work with $otherName ${FabricVersions.describe(alternatives)}."
}

private fun CompatIssue.fixLabel(): String = when (this) {
    is CompatIssue.WrongVersion -> "Switch $depName to ${FabricVersions.describe(alternatives)}"
    is CompatIssue.Missing -> "Install $depId"
    is CompatIssue.WrongGame, is CompatIssue.Breaks -> "Disable $modName"
}

@Composable
private fun ContentRow(
    state: AppState,
    item: ContentItem,
    update: UpdateInfo?,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onUpdate: (UpdateInfo) -> Unit,
    onChangeVersion: () -> Unit,
    onRemove: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(cs.surface)
            .border(1.dp, cs.outline, MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.alpha(if (item.enabled) 1f else 0.45f)) { RemoteIcon(state.http, item.iconUrl, item.title, 40.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(if (item.enabled) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                item.versionNumber?.let { Tag(it) }
                if (item.projectId == null) Tag("Not from Modrinth")
                if (!item.enabled) Tag("Disabled")
            }
            Text(item.currentName, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            update?.let { u ->
                if (u.heldBack == null) {
                    Text("Update available: ${u.version.versionNumber}", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                } else {
                    Text("Newer ${u.version.versionNumber} held back: ${u.heldBack}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        } else if (update != null && update.heldBack == null) {
            FilledTonalButton(onClick = { onUpdate(update) }, shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Update") }
            Spacer(Modifier.width(6.dp))
        }
        Switch(checked = item.enabled, onCheckedChange = onToggle, enabled = !busy, modifier = Modifier.scale(0.75f))
        Box {
            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (item.projectId != null) {
                    DropdownMenuItem(
                        text = { Text("Change version…") },
                        leadingIcon = { Icon(Icons.Rounded.History, null) },
                        onClick = { menu = false; onChangeVersion() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Remove", color = cs.error) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = cs.error) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun EmptyTab(kind: ContentKind, instance: Instance, onBrowse: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No ${kind.label.lowercase()} in ${instance.name} yet", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        val note = if (kind == ContentKind.MOD && instance.loader == ModLoader.VANILLA) "Vanilla instances can't load mods." else "Find some in the Mod Browser."
        Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        if (!(kind == ContentKind.MOD && instance.loader == ModLoader.VANILLA)) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onBrowse) { Text("Browse ${kind.label.lowercase()}") }
        }
    }
}

@Composable
private fun RemoveDialog(item: ContentItem, all: List<ContentItem>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val entries = all.filter { it.kind == ContentKind.MOD && it.enabled && it.meta != null }.map { ModEntry(it.key, it.title, it.meta!!) }
    val dependents = Compatibility.requirementsOn(item.key, entries).map { it.first.displayName }.distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${item.title}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This deletes ${item.currentName} from the instance.")
                if (dependents.isNotEmpty()) {
                    Text(
                        "${dependents.joinToString()} ${if (dependents.size == 1) "needs" else "need"} this mod and won't start without it.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangeVersionDialog(
    state: AppState,
    instance: Instance,
    item: ContentItem,
    all: List<ContentItem>,
    onDismiss: () -> Unit,
    onConfirm: (ModrinthVersion) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var versions by remember { mutableStateOf<List<ModrinthVersion>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf(item.versionId) }
    LaunchedEffect(item.key) {
        runCatching { state.content.candidateVersions(instance, item) }
            .onSuccess { versions = it }
            .onFailure { error = it.message }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${item.title} versions") },
        text = {
            Column(Modifier.widthIn(min = 420.dp)) {
                Text(
                    "Showing versions for ${instance.loader.displayName} ${instance.gameVersion}.",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    error != null -> Text("Couldn't load versions: $error", color = cs.error)
                    versions == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    versions!!.isEmpty() -> Text("Modrinth has no other versions for this instance.", color = cs.onSurfaceVariant)
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(versions!!, key = { it.id }) { v ->
                            val blocker = state.content.blockingRequirement(item, v, all, instance.gameVersion)
                            Row(
                                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { chosen = v.id }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = chosen == v.id, onClick = { chosen = v.id })
                                Column(Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(v.versionNumber, style = MaterialTheme.typography.bodyMedium)
                                        if (v.id == item.versionId) Tag("Installed", accent = true)
                                    }
                                    Text(
                                        "${v.versionType.name.lowercase()} · ${v.datePublished.take(10)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = cs.onSurfaceVariant,
                                    )
                                    blocker?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.error) }
                                    if (v.versionType != dev.lumen.launcher.modrinth.VersionType.RELEASE) {
                                        Text("Pre-release: may be unstable", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val pick = versions?.firstOrNull { it.id == chosen }
            Button(onClick = { pick?.let(onConfirm) }, enabled = pick != null && pick.id != item.versionId) { Text("Switch") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
