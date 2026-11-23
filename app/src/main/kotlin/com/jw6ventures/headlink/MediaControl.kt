package com.jw6ventures.headlink

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.KeyEvent

/**
 * The phone's media stack as the head unit sees it (P5.1): head-unit transport buttons go
 * out to whatever is playing, and what is playing is published for the `media_info`
 * channel.
 *
 * **This app plays nothing and owns no session.** It drives another application's
 * playback, which splits the work across two Android authorisations that are not
 * equivalent:
 *
 *  * **Sending** a transport button needs nothing at all. [AudioManager.dispatchMediaKeyEvent]
 *    declares no permission and routes to the current media button receiver, which is the
 *    same path a Bluetooth headset button takes. This is not event injection into another
 *    app (spec §4): no `INJECT_EVENTS`, no accessibility service, and the platform — not
 *    this app — decides which session receives it.
 *  * **Reading** what is playing needs [MediaAccessService] to be enabled, because
 *    `MediaSessionManager.getActiveSessions` accepts only a notification listener or the
 *    signature-level `MEDIA_CONTENT_CONTROL`. Without it there is no session object, and
 *    therefore also no precise seek.
 *
 * So the integration degrades rather than failing: buttons always work, and without the
 * grant the head unit is told a stopped, empty state instead of a plausible-looking guess
 * (APP09, spec §5.2). [report] records which of the two was in force, so evidence from a
 * drive says which case it is.
 */
