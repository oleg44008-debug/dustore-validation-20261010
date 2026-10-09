import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;

/**
 * Достаёт из PKCS12-хранилища keytool ключ и сертификат стаба в голом DER:
 * так их читает телефон без единой библиотеки.
 *
 *   java tools/ExportKey.java stub.p12 пароль alias out.pk8 out.der
 */
public final class ExportKey {

    public static void main(String[] args) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");

        try (FileInputStream in = new FileInputStream(args[0])) {
            store.load(in, args[1].toCharArray());
        }

        PrivateKey key = (PrivateKey) store.getKey(args[2], args[1].toCharArray());
        Certificate cert = store.getCertificate(args[2]);

        try (FileOutputStream out = new FileOutputStream(args[3])) {
            out.write(key.getEncoded());
        }

        try (FileOutputStream out = new FileOutputStream(args[4])) {
            out.write(cert.getEncoded());
        }

        System.out.println("key: " + key.getAlgorithm() + " " + key.getFormat()
                + ", cert: " + ((java.security.cert.X509Certificate) cert).getSubjectX500Principal());
    }
}
