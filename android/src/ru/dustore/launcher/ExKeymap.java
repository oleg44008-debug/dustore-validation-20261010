package ru.dustore.launcher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Клавиатура нашего экрана — описанная нами, а не взятая у чужой системы.
 *
 * Игра, поднявшись, первым делом спрашивает у экрана: «какая у тебя клавиатура?» — и ждёт
 * в ответ раскладку в том виде, в каком её понимает разбор клавиатур (xkb). Обычно этот
 * файл берут из чужого набора данных на диске; здесь он собирается прямо в коде, из одной
 * таблицы: имя кнопки на нашей панели → номер клавиши → буква, которую она печатает.
 *
 * Номер клавиши — это номер из ядра Linux плюс восемь. Так сложилось исторически (первые
 * восемь номеров в X заняты кнопками мыши), и так же его ждёт любая программа: 30 + 8 =
 * 38 — это «A», и никак иначе. Поэтому таблица короткая и проверяемая глазами.
 *
 * Ценность в том, что имена здесь — те же, что на панели управления в игре и те же, что
 * понимает компьютер в eXeportal. Одно имя на все дороги: нажали «Enter» на телефоне —
 * игра получила Enter, играете вы через компьютер или прямо здесь.
 */
final class ExKeymap {

    /** Одна кнопка: как её зовут у нас, её номер в ядре и что она печатает. */
    private static final class Key {

        final int code;
        final String lower;
        final String upper;

        Key(int code, String lower, String upper) {
            this.code = code;
            this.lower = lower;
            this.upper = upper;
        }
    }

    private static final Map<String, Key> KEYS = new LinkedHashMap<>();

    private static void add(String name, int evdev, String lower, String upper) {
        KEYS.put(name, new Key(evdev + 8, lower, upper));
    }

    private static void add(String name, int evdev, String only) {
        add(name, evdev, only, only);
    }

    static {
        add("ESC", 1, "Escape");
        add("1", 2, "1", "exclam");
        add("2", 3, "2", "at");
        add("3", 4, "3", "numbersign");
        add("4", 5, "4", "dollar");
        add("5", 6, "5", "percent");
        add("6", 7, "6", "asciicircum");
        add("7", 8, "7", "ampersand");
        add("8", 9, "8", "asterisk");
        add("9", 10, "9", "parenleft");
        add("0", 11, "0", "parenright");
        add("MINUS", 12, "minus", "underscore");
        add("EQUALS", 13, "equal", "plus");
        add("BACKSPACE", 14, "BackSpace");
        add("TAB", 15, "Tab");
        add("Q", 16, "q", "Q");
        add("W", 17, "w", "W");
        add("E", 18, "e", "E");
        add("R", 19, "r", "R");
        add("T", 20, "t", "T");
        add("Y", 21, "y", "Y");
        add("U", 22, "u", "U");
        add("I", 23, "i", "I");
        add("O", 24, "o", "O");
        add("P", 25, "p", "P");
        add("LBRACKET", 26, "bracketleft", "braceleft");
        add("RBRACKET", 27, "bracketright", "braceright");
        add("ENTER", 28, "Return");
        add("LCTRL", 29, "Control_L");
        add("A", 30, "a", "A");
        add("S", 31, "s", "S");
        add("D", 32, "d", "D");
        add("F", 33, "f", "F");
        add("G", 34, "g", "G");
        add("H", 35, "h", "H");
        add("J", 36, "j", "J");
        add("K", 37, "k", "K");
        add("L", 38, "l", "L");
        add("SEMICOLON", 39, "semicolon", "colon");
        add("QUOTE", 40, "apostrophe", "quotedbl");
        add("GRAVE", 41, "grave", "asciitilde");
        add("LSHIFT", 42, "Shift_L");
        add("BACKSLASH", 43, "backslash", "bar");
        add("Z", 44, "z", "Z");
        add("X", 45, "x", "X");
        add("C", 46, "c", "C");
        add("V", 47, "v", "V");
        add("B", 48, "b", "B");
        add("N", 49, "n", "N");
        add("M", 50, "m", "M");
        add("COMMA", 51, "comma", "less");
        add("PERIOD", 52, "period", "greater");
        add("SLASH", 53, "slash", "question");
        add("RSHIFT", 54, "Shift_R");
        add("LALT", 56, "Alt_L");
        add("SPACE", 57, "space");
        add("CAPSLOCK", 58, "Caps_Lock");
        add("F1", 59, "F1");
        add("F2", 60, "F2");
        add("F3", 61, "F3");
        add("F4", 62, "F4");
        add("F5", 63, "F5");
        add("F6", 64, "F6");
        add("F7", 65, "F7");
        add("F8", 66, "F8");
        add("F9", 67, "F9");
        add("F10", 68, "F10");
        add("F11", 87, "F11");
        add("F12", 88, "F12");
        add("RCTRL", 97, "Control_R");
        add("RALT", 100, "Alt_R");
        add("HOME", 102, "Home");
        add("UP", 103, "Up");
        add("PAGEUP", 104, "Prior");
        add("LEFT", 105, "Left");
        add("RIGHT", 106, "Right");
        add("END", 107, "End");
        add("DOWN", 108, "Down");
        add("PAGEDOWN", 109, "Next");
        add("INSERT", 110, "Insert");
        add("DELETE", 111, "Delete");
    }

    /** Номер клавиши по имени с панели, или -1, если такой кнопки у нас нет. */
    static int code(String name) {
        Key key = KEYS.get(name == null ? "" : name.toUpperCase(java.util.Locale.ROOT));
        return key == null ? -1 : key.code;
    }

