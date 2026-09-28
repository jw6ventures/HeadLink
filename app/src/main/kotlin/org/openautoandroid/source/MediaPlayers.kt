package org.openautoandroid.source

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.media.MediaBrowserService

/**
 * The player apps the idle media pane lists, each with a Resume button (P7.5, plan §5.5).
 *
 * Two sources, merged by [Players.merge]:
 *
 *  1. **Apps with a live media session**, from `MediaSessionManager.getActiveSessions`.
 *     That needs [MediaAccessService] enabled, exactly as [MediaControl]'s now-playing
 *     does; without it this source is simply empty. Resume is `transportControls.play()`.
 *  2. **Apps exporting a `MediaBrowserService`**, found through the manifest's `<queries>`
 *     entry for `android.media.browse.MediaBrowserService` (a package-visibility
 *     declaration, not a permission; plan §9 item 3). These can be resumed with no session
 *     left at all, after the app was killed, through Android's media resumption: connect
 *     with `BrowserRoot.EXTRA_RECENT` in the root hints, and an app that supports it
 *     answers with a session primed with its most recent item. Resume then does what
 *     SystemUI's `ResumeMediaBrowser.restart()` does: `prepare()` and `play()` on the
 *     session the connection returns.
 *
 * **Only real players are listed** (the part-2a lab review: the emulator offered
 * "Bluetooth" and "Android Open Source…", system components that export a browser service
 * and play nothing a driver would pick). A browser-service app must have a launcher
 * activity, which Bluetooth and the like do not; this app is never listed; and the list
 * is ordered by real use — every package seen with a *playing* session is remembered in
 * app-private preferences ([PlayedStore]), and those come before apps never seen to play,
 * of which at most [Players.UNPLAYED_MAX] are kept.
 *
 * `getLaunchIntentForPackage` needs no `<queries>` entry of its own for these packages:
 * the `MediaBrowserService` intent query already makes each one visible to this app, and
 * visibility is per package, so its launcher activity can be resolved too.
 *
 * **Whether a browser app really supports resumption is learned only at Resume.** SystemUI
 * probes each candidate with the same hint when it first sees the app play; doing that for
 * every browser app on the phone at list time would start every one of their services
 * during a drive. So the list offers every exporting app, and one that refuses the hint
 * comes back as [ResumeResult.REFUSED].
 *
 * **Threading.** Everything here runs on its own `aap-players` thread: `getActiveSessions`
 * and `queryIntentServices` are binder calls, and a `MediaBrowser` delivers its callbacks
 * on the thread that built it. [onPlayers] and every Resume callback arrive on that
 * thread, never on main or the render thread.
 *
 * **Privacy.** Which apps are listed is listening history. The report carries how many
 * were listed and what Resume did, never which app (spec §10). The remembered packages
 * stay in this app's private preferences, are never reported, and are only package names.
 */
