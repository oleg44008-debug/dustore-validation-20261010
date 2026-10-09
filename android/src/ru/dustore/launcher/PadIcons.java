package ru.dustore.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Знаки на кнопках eXepad.
 *
 * Кнопка с надписью «ПН» ничего не говорит человеку, который первый раз взял телефон как
 * джойстик, — а нога в замахе говорит сразу. Поэтому у каждой раскладки свои знаки: в
 * гонках руль, педали и рычаги, в файтинге кулаки и ноги, в Майнкрафте кирка и блок.
 *
 * Рисуются векторами, а не эмодзи: шрифты с эмодзи на телефонах разные, и один и тот же
 * знак выглядел бы у разных людей по-разному, а половина — квадратиком. Здесь же он
 * одинаково резкий на любом экране и любого размера.
 *
 * Имена знаков присылает компьютер (см. JoypadIcons.cs). Незнакомое имя — не беда: тогда
 * на кнопке остаётся её подпись, как было раньше.
 */
final class PadIcons {

    private static final Paint LINE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint SOLID = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path();
    private static final RectF BOX = new RectF();

    static {
        LINE.setStyle(Paint.Style.STROKE);
        LINE.setStrokeCap(Paint.Cap.ROUND);
        LINE.setStrokeJoin(Paint.Join.ROUND);
        LINE.setStrokeMiter(2f);
        SOLID.setStyle(Paint.Style.FILL);
    }

    /** Есть ли у нас такой знак — иначе кнопка обойдётся подписью. */
    static boolean known(String icon) {
        if (icon == null || icon.isEmpty() || "letter".equals(icon)) {
            return false;
        }

        switch (icon) {
            case "stick": case "pad": case "boots": case "jump": case "crouch":
            case "run": case "sneak": case "wheel": case "gas": case "brake":
            case "handbrake": case "gearup": case "geardown": case "camera":
            case "fist": case "fist2": case "kick": case "kick2": case "shield":
            case "grab": case "fire": case "aim": case "reload": case "hand":
            case "torch": case "pick": case "cube": case "bag": case "throw":
            case "menu": case "ok": case "cancel": case "next": case "skip":
            case "eye": case "mouseL": case "mouseR":

            // Знаки интерфейса — не для кнопок пада, а для надписей в приложении: там,
            // где раньше стояли эмодзи. Компьютер их не присылает, но и мешать они не
            // мешают: имена свободные.
            case "gamepad": case "phone": case "monitor": case "keyboard": case "spin": case "search":
            case "up": case "down": case "left": case "right":
            case "triangle": case "square": case "circle": case "cross":
                return true;
            default:
                return false;
        }
    }

