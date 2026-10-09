package ru.dustore.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Вход в аккаунт eXepc — тот же самый, что в настольном лаунчере.
 *
 * Аккаунт один на все устройства: человек, зарегистрировавшийся на компьютере, входит
 * здесь той же почтой и паролем, и его отзывы — одни и те же в обоих местах. Ради этого
 * телефон не изобретает своей проверки личности, а разговаривает с тем же сервером теми
 * же запросами.
 *
 * Хранится только выданный сервером токен. Пароль не хранится нигде и никогда: он нужен
 * ровно один раз, в момент входа, и дальше от него нет никакой пользы — а вот украсть
 * сохранённый пароль можно, и он же обычно открывает и почту.
 */
final class Account {

    private static final String FILE = "account";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_NAME = "account";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_CREATOR = "creator";

    private final SharedPreferences store;

    Account(Context context) {
        this.store = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String token() {
        return store.getString(KEY_TOKEN, null);
    }

    String name() {
        return store.getString(KEY_NAME, null);
    }

    boolean creator() {
        return store.getBoolean(KEY_CREATOR, false);
    }

    boolean signedIn() {
        return token() != null;
    }

    void remember(JSONObject reply) {
        store.edit()
                .putString(KEY_TOKEN, reply.optString("token", token()))
                .putString(KEY_NAME, reply.optString("account", null))
                .putString(KEY_EMAIL, reply.optString("email", store.getString(KEY_EMAIL, null)))
                .putBoolean(KEY_CREATOR, reply.optBoolean("creator", false))
                .apply();
    }

    void forget() {
        store.edit().clear().apply();
    }

    interface Result {
        /** Ошибка — текстом для человека, либо null, если всё получилось. */
        void done(String error);
    }

    interface CodeSent {
        /**
         * @param error   текст ошибки либо null
         * @param known   true, если аккаунт на эту почту уже есть — тогда это не
         *                регистрация, а смена пароля, и спрашивать надо другое
         */
        void done(String error, boolean known);
    }

    /** Просит выслать код на почту. Первый шаг и регистрации, и смены пароля. */
    void requestCode(String email, CodeSent result) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
        } catch (Exception ignored) {
        }

        Api.post("/api/auth/start", body, reply -> {
            if (!reply.ok()) {
                result.done(reply.message(), false);
                return;
            }

            JSONObject json = reply.object();
            result.done(null, json != null && json.optBoolean("existing", false));
        });
    }

    /**
     * Завершает регистрацию — либо меняет пароль, если аккаунт уже есть.
     *
     * Одна почта — один аккаунт. Повторная регистрация на ту же почту сервером
     * отклоняется, и это правильно: раньше она молча затирала прежний аккаунт вместе с
     * ником, и выглядело это как несколько аккаунтов на одну почту.
     */
    void register(String email, String code, String password, String nickname,
                  boolean reset, Result result) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("code", code);
            body.put("password", password);
            body.put("reset", reset);

            if (!reset && nickname != null && !nickname.trim().isEmpty()) {
                body.put("nickname", nickname.trim());
            }
        } catch (Exception ignored) {
        }

        Api.post("/api/auth/finish", body, reply -> {
            if (!reply.ok()) {
                result.done(reply.message());
                return;
            }

            JSONObject json = reply.object();
            if (json == null) {
                result.done("Сервер ответил непонятно.");
                return;
            }

            remember(json);
            result.done(null);
        });
    }

    void signIn(String email, String password, Result result) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("password", password);
        } catch (Exception ignored) {
        }

        Api.post("/api/auth/login", body, reply -> {
            if (!reply.ok()) {
                result.done(reply.message());
                return;
            }

            JSONObject json = reply.object();
            if (json == null) {
                result.done("Сервер ответил непонятно.");
                return;
            }

            remember(json);
            result.done(null);
        });
    }

    /**
     * Проверяет, что сохранённый токен ещё годится.
     *
     * Токен живёт три месяца, но может кончиться и раньше. Молча показывать имя человека,
     * которого сервер уже не признаёт, нельзя: он нажмёт «оценить» и получит отказ, не
     * понимая, при чём тут вход. Поэтому просроченный токен забывается сразу.
     */
    void verify(Result result) {
        String token = token();

        if (token == null) {
            result.done("Нужен вход.");
            return;
        }

        JSONObject body = new JSONObject();
        try {
            body.put("token", token);
        } catch (Exception ignored) {
        }

        Api.post("/api/auth/check", body, reply -> {
            if (reply.status == 401) {
                forget();
                result.done("Вход устарел, войдите снова.");
                return;
            }

            if (!reply.ok()) {
                // Сеть могла просто отвалиться — это не повод выкидывать человека из
                // аккаунта. Забываем токен только когда сервер прямо сказал, что не
                // признаёт его.
                result.done(reply.message());
                return;
            }

            JSONObject json = reply.object();
            if (json != null) {
                remember(json);
            }

            result.done(null);
        });
    }
}
