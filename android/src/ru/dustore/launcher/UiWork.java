package ru.dustore.launcher;

/** Small shared background pool for metadata; never does game rendering or owns a screen. */
final class UiWork {
    private static final java.util.concurrent.ExecutorService WORK = java.util.concurrent.Executors.newFixedThreadPool(2, work -> {
        Thread thread = new Thread(work, "dustore-metadata"); thread.setDaemon(true); return thread;
    });
    private static final java.util.concurrent.ExecutorService HEAVY = java.util.concurrent.Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "dustore-components"); thread.setDaemon(true); return thread;
    });
    static void execute(Runnable work) { WORK.execute(work); }
    static void executeHeavy(Runnable work) { HEAVY.execute(work); }
}
