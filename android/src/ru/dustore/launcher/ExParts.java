package ru.dustore.launcher;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Части, из которых телефон собирает eX, и откуда он их берёт.
 *
 * Правило одно: компьютер для сборки не нужен. Каждая часть качается самим телефоном из
 * того места, где её выкладывают авторы, с докачкой после обрыва — на телефоне обрывы
 * обычное дело, и начинать 96 мегабайт заново из-за лифта было бы издевательством.
 *
 * Компьютер по кабелю может докинуть те же файлы быстрее: он кладёт их во внешнюю папку
 * приложения, и здесь они берутся оттуда прежде, чем идти в сеть. Это ускоритель, а не
 * условие.
 *
 * Зеркало: одна строка адреса в настройках. Если авторские хранилища недоступны, части
 * ищутся там под теми же именами — так студия может выложить их у себя, не пересобирая
 * приложение.
 */
final class ExParts {

    /** Одна часть: как называется у нас, откуда берётся, сколько весит, как объяснить. */
    static final class Part {

        final String name;
        final String url;
        final String what;
        final long size;

        /** Прежнее имя файла во внешней папке — так его клал компьютер до переименования. */
        final String legacy;

        Part(String name, String url, String what, long size, String legacy) {
            this.name = name;
            this.url = url;
            this.what = what;
            this.size = size;
            this.legacy = legacy;
        }
    }

    /** Основа: Ubuntu 24.04 для arm64, ровно та сборка, что проверена на телефоне. */
    static final Part BASE = new Part("base.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
                    + "ubuntu-base-24.04.4-base-arm64.tar.gz",
            "основу системы (30 МБ)", 29870567L, "rootfs.tar.gz");

    /** Слой Windows: Wine с новым режимом wow64 — 32-битные игры идут без второго транслятора. */
    static final Part WINE = new Part("wine.tar.xz",
            "https://github.com/Kron4ek/Wine-Builds/releases/download/11.16/"
                    + "wine-11.16-amd64-wow64.tar.xz",
            "слой Windows (96 МБ)", 101013508L, "wine.tar.xz");

    /** Открытый драйвер видеокарты Turnip для Adreno, собранный под контейнеры на Android. */
    static final Part MESA = new Part("mesa.tar.gz",
            "https://github.com/lfdevs/mesa-for-android-container/releases/download/"
                    + "mesa-26.3.0-devel-20260824/"
                    + "mesa-for-android-container_26.3.0-devel-20260824_ubuntu_noble_arm64.tar.gz",
            "драйвер видеокарты (11 МБ)", 11617615L, null);

    /** DXVK: DirectX 8–11 поверх Vulkan. */
    static final Part DXVK = new Part("dxvk.tar.gz",
            "https://github.com/doitsujin/dxvk/releases/download/v3.1/dxvk-3.1.tar.gz",
            "перевод DirectX (17 МБ)", 18056721L, null);

    static final Part WAYLAND = new Part("wayland.deb",
            "https://old-releases.ubuntu.com/ubuntu/pool/main/w/wayland/"
                    + "libwayland-client0_1.23.0-1_arm64.deb",
            "библиотеку экрана (27 КБ)", 26672L, null);

    /** ARM64 Wine runs Wine services natively and translates only Windows game code. */
    static final Part HANGOVER = new Part("hangover-11.16-arm64.tar",
            "https://github.com/AndreRH/hangover/releases/download/hangover-11.16/"
                    + "hangover_11.16_ubuntu2404_noble_arm64.tar",
            "экономный слой Windows (307 МБ)", 307138560L,
            "hangover_11.16_ubuntu2404_noble_arm64.tar");

    static Part[] all() {
        return new Part[] { BASE, MESA, WAYLAND, HANGOVER };
    }

    /** Части лежат во внутренней памяти: наружу их не видно, и никто их не подменит. */
    static File dir(Context context) {
        File dir = new File(Ex.home(context), "parts");

        if (!dir.exists()) {
            dir.mkdirs();
        }

        return dir;
    }

