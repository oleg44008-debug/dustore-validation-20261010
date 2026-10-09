package ru.dustore.launcher;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.zip.*;

/** Identify content before offering installation, a web player or eX. */
final class Unpack {
    enum Kind { APP, WEB, PC, UNKNOWN }
    private static final int MAX_FILES = 100000;
    private static final long MAX_BYTES = 32L * 1024 * 1024 * 1024;

    private static ZipFile open(File file) throws IOException {
        // The UTF-8 flag takes precedence; CP866 supports older Windows archives.
        return new ZipFile(file, Charset.forName("CP866"));
    }

    private static boolean pe(File file) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 64 || in.readUnsignedShort() != 0x4d5a) return false;
            in.seek(0x3c);
            long offset = ((long) Integer.reverseBytes(in.readInt())) & 0xffffffffL;
            if (offset + 4 > in.length()) return false;
            in.seek(offset);
            return in.readInt() == 0x50450000;
        } catch (Exception ignored) { return false; }
    }

    static Kind kindOf(File file) {
        if (file == null || !file.isFile()) return Kind.UNKNOWN;
        if (pe(file)) return Kind.PC;
        try (ZipFile zip = open(file)) {
            if (zip.getEntry("AndroidManifest.xml") != null) return Kind.APP;
            boolean web = false, pc = false;
            int count = 0;
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                if (++count > MAX_FILES) return Kind.UNKNOWN;
                String name = entries.nextElement().getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (name.equals("index.html") || name.endsWith("/index.html")) web = true;
                if (name.endsWith(".exe")) pc = true;
            }
            return web ? Kind.WEB : pc ? Kind.PC : Kind.UNKNOWN;
        } catch (Exception ignored) { return Kind.UNKNOWN; }
    }

    static File web(File archive, File target) {
        if (all(archive, target) == null) return null;
        File index = findIndex(target, 0);
        return index == null ? null : index.getParentFile();
    }
    private static File findIndex(File folder, int depth) {
        if (depth > 64) return null;
        File[] children = folder.listFiles();
        if (children == null) return null;
        for (File child : children) if (child.isFile() && child.getName().equalsIgnoreCase("index.html")) return child;
        File best = null;
        for (File child : children) if (child.isDirectory()) {
            File candidate = findIndex(child, depth + 1);
            if (candidate != null && (best == null || candidate.getPath().split("/").length < best.getPath().split("/").length)) best = candidate;
        }
        return best;
    }
    static File all(File archive, File target) {
        deleteAll(target);
        if (!target.mkdirs()) return null;
        return overlay(archive, target);
    }
    /** Extract into staging; publish only after success. Existing save files survive updates. */
    static File overlay(File archive, File target) {
        try {
            if (pe(archive)) {
                String name = archive.getName();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".exe")) name += ".exe";
                try (InputStream input = new FileInputStream(archive)) {
                    copy(input, new File(target, name), Math.min(MAX_BYTES, target.getUsableSpace() - 64 * 1048576));
                }
                return target;
            }
            String root = target.getCanonicalPath() + File.separator;
            long limit = Math.min(MAX_BYTES, target.getUsableSpace() - 64 * 1048576), total = 0;
            int count = 0;
            try (ZipFile zip = open(archive)) {
                for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                    ZipEntry entry = entries.nextElement();
                    if (++count > MAX_FILES) throw new IOException("Слишком много файлов");
                    String name = entry.getName().replace('\\', '/');
                    if (name.startsWith("/") || name.indexOf('\0') >= 0 || name.matches("^[A-Za-z]:.*")) throw new IOException("Недопустимый путь");
                    File file = new File(target, name);
                    if (!file.getCanonicalPath().startsWith(root)) throw new IOException("Путь выходит из папки игры");
                    if (entry.isDirectory()) { if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Нет папки"); continue; }
                    if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) throw new IOException("Нет папки");
                    if (entry.getSize() > limit - total) throw new IOException("Недостаточно места");
                    try (CheckedInputStream input = new CheckedInputStream(zip.getInputStream(entry), new CRC32())) {
                        long written = copy(input, file, limit - total);
                        if ((entry.getSize() >= 0 && written != entry.getSize())
                                || (entry.getCrc() >= 0 && input.getChecksum().getValue() != entry.getCrc()))
                            throw new IOException("Повреждённый файл в ZIP");
                        total += written;
                    }
                }
            }
            return count == 0 ? null : target;
        } catch (Exception broken) { return null; }
    }
    private static long copy(InputStream input, File file, long limit) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        try (FileOutputStream out = new FileOutputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                ExWork.check();
                total += read;
                if (total > limit) throw new IOException("Распакованная игра не помещается в память");
                out.write(buffer, 0, read);
            }
        }
        return total;
    }
    static void deleteAll(File file) {
        if (file == null) return;
        if (isLink(file)) { file.delete(); return; }
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteAll(child);
        file.delete();
    }
    private Unpack() {}
    static boolean isLink(File file) {
        try { return android.system.OsConstants.S_ISLNK(android.system.Os.lstat(file.getAbsolutePath()).st_mode); }
        catch (Exception missing) { return false; }
    }
}
