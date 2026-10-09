package ru.dustore.launcher;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

/** Runs on the attached Android device with app_process; uses production Unpack. */
public final class UnpackTest {
    private static int passed;
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        System.out.println("PASS " + name); passed++;
    }
    private static File zip(File root, String filename, Charset charset, String... names) throws Exception {
        File file = new File(root, filename);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file), charset)) {
            for (String name : names) { zip.putNextEntry(new ZipEntry(name)); zip.write("payload".getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        }
        return file;
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]); root.mkdirs();
        if (!root.getCanonicalPath().equals("/data/local/tmp/dustore-unpack-tests")) throw new IllegalArgumentException("Test path");
        File target = new File(root, "target");
        File archive = zip(root, "cp866.zip", Charset.forName("CP866"), "Игра/Запуск.exe");
        check(Unpack.kindOf(archive) == Unpack.Kind.PC && Unpack.all(archive, target) != null
                && new File(target, "Игра/Запуск.exe").isFile(), "CP866 filenames");
        File apk = zip(root, "android.bin", StandardCharsets.UTF_8, "assets/index.html", "AndroidManifest.xml");
        check(Unpack.kindOf(apk) == Unpack.Kind.APP, "APK has priority over bundled HTML");
        File dll = zip(root, "dll.zip", StandardCharsets.UTF_8, "library.dll");
        check(Unpack.kindOf(dll) == Unpack.Kind.UNKNOWN, "DLL alone is not a Windows game");
        File traversal = zip(root, "traversal.zip", StandardCharsets.UTF_8, "../escaped");
        check(Unpack.all(traversal, target) == null && !new File(root, "escaped").exists(), "ZIP traversal rejected");
        File backslash = zip(root, "backslash.zip", StandardCharsets.UTF_8, "..\\escaped");
        check(Unpack.all(backslash, target) == null && !new File(root, "escaped").exists(), "Windows traversal rejected");
        File exe = new File(root, "windows.bin");
        byte[] pe = new byte[128]; pe[0]=77; pe[1]=90; pe[60]=64; pe[64]=80; pe[65]=69;
        try (FileOutputStream out = new FileOutputStream(exe)) { out.write(pe); }
        check(Unpack.kindOf(exe) == Unpack.Kind.PC && Unpack.all(exe, target) != null
                && new File(target, "windows.bin.exe").isFile(), "standalone EXE");
        File save = new File(target, "save.dat");
        try (FileOutputStream out = new FileOutputStream(save)) { out.write(123); }
        check(Unpack.overlay(archive, target) != null && save.isFile(), "update preserves other files");
        File outside = new File(root, "outside"); outside.mkdirs();
        File marker = new File(outside, "marker"); marker.createNewFile();
        android.system.Os.symlink(outside.getAbsolutePath(), new File(target, "link").getAbsolutePath());
        Unpack.deleteAll(target);
        check(marker.isFile(), "cleanup does not follow symlinks");
        File web = zip(root, "web.zip", StandardCharsets.UTF_8, "game/assets/docs/index.html", "game/index.html");
        check(Unpack.web(web, target).getCanonicalPath().equals(new File(target, "game").getCanonicalPath()), "top-level web entry");
        System.out.println("TOTAL " + passed + " passed");
        Unpack.deleteAll(root);
    }
}