class MediaControl(
    private val context: Context,
    private val handle: Long,
    private val note: (String) -> Unit,
    /**
     * Called on the media thread with exactly what was published, so the projected scene
     * can draw the same thing the head unit was told and the two cannot disagree.
     */
    private val onNowPlaying: (
        access: Boolean,
        state: String,
        source: String,
        title: String,
        artist: String,
        /**
         * Cover art, already scaled down and owned by this app, or null. Drawn into the
         * projected scene only: it is deliberately **not** put on the `media_info`
         * channel, where `album_art` is unbounded `bytes` over a 16 KiB-fragment
         * transport and Ford's limit is unknown (P5.1).
         */
        art: android.graphics.Bitmap?,
        /**
         * Where in the track playback is, in milliseconds, or -1 with no session. For the
         * media pane's progress bar; the wire carries its own whole-second copy.
         */
        positionMs: Long,
        /** The track's length in milliseconds, or -1 when the player does not give one. */
        durationMs: Long,
    ) -> Unit,
) {
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val sessions: MediaSessionManager? =
        context.getSystemService(MediaSessionManager::class.java)
    /** The track the held [art] belongs to, so it is rescaled only on a change. */
    private var artTrack: String? = null
    private var art: android.graphics.Bitmap? = null
    private var artUpdates = 0L

    private val thread = HandlerThread("aap-media")
    private var handler: Handler? = null

    @Volatile
    private var running = false

    @Volatile
    private var dispatched = 0L

    @Volatile
    private var seeks = 0L

    @Volatile
    private var refused = 0L

    @Volatile
    private var published = 0L

    @Volatile
    private var publishFailures = 0L

    @Volatile
    private var lastAction = "-"

    @Volatile
    private var lastSource = "-"

    @Volatile
    private var lastState = "-"

    /** Whether the listener grant was held at the latest poll. */
    @Volatile
    private var access = false

    /** Starts publishing what is playing, once per [POLL_MS]. */
    fun start() {
        thread.start()
        val handler = Handler(thread.looper)
        this.handler = handler
        running = true
        handler.post(::poll)
    }

    /**
     * Applies a head-unit transport button. Called on the main thread; the platform call
     * is a one-way binder and does not block on the playing app.
     *
     * Rewind and fast-forward prefer an exact [SEEK_SECONDS]-second seek, which needs the
     * session object and so the listener grant, and fall back to the corresponding media
     * key — whose step is the playing app's choice, not ours.
     */
    fun transport(action: String) {
        lastAction = action
        when (action) {
            ACTION_REWIND -> if (!seek(-SEEK_SECONDS)) dispatch(KeyEvent.KEYCODE_MEDIA_REWIND)
            ACTION_FAST_FORWARD ->
                if (!seek(SEEK_SECONDS)) dispatch(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            else -> {
                val keycode = KEYCODES[action]
                if (keycode == null) {
                    refused++
                    note("unknown transport action $action")
                } else {
                    dispatch(keycode)
                }
            }
        }
        // The button may have changed the state; publish without waiting for the tick.
        handler?.post(::publish)
    }

    /**
     * A media button, as a down and an up: the platform requires both, and a lone down
     * leaves the receiving app holding a key.
     */
    private fun dispatch(keycode: Int) {
        val audio = audio ?: return
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keycode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keycode, 0))
        dispatched++
    }

    /**
     * Seeks the active session by [deltaSeconds]. Returns false when there is no session
     * to seek (no grant, nothing playing) or it declares it cannot seek, so the caller
     * can fall back to a media key.
     */
    private fun seek(deltaSeconds: Int): Boolean {
        val controller = controller() ?: return false
        val state = controller.playbackState ?: return false
        if (state.actions and PlaybackState.ACTION_SEEK_TO == 0L) return false
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var target = positionMs(state) + deltaSeconds * 1_000L
        if (target < 0L) target = 0L
        if (duration > 0L && target > duration) target = duration
        controller.transportControls.seekTo(target)
        seeks++
        return true
    }

    /**
     * The highest-priority active session that is a player's ([MediaSessions.pick]), or
     * null when there is none or this app has no access to the list. The grant is checked
     * every time: the operator can withdraw it in Settings mid-drive, and a
     * `SecurityException` here must not end the session.
     */
    private fun controller(): MediaController? {
        val sessions = sessions ?: return null
        if (!MediaAccessService.enabled(context)) return null
        return try {
            val active = sessions.getActiveSessions(MediaAccessService.component(context))
            val index = MediaSessions.pick(
                active.map { it.packageName },
                active.map { it.playbackState != null },
                context.packageName,
            )
            active.getOrNull(index)
        } catch (e: SecurityException) {
            note("media session access refused: $e")
            null
        }
    }

    /** Media thread: publish, then schedule the next poll. */
    private fun poll() {
        if (!running) return
        publish()
        handler?.postDelayed(::poll, POLL_MS)
    }

    private fun publish() {
        if (!running) return
        val controller = controller()
        // The grant itself, not "a session was read": with nothing playing there is no
        // session, and reporting that as no access put the idle pane's "notification
        // access" hint up on a phone that had granted it (docs/phone-app.md).
        access = MediaAccessService.enabled(context)
        val state = controller?.playbackState
        val metadata = controller?.metadata
        // No session at all — no grant, or nothing playing — is reported as stopped.
        val wire = if (state == null) {
            NativeBridge.PLAYBACK_STOPPED
        } else {
            when (state.state) {
                PlaybackState.STATE_PLAYING -> NativeBridge.PLAYBACK_PLAYING
                PlaybackState.STATE_PAUSED -> NativeBridge.PLAYBACK_PAUSED
                PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE ->
                    NativeBridge.PLAYBACK_STOPPED
                PlaybackState.STATE_ERROR -> NativeBridge.PLAYBACK_ERROR
                // Buffering, connecting, skipping and the rest have no wire value of
                // their own; the head unit is told the state is not known rather than a
                // wrong one.
                else -> NativeBridge.PLAYBACK_UNKNOWN
            }
        }
        val positionNow = if (state == null) -1L else positionMs(state).coerceAtLeast(0L)
        val position = if (positionNow < 0) 0 else (positionNow / 1_000L).toInt()
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L } ?: -1L
        val source = controller?.let { label(it.packageName) } ?: ""
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val stateName = STATE_NAMES[wire] ?: "unknown"
        val result = NativeBridge.submitNowPlaying(
            handle,
            wire,
            position.coerceAtLeast(0),
            source,
            title,
            artist,
            metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
        )
        if (result < 0) {
            publishFailures++
        } else {
            published++
            lastState = stateName
            lastSource = source.ifEmpty { "-" }
            // Only what actually went to the session reaches the screen, so the projected
            // picture cannot claim a track the head unit was never told about.
            onNowPlaying(access, stateName, source, title, artist, artFor(metadata, title, artist), positionNow, durationMs)
        }
    }

    /**
     * A scaled, app-owned copy of the session's cover art, or null.
     *
     * Copied rather than referenced because the framework owns the metadata bitmap and
     * may recycle it, and a recycled bitmap in the middle of a projected frame throws
     * on the renderer thread. Scaled because album art arrives at whatever size the
     * player chose and the pane is a few hundred pixels.
     *
     * Recomputed only when the track changes: [publish] runs about once a second and
     * rescaling a bitmap at that rate for an unchanged track is pure waste.
     */
    private fun artFor(metadata: MediaMetadata?, title: String, artist: String): android.graphics.Bitmap? {
        val key = "$title|$artist"
        if (key == artTrack) return art
        artTrack = key
        val source = metadata?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
        art = when {
            source == null || source.isRecycled || source.width <= 0 || source.height <= 0 -> null
            else -> {
                val longest = maxOf(source.width, source.height)
                try {
                    if (longest <= ART_MAX_PX) {
                        source.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    } else {
                        val scale = ART_MAX_PX.toFloat() / longest
                        android.graphics.Bitmap.createScaledBitmap(
                            source,
                            maxOf(1, (source.width * scale).toInt()),
                            maxOf(1, (source.height * scale).toInt()),
                            true,
                        )
                    }
                } catch (e: RuntimeException) {
                    // Out of memory or a bitmap the framework pulled out from under us:
                    // the pane falls back to its placeholder rather than the scene dying.
                    note("album art unavailable: $e")
                    null
                }
            }
        }
        if (art != null) artUpdates++
        return art
    }

    /**
     * The position now, extrapolated from the update the session last reported. A
     * `PlaybackState` carries the position as of `lastPositionUpdateTime`, so reading
     * `position` alone would report a track as stuck.
     */
    private fun positionMs(state: PlaybackState): Long {
        if (state.state != PlaybackState.STATE_PLAYING) return state.position
        val since = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return state.position + (since * state.playbackSpeed).toLong()
    }

    /**
     * The user-visible name of the app that owns the session, which is what the head unit
     * displays. The captured reference message carries "YouTube Music", not a package
     * name, so the label is resolved and the package kept only as a fallback.
     */
    private fun label(packageName: String): String =
        try {
            val packages = context.packageManager
            packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }

    fun release() {
        running = false
        handler = null
        thread.quitSafely()
        thread.join(RELEASE_WAIT_MS)
    }

    /** `key=value` lines for the session report (spec §10). */
    fun report(): String = buildString {
        appendLine(
            "media_control_access=${if (access) "granted" else "none"} " +
                "media_control_dispatched=$dispatched media_control_seeks=$seeks " +
                "media_control_refused=$refused media_control_published=$published " +
                "media_control_publish_failures=$publishFailures " +
                "media_control_art_updates=$artUpdates",
        )
        // The track is not reported: it is the operator's listening history, which spec
        // §10 keeps out of ordinary evidence. The owning app and the state are enough to
        // tell whether the integration worked.
        appendLine(
            "media_control_last_action=$lastAction media_control_last_state=$lastState " +
                "media_control_source=$lastSource media_control_seek_seconds=$SEEK_SECONDS",
        )
    }

    companion object {
        /** How far rewind and fast-forward move when the session can be seeked exactly. */
        const val SEEK_SECONDS = 30

        /**
         * Longest edge the cover art is scaled to. The pane is a few hundred pixels at
         * 1080p; players routinely publish art many times that, and the copy is held
         * for the whole session.
         */
        private const val ART_MAX_PX = 512

        private const val POLL_MS = 1_000L
        private const val RELEASE_WAIT_MS = 1_000L
        private const val ACTION_REWIND = "rewind"
        private const val ACTION_FAST_FORWARD = "fast-forward"

        /**
         * Transport actions the native session names, and the media key each dispatches.
         * The names are [com.jw6ventures.headlink] mirrors of `TransportAction::name`
         * in core/aap-session/src/now_playing.rs; rewind and fast-forward are handled
         * above because they prefer an exact seek.
         */
        private val KEYCODES = mapOf(
            "play-pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
            "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
            "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
            "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
            "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            ACTION_REWIND to KeyEvent.KEYCODE_MEDIA_REWIND,
            ACTION_FAST_FORWARD to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        )

        private val STATE_NAMES = mapOf(
            NativeBridge.PLAYBACK_UNKNOWN to "unknown",
            NativeBridge.PLAYBACK_STOPPED to "stopped",
            NativeBridge.PLAYBACK_PLAYING to "playing",
            NativeBridge.PLAYBACK_PAUSED to "paused",
            NativeBridge.PLAYBACK_ERROR to "error",
        )
    }
}

