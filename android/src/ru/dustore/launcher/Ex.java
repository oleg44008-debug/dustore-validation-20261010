package ru.dustore.launcher;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * eX — система запуска игр для Windows на телефоне. Без компьютера.
 *
 * Здесь нет чужого приложения-эмулятора: экран, ввод, пады, установка, сборка, разбор
 * игры и запуск написаны в этом проекте. Четыре открытых мотора внутри названы честно, и
 * без них .exe на ARM-телефоне не запустить ничем:
 *
 *   * **Box64** переводит машинный код x86-64 в код ARM на лету;
 *   * **Wine** отвечает за то, что программа для Windows зовёт у Windows;
 *   * **Mesa Turnip** — открытый драйвер Vulkan для видеокарт Adreno;
 *   * **DXVK** переводит DirectX 8–11 в Vulkan.
 *
 * Всё остальное — наше: экран ({@link ExDisplay}, композитор Wayland внутри приложения),
 * ввод и пады, сборка ({@link ExBuild}) из частей ({@link ExParts}), которые телефон
 * качает сам, рецепт под игру ({@link ExRecipe}), переукладка программ под правила
 * Android ({@link ExPe}) и сборка APK-игры ({@link ExApk}).
 *
 * Почему бинарники лежат в lib-каталоге и называются lib*.so: Android с 10-й версии
 * запрещает исполнять файлы из папки данных приложения. Единственное место, откуда
 * система разрешает запуск, — каталог нативных библиотек APK.
 */
final class Ex {

    /** Система не разворачивалась вовсе. */
    static final int STAGE_NONE = 0;

    /** Есть основа: Linux внутри приложения и переводчик кода. */
    static final int STAGE_BASE = 1;

    /** Довезены детали: экранные библиотеки, шрифты, части x86_64. */
    static final int STAGE_PARTS = 2;

    /** Есть слой Windows. */
    static final int STAGE_WINDOWS = 3;

    /** Есть драйвер видеокарты (или честно записано, что его нет и рисуем программно). */
    static final int STAGE_GPU = 4;

    /** Есть перевод DirectX: игры могут запускаться целиком. */
    static final int STAGE_DXVK = 5;

    /** Путь к описанию драйвера Vulkan внутри системы — его ставит сборка. */
    static final String ICD = "/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json";

    /** Куда разворачивается система. Личная папка приложения — её не видно чужим. */
    static File home(Context context) {
        File fresh = new File(context.getFilesDir(), "ex");
        File old = new File(context.getFilesDir(), "exen");

        // Системы, собранные под прежним именем, переезжают под новое без пересборки.
        if (!fresh.exists() && old.exists()) {
            old.renameTo(fresh);
        }

        return fresh;
    }

    static File rootfs(Context context) {
        return new File(home(context), "rootfs");
    }

    /**
     * Папка, через которую игра находит наш экран.
     *
     * Это XDG_RUNTIME_DIR изнутри системы: там лежит сокет композитора. Папка обязана быть
     * нашей — сокет создаёт приложение, а не контейнер.
     */
    static File runDir(Context context) {
        File dir = new File(home(context), "run");

        if (!dir.exists()) {
            dir.mkdirs();
        }

        return dir;
    }

    /** Игры, которые привёз компьютер по кабелю: внешняя папка приложения. */
    static File gamesDir(Context context) {
        return new File(context.getExternalFilesDir(null), "games");
    }

    /**
     * Игры, которые эмулированы здесь: внутренняя память.
     *
     * Только отсюда программа исполняется: общее хранилище телефона смонтировано без права
     * исполнять, и слой Windows на нём молча не запускает ничего.
     */
    static File localGames(Context context) {
        File dir = new File(home(context), "games");

        if (!dir.exists()) {
            dir.mkdirs();
        }

        return dir;
    }