    /**
     * Рисует знак в круге радиуса s вокруг точки (cx, cy).
     *
     * @param s половина размера знака — знак вписан в квадрат 2s×2s.
     */
    static synchronized void draw(Canvas canvas, String icon, float cx, float cy, float s, int color) {
        if (icon == null || s <= 0f) return;
        LINE.setColor(color);
        LINE.setStrokeWidth(Math.max(1.25f, s * 0.118f));
        SOLID.setColor(color);

        switch (icon) {
            case "stick": stick(canvas, cx, cy, s); break;
            case "pad": pad(canvas, cx, cy, s); break;
            case "boots": boots(canvas, cx, cy, s); break;
            case "jump": jump(canvas, cx, cy, s); break;
            case "crouch": crouch(canvas, cx, cy, s); break;
            case "run": run(canvas, cx, cy, s); break;
            case "sneak": sneak(canvas, cx, cy, s); break;

            case "wheel": wheel(canvas, cx, cy, s); break;
            case "gas": pedal(canvas, cx, cy, s, true); break;
            case "brake": pedal(canvas, cx, cy, s, false); break;
            case "handbrake": handbrake(canvas, cx, cy, s); break;
            case "gearup": gear(canvas, cx, cy, s, true); break;
            case "geardown": gear(canvas, cx, cy, s, false); break;
            case "camera": camera(canvas, cx, cy, s); break;

            case "fist": fist(canvas, cx, cy, s, false); break;
            case "fist2": fist(canvas, cx, cy, s, true); break;
            case "kick": kick(canvas, cx, cy, s, false); break;
            case "kick2": kick(canvas, cx, cy, s, true); break;
            case "shield": shield(canvas, cx, cy, s); break;
            case "grab": grab(canvas, cx, cy, s); break;

            case "fire": fire(canvas, cx, cy, s); break;
            case "aim": aim(canvas, cx, cy, s); break;
            case "reload": reload(canvas, cx, cy, s); break;
            case "hand": hand(canvas, cx, cy, s); break;
            case "torch": torch(canvas, cx, cy, s); break;

            case "pick": pick(canvas, cx, cy, s); break;
            case "cube": cube(canvas, cx, cy, s); break;
            case "bag": bag(canvas, cx, cy, s); break;
            case "throw": toss(canvas, cx, cy, s); break;

            case "menu": menu(canvas, cx, cy, s); break;
            case "ok": ok(canvas, cx, cy, s); break;
            case "cancel": cancel(canvas, cx, cy, s); break;
            case "next": next(canvas, cx, cy, s, false); break;
            case "skip": next(canvas, cx, cy, s, true); break;
            case "eye": eye(canvas, cx, cy, s); break;

            case "mouseL": mouse(canvas, cx, cy, s, true); break;
            case "mouseR": mouse(canvas, cx, cy, s, false); break;

            case "gamepad": gamepad(canvas, cx, cy, s); break;
            case "phone": phone(canvas, cx, cy, s); break;
            case "monitor": monitor(canvas, cx, cy, s); break;
            case "keyboard": keyboard(canvas, cx, cy, s); break;
            case "spin": spin(canvas, cx, cy, s); break;
            case "search": search(canvas, cx, cy, s); break;

            case "up": arrow(canvas, cx, cy, s * 1.3f, 0); break;
            case "right": arrow(canvas, cx, cy, s * 1.3f, 90); break;
            case "down": arrow(canvas, cx, cy, s * 1.3f, 180); break;
            case "left": arrow(canvas, cx, cy, s * 1.3f, 270); break;
            case "triangle": symbol(canvas, cx, cy, s, 0); break;
            case "square": symbol(canvas, cx, cy, s, 1); break;
            case "circle": symbol(canvas, cx, cy, s, 2); break;
            case "cross": symbol(canvas, cx, cy, s, 3); break;
        }
    }

    /** Console glyphs stay geometric rather than depending on an OEM symbol font. */
    private static void symbol(Canvas canvas, float cx, float cy, float s, int kind) {
        if (kind == 0) {
            PATH.reset(); PATH.moveTo(cx, cy - s * .86f);
            PATH.lineTo(cx + s * .81f, cy + s * .67f); PATH.lineTo(cx - s * .81f, cy + s * .67f);
            PATH.close(); canvas.drawPath(PATH, LINE);
        } else if (kind == 1) {
            BOX.set(cx - s * .72f, cy - s * .72f, cx + s * .72f, cy + s * .72f);
            canvas.drawRoundRect(BOX, s * .055f, s * .055f, LINE);
        } else if (kind == 2) canvas.drawCircle(cx, cy, s * .78f, LINE);
        else {
            canvas.drawLine(cx - s * .62f, cy - s * .62f, cx + s * .62f, cy + s * .62f, LINE);
            canvas.drawLine(cx + s * .62f, cy - s * .62f, cx - s * .62f, cy + s * .62f, LINE);
        }
    }

    // ---------------------------------------------------------------- интерфейс ----

    private static void search(Canvas canvas, float cx, float cy, float s) {
        canvas.drawCircle(cx - s * .18f, cy - s * .18f, s * .58f, LINE);
        canvas.drawLine(cx + s * .26f, cy + s * .26f, cx + s * .80f, cy + s * .80f, LINE);
    }

