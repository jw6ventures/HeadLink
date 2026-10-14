package org.openautoandroid.source

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * P11.5: the bitmaps VTM draws for the Gas, Food and Shop markers. A disc in the kind's
 * colour ([PoiRules.colour]) with a white glyph — a pump, a fork and knife, a bag — ringed
 * white inside a dark edge, so it reads on the night ground (the white ring) and the day
 * ground (the dark one) alike, and over a road of either theme.
 */
object PoiMarkerArt {
    fun draw(kind: PoiKind, size: Int): Bitmap {
        val s = maxOf(8, size)
        val bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val c = s / 2f
        val edge = maxOf(1f, s / 22f)
        paint.color = EDGE
        canvas.drawCircle(c, c, c, paint)
        paint.color = WHITE
        canvas.drawCircle(c, c, c - edge, paint)
        paint.color = PoiRules.colour(kind)
        canvas.drawCircle(c, c, c - edge * 2.2f, paint)
        paint.color = WHITE
        val u = s / 24f // glyph unit: the glyphs are drawn on a 24-unit square
        when (kind) {
            PoiKind.GAS -> {
                // The pump's body, its window cut out, and the hose to the right.
                canvas.drawRect(7.5f * u, 6.5f * u, 14f * u, 17.5f * u, paint)
                paint.color = PoiRules.colour(kind)
                canvas.drawRect(8.8f * u, 8f * u, 12.7f * u, 10.8f * u, paint)
                paint.color = WHITE
                canvas.drawRect(6.5f * u, 16.5f * u, 15f * u, 17.8f * u, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.3f * u
                val hose = Path().apply {
                    moveTo(14f * u, 9f * u)
                    lineTo(16.5f * u, 10.5f * u)
                    lineTo(16.5f * u, 15f * u)
                    lineTo(17.5f * u, 15f * u)
                    lineTo(17.5f * u, 9.5f * u)
                }
                canvas.drawPath(hose, paint)
                paint.style = Paint.Style.FILL
            }
            PoiKind.FOOD -> {
                // A fork (three tines over a handle) and a knife.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f * u
                for (x in listOf(7.5f, 9.5f, 11.5f)) canvas.drawLine(x * u, 6f * u, x * u, 10f * u, paint)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(6.9f * u, 9.5f * u, 12.1f * u, 11.5f * u), u, u, paint)
                canvas.drawRect(8.8f * u, 11f * u, 10.2f * u, 18f * u, paint)
                val knife = Path().apply {
                    moveTo(14.5f * u, 18f * u)
                    lineTo(14.5f * u, 6f * u)
                    quadTo(17.5f * u, 8f * u, 17f * u, 13f * u)
                    lineTo(15.8f * u, 13f * u)
                    lineTo(15.8f * u, 18f * u)
                    close()
                }
                canvas.drawPath(knife, paint)
            }
            PoiKind.SHOP -> {
                // A bag with a handle.
                canvas.drawRoundRect(RectF(6.5f * u, 9.5f * u, 17.5f * u, 18f * u), u, u, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.3f * u
                canvas.drawArc(RectF(9f * u, 6f * u, 15f * u, 12.5f * u), 180f, 180f, false, paint)
                paint.style = Paint.Style.FILL
            }
        }
        return bitmap
    }

    private const val EDGE = 0xFF101418.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
