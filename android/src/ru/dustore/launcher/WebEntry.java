package ru.dustore.launcher;

import java.io.File;

/** Match the entry accepted by Unpack while preserving its actual case on disk. */
final class WebEntry {
    private WebEntry() { }

    static File file(File folder) {
        if (folder == null || !folder.isDirectory()) return null;
        File[] children = folder.listFiles();
        if (children == null) return null;
        File fallback = null;
        for (File child : children) {
            if (!child.isFile()) continue;
            if ("index.html".equals(child.getName())) return child;
            if (fallback == null && "index.html".equalsIgnoreCase(child.getName())) fallback = child;
        }
        return fallback;
    }
}
