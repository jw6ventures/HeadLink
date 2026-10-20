package com.jw6ventures.headlink

import java.time.ZoneId

/** A rectangle in video frame coordinates. Plain JVM, so tools/jni-host-test can check it. */
data class Box(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2

    fun contains(px: Int, py: Int): Boolean = px >= x && px < right && py >= y && py < bottom
}

/** A live alternative as the dashboard knows it: which one, where its bubble goes, and what it says. */
data class AlternativeMark(val id: Long, val latitude: Double, val longitude: Double, val label: String)

/** An alternative's bubble on this frame: where, what it says, and whether a finger is on it. */
data class AlternativeBubble(val id: Long, val box: Box, val label: String, val pressed: Boolean)

/**
 * The offer as the divided turn card draws it (P9.6, docs/p9-plan.md §7): "Faster: −7 min",
 * the reason, and which of Take and Keep a finger is on. Words only, so the chrome repaints
 * when what it says changes and not when the traffic loop republishes the same offer.
 */
data class OfferCard(val id: Long, val label: String, val reason: String, val pressed: String?)

/** Take or Keep pressed on offer [id] (P9.6): [take] is Take. */
data class OfferAnswer(val id: Long, val take: Boolean)

/**
 * How the turn card divides while a faster way round is offered (P9.6). The plan leaves the
 * choice to lab renders at the truck's pane size (§11), so both are built.
 */
enum class OfferLayout(val key: String) {
    /** The turn card as it always is, with the offer in a band of its own beneath it. */
    STACKED("stacked"),

    /** One card: the turn at the left, two fifths wide, the offer at the right. */
    SIDE_BY_SIDE("side-by-side"),
}

/**
 * Layout of the projected dashboard inside the video content rectangle (spec §8.2): a
 * status header across the top, an owned map filling one half below it, and the other
 * half holding the media pane over two tiles, the first of which lists the saved places —
 * or, while a route exists, is the trip panel (P7.4) — and the second of which is the
 * flex tile (plan §5.8). The turn card lies across the top of the map during a route.
 *
 * The map side is configurable because which side falls to hand depends on the vehicle,
 * and getting it wrong is only discoverable from the driver's seat — the same reason
 * `scene_area` is a setting rather than a constant.
 */
