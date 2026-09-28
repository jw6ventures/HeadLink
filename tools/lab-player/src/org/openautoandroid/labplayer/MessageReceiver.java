package org.openautoandroid.labplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Alerting notifications for the emulator lab's flex tile (P7.8): POST with extras
 * "tag", "title" and "text" posts one on a default-importance channel, the lowest that
 * alerts; CANCEL with "tag" takes it away again, which is how the lab removes one "on the
 * phone". `cmd notification post` would do the first, but on this image its notifications
 * never reached the shade.
 */
public final class MessageReceiver extends BroadcastReceiver {
    static final String POST = "org.openautoandroid.labplayer.POST";
    static final String CANCEL = "org.openautoandroid.labplayer.CANCEL";
    private static final String CHANNEL = "lab-messages";

    @Override
    public void onReceive(Context context, Intent intent) {
        String tag = intent.getStringExtra("tag");
        if (tag == null) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Lab messages", NotificationManager.IMPORTANCE_DEFAULT));
        if (POST.equals(intent.getAction())) {
            manager.notify(tag, 1, new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.sym_action_chat)
                    .setContentTitle(intent.getStringExtra("title"))
                    .setContentText(intent.getStringExtra("text"))
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .build());
            Log.i(PlayerService.TAG, "message posted");
        } else if (CANCEL.equals(intent.getAction())) {
            manager.cancel(tag, 1);
            Log.i(PlayerService.TAG, "message cancelled");
        }
    }
}
