package ru.dustore.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/** A real-stage diagram. Flow means busy work, never an estimate or a synthetic percentage. */
final class ExTaskVisual extends View {
    private static final String[] NUMBERS = { "1", "2", "3", "4", "5", "6", "7", "8", "9" };
    private final boolean compact;
    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF(), file = new RectF(), phone = new RectF(), core = new RectF(), arc = new RectF();
    private final Rect visible = new Rect();
    private final Path fold = new Path(), success = new Path(), stopped = new Path(), failed = new Path();
    private final float[] nodes = new float[9];
    private final ValueAnimator flow = ValueAnimator.ofFloat(0f, 1f), settle = ValueAnimator.ofFloat(0f, 1f);
    private ExTask.Snapshot snapshot;
    private boolean hostActive, drawingActive;
    private float phase, stateMix = 1f, centerY, nodeY, radius, leftStart, leftEnd, rightStart, rightEnd;
    private int count, completed, current = -1, tone = Ui.ACCENT;
    private float transferRatio = -1f;
    private boolean scaleResponse;
    private long terminalGeneration = -1;
    private Runnable visibilityChanged;

    ExTaskVisual(Context context, boolean compact) {
        super(context);
        this.compact = compact;
        density = context.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(px(1.5f));
        line.setStrokeCap(Paint.Cap.ROUND); line.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        flow.setDuration(2400); flow.setRepeatCount(ValueAnimator.INFINITE);
        flow.setInterpolator(new LinearInterpolator());
        flow.addUpdateListener(value -> { phase = value.getAnimatedFraction(); invalidate(); });
        settle.setInterpolator(new DecelerateInterpolator(1.6f));
        settle.addUpdateListener(value -> { stateMix = value.getAnimatedFraction(); invalidate(); });
    }

    private float px(float dp) { return dp * density; }

    void visibilityListener(Runnable listener) { visibilityChanged = listener; }

    void bind(ExTask.Snapshot value, boolean animate) {
        ExTask.Snapshot previous = snapshot;
        boolean changed = previous != null && (previous.generation != value.generation || previous.step != value.step
                || !previous.state.equals(value.state) || !previous.stage.equals(value.stage));
        boolean terminal = previous != null && previous.busy() && !value.busy()
                && previous.generation == value.generation && terminalGeneration != value.generation;
        int previousCount = count;
        snapshot = value;
        count = Math.max(0, Math.min(nodes.length, value.total));
        completed = ExTask.DONE.equals(value.state) ? count : Math.max(0, Math.min(count, value.step - 1));
        current = value.step > 0 && value.step <= count ? value.step - 1 : -1;
        tone = ExTask.DONE.equals(value.state) ? Ui.SUCCESS
                : ExTask.FAILED.equals(value.state) || ExTask.INTERRUPTED.equals(value.state) ? Ui.DANGER
                : ExTask.CANCELLED.equals(value.state) || ExTask.CANCELLING.equals(value.state) ? Ui.WARNING : Ui.ACCENT;
        transferRatio = value.hasTransfer() && value.totalBytes > 0
                ? (float) Math.max(0d, Math.min(1d, value.completedBytes / (double) value.totalBytes)) : -1f;
        if (previous == null || changed || previous.total != value.total
                || previous.completedBytes != value.completedBytes || previous.totalBytes != value.totalBytes
                || !previous.transferLabel.equals(value.transferLabel)) {
            setContentDescription("Конвейер eX. " + value.stateText()
                    + (value.total > 0 ? ". Завершено этапов " + completed + " из " + value.total : "")
                    + (value.hasTransfer() ? ". " + value.transferLabel + ": " + ExTaskSheet.transferText(value) : ""));
        }
        // Log/byte updates do not change geometry. Native paths are rebuilt only
        // for a new stage count or size, not for every coalesced task notification.
        if (previous == null || previousCount != count) geometry(getWidth(), getHeight());
        if (changed || previous == null || !animate) { settle.cancel(); stateMix = 1f; }
        // Catch-up/restored terminal states are static and consume their generation once.
        if (!value.busy()) terminalGeneration = value.generation;
        if (animate && changed && (value.busy() || terminal) && drawingActive && Motion.enabled(getContext())) {
            scaleResponse = Motion.full(getContext());
            stateMix = 0f;
            settle.setDuration(Motion.duration(getContext(), terminal ? 460 : 250));
            settle.start();
        }
        refreshMotion(); invalidate();
    }

    void setHostActive(boolean active) { hostActive = active; refreshMotion(); }

