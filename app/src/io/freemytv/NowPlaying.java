package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * What's playing on the TV (Spotify or any app with a media session) for the screensaver: title, artist, cover,
 * progress, and next / previous / play-pause. Reading other apps' sessions needs notification access for
 * {@link NowPlayingListener} (`cmd notification allow_listener io.freemytv/io.freemytv.NowPlayingListener`); without
 * it there is simply nothing playing.
 */
final class NowPlaying {
    interface Listener { void onNowPlaying(); }

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessions;
    private MediaController controller;
    private String coverFor = "";

    volatile String title = "", artist = "";
    volatile Bitmap cover;
    volatile boolean playing;
    volatile long duration, position, positionAt;
    volatile float speed;
    /** A song paused this long leaves the screensaver (it comes back as soon as it plays again). */
    private static final long HIDE_PAUSED_MS = 3 * 60_000L;
    private volatile long pausedSince;

    private final MediaSessionManager.OnActiveSessionsChangedListener onSessions = list -> pick(list);

    private final MediaController.Callback onChange = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { read(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { read(); }
        @Override public void onSessionDestroyed() { pick(activeSessions()); }
    };

    NowPlaying(Context c, Listener l) {
        app = c.getApplicationContext();
        listener = l;
    }

    void start() {
        sessions = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            sessions.addOnActiveSessionsChangedListener(onSessions, component(), main);
            pick(activeSessions());
        } catch (SecurityException e) {
            AppLog.w("now playing: no notification access");
        }
    }

    void stop() {
        if (sessions != null) {
            try { sessions.removeOnActiveSessionsChangedListener(onSessions); } catch (RuntimeException ignored) {}
        }
        follow(null);
    }

    /** Whether there's something to show: a song with a title, playing, or paused for less than 3 minutes. */
    boolean showing() {
        return controller != null && !title.isEmpty()
                && (playing || SystemClock.elapsedRealtime() - pausedSince < HIDE_PAUSED_MS);
    }

    /** Where the song is now, in ms (moves on its own while it plays). */
    long positionNow() {
        if (!playing) return position;
        long p = position + (long) ((SystemClock.elapsedRealtime() - positionAt) * speed);
        return duration > 0 ? Math.min(duration, p) : p;
    }

    void next() { if (controller != null) controller.getTransportControls().skipToNext(); }
    void previous() { if (controller != null) controller.getTransportControls().skipToPrevious(); }
    void playPause() {
        if (controller == null) return;
        if (playing) controller.getTransportControls().pause(); else controller.getTransportControls().play();
    }

    private ComponentName component() { return new ComponentName(app, NowPlayingListener.class); }

    private List<MediaController> activeSessions() {
        try {
            return sessions.getActiveSessions(component());
        } catch (SecurityException e) {
            return java.util.Collections.emptyList();
        }
    }

    /** The session to show: the one playing; else one paused that has a song (the first is the most recent). */
    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController m : list) {
                if (m.getMetadata() == null) continue;
                PlaybackState s = m.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = m; break; }
                if (best == null) best = m;
            }
        }
        follow(best);
    }

    private void follow(MediaController m) {
        if (controller != null && (m == null || !controller.getSessionToken().equals(m.getSessionToken()))) {
            controller.unregisterCallback(onChange);
            controller = null;
        }
        if (m != null && controller == null) {
            controller = m;
            controller.registerCallback(onChange, main);
        }
        read();
    }

    private void read() {
        MediaController m = controller;
        MediaMetadata md = m == null ? null : m.getMetadata();
        PlaybackState s = m == null ? null : m.getPlaybackState();
        title = md == null ? "" : text(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        artist = md == null ? "" : text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        duration = md == null ? 0 : Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
        boolean was = playing;
        playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        if (!playing && (was || pausedSince == 0)) pausedSince = SystemClock.elapsedRealtime();
        position = s == null ? 0 : Math.max(0, s.getPosition());
        positionAt = s == null ? SystemClock.elapsedRealtime() : s.getLastPositionUpdateTime();
        speed = s == null || s.getPlaybackSpeed() == 0 ? 1 : s.getPlaybackSpeed();
        loadCover(md);
        listener.onNowPlaying();
    }

    private static String text(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return "";
    }

    /** The cover: the bitmap in the session if there is one, or else its address (fetched off the main thread). */
    private void loadCover(MediaMetadata md) {
        String key = md == null ? "" : title + "|" + artist;
        if (key.equals(coverFor)) return;
        coverFor = key;
        cover = null;
        if (md == null) return;
        for (String k : new String[] {MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap b = md.getBitmap(k);
            if (b != null) { cover = b; return; }
        }
        String uri = text(md, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        if (uri.isEmpty()) return;
        new Thread(() -> {
            Bitmap b = null;
            try (InputStream in = open(uri)) {
                b = in == null ? null : BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                AppLog.w("cover: " + e);
            }
            final Bitmap got = b;
            main.post(() -> {
                if (!key.equals(coverFor)) return;      // the song changed meanwhile
                cover = got;
                listener.onNowPlaying();
            });
        }, "cover").start();
    }

    private InputStream open(String uri) throws Exception {
        if (uri.startsWith("http")) {
            HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            return c.getInputStream();
        }
        return app.getContentResolver().openInputStream(Uri.parse(uri));
    }

    /** Apart from the class, so the plain-Java tests can load it without Android. */
    static final class Format {
        /** "3:07" (and "1:02:03" past an hour). */
        static String time(long ms) {
            long s = Math.max(0, ms) / 1000, h = s / 3600, m = s / 60 % 60, sec = s % 60;
            return h > 0 ? h + ":" + (m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec : m + ":" + (sec < 10 ? "0" : "") + sec;
        }
    }
}