    /**
     * До какого места система собрана.
     *
     * Считается по самим файлам, а не по записи «мы это уже делали»: запись врёт после
     * любого обрыва, файл — нет.
     *
     * Про box64 нельзя спрашивать exists(): это ссылка на `/ex-lib/libbox64.so` — путь,
     * который существует ВНУТРИ контейнера. exists() идёт по ссылке и отвечает «нет».
     * Спрашиваем саму ссылку.
     */
    static int stage(Context context) {
        File root = rootfs(context);

        if (!new File(root, "bin/bash").exists()) {
            return STAGE_NONE;
        }

        try {
            android.system.Os.lstat(new File(root, "usr/local/bin/box64").getAbsolutePath());
        } catch (Exception missing) {
            return STAGE_NONE;
        }

        if (!new File(root, "opt/x86_64/libgcc_s.so.1").exists()
                || !new File(root, "usr/bin/xz").exists()
                || !new File(root, "usr/bin/vulkaninfo").exists()) {
            return STAGE_BASE;
        }

        // Hangover is a complete Wine/DirectX route in its own prefix. Requiring the
        // legacy /opt/wine files here would rebuild a second, memory-heavy runtime.
        if (ExNative.ready(context) && new File(root, ".ex-gpu").isFile()) {
            return STAGE_DXVK;
        }

        // Спрашиваем про `wine.real`, а не про `wine`: первый появляется только после
        // того, как на слой Windows поставлены наши переходники.
        if (!new File(root, "opt/wine/bin/wine.real").exists()
                || !new File(root, "root/.wine/system.reg").exists()) {
            return STAGE_PARTS;
        }

        if (!new File(root, ".ex-gpu").exists()) {
            return STAGE_WINDOWS;
        }

        if (!new File(root, ".ex-dxvk").exists()
                || !new File(root, "root/.wine/drive_c/windows/system32/d3d11.dll").exists()) {
            return STAGE_GPU;
        }

        return STAGE_DXVK;
    }

    /** Готова ли система запускать игры целиком. */
    static boolean installed(Context context) {
        return stage(context) >= STAGE_DXVK;
    }

    /** Есть ли хотя бы основа — Linux и переводчик кода. */
    static boolean baseReady(Context context) {
        return stage(context) >= STAGE_BASE;
    }

    static String stageName(int stage) {
        switch (stage) {
            case STAGE_BASE:
                return "есть основа — Linux и переводчик кода";
            case STAGE_PARTS:
                return "есть детали — остался слой Windows";
            case STAGE_WINDOWS:
                return "есть слой Windows — остались видеокарта и DirectX";
            case STAGE_GPU:
                return "есть видеокарта — остался перевод DirectX";
            case STAGE_DXVK:
                return "собрана целиком";
            default:
                return "не собрана";
        }
    }

    /** Строка о видеокарте, которую записала сборка: «turnip …» или «soft …». */
    static String gpuLine(Context context) {
        File file = new File(rootfs(context), ".ex-gpu");

        try (FileInputStream in = new FileInputStream(file)) {
            byte[] raw = new byte[4096];
            int got = in.read(raw);
            return got <= 0 ? "" : new String(raw, 0, got, "UTF-8").trim();
        } catch (Exception missing) {
            return "";
        }
    }

    /** Рисует ли видеокарта: Turnip завёлся на этом телефоне. */
    static boolean gpuReady(Context context) {
        return gpuLine(context).startsWith("turnip");
    }

    /** Имя видеокарты, как его назвал драйвер, или короткое объяснение, почему её нет. */
    static String gpuName(Context context) {
        String line = gpuLine(context);
        int space = line.indexOf(' ');
        return space < 0 ? line : line.substring(space + 1).trim();
    }

    /**
     * Приводит ответ для компьютера в соответствие с действительностью.
     *
     * Нужно для систем, собранных до появления этого ответа: они готовы, но файла с
     * ответом у них нет, и компьютер считал бы их несобранными.
     */
    static void syncState(Context context) {
        int stage = stage(context);
        File file = new File(context.getExternalFilesDir(null), "ex.state");

        if (stage >= STAGE_DXVK && !file.exists()) {
            state(context, "ready " + describe(context));
        } else if (stage < STAGE_DXVK && file.exists()) {
            file.delete();
        }
    }

