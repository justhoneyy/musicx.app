package com.wonder.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.webkit.WebView;

/** Foreground service: keeps the app alive with the screen off and shows lock-screen controls. */
public class PlayerService extends Service {
    static final String CH = "wonder_playback";
    static final int ID = 7;

    static volatile String title = "Wonder";
    static volatile String artist = "Ready to play";
    static volatile boolean playing = false;
    static volatile PlayerService inst;

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    static void setMeta(String t, String a) { title = t; artist = a; refreshNow(); }
    static void setPlaying(boolean p) { playing = p; refreshNow(); }
    static void refreshNow() { PlayerService s = inst; if (s != null) s.refresh(); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW));
        }
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
            stopForeground(true);
            stopSelf();
            android.os.Process.killProcess(android.os.Process.myPid());
            return START_NOT_STICKY;
        }
        String js = "PREV".equals(a) ? "window.prev && prev()"
                : "NEXT".equals(a) ? "window.next && next()"
                : "TOGGLE".equals(a) ? "window.toggle && toggle()" : null;
        if (js != null) {
            final WebView w = MainActivity.web.get();
            final String code = js;
            if (w != null) w.post(() -> w.evaluateJavascript(code, null));
        }
        return START_NOT_STICKY;
    }

    void refresh() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID, build());
        try {
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
                .setContentIntent(PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2));
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
        try { if (wake != null && wake.isHeld()) wake.release(); if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
