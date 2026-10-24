package dev.lumen.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lumen.launcher.AppState
import dev.lumen.launcher.Screen
import dev.lumen.launcher.ui.account.AccountCard
import dev.lumen.launcher.ui.account.SignInDialog
import dev.lumen.launcher.ui.browser.ModBrowserScreen
import dev.lumen.launcher.ui.instance.InstanceDetailScreen
import dev.lumen.launcher.ui.window.ResizeHandles
import dev.lumen.launcher.ui.window.TitleBar
import dev.lumen.launcher.ui.window.WindowChrome
import androidx.compose.foundation.border
import kotlinx.coroutines.delay

private val SidebarWidth = 232.dp

@Composable
fun LauncherShell(state: AppState, chrome: WindowChrome, onClose: () -> Unit) {
    val instances by state.instances.instances.collectAsState()
    // Windows 11 draws its own thin border around the rounded window; elsewhere we draw one.
    val frame = if (!chrome.hasNativeFrame && !chrome.isMaximized) {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outline)
    } else Modifier

    // Surface (not a plain background) so LocalContentColor becomes onBackground; bare Text defaults to black otherwise.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(Modifier.fillMaxSize().then(frame)) {
    Column(Modifier.fillMaxSize()) {
    TitleBar(chrome, SidebarWidth, onClose)
    Row(Modifier.weight(1f).fillMaxWidth()) {
        Sidebar(
            current = state.screen,
            onSelect = {
                state.screen = it
                state.managingInstanceId = null // the sidebar always lands on a section's top level
            },
            account = { AccountCard(state) },
        )
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))

        Box(Modifier.weight(1f).fillMaxHeight()) {
            AnimatedContent(
                targetState = state.screen,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(280)) { it / 32 })
                        .togetherWith(fadeOut(tween(120)))
                },
                label = "screen",
            ) { screen ->
                when (screen) {
                    Screen.Dashboard -> DashboardScreen(state, instances)
                    Screen.Instances -> {
                        val managed = state.managingInstanceId?.let { id -> instances.firstOrNull { it.id == id } }
                        if (managed != null) {
                            InstanceDetailScreen(state, managed, onBack = { state.managingInstanceId = null })
                        } else {
                            InstancesScreen(state, instances)
                        }
                    }
                    Screen.ModBrowser -> ModBrowserScreen(state, instances)
                    Screen.Settings -> PlaceholderScreen("Settings", "Java runtimes, memory defaults, GC profile, download threads.")
                }
            }
            StatusToast(
                message = state.status,
                onDismiss = { state.status = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
    }
    }
    ResizeHandles(chrome)
    }
    if (state.signInOpen) SignInDialog(state, onDismiss = { state.signInOpen = false })
    }
}

@Composable
private fun Sidebar(current: Screen, onSelect: (Screen) -> Unit, account: @Composable () -> Unit) {
    // The logo and name live in the title bar above, which continues this panel.
    Column(
        Modifier.width(SidebarWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Screen.entries.forEach { screen ->
            NavItem(screen, selected = screen == current, onClick = { onSelect(screen) })
        }

        Spacer(Modifier.weight(1f))
        account()
    }
}

@Composable
private fun NavItem(screen: Screen, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            selected -> cs.primaryContainer
            hovered -> cs.surfaceVariant
            else -> Color.Transparent
        },
        tween(160),
        label = "navBg",
    )
    val fg by animateColorAsState(
        when {
            selected -> cs.primary
            hovered -> cs.onSurface
            else -> cs.onSurfaceVariant
        },
        tween(160),
        label = "navFg",
    )

    Row(
        Modifier.fillMaxWidth().height(42.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(screen.icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(screen.label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatusToast(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var lastMessage by remember { mutableStateOf("") }
    if (message != null) lastMessage = message
    LaunchedEffect(message) {
        if (message != null) {
            delay(3500)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.medium,
            shadowElevation = 8.dp,
        ) {
            Text(lastMessage, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
