package org.openautoandroid.source

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * The notification listener whose *authorisation* — not whose callbacks — this app needs
 * (spec §5.2, P5.1).
 *
 * `MediaSessionManager.getActiveSessions` refuses any caller that holds neither
 * `MEDIA_CONTENT_CONTROL` (signature|privileged, so barred by APP02) nor an enabled
 * notification listener. Reading what another application is playing therefore has
 * exactly one route open to an ordinary app, and this class is it.
 *
 * **It deliberately does nothing.** Every `NotificationListenerService` callback is left
 * unimplemented, so no notification is ever read, stored, or reported. The service exists
 * to be named in [ComponentName] when asking `MediaSessionManager` for the active media
 * sessions; that is the whole of its purpose. Keep it that way: the grant the operator
 * gives covers every notification on the phone, and the only defensible reason to ask for
 * it is that Android offers no narrower one.
 *
 * Transport buttons do **not** need this. [MediaControl.dispatch] works whether or not
 * the listener is enabled, so declining the grant costs the now-playing text and precise
 * seeking, and nothing else.
 */
class MediaAccessService : NotificationListenerService() {
    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, MediaAccessService::class.java)

        /**
         * Whether the operator has enabled this listener. Read from the settings list
         * rather than assumed: the grant is made in system Settings, can be withdrawn
         * there at any time, and is not a runtime permission this app can request.
         */
        fun enabled(context: Context): Boolean {
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
