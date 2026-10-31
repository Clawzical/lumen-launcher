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
import dev.lumen.launcher.ui.tr
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private val ContentKind.label: String
    @Composable get() = when (this) {
        ContentKind.MOD -> tr("Mods", "模组")
        ContentKind.RESOURCE_PACK -> tr("Resource packs", "资源包")
        ContentKind.SHADER -> tr("Shaders", "光影包")
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
                val reason = e.message?.lineSequence()?.firstOrNull()?.take(110) ?: e::class.simpleName
                state.status = state.t("$what failed: $reason", "${what}失败：$reason")
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
                val reason = e.message?.lineSequence()?.firstOrNull()?.take(100)
                state.status = state.t("Couldn't check for updates: $reason", "检查更新失败：$reason")
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
                act("issue:$issue", state.t("Fix", "修复")) { state.content.fix(instance, paths, all, issue) }
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
                Text(tr("Checking Modrinth…", "正在检查 Modrinth…"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            } else {
                if (checked && updates.isEmpty()) {
                    Text(tr("Everything is up to date", "全部已是最新"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = ::checkUpdates, enabled = all.any { it.projectId != null }) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Check for updates", "检查更新"))
                }
            }
            if (ready.isNotEmpty() && !checking) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    act("update-all", state.t("Update", "更新")) {
                        var done = 0
                        for ((key, info) in ready) {
                            val item = all.firstOrNull { it.key == key } ?: continue
                            state.content.swap(paths, item, info.version)
                            done++
                        }
                        updates = updates.filterValues { it.heldBack != null }
                        state.t(
                            "Updated $done ${if (done == 1) "item" else "items"} in ${instance.name}",
                            "已更新 ${instance.name} 中的 $done 项内容",
                        )
                    }
                }, enabled = "update-all" !in busy) { Text(tr("Update all (${ready.size})", "全部更新（${ready.size}）")) }
            }
        }
        Spacer(Modifier.height(10.dp))

        val shown = all.filter { it.kind == tab }
        when {
            items == null && loadError == null -> Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(tr("Reading ${instance.name}…", "正在读取 ${instance.name}…"), color = cs.onSurfaceVariant)
            }
            loadError != null -> Text(tr("Couldn't read this instance's files: $loadError", "无法读取该实例的文件：$loadError"), color = cs.error)
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
                            act(item.key, if (on) state.t("Enable", "启用") else state.t("Disable", "禁用")) {
                                state.content.setEnabled(paths, item, on)
                                null
                            }
                        },
                        onUpdate = { info ->
                            act(item.key, state.t("Update", "更新")) {
                                state.content.swap(paths, item, info.version)
                                updates = updates - item.key
                                state.t(
                                    "Updated ${item.title} to ${info.version.versionNumber}",
                                    "已将 ${item.title} 更新到 ${info.version.versionNumber}",
                                )
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
            act(item.key, state.t("Remove", "移除")) {
                state.content.remove(paths, item)
                updates = updates - item.key
                state.t("Removed ${item.title}", "已移除 ${item.title}")
            }
        }
    }
    changing?.let { item ->
        ChangeVersionDialog(state, instance, item, all, onDismiss = { changing = null }) { version ->
            changing = null
            act(item.key, state.t("Change version", "切换版本")) {
                state.content.swap(paths, item, version)
                updates = updates - item.key
                state.t("Switched ${item.title} to ${version.versionNumber}", "已将 ${item.title} 切换到 ${version.versionNumber}")
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
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back to instances", "返回实例列表")) }
        Spacer(Modifier.width(4.dp))
        InstanceAvatar(instance, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(instance.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(instance.gameVersion).append(" · ").append(instance.loader.displayName)
                    instance.loaderVersion?.let { append(" ").append(it) }
                    if (instance.loader != ModLoader.VANILLA) append(" · ").append(tr(if (mods == 1) "1 mod enabled" else "$mods mods enabled", "已启用 $mods 个模组"))
                },
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { state.openFolder(instance) }, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(tr("Open folder", "打开文件夹"))
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
                    Text(tr("Play", "启动"))
                }
                running -> {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Stop", "停止"))
                }
                else -> {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Starting", "启动中"))
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
                tr(
                    "${if (issues.size == 1) "1 problem" else "${issues.size} problems"} will stop ${instance.name} from starting",
                    "${issues.size} 个问题会导致 ${instance.name} 无法启动",
                ),
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

/** [FabricVersions.describe], with the "any version" / "or" wording localized. */
@Composable
private fun describeVersions(alternatives: List<String>): String =
    if (alternatives.isEmpty() || alternatives == listOf("*")) tr("any version", "任意版本")
    else alternatives.joinToString(tr(" or ", " 或 "))

@Composable
private fun CompatIssue.describe(): String = when (this) {
    is CompatIssue.WrongVersion -> describeVersions(alternatives).let { v ->
        tr("$modName needs $depName $v, but $installed is installed.", "$modName 需要 $depName $v，但当前安装的是 $installed。")
    }
    is CompatIssue.Missing -> describeVersions(alternatives).let { v ->
        tr("$modName needs $depId $v, which isn't installed.", "$modName 需要前置 $depId $v，但尚未安装。")
    }
    is CompatIssue.WrongGame -> describeVersions(alternatives).let { v ->
        tr("$modName is made for Minecraft $v, not $gameVersion.", "$modName 适用于 Minecraft $v，而不是 $gameVersion。")
    }
    is CompatIssue.Breaks -> describeVersions(alternatives).let { v ->
        tr("$modName doesn't work with $otherName $v.", "$modName 与 $otherName $v 不兼容。")
    }
}

@Composable
private fun CompatIssue.fixLabel(): String = when (this) {
    is CompatIssue.WrongVersion -> tr("Switch $depName to ${describeVersions(alternatives)}", "将 $depName 切换到 ${describeVersions(alternatives)}")
    is CompatIssue.Missing -> tr("Install $depId", "安装 $depId")
    is CompatIssue.WrongGame, is CompatIssue.Breaks -> tr("Disable $modName", "禁用 $modName")
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
                if (item.projectId == null) Tag(tr("Not from Modrinth", "非 Modrinth 来源"))
                if (!item.enabled) Tag(tr("Disabled", "已禁用"))
            }
            Text(item.currentName, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            update?.let { u ->
                if (u.heldBack == null) {
                    Text(tr("Update available: ${u.version.versionNumber}", "有可用更新：${u.version.versionNumber}"), style = MaterialTheme.typography.labelSmall, color = cs.primary)
                } else {
                    Text(tr("Newer ${u.version.versionNumber} held back: ${u.heldBack}", "暂不更新到 ${u.version.versionNumber}：${u.heldBack}"), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        } else if (update != null && update.heldBack == null) {
            FilledTonalButton(onClick = { onUpdate(update) }, shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(tr("Update", "更新")) }
            Spacer(Modifier.width(6.dp))
        }
        Switch(checked = item.enabled, onCheckedChange = onToggle, enabled = !busy, modifier = Modifier.scale(0.75f))
        Box {
            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Rounded.MoreVert, tr("More", "更多")) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (item.projectId != null) {
                    DropdownMenuItem(
                        text = { Text(tr("Change version…", "切换版本…")) },
                        leadingIcon = { Icon(Icons.Rounded.History, null) },
                        onClick = { menu = false; onChangeVersion() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(tr("Remove", "移除"), color = cs.error) },
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
        Text(tr("No ${kind.label.lowercase()} in ${instance.name} yet", "${instance.name} 中还没有${kind.label}"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        val note = if (kind == ContentKind.MOD && instance.loader == ModLoader.VANILLA) {
            tr("Vanilla instances can't load mods.", "原版实例无法加载模组。")
        } else tr("Find some in the Mod Browser.", "可以去模组下载页面找一些。")
        Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        if (!(kind == ContentKind.MOD && instance.loader == ModLoader.VANILLA)) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onBrowse) { Text(tr("Browse ${kind.label.lowercase()}", "浏览${kind.label}")) }
        }
    }
}

@Composable
private fun RemoveDialog(item: ContentItem, all: List<ContentItem>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val entries = all.filter { it.kind == ContentKind.MOD && it.enabled && it.meta != null }.map { ModEntry(it.key, it.title, it.meta!!) }
    val dependents = Compatibility.requirementsOn(item.key, entries).map { it.first.displayName }.distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Remove ${item.title}?", "移除 ${item.title}？")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("This deletes ${item.currentName} from the instance.", "这将从实例中删除 ${item.currentName}。"))
                if (dependents.isNotEmpty()) {
                    Text(
                        tr(
                            "${dependents.joinToString()} ${if (dependents.size == 1) "needs" else "need"} this mod and won't start without it.",
                            "${dependents.joinToString("、")} 以此模组为前置，移除后将无法启动。",
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(tr("Remove", "移除")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "取消")) } },
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
        title = { Text(tr("${item.title} versions", "${item.title} 的版本")) },
        text = {
            Column(Modifier.widthIn(min = 420.dp)) {
                Text(
                    tr(
                        "Showing versions for ${instance.loader.displayName} ${instance.gameVersion}.",
                        "正在显示适用于 ${instance.loader.displayName} ${instance.gameVersion} 的版本。",
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    error != null -> Text(tr("Couldn't load versions: $error", "无法加载版本列表：$error"), color = cs.error)
                    versions == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    versions!!.isEmpty() -> Text(tr("Modrinth has no other versions for this instance.", "Modrinth 上没有适用于该实例的其他版本。"), color = cs.onSurfaceVariant)
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
                                        if (v.id == item.versionId) Tag(tr("Installed", "已安装"), accent = true)
                                    }
                                    val typeLabel = when (v.versionType) {
                                        dev.lumen.launcher.modrinth.VersionType.RELEASE -> tr("release", "正式版")
                                        dev.lumen.launcher.modrinth.VersionType.BETA -> tr("beta", "测试版")
                                        dev.lumen.launcher.modrinth.VersionType.ALPHA -> tr("alpha", "早期测试版")
                                    }
                                    Text(
                                        "$typeLabel · ${v.datePublished.take(10)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = cs.onSurfaceVariant,
                                    )
                                    blocker?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.error) }
                                    if (v.versionType != dev.lumen.launcher.modrinth.VersionType.RELEASE) {
                                        Text(tr("Pre-release: may be unstable", "预发布版本：可能不稳定"), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
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
            Button(onClick = { pick?.let(onConfirm) }, enabled = pick != null && pick.id != item.versionId) { Text(tr("Switch", "切换")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "取消")) } },
    )
}
