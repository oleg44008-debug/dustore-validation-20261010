package ru.dustore.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Игра для Windows на экране телефона — экран системы eX.
 *
 * Здесь сходится всё: наш экран ({@link ExDisplay}) открывается прямо в приложении, слой
 * Windows запускается внутрь него, кадры игры приезжают в нашу память и рисуются на стекле
 * телефона, а пальцы уходят обратно тем же путём. Поверх кадра — те же пады, что в eXepad
 * ({@link PadView}, раскладки {@link ExPads}): стик, кнопки, камера пальцем, тап как мышь.
 *
 * Запускается тремя способами и всеми одинаково: с карточки игры в библиотеке, со значка
 * игры-приложения на рабочем столе (стаб шлёт {@link #ACTION_PLAY} с id игры) и по кабелю
 * с компьютера (папка с описанием).
 */
public final class ExActivity extends Activity {

    static final String ACTION_PLAY = "ru.exepc.ex.PLAY";

    /** Папка с игрой и описанием рядом (dustorex.json или ex.json). */
    static final String EXTRA_FOLDER = "folder";

    /** Id игры из {@link ExGame} — так зовёт стаб со значка. */
    static final String EXTRA_GAME = "game";

    /** «screen» — только открыть экран, без игры: для проверок по кабелю. */
    static final String EXTRA_MODE = "mode";

    /**
     * Размер экрана игры.
     *
     * Не размер телефона нарочно: игры на Windows считают, что экран не меняется, а
     * телефон бывает и с вырезом, и с полосой. 800×450 оставляет запас для перевода
     * Windows-игры на телефоне. Вписываем картинку в экран с полями.
     */
    static final int GAME_WIDTH = 800;
    static final int GAME_HEIGHT = 450;

    // A 3D Unity player spent most of its time below 30 fps at 800x450 on the
    // test phone. Keep the readable size for other games and reduce only Unity's
    // render target; both the virtual desktop and touch coordinates must agree.
    static int gameWidth(ExRecipe recipe) {
        return recipe != null && "Unity".equals(recipe.engine) ? 640 : GAME_WIDTH;
    }

    static int gameHeight(ExRecipe recipe) {
        return recipe != null && "Unity".equals(recipe.engine) ? 360 : GAME_HEIGHT;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());

    private ExDisplay display;
    private PadView pad;
    private PadPanel keys;
    private LinearLayout bar;
    private TextView status;
    private TextView hud;
    private TextView inputToggle;
    private TextView log;
    private ScrollView logBox;
    private TextView logClose;
    private volatile boolean logVisible;
    private final java.util.ArrayDeque<String> recentLog = new java.util.ArrayDeque<>();
    private int recentLogCharacters;
    private boolean logQueued;
    private final Runnable refreshLog = this::renderLog;

    private Process game;
    private ExAudio audio;
    private Thread worker;
    private volatile ExWork.Scope launchScope;
    private volatile boolean watching;
    private volatile boolean sessionHeld;
    private final java.util.concurrent.atomic.AtomicInteger memoryGuardGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    private File folder;
    private String name;
    private String exe;
    private ExRecipe recipe;
    private JSONObject legacyEnv;

    private ExPads.Layout layout;
    private boolean[][] held = new boolean[0][];
    private volatile boolean padsOn = true;
    /** 0 = detect pointer capture, 1 = look by dragging, 2 = tap to click. */
    private volatile int inputMode;

    private volatile boolean busy;
    private volatile boolean stopping;

    // ---- кадры -------------------------------------------------------------------

    private volatile boolean dirty;
    private volatile boolean pictureNoticeQueued;
    private final Object frameSignal = new Object();
    private volatile boolean painting;
    private boolean nativeWine;
    private Thread painter;
    private int framesThisSecond;
    private int gameFramesAtSecond;
    private long secondStarted;
    private volatile int fps;
    private volatile int gameFps;
    private AndroidUltra ultra;
    private volatile boolean renderForeground;
    private volatile int renderGeneration;
    private volatile String gpuDescription = "графика eX";
    private final InputLedger input = new InputLedger(this::pressRaw);

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        String path = getIntent().getStringExtra(EXTRA_FOLDER);
        String id = getIntent().getStringExtra(EXTRA_GAME);

        // The window is exported: the computer opens it over the cable and a game's own icon
        // opens it by id. A folder named from outside must be one of the launcher's game
        // folders — anything else would run a stranger's build with this app's files and rights.
        if (path != null) {
            File asked = new File(path), external = getExternalFilesDir(null);
            boolean owned = GameFolders.contains(Ex.localGames(this), asked)
                    || external != null && GameFolders.contains(new File(external, "games"), asked);
            if (!owned) path = null;
        }

        if (path == null && id != null) {
            File byId = ExGame.folderOf(this, id);
            path = byId == null ? null : byId.getAbsolutePath();
        }

        folder = path == null ? null : new File(path);

        setContentView(build());
        ultra = new AndroidUltra(this, pad.getHolder());

        Ex.syncState(this);
        read();
        applyLayout(preferredLayout());

        if ("screen".equals(getIntent().getStringExtra(EXTRA_MODE))) {
            if (!ExSession.acquire(this)) { state("Другая игра eX уже работает."); return; }
            sessionHeld = true;
            state("Экран eX открыт — ждём, кто в него нарисует.");
            openScreen();
            return;
        }

        if (folder == null || !folder.isDirectory()) {
            state("Папка с игрой не найдена. Эмулируйте игру заново.");
            return;
        }

        start();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ------------------------------------------------------------------ экран ----

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        pad = new PadView(this);
        pad.useHardwareCanvas();
        pad.setSubtitle("eX · игра работает на телефоне");
        pad.setListener(new PadView.Listener() {

            @Override
            public void onControl(int index, boolean down, int x, int y) {
                control(index, down, x, y);
            }

            @Override
            public void onLook(int dx, int dy) {
                if (display != null && layout != null) {
                    display.relativeMove(Math.round(dx * layout.look), Math.round(dy * layout.look));
                }
            }

            @Override
            public void onPointer(int perMilleX, int perMilleY, int button, int state) {
                pointer(perMilleX, perMilleY, state);
            }
        });

        root.addView(pad, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Что происходит — крупно и по центру, пока картинки ещё нет.
        status = Ui.text(this, "Готовим систему…", 15, Ui.TEXT);
        status.setGravity(Gravity.CENTER);
        status.setPadding(Ui.dp(this, 40), 0, Ui.dp(this, 40), 0);
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Строка о системе слева сверху: кадры в секунду и чем рисуем. Мелко и тускло —
        // она для того, кто хочет знать, а не для того, кто играет.
        hud = Ui.text(this, "eX", 10, Ui.DIM_FIXED);
        hud.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        hud.setBackground(Ui.rounded(this, 0x66000000, 8));
        FrameLayout.LayoutParams hudParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        hudParams.setMargins(Ui.dp(this, 8), Ui.dp(this, 6), 0, 0);
        root.addView(hud, hudParams);
        hud.setMaxLines(2);
        hud.setMaxWidth(Ui.dp(this, Math.max(120, getResources().getConfiguration().screenWidthDp - 330)));
        hud.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hud.setContentDescription("Диагностика отрисовки eX");
        Ui.fit(hud, true, false, true);

        // Полоска управления справа сверху: выйти, любая клавиша, пады, журнал. Справа —
        // потому что окно Windows обычно оказывается слева сверху, и там его заголовок.
        bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.rounded(this, 0xB00E0E14, 10));
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));

        bar.addView(small("⌨", "Открыть клавиши и раскладки", v -> showKeys(true)));
        bar.addView(small("🎮", "Показать или скрыть пады", v -> togglePads()));
        inputToggle = small("⇄", "Режим ввода: автоматически. Нажмите для смены", v -> cycleInputMode());
        bar.addView(inputToggle);
        bar.addView(small("≡", "Открыть или закрыть журнал игры", v -> toggleLog()));
        bar.addView(small("✕", "Завершить игру и вернуться", v -> finish()));

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        barParams.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 8), 0);
        root.addView(bar, barParams);
        Ui.fit(bar, true, false, true);

        logBox = new ScrollView(this);
        logBox.setBackgroundColor(0xE0000000);
        logBox.setVisibility(View.GONE);

        log = Ui.text(this, "", 11, Ui.MUTED);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setPadding(Ui.dp(this, 14), Ui.dp(this, 48), Ui.dp(this, 14), Ui.dp(this, 14));
        logBox.addView(log);

        root.addView(logBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        logClose = small("✕", "Закрыть журнал и вернуться к игре", v -> toggleLog());
        logClose.setVisibility(View.GONE);
        FrameLayout.LayoutParams logCloseParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        logCloseParams.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 8), 0);
        root.addView(logClose, logCloseParams); Ui.fit(logClose, true, false, true);

        keys = new PadPanel(this, new PadPanel.Listener() {

            @Override
            public void onKey(String key, boolean down) {
                input.set("panel:" + key, key, down);
            }

            @Override
            public void onProfile(String profile) {
                getSharedPreferences("ex-controls", MODE_PRIVATE).edit()
                        .putString(folder.getAbsolutePath(), profile).apply();
                applyLayout(profile);
                showKeys(false);
            }

            @Override
            public void onTilt(boolean on) {
                // Руля от наклона в eX нет: машиной здесь правят пальцем по нарисованному рулю.
            }

            @Override
            public void onClose() {
                showKeys(false);
            }
        });

        keys.setTitle("Клавиши и пады");
        keys.setWheel(false, false, false);
        keys.setVisibility(View.GONE);

        root.addView(keys, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return root;
    }

    private TextView small(String label, String description, View.OnClickListener action) {
        TextView button = Ui.text(this, label, 20, Ui.TEXT);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(Ui.dp(this, 48)); button.setMinHeight(Ui.dp(this, 48));
        button.setContentDescription(description);
        button.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        button.setBackground(Ui.rounded(this, 0x40FFFFFF, 8));
        button.setOnClickListener(action);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Ui.dp(this, 6);
        button.setLayoutParams(params);

        return button;
    }

    private void showKeys(boolean show) {
        releaseAllInputs();
        keys.setVisibility(show ? View.VISIBLE : View.GONE);
        bar.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void toggleLog() {
        logVisible = logBox.getVisibility() != View.VISIBLE;
        logBox.setVisibility(logVisible ? View.VISIBLE : View.GONE);
        logClose.setVisibility(logVisible ? View.VISIBLE : View.GONE);
        bar.setVisibility(logVisible || keys.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        if (logVisible) renderLog();
    }

    /** Пады можно спрятать: в меню игры они мешают, а тап по экрану работает и без них. */
    private void togglePads() {
        padsOn = !padsOn;
        releaseAll();

        if (layout != null) {
            pad.setControls(padsOn ? layout.controls : new java.util.ArrayList<>(),
                    padsOn ? layout.pointer : true);
            pad.setStatus(padsOn ? layout.name : "пады спрятаны · тап = мышь");
            pad.redraw();
        }
    }

    /** Some games capture the cursor; others need an ordinary pointer. */
    private void cycleInputMode() {
        inputMode = (inputMode + 1) % 3;
        releaseAllInputs();
        inputToggle.setContentDescription("Режим ввода: " + (inputMode == 1 ? "камера" : inputMode == 2 ? "указатель" : "автоматически") + ". Нажмите для смены");
        inputToggle.setSelected(inputMode != 0);
        pad.setForcePointer(inputMode == 2);
        pad.setCameraMode(inputMode == 1
                || (inputMode == 0 && display != null && display.locked()));
        refreshHud();
    }

    // ------------------------------------------------------------------- пады ----

    private void applyLayout(String profile) {
        releaseAll();
        layout = ExPads.build(profile);
        held = new boolean[layout.controls.size()][4];

        pad.setControls(padsOn ? layout.controls : new java.util.ArrayList<>(),
                padsOn ? layout.pointer : true);
        pad.setStatus(layout.name);
        pad.redraw();

        try {
            JSONArray list = new JSONArray();

            for (String known : ExPads.NAMES) {
                JSONObject item = new JSONObject();
                item.put("name", known);
                item.put("note", ExPads.note(known));
                item.put("mine", false);
                list.put(item);
            }

            keys.setProfiles(list, layout.name);
        } catch (Exception ignored) {
            // Список раскладок — удобство; без него панель всё равно нажимает клавиши.
        }
    }

    /** Кнопка или стик пада: у кнопки одна клавиша, у стика четыре по направлению. */
    private void control(int index, boolean down, int x, int y) {
        if (layout == null || index < 0 || index >= layout.controls.size()) {
            return;
        }

        String[] bound = layout.keysOf(index);
        PadView.Control control = layout.controls.get(index);

        if ("button".equals(control.kind)) {
            if (bound.length > 0) {
                input.set("button:" + index, bound[0], down);
            }

            return;
        }

        if (!down) {
            for (int slot = 0; slot < 4; slot++) {
                hold(index, slot, false);
            }

            return;
        }

        hold(index, 0, y < -ExPads.DEAD_ZONE);
        hold(index, 1, y > ExPads.DEAD_ZONE);
        hold(index, 2, x < -ExPads.DEAD_ZONE);
        hold(index, 3, x > ExPads.DEAD_ZONE);
    }

    private void hold(int index, int slot, boolean down) {
        String[] bound = layout.keysOf(index);

        if (slot >= bound.length || index >= held.length || held[index][slot] == down) {
            return;
        }

        held[index][slot] = down;

        if (bound[slot] != null && !bound[slot].isEmpty()) {
            input.set("axis:" + index + ":" + slot, bound[slot], down);
        }
    }

    /** Отпускает всё зажатое — при смене раскладки, иначе газ уехал бы в новую. */
    private void releaseAll() {
        input.releaseAll();
        for (boolean[] control : held) java.util.Arrays.fill(control, false);
    }

    private void releaseAllInputs() {
        if (pad != null) pad.releaseAllInputs();
        if (keys != null) keys.releaseAllInputs();
        releaseAll();
    }

    private void pressRaw(String key, boolean down) {
        if (display == null || key == null) {
            return;
        }

        switch (key) {
            case "MOUSE_L":
                display.pointerButton(0x110, down);
                return;
            case "MOUSE_R":
                display.pointerButton(0x111, down);
                return;
            case "MOUSE_M":
                display.pointerButton(0x112, down);
                return;
            case "WHEEL_UP":
            case "WHEEL_DOWN":
                if (down) {
                    display.wheel("WHEEL_UP".equals(key));
                }
                return;
            default:
                display.key(key, down);
        }
    }

    /**
     * Палец по кадру как мышь.
     *
     * Касание сразу нажимает левую кнопку: в играх на телефоне «навести, не нажимая» не
     * бывает, а «ткнуть в кнопку» бывает постоянно. Если игра заперла указатель (смотрит
     * по сторонам сдвигами), тап переставлять его не пытается — уходит только сдвиг.
     */
    private void pointer(int perMilleX, int perMilleY, int state) {
        if (display == null) {
            return;
        }

        int x = perMilleX * gameWidth(recipe) / 1000;
        int y = perMilleY * gameHeight(recipe) / 1000;

        display.pointerMove(x, y);

        if (state == 1) {
            input.set("pointer:left", "MOUSE_L", true);
        } else if (state == 2) {
            input.set("pointer:left", "MOUSE_L", false);
        }
    }

    // ------------------------------------------------------------- описание ----

    /** Описание, приехавшее вместе с игрой: что запускать и как её звать. */
    private void read() {
        name = folder == null ? "Игра" : folder.getName();
        exe = "";

        if (folder == null) {
            return;
        }

        JSONObject meta = readJson(new File(folder, "ex.json"));

        if (meta == null) {
            meta = readJson(new File(folder, "dustorex.json"));
        }

        if (meta == null) {
            return;
        }

        name = meta.optString("title", name);
        exe = meta.optString("exe", "");

        JSONObject recipeJson = meta.optJSONObject("recipe");

        if (recipeJson != null) {
            recipe = ExRecipe.fromJson(recipeJson);

            if (exe.isEmpty() && recipe.exe != null) {
                exe = recipe.exe;
            }
        } else {
            // Описание с компьютера старого образца: раскладка и окружение лежат рядом.
            legacyEnv = meta.optJSONObject("env");
            recipe = new ExRecipe();
            recipe.profile = ExPads.suggest(meta.optString("engine", null));
            recipe.exe = exe;
        }
    }

    private static JSONObject readJson(File file) {
        try {
            return new JSONObject(ExFileRead.utf8(file, ExFileRead.GAME_METADATA_BYTES));
        } catch (Exception missing) {
            return null;
        }
    }

    // ------------------------------------------------------------------ запуск ----

    private void start() {
        if (busy) {
            return;
        }

        // On Samsung's low-memory policy a large Wine process can evict a VPN or
        // antivirus before Android evicts the foreground game. Check before any
        // components are launched; a cancelled run must leave those services alone.
        if (ExMemory.warnBeforeLaunch(this)) {
            Ex.log(this, "Предупреждение перед запуском: "
                    + ExMemory.snapshot(this, ExMemory.read()));
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Недостаточно свободной памяти")
                    .setMessage("Запуск Windows-игры сейчас может закрыть VPN, антивирус "
                            + "и другие фоновые приложения. Освободите память или "
                            + "выберите менее требовательную игру.")
                    .setPositiveButton("Отмена", (dialog, which) -> finish())
                    .setNegativeButton("Запустить всё равно", (dialog, which) -> startUnchecked())
                    .setOnCancelListener(dialog -> finish())
                    .show();
            return;
        }

        startUnchecked();
    }

    /** Stop only after sustained critical RAM pressure; swap percentage alone is not a kill signal. */
    private void watchMemory() {
        final int generation = memoryGuardGeneration.incrementAndGet();
        new Thread(() -> {
            int lowRamSamples = 0;
            long lastReport = 0;
            while (sessionHeld && !stopping && generation == memoryGuardGeneration.get()) {
                long[] memory = ExMemory.read();
                long now = android.os.SystemClock.elapsedRealtime();
                if (lastReport == 0 || now - lastReport >= 5000) {
                    Ex.log(this, new java.util.Date() + " " + ExMemory.snapshot(this, memory)
                            + ", guestWindowCommitsPerSecond=" + gameFps + ", composedFramesPerSecond=" + fps);
                    lastReport = now;
                }
                lowRamSamples = ExMemory.lowRam(memory) ? lowRamSamples + 1 : 0;
                if (lowRamSamples >= 8 && sessionHeld && !stopping
                        && generation == memoryGuardGeneration.get()) {
                        Ex.log(this, "Критическая память после 2 секунд: "
                                + ExMemory.snapshot(this, memory));
                        state("eX остановил игру после устойчивой нехватки памяти. "
                                + "Проверьте VPN и антивирус.");
                        stopping = true;
                        if (game != null) game.destroy();
                        Ex.stopOwnChildren();
                        shutdown(null);
                        return;
                }
                try { Thread.sleep(250); }
                catch (InterruptedException stopped) { return; }
            }
        }, "ex-memory").start();
    }

    private void startUnchecked() {
        if (busy) return;

        busy = true;
        stopping = false;
        ExWork.Scope operation = new ExWork.Scope((index, count, text) -> state(text));
        launchScope = operation;
        worker = new Thread(() -> {
            try (ExWork.Scope ignored = operation) {
                operation.enter();
                long deadline = android.os.SystemClock.uptimeMillis() + 30000;
                if (ExSession.busy()) state("Ждём завершения предыдущего сеанса eX…");
                while (!stopping && !ExSession.acquire(this)) {
                    ExWork.check();
                    if (android.os.SystemClock.uptimeMillis() >= deadline) {
                        state("Закройте другую игру eX и повторите запуск.");
                        return;
                    }
                    Thread.sleep(300);
                }
                if (stopping) { ExSession.release(this); return; }
                sessionHeld = true;
                watchMemory();
                work();
            }
            catch (ExWork.Cancelled cancelled) { }
            catch (Exception error) { if (!stopping) state("Не удалось запустить игру: " + error.getMessage()); }
            finally {
                try {
                    if (!watching) ExWork.cleanup(this::cleanupSession);
                } finally {
                    launchScope = null;
                    // singleTask retries reuse this owner; cleanup must finish before it can enter again.
                    if (!watching) busy = false;
                }
            }
        }, "ex-game");
        worker.start();
    }

    /** Папка, из которой игра пойдёт на самом деле, — всегда внутренняя. */
    private File runFolder;

    /**
     * Переносит сборку во внутреннюю память и возвращает тот же файл, но уже там.
     *
     * Сборки, эмулированные здесь, и так лежат внутри. Привезённые компьютером лежат в
     * общем хранилище, а оттуда программу исполнять нельзя — копируем один раз, дальше
     * сверяем длину каждого файла и трогаем только то, что изменилось.
     */
    private File internal(File binary) {
        File home = Ex.localGames(this);

        if (folder.getAbsolutePath().startsWith(getFilesDir().getAbsolutePath())) {
            runFolder = folder;
            return binary;
        }

        File target = new File(home, folder.getName());
        String relative = binary.getAbsolutePath()
                .substring(folder.getAbsolutePath().length() + 1);

        state("Переносим сборку внутрь телефона…");

        if (!copyTree(folder, target)) {
            return null;
        }

        runFolder = target;
        say("сборка перенесена внутрь: " + target.getAbsolutePath());

        return new File(target, relative);
    }

    private boolean copyTree(File from, File to) {
        if (!to.exists() && !to.mkdirs()) {
            return false;
        }

        File[] children = from.listFiles();

        if (children == null) {
            return false;
        }

        for (File child : children) {
            ExWork.check();
            File mirror = new File(to, child.getName());

            if (child.isDirectory()) {
                if (!copyTree(child, mirror)) {
                    return false;
                }

                continue;
            }

            if (mirror.exists() && mirror.length() == child.length()) {
                continue;
            }

            try (InputStream in = new FileInputStream(child);
                 java.io.OutputStream out = new java.io.FileOutputStream(mirror)) {
                byte[] buffer = new byte[1 << 16];
                int read;

                while ((read = in.read(buffer)) > 0) {
                    ExWork.check();
                    out.write(buffer, 0, read);
                }
            } catch (Exception broken) {
                say("не скопировалось: " + child.getName() + " — " + broken);
                return false;
            }
        }

        return true;
    }

    /** Открывает наш экран. Возвращает null при успехе. */
    private String openScreen() {
        pictureNoticeQueued = false;
        display = new ExDisplay(Ex.runDir(this), gameWidth(recipe), gameHeight(recipe),
                new ExDisplay.Watcher() {

                    @Override
                    public void say(String line) {
                        ExActivity.this.say(line);
                    }

                    @Override
                    public void changed() {
                        dirty = true;
                        synchronized (frameSignal) { frameSignal.notify(); }
                    }
                });

        String opened = display.start();

        if (opened != null) {
            display = null;
            return opened;
        }

        painting = true;
        painter = new Thread(() -> { Prime.boostThread(); paintLoop(); }, "ex-paint");
        painter.start();

        return null;
    }

    /**
     * Кадры рисуются своим потоком и только тогда, когда пришёл новый: перерисовывать
     * неизменившееся шестьдесят раз в секунду — это греть телефон впустую, а телефон здесь
     * и так занят переводом чужого машинного кода.
     */
    private void paintLoop() {
        long lastFrame = 0;
        AndroidUltra.Hints hints = null;
        long nextHintAttempt = 0;
        int generation = -1;
        try {
        while (painting) {
            if (!renderForeground || !dirty) {
                if (hints != null && (!renderForeground || lastFrame != 0 && System.nanoTime() - lastFrame > 2_000_000_000L)) {
                    hints.close(); hints = null;
                }
                try {
                    synchronized (frameSignal) {
                        if ((!renderForeground || !dirty) && painting) frameSignal.wait(1000);
                    }
                } catch (InterruptedException stop) {
                    return;
                }

                continue;
            }

            dirty = false;
            if (generation != renderGeneration) {
                generation = renderGeneration; lastFrame = 0; secondStarted = 0; framesThisSecond = 0;
                gameFramesAtSecond = display == null ? 0 : display.gameFrames();
            }

            // Use the same nanosecond budget as the guest's Wayland callbacks.
            // A 120 Hz device can accept distinct frames above the former 60 cap.
            long now = System.nanoTime();
            long rest = UltraPolicy.waitNanos(now, lastFrame, AndroidUltra.frameBudgetNanos());
            if (rest > 0) {
                try { Thread.sleep(rest / 1_000_000L, (int) (rest % 1_000_000L)); }
                catch (InterruptedException stop) { return; }
            }
            // Считаем период от НАЧАЛА кадра. Если считать от конца render(),
            // 25–30 мс работы плюс ещё 33 мс сна режут даже лёгкую игру до 15 fps.
            if (!renderForeground || !painting) continue;
            lastFrame = System.nanoTime();
            long workStart = System.nanoTime();

            if (!AndroidUltra.hintWanted() && hints != null) { hints.close(); hints = null; }
            if (AndroidUltra.hintWanted() && hints == null && workStart >= nextHintAttempt) {
                hints = new AndroidUltra.Hints(this);
                if (!hints.active()) { hints = null; nextHintAttempt = workStart + 30_000_000_000L; }
            }

            try {
                ExDisplay screen = display;

                if (screen != null) {
                    pad.setCameraMode(inputMode == 1
                            || (inputMode == 0 && screen.locked()));
                    android.os.Trace.beginSection("dustore.ex.compose-present");
                    try {
                        Bitmap frame = screen.compose();
                        pad.showFrame(frame);
                    } finally { android.os.Trace.endSection(); }
                    countFrame();
                    long actualWork = System.nanoTime() - workStart;
                    if (ultra != null) ultra.recordFrame(actualWork);
                    if (hints != null) hints.report(actualWork);
                    if (!pictureNoticeQueued && screen.hasPicture()) {
                        pictureNoticeQueued = true;
                        ui.post(this::pictureArrived);
                    }
                }
            } catch (Exception ignored) {
                // Стекло могли забрать — на следующем кадре попробуем снова.
            }
        }
        } finally { if (hints != null) hints.close(); }
    }

    private void countFrame() {
        long now = android.os.SystemClock.uptimeMillis();

        if (secondStarted == 0) {
            secondStarted = now;
        }

        framesThisSecond++;

        if (now - secondStarted >= 1000) {
            long elapsed = now - secondStarted;
            fps = (int) (framesThisSecond * 1000L / elapsed);
            framesThisSecond = 0;
            ExDisplay screen = display;
            int totalGameFrames = screen == null ? 0 : screen.gameFrames();
            gameFps = (int) (Math.max(0, totalGameFrames - gameFramesAtSecond)
                    * 1000L / elapsed);
            gameFramesAtSecond = totalGameFrames;
            if (ultra != null) ultra.recordFps(fps, gameFps);
            secondStarted = now;
            ui.post(this::refreshHud);
        }
    }

    private void refreshHud() {
        hud.setText("eX · " + gpuDescription + " · окна игры " + gameFps
                + "/с · отрисовка " + fps + " к/с · "
                + (inputMode == 1 ? "камера" : inputMode == 2 ? "указатель"
                : display != null && display.locked() ? "авто: камера" : "авто: указатель"));
    }

    /**
     * Весь путь одной дорогой: система, экран, игра.
     *
     * Каждый шаг вслух — потому что до картинки доходит не каждая сборка, и человек имеет
     * право знать, на чём именно всё встало, вместо чёрного экрана.
     */
    private void work() {
        say("папка: " + folder.getAbsolutePath());

        int stage = Ex.stage(this);

        if (stage < Ex.STAGE_DXVK) {
            state("Готовим основу ARM64 eX — это делается один раз.");
            say("система собрана до шага " + stage + " из " + Ex.STAGE_DXVK);

            String problem = ExBuild.nativeFoundation(this, this::say);

            if (problem != null) {
                state("Система не собралась: " + problem);
                return;
            }
        }

        say(Ex.describe(this));
        gpuDescription = Ex.gpuReady(this) ? Ex.gpuName(this) : "программная графика";
        ui.post(() -> { if (ultra != null) ultra.refreshGpu(); });
        ui.post(this::refreshHud);

        File binary = exe == null || exe.isEmpty() ? null : new File(folder, exe);

        if (binary == null || !binary.exists()) {
            String found = ExRecipe.findExe(folder);
            binary = found == null ? null : new File(folder, found);
        }

        if (binary == null) {
            state("В сборке не нашлось, что запускать.");
            return;
        }

        say("что за файл: " + Ex.kindOf(binary) + ", " + (binary.length() / 1048576) + " МБ");

        File run = internal(binary);

        if (run == null) {
            state("Сборку не удалось перенести внутрь телефона: не хватает места?");
            return;
        }

        binary = run;
        File source = runFolder;

        if (recipe == null || recipe.version < ExRecipe.CURRENT_VERSION) {
            state("Обновляем настройки совместимости…");
            String relativeExe = binary.getAbsolutePath().substring(source.getAbsolutePath().length() + 1);
            recipe = ExRecipe.read(source, relativeExe);
            ExGame saved = ExGame.load(source);
            saved.recipe = recipe;
            saved.save();
            ui.post(() -> applyLayout(preferredLayout()));
            say("Настройки обновлены: " + recipe.engine + " · " + recipe.api);
        }
        if (!recipe.canRun()) {
            state(recipe.expect);
            return;
        }

        state("Готовим сборку к запуску…");
        ExPe.prepare(source, this::say);
        String dependencies = ExBuild.ensureDisplayParts(this, this::say);
        if (dependencies != null) {
            state(dependencies);
            return;
        }
        if (stopping) return;

        state("Открываем экран eX…");

        String opened = openScreen();

        if (opened != null) {
            state("Экран не открылся: " + opened);
            return;
        }

        if (stopping) return;
        state("Готовим экономный ARM64 Wine…");
        String nativeProblem = ExNative.prepare(this, this::say);
        nativeWine = nativeProblem == null;
        if (!nativeWine) {
            say("ARM64 Wine пока недоступен: " + nativeProblem + ". Используем прежнюю среду.");
            String fallbackProblem = ExBuild.installLogged(this, this::say);
            if (fallbackProblem != null) {
                state("Запасная среда не собралась: " + fallbackProblem);
                return;
            }
            restoreDesktop();
        } else {
            Ex.state(this, "ready " + Ex.describe(this));
        }
        if (stopping) return;

        state("Запускаем игру. Первый запуск долгий: код переводится на ходу.");
        // Wine's setup windows may have already triggered the first-picture callback.
        // The real game needs a fresh callback to remove this startup message.
        pictureNoticeQueued = false;
        audio = new ExAudio(this);
        String audioProblem = audio.start();
        if (audioProblem != null) say(audioProblem);

        String inside = "/games-local/" + source.getName();
        String relative = binary.getAbsolutePath()
                .substring(source.getAbsolutePath().length() + 1).replace('\\', '/');

        int width = gameWidth(recipe);
        int height = gameHeight(recipe);
        java.util.List<String> args = recipe == null ? null : recipe.launchArgs(width, height);
        String command = nativeWine
                ? ExNative.gameCommand(inside, relative, args, width, height)
                : Ex.gameCommand(inside, relative, args, width, height);
        say("Среда запуска: " + (nativeWine ? "Hangover ARM64" : "Box64 Wine")
                + "; игра: " + relative + "; разрешение: " + width + "x" + height);
        Ex.log(this, "Перед запуском: " + ExMemory.snapshot(this, ExMemory.read()));

        try {
            Map<String, String> gameSettings = settings();
            if (nativeWine) gameSettings = ExNative.environment(recipe, gameSettings);
            game = Ex.launch(this, new String[] { "/bin/bash", "-c", command }, gameSettings);
        } catch (Exception broken) {
            state("Игру не удалось запустить: " + broken.getMessage());
            return;
        }

        ui.post(this::describeTask);
        watching = true;
        new Thread(this::watch, "ex-out").start();
    }

    /** Undo the registry experiment that cannot display this Godot game. */
    private void restoreDesktop() {
        File marker = new File(Ex.rootfs(this), ".ex-desktop-800x450");
        if (!marker.isFile()) return;

        state("Восстанавливаем настройки Wine…");
        File registry = new File(Ex.runDir(this), "desktop.reg");
        String contents = "Windows Registry Editor Version 5.00\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Wine\\Explorer]\r\n"
                + "\"Desktop\"=-\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Wine\\Explorer\\Desktops]\r\n"
                + "\"Default\"=-\r\n";
        try (FileOutputStream out = new FileOutputStream(registry)) {
            out.write(0xff);
            out.write(0xfe);
            out.write(contents.getBytes(StandardCharsets.UTF_16LE));
        } catch (Exception error) {
            say("Не удалось восстановить настройки Wine: " + error.getMessage());
            return;
        }

        String result = Ex.run(this, new String[] { "/usr/bin/timeout", "--kill-after=3",
                "30", "/opt/wine/bin/wine", "regedit", "/S", "/run/ex/desktop.reg" },
                false, null);
        if (result.contains("EX_EXIT_CODE") || result.contains("EX_EXEC_FAILED")) {
            say("Восстановление Wine не завершилось: " + Ex.clean(result));
            return;
        }
        Ex.run(this, new String[] { "/usr/bin/timeout", "--kill-after=2", "10",
                "/opt/wine/bin/wineserver", "-k" }, false, null);
        Ex.stopOwnChildren();
        if (!marker.delete()) say("Не удалось снять временную настройку Wine.");
    }

    /** Настройки под конкретную сборку — то, что посчитал рецепт. */
    private Map<String, String> settings() {
        Map<String, String> env = new HashMap<>();

        if (recipe != null) {
            env.putAll(recipe.env);
        }

        if (legacyEnv != null) {
            Iterator<String> names = legacyEnv.keys();

            while (names.hasNext()) {
                String key = names.next();
                env.put(key, legacyEnv.optString(key, ""));
            }
        }

        return env;
    }

    /** Задача в «недавних» подписана именем игры и её иконкой, а не именем лаунчера. */
    private void describeTask() {
        try {
            Bitmap icon = null;
            File png = new File(folder, "icon.png");

            if (png.exists()) {
                icon = BitmapFactory.decodeFile(png.getAbsolutePath());
            }

            setTaskDescription(new ActivityManager.TaskDescription(name, icon));
        } catch (Exception ignored) {
            // Подпись задачи — удобство, а не условие работы.
        }
    }

    /** Всё, что говорит игра, — в журнал. */
    private void watch() {
        try (InputStream stream = game.getInputStream()) {
            byte[] buffer = new byte[4096];
            int read;

            while ((read = stream.read(buffer)) > 0) {
                say(new String(buffer, 0, read, StandardCharsets.UTF_8).trim());
            }
        } catch (Exception ignored) {
            // Игра закончилась — читать больше нечего.
        }

        final boolean hadPicture = display != null && display.gameFrames() > 0;
        if (audio != null) audio.stop();

        cleanupSession();
        watching = false;
        busy = false;

        if (stopping) {
            return;
        }

        ui.post(() -> {
            if (hadPicture) {
                state("Игра закрылась.");
            } else {
                state("Игра закрылась, не показав картинки. Откройте журнал — там сказано, "
                        + "на чём встало.");
                logBox.setVisibility(View.VISIBLE);
                logVisible = true; logClose.setVisibility(View.VISIBLE); bar.setVisibility(View.GONE); renderLog();
            }
        });
    }

    /** Картинка появилась — надписи больше не нужны. */
    private void pictureArrived() {
        if (display == null || !display.hasPicture()) {
            // The first Wine window may disappear before the UI callback runs.
            // Let the next real frame retry, otherwise the full-screen startup
            // message stays over the game for the entire session.
            pictureNoticeQueued = false;
            return;
        }

        if (status.getVisibility() == View.VISIBLE) {
            status.setVisibility(View.GONE);
            String title = display.title();

            if (title != null && !title.isEmpty()) {
                say("окно игры: " + title);
            }
        }
    }

    private void state(String text) {
        ui.post(() -> {
            status.setVisibility(View.VISIBLE);
            status.setText(text);
        });

        say(text);
    }

    private void say(String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }

        final String trimmed = line.trim();
        Ex.log(this, trimmed);
        synchronized (recentLog) {
            String tail = trimmed.length() > 24000 ? trimmed.substring(trimmed.length() - 24000) : trimmed;
            recentLog.addLast(tail); recentLogCharacters += tail.length() + 1;
            while (recentLog.size() > 1 && recentLogCharacters > 24000)
                recentLogCharacters -= recentLog.removeFirst().length() + 1;
            if (logVisible && renderForeground && !logQueued) {
                logQueued = true; ui.postDelayed(refreshLog, 120);
            }
        }
    }

    private void renderLog() {
        String text;
        synchronized (recentLog) {
            logQueued = false;
            if (!logVisible || isDestroyed()) return;
            StringBuilder tail = new StringBuilder(recentLogCharacters);
            for (String line : recentLog) { if (tail.length() > 0) tail.append('\n'); tail.append(line); }
            text = tail.toString();
        }
        log.setText(text);
    }

    @Override
    protected void onDestroy() {
        renderForeground = false;
        ui.removeCallbacks(refreshLog);
        if (ultra != null) ultra.close();
        stopping = true;
        if (audio != null) audio.stop();
        painting = false;
        synchronized (frameSignal) { frameSignal.notifyAll(); }
        if (painter != null) painter.interrupt();
        releaseAllInputs();

        if (game != null) {
            game.destroy();
        }

        shutdown(null);
        AndroidUltra controller = ultra;
        Thread rendering = painter;
        if (controller != null) UiWork.execute(() -> {
            try { if (rendering != null) rendering.join(2000); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            controller.saveReport();
        });

        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        renderForeground = true;
        if (logVisible) renderLog();
        renderGeneration++;
        fps = 0; gameFps = 0; AndroidUltra.noteFps(0, 0);
        if (ultra != null) ultra.resume();
        dirty = true;
        synchronized (frameSignal) { frameSignal.notifyAll(); }
    }

    @Override protected void onPause() {
        releaseAllInputs();
        renderForeground = false;
        if (ultra != null) ultra.pause();
        synchronized (frameSignal) { frameSignal.notifyAll(); }
        super.onPause();
    }

    private String preferredLayout() {
        String fallback = recipe != null ? recipe.profile : ExPads.DESKTOP;
        return folder == null ? fallback : getSharedPreferences("ex-controls", MODE_PRIVATE)
                .getString(folder.getAbsolutePath(), fallback);
    }

    private synchronized void cleanupSession() {
        if (!sessionHeld) return;
        sessionHeld = false;
        memoryGuardGeneration.incrementAndGet();
        if (game != null) game.destroy();
        if (audio != null) audio.stop();
        String wineStop;
        if (nativeWine) {
            ExNative.stop(this);
            wineStop = "";
        } else {
            wineStop = Ex.run(this, new String[] { "/usr/bin/timeout", "--kill-after=2", "10",
                    "/opt/wine/bin/wineserver", "-k" }, false, null);
        }
        if (wineStop.contains("EX_EXIT_CODE") || wineStop.contains("EX_EXEC_FAILED"))
            Ex.log(this, "Wine stop: " + wineStop.trim());
        int remaining = Ex.stopOwnChildren();
        if (remaining > 0) Ex.log(this, "Закрыты оставшиеся процессы eX: " + remaining);
        painting = false;
        if (display != null) display.stop();
        ExSession.release(this);
    }

    private void shutdown(Runnable after) {
        stopping = true;
        ExWork.Scope operation = launchScope;
        if (operation != null) {
            operation.requestCancel();
            if (worker != null) worker.interrupt();
        }
        if (game != null) game.destroy();
        new Thread(() -> {
            if (operation != null) operation.closeResources();
            try { if (worker != null) worker.join(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            cleanupSession();
            if (after != null) ui.post(after);
        }, "ex-cleanup").start();
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (java.util.Objects.equals(intent.getStringExtra(EXTRA_GAME), getIntent().getStringExtra(EXTRA_GAME))
                && java.util.Objects.equals(intent.getStringExtra(EXTRA_FOLDER), getIntent().getStringExtra(EXTRA_FOLDER))) {
            if (!busy && !watching) start();
            return;
        }
        android.content.Intent next = new android.content.Intent(intent);
        shutdown(() -> { finish(); startActivity(next); });
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);

        if (focus) {
            immersive();
        } else releaseAllInputs();
    }

    @Override public void onBackPressed() {
        if (keys != null && keys.getVisibility() == View.VISIBLE) { showKeys(false); return; }
        if (logBox != null && logBox.getVisibility() == View.VISIBLE) { toggleLog(); return; }
        super.onBackPressed();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        releaseAllInputs();
        super.onConfigurationChanged(configuration);
        if (hud != null) hud.setMaxWidth(Ui.dp(this, Math.max(120, configuration.screenWidthDp - 330)));
        if (ultra != null) ultra.refreshDisplay();
    }
}
