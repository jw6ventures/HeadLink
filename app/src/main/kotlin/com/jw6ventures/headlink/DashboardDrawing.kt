package com.jw6ventures.headlink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale

/**
 * Paints the dashboard chrome — header, tiles, media controls, and the map pane's
 * marker and floating controls — into an ARGB bitmap whose map pane is otherwise left
 * fully transparent, so the composited map shows through it.
 *
 * Separated from EGL and from [Dashboard] so the drawing is easy to follow and the state
 * it draws stays testable without Android.
 */
object DashboardDrawing {

    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    fun draw(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        art: android.graphics.Bitmap? = null,
    ) {
        // The resolved palette (P7.6), not the head unit's word: ☀/☾ may be holding
        // day or night, and the map was themed from this same value.
        val palette = DashboardPalette.of(snapshot.night)

        canvas.drawColor(palette.background, PorterDuff.Mode.SRC)
        paint.style = Paint.Style.FILL

        drawHeader(canvas, paint, layout, snapshot, palette)
        drawMedia(canvas, paint, layout, snapshot, palette, art)
        drawTiles(canvas, paint, layout, snapshot, palette)
        // P7.9: over the media pane and both tiles, and not the map, which stays in view.
        if (snapshot.keypadOpen) drawKeypad(canvas, paint, layout, snapshot, palette)

        // Punched last so nothing drawn above can bleed into the pane, and so the map
        // texture underneath is what the head unit actually sees there.
        canvas.drawRect(
            layout.map.x.toFloat(), layout.map.y.toFloat(),
            layout.map.right.toFloat(), layout.map.bottom.toFloat(), clear,
        )
        drawMapOverlay(canvas, paint, layout, snapshot, palette)
    }

    /**
     * Clock, speed and compass from the left; the two header icons, the signal and the
     * battery from the right (P7.3, plan §5.1). The debug line under them is drawn only
     * with Diagnostics on.
     */
    private fun drawHeader(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        paint.color = palette.header
        canvas.drawRect(
            layout.x.toFloat(), layout.y.toFloat(),
            (layout.x + layout.width).toFloat(), (layout.y + layout.header).toFloat(), paint,
        )

        val big = layout.header * 0.52f
        val small = layout.header * 0.24f
        // With no debug line under it, the one line of text sits in the middle of the
        // header rather than leaving a gap where the second line used to be.
        val baseline = layout.y + layout.header * (if (snapshot.diagnostics) 0.62f else 0.68f)
        // P10: short of the weather item while it is shown, else as far as Ⓟ.
        // And short of the Home Assistant button while it is in the header.
        val textRight = layout.headerTextRight(snapshot.weatherHeader != null, snapshot.homeAssistant != null).toFloat()
        var cursor = (layout.x + layout.margin).toFloat()

        paint.color = palette.text
        paint.textSize = big
        canvas.drawText(snapshot.clock, cursor, baseline, paint)
        cursor += paint.measureText(snapshot.clock) + layout.margin * 2

        // Speed and bearing read from whichever source won, with the unit spelled out:
        // an unlabelled number on a dashboard is read as authoritative, and neither of
        // these has been confirmed against a moving vehicle. Which unit is the
        // operator's choice, and the snapshot carries it so this stays a drawing.
        val speed = snapshot.speedUnit.format(snapshot.speed)
        paint.textSize = big
        canvas.drawText(speed, cursor, baseline, paint)
        cursor += paint.measureText(speed) + layout.margin * 2

        // The needle, the point and the bearing are kept whatever else leaves the header
        // (James, plan §1 item 9): the one way to check the map's arrow at a glance.
        val bearing = snapshot.bearing
        if (bearing != null) {
            drawCompass(canvas, paint, cursor + big * 0.4f, baseline - big * 0.3f, big * 0.4f, bearing, palette)
            cursor += big
            paint.textSize = small
            paint.color = palette.text
            val text = "${compassPoint(bearing)} ${Math.round(bearing)}°"
            canvas.drawText(text, cursor, baseline, paint)
            cursor += paint.measureText(text) + layout.margin * 2
        }

        // The upcoming turn used to fill the rest of this line; since P7.4 it is on the
        // turn card over the map, and the room it leaves stays empty (plan §5.1).

        if (snapshot.diagnostics) {
            // The evidence a drive has to record, behind the Diagnostics switch (plan
            // §5.1, §6.1). "scale inferred" is back here, off the everyday header, for
            // any shown value whose divisor no drive has confirmed.
            paint.color = palette.dim
            paint.textSize = small
            val left = (layout.x + layout.margin).toFloat()
            val line = "gear ${snapshot.vehicle.gearName()}  ${snapshot.restriction}  " +
                "sats ${snapshot.vehicle.satellitesUsed ?: "-"}/${snapshot.vehicle.satelliteCount ?: "-"}  " +
                (if (snapshot.scaleInferred) "scale inferred  " else "") +
                "src ${snapshot.source.label}"
            canvas.drawText(fit(paint, line, textRight - left), left, baseline + small * 1.15f, paint)
        }

        drawHeaderIcons(canvas, paint, layout, snapshot, palette)
        snapshot.homeAssistant?.let {
            if (layout.homeAssistantInHeader) {
                val box = layout.headerHomeAssistant(snapshot.weatherHeader != null)
                drawHomeAssistant(canvas, paint, box, it, palette, header = true)
            }
        }
        snapshot.weatherHeader?.let { WeatherDrawing.drawHeaderItem(canvas, paint, layout, it, baseline, palette) }
        drawSignal(canvas, paint, layout, snapshot, palette)
        drawBattery(canvas, paint, layout, snapshot, palette)
    }

    /**
     * Ⓟ and ☀/☾, each centred in its header-height target, with the pressed one lit the
     * way a pressed tile is.
     */
    private fun drawHeaderIcons(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        DashboardLayout.HEADER_ACTIONS.forEachIndexed { index, action ->
            val box = layout.headerIcon(index)
            var ground = palette.header
            if (snapshot.pressedHeaderIcon == action) {
                ground = palette.tilePressed
                paint.color = ground
                val inset = layout.margin / 2f
                canvas.drawRoundRect(
                    box.x + inset, box.y + inset, box.right - inset, box.bottom - inset,
                    CORNER, CORNER, paint,
                )
            }
            val cx = box.centerX.toFloat()
            val cy = box.centerY.toFloat()
            val radius = box.height * 0.28f
            when (action) {
                "pause" -> drawPause(canvas, paint, cx, cy, radius, snapshot.notificationsPaused, palette)
                else -> drawDayNight(
                    canvas, paint, cx, cy, radius, snapshot.night,
                    snapshot.dayNight == DayNightMode.AUTO, ground, palette,
                )
            }
        }
    }

