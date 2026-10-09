package ru.dustore.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.WindowManager;

/** Refresh-rate hint for our local WebView game window, restored when it leaves the foreground. */
final class GameRefreshRate {
    private final Activity activity;
    private final PowerManager power;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final UltraPolicy policy = new UltraPolicy();
    private final int originalMode;
    private final float originalRefresh;
    private float maximumHz;
    private boolean active;
    private boolean registered;
    private Object listener;
    private int thermal = -1;
    private float headroom = Float.NaN;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active) return;
            sample();
            main.postDelayed(this, 10_000);
        }
    };
    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { apply(); }
    };

    GameRefreshRate(Activity activity) {
        this.activity = activity;
        power = activity.getSystemService(PowerManager.class);
        WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
        originalMode = attrs.preferredDisplayModeId;
        originalRefresh = attrs.preferredRefreshRate;
        maximumHz = AndroidUltra.highestRate(activity);
    }

    void resume() {
        if (active || !Prime.maxFps()) return;
        active = true;
        if (power != null && Build.VERSION.SDK_INT >= 29) {
            try {
                PowerManager.OnThermalStatusChangedListener changed = status -> {
                    thermal = status;
                    apply();
                };
                power.addThermalStatusListener(activity.getMainExecutor(), changed);
                listener = changed;
            } catch (RuntimeException unsupported) { thermal = -1; }
        }
        try {
            IntentFilter filter = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(battery, filter, Context.RECEIVER_NOT_EXPORTED);
            else activity.registerReceiver(battery, filter);
            registered = true;
        } catch (RuntimeException unavailable) { registered = false; }
        main.post(poll);
    }

    void refreshDisplay() {
        maximumHz = AndroidUltra.highestRate(activity);
        if (active) apply();
    }

    private void sample() {
        if (!active) return;
        if (power != null) {
            if (Build.VERSION.SDK_INT >= 29) {
                try { thermal = power.getCurrentThermalStatus(); }
                catch (RuntimeException unavailable) { thermal = -1; }
            }
            headroom = ThermalSample.read(power);
        }
        apply();
    }

    private void apply() {
        if (!active) return;
        try {
            boolean saver = power != null && power.isPowerSaveMode();
            float target = policy.target(Prime.maxFps(), true, saver, thermal, headroom, maximumHz);
            WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
            // A fixed mode ID takes precedence over preferredRefreshRate. Allow Android
            // to choose a matching mode without changing global display settings.
            float rate = AndroidUltra.windowRate(activity, target);
            if (attrs.preferredDisplayModeId != 0 || attrs.preferredRefreshRate != rate) {
                attrs.preferredDisplayModeId = 0;
                attrs.preferredRefreshRate = rate;
                activity.getWindow().setAttributes(attrs);
            }
        } catch (RuntimeException unsupported) { }
    }

    void pause() {
        if (!active) return;
        active = false;
        main.removeCallbacks(poll);
        if (registered) {
            try { activity.unregisterReceiver(battery); } catch (RuntimeException ignored) { }
            registered = false;
        }
        if (listener != null && power != null && Build.VERSION.SDK_INT >= 29) {
            try { power.removeThermalStatusListener((PowerManager.OnThermalStatusChangedListener) listener); }
            catch (RuntimeException ignored) { }
            listener = null;
        }
        try {
            WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
            attrs.preferredDisplayModeId = originalMode;
            attrs.preferredRefreshRate = originalRefresh;
            activity.getWindow().setAttributes(attrs);
        } catch (RuntimeException ignored) { }
    }
}
