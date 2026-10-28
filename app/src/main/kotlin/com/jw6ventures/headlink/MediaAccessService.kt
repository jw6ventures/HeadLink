package com.jw6ventures.headlink

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The notification listener. The operator enables it in system Settings; this app never
 * binds it (spec §5.2, P5.1).
 *
 * **Why it exists at all.** `MediaSessionManager.getActiveSessions` refuses any caller
 * that holds neither `MEDIA_CONTENT_CONTROL` (signature|privileged, so barred by APP02)
 * nor an enabled notification listener. Reading what another application is playing, and
 * listing the player apps with a live session (P7.5), therefore has exactly one route
 * open to an ordinary app, and this class is it. For that purpose it only needs to be
 * named in [ComponentName]; none of its callbacks are involved.
 *
 * **What it reads, and when (P7.8, P7.9).** The grant covers every
 * notification on the phone, but this service reads only what an operator's own switch
 * asks for, and nothing otherwise:
 *
 *  * **Dashboard notifications** (off by default). While [NotificationFeed.active] —
 *    turned on and not paused with Ⓟ — a posted notification is described to
 *    [NotificationFeed], which keeps alerting ones only: not ongoing, silent, minimum
 *    importance, group summaries, media, calls or this app's own, and not from an
 *    excluded app. They are held in memory for the session, newest first, and never
 *    written, logged or reported; the report counts them.
 *  * **A tapped notification's full text, for reading aloud** (P8.1, docs/audio-and-speech.md).
 *    When the driver taps a dashboard row, [Speaker] asks for that one notification by
 *    its key, at that moment only, and hands its words to the speech engine; they are not
 *    kept, logged or reported. Only rows the feed already shows can be tapped.
 *  * **Call notifications, only as a fallback** for the optional Call controls: while
 *    [CallControl.fallbackWanted] — Call controls on, and Telecom has not bound the
 *    companion in-call service — a call notification's caller and its Answer, Decline and
 *    Hang up intents go to [CallControl]. Nothing else is taken from it.
 *
 * With both off, every callback returns before looking at the notification, which is what
 * this service did before P7.8 by not implementing them. Keep it that way: the only
 * defensible reason to hold this grant is that Android offers no narrower one.
 *
 * Transport buttons do **not** need this. [MediaControl.dispatch] works whether or not the
 * listener is enabled, so declining the grant costs the now-playing text, precise seeking,
 * the live-session player list and the features above, and nothing else.
 *
 * Every callback arrives on the main thread.
 */
