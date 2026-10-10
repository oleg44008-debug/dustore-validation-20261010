package ru.dustore.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * Имя этого телефона для компьютера: случайное, своё у каждой установки, без всякой связи
 * с самим устройством или владельцем.
 *
 * По Wi-Fi компьютер один раз спрашивает у нового телефона код со своего экрана и после
 * верного ответа запоминает это имя — дальше телефон подключается без цифр. Код на
 * компьютере новый при каждом запуске, поэтому запоминать приходится не его.
 */
final class PadIdentity {

    private static final String STORE = "exepad";
    private static final String KEY = "device";

    private PadIdentity() { }

    static synchronized String of(Context context) {
        SharedPreferences saved = context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
        String known = saved.getString(KEY, null);
        if (known != null && known.matches("[0-9a-f]{32}")) return known;

        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder text = new StringBuilder(32);
        for (byte value : random) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        saved.edit().putString(KEY, text.toString()).apply();
        return text.toString();
    }
}
