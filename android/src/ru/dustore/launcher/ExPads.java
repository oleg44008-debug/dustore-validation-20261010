package ru.dustore.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Раскладки падов для игр, которые идут на самом телефоне (eX).
 *
 * Те же самые пады, что в eXepad, и те же значки: человек, который играл с телефона в
 * ПК-игру, узнаёт кнопки в игре на телефоне. Разница одна: клавиши здесь нажимаются не на
 * компьютере, а уходят в наш экран ({@link ExDisplay}) — поэтому и клавиши, и геометрия
 * живут тут, а не приходят по проводу.
 *
 * Координаты и радиусы — доли экрана, как в PadView: x и y — центр, r — радиус в долях
 * высоты, чтобы круги оставались кругами.
 */
final class ExPads {

    static final String SHOOTER = "Шутер и хоррор";
    static final String PLATFORMER = "Платформер";
    static final String RPG_MAKER = "RPG Maker";
    static final String NOVEL = "Новелла";
    static final String DESKTOP = "Мышь и клавиатура";
    static final String RACING = "Гонки";
    static final String FIGHTING = "Файтинг";

    static final String[] NAMES = {
            SHOOTER, PLATFORMER, RPG_MAKER, NOVEL, DESKTOP, RACING, FIGHTING
    };

    /** Раскладки только для ПК-игр (eXepad) — здесь ради превью на экране выбора. */
    static final String MINECRAFT = "Майнкрафт";
    static final String ROBLOX = "Роблокс";
    static final String SHOOTER_PC = "Шутер и хоррор";

    /** Есть ли у нас геометрия для раскладки с таким именем — чтобы нарисовать превью. */
    static boolean known(String name) {
        if (name == null) {
            return false;
        }

        for (String known : NAMES) {
            if (known.equals(name)) {
                return true;
            }
        }

        return MINECRAFT.equals(name) || ROBLOX.equals(name) || SHOOTER_PC.equals(name);
    }

    /** Порог стика в тысячных: тот же, что у eXepad на компьютере. */
    static final int DEAD_ZONE = 260;

    /** Раскладка: что рисовать и какие клавиши за этим стоят. */
    static final class Layout {

        String name;
        String note;

        /** Экран — тачпад: тап ставит указатель. Иначе правая половина — камера. */
        boolean pointer;

        /** Насколько сдвинуть мышь на один пиксель пальца. */
        float look = 1.6f;

        final List<PadView.Control> controls = new ArrayList<>();

        /** Клавиши по номеру контрола: у кнопки одна, у стика четыре (вверх, вниз, влево, вправо). */
        final List<String[]> keys = new ArrayList<>();

        String[] keysOf(int index) {
            return index >= 0 && index < keys.size() ? keys.get(index) : new String[0];
        }
    }

    /** Раскладка по движку игры — без жанров, их у телефона нет. */
    static String suggest(String engine) {
        if (engine == null) {
            return DESKTOP;
        }

        switch (engine) {
            case "Ren'Py":
                return NOVEL;
            case "RPG Maker":
                return RPG_MAKER;
            case "GameMaker":
                return PLATFORMER;
            case "Unity":
            case "Godot":
            case "Unreal Engine":
                // Engine does not identify genre; menus must work immediately.
                return DESKTOP;
            default:
                return DESKTOP;
        }
    }

    static Layout build(String name) {
        switch (name == null ? "" : name) {
            case PLATFORMER:
                return platformer();
            case RPG_MAKER:
                return rpgMaker();
            case NOVEL:
                return novel();
            case DESKTOP:
                return desktop();
            case RACING:
                return racing();
            case FIGHTING:
                return fighting();
            case MINECRAFT:
                return minecraft();
            case ROBLOX:
                return roblox();
            default:
                return shooter();
        }
    }

    private static Layout minecraft() {
        Layout layout = start(MINECRAFT, "Стик — идти, правая половина — камера, удар и блок под правой рукой");
        layout.look = 2.0f;

        look(layout, 0.34f, 0.0f, 0.66f, 1.0f);
        stick(layout, "Идти", 0.13f, 0.66f, 0.26f, "boots", new String[] { "W", "S", "A", "D" });
        button(layout, "Удар", "MOUSE_L", 0.885f, 0.86f, 0.12f, "ЛКМ", "pick");
        button(layout, "Поставить", "MOUSE_R", 0.885f, 0.58f, 0.105f, "ПКМ", "cube");
        button(layout, "Прыжок", "SPACE", 0.70f, 0.86f, 0.105f, "Пробел", "jump");
        button(layout, "Красться", "LSHIFT", 0.555f, 0.86f, 0.095f, "Shift", "sneak");
        button(layout, "Инвентарь", "E", 0.70f, 0.58f, 0.09f, "E", "bag");
        button(layout, "Бросить", "Q", 0.555f, 0.58f, 0.08f, "Q", "throw");
        button(layout, "Меню", "ESC", 0.955f, 0.10f, 0.075f, "Esc", "menu");

        return layout;
    }