    static File file(Context context, Part part) {
        return new File(dir(context), part.name);
    }

    /** Принимаем только полный файл с закреплённой контрольной суммой. */
    static boolean have(Context context, Part part) {
        File file = file(context, part);
        return valid(file, part);
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> verified = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean valid(File file, Part part) {
        if (!file.isFile() || file.length() != part.size) return false;
        String key = file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
        if (Boolean.TRUE.equals(verified.get(key))) return true;
        try (InputStream input = new FileInputStream(file)) {
            java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[65536]; int n;
            while ((n = input.read(block)) != -1) { ExWork.check(); hash.update(block, 0, n); }
            StringBuilder hex = new StringBuilder();
            for (byte b : hash.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (!hex.toString().equals(expectedHash(part.name))) return false;
            verified.put(key, true);
            return true;
        } catch (Exception error) { return false; }
    }

    private static String expectedHash(String name) {
        switch (name) {
            case "base.tar.gz": return "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2";
            case "wine.tar.xz": return "63d5e45a4843930ca7512cc1c0bd34e6d4093dc392d96a1752545dea289feaf4";
            case "mesa.tar.gz": return "06c31d6dd9a404d5f650cfe506521c3c64dc2022a82f12545d0acbcb4544e137";
            case "dxvk.tar.gz": return "30f9cc326874be344285582275446968cfa4c069db31ce56df312d6644179154";
            case "wayland.deb": return "2b284a2ae7895433f5c0efb0e13e3654894700937313e9d14325cc6c041433fd";
            case "hangover-11.16-arm64.tar": return "94611dad2b23978d35825a018c81b3947caaec174aa04c052849d37bc6a39e72";
            default: return "";
        }
    }

    /** Куда кладёт часть компьютер по кабелю. */
    static File cableFile(Context context, Part part) {
        File outside = context.getExternalFilesDir(null);

        if (outside == null) {
            return new File("/nonexistent");
        }

        File fresh = new File(new File(outside, "parts"), part.name);

        if (fresh.exists()) {
            return fresh;
        }

        return part.legacy == null ? fresh : new File(outside, part.legacy);
    }

    /** Адрес зеркала из настроек, или пустая строка. */
    static String mirror(Context context) {
        String value = context.getSharedPreferences("ex", Context.MODE_PRIVATE)
                .getString("mirror", "");

        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    static void setMirror(Context context, String value) {
        context.getSharedPreferences("ex", Context.MODE_PRIVATE)
                .edit().putString("mirror", value == null ? "" : value.trim()).apply();
    }

    /** Все ли части на месте. */
    static boolean haveAll(Context context) {
        for (Part part : all()) {
            if (!have(context, part)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Добывает часть: сначала с кабеля, потом из сети. Возвращает null при успехе.
     *
     * Докачка по Range: обрыв на восьмидесятом мегабайте продолжается с восьмидесятого,
     * а не с нуля. Хранилища GitHub и Ubuntu Range понимают; кто не понимает — отдаёт
     * файл целиком, и мы честно начинаем сначала.
     */
    static String fetch(Context context, Part part, Ex.Progress say) {
        ExWork.check();
        if (have(context, part)) {
            return null;
        }

        File target = file(context, part);
        File cable = cableFile(context, part);

        if (valid(cable, part)) {
            say(say, "Берём " + part.what + " с кабеля…");

            if (copy(cable, target)) {
                return null;
            }
        }

        File piece = new File(target.getPath() + ".part");
        String mirror = mirror(context);
        String[] urls = mirror.isEmpty()
                ? new String[] { part.url }
                : new String[] { mirror + "/" + part.name, part.url };

        String last = "нет сети";

        for (String url : urls) {
            for (int attempt = 0; attempt < 4; attempt++) {
                String problem = download(url, piece, part, say);

                if (problem == null) {
                    if (target.exists()) {
                        target.delete();
                    }

                    if (piece.renameTo(target)) {
                        return null;
                    }

                    return "Скачали " + part.what + ", но не смогли положить в папку системы.";
                }

                last = problem;

                if (problem.startsWith("нет такого файла")) {
                    // Зеркало без этой части — идём к авторам, не тратя попытки.
                    break;
                }

                say(say, "Обрыв: " + problem + ". Пробуем ещё…");

                try {
                    Thread.sleep(2000L * (attempt + 1));
                } catch (InterruptedException stop) {
                    return "Загрузка прервана.";
                }
            }
        }

        return "Не удалось скачать " + part.what + ": " + last;
    }

    /** Одна попытка докачать файл по адресу. Возвращает null, когда файл целый. */
    private static String download(String url, File piece, Part part, Ex.Progress say) {
        ExWork.check();
        long have = piece.exists() ? piece.length() : 0;

        if (valid(piece, part)) return null;
        if (have >= part.size) { piece.delete(); have = 0; }

        HttpURLConnection http = null;
        ExWork.Hook cancellation = null;

        try {
            http = (HttpURLConnection) new URL(url).openConnection();
            HttpURLConnection owned = http;
            cancellation = ExWork.hook(owned::disconnect);
            http.setInstanceFollowRedirects(true);
            http.setConnectTimeout(20000);
            http.setReadTimeout(60000);
            http.setRequestProperty("User-Agent", "DustoreX/5.0 eX");

            if (have > 0) {
                http.setRequestProperty("Range", "bytes=" + have + "-");
            }

            int code = http.getResponseCode();

            if (code == 404) {
                return "нет такого файла: " + url;
            }

            if (code == 416) {
                // Просили дальше конца — то, что лежит, либо целое, либо чужое.
                piece.delete();
                return "хранилище не приняло докачку";
            }

            if (code != 200 && code != 206) {
                return "ответ " + code;
            }

            boolean append = code == 206;
            if (append) {
                String range = http.getHeaderField("Content-Range");
                if (range == null || !range.startsWith("bytes " + have + "-") || !range.endsWith("/" + part.size)) {
                    piece.delete();
                    return "хранилище прислало неверный диапазон докачки";
                }
            }

            if (!append) {
                have = 0;
            }

            long total = Math.max(part.size, have + Math.max(0, http.getContentLengthLong()));
            ExWork.transfer(part.what, have, total);

            try (InputStream in = http.getInputStream();
                 FileOutputStream out = new FileOutputStream(piece, append)) {
                byte[] buffer = new byte[1 << 16];
                long done = have;
                long said = have;
                int read;

                while ((read = in.read(buffer)) > 0) {
                    ExWork.check();
                    out.write(buffer, 0, read);
                    done += read;
                    ExWork.transfer(part.what, done, total);

                    if (done - said >= 2L << 20) {
                        said = done;
                        say(say, "Качаем " + part.what + ": " + done / 1048576 + " из "
                                + total / 1048576 + " МБ");
                    }
                }
            }

            if (piece.length() != part.size) {
                if (piece.length() > part.size) piece.delete();
                return "файл получен не полностью: ожидается " + part.size + " байт";
            }
            if (!valid(piece, part)) {
                piece.delete();
                return "контрольная сумма SHA-256 не совпала — файл будет скачан заново";
            }
            return null;
        } catch (IOException broken) {
            ExWork.check();
            return broken.getMessage() == null ? broken.toString() : broken.getMessage();
        } finally {
            ExWork.transfer("", -1, -1);
            if (cancellation != null) cancellation.close();
            if (http != null) {
                http.disconnect();
            }
        }
    }

    private static boolean copy(File from, File to) {
        File tmp = new File(to.getPath() + ".part");

        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[1 << 16];
            int read;

            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (Exception broken) {
            tmp.delete();
            return false;
        }

        if (to.exists()) {
            to.delete();
        }

        return tmp.renameTo(to);
    }

    private static void say(Ex.Progress say, String text) {
        if (say != null) {
            say.say(text);
        }
    }

    private ExParts() {
    }
}
