package dev.lumen.launcher.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Lumen mark: an isometric block (Minecraft) with a spark of light (Lumen) on a blue tile.
 * Geometry lives here once, in a 64×64 grid, and is drawn both by Compose (in-app, window icon)
 * and by Java2D (`IconExport`, for the .ico/.png installer icons) so they can never drift apart.
 */
object LogoGeometry {
    const val SIZE = 64f
    const val TILE_RADIUS = 15f
    const val TILE_START = 0xFF3D9BFF // top-left
    const val TILE_END = 0xFF1A56CC // bottom-right

    data class Face(val points: List<Pair<Float, Float>>, val argb: Long)

    private val top = 32f to 19f
    private val upperRight = 46f to 27f
    private val lowerRight = 46f to 43f
    private val bottom = 32f to 51f
    private val lowerLeft = 18f to 43f
    private val upperLeft = 18f to 27f
    private val center = 32f to 35f

    /** Whole block outline, filled first so anti-aliased seams between faces never show the tile through. */
    val silhouette = listOf(top, upperRight, lowerRight, bottom, lowerLeft, upperLeft)
    const val SILHOUETTE_ARGB = 0xFFCFE3FF

    /** Lit from the top-left: brightest top, softer left, coolest right. */
    val faces = listOf(
        Face(listOf(top, upperRight, center, upperLeft), 0xFFFFFFFF),
        Face(listOf(upperLeft, center, bottom, lowerLeft), 0xFFE6F1FF),
        Face(listOf(center, upperRight, lowerRight, bottom), 0xFFB9D6FF),
    )

    /** Four-point spark above the block's right shoulder. */
    val spark: List<Pair<Float, Float>> = run {
        val (cx, cy, r, i) = listOf(48f, 13.5f, 7f, 1.7f)
        listOf(cx to cy - r, cx + i to cy - i, cx + r to cy, cx + i to cy + i, cx to cy + r, cx - i to cy + i, cx - r to cy, cx - i to cy - i)
    }
    const val SPARK_ARGB = 0xFFFFFFFF
}

val LumenLogoVector: ImageVector by lazy {
    val g = LogoGeometry
    ImageVector.Builder("LumenLogo", 64.dp, 64.dp, g.SIZE, g.SIZE).apply {
        addPath(
            pathData = roundedSquare(g.SIZE, g.TILE_RADIUS),
            fill = Brush.linearGradient(listOf(Color(g.TILE_START), Color(g.TILE_END)), Offset.Zero, Offset(g.SIZE, g.SIZE)),
        )
        addPath(pathData = polygon(g.silhouette), fill = SolidColor(Color(g.SILHOUETTE_ARGB)))
        g.faces.forEach { addPath(pathData = polygon(it.points), fill = SolidColor(Color(it.argb))) }
        addPath(pathData = polygon(g.spark), fill = SolidColor(Color(g.SPARK_ARGB)))
    }.build()
}

@Composable
fun LumenLogo(size: Dp, modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(LumenLogoVector), contentDescription = "Lumen", modifier = modifier.size(size))
}

private fun polygon(points: List<Pair<Float, Float>>) = PathBuilder().apply {
    moveTo(points.first().first, points.first().second)
    points.drop(1).forEach { (x, y) -> lineTo(x, y) }
    close()
}.nodes

private fun roundedSquare(s: Float, r: Float) = PathBuilder().apply {
    moveTo(r, 0f)
    lineTo(s - r, 0f)
    arcTo(r, r, 0f, false, true, s, r)
    lineTo(s, s - r)
    arcTo(r, r, 0f, false, true, s - r, s)
    lineTo(r, s)
    arcTo(r, r, 0f, false, true, 0f, s - r)
    lineTo(0f, r)
    arcTo(r, r, 0f, false, true, r, 0f)
    close()
}.nodes
