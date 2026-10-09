package ru.dustore.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Иконка игры из её же .exe — чтобы значок на рабочем столе был значком игры, а не eX.
 *
 * Читаются ресурсы программы Windows своим кодом: раздел `.rsrc`, дерево ресурсов, группа
 * иконок (тип 14), лучшая по размеру запись, сама иконка (тип 3). Внутри либо готовый PNG
 * (так хранят большие иконки), либо точечный рисунок DIB — его переписываем в PNG сами,
 * тоже своим кодом: сжатие даёт java.util.zip, контрольные суммы — CRC32.
 */
final class ExIcon {

    /** PNG-байты крупнейшей иконки или null, если у файла её нет. */
    static byte[] extract(File exe) {
        try (RandomAccessFile in = new RandomAccessFile(exe, "r")) {
            byte[] head = read(in, 0, 4096);

            if (head.length < 0x40 || head[0] != 'M' || head[1] != 'Z') {
                return null;
            }

            int pe = le32(head, 0x3C);

            if (pe <= 0 || pe + 24 > head.length || head[pe] != 'P' || head[pe + 1] != 'E') {
                return null;
            }

            int sections = le16(head, pe + 6);
            int optionalSize = le16(head, pe + 20);
            int optional = pe + 24;
            boolean plus = le16(head, optional) == 0x20B;
            int directories = optional + (plus ? 112 : 96);

            // Каталог ресурсов — третий (индекс 2) в списке каталогов данных.
            long resourceRva = le32(head, directories + 16) & 0xFFFFFFFFL;
            long resourceSize = le32(head, directories + 20) & 0xFFFFFFFFL;

            if (resourceRva == 0 || resourceSize == 0) {
                return null;
            }

            int table = optional + optionalSize;
            List<long[]> map = new ArrayList<>();

            for (int i = 0; i < sections && i < 96 && table + 40 <= head.length; i++, table += 40) {
                long rva = le32(head, table + 12) & 0xFFFFFFFFL;
                long virtualSize = le32(head, table + 8) & 0xFFFFFFFFL;
                long fileOffset = le32(head, table + 20) & 0xFFFFFFFFL;
                long fileSize = le32(head, table + 16) & 0xFFFFFFFFL;
                map.add(new long[] { rva, Math.max(virtualSize, fileSize), fileOffset });
            }

            long base = toOffset(map, resourceRva);

            if (base < 0) {
                return null;
            }

            byte[] rsrc = read(in, base, (int) Math.min(resourceSize, 16L * 1024 * 1024));

            // Группа иконок: тип 14 → первая группа → первый язык → данные.
            int groupDir = findType(rsrc, 14);

            if (groupDir < 0) {
                return null;
            }

            int groupData = firstLeaf(rsrc, groupDir);

            if (groupData < 0) {
                return null;
            }

            long groupRva = le32(rsrc, groupData) & 0xFFFFFFFFL;
            int groupSize = le32(rsrc, groupData + 4);
            long groupAt = toOffset(map, groupRva);

            if (groupAt < 0 || groupSize < 6) {
                return null;
            }

            byte[] group = read(in, groupAt, Math.min(groupSize, 4096));
            int count = le16(group, 4);
            int bestId = -1;
            int bestScore = -1;

            for (int i = 0; i < count && 6 + 14 * (i + 1) <= group.length; i++) {
                int at = 6 + 14 * i;
                int width = group[at] & 0xFF;
                int bits = le16(group, at + 6);
                int id = le16(group, at + 12);

                if (width == 0) {
                    width = 256;
                }

                int score = width * 64 + Math.min(bits, 32);

                if (score > bestScore) {
                    bestScore = score;
                    bestId = id;
                }
            }

            if (bestId < 0) {
                return null;
            }

            int iconDir = findType(rsrc, 3);

            if (iconDir < 0) {
                return null;
            }

            int iconName = findId(rsrc, iconDir, bestId);

            if (iconName < 0) {
                return null;
            }

            int iconData = firstLeaf(rsrc, iconName);

            if (iconData < 0) {
                return null;
            }

            long iconRva = le32(rsrc, iconData) & 0xFFFFFFFFL;
            int iconSize = le32(rsrc, iconData + 4);
            long iconAt = toOffset(map, iconRva);

            if (iconAt < 0 || iconSize <= 0) {
                return null;
            }

            byte[] icon = read(in, iconAt, Math.min(iconSize, 4 << 20));

            if (icon.length > 8 && (icon[0] & 0xFF) == 0x89 && icon[1] == 'P' && icon[2] == 'N'
                    && icon[3] == 'G') {
                return icon;
            }

            return dibToPng(icon);
        } catch (Exception broken) {
            return null;
        }
    }

