package app.sparkink.core

/** Vector export: one polyline per stroke, page size in mm so it prints at real size. */
fun Sketch.toSvg(color: String = "#000000", strokeMm: Double = 0.35): String {
    val sb = StringBuilder()
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="${width / 100.0}mm" height="${height / 100.0}mm" viewBox="0 0 $width $height">""")
    sb.append("""<rect width="$width" height="$height" fill="#ffffff"/>""")
    sb.append("""<g fill="none" stroke="$color" stroke-width="${(strokeMm * 100).toInt()}" stroke-linecap="round" stroke-linejoin="round">""")
    for (s in strokes) {
        if (s.isEmpty()) continue
        // a single point needs a zero-length segment to be drawn as a dot
        val pts = if (s.size == 1) listOf(s[0], s[0]) else s
        sb.append("<polyline points=\"").append(pts.joinToString(" ") { "${it.x},${it.y}" }).append("\"/>")
    }
    return sb.append("</g></svg>").toString()
}

/**
 * One notification from the live pen-data characteristic (Tuhi: `a1 <len> (x y p as u16le)*`,
 * where ff ff ff ff ff ff means the pen was lifted). Returns the points, with null for a lift;
 * anything else (proximity `a2`, status `10`) gives an empty list.
 */
fun parseLive(data: ByteArray): List<Pt?> {
    if (data.size < 2 || (data[0].toInt() and 0xff) != 0xa1) return emptyList()
    val len = minOf(data[1].toInt() and 0xff, data.size - 2)
    val out = ArrayList<Pt?>()
    var i = 2
    while (i + 6 <= 2 + len) {
        if ((0 until 6).all { data[i + it] == 0xff.toByte() }) out.add(null)
        else out.add(Pt(data.u16le(i), data.u16le(i + 2), data.u16le(i + 4)))
        i += 6
    }
    return out
}
