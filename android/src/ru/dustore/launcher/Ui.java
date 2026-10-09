package ru.dustore.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Цвета, кегли, отступы и мелкие детали оформления — в одном месте.
 *
 * Prime native materials: obsidian surfaces, lavender selection and a citrus primary action.
 * Gradients are cached per drawable size; motion runs only during a user interaction.
 */
final class Ui {

    // ------------------------------------------------------------------ палитра ----

    /** Тёмный синий фон приложения. */
    static final int BACK = Prime.color("BACK", "#0B0D14");
    /** На полступени светлее фона: полосы, шапки, подложки диалогов. */
    static final int BACK_SOFT = Prime.color("BACK_SOFT", "#111420");

    static final int CARD = Prime.color("CARD", "#181C2B");
    static final int CARD_HOVER = Prime.color("CARD_HOVER", "#23283B");
    static final int CARD_PRESSED = Prime.color("CARD_PRESSED", "#101320");

    static final int LINE = Prime.color("LINE", "#333A52");
    static final int LINE_SOFT = Prime.color("LINE_SOFT", "#282E43");
    static final int LINE_STRONG = Prime.color("LINE_STRONG", "#535D7E");

    static final int TEXT = Color.parseColor("#F5F3FF");
    static final int MUTED = Color.parseColor("#C7C4D8");
    /** Только разделители и «водяные» надписи. Для абзаца слишком бледный. */
    static final int DIM = Color.parseColor("#9394AE");
    /** Абзацы текста: то же настроение, что DIM, но читается с руки. */
    static final int DIM_FIXED = Color.parseColor("#B1B3CB");

    /** Лавандовый акцент выделения. */
    static final int ACCENT = Prime.color("ACCENT", "#B7A4FF");
    static final int ACCENT_HOVER = Prime.color("ACCENT_HOVER", "#D3C7FF");
    static final int ACCENT_PRESSED = Prime.color("ACCENT_PRESSED", "#9580DE");
    /** Тёмный текст на лавандовой заливке. */
    static final int ACCENT_INK = Prime.color("ACCENT_INK", "#17122F");
    /** Приглушённая подложка акцентных элементов. */
    static final int ACCENT_SUBTLE = Prime.color("ACCENT_SUBTLE", "#29223E");

    /** Цитрусовая заливка основного действия. */
    static final int ACTION = Color.parseColor("#E5F58E");
    static final int ACTION_PRESSED = Color.parseColor("#C9DF6B");
    static final int ACTION_INK = Color.parseColor("#17200A");

    static final int INFO = Color.parseColor("#9DB8FA");
    static final int SUCCESS = Color.parseColor("#81DFAB");
    static final int WARNING = Color.parseColor("#E8C47A");
    static final int DANGER = Color.parseColor("#F08395");
    static final int FOCUS = Color.parseColor("#D3C7FF");

    // -------------------------------------------------------------------- кегли ----
    //
    // Шесть ступеней, и ни одной сверх того. Каждый лишний кегль — это ещё одна причина
    // для двух надписей выглядеть по-разному без повода.

    /** Имя приложения на своём экране: eXepad, eX. */
    static final int T_DISPLAY = 30;
    /** Заголовок экрана: «Библиотека», «Отзывы». */
    static final int T_TITLE = 21;
    /** Заголовок карточки и раздела. */
    static final int T_HEAD = 17;
    /** Основной текст и надписи на кнопках. */
    static final int T_BODY = 15;
    /** Подписи, состояния, второстепенные действия. */
    static final int T_LABEL = 13;
    /** Самое мелкое, что здесь бывает. Ниже 12sp абзацы не опускаются. */
    static final int T_MICRO = 12;

    // ------------------------------------------------------------------ отступы ----
    //
    // 4 / 8 / 12 / 16 / 24 / 32 — всё остальное подгоняется к ближайшей ступени.

    static final int S1 = 4;
    static final int S2 = 8;
    static final int S3 = 12;
    static final int S4 = 16;
    static final int S5 = 24;
    static final int S6 = 32;

