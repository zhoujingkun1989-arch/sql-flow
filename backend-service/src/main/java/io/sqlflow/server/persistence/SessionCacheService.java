package io.sqlflow.server.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.security.CryptoService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manages {@code %02d}-sharded interactive session and graph caches under
 * {@code data/session/sqlflow/{00..99}/} and {@code tmp/cache/sqlflow/{00..99}/}.
 */
@Service
public class SessionCacheService {

    private static final Logger log = LoggerFactory.getLogger(SessionCacheService.class);
    private static final int MAX_MEMORY_ENTRIES = 256;

    private final SqlflowRuntimeConfig config;
    private final ObjectMapper objectMapper;
    private final Map<String, SessionEntry> memoryCache = new ConcurrentHashMap<>();

    public SessionCacheService(SqlflowRuntimeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public static String computeBucket(String key) {
        if (key == null || key.isEmpty()) {
            return "00";
        }
        String prefix = key;
        int dot = key.indexOf('.');
        if (dot > 0) {
            prefix = key.substring(0, dot);
        }
        int bucket = Math.abs(prefix.hashCode()) % 100;
        return String.format("%02d", bucket);
    }

    public String computeSessionId(String userId, String dbvendor, String sqlText, String showRelationType) {
        String seed = (userId == null ? "" : userId)
                + "|" + (dbvendor == null ? "" : dbvendor)
                + "|" + (showRelationType == null ? "fdd" : showRelationType)
                + "|" + (sqlText == null ? "" : sqlText);
        return CryptoService.sha256Hex(seed);
    }

    public void saveSession(String sessionId, String sqlText, String dbvendor, DataFlow dataFlow, SqlflowJSON graph, String xml) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (memoryCache.size() >= MAX_MEMORY_ENTRIES) {
            evictOldest();
        }
        SessionEntry entry = new SessionEntry(sessionId, sqlText, dbvendor, dataFlow, graph, xml, Instant.now().toEpochMilli());
        memoryCache.put(sessionId, entry);

        String bucket = computeBucket(sessionId);
        Path sessionBucketDir = config.getDataDir().resolve("session/sqlflow").resolve(bucket);
        Path cacheBucketDir = config.getTmpDir().resolve("cache/sqlflow").resolve(bucket);
        try {
            Files.createDirectories(sessionBucketDir);
            Files.createDirectories(cacheBucketDir);
            if (dataFlow != null) {
                Files.writeString(
                        sessionBucketDir.resolve(sessionId + ".dataflow.json"),
                        objectMapper.writeValueAsString(dataFlow),
                        StandardCharsets.UTF_8);
            }
            if (sqlText != null) {
                Files.writeString(
                        sessionBucketDir.resolve(sessionId + ".sql"),
                        sqlText,
                        StandardCharsets.UTF_8);
            }
            if (xml != null && !xml.isEmpty()) {
                Files.writeString(
                        sessionBucketDir.resolve(sessionId + ".dataflow.xml"),
                        xml,
                        StandardCharsets.UTF_8);
            }
            if (graph != null) {
                Files.writeString(
                        cacheBucketDir.resolve(sessionId + ".json"),
                        objectMapper.writeValueAsString(graph),
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Failed to persist session {} to disk: {}", sessionId, e.getMessage());
        }
    }

    public SessionEntry loadSession(String sessionIdOrJobKey) {
        if (sessionIdOrJobKey == null || sessionIdOrJobKey.isBlank()) {
            return null;
        }
        SessionEntry inMem = memoryCache.get(sessionIdOrJobKey);
        if (inMem != null) {
            return inMem;
        }
        // Also try sha256("generateSqlflowGraph:" + jobId)
        String hashedJobKey = CryptoService.sha256Hex("generateSqlflowGraph:" + sessionIdOrJobKey);
        inMem = memoryCache.get(hashedJobKey);
        if (inMem != null) {
            return inMem;
        }

        SessionEntry fromDisk = readFromDisk(sessionIdOrJobKey);
        if (fromDisk != null) {
            return fromDisk;
        }
        return readFromDisk(hashedJobKey);
    }

    private SessionEntry readFromDisk(String key) {
        String bucket = computeBucket(key);
        Path sessionBucketDir = config.getDataDir().resolve("session/sqlflow").resolve(bucket);
        Path cacheBucketDir = config.getTmpDir().resolve("cache/sqlflow").resolve(bucket);
        Path dfFile = sessionBucketDir.resolve(key + ".dataflow.json");
        if (!Files.isRegularFile(dfFile)) {
            return null;
        }
        try {
            DataFlow df = objectMapper.readValue(Files.readString(dfFile, StandardCharsets.UTF_8), DataFlow.class);
            Path sqlFile = sessionBucketDir.resolve(key + ".sql");
            String sql = Files.isRegularFile(sqlFile) ? Files.readString(sqlFile, StandardCharsets.UTF_8) : "";
            Path xmlFile = sessionBucketDir.resolve(key + ".dataflow.xml");
            String xml = Files.isRegularFile(xmlFile) ? Files.readString(xmlFile, StandardCharsets.UTF_8) : null;
            Path graphFile = cacheBucketDir.resolve(key + ".json");
            SqlflowJSON graph = Files.isRegularFile(graphFile)
                    ? objectMapper.readValue(Files.readString(graphFile, StandardCharsets.UTF_8), SqlflowJSON.class)
                    : null;
            SessionEntry loaded = new SessionEntry(key, sql, "dbvoracle", df, graph, xml, Instant.now().toEpochMilli());
            memoryCache.put(key, loaded);
            return loaded;
        } catch (IOException e) {
            log.warn("Failed reading cached session {}: {}", key, e.getMessage());
            return null;
        }
    }

    public void removeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        memoryCache.remove(sessionId);
        String bucket = computeBucket(sessionId);
        deleteQuietly(config.getDataDir().resolve("session/sqlflow").resolve(bucket).resolve(sessionId + ".dataflow.json"));
        deleteQuietly(config.getDataDir().resolve("session/sqlflow").resolve(bucket).resolve(sessionId + ".sql"));
        deleteQuietly(config.getDataDir().resolve("session/sqlflow").resolve(bucket).resolve(sessionId + ".dataflow.xml"));
        deleteQuietly(config.getTmpDir().resolve("cache/sqlflow").resolve(bucket).resolve(sessionId + ".json"));
    }

    public void evictSession(String sessionId) {
        removeSession(sessionId);
    }

    public DataFlow loadDataFlow(String sessionId) {
        SessionEntry e = loadSession(sessionId);
        return e != null ? e.dataFlow() : null;
    }

    public SqlflowJSON loadGraph(String sessionId) {
        SessionEntry e = loadSession(sessionId);
        return e != null ? e.graph() : null;
    }

    public void saveDataFlow(String sessionId, DataFlow df) {
        SessionEntry existing = loadSession(sessionId);
        saveSession(
                sessionId,
                existing != null ? existing.sqlText() : "",
                existing != null ? existing.dbvendor() : "dbvoracle",
                df,
                existing != null ? existing.graph() : null,
                existing != null ? existing.xml() : null);
    }

    public void saveGraph(String sessionId, SqlflowJSON graph) {
        SessionEntry existing = loadSession(sessionId);
        DataFlow df = existing != null && existing.dataFlow() != null
                ? existing.dataFlow()
                : (graph != null ? graph.getSqlflow() : null);
        saveSession(
                sessionId,
                existing != null ? existing.sqlText() : "",
                existing != null ? existing.dbvendor() : "dbvoracle",
                df,
                graph,
                existing != null ? existing.xml() : null);
    }

    /**
     * Purges {@code data/session} and {@code tmp} caches on user schema upload/delete (01 §3.3.6).
     */
    public void purgeAll() {
        memoryCache.clear();
        deleteDirectoryContents(config.getDataDir().resolve("session"));
        deleteDirectoryContents(config.getTmpDir());
        try {
            Files.createDirectories(config.getDataDir().resolve("session/sqlflow"));
            Files.createDirectories(config.getTmpDir().resolve("cache/sqlflow"));
        } catch (IOException ignored) {
        }
    }

    private void evictOldest() {
        memoryCache.entrySet().stream()
                .min(Comparator.comparingLong(e -> e.getValue().createdAtEpochMs()))
                .ifPresent(oldest -> memoryCache.remove(oldest.getKey()));
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private static void deleteDirectoryContents(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(dir))
                    .forEach(SessionCacheService::deleteQuietly);
        } catch (IOException ignored) {
        }
    }

    public record SessionEntry(
            String sessionId,
            String sqlText,
            String dbvendor,
            DataFlow dataFlow,
            SqlflowJSON graph,
            String xml,
            long createdAtEpochMs) {
    }
}