    private static String lib(Context context, String name) {
        return new File(context.getApplicationInfo().nativeLibraryDir, name).getAbsolutePath();
    }

    /**
     * Полная команда запуска чего-либо внутри системы.
     *
     * Здесь же собраны все привязки папок, и каждая стоит по делу:
     *
     * * `/ex-lib` — каталог нативных библиотек APK, оттуда исполняется переводчик кода;
     * * тот же каталог **по своему настоящему пути** — без этого Wine не может запустить
     *   собственную службу: переводчик кода зовёт сам себя по пути из `/proc/self/exe`;
     * * `/parts` — части, из которых собирается система;
     * * `/games` — сборки, привезённые компьютером по кабелю;
     * * `/games-local` — сборки, эмулированные здесь (внутренняя память);
     * * `/run/ex` — наша папка с сокетом экрана: игра ищет экран именно там.
     */
    static List<String> argv(Context context, boolean withImage, String[] command) {
        File home = home(context);
        String libs = context.getApplicationInfo().nativeLibraryDir;

        List<String> line = new ArrayList<>();
        // ULTRA: вся система eX (Wine, Box64, игра) — только на больших ядрах.
        String bigCores = Prime.bigCoreMask();
        if (bigCores != null && new File("/system/bin/taskset").exists()) {
            line.add("/system/bin/taskset");
            line.add(bigCores);
        }
        line.add(lib(context, "libproot.so"));
        line.add("-r");
        line.add(rootfs(context).getAbsolutePath());
        line.add("-0");
        line.add("--link2symlink");
        line.add("-w");
        line.add("/root");
        line.add("-b");
        line.add("/dev");
        line.add("-b");
        line.add("/proc");
        line.add("-b");
        line.add("/sys");
        line.add("-b");
        line.add(libs + ":/ex-lib");
        line.add("-b");
        line.add(libs + ":" + libs);
        line.add("-b");
        line.add(runDir(context).getAbsolutePath() + ":/run/ex");
        line.add("-b");
        line.add(ExParts.dir(context).getAbsolutePath() + ":/parts");
        line.add("-b");
        line.add(localGames(context).getAbsolutePath() + ":/games-local");

        if (withImage) {
            line.add("-b");
            line.add(home.getAbsolutePath() + ":/image");
        }

        File games = gamesDir(context);

        if (games.exists()) {
            line.add("-b");
            line.add(games.getAbsolutePath() + ":/games");
        }

        for (String part : command) {
            line.add(part);
        }

        return line;
    }