    /** Скругление карточек и панелей. */
    static final int R_CARD = 20;
    /** Скругление кнопок и полей. */
    static final int R_BUTTON = 16;

    /** Палец накрывает примерно столько; меньше нажимаемое не делаем. */
    static final int TOUCH = 48;

    // ------------------------------------- eXepad: кнопки поверх чужой картинки ----
    //
    // Пад лежит на кадре игры, а кадр бывает каким угодно: заснеженное поле и подвал
    // светят по-разному. Поэтому у каждой кнопки два контура — светлый и тёмный — и
    // тень: что-то одно из этого видно на любой сцене.

    /** Пустой экран пада — темнее фона приложения, чтобы кадр казался ярче. */
    static final int PAD_VOID = blend(BACK, Color.BLACK, 0.45f);
    static final int PAD_INK = TEXT;
    static final int PAD_INK_SOFT = alpha(MUTED, 0xC0);
    static final int PAD_INK_DIM = MUTED;

    /** Стекло кнопки: холодная темнота, сквозь которую видно игру. */
    static final int PAD_GLASS = alpha(blend(BACK, Color.BLACK, 0.25f), 0x73);
    static final int PAD_GLASS_HELD = alpha(ACCENT, 0xE0);
    /** Заливка основания стика и руля — прозрачнее кнопки: под ним важнее видеть игру. */
    static final int PAD_BASE = alpha(blend(BACK, Color.BLACK, 0.25f), 0x33);
    static final int PAD_BASE_HELD = alpha(ACCENT, 0x40);

    /** Светлый контур — читается на тёмной сцене. */
    static final int PAD_RIM = alpha(TEXT, 0x99);
    /** Тёмный контур под светлым — читается на светлой. */
    static final int PAD_RIM_DARK = 0x73000000;
    static final int PAD_RIM_HELD = ACCENT_HOVER;
    static final int PAD_RIM_SOFT = alpha(TEXT, 0x40);
    static final int PAD_RIM_SOFT_HELD = alpha(ACCENT, 0x99);

    /** Ореол вокруг нажатой кнопки: палец закрывает её саму, ореол — нет. */
    static final int PAD_HALO = alpha(ACCENT_HOVER, 0x66);
    static final int PAD_SHADOW = 0x66000000;
    static final int PAD_SHADOW_SOFT = 0x50000000;
    static final int PAD_GLOSS = alpha(Color.WHITE, 0x1A);
    static final int PAD_MARK = alpha(TEXT, 0x40);

    /** Шарик стика — светлый: его ищут глазами первым. */
    static final int PAD_KNOB = alpha(ACCENT, 0xE6);
    static final int PAD_KNOB_HELD = Color.WHITE;
    static final int PAD_ICON = alpha(TEXT, 0xCC);
    static final int PAD_ICON_HELD = ACCENT_INK;

    private Ui() {
    }

    // ------------------------------------------------------------------- цифры ----

    static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** Тот же цвет, но с другой прозрачностью. */
    static int alpha(int color, int value) {
        return (value << 24) | (color & 0x00FFFFFF);
    }

