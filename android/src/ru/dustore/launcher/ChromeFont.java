package ru.dustore.launcher;

/** How far the window's own bars follow the system font size; kept free of Android types. */
final class ChromeFont {
    static final float LIMIT = 1.3f;

    private ChromeFont() { }

    static float scale(float fontScale) {
        if (!(fontScale > 0f)) return 1f;
        return Math.min(fontScale, LIMIT);
    }
}
