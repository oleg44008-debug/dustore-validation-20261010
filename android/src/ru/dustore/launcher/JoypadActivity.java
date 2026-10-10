package ru.dustore.launcher;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * eXepad — телефон вместо геймпада.
 *
 * Игра идёт на компьютере, как шла: Роблокс, Майнкрафт, Мортал Комбат — что угодно, хоть
 * не из Dustore вовсе. Телефон здесь — только руки: он рисует стики и кнопки, а каждое
 * касание уезжает по кабелю и нажимается на компьютере настоящей клавишей.
 *
 * Порядок такой: сначала связь, потом выбор. Список раскладок присылает компьютер — там
 * они и живут, вместе с клавишами и знаками, — поэтому своя раскладка, собранная в
 * лаунчере, появляется здесь сама, без обновления приложения. Раньше список был вбит
 * прямо тут, пятью строками, и своя раскладка попасть в него не могла в принципе.
 */
public final class JoypadActivity extends Activity implements PadView.Listener {

    private static final int PORT = 27186;
    private static final int BEACON = 27187;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private PadView pad;
    private PadPanel panel;
    private TextView opener;
    private ScrollView picker;
    private LinearLayout column;
    private TextView status;

    /** Список раскладок с компьютера — нужен и экрану выбора, и панели в игре. */
    private volatile JSONArray profileList;

    private Socket socket;
    private OutputStream out;
    private DataInputStream in;

    private volatile boolean running;
    private volatile String profile;
    private volatile boolean playing;

    /** Подключились по сети, а не по кабелю: тогда нужен код с экрана компьютера. */
    private volatile boolean wifi;

    /** Код подключения — вводится один раз и запоминается. */
    private volatile String code;

    /** Адрес найденного компьютера — чтобы в следующий раз не искать заново. */
    private volatile String found;

    /** Пришла ли раскладка. До неё любое сообщение от компьютера — это отказ по коду. */
    private volatile boolean greeted;

    /** Идёт трансляция игры с компьютера (eXeportal), а не просто джойстик. */
    private volatile boolean portal;

    /** Картинка, в которую разжимаются кадры: одна на всю игру, а не новая на каждый. */
    private android.graphics.Bitmap reuse;

    /** Руль от наклона телефона — включается в панели. */
    private Tilt tilt;
    private boolean tiltWanted;
    private boolean foreground;

    /** Звук игры с компьютера. Молчит, пока звук не придёт. */
    private final Speaker speaker = new Speaker();

    /** Номер руля в текущей раскладке, или −1, если руля в ней нет. */
    private volatile int wheelIndex = -1;

    /**
     * Нажатия ждут отправки здесь: трогают экран в потоке интерфейса, где Android сокеты
     * запрещает. Очередь ограничена нарочно — при заторе пропадает самое старое движение
     * стика, а не всё приложение.
     */
    private final ArrayBlockingQueue<Packet> outbox = new ArrayBlockingQueue<>(256);

    private static final class Packet {
        final byte type;
        final byte[] payload;

        Packet(byte type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        goFullScreen();

        profile = getIntent().getStringExtra("profile");
        portal = getIntent().getBooleanExtra("portal", false);

        recall();

        tilt = new Tilt(this, value -> {
            // Наклон едет тем же письмом, что и палец по рулю: компьютеру всё равно, чем
            // повернули, — он видит положение руля.
            int index = wheelIndex;

            if (index >= 0) {
                onControl(index, true, value, 0);

                if (pad != null) {
                    pad.setKnob(index, value / 1000f, 0f);
                }
            }
        });

        setContentView(buildPicker());

        running = true;
        new Thread(this::network, "joypad-net").start();
        new Thread(this::writer, "joypad-out").start();

        if (profile != null && !profile.isEmpty()) {
            showPad();
        }
    }

    // ------------------------------------------------------------------- выбор ----

    /** Экран выбора раскладки. Пока список не пришёл — на нём только строка состояния. */
    private View buildPicker() {
        picker = new ScrollView(this);
        picker.setBackgroundColor(Ui.BACK);
        picker.setClipToPadding(false);

        // Экран выбора открывается ещё до полноэкранного режима: без врезок заголовок
        // уезжает под часы, а последняя раскладка — под навигационную полосу.
        Ui.fit(picker, true, true, true);

        column = Ui.column(this);
        column.setPadding(Ui.dp(this, Ui.S5), Ui.dp(this, Ui.S5),
                Ui.dp(this, Ui.S5), Ui.dp(this, Ui.S6));

        status = Ui.paragraph(this, "Ищем компьютер по кабелю…", Ui.T_LABEL, Ui.DIM_FIXED);

        picker.addView(column);
        rebuild(null);

        return picker;
    }

