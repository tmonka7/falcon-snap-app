package com.falcon.snap;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.annotation.Nullable;

public class SplashActivity extends BaseActivity {
    private static final long SPLASH_MILLIS = 1400;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openHome = () -> {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        TextView languages = findViewById(R.id.splash_lang);
        languages.setText(prefs.source().shortName + "   ⇄   " + prefs.target().shortName);
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.postDelayed(openHome, SPLASH_MILLIS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(openHome);
    }
}
