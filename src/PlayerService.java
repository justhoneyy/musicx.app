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

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private MediaSession session;

    static void setMeta(String t, String a, final String url) {
        title = t; artist = a;
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

    private void syncSession() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
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
