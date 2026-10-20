package com.jw6ventures.headlink

/**
 * Layout of the projected test scene inside the video content rectangle (spec §8.2).
 * The same rules are implemented by the lab harness scene and checked by the lab
 * receiver, so a decoded frame can be verified at fixed sample points: a header of one
 * fifth of the height, then a 2x2 button grid inset by `width / 40`. Each button keeps
 * its lower part free of text, so [samplePoint] is a clean patch of the button color.
 */
class SceneLayout(val x: Int, val y: Int, val width: Int, val height: Int) {
    val header: Int = height / 5
    val margin: Int = maxOf(2, width / 40)
    val buttonWidth: Int = (width - 3 * margin) / 2
    val buttonHeight: Int = (height - (header + 3 * margin)) / 2

    fun buttonX(index: Int): Int = x + margin + (index % 2) * (buttonWidth + margin)

    fun buttonY(index: Int): Int = y + header + margin + (index / 2) * (buttonHeight + margin)

    fun centerX(index: Int): Int = buttonX(index) + buttonWidth / 2

    fun centerY(index: Int): Int = buttonY(index) + buttonHeight / 2

    fun samplePoint(index: Int): Pair<Int, Int> =
        buttonX(index) + buttonWidth / 4 to buttonY(index) + buttonHeight * 3 / 4

    /** The button containing a point in video frame coordinates, or -1. */
    fun hit(px: Int, py: Int): Int =
        (0 until BUTTONS).firstOrNull { index ->
            px >= buttonX(index) && px < buttonX(index) + buttonWidth &&
                py >= buttonY(index) && py < buttonY(index) + buttonHeight
        } ?: -1

    override fun toString(): String =
        "content=$x,$y+${width}x$height header=$header margin=$margin " +
            "button=${buttonWidth}x$buttonHeight centers_samples=" +
            (0 until BUTTONS).joinToString(",") { index ->
                val (sx, sy) = samplePoint(index)
                "${centerX(index)}:${centerY(index)}/$sx:$sy"
            }

    companion object {
        const val BUTTONS = 4
    }
}

/**
 * State of the projected test scene: a fresh session token, a frame counter, elapsed
 * time, four numbered buttons with their tap counts, the last head-unit input, and the
 * driving restriction the head unit reported. Pure JVM and thread safe: input arrives on
 * the main thread and the renderer thread draws [snapshot]s. Nothing here draws or talks
 * to Android, so tools/jni-host-test can check the rules.
 */
class DemoScene(layout: SceneLayout, val token: String) {
    /**
     * Where the scene is drawn. A head unit can move the content rectangle inside an
     * unchanged frame mid-stream (`UPDATE_UI_CONFIG_REQUEST`), so this is replaced in
     * place: tap counts and the session token survive, and the renderer thread picks the
     * new rectangle up on its next frame.
     */
    @Volatile
    var layout: SceneLayout = layout
        private set

    fun relayout(updated: SceneLayout) {
        layout = updated
    }

    /** Immutable values for one drawn frame. */
    data class Snapshot(
        val frame: Long,
        val elapsedMs: Long,
        val taps: List<Int>,
        val pressed: Int,
        val lastTouchX: Int,
        val lastTouchY: Int,
        val lastRawX: Int,
        val lastRawY: Int,
        val lastKey: Int,
        val restriction: String,
        /**
         * The last transport button and how many have arrived, or null while none has:
         * an idle indicator and a switched-off integration must not look the same.
         */
        val transport: String?,
        /**
         * What the head unit is being told is playing, or null when the media
         * integration is off for this attempt and no line is drawn at all.
         */
        val nowPlaying: String?,
    )

