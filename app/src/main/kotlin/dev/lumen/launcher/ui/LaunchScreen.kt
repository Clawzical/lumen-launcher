package dev.lumen.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lumen.launcher.AppState
import dev.lumen.launcher.LaunchPhase
import dev.lumen.launcher.Screen
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.instance.ModLoader
import dev.lumen.launcher.core.settings.AccountMode
import dev.lumen.launcher.core.settings.LauncherSettings
import dev.lumen.launcher.ui.account.PlayerHead
import dev.lumen.launcher.ui.theme.LocalChrome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

private val PanelWidth = 280.dp

/**
 * The home tab: a frosted account panel on the left (Microsoft or offline, the big Launch button,
 * version and instance-settings shortcuts) and the background picture on the right.
 */
@Composable
fun LaunchScreen(state: AppState, instances: List<Instance>) {
    val settings by state.settings.settings.collectAsState()
    val selected = state.selected(instances)
    val downloads by state.downloads.active.collectAsState()

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(PanelWidth).fillMaxHeight().background(LocalChrome.current.panel).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AccountModeTabs(settings.accountMode, onChange = { mode -> state.updateSettings { it.copy(accountMode = mode) } })
            Spacer(Modifier.weight(1f))
            when (settings.accountMode) {
                AccountMode.MICROSOFT -> MicrosoftAccount(state)
                AccountMode.OFFLINE -> OfflineAccount(state, settings)
            }
            Spacer(Modifier.weight(1f))
            LaunchButton(
                instance = selected,
                phase = selected?.let { state.launchPhases[it.id] },
                onPlay = { selected?.let(state::play) },
                onStop = { selected?.let(state::stop) },
                onCreate = { state.screen = Screen.Instances },
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VersionSelectButton(state, instances, selected, Modifier.weight(1f))
                OutlinedButton(
                    onClick = { selected?.let(state::manage) },
                    enabled = selected != null,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).height(40.dp),
                ) { Text(tr("Manage", "版本设置"), maxLines = 1) }
            }
        }

        Column(
            Modifier.weight(1f).fillMaxHeight().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // With their own picture the user wants to see it; the Launch button already names the instance.
            if (selected != null && settings.backgroundPath == null) InstanceSummary(state, selected)
            Spacer(Modifier.weight(1f))
            if (downloads.isNotEmpty()) {
                DownloadsPanel(downloads.values.toList(), Modifier.widthIn(max = 520.dp).align(Alignment.End))
            }
        }
    }
}

@Composable
private fun AccountModeTabs(mode: AccountMode, onChange: (AccountMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ModeTab(tr("Microsoft", "正版"), Icons.Outlined.VerifiedUser, mode == AccountMode.MICROSOFT) { onChange(AccountMode.MICROSOFT) }
        ModeTab(tr("Offline", "离线"), Icons.Outlined.Link, mode == AccountMode.OFFLINE) { onChange(AccountMode.OFFLINE) }
    }
}

