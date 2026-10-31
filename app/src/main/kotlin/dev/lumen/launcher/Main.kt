package dev.lumen.launcher

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.lumen.launcher.ui.LocalLanguage
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.lumen.launcher.ui.LauncherShell
import dev.lumen.launcher.ui.brand.LumenLogoVector
import dev.lumen.launcher.ui.theme.LumenTheme
import dev.lumen.launcher.ui.window.WindowChrome
import java.awt.Dimension

private val MinWindowSize = Dimension(980, 620)

fun main() = application {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }
    LaunchedEffect(Unit) { state.loadInstances() }

    val windowState = rememberWindowState(size = DpSize(1200.dp, 760.dp), position = WindowPosition(Alignment.Center))
    val close = {
        state.close()
        exitApplication()
    }

    Window(
        onCloseRequest = close,
        title = "Lumen",
        icon = rememberVectorPainter(LumenLogoVector), // taskbar + Alt-Tab
        undecorated = true, // the app draws its own title bar (see ui/window)
        state = windowState,
    ) {
        val chrome = remember { WindowChrome(window, windowState, MinWindowSize) }
        LaunchedEffect(Unit) {
            window.minimumSize = MinWindowSize
            chrome.applyNativeFrame()
        }
        val settings by state.settings.settings.collectAsState()
        LumenTheme(settings.theme) {
            CompositionLocalProvider(LocalLanguage provides settings.language) {
                LauncherShell(state, chrome, onClose = close)
            }
        }
    }
}
