package dev.lumen.launcher.ui.bedrock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.lumen.launcher.AppState
import dev.lumen.launcher.bedrock.BedrockEdition
import dev.lumen.launcher.bedrock.BedrockInstall
import dev.lumen.launcher.ui.Tag
import dev.lumen.launcher.ui.theme.LocalChrome
import dev.lumen.launcher.ui.tr

/**
 * Bedrock Edition: a Store app, so this tab finds it, starts it and opens its folders. It plays
 * offline on its own once the Store licence is on the PC; nothing here touches the licence.
 */
@Composable
fun BedrockScreen(state: AppState) {
    LaunchedEffect(Unit) { if (state.bedrockInstalls == null) state.scanBedrock() }
    val installs = state.bedrockInstalls
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Bedrock Edition", "基岩版"), style = MaterialTheme.typography.headlineMedium)
                Text(
                    tr("Start Minecraft for Windows from Lumen.", "在 Lumen 中启动 Minecraft 基岩版。"),
                    color = cs.onSurfaceVariant,
                )
            }
            TextButton(onClick = state::scanBedrock, enabled = !state.bedrockScanning) {
                if (state.bedrockScanning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(tr("Refresh", "刷新"))
            }
        }

        when {
            !state.bedrock.supported -> Notice(tr("Bedrock Edition is only available on Windows.", "基岩版仅支持 Windows。"))
            installs == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> BedrockEdition.entries.forEach { edition ->
                EditionCard(state, edition, installs[edition])
            }
        }

        Notice(
            tr(
                "Bedrock uses the Microsoft account signed in to the Xbox app and Microsoft Store. Once it's installed, " +
                    "worlds work offline without signing in.",
                "基岩版使用 Xbox 应用和微软商店中登录的微软账号。安装后，单人世界无需联网即可游玩。",
            ),
        )
    }
}

@Composable
private fun EditionCard(state: AppState, edition: BedrockEdition, install: BedrockInstall?) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.widthIn(max = 760.dp).fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(LocalChrome.current.panel)
            .border(1.dp, cs.outlineVariant, MaterialTheme.shapes.large)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tile = if (edition == BedrockEdition.Release) listOf(Color(0xFF5BBF4A), Color(0xFF2E8B3E)) else listOf(Color(0xFFF5A623), Color(0xFFD9771C))
        Box(
            Modifier.size(56.dp).clip(MaterialTheme.shapes.medium).background(Brush.linearGradient(tile)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.ViewInAr, null, Modifier.size(30.dp), tint = Color.White)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr(edition.en, edition.zh), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (install != null) {
                    Tag(install.gameVersion, accent = true)
                    Tag(tr("Installed", "已安装"))
                } else {
                    Tag(tr("Not installed", "未安装"))
                }
            }
        }
        if (install != null) {
            OutlinedButton(onClick = { state.openBedrockData(install) }, shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Data folder", "数据文件夹"))
            }
            Spacer(Modifier.width(10.dp))
            Button(onClick = { state.playBedrock(install) }, shape = MaterialTheme.shapes.medium, modifier = Modifier.height(44.dp)) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(tr("Play", "启动"))
            }
        } else {
            OutlinedButton(onClick = { state.openBedrockStore(edition) }, shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Outlined.ShoppingBag, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Get it from the Store", "前往微软商店"))
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.widthIn(max = 760.dp).fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(cs.primaryContainer.copy(alpha = 0.7f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = cs.onPrimaryContainer)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
    }
}