    /** Геймпад целиком — «телефон вместо джойстика». */
    private static void gamepad(Canvas canvas, float cx, float cy, float s) {
        BOX.set(cx - s * 0.95f, cy - s * 0.45f, cx + s * 0.95f, cy + s * 0.55f);
        canvas.drawRoundRect(BOX, s * 0.40f, s * 0.40f, LINE);

        canvas.drawLine(cx - s * 0.66f, cy + s * 0.05f, cx - s * 0.30f, cy + s * 0.05f, LINE);
        canvas.drawLine(cx - s * 0.48f, cy - s * 0.13f, cx - s * 0.48f, cy + s * 0.23f, LINE);

        canvas.drawCircle(cx + s * 0.34f, cy + s * 0.20f, s * 0.11f, SOLID);
        canvas.drawCircle(cx + s * 0.60f, cy - s * 0.06f, s * 0.11f, SOLID);
    }

    /** Телефон — «здесь, в руке». */
    private static void phone(Canvas canvas, float cx, float cy, float s) {
        BOX.set(cx - s * 0.52f, cy - s * 0.92f, cx + s * 0.52f, cy + s * 0.92f);
        canvas.drawRoundRect(BOX, s * 0.20f, s * 0.20f, LINE);

        canvas.drawLine(cx - s * 0.16f, cy - s * 0.66f, cx + s * 0.16f, cy - s * 0.66f, LINE);
        canvas.drawCircle(cx, cy + s * 0.64f, s * 0.09f, SOLID);
    }

    /** Монитор — «там, на компьютере». */
    private static void monitor(Canvas canvas, float cx, float cy, float s) {
        BOX.set(cx - s * 0.92f, cy - s * 0.80f, cx + s * 0.92f, cy + s * 0.36f);
        canvas.drawRoundRect(BOX, s * 0.16f, s * 0.16f, LINE);

        canvas.drawLine(cx, cy + s * 0.36f, cx, cy + s * 0.66f, LINE);
        canvas.drawLine(cx - s * 0.44f, cy + s * 0.80f, cx + s * 0.44f, cy + s * 0.80f, LINE);
    }

    /** Клавиатура — за ней открывают панель с клавишами, которых нет на паде. */
    private static void keyboard(Canvas canvas, float cx, float cy, float s) {
        BOX.set(cx - s * 0.95f, cy - s * 0.62f, cx + s * 0.95f, cy + s * 0.62f);
        canvas.drawRoundRect(BOX, s * 0.16f, s * 0.16f, LINE);

        for (int i = -1; i <= 1; i++) {
            canvas.drawCircle(cx + i * s * 0.44f, cy - s * 0.22f, s * 0.08f, SOLID);
        }

        canvas.drawLine(cx - s * 0.44f, cy + s * 0.26f, cx + s * 0.44f, cy + s * 0.26f, LINE);
    }

    /** Круговая стрелка — поворот телефона рулём. */
    private static void spin(Canvas canvas, float cx, float cy, float s) {
        BOX.set(cx - s * 0.72f, cy - s * 0.72f, cx + s * 0.72f, cy + s * 0.72f);
        canvas.drawArc(BOX, 120, 280, false, LINE);

        // Наконечник на конце дуги: без него это просто разорванный круг.
        canvas.save();
        canvas.rotate(40, cx, cy);
        canvas.rotate(90, cx + s * 0.72f, cy);
        triangle(canvas, cx + s * 0.72f, cy, s * 0.34f);
        canvas.restore();
    }

    // ------------------------------------------------------------------ движение ----

    private static void stick(Canvas canvas, float cx, float cy, float s) {
        canvas.drawCircle(cx, cy, s * 0.9f, LINE);
        canvas.drawCircle(cx, cy, s * 0.32f, SOLID);
    }

