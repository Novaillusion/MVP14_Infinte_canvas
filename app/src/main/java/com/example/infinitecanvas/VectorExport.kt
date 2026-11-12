package com.example.infinitecanvas

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidPath
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Exports strokes as true vector files, cropped to the drawing with a small margin.
 * Background is transparent and the dot grid is not included.
 */
object VectorExport {
    private const val MARGIN = 24f

    private fun area(strokes: List<InkStroke>): Rect {
        var r = strokes[0].bounds
        for (s in strokes) r = Rect(
            min(r.left, s.bounds.left), min(r.top, s.bounds.top),
            max(r.right, s.bounds.right), max(r.bottom, s.bounds.bottom)
        )
        return r.inflate(MARGIN)
    }

    private fun f(v: Float) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun hex(argb: Int) = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    /** SVG path data matching [buildSmoothPath] exactly. */
    private fun pathData(pts: FloatArray, x0: Float, y0: Float): String {
        val n = pts.size / 2
        val sb = StringBuilder()
        fun x(i: Int) = pts[2 * i] - x0
        fun y(i: Int) = pts[2 * i + 1] - y0
        sb.append("M").append(f(x(0))).append(' ').append(f(y(0)))
        if (n == 1) {
            sb.append("L").append(f(x(0) + 0.01f)).append(' ').append(f(y(0)))
            return sb.toString()
        }
        for (i in 1 until n - 1) {
            val mx = (x(i) + x(i + 1)) / 2f
            val my = (y(i) + y(i + 1)) / 2f
            sb.append("Q").append(f(x(i))).append(' ').append(f(y(i)))
                .append(' ').append(f(mx)).append(' ').append(f(my))
        }
        sb.append("L").append(f(x(n - 1))).append(' ').append(f(y(n - 1)))
        return sb.toString()
    }

    fun toSvg(strokes: List<InkStroke>): String {
        val a = area(strokes)
        val w = f(a.width)
        val h = f(a.height)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h">""").append('\n')
        sb.append("""<g fill="none" stroke-linecap="round" stroke-linejoin="round">""").append('\n')
        for (s in strokes) {
            sb.append("""<path stroke="${hex(s.color)}" stroke-width="${f(s.width)}" d="${pathData(s.points, a.left, a.top)}"/>""")
                .append('\n')
        }
        sb.append("</g>\n</svg>\n")
        return sb.toString()
    }

    fun toPdf(strokes: List<InkStroke>): ByteArray {
        val a = area(strokes)
        // PDF pages max out at 200 inches (14400 pt); scale huge drawings down to fit.
        val fit = min(1f, 14400f / max(a.width, a.height))
        val pw = max(1, ceil(a.width * fit).toInt())
        val ph = max(1, ceil(a.height * fit).toInt())

        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
        val c = page.canvas
        c.scale(fit, fit)
        c.translate(-a.left, -a.top)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (s in strokes) {
            paint.color = s.color
            paint.strokeWidth = s.width
            c.drawPath(s.path.asAndroidPath(), paint)
        }
        doc.finishPage(page)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}
