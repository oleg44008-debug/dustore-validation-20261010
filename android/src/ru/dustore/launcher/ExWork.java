package ru.dustore.launcher;

import java.util.ArrayList;
import java.util.List;

/** Cooperative cancellation belongs only to the current eX task and its own resources. */
final class ExWork {
    interface Stage { void changed(int index, int total, String text); }
    interface Transfer { void changed(String label, long completedBytes, long totalBytes); }
    static final class Cancelled extends RuntimeException {
        Cancelled() { super("Работа отменена"); }
    }
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    static final class Scope implements AutoCloseable {
        private final List<Runnable> resources = new ArrayList<>();
        private final Stage stage;
        private final Transfer transfer;
        private long lastTransferNotify;
        private volatile boolean cancelled;
        private volatile boolean acceptingCancellation = true;
        Scope(Stage stage) { this(stage, null); }
        Scope(Stage stage, Transfer transfer) { this.stage = stage; this.transfer = transfer; }
        void transfer(String label, long completedBytes, long totalBytes) {
            if (transfer == null) return;
            long now = android.os.SystemClock.elapsedRealtime();
            boolean boundary = completedBytes < 0 || completedBytes == 0
                    || totalBytes > 0 && completedBytes >= totalBytes;
            if (!boundary && now - lastTransferNotify < 250) return;
            lastTransferNotify = now;
            transfer.changed(label, completedBytes, totalBytes);
        }
        void enter() { CURRENT.set(this); check(); }
        boolean cancelled() { return cancelled; }
        synchronized boolean requestCancel() {
            if (!acceptingCancellation) return false;
            cancelled = true; return true;
        }
        boolean cancellable() { return acceptingCancellation && !cancelled; }
        synchronized void commit() {
            check(); acceptingCancellation = false;
        }
        void closeResources() {
            List<Runnable> closing;
            synchronized (resources) { closing = new ArrayList<>(resources); }
            for (Runnable resource : closing) {
                try { resource.run(); } catch (RuntimeException ignored) { }
            }
        }
        Hook hook(Runnable resource) {
            synchronized (resources) { resources.add(resource); }
            if (cancelled) resource.run();
            return () -> { synchronized (resources) { resources.remove(resource); } };
        }
        @Override public void close() { CURRENT.remove(); }
    }

    interface Hook extends AutoCloseable { @Override void close(); }

    static Hook hook(Runnable cancelResource) {
        Scope scope = CURRENT.get();
        return scope == null ? () -> { } : scope.hook(cancelResource);
    }

    static void check() {
        Scope scope = CURRENT.get();
        if (scope != null && (scope.cancelled || scope.acceptingCancellation && Thread.currentThread().isInterrupted())) throw new Cancelled();
    }

    static void stage(int index, int total, String text) {
        check();
        Scope scope = CURRENT.get();
        if (scope != null && scope.stage != null) scope.stage.changed(index, total, text);
    }

    /** Actual counters for the current transfer, never a percentage for the whole task. */
    static void transfer(String label, long completedBytes, long totalBytes) {
        Scope scope = CURRENT.get();
        if (scope != null) scope.transfer(label, completedBytes, totalBytes);
    }

    static boolean cancelled() {
        Scope scope = CURRENT.get();
        return scope != null && scope.cancelled;
    }

    static void commit() {
        Scope scope = CURRENT.get();
        if (scope != null) scope.commit();
    }

    /** Cleanup must finish even when the work being cleaned up was cancelled. */
    static void cleanup(Runnable action) {
        Scope scope = CURRENT.get();
        boolean interrupted = Thread.interrupted();
        CURRENT.remove();
        try { action.run(); }
        finally {
            if (scope != null) CURRENT.set(scope);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
