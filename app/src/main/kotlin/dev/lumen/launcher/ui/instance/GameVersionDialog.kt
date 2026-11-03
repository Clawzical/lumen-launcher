package dev.lumen.launcher.ui.instance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.lumen.launcher.AppState
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.modrinth.content.ContentItem
import dev.lumen.launcher.modrinth.content.ContentKind
import dev.lumen.launcher.modrinth.content.MigrationPlan
import dev.lumen.launcher.modrinth.content.MigrationResult
import dev.lumen.launcher.modrinth.content.MigrationStep
import dev.lumen.launcher.ui.VersionPicker
import dev.lumen.launcher.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.awt.Desktop

private sealed interface Stage {
    data object Choose : Stage
    data object Checking : Stage
    data class Preview(val plan: MigrationPlan) : Stage
    data class Applying(val plan: MigrationPlan, val progress: String) : Stage
    data class Done(val plan: MigrationPlan, val result: MigrationResult) : Stage
}

/**
 * Upgrade or downgrade an instance's Minecraft version. Shows exactly what will happen to each
 * mod before anything changes; worlds, configs and settings are never touched (worlds can be
 * copied first, which is on by default when going back to an older version).
 */
@Composable
fun GameVersionDialog(state: AppState, instance: Instance, items: List<ContentItem>, onDismiss: () -> Unit) {
    val manifest = state.versionManifest
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<Stage>(Stage.Choose) }
    var target by remember { mutableStateOf(instance.gameVersion) }
    var error by remember { mutableStateOf<String?>(null) }

    val known = manifest == null || manifest.versions.any { it.id == target }
    val supported = manifest?.supports(instance.loader, target) != false
    val releaseTime = { id: String -> manifest?.versions?.firstOrNull { it.id == id }?.releaseTime }
    val downgrade = releaseTime(target)?.let { t -> releaseTime(instance.gameVersion)?.let { t < it } } ?: false
    var backupWorlds by remember(downgrade) { mutableStateOf(downgrade) }
    val busy = stage is Stage.Checking || stage is Stage.Applying

    fun check() {
        error = null
        stage = Stage.Checking
        scope.launch {
            stage = try {
                Stage.Preview(state.migrator.plan(instance, items, target))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = state.t("Couldn't reach Modrinth: ", "无法连接 Modrinth：") + (e.message?.lineSequence()?.firstOrNull()?.take(100) ?: "")
                Stage.Choose
            }
        }
    }

    fun apply(plan: MigrationPlan) {
        stage = Stage.Applying(plan, state.t("Starting", "正在开始"))
        scope.launch {
            stage = try {
                val result = state.changeGameVersion(instance, plan, backupWorlds) { p -> stage = Stage.Applying(plan, p) }
                Stage.Done(plan, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = state.t("Change failed — the instance is still on ${instance.gameVersion}: ", "切换失败，实例仍为 ${instance.gameVersion}：") +
                    (e.message?.lineSequence()?.firstOrNull()?.take(100) ?: "")
                Stage.Preview(plan)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !busy, dismissOnBackPress = !busy),
        modifier = Modifier.widthIn(max = 620.dp),
        title = {
            Text(
                when (val s = stage) {
                    is Stage.Done -> tr("Now on Minecraft ${s.plan.to}", "已切换到 Minecraft ${s.plan.to}")
                    is Stage.Preview, is Stage.Applying -> tr("${instance.gameVersion} → $target", "${instance.gameVersion} → $target")
                    else -> tr("Change Minecraft version", "切换 Minecraft 版本")
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val s = stage) {
                    Stage.Choose, Stage.Checking -> ChooseStep(state, instance, target, { target = it }, known, supported, downgrade, backupWorlds) { backupWorlds = it }
                    is Stage.Preview -> PlanView(instance, s.plan, downgrade, backupWorlds) { backupWorlds = it }
                    is Stage.Applying -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(s.progress, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is Stage.Done -> ResultView(instance, s.plan, s.result)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            when (val s = stage) {
                Stage.Choose -> Button(onClick = ::check, enabled = known && supported && target != instance.gameVersion) {
                    Text(tr("Check mods", "检查模组"))
                }
                Stage.Checking -> Button(onClick = {}, enabled = false) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Checking…", "检查中…"))
                }
                is Stage.Preview -> Button(onClick = { apply(s.plan) }) { Text(tr("Change to ${s.plan.to}", "切换到 ${s.plan.to}")) }
                is Stage.Applying -> Button(onClick = {}, enabled = false) { Text(tr("Working…", "处理中…")) }
                is Stage.Done -> Button(onClick = onDismiss) { Text(tr("Done", "完成")) }
            }
        },
        dismissButton = {
            when (stage) {
                is Stage.Preview -> TextButton(onClick = { stage = Stage.Choose }) { Text(tr("Back", "返回")) }
                Stage.Choose -> TextButton(onClick = onDismiss) { Text(tr("Cancel", "取消")) }
                else -> {}
            }
        },
    )
}

@Composable
private fun ChooseStep(
    state: AppState,
    instance: Instance,
    target: String,
    onTarget: (String) -> Unit,
    known: Boolean,
    supported: Boolean,
    downgrade: Boolean,
    backupWorlds: Boolean,
    onBackupWorlds: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Text(
        tr(
            "Lumen finds each mod's build for the new version and switches to it. Mods without one are turned off, not deleted. " +
                "Worlds, configs, resource packs and settings stay as they are.",
            "Lumen 会为每个模组找到新版本对应的文件并替换。没有对应版本的模组会被关闭（不会删除）。存档、配置、资源包和设置都会保留。",
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = cs.onSurfaceVariant,
    )
    VersionPicker(state.versionManifest, target, onTarget, Modifier.fillMaxWidth())
    if (known && !supported) {
        Text(
            tr("${instance.loader.displayName} needs ${instance.loader.oldestGameVersion}+", "${instance.loader.displayName} 需要 ${instance.loader.oldestGameVersion} 及以上"),
            color = cs.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (downgrade) DowngradeWarning(backupWorlds, onBackupWorlds)
}

@Composable
private fun DowngradeWarning(backupWorlds: Boolean, onBackupWorlds: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(cs.errorContainer.copy(alpha = 0.5f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, null, Modifier.size(18.dp), tint = cs.error)
            Spacer(Modifier.width(8.dp))
            Text(
                tr("Going back to an older version", "正在降级到旧版本"),
                style = MaterialTheme.typography.labelLarge,
                color = cs.onErrorContainer,
            )
        }
        Text(
            tr(
                "Worlds saved in a newer version can break when opened in an older one.",
                "在新版本中保存过的存档用旧版本打开可能会损坏。",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onErrorContainer,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = backupWorlds, onCheckedChange = onBackupWorlds)
            Text(tr("Back up worlds first", "先备份存档"), style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
        }
    }
}

@Composable
private fun PlanView(instance: Instance, plan: MigrationPlan, downgrade: Boolean, backupWorlds: Boolean, onBackupWorlds: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val mods = plan.steps.count { it.item.kind == ContentKind.MOD }
    Text(
        tr(
            "${plan.switches.size} will switch, ${plan.turnOffs.size} will be turned off, ${plan.keeps.size} stay as they are.",
            "${plan.switches.size} 项将替换，${plan.turnOffs.size} 项将被关闭，${plan.keeps.size} 项保持不变。",
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (mods == 0) {
        Text(tr("No mods to check — only the game version changes.", "没有需要检查的模组，只会切换游戏版本。"), color = cs.onSurfaceVariant)
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(plan.turnOffs, key = { "off:" + it.item.key }) { step ->
            StepRow(Icons.Rounded.PowerSettingsNew, cs.error, step.item.title, reasonText(plan, step))
        }
        items(plan.switches, key = { "sw:" + it.item.key }) { step ->
            StepRow(
                Icons.Rounded.ArrowForward, cs.primary, step.item.title,
                "${step.item.versionNumber ?: "?"} → ${step.version.versionNumber}" +
                    if (!step.item.enabled) tr(" (stays off)", "（保持关闭）") else "",
            )
        }
        items(plan.keeps, key = { "keep:" + it.item.key }) { step ->
            StepRow(Icons.Rounded.Check, cs.onSurfaceVariant, step.item.title, keepText(step.item))
        }
    }
    if (downgrade) DowngradeWarning(backupWorlds, onBackupWorlds)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
            tr("Your current mods are copied to the instance's backups folder first.", "当前模组会先复制到实例的 backups 文件夹。"),
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultView(instance: Instance, plan: MigrationPlan, result: MigrationResult) {
    val cs = MaterialTheme.colorScheme
    Text(
        tr(
            "${result.switched} switched to their ${plan.to} versions. Worlds, configs and settings were kept.",
            "${result.switched} 项已换成 ${plan.to} 版本，存档、配置和设置均已保留。",
        ),
    )
    if (result.addedDependencies.isNotEmpty()) {
        Text(
            tr("Added what the new versions need: ", "已添加新版本所需的前置：") + result.addedDependencies.joinToString(),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
    if (result.turnedOff.isNotEmpty()) {
        Text(
            tr("Turned off (${result.turnedOff.size}) — turn them back on in the Mods tab when they update:",
                "已关闭（${result.turnedOff.size}）：等它们更新后可在模组页重新启用："),
            style = MaterialTheme.typography.labelLarge,
            color = cs.error,
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
            items(result.turnedOff, key = { it.item.key }) { step ->
                StepRow(Icons.Rounded.PowerSettingsNew, cs.error, step.item.title, reasonText(plan, step))
            }
        }
    }
    TextButton(onClick = { runCatching { Desktop.getDesktop().open(result.backup.toFile()) } }) {
        Text(tr("Open backup", "打开备份"))
    }
}

@Composable
private fun StepRow(icon: ImageVector, tint: Color, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(10.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(10.dp))
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun reasonText(plan: MigrationPlan, step: MigrationStep.TurnOff): String = when (step.reason) {
    MigrationStep.Reason.NO_VERSION -> tr("No ${plan.loader.displayName} ${plan.to} version", "没有 ${plan.loader.displayName} ${plan.to} 版本")
    MigrationStep.Reason.NOT_ON_MODRINTH -> tr("Not from Modrinth — can't look it up", "非 Modrinth 来源，无法查找")
    MigrationStep.Reason.NEEDS_TURNED_OFF_MOD -> plan.neededBy[step.item.key].let { tr("Needs $it, which is turned off", "依赖已关闭的 $it") }
}

@Composable
private fun keepText(item: ContentItem): String = when {
    item.kind != ContentKind.MOD -> tr("Pack, works as is", "资源包，可直接使用")
    !item.enabled -> tr("Already off", "已关闭")
    else -> tr("Already works", "已兼容")
}
