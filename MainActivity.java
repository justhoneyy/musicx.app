package com.wonder.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.lang.ref.WeakReference;

public class MainActivity extends Activity {

    // >>>>>>>>>> CHANGE THIS to your Render address <<<<<<<<<<
    static final String SITE_URL = "https://YOUR-SERVICE.onrender.com";

    // Desktop user agent: this is what lets YouTube keep playing in the background.
    static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    static WeakReference<WebView> web = new WeakReference<>(null);
    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** A WebView that never learns it went to the background, so media keeps playing. */
    static class BgWebView extends WebView {
        BgWebView(Context c) { super(c); }
        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE);
        }
    }

    class Bridge {
        @JavascriptInterface public void meta(String title, String artist) { PlayerService.setMeta(title, artist); }
        @JavascriptInterface public void playing(boolean p) { PlayerService.setPlaying(p); }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF14110F);
        getWindow().setNavigationBarColor(0xFF14110F);

        webView = new BgWebView(this);
        webView.setBackgroundColor(0xFF14110F);
        setContentView(webView);
        web = new WeakReference<>(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(DESKTOP_UA);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient());
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
    @Override protected void onResume() { super.onResume(); webView.onResume(); webView.resumeTimers(); }

    @Override
    public void onBackPressed() {
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
