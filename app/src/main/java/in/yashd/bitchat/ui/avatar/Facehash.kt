package `in`.yashd.bitchat.ui.avatar

import java.util.Locale
import kotlin.math.abs
import kotlin.math.tan

/** Offline, deterministic facehash SVG generation. No Android runtime required. */
object Facehash {
    /** Returns a scalable SVG with blinking eyes; set [animated] false for static consumers. */
    fun svg(name: String, animated: Boolean = true): String {
        val scene = scene(name)
        val geometry = scene.geometry
        val gradientId = "facehash-${scene.hash}-gradient"
        return buildString {
            append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="100" height="100">""")
            if (animated) {
                append("""<style>@keyframes facehash-blink{0%,92%,100%{transform:scaleY(1)}96%{transform:scaleY(.05)}}.facehash-eye{transform-box:fill-box;transform-origin:center;animation-name:facehash-blink;animation-timing-function:ease-in-out;animation-iteration-count:infinite}@media(prefers-reduced-motion:reduce){.facehash-eye{animation:none!important}}</style>""")
            }
            append("""<defs><radialGradient id="$gradientId" cx="${scene.gradientX}%" cy="${scene.gradientY}%" r="70%"><stop offset="0%" stop-color="#fff" stop-opacity=".15"/><stop offset="60%" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>""")
            append("""<path fill="${scene.color}" d="M0 0h100v100H0z"/><path fill="url(#$gradientId)" d="M0 0h100v100H0z"/>""")
            append("""<g fill="#000" transform="matrix(${scene.matrix.joinToString(" ")})">""")
            append("""<g transform="translate(20 ${scene.eyeY}) scale(${scene.eyeScale})">""")
            for (eye in listOf(geometry.left, geometry.right)) {
                append("<g")
                if (animated) append(""" class="facehash-eye" style="animation-duration:${scene.blinkDurationMs}ms;animation-delay:${scene.blinkDelayMs}ms"""")
                append(">")
                for (path in eye) append("""<path d="$path"/>""")
                append("</g>")
            }
            append("</g>")
            append("""<text x="50" y="70" text-anchor="middle" dominant-baseline="middle" font-family="monospace" font-size="26" font-weight="700">${escapeXml(scene.initial)}</text></g></svg>""")
        }
    }

    internal fun scene(name: String): FacehashScene {
        // Java/Kotlin string hashing has the same UTF-16 and Int overflow semantics as JS.
        val hash = abs(name.hashCode().toLong())
        val positions = listOf(-1 to 1, 1 to 1, 1 to 0, 0 to 1, -1 to 0, 0 to 0, 0 to -1, -1 to -1, 1 to -1)
        val (x, y) = positions[(hash % positions.size).toInt()]
        val colors = listOf("#ec4899", "#f59e0b", "#3b82f6", "#f97316", "#10b981")
        val sx = 1f - abs(y) * .08f
        val sy = 1f - abs(x) * .08f
        val skewX = tan(Math.toRadians(x * -3.5)).toFloat()
        val skewY = tan(Math.toRadians(y * 1.75)).toFloat()
        // SVG applies scale, then skewY, then skewX, about (50, 50).
        val a = (1 + skewX * skewY) * sx
        val b = skewY * sx
        val c = skewX * sy
        val d = sy
        val timing = ((hash * 31) % 40).toInt() * 100
        val codePoint = name.firstOrNull()?.let { name.codePointAt(0) } ?: 0
        // Unlike JS charAt(0), keep emoji intact and reject invalid XML characters.
        val initial = if (codePoint >= 32 && codePoint !in 0xD800..0xDFFF && codePoint !in 0xFFFE..0xFFFF) {
            String(Character.toChars(codePoint)).uppercase(Locale.ROOT)
        } else ""
        return FacehashScene(
            hash = hash,
            faceIndex = (hash % 4).toInt(),
            color = colors[(hash % colors.size).toInt()],
            initial = initial,
            matrix = listOf(a, b, c, d, y * 5f + 50 - a * 50 - c * 50, x * -5f + 50 - b * 50 - d * 50),
            gradientX = 50 - y * 6,
            gradientY = 50 + x * 6,
            blinkDelayMs = timing,
            blinkDurationMs = 2000 + timing,
        )
    }

    private fun escapeXml(value: String) = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}

internal data class FacehashScene(
    val hash: Long,
    val faceIndex: Int,
    val color: String,
    val initial: String,
    /** SVG matrix order: a, b, c, d, translateX, translateY. */
    val matrix: List<Float>,
    val gradientX: Int,
    val gradientY: Int,
    val blinkDelayMs: Int,
    val blinkDurationMs: Int,
) {
    val geometry get() = faceGeometries[faceIndex]
    val eyeScale get() = 60f / geometry.width
    val eyeY get() = 37f - geometry.height * eyeScale / 2
}
