package com.wonder.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
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
import java.lang.ref.WeakReference;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import org.json.JSONObject;

public class MainActivity extends Activity {

    // >>>>>>>>>> CHANGE THIS to your Render address <<<<<<<<<<
    static final String SITE_URL = "https://musicx-cef6.onrender.com";

    // Desktop user agent: this is what lets YouTube keep playing in the background.
    static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // The desktop UA makes WebView lay the page out ~980px wide and shrink it, so it never
    // fills the screen and text is tiny. This makes the page use the real screen width.
    static final boolean FORCE_DEVICE_WIDTH = true;

    static WeakReference<WebView> web = new WeakReference<>(null);
    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private LinearLayout content;
    private FrameLayout fullscreenHost;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private WebChromeClient chrome;

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
        }
        /** Used by the injected helper when the site itself never reports a title. */
        @JavascriptInterface public void metaFallback(String title, String artist, String art) {
            if (PlayerService.siteMeta) return;
            PlayerService.setMeta(title, artist, art);
        }
        @JavascriptInterface public void playing(boolean p) { PlayerService.setPlaying(p); }
        @JavascriptInterface public void progress(long posMs, long durMs) {
            PlayerService.setProgress(posMs, durMs);
        }

        /** Shows the phone's own Google account picker and hands the ID token back to the page. */
        @JavascriptInterface public void googleSignIn(String webClientId) {
            handler.post(() -> startGoogleSignIn(webClientId));
        }

        /** Opens one of the site's own links in the real browser (sign-in fallback). */
        @JavascriptInterface public void openExternal(String url) {
            handler.post(() -> {
                try {
                    Uri u = Uri.parse(url);
                    String host = u.getHost(), scheme = u.getScheme();
                    if (host != null && host.equals(Uri.parse(SITE_URL).getHost())
                            && ("https".equals(scheme) || "http".equals(scheme))) {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // Draw behind the status bar / notch so the site really fills the screen.
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
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
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreenHost = new FrameLayout(this);
        fullscreenHost.setBackgroundColor(Color.BLACK);
        fullscreenHost.setVisibility(View.GONE);
        root.addView(fullscreenHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Only keep content clear of a side cut-out (landscape); the top goes edge to edge.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                android.view.DisplayCutout c = insets.getDisplayCutout();
                content.setPadding(c.getSafeInsetLeft(), 0,
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
  var yt = {pos:0, dur:0, title:'', author:'', id:'', state:-2};
  window.addEventListener('message', function(e){
    try {
      var d = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
      if (!d || !d.info) return;
      if (d.event === 'infoDelivery' || d.event === 'initialDelivery') {
        var i = d.info;
        if (i.currentTime != null) yt.pos = i.currentTime;
        if (i.duration) yt.dur = i.duration;
        if (i.playerState != null) yt.state = i.playerState;
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

      // Real playing state, so the notification never says "paused" while sound is coming out.
      var pl = null, all = document.querySelectorAll('audio,video');
      for (var k = 0; k < all.length; k++) {
        var m = all[k];
        if (!m.paused && !m.ended && m.readyState > 2 && !m.muted) { pl = true; break; }
      }
      if (pl === null && all.length) pl = false;
      var msState = navigator.mediaSession && navigator.mediaSession.playbackState;
      if (pl === null && msState) pl = (msState === 'playing');
      if (pl === null && p && typeof p.getPlayerState === 'function') pl = (p.getPlayerState() === 1);
      if (pl === null && yt.state !== -2) pl = (yt.state === 1);
      if (pl !== null && pl !== window.__wxPl) { window.__wxPl = pl; AndroidBridge.playing(pl); }

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

    // ---------------------------------------------------------------- Google sign-in (native)

    private boolean signingIn = false;

    private void jsGoogleResult(String token) {
        if (webView == null) return;
        webView.evaluateJavascript("window.__nativeGoogle && window.__nativeGoogle("
                + JSONObject.quote(token) + ")", null);
    }

    private void jsGoogleError(String kind, String msg) {
        if (webView == null) return;
        webView.evaluateJavascript("window.__nativeGoogleErr && window.__nativeGoogleErr("
                + JSONObject.quote(kind) + "," + JSONObject.quote(msg == null ? "" : msg) + ")", null);
    }

    /**
     * Google refuses sign-in inside a WebView, so the account picker is shown by Android itself
     * (Credential Manager). It lists the Google accounts already signed in on the phone. The
     * resulting ID token goes to the website, which verifies it on the server exactly like
     * the normal browser sign-in.
     */
    private void startGoogleSignIn(String webClientId) {
        if (signingIn) return;
        if (webClientId == null || webClientId.isEmpty()) { jsGoogleError("config", "Missing web client ID"); return; }
        signingIn = true;
        try {
            CredentialManager cm = CredentialManager.create(this);
            GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(webClientId).build();
            GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
            cm.getCredentialAsync(this, request, null, command -> handler.post(command),
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override public void onResult(GetCredentialResponse result) {
                            signingIn = false;
                            try {
                                Credential c = result.getCredential();
                                if (c instanceof CustomCredential
                                        && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                                    GoogleIdTokenCredential g =
                                            GoogleIdTokenCredential.createFrom(((CustomCredential) c).getData());
                                    jsGoogleResult(g.getIdToken());
                                    return;
                                }
                                jsGoogleError("other", "Unexpected credential type");
                            } catch (Exception e) {
                                jsGoogleError("other", String.valueOf(e.getMessage()));
                            }
                        }

                        @Override public void onError(GetCredentialException e) {
                            signingIn = false;
                            String m = String.valueOf(e.getMessage());
                            if (e instanceof GetCredentialCancellationException) jsGoogleError("cancelled", "");
                            else if (e instanceof NoCredentialException) jsGoogleError("no_account", m);
                            else if (m.contains("28444") || m.toLowerCase().contains("not set up correctly")
                                    || m.contains("[16]")) jsGoogleError("config", m);
                            else jsGoogleError("other", m);
                        }
                    });
        } catch (Exception e) {
            signingIn = false;
            jsGoogleError("other", String.valueOf(e.getMessage()));
        }
    }

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
