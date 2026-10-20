package com.jw6ventures.headlink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * P10's drawing (docs/p10-plan.md §5): the header's weather item, the tapped weather view
 * and the alert view in the flex tile, and the twelve condition glyphs they share. The
 * glyphs are drawn from shapes, as the maneuver icons are, so none can fall back to a
 * missing font glyph. [DashboardDrawing] calls in; everything drawn comes from the
 * snapshot's [WeatherHeader], [WeatherPanel] and [WeatherAlertCard].
 */
object WeatherDrawing {
    private const val CORNER = 10f

    /**
     * The weather view's hour row, as a share of the view's height (P10 lab §4.2), and the
     * larger share it takes when any hour has a rain line under its temperature.
     */
    private const val ROW_SHARE = 0.3f
    private const val ROW_SHARE_RAIN = 0.36f

    /** Sun colours: a darker amber on the day tiles, where a bright yellow washes out. */
    private const val SUN_DAY = 0xFFD98200.toInt()
    private const val SUN_NIGHT = 0xFFFFC94D.toInt()

    /**
     * The weather item, left of Ⓟ: glyph then temperature, on [baseline] with the clock. With
     * no reading, only the dimmed "—" where the temperature goes.
     */
    fun drawHeaderItem(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        item: WeatherHeader,
        baseline: Float,
        palette: DashboardPalette,
    ) {
        val box = layout.headerWeather
        var ground = palette.header
        if (item.pressed) {
            ground = palette.tilePressed
            paint.color = ground
            val inset = layout.margin / 2f
            canvas.drawRoundRect(box.x + inset, box.y + inset, box.right - inset, box.bottom - inset, CORNER, CORNER, paint)
        }
        val side = layout.headerWeatherGlyph.toFloat()
        // Half a margin each side of the glyph ([DashboardLayout.headerWeatherText]).
        val cx = box.x + layout.margin / 2f + side / 2f
        val cy = box.centerY.toFloat()
        // With no reading there is no glyph: the dimmed "—" where the temperature goes says
        // it alone (plan §5.1), rather than a second dash in the glyph's place.
        item.code?.let { glyph(canvas, paint, it, item.night, cx, cy, side, ground, palette, item.dim) }
        item.alertColour?.let { warningMark(canvas, paint, cx + side * 0.34f, cy - side * 0.5f, side * 0.42f, it, ground) }
        paint.style = Paint.Style.FILL
        paint.color = if (item.dim) palette.dim else palette.text
        paint.textSize = layout.headerWeatherTextSize
        val x = box.x + layout.margin / 2f + side + layout.margin / 2f
        canvas.drawText(fit(paint, item.temperature, box.right - x), x, baseline, paint)
    }

