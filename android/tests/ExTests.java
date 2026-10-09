package ru.dustore.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Проверки чистых частей eX на обычной JDK, без телефона.
 *
 *   ExTests recipe <папка игры> [...]            — рецепт на настоящих сборках с диска
 *   ExTests axml <stub.apk>                      — правка бинарного манифеста туда-обратно
 *   ExTests stub <stub.apk> <out.apk> <название> [icon.png] — сборка и подпись стаба
 *                                                  (дальше: apksigner verify --verbose out.apk)
 *   ExTests icon <игра.exe> <out.png>            — иконка из ресурсов PE
 *
 * Классы компилируются те же, что едут в APK, — не копия логики.
 */
public final class ExTests {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("recipe | axml | stub | icon | memory | performance");
            return;
        }

        switch (args[0]) {
            case "recipe":
                for (int i = 1; i < args.length; i++) {
                    recipe(new File(args[i]));
                }
                return;
            case "axml":
                axml(new File(args[1]));
                return;
            case "stub":
                stub(new File(args[1]), new File(args[2]), args[3],
                        args.length > 4 ? new File(args[4]) : null);
                return;
            case "icon":
                icon(new File(args[1]), new File(args[2]));
                return;
            case "memory":
                memory();
                return;
            case "performance":
                PerformanceTests.main(new String[0]);
                return;
            case "behavior":
                BehaviorTests.main(new String[0]);
                return;
            default:
                System.out.println("неизвестная проверка: " + args[0]);
                System.exit(2);
        }
    }

    private static void memory() {
        long swap = 8388604L;
        check("обычный запас памяти не вызывает предупреждение",
                !ExMemory.shouldWarn(new long[] { 3300000L, 6170000L, swap }, false));
        check("запас zRAM сам по себе не вызывает предупреждение",
                !ExMemory.shouldWarn(new long[] { 3300000L, 5400000L, swap }, false));
        check("сигнал Android о нехватке памяти вызывает предупреждение",
                ExMemory.shouldWarn(new long[] { 3300000L, 6170000L, swap }, true));
        check("свободный swap ниже 65% при достаточной RAM не вызывает предупреждение",
                !ExMemory.shouldWarn(new long[] { 2398104L, 5099372L, swap }, false));
        check("критически малая RAM определяется независимо от процента swap",
                ExMemory.lowRam(new long[] { 400000L, 6170000L, swap }));
        check("достаточная RAM не определяется как критическая",
                !ExMemory.lowRam(new long[] { 1800000L, 1000000L, swap }));
    }

    private static void recipe(File folder) {
        long started = System.currentTimeMillis();
        ExRecipe recipe = ExRecipe.read(folder);

        System.out.println("== " + folder);
        System.out.println("   exe:      " + recipe.exe);
        System.out.println("   движок:   " + recipe.engine);
        System.out.println("   64 бит:   " + recipe.wide);
        System.out.println("   графика:  " + recipe.api + " (" + recipe.source + ")");
        System.out.println("   перевод:  " + recipe.renderer + " · " + recipe.tierName());
        System.out.println("   античит:  " + recipe.antiCheat);
        System.out.println("   пад:      " + recipe.profile);
        System.out.println("   ключи:    " + recipe.args);
        System.out.println("   видели:   " + recipe.saw);
        System.out.println("   env:      " + recipe.env);
        System.out.println("   ожидание: " + recipe.expect);
        System.out.println("   json ок:  " + ExRecipe.fromJson(recipe.toJson()).api.equals(recipe.api));
        System.out.println("   " + (System.currentTimeMillis() - started) + " мс");
    }

    private static byte[] entry(File apk, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(apk))) {
            ZipEntry entry;

            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return ExApk.readAll(in);
                }
            }
        }

        throw new IllegalStateException("нет записи " + name);
    }

    private static void axml(File apk) throws Exception {
        byte[] manifest = entry(apk, "AndroidManifest.xml");
        List<String> before = ExAxml.strings(manifest);
        System.out.println("строк в манифесте: " + before.size());

        Map<String, String> replacements = new HashMap<>();
        replacements.put(ExApk.PLACEHOLDER_PACKAGE, "ru.exepc.exgame.kontur1a2b3c");
        replacements.put(ExApk.PLACEHOLDER_LABEL, "К.О.Н.Т.У.Р.: Начальная beta");

        byte[] patched = ExAxml.replaceStrings(manifest, replacements);
        List<String> after = ExAxml.strings(patched);

        check("столько же строк", before.size() == after.size());
        check("пакет подменён", after.contains("ru.exepc.exgame.kontur1a2b3c"));
        check("подпись подменена", after.contains("К.О.Н.Т.У.Р.: Начальная beta"));
        check("заглушек не осталось", !after.contains(ExApk.PLACEHOLDER_PACKAGE)
                && !after.contains(ExApk.PLACEHOLDER_LABEL));

        // Размер файла в заголовке должен совпасть с фактическим.
        int declared = (patched[4] & 0xFF) | ((patched[5] & 0xFF) << 8)
                | ((patched[6] & 0xFF) << 16) | ((patched[7] & 0xFF) << 24);
        check("размер файла в заголовке верный", declared == patched.length);

        for (int i = 0; i < before.size(); i++) {
            String was = before.get(i);
            String expected = replacements.containsKey(was) ? replacements.get(was) : was;

            if (!expected.equals(after.get(i))) {
                check("строка " + i + " на месте", false);
            }
        }

        System.out.println("AXML: ок");
    }

    private static void stub(File template, File out, String title, File icon) throws Exception {
        File raw = template.getParentFile();
        byte[] key = read(new File(raw, "exstub_key.bin"));
        byte[] cert = read(new File(raw, "exstub_cer.bin"));
        byte[] png = icon == null ? null : read(icon);

        ExGame game = new ExGame();
        game.id = ExGame.idFor(title);
        game.title = title;

        byte[] unsigned = ExApk.rebuild(read(template), ExApk.packageFor(game), title, png);
        byte[] signed = ExApk.signV2(unsigned, key, cert);

        try (FileOutputStream stream = new FileOutputStream(out)) {
            stream.write(signed);
        }

        System.out.println("пакет: " + ExApk.packageFor(game));
        System.out.println("подписано: " + signed.length + " байт → " + out);
        System.out.println("дальше: apksigner verify --verbose " + out);
    }

    private static void icon(File exe, File out) throws Exception {
        byte[] png = ExIcon.extract(exe);

        if (png == null) {
            System.out.println("иконки нет");
            System.exit(1);
        }

        try (FileOutputStream stream = new FileOutputStream(out)) {
            stream.write(png);
        }

        check("это PNG", (png[0] & 0xFF) == 0x89 && png[1] == 'P');
        System.out.println("иконка: " + png.length + " байт → " + out);
    }

    private static byte[] read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            return ExApk.readAll(in);
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  ✗ ") + what);

        if (!ok) {
            System.exit(1);
        }
    }
}