    /**
     * Ⓟ, the notification pause (plan §5.8): a thin grey ring while notifications are
     * allowed, and bold in the accent colour while they are held back, because a pause
     * left on is the mistake to make visible.
     */
    private fun drawPause(
        canvas: Canvas,
        paint: Paint,
        cx: Float,
        cy: Float,
        radius: Float,
        paused: Boolean,
        palette: DashboardPalette,
    ) {
        paint.color = if (paused) palette.accent else palette.dim
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (paused) maxOf(2f, radius / 4.5f) else maxOf(1f, radius / 12f)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.FILL
        val typeface = paint.typeface
        paint.typeface = if (paused) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = radius * 1.25f
        canvas.drawText("P", cx, cy + paint.textSize * 0.36f, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = typeface
    }

    /**
     * ☀ or ☾ for the palette actually in use, drawn rather than set in a font so it
     * cannot fall back to a missing glyph, with a small "A" while Automatic chose it.
     */
    private fun drawDayNight(
        canvas: Canvas,
        paint: Paint,
        cx: Float,
        cy: Float,
        radius: Float,
        night: Boolean,
        automatic: Boolean,
        ground: Int,
        palette: DashboardPalette,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = palette.text
        if (night) {
            // A crescent: a disc with an offset disc of the ground colour over it.
            canvas.drawCircle(cx, cy, radius * 0.8f, paint)
            paint.color = ground
            canvas.drawCircle(cx + radius * 0.38f, cy - radius * 0.28f, radius * 0.68f, paint)
        } else {
            canvas.drawCircle(cx, cy, radius * 0.42f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1.5f, radius / 7f)
            paint.strokeCap = Paint.Cap.ROUND
            for (ray in 0 until 8) {
                val angle = Math.toRadians(ray * 45.0)
                val sin = Math.sin(angle).toFloat()
                val cos = Math.cos(angle).toFloat()
                canvas.drawLine(
                    cx + sin * radius * 0.62f, cy - cos * radius * 0.62f,
                    cx + sin * radius * 0.92f, cy - cos * radius * 0.92f, paint,
                )
            }
            paint.strokeCap = Paint.Cap.BUTT
            paint.style = Paint.Style.FILL
        }
        if (automatic) {
            val typeface = paint.typeface
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = palette.accent
            paint.textSize = radius * 0.72f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("A", cx + radius * 0.95f, cy + radius * 1.05f, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = typeface
        }
    }

    /**
     * Four bars for the primary SIM and its network type beside them, `wifi` instead
     * while the phone is on wifi, and under the bars one row of four dots for each other
     * SIM (plan §5.2). Unlit bars and dots are drawn faint rather than left out, so four
     * bars always read as a scale; a row of hollow rings is a SIM with no service, and a
     * missing row is no SIM at all.
     */
    private fun drawSignal(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        val box = layout.headerSignal
        val h = layout.header.toFloat()
        val barsWidth = h * 0.42f
        val gap = barsWidth / 14f
        val barWidth = (barsWidth - 3 * gap) / 4f
        val bottom = box.y + h * 0.55f
        val tallest = h * 0.36f
        val faint = (palette.dim and 0x00FFFFFF) or 0x59000000
        val bars = snapshot.phone.signalBars
        paint.style = Paint.Style.FILL
        for (bar in 0 until 4) {
            val left = box.x + bar * (barWidth + gap)
            paint.color = if (bars > bar) palette.text else faint
            canvas.drawRect(left, bottom - tallest * (bar + 1) / 4f, left + barWidth, bottom, paint)
        }

        val label = when {
            snapshot.phone.network == "wifi" -> "wifi"
            bars < 0 -> "—"
            snapshot.phone.cellNetworkType == "-" -> ""
            else -> snapshot.phone.cellNetworkType
        }
        paint.color = palette.dim
        paint.textSize = h * 0.24f
        val labelX = box.x + barsWidth + h * 0.06f
        canvas.drawText(fit(paint, label, box.right - labelX), labelX, bottom, paint)

        val dot = h * 0.034f
        snapshot.phone.secondarySignalBars.take(SimSignal.MAX_SECONDARY).forEachIndexed { row, level ->
            val y = box.y + h * (0.68f + row * 0.1f)
            for (index in 0 until 4) {
                val x = box.x + barsWidth * (index + 0.5f) / 4f
                when {
                    level < 0 -> {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = maxOf(1f, dot / 3f)
                        paint.color = palette.dim
                        canvas.drawCircle(x, y, dot, paint)
                        paint.style = Paint.Style.FILL
                    }
                    else -> {
                        paint.color = if (level > index) palette.text else faint
                        canvas.drawCircle(x, y, dot, paint)
                    }
                }
            }
        }
    }

    /**
     * A battery glyph that fills with the level, a bolt over it while charging, and the
     * percentage. The warning colour at 15% or less when not charging, as before P7.3.
     */
    private fun drawBattery(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        val box = layout.headerBattery
        val h = layout.header.toFloat()
        val level = snapshot.phone.batteryPercent
        val charging = snapshot.phone.charging
        val colour = if (level in 0..15 && !charging) palette.warning else palette.text

        val bodyWidth = h * 0.42f
        val bodyHeight = h * 0.22f
        val left = box.x.toFloat()
        val cy = box.y + h * 0.45f
        val top = cy - bodyHeight / 2f
        val stroke = maxOf(1.5f, h / 50f)
        paint.color = colour
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        canvas.drawRoundRect(
            left + stroke / 2f, top, left + bodyWidth, top + bodyHeight, stroke * 1.5f, stroke * 1.5f, paint,
        )
        paint.style = Paint.Style.FILL
        // The terminal nub, so the glyph reads as a battery and not a box.
        val nub = bodyHeight * 0.45f
        canvas.drawRect(left + bodyWidth, cy - nub / 2f, left + bodyWidth + h * 0.04f, cy + nub / 2f, paint)
        if (level >= 0) {
            val inset = stroke * 1.8f
            val inner = bodyWidth - stroke / 2f - 2 * inset
            canvas.drawRect(
                left + stroke / 2f + inset, top + inset,
                left + stroke / 2f + inset + inner * level.coerceIn(0, 100) / 100f, top + bodyHeight - inset, paint,
            )
        }
        if (charging) {
            // In the accent colour with an edge of the header's, so it shows over a full
            // glyph and an empty one alike.
            val bx = left + bodyWidth / 2f
            val s = bodyHeight * 0.62f
            val bolt = Path().apply {
                moveTo(bx + s * 0.15f, cy - s)
                lineTo(bx - s * 0.45f, cy + s * 0.12f)
                lineTo(bx - s * 0.02f, cy + s * 0.12f)
                lineTo(bx - s * 0.15f, cy + s)
                lineTo(bx + s * 0.45f, cy - s * 0.12f)
                lineTo(bx + s * 0.02f, cy - s * 0.12f)
                close()
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = palette.header
            canvas.drawPath(bolt, paint)
            paint.style = Paint.Style.FILL
            paint.strokeJoin = Paint.Join.MITER
            paint.color = palette.accent
            canvas.drawPath(bolt, paint)
        }

        val text = if (level < 0) "—%" else "$level%"
        paint.color = colour
        paint.textSize = h * 0.28f
        val textX = left + bodyWidth + h * 0.1f
        canvas.drawText(fit(paint, text, box.right - textX), textX, box.y + h * 0.55f, paint)
    }

    /** A filled needle pointing along the bearing, north being up. */
    private fun drawCompass(
        canvas: Canvas,
        paint: Paint,
        cx: Float,
        cy: Float,
        radius: Float,
        bearing: Double,
        palette: DashboardPalette,
    ) {
        paint.color = palette.dim
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, radius / 8f)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.FILL

        val radians = Math.toRadians(bearing)
        val tipX = cx + (radius * 0.9f * Math.sin(radians)).toFloat()
        val tipY = cy - (radius * 0.9f * Math.cos(radians)).toFloat()
        val leftX = cx + (radius * 0.5f * Math.sin(radians + TAIL)).toFloat()
        val leftY = cy - (radius * 0.5f * Math.cos(radians + TAIL)).toFloat()
        val rightX = cx + (radius * 0.5f * Math.sin(radians - TAIL)).toFloat()
        val rightY = cy - (radius * 0.5f * Math.cos(radians - TAIL)).toFloat()
        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(leftX, leftY)
            lineTo(rightX, rightY)
            close()
        }
        paint.color = palette.accent
        canvas.drawPath(path, paint)
    }

    private fun drawTiles(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        layout.tiles.forEachIndexed { index, tile ->
            // The favorites tile is a list, not a button: its rows light up, not the tile.
            // Nor is the trip panel that takes its place; End route lights up on its own.
            // Nor the flex tile since P7.8: its rows and call buttons light on their own.
            val pressed = snapshot.pressedTile == index && index != DashboardLayout.FAVORITES_TILE &&
                index != DashboardLayout.FLEX_TILE
            paint.color = if (pressed) palette.tilePressed else palette.tile
            canvas.drawRoundRect(rect(tile), CORNER, CORNER, paint)
            val trip = snapshot.trip
            if (index == DashboardLayout.FAVORITES_TILE && trip != null) {
                // P7.4: a trip takes the favourites' tile, label and all, for as long as
                // the route exists (plan §5.4).
                drawTrip(canvas, paint, layout, trip, palette)
                return@forEachIndexed
            }
            when (index) {
                DashboardLayout.FAVORITES_TILE -> {
                    drawTileLabel(canvas, paint, layout, tile, DashboardLayout.TILE_LABELS[index], palette)
                    drawFavorites(canvas, paint, layout, snapshot, palette)
                }
                else -> {
                    // The flex tile's label follows what it shows; "Muted" is bold in the
                    // warning colour, because a forgotten mute is the mistake to catch.
                    // P10: "weather alert" the same way, since an alert is what it is there for.
                    val muted = snapshot.flex == FlexSource.CALL && snapshot.call.muted
                    val alert = muted || snapshot.flex == FlexSource.WEATHER_ALERT
                    drawTileLabel(canvas, paint, layout, tile, snapshot.flexLabel, palette, alert)
                    drawFlexTile(canvas, paint, layout, snapshot, palette)
                }
            }
        }
    }

    private fun drawTileLabel(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        tile: Box,
        label: String,
        palette: DashboardPalette,
        alert: Boolean = false,
    ) {
        val typeface = paint.typeface
        paint.color = if (alert) palette.warning else palette.dim
        paint.textSize = tile.height / 12f * (if (alert) 1.25f else 1f)
        if (alert) paint.typeface = Typeface.DEFAULT_BOLD
        // Labels are kept lowercase as data (the report and tests name them so) and drawn
        // in sentence case, like every other title on the dashboard.
        val shown = label.replaceFirstChar { it.titlecase(Locale.ROOT) }
        canvas.drawText(shown, (tile.x + layout.margin).toFloat(), (tile.y + tile.height / 9f) + tile.height / 24f, paint)
        paint.typeface = typeface
    }

    /**
     * The flex tile's content under its label (P7.8, P7.9, plan §5.8): whichever source has
     * something to say, a call first, then notifications, else a quiet line. Never "not
     * configured": the tile always has a job, even when the answer is "nothing new".
     */
    private fun drawFlexTile(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        when (snapshot.flex) {
            FlexSource.CALL -> drawCallCard(canvas, paint, layout, snapshot, palette)
            FlexSource.POI -> snapshot.poiCard?.let { drawPoiCard(canvas, paint, layout, it, palette) }
            FlexSource.NOTIFICATIONS -> drawNotifications(canvas, paint, layout, snapshot, palette)
            FlexSource.WEATHER -> snapshot.weatherView?.let {
                WeatherDrawing.drawView(canvas, paint, layout, layout.notificationsList, it, palette)
            }
            FlexSource.WEATHER_ALERT -> snapshot.weatherAlert?.let {
                WeatherDrawing.drawAlert(canvas, paint, layout, layout.notificationsList, it, palette)
            }
            FlexSource.EMPTY -> {
                val list = layout.notificationsList
                if (list.width <= 0 || list.height <= 0) return
                val text = if (snapshot.notificationsPaused) FlexRules.PAUSED else FlexRules.NO_NOTIFICATIONS
                // Sized as the empty favourites are, so the two tiles' quiet states match,
                // and brought down if the real text would still overrun.
                paint.color = palette.dim
                paint.textSize = layout.emptyFavoritesTextSize
                val width = paint.measureText(text)
                if (width > list.width) paint.textSize = paint.textSize * list.width / width * 0.95f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(text, list.centerX.toFloat(), list.centerY + paint.textSize * 0.36f, paint)
                paint.textAlign = Paint.Align.LEFT
            }
        }
    }

    /**
     * The notifications, newest first, one row each (plan §5.8): the sender or title,
     * large, across the row; under it, small, the app's name dimmed and one line of the
     * text. The full text is not drawn: at a glance from the seat, who and which app is
     * what a driver needs, and the rest is what reading aloud is for.
     *
     * The rows scroll and light exactly as the favourites do ([drawList]); a tapped row
     * stays lit for [Dashboard.READ_FLASH_MS], so the tap is seen to land, and then for as
     * long as it is being read aloud (P8.1).
     */
    private fun drawNotifications(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        val list = layout.notificationsList
        if (list.width <= 0 || list.height <= 0) return
        val rows = snapshot.notifications
        val rowHeight = layout.notificationRowHeight
        val lit = snapshot.flashNotification?.let { key -> rows.indexOfFirst { it.key == key } }?.takeIf { it >= 0 }
        drawList(
            canvas, paint, layout, list, rowHeight, rows.size, snapshot.notificationsScroll,
            snapshot.pressedNotification, lit, palette,
        ) { index, row, textWidth ->
            val item = rows[index]
            val x = (row.x + layout.margin).toFloat()
            val typeface = paint.typeface
            // Line 1: who, bold and large, across the whole row. The app's name used to
            // share this line and a 455 px tile then read "Lab Play… Sender…", both cut.
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = palette.text
            paint.textSize = rowHeight * 0.28f
            canvas.drawText(fit(paint, item.title, textWidth), x, row.y + rowHeight * 0.42f, paint)
            paint.typeface = typeface
            // Line 2, small: "App · text", the app dimmed, then the text, cut at the end.
            // The app's name is left out when the row already fell back to it as the title.
            paint.textSize = rowHeight * 0.2f
            val baseline = row.y + rowHeight * 0.8f
            var cursor = x
            val app = if (item.title == item.appLabel) "" else item.appLabel
            if (app.isNotEmpty()) {
                paint.color = palette.dim
                val prefix = fit(paint, if (item.text.isEmpty()) app else "$app · ", textWidth * 0.6f)
                canvas.drawText(prefix, cursor, baseline, paint)
                cursor += paint.measureText(prefix)
            }
            if (item.text.isNotEmpty()) {
                paint.color = palette.text
                canvas.drawText(fit(paint, item.text, x + textWidth - cursor), cursor, baseline, paint)
            }
        }
    }

    /**
     * The call card (P7.9, plan §5.8): the caller large under the label, and under that
     * either Answer and Decline side by side, green and red, each half the tile and the
     * whole height left; or, connected, the running time and a row of Mute (lit while
     * muted), Keypad and Hang up. Mute and Keypad are left out where they cannot work, on
     * the notification route; a call that is dialling or on hold has Hang up alone.
     */
    private fun drawCallCard(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        val call = snapshot.call
        val name = layout.callName
        if (name.width <= 0 || name.height <= 0) return
        val typeface = paint.typeface
        // The caller is drawn here and nowhere else: never logged, never in the report.
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = palette.text
        paint.textSize = name.height * 0.55f
        canvas.drawText(fit(paint, call.caller, name.width.toFloat()), name.x.toFloat(), name.y + name.height * 0.7f, paint)
        paint.typeface = typeface
        val duration = snapshot.callDuration
        if (duration != null && snapshot.callActions != FlexRules.RING_ACTIONS) {
            val box = layout.callDuration
            paint.color = palette.dim
            paint.textSize = box.height * 0.75f
            canvas.drawText(fit(paint, duration, box.width.toFloat()), box.x.toFloat(), box.y + box.height * 0.8f, paint)
        }
        snapshot.callActions.forEachIndexed { index, action ->
            val box = layout.callActionBox(snapshot.callActions, index)
            if (box.width <= 0 || box.height <= 0) return@forEachIndexed
            val pressed = snapshot.pressedCallAction == action
            when (action) {
                "answer" -> drawCallButton(canvas, paint, layout, box, "Answer", palette.answer, palette.onAction, pressed, palette)
                "decline" -> drawCallButton(canvas, paint, layout, box, "Decline", palette.decline, palette.onAction, pressed, palette)
                "hang-up" -> drawCallButton(canvas, paint, layout, box, "Hang up", palette.decline, palette.onAction, pressed, palette)
                // Lit while muted in the warning colour, which also colours the "Muted"
                // label, so the two read together.
                "mute" -> if (call.muted) {
                    drawCallButton(canvas, paint, layout, box, "Mute", palette.warning, palette.tile, pressed, palette)
                } else {
                    drawCallButton(canvas, paint, layout, box, "Mute", palette.button, palette.text, pressed, palette)
                }
                "keypad" -> drawCallButton(canvas, paint, layout, box, "Keypad", palette.button, palette.text, pressed, palette)
            }
        }
    }

    /**
     * One call or keypad button: a filled plate with its word centred, bold, brought down
     * to fit rather than cut. Pressed, it gets a thick rim in the text colour instead of a
     * change of fill, so green stays green and red stays red under a finger.
     */
    private fun drawCallButton(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        box: Box,
        label: String,
        fill: Int,
        ink: Int,
        pressed: Boolean,
        palette: DashboardPalette,
        textScale: Float = 0.26f,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(rect(box), CORNER, CORNER, paint)
        if (pressed) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(3f, box.height / 20f)
            paint.color = palette.text
            val inset = paint.strokeWidth / 2f
            canvas.drawRoundRect(
                box.x + inset, box.y + inset, box.right - inset, box.bottom - inset, CORNER, CORNER, paint,
            )
            paint.style = Paint.Style.FILL
        }
        val typeface = paint.typeface
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = ink
        paint.textSize = minOf(box.height, box.width) * textScale
        val room = box.width - layout.margin.toFloat()
        val width = paint.measureText(label)
        if (width > room) paint.textSize = paint.textSize * room / width * 0.95f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(fit(paint, label, room), box.centerX.toFloat(), box.centerY + paint.textSize * 0.36f, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = typeface
    }

    /**
     * The keypad (P7.9, plan §5.8), over the media pane and both tiles: the echo strip of
     * this call's keys with Close at its right, then 1–9, *, 0 and # in the telephone's
     * order. On a solid plate with a rim, like the turn card, so it reads as a pop-up over
     * the dashboard rather than as a new layout of it.
     *
     * **The echo is drawn and held nowhere else** (plan §5.8): keypad digits are often
     * PINs, so they are never logged or reported. The strip shows the latest keys when
     * there are more than fit.
     */
    private fun drawKeypad(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        val pad = layout.keypad
        if (pad.width <= 0 || pad.height <= 0) return
        paint.style = Paint.Style.FILL
        paint.color = palette.tile
        canvas.drawRoundRect(rect(pad), CORNER, CORNER, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, pad.height / 300f)
        paint.color = palette.bubbleRim
        canvas.drawRoundRect(rect(pad), CORNER, CORNER, paint)
        paint.style = Paint.Style.FILL

        val echo = layout.keypadEcho
        if (echo.width > 0 && echo.height > 0) {
            paint.color = palette.background
            canvas.drawRoundRect(rect(echo), CORNER, CORNER, paint)
            val typeface = paint.typeface
            paint.typeface = Typeface.MONOSPACE
            paint.textSize = echo.height * 0.55f
            val room = echo.width - 2f * layout.margin
            val digits = snapshot.keypadEcho
            // The keypad hides the tile that says "Muted", and a forgotten mute is the
            // mistake to catch (plan §5.8), so the strip says it too while it is on.
            var left = echo.x + layout.margin.toFloat()
            if (snapshot.call.muted) {
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.color = palette.warning
                val muted = fit(paint, "Muted", room / 2f)
                canvas.drawText(muted, left, echo.y + echo.height * 0.68f, paint)
                left += paint.measureText(muted) + layout.margin
                paint.typeface = Typeface.MONOSPACE
            }
            if (digits.isEmpty()) {
                paint.color = palette.dim
                paint.typeface = typeface
                if (!snapshot.call.muted) {
                    canvas.drawText(fit(paint, "Keypad", room), left, echo.y + echo.height * 0.68f, paint)
                }
            } else {
                // The latest keys, right-aligned: the one just pressed is always in view.
                paint.color = palette.text
                val space = echo.right - layout.margin - left
                var shown = digits
                while (shown.length > 1 && paint.measureText(shown) > space) shown = shown.substring(1)
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(shown, echo.right - layout.margin.toFloat(), echo.y + echo.height * 0.72f, paint)
                paint.textAlign = Paint.Align.LEFT
            }
            paint.typeface = typeface
        }
        drawCallButton(
            canvas, paint, layout, layout.keypadClose, "Close", palette.button, palette.text,
            snapshot.pressedKeypadClose, palette, 0.4f,
        )
        FlexRules.KEYPAD_KEYS.forEachIndexed { index, key ->
            val box = layout.keypadKey(index)
            if (box.width <= 0 || box.height <= 0) return@forEachIndexed
            // Lit in the accent while held, as the media buttons are: the tone is playing.
            val pressed = snapshot.pressedKey == index
            paint.color = if (pressed) palette.accent else palette.button
            canvas.drawRoundRect(rect(box), CORNER, CORNER, paint)
            val typeface = paint.typeface
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = palette.text
            paint.textSize = box.height * 0.45f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(key.toString(), box.centerX.toFloat(), box.centerY + paint.textSize * 0.36f, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = typeface
        }
    }

    /**
     * The trip panel (P7.4, plan §5.4): arrival time big at the top left with the time and
     * distance remaining right-aligned beside it, the two maneuvers after the next one
     * small underneath, and End route across the bottom.
     *
     * Figures guidance cannot vouch for — re-routing, or a route with no fix on it yet —
     * are drawn as a dash rather than left as they were: a stale arrival time is worse
     * than none. End route is drawn in every state, because it is the one way to cancel a
     * route from the car.
     */
    private fun drawTrip(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        trip: TripPanel,
        palette: DashboardPalette,
    ) {
        val summary = layout.tripSummary
        if (summary.height > 0) {
            val h = summary.height.toFloat()
            val left = summary.x.toFloat()
            paint.color = palette.dim
            paint.textSize = h * 0.22f
            val caption = when {
                trip.arrived -> "trip"
                trip.rerouting -> "rerouting…"
                else -> "arrive"
            }
            val traffic = trip.traffic?.takeUnless { trip.arrived || trip.rerouting }
            if (traffic == null) {
                canvas.drawText(fit(paint, caption, summary.width.toFloat()), left, summary.y + h * 0.26f, paint)
            } else {
                drawTripTraffic(canvas, paint, layout, trip, traffic, palette)
            }
            val typeface = paint.typeface
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = palette.text
            paint.textSize = h * 0.52f
            val big = if (trip.arrived) "Arrived" else trip.arrival ?: "—"
            val bigRight = left + drawClock(canvas, paint, big, left, summary.y + h * 0.86f, h * 0.52f, h * 0.24f, summary.width.toFloat())
            paint.typeface = typeface
            if (!trip.arrived) {
                // Right-aligned, so the two remaining figures line up under each other
                // whatever their widths, and stay clear of the arrival time.
                val room = summary.right - bigRight - layout.margin
                paint.textSize = h * 0.3f
                paint.textAlign = Paint.Align.RIGHT
                paint.color = palette.text
                canvas.drawText(fit(paint, trip.remaining ?: "—", room), summary.right.toFloat(), summary.y + h * 0.44f, paint)
                paint.color = palette.dim
                canvas.drawText(fit(paint, trip.distance ?: "", room), summary.right.toFloat(), summary.y + h * 0.86f, paint)
                paint.textAlign = Paint.Align.LEFT
            }
        }

        trip.later.take(RouteFollower.LATER_STEPS).forEachIndexed { index, step ->
            val cell = layout.tripLater(index)
            if (cell.height <= 0 || cell.width <= 0) return@forEachIndexed
            // At 800x480 a cell is about 83 px wide: the icon takes a third, so the distance
            // beside it is never cut short.
            val side = minOf(cell.height * 3 / 4, cell.width / 3)
            val icon = Box(cell.x, cell.y + (cell.height - side) / 2, side, side)
            drawManeuver(canvas, paint, Maneuvers.glyph(step.maneuver, step.exit), icon, palette.text, palette.dim, palette.tile)
            paint.color = palette.text
            paint.textSize = cell.height * 0.36f
            val textX = icon.right + layout.margin / 2f
            canvas.drawText(fit(paint, step.distance, cell.right - textX), textX, cell.y + cell.height * 0.64f, paint)
        }

        val button = layout.endRoute
        if (button.height > 0) {
            paint.color = if (trip.endPressed) palette.accent else palette.button
            canvas.drawRoundRect(rect(button), CORNER, CORNER, paint)
            val typeface = paint.typeface
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = palette.text
            // Since P8.2 the button shares its row with the voice toggle, so the words are
            // sized to its width as well as its height: about 4.5 em in bold, 5 allowed.
            paint.textSize = minOf(button.height * 0.28f, (button.width - 2f * layout.margin) / 5f)
            val text = fit(paint, "End route", button.width - 2f * layout.margin)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(text, button.centerX.toFloat(), button.centerY + paint.textSize * 0.36f, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = typeface
        }
        drawVoiceToggle(canvas, paint, layout.voiceToggle, trip, palette)
    }

    /**
     * "+7 min traffic" on the trip panel's caption line (P9.4, plan §6), in the warning
     * colour, left of the time remaining, which already includes the delay. "arrive ·" stays
     * in front of it where both fit; where they do not, the label takes the caption's place,
     * since the big clock time under it reads as an arrival either way. Brought down to fit
     * rather than cut, to three quarters of its size at most.
     */
    private fun drawTripTraffic(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        trip: TripPanel,
        label: String,
        palette: DashboardPalette,
    ) {
        val summary = layout.tripSummary
        val h = summary.height.toFloat()
        paint.textSize = h * 0.3f
        val remaining = paint.measureText(trip.remaining ?: "—")
        val room = summary.width - remaining - layout.margin
        val size = layout.tripTrafficTextSize
        paint.textSize = size
        val lead = "arrive · "
        var x = summary.x.toFloat()
        val baseline = summary.y + h * 0.26f
        if (paint.measureText(lead + label) <= room) {
            paint.color = palette.dim
            canvas.drawText(lead, x, baseline, paint)
            x += paint.measureText(lead)
        } else {
            val width = paint.measureText(label)
            if (width > room) paint.textSize = maxOf(size * 0.75f, size * room / width * 0.95f)
        }
        paint.color = palette.warning
        canvas.drawText(fit(paint, label, summary.x + room - x), x, baseline, paint)
    }

    /**
     * The voice-guidance toggle (P8.2, P8 plan §6): a speaker drawn from strokes, as the
     * maneuver icons are. On, it is lit in the accent with two sound waves; off, it is dim
     * and slashed. Whenever speech cannot happen — no engine, or no Bluetooth output — a
     * small warning triangle sits at its top right whichever way it is set, so "why is it
     * silent" is answered from the driver's seat.
     */
    private fun drawVoiceToggle(canvas: Canvas, paint: Paint, box: Box, trip: TripPanel, palette: DashboardPalette) {
        if (box.width <= 0 || box.height <= 0) return
        paint.style = Paint.Style.FILL
        paint.color = if (trip.voicePressed) palette.accent else palette.button
        canvas.drawRoundRect(rect(box), CORNER, CORNER, paint)
        val s = minOf(box.width, box.height) * 0.8f
        val cx = box.centerX.toFloat()
        val cy = box.centerY.toFloat()
        val colour = when {
            trip.voicePressed -> palette.text
            trip.voiceOn -> palette.accent
            else -> palette.dim
        }
        // The speaker: a small box and the cone opening to the right of it.
        val left = cx - s * 0.36f
        val neck = left + s * 0.16f
        val mouth = left + s * 0.38f
        paint.color = colour
        canvas.drawRect(left, cy - s * 0.11f, neck, cy + s * 0.11f, paint)
        canvas.drawPath(
            Path().apply {
                moveTo(neck, cy - s * 0.11f)
                lineTo(mouth, cy - s * 0.3f)
                lineTo(mouth, cy + s * 0.3f)
                lineTo(neck, cy + s * 0.11f)
                close()
            },
            paint,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = maxOf(1.5f, s * 0.07f)
        if (trip.voiceOn) {
            for (r in floatArrayOf(s * 0.17f, s * 0.32f)) {
                canvas.drawArc(RectF(mouth - r, cy - r, mouth + r, cy + r), -45f, 90f, false, paint)
            }
        } else {
            // The slash, corner to corner across the speaker, in the text colour so it
            // reads over the dim speaker and the lit press alike.
            paint.color = palette.text
            canvas.drawLine(cx - s * 0.4f, cy - s * 0.4f, cx + s * 0.4f, cy + s * 0.4f, paint)
        }
        paint.style = Paint.Style.FILL
        if (trip.voiceWarning) {
            val t = s * 0.36f
            val right = box.right - maxOf(2f, box.width * 0.06f)
            val top = box.y + maxOf(2f, box.height * 0.05f)
            paint.color = palette.warning
            canvas.drawPath(
                Path().apply {
                    moveTo(right - t / 2f, top)
                    lineTo(right, top + t * 0.87f)
                    lineTo(right - t, top + t * 0.87f)
                    close()
                },
                paint,
            )
            paint.color = palette.button
            paint.strokeWidth = maxOf(1f, t * 0.12f)
            paint.style = Paint.Style.STROKE
            canvas.drawLine(right - t / 2f, top + t * 0.3f, right - t / 2f, top + t * 0.58f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(right - t / 2f, top + t * 0.72f, maxOf(0.75f, t * 0.06f), paint)
        }
    }

    /**
     * The saved places, one a row, with the pressed one lit and a scrollbar along the
     * right edge whenever there are more rows than fit.
     *
     * Clipped to the list box: a list scrolled part-way has a row straddling each edge,
     * and drawing those in full would put text over the tile's label above and its
     * rounded corner below. An empty list says how to fill it, so a tile with nothing in
     * it reads as unconfigured rather than broken.
     */
    private fun drawFavorites(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        val list = layout.favoritesList
        if (list.width <= 0 || list.height <= 0) return
        val favorites = snapshot.favorites
        if (favorites.isEmpty()) {
            // Two centred lines, sized to fit rather than cut off (plan §5.7): the layout's
            // size is an estimate, so the real text is measured and the size brought down
            // further if either line would still overrun.
            paint.color = palette.dim
            paint.textSize = layout.emptyFavoritesTextSize
            val widest = DashboardLayout.EMPTY_FAVORITES.maxOf { paint.measureText(it) }
            if (widest > list.width) paint.textSize = paint.textSize * list.width / widest
            paint.textAlign = Paint.Align.CENTER
            val gap = paint.textSize * 1.3f
            DashboardLayout.EMPTY_FAVORITES.forEachIndexed { line, text ->
                val y = list.centerY + (line - (DashboardLayout.EMPTY_FAVORITES.size - 1) / 2f) * gap + paint.textSize * 0.36f
                canvas.drawText(text, list.centerX.toFloat(), y, paint)
            }
            paint.textAlign = Paint.Align.LEFT
            return
        }

        val rowHeight = layout.favoriteRowHeight
        paint.textSize = rowHeight * 0.4f
        drawList(
            canvas, paint, layout, list, rowHeight, favorites.size, snapshot.favoritesScroll,
            snapshot.pressedFavorite, null, palette,
        ) { index, row, textWidth ->
            var x = (row.x + layout.margin).toFloat()
            var width = textWidth
            if (index == 0 && snapshot.recentFavorite) {
                // The recent destination, not a saved place: a history icon before its name.
                val radius = rowHeight * 0.17f
                drawHistory(canvas, paint, x + radius * 1.4f, row.y + rowHeight * 0.5f, radius, palette)
                val indent = radius * 3.4f
                x += indent
                width -= indent
            }
            paint.color = palette.text
            canvas.drawText(fit(paint, favorites[index].name, width), x, row.y + rowHeight * 0.64f, paint)
        }
    }

    /**
     * A history icon, a clock face whose rim is an arrow turning back, drawn rather than
     * set in a font so it cannot fall back to a missing glyph. [radius] is the rim's; the
     * arrowhead reaches about [radius] * 0.4 past it on the left.
     */
    private fun drawHistory(
        canvas: Canvas,
        paint: Paint,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: DashboardPalette,
    ) {
        paint.color = palette.dim
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1.5f, radius / 5f)
        paint.strokeCap = Paint.Cap.ROUND
        // The rim from nine o'clock, clockwise round to half past seven, open at the lower left.
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 180f, 315f, false, paint)
        // The hands, at twelve and towards four.
        canvas.drawLine(cx, cy, cx, cy - radius * 0.55f, paint)
        canvas.drawLine(cx, cy, cx + radius * 0.42f, cy + radius * 0.28f, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
        // The arrowhead at nine o'clock, pointing down: the way back round the face.
        val head = radius * 0.42f
        val path = Path()
        path.moveTo(cx - radius - head, cy - head * 0.2f)
        path.lineTo(cx - radius + head, cy - head * 0.2f)
        path.lineTo(cx - radius, cy + head * 0.9f)
        path.close()
        canvas.drawPath(path, paint)
    }

    /**
     * A scrolling list of rows in [list] (the favourites since P6.6, the notifications
     * since P7.8): [drawRow] draws each row that touches the box, given the width its text
     * may take; this draws the pressed or [lit] row's highlight, the hairlines between
     * rows, and the scrollbar along the right edge whenever there are more rows than fit.
     *
     * Clipped to the list box: a list scrolled part-way has a row straddling each edge,
     * and drawing those in full would put text over the tile's label above and its
     * rounded corner below.
     */
    private fun drawList(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        list: Box,
        rowHeight: Int,
        count: Int,
        scroll: Int,
        pressed: Int,
        lit: Int?,
        palette: DashboardPalette,
        drawRow: (index: Int, row: Box, textWidth: Float) -> Unit,
    ) {
        val maxScroll = layout.listMaxScroll(list, rowHeight, count)
        val barWidth = if (maxScroll > 0) maxOf(2f, layout.margin / 2f) else 0f
        val textWidth = list.width - barWidth - 2f * layout.margin

        canvas.save()
        canvas.clipRect(rect(list))
        // Only the rows that touch the box are drawn; the rest are off the top or bottom.
        val first = maxOf(0, scroll / rowHeight)
        val last = minOf(count - 1, (scroll + list.height) / rowHeight)
        for (index in first..last) {
            val row = layout.listRow(list, rowHeight, index, scroll)
            if (pressed == index || lit == index) {
                paint.color = palette.button
                canvas.drawRoundRect(
                    row.x.toFloat(), row.y.toFloat(), row.right - barWidth, row.bottom.toFloat(),
                    CORNER, CORNER, paint,
                )
            }
            val size = paint.textSize
            drawRow(index, row, textWidth)
            paint.textSize = size
            // A hairline under every row but the last, so rows read as rows.
            if (index < count - 1) {
                paint.color = palette.header
                canvas.drawRect(
                    (row.x + layout.margin).toFloat(), row.bottom - 1f,
                    row.right - barWidth - layout.margin, row.bottom.toFloat(), paint,
                )
            }
        }
        if (maxScroll > 0) {
            // Track the height of the box; thumb the fraction of the list in view, placed
            // by the fraction scrolled. The one thing on the tile that says there is more.
            paint.color = palette.header
            canvas.drawRect(list.right - barWidth, list.y.toFloat(), list.right.toFloat(), list.bottom.toFloat(), paint)
            val content = (count * rowHeight).toFloat()
            val thumb = maxOf(barWidth * 2f, list.height * list.height / content)
            val top = list.y + (list.height - thumb) * scroll / maxScroll
            paint.color = palette.dim
            canvas.drawRect(list.right - barWidth, top, list.right.toFloat(), top + thumb, paint)
        }
        canvas.restore()
    }

    /** The full-width now-playing pane: cover art, two lines of text, three buttons. */
    private fun drawMedia(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
        art: android.graphics.Bitmap?,
    ) {
        val tile = layout.media
        paint.color = palette.tile
        canvas.drawRoundRect(rect(tile), CORNER, CORNER, paint)

        if (snapshot.mediaIdle) {
            // P7.5: nothing is playing, so the pane offers what could be (plan §5.5).
            drawPlayers(canvas, paint, layout, snapshot, palette)
            return
        }

        drawAlbumArt(canvas, paint, layout, palette, art)

        val textX = layout.mediaTextX().toFloat()
        val textWidth = layout.mediaTextWidth().toFloat()

        // Title and artist on their own lines when both are known. The single combined
        // line the four-tile layout used had to be truncated almost immediately; a pane
        // this wide can show the two things a driver actually looks for.
        paint.color = palette.text
        paint.textSize = tile.height / 7f
        val title = snapshot.title ?: snapshot.nowPlaying ?: DemoScene.NO_MEDIA
        canvas.drawText(fit(paint, title, textWidth), textX, tile.y + tile.height * 0.25f, paint)

        paint.color = palette.dim
        paint.textSize = tile.height / 10f
        val second = snapshot.artist ?: snapshot.transport ?: ""
        if (second.isNotEmpty()) {
            canvas.drawText(fit(paint, second, textWidth), textX, tile.y + tile.height * 0.38f, paint)
        }

        DashboardLayout.MEDIA_ACTIONS.forEachIndexed { index, action ->
            val button = layout.mediaButton(index)
            paint.color =
                if (snapshot.pressedMedia == action) palette.accent else palette.button
            canvas.drawRoundRect(rect(button), CORNER, CORNER, paint)
            paint.color = palette.text
            val size = button.height * 0.3f
            val cx = button.centerX.toFloat()
            val cy = button.centerY.toFloat()
            when (action) {
                "previous" -> {
                    triangle(canvas, paint, cx + size * 0.5f, cy, size, false)
                    canvas.drawRect(cx - size * 0.8f, cy - size, cx - size * 0.55f, cy + size, paint)
                }
                "next" -> {
                    triangle(canvas, paint, cx - size * 0.5f, cy, size, true)
                    canvas.drawRect(cx + size * 0.55f, cy - size, cx + size * 0.8f, cy + size, paint)
                }
                // One button, two meanings, and the icon has to say which: showing a
                // play triangle while audio is playing is how a driver ends up pressing
                // it twice.
                else -> if (snapshot.playing) {
                    canvas.drawRect(cx - size * 0.55f, cy - size, cx - size * 0.15f, cy + size, paint)
                    canvas.drawRect(cx + size * 0.15f, cy - size, cx + size * 0.55f, cy + size, paint)
                } else {
                    triangle(canvas, paint, cx - size * 0.3f, cy, size, true)
                }
            }
        }
    }

    /**
     * The idle media pane (P7.5, plan §5.5): the player apps, most recently active first,
     * as many as [DashboardLayout.PLAYER_ROWS], each a row with a Resume button, and
     * without notification access one small line saying the phone could list more.
     *
     * A press is answered on the row itself: greyed with "Starting…" while it is pending,
     * "Couldn't resume" in the warning colour when it failed. A Resume that works shows
     * itself by the pane turning into now-playing, once the player's session says so.
     */
    private fun drawPlayers(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        val hint = !snapshot.mediaAccess
        val players = snapshot.players.take(DashboardLayout.PLAYER_ROWS)
        if (players.isEmpty()) {
            val area = layout.playerRow(0, hint)
            paint.color = palette.dim
            paint.textSize = area.height * 0.26f
            paint.textAlign = Paint.Align.CENTER
            val text = fit(paint, "No media players found", layout.media.width - 2f * layout.margin)
            val middle = (layout.media.y + (if (hint) layout.playerHint.y else layout.media.bottom)) / 2f
            canvas.drawText(text, layout.media.centerX.toFloat(), middle + paint.textSize * 0.36f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        players.forEachIndexed { index, player ->
            val row = layout.playerRow(index, hint)
            if (row.height <= 0) return@forEachIndexed
            val button = layout.resumeButton(row)
            val pending = snapshot.resumePending == player.key
            val failed = !pending && snapshot.resumeFailed == player.key
            val pressed = snapshot.pressedPlayer == index
            if (pressed) {
                paint.color = palette.tilePressed
                canvas.drawRoundRect(rect(row), CORNER, CORNER, paint)
            }
            // The name is drawn and never logged (Player's KDoc): listening history.
            paint.color = if (pending) palette.dim else palette.text
            paint.textSize = row.height * 0.3f
            val nameX = row.x + layout.margin.toFloat()
            canvas.drawText(
                fit(paint, player.label, button.x - layout.margin - nameX),
                nameX,
                row.centerY + paint.textSize * 0.36f,
                paint,
            )
            paint.color = when {
                pressed -> palette.accent
                pending -> palette.tilePressed
                else -> palette.button
            }
            canvas.drawRoundRect(rect(button), CORNER, CORNER, paint)
            val label = when {
                pending -> "Starting…"
                failed -> "Couldn't resume"
                else -> "Resume"
            }
            paint.color = when {
                failed -> palette.warning
                pending -> palette.dim
                else -> palette.text
            }
            paint.textSize = button.height * 0.24f
            val room = button.width - 2f * layout.margin
            if (!pending && !failed) {
                // A play triangle before the word, the transport buttons' own shape, so
                // the row reads as "play this" before the word is read.
                val size = paint.textSize * 0.42f
                val word = paint.measureText(label)
                val start = button.centerX - (word + size * 2.4f) / 2f
                triangle(canvas, paint, start, button.centerY.toFloat(), size, true)
                canvas.drawText(label, start + size * 2.4f, button.centerY + paint.textSize * 0.36f, paint)
            } else {
                // Brought down to fit rather than cut: "Couldn't res…" says less than the
                // whole words a little smaller.
                // A little under the exact ratio: text width does not scale quite linearly
                // with size, and the first lab frame came out one pixel over and ellipsized.
                val width = paint.measureText(label)
                if (width > room) paint.textSize = paint.textSize * room / width * 0.95f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(fit(paint, label, room), button.centerX.toFloat(), button.centerY + paint.textSize * 0.36f, paint)
                paint.textAlign = Paint.Align.LEFT
            }
        }
        if (hint) {
            // Small and once, no longer the headline (plan §5.5): apps with a browser
            // service are listed without access, live sessions only with it.
            val box = layout.playerHint
            paint.color = palette.dim
            paint.textSize = box.height * 0.55f
            val text = "The phone can list more players with notification access on"
            val width = paint.measureText(text)
            if (width > box.width) paint.textSize = maxOf(box.height * 0.4f, paint.textSize * box.width / width)
            canvas.drawText(fit(paint, text, box.width.toFloat()), box.x.toFloat(), box.y + box.height * 0.7f, paint)
        }
    }

    /**
     * Cover art, or a placeholder square when there is none.
     *
     * A placeholder rather than nothing: an empty gap where the art belongs reads as a
     * layout bug, where a drawn note glyph reads as "this track has no art" — and the
     * two happen for different reasons, one of which is a missing notification grant.
     */
    private fun drawAlbumArt(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        palette: DashboardPalette,
        art: android.graphics.Bitmap?,
    ) {
        val box = layout.albumArt
        if (box.width <= 0 || box.height <= 0) return
        val target = rect(box)
        if (art != null && !art.isRecycled) {
            // Centre-cropped to a square so non-square art is not stretched.
            val side = minOf(art.width, art.height)
            val source = android.graphics.Rect(
                (art.width - side) / 2,
                (art.height - side) / 2,
                (art.width - side) / 2 + side,
                (art.height - side) / 2 + side,
            )
            paint.isFilterBitmap = true
            canvas.drawBitmap(art, source, target, paint)
            paint.isFilterBitmap = false
            return
        }
        paint.color = palette.button
        canvas.drawRoundRect(target, CORNER, CORNER, paint)
        paint.color = palette.dim
        val cx = box.centerX.toFloat()
        val cy = box.centerY.toFloat()
        val note = box.height * 0.18f
        // A minim: filled head with a stem, drawn rather than shipped as an asset.
        canvas.drawCircle(cx - note * 0.35f, cy + note * 0.7f, note * 0.55f, paint)
        canvas.drawRect(cx + note * 0.1f, cy - note, cx + note * 0.28f, cy + note * 0.7f, paint)
    }

    /** A play-style triangle pointing right, or left when [right] is false. */
    private fun triangle(canvas: Canvas, paint: Paint, x: Float, y: Float, size: Float, right: Boolean) {
        val direction = if (right) 1f else -1f
        val path = Path().apply {
            moveTo(x + direction * size, y)
            lineTo(x, y - size)
            lineTo(x, y + size)
            close()
        }
        canvas.drawPath(path, paint)
    }

    /**
     * What is drawn over the map pane: its border, the attribution OSM's licence
     * requires, the vehicle marker, the floating controls, and a reason when there is no
     * map to show.
     *
     * Everything but the border is inside the `ok` branch, because a pane with no map in
     * it has no geography to mark and nothing to zoom: a compass over an error message
     * would invite presses that could not do anything.
     */
    private fun drawMapOverlay(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        val map = layout.map
        if (snapshot.mapStatus != "ok") {
            paint.color = palette.dim
            paint.textSize = map.height / 22f
            val text = "map: ${snapshot.mapStatus}"
            canvas.drawText(text, map.centerX - paint.measureText(text) / 2f, map.centerY.toFloat(), paint)
        } else {
            // ODbL requires the source to be credited wherever the data is shown, and
            // the projected frame is the only place this map is ever shown. Drawn on
            // its own backing plate rather than straight onto the map: the map is
            // arbitrary colours underneath, and an attribution that disappears over a
            // dark forest or a light car park is not an attribution.
            // Sized and placed by the layout, which stacks the zoom bubbles above it.
            paint.textSize = layout.attributionTextSize.toFloat()
            val width = paint.measureText(ATTRIBUTION)
            val textX = map.right - layout.margin - width
            val textY = (layout.attributionTop + layout.attributionTextSize).toFloat()
            val padding = paint.textSize * 0.3f
            paint.color = palette.attributionPlate
            canvas.drawRect(
                textX - padding,
                textY - paint.textSize,
                textX + width + padding,
                textY + padding * 0.6f,
                paint,
            )
            paint.color = palette.attribution
            canvas.drawText(ATTRIBUTION, textX, textY, paint)
            drawAlternativeBubbles(canvas, paint, map, snapshot, palette)
            drawVehicle(canvas, paint, map, snapshot, palette)
            drawMapControls(canvas, paint, layout, snapshot, palette)
            snapshot.poiButtons?.let { drawPoiButtons(canvas, paint, layout, it, palette) }
        }
        // P9.3: HERE's credit whenever traffic is drawn, or is in the time, or shaped the
        // route (plan §1, 6.4 a). Outside the `ok` branch, because the arrival time owes it
        // even over a pane with no map in it.
        if (snapshot.trafficAttributed) drawTrafficAttribution(canvas, paint, layout, palette)
        // Outside the `ok` branch too: the Home Assistant button has nothing to do with the
        // map, and a phone with no map installed can still open the garage.
        snapshot.homeAssistant?.let {
            if (!layout.homeAssistantInHeader) drawHomeAssistant(canvas, paint, layout.homeAssistantBubble, it, palette, header = false)
        }
        // Outside the map's `ok` branch: guidance does not need a map, and a driver
        // following a route with no map installed still needs to know the next turn.
        val offer = snapshot.offer
        if (offer != null) {
            drawOfferCard(canvas, paint, layout, snapshot.turnCard, offer, palette)
        } else {
            snapshot.turnCard?.let { drawTurnCard(canvas, paint, layout, it, palette) }
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, layout.height / 540f)
        paint.color = palette.tile
        canvas.drawRoundRect(rect(map), CORNER, CORNER, paint)
        paint.style = Paint.Style.FILL
    }

    /**
     * The turn card (P7.4, plan §5.3): the maneuver's icon, the distance to it large, and
     * the street it turns onto on its own line; `Rerouting…` or `You have arrived` in that
     * line's place, with no distance.
     *
     * On a solid plate with a rim, like the attribution and for the same reason: the map
     * under it is any colour at all.
     */
    private fun drawTurnCard(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        card: TurnCard,
        palette: DashboardPalette,
    ) {
        val box = layout.turnCard
        if (box.width <= 0 || box.height <= 0) return
        drawCardPlate(canvas, paint, box, palette)
        drawTurn(canvas, paint, layout, card, box, layout.turnIcon, palette)
    }

    /** The turn card's solid plate and rim. */
    private fun drawCardPlate(canvas: Canvas, paint: Paint, box: Box, palette: DashboardPalette) {
        paint.style = Paint.Style.FILL
        paint.color = palette.tile
        canvas.drawRoundRect(rect(box), CORNER, CORNER, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, rimWidth(box))
        paint.color = palette.bubbleRim
        canvas.drawRoundRect(rect(box), CORNER, CORNER, paint)
        paint.style = Paint.Style.FILL
    }

    private fun rimWidth(box: Box): Float = minOf(box.height, box.width) / 60f

    /**
     * The next turn in [box] with its icon at [icon]: the icon at the left and the distance
     * and street beside it, as the whole card has drawn it since P7.4.
     */
    private fun drawTurn(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        card: TurnCard,
        box: Box,
        icon: Box,
        palette: DashboardPalette,
    ) {
        drawManeuver(canvas, paint, Maneuvers.glyph(card.maneuver, card.exit), icon, palette.accent, palette.dim, palette.tile)
        val glyphDrawn = Maneuvers.glyph(card.maneuver, card.exit).shape != GlyphShape.NONE
        val textX = (if (glyphDrawn) icon.right else box.x) + layout.margin.toFloat()
        val room = box.right - layout.margin - textX
        val h = box.height.toFloat()
        val distance = card.distance
        if (distance != null) {
            val typeface = paint.typeface
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = palette.text
            paint.textSize = h * 0.4f
            canvas.drawText(fit(paint, distance, room), textX, box.y + h * 0.5f, paint)
            paint.typeface = typeface
            paint.textSize = h * 0.24f
            canvas.drawText(fit(paint, card.line, room), textX, box.y + h * 0.84f, paint)
        } else {
            // One line, centred in the card: re-routing or arrived.
            paint.color = if (card.line == UpcomingTurn.REROUTING) palette.warning else palette.text
            paint.textSize = h * 0.3f
            canvas.drawText(fit(paint, card.line, room), textX, box.centerY + paint.textSize * 0.36f, paint)
        }
    }

    /**
     * The turn card divided for an offer (P9.6, plan §7 "The offer"): the next turn in its
     * part — stacked, exactly as the whole card draws it; side by side, the icon over the
     * distance with the street dropped — and in the other, "Faster: −7 min" in bold, the
     * reason under it, and Take and Keep. Take is filled in the accent, as the press that
     * does something; Keep is a plain button. Guidance is drawn whatever the offer says:
     * with no turn to show ([card] null), its part is left empty rather than given away.
     */
    private fun drawOfferCard(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        card: TurnCard?,
        offer: OfferCard,
        palette: DashboardPalette,
    ) {
        val box = layout.offerCard
        if (box.width <= 0 || box.height <= 0) return
        drawCardPlate(canvas, paint, box, palette)
        val turn = layout.offerTurn
        if (card != null) {
            if (layout.offerTurnStreet) {
                drawTurn(canvas, paint, layout, card, turn, layout.offerTurnIcon, palette)
            } else {
                drawTurnUnderIcon(canvas, paint, layout, card, palette)
            }
        }
        // The line between the two parts, in the rim's colour.
        val part = layout.offerPart
        paint.color = palette.bubbleRim
        val hair = maxOf(1f, rimWidth(box))
        if (layout.offerLayout == OfferLayout.STACKED) {
            canvas.drawRect(part.x + layout.margin.toFloat(), part.y.toFloat(), part.right - layout.margin.toFloat(), part.y + hair, paint)
        } else {
            canvas.drawRect(part.x.toFloat(), part.y + layout.margin.toFloat(), part.x + hair, part.bottom - layout.margin.toFloat(), paint)
        }

        val text = layout.offerText
        if (text.width > 0 && text.height > 0) {
            val th = text.height.toFloat()
            val typeface = paint.typeface
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = palette.text
            paint.textSize = th * 0.4f
            val labelWidth = paint.measureText(offer.label)
            if (labelWidth > text.width) paint.textSize = maxOf(th * 0.28f, paint.textSize * text.width / labelWidth * 0.95f)
            canvas.drawText(fit(paint, offer.label, text.width.toFloat()), text.x.toFloat(), text.y + th * 0.46f, paint)
            paint.typeface = typeface
            paint.color = palette.dim
            paint.textSize = th * 0.28f
            canvas.drawText(fit(paint, offer.reason, text.width.toFloat()), text.x.toFloat(), text.y + th * 0.86f, paint)
        }
        DashboardLayout.OFFER_ACTIONS.forEachIndexed { index, action ->
            val button = layout.offerButton(index)
            if (button.width <= 0 || button.height <= 0) return@forEachIndexed
            val pressed = offer.pressed == action
            if (action == "take") {
                drawCallButton(canvas, paint, layout, button, "Take", palette.accent, palette.tile, pressed, palette, 0.4f)
            } else {
                drawCallButton(canvas, paint, layout, button, "Keep", palette.button, palette.text, pressed, palette, 0.4f)
            }
        }
    }

    /**
     * The next turn in the side-by-side card's narrow part: the icon across its top and the
     * distance centred under it, or re-routing and arrived in small type. No street: the
     * part is too narrow for one (plan §7).
     */
    private fun drawTurnUnderIcon(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        card: TurnCard,
        palette: DashboardPalette,
    ) {
        val part = layout.offerTurn
        val icon = layout.offerTurnIcon
        drawManeuver(canvas, paint, Maneuvers.glyph(card.maneuver, card.exit), icon, palette.accent, palette.dim, palette.tile)
        val inset = maxOf(1, layout.margin / 2).toFloat()
        val room = part.width - 2 * inset
        val below = part.bottom - inset - icon.bottom
        if (room <= 0f || below <= 0f) return
        val typeface = paint.typeface
        paint.textAlign = Paint.Align.CENTER
        val distance = card.distance
        if (distance != null) {
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = palette.text
            paint.textSize = below * 0.75f
            val width = paint.measureText(distance)
            if (width > room) paint.textSize = paint.textSize * room / width * 0.95f
        } else {
            paint.color = if (card.line == UpcomingTurn.REROUTING) palette.warning else palette.text
            paint.textSize = below * 0.45f
        }
        val line = distance ?: card.line
        canvas.drawText(fit(paint, line, room), part.centerX.toFloat(), icon.bottom + below * 0.5f + paint.textSize * 0.36f, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = typeface
    }

    /**
     * "Traffic © HERE" (P9.3) in its layout box above the ODbL credit, right-aligned, on the
     * same plate and in the same size, so the two credits read as a pair in either palette.
     */
    private fun drawTrafficAttribution(canvas: Canvas, paint: Paint, layout: DashboardLayout, palette: DashboardPalette) {
        val box = layout.trafficAttribution
        paint.textSize = layout.attributionTextSize.toFloat()
        val pad = paint.textSize * 0.3f
        val text = DashboardLayout.TRAFFIC_ATTRIBUTION
        val width = paint.measureText(text)
        val textX = box.right - pad - width
        paint.color = palette.attributionPlate
        canvas.drawRect(textX - pad, box.y.toFloat(), box.right.toFloat(), box.bottom.toFloat(), paint)
        paint.color = palette.attribution
        canvas.drawText(text, textX, box.y + pad + paint.textSize * 0.8f, paint)
    }

    /**
     * One maneuver icon (P7.4) in [box], from [Maneuvers.glyph] of the value the cluster
     * is sent. [colour] is the path taken, [faint] the road not taken, and [ground] what
     * it is drawn over, for the pin's hole.
     *
     * Drawn from strokes rather than set from a font, as ☀/☾ is, so no glyph can fall back
     * to a missing character; and in one small vocabulary — a stem from the bottom, a
     * branch at the maneuver's angle, an arrowhead — so every turn reads the same way.
     */
    private fun drawManeuver(
        canvas: Canvas,
        paint: Paint,
        glyph: ManeuverGlyph,
        box: Box,
        colour: Int,
        faint: Int,
        ground: Int,
    ) {
        val s = minOf(box.width, box.height).toFloat()
        if (s <= 0f || glyph.shape == GlyphShape.NONE) return
        val cx = box.centerX.toFloat()
        val cy = box.centerY.toFloat()
        val bottom = cy + s * 0.42f
        paint.strokeWidth = s * 0.11f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        fun stroke(path: Path, c: Int) {
            paint.style = Paint.Style.STROKE
            paint.color = c
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }
        when (glyph.shape) {
            GlyphShape.TURN, GlyphShape.KEEP, GlyphShape.RAMP -> {
                val radians = Math.toRadians(glyph.degrees.toDouble())
                val dx = Math.sin(radians).toFloat()
                val dy = -Math.cos(radians).toFloat()
                val reach = s * (if (glyph.shape == GlyphShape.TURN) 0.3f else 0.28f)
                // A turn leans to one side; moving the stem the other way keeps the whole
                // icon about the middle of its box.
                val px = if (glyph.shape == GlyphShape.TURN) cx - dx * reach * 0.6f else cx
                val py = when {
                    glyph.shape != GlyphShape.TURN -> cy + s * 0.1f
                    glyph.degrees == 0 -> cy - s * 0.2f
                    Math.abs(glyph.degrees) > 90 -> cy - s * 0.12f
                    else -> cy + s * 0.05f
                }
                if (glyph.shape == GlyphShape.KEEP) {
                    // The fork's other side, faint, so "keep left" reads as a choice made.
                    stroke(Path().apply { moveTo(px, py); lineTo(px - dx * reach, py + dy * reach) }, faint)
                } else if (glyph.shape == GlyphShape.RAMP) {
                    // The road carrying straight on past the exit.
                    stroke(Path().apply { moveTo(px, py); lineTo(px, cy - s * 0.4f) }, faint)
                }
                val endX = if (glyph.degrees == 0) px else px + dx * reach
                val endY = if (glyph.degrees == 0) py else py + dy * reach
                stroke(Path().apply { moveTo(px, bottom); lineTo(px, py); lineTo(endX, endY) }, colour)
                arrowhead(canvas, paint, endX, endY, dx, dy, s, colour)
            }
            GlyphShape.U_TURN -> {
                // Up one leg, over the top, down the other; left-hand U-turns turn left.
                val side = if (glyph.degrees < 0) 1f else -1f
                val r = s * 0.16f
                val top = cy - s * 0.1f
                val path = Path().apply {
                    moveTo(cx + side * r, cy + s * 0.38f)
                    lineTo(cx + side * r, top)
                    arcTo(RectF(cx - r, top - r, cx + r, top + r), if (side > 0) 0f else 180f, if (side > 0) -180f else 180f)
                    lineTo(cx - side * r, cy + s * 0.1f)
                }
                stroke(path, colour)
                arrowhead(canvas, paint, cx - side * r, cy + s * 0.1f, 0f, 1f, s, colour)
            }
            GlyphShape.ROUNDABOUT -> {
                val ringY = cy - s * 0.08f
                val r = s * 0.2f
                stroke(Path().apply { moveTo(cx, bottom); lineTo(cx, ringY + r) }, colour)
                paint.style = Paint.Style.STROKE
                paint.color = colour
                canvas.drawCircle(cx, ringY, r, paint)
                paint.style = Paint.Style.FILL
                // Which way round: counter-clockwise (right-hand traffic) goes up the
                // ring's right side, clockwise up its left.
                val sideX = cx - glyph.degrees * r
                arrowhead(canvas, paint, sideX, ringY + s * 0.02f, 0f, -1f, s * 0.7f, colour)
                if (glyph.exit > 0) {
                    val typeface = paint.typeface
                    paint.typeface = Typeface.DEFAULT_BOLD
                    paint.textAlign = Paint.Align.CENTER
                    paint.textSize = s * 0.22f
                    canvas.drawText(glyph.exit.toString(), cx, ringY + paint.textSize * 0.36f, paint)
                    paint.textAlign = Paint.Align.LEFT
                    paint.typeface = typeface
                }
            }
            GlyphShape.DESTINATION -> {
                // A map pin: a disc over a point, with a hole of the ground's colour.
                paint.style = Paint.Style.FILL
                paint.color = colour
                val headY = cy - s * 0.12f
                canvas.drawCircle(cx, headY, s * 0.2f, paint)
                canvas.drawPath(
                    Path().apply {
                        moveTo(cx - s * 0.17f, headY + s * 0.1f)
                        lineTo(cx + s * 0.17f, headY + s * 0.1f)
                        lineTo(cx, cy + s * 0.4f)
                        close()
                    },
                    paint,
                )
                paint.color = ground
                canvas.drawCircle(cx, headY, s * 0.08f, paint)
            }
            GlyphShape.NONE -> {}
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
    }

    /** A filled arrowhead with its base centred on ([x], [y]), pointing along ([dx], [dy]). */
    private fun arrowhead(canvas: Canvas, paint: Paint, x: Float, y: Float, dx: Float, dy: Float, s: Float, colour: Int) {
        val length = s * 0.2f
        val half = s * 0.16f
        paint.style = Paint.Style.FILL
        paint.color = colour
        canvas.drawPath(
            Path().apply {
                moveTo(x + dx * length, y + dy * length)
                lineTo(x - dy * half, y + dx * half)
                lineTo(x + dy * half, y - dx * half)
                close()
            },
            paint,
        )
    }

    /**
     * The position source, wherever the map has put it.
     *
     * Clipped to the pane rather than trusted to stay inside it: a marker a pixel past
     * the edge would be drawn on the chrome beside the map, where it would read as a
     * vehicle sitting in the media pane.
     */
    private fun drawVehicle(
        canvas: Canvas,
        paint: Paint,
        map: Box,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        val (x, y) = snapshot.mapMarker ?: return
        canvas.save()
        canvas.clipRect(rect(map))
        val size = map.height / 60f
        val arrow = snapshot.mapArrow
        // The driver's colour (P7.2), with the rim that colour was chosen with: the dot
        // gets one too now, because a white or yellow dot on the day map has no edge
        // without it.
        val colour = snapshot.markerColour
        if (arrow == null) {
            paint.color = colour.fill
            canvas.drawCircle(x.toFloat(), y.toFloat(), size, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1.5f, size / 4f)
            paint.color = colour.rim
            canvas.drawCircle(x.toFloat(), y.toFloat(), size, paint)
            paint.style = Paint.Style.FILL
        } else {
            // A notched arrowhead with its tip along the direction of travel, pivoting on
            // the fix itself so it sits where the dot did. Bigger than the dot and rimmed,
            // because an arrow's thin tip is what disappears into a busy map first.
            val r = size * 2.2f
            canvas.translate(x.toFloat(), y.toFloat())
            canvas.rotate(arrow.toFloat())
            val path = Path().apply {
                moveTo(0f, -r)
                lineTo(r * 0.72f, r * 0.8f)
                lineTo(0f, r * 0.38f)
                lineTo(-r * 0.72f, r * 0.8f)
                close()
            }
            paint.color = colour.fill
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = maxOf(1.5f, r / 7f)
            paint.color = colour.rim
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            paint.strokeJoin = Paint.Join.MITER
        }
        canvas.restore()
    }

    /**
     * The floating bubbles in the pane's bottom-left corner: recentre, and the compass
     * that switches between north-up and direction-of-travel-up; and in its bottom-right
     * corner, above the credit, + and − (P7.2).
     *
     * Translucent plates rather than opaque ones, so they read as floating over the map
     * instead of as holes punched in it, and so a street running under one is still
     * followable. They are drawn after the pane is punched, which is what puts them over
     * the map texture rather than under it.
     *
     * Each gets a rim, for the same reason the attribution gets a backing plate: the map
     * underneath is arbitrary colours, and the first lab frame had the plates vanish
     * into a dark navy suburb, leaving a needle and a crosshair floating on nothing.
     */
    private fun drawMapControls(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        DashboardLayout.MAP_ACTIONS.forEachIndexed { index, action ->
            val box = layout.mapBubble(index)
            val cx = box.centerX.toFloat()
            val cy = box.centerY.toFloat()
            val radius = box.width / 2f
            drawBubble(canvas, paint, box, snapshot.pressedMapBubble == action, palette)
            if (action == "recentre") {
                drawRecentre(canvas, paint, cx, cy, radius * 0.55f, snapshot.mapFollowing, palette)
            } else {
                // The needle points at north wherever the map has put north, so the
                // bubble says which way is up as well as being the way to change it.
                drawCompass(canvas, paint, cx, cy, radius * 0.58f, -snapshot.mapRotation.toDouble(), palette)
                if (snapshot.mapOrientation == MapOrientation.HEADING_UP) {
                    // A lit rim for the mode a glance cannot otherwise tell apart: due
                    // north on a straight northbound road looks exactly like north-up.
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = maxOf(2f, radius / 9f)
                    paint.color = palette.accent
                    canvas.drawCircle(cx, cy, radius - paint.strokeWidth / 2f, paint)
                }
            }
            paint.style = Paint.Style.FILL
        }
        // P7.2: + above −, bottom-right, one zoom level a press.
        DashboardLayout.ZOOM_ACTIONS.forEachIndexed { index, action ->
            val box = layout.zoomBubble(index)
            drawBubble(canvas, paint, box, snapshot.pressedZoom == action, palette)
            val cx = box.centerX.toFloat()
            val cy = box.centerY.toFloat()
            val arm = box.width * 0.24f
            val half = maxOf(1.5f, box.width / 22f)
            paint.color = palette.text
            canvas.drawRect(cx - arm, cy - half, cx + arm, cy + half, paint)
            if (action == "zoom-in") canvas.drawRect(cx - half, cy - arm, cx + half, cy + arm, paint)
        }
    }

    /**
     * The Home Assistant button: a house with a small circuit inside, after Home Assistant's
     * own mark. In the header it sits like Ⓟ and ☀/☾, lit the same way when pressed; over
     * the map it is a bubble. While a press is sending, an accent ring; once answered, green
     * with a tick or red with a cross for [Dashboard.HOME_ASSISTANT_RESULT_MS], the answer
     * and decline colours, with the shape saying it too for a driver who cannot tell the two
     * apart.
     */
    private fun drawHomeAssistant(
        canvas: Canvas,
        paint: Paint,
        box: Box,
        button: HomeAssistantButton,
        palette: DashboardPalette,
        header: Boolean,
    ) {
        val cx = box.centerX.toFloat()
        val cy = box.centerY.toFloat()
        // In the header, the disc the other icons' glyphs would fit, so it reads as one of them.
        val radius = if (header) box.height * 0.36f else box.width / 2f
        val answered = when (button.state) {
            HomeAssistantState.OK -> palette.answer
            HomeAssistantState.FAILED -> palette.decline
            else -> null
        }
        if (header) {
            paint.style = Paint.Style.FILL
            if (button.pressed) {
                paint.color = palette.tilePressed
                val inset = box.height / 20f
                canvas.drawRoundRect(box.x + inset, box.y + inset, box.right - inset, box.bottom - inset, CORNER, CORNER, paint)
            }
        } else {
            drawBubble(canvas, paint, box, button.pressed, palette)
        }
        if (answered != null) {
            paint.color = answered
            canvas.drawCircle(cx, cy, radius * 0.94f, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = maxOf(2f, radius / 9f)
        paint.color = if (answered != null) palette.onAction else palette.text
        val r = radius * 0.5f
        when (button.state) {
            HomeAssistantState.OK -> {
                val tick = Path()
                tick.moveTo(cx - r * 0.8f, cy)
                tick.lineTo(cx - r * 0.2f, cy + r * 0.6f)
                tick.lineTo(cx + r * 0.85f, cy - r * 0.6f)
                canvas.drawPath(tick, paint)
            }
            HomeAssistantState.FAILED -> {
                canvas.drawLine(cx - r * 0.6f, cy - r * 0.6f, cx + r * 0.6f, cy + r * 0.6f, paint)
                canvas.drawLine(cx + r * 0.6f, cy - r * 0.6f, cx - r * 0.6f, cy + r * 0.6f, paint)
            }
            else -> {
                // The house: a roof to a point and walls down to a flat floor.
                val house = Path()
                house.moveTo(cx - r * 0.8f, cy + r * 0.85f)
                house.lineTo(cx - r * 0.8f, cy - r * 0.1f)
                house.lineTo(cx, cy - r * 0.9f)
                house.lineTo(cx + r * 0.8f, cy - r * 0.1f)
                house.lineTo(cx + r * 0.8f, cy + r * 0.85f)
                house.close()
                canvas.drawPath(house, paint)
                // The circuit: a stem from the floor with a node at its top and one on each branch.
                paint.strokeWidth = maxOf(1.5f, radius / 14f)
                canvas.drawLine(cx, cy + r * 0.85f, cx, cy - r * 0.2f, paint)
                canvas.drawLine(cx, cy + r * 0.45f, cx - r * 0.35f, cy + r * 0.15f, paint)
                canvas.drawLine(cx, cy + r * 0.3f, cx + r * 0.35f, cy, paint)
                paint.style = Paint.Style.FILL
                val node = maxOf(1.5f, r * 0.13f)
                canvas.drawCircle(cx, cy - r * 0.25f, node, paint)
                canvas.drawCircle(cx - r * 0.38f, cy + r * 0.12f, node, paint)
                canvas.drawCircle(cx + r * 0.38f, cy - r * 0.03f, node, paint)
                if (button.state == HomeAssistantState.SENDING) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = maxOf(2f, radius / 9f)
                    paint.color = palette.accent
                    canvas.drawCircle(cx, cy, radius - paint.strokeWidth / 2f, paint)
                }
            }
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
        paint.style = Paint.Style.FILL
    }

    /**
     * The live alternatives' "+6 min" bubbles: a rounded plate in the map bubbles' colours,
     * lit while pressed, with the time on it. Clipped to the pane like the vehicle marker.
     */
    private fun drawAlternativeBubbles(
        canvas: Canvas,
        paint: Paint,
        map: Box,
        snapshot: Dashboard.Snapshot,
        palette: DashboardPalette,
    ) {
        if (snapshot.alternativeBubbles.isEmpty()) return
        canvas.save()
        canvas.clipRect(rect(map))
        for (bubble in snapshot.alternativeBubbles) {
            val box = bubble.box
            val radius = box.height / 2f
            paint.style = Paint.Style.FILL
            paint.color = if (bubble.pressed) palette.bubblePressed else palette.bubble
            canvas.drawRoundRect(rect(box), radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1f, radius / 8f)
            paint.color = palette.bubbleRim
            canvas.drawRoundRect(rect(box), radius, radius, paint)
            paint.style = Paint.Style.FILL
            paint.color = palette.text
            paint.textSize = box.height * 0.5f
            val width = paint.measureText(bubble.label)
            canvas.drawText(bubble.label, box.centerX - width / 2f, box.centerY + paint.textSize * 0.36f, paint)
        }
        canvas.restore()
    }

    /**
     * P11.5: Gas, Food and Shop along the top of the map pane, plates like the bubbles'
     * with a rounded end, each with its marker's colour as a dot before the word. Lit, the
     * plate fills with the accent and the word takes [DashboardPalette.onAction], the pair
     * already held to 4.5:1 for the call buttons' words; pressed, it takes the pressed fill.
     */
    private fun drawPoiButtons(canvas: Canvas, paint: Paint, layout: DashboardLayout, buttons: PoiButtons, palette: DashboardPalette) {
        PoiRules.ORDER.forEachIndexed { index, kind ->
            val box = layout.poiButton(index, buttons.top)
            val lit = kind in buttons.lit
            val radius = box.height / 2f
            paint.style = Paint.Style.FILL
            paint.color = when {
                buttons.pressed == kind -> palette.bubblePressed
                lit -> palette.accent
                else -> palette.bubble
            }
            canvas.drawCircle(box.centerX.toFloat(), box.centerY.toFloat(), radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1f, radius / 16f)
            paint.color = palette.bubbleRim
            canvas.drawCircle(box.centerX.toFloat(), box.centerY.toFloat(), radius, paint)
            paint.style = Paint.Style.FILL
            // The map marker's own icon, so a button and the markers it shows look alike;
            // dimmed while off, full while lit on the accent.
            val icon = poiButtonIcon(kind, (box.height * 0.74f).toInt())
            val alpha = paint.alpha
            paint.alpha = if (lit || buttons.pressed == kind) 255 else 150
            canvas.drawBitmap(icon, box.centerX - icon.width / 2f, box.centerY - icon.height / 2f, paint)
            paint.alpha = alpha
        }
    }

    /** One icon per kind and size, made once: [PoiMarkerArt] allocates a bitmap each call. */
    private val poiButtonIcons = HashMap<Pair<PoiKind, Int>, android.graphics.Bitmap>()

    private fun poiButtonIcon(kind: PoiKind, size: Int): android.graphics.Bitmap =
        poiButtonIcons.getOrPut(kind to size) { PoiMarkerArt.draw(kind, size) }

    /**
     * P11.5: the POI card in the flex tile (plan §6): one place large — its name, what it is
     * and how far, and the open-now line when its hours parse — or up to three, one row each
     * with the chosen one lit (P11.5a's stops). Go (or Add stop) in the accent and Close
     * along the bottom. The names are drawn here and nowhere else.
     */
    private fun drawPoiCard(canvas: Canvas, paint: Paint, layout: DashboardLayout, card: PoiCardView, palette: DashboardPalette) {
        val list = layout.poiCardList
        if (list.width <= 0 || list.height <= 0) return
        val typeface = paint.typeface
        card.message?.takeIf { card.rows.isEmpty() }?.let { message ->
            // A notice (P11.5a: nothing ahead): its one line, and Close alone in its usual place.
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = palette.text
            paint.textSize = minOf(list.height * 0.2f, list.width / 11f)
            val margin = maxOf(3f, layout.margin / 2f)
            canvas.drawText(fit(paint, message, list.width - 2 * margin), list.x + margin, list.y + list.height * 0.4f, paint)
            paint.typeface = typeface
            drawCallButton(
                canvas, paint, layout, layout.poiCardButton(1), "Close", palette.button, palette.text,
                card.pressed == "close", palette, 0.42f,
            )
            return
        }
        if (card.rows.isEmpty()) return
        val count = card.rows.size
        card.rows.forEachIndexed { index, row ->
            val box = layout.poiCardRow(index, count)
            if (count > 1 && (index == card.selected || index == card.pressedRow)) {
                paint.color = palette.tilePressed
                canvas.drawRoundRect(rect(box), CORNER / 2, CORNER / 2, paint)
            }
            val x = (box.x + layout.margin / 2).toFloat()
            val textWidth = (box.width - layout.margin).toFloat()
            // The kind's colour as a bar at the left, tying the row to its marker.
            row.kind?.let { kind ->
                paint.color = PoiRules.colour(kind)
                val bar = maxOf(3f, layout.margin / 2f)
                canvas.drawRect(box.x.toFloat(), box.y + box.height * 0.15f, box.x + bar, box.bottom - box.height * 0.15f, paint)
            }
            val left = x + maxOf(3f, layout.margin / 2f)
            val width = textWidth - maxOf(3f, layout.margin / 2f)
            if (count == 1) {
                val h = box.height.toFloat()
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.color = palette.text
                paint.textSize = minOf(h * 0.26f, list.width / 9f)
                canvas.drawText(fit(paint, row.name, width), left, box.y + h * 0.3f, paint)
                paint.typeface = typeface
                paint.textSize = minOf(h * 0.19f, list.width / 12f)
                paint.color = palette.dim
                canvas.drawText(fit(paint, row.detail, width), left, box.y + h * 0.6f, paint)
                row.open?.let {
                    paint.color = if (it == PoiRules.CLOSED) palette.warning else palette.text
                    canvas.drawText(fit(paint, it, width), left, box.y + h * 0.88f, paint)
                }
            } else {
                val h = box.height.toFloat()
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.color = palette.text
                paint.textSize = h * 0.38f
                canvas.drawText(fit(paint, row.name, width), left, box.y + h * 0.44f, paint)
                paint.typeface = typeface
                paint.textSize = h * 0.28f
                paint.color = palette.dim
                val detail = if (row.open != null) "${row.detail} · ${row.open}" else row.detail
                canvas.drawText(fit(paint, detail, width), left, box.y + h * 0.86f, paint)
            }
        }
        paint.typeface = typeface
        drawCallButton(
            canvas, paint, layout, layout.poiCardButton(0), card.primary, palette.accent, palette.onAction,
            card.pressed == "primary", palette, 0.42f,
        )
        drawCallButton(
            canvas, paint, layout, layout.poiCardButton(1), "Close", palette.button, palette.text,
            card.pressed == "close", palette, 0.42f,
        )
    }

    /** One floating plate with its rim, lit while pressed. */
    private fun drawBubble(canvas: Canvas, paint: Paint, box: Box, pressed: Boolean, palette: DashboardPalette) {
        val cx = box.centerX.toFloat()
        val cy = box.centerY.toFloat()
        val radius = box.width / 2f
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) palette.bubblePressed else palette.bubble
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, radius / 16f)
        paint.color = palette.bubbleRim
        canvas.drawCircle(cx, cy, radius - paint.strokeWidth / 2f, paint)
        paint.style = Paint.Style.FILL
    }

    /**
     * A crosshair over a dot. Accent while the map is following the vehicle and plain
     * when it is not, so the bubble reports the state it changes.
     */
    private fun drawRecentre(
        canvas: Canvas,
        paint: Paint,
        cx: Float,
        cy: Float,
        radius: Float,
        following: Boolean,
        palette: DashboardPalette,
    ) {
        paint.color = if (following) palette.accent else palette.text
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1.5f, radius / 6f)
        canvas.drawCircle(cx, cy, radius * 0.6f, paint)
        val tick = radius * 0.45f
        canvas.drawLine(cx, cy - radius, cx, cy - radius + tick, paint)
        canvas.drawLine(cx, cy + radius, cx, cy + radius - tick, paint)
        canvas.drawLine(cx - radius, cy, cx - radius + tick, cy, paint)
        canvas.drawLine(cx + radius, cy, cx + radius - tick, cy, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius * 0.22f, paint)
    }

    /**
     * A clock time from [x] at [size], with a 12-hour time's AM/PM marker after it at
     * [markerSize] ([ClockFormat.split]), so "6:40 PM" takes little more room than
     * "18:40". Anything else is drawn whole at [size]. The digits are cut to [width]
     * like any text, and the marker is left out rather than cut. Returns the width drawn.
     */
    private fun drawClock(
        canvas: Canvas,
        paint: Paint,
        time: String,
        x: Float,
        baseline: Float,
        size: Float,
        markerSize: Float,
        width: Float,
    ): Float {
        val (digits, marker) = ClockFormat.split(time)
        paint.textSize = size
        val text = fit(paint, digits, width)
        canvas.drawText(text, x, baseline, paint)
        var drawn = paint.measureText(text)
        if (marker != null && text == digits) {
            val gap = size * 0.12f
            paint.textSize = markerSize
            val markerWidth = paint.measureText(marker)
            if (drawn + gap + markerWidth <= width) {
                canvas.drawText(marker, x + drawn + gap, baseline, paint)
                drawn += gap + markerWidth
            }
            paint.textSize = size
        }
        return drawn
    }

    private fun rect(box: Box) =
        RectF(box.x.toFloat(), box.y.toFloat(), box.right.toFloat(), box.bottom.toFloat())

    /** [text] truncated with an ellipsis to [width] pixels under [paint]. */
    private fun fit(paint: Paint, text: String, width: Float): String {
        if (width <= 0f) return ""
        if (paint.measureText(text) <= width) return text
        val room = width - paint.measureText(ELLIPSIS)
        if (room <= 0f) return ELLIPSIS
        return text.take(paint.breakText(text, true, room, null)) + ELLIPSIS
    }

    /** The eight-point compass name for a bearing in degrees. */
    fun compassPoint(bearing: Double): String {
        val normalised = ((bearing % 360) + 360) % 360
        return POINTS[(Math.round(normalised / 45.0).toInt()) % 8]
    }

    private val POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    private val TAIL = Math.toRadians(140.0)
    private const val CORNER = 12f
    private const val ELLIPSIS = "…"
    private const val ATTRIBUTION = "© OpenStreetMap contributors (ODbL)"
}
