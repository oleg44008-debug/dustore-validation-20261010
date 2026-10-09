package ru.dustore.launcher;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Переукладчик программ Windows — то, без чего игра на телефоне не запускается вовсе.
 *
 * Android запрещает исполнять код, который сначала отобразили из файла, потом в него
 * написали. Проверено прямо на телефоне: чистое отображение файла исполнять можно,
 * анонимную память — можно, а вот отображение файла после записи в него — нельзя, ядро
 * отвечает отказом. Слой Windows делает ровно запрещённое: раскладывает разделы программы
 * из файла, дописывает в них связи между кусками и только потом просит право исполнять.
 * Наружу это выглядело как «игра закрылась, не показав картинки».
 *
 * Обойти это можно, не трогая ни строчки чужого кода. У слоя Windows есть честная запасная
 * дорога: если разделы программы лежат в файле по смещениям, которые не делятся на размер
 * страницы памяти, отобразить их невозможно в принципе — и он читает их в обычную
 * анонимную память. А её исполнять Android разрешает.
 *
 * Поэтому здесь мы один раз перекладываем файл: содержимое не меняется ни на байт, меняются
 * только смещения разделов внутри файла — так, чтобы ни одно не делилось на 4096. Программа
 * остаётся той же самой программой; меняется лишь то, каким способом её прочитают.
 *
 * Заодно это объясняет, почему обычные игры чаще всего заводятся сразу: их собирают с
 * шагом 512 байт, и они на эту дорогу попадают сами. Ровно выровненными по странице бывают
 * как раз части самого слоя Windows.
 */
final class ExPe {

    private static final int PAGE = 4096;
    private static final int STEP = 512;

    /** Метка о том, что папку уже переложили — второй раз работать незачем. */
    private static final String MARK = ".ex-pe";

    /**
     * Перекладывает все программы Windows в дереве папок. Возвращает, сколько тронули.
     *
     * Работа долгая (сотни файлов), поэтому делается один раз и помечается: у слоя Windows
     * — при сборке системы, у игры — при первом запуске.
     */
    static int prepare(File root, Ex.Progress say) {
        if (root == null || !root.isDirectory()) {
            return 0;
        }

        File mark = new File(root, MARK);

        if (mark.exists()) {
            return 0;
        }

        int[] count = new int[2];
        walk(root, count, say);

        try {
            new java.io.FileOutputStream(mark).close();
        } catch (Exception ignored) {
            // Без метки просто переложим ещё раз — потеря времени, не беда.
        }

        if (say != null) {
            say.say("переложено программ: " + count[0] + ", пропущено: " + count[1]);
        }

        return count[0];
    }

    private static void walk(File where, int[] count, Ex.Progress say) {
        File[] children = where.listFiles();

        if (children == null) {
            return;
        }

        for (File child : children) {
            if (child.isDirectory()) {
                walk(child, count, say);
                continue;
            }

            String name = child.getName().toLowerCase(java.util.Locale.ROOT);

            if (!name.endsWith(".exe") && !name.endsWith(".dll") && !name.endsWith(".drv")
                    && !name.endsWith(".ocx") && !name.endsWith(".sys")
                    && !name.endsWith(".cpl") && !name.endsWith(".acm")) {
                continue;
            }

            if (relayout(child)) {
                count[0]++;
            } else {
                count[1]++;
            }
        }
    }

    /**
     * Одна программа. Возвращает true, если файл переложен.
     *
     * Ничего не выдумываем: читаем заголовок ровно так, как его читает сама Windows, и
     * пишем новый файл рядом, а старый заменяем только после того, как новый дописан
     * целиком. Оборванная перекладка не должна превращать рабочую программу в мусор.
     */
    private static boolean relayout(File file) {
        File temporary = new File(file.getParentFile(), file.getName() + ".ex-tmp");

        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 0x40) {
                return false;
            }

            byte[] dos = new byte[0x40];
            in.readFully(dos);

            if (dos[0] != 'M' || dos[1] != 'Z') {
                return false;
            }

            int pe = le32(dos, 0x3C);

