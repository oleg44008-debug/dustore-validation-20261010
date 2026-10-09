package ru.dustore.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One application-owned operation; screens observe it without owning the worker or Context. */
final class ExTask {
    interface Listener { void changed(Snapshot task); }
    static final String RUNNING = "running", CANCELLING = "cancelling", CANCELLED = "cancelled",
            DONE = "done", FAILED = "failed", INTERRUPTED = "interrupted";
    private static ExTask shared;
    static synchronized ExTask get(Context context) {
        if (shared == null) shared = new ExTask(context.getApplicationContext());
        return shared;
    }

    static final class Snapshot {
        final String kind, title, state, stage, detail, error, gameId;
        final int step, total;
        final long elapsedMillis;
        final long generation, completedBytes, totalBytes;
        final String transferLabel;
        final boolean cancellable;
        final List<String> log;
        Snapshot(String kind, String title, String state, String stage, String detail,
                 String error, String gameId, int step, int total, long elapsed, List<String> log, boolean cancellable) {
            this(kind, title, state, stage, detail, error, gameId, step, total, elapsed, log, cancellable,
                    0, "", -1, -1);
        }
        Snapshot(String kind, String title, String state, String stage, String detail,
                 String error, String gameId, int step, int total, long elapsed, List<String> log, boolean cancellable,
                 long generation, String transferLabel, long completedBytes, long totalBytes) {
            this.kind = kind; this.title = title; this.state = state; this.stage = stage;
            this.detail = detail; this.error = error; this.gameId = gameId; this.step = step;
            this.total = total; this.elapsedMillis = elapsed; this.log = log;
            this.cancellable = cancellable;
            this.generation = generation; this.transferLabel = transferLabel;
            this.completedBytes = completedBytes; this.totalBytes = totalBytes;
        }
        boolean busy() { return RUNNING.equals(state) || CANCELLING.equals(state); }
        boolean present() { return !state.isEmpty(); }
        boolean hasTransfer() { return completedBytes >= 0 && !transferLabel.isEmpty(); }
        String stateText() {
            if (CANCELLING.equals(state)) return "Останавливаем текущую операцию…";
            if (CANCELLED.equals(state)) return "Остановлено. Завершённые этапы сохранены.";
            if (DONE.equals(state)) return "Готово";
            if (FAILED.equals(state)) return "Не удалось завершить";
            if (INTERRUPTED.equals(state)) return "Работа прервалась при закрытии приложения";
            return stage;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<WeakReference<Listener>> listeners = new ArrayList<>();
    private final ArrayList<String> log = new ArrayList<>();
    private int logCharacters;
    private String kind = "", title = "", state = "", stage = "", detail = "", error = "", gameId = "";
    private String sourceFile = "";
    private long sourceDownload;
    private int step, total;
    private long started, elapsed, lastSaved;
    private long generation, completedBytes = -1, totalBytes = -1;
    private String transferLabel = "";
    private Thread worker;
    private ExWork.Scope scope;
    private boolean notifyQueued;
    private boolean recovering;
    private final Runnable notifyViews = () -> {
        List<Listener> current = new ArrayList<>();
        Snapshot snapshot;
        synchronized (ExTask.this) {
            notifyQueued = false;
            listeners.removeIf(item -> item.get() == null);
            for (WeakReference<Listener> item : listeners) {
                Listener listener = item.get();
                if (listener != null) current.add(listener);
            }
            if (current.isEmpty()) return;
            snapshot = snapshot();
        }
        for (Listener listener : current) listener.changed(snapshot);
    };

    private ExTask(Context context) { this.context = context; restore(); }

    synchronized Snapshot snapshot() {
        return new Snapshot(kind, title, state, stage, detail, error, gameId, step, total, elapsedMillis(),
                new ArrayList<>(log), scope != null && scope.cancellable(), generation, transferLabel, completedBytes, totalBytes);
    }

    synchronized long elapsedMillis() {
        return started == 0 ? elapsed : Math.max(0, SystemClock.elapsedRealtime() - started);
    }

    synchronized void observe(Listener listener) {
        for (WeakReference<Listener> item : listeners) if (item.get() == listener) return;
        listeners.add(new WeakReference<>(listener));
        listener.changed(snapshot());
    }
    synchronized void remove(Listener listener) { listeners.removeIf(item -> item.get() == null || item.get() == listener); }

    String setup() {
        return start("setup", "Подготовка системы eX", 6, null, null);
    }
    String proof() { return start("proof", "Проверка системы eX", 4, null, null); }
    String convert(Library library, Library.Entry entry) {
        return start("convert", "Подготовка «" + entry.title + "»", ExConvert.STEPS, library, entry);
    }

    synchronized Library.Entry entry(Library library) {
        if (!"convert".equals(kind) || library == null) return null;
        for (Library.Entry candidate : library.all()) {
            if (!gameId.isEmpty() && gameId.equals(candidate.exId)
                    || sourceDownload > 0 && sourceDownload == candidate.downloadId
                    || !sourceFile.isEmpty() && sourceFile.equals(candidate.fileName)) return candidate;
        }
        return null;
    }

    private synchronized String start(String operation, String taskTitle, int steps, Library library, Library.Entry entry) {
        if (recovering) return "Завершаем предыдущий сеанс eX. Повторите запуск через несколько секунд.";
        if (snapshot().busy()) return "Другая операция eX уже выполняется. Откройте её состояние.";
        Object owner = new Object();
        if (!ExSession.acquire(owner)) return "Сначала закройте текущую игру eX.";
        kind = operation; title = taskTitle; total = steps; step = 0; stage = "Подготовка";
        generation++; transferLabel = ""; completedBytes = totalBytes = -1;
        state = RUNNING; detail = ""; error = ""; gameId = entry == null || entry.exId == null ? "" : entry.exId;
        sourceFile = entry == null || entry.fileName == null ? "" : entry.fileName;
        sourceDownload = entry == null ? 0 : entry.downloadId;
        log.clear(); logCharacters = 0; started = SystemClock.elapsedRealtime(); elapsed = 0; lastSaved = 0;
        scope = new ExWork.Scope((index, count, text) -> {
            if (!"convert".equals(operation)) stage(index, count, text);
            else line(text);
        }, this::transfer);
        ExWork.Scope operationScope = scope;
        ExService.hold(context, taskTitle);
        save(); queueNotify();
        worker = new Thread(() -> {
            String problem = null;
            try (ExWork.Scope ignored = operationScope) {
                operationScope.enter();
                if ("setup".equals(operation)) problem = ExBuild.installNativeLogged(context, this::line);
                else if ("proof".equals(operation)) problem = verify();
                else problem = ExConvert.runOwnedTask(context, library, entry, new ExConvert.Report() {
                    @Override public void step(int index, String text) { stage(index, ExConvert.STEPS, text); }
                    @Override public void log(String text) { line(text); }
                }, owner);
                ExWork.check();
            } catch (ExWork.Cancelled cancelled) {
                operationScope.requestCancel();
            } catch (Exception broken) {
                problem = broken.getMessage() == null ? broken.toString() : broken.getMessage();
            } finally {
                Thread.interrupted();
                if (operationScope.cancelled()) {
                    // The session is still ours. Stop native processes belonging to this app UID.
                    Ex.stopOwnChildren();
                }
                ExService.release(context);
                ExSession.release(owner);
            }
            synchronized (ExTask.this) {
                elapsed = Math.max(0, SystemClock.elapsedRealtime() - started); started = 0;
                state = operationScope.cancelled() ? CANCELLED : problem == null ? DONE : FAILED;
                error = operationScope.cancelled() ? "" : problem == null ? "" : problem;
                if (entry != null) gameId = entry.exId == null ? "" : entry.exId;
                if (DONE.equals(state)) {
                    step = total;
                    if (entry != null) {
                        ExGame converted = ExGame.load(context, gameId);
                        detail = converted == null ? "Подготовка завершена" : converted.probe == null ? "" : converted.probe;
                    } else if ("setup".equals(operation)) detail = "Компоненты eX готовы. Игру можно подготовить из библиотеки.";
                }
                worker = null; scope = null; save(); queueNotify();
            }
        }, "ex-task-" + operation);
        worker.start();
        return null;
    }

    private String verify() {
        String[] names = { "Архитектура системы", "Переводчик кода", "Слой Windows", "Видеокарта" };
        String[][] commands = { { "/bin/uname", "-m" }, { "/usr/local/bin/box64", "--version" },
                { ExNative.ready(context) ? "/usr/bin/wine" : "/opt/wine/bin/wine", "--version" } };
        for (int i = 0; i < commands.length; i++) {
            stage(i + 1, 4, names[i]);
            String result = Ex.run(context, commands[i], false, this::line);
            if (result.contains("EX_EXEC_FAILED") || result.contains("EX_EXIT_CODE")) return names[i] + ": " + Ex.clean(result);
            line(names[i] + ": " + result.trim());
        }
        stage(4, 4, names[3]); line(ExBuild.gpuProof(context));
        return null;
    }

    synchronized void cancel() {
        if (scope == null || !RUNNING.equals(state)) return;
        if (!scope.requestCancel()) return;
        state = CANCELLING;
        ExWork.Scope cancelling = scope;
        if (worker != null) worker.interrupt();
        new Thread(cancelling::closeResources, "ex-task-cancel").start();
        save(); queueNotify();
    }

    private synchronized void stage(int index, int steps, String text) {
        ExWork.check();
        step = Math.max(0, Math.min(index, steps)); total = steps; stage = text; detail = text;
        transferLabel = ""; completedBytes = totalBytes = -1;
        save(); queueNotify();
    }
    private synchronized void transfer(String label, long completed, long expected) {
        transferLabel = completed < 0 || label == null ? "" : label;
        completedBytes = completed < 0 ? -1 : completed;
        totalBytes = completed < 0 || expected <= 0 ? -1 : expected;
        if (SystemClock.elapsedRealtime() - lastSaved >= 1000) save();
        queueNotify();
    }
    private synchronized void line(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String[] lines = text.trim().split("\n");
        for (String value : lines) {
            String line = value.trim(); if (line.isEmpty()) continue;
            String bounded = line.length() > 1500 ? line.substring(0, 1500) + "…" : line;
            log.add(bounded); logCharacters += bounded.length();
            while (log.size() > 200 || logCharacters > 80_000) logCharacters -= log.remove(0).length();
            detail = line;
        }
        if (SystemClock.elapsedRealtime() - lastSaved >= 1000) save();
        queueNotify();
    }
    private void queueNotify() {
        if (notifyQueued) return;
        boolean observing = false;
        for (WeakReference<Listener> listener : listeners) if (listener.get() != null) { observing = true; break; }
        if (!observing) return;
        notifyQueued = true; main.postDelayed(notifyViews, 120);
    }

    private synchronized void save() {
        try {
            Snapshot value = snapshot();
            JSONObject json = new JSONObject();
            json.put("kind", kind); json.put("title", title); json.put("state", state);
            json.put("stage", stage); json.put("detail", detail); json.put("error", error);
            json.put("step", step); json.put("total", total); json.put("gameId", gameId);
            json.put("sourceFile", sourceFile); json.put("sourceDownload", sourceDownload);
            json.put("elapsedMillis", value.elapsedMillis); json.put("log", new JSONArray(log));
            json.put("generation", generation); json.put("transferLabel", transferLabel);
            json.put("completedBytes", completedBytes); json.put("totalBytes", totalBytes);
            File temporary = new File(context.getFilesDir(), "ex-task.json.tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(json.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
            }
            if (!temporary.renameTo(new File(context.getFilesDir(), "ex-task.json"))) temporary.delete();
            lastSaved = SystemClock.elapsedRealtime();
        } catch (Exception ignored) { }
    }

    private void restore() {
        try {
            File file = new File(context.getFilesDir(), "ex-task.json");
            if (!file.isFile() || file.length() > 512 * 1024) return;
            JSONObject json = new JSONObject(ExFileRead.utf8(file, 512 * 1024));
            kind = json.optString("kind", ""); title = json.optString("title", "");
            state = json.optString("state", ""); stage = json.optString("stage", ""); detail = json.optString("detail", "");
            error = json.optString("error", ""); gameId = json.optString("gameId", "");
            sourceFile = json.optString("sourceFile", ""); sourceDownload = json.optLong("sourceDownload");
            step = json.optInt("step"); total = json.optInt("total"); elapsed = json.optLong("elapsedMillis");
            generation = json.optLong("generation"); transferLabel = json.optString("transferLabel", "");
            completedBytes = json.optLong("completedBytes", -1); totalBytes = json.optLong("totalBytes", -1);
            JSONArray lines = json.optJSONArray("log");
            if (lines != null) for (int i = Math.max(0, lines.length() - 200); i < lines.length(); i++) {
                String line = lines.optString(i); log.add(line); logCharacters += line.length();
            }
            while (logCharacters > 80_000) logCharacters -= log.remove(0).length();
            if (RUNNING.equals(state) || CANCELLING.equals(state)) {
                state = INTERRUPTED; save();
                Object owner = new Object();
                if (ExSession.acquire(owner)) {
                    recovering = true;
                    new Thread(() -> {
                        try { Ex.stopOwnChildren(); }
                        finally {
                            ExSession.release(owner);
                            synchronized (ExTask.this) { recovering = false; queueNotify(); }
                        }
                    }, "ex-recovery-cleanup").start();
                }
            }
        } catch (Exception ignored) { }
    }
}
