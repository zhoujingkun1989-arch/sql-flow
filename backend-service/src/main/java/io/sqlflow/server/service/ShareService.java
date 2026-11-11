package io.sqlflow.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.domain.ShareInfo;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.security.CryptoService;
import io.sqlflow.server.security.SqlflowBusinessException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Manages shared lineage graphs ({@code data/share/{urlEncUserId}/{shareId}.json} and {@code share.json})
 * and short permalinks ({@code data/permalink/{%02d}/{id}.json}) ({@code 01 §3.3.5}, {@code OBL-025}).
 */
@Service
public class ShareService {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final SqlflowRuntimeConfig config;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public ShareService(SqlflowRuntimeConfig config, CryptoService cryptoService, ObjectMapper objectMapper) {
        this.config = config;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
    }

    private Path userShareDir(String userId) {
        String effective = (userId == null || userId.isBlank())
                ? SqlflowRuntimeConfig.DEFAULT_GUEST_USER_ID
                : userId.trim();
        return config.getDataDir()
                .resolve("share")
                .resolve(URLEncoder.encode(effective, StandardCharsets.UTF_8));
    }

    private Path permalinkFile(String id) {
        String safeId = sanitizeId(id);
        int shard = Math.floorMod(safeId.hashCode(), 100);
        return config.getDataDir()
                .resolve("permalink")
                .resolve(String.format("%02d", shard))
                .resolve(safeId + ".json");
    }

    private static String sanitizeId(String id) {
        if (id == null || id.isBlank()) {
            throw SqlflowBusinessException.badRequest("shareId is required");
        }
        return id.trim().replaceAll("[^a-zA-Z0-9_\\-.]", "_");
    }

    public synchronized ShareInfo createShare(
            String userId,
            SqlflowJSON graph,
            String sessionId,
            String jobId,
            boolean tableLevel,
            String title,
            String seedText) {
        String effectiveUser = (userId == null || userId.isBlank())
                ? SqlflowRuntimeConfig.DEFAULT_GUEST_USER_ID
                : userId.trim();
        String hashBasis = effectiveUser + "|" + tableLevel + "|"
                + (sessionId != null ? sessionId : "") + "|"
                + (jobId != null ? jobId : "") + "|"
                + (seedText != null ? seedText : Instant.now().toString());
        String shareId = cryptoService.md5Hex(hashBasis);

        Date now = new Date();
        if (graph != null) {
            graph.setShareId(shareId);
            graph.setTableLevel(tableLevel);
            graph.setUserId(effectiveUser);
            graph.setCreateTime(TS_FMT.format(now.toInstant()));
        }

        ShareInfo info = new ShareInfo(shareId, now, tableLevel, effectiveUser);
        info.setSessionId(sessionId);
        info.setJobId(jobId);
        info.setTitle(title != null && !title.isBlank() ? title : ("Shared Lineage " + shareId.substring(0, 8)));

        try {
            Path uDir = userShareDir(effectiveUser);
            Files.createDirectories(uDir);
            Path graphPath = uDir.resolve(shareId + ".json");
            objectMapper.writeValue(graphPath.toFile(), graph != null ? graph : new SqlflowJSON());

            Path pFile = permalinkFile(shareId);
            Files.createDirectories(pFile.getParent());
            objectMapper.writeValue(pFile.toFile(), graph != null ? graph : new SqlflowJSON());

            List<ShareInfo> existing = new ArrayList<>(readShareIndex(uDir));
            existing.removeIf(s -> shareId.equals(s.getShareId()));
            existing.add(0, info);
            objectMapper.writeValue(uDir.resolve("share.json").toFile(), existing);
            return info;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist shared graph: " + e.getMessage(), e);
        }
    }

    public SqlflowJSON getSharedGraph(String shareId) {
        String safeId = sanitizeId(shareId);
        Path pFile = permalinkFile(safeId);
        try {
            if (Files.isRegularFile(pFile)) {
                return objectMapper.readValue(pFile.toFile(), SqlflowJSON.class);
            }
            Path shareRoot = config.getDataDir().resolve("share");
            if (Files.isDirectory(shareRoot)) {
                try (Stream<Path> userDirs = Files.list(shareRoot)) {
                    for (Path uDir : userDirs.filter(Files::isDirectory).toList()) {
                        Path candidate = uDir.resolve(safeId + ".json");
                        if (Files.isRegularFile(candidate)) {
                            return objectMapper.readValue(candidate.toFile(), SqlflowJSON.class);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read shared graph: " + safeId, e);
        }
        throw new SqlflowBusinessException(404, "Shared graph does not exist: " + safeId);
    }

    public List<ShareInfo> listUserShares(String userId) {
        Path uDir = userShareDir(userId);
        return readShareIndex(uDir);
    }

    public synchronized boolean deleteUserShare(String userId, String shareId) {
        String safeId = sanitizeId(shareId);
        Path uDir = userShareDir(userId);
        try {
            boolean removed = Files.deleteIfExists(uDir.resolve(safeId + ".json"));
            removed |= Files.deleteIfExists(permalinkFile(safeId));
            List<ShareInfo> current = new ArrayList<>(readShareIndex(uDir));
            boolean idxRemoved = current.removeIf(s -> safeId.equals(s.getShareId()));
            if (idxRemoved) {
                Files.createDirectories(uDir);
                objectMapper.writeValue(uDir.resolve("share.json").toFile(), current);
            }
            return removed || idxRemoved;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete shared graph: " + safeId, e);
        }
    }

    // Permalink endpoints (`/permalink/*`)
    public boolean checkPermalinkExistence(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return Files.isRegularFile(permalinkFile(id));
    }

    public Map<String, Object> createPermalink(String id, Object payload, String userId) {
        String effectiveId = (id != null && !id.isBlank())
                ? sanitizeId(id)
                : cryptoService.md5Hex((userId != null ? userId : "") + "|" + System.nanoTime()).substring(0, 12);
        Path target = permalinkFile(effectiveId);
        try {
            Files.createDirectories(target.getParent());
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("id", effectiveId);
            envelope.put("userId", userId != null ? userId : SqlflowRuntimeConfig.DEFAULT_GUEST_USER_ID);
            envelope.put("createTime", TS_FMT.format(Instant.now()));
            envelope.put("data", payload);
            objectMapper.writeValue(target.toFile(), envelope);
            return Map.of("id", effectiveId, "shareId", effectiveId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create permalink: " + e.getMessage(), e);
        }
    }

    public Object getPermalinkData(String id) {
        Path target = permalinkFile(id);
        if (!Files.isRegularFile(target)) {
            throw new SqlflowBusinessException(404, "Permalink not found: " + id);
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(target.toFile(), new TypeReference<>() {});
            if (raw.containsKey("data") && raw.containsKey("id") && raw.size() <= 5) {
                return raw.get("data");
            }
            return raw;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read permalink data: " + id, e);
        }
    }

    private List<ShareInfo> readShareIndex(Path uDir) {
        Path indexFile = uDir.resolve("share.json");
        if (!Files.isRegularFile(indexFile)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(indexFile.toFile(), new TypeReference<List<ShareInfo>>() {});
        } catch (IOException e) {
            return List.of();
        }
    }
}
