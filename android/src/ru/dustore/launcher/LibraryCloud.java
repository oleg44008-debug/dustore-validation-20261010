package ru.dustore.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Библиотека, привязанная к аккаунту eXepc, а не к устройству.
 *
 * Раньше телефон и компьютер вели каждый свой список. Человек скачивал игру на компьютере,
 * брал телефон — и не находил её; а eXeportal, который весь смысл имеет в том, чтобы играть
 * в игру компьютера на телефоне, показывать было нечего, пока телефон не воткнут в кабель.
 *
 * Теперь опись библиотеки лежит в аккаунте. Уезжает только опись — ссылка на игру, название,
 * обложка, размер и сколько играли; сами игры остаются там, где лежат.
 */
final class LibraryCloud {

    /** Игра из общей библиотеки — с пометкой, где она есть. */
    static final class Game {
        String key;
        String title;
        String cover;
        String kind;
        long size;
        boolean onPc;
        boolean onPhone;
        long playSeconds;

        /** Есть только на компьютере — такую можно открыть через eXeportal. */
        boolean pcOnly() {
            return onPc && !onPhone;
        }
    }

    interface Done {
        void ready(List<Game> games, String error);
    }

    private static List<Game> known = new ArrayList<>();

    private LibraryCloud() {
    }

    /** Что лежит в аккаунте по последнему обмену — чтобы не ждать сети при отрисовке. */
    static List<Game> known() {
        return known;
    }

    /**
     * Отправляет опись этого телефона и забирает общую.
     *
     * Порядок именно такой: сначала рассказать о себе, потом узнать про остальных — иначе
     * забранный список не содержал бы игры, скачанной минуту назад.
     */
    static void sync(Context context, Account account, Library library, Done done) {
        if (account == null || !account.signedIn()) {
            if (done != null) {
                done.ready(known, "Войдите в аккаунт eXepc — библиотека привязана к нему.");
            }

            return;
        }

        JSONArray games = new JSONArray();

        for (Library.Entry entry : library.all()) {
            String key = keyOf(entry);

            if (key == null) {
                continue;
            }

            try {
                JSONObject game = new JSONObject();

                game.put("key", key);
                game.put("title", entry.title == null ? "" : entry.title);
                game.put("kind", entry.kind == null ? "" : entry.kind);
                game.put("playSeconds", entry.playedMinutes * 60);

                // Размер считаем по тому, что лежит на телефоне на самом деле, а не по
                // тому, что обещал магазин: скачанная веб-сборка занимает не столько,
                // сколько её коробка. Игра, скачанная только здесь, иначе оставалась бы в
                // аккаунте с нулём вместо веса.
                long weight = weigh(library, entry);

                if (weight > 0) {
                    game.put("size", weight);
                }

                games.put(game);
            } catch (Exception ignored) {
                // Одна испорченная запись не должна ломать обмен целиком.
            }
        }

        try {
            JSONObject body = new JSONObject();

            body.put("token", account.token());
            body.put("device", "phone");
            body.put("games", games);

            Api.post("/api/library", body, reply -> {
                if (!reply.ok()) {
                    if (done != null) {
                        done.ready(known, "Библиотека не ушла в аккаунт.");
                    }

                    return;
                }

                pull(account, done);
            });
        } catch (Exception broken) {
            if (done != null) {
                done.ready(known, "Не удалось собрать список библиотеки.");
            }
        }
    }

    private static void pull(Account account, Done done) {
        Api.get("/api/library?token=" + Api.encode(account.token()), reply -> {
            JSONObject answer = reply.object();

            if (!reply.ok() || answer == null) {
                if (done != null) {
                    done.ready(known, "Библиотеку аккаунта прочитать не удалось.");
                }

                return;
            }

            List<Game> list = new ArrayList<>();
            JSONArray array = answer.optJSONArray("games");

            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);

                    if (item == null) {
                        continue;
                    }

                    Game game = new Game();

                    game.key = item.optString("key", "");
                    game.title = item.optString("title", "Игра");
                    game.cover = item.optString("cover", "");
                    game.kind = item.optString("kind", "");
                    game.size = item.optLong("size", 0);
                    game.onPc = item.optBoolean("onPc", false);
                    game.onPhone = item.optBoolean("onPhone", false);
                    game.playSeconds = item.optLong("playSeconds", 0);

                    if (!game.key.isEmpty()) {
                        list.add(game);
                    }
                }
            }

            known = list;

            if (done != null) {
                done.ready(list, null);
            }
        });
    }

    /**
     * Ключ игры — её страница в магазине, приведённая к общему виду.
     *
     * Тот же вид, что и на компьютере: без схемы, без «www», без хвостовой косой черты.
     * Иначе одна и та же игра разошлась бы в аккаунте на две записи — по одной с устройства.
     */
    /** Сколько игра занимает на телефоне: файл сборки или распакованная папка. */
    private static long weigh(Library library, Library.Entry entry) {
        try {
            java.io.File file = library.fileOf(entry);

            if (file != null && file.isFile()) {
                return file.length();
            }

            if (entry.folder != null) {
                return weighFolder(new java.io.File(entry.folder), 0);
            }
        } catch (Exception ignored) {
            // Не посчиталось — отправим запись без веса, это лучше вранья.
        }

        return 0;
    }

    private static long weighFolder(java.io.File where, int depth) {
        if (where == null || depth > 8) {
            return 0;
        }

        if (where.isFile()) {
            return where.length();
        }

        java.io.File[] children = where.listFiles();

        if (children == null) {
            return 0;
        }

        long sum = 0;

        for (java.io.File child : children) {
            sum += weighFolder(child, depth + 1);
        }

        return sum;
    }

    static String keyOf(Library.Entry entry) {
        if (entry == null) {
            return null;
        }

        String url = entry.pageUrl != null ? entry.pageUrl : entry.gameKey;

        return normalise(url);
    }

    static String normalise(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }

        String text = url.trim().toLowerCase(java.util.Locale.ROOT);

        if (text.startsWith("https://")) {
            text = text.substring(8);
        } else if (text.startsWith("http://")) {
            text = text.substring(7);
        }

        if (text.startsWith("www.")) {
            text = text.substring(4);
        }

        int cut = text.indexOf('?');

        if (cut >= 0) {
            text = text.substring(0, cut);
        }

        cut = text.indexOf('#');

        if (cut >= 0) {
            text = text.substring(0, cut);
        }

        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }

        return text.isEmpty() ? null : text;
    }
}
