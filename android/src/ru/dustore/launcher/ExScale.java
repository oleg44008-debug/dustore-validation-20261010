package ru.dustore.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;

/**
 * ULTRA для игр eX: в каком разрешении рисовать игру.
 *
 * Игра Windows на телефоне рисуется на маленьком «рабочем столе» и растягивается на экран.
 * Чем он меньше, тем меньше точек проходит через графику и через общую память на каждом
 * кадре — на телефонах без ускорения это почти прямая зависимость. Стандартный размер
 * подобран на живом телефоне и остаётся прежним; ULTRA опускается на одну ступень ниже
 * только для игры, которая шла медленно, и возвращается, если игре это не подошло.
 *
 * Здесь нет типов Android, кроме чтения настройки: правила проверяются тестами на JVM.
 */
final class ExScale {
    static final String AUTO = "auto";
    static final String STANDARD = "standard";
    static final String FASTER = "faster";
    static final String[] MODES = { AUTO, STANDARD, FASTER };
    static final String[] MODE_NAMES = { "Авто", "Стандарт", "Быстрее" };

    /** Ниже этой медианы кадров в секунду игра считается медленной. */
    static final int SLOW_FPS = 24;
    /** Короче этого сеанс ничего не говорит о скорости игры, секунд. */
    static final int FEWEST_SECONDS = 60;
    /** Сеанс в уменьшенном размере короче этого — игре он не подошёл, секунд. */
    static final int BROKEN_SECONDS = 30;
    static final int BROKEN_FRAMES = 30;

    /** Запомнено об игре: «ещё не решали», «уменьшать», «не уменьшать — пробовали, не подошло». */
    static final int UNDECIDED = 0, LOWER = 1, KEEP = 2;

    private static final String PREFERENCES = "ultra_ex";
    private static final String MODE_KEY = "exScale";

    private ExScale() { }

    static String normalize(String mode) {
        return Arrays.asList(MODES).contains(mode) ? mode : AUTO;
    }

    /** Размер «рабочего стола» игры: стандартный или на ступень меньше. Всегда 16:9. */
    static int[] size(boolean unity, boolean lowered) {
        if (unity) return lowered ? new int[] { 512, 288 } : new int[] { 640, 360 };
        return lowered ? new int[] { 640, 360 } : new int[] { 800, 450 };
    }

    /** Рисовать ли эту игру в уменьшенном размере в этом сеансе. */
    static boolean lowered(boolean ultra, String mode, int remembered) {
        if (!ultra) return false;
        mode = normalize(mode);
        if (mode.equals(STANDARD)) return false;
        if (remembered == KEEP) return false;
        return mode.equals(FASTER) || remembered == LOWER;
    }

    /**
     * Что запомнить об игре после сеанса.
     *
     * @param lowered   сеанс шёл в уменьшенном размере
     * @param medianFps медиана кадров игры в секунду, 0 — нет измерений
     * @param seconds   сколько секунд игра была на экране
     * @param frames    сколько кадров игра показала за сеанс
     */
    static int learn(int remembered, boolean lowered, int medianFps, int seconds, int frames) {
        // Уменьшили — и игра закрылась, так ничего и не показав: возвращаем размер и больше
        // не трогаем. Если кадры были, её просто быстро закрыли — это ни о чём не говорит.
        if (lowered && seconds < BROKEN_SECONDS && frames < BROKEN_FRAMES) return KEEP;
        if (remembered == KEEP) return KEEP;
        if (seconds < FEWEST_SECONDS || medianFps <= 0) return remembered;
        // В уменьшенном размере игра и должна идти быстрее: это не повод возвращать прежний.
        if (lowered) return LOWER;
        return medianFps < SLOW_FPS ? LOWER : UNDECIDED;
    }

    /** Медиана ненулевых посекундных измерений; 0, если их меньше десяти. */
    static int median(int[] samples, int count) {
        int[] real = new int[count];
        int kept = 0;
        for (int i = 0; i < count; i++) if (samples[i] > 0) real[kept++] = samples[i];
        if (kept < 10) return 0;
        real = Arrays.copyOf(real, kept);
        Arrays.sort(real);
        return real[kept / 2];
    }

    // ------------------------------------------------------------- настройка ----

    static String mode(Context context) {
        return normalize(context.getSharedPreferences("prime", Context.MODE_PRIVATE).getString(MODE_KEY, AUTO));
    }

    static void setMode(Context context, String mode) {
        context.getSharedPreferences("prime", Context.MODE_PRIVATE).edit().putString(MODE_KEY, normalize(mode)).apply();
    }

    static int remembered(Context context, String game) {
        if (game == null) return UNDECIDED;
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getInt(game, UNDECIDED);
    }

    static void remember(Context context, String game, int value) {
        if (game == null) return;
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit();
        if (value == UNDECIDED) editor.remove(game); else editor.putInt(game, value);
        editor.apply();
    }

    /** Строка для журнала сеанса: что ULTRA сделал с размером и почему. */
    static String describe(boolean lowered, int[] size, String mode, int remembered) {
        String dimensions = size[0] + "×" + size[1];
        if (lowered) {
            return "ULTRA: игра рисуется в " + dimensions + " — "
                    + (normalize(mode).equals(FASTER) ? "в настройках выбрано «Быстрее»." : "в прошлый раз она шла медленно.");
        }
        if (remembered == KEEP) return "ULTRA: размер " + dimensions + " не уменьшается — этой игре меньший не подошёл.";
        return null;
    }
}
