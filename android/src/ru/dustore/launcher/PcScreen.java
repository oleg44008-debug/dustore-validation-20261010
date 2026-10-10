package ru.dustore.launcher;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Вкладка «С компьютера».
 *
 * Здесь две половины одной мысли. Сверху — игры, которые уже переехали сюда: они лежат на
 * телефоне и идут без компьютера вообще. Ниже — библиотека компьютера, как она есть прямо
 * сейчас, и у каждой игры честно написано, что с ней будет: одну можно пересобрать
 * по-настоящему, другой нужен слой совместимости.
 *
 * Связь — по кабелю на 127.0.0.1: лаунчер на компьютере сам поднимает `adb reverse`, когда
 * видит телефон. Адрес вводить не нужно, в сеть ничего не открыто, и всё это работает там,
 * где сети нет.
 */
final class PcScreen extends ScrollView {

    private static final int PORT = 27185;

    /** Маяк, которым компьютер откликается на «кто здесь DustoreX?». */
    private static final int BEACON = 27187;

    private final Context context;
    private final LinearLayout column;
    private final TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Socket socket;
    private OutputStream out;
    private DataInputStream in;
    private volatile boolean running;
    private volatile int connectionGeneration;

    /** Адрес компьютера, если связались по Wi-Fi; null — значит по кабелю. */
    private volatile String wifi;

    /** Игры из общей библиотеки аккаунта — их видно и без кабеля. */
    private java.util.List<LibraryCloud.Game> account = new java.util.ArrayList<>();

    /** Пришёл ли живой список от самого компьютера. */
    private boolean live;
    private final java.util.List<LocalGame> localGames = new java.util.ArrayList<>();
    private boolean localLoaded;
    private int localGeneration;
    private JSONArray currentPcGames;
    private static final class LocalGame {
        final File folder; final JSONObject meta;
        LocalGame(File folder, JSONObject meta) { this.folder = folder; this.meta = meta; }
    }

    PcScreen(Context context) {
        super(context);
        this.context = context;

        SeasonScene.apply(this);
        setClipToPadding(false);

        // Содержимое не должно уезжать под часы и под навигационную полосу: врезки
        // берутся у системы, а не угадываются по высоте шапки.
        Ui.fit(this, true, false, true);

        column = Ui.column(context);
        column.setPadding(Ui.dp(context, Ui.S4), Ui.dp(context, Ui.S4),
                Ui.dp(context, Ui.S4), Ui.dp(context, Ui.S6));

        status = Ui.paragraph(context, "Ищем компьютер по кабелю…", Ui.T_LABEL, Ui.DIM_FIXED);
        status.setPadding(0, 0, 0, Ui.dp(context, Ui.S3));

        addView(column);
    }

    /** Вызывается, когда вкладку открыли: список каждый раз собирается заново. */
    void load() {
        build(null);
        refreshLocalGames();
        connect();

        // Библиотека принадлежит аккаунту, а не устройству: игры компьютера видно здесь
        // ещё до того, как компьютер найдётся, — иначе eXeportal нечего было бы предложить,
        // пока телефон не воткнут в кабель.
        MainActivity host = context instanceof MainActivity ? (MainActivity) context : null;

        if (host != null) {
            LibraryCloud.sync(context, host.account(), host.library(), (games, error) -> {
                account = games;

                if (!live) {
                    ui.post(() -> build(null));
                }
            });
        }
    }

    // ------------------------------------------------------------------- свои игры ----

    static File gamesFolder(Context context) {
        File games = new File(context.getExternalFilesDir(null), "games");

        if (!games.exists()) {
            games.mkdirs();
        }

        return games;
    }

