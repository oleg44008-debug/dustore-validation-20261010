package ru.dustore.launcher;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Панель поверх пада: сменить раскладку и нажать клавишу, которой на паде нет.
 *
 * Без неё играть выходило только в те игры, чьи клавиши мы угадали. Одна игра просит Enter,
 * другая E, третья правую кнопку мыши, четвёртая F — и если нужной кнопки на нарисованном
 * паде нет, игра просто не идёт. Раскладку тоже нельзя было сменить, не выйдя из игры: в
 * трансляции пад открывается сразу, минуя выбор.
 *
 * Поэтому здесь два ответа сразу. Сверху — раскладки, одним касанием, прямо посреди игры
 * (компьютер умеет менять их на ходу и отпускает прежние клавиши сам). Ниже — клавиши: те,
 * что чаще всего просят игры, включая обе кнопки мыши.
 */
final class PadPanel extends LinearLayout {

    /** Что делать по нажатию: клавиша, смена раскладки или переключение руля. */
    interface Listener {
        void onKey(String name, boolean down);

        void onProfile(String name);

        void onTilt(boolean on);

        void onClose();
    }

    /**
     * Клавиши панели: слева — то, что просят чаще всего.
     *
     * Имена — те же, что понимает компьютер (см. BridgeInput.KeyNames): панель не может
     * попросить клавишу, которую некому нажать.
     */
    private static final String[][] KEYS = {
            { "ENTER", "Enter" },
            { "E", "E" },
            { "F", "F" },
            { "MOUSE_L", "ЛКМ" },
            { "MOUSE_R", "ПКМ" },
            { "MOUSE_M", "СКМ" },
            { "SPACE", "Пробел" },
            { "ESC", "Esc" },
            { "TAB", "Tab" },
            { "R", "R" },
            { "Q", "Q" },
            { "G", "G" },
            { "V", "V" },
            { "C", "C" },
            { "X", "X" },
            { "Z", "Z" },
            { "LSHIFT", "Shift" },
            { "LCTRL", "Ctrl" },
            { "LALT", "Alt" },
            { "1", "1" },
            { "2", "2" },
            { "3", "3" },
            { "4", "4" },
            { "5", "5" },
            { "UP", "↑" },
            { "DOWN", "↓" },
            { "LEFT", "←" },
            { "RIGHT", "→" },
            { "F1", "F1" },
            { "F5", "F5" },
            { "F11", "F11" },
            { "GRAVE", "`" },
            { "WHEEL_UP", "колесо ↑" },
            { "WHEEL_DOWN", "колесо ↓" }
    };

    private final Context context;
    private final Listener listener;
    private final LinearLayout profiles;
    private final TextView title;

    private final LinearLayout wheelRow;
    private final TextView layoutsTitle;
    private final HorizontalScrollView profileScroll;
    private final TextView tilt;
    private final TextView wheelHint;

    private boolean tilted;
    private final java.util.Map<TextView, String> heldKeys = new java.util.HashMap<>();
    private final java.util.Map<TextView, Runnable> releasePulses = new java.util.HashMap<>();
    private final java.util.List<TextView> keyButtons = new java.util.ArrayList<>();
    private LinearLayout keyRows;
    private int keyColumns;

