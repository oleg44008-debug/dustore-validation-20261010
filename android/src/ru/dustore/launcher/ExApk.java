package ru.dustore.launcher;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Сборка APK-игры на самом телефоне — без единого инструмента Android.
 *
 * Шаблон стаба собран на компьютере один раз и лежит в assets без подписи. Здесь ему
 * даются имя пакета, название и иконка игры, после чего файл подписывается схемой v2 —
 * тоже своим кодом: дайджесты кусков, блок подписи, вставка перед центральным каталогом.
 * Ключ один на все игры, поэтому переустановка стаба не спотыкается о чужую подпись.
 *
 * Стаб — не игра: он весит десятки килобайт и по нажатию значка зовёт eX внутри DustoreX
 * с id игры. Игра при этом лежит в одном месте, а не в двух.
 */
final class ExApk {

    /** Шаблон, ключ и сертификат лежат в res/raw: такие ресурсы читаются всегда. */
    static final int STUB_RES = R.raw.exstub;
    static final int KEY_RES = R.raw.exstub_key;
    static final int CERT_RES = R.raw.exstub_cer;

    static final String PLACEHOLDER_PACKAGE = "ru.exepc.exstub.PLACEHOLDER";
    static final String PLACEHOLDER_LABEL = "EXGAME_LABEL";
    static final String PACKAGE_PREFIX = "ru.exepc.exgame.";

    static String packageFor(ExGame game) {
        return PACKAGE_PREFIX + game.id;
    }

    /** Собирает и подписывает стаб для игры; возвращает файл в files/ex/apk/. */
    static File build(Context context, ExGame game) throws Exception {
        byte[] template = readRaw(context, STUB_RES);
        byte[] key = readRaw(context, KEY_RES);
        byte[] cert = readRaw(context, CERT_RES);

        byte[] icon = null;
        File png = game.icon();

        if (png != null) {
            try (FileInputStream in = new FileInputStream(png)) {
                icon = readAll(in);
            }
        }

        byte[] unsigned = rebuild(template, packageFor(game), game.title, icon);
        byte[] signed = signV2(unsigned, key, cert);

        File dir = new File(Ex.home(context), "apk");
        dir.mkdirs();

        File target = new File(dir, game.id + ".apk");

        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(signed);
        }

