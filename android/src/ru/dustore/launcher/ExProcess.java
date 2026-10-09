package ru.dustore.launcher;

import android.os.Build;
import android.os.SystemClock;
import java.util.concurrent.TimeUnit;

/** Compatibility for exact owned Process handles; no PID scanning or global process actions. */
final class ExProcess {
    private ExProcess() { }
    static boolean alive(Process process) {
        if (Build.VERSION.SDK_INT >= 26) return process.isAlive();
        try { process.exitValue(); return false; }
        catch (IllegalThreadStateException running) { return true; }
    }
    static boolean await(Process process, long timeout, TimeUnit unit) throws InterruptedException {
        if (Build.VERSION.SDK_INT >= 26) return process.waitFor(timeout, unit);
        long remaining = Math.max(0, unit.toMillis(timeout));
        long began = SystemClock.elapsedRealtime();
        while (alive(process)) {
            long elapsed = SystemClock.elapsedRealtime() - began;
            if (elapsed >= remaining) return false;
            Thread.sleep(Math.min(20, remaining - elapsed));
        }
        return true;
    }
}
