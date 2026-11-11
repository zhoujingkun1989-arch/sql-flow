package io.sqlflow.server.service;

import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.persistence.SessionCacheService;
import io.sqlflow.server.security.SqlflowBusinessException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Manages user-uploaded DDL schema files under {@code data/schema/{urlEncUserId}/{shortVendor}/}
 * ({@code 01 §3.3.6}, Wire Invariants #11 & #12, {@code OBL-026}).
 *
 * <p>Mutating schema operations purge the interactive session cache so subsequent lineage requests
 * immediately re-resolve {@code SELECT *} and unqualified column references against updated DDL.
 */
@Service
public class UserSchemaService {

    private final SqlflowRuntimeConfig config;
    private final SessionCacheService sessionCacheService;

    public UserSchemaService(SqlflowRuntimeConfig config, SessionCacheService sessionCacheService) {
        this.config = config;
        this.sessionCacheService = sessionCacheService;
    }

    public static String normalizeShortVendor(String dbvendor) {
        if (dbvendor == null || dbvendor.isBlank()) {
            return "oracle";
        }
        String trimmed = dbvendor.trim();
        return EDbVendor.find(trimmed)
                .map(EDbVendor::shortName)
                .orElseGet(() -> {
                    String lower = trimmed.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("dbv") && lower.length() > 3) {
                        return lower.substring(3);
                    }
                    return sanitizePathSegment(lower);
                });
    }

    public static String encodeUserSegment(String userId) {
        String effective = (userId == null || userId.isBlank())
                ? SqlflowRuntimeConfig.DEFAULT_GUEST_USER_ID
                : userId.trim();
        return URLEncoder.encode(effective, StandardCharsets.UTF_8);
    }

    private static String sanitizePathSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "schema.sql";
        }
        String base = Path.of(raw).getFileName().toString();
        return base.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private Path resolveUserVendorDir(String userId, String dbvendor) {
        return config.resolveSchemaDir()
                .resolve(encodeUserSegment(userId))
                .resolve(normalizeShortVendor(dbvendor));
    }

    public List<String> submitUserSchema(String userId, String dbvendor, MultipartFile[] sqlfiles, String inlineSql) {
        Path vendorDir = resolveUserVendorDir(userId, dbvendor);
        try {
            Files.createDirectories(vendorDir);
            List<String> savedNames = new ArrayList<>();
            if (sqlfiles != null && sqlfiles.length > 0) {
                for (int i = 0; i < sqlfiles.length; i++) {
                    MultipartFile file = sqlfiles[i];
                    if (file == null || file.isEmpty()) {
                        continue;
                    }
                    String orig = file.getOriginalFilename();
                    String safeName = sanitizePathSegment(
                            orig != null && !orig.isBlank() ? orig : ("schema_" + (i + 1) + ".sql"));
                    Files.write(vendorDir.resolve(safeName), file.getBytes());
                    savedNames.add(safeName);
                }
            } else if (inlineSql != null && !inlineSql.isBlank()) {
                String safeName = "inline_schema.sql";
                Files.writeString(vendorDir.resolve(safeName), inlineSql, StandardCharsets.UTF_8);
                savedNames.add(safeName);
            }
            if (savedNames.isEmpty()) {
                throw SqlflowBusinessException.badRequest("sqlfiles must not be empty");
            }
            sessionCacheService.purgeAll();
            return savedNames;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store user DDL schema files: " + e.getMessage(), e);
        }
    }

    /**
     * Wire Invariant #12: returns {@code Map<String, List<List<String>>>} keyed by vendor name
     * stripped of {@code "dbv"} (e.g., {@code {"oracle": [["a.sql", "b.sql"]]}}).
     */
    public Map<String, List<List<String>>> displayUserSchemaSummary(String userId) {
        Path userDir = config.resolveSchemaDir().resolve(encodeUserSegment(userId));
        Map<String, List<List<String>>> summary = new LinkedHashMap<>();
        if (!Files.isDirectory(userDir)) {
            return summary;
        }
        try (Stream<Path> vendorDirs = Files.list(userDir)) {
            List<Path> sortedVendors = vendorDirs
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path vDir : sortedVendors) {
                String vendorKey = vDir.getFileName().toString();
                if (vendorKey.toLowerCase(Locale.ROOT).startsWith("dbv") && vendorKey.length() > 3) {
                    vendorKey = vendorKey.substring(3);
                }
                List<String> files = listVendorFileNames(vDir);
                if (!files.isEmpty()) {
                    summary.put(vendorKey, List.of(files));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect user schema directory", e);
        }
        return summary;
    }

    public List<String> displayUserDbvendorSchema(String userId, String dbvendor) {
        Path vendorDir = resolveUserVendorDir(userId, dbvendor);
        if (!Files.isDirectory(vendorDir)) {
            return List.of();
        }
        List<String> contents = new ArrayList<>();
        for (String fileName : listVendorFileNames(vendorDir)) {
            Path file = vendorDir.resolve(fileName);
            if (Files.isRegularFile(file)) {
                try {
                    contents.add(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                }
            }
        }
        return contents;
    }

    public String displayUserDbvendorSchemaContent(String userId, String dbvendor, String fileName) {
        Path vendorDir = resolveUserVendorDir(userId, dbvendor);
        if (!Files.isDirectory(vendorDir)) {
            return "";
        }
        if (fileName != null && !fileName.isBlank()) {
            Path target = vendorDir.resolve(sanitizePathSegment(fileName));
            if (Files.isRegularFile(target)) {
                try {
                    return Files.readString(target, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to read schema file: " + fileName, e);
                }
            }
            return "";
        }
        return String.join("\n\n", displayUserDbvendorSchema(userId, dbvendor));
    }

    public boolean deleteUserSchema(String userId, String dbvendor, String fileName) {
        Path vendorDir = resolveUserVendorDir(userId, dbvendor);
        if (!Files.exists(vendorDir)) {
            return false;
        }
        boolean deleted = false;
        try {
            if (fileName != null && !fileName.isBlank() && !"*".equals(fileName.trim())) {
                Path target = vendorDir.resolve(sanitizePathSegment(fileName));
                deleted = Files.deleteIfExists(target);
            } else {
                for (String f : listVendorFileNames(vendorDir)) {
                    deleted |= Files.deleteIfExists(vendorDir.resolve(f));
                }
            }
            // Remove empty vendor directory
            if (Files.isDirectory(vendorDir) && listVendorFileNames(vendorDir).isEmpty()) {
                Files.deleteIfExists(vendorDir);
            }
            sessionCacheService.purgeAll();
            return deleted;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete user schema: " + e.getMessage(), e);
        }
    }

    public String loadCombinedUserSchemaDdl(String userId, String dbvendor) {
        if (userId == null || userId.isBlank()) {
            return "";
        }
        List<String> chunks = displayUserDbvendorSchema(userId, dbvendor);
        if (chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String chunk : chunks) {
            if (chunk != null && !chunk.isBlank()) {
                sb.append(chunk.trim());
                if (!chunk.trim().endsWith(";")) {
                    sb.append(';');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static List<String> listVendorFileNames(Path vendorDir) {
        try (Stream<Path> stream = Files.list(vendorDir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
