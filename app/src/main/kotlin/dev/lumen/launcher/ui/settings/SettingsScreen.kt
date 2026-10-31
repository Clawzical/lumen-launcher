package dev.lumen.launcher.ui.settings

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.lumen.launcher.AppState
import dev.lumen.launcher.core.settings.Language
import dev.lumen.launcher.core.settings.BackgroundFit
import dev.lumen.launcher.core.settings.LauncherSettings
import dev.lumen.launcher.core.settings.ThemeMode
import dev.lumen.launcher.ui.BackgroundCanvas
import dev.lumen.launcher.ui.LocalBackground
import dev.lumen.launcher.ui.theme.LocalChrome
import dev.lumen.launcher.ui.tr
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun SettingsScreen(state: AppState) {
    val settings by state.settings.settings.collectAsState()
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(tr("Settings", "设置"), style = MaterialTheme.typography.headlineMedium)

        Section(tr("Appearance", "外观")) {
            SettingRow(tr("Language", "语言")) {
                Choice(
                    options = listOf(Language.EN to "English", Language.ZH to "中文"),
                    selected = settings.language,
                    onSelect = { lang -> state.updateSettings { it.copy(language = lang) } },
                )
            }
            SettingRow(tr("Theme", "主题")) {
                Choice(
                    options = listOf(ThemeMode.LIGHT to tr("Light", "浅色"), ThemeMode.DARK to tr("Dark", "深色")),
                    selected = settings.theme,
                    onSelect = { mode -> state.updateSettings { it.copy(theme = mode) } },
                )
            }
        }

        Section(tr("Background", "背景")) {
            BackgroundPicker(state, settings)
        }

        Section(tr("About", "关于")) {
            SettingRow(tr("Version", "版本")) { Text("Lumen 1.0.0", color = cs.onSurfaceVariant) }
            SettingRow(tr("Data folder", "数据文件夹")) {
                TextButton(onClick = { runCatching { Desktop.getDesktop().open(state.paths.root.toFile()) } }) {
                    Text(tr("Open", "打开"))
                }
            }
            SettingRow(tr("Source code", "源代码")) {
                TextButton(onClick = { runCatching { Desktop.getDesktop().browse(java.net.URI("https://github.com/Clawzical/lumen-launcher")) } }) {
                    Text("GitHub")
                }
            }
        }
    }
}

@Composable
private fun BackgroundPicker(state: AppState, settings: LauncherSettings) {
    val cs = MaterialTheme.colorScheme
    val picture = LocalBackground.current
    val hasImage by remember(picture) { derivedStateOf { picture?.image != null } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(192.dp, 108.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(LocalChrome.current.pageBackground)
                .border(1.dp, cs.outline, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            if (picture != null && hasImage) {
                BackgroundCanvas(
                    picture,
                    Modifier.fillMaxSize(),
                    fit = settings.backgroundFit == BackgroundFit.FIT,
                    focus = settings.backgroundFocus,
                )
            } else {
                Icon(Icons.Outlined.Image, null, tint = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (settings.backgroundPath == null) tr("Lumen default", "默认背景") else tr("Your picture", "自定义图片"),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { chooseImage()?.let(state::setBackground) }, shape = MaterialTheme.shapes.medium) {
                    Text(tr("Choose image or GIF…", "选择图片或 GIF…"))
                }
                if (settings.backgroundPath != null) {
                    TextButton(onClick = { state.updateSettings { it.copy(backgroundPath = null) } }) {
                        Text(tr("Reset", "恢复默认"))
                    }
                }
            }
            Text(
                tr("Animated GIFs and WebPs play, and pause while the game is running.", "支持动态 GIF / WebP，游戏运行时自动暂停。"),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
    if (settings.backgroundPath != null) {
        SettingRow(tr("Show", "显示方式")) {
            Choice(
                options = listOf(BackgroundFit.FILL to tr("Fill", "铺满"), BackgroundFit.FIT to tr("Whole", "完整")),
                selected = settings.backgroundFit,
                onSelect = { fit -> state.updateSettings { it.copy(backgroundFit = fit) } },
            )
        }
        if (settings.backgroundFit == BackgroundFit.FILL) {
            // Which part of a cropped picture shows: slide toward the start to keep a tall GIF's face in view.
            SettingRow(tr("Position", "位置")) {
                Slider(
                    value = settings.backgroundFocus,
                    onValueChange = { v -> state.settings.preview { it.copy(backgroundFocus = v) } },
                    onValueChangeFinished = { state.updateSettings { it } },
                    modifier = Modifier.width(260.dp),
                )
            }
        }
        SettingRow(tr("Dim", "遮罩")) {
            Slider(
                value = settings.backgroundDim,
                onValueChange = { v -> state.settings.preview { it.copy(backgroundDim = v) } },
                onValueChangeFinished = { state.updateSettings { it } },
                modifier = Modifier.width(260.dp),
            )
        }
        SettingRow(tr("Blur", "模糊")) {
            Slider(
                value = settings.backgroundBlur,
                onValueChange = { v -> state.settings.preview { it.copy(backgroundBlur = v) } },
                onValueChangeFinished = { state.updateSettings { it } },
                modifier = Modifier.width(260.dp),
            )
        }
    }
}

/** Native file picker for stills and animations (GIF, WebP); the `*.jpg;…` pattern is how Windows' dialog gets a type filter. */
private fun chooseImage(): File? {
    val dialog = FileDialog(null as Frame?, "Lumen", FileDialog.LOAD)
    dialog.file = "*.jpg;*.jpeg;*.png;*.gif;*.webp;*.bmp"
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") }
    dialog.isVisible = true // modal
    val name = dialog.file ?: return null
    return File(dialog.directory, name).takeIf { it.isFile }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.widthIn(max = 760.dp).fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(LocalChrome.current.panel)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        control()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.width((options.size * 110).dp)) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}
