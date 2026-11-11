package io.sqlflow.server.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Clean-room runtime cryptographic helper:
 * <ul>
 *   <li>Ephemeral or operator-configured 1024-bit RSA keypair ({@code RSA/ECB/PKCS1Padding} for JSEncrypt PKCS#1 v1.5)</li>
 *   <li>Plaintext fallback when RSA Base64/cipher decryption fails (Wire Invariant #7)</li>
 *   <li>HS256 JWT token generation and verification ({@code jti} = {@code userId})</li>
 *   <li>MD5 / SHA-256 hex digests and runtime AES-GCM secret wrapping for persisted job configs</li>
 * </ul>
 */
@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
    private static final String RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final SqlflowRuntimeConfig config;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    private volatile PublicKey rsaPublicKey;
    private volatile PrivateKey rsaPrivateKey;
    private volatile byte[] jwtHmacKey;
    private volatile byte[] jobAesKey;

    public CryptoService(SqlflowRuntimeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public synchronized void init() {
        initRsaKeys();
        initSymmetricKeys();
    }

    private void initRsaKeys() {
        String pubB64 = config.getRaw("rsa.public_key", null);
        String privB64 = config.getRaw("rsa.private_key", null);
        if (pubB64 == null || pubB64.isBlank() || privB64 == null || privB64.isBlank()) {
            java.nio.file.Path pubPath = resolveKeyFile("SQLFLOW_RSA_PUBLIC_KEY_PATH", "keys/rsa_public.pem");
            java.nio.file.Path privPath = resolveKeyFile("SQLFLOW_RSA_PRIVATE_KEY_PATH", "keys/rsa_private.pem");
            if (pubPath != null && privPath != null
                    && java.nio.file.Files.isRegularFile(pubPath)
                    && java.nio.file.Files.isRegularFile(privPath)) {
                try {
                    pubB64 = java.nio.file.Files.readString(pubPath, StandardCharsets.UTF_8);
                    privB64 = java.nio.file.Files.readString(privPath, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
            }
        }
        if (pubB64 != null && !pubB64.isBlank() && privB64 != null && !privB64.isBlank()
                && privB64.contains("PRIVATE KEY")) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                this.rsaPublicKey = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(stripPem(pubB64))));
                this.rsaPrivateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(stripPem(privB64))));
                return;
            } catch (Exception e) {
                log.warn("Invalid configured RSA keys, generating ephemeral 1024-bit keypair: {}", e.getMessage());
            }
        }
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(1024, secureRandom);
            KeyPair kp = kpg.generateKeyPair();
            this.rsaPublicKey = kp.getPublic();
            this.rsaPrivateKey = kp.getPrivate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize 1024-bit RSA keypair", e);
        }
    }

    private void initSymmetricKeys() {
        String configuredJwtSecret = config.getRaw("jwt.secret", config.getRaw("SQLFLOW_JWT_SECRET", null));
        if (configuredJwtSecret == null || configuredJwtSecret.isBlank()) {
            java.nio.file.Path jwtPath = resolveKeyFile(null, "keys/jwt.secret");
            if (jwtPath != null && java.nio.file.Files.isRegularFile(jwtPath)) {
                try {
                    configuredJwtSecret = java.nio.file.Files.readString(jwtPath, StandardCharsets.UTF_8).trim();
                } catch (Exception ignored) {
                }
            }
        }
        if (configuredJwtSecret != null && !configuredJwtSecret.isBlank()) {
            this.jwtHmacKey = sha256Bytes(configuredJwtSecret);
        } else {
            byte[] randomBytes = new byte[32];
            secureRandom.nextBytes(randomBytes);
            this.jwtHmacKey = randomBytes;
        }
        String configuredDesSecret = config.getRaw("SQLFLOW_DES_SECRET", null);
        if (configuredDesSecret == null || configuredDesSecret.isBlank()) {
            java.nio.file.Path desPath = resolveKeyFile(null, "keys/des.secret");
            if (desPath != null && java.nio.file.Files.isRegularFile(desPath)) {
                try {
                    configuredDesSecret = java.nio.file.Files.readString(desPath, StandardCharsets.UTF_8).trim();
                } catch (Exception ignored) {
                }
            }
        }
        if (configuredDesSecret != null && !configuredDesSecret.isBlank()) {
            byte[] digest = sha256Bytes(configuredDesSecret);
            byte[] aesBytes = new byte[16];
            System.arraycopy(digest, 0, aesBytes, 0, 16);
            this.jobAesKey = aesBytes;
        } else {
            byte[] aesBytes = new byte[16];
            secureRandom.nextBytes(aesBytes);
            this.jobAesKey = aesBytes;
        }
    }

    private java.nio.file.Path resolveKeyFile(String envKey, String defaultConfRel) {
        if (envKey != null) {
            String explicit = config.getRaw(envKey, System.getenv(envKey));
            if (explicit != null && !explicit.isBlank()) {
                return java.nio.file.Paths.get(explicit).toAbsolutePath().normalize();
            }
        }
        return config.getConfDir() != null ? config.getConfDir().resolve(defaultConfRel) : null;
    }

    private static String stripPem(String key) {
        return key.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s+", "");
    }

    public String getRsaPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(rsaPublicKey.getEncoded());
    }

    public String getRsaPublicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n" + getRsaPublicKeyBase64() + "\n-----END PUBLIC KEY-----";
    }

    /**
     * Encrypts plaintext with the server's 1024-bit RSA public key using PKCS#1 v1.5 padding
     * (identical to browser JSEncrypt output).
     */
    public String encryptRsaPkcs1(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("RSA PKCS#1 encryption failed", e);
        }
    }

    /**
     * Wire Invariant #7: Decrypts Base64 RSA PKCS#1 v1.5 ciphertext; falls back cleanly to raw input
     * string when the caller supplied a plaintext password (CLI / V1 REST / unencrypted form).
     */
    public String decryptRsaOrPlaintext(String maybeEncrypted) {
        if (maybeEncrypted == null || maybeEncrypted.isEmpty()) {
            return maybeEncrypted;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(maybeEncrypted.trim());
            // 1024-bit RSA block is 128 bytes (512-bit legacy block is 64 bytes)
            if (decoded.length != 128 && decoded.length != 256 && decoded.length != 64) {
                return maybeEncrypted;
            }
            Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, rsaPrivateKey);
            byte[] decrypted = cipher.doFinal(decoded);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("try decrypt data source password failed, falling back to plaintext.");
            return maybeEncrypted;
        }
    }

    public String wrapJobSecret(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            return plainPassword;
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(jobAesKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plainPassword.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);
            return "ENC:" + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            return "";
        }
    }

    public String unwrapJobSecret(String wrapped) {
        if (wrapped == null || !wrapped.startsWith("ENC:")) {
            return wrapped;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(wrapped.substring(4));
            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] ct = new byte[combined.length - GCM_IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_BYTES);
            System.arraycopy(combined, GCM_IV_BYTES, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(jobAesKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public String generateSecretKey() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String issueJwt(String userId, String userSource, int expireHours) {
        try {
            long now = Instant.now().getEpochSecond();
            long exp = now + Math.max(1, expireHours) * 3600L;
            Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jti", userId);
            payload.put("sub", userId);
            payload.put("src", userSource != null ? userSource : "premise");
            payload.put("iat", now);
            payload.put("exp", exp);
            payload.put("nonce", UUID.randomUUID().toString().substring(0, 8));

            String headerB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(header));
            String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            String signingInput = headerB64 + "." + payloadB64;
            String sigB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + sigB64;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign HS256 JWT", e);
        }
    }

    /**
     * Verifies an HS256 JWT and returns the {@code jti}/{@code sub} userId, or {@code null} if invalid/expired.
     */
    public String verifyJwtAndGetUserId(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            return null;
        }
        String trimmed = jwt.trim();
        String[] parts = trimmed.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            String signingInput = parts[0] + "." + parts[1];
            byte[] expectedSig = hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8));
            byte[] actualSig = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expectedSig, actualSig)) {
                return null;
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            Map<String, Object> claims = objectMapper.readValue(payloadBytes, new TypeReference<>() {});
            Object expObj = claims.get("exp");
            if (expObj instanceof Number expNum) {
                if (Instant.now().getEpochSecond() > expNum.longValue()) {
                    return null;
                }
            }
            Object jti = claims.get("jti");
            if (jti != null && !jti.toString().isBlank()) {
                return jti.toString();
            }
            Object sub = claims.get("sub");
            return sub != null ? sub.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hmacSha256(byte[] data) throws Exception {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(jwtHmacKey, HMAC_SHA256));
        return mac.doFinal(data);
    }

    public static String md5Hex(String input) {
        if (input == null) {
            input = "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    public static String sha256Hex(String input) {
        return HexFormat.of().formatHex(sha256Bytes(input == null ? "" : input));
    }

    private static byte[] sha256Bytes(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String shortLinkId(String seed) {
        String hex = sha256Hex(seed);
        return hex.substring(0, 12);
    }
}
