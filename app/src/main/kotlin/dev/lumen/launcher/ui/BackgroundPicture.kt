package dev.lumen.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jetbrains.skia.AnimationDisposalMode
import org.jetbrains.skia.AnimationFrameInfo
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The user's background picture: a still image, or an animated GIF / WebP played frame by frame.
 * Only the current frame is kept, so a long animation costs two frames of memory, not all of them.
 */
@Stable
class BackgroundPicture internal constructor() {
    /** The frame to draw; null while loading, or when there's no picture or it can't be read. */
    var image by mutableStateOf<Image?>(null)
        private set

    /** Set while the game runs, so the animation doesn't take CPU from it. */
    var paused by mutableStateOf(false)

    internal suspend fun play(path: String) {
        val codec = withContext(Dispatchers.IO) {
            runCatching { Codec.makeFromData(Data.makeFromBytes(File(path).readBytes())) }.getOrNull()
        } ?: return
        val work = Bitmap()
        try {
            work.allocPixels(codec.imageInfo)
            show(withContext(Dispatchers.Default) { decode(codec, work, 0, null) })
            val frames = codec.framesInfo
            if (frames.size <= 1) return
            var i = 0
            while (true) {
                delay(frameDelay(frames[i]))
                if (paused) snapshotFlow { paused }.first { !it }
                withFrameMillis { } // waits while the window isn't drawing, e.g. minimized
                val next = (i + 1) % frames.size
                val prior = if (next == 0) null else i // a loop restarts from a blank canvas
                show(withContext(Dispatchers.Default) { decode(codec, work, next, prior) })
                i = next
            }
        } finally {
            work.close()
            codec.close()
        }
    }

    /**
     * Decodes [frame] into [work], which holds [prior] (or nothing). Frames that build on the one
     * before are decoded on top of it; Skia decodes the chain itself when that's not allowed.
     */
    private fun decode(codec: Codec, work: Bitmap, frame: Int, prior: Int?): Image {
        val info = codec.framesInfo
        val canBuildOnPrior = prior != null && info[frame].requiredFrame != -1 &&
            info[prior].disposalMethod != AnimationDisposalMode.RESTORE_PREVIOUS
        if (canBuildOnPrior) codec.readPixels(work, frame, prior!!) else codec.readPixels(work, frame)
        return Image.makeFromBitmap(work) // a copy: `work` is overwritten by the next frame
    }

    private fun show(next: Image) {
        val old = image
        image = next
        old?.close() // anything already recorded for drawing keeps its own reference
    }

    internal fun release() {
        image?.close()
        image = null
    }

    // Browsers treat GIF delays of 10 ms or less as 100 ms; many GIFs rely on that.
    private fun frameDelay(frame: AnimationFrameInfo): Long = frame.duration.let { if (it <= 10) 100L else it.toLong() }
}

/** Provided by the shell, so the Settings preview shows the same frames without decoding twice. */
val LocalBackground = staticCompositionLocalOf<BackgroundPicture?> { null }

@Composable
fun rememberBackgroundPicture(path: String?): BackgroundPicture {
    val picture = remember(path) { BackgroundPicture() }
    LaunchedEffect(picture) { if (path != null) picture.play(path) }
    DisposableEffect(picture) { onDispose { picture.release() } }
    return picture
}

/**
 * Draws the current frame, cropped to cover the canvas or, with [fit], whole and centred.
 * [focus] picks which part survives the crop (0 = top/left, 1 = bottom/right), so a tall
 * picture can keep its subject's face in view. Each new frame only redraws this canvas.
 */
@Composable
fun BackgroundCanvas(picture: BackgroundPicture, modifier: Modifier = Modifier, fit: Boolean = false, focus: Float = 0.5f) {
    Canvas(modifier) {
        val image = picture.image ?: return@Canvas
        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        val (src, dst) = if (fit) {
            val scale = min(size.width / iw, size.height / ih)
            val w = iw * scale
            val h = ih * scale
            Rect.makeWH(iw, ih) to Rect.makeXYWH((size.width - w) / 2, (size.height - h) / 2, w, h)
        } else {
            val scale = max(size.width / iw, size.height / ih)
            val w = size.width / scale
            val h = size.height / scale
            val f = focus.coerceIn(0f, 1f)
            Rect.makeXYWH((iw - w) * f, (ih - h) * f, w, h) to Rect.makeWH(size.width, size.height)
        }
        drawIntoCanvas { it.nativeCanvas.drawImageRect(image, src, dst, SamplingMode.LINEAR, null, true) }
    }
}
