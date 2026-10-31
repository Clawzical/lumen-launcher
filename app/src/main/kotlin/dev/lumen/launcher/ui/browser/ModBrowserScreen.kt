package dev.lumen.launcher.ui.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lumen.launcher.AppState
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.instance.ModLoader
import dev.lumen.launcher.modrinth.ProjectType
import dev.lumen.launcher.modrinth.SearchHit
import dev.lumen.launcher.modrinth.SortIndex
import dev.lumen.launcher.ui.InstanceAvatar
import dev.lumen.launcher.ui.RemoteIcon
import dev.lumen.launcher.ui.ScreenHeader
import dev.lumen.launcher.ui.Tag
import dev.lumen.launcher.ui.subtitleOf
import dev.lumen.launcher.ui.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val IRIS_PROJECT_ID = "YL57xq9U"

private val ProjectType.label: String
    @Composable get() = when (this) {
        ProjectType.MOD -> tr("Mods", "模组")
        ProjectType.RESOURCE_PACK -> tr("Resource packs", "资源包")
        ProjectType.SHADER -> tr("Shaders", "光影包")
        ProjectType.MODPACK -> tr("Modpacks", "整合包")
    }

private val SortIndex.label: String
    @Composable get() = when (this) {
        SortIndex.RELEVANCE -> tr("Relevance", "相关性")
        SortIndex.DOWNLOADS -> tr("Downloads", "下载量")
        SortIndex.FOLLOWS -> tr("Follows", "关注数")
        SortIndex.NEWEST -> tr("Newest", "最新发布")
        SortIndex.UPDATED -> tr("Recently updated", "最近更新")
    }

