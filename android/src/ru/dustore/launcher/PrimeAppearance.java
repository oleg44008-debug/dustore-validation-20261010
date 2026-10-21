package ru.dustore.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A native Prime studio: honest appearance previews and persistent, reversible controls. */
final class PrimeAppearance {
    private final Activity activity;
    private final Dialog dialog;
    private int theme = Prime.themeIndex();
    private int accent = Prime.accentIndex();
    private boolean seasonal;
    private final TextView preview;
    private final LinearLayout themeCards;
    private final LinearLayout accentCards;
    private final TextView apply;

    static void show(Activity activity) { new PrimeAppearance(activity).open(); }

    private PrimeAppearance(Activity activity) {
        this.activity = activity;
        seasonal = Season.enabled(activity);
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = Ui.column(activity);
        root.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 12), Ui.dp(activity, 20), Ui.dp(activity, 20));
        root.setBackground(Ui.panel(activity, 28, Ui.BACK_SOFT, Ui.LINE));
        View handle = new View(activity);
        handle.setBackground(Ui.rounded(activity, Ui.LINE_STRONG, 3));
        LinearLayout.LayoutParams grip = new LinearLayout.LayoutParams(Ui.dp(activity, 36), Ui.dp(activity, 4));
        grip.gravity = Gravity.CENTER_HORIZONTAL;
        grip.bottomMargin = Ui.dp(activity, 18);
        root.addView(handle, grip);

        LinearLayout heading = Ui.row(activity);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = Ui.column(activity);
        words.addView(Ui.text(activity, Prime.ON ? "DUSTORE PRIME" : "DUSTORE FREE", Ui.T_MICRO, Ui.ACCENT));
        words.addView(Ui.title(activity, Prime.ON ? "В вашем стиле" : "Настройки", 27, Ui.TEXT));
        heading.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = Ui.button(activity, "×", false);
        close.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 26);
        close.setPadding(0, 0, 0, 0);
        close.setContentDescription("Закрыть настройки");
        close.setOnClickListener(v -> dialog.dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
        root.addView(heading);
        root.addView(Ui.gap(activity, 18));

        ScrollView scroll = new ScrollView(activity);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.dp(activity, 12));
        LinearLayout content = Ui.column(activity);
        content.setPadding(0, 0, 0, Ui.dp(activity, 12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        preview = Ui.title(activity, Prime.ON ? "DUSTORE  /  PRIME" : "DUSTORE  /  FREE", Ui.T_TITLE, Ui.TEXT);
        preview.setGravity(Gravity.CENTER);
        preview.setMinHeight(Ui.dp(activity, 64));
        preview.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 16), Ui.dp(activity, 16), Ui.dp(activity, 16));
        boolean compact = activity.getResources().getDisplayMetrics().heightPixels
                / activity.getResources().getDisplayMetrics().density < 420
                && activity.getResources().getConfiguration().fontScale >= 1.3f;
        preview.setVisibility(compact ? View.GONE : View.VISIBLE);
        content.addView(preview);
        if (!compact) content.addView(Ui.gap(activity, 16));
        TextView themeLabel = label("ТЕМА");
        content.addView(themeLabel);
        themeCards = choices(content);
        for (int i = 0; i < Prime.THEME_NAMES.length; i++) {
            final int index = i;
            TextView card = swatch(Prime.THEME_NAMES[i], Prime.themePreview(i), Ui.MUTED);
            card.setOnClickListener(v -> { theme = index; update(); });
            themeCards.addView(card, choiceParams());
        }
        content.addView(Ui.gap(activity, 20));
        TextView accentLabel = label("АКЦЕНТ");
        content.addView(accentLabel);
        accentCards = choices(content);
        for (int i = 0; i < Prime.ACCENT_NAMES.length; i++) {
            final int index = i;
            TextView card = swatch(Prime.ACCENT_NAMES[i], Ui.CARD, Prime.accentPreview(i));
            card.setOnClickListener(v -> { accent = index; update(); });
            accentCards.addView(card, choiceParams());
        }
        if (!Prime.ON) {
            themeLabel.setVisibility(View.GONE);
            accentLabel.setVisibility(View.GONE);
            ((View) themeCards.getParent()).setVisibility(View.GONE);
            ((View) accentCards.getParent()).setVisibility(View.GONE);
            content.addView(Ui.paragraph(activity, "Темы и акцентные цвета доступны в версии Prime.", Ui.T_LABEL, Ui.DIM_FIXED));
        }
        content.addView(Ui.gap(activity, 20));
        content.addView(label("СЕЗОНЫ"));
        content.addView(Ui.gap(activity, 8));
        TextView season = row("Сезонное оформление", seasonal);
        season.setOnClickListener(v -> {
            seasonal = !seasonal;
            season.setText(toggle("Сезонное оформление", seasonal));
            update();
        });
        content.addView(season);
        content.addView(Ui.gap(activity, 8));
        content.addView(Ui.paragraph(activity, "Хеллоуин — весь октябрь, Новый год — с 1 декабря по 31 января; потом возвращается обычный вид. "
                + Season.describe(Season.current()), Ui.T_MICRO, Ui.DIM_FIXED));
        content.addView(Ui.gap(activity, 20));
        content.addView(label("ДВИЖЕНИЕ"));
        LinearLayout motion = Ui.row(activity);
        for (int i = 0; i < Motion.NAMES.length; i++) {
            final int index = i;
            TextView option = Ui.button(activity, Motion.NAMES[i], false);
            option.setTextSize(12);
            option.setPadding(Ui.dp(activity, 6), Ui.dp(activity, 10), Ui.dp(activity, 6), Ui.dp(activity, 10));
            option.setSelected(Motion.level() == i);
            option.setBackground(Ui.glass(activity, 14, option.isSelected()));
            option.setOnClickListener(v -> {
                Motion.setLevel(activity, index);
                for (int j = 0; j < motion.getChildCount(); j++) {
                    View child = motion.getChildAt(j);
                    if (index == 2) Motion.reset(child);
                    child.setSelected(j == index);
                    child.setBackground(Ui.glass(activity, 14, j == index));
                }
                if (index == 2 && activity instanceof MainActivity) ((MainActivity) activity).settleMotion();
            });
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
            cell.setMargins(0, Ui.dp(activity, 8), Ui.dp(activity, 6), 0);
            motion.addView(option, cell);
        }
        content.addView(motion);
        content.addView(Ui.gap(activity, 10));
        content.addView(Ui.paragraph(activity, "Учитывается настройка анимаций Android. Короткий режим уменьшает движение.", Ui.T_MICRO, Ui.DIM_FIXED));
        content.addView(Ui.gap(activity, 18));
        TextView haptic = row("Отклик на касание", Motion.haptics());
        haptic.setOnClickListener(v -> {
            Motion.setHaptics(activity, !Motion.haptics());
            haptic.setText(toggle("Отклик на касание", Motion.haptics()));
        });
        content.addView(haptic);
        content.addView(Ui.gap(activity, 8));
        TextView intro = row("Заставка при запуске", activity.getSharedPreferences("prime", Activity.MODE_PRIVATE).getBoolean("intro", true));
        intro.setOnClickListener(v -> {
            boolean next = !activity.getSharedPreferences("prime", Activity.MODE_PRIVATE).getBoolean("intro", true);
            activity.getSharedPreferences("prime", Activity.MODE_PRIVATE).edit().putBoolean("intro", next).apply();
            intro.setText(toggle("Заставка при запуске", next));
        });
        content.addView(intro);
        content.addView(Ui.gap(activity, 8));
        TextView ultra = row("Игровой режим ULTRA", Prime.maxFps());
        ultra.setOnClickListener(v -> {
            Prime.setMaxFps(activity, !Prime.maxFps());
            ultra.setText(toggle("Игровой режим ULTRA", Prime.maxFps()));
        });
        if (Prime.ON) content.addView(ultra);
        content.addView(Ui.gap(activity, 8));
        TextView scale = row("Размер игр eX", false);
        scale.setText(scaleLabel());
        scale.setOnClickListener(v -> {
            int at = java.util.Arrays.asList(ExScale.MODES).indexOf(ExScale.mode(activity));
            ExScale.setMode(activity, ExScale.MODES[(at + 1) % ExScale.MODES.length]);
            scale.setText(scaleLabel());
        });
        if (Prime.ON) content.addView(scale);
        content.addView(Ui.gap(activity, 10));
        if (Prime.ON) {
            content.addView(new UltraSteps(activity));
            content.addView(Ui.gap(activity, 10));
            content.addView(Ui.paragraph(activity, "Что такое ULTRA. Режим Prime, который помогает играм идти плавнее. Он работает, пока игра на экране, и ничего не меняет в телефоне и в других приложениях.", Ui.T_MICRO, Ui.DIM_FIXED));
            content.addView(Ui.gap(activity, 6));
            content.addView(Ui.paragraph(activity, "•  Кадры до возможностей экрана: окно игры обновляется на 90–120 Гц, а не на 60, пока телефон не греется и не включено энергосбережение.", Ui.T_MICRO, Ui.DIM_FIXED));
            content.addView(Ui.gap(activity, 6));
            content.addView(Ui.paragraph(activity, "•  Умный подбор для игр eX: ULTRA запоминает, как шла каждая игра. Медленно — в следующий раз она рисуется на ступень меньше (до двух ступеней); кадров с запасом — размер снова крупнее, картинка чётче. Не подошло — размер возвращается сам. «Размер игр eX»: «Авто» решает по игре, «Стандарт» никогда не уменьшает, «Быстрее» — сразу на ступень меньше.", Ui.T_MICRO, Ui.DIM_FIXED));
            content.addView(Ui.gap(activity, 6));
            content.addView(Ui.paragraph(activity, "Чего ждать: больше всего меньший размер помогает телефонам без ускорения графики. Если игре не хватает процессора, кадров прибавится мало.", Ui.T_MICRO, Ui.DIM_FIXED));
        }
        content.addView(Ui.gap(activity, 16));
        TextView diagnostic = Ui.button(activity, "Диагностика ULTRA", false);
        diagnostic.setOnClickListener(v -> new android.app.AlertDialog.Builder(activity)
                .setTitle("ULTRA · устройство").setMessage(AndroidUltra.diagnostics(activity)).setPositiveButton("Закрыть", null).show());
        if (Prime.ON) content.addView(diagnostic);

        root.addView(Ui.gap(activity, 12));
        apply = Ui.button(activity, "Готово", true);
        apply.setOnClickListener(v -> {
            boolean look = Prime.ON && (theme != Prime.themeIndex() || accent != Prime.accentIndex());
            boolean switched = seasonal != Season.enabled(activity);
            if (look) Prime.saveAppearance(activity, theme, accent);
            if (switched) Season.setEnabled(activity, seasonal);
            dialog.dismiss();
            // Палитра читается один раз при запуске, поэтому новый вид появляется после перезапуска.
            if (look || switched) Prime.restart(activity);
        });
        root.addView(apply, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(root);
        update();
    }

    private void open() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.BOTTOM);
            window.setDimAmount(.65f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(activity.getResources().getDisplayMetrics().heightPixels * .88f));
        }
    }

    private TextView label(String value) {
        TextView label = Ui.title(activity, value, Ui.T_MICRO, Ui.DIM);
        label.setLetterSpacing(.12f);
        return label;
    }

    private LinearLayout choices(LinearLayout content) {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setHorizontalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.dp(activity, 12));
        scroll.setClipToPadding(false);
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, Ui.dp(activity, 10), 0, 0);
        scroll.addView(row);
        content.addView(scroll);
        return row;
    }

    private LinearLayout.LayoutParams choiceParams() {
        int width = Math.round(112 * Math.min(1.6f, Math.max(1f, activity.getResources().getConfiguration().fontScale)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(activity, width), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Ui.dp(activity, 8);
        return params;
    }

    private TextView swatch(String name, int background, int text) {
        TextView view = Ui.title(activity, name, Ui.T_LABEL, text);
        view.setGravity(Gravity.CENTER);
        view.setTag(background);
        view.setMinHeight(Ui.dp(activity, 72));
        view.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 14), Ui.dp(activity, 8), Ui.dp(activity, 14));
        view.setMaxLines(3);
        view.setClickable(true);
        view.setFocusable(true);
        Motion.touch(view);
        return view;
    }

    private void update() {
        int selectedAccent = Prime.accentPreview(accent);
        preview.setTextColor(selectedAccent);
        preview.setBackground(material(Prime.themePreview(theme), selectedAccent, 20));
        updateChoices(themeCards, theme, selectedAccent);
        updateChoices(accentCards, accent, selectedAccent);
        if (apply != null) apply.setText(theme == Prime.themeIndex() && accent == Prime.accentIndex() && seasonal == Season.enabled(activity)
                ? "Готово" : "Применить и перезапустить");
    }

    private void updateChoices(LinearLayout group, int selected, int selectedAccent) {
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView view = (TextView) group.getChildAt(i);
            boolean active = i == selected;
            view.setSelected(active);
            view.setBackground(material((int) view.getTag(), active ? selectedAccent : Ui.LINE_SOFT, 16));
            view.setContentDescription(view.getText() + (active ? ", выбрано" : ""));
        }
    }

    private GradientDrawable material(int fill, int line, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(Ui.dp(activity, radius));
        shape.setStroke(Ui.dp(activity, 1), line);
        return shape;
    }

    private TextView row(String name, boolean on) {
        TextView view = Ui.button(activity, toggle(name, on), false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private static String toggle(String name, boolean on) { return name + "   ·   " + (on ? "Вкл" : "Выкл"); }

    private String scaleLabel() {
        int at = Math.max(0, java.util.Arrays.asList(ExScale.MODES).indexOf(ExScale.mode(activity)));
        return "Размер игр eX   ·   " + ExScale.MODE_NAMES[at];
    }
}
