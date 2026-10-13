package com.wonder.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.Locale;

public class MainActivity extends Activity {

    // >>>>>>>>>> CHANGE THIS to your Render address <<<<<<<<<<
    static final String SITE_URL = "https://musicx-cef6.onrender.com";

    // Desktop user agent: this is what lets YouTube keep playing in the background.
    static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // The desktop UA makes WebView lay the page out ~980px wide and shrink it, so it never
    // fills the screen and text is tiny. This makes the page use the real screen width.
    static final boolean FORCE_DEVICE_WIDTH = true;

    // Native title + seek bar shown at the bottom while a track is loaded.
    static final boolean SHOW_NATIVE_PLAYER_BAR = true;

    static WeakReference<WebView> web = new WeakReference<>(null);
    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private LinearLayout content;
    private FrameLayout fullscreenHost;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private WebChromeClient chrome;

    // native player bar
    private LinearLayout bar;
    private TextView barTitle, barArtist, barTime;
    private SeekBar barSeek;
    private boolean dragging = false;

    /** A WebView that never learns it went to the background, so media keeps playing. */
    static class BgWebView extends WebView {
        BgWebView(Context c) { super(c); }
        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE);
        }
    }

    class Bridge {
        @JavascriptInterface public void meta(String title, String artist, String art) {
            PlayerService.siteMeta = true;
            PlayerService.setMeta(title, artist, art);
            runOnUiThread(() -> updateBar());
        }
        /** Used by the injected helper when the site itself never reports a title. */
        @JavascriptInterface public void metaFallback(String title, String artist, String art) {
            if (PlayerService.siteMeta) return;
            PlayerService.setMeta(title, artist, art);
            runOnUiThread(() -> updateBar());
        }
        @JavascriptInterface public void playing(boolean p) { PlayerService.setPlaying(p); }
        @JavascriptInterface public void progress(long posMs, long durMs) {
            PlayerService.setProgress(posMs, durMs);
            runOnUiThread(() -> updateBar());
        }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // Draw behind the status bar / notch so the site really fills the screen.
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        webView = new BgWebView(this);
        webView.setBackgroundColor(0xFF14110F);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF14110F);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (SHOW_NATIVE_PLAYER_BAR) buildBar();
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreenHost = new FrameLayout(this);
        fullscreenHost.setBackgroundColor(Color.BLACK);
        fullscreenHost.setVisibility(View.GONE);
        root.addView(fullscreenHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Keep content out of the camera cut-out (left/right in landscape, top in portrait).
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                android.view.DisplayCutout c = insets.getDisplayCutout();
                content.setPadding(c.getSafeInsetLeft(), c.getSafeInsetTop(),
                        c.getSafeInsetRight(), 0);
            } else {
                content.setPadding(0, 0, 0, 0);
            }
            return insets;
        });
        enterImmersive();

        web = new WeakReference<>(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(DESKTOP_UA);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        chrome = new WebChromeClient() {
            // HTML5 fullscreen (video / the page's own fullscreen button)
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCallback = cb;
                fullscreenHost.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenHost.setVisibility(View.VISIBLE);
                content.setVisibility(View.GONE);
                enterImmersive();
            }
            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                fullscreenHost.removeView(customView);
                fullscreenHost.setVisibility(View.GONE);
                content.setVisibility(View.VISIBLE);
                customView = null;
                if (customCallback != null) customCallback.onCustomViewHidden();
                customCallback = null;
                enterImmersive();
            }
        };
        webView.setWebChromeClient(chrome);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                String host = r.getUrl().getHost();
                if (host != null && host.equals(Uri.parse(SITE_URL).getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) {}
                return true;
            }
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                super.onPageStarted(v, url, icon);
                if (FORCE_DEVICE_WIDTH) v.evaluateJavascript(VIEWPORT_JS, null);
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                if (FORCE_DEVICE_WIDTH) v.evaluateJavascript(VIEWPORT_JS, null);
                v.evaluateJavascript(HELPER_JS, null);
            }
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    v.loadData("<body style='background:rgb(20,17,15);color:white;font-family:sans-serif;padding:32px'>"
                            + "<h2>Connecting...</h2><p>The server may be waking up. Retrying.</p></body>", "text/html", "UTF-8");
                    handler.postDelayed(() -> { if (webView != null) webView.loadUrl(SITE_URL); }, 5000);
                }
            }
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        startPlayerService();
        askBatteryExemptionOnce();
        webView.loadUrl(SITE_URL);
    }

    // ---------------------------------------------------------------- fullscreen

    @SuppressWarnings("deprecation")
    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();   // bars come back after a swipe / dialog; hide again
    }

    // ---------------------------------------------------------------- native player bar

    private void buildBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xFF1E1A17);
        bar.setPadding(dp(16), dp(8), dp(16), dp(6));
        bar.setVisibility(View.GONE);

        barTitle = new TextView(this);
        barTitle.setTextColor(Color.WHITE);
        barTitle.setTextSize(15);
        barTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        barTitle.setSingleLine(true);
        barTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        barTitle.setMarqueeRepeatLimit(-1);
        barTitle.setSelected(true);

        barArtist = new TextView(this);
        barArtist.setTextColor(0xFFB8AFA7);
        barArtist.setTextSize(12);
        barArtist.setSingleLine(true);
        barArtist.setEllipsize(TextUtils.TruncateAt.END);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        barSeek = new SeekBar(this);
        barSeek.setPadding(dp(12), dp(8), dp(12), dp(8));
        barSeek.setProgressTintList(ColorStateList.valueOf(0xFFF0A04B));
        barSeek.setThumbTintList(ColorStateList.valueOf(0xFFF0A04B));
        barSeek.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
        barSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) barTime.setText(fmt(p) + " / " + fmt(sb.getMax()));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                PlayerService.seekTo(sb.getProgress());
            }
        });

        barTime = new TextView(this);
        barTime.setTextColor(0xFFB8AFA7);
        barTime.setTextSize(11);
        barTime.setSingleLine(true);

        row.addView(barSeek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(barTime, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar.addView(barTitle);
        bar.addView(barArtist);
        bar.addView(row);
        content.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                     : String.format(Locale.US, "%d:%02d", m, sec);
    }

    private void updateBar() {
        if (bar == null) return;
        long dur = PlayerService.durationMs, pos = PlayerService.positionMs;
        if (dur <= 0) { bar.setVisibility(View.GONE); return; }
        bar.setVisibility(View.VISIBLE);
        String t = PlayerService.title, a = PlayerService.artist;
        if (!t.contentEquals(barTitle.getText())) barTitle.setText(t);
        if (!a.contentEquals(barArtist.getText())) barArtist.setText(a);
        barArtist.setVisibility(a.isEmpty() ? View.GONE : View.VISIBLE);
        if (!dragging) {
            barSeek.setMax((int) Math.min(dur, Integer.MAX_VALUE));
            barSeek.setProgress((int) Math.min(pos, Integer.MAX_VALUE));
            barTime.setText(fmt(pos) + " / " + fmt(dur));
        }
    }

    // ---------------------------------------------------------------- injected JavaScript

    static final String VIEWPORT_JS = "(function(){try{"
            + "var v=document.querySelector('meta[name=viewport]');"
            + "if(!v){v=document.createElement('meta');v.name='viewport';(document.head||document.documentElement).appendChild(v);}"
            + "v.content='width=device-width, initial-scale=1, viewport-fit=cover';"
            + "}catch(e){}})();";

    /**
     * Reads title / position / duration out of the page and reports them to the app, and defines
     * __seek(seconds) so the app can move the playback position. Works with an <audio>/<video>
     * element, a global YouTube `player` object, or a YouTube iframe (enablejsapi=1).
     */
    static final String HELPER_JS = """
(function(){
  if (window.__wxHelper) return; window.__wxHelper = 1;
  var yt = {pos:0, dur:0, title:'', author:'', id:''};
  window.addEventListener('message', function(e){
    try {
      var d = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
      if (!d || !d.info) return;
      if (d.event === 'infoDelivery' || d.event === 'initialDelivery') {
        var i = d.info;
        if (i.currentTime != null) yt.pos = i.currentTime;
        if (i.duration) yt.dur = i.duration;
        if (i.videoData) { yt.title = i.videoData.title || yt.title; yt.author = i.videoData.author || yt.author; yt.id = i.videoData.video_id || yt.id; }
      }
    } catch (_) {}
  });
  function frames() {
    return Array.prototype.filter.call(document.querySelectorAll('iframe'), function(f){
      return /youtube(-nocookie)?\\.com\\/embed/.test(f.src);
    });
  }
  function media() {
    var m = document.querySelectorAll('audio,video');
    for (var i = 0; i < m.length; i++) if (isFinite(m[i].duration) && m[i].duration > 0) return m[i];
    return null;
  }
  function ytPlayer() {
    var p = window.player || window.ytPlayer || window.ytplayer;
    return (p && typeof p.getDuration === 'function' && typeof p.seekTo === 'function') ? p : null;
  }
  window.__seek = function(s) {
    var p = ytPlayer(); if (p) { p.seekTo(s, true); return; }
    var el = media();   if (el) { el.currentTime = s; return; }
    frames().forEach(function(f){
      try { f.contentWindow.postMessage(JSON.stringify({event:'command', func:'seekTo', args:[s, true]}), '*'); } catch (_) {}
    });
  };
  var last = '';
  function tick() {
    try {
      var pos = 0, dur = 0, p = ytPlayer(), el = media();
      frames().forEach(function(f){
        try { f.contentWindow.postMessage(JSON.stringify({event:'listening', id:1, channel:'widget'}), '*'); } catch (_) {}
      });
      if (p && p.getDuration() > 0)      { pos = p.getCurrentTime(); dur = p.getDuration(); }
      else if (el)                       { pos = el.currentTime;     dur = el.duration; }
      else if (yt.dur > 0)               { pos = yt.pos;             dur = yt.dur; }
      AndroidBridge.progress(Math.round(pos * 1000), Math.round(dur * 1000));

      var t = '', a = '', art = '', ms = navigator.mediaSession && navigator.mediaSession.metadata;
      if (ms && ms.title) {
        t = ms.title; a = ms.artist || '';
        if (ms.artwork && ms.artwork.length) art = ms.artwork[ms.artwork.length - 1].src;
      } else if (p && p.getVideoData && p.getVideoData().title) {
        var vd = p.getVideoData(); t = vd.title; a = vd.author || ''; if (vd.video_id) art = 'https://i.ytimg.com/vi/' + vd.video_id + '/hqdefault.jpg';
      } else if (yt.title) {
        t = yt.title; a = yt.author; if (yt.id) art = 'https://i.ytimg.com/vi/' + yt.id + '/hqdefault.jpg';
      }
      var key = t + '|' + a + '|' + art;
      if (t && key !== last) { last = key; AndroidBridge.metaFallback(t, a, art); }
    } catch (_) {}
  }
  setInterval(tick, 1000);
})();
""";

    // ---------------------------------------------------------------- misc

    private void startPlayerService() {
        try {
            Intent i = new Intent(this, PlayerService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception ignored) {}
    }

    private void askBatteryExemptionOnce() {
        SharedPreferences sp = getSharedPreferences("w", MODE_PRIVATE);
        if (sp.getBoolean("asked", false)) return;
        sp.edit().putBoolean("asked", true).apply();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }
    }

    @Override protected void onPause()  { super.onPause();  webView.resumeTimers(); }   // never call webView.onPause()
    @Override protected void onResume() { super.onResume(); webView.onResume(); webView.resumeTimers(); enterImmersive(); }

    @Override
    public void onBackPressed() {
        if (customView != null) { chrome.onHideCustomView(); return; }
        if (webView.canGoBack()) webView.goBack(); else moveTaskToBack(true);   // keep music playing
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (isFinishing()) stopService(new Intent(this, PlayerService.class));
        webView.destroy();
        super.onDestroy();
    }
}