    /** Собирает список заново: заголовок, строка состояния и раскладки, если пришли. */
    private void rebuild(JSONArray profiles) {
        column.removeAllViews();

        LinearLayout crown = Ui.row(this);
        crown.setGravity(Gravity.CENTER_VERTICAL);

        android.widget.ImageView mark = new android.widget.ImageView(this);
        mark.setImageDrawable(Ui.icon(this, "gamepad", 32, Ui.ACCENT));

        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 32), Ui.dp(this, 32));
        markParams.rightMargin = Ui.dp(this, Ui.S3);
        crown.addView(mark, markParams);

        crown.addView(Ui.title(this, "eXepad", Ui.T_DISPLAY, Ui.TEXT));
        LinearLayout.LayoutParams publisherParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        publisherParams.leftMargin = Ui.dp(this, Ui.S3);
        crown.addView(Ui.brandMark(this), publisherParams);
        column.addView(crown);

        TextView subtitle = Ui.paragraph(this,
                "Телефон становится джойстиком для компьютера. Откройте игру на компьютере, "
                        + "выберите раскладку — и играйте: каждая кнопка здесь нажимает "
                        + "настоящую клавишу там.", Ui.T_LABEL, Ui.DIM_FIXED);
        subtitle.setPadding(0, Ui.dp(this, Ui.S3), 0, Ui.dp(this, Ui.S4));
        column.addView(subtitle);

        // Состояние связи — карточкой с точкой: зелёная «на связи», тёплая «ищем». Строка
        // состояния живёт внутри неё и меняется по ходу.
        LinearLayout link = Ui.row(this);
        link.setGravity(Gravity.CENTER_VERTICAL);
        link.setBackground(Ui.panel(this));
        Ui.lift(link, Ui.R_CARD);
        link.setPadding(Ui.dp(this, Ui.S4), Ui.dp(this, Ui.S3), Ui.dp(this, Ui.S4), Ui.dp(this, Ui.S3));

