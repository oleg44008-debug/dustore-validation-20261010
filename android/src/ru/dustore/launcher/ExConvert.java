package ru.dustore.launcher;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.StatFs;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * «Эмулировать»: конвейер, который из скачанной сборки для Windows делает игру на телефоне.
 *
 * Семь шагов, и каждый виден человеку по имени: система, распаковка, разбор, переукладка,
 * иконка, проба, APK. Проба — честность: игра запускается без экрана на полминуты, и если
 * первого кадра не было, об этом сказано на карточке до того, как человек поставит APK.
 *
 * Итог — стаб-APK со значком игры: он весит десятки килобайт и по нажатию открывает игру
 * внутри DustoreX. Файлы игры при этом лежат в одном месте, во внутренней памяти
 * приложения, — не вдвое, как было бы с самодостаточным APK.
 */
final class ExConvert {

    static final int STEPS = 7;

    interface Report {
        void step(int index, String text);

        void log(String line);
    }

    /** Полный конвейер. Возвращает null при успехе или причину отказа. Долго — свой поток. */
    static String run(Context context, Library library, Library.Entry entry, Report report) {
        Object session = new Object();
        if (!ExSession.acquire(session)) return "Закройте текущую игру eX перед подготовкой другой.";
        try { return runOwnedTask(context, library, entry, report, session); }
        finally { ExSession.release(session); }
    }

    static String runOwnedTask(Context context, Library library, Library.Entry entry, Report report, Object session) {
        ExWork.check();
        String quota = Prime.quotaRefusal(context);
        if (quota != null) return quota;
        try {
            Prime.waitFreeQueue(line -> { report.step(0, line); report.log(line); });
            ExWork.check();
            long started = System.currentTimeMillis();
            String problem = runOwned(context, library, entry, report, session, started);
            if (problem == null && !Prime.ON) {
                ExWork.check();
                Prime.recordSuccess(context);
                report.log(Prime.remaining(context));
            }
            return problem;
        }
        catch (Exception ex) { return "Подготовка игры не завершена: " + ex.getMessage(); }
    }

