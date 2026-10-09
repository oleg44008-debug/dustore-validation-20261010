package ru.dustore.launcher;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;

/** Independent input sources may hold the same key; only the last release sends key-up. */
final class InputLedger {
    interface Sink { void key(String key, boolean down); }
    private final Sink sink;
    private final Map<String, String> sources = new HashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();

    InputLedger(Sink sink) { this.sink = sink; }

    synchronized void set(String source, String key, boolean down) {
        if (source == null || key == null || key.isEmpty()) return;
        String previous = sources.get(source);
        if (down && key.equals(previous)) return;
        if (previous != null) {
            sources.remove(source);
            int remaining = counts.getOrDefault(previous, 1) - 1;
            if (remaining == 0) { counts.remove(previous); sink.key(previous, false); }
            else counts.put(previous, remaining);
        }
        if (down) {
            int count = counts.getOrDefault(key, 0);
            counts.put(key, count + 1);
            sources.put(source, key);
            if (count == 0) sink.key(key, true);
        }
    }

    synchronized void releaseAll() {
        ArrayList<String> held = new ArrayList<>(counts.keySet());
        sources.clear();
        counts.clear();
        for (String key : held) sink.key(key, false);
    }

    synchronized int heldKeys() { return counts.size(); }
}
