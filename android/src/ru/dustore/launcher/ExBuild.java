package ru.dustore.launcher;

import android.content.Context;

import java.io.File;

/**
 * Сборка системы eX на самом телефоне.
 *
 * Пять ступеней, каждая проверяется по файлам, а не по записи «мы это делали»: основа
 * Linux и переводчик кода → пакеты (экран, шрифты, части x86_64, Vulkan) → слой Windows →
 * драйвер видеокарты → перевод DirectX. Обрыв на любой ступени не рушит сделанное: сборка
 * продолжается с той ступени, до которой дошла.
 *
 * Части телефон качает сам ({@link ExParts}); компьютер по кабелю может их только
 * докинуть быстрее. Всё, что можно докачать пакетами, докачивается из хранилища Ubuntu
 * прямо изнутри системы.
 */
final class ExBuild {

    /**
     * Части, которые нужны экрану, шрифтам, слою Windows и видеокарте.
     *
     * Список короткий нарочно: чужого X-сервера здесь нет — экран пишем мы сами. Vulkan-
     * загрузчик и vulkaninfo нужны для видеокарты, mesa-vulkan-drivers даёт программный
     * запасной путь на телефонах без Adreno, libdrm2 — зависимость драйвера.
     */
    private static final String ARM_PARTS =
            "libwayland-client0 libwayland-egl1 libxkbcommon0 libxkbregistry0 "
                    + "xkb-data libffi8 "
                    + "libfreetype6 libfontconfig1 fonts-dejavu-core "
                    + "libxml2 libasound2t64 xz-utils pulseaudio pulseaudio-utils "
                    + "libpulse0 libdbus-1-3 libsdl2-2.0-0 libusb-1.0-0 libxext6 "
                    + "libegl1 libegl-mesa0 libgbm1 mesa-libgallium libglvnd0 "
                    + "libgl1-mesa-dri libglapi-mesa "
                    + "libvulkan1 vulkan-tools mesa-vulkan-drivers libdrm2";

    /**
     * Части x86_64, которые переводчик кода не подменяет своими.
     *
     * Их немного и они маленькие: почти всё, что просит программа x86_64, переводчик
     * берёт у настоящей системы телефона. Но libgcc он не подменяет — и без неё Wine не
     * доходит даже до первой строчки.
     */
    private static final String X64_PARTS =
            "libgcc-s1:amd64 libstdc++6:amd64 libwayland-client0:amd64 "
                    + "libxkbcommon0:amd64 libffi8:amd64";

    /**
     * Собирает систему целиком. Долгая работа — звать только из своего потока.
     * Возвращает null при успехе или строку с причиной.
     */
    static String installLogged(Context context, Ex.Progress progress) {
        Ex.log(context, "=== сборка eX " + new java.util.Date() + " ===");
        Ex.state(context, "installing");

        Ex.Progress both = line -> {
            Ex.log(context, line);

            if (progress != null) {
                progress.say(line);
            }
        };

        String problem = build(context, both);

        Ex.log(context, problem == null ? "ГОТОВО: система собрана" : "ОШИБКА: " + problem);
        Ex.state(context, problem == null ? "ready " + Ex.describe(context) : "broken " + problem);

        return problem;
    }

    /** Prepare the ARM64 runtime without downloading or booting the legacy x86-64 Wine. */
    static String nativeFoundation(Context context, Ex.Progress say) {
        ExWork.stage(1, 6, "Основа Linux и переводчик кода");
        if (Ex.stage(context) < Ex.STAGE_BASE) {
            String problem = buildBase(context, say);
            if (problem != null) return problem;
        }
        ExWork.stage(2, 6, "Библиотеки и шрифты");
        if (Ex.stage(context) < Ex.STAGE_PARTS) {
            String problem = buildParts(context, say);
            if (problem != null) return problem;
        }
        ExWork.stage(3, 6, "Графический слой");
        if (!new File(Ex.rootfs(context), ".ex-gpu").isFile()) {
            String problem = buildGpu(context, say);
            if (problem != null) return problem;
        }
        ExWork.stage(4, 6, "Экран, ввод и звук");
        return ensureDisplayParts(context, say);
    }