        return target;
    }

    /**
     * Переписывает zip шаблона: манифест с новыми строками, иконка игры вместо значка eX.
     *
     * resources.arsc обязан лежать без сжатия и по границе четырёх байт — так требует
     * Android начиная с 11-й версии. Выравнивание делается лишним полем в заголовке
     * записи, как это делает zipalign.
     */
    static byte[] rebuild(byte[] template, String packageName, String label, byte[] icon)
            throws Exception {
        List<String> names = new ArrayList<>();
        Map<String, byte[]> files = new HashMap<>();

        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(template))) {
            ZipEntry entry;

            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                names.add(entry.getName());
                files.put(entry.getName(), readAll(in));
            }
        }

        Map<String, String> replacements = new HashMap<>();
        replacements.put(PLACEHOLDER_PACKAGE, packageName);
        replacements.put(PLACEHOLDER_LABEL, label == null || label.isEmpty() ? "Игра" : label);

        byte[] manifest = files.get("AndroidManifest.xml");

        if (manifest == null) {
            throw new IllegalStateException("в шаблоне нет манифеста");
        }

        files.put("AndroidManifest.xml", ExAxml.replaceStrings(manifest, replacements));

        if (icon != null) {
            for (String name : names) {
                if (name.startsWith("res/mipmap") && name.endsWith("icon.png")) {
                    files.put(name, icon);
                }
            }
        }

        Counting counting = new Counting();

        try (ZipOutputStream out = new ZipOutputStream(counting)) {
            out.setLevel(9);

            for (String name : names) {
                byte[] data = files.get(name);
                ZipEntry entry = new ZipEntry(name);

                boolean stored = name.equals("resources.arsc") || name.endsWith(".png");

                if (stored) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(data.length);
                    entry.setCompressedSize(data.length);

                    CRC32 crc = new CRC32();
                    crc.update(data);
                    entry.setCrc(crc.getValue());

                    // Начало данных = позиция + 30 байт заголовка + имя + лишнее поле.
                    int header = 30 + name.getBytes("UTF-8").length;
                    int padding = (int) ((4 - (counting.count + header + 4) % 4) % 4);
                    byte[] extra = new byte[4 + padding];
                    extra[0] = (byte) 0xD9;               // тот же признак, что у zipalign
                    extra[1] = (byte) 0x35;
                    extra[2] = (byte) padding;
                    extra[3] = 0;
                    entry.setExtra(extra);
                } else {
                    entry.setMethod(ZipEntry.DEFLATED);
                }

                out.putNextEntry(entry);
                out.write(data);
                out.closeEntry();
            }
        }

        return counting.toByteArray();
    }

    /** Поток, который помнит, сколько байт через него прошло. */
    private static final class Counting extends ByteArrayOutputStream {

        long count;

        @Override
        public synchronized void write(int b) {
            super.write(b);
            count++;
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            super.write(b, off, len);
            count += len;
        }
    }

    // ------------------------------------------------------------ подпись v2 ----

    private static final int SIG_RSA_PKCS1_SHA256 = 0x0103;
    private static final int BLOCK_ID_V2 = 0x7109871a;
    private static final byte[] MAGIC = "APK Sig Block 42".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /**
     * Подпись схемой v2.
     *
     * Три части файла (записи, центральный каталог, конец каталога с уже исправленным
     * смещением) режутся на куски по мегабайту; у каждого свой дайджест, над ними общий.
     * Подписанные данные — дайджесты и сертификат, подпись — RSA с SHA-256. Блок подписи
     * вставляется перед центральным каталогом, и смещение каталога в конце файла растёт
     * на размер блока.
     */
    static byte[] signV2(byte[] apk, byte[] keyDer, byte[] certDer) throws Exception {
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyDer));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(certDer));
        PublicKey publicKey = cert.getPublicKey();

        int eocd = findEocd(apk);

        if (eocd < 0) {
            throw new IllegalStateException("не найден конец центрального каталога");
        }

        int cdOffset = le32(apk, eocd + 16);
        int cdSize = le32(apk, eocd + 12);

        if (cdOffset + cdSize != eocd) {
            throw new IllegalStateException("центральный каталог не там, где ожидалось");
        }

        byte[] contents = new byte[cdOffset];
        System.arraycopy(apk, 0, contents, 0, cdOffset);

        byte[] cd = new byte[cdSize];
        System.arraycopy(apk, cdOffset, cd, 0, cdSize);

        byte[] eocdBytes = new byte[apk.length - eocd];
        System.arraycopy(apk, eocd, eocdBytes, 0, eocdBytes.length);

        // Для дайджеста поле «смещение каталога» в конце файла обязано указывать на
        // начало блока подписи — а он встаёт ровно туда, где каталог лежал до сих пор.
        // Проверяющий делает то же самое: подставляет длину всего, что перед блоком.
        put32(eocdBytes, 16, cdOffset);

        byte[] digest = chunkedDigest(contents, cd, eocdBytes);
        byte[] block = signingBlock(digest, key, publicKey, certDer, new byte[0]);

        // А в самом файле каталог теперь дальше — на размер блока.
        put32(eocdBytes, 16, cdOffset + block.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream(apk.length + block.length);
        out.write(contents);
        out.write(block);
        out.write(cd);
        out.write(eocdBytes);

        return out.toByteArray();
    }

    private static byte[] signingBlock(byte[] digest, PrivateKey key, PublicKey publicKey,
                                       byte[] certDer, byte[] draftSignature) throws Exception {
        // signed data
        ByteArrayOutputStream digests = new ByteArrayOutputStream();
        ByteArrayOutputStream oneDigest = new ByteArrayOutputStream();
        writeInt(oneDigest, SIG_RSA_PKCS1_SHA256);
        writePrefixed(oneDigest, digest);
        writePrefixed(digests, oneDigest.toByteArray());

        ByteArrayOutputStream certs = new ByteArrayOutputStream();
        writePrefixed(certs, certDer);

        ByteArrayOutputStream signedData = new ByteArrayOutputStream();
        writePrefixed(signedData, digests.toByteArray());
        writePrefixed(signedData, certs.toByteArray());
        writeInt(signedData, 0);                              // дополнительных атрибутов нет

        byte[] signedBytes = signedData.toByteArray();
        byte[] signature;

        if (draftSignature.length > 0) {
            signature = draftSignature;
        } else {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(signedBytes);
            signature = signer.sign();
        }

        ByteArrayOutputStream signatures = new ByteArrayOutputStream();
        ByteArrayOutputStream oneSignature = new ByteArrayOutputStream();
        writeInt(oneSignature, SIG_RSA_PKCS1_SHA256);
        writePrefixed(oneSignature, signature);
        writePrefixed(signatures, oneSignature.toByteArray());

        ByteArrayOutputStream signer = new ByteArrayOutputStream();
        writePrefixed(signer, signedBytes);
        writePrefixed(signer, signatures.toByteArray());
        writePrefixed(signer, publicKey.getEncoded());

        ByteArrayOutputStream signers = new ByteArrayOutputStream();
        writePrefixed(signers, signer.toByteArray());

        ByteArrayOutputStream value = new ByteArrayOutputStream();
        writePrefixed(value, signers.toByteArray());
        byte[] valueBytes = value.toByteArray();

        // Пара «длина — id — значение», дважды размер, магия.
        long pairLength = 4 + valueBytes.length;
        long blockLength = 8 + pairLength + 8 + 16;

        ByteArrayOutputStream block = new ByteArrayOutputStream();
        writeLong(block, blockLength);
        writeLong(block, pairLength);
        writeInt(block, BLOCK_ID_V2);
        block.write(valueBytes);
        writeLong(block, blockLength);
        block.write(MAGIC);

        return block.toByteArray();
    }

    /** Дайджест трёх частей файла кусками по мегабайту. */
    private static byte[] chunkedDigest(byte[]... sections) throws Exception {
        List<byte[]> chunks = new ArrayList<>();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");

        for (byte[] section : sections) {
            for (int at = 0; at < section.length; at += 1 << 20) {
                int length = Math.min(1 << 20, section.length - at);
                sha.reset();
                sha.update((byte) 0xa5);
                sha.update(intBytes(length));
                sha.update(section, at, length);
                chunks.add(sha.digest());
            }
        }

        sha.reset();
        sha.update((byte) 0x5a);
        sha.update(intBytes(chunks.size()));

        for (byte[] chunk : chunks) {
            sha.update(chunk);
        }

        return sha.digest();
    }

    private static int findEocd(byte[] apk) {
        for (int at = apk.length - 22; at >= 0 && at >= apk.length - 22 - 65535; at--) {
            if ((apk[at] & 0xFF) == 0x50 && (apk[at + 1] & 0xFF) == 0x4B
                    && (apk[at + 2] & 0xFF) == 0x05 && (apk[at + 3] & 0xFF) == 0x06) {
                int comment = le16(apk, at + 20);

                if (at + 22 + comment == apk.length) {
                    return at;
                }
            }
        }

        return -1;
    }

    // ----------------------------------------------------------------- мелочи ----

    private static byte[] readRaw(Context context, int id) throws Exception {
        try (InputStream in = context.getResources().openRawResource(id)) {
            return readAll(in);
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 16];
        int read;

        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }

        return out.toByteArray();
    }

    private static void writePrefixed(ByteArrayOutputStream out, byte[] data) {
        writeInt(out, data.length);
        out.write(data, 0, data.length);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(intBytes(value), 0, 4);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) {
            out.write((int) (value >>> (8 * i)) & 0xFF);
        }
    }

    private static byte[] intBytes(int value) {
        return new byte[] {
                (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)
        };
    }

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

    private ExApk() {
    }
}
