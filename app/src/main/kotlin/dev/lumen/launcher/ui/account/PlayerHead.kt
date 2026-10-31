package dev.lumen.launcher.ui.account

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.lumen.launcher.AppState
import dev.lumen.launcher.ui.rememberRemoteBitmap
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch

/**
 * The face (8×8 at 8,8) plus the hat overlay (8×8 at 40,8) from a skin texture, pixel-sharp.
 * Like vanilla, the hat layer is ignored when it has no transparent pixels: old 64×32 skins
 * often fill it with solid black, which would otherwise hide the face.
 */
@Composable
fun PlayerHead(http: HttpClient, skinUrl: String?, headSize: Dp, modifier: Modifier = Modifier) {
    val skin = rememberRemoteBitmap(http, skinUrl)
    val drawHat = remember(skin) {
        skin?.let { img ->
            val px = IntArray(64)
            runCatching { img.readPixels(px, startX = 40, startY = 8, width = 8, height = 8) }.isSuccess &&
                px.any { (it ushr 24) < 0xFF }
        } ?: false
    }
    Box(
        modifier.size(headSize).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (skin == null) {
            Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val dst = IntSize(size.width.toInt(), size.height.toInt())
                drawImage(skin, srcOffset = IntOffset(8, 8), srcSize = IntSize(8, 8), dstSize = dst, filterQuality = FilterQuality.None)
                if (drawHat) {
                    drawImage(skin, srcOffset = IntOffset(40, 8), srcSize = IntSize(8, 8), dstSize = dst, filterQuality = FilterQuality.None)
                }
            }
        }
    }
}

