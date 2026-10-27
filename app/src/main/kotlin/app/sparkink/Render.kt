package app.sparkink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import app.sparkink.core.Sketch
import java.io.ByteArrayOutputStream
import kotlin.math.max

object Render {
    /**
     * Draw the ink black on white. With [cropToInk] the picture is trimmed to the written area
     * (plus a 5 mm margin), which gives the OCR the most pixels per letter.
     */
    fun bitmap(sk: Sketch, maxEdge: Int, cropToInk: Boolean): Bitmap {
        val b = if (cropToInk) sk.bounds() else null
        val margin = 500 // 5 mm in device units
        val x0 = if (b != null) b[0] - margin else 0
        val y0 = if (b != null) b[1] - margin else 0
        val x1 = if (b != null) b[2] + margin else sk.width
        val y1 = if (b != null) b[3] + margin else sk.height
        val w = max(1, x1 - x0)
        val h = max(1, y1 - y0)
        val scale = maxEdge.toFloat() / max(w, h)

        val bmp = Bitmap.createBitmap(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = max(2f, maxEdge / 450f)
        }
        for (s in sk.strokes) {
            if (s.isEmpty()) continue
            val px = (s[0].x - x0) * scale
            val py = (s[0].y - y0) * scale
            if (s.size == 1) { c.drawPoint(px, py, paint); continue }
            val path = Path().apply { moveTo(px, py) }
            for (i in 1 until s.size) path.lineTo((s[i].x - x0) * scale, (s[i].y - y0) * scale)
            c.drawPath(path, paint)
        }
        return bmp
    }

    fun png(bmp: Bitmap): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
