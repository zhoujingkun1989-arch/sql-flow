package io.sqlflow.connectors.extractor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * In-memory archive unpacker and charset-resilient text decoder for {@code FileExtractorSpi} inputs.
 *
 * <p>Transparently expands {@code .zip} bundles (UTF-8 with GBK fallback for CJK entry names, skipping
 * {@code __MACOSX} and {@code .DS_Store}) and {@code .gz} compressed log files ({@code 02 §6.4.1–§6.4.2}).
 */
public final class ArchiveUnpacker {

    private static final Charset GBK_CHARSET = Charset.forName("GBK");

    private ArchiveUnpacker() {
    }

    /**
     * Expands any {@code .zip} or {@code .gz} entries inside {@code rawFiles} into a flat map of normalized
     * relative paths to uncompressed bytes. Non-archive entries are preserved as-is.
     */
    public static Map<String, byte[]> unpackAll(Map<String, byte[]> rawFiles) {
        if (rawFiles == null || rawFiles.isEmpty()) {
            return Map.of();
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : rawFiles.entrySet()) {
            String rawName = entry.getKey();
            byte[] payload = entry.getValue();
            if (rawName == null || payload == null) {
                continue;
            }
            String normName = normalizeEntryPath(rawName);
            if (normName.isEmpty() || shouldIgnoreEntry(normName)) {
                continue;
            }
            String lower = normName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".zip") || isZipMagic(payload)) {
                unpackZipBytes(normName, payload, out);
            } else if (lower.endsWith(".gz") || isGzipMagic(payload)) {
                String strippedName = lower.endsWith(".gz")
                        ? normName.substring(0, normName.length() - 3)
                        : normName + ".txt";
                out.put(strippedName, gunzipBytes(payload));
            } else {
                out.put(normName, payload);
            }
        }
        return out;
    }

    private static void unpackZipBytes(String parentArchiveName, byte[] zipBytes, Map<String, byte[]> out) {
        if (!tryUnpackZipWithCharset(zipBytes, StandardCharsets.UTF_8, out)) {
            tryUnpackZipWithCharset(zipBytes, GBK_CHARSET, out);
        }
    }

    private static boolean tryUnpackZipWithCharset(byte[] zipBytes, Charset charset, Map<String, byte[]> out) {
        Map<String, byte[]> staged = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), charset)) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (ze.isDirectory()) {
                    continue;
                }
                String entryPath = normalizeEntryPath(ze.getName());
                if (entryPath.isEmpty() || shouldIgnoreEntry(entryPath)) {
                    continue;
                }
                byte[] content = zis.readAllBytes();
                String lower = entryPath.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".gz") || isGzipMagic(content)) {
                    String innerName = lower.endsWith(".gz")
                            ? entryPath.substring(0, entryPath.length() - 3)
                            : entryPath;
                    staged.put(innerName, gunzipBytes(content));
                } else {
                    staged.put(entryPath, content);
                }
            }
            out.putAll(staged);
            return true;
        } catch (IllegalArgumentException e) {
            // Triggered when UTF-8 ZipInputStream encounters GBK-encoded entry filenames
            return false;
        } catch (IOException e) {
            out.putAll(staged);
            return !staged.isEmpty();
        }
    }

    public static byte[] gunzipBytes(byte[] gzBytes) {
        if (gzBytes == null || gzBytes.length == 0) {
            return new byte[0];
        }
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gzBytes));
                ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            gis.transferTo(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            return gzBytes;
        }
    }

    /**
     * Decodes text bytes strictly as UTF-8 first (stripping UTF-8 BOM if present), falling back to GBK.
     */
    public static String decodeText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int offset = 0;
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, offset, bytes.length - offset, GBK_CHARSET);
        }
    }

    public static String normalizeEntryPath(String rawPath) {
        if (rawPath == null) {
            return "";
        }
        String norm = rawPath.replace('\\', '/').trim();
        while (norm.startsWith("/")) {
            norm = norm.substring(1);
        }
        while (norm.startsWith("./")) {
            norm = norm.substring(2);
        }
        if (norm.contains("../") || norm.endsWith("/..")) {
            return "";
        }
        return norm;
    }

    private static boolean shouldIgnoreEntry(String path) {
        if (path.startsWith("__") && path.endsWith("__")) {
            // Keep internal config entries like __csvFormat__
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.startsWith("__macosx/")
                || lower.endsWith("/.ds_store")
                || ".ds_store".equals(lower)
                || lower.endsWith("/thumbs.db")
                || "thumbs.db".equals(lower);
    }

    private static boolean isZipMagic(byte[] bytes) {
        return bytes.length >= 4
                && (bytes[0] & 0xFF) == 0x50
                && (bytes[1] & 0xFF) == 0x4B
                && (bytes[2] & 0xFF) == 0x03
                && (bytes[3] & 0xFF) == 0x04;
    }

    private static boolean isGzipMagic(byte[] bytes) {
        return bytes.length >= 2
                && (bytes[0] & 0xFF) == 0x1F
                && (bytes[1] & 0xFF) == 0x8B;
    }
}
