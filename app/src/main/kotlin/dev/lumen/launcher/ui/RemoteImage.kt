package dev.lumen.launcher.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/** Small LRU of decoded icons so scrolling back up doesn't re-download or re-decode. */
private object IconCache {
    private val map = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 256
    }

    @Synchronized fun get(url: String): ImageBitmap? = map[url]
    @Synchronized fun put(url: String, image: ImageBitmap) { map[url] = image }
}

/**
 * Loads an icon over the shared client and decodes it with Skia (PNG/JPEG/WebP/GIF).
 * Anything that fails (missing URL, SVG, network) falls back to a lettered tile.
 */
/** Downloads and decodes an image once per URL (LRU-cached); null until loaded or on failure. */
@Composable
fun rememberRemoteBitmap(http: HttpClient, url: String?): ImageBitmap? {
    val image by produceState(url?.let(IconCache::get), url) {
        // produceState keeps the previous value across key changes; reset so a new (or null) URL
        // never shows the old image (e.g. a signed-out account's skin).
        value = url?.let(IconCache::get)
        if (url == null || value != null) return@produceState
        value = runCatching {
            val bytes = http.get(url).readRawBytes()
            withContext(Dispatchers.Default) { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }
        }.getOrNull()?.also { IconCache.put(url, it) }
    }
    return image
}

@Composable
fun RemoteIcon(http: HttpClient, url: String?, fallback: String, size: Dp, modifier: Modifier = Modifier) {
    val image = rememberRemoteBitmap(http, url)

    Box(
        modifier.size(size).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(image, label = "icon") { bitmap ->
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            } else {
                Text(
                    fallback.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
