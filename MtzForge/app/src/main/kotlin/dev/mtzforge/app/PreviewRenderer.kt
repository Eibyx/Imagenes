package dev.mtzforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/** Genera las imágenes preview/ del MTZ (diseño propio). */
object PreviewRenderer {
    private const val SAMPLE = "Aa Bb Cc 0123456789"
    private const val PANGRAM = "El veloz murciélago hindú comía feliz cardillo y kiwi."

    /** Miniatura horizontal con el nombre de la fuente (PNG con fondo transparente). */
    fun fontThumbnail(name: String, typeface: Typeface): ByteArray {
        val bmp = Bitmap.createBitmap(480, 80, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; this.typeface = typeface; textAlign = Paint.Align.CENTER }
        fitText(p, name, bmp.width - 24f, 54f)
        c.drawText(name, bmp.width / 2f, bmp.height / 2f - (p.ascent() + p.descent()) / 2f, p)
        return bmp.encode(Bitmap.CompressFormat.PNG)
    }

    /** Vista previa vertical 720x1280 (JPEG) con muestras de texto. */
    fun fontPreview(name: String, typeface: Typeface): ByteArray {
        val w = 720; val h = 1280
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.rgb(24, 32, 56), Color.rgb(64, 36, 96), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) }
        c.drawRoundRect(RectF(40f, 160f, w - 40f, h - 160f), 48f, 48f, card)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; this.typeface = typeface; textAlign = Paint.Align.CENTER }
        fitText(p, name, w - 120f, 72f)
        c.drawText(name, w / 2f, 320f, p)
        p.textSize = 150f
        c.drawText("Aa", w / 2f, 600f, p)
        fitText(p, SAMPLE, w - 120f, 48f)
        c.drawText(SAMPLE, w / 2f, 760f, p)
        p.textAlign = Paint.Align.LEFT
        p.textSize = 34f
        var y = 880f
        wrap(p, PANGRAM, w - 140f).forEach { line -> c.drawText(line, 70f, y, p); y += 48f }
        return bmp.encode(Bitmap.CompressFormat.JPEG)
    }

    /** Cuadrícula de iconos para preview/preview_icons_0.png. */
    fun iconGrid(icons: List<Bitmap>, title: String): ByteArray {
        val w = 720; val h = 1280; val cols = 4; val cell = 150; val icon = 112
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(16, 64, 72), Color.rgb(28, 30, 60), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        fitText(tp, title, w - 80f, 52f)
        c.drawText(title, w / 2f, 130f, tp)
        val left = (w - cols * cell) / 2
        icons.take(24).forEachIndexed { i, b ->
            val x = left + (i % cols) * cell + (cell - icon) / 2
            val y = 200 + (i / cols) * (cell + 20)
            c.drawBitmap(b, null, RectF(x.toFloat(), y.toFloat(), (x + icon).toFloat(), (y + icon).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return bmp.encode(Bitmap.CompressFormat.PNG)
    }

    /** Igual que [iconGrid] pero en JPEG (preview/preview_icons_0.jpg, como los MTZ de referencia). */
    fun iconGridJpeg(icons: List<Bitmap>, title: String): ByteArray {
        val png = iconGrid(icons, title)
        val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        return bmp.encode(Bitmap.CompressFormat.JPEG, 88)
    }

    private fun fitText(p: Paint, text: String, maxWidth: Float, start: Float) {
        p.textSize = start
        while (p.textSize > 10f && p.measureText(text) > maxWidth) p.textSize -= 2f
    }

    private fun wrap(p: Paint, text: String, maxWidth: Float): List<String> {
        val lines = ArrayList<String>()
        var cur = ""
        for (word in text.split(' ')) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(next) > maxWidth && cur.isNotEmpty()) { lines += cur; cur = word } else cur = next
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    fun Bitmap.encode(format: Bitmap.CompressFormat, quality: Int = 92): ByteArray =
        ByteArrayOutputStream().also { compress(format, quality, it) }.toByteArray()
}