    void refreshMotion() {
        boolean active = hostActive && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE
                && hasWindowFocus() && getLocalVisibleRect(visible) && !visible.isEmpty();
        boolean flowVisible = active && visible.bottom > centerY - radius - px(6)
                && visible.top < centerY + radius + px(6);
        drawingActive = active;
        if (!active || !Motion.enabled(getContext())) {
            flow.cancel(); settle.cancel(); phase = 0f; stateMix = 1f;
        } else if (flowVisible && snapshot != null && ExTask.RUNNING.equals(snapshot.state) && Motion.full(getContext())) {
            if (!flow.isStarted()) flow.start();
        } else {
            flow.cancel(); phase = 0f;
        }
        invalidate();
    }

    @Override protected void onMeasure(int width, int height) {
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), width),
                resolveSize(Math.round(px(compact ? 104 : 148)), height));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        background.setShader(new LinearGradient(0, 0, width, height,
                new int[] { Ui.CARD, Ui.blend(Ui.CARD_PRESSED, Ui.ACCENT_SUBTLE, .28f) }, null, Shader.TileMode.CLAMP));
        geometry(width, height);
    }

    private void geometry(int width, int height) {
        if (width <= 0 || height <= 0) return;
        bounds.set(px(.5f), px(.5f), width - px(.5f), height - px(.5f));
        radius = px(compact ? 22 : 29);
        centerY = px(compact ? 39 : 57); nodeY = height - px(25);
        float cx = width * .5f, fileX = width * .17f, phoneX = width * .83f;
        float fileWidth = px(compact ? 26 : 32), fileHeight = px(compact ? 32 : 42);
        file.set(fileX - fileWidth / 2, centerY - fileHeight / 2, fileX + fileWidth / 2, centerY + fileHeight / 2);
        float phoneWidth = px(compact ? 24 : 30), phoneHeight = px(compact ? 37 : 49);
        phone.set(phoneX - phoneWidth / 2, centerY - phoneHeight / 2, phoneX + phoneWidth / 2, centerY + phoneHeight / 2);
        core.set(cx - radius, centerY - radius, cx + radius, centerY + radius);
        arc.set(core.left - px(4), core.top - px(4), core.right + px(4), core.bottom + px(4));
        leftStart = file.right + px(9); leftEnd = core.left - px(9);
        rightStart = core.right + px(9); rightEnd = phone.left - px(9);
        fold.reset(); fold.moveTo(file.right - px(10), file.top); fold.lineTo(file.right - px(10), file.top + px(10));
        fold.lineTo(file.right, file.top + px(10));
        success.reset(); success.moveTo(cx - radius * .39f, centerY); success.lineTo(cx - radius * .08f, centerY + radius * .29f);
        success.lineTo(cx + radius * .43f, centerY - radius * .30f);
        stopped.reset(); stopped.moveTo(cx - radius * .31f, centerY); stopped.lineTo(cx + radius * .31f, centerY);
        failed.reset(); failed.moveTo(cx - radius * .28f, centerY - radius * .28f); failed.lineTo(cx + radius * .28f, centerY + radius * .28f);
        failed.moveTo(cx + radius * .28f, centerY - radius * .28f); failed.lineTo(cx - radius * .28f, centerY + radius * .28f);
        float margin = px(24), gap = count <= 1 ? 0 : (width - margin * 2) / (count - 1);
        for (int i = 0; i < count; i++) nodes[i] = count <= 1 ? cx : margin + i * gap;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRoundRect(bounds, px(18), px(18), background);
        line.setColor(Ui.LINE_SOFT); line.setStrokeWidth(px(1)); line.setAlpha(255);
        canvas.drawRoundRect(bounds, px(18), px(18), line);

        fill.setColor(Ui.CARD_HOVER); fill.setAlpha(255);
        canvas.drawRoundRect(file, px(6), px(6), fill);
        line.setColor(Ui.MUTED); line.setStrokeWidth(px(1.4f)); canvas.drawRoundRect(file, px(6), px(6), line);
        canvas.drawPath(fold, line);
        line.setColor(Ui.LINE_STRONG);
        canvas.drawLine(file.left + px(7), file.bottom - px(14), file.right - px(7), file.bottom - px(14), line);
        canvas.drawLine(file.left + px(7), file.bottom - px(8), file.right - px(11), file.bottom - px(8), line);
        if (transferRatio >= 0) {
            line.setColor(tone); line.setStrokeWidth(px(2));
            canvas.drawLine(file.left + px(7), file.bottom - px(5), file.left + px(7) + (file.width() - px(14)) * transferRatio, file.bottom - px(5), line);
        }

        line.setColor(Ui.LINE_STRONG); line.setStrokeWidth(px(1.3f));
        if (leftEnd > leftStart) canvas.drawLine(leftStart, centerY, leftEnd, centerY, line);
        if (rightEnd > rightStart) canvas.drawLine(rightStart, centerY, rightEnd, centerY, line);
        fill.setColor(Ui.CARD_PRESSED); canvas.drawRoundRect(phone, px(7), px(7), fill);
        line.setColor(tone); canvas.drawRoundRect(phone, px(7), px(7), line);
        line.setColor(Ui.LINE_STRONG);
        canvas.drawLine(phone.centerX() - px(4), phone.top + px(5), phone.centerX() + px(4), phone.top + px(5), line);
        canvas.drawLine(phone.centerX() - px(3), phone.bottom - px(5), phone.centerX() + px(3), phone.bottom - px(5), line);
        fill.setColor(Ui.alpha(tone, 45));
        canvas.drawCircle(phone.centerX(), centerY, px(compact ? 5 : 7), fill);

        fill.setColor(Ui.ACCENT_SUBTLE); canvas.drawOval(core, fill);
        line.setColor(Ui.alpha(tone, 90)); line.setStrokeWidth(px(1.3f)); canvas.drawOval(core, line);
        if (flow.isStarted()) {
            line.setColor(Ui.alpha(tone, 200)); line.setStrokeWidth(px(2));
            canvas.drawArc(arc, phase * 360 - 90, 78, false, line);
            fill.setColor(tone);
            if (leftEnd > leftStart) canvas.drawCircle(leftStart + (leftEnd - leftStart) * phase, centerY, px(2.6f), fill);
            if (rightEnd > rightStart) canvas.drawCircle(rightStart + (rightEnd - rightStart) * phase, centerY, px(2.6f), fill);
        }
        boolean terminal = snapshot != null && !snapshot.busy() && snapshot.present();
        if (terminal || snapshot != null && ExTask.CANCELLING.equals(snapshot.state)) {
            line.setColor(tone); line.setStrokeWidth(px(2.6f)); line.setAlpha(Math.round(90 + stateMix * 165));
            float scale = scaleResponse ? 0.90f + stateMix * .10f : 1f;
            int save = canvas.save(); canvas.scale(scale, scale, core.centerX(), centerY);
            canvas.drawPath(ExTask.DONE.equals(snapshot.state) ? success
                    : ExTask.FAILED.equals(snapshot.state) || ExTask.INTERRUPTED.equals(snapshot.state) ? failed : stopped, line);
            canvas.restoreToCount(save); line.setAlpha(255);
        } else {
            text.setTextSize(px(compact ? 20 : 26)); text.setColor(Ui.TEXT);
            canvas.drawText("eX", core.centerX(), centerY - (text.ascent() + text.descent()) / 2, text);
        }

        float nodeRadius = px(compact ? 7.5f : 9);
        line.setStrokeWidth(px(1.4f)); line.setAlpha(255);
        for (int i = 0; i < count; i++) {
            if (i + 1 < count) {
                line.setColor(i + 1 < completed ? Ui.alpha(Ui.SUCCESS, 130) : Ui.LINE);
                canvas.drawLine(nodes[i] + nodeRadius + px(4), nodeY, nodes[i + 1] - nodeRadius - px(4), nodeY, line);
            }
            boolean done = i < completed, active = i == current && !ExTask.DONE.equals(snapshot.state);
            fill.setColor(done ? Ui.SUCCESS : active ? tone : Ui.CARD_HOVER);
            canvas.drawCircle(nodes[i], nodeY, nodeRadius, fill);
            if (done) {
                line.setColor(Ui.ACTION_INK); line.setStrokeWidth(px(1.5f));
                canvas.drawLine(nodes[i] - px(3), nodeY, nodes[i] - px(.8f), nodeY + px(2.3f), line);
                canvas.drawLine(nodes[i] - px(.8f), nodeY + px(2.3f), nodes[i] + px(3.4f), nodeY - px(2.5f), line);
            } else {
                text.setTextSize(px(compact ? 9 : 11)); text.setColor(active ? Ui.ACCENT_INK : Ui.DIM);
                canvas.drawText(NUMBERS[i], nodes[i], nodeY - (text.ascent() + text.descent()) / 2, text);
            }
            if (active) {
                line.setColor(Ui.alpha(tone, Math.round(100 + stateMix * 80))); line.setStrokeWidth(px(1));
                canvas.drawCircle(nodes[i], nodeY, nodeRadius + px(3 + (scaleResponse ? (1 - stateMix) * 2 : 0)), line);
            }
        }
    }

    private void changedVisibility() {
        if (visibilityChanged != null) visibilityChanged.run(); else refreshMotion();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); changedVisibility(); }
    @Override protected void onDetachedFromWindow() {
        hostActive = false; drawingActive = false; flow.cancel(); settle.cancel(); stateMix = 1f; phase = 0f;
        super.onDetachedFromWindow();
    }
    @Override public void onVisibilityAggregated(boolean visible) { super.onVisibilityAggregated(visible); changedVisibility(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); changedVisibility(); }
}
