package ru.dustore.launcher;

import java.io.File;

/** A player may serve only an actual game folder below one of its game roots. */
final class GameFolders {
    private GameFolders() { }

    static boolean contains(File root, File folder) {
        if (root == null || folder == null || !folder.isDirectory()) return false;
        try {
            String base = unalias(root).getCanonicalPath() + File.separator;
            return unalias(folder).getCanonicalPath().startsWith(base);
        } catch (Exception unavailable) { return false; }
    }

    /**
     * The computer names game folders through /sdcard, and the app sees the same storage as
     * /storage/emulated/0. The link between them is not always resolvable from inside an
     * app, so the well-known aliases are rewritten before the paths are compared; without
     * this a genuine folder sent over the cable could be taken for a foreign one.
     */
    static File unalias(File file) {
        return new File(unalias(file.getAbsolutePath(),
                android.os.Environment.getExternalStorageDirectory().getAbsolutePath()));
    }

    static String unalias(String path, String storage) {
        for (String alias : new String[] { "/sdcard", "/storage/self/primary", "/mnt/sdcard" }) {
            if (path.equals(alias) || path.startsWith(alias + "/")) return storage + path.substring(alias.length());
        }
        return path;
    }

    /**
     * True when the path lies in the app's own private storage (or cannot be resolved).
     *
     * The exported launcher window accepts a build to convert from outside — the computer
     * sends it over the cable — and any other app can send the same request. Pointed at a
     * private file, it used to copy that file into the app's external downloads folder,
     * where on Android 10 and older every app with storage access can read it.
     */
    static boolean isPrivate(android.content.Context context, File path) {
        if (path == null) return true;
        try {
            String canonical = path.getCanonicalPath();
            File data = new File(context.getApplicationInfo().dataDir);
            for (File root : new File[] { data, context.getFilesDir().getParentFile(), context.getCacheDir(), context.getCodeCacheDir() }) {
                if (root == null) continue;
                String base = root.getCanonicalPath();
                if (canonical.equals(base) || canonical.startsWith(base + File.separator)) return true;
            }
            return false;
        } catch (Exception unavailable) { return true; }
    }
}
