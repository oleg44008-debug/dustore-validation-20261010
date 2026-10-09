package ru.dustore.launcher;

import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.Locale;

/** Process-wide throttling: changing between our game Activities cannot over-poll ADPF. */
final class ThermalSample {
    private static long attempted = -1;
    private static float value = Float.NaN;
    private static String status = "Замер ещё не выполнен";

    static synchronized float read(PowerManager power) {
        if (power == null || Build.VERSION.SDK_INT < 30) {
            status = "Нужен Android 11 и поддержка устройства";
            return Float.NaN;
        }
        long now = SystemClock.uptimeMillis();
        if (attempted >= 0 && now - attempted < 10_000) return value;
        attempted = now;
        try {
            value = power.getThermalHeadroom(10);
            status = Float.isFinite(value) ? "" : "Устройство не вернуло доступный замер";
        } catch (RuntimeException unavailable) {
            value = Float.NaN; status = "Замер не поддержан устройством";
        }
        return value;
    }

    static synchronized String description() {
        return Float.isFinite(value) ? String.format(Locale.ROOT, "%.2f · замер %d с назад", value,
                Math.max(0, SystemClock.uptimeMillis() - attempted) / 1000) : status;
    }
    private ThermalSample() { }
}
