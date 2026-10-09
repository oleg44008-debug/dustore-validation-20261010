package ru.dustore.launcher;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Разговор с сервером отзывов.
 *
 * Тот же сервер, что у настольного лаунчера, и те же самые адреса запросов — отзывы,
 * оставленные с компьютера, видны на телефоне и наоборот. Поэтому здесь ничего «своего
 * телефонного» нет: любое расхождение в правилах означало бы, что две половины одной
 * системы считают по-разному.
 *
 * Ни библиотек, ни зависимостей: HttpURLConnection и org.json есть в самом Android.
 * Приложение весит шестьдесят килобайт, и тащить ради десяти запросов чужой клиент —
 * значит удесятерить его размер ради удобства, которого хватило и без него.
 */
final class Api {

    /**
     * Свой сервер студии. Имя с зашитым в него IP — не причуда: сертификат Let's Encrypt
     * выписывают только на имя, а домена у студии нет. При переезде машины меняется эта
     * строка; настольный лаунчер использует тот же сервер для синхронизации библиотеки.
     */
    static final String SERVER = "https://45-80-69-155.sslip.io";

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 20000;

    /**
     * Три потока, а не один: экран отзывов спрашивает список игр и следом их отзывы, и на
     * одном потоке второй запрос ждал бы конца первого. Больше трёх незачем — сервер
     * маленький, а телефону нечего открывать десяток соединений сразу.
     */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Что вернул сервер: разобранный ответ либо человеческая причина отказа. */
    static final class Reply {
        final int status;
        final String body;
        final String error;

        Reply(int status, String body, String error) {
            this.status = status;
            this.body = body;
            this.error = error;
        }

        boolean ok() {
            return status >= 200 && status < 300 && error == null;
        }

        JSONObject object() {
            try {
                return new JSONObject(body);
            } catch (Exception ex) {
                return null;
            }
        }

        JSONArray array() {
            try {
                return new JSONArray(body);
            } catch (Exception ex) {
                return null;
            }
        }

        /**
         * Что показать человеку.
         *
         * Сервер объясняет отказ по-русски в поле error — это объяснение и надо
         * показывать. Код 500 сам по себе никому ничего не говорит.
         */
        String message() {
            if (error != null) {
                return error;
            }

            JSONObject json = object();
            if (json != null && json.has("error")) {
                return json.optString("error");
            }

            return "Сервер ответил: " + status;
        }
    }

    interface Done {
        void run(Reply reply);
    }

    private Api() {
    }

    static void get(String path, Done done) {
        send("GET", path, null, done);
    }

    static void post(String path, JSONObject body, Done done) {
        send("POST", path, body == null ? new JSONObject() : body, done);
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ex) {
            return value;
        }
    }

    private static void send(String method, String path, JSONObject body, Done done) {
        POOL.execute(() -> {
            Reply reply = call(method, path, body);
            // Ответ всегда возвращается в главный поток: вызывающий код рисует по нему
            // экран, а трогать виды не из главного потока Android не разрешает.
            MAIN.post(() -> done.run(reply));
        });
    }

    private static Reply call(String method, String path, JSONObject body) {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(SERVER + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestProperty("Accept", "application/json");

            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);

                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }

            int status = connection.getResponseCode();

            // Тело ошибки читается из getErrorStream: именно там лежит объяснение отказа,
            // а getInputStream на коде 4xx просто бросает исключение, и объяснение
            // пропало бы.
            InputStream stream = status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();

            return new Reply(status, read(stream), null);
        } catch (java.net.UnknownHostException ex) {
            return new Reply(0, "", "Нет связи с сервером отзывов.");
        } catch (java.net.SocketTimeoutException ex) {
            return new Reply(0, "", "Сервер отзывов не ответил вовремя.");
        } catch (Exception ex) {
            return new Reply(0, "", "Не удалось связаться с сервером: " + ex.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String read(InputStream stream) {
        if (stream == null) {
            return "";
        }

        StringBuilder text = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line);
            }
        } catch (Exception ex) {
            // Оборванное чтение — это пустой ответ, а не падение: вызывающий код и так
            // готов к тому, что разобрать нечего.
        }

        return text.toString();
    }
}
