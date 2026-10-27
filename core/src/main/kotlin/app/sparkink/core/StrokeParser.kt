package app.sparkink.core

class ParsedStrokes(val strokes: List<List<Pt>>, val warnings: List<String>)

/**
 * Decoder for the pen-data file the pad streams out. Format as reverse-engineered by the
 * open-source Tuhi project: a 4-byte file header, then packets that start with a bitmask
 * byte whose set bits count the payload bytes.
 *
 *  - low two bits set: special packet (stroke header, end of stroke, lost point, full point, EOF)
 *  - low two bits clear: delta packet, 2 bits per axis (x, y, pressure): 0 none, 2 = int8 delta, 3 = u16 absolute
 *
 * Deltas accumulate (P2 = P0 + 2*d1 + d2), reset to zero whenever an absolute value arrives
 * for that axis or a new stroke starts.
 */
object StrokeParser {
    private val SPARK_HEADER = intArrayOf(0x62, 0x38, 0x62, 0x74)
    private val INTUOS_HEADER = intArrayOf(0x67, 0x82, 0x69, 0x65)

    private enum class Kind { UNKNOWN, FILE_HEADER, STROKE_END, POINT, DELTA, EOF, STROKE_HEADER, LOST_POINT }

    private class Axes(var x: Int? = null, var y: Int? = null, var p: Int? = null,
                       var dx: Int? = null, var dy: Int? = null, var dp: Int? = null,
                       var size: Int = 0)

    fun parse(raw: ByteArray): ParsedStrokes {
        val d = IntArray(raw.size) { raw[it].toInt() and 0xff }
        val warnings = ArrayList<String>()
        val strokes = ArrayList<List<Pt>>()
        var points = ArrayList<Pt>()

        fun flush() {
            if (points.isNotEmpty()) strokes.add(points)
            points = ArrayList()
        }

        var pos = when {
            matches(d, 0, SPARK_HEADER) -> 4
            matches(d, 0, INTUOS_HEADER) -> 16
            else -> {
                warnings.add("Unknown file header: " + raw.take(4).toByteArray().hex())
                return ParsedStrokes(emptyList(), warnings)
            }
        }

        var lastX = 0; var lastY = 0; var lastP = 0
        var dX = 0; var dY = 0; var dP = 0

        while (pos < d.size) {
            val header = d[pos]
            val nbytes = Integer.bitCount(header)
            val special = 1 + nbytes
            when (identify(d, pos, nbytes)) {
                Kind.FILE_HEADER -> {
                    warnings.add("Unexpected file header at byte $pos")
                    break
                }
                Kind.STROKE_END -> { flush(); pos += special }
                Kind.EOF -> { flush(); pos += special; break }
                Kind.STROKE_HEADER -> { flush(); dX = 0; dY = 0; dP = 0; pos += special }
                Kind.LOST_POINT -> pos += special
                Kind.UNKNOWN -> {
                    warnings.add("Unknown packet at byte $pos: ${raw.copyOfRange(pos, minOf(raw.size, pos + 9)).hex()}")
                    pos += special
                }
                Kind.POINT, Kind.DELTA -> {
                    val isPoint = identify(d, pos, nbytes) == Kind.POINT
                    val a = try {
                        if (isPoint) readAxes(d, header and 0x3.inv(), pos + 3).also { it.size += 3 }
                        else readAxes(d, header, pos + 1).also { it.size += 1 }
                    } catch (e: IllegalStateException) {
                        warnings.add("Bad packet at byte $pos: ${e.message}")
                        break
                    }
                    if (pos + a.size > d.size) {
                        warnings.add("Truncated packet at byte $pos")
                        break
                    }
                    a.dx?.let { dX += it } ?: a.x?.let { lastX = it; dX = 0 }
                    a.dy?.let { dY += it } ?: a.y?.let { lastY = it; dY = 0 }
                    a.dp?.let { dP += it } ?: a.p?.let { lastP = it; dP = 0 }
                    // Same bookkeeping as the reference decoder: the new point is the previous
                    // point plus the accumulated delta, and becomes the base for the next one.
                    lastX += dX; lastY += dY; lastP += dP
                    points.add(Pt(lastX, lastY, lastP))
                    pos += a.size
                }
            }
        }
        flush()
        return ParsedStrokes(strokes, warnings)
    }

    private fun matches(d: IntArray, at: Int, want: IntArray): Boolean {
        if (at + want.size > d.size) return false
        for (i in want.indices) if (d[at + i] != want[i]) return false
        return true
    }

    private fun identify(d: IntArray, pos: Int, nbytes: Int): Kind {
        val header = d[pos]
        fun pl(i: Int) = d.getOrElse(pos + 1 + i) { -1 }
        val avail = minOf(nbytes, d.size - pos - 1)

        if (matches(d, pos, SPARK_HEADER) || matches(d, pos, INTUOS_HEADER)) return Kind.FILE_HEADER
        if (matches(d, pos, intArrayOf(0xfc, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff))) return Kind.STROKE_END
        if (avail == 8 && (0 until 8).all { pl(it) == 0xff }) return Kind.EOF
        if (header and 0x3 == 0) return Kind.DELTA
        if (avail == 0) return Kind.UNKNOWN
        if (pl(0) == 0xfa || (pl(0) == 0xff && pl(1) == 0xee && pl(2) == 0xee)) return Kind.STROKE_HEADER
        if (pl(0) == 0xff && pl(1) == 0xff) return Kind.POINT
        if (pl(0) == 0xdd && pl(1) == 0xdd) return Kind.LOST_POINT
        return Kind.UNKNOWN
    }

    /** Decode the per-axis masks in [header]; data bytes start at [start]. size = data bytes consumed. */
    private fun readAxes(d: IntArray, header: Int, start: Int): Axes {
        val a = Axes()
        var off = start
        fun axis(mask: Int, setAbs: (Int) -> Unit, setDelta: (Int) -> Unit) {
            when (mask) {
                0 -> {}
                2 -> {
                    check(off < d.size) { "delta beyond end of data" }
                    setDelta(d[off].toByte().toInt()); off += 1
                }
                3 -> {
                    check(off + 1 < d.size) { "coordinate beyond end of data" }
                    setAbs(d[off] or (d[off + 1] shl 8)); off += 2
                }
                else -> error("unsupported axis mask $mask")
            }
        }
        axis((header and 0x0c) shr 2, { a.x = it }, { a.dx = it })
        axis((header and 0x30) shr 4, { a.y = it }, { a.dy = it })
        axis((header and 0xc0) shr 6, { a.p = it }, { a.dp = it })
        a.size = off - start
        return a
    }
}