    private static String runOwned(Context context, Library library, Library.Entry entry,
            Report report, Object session, long started) {
        Ex.log(context, "=== эмуляция «" + entry.title + "» " + new java.util.Date() + " ===");

        Ex.Progress say = line -> {
            Ex.log(context, line);
            report.log(line);
        };

        // 1. Система.
        report.step(1, "Система eX");

        if (!ExNative.ready(context)) {
            String problem = ExBuild.installNativeLogged(context, say);

            if (problem != null) {
                return "ARM64 система eX не собралась: " + problem;
            }
        } else {
            say.say("ARM64 система eX собрана: " + Ex.gpuLine(context));
        }

        String dependencies = ExBuild.ensureDisplayParts(context, say);
        if (dependencies != null) return dependencies;

        // 2. Распаковка.
        report.step(2, "Распаковка");

        File source = entry.folder == null ? null : new File(entry.folder);
        File zip = library.fileOf(entry);

        if ((source == null || !source.isDirectory()) && (zip == null || !zip.exists())) {
            return "Файл игры не найден — скачайте её заново.";
        }

        String id = entry.exId != null && !entry.exId.isEmpty()
                ? entry.exId : ExGame.idFor(entry.title,
                        entry.gameKey != null ? entry.gameKey : entry.title + "|" + entry.fileName);
        File folder = new File(ExGame.root(context), id);

        long need = (zip != null && zip.exists() ? zip.length() * 2 : 0)
                + (source != null && source.isDirectory() ? sizeOf(source) : 0)
                + 300L * 1024 * 1024;
        long free = new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();

        if (free < need) {
            return "Не хватает места: нужно ещё около " + ((need - free) / 1048576)
                    + " МБ во внутренней памяти.";
        }

        ExGame previous = ExGame.load(folder);
        String sourceHash = zip != null && zip.isFile() ? fingerprint(zip) : previous.sourceHash;
        if (zip != null && zip.isFile() && sourceHash == null) return "Не удалось прочитать файл игры.";
        if (!new File(folder, "ex.json").exists() || (sourceHash != null && !sourceHash.equals(previous.sourceHash))) {
            File staging = new File(folder.getParentFile(), ".preparing-" + id);
            File backup = new File(folder.getParentFile(), ".previous-" + id);
            // Recover an interrupted directory swap before doing any extraction.
            if (!folder.exists() && backup.exists() && !backup.renameTo(folder))
                return "Не удалось восстановить предыдущую сборку.";
            Unpack.deleteAll(staging);
            staging.mkdirs();
            try {
                File existing = folder.isDirectory() ? folder : source;
                if (existing != null && existing.isDirectory() && !copyTree(existing, staging))
                    return "Не удалось сохранить существующие файлы игры перед обновлением.";
                if (zip != null && zip.isFile()) {
                    say.say("Распаковываем " + (zip.length() / 1048576) + " МБ…");
                    if (Unpack.overlay(zip, staging) == null)
                        return "Не удалось распаковать сборку. Поддерживаются ZIP и отдельные EXE; проверьте файл и свободное место.";
                }
                if (ExRecipe.findExe(staging) == null) return "В сборке не найден Windows EXE.";
                Unpack.deleteAll(backup);
                if (folder.exists() && !folder.renameTo(backup)) return "Не удалось обновить занятую папку игры.";
                if (!staging.renameTo(folder)) {
                    backup.renameTo(folder);
                    return "Не удалось сохранить новую сборку. Предыдущая сохранена.";
                }
            } finally { Unpack.deleteAll(staging); }
        } else {
            say.say("Уже распакована.");
        }

        ExGame game = ExGame.load(folder);
        game.title = entry.title == null ? game.title : entry.title;
        game.id = id;
        game.sourceHash = sourceHash;

        // 3. Разбор.
        report.step(3, "Разбор сборки");

        ExRecipe recipe = ExRecipe.read(folder);
        game.recipe = recipe;

        if (recipe.exe == null) {
            game.save();
            return "В сборке не нашлось файла .exe — это не игра для Windows?";
        }

        say.say("файл запуска: " + recipe.exe);
        say.say("движок: " + (recipe.engine == null ? "не опознан" : recipe.engine)
                + " · " + (recipe.wide ? "64" : "32") + " бит · " + recipe.api
                + " · " + recipe.tierName());

        if (!recipe.canRun()) {
            game.save();
            return recipe.expect;
        }

        entry.exId = id;
        entry.folder = folder.getAbsolutePath();
        library.save();

        // 4. Переукладка.
        report.step(4, "Переукладка под Android");
        ExPe.prepare(folder, say);
        game.state = ExGame.PREPARED;
        game.save();

        // 5. Иконка.
        report.step(5, "Иконка игры");

        try {
            byte[] png = ExIcon.extract(new File(folder, recipe.exe));

            if (png != null) {
                try (FileOutputStream out = new FileOutputStream(new File(folder, "icon.png"))) {
                    out.write(png);
                }

                say.say("иконка взята из .exe");
            } else {
                say.say("своей иконки у игры нет — будет значок eX");
            }
        } catch (Exception broken) {
            say.say("иконка не прочиталась: " + broken);
        }

        // 6. Проба.
        //
        // Только на переднем плане. Игра в пробе занимает полтора гигабайта, и если экран
        // погас, приложение в фоне становится первой жертвой нехватки памяти — вместе с
        // пробой гибнет вся эмуляция. Проверено на живом телефоне: убили на тридцатой
        // секунде. Свёрнутому приложению честнее пропустить пробу, чем упасть.
        report.step(6, "Пробный запуск");

        if (inForeground(context)) {
            game.probe = ExProbe.run(context, game, say, session);
        } else {
            game.probe = "пропущена: приложение было в фоне";
        }

        game.save();
        say.say("проба: " + game.probe);

        // Free's existing pace completes BEFORE publishing the successful result.
        // Cancellation must not publish a working APK and skip its success-only quota.
        if (!Prime.ON) {
            long bytes = zip != null && zip.isFile() ? zip.length() : source != null && source.isDirectory() ? sizeOf(source) : 0;
            Prime.holdFreePace(bytes, started, line -> { report.step(6, line); report.log(line); });
            ExWork.check();
        }
        // 7. APK. The short atomic finalization is no longer cancellable.
        report.step(7, "Сборка APK");
        ExWork.commit();

        try {
            File apk = ExApk.build(context, game);
            game.apk = apk;
            game.stubPackage = ExApk.packageFor(game);
            game.state = ExGame.APK;
            game.save();

            File shared = publish(context, apk, game.title);
            say.say("APK готов: " + (shared == null ? apk.getName() : shared.getName()));
        } catch (Exception broken) {
            game.save();
            Ex.log(context, "APK не собрался: " + broken);

            return "Игра готова и запускается из библиотеки, но APK не собрался: " + broken;
        }

        Ex.log(context, "=== эмуляция завершена ===");
        return null;
    }

