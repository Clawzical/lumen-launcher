package dev.lumen.launcher.ui

import dev.lumen.launcher.tools.render
import java.awt.SystemTray
import java.awt.TrayIcon
import java.util.Timer
import kotlin.concurrent.schedule

/**
 * A system notification (a toast on Windows) for results the user may not be looking at, such as
 * a long version change finishing. Lumen has no permanent tray icon, so one is added just for the
 * message and removed shortly after. Silently does nothing where the OS has no tray.
 */
object DesktopNotifier {
    fun notify(title: String, message: String, warning: Boolean = false) {
        runCatching {
            if (!SystemTray.isSupported()) return
            val tray = SystemTray.getSystemTray()
            val icon = TrayIcon(render(64), "Lumen").apply { isImageAutoSize = true } // the Lumen logo
            tray.add(icon)
            icon.displayMessage(title, message, if (warning) TrayIcon.MessageType.WARNING else TrayIcon.MessageType.INFO)
            Timer(true).schedule(15_000) { runCatching { tray.remove(icon) } }
        }
    }
}
