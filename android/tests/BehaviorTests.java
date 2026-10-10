package ru.dustore.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Real overlap, cancellation, session-ownership and finalization checks on production classes. */
public final class BehaviorTests {
    private static int checks;
    public static void main(String[] args) throws Exception {
        List<String> events = new ArrayList<>();
        InputLedger ledger = new InputLedger((key, down) -> events.add(key + ":" + down));
        ledger.set("stick:up", "W", true); ledger.set("button:W", "W", true);
        ledger.set("stick:up", "W", true);
        check("one keydown for overlapping sources", events.equals(List.of("W:true")));
        ledger.set("stick:up", "W", false);
        check("other source still holds W", ledger.heldKeys() == 1 && events.size() == 1);
        ledger.set("button:W", "W", false);
        check("last source releases W", events.equals(List.of("W:true", "W:false")));
        events.clear(); ledger.set("pointer", "MOUSE_L", true); ledger.set("button", "MOUSE_L", true);
        ledger.set("axis", "A", true); ledger.set("axis", "D", true);
        check("axis reassignment releases its old key", events.contains("A:false") && ledger.heldKeys() == 2);
        ledger.releaseAll(); int count = events.size(); ledger.releaseAll();
        check("lifecycle reset releases distinct keys once", ledger.heldKeys() == 0 && events.contains("MOUSE_L:false") && events.contains("D:false"));
        check("lifecycle reset is idempotent", events.size() == count);
        ledger.set("released", "D", false); check("late pointer up is harmless", events.size() == count);

        // The computer names game folders through /sdcard; the app compares them as /storage/emulated/0.
        String storage = "/storage/emulated/0", games = "/Android/data/ru.dustore.launcher/files/games/demo";
        check("cable path alias is rewritten", GameFolders.unalias("/sdcard" + games, storage).equals(storage + games));
        check("self-primary alias is rewritten", GameFolders.unalias("/storage/self/primary" + games, storage).equals(storage + games));
        check("a lookalike prefix is not an alias", GameFolders.unalias("/sdcard2/x", storage).equals("/sdcard2/x"));
        check("an ordinary path is left alone", GameFolders.unalias("/data/local/tmp/x", storage).equals("/data/local/tmp/x"));

        // The bars follow the system font size up to 130%; content keeps scaling in full.
        check("bars follow a normal font size", ChromeFont.scale(1.0f) == 1.0f && ChromeFont.scale(1.15f) == 1.15f);
        check("bars stop growing at the limit", ChromeFont.scale(2.0f) == ChromeFont.LIMIT && ChromeFont.scale(1.3f) == 1.3f);
        check("a nonsense font scale is treated as normal", ChromeFont.scale(0f) == 1.0f && ChromeFont.scale(Float.NaN) == 1.0f);

        Object first = new Object(), second = new Object();
        check("first session owns compositor", ExSession.acquire(first));
        check("nested probe can use the same owner", ExSession.acquire(first));
        check("another owner cannot enter", !ExSession.acquire(second));
        ExSession.release(second); check("foreign release cannot unlock session", ExSession.busy());
        ExSession.release(first); check("real owner releases session", !ExSession.busy() && ExSession.acquire(second));
        ExSession.release(second);
        CountDownLatch start = new CountDownLatch(1), acquired = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicInteger owners = new AtomicInteger();
        Thread[] rivals = new Thread[2];
        for (int i = 0; i < rivals.length; i++) rivals[i] = new Thread(() -> {
            Object token = new Object();
            try {
                start.await(); boolean held = ExSession.acquire(token);
                if (held) owners.incrementAndGet(); acquired.countDown(); release.await();
                if (held) ExSession.release(token);
            } catch (InterruptedException error) { throw new AssertionError(error); }
        });
        for (Thread rival : rivals) rival.start(); start.countDown();
        check("both rivals reached acquire", acquired.await(3, TimeUnit.SECONDS));
        check("concurrent ownership remains exclusive", owners.get() == 1);
        release.countDown(); for (Thread rival : rivals) rival.join(3000);
        check("rivals finish without orphaned owner", !ExSession.busy());

        AtomicInteger closed = new AtomicInteger(), stages = new AtomicInteger();
        ExWork.Scope scope = new ExWork.Scope((index, total, title) -> stages.incrementAndGet());
        scope.enter(); ExWork.stage(1, 2, "real stage"); check("scope forwards actual stage", stages.get() == 1);
        ExWork.Hook removed = ExWork.hook(() -> closed.addAndGet(100)); removed.close();
        ExWork.Hook active = ExWork.hook(closed::incrementAndGet);
        check("pre-finalization cancellation accepted", scope.requestCancel()); scope.closeResources();
        check("cancel closes only registered resources", closed.get() == 1);
        try { ExWork.check(); throw new AssertionError("cancel not observed"); } catch (ExWork.Cancelled expected) { checks++; }
        AtomicBoolean cleanupRan = new AtomicBoolean();
        Thread.currentThread().interrupt();
        ExWork.cleanup(() -> { ExWork.check(); cleanupRan.set(true); check("cleanup clears interrupt while it runs", !Thread.currentThread().isInterrupted()); });
        check("cancelled task still completes cleanup", cleanupRan.get());
        check("cleanup preserves interrupt for caller", Thread.interrupted());
        active.close(); scope.close(); ExWork.check(); check("closing scope unbinds cancellation", true);

        ExWork.Scope publication = new ExWork.Scope(null); publication.enter(); ExWork.commit();
        check("atomic finalization rejects late cancellation", !publication.requestCancel() && !publication.cancellable());
        Thread.currentThread().interrupt(); ExWork.check(); Thread.interrupted(); publication.close();
        check("finalization is not interrupted halfway through", !publication.cancelled());
        ExWork.Scope interrupted = new ExWork.Scope(null); interrupted.enter(); Thread.currentThread().interrupt();
        try { ExWork.check(); throw new AssertionError("interrupt not observed"); } catch (ExWork.Cancelled expected) { checks++; }
        Thread.interrupted(); interrupted.close();
        System.out.println("PASS " + checks + " Android input/task ownership checks.");
    }
    private static void check(String name, boolean okay) { if (!okay) throw new AssertionError(name); checks++; }
}