    /**
     * Смесь двух цветов.
     *
     * Нужна ради градиентов: разница между верхом и низом карточки должна быть в два-три
     * пункта яркости, а не в целый оттенок. Брать для этого новые константы — значит
     * развести палитру телефона и компьютера, чего делать нельзя.
     */
    static int blend(int from, int to, float amount) {
        float k = Math.max(0f, Math.min(1f, amount));

        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * k);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * k);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * k);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k);

        return Color.argb(a, r, g, b);
    }

    // ------------------------------------------------------------------- фигуры ----

    /**
     * Карточка: тёмный прямоугольник с рамкой и скруглением.
     *
     * Осталась ради полей ввода и всего, чему хватает простой подложки. Настоящие
     * карточки собирает {@link #panel}: у них есть светлая кромка сверху.
     */
    static GradientDrawable card(Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(CARD);
        shape.setStroke(dp(context, 1), LINE_SOFT);
        shape.setCornerRadius(dp(context, R_CARD));
        return shape;
    }

    static GradientDrawable rounded(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    /**
     * Карточка со слоями: мягкий вертикальный градиент, рамка, светлая кромка сверху и
     * тень под нижним краем.
     *
     * Разница между верхом и низом — два-три пункта яркости: этого хватает, чтобы
     * поверхность перестала быть плоским прямоугольником, и мало, чтобы кто-нибудь
     * заметил сам градиент.
     */
    static Drawable panel(Context context) {
        return panel(context, R_CARD, CARD, LINE);
    }

    static Drawable panel(Context context, int radius, int base, int line) {
        return new Panel(dp(context, radius), dp(context, 1), blend(base, CARD_HOVER, .35f), base,
                line == LINE ? LINE_SOFT : line, alpha(TEXT, 0x1A), alpha(Color.BLACK, 0x33));
    }

    /** Glass-inspired material for navigation and secondary controls, not game content. */
    static Drawable glass(Context context, int radius, boolean selected) {
        return selected ? panel(context, radius, ACCENT_SUBTLE, alpha(ACCENT, 0x85))
                : panel(context, radius, CARD, LINE_SOFT);
    }

    /** Выбранная вкладка: короткая розовая черта сверху, как кромка выбранной обложки на ПК. */
    static Drawable topMark(Context context) {
        GradientDrawable line = new GradientDrawable();
        line.setColor(ACCENT);
        line.setCornerRadius(dp(context, 2));
        android.graphics.drawable.LayerDrawable layer =
                new android.graphics.drawable.LayerDrawable(new Drawable[] { line });
        layer.setLayerGravity(0, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        layer.setLayerSize(0, dp(context, 28), dp(context, 3));
        return layer;
    }

    /** Заголовок экрана: узкий жирный шрифт заглавными, как на компьютере. */
    static TextView display(Context context, String value, int size) {
        TextView view = text(context, value, size, TEXT);
        view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        view.setLetterSpacing(-0.025f);
        return view;
    }

    /** Панель без кромки: шапки, полосы вкладок, подложки под содержимым. */
    static Drawable bar(Context context, boolean lineOnTop) {
        return new Edge(BACK_SOFT, LINE_SOFT, dp(context, 1), lineOnTop);
    }

    /** Поле ввода: чуть темнее карточки, чтобы было видно, что сюда пишут. */
    static Drawable field(Context context) {
        StateListDrawable list = new StateListDrawable();
        list.addState(new int[] { android.R.attr.state_focused },
                flat(context, CARD_PRESSED, ACCENT, R_BUTTON, 1));
        list.addState(new int[0], flat(context, CARD_PRESSED, LINE, R_BUTTON, 1));
        return list;
    }

    /** Подложка диалога: панель, а не окно во всю ширину экрана. */
    static Drawable sheet(Context context) {
        Panel shape = new Panel(dp(context, R_CARD + 4), dp(context, 1),
                blend(BACK_SOFT, CARD, 0.5f), BACK_SOFT, LINE,
                alpha(TEXT, 0x14), alpha(Color.BLACK, 0x3A));

        return new InsetDrawable(shape, dp(context, S3), dp(context, S5),
                dp(context, S3), dp(context, S5));
    }

    /** Пилюля-метка: точка и слово. Цвет говорит, а не кричит. */
    static TextView chip(Context context, String label, int color) {
        TextView view = text(context, "•  " + label, T_MICRO, color);
        view.setPadding(dp(context, S2), dp(context, S1) + dp(context, 1),
                dp(context, S2 + S1), dp(context, S1) + dp(context, 1));

        GradientDrawable shape = new GradientDrawable();
        shape.setColor(alpha(color, 0x1F));
        shape.setStroke(dp(context, 1), alpha(color, 0x3D));
        shape.setCornerRadius(dp(context, R_BUTTON));
        view.setBackground(shape);

        return view;
    }

    /**
     * Кружок выбора: пустой или закрашенный.
     *
     * Нужен там, где выбирают один ответ из нескольких. Раньше выбранный вариант просто
     * заливался оранжевым во всю строку — пять таких подряд превращали вопрос в
     * светофор, а белые буквы на оранжевом ещё и не читались.
     */
    static Drawable radio(Context context, boolean on) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(context, 2), on ? ACCENT : LINE_STRONG);
        ring.setSize(dp(context, S5 - S1), dp(context, S5 - S1));

        if (!on) {
            return ring;
        }

        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ACCENT);

        android.graphics.drawable.LayerDrawable both =
                new android.graphics.drawable.LayerDrawable(new Drawable[] { ring, dot });
        both.setLayerInset(1, dp(context, 5), dp(context, 5), dp(context, 5), dp(context, 5));

        return both;
    }

    /** Волосяная линия — там, где разделить нужно, а рисовать рамку не за что. */
    static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(LINE_SOFT);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1))));
        return line;
    }

    /** Пустое место заданной высоты: воздух — тоже часть разметки. */
    static View gap(Context context, int height) {
        View empty = new View(context);
        empty.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, height)));
        return empty;
    }

    /**
     * Поднимает карточку над фоном настоящей тенью.
     *
     * На тёмном тень почти не видна — и хорошо: её задача не темнить, а отделить край
     * карточки от края экрана, когда они рядом.
     */
    static void lift(View view, final int radius) {
        final Context context = view.getContext();

        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View target, Outline outline) {
                outline.setRoundRect(0, 0, target.getWidth(), target.getHeight(),
                        dp(context, radius));
            }
        });

        view.setClipToOutline(false);
        view.setElevation(dp(context, 3));
        if (Build.VERSION.SDK_INT >= 28) {
            view.setOutlineAmbientShadowColor(alpha(Color.BLACK, 0x66));
            view.setOutlineSpotShadowColor(alpha(Color.BLACK, 0x88));
        }
    }

    /**
     * Обрезает содержимое по скруглённым углам.
     *
     * Нужно обложкам: своя плитка рисует скругление сама, а настоящий значок игры
     * приходит из системы прямоугольным. Без обрезки половина списка была бы с
     * закруглёнными углами, а половина — с острыми.
     */
    static void roundCorners(View view, final int radius) {
        final Context context = view.getContext();

        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View target, Outline outline) {
                outline.setRoundRect(0, 0, target.getWidth(), target.getHeight(),
                        dp(context, radius));
            }
        });

        view.setClipToOutline(true);
    }

    // -------------------------------------------------------------------- текст ----

    static TextView text(Context context, String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setIncludeFontPadding(false);
        view.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        return view;
    }

    /** Заголовок: тот же текст, но набранный так, что его видно первым. */
    static TextView title(Context context, String value, int size, int color) {
        TextView view = text(context, value, size, color);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    /** Quiet studio signature for native screens; never laid over game art or actions. */
    static TextView brandMark(Context context) {
        TextView mark = text(context, "eXepc", T_MICRO, DIM);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setLetterSpacing(0.08f);
        mark.setGravity(Gravity.CENTER_VERTICAL);
        return mark;
    }

    /**
     * Абзац: межстрочный интервал и цвет, которым читают, а не подписывают.
     *
     * Сплошной текст в 11sp серым по тёмному — это текст, который пролистывают. Если его
     * стоило написать, его стоит и прочитать.
     */
    static TextView paragraph(Context context, String value, int size, int color) {
        TextView view = text(context, value, Math.max(T_MICRO, size), color);
        view.setLineSpacing(dp(context, S1), 1f);
        return view;
    }

    // ------------------------------------------------------------------- кнопки ----

    /**
     * Кнопка-надпись.
     *
     * Обычная Button на Android приносит с собой системный фон и заглавные буквы — рядом
     * с тёмными карточками это выглядит как чужая деталь. Проще нарисовать своё.
     *
     * Своё, впрочем, обязано вести себя как кнопка. Раньше здесь была одна картинка на все
     * случаи: {@code setEnabled(false)} не менял ровным счётом ничего, и «Уже здесь» на
     * вкладке «С компьютера» выглядела ровно как рабочая кнопка — нажимаешь, и ничего.
     * Теперь состояний три: обычное, нажатое и недоступное, и у каждого свой вид.
     */
    static TextView button(Context context, String label, boolean filled) {
        TextView view = new ActionTextView(context);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, T_BODY);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, S4), dp(context, S3), dp(context, S4), dp(context, S3));
        view.setMinHeight(dp(context, TOUCH));
        view.setMinWidth(dp(context, TOUCH));
        view.setTextColor(filled ? inkOnAccent() : inkOnCard());
        view.setBackground(filled ? accentBack(context) : ghostBack(context));
        view.setClickable(true);
        view.setFocusable(true);
        touchMotion(view);

        if (filled) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }

        return view;
    }

    /** A short touch-only response; no continuously running animation or blur pass. */
    static void touchMotion(View view) {
        Motion.touch(view);
    }

    /**
     * Меняет вид кнопки, не меняя её саму.
     *
     * Нужно там, где одна и та же кнопка то главная, то нет: «Собрать систему» становится
     * «Пересобрать», выбранная раскладка — залитой.
     */
    static void asFilled(TextView view, boolean filled) {
        view.setTextColor(filled ? inkOnAccent() : inkOnCard());
        view.setBackground(filled ? accentBack(view.getContext()) : ghostBack(view.getContext()));
        view.setTypeface(filled ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    /**
     * Приглушает кнопку, оставляя её нажимаемой.
     *
     * Разница с {@code setEnabled(false)} важная: недоступная кнопка молчит, а
     * приглушённая по нажатию объясняет, чего не хватает. «Отправить отзыв» до трёх
     * ответов — как раз такая.
     */
    static void dim(View view, boolean on) {
        view.setAlpha(on ? 0.5f : 1f);
    }

    private static ColorStateList inkOnAccent() {
        return new ColorStateList(
                new int[][] { new int[] { -android.R.attr.state_enabled }, new int[0] },
                new int[] { DIM, ACTION_INK });
    }

    private static ColorStateList inkOnCard() {
        return new ColorStateList(
                new int[][] {
                        new int[] { -android.R.attr.state_enabled },
                        new int[] { android.R.attr.state_pressed },
                        new int[0]
                },
                new int[] { DIM, ACCENT_HOVER, ACCENT });
    }

    /**
     * Полоса вкладок: выбранная — оранжевая, остальные — приглушённые.
     *
     * Готово к нижней панели в MainActivity, три строки на всю полосу:
     * <pre>
     *     bar.setBackground(Ui.bar(this, true));   // подложка с волосяной линией сверху
     *     Ui.fit(bar, false, true, true);          // отступ под навигационную полосу
     *     tab.setTextColor(Ui.tabInk());           // цвет сам следит за setSelected
     * </pre>
     * После этого вкладку переключают через {@code tab.setSelected(...)}, а не
     * перекрашиванием вручную: цвет уже знает про выбранное и нажатое состояния.
     * Экраны внутри отступ снизу не берут — его берёт полоса, иначе он выйдет двойным.
     */
    static ColorStateList tabInk() {
        return new ColorStateList(
                new int[][] {
                        new int[] { android.R.attr.state_selected },
                        new int[] { android.R.attr.state_pressed },
                        new int[0]
                },
                new int[] { ACCENT, TEXT, MUTED });
    }

    private static Drawable accentBack(Context context) {
        StateListDrawable list = new StateListDrawable();

        list.addState(new int[] { -android.R.attr.state_enabled },
                flat(context, ACCENT_SUBTLE, LINE, R_BUTTON, 1));
        list.addState(new int[] { android.R.attr.state_pressed },
                flat(context, ACTION_PRESSED, ACTION_PRESSED, R_BUTTON, 1));

        list.addState(new int[0], flat(context, ACTION, ACTION, R_BUTTON, 1));

        return ripple(context, list, alpha(ACTION_INK, 0x30), R_BUTTON);
    }

    private static Drawable ghostBack(Context context) {
        StateListDrawable list = new StateListDrawable();

        list.addState(new int[] { -android.R.attr.state_enabled },
                flat(context, Color.TRANSPARENT, LINE_SOFT, R_BUTTON, 1));
        list.addState(new int[] { android.R.attr.state_pressed },
                flat(context, CARD_PRESSED, LINE_STRONG, R_BUTTON, 1));

        list.addState(new int[0], glass(context, R_BUTTON, false));

        return ripple(context, list, alpha(ACCENT, 0x33), R_BUTTON);
    }

    /**
     * Отклик на касание для того, что кнопкой не является: карточки, надписи-действия.
     *
     * Кладётся поверх ({@code setForeground}), а не вместо фона, поэтому годится и там,
     * где фон уже занят карточкой. Без него нажатие на карточку раскладки ничем не
     * отличалось от нажатия мимо неё.
     */
    static Drawable tap(Context context, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(alpha(ACCENT, 0x30)), null,
                rounded(context, Color.WHITE, radius));
    }

    private static GradientDrawable flat(Context context, int fill, int line,
                                         int radius, int width) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setStroke(dp(context, width), line);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    private static Drawable ripple(Context context, Drawable content, int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(color), content,
                rounded(context, Color.WHITE, radius));
    }

    // -------------------------------------------------------------------- знаки ----

    /**
     * Значок вектором вместо эмодзи.
     *
     * Эмодзи рисует шрифт прошивки: на одном телефоне это цветная картинка, на другом
     * чёрно-белый контур, на третьем квадратик, и базовая линия у всех своя — надпись
     * рядом с ним съезжает. Вектор везде один и тот же и красится в нужный цвет.
     */
    static Drawable icon(Context context, String name, int size, int color) {
        return new Icon(name, dp(context, size), color);
    }

    /** Ставит знак слева от надписи, на её же базовой линии. */
    static void setIcon(TextView view, String name, int size, int color) {
        Drawable mark = icon(view.getContext(), name, size, color);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(mark, null, null, null);
        view.setCompoundDrawablePadding(dp(view.getContext(), S2 + S1));
    }

    /**
     * Обложка игры, у которой обложки нет.
     *
     * Цвет в приложение приносят игры, а не интерфейс. Пока настоящей картинки нет, её
     * место занимает плитка, цвет которой посчитан из названия: две разные игры не
     * сольются, а одна и та же всегда выглядит одинаково. Тона глухие — обложка должна
     * оживлять список, а не спорить с ним.
     */
    static Drawable cover(Context context, String name) {
        return new Cover(name == null ? "" : name, dp(context, R_CARD), dp(context, 1));
    }

    // ------------------------------------------------------------------- врезки ----

    /**
     * Отодвигает содержимое от строки состояния, навигационной полосы и выреза камеры.
     *
     * Врезки не «съедаются»: список получает их и передаёт дальше, иначе соседний экран
     * останется без них и залезет под полосу. Там, где система сама отступила за нас,
     * добавка равна нулю — так же безопасно на старых прошивках, как и на новых.
     */
    static void fit(final View view, final boolean top, final boolean bottom,
                    final boolean sides) {
        final int left = view.getPaddingLeft();
        final int up = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int down = view.getPaddingBottom();

        view.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View target, WindowInsets insets) {
                Rect edges = systemEdges(insets);

                target.setPadding(
                        left + (sides ? edges.left : 0),
                        up + (top ? edges.top : 0),
                        right + (sides ? edges.right : 0),
                        down + (bottom ? edges.bottom : 0));

                return insets;
            }
        });

        view.requestApplyInsets();
    }

    /** Врезки строки состояния, полос и выреза — одним прямоугольником. */
    static Rect systemEdges(WindowInsets insets) {
        if (insets == null) {
            return new Rect();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());

            return new Rect(bars.left, bars.top, bars.right, bars.bottom);
        }

        Rect edges = new Rect(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());

        android.view.DisplayCutout cutout = Build.VERSION.SDK_INT >= 28 ? insets.getDisplayCutout() : null;

        if (cutout != null) {
            edges.left = Math.max(edges.left, cutout.getSafeInsetLeft());
            edges.top = Math.max(edges.top, cutout.getSafeInsetTop());
            edges.right = Math.max(edges.right, cutout.getSafeInsetRight());
            edges.bottom = Math.max(edges.bottom, cutout.getSafeInsetBottom());
        }

        return edges;
    }

    // ------------------------------------------------------------------ разметка ----

    static LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    static LinearLayout column(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return column;
    }

    /** Полоса во всю ширину с отступом снизу — так кладут кнопки и карточки друг под друга. */
    static LinearLayout.LayoutParams wide(Context context, int below) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(context, below);
        return params;
    }

    /** «5 мин», «1 ч 20 мин» — время игры так, как его произносят вслух. */
    static String playtime(long minutes) {
        if (minutes < 60) {
            return minutes + " мин";
        }

        long hours = minutes / 60;
        long rest = minutes % 60;

        return rest == 0 ? hours + " ч" : hours + " ч " + rest + " мин";
    }

    // ------------------------------------------------------------------ фигуры ----

    /**
     * Карточка: градиент, рамка, светлая кромка сверху, тень под нижним краем.
     *
     * Кромка — одна волосяная линия по верхнему краю и по загибу углов. Из-за неё
     * поверхность читается как приподнятая: свет всегда падает сверху, и глаз знает это
     * без объяснений.
     */
    private static final class Panel extends Drawable {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        private final float radius;
        private final float border;
        private final int top;
        private final int bottom;
        private final int line;
        private final int gloss;
        private final int shade;

        Panel(float radius, float border, int top, int bottom, int line, int gloss, int shade) {
            this.radius = radius;
            this.border = Math.max(1f, border);
            this.top = top;
            this.bottom = bottom;
            this.line = line;
            this.gloss = gloss;
            this.shade = shade;

            // Цвет на случай, если рисовать позовут раньше, чем станут известны размеры:
            // без него панель в этот единственный кадр была бы прозрачной.
            fill.setColor(bottom);

            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(this.border);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            if (bounds.height() > 0) fill.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                    top, bottom, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();

            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return;
            }

            box.set(bounds.left + border / 2f, bounds.top + border / 2f,
                    bounds.right - border / 2f, bounds.bottom - border / 2f);

            canvas.drawRoundRect(box, radius, radius, fill);

            edge.setColor(line);
            canvas.drawRoundRect(box, radius, radius, edge);

            // Свет сверху и тень снизу рисуются одним и тем же контуром, обрезанным по
            // верхней и нижней полосе высотой в скругление: получается кромка, которая
            // заворачивает за углы, а не обрывается на них.
            if (Color.alpha(gloss) > 0) {
                canvas.save();
                canvas.clipRect(bounds.left, bounds.top, bounds.right, bounds.top + radius);
                edge.setColor(gloss);
                canvas.drawRoundRect(box, radius, radius, edge);
                canvas.restore();
            }

            if (Color.alpha(shade) > 0) {
                canvas.save();
                canvas.clipRect(bounds.left, bounds.bottom - radius, bounds.right, bounds.bottom);
                edge.setColor(shade);
                canvas.drawRoundRect(box, radius, radius, edge);
                canvas.restore();
            }
        }

        @Override
        public void setAlpha(int value) {
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Полоса с волосяной линией по одному краю: шапка сверху, вкладки снизу. */
    private static final class Edge extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int line;
        private final float width;
        private final boolean onTop;
        private Shader glass;

        Edge(int back, int line, float width, boolean onTop) {
            this.line = line;
            this.width = Math.max(1f, width);
            this.onTop = onTop;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            if (bounds.height() > 0) {
                glass = new LinearGradient(0, bounds.top, 0, bounds.bottom,
                        new int[] { alpha(blend(BACK_SOFT, CARD_HOVER, 0.48f), 0xF3),
                                alpha(BACK_SOFT, 0xF5), alpha(BACK_SOFT, 0xF9) },
                        new float[] { 0f, 0.48f, 1f }, Shader.TileMode.CLAMP);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();

            paint.setColor(Color.WHITE);
            paint.setShader(glass);
            canvas.drawRect(bounds, paint);
            paint.setShader(null);

            paint.setColor(line);

            if (onTop) {
                canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + width, paint);
            } else {
                canvas.drawRect(bounds.left, bounds.bottom - width,
                        bounds.right, bounds.bottom, paint);
            }

            paint.setColor(alpha(Color.WHITE, 0x38));
            float lip = onTop ? bounds.top + width : bounds.bottom - width * 2f;
            canvas.drawRect(bounds.left, lip, bounds.right, lip + width, paint);
        }

        @Override
        public void setAlpha(int value) {
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Знак из {@link PadIcons} в виде картинки: годится и для кнопки, и для надписи. */
    private static final class Icon extends Drawable {

        private final String name;
        private final int size;
        private final int color;

        Icon(String name, int size, int color) {
            this.name = name;
            this.size = size;
            this.color = color;
            setBounds(0, 0, size, size);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();

            PadIcons.draw(canvas, name, bounds.exactCenterX(), bounds.exactCenterY(),
                    Math.min(bounds.width(), bounds.height()) * 0.42f, color);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int value) {
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Плитка вместо обложки: цвет посчитан из названия, буква — первая из него же. */
    private static final class Cover extends Drawable {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint motif = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final RectF orbit = new RectF();

        private final String mark;
        private final float radius;
        private final float border;
        private final int top;
        private final int bottom;

        Cover(String name, float radius, float border) {
            this.radius = radius;
            this.border = Math.max(1f, border);

            String trimmed = name.trim();
            this.mark = trimmed.isEmpty()
                    ? "?" : trimmed.substring(0, 1).toUpperCase(java.util.Locale.getDefault());

            // Тон — из названия, насыщенность и яркость — заданы жёстко. Так плитка
            // никогда не выйдет ни кислотной, ни белёсой, какой бы ни была игра.
            float[] hues = { 175f, 216f, 260f, 318f, 34f, 145f };
            float hue = hues[Math.floorMod(name.hashCode(), hues.length)];

            this.top = Color.HSVToColor(new float[] { hue, 0.56f, 0.53f });
            this.bottom = Color.HSVToColor(new float[] { (hue + 20f) % 360f, 0.65f, 0.31f });

            fill.setColor(Color.HSVToColor(new float[] { hue, 0.5f, 0.42f }));

            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(this.border);

            letter.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            letter.setTextAlign(Paint.Align.CENTER);
            motif.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            if (bounds.height() > 0) {
                fill.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                        top, bottom, Shader.TileMode.CLAMP));
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();

            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return;
            }

            box.set(bounds.left + border / 2f, bounds.top + border / 2f,
                    bounds.right - border / 2f, bounds.bottom - border / 2f);

            canvas.drawRoundRect(box, radius, radius, fill);

            canvas.save();
            canvas.clipRect(box);
            motif.setColor(alpha(Color.WHITE, 0x18));
            motif.setStrokeWidth(Math.max(1f, bounds.width() * .016f));
            float ring = bounds.width() * .42f;
            canvas.drawCircle(bounds.right - ring * .12f, bounds.top + ring * .25f, ring, motif);
            canvas.drawCircle(bounds.left + ring * .15f, bounds.bottom + ring * .45f, ring * 1.2f, motif);
            canvas.restore();

            letter.setColor(alpha(Color.WHITE, 0xD9));
            letter.setTextSize(bounds.height() * 0.44f);
            canvas.drawText(mark, bounds.exactCenterX(),
                    bounds.exactCenterY() + letter.getTextSize() * 0.35f, letter);

            edge.setColor(alpha(Color.WHITE, 0x24));
            canvas.drawRoundRect(box, radius, radius, edge);


        }

        @Override
        public void setAlpha(int value) {
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static final class ActionTextView extends TextView {
        ActionTextView(Context context) { super(context); }
        @Override public CharSequence getAccessibilityClassName() { return android.widget.Button.class.getName(); }
    }
}
