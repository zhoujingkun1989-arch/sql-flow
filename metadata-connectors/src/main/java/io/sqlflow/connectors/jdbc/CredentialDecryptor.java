package io.sqlflow.connectors.jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.DESKeySpec;

/**
 * Credential decryption and encryption helper supporting SQLFlow's dual password schemes ({@code 02 §4.6, §6.3.1}):
 * <ul>
 *   <li>RSA {@code PKCS#1 v1.5} ({@code RSA/ECB/PKCS1Padding}) Base64-encoded ciphertexts from the web UI
 *       ({@code GET /sqlflow/user/secretKey} + {@code JSEncrypt})</li>
 *   <li>{@code "gbtepted"} prefix DES-encrypted CLI/Grabit config passwords ({@code DES/ECB/PKCS5Padding})</li>
 *   <li>Plaintext pass-through fallback for unencrypted API / embedded / local credentials</li>
 * </ul>
 */
public final class CredentialDecryptor {

    /** Canonical prefix marking DES-encrypted passwords in Grabit / Exporter config files ({@code 02 §6.3.1}). */
    public static final String GBTEPTED_PREFIX = "gbtepted";

    /** System property / env var holding Base64 or PEM PKCS#8 RSA private key for UI password decryption. */
    public static final String RSA_PRIVATE_KEY_PROP = "sqlflow.security.rsa.private-key";
    public static final String RSA_PRIVATE_KEY_PATH_PROP = "sqlflow.security.rsa.private-key-path";
    public static final String RSA_PRIVATE_KEY_ENV = "SQLFLOW_RSA_PRIVATE_KEY";
    public static final String RSA_PRIVATE_KEY_PATH_ENV = "SQLFLOW_RSA_PRIVATE_KEY_PATH";

    /** System property / env var holding 8+ byte DES key material for {@code gbtepted} CLI password decryption. */
    public static final String DES_KEY_PROP = "sqlflow.security.des.key";
    public static final String DES_KEY_ENV = "SQLFLOW_DES_KEY";
    public static final String DES_SECRET_ENV = "SQLFLOW_DES_SECRET";

    private static final byte[] DEFAULT_GBTEPTED_KEY_MATERIAL =
            "sqlflow_des_0001".getBytes(StandardCharsets.UTF_8);

    private final PrivateKey rsaPrivateKey;
    private final byte[] desKeyMaterial;

    public CredentialDecryptor() {
        this(resolveConfiguredPrivateKey(), resolveConfiguredDesKey());
    }

    public CredentialDecryptor(PrivateKey rsaPrivateKey) {
        this(rsaPrivateKey, DEFAULT_GBTEPTED_KEY_MATERIAL);
    }

    public CredentialDecryptor(PrivateKey rsaPrivateKey, byte[] desKeyMaterial) {
        this.rsaPrivateKey = rsaPrivateKey;
        this.desKeyMaterial = desKeyMaterial != null && desKeyMaterial.length >= 8
                ? Arrays.copyOf(desKeyMaterial, 8)
                : Arrays.copyOf(DEFAULT_GBTEPTED_KEY_MATERIAL, 8);
    }