    private static Layout roblox() {
        Layout layout = start(ROBLOX, "Стик — идти, правая половина — камера, прыжок под большим пальцем");
        layout.look = 2.0f;

        look(layout, 0.34f, 0.0f, 0.66f, 1.0f);
        stick(layout, "Идти", 0.13f, 0.66f, 0.26f, "boots", new String[] { "W", "S", "A", "D" });
        button(layout, "Прыжок", "SPACE", 0.885f, 0.84f, 0.13f, "Пробел", "jump");
        button(layout, "Действие", "MOUSE_L", 0.885f, 0.55f, 0.10f, "ЛКМ", "hand");
        button(layout, "E", "E", 0.70f, 0.84f, 0.09f, "E", "letter");
        button(layout, "Рюкзак", "GRAVE", 0.70f, 0.55f, 0.08f, "`", "bag");
        button(layout, "Меню", "ESC", 0.955f, 0.10f, 0.075f, "Esc", "menu");

        return layout;
    }

    /** Подпись раскладки для списка — то, что видно под именем. */
    static String note(String name) {
        return build(name).note;
    }

    private static Layout shooter() {
        Layout layout = start(SHOOTER, "Левый стик — идти, правая половина — осмотреться");
        layout.look = 2.2f;

        look(layout, 0.34f, 0.0f, 0.66f, 1.0f);
        stick(layout, "Идти", 0.13f, 0.66f, 0.26f, "boots", new String[] { "W", "S", "A", "D" });
        button(layout, "Огонь", "MOUSE_L", 0.885f, 0.86f, 0.125f, "ЛКМ", "fire");
        button(layout, "Прицел", "MOUSE_R", 0.885f, 0.58f, 0.10f, "ПКМ", "aim");
        button(layout, "Прыжок", "SPACE", 0.70f, 0.86f, 0.105f, "Пробел", "jump");
        button(layout, "Присесть", "LCTRL", 0.555f, 0.86f, 0.095f, "Ctrl", "crouch");
        button(layout, "Бег", "LSHIFT", 0.40f, 0.86f, 0.095f, "Shift", "run");
        button(layout, "Взять", "E", 0.70f, 0.58f, 0.09f, "E", "hand");
        button(layout, "Фонарь", "F", 0.555f, 0.58f, 0.085f, "F", "torch");
        button(layout, "Меню", "ESC", 0.955f, 0.10f, 0.075f, "Esc", "menu");

        return layout;
    }

    private static Layout platformer() {
        Layout layout = start(PLATFORMER, "Крестовина слева, прыжок и удар справа");

        pad(layout, "Ход", 0.14f, 0.68f, 0.27f, new String[] { "UP", "DOWN", "LEFT", "RIGHT" });
        button(layout, "Прыжок", "SPACE", 0.88f, 0.78f, 0.125f, "Пробел", "jump");
        button(layout, "Действие", "Z", 0.70f, 0.86f, 0.10f, "Z", "letter");
        button(layout, "Второе", "X", 0.70f, 0.60f, 0.10f, "X", "letter");
        button(layout, "Бег", "LSHIFT", 0.53f, 0.86f, 0.095f, "Shift", "run");
        button(layout, "Меню", "ESC", 0.955f, 0.10f, 0.075f, "Esc", "menu");

        return layout;
    }

    private static Layout rpgMaker() {
        Layout layout = start(RPG_MAKER, "Стрелки слева, Z — «ок», X — «отмена»");

        pad(layout, "Ход", 0.14f, 0.66f, 0.28f, new String[] { "UP", "DOWN", "LEFT", "RIGHT" });
        button(layout, "Ок", "Z", 0.88f, 0.80f, 0.13f, "Z / Enter", "ok");
        button(layout, "Отмена", "X", 0.69f, 0.62f, 0.105f, "X", "cancel");
        button(layout, "Бег", "LSHIFT", 0.69f, 0.88f, 0.095f, "Shift", "run");
        button(layout, "Меню", "ESC", 0.955f, 0.10f, 0.075f, "Esc", "menu");

        return layout;
    }

    private static Layout novel() {
        Layout layout = start(NOVEL, "Тап по экрану — дальше по тексту");
        layout.pointer = true;

        look(layout, 0.0f, 0.0f, 1.0f, 1.0f);
        button(layout, "Дальше", "MOUSE_L", 0.90f, 0.86f, 0.115f, "ЛКМ", "next");
        button(layout, "Пропуск", "LCTRL", 0.72f, 0.86f, 0.095f, "Ctrl", "skip");
        button(layout, "Назад", "PAGEUP", 0.56f, 0.86f, 0.085f, "PgUp", "letter");
        button(layout, "Скрыть", "H", 0.40f, 0.86f, 0.085f, "H", "eye");
        button(layout, "Меню", "ESC", 0.955f, 0.10f, 0.075f, "Esc", "menu");

        return layout;
    }

