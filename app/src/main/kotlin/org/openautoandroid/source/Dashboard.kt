package org.openautoandroid.source

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A rectangle in video frame coordinates. Plain JVM, so tools/jni-host-test can check it. */
data class Box(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2

    fun contains(px: Int, py: Int): Boolean = px >= x && px < right && py >= y && py < bottom
}

/**
 * Layout of the projected dashboard inside the video content rectangle (spec §8.2): a
 * status header across the top, an owned map filling one half below it, and the other
 * half divided into four tiles.
 *
 * The map side is configurable because which side falls to hand depends on the vehicle,
 * and getting it wrong is only discoverable from the driver's seat — the same reason
 * `scene_area` is a setting rather than a constant.
 */
class DashboardLayout(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val mapOnLeft: Boolean,
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
     * the ODbL attribution sits bottom-right — and because the bottom of the pane is
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

    /** The tile at a point in frame coordinates, or -1. */
    fun hitTile(px: Int, py: Int): Int = tiles.indexOfFirst { it.contains(px, py) }

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

    override fun toString(): String =
        "content=$x,$y+${width}x$height header=$header margin=$margin map_on_left=$mapOnLeft " +
            "map=${map.x},${map.y}+${map.width}x${map.height} bubble=$bubbleSize " +
            "media=${media.x}:${media.y}+${media.width}x${media.height} " +
            "art=${albumArt.width}x${albumArt.height} " +
            "tiles=" + tiles.joinToString(",") { "${it.x}:${it.y}+${it.width}x${it.height}" }

    companion object {
        /** The configurable tiles under the media pane; media is not one of them. */
        const val TILES = 2

        /** Left to right, matching the drawn order. */
        val MEDIA_ACTIONS = listOf("previous", "play-pause", "next")

        /** The floating map controls, bottom upwards, matching the drawn order. */
        val MAP_ACTIONS = listOf("recentre", "compass")

        /** Labels for the tiles that have no feature assigned yet. */
        val TILE_LABELS = listOf("trip", "vehicle")
    }
}

/** What the phone itself knows, none of which comes from the head unit. */
data class PhoneSnapshot(
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
        /** True when the drawn speed or bearing rests on an inferred scale. */
        val unvalidated: Boolean,
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
        /**
         * Degrees clockwise from the top of the pane that the vehicle marker points, or
         * null to draw it as a dot: stopped, crawling, or with no bearing to point along.
         * Whole degrees for the same repaint reason as [mapRotation].
         */
        val mapArrow: Int?,
        val tileTaps: List<Int>,
    )

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
    private var night = false
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

    /** The map engine's real tile size, which the pan arithmetic scales pixels by. */
    @Synchronized
    fun mapTileSize(size: Int) {
        mapControl.useTileSize(size)
    }

    @Synchronized
    fun restriction(drivingStatus: String, nightValue: String) {
        restriction = "driving=$drivingStatus night=$nightValue"
        night = nightValue == "true"
    }

    @Synchronized
    fun isNight(): Boolean = night

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
        val first = pointers.firstOrNull() ?: return null
        val px = first.x
        val py = first.y
        var dispatch: String? = null
        when (action) {
            "down", "pointer-down" -> {
                pressedTile = layout.hitTile(px, py)
                pressedMedia = layout.hitMedia(px, py)
                if (action == "down") {
                    pressedMapBubble = layout.hitMapBubble(px, py)
                    mapGesture = pressedMapBubble == null
                }
            }
            "up" -> {
                val tile = layout.hitTile(px, py)
                val media = layout.hitMedia(px, py)
                val bubble = layout.hitMapBubble(px, py)
                val wasTile = pressedTile
                val wasMedia = pressedMedia
                val wasBubble = pressedMapBubble
                pressedTile = -1
                pressedMedia = null
                pressedMapBubble = null
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
            }
            "cancel" -> {
                pressedTile = -1
                pressedMedia = null
                pressedMapBubble = null
            }
        }
        if (mapGesture) mapControl.touch(action, pointers, layout.map, nowMillis)
        if (action == "up" || action == "cancel") mapGesture = false
        return dispatch
    }

    /** The single-pointer form, for a caller that has nothing to pinch with. */
    @Synchronized
    @JvmOverloads
    fun touch(action: String, px: Int, py: Int, nowMillis: Long = System.currentTimeMillis()): String? =
        touch(action, listOf(TouchPoint(0, px, py)), nowMillis)

    /** The place a long press on the map picked as a destination, once, or null. */
    @Synchronized
    fun takeMapPick(): Pair<Double, Double>? = mapControl.takePick()

    /** The vehicle's own fix, or null: where a route starts while projecting (P6.4). */
    @Synchronized
    fun vehiclePosition(): Pair<Double, Double>? = vehicle.position()

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
        val vehiclePosition = vehicle.position()
        val phonePosition = phone.latitude?.let { lat -> phone.longitude?.let { lat to it } }
        val source = when {
            vehiclePosition != null -> PositionSource.VEHICLE
            phonePosition != null -> PositionSource.PHONE
            else -> PositionSource.NONE
        }
        val vehicleBearing = vehicle.bearing?.takeIf { it.validated }
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
        // Marked when a drawn number rests on a divisor no drive has confirmed.
        val unvalidated =
            (vehicleBearing != null && vehicleBearing.divisor !in ScaleRule.BEARING.confirmed) ||
                (vehicleSpeed != null && vehicleSpeed.divisor !in ScaleRule.SPEED.confirmed)
        return Snapshot(
            frame = frames,
            clock = CLOCK.format(Instant.ofEpochMilli(nowMillis).atZone(zone)),
            vehicle = vehicle,
            phone = phone,
            source = source,
            bearing = bearing,
            speed = speed,
            speedUnit = speedUnit,
            unvalidated = unvalidated,
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
            mapArrow = arrow,
            tileTaps = tileTaps.toList(),
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
    fun mapCamera(): MapCamera? = mapControl.camera(followPosition(), resolvedBearing())

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
            "dashboard_map_view=${mapControl.report()}\n"

    companion object {
        /** Above 5 mph the marker becomes an arrow along the direction of travel. */
        const val ARROW_ON_MPS = 5 * 0.44704

        /** Below 4 mph it goes back to a dot: a noisy bearing at a crawl points anywhere. */
        const val ARROW_OFF_MPS = 4 * 0.44704

        private fun wrapDegrees(degrees: Double): Double = ((degrees % 360) + 360) % 360

        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Reuses the demo scene's derivation so a token means the same thing in both. */
        fun token(sessionUuid: String): String = DemoScene.token(sessionUuid)
    }
}
