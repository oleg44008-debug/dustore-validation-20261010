package ru.dustore.launcher;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;

/** Host checks for thermal transitions, scheduling deadlines and native frame memory boundaries. */
public final class PerformanceTests {
    private static int checks;

    public static void main(String[] args) {
        UltraPolicy policy = new UltraPolicy();
        check("healthy GPU can follow 144 Hz", policy.target(true, true, false, 0, .2f, 144) == 144);
        check("Free/default retains 60 fps ceiling", policy.target(false, true, false, 0, .2f, 144) == 60);
        check("software rendering retains 60 fps ceiling", policy.target(true, false, false, 0, .2f, 144) == 60);
        check("Android battery saver wins over ULTRA", policy.target(true, true, true, 0, .2f, 144) == 60);
        check("forecasted thermal pressure reduces pacing", policy.target(true, true, false, 0, .85f, 144) == 60);
        check("pressure does not oscillate at .84", policy.target(true, true, false, 0, .84f, 144) == 60);
        check("cooling below hysteresis threshold restores pacing", policy.target(true, true, false, 0, .69f, 144) == 144);
        check("severe heat reduces work budget", policy.target(true, true, false, 3, Float.NaN, 144) == 30);
        check("severe pressure remains until cooling", policy.target(true, true, false, 2, .8f, 144) == 30);
        check("cool actual status with unavailable forecast recovers", policy.target(true, true, false, 1, Float.NaN, 144) == 144);
        check("headroom 1 anticipates severe throttling", policy.target(true, true, false, 0, 1f, 144) == 30);
        check("unsupported headroom does not prevent rendering", new UltraPolicy().target(true, true, false, -1, Float.NaN, 90) == 90);
        check("target never exceeds a 48 Hz display", new UltraPolicy().target(false, true, false, 0, .1f, 48) == 48);
        check("invalid display frequency uses safe default", UltraPolicy.validHz(Float.NaN) == 60 && UltraPolicy.validHz(0) == 60);
        check("120 Hz uses precise nanosecond period", UltraPolicy.budgetNanos(120) == 8_333_333L);
        check("rendering consumes its period", UltraPolicy.waitNanos(107_000_000L, 100_000_000L, 10_000_000L) == 3_000_000L);
        check("late frame adds no extra sleep", UltraPolicy.waitNanos(113_000_000L, 100_000_000L, 10_000_000L) == 0);
        check("first frame and clock reset never stall", UltraPolicy.waitNanos(2, 0, 10) == 0 && UltraPolicy.waitNanos(1, 2, 10) == 0);

        check("packed region can end at exact capacity", FrameCopyBounds.valid(640, 360, 16, 2560, 16 + 640L * 360 * 4));
        check("padded rows and crop are valid", FrameCopyBounds.valid(4, 3, 12, 32, 92));
        check("one-byte truncation is rejected", !FrameCopyBounds.valid(4, 3, 12, 32, 91));
        check("negative offset is rejected", !FrameCopyBounds.valid(4, 3, -4, 32, 92));
        check("unaligned offsets and stride are rejected", !FrameCopyBounds.valid(4, 3, 13, 32, 100) && !FrameCopyBounds.valid(4, 3, 12, 33, 100));
        check("overlapping short rows are rejected", !FrameCopyBounds.valid(4, 3, 12, 12, 100));
        check("zero-sized frames are rejected", !FrameCopyBounds.valid(0, 3, 0, 32, 100));
        check("overflowing mapped extent is rejected", !FrameCopyBounds.valid(640, 360, Long.MAX_VALUE - 3, 2560, Long.MAX_VALUE));
        equivalentCopy(640, 360, 640 * 4, 16);
        equivalentCopy(7, 5, 48, 20);
        equivalentCopy(1, 1, 4, 0);
        System.out.println("PASS " + checks + " Android performance checks; no device FPS gain inferred.");
    }

    private static void equivalentCopy(int width, int height, int stride, int offset) {
        int size = offset + (height - 1) * stride + width * 4;
        ByteBuffer bytes = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int rgba = ((x * 31 + y * 7) & 255) << 24 | ((x * 11) & 255) << 16 | ((y * 17) & 255) << 8 | (x ^ y) & 255;
            bytes.putInt(offset + y * stride + x * 4, rgba);
        }
        check("copy preflight " + width + "x" + height, FrameCopyBounds.valid(width, height, offset, stride, size));
        int[] rowwise = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) rowwise[y * width + x] = bytes.getInt(offset + y * stride + x * 4);
        IntBuffer words = bytes.asIntBuffer();
        int[] optimized = new int[rowwise.length];
        if (stride == width * 4) {
            words.position(offset / 4);
            words.get(optimized);
        } else for (int y = 0; y < height; y++) {
            words.position((offset + y * stride) / 4);
            words.get(optimized, y * width, width);
        }
        check("optimized transfer preserves every pixel " + width + "x" + height, Arrays.equals(rowwise, optimized));
    }

    private static void check(String message, boolean valid) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }
}
