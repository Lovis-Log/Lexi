package io.github.lovislog.lexi

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private val Ease = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private const val BlurPadding = 132

private data class Drift(val x: Float, val y: Float, val scale: Float)
private data class Cloud(
    val diameter: Int, val duration: Int, val middle: Drift, val end: Drift,
)

private val Clouds = listOf(
    Cloud(240, 6_500, Drift(145f, 80f, 1.2f), Drift(55f, 185f, 0.92f)),
    Cloud(220, 7_500, Drift(-150f, 95f, 1.16f), Drift(-70f, -85f, 0.9f)),
    Cloud(250, 8_500, Drift(130f, -135f, 1.18f), Drift(-40f, -55f, 0.93f)),
)

@Composable
internal fun AtmosphereLayer(modifier: Modifier = Modifier, pointer: () -> Offset = { Offset.Unspecified }) {
    val appearance = LocalAppearance.current
    val cloudOpacity by animateFloatAsState(
        when (appearance.atmosphere) {
            Atmosphere.CLOUDS -> 1f
            Atmosphere.GRID -> 0.3f
            Atmosphere.STATIC -> 0f
        }, tween(500, easing = Ease), label = "Cloud opacity",
    )
    val meshOpacity by animateFloatAsState(
        if (appearance.atmosphere == Atmosphere.GRID) 1f else 0f,
        tween(500, easing = Ease), label = "Neural mesh opacity",
    )
    val cloudColors = when (appearance.accent) {
        Accent.BLUE -> listOf(Color(0xFF60A5FA), Color(0xFFA78BFA), Color(0xFF34D399))
        Accent.VIOLET -> listOf(Color(0xFFA78BFA), Color(0xFF818CF8), Color(0xFF7DD3FC))
        Accent.MINT -> listOf(Color(0xFF2DD4BF), Color(0xFF6EE7B7), Color(0xFF60A5FA))
    }
    if (cloudOpacity > 0f) CloudLayer(modifier, cloudColors) { cloudOpacity }
    if (meshOpacity > 0f) ParticleLayer(modifier, appearance.accent, pointer) { meshOpacity }
}

@Composable
private fun CloudLayer(modifier: Modifier, colors: List<Color>, opacity: () -> Float) {
    val transition = rememberInfiniteTransition(label = "Cloud drift")
    val progress = Clouds.map { cloud ->
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(cloud.duration, easing = LinearEasing),
            RepeatMode.Reverse), label = "Cloud movement")
    }
    val masks = remember {
        Clouds.map { cloud ->
            val side = cloud.diameter + BlurPadding * 2
            val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // Convert the prototype's CSS Gaussian sigma to Skia's blur radius.
                maskFilter = BlurMaskFilter(75.3f, BlurMaskFilter.Blur.NORMAL)
            }
            android.graphics.Canvas(bitmap).drawCircle(side / 2f, side / 2f, cloud.diameter / 2f, paint)
            bitmap.asImageBitmap()
        }
    }
    Canvas(modifier) {
        val unit = 1.dp.toPx()
        Clouds.forEachIndexed { index, cloud ->
            val phase = progress[index].value
            val from = if (phase < 0.5f) Drift(0f, 0f, 1f) else cloud.middle
            val to = if (phase < 0.5f) cloud.middle else cloud.end
            val t = EaseInOut.transform(if (phase < 0.5f) phase * 2 else (phase - 0.5f) * 2)
            val dx = from.x + (to.x - from.x) * t
            val dy = from.y + (to.y - from.y) * t
            val scale = from.scale + (to.scale - from.scale) * t
            val origin = when (index) {
                0 -> Offset(-40f, -30f)
                1 -> Offset(size.width / unit - cloud.diameter + 40f, 160f)
                else -> Offset(10f, size.height / unit - cloud.diameter - 70f)
            }
            val side = (masks[index].width * unit * scale).roundToInt()
            val cx = (origin.x + dx + cloud.diameter / 2f) * unit
            val cy = (origin.y + dy + cloud.diameter / 2f) * unit
            val alpha = when (index) { 0 -> 0.26f; 1 -> 0.22f; else -> 0.20f }
            drawImage(
                masks[index], dstOffset = IntOffset((cx - side / 2f).roundToInt(), (cy - side / 2f).roundToInt()),
                dstSize = IntSize(side, side), alpha = alpha * opacity(), colorFilter = ColorFilter.tint(colors[index]),
            )
        }
    }
}

