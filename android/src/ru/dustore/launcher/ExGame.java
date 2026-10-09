package ru.dustore.launcher;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Игра, эмулированная через eX: где лежит, как зовётся, чем запускается и что с ней
 * уже сделано.
 *
 * Всё о ней — в `ex.json` рядом с файлами игры. Папка живёт во внутренней памяти
 * приложения: только оттуда программу можно исполнять, и только там её никто не тронет.
 */
final class ExGame {

    /** Только распакована. */
    static final String UNPACKED = "unpacked";

    /** Разобрана и переложена — запускается. */
    static final String PREPARED = "prepared";

    /** Собран APK-стаб: можно ставить как приложение. */
    static final String APK = "apk";

    String id;
    String title;
    File folder;
    ExRecipe recipe;
    String state = UNPACKED;
    String sourceHash;

    /** Имя пакета стаба — по нему видно, установлена ли игра как приложение. */
    String stubPackage;

    /** Собранный APK, если есть. */
    File apk;

    /** Что показала проба запуска: «кадр за 9 с» или «кадра не было». */
    String probe = "";

    /** Иконка игры из .exe, если удалось вытащить. */
    File icon() {
        File png = new File(folder, "icon.png");
        return png.exists() ? png : null;
    }

    static File root(Context context) {
        return Ex.localGames(context);
    }

    static File folderOf(Context context, String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("..")) {
            return null;
        }

        File folder = new File(root(context), id);
        return folder.isDirectory() ? folder : null;
    }

    static ExGame load(Context context, String id) {
        File folder = folderOf(context, id);
        return folder == null ? null : load(folder);
    }

    static ExGame load(File folder) {
        ExGame game = new ExGame();
        game.folder = folder;
        game.id = folder.getName();
        game.title = folder.getName();

        try {
            JSONObject json = new JSONObject(ExFileRead.utf8(new File(folder, "ex.json"),
                    ExFileRead.GAME_METADATA_BYTES));

            game.title = json.optString("title", game.title);
            game.state = json.optString("state", UNPACKED);
            game.sourceHash = json.isNull("sourceHash") ? null : json.optString("sourceHash", null);
            game.stubPackage = json.isNull("stub") ? null : json.optString("stub", null);
            game.probe = json.optString("probe", "");

            String apk = json.isNull("apk") ? null : json.optString("apk", null);
            game.apk = apk == null ? null : new File(apk);

            JSONObject recipe = json.optJSONObject("recipe");
            game.recipe = recipe == null ? null : ExRecipe.fromJson(recipe);
        } catch (Exception missing) {
            // Папка без описания — игра только распакована.
        }

        return game;
    }

    static List<ExGame> all(Context context) {
        List<ExGame> games = new ArrayList<>();
        File[] folders = root(context).listFiles();

        if (folders != null) {
            java.util.Arrays.sort(folders);

            for (File folder : folders) {
                if (!folder.getName().startsWith(".") && folder.isDirectory() && new File(folder, "ex.json").exists()) {
                    games.add(load(folder));
                }
            }
        }

        return games;
    }

    synchronized void save() {
        try {
            JSONObject json = new JSONObject();
            json.put("title", title);
            json.put("exe", recipe == null || recipe.exe == null ? JSONObject.NULL : recipe.exe);
            json.put("state", state);
            json.put("sourceHash", sourceHash == null ? JSONObject.NULL : sourceHash);
            json.put("stub", stubPackage == null ? JSONObject.NULL : stubPackage);
            json.put("apk", apk == null ? JSONObject.NULL : apk.getAbsolutePath());
            json.put("probe", probe == null ? "" : probe);
            json.put("recipe", recipe == null ? JSONObject.NULL : recipe.toJson());

            File temporary = new File(folder, "ex.json.tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!temporary.renameTo(new File(folder, "ex.json"))) temporary.delete();
        } catch (Exception ignored) {
            // Без описания игра выглядит только распакованной — переэмулируется заново.
        }
    }

    /**
     * Имя папки и хвост имени пакета: латиница и цифры из названия плюс шесть знаков
     * хеша, чтобы две игры с одинаковым названием не легли в одну папку.
     */
    static String idFor(String title) {
        return idFor(title, title);
    }

    static String idFor(String title, String identity) {
        String base = title == null ? "game" : title.toLowerCase(java.util.Locale.ROOT);
        StringBuilder clean = new StringBuilder();

        for (int i = 0; i < base.length() && clean.length() < 24; i++) {
            char c = base.charAt(i);

            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                clean.append(c);
            } else if (c >= 'а' && c <= 'я') {
                clean.append(LATIN[c - 'а']);
            }
        }

        if (clean.length() == 0) {
            clean.append("game");
        }

        String tail;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((identity == null ? "" : identity).getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (int i = 0; i < 8; i++) hash.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
            tail = hash.toString();
        } catch (Exception unavailable) { throw new IllegalStateException(unavailable); }

        // Имя пакета Android не может начинаться с цифры.
        if (Character.isDigit(clean.charAt(0))) {
            clean.insert(0, 'g');
        }

        return clean + tail;
    }

    private static final String[] LATIN = {
            "a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p",
            "r", "s", "t", "u", "f", "h", "c", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"
    };
}
