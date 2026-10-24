package `in`.yashd.bitchat.ui.avatar

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Native animated rendering of [Facehash.svg]. Decorative; put semantics on the caller. */
@Composable
fun FacehashAvatar(
    name: String,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    backgroundColor: androidx.compose.ui.graphics.Color? = null,
    foregroundColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Black,
) {
    val scene = remember(name) { Facehash.scene(name) }
    val eyes = remember(scene.faceIndex) {
        listOf(scene.geometry.left, scene.geometry.right).map { paths ->
            val parsed = paths.map { requireNotNull(PathParser.createPathFromPathData(it)) }
            val bounds = android.graphics.RectF()
            for (path in parsed) {
                val pathBounds = android.graphics.RectF()
                path.computeBounds(pathBounds, true)
                bounds.union(pathBounds)
            }
            parsed to bounds
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val blink = if (animated && started) {
        val transition = rememberInfiniteTransition(label = "Facehash blink")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    // Blink promptly on every screen instead of waiting up to 9.5s after entry.
                    durationMillis = 2800 + (scene.hash % 1000).toInt()
                    1f at 0
                    1f at 800 using CubicBezierEasing(.42f, 0f, .58f, 1f)
                    .05f at 950 using CubicBezierEasing(.42f, 0f, .58f, 1f)
                    1f at 1100
                    1f at durationMillis
                },
            ),
            label = "Eye scale",
        )
    } else rememberUpdatedState(1f)
    val foreground = remember(foregroundColor) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foregroundColor.toArgb()
            textSize = 26f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
    }
    val background = remember(scene.color, backgroundColor) {
        Paint().apply { color = backgroundColor?.toArgb() ?: Color.parseColor(scene.color) }
    }
    val highlight = remember(scene.gradientX, scene.gradientY) {
        Paint().apply {
            shader = RadialGradient(
                scene.gradientX.toFloat(), scene.gradientY.toFloat(), 70f,
                intArrayOf(0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP,
            )
        }
    }
    val projection = remember(scene) {
        val (a, b, c, d) = scene.matrix
        Matrix().apply { setValues(floatArrayOf(a, c, scene.matrix[4], b, d, scene.matrix[5], 0f, 0f, 1f)) }
    }
    Canvas(modifier.size(40.dp)) {
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val side = minOf(size.width, size.height)
            canvas.save()
            canvas.translate((size.width - side) / 2, (size.height - side) / 2)
            canvas.scale(side / 100, side / 100)
            canvas.clipRect(0f, 0f, 100f, 100f)
            canvas.drawRect(0f, 0f, 100f, 100f, background)
            canvas.drawRect(0f, 0f, 100f, 100f, highlight)
            canvas.concat(projection)
            canvas.save()
            canvas.translate(20f, scene.eyeY)
            canvas.scale(scene.eyeScale, scene.eyeScale)
            for ((eye, bounds) in eyes) {
                canvas.save()
                canvas.scale(1f, blink.value, bounds.centerX(), bounds.centerY())
                for (path in eye) canvas.drawPath(path, foreground)
                canvas.restore()
            }
            canvas.restore()
            val metrics = foreground.fontMetrics
            canvas.drawText(scene.initial, 50f, 70f - (metrics.ascent + metrics.descent) / 2, foreground)
            canvas.restore()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FacehashAvatarPreview() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Alice", "Bob", "Charlie", "Diana").forEach {
            FacehashAvatar(it, Modifier.size(64.dp), animated = false)
        }
    }
}