class DashboardLayout @JvmOverloads constructor(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val mapOnLeft: Boolean,
    /** How the turn card divides for an offer (P9.6); [defaultOfferLayout] unless a test asks. */
    val offerLayout: OfferLayout = defaultOfferLayout,
) {
    val margin: Int = maxOf(2, width / 96)

    /** Tall enough for two lines of status at 1080p, and proportional below that. */
    val header: Int = maxOf(24, height / 9)

    private val bodyY: Int = y + header
    private val bodyHeight: Int = height - header

    val paneWidth: Int = (width - 3 * margin) / 2
    val paneHeight: Int = bodyHeight - 2 * margin

    private val leftX: Int = x + margin
    private val rightX: Int = x + 2 * margin + paneWidth

    val map: Box = Box(if (mapOnLeft) leftX else rightX, bodyY + margin, paneWidth, paneHeight)

    private val originX: Int = if (mapOnLeft) rightX else leftX
    private val rowHeight: Int = (paneHeight - margin) / 2

    /**
     * Now playing, across the full width of the half the map does not occupy.
     *
     * It takes the whole top row rather than one tile of four because it is the only
     * tile with something to show at a glance — cover art, a title and three targets
     * a driver has to hit without looking. A quarter of the half-screen made the
     * transport buttons smaller than a fingertip deserves at arm's length.
     */
    val media: Box = Box(originX, bodyY + margin, paneWidth, rowHeight)

    /** The configurable tiles, side by side under the media pane. */
    val tiles: List<Box> = run {
        val tileWidth = (paneWidth - margin) / 2
        (0 until TILES).map { index ->
            Box(
                originX + index * (tileWidth + margin),
                media.bottom + margin,
                tileWidth,
                rowHeight,
            )
        }
    }

    /**
     * Square cover art at the left of the media pane.
     *
     * Capped at a third of the pane width as well as its height: sized on height alone
     * it took nearly half the pane, which left the title truncated and squeezed the
     * three transport buttons into the remainder. The art is the least important thing
     * here — it is the only element a driver never needs to read.
     */
    val albumArt: Box = run {
        val size = minOf(media.height - 2 * margin, media.width / 3)
        val side = maxOf(0, size)
        Box(media.x + margin, media.y + (media.height - side) / 2, side, side)
    }

    /** Where the media pane's text and controls start, clear of the art. */
    private val mediaContentX: Int = albumArt.right + margin

    /**
     * Diameter of the floating map controls.
     *
     * Sized against the pane rather than the frame, and generously: these are pressed
     * from the driver's seat, at arm's length, in a moving vehicle. A control small
     * enough to look tidy on a phone is one a driver has to aim at.
     */
    val bubbleSize: Int = maxOf(24, minOf(map.width, map.height) / 8)

    /**
     * The floating controls in the map pane's bottom-left corner, stacked upwards from
     * index 0: recentre nearest the corner, the compass above it.
     *
     * The bottom-left corner because the map's other two occupied corners are taken —
     * the ODbL attribution and, since P7.2, the zoom stack sit bottom-right, and the top
     * is kept for the turn card — and because the bottom of the pane is
     * the part of it a driver is least often reading: what matters on a map that is
     * following the vehicle is ahead of the marker, not behind it.
     */
    fun mapBubble(index: Int): Box {
        val bottom = map.bottom - margin - index * (bubbleSize + margin)
        return Box(map.x + margin, bottom - bubbleSize, bubbleSize, bubbleSize)
    }

    /**
     * The floating map control at a point, or null.
     *
     * Square targets for round bubbles: a finger aimed at the middle of one can land in
     * the corner of its box, and refusing that press because the drawing is a circle
     * would make the control harder to hit than it looks. The gaps between them are a
     * whole margin wide, so a square target cannot reach its neighbour.
     */
    fun hitMapBubble(px: Int, py: Int): String? =
        MAP_ACTIONS.indices.firstOrNull { mapBubble(it).contains(px, py) }?.let { MAP_ACTIONS[it] }

    /**
     * Text size of the ODbL credit in the map pane's bottom-right corner. Here rather than
     * in the drawing because the zoom bubbles stack above it, and the two must agree.
     */
    val attributionTextSize: Int = maxOf(10, map.height / 40)

    /**
     * The top of the credit's backing plate. Its width depends on the font, so only its
     * height is laid out; it runs leftwards from the pane's right edge, under the zoom
     * stack and clear of the bottom-left one.
     */
    val attributionTop: Int = map.bottom - margin / 2 - attributionTextSize

    /**
     * The zoom bubbles (P7.2, plan §4.1), + above −, in the pane's bottom-right corner
     * above the credit: index 0 is + and 1 is −, in [ZOOM_ACTIONS] order.
     *
     * The bottom-right because the plan decided it (§1.1): four bubbles in the one
     * bottom-left stack would climb more than half the pane, and the top of the pane is
     * kept for the turn card. The same size and spacing as the left stack, so a finger
     * that has learned one knows the other.
     */
    fun zoomBubble(index: Int): Box {
        val fromBottom = ZOOM_ACTIONS.size - 1 - index
        val bottom = attributionTop - margin - fromBottom * (bubbleSize + margin)
        return Box(map.right - margin - bubbleSize, bottom - bubbleSize, bubbleSize, bubbleSize)
    }

    /**
     * A live alternative's "+6 min" bubble centred on ([x], [y]), kept inside the map pane:
     * as tall as three quarters of a map bubble, wide enough for "+12 min", and never
     * smaller than a finger.
     */
    fun alternativeBubble(x: Int, y: Int): Box {
        val height = maxOf(24, bubbleSize * 3 / 4)
        val width = height * 5 / 2
        val left = (x - width / 2).coerceIn(map.x, maxOf(map.x, map.right - width))
        val top = (y - height / 2).coerceIn(map.y, maxOf(map.y, map.bottom - height))
        return Box(left, top, width, height)
    }

    /** The zoom action at a point, or null. Square targets, as [hitMapBubble] explains. */
    fun hitZoomBubble(px: Int, py: Int): String? =
        ZOOM_ACTIONS.indices.firstOrNull { zoomBubble(it).contains(px, py) }?.let { ZOOM_ACTIONS[it] }

    /**
     * The battery at the header's right edge: a glyph that fills with the level, then the
     * percentage. Reserved at a fixed width so "100%" fits and the things to its left do
     * not move as the level changes.
     */
    val headerBattery: Box = run {
        val w = header * 13 / 10
        Box(x + width - margin - w, y, w, header)
    }

    /**
     * Cell signal, left of the battery: four bars for the primary SIM with its network
     * type beside them, and a row of dots under the bars for each other SIM (plan §5.2).
     */
    val headerSignal: Box = run {
        val w = header * 11 / 10
        Box(headerBattery.x - margin - w, y, w, header)
    }

    /**
     * The header's touch targets (P7.3, plan §5.1), left of the signal, in
     * [HEADER_ACTIONS] order from the left: Ⓟ, then ☀/☾.
     *
     * Header-height squares, because the header is as tall as a target can be without
     * taking room from the panes, and a margin apart, so a square target cannot reach its
     * neighbour. They run to the top of the content rectangle: a finger reaching up
     * lands on the edge of the picture first.
     */
    fun headerIcon(index: Int): Box {
        val fromRight = HEADER_ACTIONS.size - 1 - index
        val right = headerSignal.x - margin - fromRight * (header + margin)
        return Box(right - header, y, header, header)
    }

    /** The header action at a point, or null. */
    fun hitHeaderIcon(px: Int, py: Int): String? =
        HEADER_ACTIONS.indices.firstOrNull { headerIcon(it).contains(px, py) }?.let { HEADER_ACTIONS[it] }

    /**
     * How far right the header's text may run: the clock, speed and compass stop a margin
     * short of the first icon. The turn line that used to fill the rest is on the turn
     * card since P7.4, and the room it leaves stays empty (plan §5.1).
     */
    val headerTextRight: Int get() = headerIcon(0).x - margin

    /**
     * P10: the weather item (docs/p10-plan.md §5.1), a margin left of Ⓟ: full header height,
     * like the icons, and a fixed 2.2 headers wide, so the clock and the speed never move as
     * the temperature changes. The glyph and the text sit half a margin in from each other
     * and the box's edge: with whole margins, "-12°C" did not fit by the generous
     * [EM_PER_CHARACTER] estimate, and a wider box left Ford's 726x480 header too little
     * room for the clock, speed and bearing (WeatherTest). Drawn, and a target, only while
     * Show weather is on.
     */
    val headerWeather: Box = run {
        val w = header * 22 / 10
        val right = headerIcon(0).x - margin
        Box(right - w, y, w, header)
    }

    /** How far right the header's text may run: short of the weather item while it is shown. */
    fun headerTextRight(weather: Boolean): Int = if (weather) headerWeather.x - margin else headerTextRight

    /** Whether a point is on the weather item. It cannot reach Ⓟ: a margin lies between them. */
    fun hitHeaderWeather(px: Int, py: Int): Boolean = headerWeather.contains(px, py)

    /** The weather item's condition glyph, a square at its left, and its temperature's text size. */
    val headerWeatherGlyph: Int = header * 6 / 10
    val headerWeatherTextSize: Float = header * 0.42f

    /**
     * The temperature as the item has room for it (plan §5.1): [full], "78°F", else [short],
     * "78°". Judged by [EM_PER_CHARACTER], a generous width, so what is chosen here fits
     * whatever the font; the drawing still cuts rather than overruns.
     */
    fun headerWeatherText(full: String, short: String): String {
        val room = headerWeather.width - headerWeatherGlyph - margin
        return if (EM_PER_CHARACTER * headerWeatherTextSize * full.length <= room) full else short
    }

    /** The tile at a point in frame coordinates, or -1. */
    fun hitTile(px: Int, py: Int): Int = tiles.indexOfFirst { it.contains(px, py) }

    /**
     * The strip across the top of a tile that carries its label; the tile's content
     * starts under it.
     */
    fun tileTitleHeight(tile: Box): Int = tile.height / 5

    /**
     * The scrolling list inside the favorites tile, under its label and inset by the
     * margin, so a row's edge never touches the tile's.
     */
    val favoritesList: Box = run {
        val tile = tiles[FAVORITES_TILE]
        val top = tile.y + tileTitleHeight(tile)
        Box(
            tile.x + margin,
            top,
            maxOf(0, tile.width - 2 * margin),
            maxOf(0, tile.bottom - margin - top),
        )
    }

    /**
     * Height of one favorite row: [FAVORITE_ROWS] of them fill the list exactly.
     *
     * Three rather than four or five, for the reason the media buttons are as big as
     * they are: a row is pressed from the driver's seat in a moving vehicle, and the
     * list scrolls, so showing fewer at once costs a swipe where a smaller target would
     * cost a miss.
     */
    val favoriteRowHeight: Int = maxOf(1, favoritesList.height / FAVORITE_ROWS)

    /**
     * How far a finger may wander before a press on a favorite becomes a scroll of the
     * list instead: a fifth of a row, so a tap that lands and lifts with a little shake
     * is still a tap.
     */
    val favoriteSlop: Int = maxOf(4, favoriteRowHeight / 5)

    /** The row for favorite [index] with the list scrolled [scroll] pixels up. */
    fun favoriteRow(index: Int, scroll: Int): Box = listRow(favoritesList, favoriteRowHeight, index, scroll)

    /**
     * The favorite under a point with the list of [count] entries scrolled [scroll]
     * pixels up, or -1: outside the list, or on the empty space past its last entry.
     */
    fun hitFavorite(px: Int, py: Int, scroll: Int, count: Int): Int =
        hitListRow(favoritesList, favoriteRowHeight, px, py, scroll, count)

    /** The furthest the list of [count] entries can scroll: zero while they all fit. */
    fun favoritesMaxScroll(count: Int): Int = listMaxScroll(favoritesList, favoriteRowHeight, count)

    /**
     * Row [index] of a scrolling list in [list] with rows [rowHeight] tall, scrolled [scroll]
     * pixels up. The favourites (P6.6) and the notifications (P7.8) are both this shape, so
     * the arithmetic is written once.
     */
    fun listRow(list: Box, rowHeight: Int, index: Int, scroll: Int): Box =
        Box(list.x, list.y + index * rowHeight - scroll, list.width, rowHeight)

    /** The row of [count] under a point, or -1: outside [list], or past its last row. */
    fun hitListRow(list: Box, rowHeight: Int, px: Int, py: Int, scroll: Int, count: Int): Int {
        if (!list.contains(px, py)) return -1
        val index = (py - list.y + scroll) / rowHeight
        return if (index in 0 until count) index else -1
    }

    /** The furthest a list of [count] rows can scroll: zero while they all fit. */
    fun listMaxScroll(list: Box, rowHeight: Int, count: Int): Int = maxOf(0, count * rowHeight - list.height)

    // --- P7.8, P7.9: the flex tile (plan §5.8) ---------------------------------------

    /** The second tile, which shows a call, notifications, or a quiet empty state. */
    val flexTile: Box get() = tiles[FLEX_TILE]

    /** The notification list, placed in the flex tile exactly as the favourites are in theirs. */
    val notificationsList: Box = run {
        val tile = tiles[FLEX_TILE]
        val top = tile.y + tileTitleHeight(tile)
        Box(tile.x + margin, top, maxOf(0, tile.width - 2 * margin), maxOf(0, tile.bottom - margin - top))
    }

    /**
     * Height of one notification row: [NOTIFICATION_ROWS] fill the list. Two rather than
     * the favourites' three, because a row carries two lines — who and which app, large,
     * then a line of the text, small — and three would bring the small line under 12 px at
     * 800x480. It scrolls like the favourites, so fewer at once costs a swipe, not a miss.
     */
    val notificationRowHeight: Int = maxOf(1, notificationsList.height / NOTIFICATION_ROWS)

    /** A fifth of a row, as [favoriteSlop] is. */
    val notificationSlop: Int = maxOf(4, notificationRowHeight / 5)

    fun notificationRow(index: Int, scroll: Int): Box = listRow(notificationsList, notificationRowHeight, index, scroll)

    fun hitNotification(px: Int, py: Int, scroll: Int, count: Int): Int =
        hitListRow(notificationsList, notificationRowHeight, px, py, scroll, count)

    fun notificationsMaxScroll(count: Int): Int = listMaxScroll(notificationsList, notificationRowHeight, count)

    /** The caller's name on the call card, under the tile's label strip. */
    val callName: Box = run {
        val tile = tiles[FLEX_TILE]
        Box(tile.x + margin, tile.y + tileTitleHeight(tile), maxOf(0, tile.width - 2 * margin), tile.height / 5)
    }

    /** A connected call's running duration, under the name. */
    val callDuration: Box = Box(callName.x, callName.bottom, callName.width, tiles[FLEX_TILE].height / 9)

    /**
     * Answer (index 0) and Decline (1) while ringing: each half the tile's width and the
     * full height under the name (plan §5.8). The biggest targets the tile can give: a
     * ringing call wants a decision now, from the driver's seat, at arm's length.
     */
    fun ringButton(index: Int): Box {
        val tile = tiles[FLEX_TILE]
        val top = callName.bottom + margin
        val w = maxOf(0, (tile.width - 3 * margin) / 2)
        return Box(tile.x + margin + index * (w + margin), top, w, maxOf(0, tile.bottom - margin - top))
    }

    /**
     * Button [index] of [count] in a connected call's row under the duration: Mute, Keypad
     * and Hang up with full controls, or Hang up alone across the row.
     */
    fun callRowButton(index: Int, count: Int): Box {
        val tile = tiles[FLEX_TILE]
        val top = callDuration.bottom + margin
        val n = maxOf(1, count)
        val w = maxOf(0, (tile.width - (n + 1) * margin) / n)
        return Box(tile.x + margin + index * (w + margin), top, w, maxOf(0, tile.bottom - margin - top))
    }

    /** Where button [index] of [actions] ([FlexRules.actions]) is drawn and hit. */
    fun callActionBox(actions: List<String>, index: Int): Box =
        if (actions == FlexRules.RING_ACTIONS) ringButton(index) else callRowButton(index, actions.size)

    /** The call action at a point, of those [actions] the card shows, or null. */
    fun hitCallAction(px: Int, py: Int, actions: List<String>): String? =
        actions.indices.firstOrNull { callActionBox(actions, it).contains(px, py) }?.let { actions[it] }

    /**
     * The keypad (P7.9, plan §5.8): a pop-up over the whole half the map does not occupy —
     * the media pane and both tiles — so the map stays in view. Inside the tile, which is
     * about 455 px square at 1080p, twelve keys would be about 140 × 100 px; over the half
     * they are about 290 × 180.
     */
    val keypad: Box = Box(media.x, media.y, media.width, tiles[0].bottom - media.y)

    /** The strip along the keypad's top: the call's keys so far, then Close at its right. */
    private val keypadStripHeight: Int = keypad.height / 8

    /**
     * Close, at the right of the top strip: a quarter of the keypad's width, so it is a
     * target of its own and not something a finger reaching for 3 lands on.
     */
    val keypadClose: Box = run {
        val w = keypad.width / 4
        Box(keypad.right - margin - w, keypad.y + margin, w, keypadStripHeight)
    }

    /** The echo of this call's keys, left of Close. Drawn only; not a target. */
    val keypadEcho: Box = Box(
        keypad.x + margin,
        keypad.y + margin,
        maxOf(0, keypadClose.x - margin - keypad.x - margin),
        keypadStripHeight,
    )

    /** Key [index] of [FlexRules.KEYPAD_KEYS]: three columns, four rows, under the strip. */
    fun keypadKey(index: Int): Box {
        val top = keypadEcho.bottom + margin
        val w = maxOf(0, (keypad.width - 4 * margin) / 3)
        val h = maxOf(0, (keypad.bottom - margin - top - 3 * margin) / 4)
        val column = index % 3
        val row = index / 3
        return Box(keypad.x + margin + column * (w + margin), top + row * (h + margin), w, h)
    }

    /** The keypad key under a point, as an index into [FlexRules.KEYPAD_KEYS], or -1. */
    fun hitKeypadKey(px: Int, py: Int): Int =
        FlexRules.KEYPAD_KEYS.indices.firstOrNull { keypadKey(it).contains(px, py) } ?: -1

    /**
     * The three transport buttons across the lower part of the media pane, right of the
     * cover art. The text above them is not a target.
     */
    fun mediaButton(index: Int): Box {
        val row = media.y + media.height / 2
        val available = media.right - margin - mediaContentX
        val buttonWidth = (available - 2 * margin) / 3
        return Box(
            mediaContentX + index * (buttonWidth + margin),
            row,
            maxOf(0, buttonWidth),
            media.bottom - margin - row,
        )
    }

    /** Where the now-playing text goes: right of the art, above the buttons. */
    fun mediaTextX(): Int = mediaContentX

    fun mediaTextWidth(): Int = maxOf(0, media.right - margin - mediaContentX)

    /** The transport action at a point, or null. */
    fun hitMedia(px: Int, py: Int): String? =
        MEDIA_ACTIONS.indices.firstOrNull { mediaButton(it).contains(px, py) }?.let { MEDIA_ACTIONS[it] }

    /**
     * The turn card (P7.4, plan §5.3), across the top of the map pane and inset by the
     * margin like the floating controls.
     *
     * A fifth of the pane: tall enough for the distance in type a driver reads at arm's
     * length (about 90 px at 1080p, 40 at 800x480) over the street name, and short enough
     * that with the marker moved down under it ([markerBelow]) most of the road ahead
     * still shows. Full width, because the street name is the longest thing on it and is
     * truncated only when it has to be.
     */
    val turnCard: Box = run {
        val h = maxOf(24, map.height / 5)
        Box(map.x + margin, map.y + margin, maxOf(0, map.width - 2 * margin), h)
    }

    /** The maneuver icon's square at the card's left, inset by half a margin. */
    val turnIcon: Box = run {
        val inset = maxOf(1, margin / 2)
        val side = maxOf(0, turnCard.height - 2 * inset)
        Box(turnCard.x + inset, turnCard.y + inset, side, side)
    }

    /**
     * How far under the pane's centre the followed position is drawn while the card
     * shows: half the height the card takes from the top, so the marker sits in the middle
     * of the map left uncovered rather than a card's height closer to its top.
     */
    val markerBelow: Int = (turnCard.bottom - map.y) / 2

    /**
     * The voice-guidance toggle (P8.2, P8 plan §6): at the right end of End route's row,
     * End route's height, a margin clear of it.
     *
     * **Not quite the plan's square.** The row inside the tile is only about two media
     * buttons wide (415 px beside 208 px buttons at 1080p, 174 beside 93 at 800x480), so a
     * square of End route's height would leave End route narrower than the toggle, and
     * "End route" in type too small to read; the plan also asks for End route to stay the
     * widest target on the tile. So the toggle is a square only while that leaves End
     * route at least twice its width, and narrower than it is tall otherwise: a third of
     * the row at every display the project renders. The glyph is drawn to its width.
     */
    val voiceToggle: Box = run {
        val tile = tiles[FAVORITES_TILE]
        val h = minOf(mediaButton(0).height, maxOf(0, tile.height - 3 * margin))
        val w = minOf(h, maxOf(0, (tile.width - 3 * margin) / 3))
        Box(tile.right - margin - w, tile.bottom - margin - h, w, h)
    }

    /**
     * End route (P7.4, plan §5.4): along the bottom of the trip panel, which takes the
     * favourites tile's place during a trip. **The height of a media button**, as the plan
     * asks: it is the only way to cancel a route from the car, and it is the fix for a
     * mistaken favourite, so it gets the biggest target the tile can give it. Since P8.2
     * it stops a margin short of [voiceToggle], and is still the widest target there.
     */
    val endRoute: Box = run {
        val tile = tiles[FAVORITES_TILE]
        val right = if (voiceToggle.width > 0) voiceToggle.x - margin else tile.right - margin
        Box(tile.x + margin, voiceToggle.y, maxOf(0, right - tile.x - margin), voiceToggle.height)
    }

    /** Whether a point is on End route. */
    fun hitEndRoute(px: Int, py: Int): Boolean = endRoute.contains(px, py)

    /** Whether a point is on the voice toggle. */
    fun hitVoiceToggle(px: Int, py: Int): Boolean = voiceToggle.contains(px, py)

    /**
     * The trip panel's top row, above the later maneuvers: the arrival time at the left,
     * the time and distance remaining at the right. A little over half of what End route
     * leaves, because the arrival time is the one number on the panel read most.
     */
    val tripSummary: Box = run {
        val tile = tiles[FAVORITES_TILE]
        val top = tile.y + margin
        val room = maxOf(0, endRoute.y - margin - top)
        Box(tile.x + margin, top, maxOf(0, tile.width - 2 * margin), room * 11 / 20)
    }

    /**
     * The later maneuvers (plan §5.4's "next two after the current one"), side by side
     * between the summary and End route, in route order from the left.
     */
    fun tripLater(index: Int): Box {
        val top = tripSummary.bottom
        val width = maxOf(0, (tripSummary.width - margin) / RouteFollower.LATER_STEPS)
        return Box(
            tripSummary.x + index * (width + margin),
            top,
            width,
            maxOf(0, endRoute.y - margin - top),
        )
    }

    /**
     * The idle media pane's one line of help (P7.5, plan §5.5), along its bottom: shown
     * only without notification access, and small, because it is no longer the headline.
     */
    val playerHint: Box = run {
        val h = maxOf(10, media.height / 10)
        Box(media.x + margin, media.bottom - margin - h, maxOf(0, media.width - 2 * margin), h)
    }

    /**
     * Row [index] of the idle media pane's player list, with room left for [playerHint]
     * when [hint] says it is drawn.
     *
     * [PLAYER_ROWS] of them fill the pane: at the media buttons' height a pane holds one
     * row and a sliver (205 px buttons in a 410 px pane at 1080p), and one player is not a
     * list. Two rows come to 95% of a media button's height without the hint and 84% with
     * it, still bigger than a favourites row, for the same reason as those: it is pressed
     * from the driver's seat. Full width, because the cover art has nothing to show here.
     */
    fun playerRow(index: Int, hint: Boolean): Box {
        val top = media.y + margin
        val bottom = if (hint) playerHint.y else media.bottom - margin
        val h = maxOf(0, (bottom - top - (PLAYER_ROWS - 1) * margin) / PLAYER_ROWS)
        return Box(media.x + margin, top + index * (h + margin), maxOf(0, media.width - 2 * margin), h)
    }

    /**
     * The Resume button drawn at a row's right. The whole row is the target: a row does
     * one thing, and a driver aiming at the app's name should not miss.
     */
    fun resumeButton(row: Box): Box {
        val w = row.width * 3 / 8
        return Box(row.right - w, row.y, w, row.height)
    }

    /** The player row under a point, of [count] listed, or -1. */
    fun hitPlayer(px: Int, py: Int, count: Int, hint: Boolean): Int =
        (0 until minOf(count, PLAYER_ROWS)).firstOrNull { playerRow(it, hint).contains(px, py) } ?: -1

    // --- P9: live traffic (docs/p9-plan.md §6, §7) ----------------------------------

    /**
     * "Traffic © HERE" (P9.3, plan §1 clause 6.4 a): a line above the ODbL credit, in the
     * same size and on the same kind of plate, ending a margin short of the − bubble.
     *
     * Not beside the ODbL credit on its own line: at 726x480 the two together are wider
     * than the room between the corner stacks. Not above it at the pane's edge either, where
     * the zoom stack is. Here it is clear of both stacks, of the turn card and of the offer,
     * at every display the project renders (DashboardTrafficTest). The width is estimated at
     * [EM_PER_CHARACTER]; the drawing measures the real text and right-aligns it inside.
     */
    val trafficAttribution: Box = run {
        val pad = maxOf(1, attributionTextSize * 3 / 10)
        val w = (EM_PER_CHARACTER * attributionTextSize * TRAFFIC_ATTRIBUTION.length).toInt() + 2 * pad
        val h = attributionTextSize + 2 * pad
        val right = zoomBubble(ZOOM_ACTIONS.size - 1).x - margin
        val bottom = attributionTop - maxOf(1, margin / 2)
        Box(right - w, bottom - h, w, h)
    }

    /**
     * Text size of the trip panel's "+7 min traffic" (P9.4, plan §6), on the caption line
     * above the arrival time: the caption's own 0.22 of the summary, brought down if need be
     * so "+12 min traffic" fits beside a six-character time remaining. The drawing measures
     * the real text and leaves "arrive ·" in front of it only where that fits too.
     */
    val tripTrafficTextSize: Float = run {
        val h = tripSummary.height
        val room = tripSummary.width - margin - 6 * EM_PER_CHARACTER * 0.3f * h
        minOf(0.22f * h, room / (EM_PER_CHARACTER * TRAFFIC_LABEL_CHARACTERS))
    }

    /**
     * The turn card while an offer is up (P9.6, plan §7 "The offer"): the whole plate, taller
     * than [turnCard], whose part [offerTurn] keeps the next turn and part [offerPart] holds
     * the offer. Stacked, the turn part *is* [turnCard], so guidance does not move at all;
     * the offer band under it is three quarters of the card's height, and never less than a
     * map bubble with a margin round it, so Take and Keep are at least a bubble tall.
     * Side by side, the card grows until the offer's two lines and a bubble-tall row of
     * buttons fit in three fifths of its width.
     */
    val offerCard: Box = run {
        val h = turnCard.height
        val tall = when (offerLayout) {
            OfferLayout.STACKED -> h + maxOf(bubbleSize + 2 * margin, h * 3 / 4)
            OfferLayout.SIDE_BY_SIDE -> maxOf(h, margin + h / 2 + bubbleSize + margin)
        }
        Box(turnCard.x, turnCard.y, turnCard.width, tall)
    }

    /** The next turn's part of the divided card. */
    val offerTurn: Box = when (offerLayout) {
        OfferLayout.STACKED -> turnCard
        OfferLayout.SIDE_BY_SIDE -> Box(offerCard.x, offerCard.y, offerCard.width * 2 / 5, offerCard.height)
    }

    /**
     * The maneuver icon in [offerTurn]: [turnIcon] stacked; side by side, a square across
     * the part's top, three fifths of the card tall at most, with the distance under it.
     */
    val offerTurnIcon: Box = when (offerLayout) {
        OfferLayout.STACKED -> turnIcon
        OfferLayout.SIDE_BY_SIDE -> {
            val inset = maxOf(1, margin / 2)
            val side = maxOf(0, minOf(offerTurn.width - 2 * inset, offerTurn.height * 3 / 5))
            Box(offerTurn.x + (offerTurn.width - side) / 2, offerTurn.y + inset, side, side)
        }
    }

    /**
     * Whether the divided card still has room for the street (plan §7: "the street name
     * dropped if there is no room"). Stacked it keeps its whole line; side by side the turn
     * part is two fifths of a card whose width the icon already takes most of.
     */
    val offerTurnStreet: Boolean = offerLayout == OfferLayout.STACKED

    /** The offer's part of the divided card. */
    val offerPart: Box = when (offerLayout) {
        OfferLayout.STACKED -> Box(offerCard.x, turnCard.bottom, offerCard.width, offerCard.bottom - turnCard.bottom)
        OfferLayout.SIDE_BY_SIDE -> Box(offerTurn.right, offerCard.y, offerCard.right - offerTurn.right, offerCard.height)
    }

    /**
     * Take (index 0) and Keep (1), in [OFFER_ACTIONS] order, left to right at the offer's
     * right (stacked) or across its bottom (side by side). Each at least a map bubble tall
     * and half as wide again, the size a head-unit target has had since P5.5, and a margin
     * apart, so a square target cannot reach its neighbour.
     */
    fun offerButton(index: Int): Box {
        val part = offerPart
        return when (offerLayout) {
            OfferLayout.STACKED -> {
                val h = maxOf(0, part.height - 2 * margin)
                val w = maxOf(bubbleSize * 3 / 2, part.width / 5)
                val fromRight = OFFER_ACTIONS.size - 1 - index
                Box(part.right - margin - w - fromRight * (w + margin), part.y + margin, w, h)
            }
            OfferLayout.SIDE_BY_SIDE -> {
                val w = maxOf(0, (part.width - 3 * margin) / 2)
                Box(part.x + margin + index * (w + margin), part.bottom - margin - bubbleSize, w, bubbleSize)
            }
        }
    }

    /** Where "Faster: −7 min" and the reason go: the offer's part, less its buttons. */
    val offerText: Box = run {
        val part = offerPart
        val inset = maxOf(1, margin / 2)
        when (offerLayout) {
            OfferLayout.STACKED -> Box(part.x + margin, part.y + inset, maxOf(0, offerButton(0).x - margin - part.x - margin), maxOf(0, part.height - 2 * inset))
            OfferLayout.SIDE_BY_SIDE -> Box(part.x + margin, part.y + inset, maxOf(0, part.width - 2 * margin), maxOf(0, offerButton(0).y - inset - part.y - inset))
        }
    }

    /** The offer's action at a point, "take" or "keep", or null. */
    fun hitOfferButton(px: Int, py: Int): String? =
        OFFER_ACTIONS.indices.firstOrNull { offerButton(it).contains(px, py) }?.let { OFFER_ACTIONS[it] }

    /**
     * Where the map frames both routes while an offer is up (plan §7): the pane under the
     * divided card, inset by a margin, so neither route is hidden behind the card.
     */
    val offerView: Box = run {
        val top = offerCard.bottom + margin
        Box(map.x + margin, top, maxOf(0, map.width - 2 * margin), maxOf(0, map.bottom - margin - top))
    }

    // --- P11.5: POI buttons, markers and the card (docs/p11-plan.md §6) --------------

    /**
     * Where the Gas, Food and Shop column's top edge goes: a margin into the map pane, or a
     * margin under the turn card or the divided card while one is drawn. The column runs
     * down the pane's left edge (James, 3 October 2026: "move the bubbles to the left side
     * and make it the icons instead of words"), above the recentre and compass bubbles in
     * the bottom-left corner; the cards own the top during a route, so it starts under them.
     */
    fun poiButtonsTop(turnCard: Boolean, offer: Boolean): Int = when {
        offer -> offerCard.bottom + margin
        turnCard -> this.turnCard.bottom + margin
        else -> map.y + margin
    }

    /**
     * Button [index] of [PoiRules.ORDER] with the column's top at [top]: a round map bubble
     * holding the kind's marker icon, a margin in from the pane's left edge and a margin
     * apart, in the left bubbles' own column so the left edge reads as one stack.
     */
    fun poiButton(index: Int, top: Int): Box =
        if (poiColumnFits(top)) {
            Box(map.x + margin, top + index * (bubbleSize + margin), bubbleSize, bubbleSize)
        } else {
            Box(map.x + margin + index * (bubbleSize + margin), top, bubbleSize, bubbleSize)
        }

    /**
     * Whether the column fits between [top] and the compass bubble with a margin to spare.
     * Under the stacked divided card at 1080p it does not, and the three sit side by side
     * from the left edge instead, still under the card.
     */
    private fun poiColumnFits(top: Int): Boolean {
        val n = PoiRules.ORDER.size
        val bottom = top + n * bubbleSize + (n - 1) * margin
        return bottom + margin <= mapBubble(MAP_ACTIONS.size - 1).y
    }

    /** The button at a point with the column at [top], or null. Square targets, a margin apart. */
    fun hitPoiButton(px: Int, py: Int, top: Int): PoiKind? =
        PoiRules.ORDER.indices.firstOrNull { poiButton(it, top).contains(px, py) }?.let { PoiRules.ORDER[it] }

    /** The whole column at [top], for the report and the lab's pixel check. */
    fun poiButtonRow(top: Int): Box {
        val first = poiButton(0, top)
        val last = poiButton(PoiRules.ORDER.size - 1, top)
        return Box(first.x, first.y, last.right - first.x, last.bottom - first.y)
    }

    /** A marker's drawn diameter: two thirds of a map bubble, so many fit without hiding the roads. */
    val poiMarkerSize: Int = maxOf(16, bubbleSize * 2 / 3)

    /**
     * How far from a marker's centre a press still lands on it: half a bubble, larger than
     * the drawing for the reason the bubbles' targets are square (a finger at arm's length).
     */
    val poiHitRadius: Int = maxOf(poiMarkerSize / 2, bubbleSize / 2)

    /**
     * The card's two buttons (index 0 the primary, Go or Add stop; 1 Close) across the flex
     * tile's bottom, each half the tile and a map bubble tall, or a quarter of the tile if
     * that is less.
     */
    fun poiCardButton(index: Int): Box {
        val tile = tiles[FLEX_TILE]
        val h = minOf(bubbleSize, tile.height / 4)
        val w = maxOf(0, (tile.width - 3 * margin) / 2)
        return Box(tile.x + margin + index * (w + margin), tile.bottom - margin - h, w, h)
    }

    /** The card's places, between the label strip and the buttons. */
    val poiCardList: Box = run {
        val tile = tiles[FLEX_TILE]
        val top = tile.y + tileTitleHeight(tile)
        Box(tile.x + margin, top, maxOf(0, tile.width - 2 * margin), maxOf(0, poiCardButton(0).y - margin - top))
    }

    /** Row [index] of [count] places on the card: the list shared equally. */
    fun poiCardRow(index: Int, count: Int): Box {
        val h = poiCardList.height / maxOf(1, count)
        return Box(poiCardList.x, poiCardList.y + index * h, poiCardList.width, h)
    }

    /** The card button at a point, [POI_CARD_ACTIONS], or null. */
    fun hitPoiCardButton(px: Int, py: Int): String? =
        POI_CARD_ACTIONS.indices.firstOrNull { poiCardButton(it).contains(px, py) }?.let { POI_CARD_ACTIONS[it] }

    /** The place row at a point of [count], or -1. */
    fun hitPoiCardRow(px: Int, py: Int, count: Int): Int =
        (0 until count).firstOrNull { poiCardRow(it, count).contains(px, py) } ?: -1

    /**
     * Text size for the empty favourites tile's two lines (P7.5, plan §5.7), sized so the
     * longer one fits the list's width rather than being cut off, as "none saved — add
     * places o…" was at 800x480. [EM_PER_CHARACTER] is a generous estimate of a character's
     * width; the drawing measures the real text and shrinks further if it has to.
     */
    val emptyFavoritesTextSize: Float = run {
        val longest = EMPTY_FAVORITES.maxOf { it.length }
        minOf(tiles[FAVORITES_TILE].height / 11f, favoritesList.width / (EM_PER_CHARACTER * longest))
    }

    override fun toString(): String =
        "content=$x,$y+${width}x$height header=$header margin=$margin map_on_left=$mapOnLeft " +
            "map=${map.x},${map.y}+${map.width}x${map.height} bubble=$bubbleSize " +
            "media=${media.x}:${media.y}+${media.width}x${media.height} " +
            "art=${albumArt.width}x${albumArt.height} " +
            "tiles=" + tiles.joinToString(",") { "${it.x}:${it.y}+${it.width}x${it.height}" } +
            " offer=${offerLayout.key}"

    companion object {
        /** The configurable tiles under the media pane; media is not one of them. */
        const val TILES = 2

        /** Left to right, matching the drawn order. */
        val MEDIA_ACTIONS = listOf("previous", "play-pause", "next")

        /** The floating map controls, bottom upwards, matching the drawn order. */
        val MAP_ACTIONS = listOf("recentre", "compass")

        /** The bottom-right stack, top downwards: + above −. */
        val ZOOM_ACTIONS = listOf("zoom-in", "zoom-out")

        /** The header's touch targets, left to right: the notification pause, day/night. */
        val HEADER_ACTIONS = listOf("pause", "day-night")

        /** The tile that lists the saved places, [Favorite]s, for one-tap routing. */
        const val FAVORITES_TILE = 0

        /** Rows the favorites list shows at once; more than that scrolls. */
        const val FAVORITE_ROWS = 3

        /**
         * The second tile, the flex tile (plan §5.8): a call, notifications, or a quiet
         * empty state. A trip never displaces it: the trip panel takes [FAVORITES_TILE].
         */
        const val FLEX_TILE = 1

        /**
         * The tiles' labels as the session starts. The flex tile's follows what it shows
         * ([FlexRules.label]); "notifications" is its label whenever no call is up.
         */
        val TILE_LABELS = listOf("favorites", "notifications")

        /** Notification rows the flex tile shows at once; see [notificationRowHeight]. */
        const val NOTIFICATION_ROWS = 2

        /** Player rows in the idle media pane (P7.5); see [playerRow]. */
        const val PLAYER_ROWS = 2

        /** The empty favourites tile's two lines (plan §5.7), centred. */
        val EMPTY_FAVORITES = listOf("No favourites yet", "Add them on the phone")

        /** A generous width of one character, in ems, for sizing text that must not be cut. */
        const val EM_PER_CHARACTER = 0.62f

        /** The divided card's targets, left to right (P9.6). */
        val OFFER_ACTIONS = listOf("take", "keep")

        /** P11.5: the POI card's buttons, left to right: Go (or Add stop), then Close. */
        val POI_CARD_ACTIONS = listOf("primary", "close")

        /** HERE's credit (P9.3); [TrafficDisplay.ATTRIBUTION]. */
        const val TRAFFIC_ATTRIBUTION = TrafficDisplay.ATTRIBUTION

        /** "+12 min traffic": the longest delay label [tripTrafficTextSize] is sized for. */
        const val TRAFFIC_LABEL_CHARACTERS = 15

        /**
         * Which way the card divides in layouts made from now on (P9.6). The plan leaves it
         * to the lab's renders (§11); side by side is the default because it fits the truck's
         * panes better. At 726x480 it takes 99 px of the 413 px pane where stacked takes 143,
         * leaving 293 px of map under the card for the two routes against 249, and its Take
         * and Keep are 91x44 against 67x47. It is also the plan's own description, "the next
         * turn keeps the left part". What it costs is the street name and a smaller icon (59
         * px against 76); stacked keeps the turn exactly as it is. Setting this before
         * projection starts renders stacked instead, for the lab to show James both.
         */
        @JvmStatic
        @Volatile
        var defaultOfferLayout: OfferLayout = OfferLayout.SIDE_BY_SIDE
    }
}