    /**
     * Decrypts {@code rawPassword} if it carries the {@code "gbtepted"} prefix or is a valid RSA Base64 ciphertext
     * for the configured private key; otherwise returns {@code rawPassword} unchanged.
     */
    public String decryptPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            return rawPassword;
        }
        if (rawPassword.startsWith(GBTEPTED_PREFIX) && rawPassword.length() > GBTEPTED_PREFIX.length()) {
            String payload = rawPassword.substring(GBTEPTED_PREFIX.length()).trim();
            try {
                return decryptDesBase64OrHex(payload);
            } catch (GeneralSecurityException | IllegalArgumentException ignored) {
                return rawPassword;
            }
        }
        if (rsaPrivateKey != null && looksLikeRsaCiphertext(rawPassword)) {
            try {
                byte[] cipherBytes = Base64.getMimeDecoder().decode(rawPassword.trim());
                Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                cipher.init(Cipher.DECRYPT_MODE, rsaPrivateKey);
                byte[] plain = cipher.doFinal(cipherBytes);
                return new String(plain, StandardCharsets.UTF_8);
            } catch (GeneralSecurityException | IllegalArgumentException ignored) {
                // Fall back to raw plaintext when password was not encrypted with this RSA key
                return rawPassword;
            }
        }
        return rawPassword;
    }

    /**
     * Encrypts {@code plainText} using {@code RSA/ECB/PKCS1Padding} and Base64-encodes the result (compatible with JSEncrypt).
     */
    public static String encryptRsaBase64(String plainText, PublicKey publicKey) {
        if (plainText == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to RSA-encrypt credential: " + e.getMessage(), e);
        }
    }

    /**
     * Encrypts {@code plainText} with the {@code "gbtepted"} DES scheme.
     */
    public String encryptGbtepted(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            DESKeySpec keySpec = new DESKeySpec(desKeyMaterial);
            SecretKey secretKey = SecretKeyFactory.getInstance("DES").generateSecret(keySpec);
            Cipher cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return GBTEPTED_PREFIX + Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to DES-encrypt credential: " + e.getMessage(), e);
        }
    }

    /**
     * Generates a fresh 2048-bit RSA keypair for {@code SecretKey} / credential testing.
     */
    public static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to generate RSA 2048 keypair", e);
        }
    }

    /**
     * Parses a PKCS#8 PEM or Base64-encoded RSA private key.
     */
    public static PrivateKey parsePkcs8PrivateKey(String pemOrBase64) {
        if (pemOrBase64 == null || pemOrBase64.isBlank()) {
            return null;
        }
        String cleaned = pemOrBase64
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(cleaned);
        try {
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid PKCS#8 RSA private key", e);
        }
    }

    private String decryptDesBase64OrHex(String payload) throws GeneralSecurityException {
        byte[] cipherBytes;
        if (payload.matches("^[0-9a-fA-F]+$") && payload.length() % 2 == 0) {
            cipherBytes = new byte[payload.length() / 2];
            for (int i = 0; i < cipherBytes.length; i++) {
                cipherBytes[i] = (byte) Integer.parseInt(payload.substring(i * 2, i * 2 + 2), 16);
            }
        } else {
            cipherBytes = Base64.getMimeDecoder().decode(payload);
        }
        DESKeySpec keySpec = new DESKeySpec(desKeyMaterial);
        SecretKey secretKey = SecretKeyFactory.getInstance("DES").generateSecret(keySpec);
        Cipher cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
    }

    private static boolean looksLikeRsaCiphertext(String candidate) {
        String trimmed = candidate.trim();
        // 1024-bit RSA Base64 is ~172 chars; 2048-bit RSA Base64 is ~344 chars; 512-bit test keys are >= 86 chars
        if (trimmed.length() < 80) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean valid = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '='
                    || c == '\r' || c == '\n';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    private static PrivateKey resolveConfiguredPrivateKey() {
        String raw = System.getProperty(RSA_PRIVATE_KEY_PROP);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(RSA_PRIVATE_KEY_ENV);
        }
        if (raw == null || raw.isBlank()) {
            String keyPath = System.getProperty(RSA_PRIVATE_KEY_PATH_PROP);
            if (keyPath == null || keyPath.isBlank()) {
                keyPath = System.getenv(RSA_PRIVATE_KEY_PATH_ENV);
            }
            if (keyPath != null && !keyPath.isBlank()) {
                Path p = Path.of(keyPath.trim());
                if (Files.isRegularFile(p)) {
                    try {
                        raw = Files.readString(p, StandardCharsets.UTF_8);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return parsePkcs8PrivateKey(raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static byte[] resolveConfiguredDesKey() {
        String raw = System.getProperty(DES_KEY_PROP);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(DES_KEY_ENV);
        }
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(DES_SECRET_ENV);
        }
        if (raw == null || raw.length() < 8) {
            return DEFAULT_GBTEPTED_KEY_MATERIAL;
        }
        return raw.getBytes(StandardCharsets.UTF_8);
    }
}
