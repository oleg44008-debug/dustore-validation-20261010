package ru.dustore.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.PowerManager;
import android.view.Display;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Free или Prime — решается при сборке (build.ps1 -Edition Prime пишет BuildEdition.java).
 *
 * Free — просто приложение: eX готовит игры не быстрее 2 МБ/с, после очереди 15 с, не больше
 * трёх в день; оформление одно.
 * Prime — без ограничения eX, режим ULTRA для своего окна игры/отрисовки и темы.
 * Настройки Prime читаются в DustoreApp до первой отрисовки: Ui берёт палитру один раз.
 */
final class Prime {

    /** Preserve the separate Prime edition provided by the user; no account activation. */
    static final boolean BUILD = BuildEdition.PRIME;
    static final boolean ON = BUILD;
    static final long FREE_EX_BYTES_PER_SECOND = 2L * 1024 * 1024;
    static final int FREE_QUEUE_SECONDS = 15;

    static final String[] THEME_KEYS = { "obsidian", "graphite", "ocean", "oled", "plum" };
    static final String[] THEME_NAMES = { "Обсидиан", "Графит", "Океан", "Чёрная OLED", "Слива" };
    static final String[] ACCENT_KEYS = { "lavender", "mint", "violet", "blue", "orange", "pink" };
    static final String[] ACCENT_NAMES = { "Лаванда", "Мята", "Аметист", "Лазурь", "Янтарь", "Розовый" };

    // BACK, BACK_SOFT, CARD, CARD_HOVER, CARD_PRESSED, LINE, LINE_SOFT, LINE_STRONG
    private static final String[] SURFACE_ROLES = { "BACK", "BACK_SOFT", "CARD", "CARD_HOVER", "CARD_PRESSED", "LINE", "LINE_SOFT", "LINE_STRONG" };
    private static final String[][] SURFACES = {
        { "#0B0D14", "#111420", "#181C2B", "#23283B", "#101320", "#333A52", "#282E43", "#535D7E" },
        { "#101115", "#18191E", "#202228", "#2D3038", "#17191E", "#3D414C", "#2D3038", "#5B6071" },
        { "#0D1324", "#121B30", "#1A253B", "#253550", "#142138", "#354966", "#22344F", "#4B6786" },
        { "#000000", "#08080A", "#121214", "#1E1E23", "#0C0C0E", "#2C2C32", "#18181C", "#3E3E46" },
        { "#120811", "#1A0C19", "#24101F", "#33182C", "#1C0B18", "#4A2541", "#321A2D", "#6A3A5D" },
    };
    // ACCENT, ACCENT_HOVER, ACCENT_PRESSED, ACCENT_INK, ACCENT_SUBTLE
    private static final String[] ACCENT_ROLES = { "ACCENT", "ACCENT_HOVER", "ACCENT_PRESSED", "ACCENT_INK", "ACCENT_SUBTLE" };
    private static final String[][] ACCENTS = {
        { "#B7A4FF", "#D3C7FF", "#9580DE", "#17122F", "#29223E" },
        { "#6CE8D9", "#9AF4E8", "#35BDAE", "#082529", "#183B47" },
        { "#AF7BFF", "#C7A3FF", "#8C5BDB", "#1A0E2E", "#2C2045" },
        { "#5AA8FF", "#8CC3FF", "#3A86DB", "#071A2E", "#18304A" },
        { "#FF9A4D", "#FFB884", "#DB7A30", "#2E1606", "#45291A" },
        { "#FF79B8", "#FFA5D0", "#D94A8E", "#2B1020", "#3D1A33" },
    };

    private static int theme;
    private static int accent;
    private static volatile boolean maxFps = true;

    private Prime() {}

    /** Вызывается из DustoreApp.onCreate — раньше, чем загрузится класс Ui. */
    static void load(Context context) {
        Motion.load(context);
        if (!ON) return;
        SharedPreferences saved = context.getSharedPreferences("prime", Context.MODE_PRIVATE);
        theme = Math.max(0, indexOf(THEME_KEYS, saved.getString("theme", "obsidian")));
        accent = Math.max(0, indexOf(ACCENT_KEYS, saved.getString("accent", "lavender")));
        maxFps = saved.getBoolean("maxFps", true);
    }