private class Node(var x: Float, var y: Float, var vx: Float, var vy: Float, val radius: Float)

private class Mesh {
    var width = 0f
    var height = 0f
    var lastFrame = 0L
    var tick = 0f
    val wave = Path()
    val nodes = mutableListOf<Node>()

    fun resize(w: Float, h: Float) {
        width = w
        height = h
        nodes.clear()
        repeat(24) {
            nodes += Node(Random.nextFloat() * w, Random.nextFloat() * h,
                (Random.nextFloat() - 0.5f) * 0.4f, (Random.nextFloat() - 0.5f) * 0.4f,
                1.6f + Random.nextFloat() * 2f)
        }
    }
}

@Composable
private fun ParticleLayer(modifier: Modifier, accent: Accent, pointer: () -> Offset, opacity: () -> Float) {
    val mesh = remember { Mesh() }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withInfiniteAnimationFrameNanos { frame = it }
    }
    val colors = when (accent) {
        Accent.BLUE -> listOf(Color(0xFF1A73E8), Color(0xFF60A5FA), Color(0xFF34D399))
        Accent.VIOLET -> listOf(Color(0xFF7C3AED), Color(0xFFA78BFA), Color(0xFF38BDF8))
        Accent.MINT -> listOf(Color(0xFF0D9488), Color(0xFF2DD4BF), Color(0xFF3B82F6))
    }
    Canvas(modifier) {
        val unit = 1.dp.toPx()
        val w = size.width / unit
        val h = size.height / unit
        if (mesh.width != w || mesh.height != h) mesh.resize(w, h)
        val current = frame
        val elapsed = if (mesh.lastFrame == 0L) 1f else ((current - mesh.lastFrame) / 16_666_667f).coerceIn(0f, 3f)
        mesh.lastFrame = current
        mesh.tick += 0.015f * elapsed
        val alpha = opacity()
        mesh.wave.reset()
        var x = 0f
        while (x <= w) {
            val y = h * 0.48f + sin(x * 0.015f + mesh.tick) * 22 + cos(x * 0.008f - mesh.tick * 0.7f) * 12
            if (x == 0f) mesh.wave.moveTo(x, y * unit) else mesh.wave.lineTo(x * unit, y * unit)
            x += 12f
        }
        drawPath(mesh.wave, Color(0xFF1A73E8).copy(alpha = 0.08f * alpha), style = Stroke(1.4f * unit))

        val touch = pointer()
        mesh.nodes.forEach { node ->
            node.x += node.vx * elapsed
            node.y += node.vy * elapsed
            if (node.x !in 0f..w) {
                node.x = node.x.coerceIn(0f, w)
                node.vx = -node.vx
            }
            if (node.y !in 0f..h) {
                node.y = node.y.coerceIn(0f, h)
                node.vy = -node.vy
            }
            if (touch != Offset.Unspecified) {
                val dx = touch.x / unit - node.x
                val dy = touch.y / unit - node.y
                if (hypot(dx, dy) in 1f..110f) {
                    node.x += dx * 0.008f * elapsed
                    node.y += dy * 0.008f * elapsed
                }
            }
        }
        mesh.nodes.forEachIndexed { index, node ->
            val point = Offset(node.x * unit, node.y * unit)
            for (j in index + 1 until mesh.nodes.size) {
                val other = mesh.nodes[j]
                val distance = hypot(node.x - other.x, node.y - other.y)
                if (distance < 92f) drawLine(Color(0xFF3B82F6).copy(alpha = (1f - distance / 92f) * 0.20f * alpha),
                    point, Offset(other.x * unit, other.y * unit), strokeWidth = unit)
            }
            drawCircle(colors[index % 3].copy(alpha = 0.28f * alpha), node.radius * unit, point)
        }
    }
}
