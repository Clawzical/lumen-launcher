package dev.lumen.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import dev.lumen.launcher.AppState
import dev.lumen.launcher.LaunchPhase
import dev.lumen.launcher.Screen
import dev.lumen.launcher.core.settings.BackgroundFit
import dev.lumen.launcher.core.settings.LauncherSettings
import dev.lumen.launcher.ui.account.SignInDialog
import dev.lumen.launcher.ui.bedrock.BedrockScreen
import dev.lumen.launcher.ui.browser.ModBrowserScreen
import dev.lumen.launcher.ui.instance.InstanceDetailScreen
import dev.lumen.launcher.ui.settings.SettingsScreen
import dev.lumen.launcher.ui.theme.LocalChrome
import dev.lumen.launcher.ui.window.ResizeHandles
import dev.lumen.launcher.ui.window.TitleBar
import dev.lumen.launcher.ui.window.WindowChrome
import kotlinx.coroutines.delay

@Composable
fun LauncherShell(state: AppState, chrome: WindowChrome, onClose: () -> Unit) {
    val instances by state.instances.instances.collectAsState()
    val settings by state.settings.settings.collectAsState()
    val cs = MaterialTheme.colorScheme
    // Windows 11 draws its own thin border around the rounded window; elsewhere we draw one.
    val frame = if (!chrome.hasNativeFrame && !chrome.isMaximized) Modifier.border(1.dp, cs.outline) else Modifier

    // Surface (not a plain background) so LocalContentColor becomes onBackground; bare Text defaults to black otherwise.
    Surface(Modifier.fillMaxSize(), color = cs.background, contentColor = cs.onBackground) {
        Box(Modifier.fillMaxSize().then(frame)) {
            Column(Modifier.fillMaxSize()) {
                TitleBar(
                    chrome,
                    current = state.screen,
                    onSelect = {
                        state.screen = it
                        state.managingInstanceId = null // a tab always lands on the section's top level
                    },
                    onClose = onClose,
                )
                val background = rememberBackgroundPicture(settings.backgroundPath)
                background.paused = state.launchPhases.values.any { it == LaunchPhase.Running }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    PageBackground(background, settings)
                    AnimatedContent(
                        targetState = state.screen,
                        transitionSpec = {
                            (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(280)) { it / 32 })
                                .togetherWith(fadeOut(tween(120)))
                        },
                        label = "screen",
                    ) { screen ->
                        when (screen) {
                            Screen.Launch -> LaunchScreen(state, instances)
                            Screen.Instances -> {
                                val managed = state.managingInstanceId?.let { id -> instances.firstOrNull { it.id == id } }
                                if (managed != null) {
                                    InstanceDetailScreen(state, managed, onBack = { state.managingInstanceId = null })
                                } else {
                                    InstancesScreen(state, instances)
                                }
                            }
                            Screen.ModBrowser -> ModBrowserScreen(state, instances)
                            Screen.Bedrock -> BedrockScreen(state)
                            Screen.Settings -> CompositionLocalProvider(LocalBackground provides background) { SettingsScreen(state) }
                        }
                    }
                    StatusToast(
                        message = state.status,
                        onDismiss = { state.status = null },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                    )
                }
            }
            ResizeHandles(chrome)
        }
        if (state.signInOpen) SignInDialog(state, onDismiss = { state.signInOpen = false })
    }
}

/**
 * The user's own picture or GIF (cropped to fill, optionally blurred, dimmed toward the theme
 * colour so text stays readable) or the theme's soft gradient when none is set or the file is gone.
 */
@Composable
private fun PageBackground(picture: BackgroundPicture, settings: LauncherSettings) {
    // Derived, so a new animation frame redraws the canvas without recomposing this.
    val hasImage by remember(picture) { derivedStateOf { picture.image != null } }
    if (!hasImage) {
        Box(Modifier.fillMaxSize().background(LocalChrome.current.pageBackground))
        return
    }
    val blur = if (settings.backgroundBlur > 0f) Modifier.blur((settings.backgroundBlur * 24).dp) else Modifier
    if (settings.backgroundFit == BackgroundFit.FIT) {
        // The same frame, heavily blurred, fills the sides around the whole picture.
        BackgroundCanvas(picture, Modifier.fillMaxSize().blur(40.dp))
        BackgroundCanvas(picture, Modifier.fillMaxSize().then(blur), fit = true)
    } else {
        BackgroundCanvas(picture, Modifier.fillMaxSize().then(blur), focus = settings.backgroundFocus)
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = settings.backgroundDim.coerceIn(0f, 1f))))
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
            modifier = Modifier.widthIn(max = 560.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.medium,
            shadowElevation = 8.dp,
        ) {
            Text(lastMessage, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
