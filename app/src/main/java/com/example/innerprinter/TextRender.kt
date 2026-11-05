package com.example.innerprinter

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint

/** يرسم النص (عربي أو إنجليزي) كصورة، فيُطبع بشكل صحيح دون الحاجة لدعم الطابعة للعربية */
object TextRender {
    fun render(text: String, widthDots: Int, sizePx: Float = 26f, bold: Boolean = true): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = sizePx
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val pad = 8
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthDots - pad * 2)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setLineSpacing(4f, 1f)
            .build()
        val bmp = Bitmap.createBitmap(widthDots, layout.height + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.translate(pad.toFloat(), pad.toFloat())
        layout.draw(c)
        return bmp
    }
}