    private static void pad(Canvas canvas, float cx, float cy, float s) {
        outline(canvas, cx, cy, s, 9, 3, 15, 3, 15, 9, 21, 9, 21, 15, 15, 15,
                15, 21, 9, 21, 9, 15, 3, 15, 3, 9, 9, 9, 9, 3);
    }

    private static void boots(Canvas canvas, float cx, float cy, float s) {
        foot(canvas, cx - s * 0.38f, cy + s * 0.18f, s * 0.5f);
        foot(canvas, cx + s * 0.36f, cy - s * 0.24f, s * 0.5f);
    }

    private static void foot(Canvas canvas, float cx, float cy, float s) {
        BOX.set(cx - s * 0.42f, cy - s * 0.9f, cx + s * 0.42f, cy + s * 0.55f);
        canvas.drawRoundRect(BOX, s * 0.42f, s * 0.42f, SOLID);
        canvas.drawCircle(cx, cy + s * 0.85f, s * 0.26f, SOLID);
    }

    private static void jump(Canvas canvas, float cx, float cy, float s) {
        arrow(canvas, cx, cy - s * 0.15f, s * 1.05f, 0);
        canvas.drawLine(cx - s * 0.7f, cy + s * 0.78f, cx + s * 0.7f, cy + s * 0.78f, LINE);
    }

    private static void crouch(Canvas canvas, float cx, float cy, float s) {
        canvas.drawCircle(cx + s * 0.18f, cy - s * 0.68f, s * 0.16f, LINE);
        outline(canvas, cx, cy, s, 12, 8, 8, 13, 15, 15, 17, 21);
        outline(canvas, cx, cy, s, 12, 8, 17, 12, 21, 12);
        outline(canvas, cx, cy, s, 8, 13, 5, 20, 10, 20);
    }

    private static void run(Canvas canvas, float cx, float cy, float s) {
        canvas.drawCircle(cx + s * 0.18f, cy - s * 0.68f, s * 0.16f, LINE);
        outline(canvas, cx, cy, s, 13, 8, 10, 14, 16, 17, 15, 22);
        outline(canvas, cx, cy, s, 10, 14, 5, 20, 2, 20);
        outline(canvas, cx, cy, s, 13, 8, 17, 12, 21, 10);
        outline(canvas, cx, cy, s, 13, 8, 8, 8, 5, 12);
        outline(canvas, cx, cy, s, 2, 5, 7, 5);
    }

    private static void sneak(Canvas canvas, float cx, float cy, float s) {
        // Согнутая фигура: голова и низкая дуга спины — «красться».
        canvas.drawCircle(cx - s * 0.45f, cy - s * 0.25f, s * 0.26f, SOLID);

        PATH.reset();
        PATH.moveTo(cx - s * 0.2f, cy - s * 0.05f);
        PATH.quadTo(cx + s * 0.35f, cy - s * 0.5f, cx + s * 0.7f, cy + s * 0.35f);
        canvas.drawPath(PATH, LINE);

        canvas.drawLine(cx - s * 0.75f, cy + s * 0.75f, cx + s * 0.75f, cy + s * 0.75f, LINE);
    }

    // -------------------------------------------------------------------- машина ----

    /** Руль: обод, ступица и три спицы — как в машине. */
    private static void wheel(Canvas canvas, float cx, float cy, float s) {
        canvas.drawCircle(cx, cy, s * 0.92f, LINE);
        canvas.drawCircle(cx, cy, s * 0.26f, SOLID);

        canvas.drawLine(cx - s * 0.9f, cy, cx - s * 0.26f, cy, LINE);
        canvas.drawLine(cx + s * 0.9f, cy, cx + s * 0.26f, cy, LINE);
        canvas.drawLine(cx, cy + s * 0.9f, cx, cy + s * 0.26f, LINE);
    }

