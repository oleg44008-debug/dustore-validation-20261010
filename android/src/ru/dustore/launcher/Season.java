package ru.dustore.launcher;

import android.content.Context;

import java.util.Calendar;
import java.util.Locale;

/**
 * Приложение дважды в год переодевается и само снимает костюм:
 * Хеллоуин — весь октябрь, Новый год — с 1 декабря по 31 января, с 1 февраля обычный вид.
 *
 * Сезон — это палитра и одно тихое украшение на нижней полосе; раскладка и элементы
 * остаются на местах. Решается один раз при запуске по дате телефона (Ui берёт палитру
 * один раз), выключается в настройках. Здесь нет типов Android, кроме чтения настройки:
 * правила дат проверяются обычными тестами на JVM.
 */
final class Season {
    static final int NONE = 0;
    static final int HALLOWEEN = 1;
    static final int NEW_YEAR = 2;

    private static final String PREFERENCES = "prime";
    private static final String KEY = "seasonal";

    private static int current = NONE;

    private Season() { }

    static int current() { return current; }

    /** Сезон месяца, месяц от 1 до 12. */
    static int of(int month) {
        if (month == 10) return HALLOWEEN;
        if (month == 12 || month == 1) return NEW_YEAR;
        return NONE;
    }

    /** Что надеть: явная просьба сильнее выключателя, выключатель сильнее даты. */
    static int decide(int month, boolean enabled, String forced) {
        if (forced != null) {
            String asked = forced.trim().toLowerCase(Locale.ROOT);
            if (asked.equals("halloween")) return HALLOWEEN;
            if (asked.equals("newyear")) return NEW_YEAR;
            if (asked.equals("none")) return NONE;
        }
        return enabled ? of(month) : NONE;
    }

    /** Вызывается из DustoreApp.onCreate — раньше, чем загрузится класс Ui. */
    static void load(Context context) {
        int month = Calendar.getInstance().get(Calendar.MONTH) + 1;
        current = decide(month, enabled(context), null);
    }

    static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).commit();
    }

    static String describe(int kind) {
        if (kind == HALLOWEEN) return "Сейчас: Хеллоуин — до конца октября.";
        if (kind == NEW_YEAR) return "Сейчас: Новый год — по 31 января.";
        return "Сейчас: обычное оформление.";
    }

    private static final String[] ROLES = {
        "BACK", "BACK_SOFT", "CARD", "CARD_HOVER", "CARD_PRESSED", "LINE", "LINE_SOFT", "LINE_STRONG",
        "ACCENT", "ACCENT_HOVER", "ACCENT_PRESSED", "ACCENT_INK", "ACCENT_SUBTLE",
        "ACTION", "ACTION_PRESSED", "ACTION_INK", "MUTED", "DIM", "DIM_FIXED", "FOCUS"
    };

    // Ночь со сливовым отливом, призрачно-фиолетовое выделение, тыквенное действие.
    private static final String[] HALLOWEEN_COLORS = {
        "#0E0A10", "#16101A", "#201725", "#2D2134", "#18111C", "#45324D", "#30233A", "#6A4E75",
        "#B78CFF", "#D0B3FF", "#9468DB", "#1A0E2E", "#2E2040",
        "#FF9A3D", "#DB7A22", "#1B1206", "#D9CBD6", "#A38FA6", "#BFAEC0", "#D0B3FF"
    };

    // Морозная синяя ночь, лёд для выделения, золото огней для действия.
    private static final String[] NEW_YEAR_COLORS = {
        "#0A0F1A", "#0F1626", "#151E31", "#202C46", "#111929", "#30405F", "#1F2A42", "#4F648C",
        "#9ED8FF", "#C4E7FF", "#6FB4E0", "#071A2E", "#18304A",
        "#FFD36B", "#E0B047", "#1A1606", "#C9D6EA", "#8898B4", "#A9B8D0", "#C4E7FF"
    };

    /** Цвет роли в сезоне или null, если сезон эту роль не трогает. */
    static String color(int kind, String role) {
        String[] colors = kind == HALLOWEEN ? HALLOWEEN_COLORS : kind == NEW_YEAR ? NEW_YEAR_COLORS : null;
        if (colors == null) return null;
        for (int i = 0; i < ROLES.length; i++) if (ROLES[i].equals(role)) return colors[i];
        return null;
    }
}
