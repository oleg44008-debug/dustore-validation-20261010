package ru.dustore.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/** Short, interruptible native motion. Android's animation setting always takes precedence. */
final class Motion {
    static final String[] KEYS = { "full", "reduced", "off" };
    static final String[] NAMES = { "Полные", "Короткие", "Выключены" };
    private static volatile int level;
    private static volatile boolean haptics = true;

    private Motion() { }

    static void load(Context context) {
        String saved = context.getSharedPreferences("prime", Context.MODE_PRIVATE).getString("motion", "full");
        level = "off".equals(saved) ? 2 : "reduced".equals(saved) ? 1 : 0;
        haptics = context.getSharedPreferences("prime", Context.MODE_PRIVATE).getBoolean("haptics", true);
    }

    static int level() { return level; }
    static boolean haptics() { return haptics; }

    static boolean enabled(Context context) {
        if (level == 2) return false;
        if (Build.VERSION.SDK_INT >= 26) return ValueAnimator.areAnimatorsEnabled();
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null && power.isPowerSaveMode()) return false;
        } catch (RuntimeException ignored) { }
        try { return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0; }
        catch (RuntimeException ignored) { return true; }
    }

    static boolean full(Context context) { return level == 0 && enabled(context); }
    static long duration(Context context, long full) { return !enabled(context) ? 0 : level == 1 ? Math.min(130, full) : full; }

    static void setLevel(Context context, int value) {
        level = Math.max(0, Math.min(2, value));
        context.getSharedPreferences("prime", Context.MODE_PRIVATE).edit().putString("motion", KEYS[level]).apply();
    }

    static void setHaptics(Context context, boolean on) {
        haptics = on;
        context.getSharedPreferences("prime", Context.MODE_PRIVATE).edit().putBoolean("haptics", on).apply();
    }

    static void feedback(View view) {
        if (haptics && view.isEnabled()) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    static void reset(View view) {
        view.animate().cancel();
        view.animate().setStartDelay(0).withEndAction(null);
        view.setAlpha(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
    }

    static void touch(View view) {
        view.setOnTouchListener((target, event) -> {
            if (!target.isEnabled()) { reset(target); return false; }
            int action = event.getActionMasked();
            if (!enabled(target.getContext())) {
                reset(target);
                if (action == MotionEvent.ACTION_UP && inside(target, event)) feedback(target);
                return false;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                target.animate().cancel();
                float scale = full(target.getContext()) ? .965f : .987f;
                target.animate().scaleX(scale).scaleY(scale).setStartDelay(0).setDuration(85)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                target.animate().cancel();
                target.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(duration(target.getContext(), 290))
                        .setInterpolator(full(target.getContext()) ? new OvershootInterpolator(1.4f) : new DecelerateInterpolator()).start();
                if (action == MotionEvent.ACTION_UP && inside(target, event)) feedback(target);
            }
            return false;
        });
    }

    private static boolean inside(View view, MotionEvent event) {
        return event.getX() >= 0 && event.getY() >= 0 && event.getX() <= view.getWidth() && event.getY() <= view.getHeight();
    }

    static void reveal(View view, int order, int direction) {
        reset(view);
        if (!enabled(view.getContext()) || order > 7) return;
        view.setAlpha(.25f);
        if (full(view.getContext())) view.setTranslationY(Ui.dp(view.getContext(), 14) * direction);
        view.animate().alpha(1f).translationY(0f).setStartDelay(full(view.getContext()) ? Math.min(180, order * 28L) : 0)
                .setDuration(duration(view.getContext(), 340)).setInterpolator(new DecelerateInterpolator(1.7f)).start();
    }

    static void revealChildren(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) reveal(group.getChildAt(i), i, 1);
    }
}