    /** Педаль: наклонная площадка с рёбрами. Газ — узкая, тормоз — широкая. */
    private static void pedal(Canvas canvas, float cx, float cy, float s, boolean gas) {
        float half = gas ? s * 0.34f : s * 0.5f;

        PATH.reset();
        PATH.moveTo(cx - half, cy - s * 0.75f);
        PATH.lineTo(cx + half, cy - s * 0.45f);
        PATH.lineTo(cx + half, cy + s * 0.8f);
        PATH.lineTo(cx - half, cy + s * 0.5f);
        PATH.close();
        canvas.drawPath(PATH, LINE);

        for (int i = 0; i < 3; i++) {
            float t = -0.3f + i * 0.35f;
            canvas.drawLine(cx - half * 0.6f, cy + s * t, cx + half * 0.6f, cy + s * (t + 0.14f), LINE);
        }
    }

    /** Рычаг ручника: основание и наклонная ручка с шариком. */
    private static void handbrake(Canvas canvas, float cx, float cy, float s) {
        canvas.drawLine(cx - s * 0.65f, cy + s * 0.8f, cx + s * 0.5f, cy + s * 0.8f, LINE);

        PATH.reset();
        PATH.moveTo(cx - s * 0.4f, cy + s * 0.75f);
        PATH.quadTo(cx + s * 0.1f, cy + s * 0.3f, cx + s * 0.45f, cy - s * 0.55f);
        canvas.drawPath(PATH, LINE);

        canvas.drawCircle(cx + s * 0.5f, cy - s * 0.7f, s * 0.26f, SOLID);
    }

    /** Кулиса передач: шарик на стержне и стрелка, куда её тянуть. */
    private static void gear(Canvas canvas, float cx, float cy, float s, boolean up) {
        canvas.drawLine(cx - s * 0.15f, cy + s * 0.8f, cx - s * 0.15f, cy - s * 0.3f, LINE);
        canvas.drawCircle(cx - s * 0.15f, cy - s * 0.5f, s * 0.26f, SOLID);
        arrow(canvas, cx + s * 0.55f, cy + (up ? -s * 0.05f : s * 0.15f), s * 0.7f, up ? 0 : 180);
    }

    private static void camera(Canvas canvas, float cx, float cy, float s) {
        BOX.set(cx - s * 0.85f, cy - s * 0.45f, cx + s * 0.85f, cy + s * 0.6f);
        canvas.drawRoundRect(BOX, s * 0.2f, s * 0.2f, LINE);
        canvas.drawCircle(cx, cy + s * 0.08f, s * 0.3f, LINE);
        canvas.drawLine(cx - s * 0.4f, cy - s * 0.55f, cx + s * 0.1f, cy - s * 0.55f, LINE);
    }

    // ----------------------------------------------------------------------- бой ----

    /** Кулак: костяшки и большой палец. Тяжёлый удар — с дугой замаха. */
    private static void fist(Canvas canvas, float cx, float cy, float s, boolean heavy) {
        outline(canvas, cx, cy, s, 5, 12, 5, 6, 8, 4, 18, 4, 20, 7, 20, 13,
                17, 19, 8, 19, 4, 15, 4, 12, 9, 12, 12, 9);
        outline(canvas, cx, cy, s, 9, 5, 9, 8);
        outline(canvas, cx, cy, s, 13, 5, 13, 8);
        outline(canvas, cx, cy, s, 17, 5, 17, 8);
        if (heavy) {
            outline(canvas, cx, cy, s, 1, 7, 2, 5);
            outline(canvas, cx, cy, s, 3, 2, 5, 1);
        }
    }

    private static void kick(Canvas canvas, float cx, float cy, float s, boolean heavy) {
        outline(canvas, cx, cy, s, 8, 3, 14, 3, 14, 10, 20, 15, 21, 19, 17, 21,
                8, 16, 5, 12, 8, 3);
        outline(canvas, cx, cy, s, 8, 16, 17, 18, 21, 18);
        if (heavy) {
            outline(canvas, cx, cy, s, 18, 5, 22, 3);
            outline(canvas, cx, cy, s, 20, 9, 23, 8);
        }
    }

