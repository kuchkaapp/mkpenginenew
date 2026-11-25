package com.sakuoto.en;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.view.Gravity;

public class MainActivity extends Activity {
    private GameView view;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();
        Loc loc = new Loc(this);
        PackStorage storage = new PackStorage(this);
        if (storage.getPackCount() == 0 && storage.readAsset("script/initial.src") == null) {
            TextView t = new TextView(this);
            t.setText(loc.t("nodata")); t.setGravity(Gravity.CENTER); t.setTextSize(18);
            setContentView(t);
            return;
        }
        view = new GameView(this, storage, loc);
        setContentView(view);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideSystemUi(); }
    @Override protected void onPause() { super.onPause(); if (view != null) view.onPause(); }
    @Override protected void onResume() { super.onResume(); if (view != null) view.onResume(); }
    @Override protected void onDestroy() { super.onDestroy(); if (view != null) view.destroy(); }
    @Override public void onBackPressed() { if (view == null || !view.onBack()) super.onBackPressed(); }
}
