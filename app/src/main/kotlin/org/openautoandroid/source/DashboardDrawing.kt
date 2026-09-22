package org.openautoandroid.source

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF

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
        val night = snapshot.restriction.contains("night=true")
        val palette = if (night) Palette.NIGHT else Palette.DAY

        canvas.drawColor(palette.background, PorterDuff.Mode.SRC)
        paint.style = Paint.Style.FILL

        drawHeader(canvas, paint, layout, snapshot, palette)
        drawMedia(canvas, paint, layout, snapshot, palette, art)
        drawTiles(canvas, paint, layout, snapshot, palette)

        // Punched last so nothing drawn above can bleed into the pane, and so the map
        // texture underneath is what the head unit actually sees there.
        canvas.drawRect(
            layout.map.x.toFloat(), layout.map.y.toFloat(),
            layout.map.right.toFloat(), layout.map.bottom.toFloat(), clear,
        )
        drawMapOverlay(canvas, paint, layout, snapshot, palette)
    }

    /** Clock on the left, vehicle motion in the middle, phone status on the right. */
    private fun drawHeader(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: Palette,
    ) {
        paint.color = palette.header
        canvas.drawRect(
            layout.x.toFloat(), layout.y.toFloat(),
            (layout.x + layout.width).toFloat(), (layout.y + layout.header).toFloat(), paint,
        )

        val big = layout.header * 0.52f
        val small = layout.header * 0.24f
        val baseline = layout.y + layout.header * 0.62f
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

        // The mark that says a drawn number rests on an inferred scale rather than a
        // measured one. It is deliberately in the header rather than a report: the run
        // that settles it is a drive, and the driver is the one who can see it.
        if (snapshot.unvalidated) {
            paint.textSize = small
            paint.color = palette.warning
            canvas.drawText(UNVALIDATED, cursor, baseline, paint)
            cursor += paint.measureText(UNVALIDATED) + layout.margin * 2
        }

        paint.color = palette.dim
        paint.textSize = small
        val second = baseline + small * 1.15f
        val left = (layout.x + layout.margin).toFloat()
        canvas.drawText(
            "gear ${snapshot.vehicle.gearName()}  ${snapshot.restriction}  " +
                "sats ${snapshot.vehicle.satellitesUsed ?: "-"}/${snapshot.vehicle.satelliteCount ?: "-"}",
            left, second, paint,
        )

        drawPhoneStatus(canvas, paint, layout, snapshot, palette, baseline, second)
    }

    /** Right-aligned: position source, signal, battery. */
    private fun drawPhoneStatus(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: Palette,
        baseline: Float,
        second: Float,
    ) {
        val right = (layout.x + layout.width - layout.margin).toFloat()
        val small = layout.header * 0.24f
        paint.textSize = small

        val battery = snapshot.phone.batteryPercent
        val batteryText = if (battery < 0) "—%" else "$battery%"
        val charge = if (snapshot.phone.charging) "+" else ""
        val line = "$charge$batteryText"
        paint.color = when {
            battery in 0..15 && !snapshot.phone.charging -> palette.warning
            else -> palette.text
        }
        paint.textSize = layout.header * 0.34f
        canvas.drawText(line, right - paint.measureText(line), baseline, paint)

        paint.textSize = small
        paint.color = palette.dim
        val bars = snapshot.phone.signalBars
        val signal = if (bars < 0) "—" else "▂▄▆█".take(maxOf(1, bars))
        val status = "src ${snapshot.source.label}  ${snapshot.phone.network} $signal"
        canvas.drawText(status, right - paint.measureText(status), second, paint)
    }

    /** A filled needle pointing along the bearing, north being up. */
    private fun drawCompass(
        canvas: Canvas,
        paint: Paint,
        cx: Float,
        cy: Float,
        radius: Float,
        bearing: Double,
        palette: Palette,
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
        palette: Palette,
    ) {
        layout.tiles.forEachIndexed { index, tile ->
            paint.color = if (snapshot.pressedTile == index) palette.tilePressed else palette.tile
            canvas.drawRoundRect(rect(tile), CORNER, CORNER, paint)
            paint.color = palette.dim
            paint.textSize = tile.height / 12f
            canvas.drawText(
                DashboardLayout.TILE_LABELS.getOrElse(index) { "tile ${index + 1}" },
                (tile.x + layout.margin).toFloat(),
                (tile.y + tile.height / 9f) + tile.height / 24f,
                paint,
            )
            // Named and empty on purpose: the slot exists, nothing is assigned to it
            // yet, and an operator should be able to see that from the seat rather
            // than wonder whether a feature has failed.
            paint.color = palette.dim
            paint.textSize = tile.height / 14f
            val text = "not configured"
            canvas.drawText(
                text,
                tile.centerX - paint.measureText(text) / 2f,
                tile.centerY.toFloat(),
                paint,
            )
        }
    }

    /** The full-width now-playing pane: cover art, two lines of text, three buttons. */
    private fun drawMedia(
        canvas: Canvas,
        paint: Paint,
        layout: DashboardLayout,
        snapshot: Dashboard.Snapshot,
        palette: Palette,
        art: android.graphics.Bitmap?,
    ) {
        val tile = layout.media
        paint.color = palette.tile
        canvas.drawRoundRect(rect(tile), CORNER, CORNER, paint)

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
        palette: Palette,
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
        palette: Palette,
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
            paint.textSize = maxOf(10f, map.height / 40f)
            val width = paint.measureText(ATTRIBUTION)
            val textX = map.right - layout.margin - width
            val textY = map.bottom - layout.margin / 2f
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
            drawVehicle(canvas, paint, map, snapshot, palette)
            drawMapControls(canvas, paint, layout, snapshot, palette)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, layout.height / 540f)
        paint.color = palette.tile
        canvas.drawRoundRect(rect(map), CORNER, CORNER, paint)
        paint.style = Paint.Style.FILL
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
        palette: Palette,
    ) {
        val (x, y) = snapshot.mapMarker ?: return
        canvas.save()
        canvas.clipRect(rect(map))
        val size = map.height / 60f
        val arrow = snapshot.mapArrow
        if (arrow == null) {
            paint.color = palette.accent
            canvas.drawCircle(x.toFloat(), y.toFloat(), size, paint)
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
            paint.color = palette.accent
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = maxOf(1.5f, r / 7f)
            paint.color = palette.bubbleRim
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            paint.strokeJoin = Paint.Join.MITER
        }
        canvas.restore()
    }

    /**
     * The floating bubbles in the pane's bottom-left corner: recentre, and the compass
     * that switches between north-up and direction-of-travel-up.
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
        palette: Palette,
    ) {
        DashboardLayout.MAP_ACTIONS.forEachIndexed { index, action ->
            val box = layout.mapBubble(index)
            val cx = box.centerX.toFloat()
            val cy = box.centerY.toFloat()
            val radius = box.width / 2f
            paint.style = Paint.Style.FILL
            paint.color =
                if (snapshot.pressedMapBubble == action) palette.bubblePressed else palette.bubble
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1f, radius / 16f)
            paint.color = palette.bubbleRim
            canvas.drawCircle(cx, cy, radius - paint.strokeWidth / 2f, paint)
            paint.style = Paint.Style.FILL
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
        palette: Palette,
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
    private const val UNVALIDATED = "scale inferred"
    private const val ATTRIBUTION = "© OpenStreetMap contributors (ODbL)"

    /** Day and night colours; the night set is what pairs with VTM's NEWTRON theme. */
    data class Palette(
        val background: Int,
        val header: Int,
        val tile: Int,
        val tilePressed: Int,
        val button: Int,
        val text: Int,
        val dim: Int,
        val accent: Int,
        val warning: Int,
        val attribution: Int,
        /** Backing plate, so the credit stays legible over any map colour. */
        val attributionPlate: Int,
        /** The floating map controls: mostly opaque, so the map still shows under them. */
        val bubble: Int,
        val bubblePressed: Int,
        /** Their edge, so the plate has one over a map that happens to match it. */
        val bubbleRim: Int,
    ) {
        companion object {
            val DAY = Palette(
                background = 0xFF14171A.toInt(),
                header = 0xFF1F2429.toInt(),
                tile = 0xFF262C33.toInt(),
                tilePressed = 0xFF39424C.toInt(),
                button = 0xFF39424C.toInt(),
                text = 0xFFF2F4F6.toInt(),
                dim = 0xFF9AA5B1.toInt(),
                accent = 0xFF4E9AF1.toInt(),
                warning = 0xFFE2B33C.toInt(),
                attribution = 0xFF12161A.toInt(),
                attributionPlate = 0xCCF2F4F6.toInt(),
                bubble = 0xE62B323A.toInt(),
                bubblePressed = 0xF24A5666.toInt(),
                bubbleRim = 0xB39AA5B1.toInt(),
            )
            val NIGHT = Palette(
                background = 0xFF07090B.toInt(),
                header = 0xFF0E1216.toInt(),
                tile = 0xFF141A20.toInt(),
                tilePressed = 0xFF223040.toInt(),
                button = 0xFF1C2630.toInt(),
                text = 0xFFD8DEE4.toInt(),
                dim = 0xFF6C7884.toInt(),
                accent = 0xFF2F6FB8.toInt(),
                warning = 0xFFB98F2A.toInt(),
                attribution = 0xFFE8EDF2.toInt(),
                attributionPlate = 0xCC0A0D10.toInt(),
                bubble = 0xE61C2630.toInt(),
                bubblePressed = 0xF2334458.toInt(),
                bubbleRim = 0xB36C7884.toInt(),
            )
        }
    }
}
