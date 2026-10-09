package ru.dustore.launcher;

import java.net.URI;

/**
 * Ссылка на страницу игры → ключ, под которым живут её отзывы.
 *
 * Это единственное место, где телефон обязан совпасть с настольным лаунчером буква в
 * букву. Оценки у них общие: если здесь получится «dustore.ru/g/221/», а там
 * «dustore.ru/g/221», то одна и та же игра разъедется на две — с половиной отзывов в
 * каждой, и обе цифры станут враньём.
 *
 * Правила те же, что в Reviews.GameKey настольной версии: без схемы, без «www.», без
 * параметров и якоря, без хвостового слэша, в нижнем регистре.
 *
 * Класс намеренно без единого импорта Android — так его можно прогнать обычной java на
 * компьютере и убедиться, что он согласен с настольным лаунчером, не поднимая телефон.
 */
final class GameKey {

    private GameKey() {
    }

    static String of(String pageUrl) {
        if (pageUrl == null || pageUrl.isEmpty()) {
            return null;
        }

        URI uri;
        try {
            uri = new URI(pageUrl.trim());
        } catch (Exception ex) {
            return null;
        }

        String host = uri.getHost();
        if (host == null) {
            return null;
        }

        host = host.toLowerCase(java.util.Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }

        if (host.startsWith("www.")) {
            host = host.substring(4);
        }

        String path = uri.getPath();
        if (path == null) {
            path = "/";
        }

        path = path.toLowerCase(java.util.Locale.ROOT);
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        if (path.isEmpty()) {
            path = "/";
        }

        // Главная страница — не игра. Без этой проверки первый же отзыв уехал бы на ключ
        // «dustore.ru/», то есть на весь магазин разом.
        if (path.equals("/")) {
            return null;
        }

        return host + path;
    }

    /**
     * Похоже ли, что это страница игры.
     *
     * Отзыв должен относиться к игре, а не к списку игр или к новости. Разделы сайта
     * известны наперёд, и всё, что лежит глубже одного из них, считается игрой.
     */
    static boolean looksLikeGame(String pageUrl) {
        String key = of(pageUrl);
        if (key == null) {
            return false;
        }

        int slash = key.indexOf('/');
        String path = slash < 0 ? "" : key.substring(slash + 1);

        return path.startsWith("g/") || path.startsWith("game/") || path.startsWith("games/")
                || path.startsWith("m/game/");
    }
}
