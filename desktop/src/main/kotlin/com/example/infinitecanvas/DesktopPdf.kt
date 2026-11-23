package com.example.infinitecanvas

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Vector PDF export with Apache PDFBox (same look as the Android export). */
object DesktopPdf {
    fun export(strokes: List<InkStroke>): ByteArray {
        val a = VectorExport.area(strokes)
        val fit = min(1f, 14400f / max(a.width, a.height)) // PDF max page size is 200 in
        val pw = max(1f, ceil(a.width * fit))
        val ph = max(1f, ceil(a.height * fit))

        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(pw, ph))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                // PDF's origin is bottom-left with y up; flip to the canvas's top-left, y down.
                cs.transform(Matrix(fit, 0f, 0f, -fit, 0f, ph))
                cs.transform(Matrix(1f, 0f, 0f, 1f, -a.left, -a.top))
                cs.setLineCapStyle(1)  // round
                cs.setLineJoinStyle(1) // round
                for (s in VectorExport.paintOrder(strokes)) {
                    val r = ((s.color shr 16) and 0xFF) / 255f
                    val g = ((s.color shr 8) and 0xFF) / 255f
                    val b = (s.color and 0xFF) / 255f
                    val gs = PDExtendedGraphicsState()
                    val alpha = VectorExport.alphaOf(s.color)
                    gs.setNonStrokingAlphaConstant(alpha)
                    gs.setStrokingAlphaConstant(alpha)
                    cs.setGraphicsStateParameters(gs)

                    var cx = 0f
                    var cy = 0f
                    for (c in pathCommands(s.points)) when (c) {
                        is PathCmd.Move -> { cs.moveTo(c.x, c.y); cx = c.x; cy = c.y }
                        is PathCmd.Line -> { cs.lineTo(c.x, c.y); cx = c.x; cy = c.y }
                        is PathCmd.Quad -> {
                            // quadratic -> cubic Bézier
                            val c1x = cx + 2f / 3f * (c.cx - cx)
                            val c1y = cy + 2f / 3f * (c.cy - cy)
                            val c2x = c.x + 2f / 3f * (c.cx - c.x)
                            val c2y = c.y + 2f / 3f * (c.cy - c.y)
                            cs.curveTo(c1x, c1y, c2x, c2y, c.x, c.y)
                            cx = c.x; cy = c.y
                        }
                    }
                    if (s.filled) {
                        cs.closePath()
                        cs.setNonStrokingColor(r, g, b)
                        cs.fill()
                    } else {
                        cs.setStrokingColor(r, g, b)
                        cs.setLineWidth(s.width)
                        cs.stroke()
                    }
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }
}
