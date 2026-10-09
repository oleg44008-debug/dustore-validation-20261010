package ru.dustore.launcher;

/** Validate the entire mapped region before allocating a bitmap or copying a first row. */
final class FrameCopyBounds {
    private FrameCopyBounds() { }

    static boolean valid(int width, int height, long firstByte, int stride, long capacity) {
        if (width <= 0 || height <= 0 || firstByte < 0 || (firstByte & 3) != 0
                || stride < (long) width * 4 || (stride & 3) != 0) return false;
        long end = firstByte + (height - 1L) * stride + width * 4L;
        return end >= firstByte && end <= capacity;
    }
}