/**
 * What the phone itself knows, none of which comes from the head unit.
 *
 * `@JvmOverloads` so the Java host tests that build one positionally keep compiling as
 * fields with defaults are added (P7.3 added two).
 */
data class PhoneSnapshot @JvmOverloads constructor(
    /** 0..100, or -1 when unread. */
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    /** 0..4 bars, or -1 when unread. */
    val signalBars: Int = -1,
    /** `lte`, `nr`, `wifi`, `none`, or `-`. */
    val network: String = "-",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val bearingDegrees: Double? = null,
    val speedMetresPerSecond: Double? = null,
    val accuracyMetres: Float? = null,
    /** Whether location was asked for at all; false means the permission is not held. */
    val locationEnabled: Boolean = false,
    /**
     * P7.3 (plan §5.2): one level per additional SIM, up to [SimSignal.MAX_SECONDARY],
     * each 0..4 or -1 for a hollow row (inactive, out of service, unread). Empty on a
     * single-SIM phone or without `READ_PHONE_STATE`. [signalBars] stays the primary's.
     */
    val secondarySignalBars: List<Int> = emptyList(),
    /**
     * The primary SIM's network type for beside its bars: `5G`, `LTE`, `H+`, `H`, `3G`,
     * `E`, `2G`, or `-` (SimSignal.networkLabel). [network] still says whether the phone
     * is on wifi, which the header shows instead when it is.
     */
    val cellNetworkType: String = "-",
)

/**
 * How the header spells a speed: what to multiply the metres per second every source
 * reports by, and the label that goes beside the result.
 *
 * A setting rather than a constant because the unit belongs to the driver, not to the
 * vehicle or the head unit — nothing on the wire says which one is read — and because an
 * unlabelled or mislabelled number on a dashboard is read as authoritative. [MPH] is
 * first because it is the default the settings screen opens on.
 */
enum class SpeedUnit(val label: String, val perMetrePerSecond: Double) {
    MPH("mph", 2.2369362920544),
    KMH("km/h", 3.6),
    ;

    /**
     * The header's speed text, rounded to whole units.
     *
     * A null speed still draws its unit: an em dash beside "mph" says the source has
     * not reported one, where a bare dash would look like a drawing that failed.
     */
    fun format(metresPerSecond: Double?): String =
        "${metresPerSecond?.let { Math.round(it * perMetrePerSecond) } ?: "—"} $label"

    /**
     * A distance in the system this speed unit belongs to: feet then miles beside mph,
     * metres then kilometres beside km/h. Short distances go in steps of 50 and long ones
     * in tenths, and whole units from ten up, so the text changes about as often as a
     * driver can read it rather than on every fix.
     */
    fun distance(metres: Double): String {
        val m = if (metres.isFinite()) metres.coerceAtLeast(0.0) else 0.0
        val (small, smallLabel, large, largeLabel) = when (this) {
            MPH -> Units(m / 0.3048, "ft", m / 1609.344, "mi")
            KMH -> Units(m, "m", m / 1000.0, "km")
        }
        return when {
            small < 1000.0 -> "${Math.round(small / 50.0) * 50} $smallLabel"
            large < 10.0 -> String.format(java.util.Locale.ROOT, "%.1f %s", large, largeLabel)
            else -> "${Math.round(large)} $largeLabel"
        }
    }

    private data class Units(val small: Double, val smallLabel: String, val large: Double, val largeLabel: String)

    companion object {
        /** The choices in the order the settings screen offers them; the first is the default. */
        val LABELS: List<String> = entries.map { it.label }
    }
}

/** Which source the map is following. */
enum class PositionSource(val label: String) {
    VEHICLE("vehicle"),
    PHONE("phone"),
    NONE("none"),
}

/**
 * The turn card's content (P7.4, plan §5.3), already in words, so the chrome repaints only
 * when what is drawn changes. [maneuver] and [exit] are the values the cluster is sent.
 */
data class TurnCard(
    val maneuver: Int,
    val exit: Int,
    /** "0.3 mi", or null while re-routing or arrived. */
    val distance: String?,
    /** The street turned onto; else the instruction; or `Rerouting…` / `You have arrived`. */
    val line: String,
)

/** One maneuver on the trip panel: the cluster's values and the distance to it, in words. */
data class TripStep(val maneuver: Int, val exit: Int, val distance: String)

/**
 * The trip panel (P7.4, plan §5.4), in the favourites tile's place for as long as a route
 * exists. Every figure is null when there is nothing honest to show (re-routing, or a
 * route with no fix on it yet); End route is drawn whatever they are.
 */
data class TripPanel(
    /** The clock time of arrival, "18:40" or "6:40 PM", or null. */
    val arrival: String?,
    /** Time remaining, "12 min", or null. */
    val remaining: String?,
    /** Distance remaining, "4.3 mi", or null. */
    val distance: String?,
    val later: List<TripStep>,
    val arrived: Boolean,
    val rerouting: Boolean,
    /** A finger is on End route. */
    val endPressed: Boolean,
    /** Voice guidance is on (P8.2): the speaker glyph lit, else slashed. */
    val voiceOn: Boolean = true,
    /** Speech cannot happen now (no engine, no A2DP output): the glyph's warning mark. */
    val voiceWarning: Boolean = false,
    /** A finger is on the voice toggle. */
    val voicePressed: Boolean = false,
    /**
     * "+7 min traffic" (P9.4, plan §6): the traffic delay already added into [remaining]
     * and [arrival], labelled; null when it rounds to under a minute or there is no figure.
     */
    val traffic: String? = null,
)

/**
 * The display choices a dashboard starts a session with (P7.2, P7.3, P7.6), read from
 * [AppSettings] when projection starts.
 *
 * Here rather than in [AttemptSettings] because two of them change on the dashboard
 * itself mid-session (☀/☾ and Ⓟ), and because a lab run builds its attempt from extras
 * and must not read the saved settings for it, while what the dashboard looks like is the
 * driver's in either case.
 */
data class DashboardChoices @JvmOverloads constructor(
    val markerColour: MarkerColour = MarkerColour.DEFAULT,
    val dayNight: DayNightMode = DayNightMode.DEFAULT,
    /** The header's second line of debug text (plan §5.1, §6.1). */
    val diagnostics: Boolean = false,
    /** Ⓟ as last left. */
    val notificationsPaused: Boolean = false,
    /**
     * The palette the last session ended in, or null when none has been recorded. What
     * Automatic draws until the head unit says anything (see [Dashboard.resolveNight]).
     */
    val rememberedNight: Boolean? = null,
    /** The trip panel's voice toggle as last left (P8.2); saved, so a muted route stays muted. */
    val voiceGuidance: Boolean = true,
    /** P10: Show weather: the header's weather item, its tapped view and alerts in the tile. */
    val weather: Boolean = false,
    /** P10: Alerts shown from: the lowest severity that takes the flex tile. */
    val alertLevel: AlertLevel = AlertLevel.DEFAULT,
    /** 12- or 24-hour: the header clock, the arrival time and the weather's times. */
    val clockFormat: ClockFormat = ClockFormat.DEFAULT,
)

