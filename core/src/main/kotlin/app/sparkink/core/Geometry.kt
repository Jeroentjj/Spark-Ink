package app.sparkink.core

/** Strokes plus the page size they live in (device units, 10 µm). */
class Sketch(val strokes: List<List<Pt>>, val width: Int, val height: Int) {

    /** Rotate clockwise by 0/90/180/270 degrees; the page dimensions swap for 90 and 270. */
    fun rotated(degrees: Int): Sketch = when (((degrees % 360) + 360) % 360) {
        90 -> Sketch(strokes.map { s -> s.map { Pt(height - it.y, it.x, it.p) } }, height, width)
        180 -> Sketch(strokes.map { s -> s.map { Pt(width - it.x, height - it.y, it.p) } }, width, height)
        270 -> Sketch(strokes.map { s -> s.map { Pt(it.y, width - it.x, it.p) } }, height, width)
        else -> this
    }

    /** Bounding box of all ink (minX, minY, maxX, maxY) or null if empty. */
    fun bounds(): IntArray? {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        var any = false
        for (s in strokes) for (p in s) {
            any = true
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        return if (any) intArrayOf(minX, minY, maxX, maxY) else null
    }

    /**
     * Group strokes into text lines (top to bottom, strokes left to right inside a line).
     * A stroke joins the current line when its vertical centre lies within the line's band;
     * the band is the typical stroke height, so tall and short handwriting both work.
     */
    fun lines(): List<List<List<Pt>>> {
        if (strokes.isEmpty()) return emptyList()
        class Info(val stroke: List<Pt>, val minX: Int, val minY: Int, val maxY: Int) {
            val cy get() = (minY + maxY) / 2
        }
        val infos = strokes.filter { it.isNotEmpty() }.map {
            Info(it, it.minOf { p -> p.x }, it.minOf { p -> p.y }, it.maxOf { p -> p.y })
        }.sortedBy { it.cy }
        val heights = infos.map { it.maxY - it.minY }.sorted()
        // 100 units = 1 mm; never let the band get thinner than 4 mm (dots, commas) or thicker than 12 mm
        val band = heights[heights.size * 2 / 3].coerceIn(400, 1200)

        val lines = ArrayList<ArrayList<Info>>()
        var lineCy = 0
        for (i in infos) {
            val cur = lines.lastOrNull()
            if (cur != null && i.cy - lineCy <= band) {
                cur.add(i)
                lineCy = cur.sumOf { it.cy } / cur.size
            } else {
                lines.add(arrayListOf(i))
                lineCy = i.cy
            }
        }
        return lines.map { line -> line.sortedBy { it.minX }.map { it.stroke } }
    }
}