    private static Layout desktop() {
        Layout layout = start(DESKTOP, "Экран — тачпад: где нажали, туда и указатель");
        layout.pointer = true;

        look(layout, 0.0f, 0.0f, 1.0f, 1.0f);
        button(layout, "ЛКМ", "MOUSE_L", 0.90f, 0.86f, 0.10f, "клик", "mouseL");
        button(layout, "ПКМ", "MOUSE_R", 0.90f, 0.62f, 0.085f, "правая", "mouseR");
        stick(layout, "Идти", 0.12f, 0.70f, 0.22f, "stick", new String[] { "W", "S", "A", "D" });
        button(layout, "Пробел", "SPACE", 0.72f, 0.86f, 0.09f, "прыжок", "jump");
        button(layout, "Ввод", "ENTER", 0.56f, 0.86f, 0.085f, "Enter", "ok");
        button(layout, "Действие", "E", 0.56f, 0.60f, 0.085f, "E", "hand");
        button(layout, "Меню", "ESC", 0.955f, 0.10f, 0.075f, "Esc", "menu");

        return layout;
    }

    private static Layout racing() {
        Layout layout = start(RACING, "Руль крутится пальцем, справа педали, между ними ручник и передачи");

        // Руль слушает только поворот: «вперёд» и «назад» пустые, чтобы случайное
        // движение пальца вверх не давало газ.
        stick(layout, "Руль", 0.155f, 0.60f, 0.30f, "wheel", new String[] { "", "", "LEFT", "RIGHT" });
        button(layout, "Газ", "UP", 0.915f, 0.74f, 0.145f, "↑", "gas");
        button(layout, "Тормоз", "DOWN", 0.745f, 0.86f, 0.115f, "↓", "brake");
        button(layout, "Ручник", "SPACE", 0.60f, 0.80f, 0.10f, "Пробел", "handbrake");
        button(layout, "Передача ↑", "LSHIFT", 0.745f, 0.52f, 0.09f, "Shift", "gearup");
        button(layout, "Передача ↓", "LCTRL", 0.60f, 0.46f, 0.085f, "Ctrl", "geardown");
        button(layout, "Камера", "C", 0.46f, 0.86f, 0.08f, "C", "camera");
        button(layout, "Меню", "ESC", 0.955f, 0.10f, 0.075f, "Esc", "menu");

        return layout;
    }

    private static Layout fighting() {
        Layout layout = start(FIGHTING, "Крестовина слева, четыре удара справа, блок между ними");

        pad(layout, "Ход", 0.14f, 0.64f, 0.28f, new String[] { "W", "S", "A", "D" });
        button(layout, "ПУ", "J", 0.79f, 0.62f, 0.095f, "передний удар", "fist");
        button(layout, "ЗУ", "I", 0.925f, 0.52f, 0.095f, "задний удар", "fist2");
        button(layout, "ПН", "K", 0.79f, 0.88f, 0.095f, "передняя нога", "kick");
        button(layout, "ЗН", "L", 0.925f, 0.80f, 0.095f, "задняя нога", "kick2");
        button(layout, "Блок", "SPACE", 0.60f, 0.86f, 0.10f, "Пробел", "shield");
        button(layout, "Бросок", "U", 0.60f, 0.58f, 0.085f, "U", "grab");
        button(layout, "Меню", "ESC", 0.955f, 0.10f, 0.075f, "Esc", "menu");

        return layout;
    }

    // ------------------------------------------------------------------ сборка ----

    private static Layout start(String name, String note) {
        Layout layout = new Layout();
        layout.name = name;
        layout.note = note;
        return layout;
    }

    private static PadView.Control add(Layout layout, String kind, String label, String hint,
                                       String icon, float x, float y, float r, float w, float h,
                                       String[] keys) {
        PadView.Control control = new PadView.Control();
        control.index = layout.controls.size();
        control.kind = kind;
        control.label = label;
        control.hint = hint;
        control.icon = icon == null ? "" : icon;
        control.x = x;
        control.y = y;
        control.r = r;
        control.w = w;
        control.h = h;

        layout.controls.add(control);
        layout.keys.add(keys);

        return control;
    }

    private static void stick(Layout layout, String label, float x, float y, float r,
                              String icon, String[] keys) {
        add(layout, "stick", label, hint(keys), icon, x, y, r, 0, 0, keys);
    }

    private static void pad(Layout layout, String label, float x, float y, float r, String[] keys) {
        add(layout, "dpad", label, hint(keys), "pad", x, y, r, 0, 0, keys);
    }

    private static void button(Layout layout, String label, String key, float x, float y,
                               float r, String hint, String icon) {
        add(layout, "button", label, hint, icon, x, y, r, 0, 0, new String[] { key });
    }

    private static void look(Layout layout, float x, float y, float w, float h) {
        add(layout, "look", "", "", "", x, y, 0, w, h, new String[0]);
    }

    private static String hint(String[] keys) {
        StringBuilder text = new StringBuilder();

        for (String key : keys) {
            if (key == null || key.isEmpty()) {
                continue;
            }

            if (text.length() > 0) {
                text.append(' ');
            }

            text.append(key);
        }

        return text.toString();
    }

    private ExPads() {
    }
}