    PadPanel(Context context, Listener listener) {
        super(context);

        this.context = context;
        this.listener = listener;

        setOrientation(VERTICAL);

        // Панель ложится на кадр игры: почти непрозрачная, но всё же чуть прозрачная —
        // так видно, что игра под ней никуда не делась и продолжает идти.
        setBackgroundColor(Ui.alpha(Ui.BACK, 0xF2));
        setPadding(Ui.dp(context, Ui.S4), Ui.dp(context, Ui.S3),
                Ui.dp(context, Ui.S4), Ui.dp(context, Ui.S3));

        // Панель открывается поверх игры в полный экран — значит, и вырез камеры, и
        // навигационная полоса на ней свои: содержимое отодвигается от обоих.
        Ui.fit(this, true, true, true);

        // ---- заголовок и выход ----------------------------------------------------------

        LinearLayout head = Ui.row(context);
        head.setGravity(Gravity.CENTER_VERTICAL);

        title = Ui.title(context, "Настройка на ходу", Ui.T_HEAD, Ui.TEXT);
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(title, grow);

        TextView close = Ui.button(context, "Играть", true);
        close.setPadding(Ui.dp(context, Ui.S5), Ui.dp(context, Ui.S2),
                Ui.dp(context, Ui.S5), Ui.dp(context, Ui.S2));
        Ui.setIcon(close, "cancel", 16, Ui.ACCENT_INK);
        close.setOnClickListener(view -> listener.onClose());
        head.addView(close);

        addView(head);

        // ---- раскладки ------------------------------------------------------------------

        layoutsTitle = Ui.text(context, "Раскладка — меняется прямо в игре",
                Ui.T_LABEL, Ui.MUTED);
        layoutsTitle.setPadding(0, Ui.dp(context, Ui.S3), 0, Ui.dp(context, Ui.S2));
        addView(layoutsTitle);

        profiles = Ui.row(context);

        profileScroll = new HorizontalScrollView(context);
        profileScroll.setHorizontalScrollBarEnabled(true);
        profileScroll.addView(profiles);
        addView(profileScroll);

        // ---- руль от наклона ------------------------------------------------------------

        wheelRow = Ui.column(context);
        wheelRow.setVisibility(GONE);

        TextView wheelTitle = Ui.text(context, "Руль", Ui.T_LABEL, Ui.MUTED);
        wheelTitle.setPadding(0, Ui.dp(context, Ui.S4), 0, Ui.dp(context, Ui.S2));
        wheelRow.addView(wheelTitle);

        tilt = Ui.button(context, "Крутить телефоном  —  выключено", false);
        Ui.setIcon(tilt, "spin", 20, Ui.ACCENT);
        tilt.setOnClickListener(view -> listener.onTilt(!tilted));

        wheelRow.addView(tilt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wheelHint = Ui.paragraph(context,
                "Держите телефон как руль и поворачивайте его — машина поедет туда же. "
                        + "Ноль берётся в момент включения, поэтому играть можно в любой позе.",
                Ui.T_MICRO, Ui.DIM_FIXED);
        wheelHint.setPadding(0, Ui.dp(context, Ui.S2), 0, 0);
        wheelRow.addView(wheelHint);

        addView(wheelRow);

        // ---- клавиши --------------------------------------------------------------------

        TextView keysTitle = Ui.paragraph(context,
                "Клавиши — для того, чего нет на паде. Держать можно: нажатие идёт, пока "
                        + "палец на кнопке.", Ui.T_LABEL, Ui.MUTED);
        keysTitle.setPadding(0, Ui.dp(context, Ui.S4), 0, Ui.dp(context, Ui.S2));
        addView(keysTitle);

        ScrollView keyScroll = new ScrollView(context);
        LinearLayout rows = Ui.column(context);
        keyRows = rows;
        for (String[] key : KEYS) keyButtons.add(keyButton(key[0], key[1]));
        layoutKeys(Ui.dp(context, getResources().getConfiguration().screenWidthDp) - getPaddingLeft() - getPaddingRight());

        keyScroll.addView(rows);

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        addView(keyScroll, scrollParams);

        // Keep the return action visible. The rest can scroll even with large fonts
        // or a short landscape viewport, where a weighted keyboard used to collapse.
        LinearLayout body = Ui.column(context);
        while (getChildCount() > 1) {
            android.view.View child = getChildAt(1); removeView(child);
            if (child == keyScroll) {
                keyScroll.removeView(rows);
                body.addView(rows, new LinearLayout.LayoutParams(-1, -2));
            } else body.addView(child);
        }
        ScrollView bodyScroll = new ScrollView(context);
        bodyScroll.setVerticalScrollBarEnabled(true); bodyScroll.addView(body);
        addView(bodyScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void layoutKeys(int width) {
        int target = Ui.dp(context, Math.round(Ui.TOUCH * Math.min(2f, getResources().getConfiguration().fontScale)) + Ui.S2);
        int columns = Math.max(1, Math.min(6, width / Math.max(1, target)));
        if (columns == keyColumns || keyRows == null) return;
        releaseAllInputs();
        keyColumns = columns;
        for (TextView button : keyButtons) if (button.getParent() instanceof ViewGroup) ((ViewGroup) button.getParent()).removeView(button);
        keyRows.removeAllViews(); LinearLayout row = null;
        for (int i = 0; i < keyButtons.size(); i++) {
            if (i % columns == 0) { row = Ui.row(context); keyRows.addView(row); }
            row.addView(keyButtons.get(i), cell());
        }
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        releaseAllInputs();
        // Reparent after this traversal, so the newly created rows get a fresh
        // measure pass instead of keeping zero-sized buttons from the old grid.
        post(() -> layoutKeys(Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight())));
    }

    private LinearLayout.LayoutParams cell() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        params.rightMargin = Ui.dp(context, Ui.S2);
        params.bottomMargin = Ui.dp(context, Ui.S2);

        return params;
    }

    /**
     * Кнопка клавиши.
     *
     * Нажатие и отпускание отдельно, а не «тап»: в играх зажатый Shift — это бег, а зажатая
     * левая кнопка мыши — очередь. Тап такого не даёт.
     */
    private TextView keyButton(String name, String label) {
        TextView button = Ui.button(context, label, false);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Ui.T_LABEL);
        button.setPadding(Ui.dp(context, Ui.S1), Ui.dp(context, Ui.S3),
                Ui.dp(context, Ui.S1), Ui.dp(context, Ui.S3));
        button.setContentDescription("Клавиша " + label);
        final boolean[] touchClick = { false };
        Runnable release = () -> {
            releasePulses.remove(button);
            button.setPressed(false);
            if (heldKeys.remove(button) != null) listener.onKey(name, false);
        };
        button.setOnClickListener(v -> {
            if (touchClick[0] || heldKeys.containsKey(button)) return;
            heldKeys.put(button, name); releasePulses.put(button, release);
            button.setPressed(true);
            listener.onKey(name, true);
            button.postDelayed(release, 80);
        });
        button.setOnLongClickListener(v -> {
            button.removeCallbacks(release); releasePulses.remove(button);
            boolean down = heldKeys.remove(button) == null;
            if (down) heldKeys.put(button, name);
            button.setPressed(down); listener.onKey(name, down); return true;
        });
        button.setAccessibilityDelegate(new android.view.View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(android.view.View host, android.view.accessibility.AccessibilityNodeInfo node) {
                super.onInitializeAccessibilityNodeInfo(host, node);
                node.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK, "Удерживать или отпустить клавишу"));
            }
        });

        // Вид нажатой клавиши берётся из самой кнопки, а не подменяется картинкой:
        // состояние pressed у неё теперь есть, и выглядит оно одинаково со всеми
        // остальными кнопками приложения. Нажатие и отпускание по-прежнему раздельные —
        // зажатый Shift это бег, а зажатая левая кнопка мыши — очередь.
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    button.removeCallbacks(release); releasePulses.remove(button);
                    view.setPressed(true);
                    if (!heldKeys.containsKey(button)) {
                        heldKeys.put(button, name);
                        listener.onKey(name, true);
                    }
                    return true;

                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    button.removeCallbacks(release); releasePulses.remove(button);
                    view.setPressed(false);
                    if (heldKeys.remove(button) != null) listener.onKey(name, false);
                    if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                        touchClick[0] = true;
                        view.performClick();
                        touchClick[0] = false;
                    }
                    return true;

                default:
                    return false;
            }
        });

        return button;
    }

    void releaseAllInputs() {
        if (heldKeys == null || listener == null) return;
        java.util.Map<TextView, String> releasing = new java.util.HashMap<>(heldKeys);
        heldKeys.clear();
        for (java.util.Map.Entry<TextView, Runnable> pulse : releasePulses.entrySet()) pulse.getKey().removeCallbacks(pulse.getValue());
        releasePulses.clear();
        for (java.util.Map.Entry<TextView, String> key : releasing.entrySet()) {
            key.getKey().setPressed(false);
            listener.onKey(key.getValue(), false);
        }
    }

    @Override protected void onVisibilityChanged(android.view.View changedView, int visibility) {
        if (visibility != VISIBLE) releaseAllInputs();
        super.onVisibilityChanged(changedView, visibility);
    }

    @Override protected void onDetachedFromWindow() {
        releaseAllInputs();
        super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        if (visibility != VISIBLE) releaseAllInputs();
        super.onWindowVisibilityChanged(visibility);
    }

    /** Список раскладок с компьютера — тот же, что и на экране выбора. */
    void setProfiles(JSONArray list, String current) {
        profiles.removeAllViews();

        if (list == null) {
            profiles.addView(Ui.text(context, "Список раскладок ещё не пришёл",
                    Ui.T_LABEL, Ui.DIM));
            return;
        }

        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);

            if (item == null) {
                continue;
            }

            final String name = item.optString("name", "");

            if (name.isEmpty()) {
                continue;
            }

            boolean now = name.equals(current);
            TextView chip = Ui.button(context,
                    name + (item.optBoolean("mine", false) ? "  ·  своя" : ""), now);
            chip.setPadding(Ui.dp(context, Ui.S4), Ui.dp(context, Ui.S2),
                    Ui.dp(context, Ui.S4), Ui.dp(context, Ui.S2));
            chip.setOnClickListener(view -> listener.onProfile(name));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = Ui.dp(context, Ui.S2);

            profiles.addView(chip, params);
        }
    }

    /**
     * Убирает выбор раскладок.
     *
     * Нужно там, где раскладок нет вовсе: в eX игра идёт на самом телефоне, клавиша
     * означает ровно себя, и пустая полоса «раскладки» была бы обещанием того, чего нет.
     */
    void hideProfiles() {
        layoutsTitle.setVisibility(GONE);
        profileScroll.setVisibility(GONE);
    }

    void setTitle(String text) {
        title.setText(text);
    }

    /**
     * Показывает или убирает переключатель руля.
     *
     * Он есть только там, где есть руль: в раскладке шутера «крутить телефоном» нечего, и
     * лишняя кнопка там только путала бы.
     */
    void setWheel(boolean hasWheel, boolean canTilt, boolean on) {
        wheelRow.setVisibility(hasWheel ? VISIBLE : GONE);
        tilted = on;

        if (!hasWheel) {
            return;
        }

        if (!canTilt) {
            // Недоступная кнопка теперь и выглядит недоступной сама: рамка бледнеет,
            // надпись уходит в серый — руками ничего перекрашивать не надо.
            tilt.setText("Этот телефон наклон не измеряет");
            Ui.setIcon(tilt, "spin", 20, Ui.DIM);
            tilt.setEnabled(false);
            wheelHint.setText("Нужен датчик силы тяжести — на этом телефоне его нет. "
                    + "Руль остаётся под палец.");

            return;
        }

        tilt.setEnabled(true);
        tilt.setText(on
                ? "Крутить телефоном  —  включено"
                : "Крутить телефоном  —  выключено");
        Ui.asFilled(tilt, on);
        Ui.setIcon(tilt, "spin", 20, on ? Ui.ACCENT_INK : Ui.ACCENT);

        wheelHint.setText(on
                ? "Руль слушает наклон. Палец по нарисованному рулю всё равно работает — "
                  + "можно и так, и так."
                : "Держите телефон как руль и поворачивайте его — машина поедет туда же. "
                  + "Ноль берётся в момент включения, поэтому играть можно в любой позе.");
    }
}
