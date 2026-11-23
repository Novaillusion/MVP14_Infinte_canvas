package com.example.infinitecanvas

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.graphics.asAndroidPath
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Vector PDF export using Android's built-in PdfDocument. */
object AndroidPdf {
    fun export(strokes: List<InkStroke>): ByteArray {
        val a = VectorExport.area(strokes)
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
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (s in VectorExport.paintOrder(strokes)) {
            paint.color = s.color
            paint.style = if (s.filled) Paint.Style.FILL else Paint.Style.STROKE
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
