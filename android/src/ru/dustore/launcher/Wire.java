package ru.dustore.launcher;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The same five-byte header the launcher writes: one byte of type, four of length.
 *
 * Deliberately small enough to hold in your head, because both ends of it are written by
 * hand — this half has no library to lean on, and a format nobody can read from memory is
 * a format the two sides drift apart on.
 */
final class Wire {

    static final int MAGIC = 0x44584252;   // "DXBR"

    // phone -> launcher
    static final byte MSG_HELLO = 'h';
    static final byte MSG_LIST = 'L';
    static final byte MSG_TRANSLATE = 'T';
    static final byte MSG_PLAY = 'P';

    /** eXepult: «запусти эту игру у себя» — телефон как пульт для компьютера. */
    static final byte MSG_RUN_HERE = 'R';

    /** eXeportal: «запусти у себя и покажи мне» — игра идёт на ПК, картинка на телефоне. */
    static final byte MSG_PORTAL = 'V';

    // launcher -> phone
    static final byte MSG_GAMES = 'l';
    static final byte MSG_STATUS = 's';
    static final byte MSG_DONE = 'd';

    /** Ответ eXepult: запустилось ли и какую раскладку брать джойстику. */
    static final byte MSG_RAN = 'r';

    // eXepad. Отдельный сервер и отдельный порт, но тот же конверт из пяти байт.
    //
    // Буквы намеренно не пересекаются с теми, что выше, хотя сокеты разные: перепутать два
    // соединения в отладке — обычное дело, и пусть письмо само говорит, к какому оно.
    static final byte MSG_LAYOUT = 'L';
    static final byte MSG_PROFILES = 'N';
    static final byte MSG_SELECT = 's';

    /** eXeportal: кадр игры, которая идёт на компьютере. */
    static final byte MSG_FRAME = 'F';

    /** eXeportal: звук игры — [частота][каналы][16-битные отсчёты]. */
    static final byte MSG_AUDIO = 'A';

    private Wire() {
    }

    static void write(OutputStream out, byte type, byte[] payload) throws IOException {
        int length = payload == null ? 0 : payload.length;
        byte[] header = new byte[5];

        header[0] = type;
        header[1] = (byte) (length >> 24);
        header[2] = (byte) (length >> 16);
        header[3] = (byte) (length >> 8);
        header[4] = (byte) length;

        synchronized (out) {
            out.write(header);
            if (length > 0) {
                out.write(payload);
            }
            out.flush();
        }
    }

    static void writeText(OutputStream out, byte type, String text) throws IOException {
        write(out, type, text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    static boolean read(DataInputStream in, Message message) throws IOException {
        int type = in.read();
        if (type < 0) {
            return false;
        }

        int length = (in.read() << 24) | (in.read() << 16) | (in.read() << 8) | in.read();
        if (length < 0 || length > 32 * 1024 * 1024) {
            return false;
        }

        byte[] payload = new byte[length];
        in.readFully(payload);

        message.type = (byte) type;
        message.payload = payload;
        return true;
    }

    static String text(Message message) {
        return new String(message.payload, StandardCharsets.UTF_8);
    }

    static final class Message {
        byte type;
        byte[] payload;
    }
}
