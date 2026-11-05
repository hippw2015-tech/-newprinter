package com.example.innerprinter

import android.graphics.*
import android.text.TextPaint

/** يرسم رقم التغذية بخط عريض وكبير، كل سطر مجموعتان فقط لتكبير الأرقام */
object TokenRender {

    /** يحوّل النص المُدخل إلى أرقام تغذية: كل سطر فيه 16-20 رقمًا يُقسم مجموعات من 4 */
    fun parse(text: String): List<String> =
        text.lines().mapNotNull { line ->
            val d = line.filter { it.isDigit() }
            if (d.length in 16..20 && d.any { it != '0' }) d.chunked(4).joinToString("-") else null
        }

    fun render(tokens: List<String>, widthDots: Int): Bitmap {
        val pad = 12
        val avail = widthDots - pad * 2 - 8
        val rowsPerToken = tokens.map { t -> t.split("-").chunked(2).map { it.joinToString("-") } }
        val widest = rowsPerToken.flatten().maxByOrNull { it.length } ?: "0000-0000"

        val digit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        var size = 120f
        digit.textSize = size
        while (digit.measureText(widest) > avail && size > 20f) { size -= 2f; digit.textSize = size }
        val lineH = size * 1.3f

        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 30f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        val titleH = 46f
        val gap = 18f

        var height = pad + titleH
        for (rows in rowsPerToken) height += rows.size * lineH + gap
        height += pad

        val bmp = Bitmap.createBitmap(widthDots, height.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK }
        c.drawRect(4f, 4f, widthDots - 4f, height - 4f, box)

        val cx = widthDots / 2f
        var y = pad + 34f
        c.drawText("رقم التغذية", cx, y, title)
        y += titleH - 34f + 8f

        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.BLACK
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        for ((i, rows) in rowsPerToken.withIndex()) {
            for (r in rows) {
                y += lineH
                c.drawText(r, cx, y - size * 0.25f, digit)
            }
            if (i < rowsPerToken.size - 1) c.drawLine(pad.toFloat(), y + 8f, widthDots - pad.toFloat(), y + 8f, dash)
            y += gap
        }
        return bmp
    }
}
