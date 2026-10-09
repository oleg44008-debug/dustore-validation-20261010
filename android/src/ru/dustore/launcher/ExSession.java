package ru.dustore.launcher;

/** The compositor and Wine session have one owner until cleanup has finished. */
final class ExSession {
    private static Object owner;
    static synchronized boolean acquire(Object candidate) {
        if (owner != null) return owner == candidate;
        owner = candidate;
        return true;
    }
    static synchronized void release(Object candidate) {
        if (owner == candidate) owner = null;
    }
    static synchronized boolean busy() { return owner != null; }
}
