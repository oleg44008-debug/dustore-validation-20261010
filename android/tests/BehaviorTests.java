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

        // A phone on its side gets compact bars; an upright phone and a tablet do not.
        check("a phone on its side is a short screen", ChromeFont.shortScreen(360) && ChromeFont.shortScreen(411));
        check("an upright phone and a tablet are not", !ChromeFont.shortScreen(800) && !ChromeFont.shortScreen(600) && !ChromeFont.shortScreen(480));
        check("an unknown height keeps the ordinary bars", !ChromeFont.shortScreen(0));

        // Seasonal looks: Halloween is exactly October, New Year runs from December to the end of January.
        check("Halloween is October", Season.of(10) == Season.HALLOWEEN && Season.of(9) == Season.NONE && Season.of(11) == Season.NONE);
        check("New Year is December and January", Season.of(12) == Season.NEW_YEAR && Season.of(1) == Season.NEW_YEAR && Season.of(2) == Season.NONE);
        boolean quiet = true;
        for (int month = 2; month <= 9; month++) quiet &= Season.of(month) == Season.NONE;
        check("the rest of the year is the ordinary look", quiet);
        check("the switch turns a season off", Season.decide(10, false, null) == Season.NONE && Season.decide(10, true, null) == Season.HALLOWEEN);
        check("an explicit request wins on any date", Season.decide(7, false, "newyear") == Season.NEW_YEAR && Season.decide(10, true, "none") == Season.NONE);
        // ULTRA for eX games: the size a game is drawn at, and what a session teaches.
        check("the standard size is the one tuned on a phone", ExScale.size(false, false)[0] == 800 && ExScale.size(false, false)[1] == 450
                && ExScale.size(true, false)[0] == 640 && ExScale.size(true, false)[1] == 360);
        check("one step lower keeps 16:9", ExScale.size(false, true)[0] * 9 == ExScale.size(false, true)[1] * 16
                && ExScale.size(true, true)[0] * 9 == ExScale.size(true, true)[1] * 16 && ExScale.size(true, true)[0] < 640);
        check("without ULTRA nothing is lowered", !ExScale.lowered(false, ExScale.FASTER, ExScale.LOWER));
        check("auto lowers only a game seen to be slow", !ExScale.lowered(true, ExScale.AUTO, ExScale.UNDECIDED) && ExScale.lowered(true, ExScale.AUTO, ExScale.LOWER));
        check("standard never lowers, faster always does", !ExScale.lowered(true, ExScale.STANDARD, ExScale.LOWER) && ExScale.lowered(true, ExScale.FASTER, ExScale.UNDECIDED));
        check("a game the smaller size broke is left alone in every mode", !ExScale.lowered(true, ExScale.FASTER, ExScale.KEEP) && !ExScale.lowered(true, ExScale.AUTO, ExScale.KEEP));
        check("a slow session asks for the smaller size next time", ExScale.learn(ExScale.UNDECIDED, false, 17, 300, 5000) == ExScale.LOWER);
        check("a smooth session changes nothing", ExScale.learn(ExScale.UNDECIDED, false, 48, 300, 14000) == ExScale.UNDECIDED);
        check("a short session teaches nothing", ExScale.learn(ExScale.UNDECIDED, false, 9, 40, 300) == ExScale.UNDECIDED
                && ExScale.learn(ExScale.LOWER, false, 0, 500, 0) == ExScale.LOWER);
        check("faster frames at the smaller size do not undo it", ExScale.learn(ExScale.LOWER, true, 55, 300, 16000) == ExScale.LOWER);
        check("a lowered game that showed nothing gets its size back for good", ExScale.learn(ExScale.LOWER, true, 0, 12, 0) == ExScale.KEEP
                && ExScale.learn(ExScale.KEEP, false, 10, 300, 3000) == ExScale.KEEP);
        check("closing a working lowered game quickly is not a failure", ExScale.learn(ExScale.LOWER, true, 0, 12, 400) == ExScale.LOWER);
        check("the median ignores idle seconds and needs enough of them", ExScale.median(new int[] { 0, 30, 0, 31, 29, 30, 30, 28, 30, 32, 30, 33, 0 }, 13) == 30
                && ExScale.median(new int[] { 20, 21, 22 }, 3) == 0);
        check("an unknown setting reads as automatic", ExScale.normalize("?").equals(ExScale.AUTO) && ExScale.normalize(null).equals(ExScale.AUTO));

        check("a season recolours the action and leaves unknown roles alone",
                "#FF9A3D".equals(Season.color(Season.HALLOWEEN, "ACTION")) && "#FFD36B".equals(Season.color(Season.NEW_YEAR, "ACTION"))
                        && Season.color(Season.NONE, "ACTION") == null && Season.color(Season.HALLOWEEN, "TEXT") == null);

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