    private val taps = IntArray(SceneLayout.BUTTONS)
    /**
     * Raw coordinates of the last press aimed at each button's target cross, for the touch
     * calibration in the report. Each button draws a cross at a frame pixel this scene
     * knows exactly, so pressing the four of them gives four (target, raw) pairs — enough
     * to solve the scale and offset of the head unit's grid per axis instead of inferring
     * it from a photograph.
     *
     * **The last press wins, aimed or not.** Any press inside the button overwrites the
     * pair, so an ordinary tap later in the drive replaces a careful one: on report
     * `617af5ce` seven taps per button left four pairs that were merely interior points,
     * scattered over 82px where a deliberate pass scattered over 14px. These values mean
     * something only from a run that pressed the crosses and then stopped touching. If that
     * ever needs fixing, gate the recording on the gesture containing a move — the aimed
     * procedure is a drag and a casual tap is not. Do not keep "the press nearest the
     * target": selecting the smallest error biases the measurement toward zero, which is
     * the quantity being measured.
     */
    private val targetPress = Array(SceneLayout.BUTTONS) { -1 to -1 }
    /**
     * The same, at the release. Where [targetPress] measures the map and the operator's aim
     * together, this measures the map alone: the G5 procedure has the operator drag until
     * the live crosshair covers the target cross before lifting, so the release is recorded
     * with the mapped point known to be on the target. Parallax and the finger's contact
     * patch cancel — both features are on the same screen — and what remains is the raw
     * coordinate the head unit really sends for that frame pixel.
     */
    private val targetRelease = Array(SceneLayout.BUTTONS) { -1 to -1 }
    private var pressed = -1
    private var lastTouchX = -1
    private var lastTouchY = -1
    private var lastRawX = -1
    private var lastRawY = -1
    private var lastKey = -1
    private var restriction = "-"
    private var frames = 0L
    private var touches = 0L
    private var keys = 0L
    private var transports = 0L
    private var lastTransport = "-"
    /** Null until the media integration publishes, so an absent line means it is off. */
    private var nowPlaying: String? = null

    /**
     * A head-unit touch in video frame coordinates. A tap counts when a press and the
     * following release are inside the same button, as on any button.
     */
    @Synchronized
    fun touch(action: String, x: Int, y: Int, rawX: Int, rawY: Int) {
        touches++
        lastTouchX = x
        lastTouchY = y
        lastRawX = rawX
        lastRawY = rawY
        when (action) {
            // The press is where the operator aimed, and the release is where they settled
            // the crosshair: both are recorded, because they answer different questions.
            "down", "pointer-down" -> {
                pressed = layout.hit(x, y)
                if (pressed >= 0) targetPress[pressed] = rawX to rawY
            }
            "up" -> {
                val hit = layout.hit(x, y)
                if (hit >= 0 && hit == pressed) {
                    taps[hit]++
                    targetRelease[hit] = rawX to rawY
                }
                pressed = -1
            }
            "cancel" -> pressed = -1
        }
    }

    /** A head-unit button press taps the button after the last one, so it is visible. */
    @Synchronized
    fun key(keycode: Int, down: Boolean) {
        keys++
        lastKey = keycode
        if (down) taps[(keys % SceneLayout.BUTTONS).toInt()]++
    }

    @Synchronized
    fun restriction(drivingStatus: String, night: String) {
        restriction = "driving=$drivingStatus night=$night"
    }

    /**
     * A head-unit transport button. The scene only records that it arrived: the button
     * acts on whatever is playing, and this app owns no playback to change. Drawing it is
     * what makes a press visible on the factory display when the head unit's own
     * now-playing interface is not on screen (G6).
     */
    @Synchronized
    fun transport(action: String) {
        transports++
        lastTransport = action
    }

