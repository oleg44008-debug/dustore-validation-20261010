package ru.dustore.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * Картинка над описанием ULTRA — та же, что в лаунчере для Windows и Mac: три ступени, между
 * которыми умный ULTRA переводит игру eX, доля точек каждой и полоска кадров, которые она даёт.
 */
final class UltraSteps extends View {
    private static final String[] SIZES = { "Стандарт", "−1 ступень", "−2 ступени" };
    private static final String[] NAMES = { "Чётко", "Баланс", "Больше кадров" };
    private static final String[] SAVED = { "все точки", "на 36 % меньше точек", "на 59 % меньше точек" };

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float d;

    UltraSteps(Context context) {
        super(context);
        d = context.getResources().getDisplayMetrics().density;
        setContentDescription("Умный ULTRA: стандартный размер, на ступень меньше, на две ступени меньше. "
                + "Медленно — ступень ниже, кадров с запасом — ступень выше.");
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (int) (132 * d));
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = getWidth(), gap = 10 * d, card = (width - gap * 2) / 3f;
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(10 * d);
        text.setColor(Ui.ACCENT);
        canvas.drawText("УМНЫЙ ULTRA", 0, 12 * d, text);
        float top = 22 * d, bottom = getHeight() - 2 * d;
        for (int i = 0; i < 3; i++) {
            float left = i * (card + gap);
            box.set(left, top, left + card, bottom);
            fill.setShader(null);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(i == 1 ? Ui.ACCENT_SUBTLE : Ui.CARD);
            canvas.drawRoundRect(box, 12 * d, 12 * d, fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(1f, d));
            fill.setColor(i == 1 ? Ui.ACCENT : Ui.LINE_SOFT);
            canvas.drawRoundRect(box, 12 * d, 12 * d, fill);
            float x = left + 10 * d;
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextSize(13 * d);
            text.setColor(i == 1 ? Ui.ACTION : Ui.MUTED);
            canvas.drawText(SIZES[i], x, top + 22 * d, text);
            text.setTextSize(11 * d);
            text.setColor(Ui.MUTED);
            canvas.drawText(NAMES[i], x, top + 42 * d, text);
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(9.5f * d);
            text.setColor(Ui.DIM);
            canvas.drawText(SAVED[i], x, top + 60 * d, text);
            float trackTop = bottom - 16 * d, trackRight = left + card - 10 * d;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Ui.LINE_SOFT);
            box.set(x, trackTop, trackRight, trackTop + 5 * d);
            canvas.drawRoundRect(box, 3 * d, 3 * d, fill);
            float share = (float) (1.0 / Math.pow(0.64, i)) / (float) (1.0 / Math.pow(0.64, 2));
            box.set(x, trackTop, x + (trackRight - x) * share, trackTop + 5 * d);
            fill.setShader(new LinearGradient(x, 0, trackRight, 0, Ui.ACCENT, Ui.ACTION, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, 3 * d, 3 * d, fill);
            fill.setShader(null);
        }
    }
}
