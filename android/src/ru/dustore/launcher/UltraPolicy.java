package ru.dustore.launcher;

/** Deterministic pacing policy; Android APIs and measurements live in AndroidUltra. */
final class UltraPolicy {
    private int pressure;

    float target(boolean ultra, boolean gpuReady, boolean powerSave, int thermal, float headroom, float maximumHz) {
        if (thermal >= 3 || Float.isFinite(headroom) && headroom >= 1f) pressure = 2;
        else if (thermal >= 2 || Float.isFinite(headroom) && headroom >= .85f) pressure = Math.max(1, pressure);
        else if (thermal <= 1 && (!Float.isFinite(headroom) || headroom < .70f)) pressure = 0;
        float limit = pressure >= 2 ? 30f : pressure >= 1 || powerSave || !ultra || !gpuReady ? 60f : maximumHz;
        return Math.max(24f, Math.min(validHz(maximumHz), limit));
    }

    int pressure() { return pressure; }

    static float validHz(float hz) { return Float.isFinite(hz) && hz >= 24f && hz <= 360f ? hz : 60f; }

    static long budgetNanos(float hz) { return Math.max(1, Math.round(1_000_000_000d / validHz(hz))); }

    /** Start-to-start pacing: expensive rendering consumes the period instead of adding to it. */
    static long waitNanos(long now, long previousStart, long budget) {
        if (previousStart == 0 || now < previousStart) return 0;
        return Math.max(0, budget - (now - previousStart));
    }
}