    /** Цвет роли палитры: стандартный в Free и в теме «Океан». */
    static int color(String role, String standard) {
        // Сезон (Хеллоуин, Новый год) на свои недели сильнее и темы Prime, и вида Free.
        String seasonal = Season.color(Season.current(), role);
        if (seasonal != null) return Color.parseColor(seasonal);
        int at = indexOf(SURFACE_ROLES, role);
        if (ON && at >= 0 && SURFACES[theme] != null) return Color.parseColor(SURFACES[theme][at]);
        at = indexOf(ACCENT_ROLES, role);
        if (ON && at >= 0 && ACCENTS[accent] != null) return Color.parseColor(ACCENTS[accent][at]);
        return Color.parseColor(standard);
    }

    static boolean maxFps() { return ON && maxFps; }

    static int themeIndex() { return theme; }
    static int accentIndex() { return accent; }
    static int themePreview(int index) { return Color.parseColor(SURFACES[index][2]); }
    static int accentPreview(int index) { return Color.parseColor(ACCENTS[index][0]); }
    static void setMaxFps(Context context, boolean enabled) {
        maxFps = enabled;
        context.getSharedPreferences("prime", Context.MODE_PRIVATE).edit().putBoolean("maxFps", enabled).apply();
    }
    static void saveAppearance(Context context, int selectedTheme, int selectedAccent) {
        context.getSharedPreferences("prime", Context.MODE_PRIVATE).edit()
                .putString("theme", THEME_KEYS[selectedTheme]).putString("accent", ACCENT_KEYS[selectedAccent]).commit();
    }

