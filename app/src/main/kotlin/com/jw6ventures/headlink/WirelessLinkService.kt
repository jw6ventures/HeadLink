package com.jw6ventures.headlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder

/**
 * The foreground while a wireless link sets up (W2, W3): from the first Bluetooth frame
 * until [ProjectionService] takes over with the session, or the link ends. A link standing
 * by for the truck ([WirelessStandby]) holds none while it waits, and starts this only when
 * the truck's start request is accepted.
 *
 * Android accepts a local-only Wi-Fi request only from a foreground app or foreground
 * service (`WifiNetworkFactory`), and a link that W3 started when the truck connected has
 * no screen showing, so without this the join would be refused. It also keeps the process
 * up meanwhile. Its notification says what the link is doing and offers Cancel.
 */
class WirelessLinkService : Service() {
    private val progress: (String) -> Unit = { text -> update(text) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WirelessLink.addListener(progress)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote first: startForegroundService obliges it even when the link has already ended.
        startForeground(
            NOTIFICATION_ID,
            build(WirelessLink.lastStatus ?: getString(R.string.wireless_link_bootstrap)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        WirelessLink.foregroundHeld = true
        if (intent?.action == ACTION_CANCEL) WirelessLink.end("cancelled from the notification")
        if (!WirelessLink.settingUp) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        WirelessLink.foregroundHeld = false
        WirelessLink.foregroundRequested = false
        WirelessLink.removeListener(progress)
        super.onDestroy()
    }

    private fun update(text: String) {
        // Set up, or given up: the session's own notification, or none, from here on.
        if (!WirelessLink.settingUp) return stopSelf()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(text))
    }

    private fun build(text: String): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.wireless_link_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ConnectionActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, WirelessLinkService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.wireless_link_notification_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification), getString(R.string.home_wireless_cancel), cancel,
                ).build(),
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "wireless_link"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_CANCEL = "com.jw6ventures.headlink.action.CANCEL_WIRELESS"

        /**
         * Main thread, as the link starts. A start Android refuses is noted, not fatal: from
         * Home the screen is showing, which is foreground enough for the Wi-Fi request.
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, WirelessLinkService::class.java))
                WirelessLink.foregroundRequested = true
            } catch (e: RuntimeException) {
                SessionController.note("wireless link foreground service not started: $e")
            }
        }
    }
}