class MediaPlayers(
    private val context: Context,
    /** On the `aap-players` thread: the merged list, most recently active first. */
    private val onPlayers: (List<Player>) -> Unit,
) {
    private val thread = HandlerThread("aap-players")
    private var handler: Handler? = null
    private val sessions: MediaSessionManager? = context.getSystemService(MediaSessionManager::class.java)

    // Worker thread only.
    private val lastActive = HashMap<String, Long>()
    private val played = PlayedStore(context)
    private val browsers = HashMap<String, ComponentName>()
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var pending: MediaBrowser? = null
    private var held: MediaBrowser? = null

    @Volatile private var running = false
    @Volatile private var listed = 0
    @Volatile private var listedSessions = 0
    @Volatile private var listedBrowsers = 0
    @Volatile private var noLauncher = 0
    @Volatile private var remembered = 0
    @Volatile private var presses = 0L
    @Volatile private var playedSession = 0L
    @Volatile private var playedRecent = 0L
    @Volatile private var refused = 0L
    @Volatile private var timeouts = 0L
    @Volatile private var notFound = 0L

    /** Starts the worker, lists once, and follows session changes when access allows. */
    fun start() {
        if (running) return
        thread.start()
        val handler = Handler(thread.looper)
        this.handler = handler
        running = true
        handler.post {
            follow(handler)
            list()
        }
    }

    /**
     * Lists again, e.g. when the pane goes idle or the operator has just granted access.
     * Posts; returns at once.
     */
    fun refresh() {
        handler?.post {
            if (sessionsListener == null) handler?.let(::follow)
            list()
        }
    }

    /**
     * Resumes [player]. Posts; returns at once. [onResult] arrives on the `aap-players`
     * thread within [RESUME_TIMEOUT_MS]. A second Resume while one is connecting cancels
     * the first, so a double tap cannot start two apps.
     */
    fun resume(player: Player, onResult: (ResumeResult) -> Unit) {
        presses++
        val handler = handler ?: return onResult(ResumeResult.NOT_FOUND)
        handler.post { resumeOn(handler, player, onResult) }
    }

    /**
     * Pushed updates for source 1, so a player that starts or stops mid-drive moves in the
     * list without polling. Needs the listener grant; without it the list is refreshed
     * only on request.
     */
    private fun follow(handler: Handler) {
        val manager = sessions ?: return
        if (!MediaAccessService.enabled(context)) return
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { list() }
        try {
            manager.addOnActiveSessionsChangedListener(listener, MediaAccessService.component(context), handler)
            sessionsListener = listener
        } catch (e: SecurityException) {
            // Withdrawn between the check and the call; source 1 stays empty.
        }
    }

    /** Worker thread. */
    private fun list() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        val live = activeControllers().map { controller ->
            val key = controller.packageName
            val state = controller.playbackState
            val seen = when {
                state?.state == PlaybackState.STATE_PLAYING -> now
                // The time the session last reported a position: about when it paused.
                state != null && state.lastPositionUpdateTime > 0 -> state.lastPositionUpdateTime
                else -> 0L
            }
            if (seen > (lastActive[key] ?: 0L)) lastActive[key] = seen
            // Real use, remembered for the next drive's cold list: playing, not merely a
            // session that exists.
            if (state?.state == PlaybackState.STATE_PLAYING) played.remember(key)
            Player(key, label(key), hasSession = true, hasBrowser = false, lastActiveMillis = lastActive[key] ?: 0L)
        }
        val cold = findBrowsers().map { key ->
            Player(key, label(key), hasSession = false, hasBrowser = true, lastActiveMillis = lastActive[key] ?: 0L)
        }
        val recent = played.recent()
        remembered = recent.size
        val merged = Players.merge(live, cold, recent)
        listed = merged.size
        listedSessions = live.size
        listedBrowsers = cold.size
        onPlayers(merged)
    }

    /** Worker thread. Empty without the listener grant, never an exception. */
    private fun activeControllers(): List<MediaController> {
        val manager = sessions ?: return emptyList()
        if (!MediaAccessService.enabled(context)) return emptyList()
        return try {
            manager.getActiveSessions(MediaAccessService.component(context))
                .filter { it.packageName != context.packageName && isPlayer(it) }
                .distinctBy { it.packageName }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /**
     * Whether a live session is a player's. Two system sessions are not, and the lab
     * listed the first as a player during a call: Telecom's `HeadsetMediaButton`, which
     * exists to catch the headset button while a call is up and never has a playback
     * state; and anything whose app, visible to this one, has no launcher activity. A
     * package this app cannot see is kept: a real player need not be visible to a
     * notification listener, and dropping it would be worse than listing it.
     */
    private fun isPlayer(controller: MediaController): Boolean {
        if (!MediaSessions.isPlayback(controller.packageName, controller.playbackState != null, context.packageName)) {
            return false
        }
        val packages = context.packageManager
        val visible = try {
            packages.getApplicationInfo(controller.packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        return !visible || packages.getLaunchIntentForPackage(controller.packageName) != null
    }

    /** Worker thread. Packages exporting a browser service, and the component to connect. */
    private fun findBrowsers(): List<String> {
        browsers.clear()
        var unlaunchable = 0
        val found = try {
            context.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
        } catch (e: RuntimeException) {
            emptyList()
        }
        for (entry in found) {
            val service = entry.serviceInfo ?: continue
            // Only an exported service can be connected to from here, and this app is
            // not a player of its own.
            if (!service.exported || service.packageName == context.packageName) continue
            if (browsers.containsKey(service.packageName)) continue
            // A player is an app someone opens. A system component that exports a browser
            // service (Bluetooth's A2DP sink, a media provider) has no launcher activity.
            if (context.packageManager.getLaunchIntentForPackage(service.packageName) == null) {
                unlaunchable++
                continue
            }
            browsers[service.packageName] = ComponentName(service.packageName, service.name)
        }
        noLauncher = unlaunchable
        return browsers.keys.toList()
    }

    /** Worker thread. */
    private fun resumeOn(handler: Handler, player: Player, onResult: (ResumeResult) -> Unit) {
        cancelPending()
        val controller = activeControllers().firstOrNull { it.packageName == player.key }
        if (controller != null) {
            controller.transportControls.play()
            lastActive[player.key] = SystemClock.elapsedRealtime()
            played.remember(player.key)
            playedSession++
            onResult(ResumeResult.PLAYED_SESSION)
            return
        }
        if (browsers.isEmpty()) findBrowsers()
        val component = browsers[player.key]
        if (component == null) {
            notFound++
            onResult(ResumeResult.NOT_FOUND)
            return
        }
        var done = false
        var browser: MediaBrowser? = null
        val timeout = Runnable {
            if (!done) {
                done = true
                timeouts++
                browser?.let { if (pending === it) pending = null; it.disconnect() }
                onResult(ResumeResult.TIMED_OUT)
            }
        }
        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val connected = browser ?: return
                if (done) return
                done = true
                handler.removeCallbacks(timeout)
                if (pending === connected) pending = null
                val played = try {
                    // SystemUI's restart(): a controller on the session the connection
                    // hands back, prepare, then play. The app has primed that session
                    // with its most recent item because the hint asked for it.
                    val session = MediaController(context, connected.sessionToken)
                    session.transportControls.prepare()
                    session.transportControls.play()
                    true
                } catch (e: RuntimeException) {
                    // IllegalStateException when the service connected without a session.
                    false
                }
                if (played) {
                    lastActive[player.key] = SystemClock.elapsedRealtime()
                    this@MediaPlayers.played.remember(player.key)
                    playedRecent++
                    // Stay connected for a while, as SystemUI does until its player
                    // updates: an app whose service is unbound the instant it starts
                    // may be torn down before it has started playing in its own right.
                    held?.disconnect()
                    held = connected
                    handler.postDelayed({ if (held === connected) { held = null; connected.disconnect() } }, HOLD_MS)
                    onResult(ResumeResult.PLAYED_RECENT)
                } else {
                    refused++
                    connected.disconnect()
                    onResult(ResumeResult.REFUSED)
                }
            }

            /** Also what a service that returns a null root (no resumption) produces. */
            override fun onConnectionFailed() {
                if (done) return
                done = true
                handler.removeCallbacks(timeout)
                browser?.let { if (pending === it) pending = null }
                refused++
                onResult(ResumeResult.REFUSED)
            }

            override fun onConnectionSuspended() {
                browser?.let { if (held === it) held = null; it.disconnect() }
            }
        }
        val hints = Bundle().apply { putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true) }
        val created = MediaBrowser(context, component, callback, hints)
        browser = created
        pending = created
        handler.postDelayed(timeout, RESUME_TIMEOUT_MS)
        try {
            created.connect()
        } catch (e: RuntimeException) {
            // A service that vanished between the query and the bind.
            handler.removeCallbacks(timeout)
            done = true
            pending = null
            notFound++
            onResult(ResumeResult.NOT_FOUND)
        }
    }

    private fun cancelPending() {
        pending?.disconnect()
        pending = null
    }

    private fun label(packageName: String): String =
        try {
            val packages = context.packageManager
            packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }

    /** Any thread. Disconnects whatever is still connected and stops the worker. */
    fun release() {
        if (!running) return
        running = false
        val handler = handler ?: return
        this.handler = null
        handler.post {
            sessionsListener?.let { sessions?.removeOnActiveSessionsChangedListener(it) }
            sessionsListener = null
            cancelPending()
            held?.disconnect()
            held = null
        }
        thread.quitSafely()
        thread.join(RELEASE_WAIT_MS)
    }

    /** `key=value` lines for the session report: counts only, never an app (spec §10). */
    fun report(): String =
        "media_players_listed=$listed media_players_sessions=$listedSessions " +
            "media_players_browsers=$listedBrowsers media_players_no_launcher=$noLauncher " +
            "media_players_remembered=$remembered media_resume_presses=$presses " +
            "media_resume_session=$playedSession media_resume_recent=$playedRecent " +
            "media_resume_refused=$refused media_resume_timeouts=$timeouts " +
            "media_resume_not_found=$notFound\n"

    companion object {
        /**
         * How long a cold resume may take to connect. Binding a killed app's service
         * starts its process, which takes a second or two on the Pixel; five leaves room
         * without leaving a press hanging for longer than a driver would wait.
         */
        const val RESUME_TIMEOUT_MS = 5_000L

        /** How long a resumed app's browser connection is kept after `play()`. */
        const val HOLD_MS = 10_000L

        private const val RELEASE_WAIT_MS = 1_000L
    }
}

/**
 * The packages that have really had a playing session on this phone, most recent first
 * (the part-2a review): what orders a cold player list, when this process has seen
 * nothing play yet.
 *
 * App-private `SharedPreferences`, one entry per package name with the wall-clock time it
 * was last seen playing, pruned to [MAX]. Package names only, never a title; never
 * reported (the report carries the count). Worker thread only: [remember] is an in-memory
 * write plus `apply()`, like the other settings, and only when a package moves to the top.
 */
class PlayedStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Remembered packages, most recently played first. */
    fun recent(): List<String> =
        prefs.all.entries
            .mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }
            .sortedByDescending { it.second }
            .map { it.first }

    /** [packageName] was seen playing now. */
    fun remember(packageName: String) {
        val order = recent()
        // Already the most recent: nothing to write, so a player that stays playing does
        // not rewrite the file on every list.
        if (order.firstOrNull() == packageName) return
        val editor = prefs.edit().putLong(packageName, System.currentTimeMillis())
        (order.filter { it != packageName }).drop(MAX - 1).forEach { editor.remove(it) }
        editor.apply()
    }

    companion object {
        private const val FILE = "played_players"

        /** More than a phone has players worth offering; the pane shows two. */
        const val MAX = 20
    }
}
