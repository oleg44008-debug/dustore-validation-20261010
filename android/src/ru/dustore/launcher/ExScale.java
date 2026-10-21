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

    /**
     * Запомнено об игре: «ещё не решали», «на ступень меньше», «не уменьшать — пробовали, не
     * подошло», «на две ступени меньше».
     */
    static final int UNDECIDED = 0, LOWER = 1, KEEP = 2, LOWEST = 3;

    /**
     * Умный ULTRA возвращает игре размер побольше, когда кадров в нём будет не меньше этого:
     * его оценка — кадры этого сеанса, умноженные на долю точек (ступень меньше — 64 % точек).
     */
    static final int SMOOTH_FPS = 36;
    private static final double STEP_PIXELS = 0.64;

    private static final String PREFERENCES = "ultra_ex";
    private static final String MODE_KEY = "exScale";

    private ExScale() { }

    static String normalize(String mode) {
        return Arrays.asList(MODES).contains(mode) ? mode : AUTO;
    }

    /** Размер «рабочего стола» игры: стандартный или на ступень меньше. Всегда 16:9. */
    static int[] size(boolean unity, boolean lowered) {
        return size(unity, lowered ? 1 : 0);
    }

    /** Размер на ступени 0, 1 или 2 — у каждой следующей 64 % точек предыдущей. Всегда 16:9. */
    static int[] size(boolean unity, int level) {
        if (unity) return level >= 2 ? new int[] { 416, 234 } : level == 1 ? new int[] { 512, 288 } : new int[] { 640, 360 };
        return level >= 2 ? new int[] { 512, 288 } : level == 1 ? new int[] { 640, 360 } : new int[] { 800, 450 };
    }

    /** Ступень для этого сеанса: 0 — стандартный размер, 1 и 2 — меньше. */
    static int level(boolean ultra, String mode, int remembered) {
        if (!ultra) return 0;
        mode = normalize(mode);
        if (mode.equals(STANDARD) || remembered == KEEP) return 0;
        if (remembered == LOWEST) return 2;
        return mode.equals(FASTER) || remembered == LOWER ? 1 : 0;
    }

    /**
     * Что запомнить после сеанса на ступени <paramref name="level"/>. Медленно — на ступень
     * ниже; кадров с запасом даже с учётом лишних точек — на ступень выше; иначе как было.
     */
    static int learnLevel(int remembered, int level, int medianFps, int seconds, int frames) {
        if (level > 0 && seconds < BROKEN_SECONDS && frames < BROKEN_FRAMES) return KEEP;
        if (remembered == KEEP) return KEEP;
        if (seconds < FEWEST_SECONDS || medianFps <= 0) return remembered;
        if (level <= 0) return medianFps < SLOW_FPS ? LOWER : UNDECIDED;
        boolean roomAbove = medianFps * STEP_PIXELS >= SMOOTH_FPS;
        if (level == 1) return medianFps < SLOW_FPS ? LOWEST : roomAbove ? UNDECIDED : LOWER;
        return roomAbove ? LOWER : LOWEST;
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
        if (lowered) return learnLevel(remembered, 1, medianFps, seconds, frames);
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

    /** Строка для журнала сеанса на ступени <paramref name="level"/>. */
    static String describe(int level, int[] size, String mode, int remembered) {
        if (level >= 2) return "Умный ULTRA: игра рисуется в " + size[0] + "×" + size[1] + " — и в меньшем размере ей не хватало кадров.";
        return describe(level == 1, size, mode, remembered);
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