    private static void shield(Canvas canvas, float cx, float cy, float s) {
        PATH.reset();
        PATH.moveTo(cx, cy - s * 0.85f);
        PATH.lineTo(cx + s * 0.75f, cy - s * 0.5f);
        PATH.lineTo(cx + s * 0.75f, cy + s * 0.2f);
        PATH.quadTo(cx + s * 0.6f, cy + s * 0.75f, cx, cy + s * 0.9f);
        PATH.quadTo(cx - s * 0.6f, cy + s * 0.75f, cx - s * 0.75f, cy + s * 0.2f);
        PATH.lineTo(cx - s * 0.75f, cy - s * 0.5f);
        PATH.close();
        canvas.drawPath(PATH, LINE);
    }

    /** Захват: две руки навстречу друг другу. */
    private static void grab(Canvas canvas, float cx, float cy, float s) {
        BOX.set(cx - s * 0.9f, cy - s * 0.35f, cx - s * 0.1f, cy + s * 0.35f);
        canvas.drawRoundRect(BOX, s * 0.2f, s * 0.2f, SOLID);

        BOX.set(cx + s * 0.1f, cy - s * 0.35f, cx + s * 0.9f, cy + s * 0.35f);
        canvas.drawRoundRect(BOX, s * 0.2f, s * 0.2f, SOLID);

        arrow(canvas, cx - s * 0.02f, cy - s * 0.62f, s * 0.44f, 180);
    }

    // -------------------------------------------------------------- оружие и руки ----

    private static void fire(Canvas canvas, float cx, float cy, float s) {
        outline(canvas, cx, cy, s, 3, 7, 20, 7, 20, 11, 12, 11, 9, 20, 4, 20,
                7, 11, 3, 11, 3, 7);
        outline(canvas, cx, cy, s, 13, 11, 13, 15, 10, 15);
    }

    private static void aim(Canvas canvas, float cx, float cy, float s) {
        canvas.drawCircle(cx, cy, s * 0.85f, LINE);
        canvas.drawCircle(cx, cy, s * 0.4f, LINE);
        canvas.drawLine(cx, cy - s, cx, cy - s * 0.6f, LINE);
        canvas.drawLine(cx, cy + s * 0.6f, cx, cy + s, LINE);
        canvas.drawLine(cx - s, cy, cx - s * 0.6f, cy, LINE);
        canvas.drawLine(cx + s * 0.6f, cy, cx + s, cy, LINE);
    }

    /** Перезарядка: круговая стрелка. */
    private static void reload(Canvas canvas, float cx, float cy, float s) {
        BOX.set(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f);
        canvas.drawArc(BOX, 40, 280, false, LINE);
        arrow(canvas, cx + s * 0.62f, cy + s * 0.58f, s * 0.5f, 45);
    }

    private static void hand(Canvas canvas, float cx, float cy, float s) {
        outline(canvas, cx, cy, s, 7, 12, 7, 5, 10, 5, 10, 11, 10, 2, 13, 2,
                13, 11, 13, 5, 16, 5, 16, 12, 16, 8, 19, 8, 19, 17, 16, 22,
                9, 22, 4, 17, 3, 13, 5, 12, 9, 16);
    }

    private static void torch(Canvas canvas, float cx, float cy, float s) {
        BOX.set(cx - s * 0.9f, cy - s * 0.3f, cx - s * 0.25f, cy + s * 0.3f);
        canvas.drawRoundRect(BOX, s * 0.12f, s * 0.12f, SOLID);

        PATH.reset();
        PATH.moveTo(cx - s * 0.2f, cy - s * 0.25f);
        PATH.lineTo(cx + s * 0.9f, cy - s * 0.75f);
        PATH.lineTo(cx + s * 0.9f, cy + s * 0.75f);
        PATH.lineTo(cx - s * 0.2f, cy + s * 0.25f);
        PATH.close();
        canvas.drawPath(PATH, LINE);
    }