    /**
     * Free держит темп eX: подготовка игры занимает не меньше, чем перенос её байтов на
     * 2 МБ/с. Работа уже сделана — ждём только остаток и честно об этом пишем.
     */
    static void holdFreePace(long bytes, long startedMs, Ex.Progress say) {
        if (ON || bytes <= 0) return;
        long target = bytes * 1000L / FREE_EX_BYTES_PER_SECOND;
        long last = -1;
        while (true) {
            long spent = System.currentTimeMillis() - startedMs;
            if (spent >= target) return;
            long percent = spent * 100 / Math.max(1, target);
            if (percent / 10 != last) {
                last = percent / 10;
                say.say("Free: eX не быстрее 2 МБ/с — " + (bytes * percent / 100 / 1048576) + " из "
                        + (bytes / 1048576) + " МБ (" + percent + "%). В Prime без ограничений.");
            }
            try { Thread.sleep(Math.min(500, target - spent)); }
            catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; }
        }
    }

    static final int FREE_EX_PER_DAY = 3;

    /** Очередь Free: короткое ожидание перед каждым переносом. Prime начинает сразу. */
    static void waitFreeQueue(Ex.Progress say) {
        if (ON) return;
        for (int left = FREE_QUEUE_SECONDS; left > 0; left--) {
            say.say("Очередь Free: перенос начнётся через " + left + " с. В Prime — сразу.");
            try { Thread.sleep(1000); }
            catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; }
        }
    }

    static int usedToday(Context context) {
        return ExQuota.usedToday(context, FREE_EX_PER_DAY);
    }

    /** Free: не больше трёх переносов eX в сутки по времени сервера. null — можно начинать. Из фонового потока. */
    static String quotaRefusal(Context context) {
        if (ON) return null;
        ExQuota.sync(context);
        if (usedToday(context) < FREE_EX_PER_DAY) return null;
        return "Сегодня использованы все " + FREE_EX_PER_DAY + " переноса eX версии Free. "
                + "Новые появятся после полуночи по Москве (время берётся с сервера, нужен интернет). "
                + "В Prime переносов без лимита.";
    }

    /** Засчитывается только готовая игра: ошибка и отмена лимит не тратят. */
    static void recordSuccess(Context context) {
        if (ON) return;
        ExQuota.recordSuccess(context, FREE_EX_PER_DAY);
    }

    /** Проверка лимита на самом телефоне: ответ сервера подменяется, часы телефона не трогаются. */
    static String quotaSelfTest(Context context) {
        if (ON) return "prime";
        StringBuilder out = new StringBuilder();
        long noon = 1791108000000L;                       // 2026-10-04 10:00 UTC = 13:00 МСК
        ExQuota.clear(context);
        ExQuota.serverOverride = noon;
        ExQuota.sync(context);
        for (int i = 0; i < FREE_EX_PER_DAY; i++) recordSuccess(context);
        out.append("fourthRefused=").append(usedToday(context) >= FREE_EX_PER_DAY);
        ExQuota.serverOverride = noon - 30L * 3600 * 1000;
        ExQuota.sync(context);
        out.append(" clockBackKeeps=").append(usedToday(context) >= FREE_EX_PER_DAY);
        ExQuota.deleteFirstCopy(context);
        out.append(" deletedCopyKeeps=").append(usedToday(context) >= FREE_EX_PER_DAY);
        ExQuota.corruptFirstCopy(context);
        recordSuccess(context);
        ExQuota.corruptFirstCopy(context);
        out.append(" editedReadsUsedUp=").append(usedToday(context) >= FREE_EX_PER_DAY);
        ExQuota.clear(context);
        ExQuota.serverOverride = noon;
        ExQuota.sync(context);
        for (int i = 0; i < FREE_EX_PER_DAY; i++) recordSuccess(context);
        ExQuota.serverOverride = noon + 12L * 3600 * 1000;   // 01:00 следующих суток по Москве
        ExQuota.sync(context);
        out.append(" newServerDayResets=").append(usedToday(context) == 0);
        ExQuota.clear(context);
        ExQuota.serverOverride = null;
        return out.toString();
    }

    static String remaining(Context context) {
        if (ON) return null;
        return "Free: сегодня осталось переносов eX — " + Math.max(0, FREE_EX_PER_DAY - usedToday(context))
                + " из " + FREE_EX_PER_DAY + ".";
    }

    /**
     * Existing Prime runtime options. Engine-specific memory settings remain intact;
     * resolution, textures and asynchronous shader policy are not changed here.
     */
    static void boostEnvironment(java.util.Map<String, String> env) {
        if (!maxFps()) return;
        // Preserve the original Prime Box64 call/return option.
        env.put("BOX64_DYNAREC_CALLRET", "1");
        // Preserve Ex.environment / engine-specific memory policy rather than replacing it.
        // Кэш шейдеров видеодрайвера — большой и постоянный.
        env.put("MESA_SHADER_CACHE_MAX_SIZE", "2G");
        env.put("MESA_SHADER_CACHE_DISABLE", "false");
        // No additional DXVK frame cap; Wayland callbacks and the painter share a budget.
        env.put("DXVK_FRAME_RATE", "0");
    }

    /** CPU affinity remains under Android scheduling; no pinning of all Wine processes. */
    static String bigCoreMask() { return null; }

    /** Поток кадров — впереди фоновых задач. */
    static void boostThread() {
        if (!maxFps()) return;
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY); }
        catch (RuntimeException ignored) { }
    }

    /** Both editions expose motion settings; Prime also exposes themes and ULTRA. */
    static TextView mark(final Activity activity) {
        TextView chip = Ui.chip(activity, ON ? "PRIME" : "FREE", ON ? Ui.ACCENT : Ui.DIM);
        Ui.chromeText(chip, Ui.T_MICRO);
        chip.setSingleLine(true);
        chip.setMinHeight(Ui.dp(activity, Ui.TOUCH));
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setContentDescription(ON ? "Настройки Dustore Prime" : "Настройки Dustore Free");
        chip.setClickable(true);
        chip.setFocusable(true);
        Motion.touch(chip);
        chip.setOnClickListener(v -> PrimeAppearance.show(activity));
        return chip;
    }

    static void restart(Activity activity) {
        Intent start = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (start != null) {
            start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.startActivity(start);
        }
        Runtime.getRuntime().exit(0);
    }

    private static void pitch(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("DustoreX Prime")
                .setMessage("• ULTRA: частота своего окна игры, отрисовка eX и подсказки Android с учётом нагрева и энергосбережения. Эффект зависит от устройства и игры.\n"
                        + "• eX без очереди и ограничения скорости (во Free — 15 с и 2 МБ/с).\n"
                        + "• Переносов eX без лимита (во Free — 3 в день).\n"
                        + "• Темы и акцентные цвета.\n\n"
                        + "Prime — отдельная сборка приложения, ставится поверх этой; библиотека сохраняется.")
                .setPositiveButton("Понятно", null)
                .show();
    }

    private static int indexOf(String[] items, String value) {
        for (int i = 0; i < items.length; i++) if (items[i].equals(value)) return i;
        return -1;
    }
}
