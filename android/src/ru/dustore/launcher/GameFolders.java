package ru.dustore.launcher;

import java.io.File;

/** A player may serve only an actual game folder below one of its game roots. */
final class GameFolders {
    private GameFolders() { }

    static boolean contains(File root, File folder) {
        if (root == null || folder == null || !folder.isDirectory()) return false;
        try {
            return folder.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
        } catch (Exception unavailable) { return false; }
    }
}