    private void build(JSONArray pcGames) {
        currentPcGames = pcGames;
        column.removeAllViews();

        LinearLayout heading = Ui.row(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(Ui.display(context, "С компьютера", Ui.T_DISPLAY),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        heading.addView(Ui.brandMark(context));
        column.addView(heading);

        TextView about = Ui.paragraph(context,
                "Переносите игры между устройствами, запускайте через eX "
                        + "и используйте телефон как джойстик.",
                Ui.T_MICRO, Ui.DIM);
        about.setPadding(0, Ui.dp(context, Ui.S1), 0, Ui.dp(context, Ui.S4));
        column.addView(about);

        LinearLayout connection = Ui.column(context);
        connection.setBackground(Ui.panel(context, 18, Ui.CARD, Ui.LINE));
        int connectionPadding = Ui.dp(context, 16);
        connection.setPadding(connectionPadding, connectionPadding, connectionPadding, connectionPadding);
        TextView connectionTitle = Ui.title(context, "Связь с компьютером", Ui.T_LABEL, Ui.TEXT);
        connection.addView(connectionTitle);
        if (status.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup) status.getParent()).removeView(status);
        status.setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 12));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        connection.addView(status);
        TextView retry = Ui.button(context, "Найти компьютер снова", false);
        retry.setOnClickListener(v -> { stop(); connect(); }); connection.addView(retry);
        column.addView(connection, Ui.wide(context, 16));

        // Cross-device library sign-in remains useful after removing the review service.
        MainActivity host = context instanceof MainActivity ? (MainActivity) context : null;
        if (host != null) {
            Account user = host.account();
            TextView accountButton = Ui.button(context,
                    user.signedIn() ? "eXepc · " + user.name() : "Войти в eXepc · синхронизировать игры",
                    false);
            accountButton.setOnClickListener(v -> {
                if (user.signedIn()) {
                    new android.app.AlertDialog.Builder(context)
                            .setTitle("Аккаунт eXepc")
                            .setMessage("Вы вошли как " + user.name())
                            .setPositiveButton("Закрыть", null)
                            .setNegativeButton("Выйти", (dialog, which) -> {
                                user.forget();
                                account.clear();
                                load();
                            }).show();
                } else {
                    new AccountSheet(host, user).show(this::load);
                }
            });
            LinearLayout.LayoutParams accountSpace = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            accountSpace.bottomMargin = Ui.dp(context, Ui.S4);
            column.addView(accountButton, accountSpace);
        }

        // eXepad — на самом видном месте: за ним сюда будут заходить чаще всего. Не
        // кнопка во всю ширину, а карточка: у неё есть имя, объяснение и знак, и она
        // не спорит по весу с кнопками внутри игр ниже.
        column.addView(hero("gamepad", "eXepad", "Телефон как джойстик для компьютера",
                view -> context.startActivity(new Intent(context, JoypadActivity.class))));

        // Своя система — сразу под джойстиком: это то, чем игры для Windows и запускаются.
        if (context instanceof android.app.Activity) {
            column.addView(new ExScreen((android.app.Activity) context));
        }

        column.addView(section("Переведённые игры", "лежат на телефоне, компьютер им не нужен"));

        for (LocalGame game : localGames) column.addView(translated(game.folder, game.meta));
        if (localGames.isEmpty()) {
            TextView empty = Ui.paragraph(context,
                    !localLoaded ? "Читаем игры на устройстве…" : "Пока пусто. Ниже — библиотека компьютера: выберите игру, и она "
                            + "переедет сюда. После этого компьютер ей больше не нужен.",
                    Ui.T_LABEL, Ui.DIM_FIXED);
            empty.setPadding(0, 0, 0, Ui.dp(context, Ui.S4));
            column.addView(empty);
        }

        column.addView(section("Игры на компьютере", "то, что стоит на нём прямо сейчас"));

        if (pcGames == null) {
            // Компьютера сейчас не слышно — показываем то, что знает аккаунт. Играть в такую
            // игру нельзя, пока компьютер не появится, и об этом сказано прямо на карточке.
            for (LibraryCloud.Game game : account) {
                if (game.onPc) {
                    column.addView(fromAccount(game));
                }
            }

            return;
        }

        live = true;

