package com.jw6ventures.headlink.labplayer;

import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.media.MediaBrowserService;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * A player that supports Android's media resumption the way SystemUI expects: a connection
 * carrying BrowserRoot.EXTRA_RECENT gets a root back, and the session it hands out plays
 * when told to. Nothing is heard; the session only says it is playing, which is all the
 * dashboard and MediaPlayers can see of any player.
 */
public final class PlayerService extends MediaBrowserService {
    static final String TAG = "OaaLabPlayer";
    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        session = new MediaSession(this, "lab-player");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPrepare() {
                Log.i(TAG, "prepare");
            }

            @Override
            public void onPlay() {
                Log.i(TAG, "play");
                session.setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "Labtrack Tune")
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "Lab Artist")
                        .build());
                state(PlaybackState.STATE_PLAYING);
                session.setActive(true);
            }

            @Override
            public void onPause() {
                Log.i(TAG, "pause");
                state(PlaybackState.STATE_PAUSED);
            }

            @Override
            public void onStop() {
                state(PlaybackState.STATE_STOPPED);
                session.setActive(false);
            }
        });
        state(PlaybackState.STATE_NONE);
        setSessionToken(session.getSessionToken());
    }

    private void state(int state) {
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_PREPARE | PlaybackState.ACTION_STOP)
                .setState(state, 0, 1f)
                .build());
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        boolean recent = rootHints != null && rootHints.getBoolean(BrowserRoot.EXTRA_RECENT);
        Log.i(TAG, "root recent=" + recent);
        if (recent) {
            Bundle extras = new Bundle();
            extras.putBoolean(BrowserRoot.EXTRA_RECENT, true);
            return new BrowserRoot("recent", extras);
        }
        return new BrowserRoot("root", null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        result.sendResult(new ArrayList<>());
    }

    @Override
    public void onDestroy() {
        session.release();
        super.onDestroy();
    }
}
