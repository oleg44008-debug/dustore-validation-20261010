package ru.dustore.launcher;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** UI ownership only. Suspending a screen never cancels or changes application-owned work. */
final class ExTaskBinding implements View.OnAttachStateChangeListener {
    interface Changed { void changed(ExTask.Snapshot snapshot, boolean transition); }
    interface Clock { void changed(ExTask.Snapshot snapshot, long elapsedMillis); }
    private static final WeakHashMap<Activity, Boolean> resumed = new WeakHashMap<>();
    private static final WeakHashMap<Application, Boolean> installed = new WeakHashMap<>();
    private static final WeakHashMap<ExTaskBinding, Boolean> bindings = new WeakHashMap<>();

    /** Called before the first Activity is created; a window having focus is not proof of resume. */
    static void install(Application application) {
        if (installed.put(application, true) != null) return;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity activity, Bundle state) { }
            public void onActivityStarted(Activity activity) { }
            public void onActivityResumed(Activity activity) { resumed.put(activity, true); update(activity, false); }
            public void onActivityPaused(Activity activity) { resumed.remove(activity); update(activity, false); }
            public void onActivityStopped(Activity activity) { resumed.remove(activity); update(activity, false); }
            public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            public void onActivityDestroyed(Activity activity) { resumed.remove(activity); update(activity, true); }
        });
    }
    private static void update(Activity activity, boolean destroy) {
        for (ExTaskBinding binding : new ArrayList<>(bindings.keySet())) {
            if (binding != null && binding.activity == activity) {
                if (destroy) { binding.close(); binding.root.hostDestroyed(); } else binding.refresh();
            }
        }
    }

    /** Root window/ancestor hooks are shared by the inline card and the sheet. */
    static class Root extends LinearLayout {
        private ExTaskBinding binding;
        Root(Context context) { super(context); }
        void binding(ExTaskBinding value) { binding = value; }
        void hostDestroyed() { }
        @Override protected void onVisibilityChanged(View changed, int visibility) {
            super.onVisibilityChanged(changed, visibility); if (binding != null) binding.refresh();
        }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility); if (binding != null) binding.refresh();
        }
        @Override public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused); if (binding != null) binding.refresh();
        }
        @Override public void onVisibilityAggregated(boolean visible) {
            super.onVisibilityAggregated(visible); if (binding != null) binding.refresh();
        }
        @Override protected void onConfigurationChanged(Configuration configuration) {
            super.onConfigurationChanged(configuration); if (binding != null) binding.configurationChanged();
        }
    }

    private final Activity activity;
    private final Root root;
    private final ExTaskVisual visual;
    private final ExTask task;
    private final Changed changed;
    private final Clock clock;
    private final Rect visible = new Rect();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private final ContentObserver scaleObserver = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChanged = (saved, key) -> {
        if ("motion".equals(key)) refresh();
    };
    private final BroadcastReceiver powerChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };
    private final ViewTreeObserver.OnGlobalLayoutListener layoutChanged = () -> { this.layoutPending = false; refresh(); };
    private final ViewTreeObserver.OnScrollChangedListener scrollChanged = this::refresh;
    private final ExTask.Listener listener = this::taskChanged;
    private ViewTreeObserver tree;
    // Keep API33-only types inside AnimatorScale33 so API24 can load this owner.
    private Object nativeScaleObserver;
    private long attachEpoch;
    private ExTask.Snapshot latest;
    private boolean attached, active, connected, catchingUp, clockPosted, closed, observersRegistered, powerRegistered, layoutPending;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clockPosted = false;
            if (!active || closed || clock == null || latest == null || !latest.busy()) return;
            clock.changed(latest, task.elapsedMillis());
            clockPosted = true; root.postDelayed(this, 1000);
        }
    };

    ExTaskBinding(Activity activity, Root root, ExTaskVisual visual, Changed changed, Clock clock) {
        this.activity = activity; this.root = root; this.visual = visual; this.changed = changed; this.clock = clock;
        install(activity.getApplication());
        task = ExTask.get(activity); latest = task.snapshot();
        preferences = activity.getSharedPreferences("prime", Context.MODE_PRIVATE);
        bindings.put(this, true); root.binding(this); root.addOnAttachStateChangeListener(this);
        visual.visibilityListener(this::refresh);
        if (root.isAttachedToWindow()) onViewAttachedToWindow(root);
    }

    private void taskChanged(ExTask.Snapshot value) {
        if (!active || closed) return;
        latest = value;
        visual.bind(value, !catchingUp);
        changed.changed(value, !catchingUp);
        if (value.busy() && clock != null) {
            if (!clockPosted) tick.run();
        } else {
            stopClock(); if (clock != null) clock.changed(value, value.elapsedMillis);
        }
    }

    void refresh() {
        if (closed) return;
        boolean now = attached && !layoutPending && Boolean.TRUE.equals(resumed.get(activity)) && !activity.isDestroyed()
                && root.isShown() && root.getWindowVisibility() == View.VISIBLE && root.hasWindowFocus()
                && root.getGlobalVisibleRect(visible) && !visible.isEmpty();
        boolean wasActive = active; active = now;
        if (!now) {
            if (connected) { connected = false; task.remove(listener); }
            stopClock(); visual.setHostActive(false);
            if (wasActive) resetMotion(root);
            return;
        }
        if (!connected) {
            // Restoring the latest state must not replay a completion which happened while hidden.
            visual.setHostActive(false);
            connected = true; catchingUp = true;
            try { task.observe(listener); }
            finally { catchingUp = false; }
        }
        visual.setHostActive(active);
        if (!Motion.enabled(activity)) resetMotion(root);
    }

    private static void resetMotion(View view) {
        Motion.reset(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) resetMotion(group.getChildAt(i));
        }
    }

    private void configurationChanged() {
        layoutPending = true;
        refresh(); root.requestLayout();
    }

    private void stopClock() { root.removeCallbacks(tick); clockPosted = false; }

    @Override public void onViewAttachedToWindow(View view) {
        if (closed || attached) return;
        attached = true; long epoch = ++attachEpoch; tree = root.getViewTreeObserver();
        tree.addOnGlobalLayoutListener(layoutChanged); tree.addOnScrollChangedListener(scrollChanged);
        preferences.registerOnSharedPreferenceChangeListener(preferenceChanged);
        try {
            activity.getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, scaleObserver);
            observersRegistered = true;
        } catch (RuntimeException ignored) { }
        try {
            IntentFilter filter = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            Context app = activity.getApplicationContext();
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(powerChanged, filter, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(powerChanged, filter);
            powerRegistered = true;
        } catch (RuntimeException ignored) { }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                nativeScaleObserver = AnimatorScale33.register(main, () -> {
                    if (!closed && attached && attachEpoch == epoch) refresh();
                });
            } catch (RuntimeException ignored) { }
        }
        refresh();
    }

    @Override public void onViewDetachedFromWindow(View view) {
        attached = false; ++attachEpoch; layoutPending = false;
        try { refresh(); } finally { unregisterObservers(); }
    }

    private void unregisterObservers() {
        unregisterNativeScale();
        if (tree != null && tree.isAlive()) {
            tree.removeOnGlobalLayoutListener(layoutChanged); tree.removeOnScrollChangedListener(scrollChanged);
        }
        tree = null;
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceChanged);
        if (observersRegistered) { activity.getContentResolver().unregisterContentObserver(scaleObserver); observersRegistered = false; }
        if (powerRegistered) {
            try { activity.getApplicationContext().unregisterReceiver(powerChanged); } catch (IllegalArgumentException ignored) { }
            powerRegistered = false;
        }
    }

    private void unregisterNativeScale() {
        Object observer = nativeScaleObserver; nativeScaleObserver = null;
        if (observer != null) AnimatorScale33.close(observer);
    }

    /** Native scale changes may arrive on a Binder thread; all View work stays on main. */
    private static final class AnimatorScale33 {
        private final Handler main;
        private final Runnable delivery;
        private final android.animation.ValueAnimator.DurationScaleChangeListener listener;
        private volatile boolean closed;
        private boolean registrationAttempted;

        private AnimatorScale33(Handler main, Runnable changed) {
            this.main = main;
            delivery = () -> { if (!closed) changed.run(); };
            listener = scale -> {
                if (!closed) main.post(delivery);
            };
        }

        static Object register(Handler main, Runnable changed) {
            AnimatorScale33 observer = new AnimatorScale33(main, changed);
            boolean registered = false;
            try {
                observer.registrationAttempted = true;
                registered = android.animation.ValueAnimator.registerDurationScaleChangeListener(observer.listener);
                return registered ? observer : null;
            } finally {
                if (!registered) observer.close();
            }
        }

        static void close(Object observer) { ((AnimatorScale33) observer).close(); }

        private void close() {
            if (closed) return;
            closed = true;
            try {
                if (registrationAttempted) android.animation.ValueAnimator.unregisterDurationScaleChangeListener(listener);
            } catch (RuntimeException ignored) {
                // Even a failed framework unregister must leave this owner inert.
            } finally {
                main.removeCallbacks(delivery);
            }
        }
    }

    void close() {
        if (closed) return;
        closed = true; ++attachEpoch; active = false; unregisterNativeScale();
        task.remove(listener); connected = false; stopClock(); visual.setHostActive(false);
        unregisterObservers(); bindings.remove(this); root.removeOnAttachStateChangeListener(this);
        root.binding(null); visual.visibilityListener(null);
    }
}
