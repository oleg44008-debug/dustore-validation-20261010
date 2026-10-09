package ru.dustore.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded persisted UTF-8 reads supported by the declared Android API24 minimum. */
final class ExFileRead {
    static final int GAME_METADATA_BYTES = 1024 * 1024;
    static final int LIBRARY_BYTES = 4 * 1024 * 1024;
    static final class TooLarge extends IOException {
        TooLarge() { super("Persisted eX record exceeds its size limit"); }
    }
    private ExFileRead() { }
    static String utf8(File file, int maximumBytes) throws IOException {
        if (file.length() > maximumBytes) throw new TooLarge();
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(file.length(), 8192))) {
            byte[] block = new byte[8192]; int size = 0, read;
            while ((read = input.read(block)) != -1) {
                if (read > maximumBytes - size) throw new TooLarge();
                output.write(block, 0, read); size += read;
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
