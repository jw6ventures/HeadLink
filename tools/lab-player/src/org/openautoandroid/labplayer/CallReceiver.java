package org.openautoandroid.labplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * A stand-in dialer for the emulator lab's call-notification route (P7.9): RING posts a
 * CallStyle incoming call, as Google Phone does, whose Answer and Decline are this
 * receiver's own PendingIntents; ANSWER turns it into an ongoing call with Hang up;
 * DECLINE and HANG_UP take it away. Each is logged, so the lab can check which intent the
 * dashboard sent. The emulator's own dialer posts a plain notification with actions and
 * no CallStyle, which the call-notification route does not read.
 */
public final class CallReceiver extends BroadcastReceiver {
    static final String RING = "org.openautoandroid.labplayer.RING";
    static final String ANSWER = "org.openautoandroid.labplayer.ANSWER";
    static final String DECLINE = "org.openautoandroid.labplayer.DECLINE";
    static final String HANG_UP = "org.openautoandroid.labplayer.HANG_UP";
    private static final String CHANNEL = "lab-calls";
    private static final int ID = 7;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        Log.i(PlayerService.TAG, "call " + action.substring(action.lastIndexOf('.') + 1).toLowerCase());
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Lab calls", NotificationManager.IMPORTANCE_HIGH));
        Person caller = new Person.Builder().setName("Labcaller Person").build();
        switch (action) {
            case RING:
                manager.notify(ID, builder(context, System.currentTimeMillis())
                        .setStyle(Notification.CallStyle.forIncomingCall(caller, pending(context, DECLINE), pending(context, ANSWER)))
                        .build());
                break;
            case ANSWER:
                manager.notify(ID, builder(context, System.currentTimeMillis())
                        .setStyle(Notification.CallStyle.forOngoingCall(caller, pending(context, HANG_UP)))
                        .build());
                break;
            default:
                manager.cancel(ID);
                break;
        }
    }

    private static Notification.Builder builder(Context context, long when) {
        PendingIntent screen = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setWhen(when)
                .setFullScreenIntent(screen, true);
    }

    private static PendingIntent pending(Context context, String action) {
        Intent intent = new Intent(action).setClass(context, CallReceiver.class);
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE);
    }
}
