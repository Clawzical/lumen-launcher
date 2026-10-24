package dev.lumen.launcher.tools

import dev.lumen.launcher.ui.brand.LogoGeometry
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * Renders [LogoGeometry] with Java2D into installer icons:
 *   lumen.ico  – 16…256 px, PNG-compressed entries (Windows Vista+)
 *   lumen.png  – 512 px (Linux packages, README)
 * Run with `./gradlew :app:generateIcons`.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "icons").apply { mkdirs() }
    ImageIO.write(render(512), "png", File(out, "lumen.png"))
    File(out, "lumen.ico").writeBytes(ico(listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)))
    println("Wrote ${out.absolutePath}/lumen.png and lumen.ico")
}

fun render(px: Int): BufferedImage {
    val g = LogoGeometry
    val img = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
    img.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        scale(px / g.SIZE.toDouble(), px / g.SIZE.toDouble())
        paint = GradientPaint(0f, 0f, awt(g.TILE_START), g.SIZE, g.SIZE, awt(g.TILE_END))
        fill(RoundRectangle2D.Float(0f, 0f, g.SIZE, g.SIZE, g.TILE_RADIUS * 2, g.TILE_RADIUS * 2))
        color = awt(g.SILHOUETTE_ARGB)
        fill(polygon(g.silhouette))
        g.faces.forEach { face -> color = awt(face.argb); fill(polygon(face.points)) }
        color = awt(g.SPARK_ARGB)
        fill(polygon(g.spark))
        dispose()
    }
    return img
}

/** ICONDIR + ICONDIRENTRY[n] + PNG payloads, little-endian. */
private fun ico(sizes: List<Int>): ByteArray {
    val pngs = sizes.map { s -> ByteArrayOutputStream().also { ImageIO.write(render(s), "png", it) }.toByteArray() }
    val header = 6 + 16 * sizes.size
    val buf = ByteBuffer.allocate(header + pngs.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(0).putShort(1).putShort(sizes.size.toShort())
    var offset = header
    sizes.zip(pngs).forEach { (s, png) ->
        buf.put((if (s >= 256) 0 else s).toByte()).put((if (s >= 256) 0 else s).toByte()) // 0 means 256
        buf.put(0).put(0) // palette size, reserved
        buf.putShort(1).putShort(32) // planes, bits per pixel
        buf.putInt(png.size).putInt(offset)
        offset += png.size
    }
    pngs.forEach { buf.put(it) }
    return buf.array()
}

private fun polygon(points: List<Pair<Float, Float>>) = Path2D.Float().apply {
    moveTo(points.first().first, points.first().second)
    points.drop(1).forEach { (x, y) -> lineTo(x, y) }
    closePath()
}

private fun awt(argb: Long) = Color(argb.toInt(), true)
