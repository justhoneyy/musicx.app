package com.wonder.music;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Full-black splash screen with the Zuno logo animating in, shown while the
 * real UI (MainActivity) spins up behind it. Pure code, no layout XML, to match
 * the rest of the app.
 */
public class SplashActivity extends Activity {

    private static final int HOLD_MS = 950;   // how long the logo stays fully visible
    private static final int ANIM_MS = 650;   // fade / scale duration

    @SuppressLint("ObjectAnimatorBinding")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        hideSystemBars();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.zuno_logo);
        int size = dp(168);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
        root.addView(logo, lp);
        setContentView(root);

        // Start small, transparent and slightly sunken; animate up to full size/opacity,
        // then hold, then hand off to MainActivity.
        logo.setAlpha(0f);
        logo.setScaleX(0.72f);
        logo.setScaleY(0.72f);

        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.72f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.72f, 1f);
        AnimatorSet in = new AnimatorSet();
        in.playTogether(fadeIn, scaleX, scaleY);
        in.setDuration(ANIM_MS);
        in.setInterpolator(new DecelerateInterpolator());
        in.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                logo.postDelayed(SplashActivity.this::goToApp, HOLD_MS);
            }
        });
        in.start();
    }

    private void goToApp() {
        if (isFinishing()) return;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