    /**
     * What the head unit is being told is playing, drawn on the projected scene as well as
     * sent on the `media_info` channel.
     *
     * Drawn *because* it is sent: a head unit that ignores the channel, or shows its own
     * now-playing screen only when the projection is not in front, would otherwise leave
     * no way to see whether this source is publishing anything (G6). The strings are the
     * same ones that go on the wire, so the screen and the channel cannot disagree.
     *
     * Every argument may be empty — without notification access that is the normal case —
     * and the line then reads [NO_MEDIA] rather than a blank or a guess.
     */
    @Synchronized
    fun nowPlaying(access: Boolean, state: String, source: String, title: String, artist: String) {
        val track = when {
            title.isNotEmpty() && artist.isNotEmpty() -> "$title — $artist"
            title.isNotEmpty() -> title
            // No track but a named app still says something useful: the head unit is
            // being told that app is playing, just not what.
            source.isNotEmpty() -> source
            else -> ""
        }
        nowPlaying = when {
            // The two ways of having nothing to show are not the same thing, and the
            // difference is only actionable from the driver's seat if it is on the
            // screen. Without this both read "no media" and an operator cannot tell a
            // missing grant from a silent phone without exporting a report.
            !access -> NO_ACCESS
            track.isEmpty() && state == "stopped" -> NO_MEDIA
            track.isEmpty() -> state
            else -> "$state · $track"
        }
    }

    /** The next frame's values; increments the frame counter. */
    @Synchronized
    fun nextFrame(elapsedMs: Long): Snapshot {
        frames++
        return Snapshot(
            frames,
            elapsedMs,
            taps.toList(),
            pressed,
            lastTouchX,
            lastTouchY,
            lastRawX,
            lastRawY,
            lastKey,
            restriction,
            if (transports == 0L) null else "$lastTransport x$transports",
            nowPlaying,
        )
    }

    /** `key=value` lines for the session report. */
    @Synchronized
    fun report(): String =
        "scene_token=$token\n" +
            "scene_layout=$layout\n" +
            // The now-playing line is drawn but deliberately not reported: it is the
            // operator's listening history, which spec §10 keeps out of ordinary
            // evidence. Whether anything was published at all is in the native report.
            "scene_transport=$transports scene_last_transport=$lastTransport\n" +
            "scene_frames=$frames scene_touches=$touches scene_keys=$keys " +
            "scene_taps=${taps.joinToString(",")} scene_last_touch=$lastTouchX,$lastTouchY " +
            "scene_last_touch_raw=$lastRawX,$lastRawY scene_last_key=$lastKey " +
            "scene_restriction=$restriction\n" +
            "scene_calibration=" +
            (0 until SceneLayout.BUTTONS).joinToString(" ") { index ->
                "${index + 1}:target=${layout.centerX(index)},${layout.centerY(index)}" +
                    ":press=${raw(targetPress[index])}:release=${raw(targetRelease[index])}"
            } + "\n"

    /** A recorded raw coordinate for the report, or `-` if that button was never pressed. */
    private fun raw(pair: Pair<Int, Int>): String =
        if (pair.first < 0) "-" else "${pair.first},${pair.second}"

    companion object {
        /**
         * Shown when nothing is playing, and when notification access is not granted —
         * which are the same thing as far as this source knows, and are what the head
         * unit is told in both cases.
         */
        const val NO_MEDIA = "no media"

        /**
         * Shown instead when this app has no notification access, which is the one thing
         * an operator can act on from the vehicle — and the one case that otherwise looks
         * identical to a phone that simply is not playing anything.
         */
        const val NO_ACCESS = "no media · grant notification access on the phone"

        /** Idle button fill; a tapped button takes its own color. */
        const val COLOR_BACKGROUND = 0xFF1C1C1C.toInt()
        const val COLOR_HEADER = 0xFF3F3F3F.toInt()
        const val COLOR_TEXT = 0xFFF9F9F9.toInt()
        const val COLOR_IDLE_BUTTON = 0xFF6D6D6D.toInt()

        /** Distinct tapped-button colors, checked by the lab receiver in decoded frames. */
        val BUTTON_COLORS = listOf(
            0xFFC82828.toInt(),
            0xFF28B43C.toInt(),
            0xFF325ADC.toInt(),
            0xFFDCBE28.toInt(),
        )

        /** Six decimal digits derived from the session UUID, fresh for every attempt. */
        fun token(sessionUuid: String): String {
            val digits = sessionUuid.filter { it.isDigit() || it in 'a'..'f' }.take(6)
            val value = digits.toLongOrNull(16) ?: 0L
            return (value % 1_000_000).toString().padStart(6, '0')
        }
    }
}
