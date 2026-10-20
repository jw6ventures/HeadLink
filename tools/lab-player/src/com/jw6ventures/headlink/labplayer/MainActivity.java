package com.jw6ventures.headlink.labplayer;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** The launcher activity a real player has, and the lab's call screen for the full-screen intent. */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("HeadLink lab player");
        setContentView(text);
    }
}