        dot = new View(this);
        dot.setBackground(Ui.rounded(this, Ui.WARNING, 6));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 12), Ui.dp(this, 12));
        dotParams.rightMargin = Ui.dp(this, Ui.S3);
        link.addView(dot, dotParams);

        status.setPadding(0, 0, 0, 0);
        if (status.getParent() instanceof ViewGroup) {
            ((ViewGroup) status.getParent()).removeView(status);
        }
        link.addView(status, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        column.addView(link, Ui.wide(this, Ui.S2));

        if (profiles == null || profiles.length() == 0) {
            return;
        }

        column.addView(Ui.gap(this, Ui.S3));

        // Плитки в две колонки: каждая раскладка нарисована как есть, мельче. Выбирают
        // глазами — где стик, где педали — а не по слову «Гонки».
        LinearLayout row = null;
        int tiles = 0;

        for (int i = 0; i < profiles.length(); i++) {
            JSONObject item = profiles.optJSONObject(i);

            if (item == null) {
                continue;
            }

            final String name = item.optString("name", "");

            if (name.isEmpty()) {
                continue;
            }

            if (row == null || tiles % 2 == 0) {
                row = Ui.row(this);
                column.addView(row, Ui.wide(this, 0));
            }

            View tile = tile(name, item.optString("note", ""), item.optBoolean("mine", false));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.leftMargin = tiles % 2 == 0 ? 0 : Ui.dp(this, Ui.S2);
            params.rightMargin = tiles % 2 == 0 ? Ui.dp(this, Ui.S2) : 0;
            params.bottomMargin = Ui.dp(this, Ui.S3);
            row.addView(tile, params);
            tiles++;
        }

        // Нечётное число плиток: последняя занимала бы всю строку и выглядела бы иначе.
        if (tiles % 2 == 1 && row != null) {
            View filler = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.leftMargin = Ui.dp(this, Ui.S2);
            row.addView(filler, params);
        }

        TextView note = Ui.paragraph(this,
                "Свою раскладку собирают в лаунчере на компьютере: вкладка eXepad → "
                        + "«Собрать свою раскладку». Здесь она появится сразу.",
                Ui.T_MICRO, Ui.DIM);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, Ui.dp(this, Ui.S5), 0, 0);
        column.addView(note);
    }

    /** Точка состояния связи: тёплая, пока ищем, зелёная, когда компьютер ответил. */
    private View dot;

    /**
     * Плитка раскладки: превью пада, имя, подпись. Для заводских раскладок геометрия есть
     * на телефоне (та же, что у eX), своя раскладка получает плитку со знаком геймпада —
     * её кнопки известны только компьютеру.
     */
    private View tile(final String name, String note, boolean mine) {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.panel(this));
        Ui.lift(card, Ui.R_CARD);
        card.setForeground(Ui.tap(this, Ui.R_CARD));
        card.setClickable(true);
        card.setOnClickListener(view -> choose(name));

        int pad = Ui.dp(this, Ui.S3);
        card.setPadding(pad, pad, pad, pad);

        android.widget.ImageView picture = new android.widget.ImageView(this);
        picture.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        picture.setAdjustViewBounds(true);
        Ui.roundCorners(picture, Ui.R_BUTTON);

        int width = Ui.dp(this, 320);
        int height = width * 9 / 16;

        if (ExPads.known(name)) {
            picture.setImageBitmap(PadView.preview(this, ExPads.build(name).controls, width, height));
        } else {
            android.graphics.Bitmap blank = android.graphics.Bitmap.createBitmap(
                    width, height, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(blank);
            canvas.drawColor(Ui.PAD_VOID);
            android.graphics.drawable.Drawable icon = Ui.icon(this, "gamepad", 48, Ui.ACCENT);
            int size = Ui.dp(this, 48);
            icon.setBounds((width - size) / 2, (height - size) / 2,
                    (width + size) / 2, (height + size) / 2);
            icon.draw(canvas);
            picture.setImageBitmap(blank);
        }

        card.addView(picture, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, Ui.dp(this, Ui.S2), 0, 0);

        TextView title = Ui.title(this, name, Ui.T_BODY, Ui.TEXT);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (mine) {
            head.addView(Ui.chip(this, "своя", Ui.ACCENT));
        }

        card.addView(head);

        if (!note.isEmpty()) {
            TextView hint = Ui.text(this, note, Ui.T_MICRO, Ui.DIM_FIXED);
            hint.setMaxLines(2);
            hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
            hint.setPadding(0, Ui.dp(this, 2), 0, 0);
            card.addView(hint);
        }

        return card;
    }

    private void choose(String name) {
        profile = name;
        offer(Wire.MSG_SELECT, name.getBytes(StandardCharsets.UTF_8));
        showPad();
    }

    private void showPad() {
        if (playing) {
            return;
        }

        playing = true;

        pad = new PadView(this);
        pad.setListener(this);
        pad.setStatus(portal
                ? "Ждём картинку с компьютера…"
                : (profile == null ? "Ждём раскладку…" : profile));

        // Пад, поверх него кнопка вызова панели, поверх всего — сама панель.
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(pad, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new PadPanel(this, new PadPanel.Listener() {
            @Override
            public void onKey(String name, boolean down) {
                sendKey(name, down);
            }

            @Override
            public void onProfile(String name) {
                profile = name;
                offer(Wire.MSG_SELECT, name.getBytes(StandardCharsets.UTF_8));
                hidePanel();
            }

            @Override
            public void onTilt(boolean on) {
                setTilt(on);
            }

            @Override
            public void onClose() {
                hidePanel();
            }
        });

        panel.setVisibility(View.GONE);
        root.addView(panel, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Кнопка вызова — в левом верхнем углу, подальше от больших пальцев: случайно
        // открыть панель посреди боя было бы хуже, чем не иметь её вовсе.
        opener = Ui.text(this, "Клавиши", Ui.T_LABEL, Ui.TEXT);
        opener.setGravity(Gravity.CENTER_VERTICAL);
        opener.setBackground(Ui.rounded(this, Ui.alpha(Ui.CARD, 0xE0), Ui.R_CARD + Ui.S2));
        opener.setPadding(Ui.dp(this, Ui.S4), Ui.dp(this, Ui.S2),
                Ui.dp(this, Ui.S4), Ui.dp(this, Ui.S2));
        opener.setMinHeight(Ui.dp(this, Ui.TOUCH));
        opener.setMinWidth(Ui.dp(this, Ui.TOUCH));
        Ui.setIcon(opener, "keyboard", 18, Ui.ACCENT);
        opener.setOnClickListener(view -> showPanel());

        android.widget.FrameLayout.LayoutParams openerParams =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        openerParams.gravity = Gravity.START | Gravity.TOP;
        openerParams.leftMargin = Ui.dp(this, Ui.S3);
        openerParams.topMargin = Ui.dp(this, Ui.S3);
        root.addView(opener, openerParams);

        // Вырез камеры и полосы: в полноэкранном режиме они прячутся, но вырез — нет, и
        // кнопка в углу иначе оказывается ровно под ним.
        Ui.fit(opener, true, false, true);

        setContentView(root);
        goFullScreen();
        pad.redraw();
    }

    private void showPanel() {
        if (panel == null) {
            return;
        }

        panel.setProfiles(profileList, profile);
        panel.setTitle(profile == null ? "Настройка на ходу" : "Сейчас: " + profile);
        panel.setWheel(wheelIndex >= 0, tilt != null && tilt.available(),
                tilt != null && tilt.live());
        panel.setVisibility(View.VISIBLE);
        opener.setVisibility(View.GONE);
    }

    /**
     * Включает или выключает руль от наклона телефона.
     *
     * Датчик слушается только пока он нужен: постоянно включённый датчик тяжести — это
     * лишний расход батареи в игре, где батарея и так уходит на экран.
     */
    private void setTilt(boolean on) {
        tiltWanted = on;
        if (tilt == null || wheelIndex < 0) {
            return;
        }

        if (on) {
            tilt.recentre();
            tilt.start();
        } else {
            tilt.stop();
        }

        panel.setWheel(true, tilt.available(), tilt.live());
        toast(on
                ? "Руль слушает наклон телефона. Как держите сейчас — так и «прямо»."
                : "Руль снова под палец.");
    }

    private void hidePanel() {
        if (panel == null) {
            return;
        }

        panel.setVisibility(View.GONE);
        opener.setVisibility(View.VISIBLE);
        goFullScreen();
    }

    /** Любая клавиша по имени — то, чего на паде нет. */
    private void sendKey(String name, boolean down) {
        byte[] label = name.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[1 + label.length];

        payload[0] = (byte) (down ? 1 : 0);
        System.arraycopy(label, 0, payload, 1, label.length);

        offer((byte) 'k', payload);
    }

    // ------------------------------------------------------------------- сеть ----

    /**
     * Держит связь с компьютером, пока открыт экран.
     *
     * Именно держит, а не пробует один раз. Канал до компьютера поднимает лаунчер, увидев
     * телефон, и эти два события никто не выстраивал по порядку: экран джойстика вполне
     * может открыться на полсекунды раньше — и раньше здесь это значило тупик с надписью
     * «Компьютер не отвечает» до тех пор, пока человек сам не выйдет и не зайдёт снова.
     * Теперь экран просто ждёт и подключается сам, как только становится куда.
     */
    private void network() {
        int attempt = 0;

        while (running) {
            attempt++;

            try {
                Socket fresh = connect(attempt);

                if (fresh == null) {
                    throw new java.io.IOException("компьютер не найден");
                }

                socket = fresh;
                out = fresh.getOutputStream();
                in = new DataInputStream(fresh.getInputStream());

                greeted = false;
                sendHello();
                attempt = 0;
                say(wifi ? "Компьютер на связи по Wi-Fi." : "Компьютер на связи по кабелю.");

                Wire.Message message = new Wire.Message();

                while (running && Wire.read(in, message)) {
                    switch (message.type) {
                        case Wire.MSG_LAYOUT:
                            greeted = true;
                            applyLayout(new String(message.payload, StandardCharsets.UTF_8));
                            break;

                        case Wire.MSG_PROFILES:
                            greeted = true;
                            profiles(new String(message.payload, StandardCharsets.UTF_8));
                            break;

                        case Wire.MSG_AUDIO:
                            // Звук играем сразу из сетевого потока: очередь к потоку
                            // интерфейса добавила бы задержку, а её слышно как эхо.
                            speaker.play(message.payload);
                            break;

                        case Wire.MSG_FRAME:
                            // eXeportal: кадр игры, которая идёт на компьютере. Рисуем его
                            // прямо здесь, в сетевом потоке: SurfaceView это позволяет, а
                            // очередь к потоку интерфейса добавила бы задержку именно там,
                            // где она заметнее всего — под пальцем.
                            greeted = true;
                            showFrame(message.payload);
                            break;

                        case 'T':
                            String said = new String(message.payload, StandardCharsets.UTF_8);
                            toast(said);

                            // Единственное, что компьютер может сказать ДО списка раскладок,
                            // — что код не подошёл (или что он вообще нужен). Вот теперь и
                            // спрашиваем: замок на той стороне включён, а значит четыре
                            // цифры сейчас действительно на что-то влияют. Компьютер после
                            // отказа кладёт трубку сам, и следующий круг цикла поздоровается
                            // заново — уже с кодом.
                            if (!greeted && wifi) {
                                code = null;
                                askCode();
                            }

                            break;

                        case 'Q':
                            running = false;
                            break;
                    }
                }

                if (running) {
                    say("Компьютер отключился. Ждём его снова…");
                }
            } catch (Exception failure) {
                say("Ждём компьютер. Кабель воткнут, DustoreX на нём открыт?");
            }

            out = null;
            in = null;
            close();

            if (!running) {
                break;
            }

            try {
                Thread.sleep(1500);
            } catch (InterruptedException stop) {
                return;
            }
        }
    }

    /**
     * Находит компьютер и подключается к нему: сначала кабель, потом Wi-Fi.
     *
     * Кабель пробуем первым нарочно — он и быстрее, и не требует кода: `adb reverse` делает
     * компьютер доступным по 127.0.0.1 самого телефона, а такое соединение чужим быть не
     * может. Если кабеля нет, кричим в свою сеть «кто здесь DustoreX?» — адрес компьютера
     * человек вводить не должен, адреса меняются и их никто не помнит.
     */
    private Socket connect(int attempt) {
        try {
            say(attempt == 1 ? "Подключаемся по кабелю…" : "Ищем компьютер… попытка " + attempt);

            Socket cable = new Socket();
            cable.connect(new InetSocketAddress("127.0.0.1", PORT), 2500);
            cable.setTcpNoDelay(true);

            wifi = false;
            return cable;
        } catch (Exception noCable) {
            // Кабеля нет — это не беда, а обычный случай: играют с дивана.
        }

        say("Кабеля нет — ищем компьютер по Wi-Fi…");

        // Прошлый адрес пробуем первым: в домашней сети он обычно тот же, и это быстрее
        // широковещательного опроса.
        if (found != null) {
            try {
                Socket again = new Socket();
                again.connect(new InetSocketAddress(found, PORT), 1800);
                again.setTcpNoDelay(true);

                wifi = true;
                say("Компьютер там же, где в прошлый раз.");

                return again;
            } catch (Exception moved) {
                // Переехал — найдём заново.
            }
        }

        String host = discover();

        if (host == null) {
            // Самая частая причина — не «не работает», а разные сети: телефон на одном
            // Wi-Fi, компьютер на другом (или на кабеле от другого роутера). Поэтому
            // показываем СВОЙ адрес: сравнить его с адресом на вкладке eXepad человек
            // может сам, и станет видно, что сети не совпадают.
            say("Компьютера с DustoreX в этой сети не видно.\n"
                    + "Телефон сейчас: " + (self() == null ? "без Wi-Fi" : self())
                    + "\nНа компьютере, вкладка eXepad, написан его адрес — первые три числа "
                    + "должны совпадать.");

            return null;
        }

        try {
            Socket over = new Socket();
            over.connect(new InetSocketAddress(host, PORT), 4000);
            over.setTcpNoDelay(true);

            wifi = true;
            found = host;

            return over;
        } catch (Exception broken) {
            say("Компьютер найден (" + host + "), но не отвечает на " + PORT + ".");
            return null;
        }
    }

    /**
     * Спрашивает сеть, где стоит компьютер с открытым eXepad.
     *
     * Широковещательный вопрос и первый же ответ: в домашней сети компьютер с лаунчером
     * один, а выбирать из списка на телефоне, когда хочется играть, — лишний экран.
     */
    private String discover() {
        try (java.net.DatagramSocket probe = new java.net.DatagramSocket()) {
            probe.setBroadcast(true);
            probe.setSoTimeout(900);

            byte[] question = "DXPAD?".getBytes(StandardCharsets.UTF_8);

            for (int round = 0; round < 4 && running; round++) {
                for (java.net.InetAddress where : broadcasts()) {
                    try {
                        probe.send(new java.net.DatagramPacket(
                                question, question.length, where, BEACON));
                    } catch (Exception skip) {
                        // Один адрес не ответил — спросим по другому.
                    }
                }

                byte[] buffer = new byte[256];
                java.net.DatagramPacket answer = new java.net.DatagramPacket(buffer, buffer.length);

                try {
                    probe.receive(answer);

                    String text = new String(answer.getData(), 0, answer.getLength(),
                            StandardCharsets.UTF_8);

                    if (text.startsWith("DXPAD!")) {
                        String name = text.substring(6).split("\\|")[0];
                        say("Нашли компьютер: " + name);

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

    /** Свой адрес в Wi-Fi — чтобы человек мог сравнить сети, а не гадать. */
    private String self() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> all =
                    java.net.NetworkInterface.getNetworkInterfaces();

            while (all.hasMoreElements()) {
                java.net.NetworkInterface adapter = all.nextElement();

                if (!adapter.isUp() || adapter.isLoopback()) {
                    continue;
                }

                java.util.Enumeration<java.net.InetAddress> addresses = adapter.getInetAddresses();

                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();

                    if (address instanceof java.net.Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // Без сети адреса и нет.
        }

        return null;
    }

    /** Куда кричать: широковещательные адреса всех сетей телефона плюс общий. */
    private java.util.List<java.net.InetAddress> broadcasts() {
        java.util.List<java.net.InetAddress> where = new ArrayList<>();

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

    private void sendHello() throws Exception {
        JSONObject hello = new JSONObject();
        hello.put("role", "joypad");
        hello.put("model", Build.MODEL);
        // Имя телефона: компьютер запоминает его после первого верного кода.
        hello.put("device", PadIdentity.of(this));

        // По Wi-Fi компьютер спрашивает код со своего экрана у нового телефона один раз (и
        // каждый раз — если на нём включён замок). Первым делом здороваемся как есть:
        // знакомый телефон проходит без цифр, а отказ приходит раньше раскладок, и тогда
        // спрашиваем код. Известный код отправляем сразу — знакомому он не мешает.
        if (wifi && code != null) {
            hello.put("code", code);
        }

        // Раскладку в приветствии называем только если её уже выбрали: иначе компьютер
        // сначала пришлёт список, и выбор будет из настоящего, а не из вбитого сюда.
        if (profile != null && !profile.isEmpty()) {
            hello.put("profile", profile);
        }

        byte[] json = hello.toString().getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[4 + json.length];

        payload[0] = (byte) (Wire.MAGIC >> 24);
        payload[1] = (byte) (Wire.MAGIC >> 16);
        payload[2] = (byte) (Wire.MAGIC >> 8);
        payload[3] = (byte) Wire.MAGIC;
        System.arraycopy(json, 0, payload, 4, json.length);

        Wire.write(out, (byte) 'h', payload);
    }

    /** Список раскладок с компьютера — заводские и свои. */
    private void profiles(String json) {
        try {
            JSONArray list = new JSONObject(json).optJSONArray("profiles");
            profileList = list;

            ui.post(() -> {
                // В игре список нужен панели: раскладку меняют, не выходя из игры.
                if (playing) {
                    if (panel != null) {
                        panel.setProfiles(list, profile);
                    }

                    return;
                }

                status.setText("Выберите раскладку под свою игру.");
                status.setTextColor(Ui.DIM_FIXED);
                rebuild(list);
            });
        } catch (Exception broken) {
            say("Список раскладок прочитать не удалось.");
        }
    }

    private void applyLayout(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray array = root.optJSONArray("controls");
            List<PadView.Control> list = new ArrayList<>();
            int found = -1;

            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);

                    PadView.Control control = new PadView.Control();
                    control.index = item.optInt("i", i);
                    control.kind = item.optString("kind", "button");
                    control.label = item.optString("label", "");
                    control.hint = item.optString("hint", "");
                    control.icon = item.optString("ic", "");

                    // Руль запоминаем: только его можно крутить наклоном телефона.
                    if ("wheel".equals(control.icon)) {
                        found = control.index;
                    }
                    control.x = (float) item.optDouble("x", 0.5);
                    control.y = (float) item.optDouble("y", 0.5);
                    control.r = (float) item.optDouble("r", 0.1);
                    control.w = (float) item.optDouble("w", 0);
                    control.h = (float) item.optDouble("h", 0);

                    list.add(control);
                }
            }

            profile = root.optString("profile", profile);
            wheelIndex = found;

            // Раскладка без руля — наклон выключаем сам: держать датчик включённым для
            // раскладки, где его некуда приложить, незачем.
            if (found < 0 && tilt != null && tilt.live()) {
                tilt.stop();
            }

            // Create the pad and apply the layout in the same UI task. Previously the
            // network thread checked pad immediately after posting showPad(), saw null,
            // and discarded the first layout. The screen then had no working buttons.
            final boolean pointer = root.optBoolean("pointer", false);
            final String selected = root.optString("profile", "eXepad");
            ui.post(() -> {
                showPad();
                if (pad != null) {
                    pad.setControls(list, pointer);
                    pad.setStatus(selected);
                    pad.redraw();
                }
            });
        } catch (Exception broken) {
            say("Раскладку прочитать не удалось");
        }
    }

    /**
     * Спрашивает код подключения и ждёт, пока его введут.
     *
     * Один раз на компьютер: код запоминается, и со второго раза телефон подключается по
     * Wi-Fi сам. Ждём прямо здесь, в сетевом потоке, — приветствие без кода отправлять
     * бессмысленно, компьютер его отклонит.
     */
    private void askCode() {
        ui.post(() -> {
            android.widget.EditText field = new android.widget.EditText(this);
            field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            field.setHint("четыре цифры");
            field.setTextColor(Ui.TEXT);

            new android.app.AlertDialog.Builder(this)
                    .setTitle("Код с компьютера")
                    .setMessage("На компьютере откройте вкладку eXepad — там четыре цифры. "
                            + "Вводится один раз: дальше телефон подключится по Wi-Fi сам.")
                    .setView(field)
                    .setCancelable(false)
                    .setPositiveButton("Подключиться", (dialog, which) -> {
                        code = field.getText().toString().trim();
                        remember();
                    })
                    .setNegativeButton("Отмена", (dialog, which) -> {
                        running = false;
                        finish();
                    })
                    .show();
        });

        while (running && code == null) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException stop) {
                return;
            }
        }
    }

    /** Запоминает код и адрес: со второго раза Wi-Fi подключается без вопросов. */
    private void remember() {
        getSharedPreferences("exepad", MODE_PRIVATE).edit()
                .putString("code", code)
                .putString("host", found)
                .apply();
    }

    private void recall() {
        android.content.SharedPreferences saved = getSharedPreferences("exepad", MODE_PRIVATE);

        code = saved.getString("code", null);
        found = saved.getString("host", null);
    }

    /**
     * Показывает кадр игры с компьютера.
     *
     * Кадр приходит сжатым и разжимается в тот же самый Bitmap, если размеры совпали:
     * тридцать новых картинок в секунду — это тридцать сборок мусора в секунду, и рывки от
     * них видны сильнее, чем от самой сети.
     */
    private void showFrame(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) {
            return;
        }

        try {
            android.graphics.BitmapFactory.Options options =
                    new android.graphics.BitmapFactory.Options();

            options.inMutable = true;
            options.inBitmap = reuse;

            android.graphics.Bitmap picture =
                    android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);

            if (picture == null) {
                return;
            }

            reuse = picture;
            portal = true;

            if (!playing) {
                ui.post(this::showPad);
                return;
            }

            if (pad != null) {
                pad.showFrame(picture);
            }
        } catch (Exception broken) {
            // Испорченный кадр — не беда: следующий придёт через тридцатую долю секунды.
            reuse = null;
        }
    }

    /** Пишет строку и на экран выбора, и на пад — смотря что сейчас открыто. */
    private void say(String text) {
        ui.post(() -> {
            if (playing) {
                if (pad != null) {
                    pad.setStatus(text);
                    pad.redraw();
                }
            } else if (status != null) {
                status.setText(text);

                if (dot != null) {
                    boolean live = text.contains("на связи");
                    dot.setBackground(Ui.rounded(this, live ? Ui.SUCCESS : Ui.WARNING, 6));
                }
            }
        });
    }

    /**
     * Отправляет нажатия и, когда их нет, признак жизни.
     *
     * «Я тут» каждые несколько секунд нужно компьютеру: без него он не может отличить
     * игрока, который замер в меню, от телефона, унесённого в другую комнату, — а мёртвый
     * сеанс, который считается живым, не пускает на своё место новый.
     *
     * Оборванная запись больше не убивает поток: сокет переподключит network(), а очередь
     * нажатий должна дожить до новой связи.
     */
    private void writer() {
        while (running) {
            try {
                Packet packet = outbox.poll(4, TimeUnit.SECONDS);
                OutputStream stream = out;

                if (stream == null) {
                    continue;
                }

                if (packet == null) {
                    Wire.write(stream, (byte) 'p', new byte[0]);
                } else {
                    Wire.write(stream, packet.type, packet.payload);
                }
            } catch (InterruptedException stop) {
                return;
            } catch (Exception broken) {
                // Связь порвалась — ждём, пока её поднимут заново.
                try {
                    Thread.sleep(300);
                } catch (InterruptedException stop) {
                    return;
                }
            }
        }
    }

    private void offer(byte type, byte[] payload) {
        Packet packet = new Packet(type, payload);

        if (!outbox.offer(packet)) {
            outbox.poll();
            outbox.offer(packet);
        }
    }

    // ------------------------------------------------------------ события пада ----

    @Override
    public void onControl(int index, boolean down, int x, int y) {
        byte[] payload = new byte[6];
        payload[0] = (byte) index;
        payload[1] = (byte) (down ? 1 : 0);
        putShort(payload, 2, x);
        putShort(payload, 4, y);

        offer((byte) 'c', payload);
    }

    @Override
    public void onLook(int dx, int dy) {
        if (dx == 0 && dy == 0) {
            return;
        }

        byte[] payload = new byte[4];
        putShort(payload, 0, dx);
        putShort(payload, 2, dy);

        offer((byte) 'l', payload);
    }

    @Override
    public void onPointer(int perMilleX, int perMilleY, int button, int state) {
        byte[] payload = new byte[6];
        putShort(payload, 0, perMilleX);
        putShort(payload, 2, perMilleY);
        payload[4] = (byte) button;
        payload[5] = (byte) state;

        offer((byte) 't', payload);
    }

    private static void putShort(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value >> 8);
        buffer[offset + 1] = (byte) value;
    }

    // ----------------------------------------------------------------- прочее ----

    private void toast(String value) {
        if (value != null && value.trim().length() > 0) {
            runOnUiThread(() -> Toast.makeText(this, value, Toast.LENGTH_SHORT).show());
        }
    }

    private void goFullScreen() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            goFullScreen();
            resumeTilt();
        } else releaseAllInputs();
    }

    /** Назад с пада — обратно к выбору раскладки, а не сразу из eXepad. */
    @Override
    public void onBackPressed() {
        if (panel != null && panel.getVisibility() == View.VISIBLE) {
            hidePanel();
            return;
        }

        if (playing) {
            releaseAllInputs();
            tiltWanted = false;
            playing = false;
            pad = null;
            panel = null;
            opener = null;
            profile = null;

            setContentView(picker);
            status.setText("Выберите раскладку под свою игру.");

            // Компьютеру о смене раскладки скажет следующий выбор; пока пусть держит
            // прежнюю — отпускать клавиши он умеет сам.
            return;
        }

        super.onBackPressed();
    }

    /** Уходя с экрана, отпускаем датчик: он тратит батарею и без игры не нужен. */
    @Override
    protected void onPause() {
        foreground = false;
        releaseAllInputs();
        super.onPause();

        if (tilt != null) {
            tilt.stop();
        }
    }

    @Override
    protected void onDestroy() {
        releaseAllInputs();
        running = false;

        if (tilt != null) {
            tilt.stop();
        }

        speaker.close();
        close();
        super.onDestroy();
    }

    private void close() {
        releaseAllInputs();
        try {
            if (out != null) {
                Wire.write(out, (byte) 'b', new byte[0]);
            }
        } catch (Exception ignored) {
            // Прощание в мёртвый сокет — не беда.
        }

        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
            // Уже закрыт.
        }
    }

    private void releaseAllInputs() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(this::releaseAllInputs);
            return;
        }
        if (pad != null) pad.releaseAllInputs();
        if (panel != null) panel.releaseAllInputs();
        if (tilt != null) {
            if (tilt.live() && wheelIndex >= 0) {
                onControl(wheelIndex, false, 0, 0);
                if (pad != null) pad.setKnob(wheelIndex, 0, 0);
            }
            tilt.stop();
        }
    }

    @Override protected void onResume() {
        super.onResume(); foreground = true;
        if (hasWindowFocus()) resumeTilt();
    }

    private void resumeTilt() {
        if (foreground && playing && tiltWanted && wheelIndex >= 0 && tilt != null && tilt.available()) tilt.start();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        releaseAllInputs();
        super.onConfigurationChanged(configuration);
    }
}