        for (int i = 0; i < pcGames.length(); i++) {
            JSONObject game = pcGames.optJSONObject(i);

            if (game != null) {
                column.addView(onPc(game));
            }
        }
    }

    private void refreshLocalGames() {
        int version = ++localGeneration;
        android.content.Context app = context.getApplicationContext();
        UiWork.execute(() -> {
            java.util.List<LocalGame> loaded = new java.util.ArrayList<>();
            File[] folders = gamesFolder(app).listFiles();
            if (folders != null) {
                Arrays.sort(folders);
                for (File folder : folders) if (folder.isDirectory()) loaded.add(new LocalGame(folder, readMeta(folder)));
            }
            ui.post(() -> {
                if (version != localGeneration || context instanceof android.app.Activity && ((android.app.Activity) context).isDestroyed()) return;
                localGames.clear(); localGames.addAll(loaded); localLoaded = true;
                build(currentPcGames);
            });
        });
    }

    /**
     * Игра компьютера, известная из аккаунта, а не с кабеля.
     *
     * Нужна ровно для одного: чтобы список был виден всегда. Запускать её нельзя, пока
     * компьютер не на связи, — но человек хотя бы знает, что у него есть и что его ждёт.
     */
    private View fromAccount(LibraryCloud.Game game) {
        LinearLayout card = card();

        card.addView(head(game.title,
                "из библиотеки аккаунта" + (game.onPhone ? "  ·  есть и здесь" : "")
                        + (game.size > 0 ? "  ·  " + (game.size / 1048576) + " МБ" : ""),
                Ui.MUTED, "компьютер выключен", Ui.DIM));

        TextView action = wideButton("Играть здесь  ·  eXeportal", false, "phone", Ui.ACCENT);
        action.setOnClickListener(view -> {
            if (!running) {
                status.setText("Эта игра лежит на компьютере. Включите его и откройте "
                        + "DustoreX — тогда она пойдёт сюда картинкой.");
                return;
            }

            status.setText("Запускаем на компьютере и включаем трансляцию…");
            ask(Wire.MSG_PORTAL, game.key);
        });

        card.addView(action, actionParams(Ui.S4));

        return card;
    }

    private View translated(File folder, JSONObject meta) {
        String name = meta.optString("title", folder.getName());
        String engine = meta.optString("engine", "");
        boolean ex = "ex".equals(meta.optString("kind", "web"));

        LinearLayout card = card();

        card.addView(head(name,
                engine.length() > 0 ? engine : "переехала на телефон", Ui.MUTED,
                ex ? "Windows — через eX" : "работает без компьютера",
                ex ? Ui.WARNING : Ui.SUCCESS));

        TextView play = wideButton(ex ? "Запустить в eX" : "Играть", true, null, 0);
        play.setOnClickListener(view -> {
            if (ex) {
                Intent intent = new Intent(context, ExActivity.class);
                intent.putExtra(ExActivity.EXTRA_FOLDER, folder.getAbsolutePath());
                context.startActivity(intent);
                return;
            }

            play(folder, name, meta);
        });

        card.addView(play, actionParams(Ui.S4));

        return card;
    }

    private void play(File folder, String title, JSONObject meta) {
        Intent intent = new Intent(context, GameActivity.class);
        intent.putExtra(GameActivity.EXTRA_FOLDER, folder.getAbsolutePath());
        intent.putExtra(GameActivity.EXTRA_TITLE, title);

        // Игра писалась под клавиатуру, поэтому окно рисует её поверх картинки. Какую
        // именно раскладку — решено на компьютере, где видно движок сборки.
        intent.putExtra(GameActivity.EXTRA_CONTROLS, meta.optString("controls", "arcade"));
        intent.putExtra(GameActivity.EXTRA_ORIENTATION, meta.optString("orientation", "landscape"));

        context.startActivity(intent);
    }

    // --------------------------------------------------------------- игры компьютера ----

    private View onPc(JSONObject game) {
        final String id = game.optString("id");
        String name = game.optString("title", "Игра");
        boolean real = "repack".equals(game.optString("kind", "repack"));
        boolean here = game.optBoolean("onPhone", false);
        String size = game.optString("size", "");
        String engine = game.optString("engine", "");

        LinearLayout card = card();

        String tail = (size.length() > 0 ? size : "")
                + (engine.length() > 0 ? (size.length() > 0 ? "  ·  " : "") + engine : "");

        card.addView(head(name, tail.length() > 0 ? tail : "на компьютере", Ui.MUTED,
                here ? "уже переведена"
                     : (real ? "переведётся по-настоящему" : "машинный код — eX (BETA)"),
                here ? Ui.SUCCESS : (real ? Ui.INFO : Ui.WARNING)));

        // eXeportal — главная кнопка на этой карточке: играть В ЭТУ игру прямо здесь, на
        // телефоне. Считает компьютер, картинка едет сюда, управление — нарисованные кнопки.
        // Работает с любой игрой, даже с тяжёлой трёхмерной, потому что переносить ничего не
        // надо: игра остаётся там, где ей хорошо. Поэтому она одна здесь залита цветом:
        // три оранжевые кнопки подряд — это ни одной главной.
        TextView portal = wideButton("Играть здесь, на телефоне  ·  eXeportal", true,
                "phone", Ui.ACCENT_INK);
        portal.setOnClickListener(view -> {
            status.setText("Запускаем на компьютере и включаем трансляцию…");
            ask(Wire.MSG_PORTAL, id);
        });
        card.addView(portal, actionParams(Ui.S4));

        TextView action = wideButton(here ? "Уже здесь" : "Перевести на телефон", false,
                null, 0);

        if (here) {
            // Теперь это видно: заливка гаснет, рамка бледнеет, надпись уходит в серый.
            // Раньше «Уже здесь» выглядела как рабочая кнопка, которая молча ничего не
            // делает, — а это худший вид кнопки.
            action.setEnabled(false);
        } else {
            // Кнопка одна на оба случая: компьютер сам знает, пересобирать игру или
            // отдавать её eX. Раньше половина списка кончалась советом «откройте
            // eXeport на компьютере» — то есть кнопкой, которая ничего не делает.
            action.setOnClickListener(view -> send(Wire.MSG_TRANSLATE, id));
        }

        card.addView(action, actionParams(Ui.S2));

        // eXepult — пульт: игра запускается ТАМ, на компьютере, а телефон тут же становится
        // джойстиком. Это отдельная мысль от перевода: не «перенести игру сюда», а «не
        // вставать с дивана, чтобы её включить».
        TextView remote = wideButton("Запустить на компьютере  ·  eXepult", false,
                "monitor", Ui.ACCENT);
        remote.setOnClickListener(view -> {
            status.setText("Просим компьютер запустить игру…");
            ask(Wire.MSG_RUN_HERE, id);
        });

        card.addView(remote, actionParams(Ui.S2));

        return card;
    }

    private LinearLayout card() {
        LinearLayout card = Ui.column(context);
        card.setBackground(Ui.panel(context));
        Ui.lift(card, Ui.R_CARD);

        int pad = Ui.dp(context, Ui.S4);
        card.setPadding(pad, pad, pad, pad);
        card.setLayoutParams(Ui.wide(context, Ui.S3));

        return card;
    }

    /**
     * Шапка карточки игры: обложка, название и метка состояния.
     *
     * Обложка здесь — плитка, посчитанная из названия: настоящей картинки у игры с
     * компьютера нет, а серый прямоугольник вместо неё сделал бы список одинаковым.
     */
    private LinearLayout head(String name, String note, int noteColor,
                              String mark, int markColor) {
        LinearLayout row = Ui.row(context);

        ImageView cover = new ImageView(context);
        cover.setImageDrawable(Ui.cover(context, name));
        cover.setScaleType(ImageView.ScaleType.FIT_XY);
        Ui.roundCorners(cover, Ui.R_CARD);

        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(
                Ui.dp(context, 56), Ui.dp(context, 56));
        coverParams.rightMargin = Ui.dp(context, Ui.S3);
        row.addView(cover, coverParams);

        LinearLayout words = Ui.column(context);
        words.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = Ui.title(context, name, Ui.T_HEAD, Ui.TEXT);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(title);

        if (note != null && note.length() > 0) {
            TextView line = Ui.text(context, note, Ui.T_MICRO, noteColor);
            line.setPadding(0, Ui.dp(context, 2), 0, 0);
            words.addView(line);
        }

        if (mark != null && mark.length() > 0) {
            LinearLayout box = Ui.row(context);
            box.setPadding(0, Ui.dp(context, Ui.S2), 0, 0);
            box.addView(Ui.chip(context, mark, markColor), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            words.addView(box);
        }

        row.addView(words);

        return row;
    }

    /** Кнопка во всю ширину карточки — со знаком вместо эмодзи, если он нужен. */
    private TextView wideButton(String label, boolean filled, String icon, int iconColor) {
        TextView button = Ui.button(context, label, filled);
        button.setPadding(Ui.dp(context, Ui.S3), Ui.dp(context, Ui.S3),
                Ui.dp(context, Ui.S3), Ui.dp(context, Ui.S3));

        if (icon != null) {
            Ui.setIcon(button, icon, 20, iconColor);
        }

        return button;
    }

    private LinearLayout.LayoutParams actionParams(int above) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(context, above);
        return params;
    }

    /**
     * Заголовок раздела: слово и строка объяснения под ним.
     *
     * Разделов на этом экране три, и они про разное — «здесь», «там» и «своя система».
     * Без подписи их путают, а путаница тут дорогая: одна кнопка переносит игру, другая
     * включает её за стеной.
     */
    private LinearLayout section(String name, String note) {
        LinearLayout box = Ui.column(context);
        box.setPadding(0, Ui.dp(context, Ui.S5), 0, Ui.dp(context, Ui.S3));

        box.addView(Ui.title(context, name, Ui.T_HEAD, Ui.TEXT));

        TextView line = Ui.text(context, note, Ui.T_MICRO, Ui.DIM);
        line.setPadding(0, Ui.dp(context, Ui.S1), 0, 0);
        box.addView(line);

        return box;
    }

    /**
     * Большая карточка-вход: знак, имя, объяснение.
     *
     * Ею открывается экран, поэтому она не кнопка: у кнопки нет места для второй строки,
     * а «eXepad» без объяснения — просто слово.
     */
    private LinearLayout hero(String icon, String name, String note, OnClickListener click) {
        LinearLayout card = Ui.row(context);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.panel(context, Ui.R_CARD, Ui.ACCENT_SUBTLE,
                Ui.blend(Ui.LINE, Ui.ACCENT, 0.35f)));
        Ui.lift(card, Ui.R_CARD);

        int pad = Ui.dp(context, Ui.S4);
        card.setPadding(pad, pad, pad, pad);
        card.setMinimumHeight(Ui.dp(context, Ui.TOUCH + Ui.S5));
        card.setForeground(Ui.tap(context, Ui.R_CARD));
        card.setClickable(true);
        card.setOnClickListener(click);
        card.setLayoutParams(Ui.wide(context, Ui.S3));

        ImageView mark = new ImageView(context);
        mark.setImageDrawable(Ui.icon(context, icon, 28, Ui.ACCENT));

        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(
                Ui.dp(context, 28), Ui.dp(context, 28));
        markParams.rightMargin = Ui.dp(context, Ui.S3);
        card.addView(mark, markParams);

        LinearLayout words = Ui.column(context);
        words.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        words.addView(Ui.title(context, name, Ui.T_HEAD, Ui.TEXT));

        TextView line = Ui.text(context, note, Ui.T_LABEL, Ui.MUTED);
        line.setPadding(0, Ui.dp(context, 2), 0, 0);
        words.addView(line);

        card.addView(words);

        ImageView chevron = new ImageView(context);
        chevron.setImageDrawable(Ui.icon(context, "right", 20, Ui.DIM));
        card.addView(chevron, new LinearLayout.LayoutParams(
                Ui.dp(context, 20), Ui.dp(context, 20)));

        return card;
    }

    // ---------------------------------------------------------------------- кабель ----

    private synchronized void connect() {
        if (running) {
            ask(Wire.MSG_LIST, null);
            return;
        }

        running = true;
        final int generation = ++connectionGeneration;

        new Thread(() -> {
            Socket connected = null;
            try {
                connected = reach(generation);

                if (connected == null) {
                    throw new java.io.IOException("компьютер не найден");
                }

                OutputStream outgoing = connected.getOutputStream();
                DataInputStream incoming = new DataInputStream(connected.getInputStream());
                synchronized (PcScreen.this) {
                    if (!running || generation != connectionGeneration) return;
                    socket = connected; out = outgoing; in = incoming;
                }

                // Как именно связались — видно на экране, а не угадывается. Кабель и
                // Wi-Fi ведут себя по-разному (скорость, задержка, отвал при уходе из
                // сети), и человек имеет право знать, что у него сейчас.
                final String how = connected.getInetAddress().isLoopbackAddress()
                        ? "по кабелю"
                        : "по Wi-Fi, " + connected.getInetAddress().getHostAddress();

                ui.post(() -> { if (generation == connectionGeneration) status.setText("Компьютер на связи " + how + "."); });

                JSONObject hello = new JSONObject();
                hello.put("code", context.getSharedPreferences("exepad", Context.MODE_PRIVATE).getString("code", ""));
                hello.put("device", PadIdentity.of(context));
                Wire.writeText(outgoing, Wire.MSG_HELLO, hello.toString());
                Wire.write(outgoing, Wire.MSG_LIST, null);

                Wire.Message message = new Wire.Message();

                while (running && generation == connectionGeneration && Wire.read(incoming, message)) {
                    final String text = Wire.text(message);
                    final byte type = message.type;

                    ui.post(() -> { if (generation == connectionGeneration) receive(type, text); });
                }
            } catch (Exception failure) {
                ui.post(() -> { if (generation == connectionGeneration) status.setText(
                        "Компьютер не отвечает.\n\nПодойдёт и кабель, и Wi-Fi: воткните "
                                + "телефон в компьютер либо подключите оба к одной сети. "
                                + "На компьютере должен быть открыт DustoreX — собирает игру "
                                + "для телефона именно он, здесь нажимается кнопка."); });
            } finally {
                try { if (connected != null) connected.close(); } catch (Exception ignored) { }
                synchronized (PcScreen.this) {
                    if (generation == connectionGeneration) { running = false; socket = null; out = null; in = null; }
                }
            }
        }, "pc-link").start();
    }

    private void receive(byte type, String text) {
        switch (type) {
            case (byte)'A':
                try {
                    JSONObject auth = new JSONObject(text);
                    if (!auth.optBoolean("ok")) {
                        stop();
                        android.widget.EditText input = new android.widget.EditText(context);
                        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                        input.setHint("Код с вкладки eXepad на компьютере");
                        new android.app.AlertDialog.Builder(context).setTitle("Подключение к компьютеру")
                            .setMessage(auth.optString("error", "Введите код подключения"))
                            .setView(input).setNegativeButton("Отмена", null)
                            .setPositiveButton("Подключиться", (dialog, which) -> {
                                context.getSharedPreferences("exepad", Context.MODE_PRIVATE).edit()
                                    .putString("code", input.getText().toString().trim()).apply();
                                connect();
                            }).show();
                    }
                } catch (Exception broken) { status.setText("Не удалось проверить код подключения."); }
                break;
            case Wire.MSG_GAMES:
                try {
                    build(new JSONObject(text).optJSONArray("games"));
                    status.setText("Нажмите на игру — она переедет сюда.");
                } catch (Exception broken) {
                    status.setText("Список пришёл в непонятном виде.");
                }

                break;

            case Wire.MSG_STATUS:
                status.setText(text);
                break;

            case Wire.MSG_DONE:
                status.setText(text);
                ask(Wire.MSG_LIST, null);
                break;

            case Wire.MSG_RAN:
                ran(text);
                break;
        }
    }

    /**
     * Игра пошла на компьютере — предлагаем взять джойстик.
     *
     * Раскладку выбирать не надо: компьютер прислал ту, что подходит этой игре, — он знает
     * и движок, и жанры. Одно нажатие с дивана: игра включилась, пад открылся настроенным.
     */
    private void ran(String json) {
        try {
            JSONObject answer = new JSONObject(json);

            status.setText(answer.optString("text", "Готово."));

            if (!answer.optBoolean("ok", false)) {
                return;
            }

            final String profile = answer.optString("profile", "");
            final boolean portal = answer.optBoolean("portal", false);

            // При трансляции спрашивать нечего: человек нажал «играть здесь» — значит, туда
            // и надо, без лишнего диалога. Это и есть «максимально удобно».
            if (portal) {
                Intent intent = new Intent(context, JoypadActivity.class);
                intent.putExtra("portal", true);

                if (!profile.isEmpty()) {
                    intent.putExtra("profile", profile);
                }

                context.startActivity(intent);
                return;
            }

            new android.app.AlertDialog.Builder(context)
                    .setTitle("Игра пошла на компьютере")
                    .setMessage(answer.optString("text", "")
                            + (profile.isEmpty() ? "" : "\n\nРаскладка для неё: " + profile)
                            + "\n\nВзять телефон джойстиком?")
                    .setPositiveButton("Взять джойстик", (dialog, which) -> {
                        Intent intent = new Intent(context, JoypadActivity.class);

                        if (!profile.isEmpty()) {
                            intent.putExtra("profile", profile);
                        }

                        context.startActivity(intent);
                    })
                    .setNegativeButton("Потом", null)
                    .show();
        } catch (Exception broken) {
            status.setText(json);
        }
    }

    private void send(byte type, String id) {
        status.setText("Просим компьютер…");
        ask(type, id);
    }

    private void ask(byte type, String payload) {
        final OutputStream outgoing;
        final int generation;
        synchronized (this) { outgoing = out; generation = connectionGeneration; }
        new Thread(() -> {
            try {
                if (outgoing != null && generation == connectionGeneration) {
                    Wire.writeText(outgoing, type, payload);
                }
            } catch (Exception broken) {
                connectionStatus(generation, "Связь с компьютером оборвалась.");
            }
        }, "pc-ask").start();
    }

    private void connectionStatus(int generation, String text) {
        ui.post(() -> { if (generation == connectionGeneration) status.setText(text); });
    }

    synchronized void stop() {
        running = false;
        connectionGeneration++;
        live = false;
        close();
    }

    /**
     * Находит компьютер и подключается к нему: сначала кабель, потом Wi-Fi.
     *
     * Кабель пробуем первым нарочно — он быстрее и не зависит от того, в какой сети
     * телефон: `adb reverse` делает компьютер доступным по 127.0.0.1 самого телефона.
     * Нет кабеля — кричим в свою сеть «кто здесь DustoreX?» тем же маяком, которым
     * пользуется джойстик, и идём по найденному адресу. Адрес человек вводить не должен:
     * адреса меняются, и их никто не помнит.
     */
    private Socket reach(int generation) {
        if (!running || generation != connectionGeneration) return null;
        wifi = null;
        Socket cable = new Socket();
        try {
            connectionStatus(generation, "Ищем компьютер по кабелю…");
            cable.connect(new InetSocketAddress("127.0.0.1", PORT), 2500);
            cable.setTcpNoDelay(true);
            if (!running || generation != connectionGeneration) { cable.close(); return null; }
            return cable;
        } catch (Exception noCable) {
            try { cable.close(); } catch (Exception ignored) { }
            // Кабеля нет — это не поломка, а обычный случай: сидят с дивана.
        }
        if (!running || generation != connectionGeneration) return null;
        connectionStatus(generation, "Кабеля нет — ищем компьютер по Wi-Fi…");

        // Прошлый адрес пробуем первым: в домашней сети он обычно тот же, и это быстрее
        // широковещательного опроса.
        String remembered = getContext()
                .getSharedPreferences("exepad", Context.MODE_PRIVATE)
                .getString("host", null);

        if (remembered != null) {
            Socket again = dial(remembered, generation);

            if (again != null) {
                return again;
            }
        }

        if (!running || generation != connectionGeneration) return null;
        String host = discover(generation);

        if (host == null) {
            return null;
        }

        return dial(host, generation);
    }

    /** Один звонок по адресу в сети. */
    private Socket dial(String host, int generation) {
        Socket over = new Socket();
        try {
            if (!running || generation != connectionGeneration) { over.close(); return null; }
            over.connect(new InetSocketAddress(host, PORT), 2500);
            over.setTcpNoDelay(true);
            if (!running || generation != connectionGeneration) { over.close(); return null; }
            wifi = host;

            // Адрес запоминаем там же, где его держит джойстик: компьютер один и тот же,
            // и второй раз искать его незачем.
            getContext().getSharedPreferences("exepad", Context.MODE_PRIVATE)
                    .edit().putString("host", host).apply();

            return over;
        } catch (Exception silent) {
            try { over.close(); } catch (Exception ignored) { }
            return null;
        }
    }

    /**
     * Спрашивает сеть, где стоит компьютер с DustoreX.
     *
     * Тот же маяк, что у джойстика: широковещательный вопрос «DXPAD?» на 27187 и первый
     * же ответ «DXPAD!имя|номер». Номер из ответа — это номер джойстика; экран запуска
     * живёт на своём, поэтому берём из ответа только адрес.
     */
    private String discover(int generation) {
        try (java.net.DatagramSocket probe = new java.net.DatagramSocket()) {
            probe.setBroadcast(true);
            probe.setSoTimeout(900);

            byte[] question = "DXPAD?".getBytes(StandardCharsets.UTF_8);

            for (int round = 0; round < 4 && running && generation == connectionGeneration; round++) {
                for (java.net.InetAddress where : broadcasts()) {
                    try {
                        probe.send(new java.net.DatagramPacket(
                                question, question.length, where, BEACON));
                    } catch (Exception skip) {
                        // Один адрес не ответил — спросим по другому.
                    }
                }

                byte[] buffer = new byte[256];
                java.net.DatagramPacket answer =
                        new java.net.DatagramPacket(buffer, buffer.length);

                try {
                    probe.receive(answer);

                    String text = new String(answer.getData(), 0, answer.getLength(),
                            StandardCharsets.UTF_8);

                    if (text.startsWith("DXPAD!")) {
                        return answer.getAddress().getHostAddress();
                    }
                } catch (Exception silence) {
                    // Никто не ответил за отведённое время — спросим ещё раз.
                }
            }
        } catch (Exception broken) {
            // Сети нет вовсе.
        }

        return null;
    }

    /** Куда кричать: широковещательные адреса всех сетей телефона плюс общий. */
    private java.util.List<java.net.InetAddress> broadcasts() {
        java.util.List<java.net.InetAddress> where = new java.util.ArrayList<>();

        try {
            java.util.Enumeration<java.net.NetworkInterface> all =
                    java.net.NetworkInterface.getNetworkInterfaces();

            while (all.hasMoreElements()) {
                java.net.NetworkInterface adapter = all.nextElement();

                if (!adapter.isUp() || adapter.isLoopback()) {
                    continue;
                }

                for (java.net.InterfaceAddress address : adapter.getInterfaceAddresses()) {
                    if (address.getBroadcast() != null) {
                        where.add(address.getBroadcast());
                    }
                }
            }
        } catch (Exception ignored) {
            // Останется общий адрес ниже.
        }

        try {
            where.add(java.net.InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
            // Совсем без сети — вернём то, что есть.
        }

        return where;
    }

    private void close() {
        out = null; in = null;
        try {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (Exception ignored) {
            // Закрывать уже закрытое — не беда.
        }
    }

    private static JSONObject readMeta(File folder) {
        try {
            return new JSONObject(ExFileRead.utf8(new File(folder, "dustorex.json"),
                    ExFileRead.GAME_METADATA_BYTES));
        } catch (Exception missing) {
            return new JSONObject();
        }
    }
}