    // --------------------------------------------------------------- дерево ----

    /** Смещение каталога типа внутри `.rsrc`, или -1. */
    private static int findType(byte[] rsrc, int type) {
        return findEntry(rsrc, 0, type);
    }

    private static int findId(byte[] rsrc, int dir, int id) {
        return findEntry(rsrc, dir, id);
    }

    /** Запись каталога с таким номером; возвращает смещение подкаталога или -1. */
    private static int findEntry(byte[] rsrc, int dir, int id) {
        if (dir + 16 > rsrc.length) {
            return -1;
        }

        int named = le16(rsrc, dir + 12);
        int ids = le16(rsrc, dir + 14);
        int entry = dir + 16 + named * 8;

        for (int i = 0; i < ids && entry + 8 <= rsrc.length; i++, entry += 8) {
            int name = le32(rsrc, entry);
            int offset = le32(rsrc, entry + 4);

            if (name == id) {
                return offset & 0x7FFFFFFF;
            }
        }

        return -1;
    }

    /** Первая запись-лист под каталогом: каталог имени → каталог языка → данные. */
    private static int firstLeaf(byte[] rsrc, int dir) {
        int at = dir;

        for (int depth = 0; depth < 4; depth++) {
            if (at + 16 > rsrc.length) {
                return -1;
            }

            int named = le16(rsrc, at + 12);
            int ids = le16(rsrc, at + 14);

            if (named + ids == 0) {
                return -1;
            }

            int offset = le32(rsrc, at + 16 + 4);

            if ((offset & 0x80000000) == 0) {
                return offset + 16 > rsrc.length ? -1 : offset;
            }

            at = offset & 0x7FFFFFFF;
        }

        return -1;
    }

    // ------------------------------------------------------------------ DIB ----

    /** Точечный рисунок иконки (32 или 24 бита) → PNG с прозрачностью. */
    private static byte[] dibToPng(byte[] dib) {
        if (dib.length < 40 || le32(dib, 0) != 40) {
            return null;
        }

        int width = le32(dib, 4);
        int height = le32(dib, 8) / 2;   // половина высоты — маска
        int bits = le16(dib, 14);
        int compression = le32(dib, 16);

        if (width <= 0 || height <= 0 || width > 512 || height > 512 || compression != 0) {
            return null;
        }

        if (bits != 32 && bits != 24) {
            return null;
        }

        int rowBytes = ((width * bits + 31) / 32) * 4;
        int pixelsAt = 40;
        int maskRow = ((width + 31) / 32) * 4;
        int maskAt = pixelsAt + rowBytes * height;

        byte[] rgba = new byte[width * height * 4];

        for (int y = 0; y < height; y++) {
            int source = pixelsAt + (height - 1 - y) * rowBytes;
            int mask = maskAt + (height - 1 - y) * maskRow;

            for (int x = 0; x < width; x++) {
                int at = source + x * (bits / 8);

                if (at + bits / 8 > dib.length) {
                    return null;
                }

                int b = dib[at] & 0xFF;
                int g = dib[at + 1] & 0xFF;
                int r = dib[at + 2] & 0xFF;
                int a = bits == 32 ? dib[at + 3] & 0xFF : 255;

                // Маска говорит «прозрачно» битом 1; у 32-битных иконок без настоящего
                // альфа-канала он единственный источник прозрачности.
                if (mask + x / 8 < dib.length) {
                    boolean hidden = (dib[mask + x / 8] & (0x80 >> (x % 8))) != 0;

                    if (hidden) {
                        a = 0;
                    }
                }

                int out = (y * width + x) * 4;
                rgba[out] = (byte) r;
                rgba[out + 1] = (byte) g;
                rgba[out + 2] = (byte) b;
                rgba[out + 3] = (byte) a;
            }
        }

        // 32-битная иконка целиком с нулевым альфа — старый формат без прозрачности.
        if (bits == 32) {
            boolean anyAlpha = false;

            for (int i = 3; i < rgba.length; i += 4) {
                if (rgba[i] != 0) {
                    anyAlpha = true;
                    break;
                }
            }

            if (!anyAlpha) {
                for (int i = 3; i < rgba.length; i += 4) {
                    rgba[i] = (byte) 255;
                }
            }
        }

        return png(width, height, rgba);
    }