    /**
     * The tapped view (plan §5.2), in [box], the flex tile under its label: an alert strip
     * if one is in force, the glyph and the temperature large, the condition (left out
     * under a strip, where the glyph says it) and the feels-like, high and low line, a row
     * of the next four hours, and the place and age along the bottom. With no reading, the
     * status line alone.
     *
     * The bottom is laid out first and the "Now" block takes what is left: the hour row
     * has a fixed share of the height ([ROW_SHARE], or [ROW_SHARE_RAIN] with a rain line),
     * so its text stays legible (about 10-12 px at 800x480) however much else is shown, and
     * the place line gets a second line rather than losing "old" or the distance.
     */
    fun drawView(canvas: Canvas, paint: Paint, layout: DashboardLayout, box: Box, view: WeatherPanel, palette: DashboardPalette) {
        if (box.width <= 0 || box.height <= 0) return
        val ground = if (view.pressed) palette.tilePressed else palette.tile
        if (view.pressed) {
            paint.style = Paint.Style.FILL
            paint.color = ground
            canvas.drawRoundRect(box.x.toFloat(), box.y.toFloat(), box.right.toFloat(), box.bottom.toFloat(), CORNER, CORNER, paint)
        }
        val status = view.status
        if (status != null) {
            centredLines(canvas, paint, box, status, layout.emptyFavoritesTextSize, palette.dim)
            return
        }
        val h = box.height.toFloat()
        val w = box.width.toFloat()
        val x = box.x.toFloat()
        val main = if (view.dim) palette.dim else palette.text
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.DEFAULT

        // The place and age along the bottom, on two lines if one would cut it.
        val place = view.place?.let { placeLayout(paint, it, w, h * 0.08f, twoLines = true) }
        val placeTop = if (place == null) box.bottom.toFloat() else box.bottom - place.second * (place.first.size * 1.2f + 0.1f)
        place?.let { (lines, size) -> drawPlace(canvas, paint, lines, size, x, box.bottom.toFloat(), palette.dim) }

        // The hours: a fixed share of the height, just above the place line.
        val rowBottom = placeTop - h * 0.02f
        val rowTop = rowBottom - h * (if (view.hours.any { it.rain != null }) ROW_SHARE_RAIN else ROW_SHARE)
        if (view.hours.isNotEmpty()) drawHours(canvas, paint, view, x, w, rowTop, rowBottom, ground, main, palette)

        // The strip along the top.
        var y = box.y.toFloat()
        val strip = view.strip
        if (strip != null) {
            val sh = h * 0.12f
            drawStrip(canvas, paint, x, y, w, sh, strip)
            y += sh + h * 0.025f
        }

        // The lines under the "Now" block. Under a strip the condition text goes: the glyph
        // says it, and the row keeps its room.
        val lines = listOfNotNull(
            view.text?.takeIf { strip == null }?.let { it to main },
            view.detail?.let { it to palette.dim },
        )
        val lineSize = h * 0.085f
        val lineStep = lineSize * 1.2f
        // The "Now" block takes what is left, up to a third of the view: glyph and temperature.
        val nowH = (rowTop - h * 0.03f - lines.size * lineStep - y).coerceIn(0f, h * 0.34f)
        if (nowH > 4f) {
            glyph(canvas, paint, view.code, view.night, x + nowH / 2f, y + nowH / 2f, nowH, ground, palette, view.dim)
            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = main
            val tx = x + nowH + layout.margin
            paint.textSize = nowH * 0.78f
            val width = paint.measureText(view.temperature)
            if (width > x + w - tx) paint.textSize = paint.textSize * (x + w - tx) / width * 0.97f
            canvas.drawText(view.temperature, tx, y + nowH * 0.5f + paint.textSize * 0.36f, paint)
            paint.typeface = Typeface.DEFAULT
            y += nowH
        }
        paint.textSize = lineSize
        for ((text, colour) in lines) {
            y += lineStep
            paint.color = colour
            canvas.drawText(fit(paint, text, w), x, y - lineSize * 0.2f, paint)
        }
    }

    /**
     * The next hours in equal cells across [w], between [top] and [bottom]: the hour over
     * the glyph over the temperature, and under that the rain percentage, in the accent
     * colour, when any hour has one. The rain has a line of its own: beside the temperature
     * it ran into the next cell's, and which hour it belonged to was lost.
     */
    private fun drawHours(
        canvas: Canvas,
        paint: Paint,
        view: WeatherPanel,
        x: Float,
        w: Float,
        top: Float,
        bottom: Float,
        ground: Int,
        main: Int,
        palette: DashboardPalette,
    ) {
        val rowH = bottom - top
        if (rowH <= 8f) return
        val rainLine = view.hours.any { it.rain != null }
        val cellW = w / WeatherRules.VIEW_HOURS
        val hourSize = rowH * (if (rainLine) 0.2f else 0.24f)
        val tempSize = rowH * (if (rainLine) 0.23f else 0.27f)
        val rainSize = rowH * 0.18f
        val rainH = if (rainLine) rainSize * 1.1f else 0f
        val tempBaseline = bottom - rainH - tempSize * 0.22f
        val glyphTop = top + hourSize * 1.2f
        val glyphBottom = tempBaseline - tempSize * 0.98f
        val glyphSide = minOf(cellW * 0.7f, glyphBottom - glyphTop)
        paint.textAlign = Paint.Align.CENTER
        view.hours.forEachIndexed { index, cell ->
            val cx = x + cellW * (index + 0.5f)
            paint.style = Paint.Style.FILL
            paint.color = palette.dim
            paint.textSize = hourSize
            canvas.drawText(fit(paint, cell.hour, cellW), cx, top + hourSize * 0.95f, paint)
            if (glyphSide > 2f) {
                glyph(canvas, paint, cell.code, cell.night, cx, (glyphTop + glyphBottom) / 2f, glyphSide, ground, palette, view.dim)
            }
            paint.style = Paint.Style.FILL
            paint.color = main
            paint.textSize = tempSize
            canvas.drawText(fit(paint, cell.temperature, cellW), cx, tempBaseline, paint)
            cell.rain?.let {
                paint.color = palette.accent
                paint.textSize = rainSize
                canvas.drawText(fit(paint, it, cellW), cx, bottom - rainSize * 0.2f, paint)
            }
        }
        paint.textAlign = Paint.Align.LEFT
    }