    /** Prepare Hangover with a temporary Wayland socket for its first Wine startup. */
    static String installNativeLogged(Context context, Ex.Progress progress) {
        Ex.log(context, "=== сборка eX ARM64 " + new java.util.Date() + " ===");
        Ex.state(context, "installing");
        Ex.Progress both = line -> {
            Ex.log(context, line);
            if (progress != null) progress.say(line);
        };
        String problem = nativeFoundation(context, both);
        if (problem == null) ExWork.stage(5, 6, "Windows ARM64 и DirectX");
        if (problem == null && !ExNative.ready(context)) {
            ExDisplay display = new ExDisplay(Ex.runDir(context),
                    ExActivity.GAME_WIDTH, ExActivity.GAME_HEIGHT,
                    new ExDisplay.Watcher() {
                        @Override public void say(String line) { both.say(line); }
                        @Override public void changed() { }
                    });
            problem = display.start();
            if (problem == null) {
                try { problem = ExNative.prepare(context, both); }
                finally { display.stop(); }
            }
        }
        if (problem == null) ExWork.stage(6, 6, "Проверка готовности eX");
        Ex.log(context, problem == null ? "ГОТОВО: ARM64 Wine установлен"
                : "ОШИБКА ARM64 Wine: " + problem);
        Ex.state(context, problem == null ? "ready " + Ex.describe(context)
                : "broken " + problem);
        return problem;
    }

    /** Только докачать части, ничего не разворачивая. Возвращает null при успехе. */
    static String fetchAll(Context context, Ex.Progress say) {
        for (ExParts.Part part : ExParts.all()) {
            String problem = ExParts.fetch(context, part, say);

            if (problem != null) {
                return problem;
            }

            say.say("Есть: " + part.what);
        }

        return null;
    }

    private static String build(Context context, Ex.Progress say) {
        if (Ex.stage(context) < Ex.STAGE_BASE) {
            String problem = buildBase(context, say);

            if (problem != null) {
                return problem;
            }
        } else {
            say.say("Основа уже стоит — пропускаем.");
        }

        if (Ex.stage(context) < Ex.STAGE_PARTS) {
            String problem = buildParts(context, say);

            if (problem != null) {
                return problem;
            }
        } else {
            say.say("Детали уже на месте — пропускаем.");
        }

        if (Ex.stage(context) < Ex.STAGE_WINDOWS) {
            String problem = buildWindows(context, say);

            if (problem != null) {
                return problem;
            }
        } else {
            say.say("Слой Windows уже стоит — пропускаем.");
        }

        if (Ex.stage(context) < Ex.STAGE_GPU) {
            String problem = buildGpu(context, say);

            if (problem != null) {
                return problem;
            }
        } else {
            say.say("Видеокарта уже настроена: " + Ex.gpuName(context));
        }

        if (Ex.stage(context) < Ex.STAGE_DXVK) {
            String problem = buildDxvk(context, say);

            if (problem != null) {
                return problem;
            }
        }

        String runtimeFailure = ensureDisplayParts(context, say);
        if (runtimeFailure != null) return runtimeFailure;
        say.say("Проверяем систему…");

        String wine = Ex.run(context, new String[] {
                ExNative.ready(context) ? "/usr/bin/wine" : "/opt/wine/bin/wine",
                "--version" }, false, null);

        if (wine == null || !wine.toLowerCase(java.util.Locale.ROOT).contains("wine-")) {
            return "слой Windows не отвечает: " + Ex.clean(wine);
        }

        say.say("Готово: " + wine.trim() + " · " + Ex.gpuLine(context));
        return null;
    }

