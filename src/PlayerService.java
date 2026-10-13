package com.wonder.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.webkit.WebView;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Foreground service + MediaSession.
 * The MediaSession is what makes headphone buttons work and what shows the player
 * on the lock screen and in the notification shade.
 */
public class PlayerService extends Service {
    static final String CH = "wonder_playback";
    static final int ID = 7;

    static volatile String title = "Wonder";
    static volatile String artist = "Ready to play";
    static volatile boolean playing = false;
    static volatile String artUrl = "";
    static volatile Bitmap art = null;
    static volatile PlayerService inst;
    static volatile long positionMs = 0;
    static volatile long durationMs = 0;
    static volatile boolean siteMeta = false;   // true once the website itself reports a title

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private MediaSession session;

    static void setMeta(String t, String a, final String url) {
        if (t != null && t.trim().length() > 0) title = t.trim();
        artist = a == null ? "" : a.trim();
        if (url != null && url.length() > 0 && !url.equals(artUrl)) {
            artUrl = url; art = null;
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(6000); c.setReadTimeout(8000);
                    Bitmap b = BitmapFactory.decodeStream(c.getInputStream());
                    if (b != null && url.equals(artUrl)) { art = b; refreshNow(); }
                } catch (Exception ignored) {}
            }).start();
        }
        refreshNow();
    }
    static void setPlaying(boolean p) { playing = p; refreshNow(); }

    /** Called about once a second from the page. Keeps the lock-screen seek bar in step. */
    static void setProgress(long pos, long dur) {
        boolean durChanged = dur != durationMs;
        positionMs = pos; durationMs = dur;
        PlayerService s = inst;
        if (s == null) return;
        if (durChanged) s.refresh(); else s.syncPlaybackState();
    }

    /** Seek requested from the lock screen / notification / native bar. */
    static void seekTo(long ms) {
        positionMs = ms;
        run("__seek(" + (ms / 1000.0) + ")");
        PlayerService s = inst;
        if (s != null) s.syncPlaybackState();
    }
    static void refreshNow() { PlayerService s = inst; if (s != null) s.refresh(); }

    /** Run a page function (resume, halt, next, prev, toggle) inside the website. */
    static void run(final String code) {
        final WebView w = MainActivity.web.get();
        if (w != null) w.post(() -> w.evaluateJavascript("try{" + code + "}catch(e){}", null));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW));
        }

        session = new MediaSession(this, "Wonder");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { run("resume()"); }
            @Override public void onPause() { run("halt()"); }
            @Override public void onStop() { run("halt()"); }
            @Override public void onSkipToNext() { run("next()"); }
            @Override public void onSkipToPrevious() { run("prev()"); }
            @Override public void onSeekTo(long pos) { seekTo(pos); }
        });
        session.setActive(true);
        syncSession();

        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(ID, n);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wonder:play");
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wonder:wifi");
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null ? null : i.getAction();
        if ("QUIT".equals(a)) {
            try { session.release(); } catch (Exception ignored) {}
            stopForeground(true);
            stopSelf();
            android.os.Process.killProcess(android.os.Process.myPid());
            return START_NOT_STICKY;
        }
        if ("PREV".equals(a)) run("prev()");
        else if ("NEXT".equals(a)) run("next()");
        else if ("TOGGLE".equals(a)) run(playing ? "halt()" : "resume()");
        return START_NOT_STICKY;
    }

    void syncPlaybackState() {
        try {
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP
                    | PlaybackState.ACTION_SEEK_TO;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            durationMs > 0 ? positionMs : PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                            playing ? 1f : 0f)
                    .build());
        } catch (Exception ignored) {}
    }

    private void syncSession() {
        syncPlaybackState();
        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs > 0 ? durationMs : -1);
        if (art != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(m.build());
    }

    void refresh() {
        try {
            syncSession();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID, build());
            if (playing) {
                if (!wake.isHeld()) wake.acquire();
                if (!wifi.isHeld()) wifi.acquire();
            } else {
                if (wake.isHeld()) wake.release();
                if (wifi.isHeld()) wifi.release();
            }
        } catch (Exception ignored) {}
    }

    private PendingIntent act(String action, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @SuppressWarnings("deprecation")
    private Notification build() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(artist)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (art != null) b.setLargeIcon(art);
        b.addAction(android.R.drawable.ic_media_previous, "Previous", act("PREV", 1));
        b.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "Pause" : "Play", act("TOGGLE", 2));
        b.addAction(android.R.drawable.ic_media_next, "Next", act("NEXT", 3));
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Quit", act("QUIT", 4));
        return b.build();
    }

    @Override
    public void onDestroy() {
        inst = null;
        try { if (session != null) session.release(); } catch (Exception ignored) {}
        try { if (wake != null && wake.isHeld()) wake.release(); if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