            if (pe <= 0 || pe + 24 > in.length()) {
                return false;
            }

            byte[] coff = new byte[24];
            in.seek(pe);
            in.readFully(coff);

            if (coff[0] != 'P' || coff[1] != 'E' || coff[2] != 0 || coff[3] != 0) {
                return false;
            }

            int sections = le16(coff, 6);
            int optionalSize = le16(coff, 20);

            if (sections <= 0 || sections > 96 || optionalSize < 40) {
                return false;
            }

            int optional = pe + 24;
            int table = optional + optionalSize;
            long headerEnd = table + 40L * sections;

            if (headerEnd > in.length()) {
                return false;
            }

            // Весь заголовок целиком — его мы перепишем с новыми смещениями.
            byte[] head = new byte[(int) headerEnd];
            in.seek(0);
            in.readFully(head);

            int headersSize = le32(head, optional + 60);

            if (headersSize < headerEnd || headersSize > in.length()) {
                headersSize = (int) headerEnd;
            }

            // Уже переложенный файл трогать незачем: у него ни одно смещение не делится
            // на страницу, и слой Windows и так прочитает его в обычную память.
            boolean needed = false;

            for (int i = 0; i < sections; i++) {
                int entry = table + 40 * i;
                int size = le32(head, entry + 16);
                int at = le32(head, entry + 20);

                if (size > 0 && at > 0 && at % PAGE == 0) {
                    needed = true;
                    break;
                }
            }

            if (!needed) {
                return false;
            }

            byte[] header = new byte[headersSize];
            in.seek(0);
            in.readFully(header, 0, Math.min(headersSize, (int) in.length()));

            // Шаг раскладки — 512 байт: так делают и обычные сборщики программ, и такой
            // файл остаётся правильным с точки зрения самой Windows.
            put32(header, optional + 36, STEP);

            try (RandomAccessFile out = new RandomAccessFile(temporary, "rw")) {
                out.setLength(0);
                out.write(header);

                long cursor = headersSize;
                byte[] buffer = new byte[1 << 20];

                for (int i = 0; i < sections; i++) {
                    int entry = table + 40 * i;
                    int size = le32(header, entry + 16);
                    int at = le32(header, entry + 20);

                    if (size <= 0 || at <= 0) {
                        continue;
                    }

                    cursor = next(cursor);
                    put32(header, entry + 20, (int) cursor);

                    out.seek(cursor);
                    in.seek(at);

                    long left = Math.min(size, Math.max(0, in.length() - at));

                    while (left > 0) {
                        int piece = (int) Math.min(left, buffer.length);
                        in.readFully(buffer, 0, piece);
                        out.write(buffer, 0, piece);
                        left -= piece;
                    }

                    cursor += size;
                }

                // Заголовок дописываем в самом конце: в нём уже стоят новые смещения.
                out.seek(0);
                out.write(header);
            }

            if (!file.delete() || !temporary.renameTo(file)) {
                temporary.delete();
                return false;
            }

            return true;
        } catch (Exception broken) {
            temporary.delete();
            return false;
        }
    }

    /**
     * Следующее место, куда можно положить раздел.
     *
     * Два условия сразу: кратно 512 (иначе файл перестанет быть правильным) и НЕ кратно
     * 4096 (иначе отображение снова окажется возможным, и мы вернёмся туда, откуда ушли).
     */
    private static long next(long cursor) {
        long at = (cursor + STEP - 1) / STEP * STEP;

        if (at % PAGE == 0) {
            at += STEP;
        }

        return at;
    }

    private static int le16(byte[] data, int at) {
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] data, int at) {
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8)
                | ((data[at + 2] & 0xFF) << 16) | ((data[at + 3] & 0xFF) << 24);
    }

    private static void put32(byte[] data, int at, int value) {
        data[at] = (byte) (value & 0xFF);
        data[at + 1] = (byte) ((value >> 8) & 0xFF);
        data[at + 2] = (byte) ((value >> 16) & 0xFF);
        data[at + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private ExPe() {
    }
}
