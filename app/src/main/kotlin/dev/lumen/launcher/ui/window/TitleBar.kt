package dev.lumen.launcher.ui.window

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import dev.lumen.launcher.Screen
import dev.lumen.launcher.ui.brand.LumenLogo
import dev.lumen.launcher.ui.theme.LocalChrome
import dev.lumen.launcher.ui.tr
import java.awt.Cursor

val TitleBarHeight = 48.dp

/**
 * The app's own title bar and main navigation: logo and name on the left, the section tabs as
 * pills in the middle, caption buttons on the right like native Windows ones. Everything that
 * isn't a button drags the window.
 */
@Composable
fun TitleBar(chrome: WindowChrome, current: Screen, onSelect: (Screen) -> Unit, onClose: () -> Unit) {
    val colors = LocalChrome.current
    Row(Modifier.fillMaxWidth().height(TitleBarHeight).background(colors.titleBar)) {
        Row(
            Modifier.fillMaxHeight().moveArea(chrome).padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LumenLogo(24.dp)
            Spacer(Modifier.width(10.dp))
            Text("Lumen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.onTitleBar)
        }
        Box(Modifier.weight(1f).fillMaxHeight().moveArea(chrome), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Screen.entries.forEach { screen ->
                    TabPill(screen, selected = screen == current, onClick = { onSelect(screen) })
                }
            }
        }
        val captionHover = colors.onTitleBar.copy(alpha = 0.16f)
        CaptionButton(onClick = chrome::minimize, description = tr("Minimize", "最小化"), hoverBackground = captionHover) { color -> minimizeGlyph(color) }
        CaptionButton(
            onClick = chrome::toggleMaximize,
            description = if (chrome.isMaximized) tr("Restore", "还原") else tr("Maximize", "最大化"),
            hoverBackground = captionHover,
        ) { color ->
            if (chrome.isMaximized) restoreGlyph(color) else maximizeGlyph(color)
        }
        CaptionButton(onClick = onClose, description = tr("Close", "关闭"), hoverBackground = Color(0xFFE81123)) { color ->
            closeGlyph(color)
        }
    }
}

@Composable
private fun TabPill(screen: Screen, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalChrome.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            selected -> colors.tabSelected
            hovered -> colors.onTitleBar.copy(alpha = 0.14f)
            else -> Color.Transparent
        },
        tween(160),
        label = "tabBg",
    )
    val fg by animateColorAsState(if (selected) colors.onTabSelected else colors.onTitleBar, tween(160), label = "tabFg")
    Row(
        Modifier.height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(screen.icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(tr(screen.en, screen.zh), color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Drag to move, double-click to maximize/restore. One gesture loop handles both: separate tap and
 * drag detectors on the same area swallowed double-clicks. The click count comes from the OS mouse
 * event, so double-click speed follows the user's system setting.
 */
private fun Modifier.moveArea(chrome: WindowChrome): Modifier = pointerInput(chrome) {
    awaitEachGesture {
        val down = awaitFirstDown()
        if ((currentEvent.awtEventOrNull?.clickCount ?: 1) >= 2) {
            down.consume()
            chrome.toggleMaximize()
            return@awaitEachGesture
        }
        val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
        chrome.beginMove()
        drag(slop.id) { change ->
            change.consume()
            chrome.moveWithPointer()
        }
        chrome.endMove()
    }
}

@Composable
private fun CaptionButton(
    onClick: () -> Unit,
    description: String,
    hoverBackground: Color,
    glyph: DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) hoverBackground else Color.Transparent, tween(90), label = "captionBg")
    val fg = LocalChrome.current.onTitleBar
    Box(
        Modifier.width(46.dp).fillMaxHeight()
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) { glyph(fg) }
    }
}

// Thin 1px glyphs in the style of Windows 11's caption buttons (Material icons read too heavy here).
private fun DrawScope.minimizeGlyph(c: Color) =
    drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx())

private fun DrawScope.maximizeGlyph(c: Color) {
    val s = 1.dp.toPx()
    drawRect(c, topLeft = Offset(s / 2, s / 2), size = Size(size.width - s, size.height - s), style = Stroke(s))
}

private fun DrawScope.restoreGlyph(c: Color) {
    val s = 1.dp.toPx()
    val o = 2.dp.toPx()
    drawRect(c, topLeft = Offset(s / 2, o + s / 2), size = Size(size.width - o - s, size.height - o - s), style = Stroke(s))
    drawLine(c, Offset(o, s / 2), Offset(size.width - s / 2, s / 2), strokeWidth = s)
    drawLine(c, Offset(size.width - s / 2, s / 2), Offset(size.width - s / 2, size.height - o), strokeWidth = s)
}

private fun DrawScope.closeGlyph(c: Color) {
    val s = 1.dp.toPx()
    drawLine(c, Offset.Zero, Offset(size.width, size.height), strokeWidth = s)
    drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = s)
}

/** Invisible grips around the window edges (disabled while maximized, like native windows). */
@Composable
fun ResizeHandles(chrome: WindowChrome) {
    if (chrome.isMaximized) return
    val edge = 5.dp
    val corner = 10.dp
    Box(Modifier.fillMaxSize()) {
        Grip(chrome, WindowChrome.Edge.N, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(edge))
        Grip(chrome, WindowChrome.Edge.S, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(edge))
        Grip(chrome, WindowChrome.Edge.W, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(edge))
        Grip(chrome, WindowChrome.Edge.E, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(edge))
        Grip(chrome, WindowChrome.Edge.NW, Modifier.align(Alignment.TopStart).size(corner))
        Grip(chrome, WindowChrome.Edge.NE, Modifier.align(Alignment.TopEnd).size(corner))
        Grip(chrome, WindowChrome.Edge.SW, Modifier.align(Alignment.BottomStart).size(corner))
        Grip(chrome, WindowChrome.Edge.SE, Modifier.align(Alignment.BottomEnd).size(corner))
    }
}

@Composable
private fun BoxScope.Grip(chrome: WindowChrome, edge: WindowChrome.Edge, modifier: Modifier) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor(edge.cursor)))
            .pointerInput(edge) {
                detectDragGestures(onDragStart = { chrome.beginResize() }) { change, _ ->
                    change.consume()
                    chrome.resizeWithPointer(edge)
                }
            },
    )
}