    // ------------------------------------------------------------------ Майнкрафт ----

    private static void pick(Canvas canvas, float cx, float cy, float s) {
        canvas.drawLine(cx - s * 0.55f, cy + s * 0.85f, cx + s * 0.35f, cy - s * 0.55f, LINE);

        PATH.reset();
        PATH.moveTo(cx - s * 0.55f, cy - s * 0.35f);
        PATH.quadTo(cx + s * 0.1f, cy - s * 0.95f, cx + s * 0.9f, cy - s * 0.55f);
        canvas.drawPath(PATH, LINE);
    }

    /** Блок в изометрии — тот самый куб. */
    private static void cube(Canvas canvas, float cx, float cy, float s) {
        PATH.reset();
        PATH.moveTo(cx, cy - s * 0.9f);
        PATH.lineTo(cx + s * 0.85f, cy - s * 0.45f);
        PATH.lineTo(cx + s * 0.85f, cy + s * 0.45f);
        PATH.lineTo(cx, cy + s * 0.9f);
        PATH.lineTo(cx - s * 0.85f, cy + s * 0.45f);
        PATH.lineTo(cx - s * 0.85f, cy - s * 0.45f);
        PATH.close();
        canvas.drawPath(PATH, LINE);

        canvas.drawLine(cx, cy - s * 0.9f, cx, cy, LINE);
        canvas.drawLine(cx, cy, cx - s * 0.85f, cy + s * 0.45f, LINE);
        canvas.drawLine(cx, cy, cx + s * 0.85f, cy + s * 0.45f, LINE);
    }

    private static void bag(Canvas canvas, float cx, float cy, float s) {
        BOX.set(cx - s * 0.75f, cy - s * 0.35f, cx + s * 0.75f, cy + s * 0.85f);
        canvas.drawRoundRect(BOX, s * 0.22f, s * 0.22f, LINE);

        BOX.set(cx - s * 0.4f, cy - s * 0.85f, cx + s * 0.4f, cy - s * 0.1f);
        canvas.drawArc(BOX, 180, 180, false, LINE);

        canvas.drawLine(cx - s * 0.75f, cy + s * 0.2f, cx + s * 0.75f, cy + s * 0.2f, LINE);
    }

    private static void toss(Canvas canvas, float cx, float cy, float s) {
        PATH.reset();
        PATH.moveTo(cx - s * 0.85f, cy + s * 0.6f);
        PATH.quadTo(cx, cy - s * 1.1f, cx + s * 0.8f, cy + s * 0.2f);
        canvas.drawPath(PATH, LINE);

        canvas.drawCircle(cx + s * 0.82f, cy + s * 0.45f, s * 0.28f, SOLID);
    }

    // -------------------------------------------------------------- меню и ответы ----

    private static void menu(Canvas canvas, float cx, float cy, float s) {
        for (int i = -1; i <= 1; i++) {
            canvas.drawLine(cx - s * 0.75f, cy + i * s * 0.5f, cx + s * 0.75f, cy + i * s * 0.5f, LINE);
        }
    }

    private static void ok(Canvas canvas, float cx, float cy, float s) {
        PATH.reset();
        PATH.moveTo(cx - s * 0.8f, cy);
        PATH.lineTo(cx - s * 0.2f, cy + s * 0.6f);
        PATH.lineTo(cx + s * 0.8f, cy - s * 0.65f);
        canvas.drawPath(PATH, LINE);
    }

    private static void cancel(Canvas canvas, float cx, float cy, float s) {
        canvas.drawLine(cx - s * 0.7f, cy - s * 0.7f, cx + s * 0.7f, cy + s * 0.7f, LINE);
        canvas.drawLine(cx + s * 0.7f, cy - s * 0.7f, cx - s * 0.7f, cy + s * 0.7f, LINE);
    }

