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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val IRIS_PROJECT_ID = "YL57xq9U"

private val ProjectType.label: String
    get() = when (this) {
        ProjectType.MOD -> "Mods"
        ProjectType.RESOURCE_PACK -> "Resource packs"
        ProjectType.SHADER -> "Shaders"
        ProjectType.MODPACK -> "Modpacks"
    }

private val SortIndex.label: String
    get() = when (this) {
        SortIndex.RELEVANCE -> "Relevance"
        SortIndex.DOWNLOADS -> "Downloads"
        SortIndex.FOLLOWS -> "Follows"
        SortIndex.NEWEST -> "Newest"
        SortIndex.UPDATED -> "Recently updated"
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
            ScreenHeader("Mod Browser", "Search Modrinth and install straight into an instance.", Modifier.weight(1f))
            TargetPicker(instances, target, onPick = { model.targetInstanceId = it.id })
        }
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = model.query,
            onValueChange = { model.query = it },
            placeholder = { Text("Search ${model.type.label.lowercase()}…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (model.query.isNotEmpty()) IconButton(onClick = { model.query = "" }) { Icon(Icons.Rounded.Close, "Clear") }
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
                    append(if (model.loadedKey == null) "Searching…" else "${compact(model.totalHits.toLong())} results")
                    if (key.gameVersion != null) append(" for ${key.gameVersion}")
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
                    title = "Couldn't reach Modrinth",
                    body = model.error!!,
                    action = "Retry" to { scope.launch { model.search(state.modrinth, key) } },
                )
                model.results.isEmpty() && (model.loading || model.loadedKey != key) -> SkeletonList()
                model.results.isEmpty() -> CenteredMessage(
                    title = "No results",
                    body = if (model.compatibleOnly || model.categories.isNotEmpty()) {
                        "Try clearing categories or turning off “Compatible only”."
                    } else "Try a different search.",
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
                                    "End of results",
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
                        Text("Installing into", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${target.name} · ${subtitleOf(target)}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                } else {
                    Text("Create an instance first", style = MaterialTheme.typography.labelLarge)
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
                "Compatible only",
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
        target == null -> "Create an instance to install content into."
        type == ProjectType.MOD && target.loader == ModLoader.VANILLA ->
            "${target.name} is a vanilla instance, which can't load mods. Pick a Fabric, Quilt or NeoForge instance."
        type == ProjectType.SHADER && target.loader == ModLoader.VANILLA ->
            "Shaders need a mod loader with Iris. Pick a Fabric, Quilt or NeoForge instance."
        type == ProjectType.SHADER && IRIS_PROJECT_ID !in installed ->
            "Shaders need Iris. Install it from Mods first (search “Iris”)."
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
                Text("by ${hit.author}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
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
            Text("Installed", style = MaterialTheme.typography.labelLarge, color = cs.primary)
        }
        InstallState.Installing -> FilledTonalButton(onClick = {}, enabled = false, shape = MaterialTheme.shapes.small) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Installing")
        }
        InstallState.Incompatible -> Text(
            when {
                target == null -> "No instance"
                target.loader == ModLoader.VANILLA -> "Needs a loader"
                else -> "Not for ${target.gameVersion}"
            },
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
        )
        InstallState.Available -> if (retry) {
            OutlinedButton(onClick = onInstall, shape = MaterialTheme.shapes.small) { Text("Retry") }
        } else {
            Button(onClick = onInstall, shape = MaterialTheme.shapes.small, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Install")
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

private fun prettyCategory(id: String): String =
    id.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> "%.1fk".format(n / 1_000.0).replace(".0k", "k")
    else -> n.toString()
}