    /** Признак клавиши-модификатора — их состояние экран сообщает игре отдельно. */
    static int modifierBit(String name) {
        if (name == null) {
            return 0;
        }

        switch (name.toUpperCase(java.util.Locale.ROOT)) {
            case "LSHIFT":
            case "RSHIFT":
                return 1;      // Shift
            case "CAPSLOCK":
                return 2;      // Lock
            case "LCTRL":
            case "RCTRL":
                return 4;      // Control
            case "LALT":
            case "RALT":
                return 8;      // Mod1 — Alt
            default:
                return 0;
        }
    }

    /**
     * Вся раскладка одной строкой — ровно то, что игра ждёт в ответ на вопрос о
     * клавиатуре.
     *
     * Собирается из таблицы выше, без единого внешнего файла: система, которая берёт
     * раскладку из чужого набора данных, зависит от того, лежит ли он на диске; наша не
     * зависит ни от чего.
     */
    static String text() {
        StringBuilder out = new StringBuilder(8192);

        out.append("xkb_keymap {\n");

        out.append("xkb_keycodes \"ex\" {\n");
        out.append("    minimum = 8;\n    maximum = 255;\n");

        for (Map.Entry<String, Key> entry : KEYS.entrySet()) {
            out.append("    <").append(entry.getKey()).append("> = ")
                    .append(entry.getValue().code).append(";\n");
        }

        out.append("};\n");

        // Три вида поведения на все клавиши: печатающие с большой буквы по Shift и Caps,
        // печатающие вторым знаком только по Shift, и те, у кого знак один.
        out.append("xkb_types \"ex\" {\n"
                + "    virtual_modifiers NumLock,Alt,LevelThree;\n"
                + "    type \"ONE_LEVEL\" {\n"
                + "        modifiers = none;\n"
                + "        map[none] = Level1;\n"
                + "        level_name[Level1] = \"Any\";\n"
                + "    };\n"
                + "    type \"TWO_LEVEL\" {\n"
                + "        modifiers = Shift;\n"
                + "        map[none] = Level1;\n"
                + "        map[Shift] = Level2;\n"
                + "        level_name[Level1] = \"Base\";\n"
                + "        level_name[Level2] = \"Shift\";\n"
                + "    };\n"
                + "    type \"ALPHABETIC\" {\n"
                + "        modifiers = Shift+Lock;\n"
                + "        map[none] = Level1;\n"
                + "        map[Shift] = Level2;\n"
                + "        map[Lock] = Level2;\n"
                + "        level_name[Level1] = \"Base\";\n"
                + "        level_name[Level2] = \"Caps\";\n"
                + "    };\n"
                + "};\n");

        out.append("xkb_compatibility \"ex\" {\n"
                + "    virtual_modifiers NumLock,Alt,LevelThree;\n"
                + "    interpret.useModMapMods= AnyLevel;\n"
                + "    interpret.repeat= False;\n"
                + "    interpret Shift_L+AnyOf(all) {\n"
                + "        action= SetMods(modifiers=Shift,clearLocks);\n"
                + "    };\n"
                + "    interpret Shift_R+AnyOf(all) {\n"
                + "        action= SetMods(modifiers=Shift,clearLocks);\n"
                + "    };\n"
                + "    interpret Control_L+AnyOf(all) {\n"
                + "        action= SetMods(modifiers=Control,clearLocks);\n"
                + "    };\n"
                + "    interpret Control_R+AnyOf(all) {\n"
                + "        action= SetMods(modifiers=Control,clearLocks);\n"
                + "    };\n"
                + "    interpret Alt_L+AnyOf(all) {\n"
                + "        action= SetMods(modifiers=Mod1,clearLocks);\n"
                + "    };\n"
                + "    interpret Alt_R+AnyOf(all) {\n"
                + "        action= SetMods(modifiers=Mod1,clearLocks);\n"
                + "    };\n"
                + "    interpret Caps_Lock+AnyOf(all) {\n"
                + "        action= LockMods(modifiers=Lock);\n"
                + "    };\n"
                + "    interpret Any+Any {\n"
                + "        action= SetMods(modifiers=modMapMods,clearLocks);\n"
                + "    };\n"
                + "};\n");

        out.append("xkb_symbols \"ex\" {\n");
        out.append("    name[Group1] = \"eX\";\n");

        for (Map.Entry<String, Key> entry : KEYS.entrySet()) {
            Key key = entry.getValue();
            String type = key.lower.equals(key.upper)
                    ? "ONE_LEVEL"
                    : (key.lower.length() == 1 && Character.isLetter(key.lower.charAt(0))
                            ? "ALPHABETIC" : "TWO_LEVEL");

            out.append("    key <").append(entry.getKey()).append("> { type= \"")
                    .append(type).append("\", [ ").append(key.lower);

            if (!key.lower.equals(key.upper)) {
                out.append(", ").append(key.upper);
            }

            out.append(" ] };\n");
        }

        out.append("    modifier_map Shift { Shift_L, Shift_R };\n");
        out.append("    modifier_map Lock { Caps_Lock };\n");
        out.append("    modifier_map Control { Control_L, Control_R };\n");
        out.append("    modifier_map Mod1 { Alt_L, Alt_R };\n");
        out.append("};\n");

        out.append("};\n");

        return out.toString();
    }

    private ExKeymap() {
    }
}