    /**
     * The place line laid out for [width]: whole at [size] if it fits, else at no less than
     * 0.85 of it, else (with [twoLines]) broken at a [WeatherText.PLACE_SEPARATOR] into two
     * lines at [size], else cut. Its flags lead ([WeatherText.placeLine]), so a cut loses the
     * place's tail or the age, not "old". Returns the lines and their text size.
     */
    private fun placeLayout(paint: Paint, text: String, width: Float, size: Float, twoLines: Boolean): Pair<List<String>, Float> {
        paint.typeface = Typeface.DEFAULT
        paint.textSize = size
        if (paint.measureText(text) <= width) return listOf(text) to size
        val small = size * 0.85f
        paint.textSize = small
        if (paint.measureText(text) <= width || !twoLines) return listOf(fit(paint, text, width)) to small
        paint.textSize = size
        val parts = text.split(WeatherText.PLACE_SEPARATOR)
        var first = 1
        while (first < parts.size && paint.measureText(parts.subList(0, first + 1).joinToString(WeatherText.PLACE_SEPARATOR)) <= width) first++
        val lines = if (first < parts.size) {
            listOf(parts.subList(0, first), parts.subList(first, parts.size)).map { fit(paint, it.joinToString(WeatherText.PLACE_SEPARATOR), width) }
        } else {
            wrap(paint, text, width, 2)
        }
        return lines to size
    }

    /** [lines] of the place line in [colour], the last one's baseline just above [bottom]. */
    private fun drawPlace(canvas: Canvas, paint: Paint, lines: List<String>, size: Float, x: Float, bottom: Float, colour: Int) {
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.DEFAULT
        paint.color = colour
        paint.textSize = size
        var y = bottom - size * 0.3f - size * 1.2f * (lines.size - 1)
        for (line in lines) {
            canvas.drawText(line, x, y, paint)
            y += size * 1.2f
        }
    }

    /**
     * The alert view (plan §5.3): the alert's colour as a bar down the left, the headline
     * wrapped to at most three lines, "until 21:45" and "+1 more", and the place and age.
     */
    fun drawAlert(canvas: Canvas, paint: Paint, layout: DashboardLayout, box: Box, alert: WeatherAlertCard, palette: DashboardPalette) {
        if (box.width <= 0 || box.height <= 0) return
        paint.style = Paint.Style.FILL
        if (alert.pressed) {
            paint.color = palette.tilePressed
            canvas.drawRoundRect(box.x.toFloat(), box.y.toFloat(), box.right.toFloat(), box.bottom.toFloat(), CORNER, CORNER, paint)
        }
        val h = box.height.toFloat()
        val bar = maxOf(4f, box.width * 0.05f)
        paint.color = alert.colour ?: WeatherDisplay.DEFAULT_ALERT_COLOUR
        canvas.drawRoundRect(box.x.toFloat(), box.y.toFloat(), box.x + bar, box.bottom.toFloat(), bar / 2f, bar / 2f, paint)
        val x = box.x + bar + layout.margin
        val w = box.right - x
        val size = h * 0.13f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = palette.text
        paint.textSize = size
        var y = box.y + size
        for (line in wrap(paint, alert.headline, w, 3)) {
            canvas.drawText(line, x, y, paint)
            y += size * 1.2f
        }
        paint.typeface = Typeface.DEFAULT
        val small = h * 0.1f
        paint.textSize = small
        val under = listOfNotNull(alert.until, if (alert.more > 0) "+${alert.more} more" else null).joinToString(" · ")
        if (under.isNotEmpty()) {
            paint.color = palette.dim
            canvas.drawText(fit(paint, under, w), x, y + small * 0.2f, paint)
        }
        val (place, placeSize) = placeLayout(paint, alert.place, w, h * 0.085f, twoLines = false)
        drawPlace(canvas, paint, place, placeSize, x, box.bottom.toFloat(), palette.dim)
    }

