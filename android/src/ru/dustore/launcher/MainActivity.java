package ru.dustore.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Dustore на телефоне: магазин, библиотека и запуск игр.
 *
 * Раньше это было окно на сайт и ничего больше — потому что игры для Windows на Android
 * запустить нельзя. Но на dustore.ru есть игры и в виде apk, а это уже совсем другое дело:
 * такую игру телефон умеет и скачать, и поставить, и открыть. А раз так — сюда переезжает
 * и главное, ради чего библиотека существует на компьютере: **право оценить игру**.
 *
 * Правило то же самое и не смягчено ни на шаг: отзыв можно оставить только о том, что
 * скачано отсюда, установлено отсюда и во что человек хотя бы раз зашёл. Именно поэтому
 * оценкам можно верить, и именно поэтому телефон не стал местом, где их можно наставить
 * не играя.
 */
public class MainActivity extends Activity implements LibraryScreen.Host {

    private static final String HOME = "https://dustore.ru/explore";
    private static final String HOST = "dustore.ru";

    /** Представление обычного настольного браузера — для полной версии сайта. */
    private static final String DESKTOP_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";

    /**
     * Хосты, на которых люди подтверждают, что это они.
     *
     * Всё чужое приложение отправляет во внешний браузер — и это правильно: человек должен
     * видеть адрес страницы, на которой что-то делает. Но для входа это правило губительно:
     * человек логинится в Chrome, ключ от сессии остаётся там, а лаунчер по-прежнему не
     * знает, кто он. Поэтому вход остаётся внутри — и только вход.
     */
    private static final String[] AUTH_HOSTS = {
            "telegram.org", "oauth.telegram.org", "t.me",
            "accounts.google.com", "oauth.vk.com", "id.vk.com", "vk.com",
            "oauth.yandex.ru", "passport.yandex.ru", "oauth.mail.ru", "connect.mail.ru",
            "github.com", "id.apple.com", "appleid.apple.com"
    };

    private boolean desktopSite;

    private WebView web;
    private String resumeStoreUrl;
    private ProgressBar progress;
    private View offlineHost;

    /**
     * Когда главное окно в последний раз само куда-то пошло.
     *
     * Нужно ровно для одного случая — окна входа. Оно, закрываясь, обновляло сайт, чтобы
     * подхватить свежую сессию. Но вход через Telegram кончается тем, что главное окно
     * уводят на адрес вида `dustore.ru/login?id=…&hash=…` — и обновление через полторы
     * секунды обрывало этот переход на полпути. Внешне это выглядело так, будто вход
     * прошёл, а регистрации не случилось.
     */
    private long lastSiteNavigation;

    /** A successful site login currently redirects to /m/ even in desktop mode. */
    private long lastFullSiteRedirect;

    /** Служебный сценарий сайта разбирается один раз за запуск — дальше он уже не мешает. */
    private boolean serviceWorkerChecked;


    private FrameLayout screens;
    private View storeScreen;
    private LibraryScreen libraryScreen;
    private PcScreen pcScreen;
    private int shownScreen;
    private PremiumNav navigationBar;

    private Library library;
    private Account account;

    /** Библиотека и аккаунт нужны и другим экранам: библиотека привязана к аккаунту. */
    Library library() {
        return library;
    }

    Account account() {
        return account;
    }

    /** Игра, которую сейчас запустили: по ней считается время и задаются вопросы. */
    private Library.Entry playing;

    /** Игра, которую сейчас ставят, и снимок установленного до этого. */
    private Library.Entry installing;
    private java.util.Set<String> beforeInstall;

    /** Игра, ждущая, пока человек разрешит установку в настройках. */
    private Library.Entry waitingForPermission;
    private boolean waitingInstallEx;
    private volatile boolean synchronizingDownloads;
    private String observedExState = "";
    private final ExTask.Listener exTaskChanged = snapshot -> {
        if (!snapshot.busy() && !snapshot.state.equals(observedExState) && libraryScreen != null)
            libraryScreen.refresh();
        observedExState = snapshot.state;
    };

    private BroadcastReceiver downloads;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        library = new Library(this);
        account = new Account(this);
        android.content.SharedPreferences site = getSharedPreferences("site", MODE_PRIVATE);
        if (!site.getBoolean("fullVersionDefault", false)) {
            site.edit().putBoolean("desktop", true).putBoolean("fullVersionDefault", true).apply();
        }
        desktopSite = site.getBoolean("desktop", true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BACK);

        screens = new FrameLayout(this);
        root.addView(screens, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        storeScreen = buildStore(state);
        screens.addView(storeScreen);

        libraryScreen = new LibraryScreen(this, library, this);
        libraryScreen.setVisibility(View.GONE);
        screens.addView(libraryScreen);

        pcScreen = new PcScreen(this);

        pcScreen.setVisibility(View.GONE);
        screens.addView(pcScreen);

        root.addView(navigation());
        FrameLayout scene = new FrameLayout(this);
        scene.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(scene);
        getWindow().setStatusBarColor(Ui.BACK);
        getWindow().setNavigationBarColor(Ui.BACK_SOFT);
        if (state != null) show(Math.max(0, Math.min(2, state.getInt("prime.screen", 0))));
        else PrimeIntro.play(this, scene);

        listenForDownloads();
        handleExCommand(getIntent());
        quotaSelfTest(getIntent());

        if (account.signedIn()) {
            account.verify(error -> { });
        }
    }

    // ------------------------------------------------------------------ магазин ----

