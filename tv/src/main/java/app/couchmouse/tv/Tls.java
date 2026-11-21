package app.couchmouse.tv;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.Locale;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.security.auth.x500.X500Principal;

/**
 * The TV's own TLS identity: an EC P-256 key created once in the Android Keystore (it never
 * leaves the device) with a self-signed certificate. Phones learn the certificate's SHA-256
 * fingerprint when pairing (QR code, or trust on first use) and pin it, so there is nothing to
 * install on the phone and no certificate authority involved.
 */
final class Tls {
    private static final String ALIAS = "couchmouse-server";
    private static SSLContext context;
    private static String fingerprint;

    private Tls() {
    }

    static synchronized SSLContext context() throws Exception {
        if (context != null) return context;
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) generate();
        final PrivateKey key = (PrivateKey) ks.getKey(ALIAS, null);
        final X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
        fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(new KeyManager[]{new Identity(key, cert)}, null, null);
        context = c;
        return c;
    }

    /** SHA-256 of the certificate, lowercase hex (64 characters). */
    static synchronized String fingerprint() throws Exception {
        if (fingerprint == null) context();
        return fingerprint;
    }

    private static void generate() throws Exception {
        long now = System.currentTimeMillis();
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                // NONE is what the TLS stack uses (it hashes itself); the others for completeness.
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(new X500Principal("CN=CouchMouse TV"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(new Date(now - 24L * 3600 * 1000))
                .setCertificateNotAfter(new Date(now + 20L * 365 * 24 * 3600 * 1000))
                .build());
        g.generateKeyPair();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ENGLISH, "%02x", x));
        return sb.toString();
    }

    /** Always presents the one Keystore identity (the key itself can't be exported). */
    private static final class Identity extends X509ExtendedKeyManager {
        private final PrivateKey key;
        private final X509Certificate[] chain;

        Identity(PrivateKey key, X509Certificate cert) {
            this.key = key;
            this.chain = new X509Certificate[]{cert};
        }

        private static String pick(String keyType) {
            return keyType == null || keyType.toUpperCase(Locale.ENGLISH).contains("EC") ? ALIAS : null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return pick(keyType);
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return pick(keyType);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return pick(keyType) == null ? null : new String[]{ALIAS};
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return ALIAS.equals(alias) ? chain : null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return ALIAS.equals(alias) ? key : null;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return null;
        }
    }
}
