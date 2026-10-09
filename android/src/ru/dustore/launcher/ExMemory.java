package ru.dustore.launcher;

import android.app.ActivityManager;
import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Memory checks shared by game launch and the automatic compatibility probe. */
final class ExMemory {
    private static final long VERY_LOW_RAM_KIB = 512L * 1024L;

    /** MemAvailable, SwapFree and SwapTotal, in KiB. */
    static long[] read() {
        long available = -1, swapFree = 0, swapTotal = 0;
        try (BufferedReader lines = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = lines.readLine()) != null) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length < 2) continue;
                if (fields[0].equals("MemAvailable:")) available = Long.parseLong(fields[1]);
                else if (fields[0].equals("SwapFree:")) swapFree = Long.parseLong(fields[1]);
                else if (fields[0].equals("SwapTotal:")) swapTotal = Long.parseLong(fields[1]);
            }
        } catch (Exception unreadable) {
            return null;
        }
        return available < 0 ? null : new long[] { available, swapFree, swapTotal };
    }

    /** Warn only for actual RAM pressure, not a fixed percentage of compressed swap. */
    static boolean warnBeforeLaunch(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        boolean systemLow = false;
        if (manager != null) {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            systemLow = info.lowMemory;
        }
        return shouldWarn(read(), systemLow);
    }

    static boolean shouldWarn(long[] memory, boolean systemLow) {
        return systemLow || lowRam(memory);
    }

    static boolean lowRam(long[] memory) {
        return memory != null && memory[0] >= 0 && memory[0] < VERY_LOW_RAM_KIB;
    }

    static String snapshot(Context context, long[] memory) {
        StringBuilder line = new StringBuilder("memory: ");
        if (memory == null) line.append("meminfo unavailable");
        else line.append("MemAvailable=").append(memory[0])
                .append(" KiB, SwapFree=").append(memory[1])
                .append('/').append(memory[2]).append(" KiB");
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            line.append(", Android avail=").append(info.availMem / 1024)
                    .append(" KiB, threshold=").append(info.threshold / 1024)
                    .append(" KiB, lowMemory=").append(info.lowMemory);
        }
        try (BufferedReader pressure = new BufferedReader(new FileReader("/proc/pressure/memory"))) {
            String some = pressure.readLine();
            String full = pressure.readLine();
            if (some != null) line.append(", PSI ").append(some.trim());
            if (full != null) line.append("; ").append(full.trim());
        } catch (Exception ignored) {
            line.append(", PSI unavailable");
        }
        line.append(", ").append(ownProcesses());
        return line.toString();
    }

    /** All PRoot/Wine processes have our UID, including children reparented by Wine. */
    private static String ownProcesses() {
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return "process RSS unavailable";
        int uid = android.os.Process.myUid();
        List<ProcessUsage> processes = new ArrayList<>();
        long total = 0;
        for (File entry : entries) {
            int pid;
            try { pid = Integer.parseInt(entry.getName()); }
            catch (NumberFormatException notPid) { continue; }
            int owner = -1;
            long rss = 0;
            String name = "?";
            try (BufferedReader status = new BufferedReader(
                    new FileReader(new File(entry, "status")))) {
                String field;
                while ((field = status.readLine()) != null) {
                    if (field.startsWith("Name:")) name = field.substring(5).trim();
                    else if (field.startsWith("Uid:"))
                        owner = Integer.parseInt(field.substring(4).trim().split("\\s+")[0]);
                    else if (field.startsWith("VmRSS:"))
                        rss = Long.parseLong(field.substring(6).trim().split("\\s+")[0]);
                }
            } catch (Exception gone) { continue; }
            if (owner == uid) {
                processes.add(new ProcessUsage(pid, name, rss));
                total += rss;
            }
        }
        processes.sort(Comparator.comparingLong((ProcessUsage p) -> p.rss).reversed());
        StringBuilder result = new StringBuilder("own processes=").append(processes.size())
                .append(", total RSS=").append(total).append(" KiB [");
        for (int i = 0; i < Math.min(8, processes.size()); i++) {
            ProcessUsage p = processes.get(i);
            if (i > 0) result.append(", ");
            result.append(p.pid).append(':').append(p.name).append('=').append(p.rss);
        }
        return result.append(']').toString();
    }

    private static final class ProcessUsage {
        final int pid;
        final String name;
        final long rss;

        ProcessUsage(int pid, String name, long rss) {
            this.pid = pid;
            this.name = name;
            this.rss = rss;
        }
    }

    private ExMemory() { }
}
