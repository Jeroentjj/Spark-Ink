package app.sparkink.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Turns a hand-drawn stroke into a clean shape once a recogniser (ML Kit's shape model) has
 * named it. The clean shape is again a list of points, so drawing, export and replay need
 * nothing new.
 */
object Shapes {
    private fun box(s: List<Pt>) = intArrayOf(s.minOf { it.x }, s.minOf { it.y }, s.maxOf { it.x }, s.maxOf { it.y })

    /** At least 8 mm across: a drawing, not a letter. */
    fun isCandidate(s: List<Pt>): Boolean {
        if (s.size < 5) return false
        val b = box(s)
        return max(b[2] - b[0], b[3] - b[1]) >= 800
    }

    /** Clean replacement of [s] for recogniser [label], or null to keep the ink as drawn. */
    fun clean(label: String, s: List<Pt>): List<Pt>? {
        if (!isCandidate(s)) return null
        val b = box(s)
        val size = max(b[2] - b[0], b[3] - b[1])
        val p = s.map { it.p }.sorted()[s.size / 2]
        val closed = hypot((s.first().x - s.last().x).toDouble(), (s.first().y - s.last().y).toDouble()) <= 0.3 * size
        val l = label.lowercase()
        val cx = (b[0] + b[2]) / 2.0; val cy = (b[1] + b[3]) / 2.0
        val rx = max(1.0, (b[2] - b[0]) / 2.0); val ry = max(1.0, (b[3] - b[1]) / 2.0)
        // The recogniser always names something, so every shape must also fit the ink it replaces:
        // a large handwritten word must never turn into a "line".
        return when {
            "arrow" in l -> null // usually several strokes: leave as drawn
            "line" in l -> {
                val len = dist(s.first(), s.last())
                val dev = s.maxOf { abs((s.last().x - s.first().x).toDouble() * (s.first().y - it.y) - (s.first().x - it.x).toDouble() * (s.last().y - s.first().y)) } / max(1.0, len)
                if (dev <= 0.08 * len) listOf(s.first(), s.last()).map { Pt(it.x, it.y, p) } else null
            }
            !closed -> null
            "rect" in l || "square" in l -> {
                val off = s.map { minOf(abs(it.x - b[0]), abs(it.x - b[2]), abs(it.y - b[1]), abs(it.y - b[3])) }.average()
                if (off <= 0.08 * size) listOf(
                    Pt(b[0], b[1], p), Pt(b[2], b[1], p), Pt(b[2], b[3], p), Pt(b[0], b[3], p), Pt(b[0], b[1], p)) else null
            }
            "ellipse" in l || "circle" in l || "oval" in l -> {
                val off = s.map { abs(hypot((it.x - cx) / rx, (it.y - cy) / ry) - 1) }.average()
                if (off > 0.15) null else (0..48).map { i -> val a = 2 * PI * i / 48; Pt((cx + rx * cos(a)).toInt(), (cy + ry * sin(a)).toInt(), p) }
            }
            "triangle" in l -> {
                // two points furthest apart, then the point furthest from the line between them
                var a = s[0]; var c = s[0]
                for (u in s) for (v in s) if (dist(u, v) > dist(a, c)) { a = u; c = v }
                val far = s.maxBy { abs((c.x - a.x).toDouble() * (a.y - it.y) - (a.x - it.x).toDouble() * (c.y - a.y)) }
                listOf(a, far, c, a).map { Pt(it.x, it.y, p) }
            }
            "diamond" in l || "rhomb" in l -> {
                val left = s.minBy { it.x }; val top = s.minBy { it.y }; val right = s.maxBy { it.x }; val bottom = s.maxBy { it.y }
                listOf(left, top, right, bottom, left).map { Pt(it.x, it.y, p) }
            }
            else -> null
        }
    }

    private fun dist(u: Pt, v: Pt) = hypot((u.x - v.x).toDouble(), (u.y - v.y).toDouble())
}
