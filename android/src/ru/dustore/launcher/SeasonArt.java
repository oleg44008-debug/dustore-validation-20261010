package ru.dustore.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * Сезонное украшение нижней полосы: паутина в углах на Хеллоуин, гирлянда на Новый год.
 * Рисуется под вкладками и не движется — ни таймеров, ни расхода батареи.
 */
final class SeasonArt {
    private static final int[] BULBS = { 0xFFFFD36B, 0xFF9ED8FF, 0xFFFF7A7A, 0xFF8BE8B0 };

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    SeasonArt() {
        line.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
    }

    /** @param density пикселей в одном dp */
    void drawBar(Canvas canvas, int width, float density) {
        int season = Season.current();
        if (season == Season.NONE || width <= 0) return;
        if (season == Season.HALLOWEEN) {
            web(canvas, 0, 1, density);
            web(canvas, width, -1, density);
        } else lights(canvas, width, density);
    }

    /** Паутина в верхнем углу полосы; side = 1 для левого угла, -1 для правого. */
    private void web(Canvas canvas, float cornerX, int side, float density) {
        float reach = 40 * density;
        int spokes = 4;
        float[] endX = new float[spokes], endY = new float[spokes];
        line.setStrokeWidth(Math.max(1f, density * 0.75f));
        line.setColor(0x30FFFFFF);
        for (int i = 0; i < spokes; i++) {
            // От верхней кромки полосы до её боковой кромки.
            double angle = Math.toRadians(i * 90.0 / (spokes - 1));
            float length = reach * (i % 2 == 0 ? 1f : 0.88f);
            endX[i] = cornerX + side * (float) Math.cos(angle) * length;
            endY[i] = (float) Math.sin(angle) * length;
            canvas.drawLine(cornerX, 0, endX[i], endY[i], line);
        }
        // Кольца провисают к углу между соседними нитями.
        for (float ring = 0.4f; ring <= 1.001f; ring += 0.3f) {
            for (int i = 0; i < spokes - 1; i++) {
                float fromX = cornerX + (endX[i] - cornerX) * ring, fromY = endY[i] * ring;
                float toX = cornerX + (endX[i + 1] - cornerX) * ring, toY = endY[i + 1] * ring;
                float sagX = (fromX + toX) / 2f, sagY = (fromY + toY) / 2f;
                sagX += (cornerX - sagX) * 0.18f;
                sagY -= sagY * 0.18f;
                path.rewind();
                path.moveTo(fromX, fromY);
                path.quadTo(sagX, sagY, toX, toY);
                canvas.drawPath(path, line);
            }
        }
    }

    /** Гирлянда вдоль верхней кромки полосы: в каждом провисе лампочка, горит каждая вторая. */
    private void lights(Canvas canvas, int width, float density) {
        float step = 38 * density, top = 2 * density, dip = 6 * density, radius = 2.4f * density;
        int count = Math.max(1, (int) ((width - 8 * density) / step));
        float left = (width - count * step) / 2f;

        line.setStrokeWidth(Math.max(1f, density * 0.75f));
        line.setColor(0x668898B4);
        path.rewind();
        for (int i = 0; i < count; i++) {
            float x0 = left + i * step, x1 = x0 + step;
            path.moveTo(x0, top);
            path.quadTo((x0 + x1) / 2f, top + dip * 2f, x1, top);
        }
        canvas.drawPath(path, line);

        for (int i = 0; i < count; i++) {
            float x = left + i * step + step / 2f, y = top + dip + radius;
            int colour = BULBS[i % BULBS.length];
            boolean lit = (i & 1) == 0;
            if (lit) {
                fill.setColor((colour & 0x00FFFFFF) | 0x30000000);
                canvas.drawCircle(x, y, radius * 2.6f, fill);
            }
            fill.setColor(lit ? colour : (colour & 0x00FFFFFF) | 0x70000000);
            canvas.drawCircle(x, y, radius, fill);
        }
    }
}
