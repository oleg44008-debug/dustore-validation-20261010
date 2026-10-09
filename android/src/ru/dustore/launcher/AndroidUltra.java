package ru.dustore.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Own eX surface/thread optimization. It never changes system settings or another app's process. */
final class AndroidUltra implements SurfaceHolder.Callback {
    private static volatile long frameBudget = UltraPolicy.budgetNanos(60f);
    private static volatile float targetHz = 60f;
    private static volatile float maximumHz = 60f;
    private static volatile int thermalStatus = -1;
    private static volatile int thermalPressure;
    private static volatile float headroom = Float.NaN;
    private static volatile boolean powerSave;
    private static volatile boolean foreground;
    private static volatile String hintStatus = "Нет активной игры eX";
    private static volatile String pacingReason = "Стандартный режим";
    private static volatile String surfaceStatus = "Нет активной поверхности eX";
    private static volatile int renderFps;
    private static volatile int guestFps;
    private static volatile long rendered;
    private static volatile long renderNanos;
    private static volatile long maximumRenderNanos;
    private static volatile long lastFrameAt;

    private final Activity activity;
    private final SurfaceHolder holder;
    private final PowerManager power;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final UltraPolicy policy = new UltraPolicy();
    private final int originalMode;
    private final float originalRefresh;
    private float supportedMaximum;
    private boolean active;
    private boolean receiverRegistered;
    private boolean checkingGpu;
    private volatile boolean gpuReady;
    private Object thermalListener;
    private Surface requestedSurface;
    private float requestedSurfaceHz = Float.NaN;
    private int surfaceRequests;
    private volatile long ownRendered, ownRenderNanos, ownMaximumRenderNanos;
    private volatile int ownRenderFps, ownGuestFps;
    private float lastRequestedHz;
    private String lastSurfaceRequest = "Не запрашивался";
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active) return;
            sample();
            main.postDelayed(this, 10_000);
        }
    };
    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { sample(); }
    };

    AndroidUltra(Activity activity, SurfaceHolder holder) {
        this.activity = activity;
        this.holder = holder;
        power = activity.getSystemService(PowerManager.class);
        WindowManager.LayoutParams original = activity.getWindow().getAttributes();
        originalMode = original.preferredDisplayModeId;
        originalRefresh = original.preferredRefreshRate;
        supportedMaximum = highestRate(activity);
        maximumHz = supportedMaximum;
        holder.addCallback(this);
        rendered = 0; renderNanos = 0; maximumRenderNanos = 0; lastFrameAt = 0; renderFps = 0; guestFps = 0;
    }

    static long frameBudgetNanos() { return frameBudget; }
    static boolean hintWanted() { return foreground && Prime.maxFps() && !powerSave && thermalStatus < 3 && thermalPressure < 2; }
    static boolean preferEfficiency() { return powerSave || thermalStatus >= 2 || thermalPressure > 0; }

    void refreshDisplay() {
        supportedMaximum = highestRate(activity);
        maximumHz = supportedMaximum;
        if (active) apply();
    }

    void refreshGpu() {
        if (checkingGpu) return;
        checkingGpu = true;
        Context app = activity.getApplicationContext();
        UiWork.execute(() -> {
            boolean ready = Ex.gpuReady(app);
            main.post(() -> { checkingGpu = false; gpuReady = ready; if (active) apply(); });
        });
    }

    void resume() {
        if (active) return;
        active = true;
        foreground = true;
        if (power != null && Build.VERSION.SDK_INT >= 29) {
            try {
                PowerManager.OnThermalStatusChangedListener listener = status -> {
                    thermalStatus = status;
                    apply();
                };
                power.addThermalStatusListener(activity.getMainExecutor(), listener);
                thermalListener = listener;
            } catch (RuntimeException unavailable) { thermalStatus = -1; }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(battery,
                    new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), Context.RECEIVER_NOT_EXPORTED);
            else activity.registerReceiver(battery, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
            receiverRegistered = true;
        } catch (RuntimeException unavailable) { receiverRegistered = false; }
        main.post(poll);
    }

    void pause() {
        if (!active) return;
        active = false;
        foreground = false;
        main.removeCallbacks(poll);
        if (receiverRegistered) {
            try { activity.unregisterReceiver(battery); } catch (RuntimeException ignored) { }
            receiverRegistered = false;
        }
        if (thermalListener != null && power != null && Build.VERSION.SDK_INT >= 29) {
            try { power.removeThermalStatusListener((PowerManager.OnThermalStatusChangedListener) thermalListener); }
            catch (RuntimeException ignored) { }
            thermalListener = null;
        }
        // The guest may keep its audio/session in the background, but shouldn't
        // spend a foreground rendering budget producing frames nobody can see.
        frameBudget = 66_666_667L;
        targetHz = 15f;
        pacingReason = "Приложение в фоне";
        restoreWindow();
        setSurfaceRate(0f);
    }

    void close() {
        pause();
        holder.removeCallback(this);
    }

    /** Called off the UI thread after the painter has stopped. */
    void saveReport() {
        try {
            JSONObject report = new JSONObject();
            report.put("edition", Prime.ON ? "Prime" : "Free");
            report.put("ultraEnabled", Prime.maxFps());
            report.put("supportedHz", supportedMaximum);
            report.put("lastRequestedHz", lastRequestedHz);
            report.put("composedFrames", ownRendered);
            report.put("averageComposeAndPresentMs", ownRendered == 0 ? JSONObject.NULL : ownRenderNanos / (ownRendered * 1_000_000d));
            report.put("maximumComposeAndPresentMs", ownRendered == 0 ? JSONObject.NULL : ownMaximumRenderNanos / 1_000_000d);
            report.put("surfaceHintRequests", surfaceRequests);
            report.put("lastGuestWindowCommitsPerSecond", ownGuestFps);
            report.put("lastComposedFramesPerSecond", ownRenderFps);
            report.put("thermalStatus", thermalStatus);
            report.put("thermalHeadroom", Float.isFinite(headroom) ? headroom : JSONObject.NULL);
            report.put("powerSaver", powerSave);
            report.put("adpf", hintStatus);
            report.put("surfaceRequest", lastSurfaceRequest);
            report.put("scope", "Own eX render thread and surface; no benchmark gain measured");
            File output = new File(activity.getFilesDir(), "ultra-session.json");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    private void sample() {
        if (!active) return;
        refreshGpu();
        if (power != null) {
            try { powerSave = power.isPowerSaveMode(); } catch (RuntimeException ignored) { }
            if (Build.VERSION.SDK_INT >= 29) {
                try { thermalStatus = power.getCurrentThermalStatus(); } catch (RuntimeException ignored) { thermalStatus = -1; }
            }
            headroom = ThermalSample.read(power);
        }
        apply();
    }

    private void apply() {
        if (!active) return;
        float previous = targetHz;
        targetHz = policy.target(Prime.maxFps(), gpuReady, powerSave, thermalStatus, headroom, supportedMaximum);
        thermalPressure = policy.pressure();
        frameBudget = UltraPolicy.budgetNanos(targetHz);
        pacingReason = policy.pressure() >= 2 ? "Тепловая защита" : policy.pressure() > 0 ? "Высокий нагрев"
                : powerSave ? "Энергосбережение Android" : !Prime.maxFps() ? "ULTRA выключен"
                : !gpuReady ? "Программная графика eX" : "Частота устройства";
        if (Build.VERSION.SDK_INT < 30) {
            try {
                WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
                int mode = Prime.maxFps() ? 0 : originalMode;
                float rate = Prime.maxFps() ? windowRate(activity, targetHz) : originalRefresh;
                if (attrs.preferredDisplayModeId != mode || attrs.preferredRefreshRate != rate) {
                    attrs.preferredDisplayModeId = mode; attrs.preferredRefreshRate = rate;
                    activity.getWindow().setAttributes(attrs);
                }
            } catch (RuntimeException unavailable) { }
        }
        setSurfaceRate(Prime.maxFps() ? targetHz : 0f);
        if (Math.abs(previous - targetHz) > .5f) {
            String message = "ULTRA pacing: requested " + Math.round(targetHz) + " Hz; " + pacingReason + ". Display/FPS are separate measurements.";
            Context app = activity.getApplicationContext();
            UiWork.execute(() -> Ex.log(app, message));
        }
    }

    private void restoreWindow() {
        try {
            WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
            attrs.preferredDisplayModeId = originalMode;
            attrs.preferredRefreshRate = originalRefresh;
            activity.getWindow().setAttributes(attrs);
        } catch (RuntimeException ignored) { }
    }

    private void setSurfaceRate(float rate) {
        if (Build.VERSION.SDK_INT < 30) { surfaceStatus = "Surface API: нужен Android 11"; return; }
        try {
            Surface surface = holder.getSurface();
            if (surface == null || !surface.isValid()) { surfaceStatus = "Поверхность пока не готова"; return; }
            if (surface == requestedSurface && requestedSurfaceHz == rate) return;
            if (Build.VERSION.SDK_INT >= 31) surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            else surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            requestedSurface = surface; requestedSurfaceHz = rate; surfaceRequests++;
            surfaceStatus = rate == 0 ? "Запрос частоты снят" : "Запрошено " + Math.round(rate) + " Гц; решение за Android";
            if (rate > 0) { lastRequestedHz = rate; lastSurfaceRequest = surfaceStatus; }
        } catch (RuntimeException unavailable) { surfaceStatus = "Запрос частоты не поддержан"; }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { requestedSurfaceHz = Float.NaN; if (active) apply(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { requestedSurfaceHz = Float.NaN; if (active) apply(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { requestedSurface = null; requestedSurfaceHz = Float.NaN; surfaceStatus = "Поверхность закрыта"; }

    static float highestRate(Activity activity) {
        try {
            Display display = activity.getWindowManager().getDefaultDisplay();
            Display.Mode current = display.getMode();
            float best = current.getRefreshRate();
            for (Display.Mode mode : display.getSupportedModes()) if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight()) best = Math.max(best, mode.getRefreshRate());
            return UltraPolicy.validHz(best);
        } catch (RuntimeException unavailable) { return 60f; }
    }

    /** Before Android 14 the window hint must be a supported display refresh rate. */
    static float windowRate(Activity activity, float desiredHz) {
        if (Build.VERSION.SDK_INT >= 34) return desiredHz;
        try {
            Display display = activity.getWindowManager().getDefaultDisplay();
            Display.Mode current = display.getMode();
            float nearest = current.getRefreshRate();
            for (Display.Mode mode : display.getSupportedModes()) {
                if (mode.getPhysicalWidth() != current.getPhysicalWidth() || mode.getPhysicalHeight() != current.getPhysicalHeight()) continue;
                float rate = mode.getRefreshRate();
                if (Math.abs(rate - desiredHz) < Math.abs(nearest - desiredHz)
                        || Math.abs(rate - desiredHz) == Math.abs(nearest - desiredHz) && rate < nearest) nearest = rate;
            }
            return nearest;
        } catch (RuntimeException unavailable) { return 0; }
    }

    static void noteFrame(long nanos) {
        rendered++; renderNanos += Math.max(0, nanos);
        maximumRenderNanos = Math.max(maximumRenderNanos, nanos);
        lastFrameAt = SystemClock.uptimeMillis();
    }
    void recordFrame(long nanos) {
        ownRendered++; ownRenderNanos += Math.max(0, nanos);
        ownMaximumRenderNanos = Math.max(ownMaximumRenderNanos, nanos);
        noteFrame(nanos);
    }
    void recordFps(int composed, int guestCommits) {
        ownRenderFps = composed; ownGuestFps = guestCommits;
        noteFps(composed, guestCommits);
    }
    static void noteFps(int composed, int guestCommits) { renderFps = composed; guestFps = guestCommits; }

    static String statusLine() { return "ULTRA · цель " + Math.round(targetHz) + " Гц · " + pacingReason; }

    static String diagnostics(Activity activity) {
        float hz = 0;
        String saver = "данных нет";
        String thermal = "данных нет";
        try {
            PowerManager manager = activity.getSystemService(PowerManager.class);
            if (manager != null) {
                saver = manager.isPowerSaveMode() ? "включено" : "выключено";
                if (Build.VERSION.SDK_INT >= 29) thermal = Integer.toString(manager.getCurrentThermalStatus());
            }
        } catch (RuntimeException ignored) { }
        try { hz = activity.getWindowManager().getDefaultDisplay().getRefreshRate(); } catch (RuntimeException ignored) { }
        return "Режим: " + (Prime.maxFps() ? "ULTRA" : "стандартный")
                + "\nДисплей сейчас: " + Math.round(hz) + " Гц"
                + "\nДоступный максимум: " + Math.round(highestRate(activity)) + " Гц"
                + "\nЦель eX: " + (foreground ? Math.round(targetHz) + " к/с · " + pacingReason : "нет активной игры")
                + "\n" + surfaceStatus + "\nADPF: " + hintStatus
                + "\nНагрев Android: " + thermal
                + "\nТепловая нагрузка: " + ThermalSample.description()
                + "\nЭнергосбережение: " + saver
                + "\nПоследние кадры eX: " + renderFps + " к/с"
                + "\nКоммиты окон игры: " + guestFps + "/с"
                + "\nСреднее compose/present: " + (rendered == 0 ? "нет замера" : String.format(Locale.ROOT, "%.2f мс", renderNanos / (rendered * 1_000_000d)))
                + "\nПик compose/present: " + (rendered == 0 ? "нет замера" : String.format(Locale.ROOT, "%.2f мс", maximumRenderNanos / 1_000_000d))
                + (lastFrameAt == 0 ? "" : "\nПоследний кадр: " + Math.max(0, SystemClock.uptimeMillis() - lastFrameAt) / 1000 + " с назад")
                + "\n\nГерцы дисплея, кадры eX и FPS движка — разные величины. ADPF относится к своему потоку отрисовки; настройки сторонних приложений не меняются.";
    }

    /** Created, used and closed by the long-lived eX painter thread only. */
    static final class Hints implements AutoCloseable {
        private Object session;
        private long budget;
        private boolean efficient;

        Hints(Context context) {
            if (Build.VERSION.SDK_INT < 31) { hintStatus = "Нужен Android 12"; return; }
            if (!hintWanted()) { hintStatus = "Выключен политикой ULTRA"; return; }
            try {
                android.os.PerformanceHintManager manager = context.getSystemService(android.os.PerformanceHintManager.class);
                budget = frameBudget;
                if (manager != null) session = manager.createHintSession(new int[] {android.os.Process.myTid()}, budget);
                hintStatus = session == null ? "Не поддержан устройством" : "Активен для потока eX paint";
            } catch (RuntimeException unsupported) { hintStatus = "Создание сессии недоступно"; }
        }

        boolean active() { return session != null; }

        void report(long actualNanos) {
            if (session == null || Build.VERSION.SDK_INT < 31) return;
            try {
                android.os.PerformanceHintManager.Session hints = (android.os.PerformanceHintManager.Session) session;
                if (budget != frameBudget) { budget = frameBudget; hints.updateTargetWorkDuration(budget); }
                if (Build.VERSION.SDK_INT >= 35 && efficient != preferEfficiency()) {
                    efficient = preferEfficiency(); hints.setPreferPowerEfficiency(efficient);
                }
                hints.reportActualWorkDuration(Math.max(1, actualNanos));
            } catch (RuntimeException unsupported) { close(); hintStatus = "Сессия завершена: API недоступен"; }
        }

        @Override public void close() {
            if (session == null || Build.VERSION.SDK_INT < 31) return;
            try { ((android.os.PerformanceHintManager.Session) session).close(); }
            catch (RuntimeException ignored) { }
            session = null;
            hintStatus = "Сессия закрыта";
        }
    }
}