class MediaAccessService : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())

    /** Reads the shade again when the feed is turned on or un-paused. */
    private val source = NotificationFeed.Source { main.post(::readActive) }

    /** A tapped row's words, fetched by key when [Speaker] reads it aloud (P8.1). */
    private val words = NotificationTextSource(::wordsOf)

    override fun onListenerConnected() {
        NotificationFeed.ownPackage = packageName
        NotificationFeed.source = source
        Speaker.textSource = words
        if (NotificationFeed.active || CallControl.fallbackWanted) readActive()
    }

    override fun onListenerDisconnected() {
        if (NotificationFeed.source === source) NotificationFeed.source = null
        if (Speaker.textSource === words) Speaker.textSource = null
    }

    override fun onDestroy() {
        if (NotificationFeed.source === source) NotificationFeed.source = null
        if (Speaker.textSource === words) Speaker.textSource = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return
        val feed = NotificationFeed.active
        val calls = CallControl.fallbackWanted
        if (!feed && !calls) return
        val facts = describe(sbn, rankingMap)
        if (calls && NotificationRules.isCall(facts)) forwardCall(sbn, facts)
        if (feed) NotificationFeed.posted(facts)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return
        // Only the key, which identifies a row already held; nothing new is read.
        NotificationFeed.removed(sbn.key)
        CallControl.fallbackRemoved(sbn.key)
    }

    /** Main thread. Every active notification, for a feed that has just become active. */
    private fun readActive() {
        val feed = NotificationFeed.active
        val calls = CallControl.fallbackWanted
        if (!feed && !calls) return
        val active = try {
            activeNotifications.orEmpty()
        } catch (e: SecurityException) {
            // Not connected (the grant was withdrawn): nothing to read.
            return
        } catch (e: RuntimeException) {
            return
        }
        val ranking = currentRanking
        val all = active.map { it to describe(it, ranking) }
        if (calls) all.filter { NotificationRules.isCall(it.second) }.forEach { (sbn, facts) -> forwardCall(sbn, facts) }
        if (feed) NotificationFeed.replaceAll(all.map { it.second })
    }

    /**
     * One notification's words for reading aloud, or null when it is no longer
     * on the phone or the feed is off. Asked for by key alone, so nothing else in the
     * shade is read; the answer goes straight to [ReadAloudText] and is not kept.
     */
    private fun wordsOf(key: String): NotificationWords? {
        if (!NotificationFeed.active) return null
        val sbn = try {
            getActiveNotifications(arrayOf(key))?.firstOrNull { it.key == key }
        } catch (e: SecurityException) {
            null
        } catch (e: RuntimeException) {
            null
        } ?: return null
        val extras = sbn.notification.extras
        val last = lastMessage(extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java))
        return NotificationWords(
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            messageSender = last?.senderPerson?.name?.toString(),
            messageText = last?.text?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    /** The plain description [NotificationRules] filters. */
    private fun describe(sbn: StatusBarNotification, rankingMap: RankingMap?): NotificationFacts {
        val notification = sbn.notification
        val extras = notification.extras
        val ranking = Ranking()
        val importance = if (rankingMap != null && rankingMap.getRanking(sbn.key, ranking)) {
            ranking.importance
        } else {
            // Unknown importance is treated as not alerting: the rules keep a
            // notification only when it is known to alert.
            -1000
        }
        val last = lastMessage(extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java))
        return NotificationFacts(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = label(sbn.packageName),
            importance = importance,
            ongoing = notification.flags and
                (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
            groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            category = notification.category,
            template = extras.getString(Notification.EXTRA_TEMPLATE),
            hasMediaSession = extras.containsKey(Notification.EXTRA_MEDIA_SESSION),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            sender = last?.senderPerson?.name?.toString(),
            messageText = last?.text?.toString(),
            postedMillis = sbn.postTime,
        )
    }

    /** The newest `MessagingStyle` message, or null. A null sender is the user's own. */
    private fun lastMessage(messages: Array<Parcelable>?): Notification.MessagingStyle.Message? {
        if (messages.isNullOrEmpty()) return null
        return try {
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages).lastOrNull()
        } catch (e: RuntimeException) {
            null
        }
    }

    /** The fallback: only the caller and the three intents, and only for [CallControl]. */
    private fun forwardCall(sbn: StatusBarNotification, facts: NotificationFacts) {
        val extras = sbn.notification.extras
        val person = extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
        CallControl.fallbackPosted(
            key = sbn.key,
            callType = extras.getInt(EXTRA_CALL_TYPE, 0),
            caller = CallRules.caller(person?.name?.toString(), facts.title, null, CallRules.PRESENTATION_ALLOWED),
            connectedSinceMillis = if (sbn.notification.`when` > 0) sbn.notification.`when` else -1,
            answer = extras.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java),
            decline = extras.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java),
            hangUp = extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java),
        )
    }

    /**
     * The posting app's name. Resolving another package needs it to be visible to this
     * app (Android 11 package visibility); when it is not, the package name is used, as
     * [MediaControl] does. Whether posting apps are visible to a listener is unverified.
     */
    private fun label(packageName: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }

    companion object {
        /**
         * `Notification.EXTRA_CALL_TYPE`, which the SDK makes public only from API 34.
         * `CallStyle` has written the call type under this key since it arrived in API 31
         * (`Notification.java`, android-12.0.0_r1), so on this app's minimum of 33 the key
         * is spelled out rather than read from a constant lint rightly flags there.
         */
        private const val EXTRA_CALL_TYPE = "android.callType"

        fun component(context: Context): ComponentName =
            ComponentName(context, MediaAccessService::class.java)

        /**
         * Whether the operator has enabled this listener. Asked every time rather than
         * assumed: the grant is made in system Settings, can be withdrawn there at any
         * time, and is not a runtime permission this app can request.
         *
         * `NotificationManager.isNotificationListenerAccessGranted` first, which is the
         * notification service's own answer for this app's listener. The secure setting it
         * used to read alone is only a mirror of that answer, and not always kept: in the
         * part-2b lab, access granted with `cmd notification allow_listener` bound the
         * listener and fed the flex tile while this still said no. The setting stays as
         * the fallback for a platform that throws.
         */
        fun enabled(context: Context): Boolean {
            try {
                context.getSystemService(NotificationManager::class.java)?.let {
                    return it.isNotificationListenerAccessGranted(component(context))
                }
            } catch (e: RuntimeException) {
                // Fall through to the settings list.
            }
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val wanted = component(context)
            return enabled.split(':').any { entry ->
                ComponentName.unflattenFromString(entry) == wanted
            }
        }
    }
}