    private View buildStore(Bundle state) {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(Ui.BACK);
        screen.addView(toolbar());

        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Ui.BACK);
        screen.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        web = new WebView(this);
        web.setBackgroundColor(Ui.BACK);
        box.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // A thin bar rather than a spinner over the page: the site paints its own content
        // quickly, and a full-screen overlay would hide it.
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        box.addView(progress, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3)));

        // Экран «нет сети» — с кнопкой, а не с советом потянуть вниз: жеста обновления
        // здесь нет и не было, а обещание, которого приложение не выполняет, хуже
        // молчания — человек тянет, ничего не происходит, и он решает, что всё сломалось.
        LinearLayout offlineBox = new LinearLayout(this);
        offlineBox.setOrientation(LinearLayout.VERTICAL);
        offlineBox.setGravity(Gravity.CENTER);
        offlineBox.setBackgroundColor(Ui.BACK);
        offlineBox.setPadding(Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28));
        offlineBox.setVisibility(View.GONE);

        TextView offline = Ui.text(this,
                "Нет подключения к интернету.\n\nПроверьте сеть и попробуйте ещё раз.", 14, Ui.MUTED);
        offline.setGravity(Gravity.CENTER);
        offlineBox.addView(offline);

        // Та же кнопка, что и везде в приложении: системная приносила с собой белую
        // подпись на оранжевом (нечитаемо), прямые углы и заглавные буквы.
        TextView retry = Ui.button(this, "Повторить", true);
        retry.setOnClickListener(v -> {
            offlineHost.setVisibility(View.GONE);
            web.reload();
        });

        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryParams.topMargin = Ui.dp(this, 20);
        offlineBox.addView(retry, retryParams);

        offlineHost = offlineBox;
        box.addView(offlineBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        configure(web.getSettings());

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        // Отладочный канал в сайт.
        //
        // Когда страница «просто серая», угадывать причину бесполезно: надо видеть, что
        // говорит сама страница. Сообщения её сценариев уходят в системный журнал, и их
        // видно по кабелю обычным `adb logcat -s dustore-web`. Это не отладочная времянка:
        // ровно так же ловится любая будущая жалоба «у меня ничего не открывается».
        WebView.setWebContentsDebuggingEnabled(debugDoorOpen());

        web.setWebViewClient(new SiteClient());
        web.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                if (!debugDoorOpen()) return true;
                android.util.Log.i("dustore-web", message.messageLevel() + " "
                        + message.message() + "  (" + message.sourceId() + ":"
                        + message.lineNumber() + ")");
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                return openPopup(resultMsg);
            }
        });

        web.setDownloadListener((url, userAgent, disposition, mime, size) ->
                download(url, disposition, mime, web.getUrl()));

        String asked = urlFromIntent(getIntent());

        if (asked != null) {
            web.loadUrl(asked);
        } else if (state != null) {
            web.restoreState(state);
            if (desktopSite && isMobileSection(web.getUrl())) web.loadUrl(HOME);
        } else {
            web.loadUrl(resumeStoreUrl == null ? HOME : resumeStoreUrl);
            resumeStoreUrl = null;
        }

        return screen;
    }

    /** Ссылка, с которой лаунчер открыли извне — из QR-кода, чата, браузера. */
    private String urlFromIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return null;
        }

        Uri data = intent.getData();
        if (data == null) {
            return null;
        }

        String host = data.getHost();
        if (host == null) {
            return null;
        }

        host = host.toLowerCase(java.util.Locale.ROOT);

        // Чужие ссылки лаунчер не открывает, даже если его о том попросили: он умеет ровно
        // один сайт, и притворяться браузером не должен.
        return host.equals(HOST) || host.endsWith("." + HOST) ? data.toString() : null;
    }

    /**
     * Приложение уже открыто, и пришла ссылка.
     *
     * Окно объявлено singleTop, поэтому второй раз оно не создаётся — вместо этого
     * приходит сюда, и без этой обработки ссылка просто потерялась бы.
     */
    /**
     * Команда с компьютера: поставить eX.
     *
     * Лаунчер на ПК отдаёт образ и сразу просит телефон развернуть систему — человеку не
     * надо искать кнопку на маленьком экране. Ход установки пишется в журнал, который
     * компьютер потом читает и показывает.
     */
    /**
     * Открыта ли отладочная дверь.
     *
     * Две команды из этой двери — «выполни строку внутри системы» и «выполни строку в
     * открытом сайте» — делают ровно то, что просят, а окно приложения обязано быть
     * доступно снаружи: иначе его не запустить с кабеля. Без замка любое приложение на
     * телефоне могло бы через них выполнить что угодно и забрать вход в магазин.
     *
     * Замок такой: дверь работает, только пока во внешней папке приложения лежит файл
     * ex.debug. Положить его туда может человек с кабелем
     * (adb shell touch .../files/ex.debug), а чужому приложению в эту папку с Android 11
     * ходу нет. Команды «поставить» и «проверить» замка не требуют: они ничего
     * произвольного не выполняют, и через них лаунчер с ПК собирает систему.
     */
    private static org.json.JSONObject readMeta(File file) {
        try {
            return new org.json.JSONObject(ExFileRead.utf8(file, ExFileRead.GAME_METADATA_BYTES));
        } catch (Exception missing) {
            return null;
        }
    }

    private boolean debugDoorOpen() {
        File outside = getExternalFilesDir(null);
        return outside != null && new File(outside, "ex.debug").exists();
    }

    private void handleExCommand(Intent intent) {
        String what = intent == null ? null : intent.getStringExtra("ex");

        // Ответ о состоянии системы обновляется при каждом запуске: компьютеру нужно
        // знать правду и про системы, поставленные до появления этого ответа.
        Ex.syncState(this);

        if (what == null) {
            return;
        }

        // Один интент — одна команда: при пересоздании окна тот же интент приходит
        // снова, и без этого эмуляция запускалась бы дважды.
        intent.removeExtra("ex");

        if (!"web".equals(what)) show(2);

        // Та же дверь, но в сайт: спросить у открытой страницы что угодно и прочитать
        // ответ в журнале. Без этого «у меня серый экран» остаётся непроверяемым — со
        // стороны видно только, что страница вроде бы загрузилась.
        if ("web".equals(what)) {
            if (!debugDoorOpen()) {
                Ex.log(this, "отладочная дверь закрыта: нет файла ex.debug");
                return;
            }

            final String line = intent.getStringExtra("js");

            runOnUiThread(() -> {
                if (web == null) {
                    android.util.Log.i("dustore-web", "ответ: окна нет");
                    return;
                }

                web.evaluateJavascript(line, value ->
                        android.util.Log.i("dustore-web", "ответ: " + value));
            });

            return;
        }

        // Отладочная дверь: компьютер может попросить телефон выполнить строку внутри
        // системы и прочитать ответ в журнале. Нужна затем, чтобы проверять сборку по
        // кабелю, не пересобирая приложение ради каждой мелочи.
        if ("sh".equals(what)) {
            if (!debugDoorOpen()) {
                Ex.log(this, "отладочная дверь закрыта: нет файла ex.debug");
                return;
            }

            final String line = intent.getStringExtra("cmd");

            new Thread(() -> {
                Ex.log(this, "$ " + line);
                String answer = Ex.run(this, new String[] { "/bin/bash", "-c", line },
                        false, null);
                Ex.log(this, answer == null ? "(нет ответа)" : answer.trim());
                Ex.log(this, "=== конец команды ===");
            }, "ex-shell").start();

            return;
        }

        if ("parts".equals(what)) {
            Toast.makeText(this, "Качаем части eX…", Toast.LENGTH_SHORT).show();

            new Thread(() -> {
                Ex.log(this, "=== части eX " + new java.util.Date() + " ===");
                String problem = ExBuild.fetchAll(this, line -> Ex.log(this, line));
                Ex.log(this, problem == null ? "ЧАСТИ: все на месте" : "ОШИБКА: " + problem);
            }, "ex-parts").start();

            return;
        }

        if ("convert".equals(what)) {
            final String zip = intent.getStringExtra("zip");
            final String folder = intent.getStringExtra("folder");
            Library.Entry entry = null;

            if (zip != null) {
                // Архив как из магазина: запись в библиотеке, вид «сборка для Windows».
                File file = new File(zip);
                entry = new Library.Entry();
                entry.title = file.getName().replaceAll("\\.zip$", "");
                entry.kind = "pc";
                entry.addedUtc = System.currentTimeMillis();

                File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                File copy = new File(dir, file.getName());

                if (!copy.exists() && !ExConvert.copyFile(file, copy)) {
                    Ex.log(this, "ОШИБКА: архив не скопировался в загрузки");
                    return;
                }

                entry.fileName = copy.getName();
                library.add(entry);
            } else if (folder != null) {
                File dir = new File(folder);
                entry = new Library.Entry();
                entry.title = dir.getName();
                entry.kind = "pc";
                entry.folder = dir.getAbsolutePath();
                entry.addedUtc = System.currentTimeMillis();

                org.json.JSONObject meta = readMeta(new File(dir, "dustorex.json"));

                if (meta != null) {
                    entry.title = meta.optString("title", entry.title);
                }

                library.add(entry);
            }

            if (entry != null) {
                show(1);
                emulate(entry);
            }

            return;
        }

        if ("verify".equals(what)) {
            Toast.makeText(this, "Проверяем eX…", Toast.LENGTH_SHORT).show();
            new Thread(() -> Ex.proveLogged(this), "ex-verify-cable").start();
            return;
        }

        if (!"install".equals(what)) {
            return;
        }

        Toast.makeText(this, "Ставим систему eX…", Toast.LENGTH_LONG).show();
        ExService.hold(this, "Собираем систему eX");

        new Thread(() -> {
            String problem = ExBuild.installNativeLogged(this, null);

            runOnUiThread(() -> {
                ExService.release(this);
                Toast.makeText(this, problem == null
                        ? "eX установлена"
                        : "eX: " + problem, Toast.LENGTH_LONG).show();

                pcScreen.load();
            });
        }, "ex-cable").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExCommand(intent);
        quotaSelfTest(intent);

        String asked = urlFromIntent(intent);

        if (asked != null && web != null) {
            show(0);
            web.loadUrl(asked);
        }
    }

    /**
     * Полоска над сайтом: назад, обновить и переключатель версии сайта.
     *
     * Кнопка «назад» здесь не дубликат системной: системная уводит из приложения, когда
     * возвращаться уже некуда, а по сайту ходят вперёд-назад постоянно. Переключатель
     * версии — лекарство от мобильной вёрстки dustore.ru, которая прячет часть кнопок.
     */
    private LinearLayout toolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Ui.bar(this, false));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));

        TextView back = Ui.text(this, "", 12, Ui.MUTED);
        back.setCompoundDrawablesWithIntrinsicBounds(Ui.icon(this, "left", 22, Ui.MUTED), null, null, null);
        back.setMinHeight(Ui.dp(this, Ui.TOUCH));
        back.setMinWidth(Ui.dp(this, Ui.TOUCH));
        back.setGravity(Gravity.CENTER);
        back.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        back.setContentDescription("Назад");
        back.setForeground(Ui.tap(this, Ui.R_BUTTON));
        back.setClickable(true);
        back.setOnClickListener(v -> {
            if (web != null && web.canGoBack()) {
                web.goBack();
            }
        });
        bar.addView(back);

        TextView reload = Ui.text(this, "", 12, Ui.MUTED);
        reload.setCompoundDrawablesWithIntrinsicBounds(Ui.icon(this, "spin", 20, Ui.MUTED), null, null, null);
        reload.setMinHeight(Ui.dp(this, Ui.TOUCH));
        reload.setMinWidth(Ui.dp(this, Ui.TOUCH));
        reload.setGravity(Gravity.CENTER);
        reload.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        reload.setContentDescription("Обновить");
        reload.setForeground(Ui.tap(this, Ui.R_BUTTON));
        reload.setClickable(true);
        reload.setOnClickListener(v -> {
            if (web != null) {
                web.reload();
            }
        });
        bar.addView(reload);

        TextView brand = Ui.title(this, "DUSTORE", 14, Ui.TEXT);
        brand.setSingleLine(true);
        brand.setMaxWidth(Ui.dp(this, 72));
        brand.setEllipsize(android.text.TextUtils.TruncateAt.END);
        brand.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(brand);
        brand.setContentDescription("DUSTORE");

        TextView spacer = Ui.text(this, "", 12, Ui.DIM);
        bar.addView(spacer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView mode = Ui.text(this, siteModeLabel(), 12, Ui.MUTED);
        siteModeLabelView = mode;
        mode.setBackground(Ui.glass(this, Ui.R_BUTTON, false));
        mode.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        mode.setClickable(true);
        mode.setFocusable(true);
        mode.setSingleLine(true);
        mode.setMinHeight(Ui.dp(this, Ui.TOUCH));
        mode.setMinWidth(Ui.dp(this, Ui.TOUCH));
        mode.setGravity(Gravity.CENTER);
        mode.setContentDescription(desktopSite ? "Сайт: полная версия. Переключить" : "Сайт: мобильная версия. Переключить");
        Motion.touch(mode);
        mode.setOnClickListener(v -> {
            desktopSite = !desktopSite;
            getSharedPreferences("site", MODE_PRIVATE).edit()
                    .putBoolean("desktop", desktopSite).apply();

            applySiteMode(web.getSettings());
            mode.setText(siteModeLabel());
            mode.setContentDescription(desktopSite ? "Сайт: полная версия. Переключить" : "Сайт: мобильная версия. Переключить");

            // Именно loadUrl, а не reload: после смены представления браузера обычное
            // обновление оставляет прежнюю ширину страницы, и «мобильная» версия
            // продолжает выглядеть как настольная — обрезанной по правому краю.
            //
            // И отдельно: у мобильного раздела сайта (/m/…) нет двойника в полной версии —
            // там другие адреса и другие номера игр. Перезагружать тот же адрес при
            // переходе на полную версию бессмысленно: сайт покажет ту же самую страницу.
            // Поэтому уходим на начало, откуда полная версия открывается по-настоящему.
            String url = web.getUrl();

            if (url == null || url.isEmpty() || (desktopSite && isMobileSection(url))) {
                url = HOME;
            }

            web.loadUrl(url);

            Toast.makeText(this, desktopSite
                            ? "Сайт открывается как на компьютере"
                            : "Сайт открывается как на телефоне",
                    Toast.LENGTH_SHORT).show();
        });
        bar.addView(mode);
        LinearLayout.LayoutParams settingsSpace = new LinearLayout.LayoutParams(-2, -2);
        settingsSpace.leftMargin = Ui.dp(this, 8);
        bar.addView(Prime.mark(this), settingsSpace);
        Motion.touch(back);
        Motion.touch(reload);

        return bar;
    }

    private String siteModeLabel() {
        return desktopSite ? "ПК" : "Моб.";
    }

    /** Keep the store's own account control reachable on a narrow phone display. */
    private void fitStoreHeader(WebView view, String url) {
        if (!desktopSite || url == null) return;
        Uri page = Uri.parse(url);
        String host = page.getHost();
        if (host == null || !(host.equals(HOST) || host.endsWith("." + HOST))) return;

        // The site moves .buttons-right into #userMenu below 900px, but its current
        // markup has no #userChip to open that menu. Put a compact proxy in the
        // visible header; it invokes the site's existing login/profile button.
        view.evaluateJavascript("(function(){"
                + "var h=document.querySelector('.header');"
                + "if(!h)return false;"
                + "if(!document.getElementById('dustorex-header-fit')){"
                + "var s=document.createElement('style');s.id='dustorex-header-fit';"
                + "s.textContent='.header .center-section{display:none!important}'"
                + "+'.header .right-section{margin-left:auto!important;margin-right:0!important}'"
                + "+'.header .buttons-right>button:not(:last-of-type){display:none!important}'"
                + "+'.header .buttons-right{margin:0!important;padding:4px!important}';"
                + "document.head.appendChild(s);}"
                + "var right=h.querySelector('.right-section');"
                + "var original=document.querySelector('.buttons-right .button[onclick*=\"/player/\"],"
                + ".buttons-right .button[onclick*=\"/login\"]');"
                + "if(!right||!original)return true;"
                + "var chip=document.getElementById('dustorex-account');"
                + "if(!chip){chip=document.createElement('button');"
                + "chip.id='dustorex-account';chip.type='button';"
                + "chip.style.cssText='margin-left:8px;padding:7px 10px;max-width:42vw;'+"
                + "'overflow:hidden;text-overflow:ellipsis;white-space:nowrap;'+"
                + "'border:1px solid " + String.format(java.util.Locale.ROOT, "#%06X", Ui.LINE_STRONG & 0xFFFFFF) + ";border-radius:12px;'+"
                + "'background:" + String.format(java.util.Locale.ROOT, "#%06X", Ui.CARD & 0xFFFFFF)
                + ";color:" + String.format(java.util.Locale.ROOT, "#%06X", Ui.TEXT & 0xFFFFFF) + ";font:600 13px sans-serif;cursor:pointer';"
                + "right.appendChild(chip);}"
                + "var label=original.querySelector('span');"
                + "chip.textContent=(label&&label.textContent.trim())||'Аккаунт';"
                + "chip.onclick=function(){original.click()};"
                + "return chip.textContent;})()",
                result -> android.util.Log.i("dustore-web", "header fit: " + result));
    }

    private void configure(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // Всплывающие окна. Без этих двух строк вход через Telegram просто ничего не
        // делает: виджет открывает окно вызовом window.open, а WebView по умолчанию
        // такие окна не создаёт — и молча их теряет. Ни ошибки, ни объяснения, кнопка
        // будто сломана. На компьютере эту же задачу решает отдельное окно PopupForm.
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        settings.setDisplayZoomControls(false);

        // No file:// or content:// access from web content — the site has no need for it,
        // and leaving it on is how a WebView app leaks local files.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        applySiteMode(settings);
    }

    /**
     * Мобильная или полная версия сайта.
     *
     * Переключатель нужен не для красоты: мобильная вёрстка dustore.ru прячет часть
     * возможностей — например, ссылку на загрузку игры для Android она показывает только
     * после входа, а полная версия отдаёт её сразу. Подменяется только представление
     * браузера; никакого обмана сайта здесь нет, ровно то же делает кнопка «версия для
     * ПК» в любом мобильном браузере.
     */
    private void applySiteMode(WebSettings settings) {
        settings.setUserAgentString(desktopSite ? DESKTOP_AGENT : null);

        // Масштаб пальцами — только в полной версии, где страница заведомо шире экрана.
        //
        // В мобильной его включать нельзя, и это не вкусовщина: WebView с включённым
        // зумом перестаёт ужимать страницу под ширину экрана, и сайт вылезает за правый
        // край. Ровно это и произошло, когда зум включили на оба режима сразу.
        settings.setSupportZoom(desktopSite);
        settings.setBuiltInZoomControls(desktopSite);
        settings.setDisplayZoomControls(false);
    }

    /**
     * Keeps dustore.ru inside the app and sends everything else to the real browser, where
     * the user can see the address. Same rule as the desktop launcher.
     */
    private final class SiteClient extends WebViewClient {

        @Override
        public boolean onRenderProcessGone(WebView view,
                android.webkit.RenderProcessGoneDetail detail) {
            // Android may reclaim the store renderer while a Windows game uses RAM.
            // A dead WebView cannot be reused; leaving it attached kills the launcher.
            if (view == web) {
                releaseStoreWeb(true);
                if (!isFinishing() && storeScreen != null
                        && storeScreen.getVisibility() == View.VISIBLE) {
                    storeScreen.post(MainActivity.this::restoreStoreWeb);
                }
            } else {
                disposeWebView(view);
            }
            android.util.Log.w("dustore-web", "renderer gone; killed by system="
                    + !detail.didCrash());
            return true;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String host = uri.getHost();

            if (host != null && (host.equals(HOST) || host.endsWith("." + HOST))) {
                return false;
            }

            // Страницы входа остаются внутри: увести их в браузер — значит войти в
            // браузере, а не в лаунчере.
            if (isAuthHost(host)) {
                return false;
            }

            String scheme = uri.getScheme();
            if (scheme == null) {
                return true;
            }

            // tg://, vk:// и подобное — это просьба открыть установленное приложение.
            // Браузером такое не открыть, и молчать нельзя: человек нажал и ждёт.
            if (!scheme.equals("http") && !scheme.equals("https")) {
                if (scheme.equals("mailto") || scheme.equals("tel")) {
                    open(uri);
                } else {
                    openApp(uri);
                }

                return true;
            }

            // Файл — не страница, и смотреть на его адрес человеку незачем: пусть грузится
            // здесь, где его поймает слушатель загрузок и положит в библиотеку. Сборки игр
            // сплошь и рядом лежат не на dustore.ru, а на файловом хосте, — и раньше такая
            // ссылка «уезжала в браузер», где библиотека её уже никак не могла поймать.
            if (looksLikeFile(uri)) {
                return false;
            }

            open(uri);
            return true;
        }

        /**
         * Отмечаем каждый переход главного окна.
         *
         * Это нужно окну входа: закрываясь, оно обновляло сайт — и тем самым обрывало
         * переход, который само же только что и начало. Теперь видно, был переход или нет.
         */
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            lastSiteNavigation = System.currentTimeMillis();
            super.onPageStarted(view, url, icon);
        }

        /**
         * Служебный сценарий сайта (service worker) внутрь не пускаем.
         *
         * Мобильная версия dustore.ru сделана как PWA: она ставит себе служебный сценарий,
         * который дальше сам решает, откуда брать страницы. В обычном браузере это ускоряет
         * работу, а внутри приложения ломает всё: сеть у такого сценария в WebView своя,
         * запрос из него не доходит, и страница игры открывается пустой — ни ошибки, ни
         * текста, просто серый экран. Именно на это и жаловались: «после регистрации на
         * страницу игры не зайти».
         *
         * Отдаём вместо него пустой файл. Сайт считает, что сценарий поставлен, но делать
         * тому нечего — и страницы снова приходят прямо из сети. Лаунчеру запас страниц
         * «на без интернета» не нужен: без сети он и так ничего не может.
         */
        @Override
        public android.webkit.WebResourceResponse shouldInterceptRequest(
                WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String path = uri.getPath();
            String host = uri.getHost();
            boolean ours = host != null && (host.equals(HOST) || host.endsWith("." + HOST));

            if (ours && path != null && path.endsWith("sw.js")) {
                android.util.Log.i("dustore-web", "служебный сценарий обезврежен: " + safePage(uri.toString()));

                return new android.webkit.WebResourceResponse("application/javascript",
                        "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
            }

            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            android.util.Log.i("dustore-web", "открыто: " + safePage(url));
            CookieManager.getInstance().flush();
            if (view == web && desktopSite && isMobileSection(url)) {
                long now = System.currentTimeMillis();
                if (now - lastFullSiteRedirect >= 15000) {
                    lastFullSiteRedirect = now;
                    view.postDelayed(() -> {
                        if (web == view && desktopSite && isMobileSection(view.getUrl())) {
                            android.util.Log.i("dustore-web",
                                    "возвращаемся в полный каталог после входа");
                            view.loadUrl(HOME);
                        }
                    }, 800);
                }
                return;
            }
            fitStoreHeader(view, url);
            offlineHost.setVisibility(View.GONE);
            disarmServiceWorker(view);
            checkNotBlank(view, url);
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceResponse response) {
            int code = response.getStatusCode();
            String url = request.getUrl().toString();

            android.util.Log.i("dustore-web", "ответ " + code + " на " + safePage(url)
                    + (request.isForMainFrame() ? "  (страница)" : ""));

            if (request.isForMainFrame() && code >= 500 && isMobileSection(url)) {
                escapeBrokenMobileSite(url);
                return;
            }

            super.onReceivedHttpError(view, request, response);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    android.webkit.WebResourceError error) {
            android.util.Log.i("dustore-web", "ошибка " + error.getErrorCode() + " "
                    + " на " + safePage(request.getUrl().toString()));

            if (request.isForMainFrame()) {
                offlineHost.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Снимает служебный сценарий, если он успел поставиться раньше.
     *
     * Перехват нового файла спасает будущие запуски, но на телефоне, где сценарий уже
     * стоит, он продолжает командовать страницами до тех пор, пока его не снять. Спрашиваем
     * прямо: командует ли кто-то этой страницей? Если да — снимаем, чистим его запасы и
     * перезагружаем страницу один раз. Если нет — не делаем ничего, и лишнего мигания при
     * каждом запуске не будет.
     */
    private void disarmServiceWorker(WebView view) {
        if (serviceWorkerChecked || view == null) {
            return;
        }

        serviceWorkerChecked = true;
        final String urlBeforeCleanup = view.getUrl();

        view.evaluateJavascript(
                "(navigator.serviceWorker && navigator.serviceWorker.controller) ? '1' : '0'",
                answer -> {
                    if (answer == null || answer.indexOf('1') < 0) {
                        return;
                    }

                    android.util.Log.i("dustore-web", "страницей командует служебный "
                            + "сценарий — снимаем");

                    view.evaluateJavascript(
                            "navigator.serviceWorker.getRegistrations()"
                            + ".then(function(rs){rs.forEach(function(r){r.unregister();});});"
                            + "if(window.caches){caches.keys().then(function(ks){"
                            + "ks.forEach(function(k){caches.delete(k);});});}", null);

                    view.postDelayed(() -> {
                        // Не прерываем ввод, POST формы или подтверждение входа.
                        if (web == view && isMobileSection(view.getUrl())
                                && view.getUrl().equals(urlBeforeCleanup)) {
                            web.reload();
                        }
                    }, 900);
                });
    }

    /** Адрес из мобильного раздела сайта — того самого, где живут /m/… . */
    private static boolean isMobileSection(String url) {
        Uri uri = Uri.parse(url == null ? "" : url);
        String host = uri.getHost();
        String path = uri.getPath();

        return host != null && (host.equals(HOST) || host.endsWith("." + HOST))
                && path != null && (path.equals("/m") || path.startsWith("/m/"));
    }

    /**
     * Мобильный раздел сайта ответил ошибкой — уходим в полную версию.
     *
     * Проверено на живом телефоне и цифрами: `/m/game/308` отдаёт 200 без входа и 500 тому,
     * кто вошёл, а та же игра в полной версии открывается нормально. Это поломка на самом
     * сайте, чинить её не нам, — но оставлять человека перед пустым экраном нельзя.
     * Поэтому переключаемся на полную версию, говорим об этом вслух и открываем каталог,
     * где игру видно.
     */
    private void escapeBrokenMobileSite(String url) {
        // Не чаще раза в минуту: если сайт однажды начнёт отвечать ошибкой и на полной
        // версии, приложение не должно прыгать между разделами по кругу.
        long now = System.currentTimeMillis();

        if (now - lastEscape < 60000) {
            return;
        }

        lastEscape = now;

        if (!desktopSite) {
            desktopSite = true;
            getSharedPreferences("site", MODE_PRIVATE).edit()
                    .putBoolean("desktop", true).apply();

            applySiteMode(web.getSettings());
            refreshSiteModeLabel();
        }

        // Именно каталог, а не начало сайта: начало (`/`) сервер сам уводит обратно в
        // мобильный раздел — проверено, `/` отвечает переходом на `/m/`. А `/explore` и
        // страницы игр вида `/g/<номер>` открываются как есть и работают со входом.
        web.loadUrl("https://dustore.ru/explore");

        Toast.makeText(this,
                "Сайт ответил ошибкой на мобильной странице игры. "
                        + "Открыл полную версию каталога — там игра открывается.",
                Toast.LENGTH_LONG).show();
    }

    /** Когда в последний раз уходили из сломанного раздела — чтобы не ходить по кругу. */
    private long lastEscape;

    /**
     * Открылась ли страница по-настоящему.
     *
     * Ждать кода ответа оказалось недостаточно: мобильный раздел сайта устроен как
     * приложение внутри страницы, часть содержимого он подгружает сам, и ошибка сервера до
     * приложения долетает не всегда — экран просто остаётся пустым. Поэтому спрашиваем по
     * факту: есть ли на странице хоть одна буква. Если через секунду с небольшим нет ни
     * одной — это и есть тот самый серый экран, и дальше ждать нечего.
     */
    private static String safePage(String url) {
        Uri uri = Uri.parse(url == null ? "" : url);
        return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
    }

    private void checkNotBlank(WebView view, String url) {
        if (view == null || !isMobileSection(url)) {
            return;
        }

        view.postDelayed(() -> {
            if (web == null) {
                return;
            }

            // Судим только ту страницу, ради которой проверку и заводили.
            //
            // Полторы секунды — это целая жизнь: за них человек успевает нажать «Войти», и
            // тогда проверка смотрит уже на следующую страницу, которая в этот момент
            // законно пуста — она ещё грузится. Дальше срабатывал уход в полную версию, и
            // со стороны это выглядело так, будто кнопка «Войти» выбрасывает на начальный
            // экран.
            String now = web.getUrl();

            if (now == null || !now.equals(url)) {
                return;
            }

            web.evaluateJavascript(
                    "((document.body && document.body.innerText) || '').replace(/\s/g,'').length",
                    answer -> {
                        int letters = 0;

                        try {
                            letters = Integer.parseInt(answer == null
                                    ? "0" : answer.replace("\"", "").trim());
                        } catch (Exception unreadable) {
                            return;
                        }

                        if (letters > 0) {
                            return;
                        }

                        android.util.Log.i("dustore-web", "страница открылась пустой: " + safePage(url));
                        escapeBrokenMobileSite(url);
                    });
        }, 1400);
    }

    private TextView siteModeLabelView;

    private void refreshSiteModeLabel() {
        if (siteModeLabelView != null) {
            siteModeLabelView.setText(siteModeLabel());
        }
    }

    private void open(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ex) {
            Toast.makeText(this, "Нечем открыть эту ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    /** Ссылка вида tg://… — просьба открыть приложение, которого может и не быть. */
    private void openApp(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ex) {
            Toast.makeText(this, "На телефоне нет приложения для этой ссылки",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean isAuthHost(String host) {
        if (host == null) {
            return false;
        }

        String lower = host.toLowerCase(java.util.Locale.ROOT);

        for (String known : AUTH_HOSTS) {
            if (lower.equals(known) || lower.endsWith("." + known)) {
                return true;
            }
        }

        return false;
    }

    /** Ссылка ведёт на файл, а не на страницу — по расширению и по знакомым раздатчикам. */
    private static boolean looksLikeFile(Uri uri) {
        String path = uri.getPath();
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);

        for (String ext : new String[] {
                ".apk", ".zip", ".rar", ".7z", ".exe", ".tar", ".gz", ".tgz", ".bz2", ".xz"
        }) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }

        // «download_apk.php?game_id=218» — без расширения, но это точно файл.
        return lower.contains("download");
    }

    /**
     * Всплывающее окно входа.
     *
     * Виджет Telegram (и почти любой вход через чужой сервис) открывает окно вызовом
     * window.open и ждёт, что оно само доложит о результате. Поэтому окно должно быть
     * настоящим WebView в том же приложении: тогда «окно-родитель» получит ответ и сайт
     * узнает о входе. Открыть эту страницу в браузере — значит оборвать разговор на
     * полуслове.
     */
    private boolean openPopup(Message resultMsg) {
        WebView child = new WebView(this);
        configure(child.getSettings());
        CookieManager.getInstance().setAcceptThirdPartyCookies(child, true);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = Ui.column(this);
        box.setBackgroundColor(Ui.BACK);
        LinearLayout controls = Ui.row(this);
        TextView address = Ui.text(this, "Открываем…", 13, Ui.TEXT);
        controls.addView(address, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        TextView close = Ui.button(this, "Закрыть", false);
        close.setOnClickListener(v -> dialog.dismiss());
        controls.addView(close);
        box.addView(controls);
        box.addView(child, new LinearLayout.LayoutParams(-1, 0, 1f));
        dialog.setContentView(box);
        final boolean[] authenticating = { false };
        final boolean[] childDisposed = { false };
        Runnable disposeChild = () -> {
            if (childDisposed[0]) return;
            childDisposed[0] = true;
            disposeWebView(child);
        };
        child.setWebViewClient(new WebViewClient() {
            @Override public boolean onRenderProcessGone(WebView view,
                    android.webkit.RenderProcessGoneDetail detail) {
                disposeChild.run();
                dialog.dismiss();
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                Uri uri = Uri.parse(url == null ? "" : url);
                address.setText(uri.getHost() == null ? "Открываем…" : uri.getHost());
                String path = uri.getPath();
                if (isAuthHost(uri.getHost()) || (path != null
                        && (path.startsWith("/login") || path.startsWith("/register")))) {
                    authenticating[0] = true;
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("https".equals(scheme) || "http".equals(scheme)) return false;
                openApp(request.getUrl());
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                Uri uri = Uri.parse(url == null ? "" : url);
                String path = uri.getPath();
                // Переносим завершённую страницу после входа, сохраняя POST и callback.
                if (authenticating[0] && HOST.equals(uri.getHost()) && path != null
                        && (path.equals("/m/") || path.equals("/") || path.equals("/explore"))) {
                    if (web != null) web.loadUrl(url);
                    dialog.dismiss();
                }
            }
        });
        child.setDownloadListener((url, agent, disposition, mime, size) -> {
            String page = child.getUrl();
            download(url, disposition, mime, page == null ? web.getUrl() : page);
            dialog.dismiss();
        });
        child.setWebChromeClient(new WebChromeClient() {
            @Override public void onCloseWindow(WebView window) { dialog.dismiss(); }
            @Override public boolean onCreateWindow(WebView view, boolean isDialog,
                    boolean isUserGesture, Message message) { return openPopup(message); }
        });
        dialog.setOnKeyListener((d, key, event) -> {
            if (key == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP
                    && child.canGoBack()) { child.goBack(); return true; }
            return false;
        });
        dialog.setOnDismissListener(d -> {
            CookieManager.getInstance().flush();
            // Даём window.opener/postMessage доставить ответ, не перезагружаем родителя.
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(disposeChild, 300);
        });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(child);
        resultMsg.sendToTarget();
        return true;
    }

    // ------------------------------------------------------------------ вкладки ----

    private LinearLayout navigation() {
        PremiumNav bar = new PremiumNav(this);
        navigationBar = bar;
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Ui.bar(this, true));
        bar.setPadding(Ui.dp(this, Ui.S2), Ui.dp(this, Ui.S2),
                Ui.dp(this, Ui.S2), Ui.dp(this, Ui.S2));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Полоса — последнее, что стоит над системной навигацией телефона, поэтому нижнюю
        // системную врезку берёт именно она. Экраны выше её намеренно не берут, иначе
        // отступ вышел бы двойным.
        Ui.fit(bar, false, true, true);

        // «С компьютера» в две строки ломало высоту полосы и заставляло соседние подписи
        // прыгать. Слово короче — строка одна, и вкладка перестаёт быть выше остальных.
        String[] names = {"Магазин", "Библиотека", "Компьютер"};
        String[] icons = {"bag", "cube", "monitor"};
        TextView[] tabs = new TextView[names.length];
        navTabs = tabs;

        for (int i = 0; i < names.length; i++) {
            final int index = i;

            TextView tab = Ui.text(this, names[i], Ui.T_LABEL, Ui.MUTED);
            tab.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tab.setCompoundDrawablesWithIntrinsicBounds(null,
                    Ui.icon(this, icons[i], 22, i == 0 ? Ui.ACCENT : Ui.MUTED), null, null);
            tab.setCompoundDrawablePadding(Ui.dp(this, 3));
            tab.setTag(icons[i]);
            tab.setSelected(i == 0);
            styleTab(tab, i == 0);
            tab.setGravity(Gravity.CENTER);
            tab.setMaxLines(1);
            tab.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tab.setMinHeight(Ui.dp(this, 58));
            tab.setPadding(Ui.dp(this, Ui.S1), Ui.dp(this, 6),
                    Ui.dp(this, Ui.S1), Ui.dp(this, 6));
            tab.setClickable(true);
            tab.setForeground(Ui.tap(this, 20));
            tab.setFocusable(true);
            tab.setContentDescription(names[i]);
            Ui.touchMotion(tab);

            tab.setOnClickListener(v -> {
                show(index);

            });

            tabs[i] = tab;
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            item.setMargins(Ui.dp(this, Ui.S1), 0, Ui.dp(this, Ui.S1), 0);
            bar.addView(tab, item);
        }

        return bar;
    }

    private void styleTab(TextView tab, boolean active) {
        tab.setTextColor(active ? Ui.ACCENT : Ui.MUTED);
        // The vector icon draws in a fixed colour, so the selected one is drawn anew.
        Object name = tab.getTag();
        if (name instanceof String)
            tab.setCompoundDrawablesWithIntrinsicBounds(null, Ui.icon(this, (String) name, 22, active ? Ui.ACCENT : Ui.MUTED), null, null);
        tab.setTypeface(active ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        tab.setBackground(null);
    }

    private void show(int index) {
        int previous = shownScreen;
        boolean changed = shownScreen != index;
        for (View screen : new View[] {storeScreen, libraryScreen, pcScreen}) Motion.reset(screen);
        shownScreen = index;
        storeScreen.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        libraryScreen.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        pcScreen.setVisibility(index == 2 ? View.VISIBLE : View.GONE);

        if (navTabs != null) for (int j = 0; j < navTabs.length; j++) {
            navTabs[j].setSelected(j == index);
            styleTab(navTabs[j], j == index);
        }
        if (navigationBar != null) navigationBar.select(index, changed);

        if (changed) {
            View incoming = index == 0 ? storeScreen : index == 1 ? libraryScreen : pcScreen;
            Motion.reveal(incoming, 0, index >= previous ? 1 : -1);
        }

        if (index != 2) pcScreen.stop();

        if (index == 2) {
            pcScreen.load();
        }

        if (index == 1) {
            syncDownloads();
            libraryScreen.refresh();
        }

    }

    /** Turn motion off immediately, including an in-flight page or navigation transition. */
    void settleMotion() {
        settleView(storeScreen); settleView(libraryScreen); settleView(pcScreen);
        if (navigationBar != null) { settleView(navigationBar); navigationBar.select(shownScreen, false); }
    }

    private void settleView(View view) {
        Motion.reset(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) settleView(group.getChildAt(i));
        }
    }

    // ---------------------------------------------------------------- загрузки ----

    /**
     * Скачивание.
     *
     * Всё, что скачано со страницы игры, попадает в библиотеку — apk, zip, что угодно:
     * приложение запоминает, с какой страницы файл взяли, чтобы отзыв ушёл к этой игре, а
     * что внутри — разберёт examine() по содержимому. Файлы не со страниц игр уходят
     * системному менеджеру в общую папку.
     */
    private void download(String url, String disposition, String mime, String pageUrl) {
        String gameKey = GameKey.of(pageUrl);

        if (gameKey == null) {
            // Файл не со страницы игры — привязать его в библиотеке не к чему.
            downloadPlainFile(url, mime);
            return;
        }

        // Никакого отбора по типу: В БИБЛИОТЕКУ ИДЁТ ВСЁ, что скачано со страницы игры.
        // Раньше сюда пускали только .apk, а zip уезжал в системные «Загрузки» — человек
        // качал игру, смотрел в библиотеку и не находил её. Что внутри файла — apk,
        // веб-сборка, сборка для Windows — выяснит examine() по содержимому: имя и
        // заголовки на этом сайте не значат ничего.

        try {
            Uri uri = Uri.parse(url);
            String name = java.util.UUID.randomUUID().toString().substring(0, 8) + "-"
                    + fileName(uri, disposition, mime, gameKey).replaceAll("[\\\\/:*?\"<>|]", "_");

            DownloadManager.Request request = new DownloadManager.Request(uri);
            downloadHeaders(request, url, pageUrl);
            request.setTitle(title());
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            // Тип оставляем как есть: zip, назвавшийся apk, менеджер загрузок принимает
            // любым, а вот apk с типом zip установщик потом не открыл бы.
            if (mime != null && mime.contains("android.package-archive")) {
                request.setMimeType(Files.APK_TYPE);
            }

            // Своя папка приложения, а не общие «Загрузки»: файл нужен библиотеке, его
            // не надо искать руками, и никаких разрешений на чужие папки для этого не
            // требуется.
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);

            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            long id = manager.enqueue(request);

            Library.Entry entry = new Library.Entry();
            entry.downloadId = id;
            entry.gameKey = gameKey;
            entry.title = title();
            entry.pageUrl = pageUrl;
            entry.fileName = name;
            entry.addedUtc = System.currentTimeMillis();

            library.add(entry);
            libraryScreen.refresh();

            Toast.makeText(this, "Скачиваем — игра появится в «Библиотеке»",
                    Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            Toast.makeText(this, "Не удалось начать загрузку", Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadPlainFile(String url, String mime) {
        try {
            Uri uri = Uri.parse(url);
            String name = uri.getLastPathSegment();

            DownloadManager.Request request = new DownloadManager.Request(uri);
            downloadHeaders(request, url, web.getUrl());
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, name == null ? "dustore-file" : name);

            if (mime != null) {
                request.setMimeType(mime);
            }

            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            manager.enqueue(request);

            Toast.makeText(this, "Загрузка началась — файл появится в «Загрузках»",
                    Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            Toast.makeText(this, "Не удалось начать загрузку", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Название игры со страницы.
     *
     * Заголовок выглядит как «Dustore — NEON DASH: UPRISE», и брать первую часть нельзя:
     * так в библиотеку попадает «Dustore» вместо имени игры (на чём этот код и попался).
     * Название площадки отбрасывается, а из оставшегося берётся самая длинная часть — имя
     * игры почти всегда длиннее хвостов вроде «Игры» или «Каталог».
     */
    private void downloadHeaders(DownloadManager.Request request, String url, String page) {
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
        request.addRequestHeader("User-Agent", web.getSettings().getUserAgentString());
        if (page != null && page.startsWith("https://")) request.addRequestHeader("Referer", page);
    }

    private String title() {
        String title = web.getTitle();

        if (title == null || title.trim().isEmpty()) {
            return "Игра";
        }

        String best = "";

        for (String part : title.split("\\s[|—·]\\s|\\s-\\s")) {
            String piece = part.trim();

            if (piece.isEmpty() || piece.equalsIgnoreCase("Dustore")
                    || piece.equalsIgnoreCase("dustore.ru")) {
                continue;
            }

            if (piece.length() > best.length()) {
                best = piece;
            }
        }

        return best.isEmpty() ? title.trim() : best;
    }

    /**
     * Имя файла.
     *
     * Настоящее имя приходит в заголовке Content-Disposition — «NEON_DASH__UPRISE.apk», —
     * тогда как в самой ссылке стоит «download_apk.php». Первое человек узнаёт, второе не
     * говорит ни о чём, поэтому спрашиваем систему: она разберёт заголовок по правилам и
     * подставит ссылку, только если в заголовке пусто.
     */
    private String fileName(Uri uri, String disposition, String mime, String gameKey) {
        try {
            String guess = android.webkit.URLUtil.guessFileName(uri.toString(), disposition, mime);

            if (guess != null && !guess.isEmpty() && !guess.equals("downloadfile.bin")) {
                return guess.replaceAll("[^A-Za-z0-9._-]", "_");
            }
        } catch (Exception ignored) {
            // Ниже есть запасной путь — падать из-за имени файла точно не стоит.
        }

        return gameKey.replaceAll("[^A-Za-z0-9]", "_") + ".apk";
    }

    private void listenForDownloads() {
        downloads = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                Library.Entry entry = library.byDownload(id);

                if (entry == null) {
                    return;
                }

                syncDownloads();
                libraryScreen.refresh();

                Toast.makeText(MainActivity.this,
                        library.downloadComplete(entry)
                                ? "«" + entry.title + "» скачана — откройте «Библиотеку»"
                                : "Не удалось скачать «" + entry.title + "». Повторите из библиотеки.",
                        Toast.LENGTH_LONG).show();
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);

        // С Android 13 при регистрации приёмника надо прямо сказать, ждём ли мы сообщений
        // извне. Это сообщение шлёт система, значит — да.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloads, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloads, filter);
        }
    }

    /**
     * Догоняет состояние загрузок.
     *
     * Сообщение о завершении можно и не услышать — приложение могли закрыть, — поэтому
     * состояние не хранится, а каждый раз выясняется заново: файл на месте? имя пакета
     * прочитано? Так библиотека не расходится с действительностью.
     */
    private void syncDownloads() {
        if (synchronizingDownloads) return;
        synchronizingDownloads = true;
        UiWork.execute(() -> {
        try {
        for (Library.Entry entry : library.all()) {
            // Разбирается только то, что менеджер загрузок объявил ГОТОВЫМ. Раньше архив
            // читался, как только файл появлялся на диске, — а появляется он с первых же
            // байт. Разбор спотыкался о половину архива, записывал «не удалось разобрать»
            // и больше не пытался: игра качалась честно, а выглядела сломанной.
            //
            // «unknown» здесь тоже перечитывается — этим лечатся записи, уже испорченные
            // старой версией: файл давно докачан, и со второго взгляда он опознаётся.
            boolean unresolved = entry.kind == null || "unknown".equals(entry.kind);

            if (unresolved && library.downloaded(entry) && library.downloadComplete(entry)) {
                library.examine(entry);
            }
        }
        library.catchUp();
        } finally {
            synchronizingDownloads = false;
            runOnUiThread(() -> { if (!isDestroyed()) libraryScreen.refresh(); });
        }
        });
    }

    /** Закончил ли менеджер загрузок с этим файлом — а не просто начал его писать. */
    private boolean downloadFinished(Library.Entry entry) {
        if (entry.downloadId <= 0) {
            // Старые записи без номера загрузки: файл лежит годами, он точно дописан.
            return true;
        }

        try {
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            android.database.Cursor cursor = manager.query(
                    new DownloadManager.Query().setFilterById(entry.downloadId));

            if (cursor == null) {
                return true;
            }

            try {
                if (!cursor.moveToFirst()) {
                    // Менеджер о загрузке уже забыл (историю чистят), а файл на месте —
                    // значит, он дописан.
                    return true;
                }

                int status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                return status == DownloadManager.STATUS_SUCCESSFUL;
            } finally {
                cursor.close();
            }
        } catch (Exception ex) {
            return true;
        }
    }

    // ------------------------------------------------------- действия с играми ----



    /**
     * «Эмулировать»: сборка для Windows становится игрой на телефоне.
     *
     * Конвейер идёт своим потоком, а здесь окно с шагами и лентой журнала: человек видит,
     * что происходит, а не крутилку. В конце — предложение поставить APK игры.
     */
    @Override
    public void emulate(Library.Entry entry) {
        if (!library.ready(entry)) {
            Toast.makeText(this, "Дождитесь завершения загрузки игры", Toast.LENGTH_LONG).show();
            return;
        }
        ExTask task = ExTask.get(this);
        String problem = task.convert(library, entry);
        if (problem != null) Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
        ExTaskSheet.show(this, entry);
    }


    @Override
    public void playEx(Library.Entry entry) {
        if (!library.ready(entry)) {
            Toast.makeText(this, "Дождитесь завершения загрузки игры", Toast.LENGTH_LONG).show();
            return;
        }
        if (entry.exId == null) {
            emulate(entry);
            return;
        }

        Intent intent = new Intent(this, ExActivity.class);
        intent.setAction(ExActivity.ACTION_PLAY);
        intent.putExtra(ExActivity.EXTRA_GAME, entry.exId);
        releaseStoreWeb(false);
        startActivity(intent);
    }

    private void disposeWebView(WebView view) {
        if (view == null) return;
        android.view.ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
        view.destroy();
    }

    /** The store is not needed during eXen and its Chromium renderer costs RAM. */
    private void releaseStoreWeb(boolean rendererGone) {
        WebView old = web;
        if (old == null) return;
        String url = old.getUrl();
        if (url != null && (url.startsWith("https://dustore.ru/")
                || url.startsWith("https://www.dustore.ru/"))) {
            resumeStoreUrl = url;
        }
        web = null;
        CookieManager.getInstance().flush();
        if (!rendererGone) old.stopLoading();
        disposeWebView(old);
    }

    private void restoreStoreWeb() {
        if (web != null || screens == null || storeScreen == null) return;
        int position = screens.indexOfChild(storeScreen);
        int visibility = storeScreen.getVisibility();
        screens.removeView(storeScreen);
        storeScreen = buildStore(null);
        storeScreen.setVisibility(visibility);
        screens.addView(storeScreen, Math.max(0, position));
    }

    @Override
    public void installEx(Library.Entry entry) {
        ExGame game = entry.exId == null ? null : ExGame.load(this, entry.exId);

        if (game == null || game.apk == null || !game.apk.exists()) {
            emulate(entry);
            return;
        }

        // Установщику файл отдаётся через наш провайдер, а он смотрит только в папку
        // загрузок приложения — кладём копию туда.
        File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        File copy = new File(dir, game.id + ".apk");

        try (java.io.FileInputStream in = new java.io.FileInputStream(game.apk);
             java.io.FileOutputStream out = new java.io.FileOutputStream(copy)) {
            byte[] buffer = new byte[1 << 16];
            int read;

            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (Exception broken) {
            Toast.makeText(this, "APK не скопировался: " + broken.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            waitingForPermission = entry;
            waitingInstallEx = true;
            Toast.makeText(this,
                    "Разрешите установку из Dustore. После возвращения откроем установщик игры.",
                    Toast.LENGTH_LONG).show();

            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ex) {
                Toast.makeText(this, "Разреши установку в настройках приложения",
                        Toast.LENGTH_LONG).show();
            }

            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Files.uriFor(copy), Files.APK_TYPE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ex) {
            Toast.makeText(this, "Не удалось открыть установщик", Toast.LENGTH_SHORT).show();
        }
    }

    /** Первый .exe в распакованной сборке — игру ищем по файлу, а не по имени папки. */
    private String findExe(File root, File folder) {
        File[] children = folder.listFiles();

        if (children == null) {
            return null;
        }

        for (File child : children) {
            if (child.isFile() && child.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".exe")
                    && !child.getName().toLowerCase(java.util.Locale.ROOT).contains("crashhandler")
                    && !child.getName().toLowerCase(java.util.Locale.ROOT).contains("unins")) {
                return child.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
            }
        }

        for (File child : children) {
            if (child.isDirectory()) {
                String found = findExe(root, child);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    @Override
    public void install(Library.Entry entry) {
        waitingInstallEx = false;
        File file = library.fileOf(entry);

        if (file == null || !file.exists()) {
            Toast.makeText(this, "Файл не найден — скачай игру заново", Toast.LENGTH_SHORT).show();
            return;
        }

        // Android не даёт ставить приложения из чужих рук, пока человек сам не разрешит
        // это конкретной программе. Без объяснения такой отказ выглядит как поломка,
        // поэтому сначала слова, потом системное окно.
        //
        // И — главное — игра запоминается. Раньше здесь всё обрывалось: человек уходил в
        // настройки, разрешал, возвращался, а установка не начиналась, потому что про неё
        // уже никто не помнил. Выглядело это как «кнопка не работает», и именно на этом
        // всё и застряло.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            waitingForPermission = entry;

            Toast.makeText(this,
                    "Разреши установку из Dustore — и установка продолжится сама",
                    Toast.LENGTH_LONG).show();

            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ex) {
                Toast.makeText(this, "Разреши установку в настройках приложения",
                        Toast.LENGTH_LONG).show();
            }

            return;
        }

        try {
            // Снимок установленного — на случай, если имя пакета из файла прочитать не
            // удалось: тогда после установки его можно будет вычислить сравнением.
            installing = entry;
            beforeInstall = library.launchable();

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Files.uriFor(file), Files.APK_TYPE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ex) {
            Toast.makeText(this, "Не удалось открыть установщик", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Что появилось на телефоне, пока мы ждали установщик.
     *
     * Обычно имя пакета уже известно — оно прочитано из самого файла. Но если файл
     * разобрать не вышло, установленную игру иначе не опознать, и она навсегда осталась бы
     * с кнопкой «Установить».
     */
    private void finishInstall() {
        // Разрешение только что дали — продолжаем ровно с того места, где остановились.
        if (waitingForPermission != null
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || getPackageManager().canRequestPackageInstalls())) {
            Library.Entry pending = waitingForPermission;
            waitingForPermission = null;
            boolean ex = waitingInstallEx; waitingInstallEx = false;
            if (ex) installEx(pending); else install(pending);
            return;
        }

        if (installing == null) {
            return;
        }

        Library.Entry entry = installing;

        if (entry.packageName == null && beforeInstall != null) {
            for (String name : library.launchable()) {
                if (!beforeInstall.contains(name)) {
                    entry.packageName = name;
                    library.save();
                    break;
                }
            }
        }

        // Установка либо прошла, либо человек её отменил — оба раза ждать больше нечего.
        installing = null;
        beforeInstall = null;

        if (library.installed(entry)) {
            Toast.makeText(this, "«" + entry.title + "» установлена — можно играть",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Молчать здесь нельзя. Прерванная или отменённая установка выглядит точно так же,
        // как удачная: человек возвращается в лаунчер и видит прежнюю кнопку. Именно
        // поэтому и решают, что «кнопка не работает».
        Toast.makeText(this, "«" + entry.title + "» не установилась. Нажми «Установить» ещё раз.",
                Toast.LENGTH_LONG).show();
    }

    @Override
    public void play(Library.Entry entry) {
        // Веб-сборка идёт в своём окне внутри лаунчера — устанавливать нечего.
        if (entry.isWeb()) {
            if (!library.ready(entry)) {
                Toast.makeText(this, "Игра ещё не готова", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent game = new Intent(this, GameActivity.class);
            game.putExtra(GameActivity.EXTRA_FOLDER, entry.folder);
            game.putExtra(GameActivity.EXTRA_TITLE, entry.title);

            library.startedPlaying(entry);
            playing = entry;

            startActivity(game);
            return;
        }

        Intent intent = library.launchIntent(entry);

        if (intent == null) {
            Toast.makeText(this, "Игра не установлена", Toast.LENGTH_SHORT).show();
            libraryScreen.refresh();
            return;
        }

        // Засечка ставится здесь: игра запущена отсюда, значит момент начала известен
        // точно, а конец — это возвращение человека в приложение.
        library.startedPlaying(entry);
        playing = entry;

        startActivity(intent);
    }

    @Override
    public void openPage(Library.Entry entry) {
        if (entry.pageUrl == null) {
            Toast.makeText(this, "Ссылка на страницу игры не сохранилась", Toast.LENGTH_SHORT).show();
            return;
        }

        show(0);
        web.loadUrl(entry.pageUrl);
    }

    /** adb: am start -n ru.dustore.launcher/.MainActivity --ez dustore.quota.selftest true */
    private void quotaSelfTest(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("dustore.quota.selftest", false)) return;
        intent.removeExtra("dustore.quota.selftest");
        if (!debugDoorOpen() || ExSession.busy()) return;
        new Thread(() -> android.util.Log.i("dustore-quota", "selftest " + Prime.quotaSelfTest(this)), "quota-selftest").start();
    }

    @Override
    public void openStore() {
        if (navTabs != null && navTabs[0] != null) navTabs[0].performClick();
    }

    private TextView[] navTabs;

    @Override
    public void forget(Library.Entry entry) {
        if (entry.isPc() && ExSession.busy()) {
            Toast.makeText(this, "Сначала завершите игру или подготовку eX", Toast.LENGTH_LONG).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Удалить «" + entry.title + "» с устройства?")
                .setMessage("Скачанный файл и распакованная папка этой карточки будут удалены. Игра исчезнет из библиотеки. Уже установленные Android-приложения сохранятся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> UiWork.execute(() -> {
                    library.remove(entry);
                    runOnUiThread(() -> { if (!isDestroyed()) { libraryScreen.refresh(); Toast.makeText(this, "Игра удалена из библиотеки", Toast.LENGTH_SHORT).show(); } });
                })).show();
    }

    @Override public void cancelDownload(Library.Entry entry) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Остановить загрузку «" + entry.title + "»?")
                .setMessage("Незавершённая загрузка будет удалена. Скачать игру снова можно с её страницы.")
                .setNegativeButton("Продолжить загрузку", null)
                .setPositiveButton("Остановить", (dialog, which) -> UiWork.execute(() -> {
                    DownloadManager manager = getApplicationContext().getSystemService(DownloadManager.class);
                    try {
                        if (manager != null && entry.downloadId > 0) manager.remove(entry.downloadId);
                        entry.downloadCancelled = true; library.save();
                        runOnUiThread(() -> { if (!isDestroyed()) libraryScreen.refresh(); });
                    } catch (RuntimeException problem) {
                        runOnUiThread(() -> { if (!isDestroyed()) Toast.makeText(this, "Загрузку не удалось остановить", Toast.LENGTH_LONG).show(); });
                    }
                })).show();
    }

    // -------------------------------------------------------------- жизнь окна ----

    /** Back goes back through the site before it leaves the app. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && storeScreen.getVisibility() == View.VISIBLE
                && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putInt("prime.screen", shownScreen);
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            web.onPause();
        }
        ExTask.get(this).remove(exTaskChanged);
        pcScreen.stop();

        // WebView пишет куки лениво, а убитый системой процесс — потерянная сессия:
        // человек входил, всё работало, а назавтра он снова разлогинен.
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreStoreWeb();
        if (web != null) {
            web.onResume();
        }
        ExTask.get(this).observe(exTaskChanged);
        if (pcScreen.getVisibility() == View.VISIBLE) pcScreen.load();

        syncDownloads();
        finishInstall();

        // Библиотека перерисовывается ВСЕГДА при возвращении в приложение, а не только
        // после игры. На этом код и попался: человек нажимал «Установить», ставил игру,
        // возвращался — а кнопка оставалась прежней, потому что экран никто не обновлял.
        // Из-за этого игру нельзя было запустить отсюда, а значит и оценить.
        libraryScreen.refresh();

        // Человек вернулся из игры — значит, партия закончилась и об игре есть что сказать.
        // Спрашиваем сразу, независимо от того, сколько он там пробыл: минута в игре —
        // это тоже мнение, и часто самое честное.
        if (playing != null) {
            Library.Entry entry = playing;
            playing = null;

            library.stoppedPlaying(entry);
            libraryScreen.refresh();

            return;
        }
    }

    @Override
    protected void onDestroy() {
        pcScreen.stop();
        if (downloads != null) {
            try {
                unregisterReceiver(downloads);
            } catch (Exception ignored) {
                // Приёмник мог и не зарегистрироваться — падать из-за этого при закрытии
                // окна незачем.
            }
            downloads = null;
        }

        if (web != null) {
            disposeWebView(web);
            web = null;
        }

        super.onDestroy();
    }
}
