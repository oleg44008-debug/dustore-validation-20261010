package ru.dustore.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Окно, в котором идёт веб-игра.
 *
 * Отдельное окно, а не вкладка в главном: игра занимает экран целиком, а возврат из неё —
 * это конец партии, и его надо поймать. Здесь же считается время: игра открылась, игра
 * закрылась, разница и есть сыгранное. На телефоне это точнее, чем у настольного
 * лаунчера, — там приходится следить за чужим процессом, а тут игра идёт внутри.
 */
public final class GameActivity extends Activity {

    static final String EXTRA_FOLDER = "folder";
    static final String EXTRA_TITLE = "title";

    /**
     * Какие клавиши рисовать поверх игры — «rpgmaker», «novel», «arcade» или «none».
     * Ставится только для игр, переведённых с компьютера: они писались под клавиатуру,
     * которой у телефона нет. Игры, скачанные здесь, к экрану и так приспособлены.
     */
    static final String EXTRA_CONTROLS = "controls";
    static final String EXTRA_ORIENTATION = "orientation";
    static final String RESULT_MINUTES = "minutes";

    private WebServer server;
    private WebView web;
    private KeyPad pad;
    private GameRefreshRate refreshRate;
    private long startedAt;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        String folder = getIntent().getStringExtra(EXTRA_FOLDER);
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        // Экран не должен гаснуть посреди игры: в игре не жмут на экран каждые полминуты.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BACK);

        web = new WebView(this);
        web.setBackgroundColor(Ui.BACK);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // Игра сама себе сайт и наружу не ходит; всё, что ей нужно, отдаёт свой сервер
        // на localhost. Доступ к файлам телефона ей при этом не открывается.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient());

        String controls = getIntent().getStringExtra(EXTRA_CONTROLS);
        String orientation = getIntent().getStringExtra(EXTRA_ORIENTATION);

        if (orientation != null) {
            setRequestedOrientation("portrait".equals(orientation)
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }

        if (controls != null && !"none".equals(controls)) {
            pad = new KeyPad(this);
            pad.setListener(this::key);
            pad.profile(controls);

            root.addView(pad, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        setContentView(root);
        refreshRate = new GameRefreshRate(this);

        File dir = folder == null ? null : new File(folder);
        File external = getExternalFilesDir(null);
        boolean gameFolder = GameFolders.contains(new File(getFilesDir(), "games"), dir)
                || external != null && GameFolders.contains(new File(external, "games"), dir);
        if (!gameFolder || WebEntry.file(dir) == null) {
            fail(root, "Файлы игры не найдены. Скачайте её заново.");
            return;
        }

        server = new WebServer(dir);
        String address = server.start();

        if (address == null) {
            fail(root, "Не удалось запустить игру.");
            return;
        }

        startedAt = System.currentTimeMillis();
        setTitle(title == null ? "Игра" : title);
        web.loadUrl(address);
    }

    /**
     * Отдаёт странице настоящее событие клавиатуры.
     *
     * Движки слушают в разных местах: RPG Maker — на документе, экспорт из Unity или Godot
     * чаще на окне, часть игр вешает обработчик прямо на канву. Поэтому событие уходит
     * на canvas (либо документ) и всплывает до документа и окна ровно один раз.
     */
    private void key(String code, int keyCode, boolean down) {
        if (web == null) {
            return;
        }

        String type = down ? "keydown" : "keyup";
        String script = "(function(){var e=new KeyboardEvent('" + type + "',{key:'"
                + keyName(code) + "',code:'" + code + "',keyCode:" + keyCode
                + ",which:" + keyCode + ",bubbles:true,cancelable:true});"
                + "var c=document.querySelector('canvas');(c||document).dispatchEvent(e);})()";

        web.evaluateJavascript(script, null);
    }

    private static String keyName(String code) {
        if (code.startsWith("Key")) {
            return code.substring(3).toLowerCase(java.util.Locale.ROOT);
        }

        if ("Space".equals(code)) {
            return " ";
        }

        if ("ShiftLeft".equals(code)) {
            return "Shift";
        }

        if ("ControlLeft".equals(code)) {
            return "Control";
        }

        return code;
    }

    private void fail(FrameLayout root, String message) {
        TextView text = Ui.text(this, message, 15, Ui.MUTED);
        int pad = Ui.dp(this, 24);
        text.setPadding(pad, pad, pad, pad);
        root.addView(text);

        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /**
     * Сколько минут шла игра — обратно в лаунчер.
     *
     * Ответ отдаётся именно результатом окна, а не записью в общий файл: так время
     * попадает ровно к той игре, которую запускали, и не может достаться соседней.
     */
    private void report() {
        if (startedAt == 0) {
            return;
        }

        long minutes = (System.currentTimeMillis() - startedAt) / 60000;
        startedAt = 0;

        Intent result = new Intent();
        result.putExtra(RESULT_MINUTES, minutes);
        setResult(RESULT_OK, result);
    }

    @Override
    public void onBackPressed() {
        report();
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (pad != null) pad.releaseAllInputs();
        if (refreshRate != null) refreshRate.pause();
        super.onPause();

        if (web != null) {
            web.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (web != null) {
            web.onResume();
        }
        if (refreshRate != null) refreshRate.resume();
    }

    @Override
    protected void onDestroy() {
        if (pad != null) pad.releaseAllInputs();
        if (refreshRate != null) refreshRate.pause();
        report();

        if (server != null) {
            server.stop();
            server = null;
        }

        if (web != null) {
            web.destroy();
            web = null;
        }

        super.onDestroy();
    }

    /** Пустой вид на случай, если окно закрыли до запуска сервера. */
    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);

        if (focus && web != null) {
            web.setVisibility(View.VISIBLE);
        } else if (!focus && pad != null) pad.releaseAllInputs();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        if (pad != null) pad.releaseAllInputs();
        super.onConfigurationChanged(configuration);
        if (refreshRate != null) refreshRate.refreshDisplay();
    }
}