    /** The view's strip: the alert's colour, its headline in black or white, whichever reads. */
    private fun drawStrip(canvas: Canvas, paint: Paint, x: Float, y: Float, w: Float, h: Float, strip: WeatherStrip) {
        val colour = strip.colour ?: WeatherDisplay.DEFAULT_ALERT_COLOUR
        paint.style = Paint.Style.FILL
        paint.color = colour
        canvas.drawRoundRect(x, y, x + w, y + h, h / 4f, h / 4f, paint)
        paint.color = onColour(colour)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = h * 0.62f
        val pad = h * 0.3f
        canvas.drawText(fit(paint, strip.headline, w - 2 * pad), x + pad, y + h * 0.5f + paint.textSize * 0.36f, paint)
        paint.typeface = Typeface.DEFAULT
    }

    /** Black or white, whichever contrasts more with [colour]. */
    private fun onColour(colour: Int): Int {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        return if (DashboardPalette.contrast(colour, black) >= DashboardPalette.contrast(colour, white)) black else white
    }

    /**
     * A condition glyph centred on ([cx], [cy]) in a square of [s]. [ground] is what it sits
     * on, for the moon's shadow and the gaps between shapes. Null draws a dim dash: no code.
     */
    fun glyph(
        canvas: Canvas,
        paint: Paint,
        code: WeatherCode?,
        night: Boolean,
        cx: Float,
        cy: Float,
        s: Float,
        ground: Int,
        palette: DashboardPalette,
        dim: Boolean,
    ) {
        val sunColour = if (palette === DashboardPalette.NIGHT) SUN_NIGHT else SUN_DAY
        val cloudColour = palette.dim
        val front = if (dim) palette.dim else palette.text
        val water = palette.accent
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.ROUND
        when (code) {
            null -> {
                paint.color = palette.dim
                paint.strokeWidth = maxOf(1.5f, s * 0.08f)
                canvas.drawLine(cx - s * 0.2f, cy, cx + s * 0.2f, cy, paint)
            }
            WeatherCode.CLEAR -> if (night) moon(canvas, paint, cx, cy, s * 0.36f, front, ground) else sun(canvas, paint, cx, cy, s * 0.42f, sunColour)
            WeatherCode.PARTLY_CLOUDY -> {
                if (night) moon(canvas, paint, cx + s * 0.14f, cy - s * 0.14f, s * 0.24f, front, ground)
                else sun(canvas, paint, cx + s * 0.14f, cy - s * 0.14f, s * 0.3f, sunColour)
                cloud(canvas, paint, cx - s * 0.06f, cy + s * 0.1f, s * 0.72f, cloudColour, ground)
            }
            WeatherCode.CLOUDY -> {
                cloud(canvas, paint, cx + s * 0.12f, cy - s * 0.1f, s * 0.56f, palette.dim, ground)
                cloud(canvas, paint, cx - s * 0.06f, cy + s * 0.08f, s * 0.76f, front, ground)
            }
            WeatherCode.RAIN -> {
                cloud(canvas, paint, cx, cy - s * 0.12f, s * 0.8f, cloudColour, ground)
                drops(canvas, paint, cx, cy + s * 0.22f, s, water, 3)
            }
            WeatherCode.SNOW -> {
                cloud(canvas, paint, cx, cy - s * 0.12f, s * 0.8f, cloudColour, ground)
                flakes(canvas, paint, cx, cy + s * 0.26f, s, front)
            }
            WeatherCode.SLEET -> {
                cloud(canvas, paint, cx, cy - s * 0.12f, s * 0.8f, cloudColour, ground)
                drops(canvas, paint, cx - s * 0.12f, cy + s * 0.22f, s, water, 1)
                paint.color = front
                canvas.drawCircle(cx + s * 0.02f, cy + s * 0.3f, s * 0.05f, paint)
                drops(canvas, paint, cx + s * 0.16f, cy + s * 0.22f, s, water, 1)
            }
            WeatherCode.HAIL -> {
                cloud(canvas, paint, cx, cy - s * 0.12f, s * 0.8f, cloudColour, ground)
                paint.color = front
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(1f, s * 0.04f)
                for (i in -1..1) canvas.drawCircle(cx + i * s * 0.2f, cy + s * (0.26f + 0.06f * (i and 1)), s * 0.06f, paint)
                paint.style = Paint.Style.FILL
            }
            WeatherCode.THUNDER -> {
                cloud(canvas, paint, cx, cy - s * 0.12f, s * 0.8f, cloudColour, ground)
                bolt(canvas, paint, cx, cy + s * 0.2f, s * 0.34f, palette.warning)
            }
            WeatherCode.THUNDERSTORM -> {
                cloud(canvas, paint, cx, cy - s * 0.12f, s * 0.8f, cloudColour, ground)
                bolt(canvas, paint, cx + s * 0.02f, cy + s * 0.2f, s * 0.34f, palette.warning)
                drops(canvas, paint, cx - s * 0.24f, cy + s * 0.22f, s, water, 1)
                drops(canvas, paint, cx + s * 0.26f, cy + s * 0.22f, s, water, 1)
            }
            WeatherCode.WIND -> {
                paint.color = front
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(1.5f, s * 0.07f)
                for ((i, length) in floatArrayOf(0.7f, 0.85f, 0.55f).withIndex()) {
                    val y = cy + (i - 1) * s * 0.24f
                    val left = cx - s * 0.42f
                    val right = left + s * length
                    canvas.drawLine(left, y, right - s * 0.08f, y, paint)
                    canvas.drawArc(RectF(right - s * 0.16f, y - s * 0.16f, right, y), 90f, -200f, false, paint)
                }
                paint.style = Paint.Style.FILL
            }
            WeatherCode.FOG, WeatherCode.HAZE -> {
                if (code == WeatherCode.HAZE) sun(canvas, paint, cx, cy - s * 0.12f, s * 0.34f, sunColour)
                else cloud(canvas, paint, cx, cy - s * 0.14f, s * 0.7f, cloudColour, ground)
                paint.color = front
                paint.strokeWidth = maxOf(1.5f, s * 0.07f)
                for (i in 0..2) {
                    val y = cy + s * (0.1f + i * 0.14f)
                    val inset = if (i == 1) s * 0.08f else 0f
                    canvas.drawLine(cx - s * 0.4f + inset, y, cx + s * 0.4f - inset, y, paint)
                }
            }
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun sun(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float, colour: Int) {
        paint.style = Paint.Style.FILL
        paint.color = colour
        canvas.drawCircle(cx, cy, r * 0.5f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1.5f, r / 7f)
        for (ray in 0 until 8) {
            val angle = Math.toRadians(ray * 45.0)
            val sin = Math.sin(angle).toFloat()
            val cos = Math.cos(angle).toFloat()
            canvas.drawLine(cx + sin * r * 0.68f, cy - cos * r * 0.68f, cx + sin * r * 0.95f, cy - cos * r * 0.95f, paint)
        }
        paint.style = Paint.Style.FILL
    }

    /** A crescent: a disc with an offset disc of the ground over it, as ☾ is drawn. */
    private fun moon(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float, colour: Int, ground: Int) {
        paint.style = Paint.Style.FILL
        paint.color = colour
        canvas.drawCircle(cx, cy, r, paint)
        paint.color = ground
        canvas.drawCircle(cx + r * 0.48f, cy - r * 0.35f, r * 0.85f, paint)
    }

    /**
     * A cloud [w] wide centred on ([cx], [cy]): three discs on a flat base, drawn first a
     * little larger in the ground colour, so it stands clear of the sun or cloud behind it.
     */
    private fun cloud(canvas: Canvas, paint: Paint, cx: Float, cy: Float, w: Float, colour: Int, ground: Int) {
        paint.style = Paint.Style.FILL
        for ((c, e) in listOf(ground to w * 0.05f, colour to 0f)) {
            paint.color = c
            canvas.drawRoundRect(cx - w * 0.46f - e, cy - e, cx + w * 0.46f + e, cy + w * 0.24f + e, w * 0.12f + e, w * 0.12f + e, paint)
            canvas.drawCircle(cx - w * 0.22f, cy + w * 0.02f, w * 0.2f + e, paint)
            canvas.drawCircle(cx + w * 0.04f, cy - w * 0.06f, w * 0.27f + e, paint)
            canvas.drawCircle(cx + w * 0.27f, cy + w * 0.04f, w * 0.18f + e, paint)
        }
    }

    private fun drops(canvas: Canvas, paint: Paint, cx: Float, top: Float, s: Float, colour: Int, count: Int) {
        paint.color = colour
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1.5f, s * 0.07f)
        val step = s * 0.2f
        for (i in 0 until count) {
            val x = cx + (i - (count - 1) / 2f) * step
            canvas.drawLine(x + s * 0.05f, top, x - s * 0.04f, top + s * 0.18f, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun flakes(canvas: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, colour: Int) {
        paint.color = colour
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, s * 0.045f)
        val r = s * 0.07f
        for (i in -1..1) {
            val x = cx + i * s * 0.22f
            val y = cy + (if (i == 0) s * 0.06f else 0f)
            for (k in 0 until 3) {
                val a = Math.toRadians(k * 60.0)
                val dx = (Math.cos(a) * r).toFloat()
                val dy = (Math.sin(a) * r).toFloat()
                canvas.drawLine(x - dx, y - dy, x + dx, y + dy, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    private fun bolt(canvas: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, colour: Int) {
        paint.style = Paint.Style.FILL
        paint.color = colour
        canvas.drawPath(
            Path().apply {
                moveTo(cx + s * 0.1f, cy - s * 0.5f)
                lineTo(cx - s * 0.3f, cy + s * 0.08f)
                lineTo(cx - s * 0.02f, cy + s * 0.08f)
                lineTo(cx - s * 0.12f, cy + s * 0.5f)
                lineTo(cx + s * 0.3f, cy - s * 0.1f)
                lineTo(cx + s * 0.02f, cy - s * 0.1f)
                close()
            },
            paint,
        )
    }

    /**
     * The voice toggle's small warning triangle (P8 plan §6), here in the alert's own colour,
     * with its top at ([cx], [top]) and [t] across.
     */
    private fun warningMark(canvas: Canvas, paint: Paint, cx: Float, top: Float, t: Float, colour: Int, ground: Int) {
        paint.style = Paint.Style.FILL
        val outline = Path().apply {
            moveTo(cx, top)
            lineTo(cx + t / 2f, top + t * 0.87f)
            lineTo(cx - t / 2f, top + t * 0.87f)
            close()
        }
        paint.color = ground
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1.5f, t * 0.14f)
        canvas.drawPath(outline, paint)
        paint.style = Paint.Style.FILL
        paint.color = colour
        canvas.drawPath(outline, paint)
        paint.color = onColour(colour)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, t * 0.12f)
        canvas.drawLine(cx, top + t * 0.3f, cx, top + t * 0.58f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, top + t * 0.72f, maxOf(0.75f, t * 0.06f), paint)
    }

    /** [text] centred in [box] in up to two lines, brought down to fit rather than cut. */
    private fun centredLines(canvas: Canvas, paint: Paint, box: Box, text: String, size: Float, colour: Int) {
        paint.color = colour
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        val lines = if (paint.measureText(text) <= box.width) listOf(text) else wrap(paint, text, box.width.toFloat(), 2)
        val widest = lines.maxOf { paint.measureText(it) }
        if (widest > box.width) paint.textSize = paint.textSize * box.width / widest * 0.95f
        val step = paint.textSize * 1.25f
        var y = box.centerY - step * (lines.size - 1) / 2f + paint.textSize * 0.36f
        for (line in lines) {
            canvas.drawText(line, box.centerX.toFloat(), y, paint)
            y += step
        }
        paint.textAlign = Paint.Align.LEFT
    }

    /** [text] broken at spaces into at most [max] lines of [width]; the last is cut with "…". */
    private fun wrap(paint: Paint, text: String, width: Float, max: Int): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        val words = text.split(' ').filter { it.isNotEmpty() }
        var index = 0
        while (index < words.size) {
            val candidate = if (line.isEmpty()) words[index] else "$line ${words[index]}"
            if (paint.measureText(candidate) <= width || line.isEmpty()) {
                line = candidate
                index++
            } else {
                lines += line
                line = ""
                if (lines.size == max) break
            }
        }
        if (lines.size < max && line.isNotEmpty()) {
            lines += line
            line = ""
        }
        if (index < words.size || line.isNotEmpty()) {
            val last = lines.removeAt(lines.size - 1)
            lines += fit(paint, "$last …", width).let { if (it.endsWith("…")) it else "$it…" }
        }
        return lines.map { fit(paint, it, width) }
    }

    /** [text] cut with an ellipsis to fit [width], or whole if it fits. */
    private fun fit(paint: Paint, text: String, width: Float): String {
        if (width <= 0f) return ""
        if (paint.measureText(text) <= width) return text
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText("…") > width) end--
        return text.substring(0, end).trimEnd() + "…"
    }
}
