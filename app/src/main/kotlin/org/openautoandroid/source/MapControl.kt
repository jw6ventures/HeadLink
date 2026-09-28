package org.openautoandroid.source

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One head-unit pointer: video frame pixels, plus the coordinates the head unit itself
 * reported. Mirrors `aap_session::input::Pointer`, which is what produced them.
 */
data class TouchPoint(
    val id: Int,
    val x: Int,
    val y: Int,
    /** The head unit's own numbers, which the G5 calibration is written against. */
    val rawX: Int = x,
    val rawY: Int = y,
) {
    companion object {
        /**
         * The pointers of a touch event, out of the native session's `pointers=` field.
         *
         * The format is `id:x:y:rawx:rawy` per pointer, comma separated, exactly as
         * `aap_session::input::Touch`'s `Display` writes it. Pure JVM and separate from
         * the service that receives it so tools/jni-host-test can hold this to the
         * format the Rust side actually emits: a silent misparse here would not break
         * anything visible, it would just quietly reduce every pinch to one finger.
         *
         * A pointer that will not parse is dropped rather than taking the whole event
         * with it — the rest of the hand is still a usable gesture, and the native side
         * has already counted and reported anything it rejected.
         */
        fun parse(field: String?): List<TouchPoint> {
            val entries = field?.split(',') ?: return emptyList()
            val pointers = ArrayList<TouchPoint>(entries.size)
            for (entry in entries) {
                val values = entry.split(':').mapNotNull { it.toIntOrNull() }
                if (values.size < 5) continue
                pointers.add(TouchPoint(values[0], values[1], values[2], values[3], values[4]))
            }
            return pointers
        }
    }
}

/** What the map pane puts at the top of the picture. */
enum class MapOrientation(val label: String) {
    NORTH_UP("north-up"),
    HEADING_UP("heading-up"),
    ;

    companion object {
        /** The orientation a stored [label] names, or null for one this build does not know. */
        fun of(label: String?): MapOrientation? = entries.firstOrNull { it.label == label }
    }
}

/** Where the map pane is centred, how far in, and which way is up, for one frame. */
data class MapCamera(
    val latitude: Double,
    val longitude: Double,
    /** VTM's linear scale, which is 2^zoom. */
    val scale: Double,
    /** Degrees clockwise from north shown at the top of the pane. */
    val rotation: Float,
)

/**
 * Where the map pane is looking, and the head-unit gestures that move it: a one-finger
 * drag pans, two fingers pinch to zoom and twist to rotate, a long press picks the place
 * under the finger as a destination (P6.4), and the two floating bubbles recentre it and
 * switch between north-up and direction-of-travel-up. Two more, + and −, zoom it a whole
 * level at a time (P7.2).
 *
 * Pure JVM on purpose, like [Dashboard] itself: the projection here is the arithmetic
 * that decides where a driver's finger lands on the world, and tools/jni-host-test can
 * check it without an Android device or a GL context.
 *
 * Not thread safe. [Dashboard] owns the only instance and every entry point into it is
 * already under that lock, which is what keeps the touch thread and the renderer thread
 * from reading a half-applied gesture.
 */
