package ru.dustore.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Крошечный сервер для запуска веб-игры внутри лаунчера.
 *
 * Казалось бы, распакованную игру можно открыть прямо файлом — file:///…/index.html. На
 * простой странице это и сработает, но настоящие сборки (Godot, Unity, всё на wasm)
 * подтягивают свои части через fetch, а fetch к file:// браузер запрещает: игра покажет
 * чёрный экран и ошибку в консоли. Поэтому файлы отдаются по http с localhost — так
 * сборка видит обычный сайт и ведёт себя как в браузере.
 *
 * Слушается только 127.0.0.1: снаружи, из сети, сюда не попасть, порт живёт ровно пока
 * открыта игра.
 *
 * Заголовки Cross-Origin-*-Policy выставлены не для красоты: без них браузер выключает
 * SharedArrayBuffer, а на нём держатся многопоточные сборки Godot и Unity — без них они
 * просто откажутся запускаться.
 */
final class WebServer {

    private final File root;
    private volatile ServerSocket socket;
    private volatile ThreadPoolExecutor workers;
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<>());
    private Thread thread;

    WebServer(File root) {
        this.root = root;
    }

    /** Поднимает сервер и возвращает адрес, который можно открыть. Либо null. */
    synchronized String start() {
        File entry = WebEntry.file(root);
        if (entry == null) return null;
        String entryPath = "/" + entry.getName();
        if (socket != null && !socket.isClosed()) return "http://127.0.0.1:" + socket.getLocalPort() + entryPath;
        try {
            // Порт 0 — пусть система выберет свободный: фиксированный номер однажды
            // окажется занят чужой программой, и игра не откроется без объяснений.
            socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        } catch (IOException ex) {
            return null;
        }

        workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
            Thread worker = new Thread(task, "dustore-web-file"); worker.setDaemon(true); return worker;
        });
        ServerSocket listener = socket; ThreadPoolExecutor pool = workers;
        thread = new Thread(() -> loop(listener, pool), "dustore-web");
        thread.setDaemon(true);
        thread.start();

        return "http://127.0.0.1:" + socket.getLocalPort() + entryPath;
    }

    synchronized void stop() {
        ServerSocket listener = socket; socket = null;
        try {
            if (listener != null) listener.close();
        } catch (IOException ignored) {
        }

        ThreadPoolExecutor pool = workers; workers = null;
        if (pool != null) pool.shutdownNow();
        synchronized (clients) {
            for (Socket client : new ArrayList<>(clients)) try { client.close(); } catch (IOException ignored) { }
            clients.clear();
        }
        thread = null;
    }

    private void loop(ServerSocket listener, ThreadPoolExecutor pool) {
        while (!listener.isClosed()) {
            Socket client = null;
            try {
                client = listener.accept();
                // Browser preconnect can open an idle socket before the real request.
                // Bound both concurrency and header waiting so it cannot freeze a game.
                client.setSoTimeout(4000); clients.add(client);
                Socket accepted = client;
                pool.execute(() -> {
                    try (Socket owned = accepted) { serve(owned); }
                    catch (Exception ignored) { }
                    finally { clients.remove(accepted); }
                });
            } catch (Exception ex) {
                // Закрытый сокет — это остановка сервера, а не беда. Любая другая ошибка
                // касается одного запроса, и ронять из-за неё игру целиком не нужно.
                if (client != null) { clients.remove(client); try { client.close(); } catch (IOException ignored) { } }
                if (listener.isClosed()) {
                    return;
                }
            }
        }
    }

    private void serve(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();

        String request = readRequestLine(in);
        if (request == null) {
            return;
        }

        String[] parts = request.split(" ");
        if (parts.length < 2) {
            respond(out, 400, "text/plain", "Плохой запрос".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String range = null; int headerBytes = request.length();
        for (int count = 0; ; count++) {
            String line = readRequestLine(in);
            if (line == null) return;
            if (line.isEmpty()) break;
            headerBytes += line.length();
            if (count >= 128 || headerBytes > 32768) { respond(out, 431, "text/plain", new byte[0]); return; }
            if (line.regionMatches(true, 0, "Range:", 0, 6)) range = line.substring(6).trim();
        }
        boolean head = "HEAD".equals(parts[0]);
        if (!head && !"GET".equals(parts[0])) { respond(out, 405, "text/plain", new byte[0]); return; }
        String path = parts[1].split("\\?")[0];
        if (path.equals("/")) {
            File entry = WebEntry.file(root);
            path = entry == null ? "/index.html" : "/" + entry.getName();
        }

        String decoded;
        try {
            // '+' is a literal path character; form decoding would turn c++.js into c  .js.
            decoded = URLDecoder.decode(path.replace("+", "%2B"), "UTF-8");
        } catch (IllegalArgumentException malformed) {
            respond(out, 400, "text/plain", new byte[0]); return;
        }
        while (decoded.startsWith("/")) decoded = decoded.substring(1);
        File file = new File(root, decoded);

        // Из корня игры не выйти: путь приходит из самой игры, но подсунуть его может и
        // что угодно, что в ней исполняется.
        if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)
                || !file.isFile()) {
            respond(out, 404, "text/plain", "Нет такого файла".getBytes(StandardCharsets.UTF_8));
            return;
        }

        long length = file.length(), first = 0, last = length - 1;
        boolean partial = range != null && range.startsWith("bytes=") && !range.contains(",");
        if (partial) try {
            String[] edges = range.substring(6).split("-", -1);
            if (edges.length != 2) throw new IllegalArgumentException();
            if (edges[0].isEmpty()) {
                long suffix = Long.parseLong(edges[1]); if (suffix <= 0) throw new IllegalArgumentException();
                first = Math.max(0, length - suffix);
            } else {
                first = Long.parseLong(edges[0]);
                if (!edges[1].isEmpty()) last = Math.min(last, Long.parseLong(edges[1]));
            }
            if (first < 0 || first > last || first >= length) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            headers(out, 416, "text/plain", 0, "Content-Range: bytes */" + length + "\r\n"); out.flush(); return;
        }
        long count = Math.max(0, last - first + 1);
        headers(out, partial ? 206 : 200, typeOf(file.getName()), count,
                "Accept-Ranges: bytes\r\n" + (partial ? "Content-Range: bytes " + first + "-" + last + "/" + length + "\r\n" : ""));
        if (!head) try (RandomAccessFile stream = new RandomAccessFile(file, "r")) {
            stream.seek(first); byte[] buffer = new byte[65536];
            for (long remaining = count; remaining > 0;) {
                int read = stream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) throw new IOException("Game asset changed during transfer");
                out.write(buffer, 0, read); remaining -= read;
            }
        }
        out.flush();
    }

    private String readRequestLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;

        while ((value = in.read()) != -1) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                line.append((char) value);
            }
            if (line.length() > 8192) {
                return null;
            }
        }

        return value == -1 && line.length() == 0 ? null : line.toString();
    }

    private void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        headers(out, status, type, body.length, "");
        out.write(body); out.flush();
    }

    private void headers(OutputStream out, int status, String type, long length, String extra) throws IOException {
        String head = "HTTP/1.1 " + status + " OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + length + "\r\n" + extra
                + "Cross-Origin-Opener-Policy: same-origin\r\n"
                + "Cross-Origin-Embedder-Policy: require-corp\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";

        out.write(head.getBytes(StandardCharsets.UTF_8));
    }

    private String typeOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        // Тип для wasm обязателен: без него браузер откажется компилировать модуль на лету.
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";

        return "application/octet-stream";
    }
}
