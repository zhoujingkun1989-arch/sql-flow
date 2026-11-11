package io.sqlflow.lineage.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogColumn;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogRoutine;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogSequence;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogSynonym;
import io.sqlflow.lineage.catalog.TSQLEnv.CatalogTable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Binary snapshot codec ({@code sqlenv.bin}) for incremental cross-batch {@link TSQLEnv} persistence
 * ({@code OBL-050}, {@code 03 §2.2}).
 */
public final class SqlenvSnapshotCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private SqlenvSnapshotCodec() {
    }

    public static byte[] toSnapshot(TSQLEnv env) {
        if (env == null) {
            return new byte[0];
        }
        SnapshotPayload payload = new SnapshotPayload();
        payload.setVersion(1);
        payload.setVendor(env.vendor().name());
        payload.setCurrentServer(env.currentServer());
        payload.setCurrentDatabase(env.currentDatabase());
        payload.setCurrentSchema(env.currentSchema());
        payload.setTables(new ArrayList<>(env.allTables()));
        payload.setSynonyms(new ArrayList<>(env.allSynonyms()));
        payload.setRoutines(new ArrayList<>(env.allRoutines()));
        payload.setSequences(new ArrayList<>(env.allSequences()));

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
            try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
                MAPPER.writeValue(gzip, payload);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize TSQLEnv snapshot", e);
        }
    }

    public static void loadSnapshot(TSQLEnv env, byte[] snapshotBytes) {
        if (env == null || snapshotBytes == null || snapshotBytes.length == 0) {
            return;
        }
        try {
            SnapshotPayload payload;
            if (isGzip(snapshotBytes)) {
                try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(snapshotBytes))) {
                    payload = MAPPER.readValue(gis, SnapshotPayload.class);
                }
            } else {
                payload = MAPPER.readValue(snapshotBytes, SnapshotPayload.class);
            }
            if (payload == null) {
                return;
            }
            if (env.currentServer() == null && payload.getCurrentServer() != null) {
                env.setCurrentServer(payload.getCurrentServer());
            }
            if (env.currentDatabase() == null && payload.getCurrentDatabase() != null) {
                env.setCurrentDatabase(payload.getCurrentDatabase());
            }
            if (env.currentSchema() == null && payload.getCurrentSchema() != null) {
                env.setCurrentSchema(payload.getCurrentSchema());
            }
            if (payload.getTables() != null) {
                for (CatalogTable t : payload.getTables()) {
                    if (t == null || t.getName() == null) {
                        continue;
                    }
                    CatalogTable target = env.registerTable(
                            t.getServer(),
                            t.getDatabase(),
                            t.getSchema(),
                            t.getName(),
                            t.isQuoted(),
                            t.getObjectType(),
                            t.isFromDdl());
                    if (target != null) {
                        target.setViewSql(t.getViewSql());
                        for (CatalogColumn c : t.orderedColumns()) {
                            env.addOrUpdateColumn(target, c, false);
                        }
                        if (t.getForeignKeys() != null) {
                            t.getForeignKeys().forEach(target::addForeignKey);
                        }
                    }
                }
            }
            if (payload.getSynonyms() != null) {
                payload.getSynonyms().forEach(env::registerSynonym);
            }
            if (payload.getRoutines() != null) {
                payload.getRoutines().forEach(env::registerRoutine);
            }
            if (payload.getSequences() != null) {
                payload.getSequences().forEach(env::registerSequence);
            }
        } catch (IOException e) {
            // Gracefully ignore corrupt snapshot bytes rather than crashing the batch
        }
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes.length >= 2
                && (bytes[0] & 0xFF) == 0x1F
                && (bytes[1] & 0xFF) == 0x8B;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class SnapshotPayload {
        private int version;
        private String vendor;
        private String currentServer;
        private String currentDatabase;
        private String currentSchema;
        private List<CatalogTable> tables;
        private List<CatalogSynonym> synonyms;
        private List<CatalogRoutine> routines;
        private List<CatalogSequence> sequences;

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public String getVendor() {
            return vendor;
        }

        public void setVendor(String vendor) {
            this.vendor = vendor;
        }

        public String getCurrentServer() {
            return currentServer;
        }

        public void setCurrentServer(String currentServer) {
            this.currentServer = currentServer;
        }

        public String getCurrentDatabase() {
            return currentDatabase;
        }

        public void setCurrentDatabase(String currentDatabase) {
            this.currentDatabase = currentDatabase;
        }

        public String getCurrentSchema() {
            return currentSchema;
        }

        public void setCurrentSchema(String currentSchema) {
            this.currentSchema = currentSchema;
        }

        public List<CatalogTable> getTables() {
            return tables;
        }

        public void setTables(List<CatalogTable> tables) {
            this.tables = tables;
        }

        public List<CatalogSynonym> getSynonyms() {
            return synonyms;
        }

        public void setSynonyms(List<CatalogSynonym> synonyms) {
            this.synonyms = synonyms;
        }

        public List<CatalogRoutine> getRoutines() {
            return routines;
        }

        public void setRoutines(List<CatalogRoutine> routines) {
            this.routines = routines;
        }

        public List<CatalogSequence> getSequences() {
            return sequences;
        }

        public void setSequences(List<CatalogSequence> sequences) {
            this.sequences = sequences;
        }
    }
}
