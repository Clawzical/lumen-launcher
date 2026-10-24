package dev.lumen.launcher.ui.window

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowState
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import java.awt.Cursor
import java.awt.Dimension
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * Window behaviour the OS normally provides, re-implemented for an undecorated window:
 * move, maximize/restore (to the work area, so the taskbar stays visible), snap-to-top,
 * edge resizing and Windows 11 rounded corners.
 *
 * Moves/resizes use absolute screen pointer positions (not per-event deltas), which stays
 * jitter-free even though the window moves under the cursor.
 */
@Stable
class WindowChrome(
    private val window: ComposeWindow,
    private val state: WindowState,
    private val minSize: Dimension,
) {
    var isMaximized by mutableStateOf(false)
        private set

    /** True when Windows draws the rounded corners + border itself; otherwise the shell draws a border. */
    var hasNativeFrame by mutableStateOf(false)
        private set

    private var restoreBounds: Rectangle? = null
    private var startPointer = Point()
    private var startBounds = Rectangle()

    fun minimize() {
        state.isMinimized = true
    }

    fun toggleMaximize() = if (isMaximized) restore() else maximize()

    fun maximize() {
        if (!isMaximized) restoreBounds = window.bounds
        window.bounds = workArea()
        isMaximized = true
        setCorners(round = false)
    }

    fun restore() {
        restoreBounds?.let { window.bounds = it }
        isMaximized = false
        setCorners(round = true)
    }

    // --- moving -------------------------------------------------------------------------------

    fun beginMove() {
        val p = pointer()
        if (isMaximized) {
            // Like Windows: dragging a maximized window restores it under the cursor, keeping the
            // grab point at the same relative x.
            val r = restoreBounds ?: Rectangle(0, 0, 1200, 760)
            val fx = (p.x - window.x).toDouble() / window.width.coerceAtLeast(1)
            isMaximized = false
            setCorners(round = true)
            window.bounds = Rectangle((p.x - fx * r.width).toInt(), p.y - TITLE_GRAB_Y, r.width, r.height)
        }
        startPointer = p
        startBounds = window.bounds
    }

    fun moveWithPointer() {
        val p = pointer()
        window.setLocation(startBounds.x + p.x - startPointer.x, startBounds.y + p.y - startPointer.y)
    }

    /** Aero-snap equivalent: releasing at the very top edge of the screen maximizes. */
    fun endMove() {
        if (pointer().y <= workArea().y + 1) {
            restoreBounds = startBounds
            maximize()
        }
    }

    // --- resizing -------------------------------------------------------------------------------

    enum class Edge(val n: Boolean, val s: Boolean, val w: Boolean, val e: Boolean, val cursor: Int) {
        N(true, false, false, false, Cursor.N_RESIZE_CURSOR),
        S(false, true, false, false, Cursor.S_RESIZE_CURSOR),
        W(false, false, true, false, Cursor.W_RESIZE_CURSOR),
        E(false, false, false, true, Cursor.E_RESIZE_CURSOR),
        NW(true, false, true, false, Cursor.NW_RESIZE_CURSOR),
        NE(true, false, false, true, Cursor.NE_RESIZE_CURSOR),
        SW(false, true, true, false, Cursor.SW_RESIZE_CURSOR),
        SE(false, true, false, true, Cursor.SE_RESIZE_CURSOR),
    }

    fun beginResize() {
        startPointer = pointer()
        startBounds = window.bounds
    }

    fun resizeWithPointer(edge: Edge) {
        val p = pointer()
        val dx = p.x - startPointer.x
        val dy = p.y - startPointer.y
        var (x, y, w, h) = listOf(startBounds.x, startBounds.y, startBounds.width, startBounds.height)
        if (edge.e) w += dx
        if (edge.s) h += dy
        if (edge.w) { x += dx; w -= dx }
        if (edge.n) { y += dy; h -= dy }
        // Clamp to the minimum size, keeping the opposite edge anchored.
        if (w < minSize.width) { if (edge.w) x -= minSize.width - w; w = minSize.width }
        if (h < minSize.height) { if (edge.n) y -= minSize.height - h; h = minSize.height }
        window.setBounds(x, y, w, h)
    }

    // --- native frame -------------------------------------------------------------------------

    /** Windows 11: ask DWM to round the corners (it also draws the thin system border). No-op elsewhere. */
    fun applyNativeFrame() {
        hasNativeFrame = setCorners(round = true)
    }

    private fun setCorners(round: Boolean): Boolean {
        if ("win" !in System.getProperty("os.name").lowercase()) return false
        return runCatching {
            val hwnd = WinDef.HWND(Native.getComponentPointer(window))
            val pref = IntByReference(if (round) DWMWCP_ROUND else DWMWCP_DONOTROUND)
            DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, pref, 4) == 0
        }.getOrDefault(false)
    }

    private fun workArea(): Rectangle {
        val gc = window.graphicsConfiguration
        val b = gc.bounds
        val i = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        return Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
    }

    private fun pointer(): Point = MouseInfo.getPointerInfo()?.location ?: Point(window.x, window.y)

    private companion object {
        const val TITLE_GRAB_Y = 18
        const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
        const val DWMWCP_DONOTROUND = 1
        const val DWMWCP_ROUND = 2
    }
}

@Suppress("FunctionName")
private interface DwmApi : Library {
    fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int

    companion object {
        val INSTANCE: DwmApi by lazy { Native.load("dwmapi", DwmApi::class.java) }
    }
}
