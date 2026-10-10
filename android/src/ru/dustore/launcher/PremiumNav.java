package ru.dustore.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;

/** One moving selection capsule, painted behind live, accessible navigation buttons. */
final class PremiumNav extends LinearLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final Matrix materialTransform = new Matrix();
    private final LinearGradient material = new LinearGradient(0, 0, 1, 1,
            Ui.alpha(Ui.ACCENT, 0x30), Ui.alpha(Ui.ACCENT, 0x12), Shader.TileMode.CLAMP);
    private final SeasonArt season = new SeasonArt();
    private float left;
    private float right;
    private int selected;
    private ValueAnimator movement;

    PremiumNav(Context context) {
        super(context);
        setWillNotDraw(false);
        setOrientation(HORIZONTAL);
    }

    void select(int index, boolean animate) {
        selected = Math.max(0, Math.min(getChildCount() - 1, index));
        if (getChildCount() == 0) return;
        View tab = getChildAt(selected);
        float targetLeft = tab.getLeft();
        float targetRight = tab.getRight();
        if (tab.getWidth() == 0) { post(() -> select(selected, false)); return; }
        if (movement != null) { movement.cancel(); movement = null; }
        if (!animate || !Motion.enabled(getContext()) || right <= left) {
            left = targetLeft; right = targetRight; invalidate(); return;
        }
        final float fromLeft = left;
        final float fromRight = right;
        movement = ValueAnimator.ofFloat(0f, 1f);
        movement.setDuration(Motion.duration(getContext(), 340));
        movement.setInterpolator(new DecelerateInterpolator(2.1f));
        movement.addUpdateListener(a -> {
            float at = (float) a.getAnimatedValue();
            left = fromLeft + (targetLeft - fromLeft) * at;
            right = fromRight + (targetRight - fromRight) * at;
            invalidate();
        });
        movement.start();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed || right <= left) select(selected, false);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        season.drawBar(canvas, getWidth(), getResources().getDisplayMetrics().density);
        if (getChildCount() == 0 || right <= left) return;
        View tab = getChildAt(selected);
        float inset = Ui.dp(getContext(), 2);
        pill.set(left + inset, tab.getTop() + inset, right - inset, tab.getBottom() - inset);
        float radius = Ui.dp(getContext(), 20);
        materialTransform.setScale(pill.width(), pill.height());
        materialTransform.postTranslate(pill.left, pill.top);
        material.setLocalMatrix(materialTransform);
        paint.setShader(material);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(pill, radius, radius, paint);
        paint.setShader(null);
        paint.setColor(Ui.alpha(Ui.ACCENT, 0x4D));
        paint.setStrokeWidth(Math.max(1f, Ui.dp(getContext(), 1)));
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRoundRect(pill, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override protected void onDetachedFromWindow() {
        if (movement != null) { movement.cancel(); movement = null; }
        super.onDetachedFromWindow();
    }
}