@Composable
fun ModBrowserScreen(state: AppState, instances: List<Instance>) {
    val model = state.browser
    val target = instances.firstOrNull { it.id == model.targetInstanceId } ?: state.selected(instances)
    val key = model.keyFor(target)
    val scope = rememberCoroutineScope()

    // Debounced search: typing restarts this effect, so only the last keystroke's query is sent.
    LaunchedEffect(key) {
        if (model.loadedKey == key) return@LaunchedEffect
        delay(250)
        model.search(state.modrinth, key)
    }
    LaunchedEffect(target?.id, state.contentRevision) { state.refreshInstalled(target) }

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, key) { if (nearEnd) model.loadMore(state.modrinth, key) }

    var shownKey by remember { mutableStateOf(model.loadedKey) }
    LaunchedEffect(model.loadedKey) {
        if (model.loadedKey != shownKey) {
            listState.scrollToItem(0)
            shownKey = model.loadedKey
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenHeader(
                tr("Mod Browser", "模组下载"),
                tr("Search Modrinth and install straight into an instance.", "搜索 Modrinth，并直接安装到实例中。"),
                Modifier.weight(1f),
            )
            TargetPicker(instances, target, onPick = { model.targetInstanceId = it.id })
        }
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = model.query,
            onValueChange = { model.query = it },
            placeholder = { Text(tr("Search ${model.type.label.lowercase()}…", "搜索${model.type.label}…")) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (model.query.isNotEmpty()) IconButton(onClick = { model.query = "" }) { Icon(Icons.Rounded.Close, tr("Clear", "清除")) }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        FilterBar(model, target)
        Spacer(Modifier.height(8.dp))
        CategoryRow(model)
        Spacer(Modifier.height(12.dp))

        Notice(model.type, target, model.installed)

        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append(
                        if (model.loadedKey == null) tr("Searching…", "正在搜索…")
                        else tr("${compact(model.totalHits.toLong())} results", "${compact(model.totalHits.toLong())} 个结果"),
                    )
                    if (key.gameVersion != null) append(tr(" for ${key.gameVersion}", " · 适用于 ${key.gameVersion}"))
                    if (key.loader != null) append(" · ${key.loader.displayName}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (model.loading && model.results.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                model.results.isEmpty() && model.error != null -> CenteredMessage(
                    title = tr("Couldn't reach Modrinth", "无法连接到 Modrinth"),
                    body = model.error!!,
                    action = tr("Retry", "重试") to { scope.launch { model.search(state.modrinth, key) } },
                )
                model.results.isEmpty() && (model.loading || model.loadedKey != key) -> SkeletonList()
                model.results.isEmpty() -> CenteredMessage(
                    title = tr("No results", "没有结果"),
                    body = if (model.compatibleOnly || model.categories.isNotEmpty()) {
                        tr("Try clearing categories or turning off “Compatible only”.", "试试清除分类，或关闭“仅显示兼容”。")
                    } else tr("Try a different search.", "换个关键词试试。"),
                    action = null,
                )
                else -> LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    items(model.results, key = { it.projectId }) { hit ->
                        ResultCard(
                            state = state,
                            hit = hit,
                            installState = model.stateOf(hit, target),
                            target = target,
                            failure = model.failures[hit.projectId],
                            highlight = model.categories,
                            onInstall = { target?.let { state.installProject(hit, model.type, it) } },
                        )
                    }
                    item(key = "footer") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            when {
                                model.loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                !model.canLoadMore -> Text(
                                    tr("End of results", "已经到底了"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetPicker(instances: List<Instance>, target: Instance?, onPick: (Instance) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            enabled = instances.isNotEmpty(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (target != null) {
                    InstanceAvatar(target, 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(tr("Installing into", "安装到"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${target.name} · ${subtitleOf(target)}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                } else {
                    Text(tr("Create an instance first", "请先创建实例"), style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            instances.forEach { inst ->
                DropdownMenuItem(
                    leadingIcon = { InstanceAvatar(inst, 24.dp) },
                    text = {
                        Column {
                            Text(inst.name, style = MaterialTheme.typography.labelLarge)
                            Text(subtitleOf(inst), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailingIcon = { if (inst.id == target?.id) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { onPick(inst); open = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(model: ModBrowserModel, target: Instance?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ProjectType.MOD, ProjectType.RESOURCE_PACK, ProjectType.SHADER).forEach { t ->
                FilterChip(
                    selected = model.type == t,
                    onClick = { model.selectType(t) },
                    label = { Text(t.label) },
                    colors = accentChipColors(),
                    border = accentChipBorder(model.type == t),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (target != null) {
            Switch(checked = model.compatibleOnly, onCheckedChange = { model.compatibleOnly = it }, modifier = Modifier.scale(0.75f))
            Text(
                tr("Compatible only", "仅显示兼容"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
        }
        SortMenu(model.sort, onSort = { model.sort = it })
    }
}

@Composable
private fun SortMenu(current: SortIndex, onSort: (SortIndex) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(current.label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortIndex.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    trailingIcon = { if (s == current) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { onSort(s); open = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryRow(model: ModBrowserModel) {
    val options = browserCategories[model.type].orEmpty()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options, key = { it }) { id ->
            val selected = id in model.categories
            FilterChip(
                selected = selected,
                onClick = { model.toggleCategory(id) },
                label = { Text(prettyCategory(id), style = MaterialTheme.typography.labelMedium) },
                colors = accentChipColors(),
                border = accentChipBorder(selected),
            )
        }
    }
}

/** Context-specific guidance that would otherwise surface as a confusing install failure. */
@Composable
private fun Notice(type: ProjectType, target: Instance?, installed: Set<String>) {
    val text = when {
        target == null -> tr("Create an instance to install content into.", "请先创建一个实例，才能安装内容。")
        type == ProjectType.MOD && target.loader == ModLoader.VANILLA -> tr(
            "${target.name} is a vanilla instance, which can't load mods. Pick a Fabric, Quilt or NeoForge instance.",
            "${target.name} 是原版实例，无法加载模组。请选择 Fabric、Quilt 或 NeoForge 实例。",
        )
        type == ProjectType.SHADER && target.loader == ModLoader.VANILLA -> tr(
            "Shaders need a mod loader with Iris. Pick a Fabric, Quilt or NeoForge instance.",
            "光影包需要安装了 Iris 的模组加载器。请选择 Fabric、Quilt 或 NeoForge 实例。",
        )
        type == ProjectType.SHADER && IRIS_PROJECT_ID !in installed -> tr(
            "Shaders need Iris. Install it from Mods first (search “Iris”).",
            "光影包需要 Iris。请先在“模组”中安装它（搜索“Iris”）。",
        )
        else -> null
    } ?: return

    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ResultCard(
    state: AppState,
    hit: SearchHit,
    installState: InstallState,
    target: Instance?,
    failure: String?,
    highlight: Set<String>,
    onInstall: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(if (hovered) cs.onSurfaceVariant.copy(alpha = 0.4f) else cs.outline, label = "cardBorder")
    // Active filter categories first, so a card visibly explains why it matched.
    val tags = remember(hit.categories, highlight) {
        hit.categories.filter { it !in loaderCategoryIds }.sortedByDescending { it in highlight }.take(3)
    }

    Row(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(cs.surface)
            .border(1.dp, border, MaterialTheme.shapes.large)
            .hoverable(interaction)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteIcon(state.http, hit.iconUrl, hit.title, 64.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(hit.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Text(tr("by ${hit.author}", "作者 ${hit.author}"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
            }
            Text(hit.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat(Icons.Rounded.Download, compact(hit.downloads))
                Spacer(Modifier.width(4.dp))
                Stat(Icons.Rounded.Favorite, compact(hit.follows.toLong()))
                Spacer(Modifier.width(4.dp))
                tags.forEach { Tag(prettyCategory(it), accent = it in highlight) }
            }
            if (failure != null) Text(failure, style = MaterialTheme.typography.labelSmall, color = cs.error, maxLines = 2)
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.width(150.dp), contentAlignment = Alignment.CenterEnd) {
            InstallButton(installState, target, retry = failure != null, onInstall = onInstall)
        }
    }
}

@Composable
private fun InstallButton(installState: InstallState, target: Instance?, retry: Boolean, onInstall: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    when (installState) {
        InstallState.Installed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = cs.primary)
            Spacer(Modifier.width(6.dp))
            Text(tr("Installed", "已安装"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
        }
        InstallState.Installing -> FilledTonalButton(onClick = {}, enabled = false, shape = MaterialTheme.shapes.small) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(tr("Installing", "安装中"))
        }
        InstallState.Incompatible -> Text(
            when {
                target == null -> tr("No instance", "无实例")
                target.loader == ModLoader.VANILLA -> tr("Needs a loader", "需要加载器")
                else -> tr("Not for ${target.gameVersion}", "不支持 ${target.gameVersion}")
            },
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
        )
        InstallState.Available -> if (retry) {
            OutlinedButton(onClick = onInstall, shape = MaterialTheme.shapes.small) { Text(tr("Retry", "重试")) }
        } else {
            Button(onClick = onInstall, shape = MaterialTheme.shapes.small, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Install", "安装"))
            }
        }
    }
}

@Composable
private fun Stat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(3.dp))
        Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SkeletonList() {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.alpha(pulse)) {
        repeat(5) {
            Row(
                Modifier.fillMaxWidth().height(92.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(64.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(180.dp).height(14.dp).clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                    Box(Modifier.width(360.dp).height(10.dp).clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(title: String, body: String, action: Pair<String, () -> Unit>?) {
    Column(Modifier.fillMaxSize().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = action.second) { Text(action.first) }
        }
    }
}

@Composable
private fun accentChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.primary,
)

@Composable
private fun accentChipBorder(selected: Boolean) = FilterChipDefaults.filterChipBorder(
    enabled = true,
    selected = selected,
    selectedBorderColor = MaterialTheme.colorScheme.primary,
    selectedBorderWidth = 1.dp,
)

/** Chinese names for the Modrinth category ids we offer as filters; anything else falls back to the English name. */
private val categoryZh = mapOf(
    "optimization" to "优化",
    "utility" to "实用",
    "library" to "前置库",
    "technology" to "科技",
    "adventure" to "冒险",
    "magic" to "魔法",
    "worldgen" to "世界生成",
    "decoration" to "装饰",
    "storage" to "存储",
    "mobs" to "生物",
    "vanilla-like" to "原版风",
    "simplistic" to "简约",
    "realistic" to "写实",
    "semi-realistic" to "半写实",
    "themed" to "主题",
    "gui" to "界面",
    "fantasy" to "奇幻",
    "cartoon" to "卡通",
    "potato" to "低配",
    "high" to "高配",
    "medium" to "中配",
    "low" to "低配",
    "food" to "食物",
    "equipment" to "装备",
    "transportation" to "交通",
    "game-mechanics" to "游戏机制",
    "management" to "管理",
    "social" to "社交",
    "economy" to "经济",
    "minigame" to "小游戏",
    "cursed" to "整活",
    "combat" to "战斗",
)

@Composable
private fun prettyCategory(id: String): String {
    val en = id.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    return tr(en, categoryZh[id] ?: en)
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> "%.1fk".format(n / 1_000.0).replace(".0k", "k")
    else -> n.toString()
}