    /**
     * Переменные, с которыми внутри системы всё находит друг друга — и видеокарту тоже.
     *
     * Главные три строки — про графику. Драйвер видеокарты ищется по `VK_DRIVER_FILES`;
     * `MESA_VK_WSI_DEBUG=sw` заставляет драйвер отдавать готовые кадры через общую память,
     * а не через ядро видеокарты — так они попадают прямо в наш экран, и никакой чужой
     * программы между игрой и стеклом телефона нет; `WINEDLLOVERRIDES` подставляет DXVK
     * вместо встроенного в Wine DirectX.
     */
    static void environment(Context context, Map<String, String> env) {
        env.put("PROOT_LOADER", lib(context, "libprootloader.so"));
        env.put("PROOT_TMP_DIR", tmp(context).getAbsolutePath());
        env.put("LD_LIBRARY_PATH", context.getApplicationInfo().nativeLibraryDir);
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
        env.put("HOME", "/root");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("BOX64_LOG", "0");

        // Часть библиотек x86_64 переводчик кода не подменяет своими — их настоящие
        // версии лежат здесь. Без этой строки Wine не находит libgcc и не стартует вовсе.
        env.put("BOX64_LD_LIBRARY_PATH", "/opt/x86_64");

        // Без JIT-рекомпилятора Box64 переводит инструкции по одной и игра превращается в
        // слайд-шоу. Строгая память нужна Unity и Unreal: без неё они рассыпаются.
        env.put("BOX64_DYNAREC", "1");
        // The Windows desktop starts more than one explorer.exe process. Box64's
        // larger translated blocks accumulate in each process and push Android
        // into swap pressure, where it may kill the user's VPN. Box64 recommends
        // smaller blocks for explorer and memory-heavy/JIT games.
        env.put("BOX64_DYNAREC_BIGBLOCK", "0");
        env.put("BOX64_DYNAREC_STRONGMEM", "1");
        env.put("BOX64_DYNAREC_SAFEFLAGS", "0");
        env.put("BOX64_DYNAREC_FASTNAN", "1");
        env.put("BOX64_DYNAREC_FASTROUND", "1");

        env.put("WINEPREFIX", "/root/.wine");
        env.put("WINEDEBUG", "-all");
        env.put("WINEESYNC", "1");

        // mscoree и mshtml — чтобы Wine не лез ставить Mono и Gecko на каждом запуске;
        // DXVK — родным (n) вместо встроенного.
        env.put("WINEDLLOVERRIDES",
                "mscoree,mshtml,winemenubuilder.exe=d;d3d8,d3d9,d3d10core,d3d11,dxgi=n");
        env.put("DXVK_STATE_CACHE_PATH", "/root/.dxvk");
        env.put("DXVK_LOG_PATH", "none");

        // Графика: драйвер видеокарты и кадры через общую память — в наш экран.
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
        env.put("MESA_VK_WSI_DEBUG", "sw");
        env.put("MESA_VK_WSI_PRESENT_MODE", "mailbox");

        if (gpuReady(context)) {
            env.put("VK_DRIVER_FILES", ICD);
            env.put("VK_ICD_FILENAMES", ICD);
        }

        // Игра ищет экран по этим двум переменным. Папка — наша, сокет в ней открывает
        // приложение: ни один чужой сервер в этой системе не запускается.
        env.put("XDG_RUNTIME_DIR", "/run/ex");
        env.put("WAYLAND_DISPLAY", ExDisplay.SOCKET_NAME);
        env.put("DISPLAY", "");
        env.put("PULSE_SERVER", "unix:/run/ex/pulse.sock");

        // Prime: режим «Максимум FPS» добавляет ускорения поверх надёжной основы.
        Prime.boostEnvironment(env);
    }