/**
 * Which active media sessions are a player's (dashboard part 2b). Pure, so the choice is
 * host-tested (DashboardFlexTest).
 *
 * `getActiveSessions` lists more than players. During a call its first entry is Telecom's
 * `HeadsetMediaButton` session, which exists only to catch a headset's button and never
 * has a playback state; taking it made the media pane say "Phone Calls" with transport
 * buttons for the length of every call (lab frame 20260924T163118Z/037). A session with
 * no playback state has nothing to show or control, whoever owns it, so it is skipped
 * too; and this app's own sessions, of which it has none, never count.
 */
object MediaSessions {
    const val TELECOM = "com.android.server.telecom"

    /** Whether a session from [packageName] is one to show and control. */
    fun isPlayback(packageName: String, hasPlaybackState: Boolean, ownPackage: String): Boolean =
        hasPlaybackState && packageName != TELECOM && packageName != ownPackage

    /**
     * The first of the sessions, in the platform's priority order, that [isPlayback]; its
     * index, or -1. The lists are parallel: package and whether it has a playback state.
     */
    fun pick(packages: List<String>, hasPlaybackState: List<Boolean>, ownPackage: String): Int =
        packages.indices.firstOrNull { isPlayback(packages[it], hasPlaybackState.getOrElse(it) { false }, ownPackage) } ?: -1
}
