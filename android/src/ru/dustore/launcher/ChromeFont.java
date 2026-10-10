package ru.dustore.launcher;

/** How far the window's own bars follow the system font size; kept free of Android types. */
final class ChromeFont {
    static final float LIMIT = 1.3f;

    private ChromeFont() { }

    static float scale(float fontScale) {
        if (!(fontScale > 0f)) return 1f;
        return Math.min(fontScale, LIMIT);
    }

    /**
     * A phone on its side has some 360-430 dp of height. Bars drawn for an upright phone
     * took more than half of it and left the store a strip; below this the bars go compact.
     * Tablets and unfolded phones stay above it in either orientation.
     */
    static final int SHORT_SCREEN_DP = 480;

    static boolean shortScreen(int heightDp) {
        return heightDp > 0 && heightDp < SHORT_SCREEN_DP;
    }
}