    /** Wine can reparent its children to PID 1, so destroying PRoot is not enough. */
    static int stopOwnChildren() {
        int uid = android.os.Process.myUid();
        int self = android.os.Process.myPid();
        java.util.List<Integer> children = ownedPids(uid, self);
        for (int pid : children) android.os.Process.sendSignal(pid, 15);
        if (!children.isEmpty()) {
            try { Thread.sleep(300); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        for (int pid : ownedPids(uid, self)) android.os.Process.killProcess(pid);
        return children.size();
    }

    private static java.util.List<Integer> ownedPids(int uid, int self) {
        java.util.List<Integer> result = new ArrayList<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return result;
        for (File entry : entries) {
            int pid;
            try { pid = Integer.parseInt(entry.getName()); }
            catch (NumberFormatException notPid) { continue; }
            if (pid == self || pid <= 1) continue;
            try (BufferedReader status = new BufferedReader(
                    new FileReader(new File(entry, "status")))) {
                String line;
                while ((line = status.readLine()) != null) {
                    if (!line.startsWith("Uid:")) continue;
                    String[] ids = line.substring(4).trim().split("\\s+");
                    if (ids.length > 0 && Integer.parseInt(ids[0]) == uid) result.add(pid);
                    break;
                }
            } catch (Exception gone) {
                // Processes may exit between listing /proc and reading their status.
            }
        }
        return result;
    }

    /**
     * Сценарий запуска игры: из её папки, внутри рабочего стола заданного размера.
     *
     * Из папки игры — потому что сборки ищут свои данные рядом с собой; внутри рабочего
     * стола — потому что без него слой Windows не удерживает свой управляющий процесс и
     * каждая игра получает «no driver could be loaded». Ключи из рецепта — в хвост.
     */
    static String gameCommand(String inside, String exeRelative, List<String> args) {
        return gameCommand(inside, exeRelative, args,
                ExActivity.GAME_WIDTH, ExActivity.GAME_HEIGHT);
    }

    static String gameCommand(String inside, String exeRelative, List<String> args,
                              int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Некорректное разрешение игры");
        }
        String relative = exeRelative.replace('\\', '/');
        if (relative.startsWith("/") || relative.indexOf('\0') >= 0
                || java.util.Arrays.asList(relative.split("/")).contains("..")) {
            throw new IllegalArgumentException("Файл запуска находится вне папки игры");
        }

        String where = relative.contains("/")
                ? inside + "/" + relative.substring(0, relative.lastIndexOf('/'))
                : inside;

        String file = relative.contains("/")
                ? relative.substring(relative.lastIndexOf('/') + 1) : relative;

        StringBuilder extra = new StringBuilder();

        if (args != null) {
            for (String arg : args) {
                extra.append(" ").append(quote(arg));
            }
        }

        return "cd " + quote(where) + " || exit 1\n"
                + "exec /opt/wine/bin/wine explorer /desktop=ex,"
                + width + "x" + height
                + " " + quote(file) + extra + "\n";
    }

    static String quote(String text) {
        if (text == null || text.indexOf('\0') >= 0) throw new IllegalArgumentException("Некорректный аргумент");
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    /** Запускает команду внутри системы и возвращает её вывод. */
    static String run(Context context, String[] command, boolean withImage, Progress progress) {
        Process process = null;
        ExWork.Hook cancellation = null;
        try {
            ExWork.check();
            // Сборка не должна бесконечно ждать apt/Wine или зависший дочерний процесс.
            List<String> bounded = new ArrayList<>();
            bounded.add("/usr/bin/timeout");
            bounded.add("--kill-after=5");
            bounded.add("600");
            java.util.Collections.addAll(bounded, command);
            ProcessBuilder builder = new ProcessBuilder(argv(context, withImage, bounded.toArray(new String[0])));
            builder.redirectErrorStream(true);
            environment(context, builder.environment());

            process = builder.start();
            final Process owned = process;
            cancellation = ExWork.hook(() -> {
                owned.destroy();
                try { owned.getInputStream().close(); } catch (Exception ignored) { }
            });
            StringBuilder output = new StringBuilder();

            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;

                while ((read = stream.read(buffer)) > 0) {
                    ExWork.check();
                    String piece = new String(buffer, 0, read, "UTF-8");
                    output.append(piece);
                    if (output.length() > 1024 * 1024) output.delete(0, output.length() - 1024 * 1024);

                    if (progress != null) {
                        progress.say(piece.trim());
                    }
                }
            }

            ExWork.check();
            int exit = process.waitFor();
            if (exit != 0) output.append("\nEX_EXIT_CODE: ").append(exit);
            return output.toString();
        } catch (Exception ex) {
            ExWork.check();
            return "EX_EXEC_FAILED: " + ex.getMessage();
        } finally {
            if (cancellation != null) cancellation.close();
            if (process != null && ExWork.cancelled()) process.destroy();
        }
    }

    /**
     * Запускает что-то долгое и отдаёт сам процесс — игру нельзя ждать, её надо показывать.
     */
    static Process launch(Context context, String[] command, Map<String, String> extra)
            throws Exception {
        return launch(context, command, extra, true);
    }

    static Process launch(Context context, String[] command, Map<String, String> extra,
            boolean mergeErrors) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(argv(context, false, command));
        builder.redirectErrorStream(mergeErrors);
        environment(context, builder.environment());

        if (extra != null) {
            builder.environment().putAll(extra);
        }

        return builder.start();
    }

    /**
     * Пишет сценарий внутрь системы и выполняет его.
     *
     * Сценарий вместо длинной строки команд — не украшательство: половина шагов сборки
     * состоит из циклов и проверок, а собирать их из аргументов означает ошибаться на
     * кавычках там, где ошибку видно только через час.
     */
    static String script(Context context, String name, String body, Progress progress) {
        File file = new File(rootfs(context), "root/" + name);
        write(file, body);

        return run(context, new String[] { "/bin/bash", "/root/" + name }, false, progress);
    }

