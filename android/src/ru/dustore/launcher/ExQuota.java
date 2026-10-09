package ru.dustore.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Дневной лимит eX во Free — по времени сервера, а не по часам телефона.
 *
 * Время берётся из заголовка Date ответа dustore.ru (или google.com), дальше его ведёт
 * {@link SystemClock#elapsedRealtime()}: эти часы нельзя перевести в настройках, и они идут
 * даже между запусками приложения — до перезагрузки телефона. Без интернета и после
 * перезагрузки время стоит на последнем доверенном моменте: новые сутки наступают только с
 * сетью, а перевод часов вперёд или назад ничего не меняет. Сутки — московские.
 *
 * Счётчик подписан (HMAC) и лежит в двух местах; правка читается как «лимит исчерпан».
 */
final class ExQuota {

    private static final String[] SOURCES = { "https://dustore.ru/", "https://www.google.com/generate_204" };

    /** Только для проверок: подменяет ответ сервера. */
    static Long serverOverride;

    private ExQuota() {}

    // ------------------------------------------------------------------ время ----

    /** Спросить время у сервера. Долго (до 10 с) — только из фонового потока. */
    static void sync(Context context) {
        Long server = serverOverride;
        if (server == null) {
            for (String source : SOURCES) {
                try {
                    HttpURLConnection http = (HttpURLConnection) new URL(source).openConnection();
                    http.setRequestMethod("HEAD");
                    http.setConnectTimeout(5000);
                    http.setReadTimeout(5000);
                    http.connect();
                    long date = http.getHeaderFieldDate("Date", 0);
                    http.disconnect();
                    if (date > 0) { server = date; break; }
                } catch (Exception offline) {
                    // Следующий источник или офлайн.
                }
            }
        }
        if (server == null) return;
        Record record = read(context);
        if (record.tampered) return;
        String day = dayOf(server);
        boolean keep = day.equals(record.day) || later(record.day, day);
        write(context, new Record(keep ? record.day : day, keep ? record.used : 0,
                Math.max(server, record.trusted), server, SystemClock.elapsedRealtime(), bootCount(context)));
    }

    private static int bootCount(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT); }
        catch (Exception unknown) { return -1; }
    }

    /** Доверенное «сейчас» или 0, если сервер ещё ни разу не ответил. */
    private static long trustedNow(Context context, Record record) {
        if (record.syncServer > 0 && record.syncBoot == bootCount(context) && record.syncBoot != -1) {
            long elapsed = SystemClock.elapsedRealtime() - record.syncElapsed;
            if (elapsed >= 0) return Math.max(record.trusted, record.syncServer + elapsed);
        }
        return record.trusted;
    }

    private static String dayOf(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT+03:00"));
        return format.format(new Date(millis));
    }

    private static boolean later(String a, String b) {
        boolean aDate = a.length() == 10 && Character.isDigit(a.charAt(0));
        boolean bDate = b.length() == 10 && Character.isDigit(b.charAt(0));
        return aDate && (!bDate || a.compareTo(b) > 0);
    }

    private static String currentDay(Context context, Record record) {
        long now = trustedNow(context, record);
        String day = now > 0 ? dayOf(now) : record.day.isEmpty() ? "first-run" : record.day;
        return later(record.day, day) ? record.day : day;
    }

    // ---------------------------------------------------------------- счётчик ----

    static int usedToday(Context context, int limit) {
        Record record = read(context);
        if (record.tampered) return limit;
        return record.day.equals(currentDay(context, record)) ? record.used : 0;
    }

    static void recordSuccess(Context context, int limit) {
        Record record = read(context);
        String day = currentDay(context, record);
        int used = record.tampered ? limit : day.equals(record.day) ? record.used : 0;
        write(context, new Record(day, used + 1, record.trusted, record.syncServer, record.syncElapsed, record.syncBoot));
    }

    static void clear(Context context) {
        prefs(context).edit().clear().commit();
        second(context).delete();
    }

    static void corruptFirstCopy(Context context) {
        String text = prefs(context).getString("q", "");
        prefs(context).edit().putString("q", text.replace("|3|", "|0|")).commit();
    }

    static void deleteFirstCopy(Context context) {
        prefs(context).edit().clear().commit();
    }

    // ---------------------------------------------------------------- хранение ----

    private static final class Record {
        final String day; final int used; final long trusted;
        final long syncServer; final long syncElapsed; final int syncBoot;
        boolean tampered;

        Record(String day, int used, long trusted, long syncServer, long syncElapsed, int syncBoot) {
            this.day = day; this.used = used; this.trusted = trusted;
            this.syncServer = syncServer; this.syncElapsed = syncElapsed; this.syncBoot = syncBoot;
        }

        String body() { return day + "|" + used + "|" + trusted + "|" + syncServer + "|" + syncElapsed + "|" + syncBoot; }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("ex-quota-v2", Context.MODE_PRIVATE);
    }

    private static File second(Context context) {
        return new File(context.getNoBackupFilesDir(), ".exq");
    }

    private static String sign(Context context, String body) {
        try {
            String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(("dustore-ex-quota/v1/" + id).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) hex.append(String.format(Locale.US, "%02x", b));
            return hex.toString();
        } catch (Exception broken) {
            return "";
        }
    }

    private static Record parse(Context context, String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String[] parts = text.trim().split("\\|");
        try {
            if (parts.length == 7) {
                Record r = new Record(parts[0], Integer.parseInt(parts[1]), Long.parseLong(parts[2]),
                        Long.parseLong(parts[3]), Long.parseLong(parts[4]), Integer.parseInt(parts[5]));
                if (sign(context, r.body()).equals(parts[6])) return r;
            }
        } catch (Exception malformed) {
            // Ниже — как подделка.
        }
        Record bad = new Record("", 0, 0, 0, 0, -1);
        bad.tampered = true;
        return bad;
    }

    private static Record read(Context context) {
        Record merged = new Record("", 0, 0, 0, 0, -1);
        String fromFile = null;
        try {
            File file = second(context);
            if (file.exists()) fromFile = ExFileRead.utf8(file, 32 * 1024);
        } catch (ExFileRead.TooLarge malformed) {
            // A valid signed record is a short line. An oversized second copy is
            // malformed data, just like an invalid HMAC; it must remain fail-closed.
            fromFile = "oversized-record";
        } catch (Exception unreadable) {
            // Нет второй копии — бывает.
        }
        for (String text : new String[] { prefs(context).getString("q", null), fromFile }) {
            Record r = parse(context, text);
            if (r == null) continue;
            boolean newer = later(r.day, merged.day) || merged.day.isEmpty();
            String day = r.day.equals(merged.day) ? r.day : newer ? r.day : merged.day;
            int used = r.day.equals(merged.day) ? Math.max(r.used, merged.used) : newer ? r.used : merged.used;
            Record sync = r.syncServer >= merged.syncServer ? r : merged;
            Record next = new Record(day, used, Math.max(r.trusted, merged.trusted), sync.syncServer, sync.syncElapsed, sync.syncBoot);
            next.tampered = merged.tampered || r.tampered;
            merged = next;
        }
        return merged;
    }

    private static void write(Context context, Record record) {
        String text = record.body() + "|" + sign(context, record.body());
        prefs(context).edit().putString("q", text).commit();
        try (FileOutputStream out = new FileOutputStream(second(context))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception unwritable) {
            // Останется одна копия.
        }
    }
}