class MapControl(
    /** Which way is up when the session starts: the one the driver last chose. */
    orientation: MapOrientation = MapOrientation.NORTH_UP,
) {

    /** Which way is up. Changed only by the compass bubble. */
    var orientation: MapOrientation = orientation
        private set

    /** Whether the pane is still centred on the position source. */
    var following: Boolean = true
        private set

    /** VTM's linear scale, moved by the pinch. */
    var scale: Double = DEFAULT_SCALE
        private set

    /**
     * Degrees the twist has turned the map away from [orientation]'s own heading.
     *
     * Kept apart from the heading rather than folded into one absolute rotation so that
     * a twist survives the vehicle turning a corner: in direction-of-travel-up the base
     * moves under it every time a bearing arrives, and an absolute rotation would be
     * overwritten by the next fix.
     */
    var turn: Float = 0f
        private set

    /**
     * Pixels per world unit come from `scale * Tile.SIZE`, and VTM's tile size follows
     * the canvas dpi rather than being 256 — at the map engine's 240dpi it is 384. The
     * engine reports its own once it has started; this is what the arithmetic assumes
     * until then, and being wrong here makes a drag move the map by the wrong distance
     * rather than in the wrong direction.
     */
    var tileSize: Int = DEFAULT_TILE_SIZE
        private set

    /** Where the pane is centred once a drag has taken it off the position source. */
    private var freeLatitude = 0.0
    private var freeLongitude = 0.0

    /** The last heading a fix carried, held when the source goes quiet. */
    private var heading = 0f

    /**
     * The camera the last frame was drawn with, and the base every gesture works from.
     *
     * A gesture has to act on the picture the driver is touching, not on the position
     * that is about to arrive, so the anchoring below reads the camera that was actually
     * rasterised rather than recomputing one from the current fix.
     */
    private var last: MapCamera? = null

    // Gesture baseline, in frame pixels, for the pointers of the event before this one.
    private var tracking = false
    private var count = 0
    private var focalX = 0f
    private var focalY = 0f
    private var span = 0f
    private var angle = 0f

    /** How far the gesture's focal point has travelled, against [PAN_SLOP]. */
    private var travelled = 0f

    /** Where and when the gesture's first finger landed, for the long press. */
    private var downX = 0
    private var downY = 0
    private var downAt = 0L

    /** A place a long press picked and nobody has taken yet, as latitude to longitude. */
    private var pick: Pair<Double, Double>? = null

    // Counters for the session report. No position is among them.
    private var gestures = 0L
    private var multiPointer = 0L
    private var pointersMax = 0
    private var pans = 0L
    private var recentres = 0L
    private var flips = 0L
    private var longPresses = 0L
    private var pinched = false

    /** Degrees clockwise from north at the top of the pane, as last drawn. */
    val rotation: Float get() = last?.rotation ?: 0f

    /** Adopts the engine's real tile size; a value that is not plausible is ignored. */
    fun useTileSize(size: Int) {
        if (size in 64..4096) tileSize = size
    }

    /**
     * Where to put the map for this frame, or null when nothing has a fix to follow.
     *
     * `bearing` is the direction of travel from whichever source won, and is only read
     * in [MapOrientation.HEADING_UP]. A null one holds the last heading instead of
     * snapping the map north: losing the fix for a moment at a junction is exactly when
     * a driver is reading the map, and spinning it under them is worse than being a few
     * degrees stale.
     *
     * [below] is how many pixels under the pane's centre the followed position sits
     * (P7.4): while the turn card covers the top of the pane, the vehicle moves down by
     * half the card so it is centred in the part of the map still showing, and what lies
     * ahead of it in direction-of-travel-up is not the part the card hides. Only while
     * following; a map the driver has moved stays where they put it.
     */
    @JvmOverloads
    fun camera(follow: Pair<Double, Double>?, bearing: Double?, below: Int = 0): MapCamera? {
        if (bearing != null) heading = wrap(bearing.toFloat())
        val base = if (orientation == MapOrientation.HEADING_UP) heading else 0f
        val rotation = wrap(base + turn)
        val centre = if (following) {
            val (latitude, longitude) = follow ?: return null
            if (below == 0) {
                latitude to longitude
            } else {
                // The centre is the ground [below] pixels up the screen from the vehicle,
                // which is where [marker] then puts it back: exactly [below] under the centre.
                val (ox, oy) = worldOffset(0f, below.toFloat(), rotation, scale)
                var x = (worldX(longitude) - ox) % 1.0
                if (x < 0) x += 1.0
                toLatitude((worldY(latitude) - oy).coerceIn(0.0, 1.0)) to toLongitude(x)
            }
        } else {
            freeLatitude to freeLongitude
        }
        val camera = MapCamera(centre.first, centre.second, scale, rotation)
        last = camera
        return camera
    }

    /**
     * Frame pixel the position source is drawn at, or null when it is off the pane.
     *
     * While the map is following this is the pane's centre to the pixel (or the offset
     * [camera] was asked for, under the turn card), which is where the chrome used to
     * draw the vehicle unconditionally. It stops being the centre the
     * moment a drag does, and a marker still pinned there would be the dashboard
     * claiming the vehicle is somewhere it is not.
     */
    fun marker(pane: Box, follow: Pair<Double, Double>?): Pair<Int, Int>? {
        val camera = last ?: return null
        val (latitude, longitude) = follow ?: return null
        val radians = Math.toRadians(camera.rotation.toDouble())
        val k = camera.scale * tileSize
        val wx = worldX(longitude) - worldX(camera.longitude)
        val wy = worldY(latitude) - worldY(camera.latitude)
        // The inverse of [worldOffset]; the rotation is orthonormal, so it transposes.
        val x = pane.centerX + ((wx * cos(radians) + wy * sin(radians)) * k).roundToInt()
        val y = pane.centerY + ((-wx * sin(radians) + wy * cos(radians)) * k).roundToInt()
        return if (pane.contains(x, y)) x to y else null
    }

    /** Puts the pane back on the position source. */
    fun recentre() {
        if (!following) recentres++
        following = true
        travelled = 0f
    }

    /**
     * The + and − bubbles (P7.2): [levels] whole zoom levels in, or out when negative,
     * about the pane's centre, clamped to the pinch's own range.
     *
     * Following is left as it is. While following, the centre is the vehicle, so the
     * marker stays under it as it does for a pinch; and a driver who zooms out to see the
     * next junction should not have to press recentre afterwards.
     */
    fun zoomBy(levels: Int) {
        scale = (scale * Math.pow(2.0, levels.toDouble())).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /** Switches between north-up and direction-of-travel-up, and squares up the twist. */
    fun flipOrientation() {
        orientation = if (orientation == MapOrientation.HEADING_UP) {
            MapOrientation.NORTH_UP
        } else {
            MapOrientation.HEADING_UP
        }
        // Clearing the twist is what makes this button a way *out* of a rotated map and
        // not just another rotation: a driver who has turned the map by hand and wants
        // north back has one press to get it, whichever mode they were in.
        turn = 0f
        flips++
    }

    /**
     * A head-unit touch, with every pointer the event carried.
     *
     * The gesture is claimed on a press inside the pane and then keeps every pointer
     * until the last one lifts, the way a view that has taken a gesture does: a finger
     * that strays over the media pane mid-pinch is still part of the pinch.
     */
    fun touch(action: String, pointers: List<TouchPoint>, pane: Box, nowMillis: Long) {
        when (action) {
            "down" -> {
                val first = pointers.firstOrNull() ?: return
                tracking = pane.contains(first.x, first.y)
                travelled = 0f
                pinched = false
                if (tracking) {
                    gestures++
                    downX = first.x
                    downY = first.y
                    downAt = nowMillis
                    baseline(pointers)
                }
            }
            // One finger, held still, for long enough: the place under it. Read on the
            // lift rather than on a timer because touch events are all this sees, and
            // the head unit sends nothing while a finger rests.
            "up" -> {
                if (tracking && !pinched && travelled <= PAN_SLOP && nowMillis - downAt >= LONG_PRESS_MS) {
                    groundAt(downX, downY, pane)?.let {
                        pick = it
                        longPresses++
                    }
                }
                tracking = false
                count = 0
            }
            // A pointer arriving or leaving changes what the focal point and the span
            // mean, so the baseline is retaken rather than differenced across the step.
            // Without this a second finger lands and the map jumps by the distance
            // between the two.
            "pointer-down", "pointer-up" -> if (tracking) baseline(pointers)
            "move" -> if (tracking) move(pointers, pane)
            else -> {
                tracking = false
                count = 0
            }
        }
    }

    /** The place the last long press picked, once; null when there is none waiting. */
    fun takePick(): Pair<Double, Double>? = pick.also { pick = null }

    /** Latitude and longitude under a frame pixel, on the camera last drawn. */
    private fun groundAt(x: Int, y: Int, pane: Box): Pair<Double, Double>? {
        val camera = last ?: return null
        val (ax, ay) = worldOffset((x - pane.centerX).toFloat(), (y - pane.centerY).toFloat(), camera.rotation, camera.scale)
        var wx = (worldX(camera.longitude) + ax) % 1.0
        if (wx < 0) wx += 1.0
        return toLatitude((worldY(camera.latitude) + ay).coerceIn(0.0, 1.0)) to toLongitude(wx)
    }

    /** `key=value` fragment for the session report (spec §10). Carries no position. */
    fun report(): String =
        "orientation=${orientation.label} following=$following " +
            // Root locale, not the phone's: a report is parsed, and a German handset
            // would otherwise write the zoom as "16,0" into a space-separated line.
            "zoom=${String.format(java.util.Locale.ROOT, "%.1f", zoom())} " +
            "turn=${Math.round(turn)} " +
            "gestures=$gestures multi_pointer=$multiPointer pointers_max=$pointersMax " +
            "pans=$pans recentres=$recentres orientation_flips=$flips long_presses=$longPresses " +
            "tile_size=$tileSize"

    /** The zoom level behind [scale], for the report; VTM's scale is 2^zoom. */
    private fun zoom(): Double = ln(scale) / ln(2.0)

    /** Records what the pointers of this event look like, as the next move's origin. */
    private fun baseline(pointers: List<TouchPoint>) {
        val points = pointers.sortedBy { it.id }
        count = points.size
        if (count == 0) {
            tracking = false
            return
        }
        if (count > pointersMax) pointersMax = count
        if (count >= 2 && !pinched) {
            pinched = true
            multiPointer++
        }
        focalX = points.sumOf { it.x }.toFloat() / count
        focalY = points.sumOf { it.y }.toFloat() / count
        span = 0f
        angle = 0f
        if (count >= 2) {
            // Sorted by pointer id, so the pair stays the same two fingers even if the
            // head unit reorders them between events; an unsorted pair that swapped
            // would read as an instant 180 degree twist.
            val dx = (points[1].x - points[0].x).toFloat()
            val dy = (points[1].y - points[0].y).toFloat()
            span = hypot(dx, dy)
            angle = degrees(dy, dx)
        }
    }

    /** Applies one move against the baseline, then becomes the next one's baseline. */
    private fun move(pointers: List<TouchPoint>, pane: Box) {
        val points = pointers.sortedBy { it.id }
        if (points.isEmpty()) return
        // A count this event did not announce (a dropped pointer-down, or a head unit
        // that does not send one) re-seeds instead of differencing two different hands.
        if (points.size != count) {
            baseline(points)
            return
        }
        val camera = last ?: return
        val nextFocalX = points.sumOf { it.x }.toFloat() / points.size
        val nextFocalY = points.sumOf { it.y }.toFloat() / points.size

        var nextScale = scale
        var nextRotation = camera.rotation
        if (points.size >= 2) {
            val dx = (points[1].x - points[0].x).toFloat()
            val dy = (points[1].y - points[0].y).toFloat()
            val nextSpan = hypot(dx, dy)
            if (span >= MIN_SPAN && nextSpan >= MIN_SPAN) {
                nextScale = (scale * (nextSpan / span)).coerceIn(MIN_SCALE, MAX_SCALE)
                val spun = wrap(degrees(dy, dx) - angle)
                if (abs(spun) >= MIN_TWIST) {
                    // Fingers turning clockwise turn the picture clockwise, which moves
                    // whatever was to the left of the top round to the top: the heading
                    // on show decreases by the angle the hand turned through.
                    turn = wrap(turn - spun)
                    nextRotation = wrap(camera.rotation - spun)
                }
            }
            span = nextSpan
            angle = degrees(dy, dx)
        }

        travelled += hypot(nextFocalX - focalX, nextFocalY - focalY)
        if (following && travelled > PAN_SLOP) {
            // Only a deliberate drag lets go of the vehicle. A pinch is anchored on the
            // pane centre while the map is following, so zooming out to see the next
            // junction does not cost a recentre press afterwards; past the slop the
            // driver is moving the map, and from here the gesture anchors on the fingers
            // instead. Both sides of the anchoring below use whichever rule is in force
            // after this decision, so the frame it changes on does not jump.
            following = false
            pans++
        }
        if (!following) {
            // Keep the ground under the fingers under the fingers: find where the focal
            // point was pointing on the old camera, then centre the new one so the same
            // place lands where the fingers have moved to.
            val (ax, ay) = worldOffset(focalX - pane.centerX, focalY - pane.centerY, camera.rotation, camera.scale)
            val groundX = worldX(camera.longitude) + ax
            val groundY = worldY(camera.latitude) + ay
            val (bx, by) = worldOffset(
                nextFocalX - pane.centerX, nextFocalY - pane.centerY, nextRotation, nextScale,
            )
            centreOn(groundX - bx, groundY - by)
        }
        scale = nextScale
        focalX = nextFocalX
        focalY = nextFocalY
    }

    private fun centreOn(x: Double, y: Double) {
        var wrapped = x % 1.0
        if (wrapped < 0) wrapped += 1.0
        freeLongitude = toLongitude(wrapped)
        freeLatitude = toLatitude(y.coerceIn(0.0, 1.0))
    }

    /**
     * World-coordinate offset of a pixel offset from the pane centre.
     *
     * Derived from VTM's own `ViewController.moveMap`, which divides a pixel move by
     * `scale * Tile.SIZE` and rotates it by the map's bearing. That bearing is the
     * negative of the heading on show — work `moveMap` through for a one-pixel move and
     * the screen's top comes out at heading `-bearing`, which is why [MapEngine] sets
     * the bearing to the negative of this rotation.
     */
    private fun worldOffset(dx: Float, dy: Float, rotation: Float, scale: Double): Pair<Double, Double> {
        val radians = Math.toRadians(rotation.toDouble())
        val k = scale * tileSize
        return ((dx * cos(radians) - dy * sin(radians)) / k) to
            ((dx * sin(radians) + dy * cos(radians)) / k)
    }

    companion object {
        /**
         * The follow view's zoom, about level 16: the surrounding streets rather than
         * the whole city, and where the pinch starts from every session.
         */
        const val DEFAULT_SCALE = 65536.0

        /**
         * How far the pinch may travel. VTM itself allows 2^2 to 2^20; this is the part
         * of that range a driver has any use for. The far-out end is level 4, several
         * thousand kilometres across the pane: all of Texas with room around it. The
         * default Mapsforge writer keeps major roads down to level 0, so this is sparse
         * rather than empty; it was level 12, then 8, until James asked to see further.
         */
        const val MIN_SCALE = 16.0
        const val MAX_SCALE = 524288.0

        /** `Tile.calculateTileSize()` at the map engine's 240dpi. */
        const val DEFAULT_TILE_SIZE = 384

        /** Below this the two fingers are too close together to read a span or an angle. */
        private const val MIN_SPAN = 8f

        /** Hand tremor rather than a twist; a vehicle is not a still place to point. */
        private const val MIN_TWIST = 0.25f

        /**
         * How far the focal point must travel before the map lets go of the vehicle.
         * Two fingers never land or lift perfectly together, so a pinch drags its focal
         * point a little whatever the driver intends.
         */
        private const val PAN_SLOP = 24f

        /**
         * How long a still finger must rest to pick a place: Android's own long-press
         * timeout is 400–500 ms, and this is longer on purpose, because a tap that
         * happens to linger over a bump in the road must not set a destination.
         */
        const val LONG_PRESS_MS = 800L

        /** Degrees in (-180, 180]. */
        private fun wrap(degrees: Float): Float {
            var value = degrees % 360f
            if (value > 180f) value -= 360f
            if (value <= -180f) value += 360f
            return value
        }

        /** Screen angle of a vector, clockwise, in a coordinate system with y downwards. */
        private fun degrees(dy: Float, dx: Float): Float =
            Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

        // Web Mercator, matching VTM's MercatorProjection field for field so that a
        // position this class computes and one VTM computes agree exactly.
        private const val LATITUDE_MAX = 85.05112877980659

        private fun worldX(longitude: Double): Double = (longitude + 180.0) / 360.0

        private fun worldY(latitude: Double): Double {
            val s = sin(latitude.coerceIn(-LATITUDE_MAX, LATITUDE_MAX) * (PI / 180.0))
            return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)).coerceIn(0.0, 1.0)
        }

        private fun toLongitude(x: Double): Double = 360.0 * (x - 0.5)

        private fun toLatitude(y: Double): Double =
            90.0 - 360.0 * atan(exp((y - 0.5) * (2 * PI))) / PI
    }
}