    /**
     * Копия APK в общие «Загрузки» телефона — папка Download/DustoreX.
     *
     * Человек просил файл, который видно: в проводнике, в «Загрузках», чтобы отправить
     * другу. С Android 10 писать в общие папки напрямую нельзя, зато можно через
     * хранилище загрузок системы. Возвращает файл-копию или null.
     */
    static File publish(Context context, File apk, String title) {
        String name = safeName(title) + ".apk";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, Files.APK_TYPE);
                values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/DustoreX");

                Uri uri = context.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

                if (uri == null) {
                    return null;
                }

                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    copy(in, out);
                }

                return new File("/sdcard/Download/DustoreX/" + name);
            }

            File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "DustoreX");
            dir.mkdirs();
            File target = new File(dir, name);

            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = new FileOutputStream(target)) {
                copy(in, out);
            }

            return target;
        } catch (Exception broken) {
            return null;
        }
    }

    static String safeName(String title) {
        String clean = (title == null ? "game" : title).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return clean.isEmpty() ? "game" : clean;
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[1 << 16];
        int read;

        while ((read = in.read(buffer)) > 0) {
            ExWork.check();
            out.write(buffer, 0, read);
        }
    }

    /** Видно ли приложение прямо сейчас — по важности его же процесса. */
    static boolean inForeground(Context context) {
        try {
            android.app.ActivityManager manager = (android.app.ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);

            for (android.app.ActivityManager.RunningAppProcessInfo info
                    : manager.getRunningAppProcesses()) {
                if (info.pid == android.os.Process.myPid()) {
                    return info.importance
                            <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
            }
        } catch (Exception ignored) {
            // Не узнали — считаем, что в фоне: осторожнее.
        }

        return false;
    }

    static boolean copyFile(File from, File to) {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            copy(in, out);
            return true;
        } catch (Exception broken) {
            return false;
        }
    }

    private static long sizeOf(File folder) {
        ExWork.check();
        long sum = 0;
        File[] children = folder.listFiles();

        if (children != null) {
            for (File child : children) {
                if (Unpack.isLink(child)) continue;
                sum += child.isDirectory() ? sizeOf(child) : child.length();
            }
        }

        return sum;
    }

    static boolean copyTree(File from, File to) {
        ExWork.check();
        if (!to.exists() && !to.mkdirs()) {
            return false;
        }

        File[] children = from.listFiles();

        if (children == null) {
            return false;
        }

        for (File child : children) {
            if (Unpack.isLink(child)) continue;
            File mirror = new File(to, child.getName());

            if (child.isDirectory()) {
                if (!copyTree(child, mirror)) {
                    return false;
                }

                continue;
            }

            if (mirror.exists() && mirror.length() == child.length()) {
                continue;
            }

            try (InputStream in = new FileInputStream(child);
                 OutputStream out = new FileOutputStream(mirror)) {
                copy(in, out);
            } catch (Exception broken) {
                return false;
            }
        }

        return true;
    }

    private ExConvert() {
    }

    private static String fingerprint(File file) {
        try (InputStream input = new FileInputStream(file)) {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; int read;
            while ((read = input.read(buffer)) != -1) { ExWork.check(); sha.update(buffer, 0, read); }
            StringBuilder result = new StringBuilder();
            for (byte value : sha.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (Exception broken) { return null; }
    }
}