@Composable
private fun ModeTab(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            selected -> cs.primary
            hovered -> cs.primaryContainer
            else -> Color.Transparent
        },
        tween(160),
        label = "modeBg",
    )
    val fg by animateColorAsState(if (selected) cs.onPrimary else cs.primary, tween(160), label = "modeFg")
    Row(
        Modifier.height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = fg)
        Spacer(Modifier.width(5.dp))
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun MicrosoftAccount(state: AppState) {
    val accounts by state.accounts.state.collectAsState()
    val active = accounts.active
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayerHead(state.http, active?.skinUrl, 72.dp)
        if (active == null) {
            Text(tr("No Microsoft account", "尚未登录正版账号"), style = MaterialTheme.typography.titleSmall)
            Button(onClick = { state.signInOpen = true }, shape = MaterialTheme.shapes.medium) {
                Text(tr("Sign in with Microsoft", "登录微软账号"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { browse("https://www.minecraft.net/store/minecraft-java-bedrock-edition-pc") }) {
                    Text(tr("Buy Minecraft", "购买正版"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
                TextButton(onClick = { browse("https://www.minecraft.net") }) {
                    Text(tr("Official site", "前往官网"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
        } else {
            Text(active.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr("Microsoft account", "微软正版账号"), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Switch account", "切换账号"))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    accounts.accounts.forEach { acc ->
                        DropdownMenuItem(
                            leadingIcon = { PlayerHead(state.http, acc.skinUrl, 24.dp) },
                            text = { Text(acc.name) },
                            trailingIcon = { if (acc.id == active.id) Icon(Icons.Rounded.Check, null, tint = cs.primary) },
                            onClick = {
                                menuOpen = false
                                scope.launch { state.accounts.setActive(acc.id) }
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Rounded.Add, null) },
                        text = { Text(tr("Add another account", "添加账号")) },
                        onClick = { menuOpen = false; state.signInOpen = true },
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
                        text = { Text(tr("Sign out ${active.name}", "退出 ${active.name}")) },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                state.accounts.signOut(active.id)
                                state.status = state.t("Signed out ${active.name}", "已退出 ${active.name}")
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineAccount(state: AppState, settings: LauncherSettings) {
    var name by remember(settings.offlineName) { mutableStateOf(settings.offlineName) }
    val valid = LauncherSettings.isValidName(name)
    // Saved shortly after typing stops, and only when vanilla would accept the name.
    LaunchedEffect(name) {
        delay(400)
        if (valid && name != settings.offlineName) state.updateSettings { it.copy(offlineName = name) }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayerHead(state.http, null, 72.dp)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.filterNot(Char::isWhitespace).take(16) },
            label = { Text(tr("Player name", "玩家名")) },
            singleLine = true,
            isError = !valid,
            supportingText = {
                Text(
                    if (valid) tr("Singleplayer and offline-mode servers", "可用于单人游戏和离线服务器")
                    else tr("3–16 letters, digits or _", "3–16 位字母、数字或下划线"),
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The big outlined button at the bottom of the panel; it carries its own progress while preparing. */
@Composable
private fun LaunchButton(instance: Instance?, phase: LaunchPhase?, onPlay: () -> Unit, onStop: () -> Unit, onCreate: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 600f), label = "launchScale")
    val running = phase == LaunchPhase.Running
    val busy = phase is LaunchPhase.Preparing || phase == LaunchPhase.Starting
    val accent = if (running) cs.error else cs.primary

    Surface(
        onClick = when {
            instance == null -> onCreate
            running -> onStop
            else -> onPlay
        },
        enabled = !busy,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.5.dp, accent),
        modifier = Modifier.fillMaxWidth().height(70.dp).scale(scale).pointerHoverIcon(if (busy) PointerIcon.Default else PointerIcon.Hand),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                instance == null -> {
                    Text(tr("Create an instance", "新建实例"), style = MaterialTheme.typography.titleMedium, color = accent)
                    Text(tr("No game versions yet", "还没有游戏版本"), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                phase is LaunchPhase.Preparing -> LaunchProgress(phase, Modifier.fillMaxWidth())
                phase == LaunchPhase.Starting -> {
                    Text(tr("Starting…", "正在启动…"), style = MaterialTheme.typography.titleMedium, color = accent)
                    Text(instance.name, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
                running -> {
                    Text(tr("Stop game", "结束游戏"), style = MaterialTheme.typography.titleMedium, color = accent)
                    Text(tr("${instance.name} is running", "${instance.name} 运行中"), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
                else -> {
                    Text(tr("Launch game", "启动游戏"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = accent)
                    Text(
                        "${instance.name} · ${subtitleOf(instance)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Quick switch between instances without leaving the tab, plus a way into the full list. */
@Composable
private fun VersionSelectButton(state: AppState, instances: List<Instance>, selected: Instance?, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { if (instances.isEmpty()) state.screen = Screen.Instances else open = true },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) { Text(tr("Versions", "版本选择"), maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            instances.forEach { inst ->
                DropdownMenuItem(
                    leadingIcon = { InstanceAvatar(inst, 28.dp) },
                    text = {
                        Column {
                            Text(inst.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            Text(subtitleOf(inst), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailingIcon = { if (inst.id == selected?.id) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = {
                        open = false
                        state.selectedInstanceId = inst.id
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(tr("Manage instances…", "管理实例…")) },
                onClick = {
                    open = false
                    state.screen = Screen.Instances
                },
            )
        }
    }
}

@Composable
private fun InstanceSummary(state: AppState, instance: Instance) {
    val cs = MaterialTheme.colorScheme
    val memory = rememberMemoryEstimate(state, instance)
    Row(
        Modifier.widthIn(max = 520.dp)
            .clip(MaterialTheme.shapes.large)
            .background(LocalChrome.current.panel)
            .border(1.dp, cs.outlineVariant, MaterialTheme.shapes.large)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InstanceAvatar(instance, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(instance.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(instance.gameVersion, accent = true)
                Tag(instance.loader.displayName)
                if (instance.loader != ModLoader.VANILLA) Tag(modCountLabel(memory.modCount))
                Tag(tr("RAM ${memory.label}", "内存 ${memory.label}"))
            }
            Text(
                timeAgo(instance.lastPlayedAt),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
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

fun subtitleOf(instance: Instance): String =
    if (instance.loader == ModLoader.VANILLA) instance.gameVersion else "${instance.gameVersion} · ${instance.loader.displayName}"

private fun browse(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}