    private static File tmp(Context context) {
        File tmp = new File(home(context), "tmp");

        if (!tmp.exists()) {
            tmp.mkdirs();
        }

        return tmp;
    }

    static void write(File file, String text) {
        try {
            File parent = file.getParentFile();

            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(text.getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
            // Вызывающая сторона увидит отсутствие результата — паниковать здесь незачем.
        }
    }

    /** Куда писать ход сборки — чтобы человек видел процесс, а не пустой экран. */
    interface Progress {
        void say(String line);
    }

    /**
     * Ответ компьютеру о состоянии системы.
     *
     * Внутренняя папка приложения кабелю не видна, а `run-as` на подписанной сборке
     * отказывает — поэтому телефон сам кладёт ответ в свою внешнюю папку: одна строка,
     * которую видно обычным `adb shell cat`.
     */
    static void state(Context context, String text) {
        try {
            File file = new File(context.getExternalFilesDir(null), "ex.state");

            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write((text + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
            // Без ответа компьютер просто спросит ещё раз.
        }
    }

    /**
     * Что за файл нам дали — по первым байтам, а не по расширению.
     */
    static String kindOf(File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] head = new byte[20];

            if (in.read(head) < 20) {
                return "короткий файл";
            }

            if (head[0] == 'M' && head[1] == 'Z') {
                return "windows";
            }

            if ((head[0] & 0xFF) == 0x7F && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') {
                int machine = (head[18] & 0xFF) | ((head[19] & 0xFF) << 8);

                if (machine == 0x3E) {
                    return "elf-x86_64";
                }

                if (machine == 0xB7) {
                    return "elf-arm64";
                }

                return "elf-иной";
            }

            return "неизвестно";
        } catch (Exception broken) {
            return "нечитаемо";
        }
    }

    /**
     * Журнал во внешней папке приложения.
     *
     * Нужен ровно затем, чтобы «не работает» перестало быть непроверяемым: файл читается
     * с компьютера обычным adb, и видно, на каком шаге всё встало.
     */
    static void log(Context context, String line) {
        try {
            File file = new File(context.getExternalFilesDir(null), "ex.log");

            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((line + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
            // Журнал — удобство, а не условие работы.
        }
    }

    /**
     * Доказательство, а не отчёт: система обязана показать, что телефон нативно ARM, а
     * через неё исполняет настоящий машинный код x86_64, держит слой Windows и видит
     * видеокарту.
     */
    static void proveLogged(Context context) {
        log(context, "=== проверка eX " + new java.util.Date() + " ===");

        String arm = run(context, new String[] { "/bin/uname", "-m" }, false, null);
        log(context, "телефон нативно: " + clean(arm));

        String box = run(context, new String[] { "/usr/local/bin/box64", "--version" },
                false, null);
        log(context, "переводчик кода: " + clean(box));

        int stage = stage(context);

        if (stage >= STAGE_WINDOWS) {
            String wine = run(context, new String[] {
                    ExNative.ready(context) ? "/usr/bin/wine" : "/opt/wine/bin/wine",
                    "--version" }, false, null);
            log(context, "слой Windows: " + clean(wine));
        } else {
            log(context, "слой Windows: ещё не собран (шаг " + stage + " из " + STAGE_DXVK + ")");
        }

        if (stage >= STAGE_GPU) {
            log(context, "видеокарта: " + gpuLine(context));
            log(context, "vulkaninfo: " + clean(ExBuild.gpuProof(context)));
        }

        log(context, "=== конец проверки ===");
    }

    static String clean(String value) {
        return value == null ? "нет ответа" : value.trim().replace('\n', ' ');
    }

    static String describe(Context context) {
        String runtime = ExNative.ready(context) ? "ARM64 Wine · FEX" : "Box64 · Wine wow64";
        return "eX · свой экран · Ubuntu 24.04 arm64 · " + runtime + " · "
                + (gpuReady(context) ? "Turnip" : "программная графика") + " · DXVK · "
                + Build.SUPPORTED_ABIS[0];
    }

    private Ex() {
    }
}