    /** Шаг 1: Linux внутри приложения и переводчик кода. */
    private static String buildBase(Context context, Ex.Progress say) {
        String fetched = ExParts.fetch(context, ExParts.BASE, say);

        if (fetched != null) {
            return fetched;
        }

        File image = ExParts.file(context, ExParts.BASE);
        File rootfs = Ex.rootfs(context);
        Unpack.deleteAll(rootfs);
        rootfs.mkdirs();

        // Проверяем существование, а не ответ mkdirs: после пересборки часть старых
        // файлов может не удалиться (ссылки на чужие каталоги), папка остаётся — и
        // mkdirs честно отвечает false, хотя всё в порядке.
        if (!rootfs.isDirectory()) {
            return "Не удалось создать папку системы.";
        }

        say.say("Разворачиваем Linux…");

        // Распаковка своим кодом, а не чужим tar: до появления системы внутри контейнера
        // запускать нечего, а внешний tar Android ломает ссылки.
        String unpacked = Tar.extract(image, rootfs, say);

        if (unpacked != null) {
            return unpacked;
        }

        say.say("Ставим переводчик кода…");

        File bin = new File(rootfs, "usr/local/bin");
        bin.mkdirs();

        File link = new File(bin, "box64");
        link.delete();

        try {
            // Ссылка ведёт на /ex-lib — так этот путь выглядит ВНУТРИ системы.
            android.system.Os.symlink("/ex-lib/libbox64.so", link.getAbsolutePath());
        } catch (Exception ex) {
            return "Не удалось поставить переводчик кода: " + ex.getMessage();
        }

        Ex.write(new File(rootfs, "etc/resolv.conf"), "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
        Ex.write(new File(rootfs, "etc/hostname"), "ex\n");

        // Хранилище пакетов, из которого телефон соберёт остальное сам. Первая строка
        // важнее, чем кажется: без неё установщик пытается качать всё сразу для двух
        // разных процессоров и падает на первом же несуществующем файле.
        Ex.write(new File(rootfs, "etc/apt/apt.conf.d/99ex"),
                "APT::Sandbox::User \"root\";\n"
                        + "Acquire::Retries \"3\";\n"
                        + "APT::Install-Recommends \"false\";\n");

        Ex.write(new File(rootfs, "etc/apt/sources.list.d/amd64.sources"),
                "Types: deb\n"
                        + "URIs: http://archive.ubuntu.com/ubuntu\n"
                        + "Suites: noble noble-updates noble-security\n"
                        + "Components: main universe\n"
                        + "Architectures: amd64\n"
                        + "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n");

        String fix = Ex.script(context, "ex-arch.sh",
                "set -e\n"
                        + "sed -i 's|^Components: |Architectures: arm64\\nComponents: |' "
                        + "/etc/apt/sources.list.d/ubuntu.sources\n"
                        + "dpkg --add-architecture amd64\n"
                        + "echo ARCH_OK\n", null);

        if (fix == null || !fix.contains("ARCH_OK")) {
            return "Не удалось настроить хранилище частей: " + Ex.clean(fix);
        }

        say.say("Основа готова.");
        return null;
    }

    /** Шаг 2: пакеты экрана, шрифтов, Vulkan и части x86_64. */
    private static String buildParts(Context context, Ex.Progress say) {
        say.say("Спрашиваем хранилище частей…");

        String update = Ex.script(context, "ex-update.sh",
                "apt-get update 2>&1 | tail -3\n", say);

        if (update != null && update.contains("EX_EXEC_FAILED")) {
            return "Система не отвечает: " + Ex.clean(update);
        }

        say.say("Ставим части экрана, шрифты и Vulkan…");

        // Ставим списком, а при отказе — поштучно: одно отсутствующее имя не должно
        // валить всю сборку. Набор корневых сертификатов сносим: его послеустановочный
        // сценарий спотыкается о подменённый корень и останавливает всю очередь.
        String parts = Ex.script(context, "ex-parts.sh",
                "export DEBIAN_FRONTEND=noninteractive\n"
                        + "opts=\"-y --no-install-recommends "
                        + "-o Dpkg::Options::=--force-confold\"\n"
                        + "apt-get $opts install " + ARM_PARTS + " 2>&1 | tail -4\n"
                        + "dpkg --remove --force-all ca-certificates >/dev/null 2>&1 || true\n"
                        + "dpkg --configure -a >/dev/null 2>&1 || true\n"
                        + "apt-get -f install -y >/dev/null 2>&1 || true\n"
                        + "missing=\"\"\n"
                        + "for p in " + ARM_PARTS + "; do\n"
                        + "  if ! dpkg -s \"$p\" >/dev/null 2>&1; then\n"
                        + "    apt-get $opts install \"$p\" >/dev/null 2>&1 || true\n"
                        + "    dpkg -s \"$p\" >/dev/null 2>&1 || missing=\"$missing $p\"\n"
                        + "  fi\n"
                        + "done\n"
                        + "[ -n \"$missing\" ] && echo \"не встало:$missing\"\n"
                        + "ls /usr/lib/aarch64-linux-gnu/libwayland-client.so.0 "
                        + ">/dev/null 2>&1 && ls /usr/bin/xz >/dev/null 2>&1 "
                        + "&& ls /usr/bin/vulkaninfo >/dev/null 2>&1 "
                        + "&& echo PARTS_OK\n", say);

        if (parts == null || !parts.contains("PARTS_OK")) {
            return "Не удалось довезти части экрана. Нужен интернет на телефоне.\n"
                    + Ex.clean(parts);
        }

        String newer = freshWayland(context, say);

        if (newer != null) {
            return newer;
        }

        say.say("Забираем части x86_64…");

        String x64 = Ex.script(context, "ex-x64.sh",
                "rm -rf /root/x64stage\n"
                        + "mkdir -p /opt/x86_64 /root/debs /root/x64stage\n"
                        + "cd /root/debs && rm -f *.deb\n"
                        + "apt-get download " + X64_PARTS + " 2>&1 | tail -2\n"
                        + "for d in *.deb; do\n"
                        + "  [ -f \"$d\" ] || continue\n"
                        // dpkg-deb в конце правит права на каталоги, а в контейнере это
                        // отказывает — поэтому распаковываем его же потоком через tar.
                        + "  dpkg-deb --fsys-tarfile \"$d\" | tar -x "
                        + "--no-same-permissions --no-same-owner "
                        + "--no-delay-directory-restore -C /root/x64stage 2>/dev/null\n"
                        + "done\n"
                        + "cp -a /root/x64stage/usr/lib/x86_64-linux-gnu/. /opt/x86_64/ "
                        + "2>/dev/null\n"
                        + "rm -rf /root/x64stage /root/debs\n"
                        + "ls /opt/x86_64/libgcc_s.so.1 >/dev/null 2>&1 && echo X64_OK\n",
                say);

        if (x64 == null || !x64.contains("X64_OK")) {
            return "Не удалось забрать части x86_64.\n" + Ex.clean(x64);
        }

        say.say("Детали на месте.");
        return null;
    }

    /**
     * Свежая библиотека разговора с экраном — без неё слой Windows не грузит драйвер.
     *
     * Ubuntu 24.04 везёт версию 1.22, а слой Windows 11-й версии просит у неё то, чего в
     * 1.22 ещё нет. Берём одну библиотеку из следующего выпуска Ubuntu, и только её.
     */
    private static String freshWayland(Context context, Ex.Progress say) {
        File installed = new File(Ex.rootfs(context), ".ex-wayland-1.23");
        if (installed.exists()) return null;
        String failure = ExParts.fetch(context, ExParts.WAYLAND, say);
        if (failure != null) return failure;
        say.say("Обновляем библиотеку экрана…");
        String result = Ex.script(context, "ex-wayland.sh",
                "set -e\nmkdir -p /root/ex-wayland-update\n"
                + "dpkg-deb --fsys-tarfile /parts/wayland.deb | tar -x -C /root/ex-wayland-update "
                + "--no-same-owner --no-same-permissions ./usr/lib/aarch64-linux-gnu\n"
                + "cp -a /root/ex-wayland-update/usr/lib/aarch64-linux-gnu/libwayland-client.so* "
                + "/usr/lib/aarch64-linux-gnu/\n"
                + "ldconfig\ntouch /.ex-wayland-1.23\necho WL_OK\n", say);
        return installed.exists() && result.contains("WL_OK") ? null : "Не обновилась библиотека экрана: " + result;
    }

    /** Шаг 3: слой Windows и его первый запуск. */
    private static String buildWindows(Context context, Ex.Progress say) {
        String fetched = ExParts.fetch(context, ExParts.WINE, say);

        if (fetched != null) {
            return fetched;
        }

        say.say("Разворачиваем слой Windows (400 МБ, минуты три)…");

        // Файл распаковывается уже внутри системы: xz приехал на прошлом шаге.
        //
        // Переходники — то, без чего слой Windows не заводится вовсе: свою службу он
        // запускает обычным exec, а телефон умеет исполнять только ARM. Каждый файл с
        // кодом Intel переезжает под имя .real, а на его место встаёт короткий сценарий,
        // зовущий переводчик кода.
        String unpack = Ex.script(context, "ex-wine.sh",
                "set -e\n"
                        + "rm -rf /opt/wine /opt/wine-tmp\n"
                        + "mkdir -p /opt/wine-tmp\n"
                        + "tar -xJf /parts/" + ExParts.WINE.name + " -C /opt/wine-tmp "
                        + "--no-same-permissions --no-same-owner "
                        + "--no-delay-directory-restore 2>/dev/null || true\n"
                        + "first=$(ls /opt/wine-tmp | head -1)\n"
                        + "mv \"/opt/wine-tmp/$first\" /opt/wine\n"
                        + "rmdir /opt/wine-tmp 2>/dev/null || true\n"
                        + "chmod -R u+rwX,go+rX /opt/wine\n"
                        + "cd /opt/wine/bin\n"
                        + "for f in *; do\n"
                        + "  [ -f \"$f\" ] || continue\n"
                        + "  head -c 4 \"$f\" | grep -q ELF || continue\n"
                        + "  mv \"$f\" \"$f.real\"\n"
                        + "  printf '#!/bin/bash\\nexec /usr/local/bin/box64 "
                        + "/opt/wine/bin/%s.real \"$@\"\\n' \"$f\" > \"$f\"\n"
                        + "  chmod 755 \"$f\"\n"
                        + "done\n"
                        + "ls /opt/wine/bin/wine.real >/dev/null && echo WINE_OK\n", say);

        if (unpack == null || !unpack.contains("WINE_OK")) {
            return "Не удалось развернуть слой Windows.\n" + Ex.clean(unpack);
        }

        say.say("Перекладываем программы Windows под правила Android…");

        ExPe.prepare(new File(Ex.rootfs(context), "opt/wine/lib/wine"), say);

        say.say("Первый запуск слоя Windows — он создаёт себе диск C:…");

        // Без экрана: на этом шаге показывать ещё нечего, а Wine, не найдя экрана,
        // спокойно готовит диск C: в текстовом режиме.
        String boot = Ex.script(context, "ex-boot.sh",
                "export WINEDLLOVERRIDES=\"mscoree,mshtml,winemenubuilder.exe=d\"\n"
                        + "unset WAYLAND_DISPLAY\n"
                        + "unset DISPLAY\n"
                        + "/opt/wine/bin/wineboot -u 2>&1 | tail -6\n"
                        + "/opt/wine/bin/wineserver -k 2>/dev/null || true\n"
                        + "ls /root/.wine/system.reg >/dev/null 2>&1 && echo BOOT_OK\n", say);

        if (boot == null || !boot.contains("BOOT_OK")) {
            return "Слой Windows не смог создать себе диск C:.\n" + Ex.clean(boot);
        }

        ExPe.prepare(new File(Ex.rootfs(context), "root/.wine/drive_c/windows"), say);

        say.say("Слой Windows готов.");
        return null;
    }

    /**
     * Шаг 4: драйвер видеокарты.
     *
     * Turnip кладётся поверх дистрибутивной Mesa, затем спрашиваем vulkaninfo, видит ли
     * он устройство. Не видит — не беда для сборки: записываем «программно» и идём
     * дальше, а на экране eX об этом сказано прямо.
     */
    private static String buildGpu(Context context, Ex.Progress say) {
        String fetched = ExParts.fetch(context, ExParts.MESA, say);

        if (fetched != null) {
            return fetched;
        }

        say.say("Ставим драйвер видеокарты…");

        String out = Ex.script(context, "ex-gpu.sh",
                "tar -zxf /parts/" + ExParts.MESA.name + " -C / "
                        + "--no-same-permissions --no-same-owner "
                        + "--no-delay-directory-restore 2>/dev/null || true\n"
                        + "ldconfig 2>/dev/null || true\n"
                        + "ls /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so "
                        + ">/dev/null 2>&1 && echo MESA_OK\n"
                        + "unset WAYLAND_DISPLAY DISPLAY\n"
                        + "export MESA_LOADER_DRIVER_OVERRIDE=kgsl\n"
                        + "export VK_DRIVER_FILES=" + Ex.ICD + " VK_ICD_FILENAMES=" + Ex.ICD + "\n"
                        + "timeout 90 vulkaninfo --summary 2>&1 | grep -i -m1 'deviceName' "
                        + "|| echo NO_DEVICE\n", say);

        if (out == null || !out.contains("MESA_OK")) {
            return "Драйвер видеокарты не распаковался.\n" + Ex.clean(out);
        }

        String name = null;

        for (String line : out.split("\n")) {
            if (line.contains("deviceName")) {
                int eq = line.indexOf('=');
                name = (eq < 0 ? line : line.substring(eq + 1)).trim();
                break;
            }
        }

        File mark = new File(Ex.rootfs(context), ".ex-gpu");

        if (name != null && !name.isEmpty()) {
            Ex.write(mark, "turnip " + name + "\n");
            say.say("Видеокарта: " + name);
        } else {
            Ex.write(mark, "soft драйвер не увидел видеокарту\n");
            say.say("Видеокарта не ответила — рисовать будем программно (медленно, только 2D).");
        }

        return null;
    }

    /** Шаг 5: DXVK — DirectX поверх Vulkan, родными библиотеками внутри диска C:. */
    private static String buildDxvk(Context context, Ex.Progress say) {
        String fetched = ExParts.fetch(context, ExParts.DXVK, say);

        if (fetched != null) {
            return fetched;
        }

        say.say("Ставим перевод DirectX…");

        String out = Ex.script(context, "ex-dxvk.sh",
                "set -e\n"
                        + "rm -rf /opt/dxvk /opt/dxvk-tmp\n"
                        + "mkdir -p /opt/dxvk-tmp\n"
                        + "tar -zxf /parts/" + ExParts.DXVK.name + " -C /opt/dxvk-tmp "
                        + "--no-same-permissions --no-same-owner "
                        + "--no-delay-directory-restore 2>/dev/null || true\n"
                        + "first=$(ls /opt/dxvk-tmp | head -1)\n"
                        + "mv \"/opt/dxvk-tmp/$first\" /opt/dxvk\n"
                        + "rmdir /opt/dxvk-tmp 2>/dev/null || true\n"
                        + "cp -f /opt/dxvk/x64/*.dll /root/.wine/drive_c/windows/system32/\n"
                        + "[ -d /root/.wine/drive_c/windows/syswow64 ] && "
                        + "cp -f /opt/dxvk/x32/*.dll /root/.wine/drive_c/windows/syswow64/ "
                        + "|| true\n"
                        + "mkdir -p /root/.dxvk\n"
                        + "ls /root/.wine/drive_c/windows/system32/d3d11.dll >/dev/null "
                        + "&& echo DXVK_OK\n", say);

        if (out == null || !out.contains("DXVK_OK")) {
            return "Не удалось поставить перевод DirectX.\n" + Ex.clean(out);
        }

        // Библиотеки DXVK выровнены по странице — их тоже надо переложить. Метка папки
        // снимается, иначе переукладчик решит, что работа уже сделана.
        File windows = new File(Ex.rootfs(context), "root/.wine/drive_c/windows");
        new File(windows, ".ex-pe").delete();
        ExPe.prepare(windows, say);

        Ex.write(new File(Ex.rootfs(context), ".ex-dxvk"), "dxvk-3.1\n");
        say.say("Перевод DirectX на месте.");
        return null;
    }

    /**
     * Доводит уже собранную систему до того, что нужно экрану, — один раз.
     *
     * Системы, собранные до этой починки, живут на телефонах и пересобирать их никто не
     * станет. Поэтому недостающее довозим прямо перед первой игрой и отмечаем меткой.
     */
    static String ensureDisplayParts(Context context, Ex.Progress say) {
        File mark = new File(Ex.rootfs(context), ".ex-runtime-v2");
        if (mark.exists()) return null;
        Ex.Progress progress = say == null ? line -> { } : say;
        progress.say("Проверяем библиотеки изображения, ввода и звука…");
        String result = Ex.script(context, "ex-display-parts.sh",
                "set -e\nexport DEBIAN_FRONTEND=noninteractive\n"
                + "missing=''\nfor p in " + ARM_PARTS + "; do\n"
                + "dpkg-query -W -f='${Status}' \"$p\" 2>/dev/null | grep -q 'install ok installed' "
                + "|| missing=\"$missing $p\"\ndone\n"
                + "if [ -n \"$missing\" ]; then\n"
                + "apt-get -o Acquire::Retries=1 -o Acquire::http::Timeout=30 update\n"
                + "apt-get -y --no-install-recommends -o Acquire::Retries=1 "
                + "-o Acquire::http::Timeout=30 install $missing\nfi\necho PARTS_DONE\n", progress);
        if (!result.contains("PARTS_DONE")) return "Не установились библиотеки eX: " + result;
        String failure = freshWayland(context, progress);
        if (failure != null) return failure;
        Ex.write(mark, "2");
        return mark.exists() ? null : "Не удалось сохранить состояние eX";
    }

    /** Строка vulkaninfo об устройстве — доказательство, что видеокарта видна. */
    static String gpuProof(Context context) {
        String out = Ex.script(context, "ex-proof.sh",
                "unset WAYLAND_DISPLAY DISPLAY\n"
                        + "export MESA_LOADER_DRIVER_OVERRIDE=kgsl\n"
                        + (Ex.gpuReady(context)
                                ? "export VK_DRIVER_FILES=" + Ex.ICD + "\n" : "")
                        + "timeout 60 vulkaninfo --summary 2>&1 | grep -i -m3 "
                        + "'deviceName\\|driverName\\|apiVersion'\n", null);

        return out == null ? "нет ответа" : out.trim();
    }

    private ExBuild() {
    }
}
