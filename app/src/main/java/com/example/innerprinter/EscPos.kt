package com.example.innerprinter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.OutputStream

object EscPos {
    val INIT = byteArrayOf(0x1B, 0x40)
    fun feed(lines: Int) = byteArrayOf(0x1B, 0x64, lines.toByte())

    private fun lum(p: Int) = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)

    fun flatten(src: Bitmap): Bitmap {
        val f = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(f).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        return f
    }

    /** تصغير بجودة عالية (على مراحل) ليبقى النص الرفيع واضحًا */
    fun scaleToWidth(src: Bitmap, w: Int): Bitmap {
        var cur = src
        while (cur.width / 2 >= w) cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        val h = (cur.height * w.toFloat() / cur.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(cur, w, h, true)
    }

    /** قص الهوامش البيضاء ليستفيد الإيصال من كامل عرض الورق */
    fun trimWhite(src: Bitmap, limit: Int = 235, margin: Int = 6): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[y * w + x]
            if (Color.alpha(p) > 20 && lum(p) < limit) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return src
        val l = maxOf(0, minX - margin); val t = maxOf(0, minY - margin)
        val r = minOf(w, maxX + margin + 1); val b = minOf(h, maxY + margin + 1)
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    /** دمج عدة صور عموديًا في صورة واحدة بعرض الورق */
    fun stack(parts: List<Bitmap>, widthDots: Int, gap: Int = 16): Bitmap {
        val scaled = parts.map { scaleToWidth(flatten(it), widthDots) }
        val total = scaled.sumOf { it.height } + gap * (scaled.size - 1)
        val out = Bitmap.createBitmap(widthDots, total, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE)
        var y = 0
        for (b in scaled) { c.drawBitmap(b, 0f, y.toFloat(), null); y += b.height + gap }
        return out
    }

    /** يحوّل الصورة إلى أوامر GS v 0 على دفعات صغيرة (24 سطرًا) */
    fun rasterChunks(src: Bitmap, widthDots: Int, threshold: Int = 150, rowsPerChunk: Int = 24): List<ByteArray> {
        val scaled = scaleToWidth(flatten(src), widthDots)
        val h = scaled.height
        val pixels = IntArray(widthDots * h)
        scaled.getPixels(pixels, 0, widthDots, 0, 0, widthDots, h)

        val bytesPerRow = widthDots / 8
        val out = mutableListOf<ByteArray>()
        var y = 0
        while (y < h) {
            val rows = minOf(rowsPerChunk, h - y)
            val buf = ByteArray(8 + bytesPerRow * rows)
            buf[0] = 0x1D; buf[1] = 0x76; buf[2] = 0x30; buf[3] = 0
            buf[4] = (bytesPerRow and 0xFF).toByte(); buf[5] = (bytesPerRow shr 8).toByte()
            buf[6] = (rows and 0xFF).toByte();        buf[7] = (rows shr 8).toByte()
            for (r in 0 until rows) {
                for (bx in 0 until bytesPerRow) {
                    var b = 0
                    for (bit in 0 until 8) {
                        if (lum(pixels[(y + r) * widthDots + bx * 8 + bit]) < threshold) b = b or (0x80 shr bit)
                    }
                    buf[8 + r * bytesPerRow + bx] = b.toByte()
                }
            }
            out.add(buf)
            y += rows
        }
        return out
    }

    fun send(os: OutputStream, data: ByteArray, packet: Int, delayMs: Long) {
        var i = 0
        while (i < data.size) {
            val n = minOf(packet, data.size - i)
            os.write(data, i, n)
            os.flush()
            Thread.sleep(delayMs)
            i += n
        }
    }
}
