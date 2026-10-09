package ru.dustore.launcher;

import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Распаковщик tar.gz, написанный здесь, а не взятый готовым.
 *
 * Причина конкретная: образ Linux держится на symlink'ах и hardlink'ах. `/bin/ls` — это
 * ссылка, `/usr/bin/*` — сотни ссылок на один файл. Любой распаковщик, который не умеет
 * их создавать, разворачивает систему, где всё вроде на месте, но ни одна команда не
 * работает. Android-овский tar именно так и повёл себя при первой попытке: «Permission
 * denied» на каждом hardlink'е — и получился каталог-пустышка.
 *
 * Поэтому формат разбирается вручную: заголовок ustar на 512 байт, тип записи в 156-м,
 * а ссылки создаются через Os.symlink и Os.link — те самые системные вызовы, которых у
 * java.io нет.
 */
final class Tar {

    private static final int BLOCK = 512;

    private Tar() {
    }

    /** Возвращает null при успехе, иначе — человеческую причину отказа. */
    static String extract(File archive, File target, Ex.Progress progress) {
        long files = 0;

        try (InputStream raw = new java.io.FileInputStream(archive);
             GZIPInputStream in = new GZIPInputStream(raw, 65536)) {

            byte[] header = new byte[BLOCK];
            byte[] buffer = new byte[65536];
            String root = target.getCanonicalPath();

            while (true) {
                ExWork.check();
                if (!readFully(in, header, BLOCK)) {
                    break;
                }

                // Два нулевых блока подряд — конец архива.
                if (isEmpty(header)) {
                    break;
                }

                String name = text(header, 0, 100);

                // Длинные имена GNU-tar лежат в отдельной записи типа 'L'.
                char type = (char) header[156];

                if (type == 'L') {
                    long length = octal(header, 124, 12);
                    byte[] longName = new byte[(int) length];
                    readFully(in, longName, longName.length);
                    skipPadding(in, length);

                    if (!readFully(in, header, BLOCK)) {
                        break;
                    }

                    name = new String(longName, "UTF-8").trim();
                    type = (char) header[156];
                }

                String prefix = text(header, 345, 155);

                if (prefix.length() > 0) {
                    name = prefix + "/" + name;
                }

                long size = octal(header, 124, 12);
                int mode = (int) octal(header, 100, 8);
                String link = text(header, 157, 100);

                // Имя, оканчивающееся на «/», — это папка, каким бы ни был тип записи.
                // Ubuntu-архив начинается с записи «./», и попытка открыть её как файл
                // упирается в EISDIR: на этом установка и легла в первый раз.
                if (name.endsWith("/")) {
                    type = '5';
                    name = name.substring(0, name.length() - 1);
                }

                if (name.isEmpty() || name.equals(".")) {
                    skip(in, size);
                    skipPadding(in, size);
                    continue;
                }

                File file = new File(target, name);

                // Запись, которая целится за пределы корня, отбрасывается целиком —
                // архив приезжает по кабелю, но проверять его всё равно обязаны мы.
                if (!file.getCanonicalPath().startsWith(root)) {
                    skip(in, size);
                    skipPadding(in, size);
                    continue;
                }

                switch (type) {
                    case '5':
                        file.mkdirs();
                        break;

                    case '2':
                        parent(file);
                        file.delete();
                        Os.symlink(link, file.getAbsolutePath());
                        break;

                    case '1':
                        parent(file);
                        file.delete();

                        try {
                            Os.link(new File(target, link).getAbsolutePath(),
                                    file.getAbsolutePath());
                        } catch (Exception hard) {
                            // Если файловая система против жёсткой ссылки — мягкая тоже
                            // годится: содержимое то же, а системе разницы нет.
                            Os.symlink("/" + link, file.getAbsolutePath());
                        }

                        break;

                    case '0':
                    case '\0':
                        parent(file);

                        try (FileOutputStream out = new FileOutputStream(file)) {
                            long left = size;

                            while (left > 0) {
                                ExWork.check();
                                int want = (int) Math.min(buffer.length, left);
                                int read = in.read(buffer, 0, want);

                                if (read <= 0) {
                                    break;
                                }

                                out.write(buffer, 0, read);
                                left -= read;
                            }
                        }

                        // Права важны: без бита исполнения bash не запустится, и система
                        // окажется набором файлов, а не системой.
                        Os.chmod(file.getAbsolutePath(), mode == 0 ? 0644 : mode);

                        files++;

                        if (progress != null && files % 400 == 0) {
                            progress.say("Распаковано файлов: " + files);
                        }

                        skipPadding(in, size);
                        continue;

                    default:
                        // Сюда попадают PAX-заголовки (тип 'x'), которыми начинается
                        // каждая запись в архивах Ubuntu. Их содержимое нам не нужно, но
                        // выравнивание досчитать обязаны: пропустив 59 байт вместо 512,
                        // поток съезжает — и дальше весь архив читается как мусор. Ровно
                        // на этом установка обрывалась после семнадцати файлов.
                        skip(in, size);
                        skipPadding(in, size);
                        continue;
                }

                skip(in, size);
                skipPadding(in, size);
            }

            if (progress != null) {
                progress.say("Распаковано файлов: " + files);
            }

            return files > 100 ? null : "Образ распаковался пустым (" + files + " файлов).";
        } catch (Exception ex) {
            return "Не удалось развернуть систему: " + ex;
        }
    }

    private static void parent(File file) {
        File parent = file.getParentFile();

        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    private static boolean isEmpty(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }

        return true;
    }

    private static String text(byte[] block, int offset, int length) {
        int end = offset;

        while (end < offset + length && block[end] != 0) {
            end++;
        }

        try {
            return new String(block, offset, end - offset, "UTF-8");
        } catch (Exception ex) {
            return "";
        }
    }

    private static long octal(byte[] block, int offset, int length) {
        long value = 0;

        for (int i = offset; i < offset + length; i++) {
            int digit = block[i];

            if (digit == 0 || digit == ' ') {
                continue;
            }

            value = value * 8 + (digit - '0');
        }

        return value;
    }

    private static boolean readFully(InputStream in, byte[] buffer, int length) throws Exception {
        int read = 0;

        while (read < length) {
            int got = in.read(buffer, read, length - read);

            if (got <= 0) {
                return read > 0 && read == length;
            }

            read += got;
        }

        return true;
    }

    private static void skip(InputStream in, long size) throws Exception {
        long left = size;

        while (left > 0) {
            long skipped = in.skip(left);

            if (skipped <= 0) {
                if (in.read() < 0) {
                    return;
                }

                left--;
                continue;
            }

            left -= skipped;
        }
    }

    /** Записи выровнены по 512 байт — хвост надо проглотить. */
    private static void skipPadding(InputStream in, long size) throws Exception {
        long rest = size % BLOCK;

        if (rest != 0) {
            skip(in, BLOCK - rest);
        }
    }
}
