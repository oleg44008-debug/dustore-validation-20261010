package ru.dustore.launcher;

import android.content.Context;
import android.os.StatFs;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Installs ARM64 Wine beside the original runtime and selects it only after validation. */
final class ExNative {
    private static final String PREFIX = "/root/.wine-hangover";
    private static final String MARKER = ".ex-hangover-11.16";
    private static final long INSTALL_SPACE = 5L * 1024 * 1024 * 1024;

    static boolean ready(Context context) {
        File root = Ex.rootfs(context);
        return new File(root, MARKER).isFile()
                && new File(root, "usr/bin/wine").isFile()
                && new File(root, "root/.wine-hangover/system.reg").isFile()
                && new File(root, "root/.wine-hangover/drive_c/windows/system32/dxgi.dll").isFile()
                && waylandConfigured(root);
    }

    /** Must run after ExDisplay.start(), because native Wine initializes its Wayland driver. */
    static String prepare(Context context, Ex.Progress say) {
        if (ready(context)) return null;
        File root = Ex.rootfs(context);
        StatFs storage = new StatFs(root.getAbsolutePath());
        if (storage.getAvailableBytes() < INSTALL_SPACE) {
            return "Для экономного eX нужно 5 ГБ свободного места на телефоне";
        }

        String fetched = ExParts.fetch(context, ExParts.HANGOVER, say);
        if (fetched != null) return fetched;

        File unpacked = new File(root, ".ex-native-unpacked-11.16");
        if (!unpacked.isFile() || !new File(root, "usr/bin/wine").isFile()) {
            say.say("Устанавливаем ARM64 Wine рядом с прежней средой…");
            String unpack = Ex.script(context, "ex-native-unpack.sh",
                    "set -e\n"
                    + "mkdir -p /root/ex-native-install\n"
                    + "tar -xf /parts/" + ExParts.HANGOVER.name
                    + " -C /root/ex-native-install\n"
                    + "for p in /root/ex-native-install/hangover-wine_*.deb "
                    + "/root/ex-native-install/hangover-libarm64ecfex_*.deb "
                    + "/root/ex-native-install/hangover-libwow64fex_*.deb "
                    + "/root/ex-native-install/hangover-wowbox64_*.deb; do\n"
                    + "  dpkg-deb --fsys-tarfile \"$p\" | "
                    + "tar -x --no-delay-directory-restore -C /\n"
                    + "done\n"
                    + "rm -f /usr/lib/wine/aarch64-windows/.ex-pe "
                    + "/usr/lib/wine/i386-windows/.ex-pe\n"
                    + "test -f /usr/bin/wine && echo NATIVE_UNPACK_OK\n", say);
            if (!unpack.contains("NATIVE_UNPACK_OK")) return "ARM64 Wine не распаковался: " + Ex.clean(unpack);
            Ex.write(unpacked, "hangover-11.16\n");
            if (!unpacked.isFile()) return "ARM64 Wine не сохранил состояние установки";
        } else if (!new File(root, "root/ex-native-install/dxvk-v2.7.1.tar.gz").isFile()) {
            String archive = Ex.script(context, "ex-native-archive.sh",
                    "set -e\nmkdir -p /root/ex-native-install\n"
                    + "tar -xf /parts/" + ExParts.HANGOVER.name
                    + " -C /root/ex-native-install dxvk-v2.7.1.tar.gz\n"
                    + "echo NATIVE_ARCHIVE_OK\n", say);
            if (!archive.contains("NATIVE_ARCHIVE_OK")) return "Не удалось извлечь DXVK для ARM64";
        }

        ExPe.prepare(new File(root, "usr/lib/wine/aarch64-windows"), say);
        ExPe.prepare(new File(root, "usr/lib/wine/i386-windows"), say);

        File systemReg = new File(root, "root/.wine-hangover/system.reg");
        if (!systemReg.isFile()) {
            say.say("Готовим отдельный профиль Windows для ARM64…");
            Ex.script(context, "ex-native-boot.sh",
                    "export WINEPREFIX=" + PREFIX + "\n"
                    + "export WINEESYNC=0\n"
                    + "export WINEDLLOVERRIDES='mscoree,mshtml,winemenubuilder.exe=d'\n"
                    + "unset DISPLAY\n"
                    + "timeout --kill-after=5 120 /usr/bin/wineboot -u || true\n"
                    + "/usr/bin/wineserver -k 2>/dev/null || true\n", say);
            if (!systemReg.isFile()) return "ARM64 Wine не создал профиль Windows";
        }

        ExPe.prepare(new File(root, "root/.wine-hangover/drive_c/windows"), say);
        if (!waylandConfigured(root)) {
            String driver = Ex.script(context, "ex-native-wayland.sh",
                    "set -e\nexport WINEPREFIX=" + PREFIX + "\n"
                    + "export WINEESYNC=0\n"
                    + "export WINEDLLOVERRIDES='mscoree,mshtml,winemenubuilder.exe=d'\n"
                    + "unset DISPLAY\n"
                    + "timeout --kill-after=5 45 /usr/bin/wine reg.exe add "
                    + "'HKCU\\Software\\Wine\\Drivers' /v Graphics /t REG_SZ /d wayland /f\n"
                    + "/usr/bin/wineserver -k 2>/dev/null || true\n"
                    + "echo NATIVE_WAYLAND_OK\n", say);
            if (!driver.contains("NATIVE_WAYLAND_OK") || !waylandConfigured(root)) {
                return "ARM64 Wine не включил экран Wayland: " + Ex.clean(driver);
            }
        }

        File dxvk = new File(root, "opt/hangover-dxvk/dxvk-v2.7.1/arm64ec/d3d11.dll");
        if (!dxvk.isFile()) {
            String unpack = Ex.script(context, "ex-native-dxvk.sh",
                    "set -e\nmkdir -p /opt/hangover-dxvk\n"
                    + "tar -xzf /root/ex-native-install/dxvk-v2.7.1.tar.gz "
                    + "-C /opt/hangover-dxvk\n"
                    + "test -f /opt/hangover-dxvk/dxvk-v2.7.1/arm64ec/d3d11.dll "
                    + "&& echo NATIVE_DXVK_OK\n", say);
            if (!unpack.contains("NATIVE_DXVK_OK")) return "DXVK для ARM64 не распаковался";
        }
        ExPe.prepare(new File(root, "opt/hangover-dxvk/dxvk-v2.7.1/arm64ec"), say);
        ExPe.prepare(new File(root, "opt/hangover-dxvk/dxvk-v2.7.1/x32"), say);
        String copy = Ex.script(context, "ex-native-dxvk-copy.sh",
                "set -e\n"
                + "cp -f /opt/hangover-dxvk/dxvk-v2.7.1/arm64ec/*.dll "
                + "/root/.wine-hangover/drive_c/windows/system32/\n"
                + "cp -f /opt/hangover-dxvk/dxvk-v2.7.1/x32/*.dll "
                + "/root/.wine-hangover/drive_c/windows/syswow64/\n"
                + "echo NATIVE_DXVK_COPY_OK\n", say);
        if (!copy.contains("NATIVE_DXVK_COPY_OK")) return "DXVK для ARM64 не установлен";

        Unpack.deleteAll(new File(root, "root/ex-native-install"));
        Ex.write(new File(root, MARKER), "hangover-11.16\n");
        if (!ready(context)) return "ARM64 Wine установлен не полностью";
        File archive = ExParts.file(context, ExParts.HANGOVER);
        if (archive.isFile()) archive.delete();
        say.say("Экономный ARM64 Wine готов.");
        return null;
    }