/**
 * Where the dashboard hands a choice made on it, so it can outlive the session. Each is
 * called under the dashboard's lock, on the thread that delivered the touch or the head
 * unit's event, as the compass's orientation is: none may block.
 */
class DashboardSaves @JvmOverloads constructor(
    /** ☀/☾ moved to a new mode. */
    val dayNight: (DayNightMode) -> Unit = {},
    /** Ⓟ was pressed; the new value. */
    val notificationsPaused: (Boolean) -> Unit = {},
    /** The palette in use changed, for [DashboardChoices.rememberedNight] next session. */
    val resolvedNight: (Boolean) -> Unit = {},
)

/**
 * State of the projected dashboard. Pure JVM and thread safe: head-unit input and phone
 * updates arrive on other threads, and the renderer thread draws [Snapshot]s.
 */
class Dashboard @JvmOverloads constructor(
    layout: DashboardLayout,
    val token: String,
    private val zone: ZoneId,
    /** The unit the header spells the speed in; the operator's choice for this attempt. */
    private val speedUnit: SpeedUnit = SpeedUnit.MPH,
    /** Which way is up in the map pane when the session starts; the driver's last choice. */
    mapOrientation: MapOrientation = MapOrientation.NORTH_UP,
    /**
     * Told each time the compass bubble changes the orientation, so the choice can
     * outlive the session. Called under this object's lock: it must not block.
     */
    private val onMapOrientation: (MapOrientation) -> Unit = {},
    /** Marker colour, day/night, diagnostics and the pause, as the session starts. */
    choices: DashboardChoices = DashboardChoices(),
    /** Where ☀/☾, Ⓟ and the palette in use are handed for keeping. */
    private val saves: DashboardSaves = DashboardSaves(),
) {

    @Volatile
    var layout: DashboardLayout = layout
        private set

    fun relayout(updated: DashboardLayout) {
        layout = updated
    }

    /** Immutable values for one drawn frame. */
    data class Snapshot(
        val frame: Long,
        val clock: String,
        val vehicle: VehicleReading,
        val phone: PhoneSnapshot,
        val source: PositionSource,
        /** Degrees, from whichever source won, or null. */
        val bearing: Double?,
        /** Metres per second, from whichever source won, or null. */
        val speed: Double?,
        /**
         * The unit [speed] is drawn in. Carried per frame rather than read from a setting
         * at drawing time so the chrome comparison sees a unit change as a change.
         */
        val speedUnit: SpeedUnit,
        val nowPlaying: String?,
        /** Title alone, for the pane that has room for two lines. */
        val title: String?,
        val artist: String?,
        /**
         * Identity of the cover art the renderer holds, or null when there is none.
         *
         * The bitmap itself stays out of this type: [Dashboard] is pure JVM so the host
         * tests can check it without Android. What the snapshot needs is only enough to
         * notice the art *changed*, because that is what tells the renderer to repaint
         * the chrome.
         */
        val artKey: String?,
        val playing: Boolean,
        val transport: String?,
        val restriction: String,
        val pressedTile: Int,
        val pressedMedia: String?,
        val pressedMapBubble: String?,
        val mapStatus: String,
        /** Which way is up in the map pane, and whether it is still on the vehicle. */
        val mapOrientation: MapOrientation,
        val mapFollowing: Boolean,
        /**
         * Degrees clockwise from north at the top of the pane, to the degree.
         *
         * Rounded because the chrome is only repainted when this snapshot differs from
         * the last one drawn, and a rotation carried to the float would repaint the
         * whole 1920x1080 bitmap on every hundredth of a degree of sensor noise.
         */
        val mapRotation: Int,
        /** Frame pixel the position source is drawn at, or null when it is off the pane. */
        val mapMarker: Pair<Int, Int>?,
        /** The live alternatives' bubbles in the pane this frame, drawn under the vehicle marker. */
        val alternativeBubbles: List<AlternativeBubble> = emptyList(),
        /**
         * Degrees clockwise from the top of the pane that the vehicle marker points, or
         * null to draw it as a dot: stopped, crawling, or with no bearing to point along.
         * Whole degrees for the same repaint reason as [mapRotation].
         */
        val mapArrow: Int?,
        val tileTaps: List<Int>,
        /** The saved places the favorites tile lists, in order. */
        val favorites: List<Favorite>,
        /** How far up the favorites list is scrolled, in pixels; zero while it all fits. */
        val favoritesScroll: Int,
        /** The favorite a finger is resting on, or -1. */
        val pressedFavorite: Int,
        /** Whether the first of [favorites] is the recent destination, drawn with a history icon. */
        val recentFavorite: Boolean = false,
        /**
         * The turn card over the map pane (P7.4), or null with nothing to show on it.
         * Already in words, like [mapRotation] is rounded, so it repaints the chrome only
         * when what it says changes.
         */
        val turnCard: TurnCard? = null,
        /** The trip panel, drawn in the favourites tile's place, or null with no route. */
        val trip: TripPanel? = null,
        /**
         * Whether to draw the night palette (P7.6): [DayNightMode.resolve] of the head
         * unit's `is_night`, as [resolveNight] settles it. The same value themes the map,
         * so chrome and map never disagree. [restriction] still carries the head unit's
         * own word, for the diagnostics line.
         */
        val night: Boolean = true,
        /** The ☀/☾ icon's mode; in Automatic it draws a small "A". */
        val dayNight: DayNightMode = DayNightMode.DEFAULT,
        /** The vehicle dot and arrow (P7.2, plan §4.2). */
        val markerColour: MarkerColour = MarkerColour.DEFAULT,
        /** Whether the header draws its debug line (plan §5.1). */
        val diagnostics: Boolean = false,
        /** Ⓟ: bold and in the accent colour while paused. */
        val notificationsPaused: Boolean = false,
        /** The header icon a finger is resting on, or null. */
        val pressedHeaderIcon: String? = null,
        /** The zoom bubble a finger is resting on, or null. */
        val pressedZoom: String? = null,
        /**
         * Whether a bearing or speed on show rests on a divisor no drive has confirmed
         * ([ScaleRule.confirmed]); the diagnostics line says "scale inferred".
         */
        val scaleInferred: Boolean = false,
        /** The player apps for the idle media pane (P7.5), most recent first. */
        val players: List<Player> = emptyList(),
        /**
         * Nothing is playing or paused with a session (P7.5): the media pane lists
         * [players] with Resume instead of the now-playing text and transport buttons.
         */
        val mediaIdle: Boolean = false,
        /** Notification access is held; without it the idle pane says, once and small, that more could be listed. */
        val mediaAccess: Boolean = true,
        /** The player row a finger is resting on, or -1. */
        val pressedPlayer: Int = -1,
        /** The [Player.key] a Resume is waiting on, drawn greyed; or null. */
        val resumePending: String? = null,
        /** The [Player.key] whose Resume just failed, drawn "couldn't resume" for a while; or null. */
        val resumeFailed: String? = null,
        // P7.8, P7.9: the flex tile and the keypad over the half the map does not take.
        /** What the flex tile shows ([FlexRules.source]). */
        val flex: FlexSource = FlexSource.EMPTY,
        /** Its label, which follows what it shows ([FlexRules.label]). */
        val flexLabel: String = DashboardLayout.TILE_LABELS[DashboardLayout.FLEX_TILE],
        /**
         * The notification rows, newest first; empty unless [flex] is
         * [FlexSource.NOTIFICATIONS]. Drawn and nowhere else: never logged or reported.
         */
        val notifications: List<DashNotification> = emptyList(),
        val notificationsScroll: Int = 0,
        /** The notification row a finger is resting on, or -1. */
        val pressedNotification: Int = -1,
        /** The key of the row just tapped to be read aloud, lit for a moment; or null. */
        val flashNotification: String? = null,
        /** The call the card draws, or [CallState.NONE]. [CallState.caller] is drawn, never logged. */
        val call: CallState = CallState.NONE,
        /** A connected call's running time, "12:05", or null ([FlexRules.duration]). */
        val callDuration: String? = null,
        /** The call card's buttons ([FlexRules.actions]). */
        val callActions: List<String> = emptyList(),
        /** The call button a finger is resting on, or null. */
        val pressedCallAction: String? = null,
        val keypadOpen: Boolean = false,
        /**
         * This call's keys, for the keypad's strip. **Digits are often PINs**: drawn there
         * and held nowhere else, never logged or reported (plan §5.8).
         */
        val keypadEcho: String = "",
        /** The key a finger is on, as an index into [FlexRules.KEYPAD_KEYS], or -1. */
        val pressedKey: Int = -1,
        val pressedKeypadClose: Boolean = false,
        // P9: live traffic (docs/p9-plan.md §6, §7).
        /** "Traffic © HERE" is owed ([TrafficDisplay.attributed]) and drawn in the map's corner. */
        val trafficAttributed: Boolean = false,
        /** A faster way round, dividing the turn card with Take and Keep; or null. */
        val offer: OfferCard? = null,
        /** P10: the header's weather item; null while Show weather is off. */
        val weatherHeader: WeatherHeader? = null,
        /** P10: the tapped weather view, while the flex tile shows it. */
        val weatherView: WeatherPanel? = null,
        /** P10: the weather alert, while the flex tile shows it. */
        val weatherAlert: WeatherAlertCard? = null,
        /** P11.5: the Gas, Food and Shop buttons along the top of the map pane. */
        val poiButtons: PoiButtons? = null,
        /** P11.5: the POI card, while the flex tile shows it. Names are drawn, never logged. */
        val poiCard: PoiCardView? = null,
    ) {
        /** Leaves out everything drawn from the phone's own data, in case a snapshot is ever printed. */
        override fun toString(): String = "Snapshot(frame=$frame flex=${flex.key} keypad=$keypadOpen)"
    }

    private var vehicle = VehicleReading()

    /**
     * What the drive so far has shown about the head unit's scales (P6.1).
     *
     * A bearing or a speed is read against every value this session has carried, not
     * only against itself: the lab's own track ends its run pointing north, where
     * `bearing=0` is 0 degrees at every candidate divisor and on its own says the head
     * unit sends whole degrees.
     */
    private var scales = ScaleEvidence()
    /**
     * How many vehicle events arrived. Without it, a header with no speed cannot be
     * told apart from a sensor channel that never delivered anything.
     */
    private var vehicleEvents = 0L
    private var phone = PhoneSnapshot()
    private var nowPlaying: String? = null
    private var title: String? = null
    private var artist: String? = null
    private var artKey: String? = null
    private var playing = false
    private var transports = 0L
    private var lastTransport = "-"
    private var restriction = "-"

    /**
     * What the head unit's NIGHT_DATA last said, or null until it says anything. Kept
     * apart from the resolved palette: null is "no signal yet", which is not day
     * (docs/p7-6-map-results.md §3.2 — reading it as day themed every night session's
     * map day and reloaded every tile a second later).
     */
    private var headUnitNight: Boolean? = null
    private var dayNight = choices.dayNight
    private val rememberedNight = choices.rememberedNight
    private val markerColour = choices.markerColour
    private val clockFormat = choices.clockFormat
    private val diagnostics = choices.diagnostics
    private var notificationsPaused = choices.notificationsPaused

    /** The palette last handed to [DashboardSaves.resolvedNight], so a save is per change. */
    private var savedNight: Boolean? = choices.rememberedNight

    /** The palette in use, so [nightChanges] counts switches and not events. */
    private var shownNight: Boolean = resolveNight(dayNight, null, rememberedNight)
    private var nightChanges = 0L

    init {
        // A session in Day or Night, or the first one ever, starts in a palette nothing
        // has recorded yet; recording it now makes "the palette the last session ended
        // in" true from the first frame, not only after the first change.
        noteResolved()
    }

    private var nightEvents = 0L
    private var dayNightPresses = 0L
    private var pausePresses = 0L
    private var zoomPresses = 0L
    private var pressedHeaderIcon: String? = null
    private var pressedZoom: String? = null
    private var players: List<Player> = emptyList()
    private var frames = 0L
    private var touches = 0L
    private var mediaPresses = 0L
    private var pressedTile = -1
    private var pressedMedia: String? = null
    private var pressedMapBubble: String? = null
    private var mapPresses = 0L

    /**
     * Whether the gesture in flight belongs to the map.
     *
     * Claimed on a press that is inside the pane and not on one of its floating
     * controls, so a finger that comes down on the recentre bubble and slides off it
     * does not drag the map out from under itself.
     */
    private var mapGesture = false

    /** Where the map pane is looking, and the gestures that move it. */
    private val mapControl = MapControl(mapOrientation)

    /**
     * Whether the marker is an arrow. Latched with hysteresis around [ARROW_ON_MPS] so
     * a vehicle creeping at the threshold does not flicker between arrow and dot.
     */
    private var moving = false
    private val tileTaps = IntArray(DashboardLayout.TILES)
    private var mapStatus = "starting"
    private var upcomingTurn: UpcomingTurn? = null

    private var favorites: List<Favorite> = emptyList()
    private var recentFavorite = false
    private var favoritePick: Favorite? = null

    /**
     * The favourites' scroll and the finger on them. A press that came down in the list
     * either lifts on the row it pressed, which is a tap, or moves past
     * [DashboardLayout.favoriteSlop], which makes the rest of the gesture a scroll and
     * nothing else ([TouchList], shared with the notifications since P7.8).
     */
    private val favoriteList = TouchList()

    // P7.4: the trip. The route itself is [upcomingTurn]; these are how the panel was used.
    private var trips = 0L
    private var endRoutePresses = 0L
    private var pressedEndRoute = false
    private var endRouteRequested = false

    /** The live alternatives the map shows ([LiveAlternatives]), and the one a finger is on. */
    private var alternatives: List<AlternativeMark> = emptyList()
    private var pressedAlternative: Long? = null
    private var alternativePick: Long? = null
    private var alternativePresses = 0L
    /** Frames that drew at least one alternative's bubble: whether they were ever on the pane. */
    private var alternativeBubbleFrames = 0L

    // P8.2: the voice toggle beside End route, and whether speech can happen at all.
    private var voiceOn = choices.voiceGuidance
    private var voicePresses = 0L
    private var pressedVoice = false
    private var voiceRequested: Boolean? = null
    private var speechAvailable = false

    // P7.5: the idle media pane.
    private var mediaAccess = true
    private var pressedPlayer = -1
    private var resumePick: Player? = null
    private var resumePending: String? = null
    private var resumeFailed: String? = null
    private var resumeFailedUntil = 0L
    private var resumePresses = 0L
    private var resumePlayed = 0L
    private var resumeFailures = 0L
    private var playerRows = 0
    private var playerRowsMax = 0

    // P7.8: the flex tile's notifications, in memory for the session only.
    private var notifications: List<DashNotification> = emptyList()
    private val notificationList = TouchList()
    private var readPick: DashNotification? = null
    private var flashKey: String? = null
    private var flashUntil = 0L
    /** P8.1: the row being read aloud, lit for as long as [Speaker] says it is. */
    private var readingKey: String? = null
    private var notificationsShownMax = 0

    // P7.9: the call card and the keypad. Presses queue here for the service, in order,
    // because a keypad tap is a tone start then a tone stop.
    private var call: CallState = CallState.NONE
    private var calls = 0L
    private var pressedCallAction: String? = null
    private val callActions = ArrayList<CallAction>()
    private var answerPresses = 0L
    private var declinePresses = 0L
    private var hangUpPresses = 0L
    private var mutePresses = 0L
    private var keypadOpen = false

    /** This call's keys, drawn in the keypad's strip; cleared when the call ends. */
    private val keypadEcho = StringBuilder()

    /** Whether the finger on the glass came down on the open keypad, which then owns it. */
    private var keypadGesture = false
    private var pressedKey = -1
    private var toneOn = false
    private var pressedKeypadClose = false
    private var keypadOpens = 0L
    private var keypadCloses = 0L
    private var keypadAutoCloses = 0L
    private var keypadPresses = 0L

    // P9: live traffic (docs/p9-plan.md §6, §7). The traffic loop publishes what to show;
    // these are what the dashboard made of it. Counts only in the report: never a place.
    private var trafficDisplay: TrafficDisplay = TrafficDisplay.EMPTY

    /** The route being followed, as the map draws it: where the offer's view starts from. */
    private var followedRoute: Route? = null

    /**
     * The offer Take or Keep answered, hidden from then on although the traffic loop has not
     * yet published a display without it, so the card becomes whole the moment it is pressed.
     */
    private var answeredOffer: Long? = null
    private var pressedOffer: String? = null
    private var pressedOfferId = 0L
    private var offerAnswer: OfferAnswer? = null
    private var trafficDisplays = 0L
    private var attributedFrames = 0L
    private var offersShown = 0L
    private var offersTaken = 0L
    private var offersKept = 0L
    private var offersWithdrawn = 0L
    private var offerFits = 0L
    private var offerRefollows = 0L

    // P10: weather (docs/p10-plan.md §5). What the feed last read, held for drawing only;
    // the report counts how the item and the views were used, never what they said.
    private var weatherOn = choices.weather
    private var alertLevel = choices.alertLevel
    private var weatherState = WeatherState.NONE
    private var weatherOpen = false
    private var weatherOpenedAt = 0L
    private var pressedWeather = false
    /** The press on the header item came down during a call: it does nothing, and is counted. */
    private var pressedWeatherInCall = false
    /** Which weather source a finger came down on in the flex tile, or null. */
    private var pressedWeatherTile: FlexSource? = null
    private var pressedWeatherAlert: String? = null
    /** Alerts dismissed with a tap, for this session; an upgrade under a new id shows again. */
    private val dismissedAlerts = HashSet<String>()
    private val shownAlerts = HashSet<String>()
    private var lastNowMillis = 0L
    private var weatherOpens = 0L
    private var weatherClosesTap = 0L
    private var weatherClosesView = 0L
    private var weatherClosesTimeout = 0L
    private var weatherClosesCall = 0L
    private var weatherTapsInCall = 0L
    private var alertsDismissed = 0L
    private var weatherFar = false

    // P11.5: the POI buttons, the markers on the map and the card (docs/p11-plan.md §6).
    // Off when a projection starts and never saved. Places are drawn, never reported.
    private val poiKinds = LinkedHashSet<PoiKind>()
    private var pressedPoiButton: PoiKind? = null
    private val poiToggles = ArrayList<Pair<PoiKind, Boolean>>()
    private val poiButtonPresses = IntArray(PoiKind.entries.size)
    private var poiMarkers: List<ObfPoi> = emptyList()
    private var pressedPoiMarker: ObfPoi? = null
    private var poiDownX = 0
    private var poiDownY = 0
    private var poiDownAt = 0L
    private var poiCard: PoiCardContent? = null
    private var poiSelected = 0
    private var poiCardOpenedAt = 0L
    private var pressedPoiCardButton: String? = null
    private var pressedPoiRow = -1
    private var poiAction: PoiAction? = null
    private var poiTaps = 0L
    private var poiCardsShown = 0L
    private var poiPrimaryPresses = 0L
    private var poiClosePresses = 0L
    private var poiCardTimeouts = 0L
    private var poiCardClosesCall = 0L
    private var poiRowPicks = 0L
    private var poiMarkersMax = 0

    @Synchronized
    fun vehicle(reading: VehicleReading) {
        vehicleEvents++
        scales = scales.and(reading)
        vehicle = reading.at(scales)
    }

    @Synchronized
    fun phone(snapshot: PhoneSnapshot) {
        phone = snapshot
    }

    @Synchronized
    fun mapStatus(status: String) {
        mapStatus = status
    }

    /**
     * What guidance says about the route (P7.4), for the turn card and the trip panel;
     * null when there is none, which gives the favourites back their tile. Their scroll
     * position is left alone throughout, so they come back where they were.
     */
    @Synchronized
    fun upcomingTurn(turn: UpcomingTurn?) {
        if (turn != null && upcomingTurn == null) {
            trips++
            // A finger resting on the favourites when the trip took their tile would
            // otherwise lift onto End route and be read as a tap on a place.
            favoriteList.cancel()
        }
        if (turn == null) {
            pressedEndRoute = false
            pressedVoice = false
        }
        upcomingTurn = turn
    }

    /**
     * P10: what the weather feed read last (docs/p10-plan.md §3), whole, on every read; the
     * empty state when a projection ends or weather goes off. Main thread.
     */
    @Synchronized
    fun weather(state: WeatherState) {
        weatherState = state
    }

    /**
     * P10: Show weather or Alerts shown from changed mid-session (the lab, or the phone's
     * card). Off takes the item and both views away at once.
     */
    @Synchronized
    fun weatherChoices(on: Boolean, level: AlertLevel) {
        weatherOn = on
        alertLevel = level
        if (!on) {
            weatherOpen = false
            pressedWeather = false
            pressedWeatherInCall = false
            pressedWeatherTile = null
        }
    }

    /** P10: the weather view is open (for the lab and the tests). */
    @Synchronized
    fun weatherOpen(): Boolean = weatherOpen

    // --- P11.5: POI buttons, markers and the card ------------------------------------

    /** The buttons lit now, in [PoiRules.ORDER]. */
    @Synchronized
    fun poiKinds(): Set<PoiKind> = PoiRules.ORDER.filter { it in poiKinds }.toSet()

    /** Lights exactly [kinds] (the lab's LAB_POI at the start); not a press, not counted. */
    @Synchronized
    fun poiKinds(kinds: Set<PoiKind>) {
        poiKinds.clear()
        poiKinds.addAll(kinds)
    }

    /**
     * The buttons pressed since the last call, in order, each with the state it left
     * (true for lit), once. P11.5a's hook: a press that lights a button while following a
     * route is where search ahead starts.
     */
    @Synchronized
    fun takePoiToggles(): List<Pair<PoiKind, Boolean>> {
        if (poiToggles.isEmpty()) return emptyList()
        return poiToggles.toList().also { poiToggles.clear() }
    }

    /**
     * Where the pane is looking and which buttons are lit, for [PoiFeed.frame]: null until
     * a frame has been placed. Scene thread, after [mapCamera].
     */
    @Synchronized
    fun poiView(): PoiView? {
        val box = mapControl.visibleBox(layout.map) ?: return null
        val camera = mapControl.lastCamera() ?: return null
        return PoiView(poiKinds(), box, camera.latitude, camera.longitude, mapControl.zoomLevel(), mapControl.following)
    }

    /**
     * The markers [PoiFeed] published, as the map draws them (scene thread, when they
     * change): what a press is tested against. A card whose place is no longer among them
     * stays open; it names a place, not a marker.
     */
    @Synchronized
    fun pois(list: List<ObfPoi>) {
        poiMarkers = list
        poiMarkersMax = maxOf(poiMarkersMax, list.size)
        if (pressedPoiMarker != null && list.none { it === pressedPoiMarker }) pressedPoiMarker = null
    }

    /**
     * Opens the flex tile's POI card on [card], replacing any card, with its first place
     * chosen (P11.5a's hook for its list of stops with "Add stop"). At most
     * [PoiCardContent.MAX_ENTRIES] places are shown; an empty list closes the card. Closes
     * the weather view, as opening the weather view closes this.
     */
    @Synchronized
    @JvmOverloads
    fun showPoiCard(card: PoiCardContent, nowMillis: Long = System.currentTimeMillis()) {
        if (card.entries.isEmpty() && card.message == null) {
            closePoiCard()
            return
        }
        poiCard = PoiCardContent(card.label, card.entries.take(PoiCardContent.MAX_ENTRIES), card.primary, card.tag, card.message)
        poiSelected = 0
        poiCardOpenedAt = nowMillis
        pressedPoiCardButton = null
        pressedPoiRow = -1
        poiCardsShown++
        weatherOpen = false
    }

    /** Closes the POI card, if one is open. */
    @Synchronized
    fun closePoiCard() {
        poiCard = null
        pressedPoiCardButton = null
        pressedPoiRow = -1
    }

    /** The card open now (for the lab and the tests), or null. */
    @Synchronized
    fun poiCard(): PoiCardContent? = poiCard

    /** Go (or Add stop) pressed on the card since the last call, for its chosen place, once; or null. */
    @Synchronized
    fun takePoiAction(): PoiAction? = poiAction.also { poiAction = null }

    /** The marker under a point, the nearest within [DashboardLayout.poiHitRadius], or null. */
    private fun hitPoiMarker(px: Int, py: Int): ObfPoi? = hitPoiMarkers(px, py).firstOrNull()

    /**
     * Every marker within [DashboardLayout.poiHitRadius] of a point, nearest first. Markers
     * overlap — a fuel station and its own convenience shop are two places on one spot — and
     * which one VTM draws on top is its own business, so a tap on a pile lists the pile.
     */
    private fun hitPoiMarkers(px: Int, py: Int): List<ObfPoi> {
        if (poiMarkers.isEmpty()) return emptyList()
        val r = layout.poiHitRadius.toLong()
        val hits = ArrayList<Pair<Long, ObfPoi>>()
        for (poi in poiMarkers) {
            val (x, y) = mapControl.marker(layout.map, poi.latitude to poi.longitude) ?: continue
            val dx = (x - px).toLong()
            val dy = (y - py).toLong()
            val d = dx * dx + dy * dy
            if (d <= r * r) hits += d to poi
        }
        return hits.sortedBy { it.first }.map { it.second }
    }

    /** The row's top edge now: under the turn card or the divided card while either is drawn. */
    private fun poiTop(): Int = layout.poiButtonsTop(turnCard() != null, shownOffer() != null)

    /** The card as drawn this frame, with distances from the vehicle (else the map's centre). */
    private fun poiCardView(nowMillis: Long): PoiCardView? {
        val card = poiCard ?: return null
        val from = followPosition() ?: mapControl.lastCamera()?.let { it.latitude to it.longitude }
        val rows = card.entries.map { entry ->
            val poi = entry.poi
            val detail = entry.detail ?: buildString {
                append(PoiRules.kindWord(poi))
                if (from != null) {
                    append(" · ")
                    append(speedUnit.distance(PoiRules.metres(from.first, from.second, poi.latitude, poi.longitude)))
                }
            }
            val open = if (entry.openNow) PoiRules.openNow(poi.openingHours, nowMillis, zone) else null
            PoiRowView(PoiRules.title(poi), detail, open, poi.kind)
        }
        if (card.notice) return PoiCardView(emptyList(), 0, "", pressedPoiCardButton, -1, card.message)
        return PoiCardView(rows, poiSelected, card.primary, pressedPoiCardButton, pressedPoiRow)
    }

    /** The live alternatives to put bubbles on; empty takes them away. */
    @Synchronized
    fun alternatives(marks: List<AlternativeMark>) {
        alternatives = marks
        if (marks.none { it.id == pressedAlternative }) pressedAlternative = null
    }

    /** The alternative whose bubble was tapped since the last call, once, or null. The caller takes it. */
    @Synchronized
    fun takeAlternativePick(): Long? = alternativePick.also { alternativePick = null }

    /**
     * What the map shows of traffic now (P9.3, P9.4, P9.6), from the traffic loop, whole,
     * whenever the traffic, the delay or the offer changes. Main thread, through
     * [ProjectionPipeline.traffic]; the scene thread reads it back with [trafficDisplay].
     *
     * An offer arriving divides the turn card and, if the map is following the vehicle,
     * fits the map to both routes. Its going — withdrawn by the loop, replaced by another,
     * or answered here — makes the card whole and puts the map back on the vehicle, unless
     * the driver has moved the map since ([MapControl.fitted]). A map the driver had
     * already moved when the offer came is left where they put it.
     */
    @Synchronized
    fun traffic(display: TrafficDisplay) {
        trafficDisplays++
        val before = shownOffer()
        trafficDisplay = display
        if (display.offer?.id != answeredOffer) answeredOffer = null
        val after = shownOffer()
        if (after?.id == before?.id) return
        if (before != null) {
            offersWithdrawn++
            endOffer()
        }
        if (after != null) beginOffer(after)
    }

    /** The traffic display the map draws from. Scene thread, each frame. */
    @Synchronized
    fun trafficDisplay(): TrafficDisplay = trafficDisplay

    /** The offer on show, answered ones excepted: the one the map draws the proposal of. */
    @Synchronized
    fun shownOffer(): TrafficOffer? = trafficDisplay.offer?.takeIf { it.id != answeredOffer }

    /**
     * The route the map is drawing ([Routing.line]), from the scene thread when it changes:
     * the offer's view frames the rest of it from the vehicle.
     */
    @Synchronized
    fun followedRoute(route: Route?) {
        followedRoute = route
    }

    /** Take or Keep, pressed since the last call, once; or null. The caller takes it (P9.6). */
    @Synchronized
    fun takeOfferAnswer(): OfferAnswer? = offerAnswer.also { offerAnswer = null }

    private fun beginOffer(offer: TrafficOffer) {
        offersShown++
        pressedOffer = null
        // A finger already on the card came down on the whole turn card, and lifts on Take or
        // Keep: that must not answer an offer the driver had not seen when pressing.
        pressedOfferId = 0L
        if (!mapControl.following && !mapControl.fitted) return
        // The rest of the followed route from the vehicle, and the whole proposal, which
        // starts near the vehicle; the vehicle itself too, so it is never off the view.
        val lats = ArrayList<DoubleArray>()
        val lons = ArrayList<DoubleArray>()
        val position = followPosition()
        followedRoute?.let { route ->
            val from = position?.let { TrafficLines.nearestIndex(route, it.first, it.second) } ?: 0
            lats += route.latitudes.copyOfRange(from, route.points)
            lons += route.longitudes.copyOfRange(from, route.points)
        }
        lats += offer.route.latitudes
        lons += offer.route.longitudes
        position?.let {
            lats += doubleArrayOf(it.first)
            lons += doubleArrayOf(it.second)
        }
        if (mapControl.fit(lats, lons, layout.map, layout.offerView)) offerFits++
    }

    private fun endOffer() {
        pressedOffer = null
        if (mapControl.unfit()) offerRefollows++
    }

    /** Each alternative's bubble where the map puts it now; one off the pane is not drawn and takes no press. */
    private fun alternativeBubbles(): List<AlternativeBubble> = alternatives.mapNotNull { mark ->
        mapControl.marker(layout.map, mark.latitude to mark.longitude)?.let { (x, y) ->
            AlternativeBubble(mark.id, layout.alternativeBubble(x, y), mark.label, mark.id == pressedAlternative)
        }
    }

    /** The alternative whose bubble is at a point, the last drawn (topmost) first, or null. */
    private fun hitAlternative(px: Int, py: Int): Long? =
        alternativeBubbles().lastOrNull { it.box.contains(px, py) }?.id

    /** Whether a point is on the turn card, or on the divided card while an offer is up. */
    private fun onCard(px: Int, py: Int): Boolean =
        (turnCard() != null && layout.turnCard.contains(px, py)) || (shownOffer() != null && layout.offerCard.contains(px, py))

    /**
     * Whether End route was pressed since the last call (P7.4), once. The caller ends the
     * route through [Navigation.clear], the path the phone's Clear route button takes.
     */
    @Synchronized
    fun takeEndRoute(): Boolean = endRouteRequested.also { endRouteRequested = false }

    /**
     * The voice toggle's new state if it was pressed since the last call (P8.2), once; else
     * null. The caller saves it, tells [Speaker] and has [Guide] confirm it. Two presses
     * between calls hand over the state they left, and both are counted.
     */
    @Synchronized
    fun takeVoiceToggle(): Boolean? = voiceRequested.also { voiceRequested = null }

    /**
     * Voice guidance turned on or off from the phone (P8.3): drawn from the next frame and
     * reported as the final state, but not a press, and not handed back by
     * [takeVoiceToggle], since the phone has already saved it and told [Speaker].
     */
    @Synchronized
    fun voiceGuidance(on: Boolean) {
        voiceOn = on
    }

    /**
     * Whether speech can happen now ([Speaker.canSpeak]): without it the toggle is drawn
     * with its warning mark, whichever way it is set (plan §6).
     */
    @Synchronized
    fun speechAvailable(available: Boolean) {
        speechAvailable = available
    }

    /**
     * Whether notification access is held (P7.5): without it the idle pane says the phone
     * could list more. Also taken from every now-playing update.
     */
    @Synchronized
    fun mediaAccess(access: Boolean) {
        mediaAccess = access
    }

    /** The player a completed press on a Resume row chose, once, or null (P7.5). */
    @Synchronized
    fun takeResumePick(): Player? = resumePick.also { resumePick = null }

    /**
     * How a Resume ended (P7.5). A played one simply stops being pending: the pane turns
     * into now-playing when the player's session says so. A failure says so on its row
     * for [RESUME_FAILED_MS], so a press that did nothing is never silent.
     */
    @Synchronized
    @JvmOverloads
    fun resumeResult(key: String, result: ResumeResult, nowMillis: Long = System.currentTimeMillis()) {
        if (resumePending == key) resumePending = null
        when (result) {
            ResumeResult.PLAYED_SESSION, ResumeResult.PLAYED_RECENT -> resumePlayed++
            else -> {
                resumeFailures++
                resumeFailed = key
                resumeFailedUntil = nowMillis + RESUME_FAILED_MS
            }
        }
    }

    /**
     * The saved places the favorites tile lists, under the [recent] destination unless it
     * is one of them ([Favorite.tile]), replacing whatever it listed. The scroll position is kept where it can be, so a place added from the phone while
     * projecting does not throw the list back to the top, and clamped where it cannot,
     * so a shortened list never shows an empty pane.
     */
    @Synchronized
    @JvmOverloads
    fun favorites(list: List<Favorite>, recent: Favorite? = null) {
        favorites = Favorite.tile(list, recent)
        recentFavorite = favorites.size > list.size
        favoriteList.replaced(layout.favoritesMaxScroll(favorites.size))
    }

    /** The favorite a completed tap on the tile chose, once, or null. */
    @Synchronized
    fun takeFavoritePick(): Favorite? = favoritePick.also { favoritePick = null }

    /**
     * The notifications the flex tile lists (P7.8), newest first, from [NotificationFeed];
     * replaces the list whole. A row gone from the phone is gone from the tile, because
     * the feed drops it and sends the rest. The scroll is kept where it can be, as the
     * favourites' is.
     */
    @Synchronized
    fun notifications(list: List<DashNotification>) {
        notifications = list
        notificationList.replaced(layout.notificationsMaxScroll(list.size))
        if (flashKey != null && list.none { it.key == flashKey }) flashKey = null
    }

    /**
     * The notification being read aloud, or null (P8.1, docs/p8-plan.md §8): its row stays
     * lit while it is read, after the tap's own flash. From [Speaker], through the service.
     */
    @Synchronized
    fun reading(key: String?) {
        readingKey = key
    }

    /**
     * A notification row a completed tap chose to be read aloud, once, or null (P7.8).
     * The service hands it to [Speaker.read], which reads it, or stops it on a second tap.
     */
    @Synchronized
    fun takeReadAloud(): DashNotification? = readPick.also { readPick = null }

    /**
     * The call to draw (P7.9), from [CallControl] on every change.
     *
     * The keypad closes itself when the call can no longer take tones — it ended, went on
     * hold, or is on the notification route — and its echo is cleared when the call is
     * over, so the next call's strip starts empty (plan §5.8). A tone still sounding is
     * stopped by [CallControl] itself when a call ends; a stop is queued here as well, for
     * a call put on hold under a finger.
     */
    @Synchronized
    fun call(state: CallState) {
        val was = call
        call = state
        if (was.phase == CallPhase.NONE && state.phase != CallPhase.NONE) {
            calls++
            // A finger on a notification when the call took the tile would otherwise
            // lift onto Answer or Decline and be read as a tap on a row.
            notificationList.cancel()
            pressedWeatherTile = null
            pressedWeatherAlert = null
            // P10 (plan §5.2): a call closes the weather view, which does not come back.
            if (weatherOpen) {
                weatherOpen = false
                weatherClosesCall++
            }
            // P11.5: and the POI card the same way.
            if (poiCard != null) {
                closePoiCard()
                poiCardClosesCall++
            }
        }
        if (keypadOpen && !state.canDial) {
            keypadAutoCloses++
            closeKeypad()
        }
        if (state.phase == CallPhase.NONE || state.phase == CallPhase.ENDED) keypadEcho.setLength(0)
        if (pressedCallAction != null && pressedCallAction !in FlexRules.actions(state)) pressedCallAction = null
    }

    /**
     * The call card's and the keypad's presses since the last call, in order, once
     * (P7.9). The service sends each to [CallControl]; nothing here touches a call.
     */
    @Synchronized
    fun takeCallActions(): List<CallAction> {
        if (callActions.isEmpty()) return emptyList()
        return callActions.toList().also { callActions.clear() }
    }

    /** Shuts the keypad, stopping any tone a finger on it had started. */
    private fun closeKeypad() {
        if (toneOn) callActions.add(CallAction(CallActionKind.KEY_UP))
        toneOn = false
        keypadOpen = false
        keypadGesture = false
        pressedKey = -1
        pressedKeypadClose = false
    }

    /** The map engine's real tile size, which the pan arithmetic scales pixels by. */
    @Synchronized
    fun mapTileSize(size: Int) {
        mapControl.useTileSize(size)
    }

    /**
     * The head unit's driving status and `is_night`, as the native session reported them.
     *
     * [restriction] keeps both words exactly as they came, for the diagnostics line and
     * the report, whatever the palette resolves to. Only `true` and `false` are a night
     * signal: the native side writes `-` for a field it has not been sent, and a driving
     * status arriving alone must not read as "day".
     */
    @Synchronized
    fun restriction(drivingStatus: String, nightValue: String) {
        restriction = "driving=$drivingStatus night=$nightValue"
        when (nightValue) {
            "true" -> headUnitNight = true
            "false" -> headUnitNight = false
            else -> return
        }
        nightEvents++
        noteResolved()
    }

    /**
     * Whether to draw night, for the chrome and the map alike (P7.6): the ☀/☾ mode over
     * the head unit's signal, as [resolveNight] settles it.
     */
    @Synchronized
    fun isNight(): Boolean = resolveNight(dayNight, headUnitNight, rememberedNight)

    /**
     * The player apps the idle media pane lists (P7.5), from [MediaPlayers]. Held for
     * the snapshot; drawn by dashboard part 2.
     */
    @Synchronized
    fun players(list: List<Player>) {
        players = list
        pressedPlayer = -1
    }

    /**
     * Whether the media pane lists players instead of now-playing (P7.5, plan §5.5):
     * nothing is playing or paused with a session. A session that is paused still has its
     * title (or at least its state), and keeps the pane it had.
     */
    private fun mediaIdle(): Boolean =
        nowPlaying == null || nowPlaying == DemoScene.NO_MEDIA || nowPlaying == DemoScene.NO_ACCESS

    /**
     * Counts a palette switch, and hands a palette not yet saved to
     * [DashboardSaves.resolvedNight], once per change.
     */
    private fun noteResolved() {
        val night = resolveNight(dayNight, headUnitNight, rememberedNight)
        if (night != shownNight) {
            nightChanges++
            shownNight = night
        }
        if (night != savedNight) {
            savedNight = night
            saves.resolvedNight(night)
        }
    }

    @Synchronized
    fun transport(action: String) {
        transports++
        lastTransport = action
    }

    @JvmOverloads
    @Synchronized
    fun nowPlaying(
        access: Boolean,
        state: String,
        source: String,
        title: String,
        artist: String,
        artKey: String? = null,
    ) {
        playing = state == "playing"
        mediaAccess = access
        this.title = title.ifEmpty { null }
        this.artist = artist.ifEmpty { null }
        this.artKey = artKey
        val track = when {
            title.isNotEmpty() && artist.isNotEmpty() -> "$title — $artist"
            title.isNotEmpty() -> title
            source.isNotEmpty() -> source
            else -> ""
        }
        nowPlaying = when {
            !access -> DemoScene.NO_ACCESS
            track.isEmpty() && state == "stopped" -> DemoScene.NO_MEDIA
            track.isEmpty() -> state
            else -> track
        }
    }

    /**
     * A head-unit touch in video frame coordinates, with every pointer the event
     * carried. Returns the transport action a completed press selected, so the caller
     * can dispatch it to whatever is playing.
     *
     * The buttons are all single-touch and read the first pointer; the map pane is the
     * one thing here that needs the rest of them, for the pinch and the twist.
     */
    @Synchronized
    @JvmOverloads
    fun touch(action: String, pointers: List<TouchPoint>, nowMillis: Long = System.currentTimeMillis()): String? {
        touches++
        lastNowMillis = nowMillis
        val first = pointers.firstOrNull() ?: return null
        val px = first.x
        val py = first.y
        // P7.9: the open keypad claims every finger that comes down on it, before the
        // player rows, End route and the favourites underneath can see the press, and
        // keeps it until the finger lifts: nothing under the keypad is a target.
        if (keypadGesture || (keypadOpen && action == "down" && layout.keypad.contains(px, py))) {
            keypadTouch(action, px, py)
            return null
        }
        var dispatch: String? = null
        when (action) {
            "down", "pointer-down" -> {
                val idle = mediaIdle()
                // A second finger landing on the open keypad is not on what it covers.
                val covered = keypadOpen && layout.keypad.contains(px, py)
                pressedTile = if (covered) -1 else layout.hitTile(px, py)
                // The transport buttons are not drawn while the pane lists players, so
                // they are not targets either; the rows are.
                pressedMedia = if (idle || covered) null else layout.hitMedia(px, py)
                if (action == "down") {
                    pressedMapBubble = layout.hitMapBubble(px, py)
                    pressedZoom = layout.hitZoomBubble(px, py)
                    // P9.6: the divided card and its two targets own every press on it.
                    val onCard = onCard(px, py)
                    val offer = shownOffer()
                    pressedOffer = if (offer != null) layout.hitOfferButton(px, py) else null
                    pressedOfferId = offer?.id ?: 0L
                    // P11.5: the Gas, Food and Shop buttons, as the bubbles are: over the map
                    // and everything drawn on it, and never a pan.
                    pressedPoiButton = if (pressedMapBubble == null && pressedZoom == null && !onCard) {
                        layout.hitPoiButton(px, py, poiTop())
                    } else {
                        null
                    }
                    val onControl = pressedMapBubble != null || pressedZoom != null || onCard || pressedPoiButton != null
                    // Under the map's own bubbles: a control drawn over an alternative wins,
                    // and so does the card over one that has slid under it.
                    pressedAlternative = if (!onControl) hitAlternative(px, py) else null
                    // P11.5: a marker under an alternative's bubble is under it in the picture too.
                    // The press stays the map's, so a drag that starts on a marker still pans.
                    pressedPoiMarker = if (!onControl && pressedAlternative == null) hitPoiMarker(px, py) else null
                    poiDownX = px
                    poiDownY = py
                    poiDownAt = nowMillis
                    pressedHeaderIcon = layout.hitHeaderIcon(px, py)
                    // P10: the weather item. During a call it does not light (plan §5.2).
                    val onWeather = weatherOn && layout.hitHeaderWeather(px, py)
                    pressedWeatherInCall = onWeather && call.phase != CallPhase.NONE
                    pressedWeather = onWeather && !pressedWeatherInCall
                    pressedEndRoute = upcomingTurn != null && layout.hitEndRoute(px, py)
                    pressedVoice = upcomingTurn != null && layout.hitVoiceToggle(px, py)
                    pressedPlayer = if (idle) layout.hitPlayer(px, py, players.size, !mediaAccess) else -1
                    // A finger on the turn card is on the card, not on the map under it.
                    mapGesture = !onControl && pressedAlternative == null
                    // The favourites are not drawn during a trip, so they take no presses.
                    if (upcomingTurn == null && layout.favoritesList.contains(px, py)) {
                        favoriteList.down(py, layout.hitFavorite(px, py, favoriteList.scroll, favorites.size))
                    }
                    // P7.8, P7.9: the flex tile's buttons or rows, whichever it shows.
                    val flex = flexSource()
                    pressedCallAction = if (flex == FlexSource.CALL) {
                        layout.hitCallAction(px, py, FlexRules.actions(call))
                    } else {
                        null
                    }
                    if (flex == FlexSource.NOTIFICATIONS && layout.notificationsList.contains(px, py)) {
                        notificationList.down(py, layout.hitNotification(px, py, notificationList.scroll, notifications.size))
                    }
                    // P10: the weather view and the alert are each one target, the whole tile.
                    val weatherTile = (flex == FlexSource.WEATHER || flex == FlexSource.WEATHER_ALERT) &&
                        layout.flexTile.contains(px, py)
                    pressedWeatherTile = if (weatherTile) flex else null
                    pressedWeatherAlert = if (weatherTile && flex == FlexSource.WEATHER_ALERT) shownAlert(nowMillis)?.id else null
                    // P11.5: the POI card's two buttons and its rows.
                    val card = poiCard
                    if (flex == FlexSource.POI && card != null) {
                        // A notice has Close alone: the primary's place is not a button.
                        pressedPoiCardButton = layout.hitPoiCardButton(px, py)?.takeUnless { it == "primary" && card.notice }
                        pressedPoiRow = if (pressedPoiCardButton == null) layout.hitPoiCardRow(px, py, card.entries.size) else -1
                        if (layout.flexTile.contains(px, py)) poiCardOpenedAt = nowMillis
                    } else {
                        pressedPoiCardButton = null
                        pressedPoiRow = -1
                    }
                }
            }
            "move" -> {
                favoriteList.move(py, layout.favoriteSlop, layout.favoritesMaxScroll(favorites.size))
                notificationList.move(py, layout.notificationSlop, layout.notificationsMaxScroll(notifications.size))
            }
            "up" -> {
                val tile = layout.hitTile(px, py)
                val media = layout.hitMedia(px, py)
                val bubble = layout.hitMapBubble(px, py)
                val zoom = layout.hitZoomBubble(px, py)
                val icon = layout.hitHeaderIcon(px, py)
                val endRoute = upcomingTurn != null && layout.hitEndRoute(px, py)
                val voice = upcomingTurn != null && layout.hitVoiceToggle(px, py)
                val player = if (mediaIdle()) layout.hitPlayer(px, py, players.size, !mediaAccess) else -1
                val callAction = if (flexSource() == FlexSource.CALL) layout.hitCallAction(px, py, FlexRules.actions(call)) else null
                val alternative = if (bubble == null && zoom == null && !onCard(px, py)) hitAlternative(px, py) else null
                val offer = shownOffer()
                val offerHit = if (offer != null) layout.hitOfferButton(px, py) else null
                val wasOffer = pressedOffer
                pressedOffer = null
                if (offer != null && offerHit != null && offerHit == wasOffer && offer.id == pressedOfferId) {
                    // Press and release on the same target of the same offer. The card is
                    // made whole now; the caller follows the choice through takeOfferAnswer.
                    val take = offerHit == "take"
                    if (take) offersTaken++ else offersKept++
                    offerAnswer = OfferAnswer(offer.id, take)
                    answeredOffer = offer.id
                    endOffer()
                }
                val wasAlternative = pressedAlternative
                pressedAlternative = null
                if (alternative != null && alternative == wasAlternative) {
                    // Press and release on the same bubble, as on every button.
                    alternativePresses++
                    alternativePick = alternative
                }
                val wasEndRoute = pressedEndRoute
                val wasVoice = pressedVoice
                val wasPlayer = pressedPlayer
                val wasCallAction = pressedCallAction
                pressedEndRoute = false
                pressedVoice = false
                pressedPlayer = -1
                pressedCallAction = null
                if (endRoute && wasEndRoute) {
                    // Press and release on End route, as on every other button. Ended by the
                    // caller through Navigation.clear(); counted here, and nothing else.
                    endRoutePresses++
                    endRouteRequested = true
                }
                if (voice && wasVoice) {
                    // P8.2: the same rule. Flipped here so the next frame draws it; saved,
                    // spoken and stopped by the caller through takeVoiceToggle().
                    voicePresses++
                    voiceOn = !voiceOn
                    voiceRequested = voiceOn
                }
                if (player >= 0 && player == wasPlayer) {
                    resumePresses++
                    val chosen = players[player]
                    resumePick = chosen
                    resumePending = chosen.key
                    if (resumeFailed == chosen.key) resumeFailed = null
                }
                if (callAction != null && callAction == wasCallAction) callPress(callAction)
                poiUp(px, py, nowMillis)
                weatherUp(px, py, nowMillis)
                val wasTile = pressedTile
                val wasMedia = pressedMedia
                val wasBubble = pressedMapBubble
                val wasZoom = pressedZoom
                val wasIcon = pressedHeaderIcon
                pressedTile = -1
                pressedMedia = null
                pressedMapBubble = null
                pressedZoom = null
                pressedHeaderIcon = null
                if (zoom != null && zoom == wasZoom) {
                    // One level a press, and following is left as it was: zooming out to
                    // see the next junction must not cost a recentre press afterwards.
                    zoomPresses++
                    mapControl.zoomBy(if (zoom == "zoom-in") 1 else -1)
                }
                if (icon != null && icon == wasIcon) headerIcon(icon)
                // Press and release must land in the same tile, as on any button: a
                // drag that starts elsewhere and ends here is not a tap on it.
                if (tile >= 0 && tile == wasTile) tileTaps[tile]++
                if (bubble != null && bubble == wasBubble) {
                    mapPresses++
                    when (bubble) {
                        "recentre" -> mapControl.recentre()
                        "compass" -> {
                            mapControl.flipOrientation()
                            onMapOrientation(mapControl.orientation)
                        }
                    }
                }
                if (media != null && media == wasMedia) {
                    mediaPresses++
                    dispatch = media
                }
                // Press and release on the same row, as with every other button.
                val place = favoriteList.up(layout.hitFavorite(px, py, favoriteList.scroll, favorites.size))
                if (place >= 0) favoritePick = favorites[place]
                val row = notificationList.up(layout.hitNotification(px, py, notificationList.scroll, notifications.size))
                if (row >= 0) {
                    // P7.8: read aloud. The row flashes so the tap is seen to land; the
                    // service hands it to Speaker.read (P8.1), and [reading] keeps it lit.
                    val chosen = notifications[row]
                    readPick = chosen
                    flashKey = chosen.key
                    flashUntil = nowMillis + READ_FLASH_MS
                }
            }
            "cancel" -> {
                pressedEndRoute = false
                pressedVoice = false
                pressedPlayer = -1
                pressedTile = -1
                pressedMedia = null
                pressedMapBubble = null
                pressedZoom = null
                pressedHeaderIcon = null
                pressedCallAction = null
                pressedAlternative = null
                pressedOffer = null
                pressedWeather = false
                pressedWeatherInCall = false
                pressedWeatherTile = null
                pressedWeatherAlert = null
                pressedPoiButton = null
                pressedPoiMarker = null
                pressedPoiCardButton = null
                pressedPoiRow = -1
                favoriteList.cancel()
                notificationList.cancel()
            }
        }
        if (mapGesture) mapControl.touch(action, pointers, layout.map, nowMillis)
        if (action == "up" || action == "cancel") mapGesture = false
        return dispatch
    }

    /**
     * A finger on the open keypad (P7.9). The tone starts when a key is pressed and stops
     * when the finger lifts or the press is cancelled, wherever it has slid to, so a held
     * key is a held tone, as on a real keypad. The echo strip takes the key only on a
     * press and release on that same key, the rule every button here follows.
     */
    private fun keypadTouch(action: String, px: Int, py: Int) {
        when (action) {
            "down" -> {
                keypadGesture = true
                pressedKeypadClose = layout.keypadClose.contains(px, py)
                pressedKey = layout.hitKeypadKey(px, py)
                if (pressedKey >= 0) {
                    if (toneOn) callActions.add(CallAction(CallActionKind.KEY_UP))
                    callActions.add(CallAction(CallActionKind.KEY_DOWN, FlexRules.KEYPAD_KEYS[pressedKey]))
                    toneOn = true
                }
            }
            "up" -> {
                if (toneOn) callActions.add(CallAction(CallActionKind.KEY_UP))
                toneOn = false
                val key = layout.hitKeypadKey(px, py)
                if (pressedKey >= 0 && key == pressedKey) {
                    keypadPresses++
                    keypadEcho.append(FlexRules.KEYPAD_KEYS[key])
                    if (keypadEcho.length > ECHO_MAX) keypadEcho.delete(0, keypadEcho.length - ECHO_MAX)
                }
                val close = pressedKeypadClose && layout.keypadClose.contains(px, py)
                keypadGesture = false
                pressedKey = -1
                pressedKeypadClose = false
                if (close) {
                    keypadCloses++
                    closeKeypad()
                }
            }
            "cancel" -> {
                if (toneOn) callActions.add(CallAction(CallActionKind.KEY_UP))
                toneOn = false
                keypadGesture = false
                pressedKey = -1
                pressedKeypadClose = false
            }
        }
    }

    /**
     * A completed press on the call card: queued for the service, which sends it to
     * [CallControl]. Mute asks for the opposite of what is drawn, and what is drawn comes
     * back from Telecom, so a mute pressed on the phone or the truck shows here too.
     */
    private fun callPress(action: String) {
        when (action) {
            "answer" -> {
                answerPresses++
                callActions.add(CallAction(CallActionKind.ANSWER))
            }
            "decline" -> {
                declinePresses++
                callActions.add(CallAction(CallActionKind.DECLINE))
            }
            "hang-up" -> {
                hangUpPresses++
                callActions.add(CallAction(CallActionKind.HANG_UP))
            }
            "mute" -> {
                mutePresses++
                callActions.add(CallAction(if (call.muted) CallActionKind.UNMUTE else CallActionKind.MUTE))
            }
            "keypad" -> if (call.canDial) {
                keypadOpens++
                keypadOpen = true
            }
        }
    }

    /**
     * P10: a finger lifted. Press and release on the same target, as for every button: the
     * header item opens or closes the view, except during a call, when it only counts; the
     * open view closes on a tap; the alert is dismissed for the session.
     */
    private fun weatherUp(px: Int, py: Int, nowMillis: Long) {
        val onWeather = weatherOn && layout.hitHeaderWeather(px, py)
        val wasWeather = pressedWeather
        val wasInCall = pressedWeatherInCall
        val wasTile = pressedWeatherTile
        val wasAlert = pressedWeatherAlert
        pressedWeather = false
        pressedWeatherInCall = false
        pressedWeatherTile = null
        pressedWeatherAlert = null
        if (onWeather && wasInCall) {
            weatherTapsInCall++
        } else if (onWeather && wasWeather) {
            if (call.phase != CallPhase.NONE) {
                weatherTapsInCall++
            } else if (weatherOpen) {
                weatherOpen = false
                weatherClosesTap++
            } else {
                weatherOpen = true
                weatherOpenedAt = nowMillis
                weatherOpens++
                // P11.5: one tapped view at a time in the tile.
                closePoiCard()
            }
        }
        if (wasTile == null || !layout.flexTile.contains(px, py) || flexSource(nowMillis) != wasTile) return
        when (wasTile) {
            FlexSource.WEATHER -> {
                weatherOpen = false
                weatherClosesView++
            }
            FlexSource.WEATHER_ALERT -> {
                val shown = shownAlert(nowMillis)
                if (shown != null && shown.id == wasAlert) {
                    dismissedAlerts += shown.id
                    alertsDismissed++
                }
            }
            else -> Unit
        }
    }

    /**
     * P11.5: a finger lifted. Press and release on the same target, as for every button: a
     * POI button flips; a marker opens its card, if the press stayed put and was not a long
     * press (that stays the map's: a destination where the finger was); the card's Go or
     * Close, or a row, which chooses that place.
     */
    private fun poiUp(px: Int, py: Int, nowMillis: Long) {
        val wasButton = pressedPoiButton
        val wasMarker = pressedPoiMarker
        val wasCardButton = pressedPoiCardButton
        val wasRow = pressedPoiRow
        pressedPoiButton = null
        pressedPoiMarker = null
        pressedPoiCardButton = null
        pressedPoiRow = -1
        if (wasButton != null && layout.hitPoiButton(px, py, poiTop()) == wasButton) {
            poiButtonPresses[wasButton.ordinal]++
            val on = poiKinds.add(wasButton)
            if (!on) poiKinds.remove(wasButton)
            poiToggles += wasButton to on
        }
        if (wasMarker != null) {
            val still = Math.abs(px - poiDownX) <= POI_TAP_SLOP && Math.abs(py - poiDownY) <= POI_TAP_SLOP
            val pile = hitPoiMarkers(px, py)
            if (still && nowMillis - poiDownAt < MapControl.LONG_PRESS_MS && pile.firstOrNull() === wasMarker) {
                poiTaps++
                // One place, or up to three piled under the finger, nearest first.
                val places = pile.take(PoiCardContent.MAX_ENTRIES)
                val kinds = places.map { it.kind }.toSet()
                val label = if (kinds.size == 1) PoiRules.cardLabel(kinds.first()) else PoiRules.PLACES
                showPoiCard(PoiCardContent(label, places.map { PoiEntry(it) }), nowMillis)
            }
        }
        val card = poiCard ?: return
        if (flexSource(nowMillis) != FlexSource.POI) return
        val button = layout.hitPoiCardButton(px, py)
        if (button != null && button == wasCardButton) {
            when (button) {
                "primary" -> if (!card.notice) {
                    poiPrimaryPresses++
                    poiAction = PoiAction(card.entries[poiSelected.coerceIn(0, card.entries.size - 1)], card.primary, card.tag)
                    closePoiCard()
                }
                "close" -> {
                    poiClosePresses++
                    closePoiCard()
                }
            }
            return
        }
        val row = layout.hitPoiCardRow(px, py, card.entries.size)
        if (row >= 0 && row == wasRow) {
            poiRowPicks++
            poiSelected = row
        }
    }

    /** P10: the alert the tile would show now, or null (plan §5.3). */
    private fun shownAlert(nowMillis: Long): WeatherAlert? {
        if (!weatherOn) return null
        val reading = weatherState.reading ?: return null
        return WeatherRules.qualifying(reading.data.alerts, nowMillis, alertLevel, dismissedAlerts).firstOrNull()
    }

    /** What the flex tile shows now ([FlexRules.source]). */
    private fun flexSource(nowMillis: Long = lastNowMillis): FlexSource =
        FlexRules.source(
            call, notificationsPaused, notifications.size, weatherOn && weatherOpen, shownAlert(nowMillis) != null, poiCard != null,
        )

    /** The single-pointer form, for a caller that has nothing to pinch with. */
    @Synchronized
    @JvmOverloads
    fun touch(action: String, px: Int, py: Int, nowMillis: Long = System.currentTimeMillis()): String? =
        touch(action, listOf(TouchPoint(0, px, py)), nowMillis)

    /**
     * A completed press on a header icon. Each hands its new value out to be saved, as
     * the compass does, so the choice carries into the next session and the phone's
     * settings screen shows it.
     */
    private fun headerIcon(action: String) {
        when (action) {
            "pause" -> {
                pausePresses++
                notificationsPaused = !notificationsPaused
                saves.notificationsPaused(notificationsPaused)
            }
            "day-night" -> {
                dayNightPresses++
                dayNight = dayNight.next()
                saves.dayNight(dayNight)
                noteResolved()
            }
        }
    }

    /** The place a long press on the map picked as a destination, once, or null. */
    @Synchronized
    fun takeMapPick(): Pair<Double, Double>? = mapControl.takePick()

    /** The vehicle's own fix, or null: where a route starts while projecting (P6.4). */
    @Synchronized
    fun vehiclePosition(): Pair<Double, Double>? = vehicle.position()

    /**
     * The speed spoken prompts are timed by (P8 plan §5.1), metres a second: the head
     * unit's `car_speed`, else its `gps_speed`, each only at a scale the drive so far
     * supports ([ScaleEvidence]), else the phone's own, else null. Never reported.
     */
    @Synchronized
    fun guidanceSpeed(): Double? =
        vehicle.carSpeed?.takeIf { it.validated }?.value
            ?: vehicle.gpsSpeed?.takeIf { it.validated }?.value
            ?: phone.speedMetresPerSecond

    /**
     * Resolves the position source and produces the next frame's values.
     *
     * The vehicle wins when it has a fix, because it needs no Android location
     * permission and is the source P5.2 measured; the phone is the fallback. Speed and
     * bearing are resolved independently of position: this head unit populates GPS but
     * its bearing scale is inferred rather than measured, so a phone bearing is
     * preferred over a vehicle bearing that no candidate scale could bring into range.
     */
    @Synchronized
    fun nextFrame(nowMillis: Long): Snapshot {
        frames++
        lastNowMillis = nowMillis
        // P10 (plan §5.2): an untouched weather view closes by itself.
        if (weatherOpen && nowMillis - weatherOpenedAt >= WeatherRules.VIEW_TIMEOUT_MS) {
            weatherOpen = false
            weatherClosesTimeout++
        }
        // P11.5: an untouched POI card closes by itself, so it never hides the tile for a drive.
        if (poiCard != null && nowMillis - poiCardOpenedAt >= POI_CARD_TIMEOUT_MS) {
            closePoiCard()
            poiCardTimeouts++
        }
        val vehiclePosition = vehicle.position()
        val phonePosition = phone.latitude?.let { lat -> phone.longitude?.let { lat to it } }
        val source = when {
            vehiclePosition != null -> PositionSource.VEHICLE
            phonePosition != null -> PositionSource.PHONE
            else -> PositionSource.NONE
        }
        val bearing = resolvedBearing()
        val vehicleSpeed = (vehicle.carSpeed ?: vehicle.gpsSpeed)?.takeIf { it.validated }
        val speed = vehicleSpeed?.value ?: phone.speedMetresPerSecond
        moving = when {
            speed == null -> false
            moving -> speed >= ARROW_OFF_MPS
            else -> speed > ARROW_ON_MPS
        }
        // The bearing is against north and the pane's top is at the map's rotation, so
        // the arrow turns by the difference; in direction-of-travel-up that is straight up.
        val arrow = if (moving) bearing?.let { Math.round(wrapDegrees(it - mapControl.rotation)).toInt() } else null
        val idle = mediaIdle()
        if (resumeFailed != null && nowMillis >= resumeFailedUntil) resumeFailed = null
        playerRows = if (idle) minOf(players.size, DashboardLayout.PLAYER_ROWS) else 0
        playerRowsMax = maxOf(playerRowsMax, playerRows)
        val flex = flexSource(nowMillis)
        if (flashKey != null && nowMillis >= flashUntil) flashKey = null
        val weatherFix = followPosition()
        val weatherView = if (flex == FlexSource.WEATHER) {
            WeatherDisplay.panel(weatherState, nowMillis, weatherFix, zone, pressedWeatherTile == FlexSource.WEATHER, clockFormat)
        } else {
            null
        }
        val weatherAlert = if (flex == FlexSource.WEATHER_ALERT) {
            WeatherDisplay.alert(
                weatherState, nowMillis, weatherFix, zone, alertLevel, dismissedAlerts,
                pressedWeatherTile == FlexSource.WEATHER_ALERT, clockFormat,
            )?.also { shownAlerts += it.id }
        } else {
            null
        }
        if ((weatherView != null || weatherAlert != null) && weatherState.reading?.let { WeatherText.farKm(it.place, weatherFix) } != null) {
            weatherFar = true
        }
        val shown = if (flex == FlexSource.NOTIFICATIONS) notifications else emptyList()
        notificationsShownMax = maxOf(notificationsShownMax, minOf(shown.size, DashboardLayout.NOTIFICATION_ROWS))
        return Snapshot(
            frame = frames,
            clock = clockFormat.clock(nowMillis, zone),
            vehicle = vehicle,
            phone = phone,
            source = source,
            bearing = bearing,
            speed = speed,
            speedUnit = speedUnit,
            nowPlaying = nowPlaying,
            title = title,
            artist = artist,
            artKey = artKey,
            playing = playing,
            transport = if (transports == 0L) null else "$lastTransport x$transports",
            restriction = restriction,
            pressedTile = pressedTile,
            pressedMedia = pressedMedia,
            pressedMapBubble = pressedMapBubble,
            mapStatus = mapStatus,
            mapOrientation = mapControl.orientation,
            mapFollowing = mapControl.following,
            mapRotation = Math.round(mapControl.rotation),
            mapMarker = mapControl.marker(layout.map, followPosition()),
            alternativeBubbles = alternativeBubbles().also { if (it.isNotEmpty()) alternativeBubbleFrames++ },
            mapArrow = arrow,
            tileTaps = tileTaps.toList(),
            favorites = favorites,
            favoritesScroll = favoriteList.scroll,
            pressedFavorite = favoriteList.pressed,
            recentFavorite = recentFavorite,
            turnCard = turnCard(),
            trip = tripPanel(nowMillis),
            night = resolveNight(dayNight, headUnitNight, rememberedNight),
            dayNight = dayNight,
            markerColour = markerColour,
            diagnostics = diagnostics,
            notificationsPaused = notificationsPaused,
            pressedHeaderIcon = pressedHeaderIcon,
            pressedZoom = pressedZoom,
            scaleInferred = scaleInferred(vehicle),
            players = players,
            mediaIdle = idle,
            mediaAccess = mediaAccess,
            pressedPlayer = pressedPlayer,
            resumePending = resumePending,
            resumeFailed = resumeFailed,
            flex = flex,
            flexLabel = if (flex == FlexSource.POI) poiCard?.label ?: FlexRules.label(flex, call) else FlexRules.label(flex, call),
            notifications = shown,
            notificationsScroll = notificationList.scroll,
            pressedNotification = notificationList.pressed,
            flashNotification = flashKey ?: readingKey,
            call = if (flex == FlexSource.CALL) call else CallState.NONE,
            callDuration = if (flex == FlexSource.CALL) FlexRules.duration(call, nowMillis) else null,
            callActions = if (flex == FlexSource.CALL) FlexRules.actions(call) else emptyList(),
            pressedCallAction = pressedCallAction,
            keypadOpen = keypadOpen,
            keypadEcho = keypadEcho.toString(),
            pressedKey = pressedKey,
            pressedKeypadClose = pressedKeypadClose,
            trafficAttributed = trafficDisplay.attributed.also { if (it) attributedFrames++ },
            offer = shownOffer()?.let { OfferCard(it.id, it.label, it.reason, pressedOffer) },
            weatherHeader = if (weatherOn) {
                WeatherDisplay.header(weatherState, nowMillis, alertLevel, pressedWeather, layout::headerWeatherText)
            } else {
                null
            },
            weatherView = weatherView,
            weatherAlert = weatherAlert,
            poiButtons = PoiButtons(poiKinds(), pressedPoiButton, poiTop()),
            poiCard = if (flex == FlexSource.POI) poiCardView(nowMillis) else null,
        )
    }

    /** The position the map should follow, or null when nothing has a fix. */
    @Synchronized
    fun followPosition(): Pair<Double, Double>? =
        vehicle.position() ?: phone.latitude?.let { lat -> phone.longitude?.let { lat to it } }

    /**
     * Where to put the map for the next frame, or null when there is nothing to show.
     *
     * Called before [nextFrame] so the pane and the chrome drawn over it agree about
     * where the map is looking: the vehicle marker's pixel is worked out against the
     * camera this returns.
     */
    @Synchronized
    fun mapCamera(): MapCamera? =
        mapControl.camera(followPosition(), resolvedBearing(), if (turnCard() != null) layout.markerBelow else 0)

    /**
     * The turn card for the route as guidance last described it (P7.4), or null: no
     * route, or a route with no fix on it yet, which has nothing honest to put on a card.
     */
    private fun turnCard(): TurnCard? {
        val turn = upcomingTurn ?: return null
        return when (turn.text) {
            UpcomingTurn.WAITING -> null
            UpcomingTurn.REROUTING, UpcomingTurn.ARRIVED -> TurnCard(turn.maneuver, turn.exit, null, turn.text)
            // The street on its own line (plan §5.3), and the instruction when the map
            // names none: "Turn left" says more than an empty line under a left arrow.
            else -> TurnCard(turn.maneuver, turn.exit, turn.metres?.let { speedUnit.distance(it) }, turn.street ?: turn.text)
        }
    }

    /**
     * The trip panel (P7.4), or null with no route. The arrival time is [nowMillis] plus
     * BRouter's own time to the destination from the last fix, so a parked vehicle's
     * arrival slips as the clock runs, as it should; all of it is in whole minutes and
     * rounded distances, so the chrome repaints about once a minute, not every frame.
     */
    private fun tripPanel(nowMillis: Long): TripPanel? {
        val turn = upcomingTurn ?: return null
        val arrived = turn.text == UpcomingTurn.ARRIVED
        val rerouting = turn.text == UpcomingTurn.REROUTING
        val seconds = turn.destinationSeconds.takeUnless { arrived || rerouting }
        val metres = turn.destinationMetres.takeUnless { arrived || rerouting }
        return TripPanel(
            // Rounded to the nearest minute, as the time remaining is: truncated, the first
            // lab frame said "15:23" beside "9 min" at 15:15.
            arrival = seconds?.let {
                clockFormat.time(nowMillis + it.coerceAtLeast(0) * 1000L + 30_000L, zone)
            },
            remaining = seconds?.let { StatusText.duration(it) },
            distance = metres?.let { speedUnit.distance(it) },
            later = if (arrived || rerouting) {
                emptyList()
            } else {
                turn.later.map { TripStep(it.maneuver.type, it.maneuver.exit, speedUnit.distance(it.metres)) }
            },
            arrived = arrived,
            rerouting = rerouting,
            endPressed = pressedEndRoute,
            voiceOn = voiceOn,
            voiceWarning = !speechAvailable,
            voicePressed = pressedVoice,
            traffic = if (seconds == null) null else trafficDelayLabel(trafficDisplay.delaySeconds),
        )
    }

    /**
     * Direction of travel from whichever source is trusted, or null.
     *
     * The vehicle's own bearing wins when a candidate scale brought it into range; this
     * head unit populates the field but the scale behind it was inferred rather than
     * measured, so an unvalidated one gives way to the phone's.
     */
    @Synchronized
    private fun resolvedBearing(): Double? =
        vehicle.bearing?.takeIf { it.validated }?.value ?: phone.bearingDegrees

    /**
     * `key=value` lines for the session report (spec §10).
     *
     * No latitude or longitude, and no track text: position is vehicle-identifying and
     * the now-playing line is the operator's listening history. What is recorded is
     * which source won and whether its scale had to be inferred, because that is the
     * open question this slice exists to answer.
     */
    @Synchronized
    fun report(): String =
        "dashboard_token=$token\n" +
            "dashboard_layout=$layout\n" +
            "dashboard_frames=$frames dashboard_touches=$touches " +
            "dashboard_tile_taps=${tileTaps.joinToString(",")} " +
            "dashboard_media_presses=$mediaPresses dashboard_map_presses=$mapPresses " +
                "dashboard_alternative_presses=$alternativePresses dashboard_alternatives=${alternatives.size} " +
                "dashboard_alternative_bubble_frames=$alternativeBubbleFrames " +
            "dashboard_zoom_presses=$zoomPresses " +
            "dashboard_transport=$transports " +
            "dashboard_album_art=${artKey != null} " +
            "dashboard_last_transport=$lastTransport\n" +
            "dashboard_position_source=${
                (vehicle.position()?.let { PositionSource.VEHICLE }
                    ?: phone.latitude?.let { PositionSource.PHONE }
                    ?: PositionSource.NONE).label
            } " +
            "dashboard_vehicle_events=$vehicleEvents " +
            "dashboard_vehicle_fix=${vehicle.position() != null} " +
            "dashboard_phone_fix=${phone.latitude != null} " +
            "dashboard_phone_location_enabled=${phone.locationEnabled}\n" +
            "dashboard_bearing_divisor=${vehicle.bearing?.divisor ?: "-"} " +
            "dashboard_bearing_validated=${vehicle.bearing?.validated ?: "-"} " +
            "dashboard_car_speed_divisor=${vehicle.carSpeed?.divisor ?: "-"} " +
            "dashboard_car_speed_validated=${vehicle.carSpeed?.validated ?: "-"} " +
            "dashboard_speed_unit=${speedUnit.label} " +
            "dashboard_gear=${vehicle.gearName()} " +
            "dashboard_satellites=${vehicle.satellitesUsed ?: "-"}/${vehicle.satelliteCount ?: "-"}\n" +
            "dashboard_map=$mapStatus\n" +
            // P7.6: the head unit's own word, recorded as it came, beside what was drawn.
            "dashboard_day_night=${dayNight.key} " +
            "dashboard_night=${resolveNight(dayNight, headUnitNight, rememberedNight)} " +
            "dashboard_head_unit_night=${headUnitNight ?: "-"} " +
            "dashboard_remembered_night=${rememberedNight ?: "-"} " +
            "dashboard_night_events=$nightEvents dashboard_night_changes=$nightChanges " +
            "dashboard_day_night_presses=$dayNightPresses\n" +
            // P7.2, P7.3: the display choices, and the pause. Counts and names of
            // settings only.
            "dashboard_marker=${markerColour.key} dashboard_clock=${clockFormat.key} dashboard_diagnostics=$diagnostics " +
            "dashboard_notifications_paused=$notificationsPaused " +
            "dashboard_pause_presses=$pausePresses dashboard_players=${players.size}\n" +
            "dashboard_map_view=${mapControl.report()}\n" +
            // How many places and how the tile was used; never which places.
            "dashboard_favorites=${favorites.size} dashboard_favorite_taps=${favoriteList.taps} " +
            "dashboard_favorite_scrolls=${favoriteList.scrolls}\n" +
            // P7.4: whether a trip is on and how End route was used; never where to.
            "dashboard_trip=${upcomingTurn != null} dashboard_trips=$trips " +
            // P9.4: the trip panel's time to the destination, traffic included, in seconds, so
            // the lab can set it beside the cluster's; a duration, never a place.
            "dashboard_trip_seconds=${upcomingTurn?.destinationSeconds ?: "-"} " +
            "dashboard_turn_card=${turnCard() != null} dashboard_end_route_presses=$endRoutePresses " +
            "dashboard_voice_toggle_presses=$voicePresses dashboard_voice_guidance=${if (voiceOn) "on" else "off"}\n" +
            // P7.5: how many players were offered and what Resume did; never which app.
            "dashboard_media_idle=${mediaIdle()} dashboard_media_access=$mediaAccess " +
            "dashboard_player_rows=$playerRows dashboard_player_rows_max=$playerRowsMax " +
            "dashboard_resume_presses=$resumePresses dashboard_resume_played=$resumePlayed " +
            "dashboard_resume_failures=$resumeFailures\n" +
            // P7.8: what the flex tile showed and how its rows were used; never an app, a
            // sender or a word of text.
            "dashboard_flex=${flexSource().key} dashboard_notifications=${notifications.size} " +
            "dashboard_notification_rows_max=$notificationsShownMax " +
            "dashboard_notification_taps=${notificationList.taps} " +
            "dashboard_notification_scrolls=${notificationList.scrolls}\n" +
            // P7.9: how the call card and the keypad were used; never a caller, a number
            // or a key. The echo's length is not recorded either: it is a PIN's length.
            "dashboard_calls=$calls dashboard_call_phase=${call.phase.name.lowercase()} " +
            "dashboard_answer_presses=$answerPresses dashboard_decline_presses=$declinePresses " +
            "dashboard_hang_up_presses=$hangUpPresses dashboard_mute_presses=$mutePresses " +
            "dashboard_keypad_opens=$keypadOpens dashboard_keypad_closes=$keypadCloses " +
            "dashboard_keypad_auto_closes=$keypadAutoCloses dashboard_keypad_presses=$keypadPresses\n" +
            // P9: what traffic put on the dashboard and what the driver did with the offers;
            // counts, minutes and booleans, never a road, a reason or a place.
            "dashboard_traffic_displays=$trafficDisplays dashboard_traffic_runs=${trafficDisplay.traffic?.runs?.size ?: 0} " +
            "dashboard_traffic_attributed=${trafficDisplay.attributed} dashboard_traffic_attributed_frames=$attributedFrames " +
            "dashboard_traffic_delay_min=${Math.round(trafficDisplay.delaySeconds / 60.0)} " +
            "dashboard_offer=${shownOffer() != null} dashboard_offer_layout=${layout.offerLayout.key} " +
            "dashboard_offers_shown=$offersShown dashboard_offers_taken=$offersTaken dashboard_offers_kept=$offersKept " +
            "dashboard_offers_withdrawn=$offersWithdrawn dashboard_offer_fits=$offerFits " +
            "dashboard_offer_refollows=$offerRefollows\n" +
            // P10: how the weather item and views were used; never a place, a temperature,
            // a condition or a headline. weather_far: the distance label was shown.
            "weather_shown=$weatherOn weather_view_open=$weatherOpen weather_view_opens=$weatherOpens " +
            "weather_view_closes_tap=$weatherClosesTap weather_view_closes_view=$weatherClosesView " +
            "weather_view_closes_timeout=$weatherClosesTimeout weather_view_closes_call=$weatherClosesCall " +
            "weather_taps_in_call=$weatherTapsInCall weather_alerts_shown=${shownAlerts.size} " +
            "weather_alerts_dismissed=$alertsDismissed weather_far=$weatherFar\n" +
            // P11.5: how the POI buttons, markers and card were used; never a place or a name.
            "poi_on=${poiKinds().joinToString(",") { it.label }.ifEmpty { "-" }} " +
            "poi_button_presses=${PoiRules.ORDER.joinToString(",") { "${it.label}:${poiButtonPresses[it.ordinal]}" }} " +
            "poi_markers_shown=${poiMarkers.size} poi_markers_shown_max=$poiMarkersMax poi_taps=$poiTaps " +
            "poi_cards=$poiCardsShown poi_primary_presses=$poiPrimaryPresses poi_close_presses=$poiClosePresses " +
            "poi_row_picks=$poiRowPicks poi_card_timeouts=$poiCardTimeouts poi_card_closes_call=$poiCardClosesCall " +
            "poi_buttons=${layout.poiButtonRow(layout.poiButtonsTop(false, false)).let { "${it.x},${it.y}+${it.width}x${it.height}" }}\n"

    companion object {
        /** Above 5 mph the marker becomes an arrow along the direction of travel. */
        const val ARROW_ON_MPS = 5 * 0.44704

        /** Below 4 mph it goes back to a dot: a noisy bearing at a crawl points anywhere. */
        const val ARROW_OFF_MPS = 4 * 0.44704

        /**
         * How long a failed Resume says so on its row (P7.5): long enough to be seen at a
         * glance back at the screen, short enough that the row is pressable again soon.
         */
        const val RESUME_FAILED_MS = 4_000L

        /**
         * How long a notification row stays lit after a tap asks for it to be read aloud
         * (P7.8): long enough to see from the seat that the tap landed, which matters while
         * reading aloud is a stub that says nothing.
         */
        const val READ_FLASH_MS = 800L

        /** Keys the keypad's strip keeps: more than a PIN or an extension, fewer than would overflow it. */
        const val ECHO_MAX = 24

        /** P11.5: an untouched POI card closes after this long. */
        const val POI_CARD_TIMEOUT_MS = 60_000L

        /** P11.5: a press on a marker is a tap if it moves no further than this and lifts sooner than a long press. */
        const val POI_TAP_SLOP = 24

        private fun wrapDegrees(degrees: Double): Double = ((degrees % 360) + 360) % 360

        /**
         * The trip panel's "+7 min traffic" for a delay of [seconds] (P9.4), rounded to the
         * nearest minute as the time remaining is; null under half a minute, since "+0 min"
         * says nothing and the attribution already says traffic is in the time.
         */
        @JvmStatic
        fun trafficDelayLabel(seconds: Int): String? {
            val minutes = Math.round(seconds / 60.0)
            return if (minutes >= 1) "+$minutes min traffic" else null
        }

        /**
         * The palette to draw (P7.6, plan §5.6): Day and Night hold it; Automatic follows
         * [headUnit], the head unit's `is_night`, every time it changes.
         *
         * Before the head unit has said anything, Automatic draws [remembered], the palette
         * the last session ended in, else night. Not day: every recorded Ford session
         * sent `is_night=true` (the truck's display is pinned dark), the sensor channel
         * opens a moment before the picture starts, and guessing day meant themeing the
         * map day and then reloading every tile for night a second later
         * (docs/p7-6-map-results.md §3.2).
         */
        fun resolveNight(mode: DayNightMode, headUnit: Boolean?, remembered: Boolean?): Boolean =
            mode.resolve(headUnit ?: remembered ?: true)

        /**
         * Whether the bearing or speed the header shows rests on a divisor no drive has
         * confirmed ([ScaleRule.confirmed]): the diagnostics line's "scale inferred".
         * A value that did not validate is not shown from the vehicle at all (the phone's
         * is preferred), so only validated ones are asked about.
         */
        fun scaleInferred(vehicle: VehicleReading): Boolean {
            val bearing = vehicle.bearing?.takeIf { it.validated }
            val speed = (vehicle.carSpeed ?: vehicle.gpsSpeed)?.takeIf { it.validated }
            return (bearing != null && bearing.divisor !in ScaleRule.BEARING.confirmed) ||
                (speed != null && speed.divisor !in ScaleRule.SPEED.confirmed)
        }

        /** Reuses the demo scene's derivation so a token means the same thing in both. */
        fun token(sessionUuid: String): String = DemoScene.token(sessionUuid)
    }
}
