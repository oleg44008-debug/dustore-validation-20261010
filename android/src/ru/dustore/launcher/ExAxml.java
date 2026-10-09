package ru.dustore.launcher;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Бинарный XML Android (AndroidManifest.xml внутри APK): чтение и подмена строк.
 *
 * Манифест стаба один на все игры, а имя пакета и подпись у каждой свои. Пересобирать APK
 * инструментами Android на телефоне нельзя — их там нет; зато у бинарного XML простое
 * устройство: все строки лежат в одной таблице в начале файла, а узлы ссылаются на них
 * номерами. Меняем содержимое строк, пересчитываем таблицу и размеры кусков — номера
 * остаются теми же, и весь остальной файл не трогается.
 */
final class ExAxml {

    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int FLAG_UTF8 = 0x100;

    /** Все строки таблицы — для проверок и поиска. */
    static List<String> strings(byte[] axml) {
        int pool = 8;

        if (le16(axml, pool) != CHUNK_STRING_POOL) {
            throw new IllegalArgumentException("нет таблицы строк");
        }

        int headerSize = le16(axml, pool + 2);
        int count = le32(axml, pool + 8);
        int flags = le32(axml, pool + 16);
        int stringsStart = le32(axml, pool + 20);
        boolean utf8 = (flags & FLAG_UTF8) != 0;

        List<String> result = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            int offset = le32(axml, pool + headerSize + i * 4);
            result.add(readString(axml, pool + stringsStart + offset, utf8));
        }

        return result;
    }

    /**
     * Подменяет строки по точному совпадению и возвращает новый файл.
     *
     * Таблица переписывается целиком в той же кодировке, что была; стили (их у манифеста
     * не бывает, но формат их допускает) переносятся байт в байт.
     */
    static byte[] replaceStrings(byte[] axml, Map<String, String> replacements) {
        int pool = 8;

        if (le16(axml, pool) != CHUNK_STRING_POOL) {
            throw new IllegalArgumentException("нет таблицы строк");
        }

        int headerSize = le16(axml, pool + 2);
        int chunkSize = le32(axml, pool + 4);
        int count = le32(axml, pool + 8);
        int styleCount = le32(axml, pool + 12);
        int flags = le32(axml, pool + 16);
        int stringsStart = le32(axml, pool + 20);
        int stylesStart = le32(axml, pool + 24);
        boolean utf8 = (flags & FLAG_UTF8) != 0;

        List<String> strings = strings(axml);

        for (int i = 0; i < strings.size(); i++) {
            String fresh = replacements.get(strings.get(i));

            if (fresh != null) {
                strings.set(i, fresh);
            }
        }

        // Стили: смещения и данные как были.
        byte[] styleOffsets = new byte[styleCount * 4];
        System.arraycopy(axml, pool + headerSize + count * 4, styleOffsets, 0, styleOffsets.length);

        byte[] styleData = new byte[0];

        if (styleCount > 0 && stylesStart > 0) {
            int from = pool + stylesStart;
            int to = pool + chunkSize;
            styleData = new byte[Math.max(0, to - from)];
            System.arraycopy(axml, from, styleData, 0, styleData.length);
        }

        // Новые строки и их смещения.
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] offsets = new byte[count * 4];

        for (int i = 0; i < count; i++) {
            put32(offsets, i * 4, data.size());
            data.write(encode(strings.get(i), utf8), 0, encode(strings.get(i), utf8).length);
        }

        // Данные строк выравниваются на 4 байта, как делает сам aapt.
        while (data.size() % 4 != 0) {
            data.write(0);
        }

        int newStringsStart = headerSize + count * 4 + styleCount * 4;
        int newStylesStart = styleCount > 0 ? newStringsStart + data.size() : 0;
        int newChunkSize = newStringsStart + data.size() + styleData.length;

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Заголовок файла: тип и размер заголовка те же, полный размер пересчитан.
        byte[] fileHeader = new byte[8];
        System.arraycopy(axml, 0, fileHeader, 0, 8);
        int oldTotal = le32(axml, 4);
        put32(fileHeader, 4, oldTotal - chunkSize + newChunkSize);
        out.write(fileHeader, 0, 8);

        byte[] poolHeader = new byte[headerSize];
        System.arraycopy(axml, pool, poolHeader, 0, headerSize);
        put32(poolHeader, 4, newChunkSize);
        put32(poolHeader, 20, newStringsStart);
        put32(poolHeader, 24, newStylesStart);
        out.write(poolHeader, 0, headerSize);
        out.write(offsets, 0, offsets.length);
        out.write(styleOffsets, 0, styleOffsets.length);
        out.write(data.toByteArray(), 0, data.size());
        out.write(styleData, 0, styleData.length);

        // Всё после таблицы строк — без изменений.
        int rest = pool + chunkSize;
        out.write(axml, rest, axml.length - rest);

        return out.toByteArray();
    }

    // ------------------------------------------------------------- кодировка ----

    private static String readString(byte[] data, int at, boolean utf8) {
        if (utf8) {
            int[] cursor = { at };
            readLength8(data, cursor);                   // длина в знаках — не нужна
            int bytes = readLength8(data, cursor);
            return new String(data, cursor[0], bytes, StandardCharsets.UTF_8);
        }

        int length = le16(data, at);
        int start = at + 2;

        if ((length & 0x8000) != 0) {
            length = ((length & 0x7FFF) << 16) | le16(data, at + 2);
            start = at + 4;
        }

        byte[] raw = new byte[length * 2];
        System.arraycopy(data, start, raw, 0, raw.length);
        return new String(raw, StandardCharsets.UTF_16LE);
    }

    private static int readLength8(byte[] data, int[] cursor) {
        int value = data[cursor[0]++] & 0xFF;

        if ((value & 0x80) != 0) {
            value = ((value & 0x7F) << 8) | (data[cursor[0]++] & 0xFF);
        }

        return value;
    }

    private static byte[] encode(String text, boolean utf8) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        if (utf8) {
            byte[] raw = text.getBytes(StandardCharsets.UTF_8);
            writeLength8(out, text.length());
            writeLength8(out, raw.length);
            out.write(raw, 0, raw.length);
            out.write(0);
            return out.toByteArray();
        }

        byte[] raw = text.getBytes(StandardCharsets.UTF_16LE);
        int chars = raw.length / 2;

        if (chars >= 0x8000) {
            out.write((chars >> 16) & 0x7F | 0x80);
            out.write((chars >> 24) & 0xFF);
        }

        out.write(chars & 0xFF);
        out.write((chars >> 8) & 0xFF);
        out.write(raw, 0, raw.length);
        out.write(0);
        out.write(0);
        return out.toByteArray();
    }

    private static void writeLength8(ByteArrayOutputStream out, int value) {
        if (value >= 0x80) {
            out.write(((value >> 8) & 0x7F) | 0x80);
        }

        out.write(value & 0xFF);
    }

    // ---------------------------------------------------------------- мелочи ----

    private static int le32(byte[] data, int at) {
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8)
                | ((data[at + 2] & 0xFF) << 16) | ((data[at + 3] & 0xFF) << 24);
    }

    private static int le16(byte[] data, int at) {
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
    }

    private static void put32(byte[] data, int at, int value) {
        data[at] = (byte) value;
        data[at + 1] = (byte) (value >> 8);
        data[at + 2] = (byte) (value >> 16);
        data[at + 3] = (byte) (value >> 24);
    }

    private ExAxml() {
    }
}
