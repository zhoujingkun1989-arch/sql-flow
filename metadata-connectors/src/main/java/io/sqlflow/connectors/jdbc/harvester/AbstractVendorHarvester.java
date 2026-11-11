package io.sqlflow.connectors.jdbc.harvester;

import io.sqlflow.connectors.jdbc.CatalogBuilder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shared JDBC execution and case-insensitive {@link ResultSet} reader utilities for vendor harvesters.
 */
public abstract class AbstractVendorHarvester implements VendorMetadataHarvester {

    @FunctionalInterface
    protected interface RowConsumer {
        void accept(ResultSet rs, RowAccessor row) throws SQLException;
    }

    protected boolean runQuery(
            Connection conn,
            String sql,
            int timeoutSeconds,
            CatalogBuilder builder,
            String stepLabel,
            boolean warnOnFailure,
            RowConsumer consumer,
            Object... params) {
        if (params == null || params.length == 0) {
            try (Statement stmt = conn.createStatement()) {
                applyQueryTimeout(stmt, timeoutSeconds);
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    RowAccessor accessor = new RowAccessor(rs);
                    while (rs.next()) {
                        consumer.accept(rs, accessor);
                    }
                    return true;
                }
            } catch (SQLException e) {
                if (warnOnFailure && builder != null) {
                    builder.addWarning(stepLabel + " skipped: " + sanitizeSqlError(e));
                }
                return false;
            }
        } else {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                applyQueryTimeout(ps, timeoutSeconds);
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    RowAccessor accessor = new RowAccessor(rs);
                    while (rs.next()) {
                        consumer.accept(rs, accessor);
                    }
                    return true;
                }
            } catch (SQLException e) {
                if (warnOnFailure && builder != null) {
                    builder.addWarning(stepLabel + " skipped: " + sanitizeSqlError(e));
                }
                return false;
            }
        }
    }

    protected static void applyQueryTimeout(Statement stmt, int timeoutSeconds) {
        if (timeoutSeconds > 0) {
            try {
                stmt.setQueryTimeout(timeoutSeconds);
            } catch (SQLException | AbstractMethodError ignored) {
                // Some minimal JDBC drivers do not implement setQueryTimeout
            }
        }
    }

    protected static String resolveCurrentDatabase(Connection conn, String configuredDatabase, String fallback) {
        if (configuredDatabase != null && !configuredDatabase.isBlank()) {
            int q = configuredDatabase.indexOf('?');
            String cleaned = (q >= 0 ? configuredDatabase.substring(0, q) : configuredDatabase).trim();
            if (!cleaned.isEmpty()) {
                return cleaned;
            }
        }
        try {
            String cat = conn.getCatalog();
            if (cat != null && !cat.isBlank()) {
                return cat.trim();
            }
        } catch (SQLException ignored) {
        }
        return fallback;
    }

    protected static String formatPrecisionScale(String baseType, Integer length, Integer precision, Integer scale) {
        if (baseType == null || baseType.isBlank()) {
            return null;
        }
        String type = baseType.trim();
        if (type.contains("(")) {
            return type;
        }
        String upper = type.toUpperCase(Locale.ROOT);
        if (upper.contains("CHAR") || upper.contains("BINARY") || upper.equals("RAW") || upper.equals("BIT")) {
            if (length != null && length > 0 && length < 1_000_000_000) {
                return type + "(" + length + ")";
            }
            if (length != null && length == -1) {
                return type + "(MAX)";
            }
        } else if (upper.equals("NUMBER") || upper.equals("NUMERIC") || upper.equals("DECIMAL") || upper.equals("DEC")) {
            if (precision != null && precision > 0) {
                if (scale != null && scale > 0) {
                    return type + "(" + precision + "," + scale + ")";
                }
                return type + "(" + precision + ")";
            }
        }
        return type;
    }

    protected static Boolean parseNullableFlag(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if ("Y".equals(u) || "YES".equals(u) || "1".equals(u) || "TRUE".equals(u) || "T".equals(u)) {
            return Boolean.TRUE;
        }
        if ("N".equals(u) || "NO".equals(u) || "0".equals(u) || "FALSE".equals(u) || "F".equals(u)) {
            return Boolean.FALSE;
        }
        return null;
    }

    protected static String sanitizeSqlError(SQLException e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return "SQLState=" + e.getSQLState() + ", ErrorCode=" + e.getErrorCode();
        }
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, nl).trim() : msg.trim();
    }

    /**
     * Case-insensitive column lookup over a {@link ResultSet}.
     */
    protected static final class RowAccessor {
        private final ResultSet rs;
        private final Map<String, Integer> labelIndex = new HashMap<>();

        RowAccessor(ResultSet rs) throws SQLException {
            this.rs = rs;
            ResultSetMetaData meta = rs.getMetaData();
            int count = meta.getColumnCount();
            for (int i = 1; i <= count; i++) {
                String label = meta.getColumnLabel(i);
                if (label != null) {
                    labelIndex.putIfAbsent(label.toLowerCase(Locale.ROOT), i);
                }
                String name = meta.getColumnName(i);
                if (name != null) {
                    labelIndex.putIfAbsent(name.toLowerCase(Locale.ROOT), i);
                }
            }
        }

        public String str(String column) throws SQLException {
            Integer idx = labelIndex.get(column.toLowerCase(Locale.ROOT));
            if (idx == null) {
                return null;
            }
            String val = rs.getString(idx);
            return val != null ? val : null;
        }

        public String strTrim(String column) throws SQLException {
            String s = str(column);
            return s != null ? s.trim() : null;
        }

        public Integer intObj(String column) throws SQLException {
            Integer idx = labelIndex.get(column.toLowerCase(Locale.ROOT));
            if (idx == null) {
                return null;
            }
            Object raw = rs.getObject(idx);
            if (raw == null) {
                return null;
            }
            if (raw instanceof Number n) {
                return n.intValue();
            }
            try {
                return Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public Boolean boolObj(String column) throws SQLException {
            Integer idx = labelIndex.get(column.toLowerCase(Locale.ROOT));
            if (idx == null) {
                return null;
            }
            Object raw = rs.getObject(idx);
            if (raw == null) {
                return null;
            }
            if (raw instanceof Boolean b) {
                return b;
            }
            if (raw instanceof Number n) {
                return n.intValue() != 0;
            }
            return parseNullableFlag(raw.toString());
        }

        public String strAt(int oneBasedIndex) throws SQLException {
            if (oneBasedIndex < 1 || oneBasedIndex > rs.getMetaData().getColumnCount()) {
                return null;
            }
            return rs.getString(oneBasedIndex);
        }
    }
}
