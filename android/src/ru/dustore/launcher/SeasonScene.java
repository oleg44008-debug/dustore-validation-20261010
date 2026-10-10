package ru.dustore.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Фон экрана в сезон: обычный цвет и сезонная сцена в правом нижнем углу — холм с домом,
 * фонарями и привидениями на Хеллоуин, заснеженная поляна с ёлкой на Новый год.
 *
 * Сцена та же, что рисует лаунчер для Windows, сохранённая картинкой. Она стоит на месте,
 * пока список прокручивается, и не расходует батарею: ни таймеров, ни анимации.
 */
final class SeasonScene extends Drawable {
    private static Bitmap picture;
    private static int pictureFor = Season.NONE;

    private final Context context;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF place = new RectF();

    private SeasonScene(Context context) {
        this.context = context.getApplicationContext();
        // Названия игр лежат прямо на фоне, поэтому сцена немного приглушена.
        paint.setAlpha(190);
    }

    /** Ставит экрану его фон: с сезонной сценой в сезон, обычный — в остальное время. */
    static void apply(View screen) {
        if (Season.current() == Season.NONE) screen.setBackgroundColor(Ui.BACK);
        else screen.setBackground(new SeasonScene(screen.getContext()));
    }

    private static Bitmap picture(Context context) {
        int season = Season.current();
        if (pictureFor == season) return picture;
        pictureFor = season;
        picture = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            // На экране невысокой плотности половины точек картинки хватает с запасом.
            options.inSampleSize = context.getResources().getDisplayMetrics().density < 2.5f ? 2 : 1;
            picture = BitmapFactory.decodeResource(context.getResources(),
                    season == Season.HALLOWEEN ? R.drawable.season_halloween : R.drawable.season_newyear, options);
        } catch (OutOfMemoryError | RuntimeException noPicture) {
            picture = null;   // без картинки экран просто остаётся обычным
        }
        return picture;
    }

    /**
     * Где стоит сцена на экране данного размера: в правом нижнем углу, не выше 46 % экрана.
     * Возвращает {левый край, верхний край, ширину, высоту}.
     */
    static float[] place(int width, int height, float density, int pictureWidth, int pictureHeight) {
        float tall = Math.min(height * 0.46f, 400f * density);
        float wide = tall * pictureWidth / Math.max(1, pictureHeight);
        return new float[] { width - wide, height - tall, wide, tall };
    }

    @Override public void draw(Canvas canvas) {
        canvas.drawColor(Ui.BACK);
        Bitmap scene = picture(context);
        if (scene == null) return;
        Rect bounds = getBounds();
        if (bounds.width() < 8 || bounds.height() < 8) return;
        float[] at = place(bounds.width(), bounds.height(), context.getResources().getDisplayMetrics().density, scene.getWidth(), scene.getHeight());
        place.set(bounds.left + at[0], bounds.top + at[1], bounds.left + at[0] + at[2], bounds.top + at[1] + at[3]);
        canvas.save();
        canvas.clipRect(bounds);
        canvas.drawBitmap(scene, null, place, paint);
        canvas.restore();
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