    static String gameCommand(String inside, String exe, java.util.List<String> args) {
        return Ex.gameCommand(inside, exe, args).replace("/opt/wine/bin/wine", "/usr/bin/wine");
    }

    static String gameCommand(String inside, String exe, java.util.List<String> args,
                              int width, int height) {
        return Ex.gameCommand(inside, exe, args, width, height)
                .replace("/opt/wine/bin/wine", "/usr/bin/wine");
    }

    static Map<String, String> environment(ExRecipe recipe, Map<String, String> existing) {
        Map<String, String> result = new HashMap<>(existing);
        result.put("WINEPREFIX", PREFIX);
        result.put("WINEESYNC", "0");
        if (recipe != null && "native".equals(recipe.renderer)) {
            result.put("WINEDLLOVERRIDES", "mscoree,mshtml,winemenubuilder.exe=d");
        }
        return result;
    }

    static void stop(Context context) {
        Ex.script(context, "ex-native-stop.sh",
                "export WINEPREFIX=" + PREFIX + "\n"
                + "timeout --kill-after=2 10 /usr/bin/wineserver -k || true\n", null);
    }

    private static boolean waylandConfigured(File root) {
        File registry = new File(root, "root/.wine-hangover/user.reg");
        if (!registry.isFile() || registry.length() > 1024 * 1024) return false;
        try (FileInputStream input = new FileInputStream(registry)) {
            byte[] data = new byte[(int) registry.length()];
            int got = input.read(data);
            String value = new String(data, 0, Math.max(0, got), StandardCharsets.UTF_8);
            return value.contains("\"Graphics\"=\"wayland\"");
        } catch (Exception absent) {
            return false;
        }
    }

    private ExNative() { }
}
