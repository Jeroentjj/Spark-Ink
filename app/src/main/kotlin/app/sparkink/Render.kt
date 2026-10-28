package app.sparkink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.sparkink.core.Sketch
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

object Render {
    private fun inkPaint(color: Int, widthPx: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = widthPx
    }

    /** Draw the strokes, (x0, y0) in device units at the canvas origin; only the first [limit] points (replay). */
    fun draw(c: Canvas, sk: Sketch, scale: Float, x0: Int, y0: Int, paint: Paint, limit: Int = Int.MAX_VALUE) {
        var left = limit
        for (s in sk.strokes) {
            if (s.isEmpty() || left <= 0) continue
            val px = (s[0].x - x0) * scale
            val py = (s[0].y - y0) * scale
            if (s.size == 1) { c.drawPoint(px, py, paint); left--; continue }
            val path = Path().apply { moveTo(px, py) }
            val n = min(s.size, left)
            for (i in 1 until n) path.lineTo((s[i].x - x0) * scale, (s[i].y - y0) * scale)
            c.drawPath(path, paint)
            left -= n
        }
    }

    /**
     * Draw the ink on white. With [cropToInk] the picture is trimmed to the written area
     * (plus a 5 mm margin), which gives the OCR the most pixels per letter. Without [widthMm]
     * the line width follows the picture size (what the OCR was tuned with).
     */
    fun bitmap(sk: Sketch, maxEdge: Int, cropToInk: Boolean, ink: Int = Color.BLACK, widthMm: Float? = null): Bitmap {
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
        val width = if (widthMm == null) max(2f, maxEdge / 450f) else max(1.5f, widthMm * 100 * scale)
        draw(c, sk, scale, x0, y0, inkPaint(ink, width))
        return bmp
    }

    fun png(bmp: Bitmap): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** Vector PDF at real size, one page per sketch. */
    fun pdf(sketches: List<Sketch>, ink: Int, widthMm: Float, out: File) {
        val doc = PdfDocument()
        val scale = 1 / 35.278f // device units (10 µm) per PDF point (1/72 inch)
        try {
            sketches.forEachIndexed { i, sk ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder((sk.width * scale).toInt(), (sk.height * scale).toInt(), i + 1).create())
                page.canvas.drawColor(Color.WHITE)
                draw(page.canvas, sk, scale, 0, 0, inkPaint(ink, widthMm * 100 * scale))
                doc.finishPage(page)
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }
}

/**
 * A page drawn straight from its strokes: sharp at any size, and able to replay the writing.
 * Pinch to zoom, drag to move around, double tap to zoom in or back out.
 */
class InkView(ctx: Context) : View(ctx) {
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private fun clampPan() {
        // keep the page on screen: at zoom z the content may move (z - 1) * size
        panX = panX.coerceIn(-(zoom - 1) * width, 0f)
        panY = panY.coerceIn(-(zoom - 1) * height, 0f)
    }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val z = (zoom * d.scaleFactor).coerceIn(1f, 6f)
            // zoom around the fingers
            panX = d.focusX - (d.focusX - panX) * z / zoom
            panY = d.focusY - (d.focusY - panY) * z / zoom
            zoom = z
            clampPan()
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom <= 1f) return false
            panX -= dx; panY -= dy
            clampPan()
            invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1f) { zoom = 1f; panX = 0f; panY = 0f }
            else { zoom = 2.5f; panX = e.x - e.x * zoom; panY = e.y - e.y * zoom; clampPan() }
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        // while zoomed in, the screen around us must not take the drag away
        if (zoom > 1f) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    var sketch: Sketch? = null
        set(v) { field = v; invalidate() }
    var limit = Int.MAX_VALUE
        set(v) { field = v; invalidate() }
    var ink = Color.BLACK
    var widthMm = 0.35f
    private val paper = Paint().apply { color = Color.WHITE }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(c: Canvas) {
        val sk = sketch ?: return
        val scale = min(width.toFloat() / sk.width, height.toFloat() / sk.height)
        val ox = (width - sk.width * scale) / 2
        val oy = (height - sk.height * scale) / 2
        paint.color = ink
        paint.strokeWidth = max(1.5f, widthMm * 100 * scale)
        c.save()
        c.translate(panX, panY)
        c.scale(zoom, zoom)
        c.translate(ox, oy)
        c.drawRect(0f, 0f, sk.width * scale, sk.height * scale, paper)
        Render.draw(c, sk, scale, 0, 0, paint, limit)
        c.restore()
    }
}