    /** PNG из RGBA: заголовок, сжатые строки без фильтра, конец. */
    static byte[] png(int width, int height, byte[] rgba) {
        try {
            byte[] raw = new byte[(width * 4 + 1) * height];

            for (int y = 0; y < height; y++) {
                raw[y * (width * 4 + 1)] = 0;
                System.arraycopy(rgba, y * width * 4, raw, y * (width * 4 + 1) + 1, width * 4);
            }

            Deflater deflater = new Deflater(9);
            deflater.setInput(raw);
            deflater.finish();

            ByteArrayOutputStream zipped = new ByteArrayOutputStream();
            byte[] buffer = new byte[1 << 16];

            while (!deflater.finished()) {
                int got = deflater.deflate(buffer);
                zipped.write(buffer, 0, got);
            }

            deflater.end();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10 });

            byte[] ihdr = new byte[13];
            be32(ihdr, 0, width);
            be32(ihdr, 4, height);
            ihdr[8] = 8;    // бит на канал
            ihdr[9] = 6;    // RGBA
            chunk(out, "IHDR", ihdr);
            chunk(out, "IDAT", zipped.toByteArray());
            chunk(out, "IEND", new byte[0]);

            return out.toByteArray();
        } catch (Exception broken) {
            return null;
        }
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws Exception {
        byte[] length = new byte[4];
        be32(length, 0, data.length);
        out.write(length);

        byte[] name = type.getBytes("US-ASCII");
        out.write(name);
        out.write(data);

        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);

        byte[] sum = new byte[4];
        be32(sum, 0, (int) crc.getValue());
        out.write(sum);
    }

    // --------------------------------------------------------------- мелочи ----

    private static long toOffset(List<long[]> sections, long rva) {
        for (long[] section : sections) {
            if (rva >= section[0] && rva < section[0] + section[1]) {
                return section[2] + (rva - section[0]);
            }
        }

        return -1;
    }

    private static byte[] read(RandomAccessFile in, long offset, int count) throws Exception {
        if (offset < 0 || offset >= in.length()) {
            return new byte[0];
        }

        int size = (int) Math.min(count, in.length() - offset);
        byte[] data = new byte[size];
        in.seek(offset);
        in.readFully(data);
        return data;
    }

    private static int le32(byte[] data, int at) {
        if (at < 0 || at + 4 > data.length) {
            return 0;
        }

        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8)
                | ((data[at + 2] & 0xFF) << 16) | ((data[at + 3] & 0xFF) << 24);
    }

    private static int le16(byte[] data, int at) {
        if (at < 0 || at + 2 > data.length) {
            return 0;
        }

        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
    }

    private static void be32(byte[] data, int at, int value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
    }

    private ExIcon() {
    }
}
