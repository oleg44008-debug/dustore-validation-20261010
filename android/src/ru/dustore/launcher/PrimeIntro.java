package ru.dustore.launcher;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A brief, skippable native introduction. No animation survives removing its overlay. */
final class PrimeIntro {
    private PrimeIntro() { }

    static void play(Activity activity, FrameLayout host) {
        if (!Motion.full(activity)
                || !activity.getSharedPreferences("prime", Activity.MODE_PRIVATE).getBoolean("intro", true)) return;
        final LinearLayout layer = Ui.column(activity);
        layer.setGravity(Gravity.CENTER);
        layer.setBackgroundColor(Ui.BACK);
        layer.setClickable(true);
        layer.setFocusable(true);
        layer.setContentDescription("Dustore " + (Prime.ON ? "Prime" : "Free") + ". Коснитесь, чтобы пропустить заставку.");
        ImageView logo = new ImageView(activity);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setContentDescription("DUSTORE");
        layer.addView(logo, new LinearLayout.LayoutParams(Ui.dp(activity, 88), Ui.dp(activity, 88)));
        layer.addView(Ui.gap(activity, 22));
        TextView name = Ui.title(activity, "DUSTORE", 34, Ui.TEXT);
        name.setLetterSpacing(.1f);
        layer.addView(name);
        layer.addView(Ui.gap(activity, 12));
        TextView mark = Ui.chip(activity, Prime.ON ? "PRIME" : "FREE", Ui.ACCENT);
        layer.addView(mark);
        host.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        Motion.reveal(logo, 0, 1);
        Motion.reveal(name, 1, 1);
        Motion.reveal(mark, 2, 1);

        final boolean[] removed = { false };
        final Runnable finish = () -> {
            if (removed[0]) return;
            removed[0] = true;
            layer.animate().cancel();
            Motion.reset(logo); Motion.reset(name); Motion.reset(mark);
            host.removeView(layer);
        };
        layer.setOnClickListener(v -> finish.run());
        layer.setOnKeyListener((v, key, event) -> { finish.run(); return false; });
        layer.postDelayed(() -> {
            if (removed[0]) return;
            if (!Motion.enabled(activity)) { finish.run(); return; }
            layer.animate().alpha(0f).translationY(-Ui.dp(activity, 14)).setDuration(250)
                    .setInterpolator(new DecelerateInterpolator()).withEndAction(finish).start();
        }, 760);
    }
}