    private static void next(Canvas canvas, float cx, float cy, float s, boolean twice) {
        float shift = twice ? s * 0.42f : 0f;
        triangle(canvas, cx - shift, cy, s * 0.72f);

        if (twice) {
            triangle(canvas, cx + shift, cy, s * 0.72f);
        }
    }

    private static void eye(Canvas canvas, float cx, float cy, float s) {
        PATH.reset();
        PATH.moveTo(cx - s * 0.9f, cy);
        PATH.quadTo(cx, cy - s * 0.85f, cx + s * 0.9f, cy);
        PATH.quadTo(cx, cy + s * 0.85f, cx - s * 0.9f, cy);
        PATH.close();
        canvas.drawPath(PATH, LINE);

        canvas.drawCircle(cx, cy, s * 0.28f, SOLID);
    }

    private static void mouse(Canvas canvas, float cx, float cy, float s, boolean left) {
        BOX.set(cx - s * 0.55f, cy - s * 0.85f, cx + s * 0.55f, cy + s * 0.85f);
        canvas.drawRoundRect(BOX, s * 0.55f, s * 0.55f, LINE);

        // Закрашена та половина, которую эта кнопка и нажимает.
        PATH.reset();
        if (left) {
            PATH.addRect(cx - s * 0.55f, cy - s * 0.85f, cx, cy - s * 0.05f, Path.Direction.CW);
        } else {
            PATH.addRect(cx, cy - s * 0.85f, cx + s * 0.55f, cy - s * 0.05f, Path.Direction.CW);
        }

        canvas.save();
        canvas.clipPath(PATH);
        BOX.set(cx - s * 0.55f, cy - s * 0.85f, cx + s * 0.55f, cy + s * 0.85f);
        canvas.drawRoundRect(BOX, s * 0.55f, s * 0.55f, SOLID);
        canvas.restore();

        canvas.drawLine(cx, cy - s * 0.85f, cx, cy - s * 0.05f, LINE);
    }

    // ------------------------------------------------------------------- мелочи ----

    /** Стрелка: 0° — вверх, дальше по часовой. */
    private static void arrow(Canvas canvas, float cx, float cy, float s, float degrees) {
        canvas.save();
        canvas.rotate(degrees, cx, cy);

        canvas.drawLine(cx, cy + s * 0.55f, cx, cy - s * 0.55f, LINE);
        canvas.drawLine(cx - s * 0.4f, cy - s * 0.15f, cx, cy - s * 0.6f, LINE);
        canvas.drawLine(cx + s * 0.4f, cy - s * 0.15f, cx, cy - s * 0.6f, LINE);

        canvas.restore();
    }

    private static void chevron(Canvas canvas, float cx, float cy, float s) {
        canvas.drawLine(cx - s * 0.35f, cy - s * 0.8f, cx + s * 0.35f, cy, LINE);
        canvas.drawLine(cx + s * 0.35f, cy, cx - s * 0.35f, cy + s * 0.8f, LINE);
    }

    private static void triangle(Canvas canvas, float cx, float cy, float s) {
        PATH.reset();
        PATH.moveTo(cx - s * 0.5f, cy - s * 0.8f);
        PATH.lineTo(cx + s * 0.7f, cy);
        PATH.lineTo(cx - s * 0.5f, cy + s * 0.8f);
        PATH.close();
        canvas.drawPath(PATH, SOLID);
    }

    /** A common 24-unit optical grid keeps the detailed action icons equally sized. */
    private static void outline(Canvas canvas, float cx, float cy, float s, float... coordinates) {
        PATH.reset();
        PATH.moveTo(cx + (coordinates[0] - 12f) * s / 12f, cy + (coordinates[1] - 12f) * s / 12f);
        for (int i = 2; i < coordinates.length; i += 2)
            PATH.lineTo(cx + (coordinates[i] - 12f) * s / 12f, cy + (coordinates[i + 1] - 12f) * s / 12f);
        canvas.drawPath(PATH, LINE);
    }
    private PadIcons() {
    }
}
