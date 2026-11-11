package io.sqlflow.connectors.jdbc.harvester;

import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.jdbc.SchemaFilterMatcher;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sybase ASE / Sybase IQ / SQL Anywhere ({@code dbvsybase}, {@code dbvsybasease}, {@code dbvsybaseiq},
 * {@code dbvsqlanywhere}) and IBM Informix ({@code dbvinformix}) live catalog harvester ({@code 02 §6.3.3}).
 */
public final class SybaseInformixMetadataHarvester extends AbstractVendorHarvester {

    // Sybase ASE catalog queries
    private static final String SQL_SYBASE_COLUMNS = """
            SELECT u.name AS schema_name,
                   o.name AS table_name,
                   o.type AS object_type,
                   c.name AS column_name,
                   t.name AS data_type,
                   c.length AS col_length,
                   c.prec AS col_prec,
                   c.scale AS col_scale,
                   c.status AS col_status,
                   c.colid
              FROM sysobjects o
              JOIN syscolumns c ON o.id = c.id
              JOIN systypes t ON c.usertype = t.usertype
              LEFT JOIN sysusers u ON o.uid = u.uid
             WHERE o.type IN ('U', 'V')
             ORDER BY u.name, o.name, c.colid
            """;

    private static final String SQL_SYBASE_COMMENTS = """
            SELECT u.name AS schema_name,
                   o.name AS object_name,
                   o.type AS object_type,
                   m.colid,
                   m.text
              FROM sysobjects o
              JOIN syscomments m ON o.id = m.id
              LEFT JOIN sysusers u ON o.uid = u.uid
             WHERE o.type IN ('V', 'P', 'SF', 'F', 'TR')
             ORDER BY u.name, o.name, m.colid
            """;

    // Informix catalog queries
    private static final String SQL_INFORMIX_COLUMNS = """
            SELECT t.owner AS schema_name,
                   t.tabname AS table_name,
                   t.tabtype AS table_type,
                   c.colname AS column_name,
                   c.coltype,
                   c.collength,
                   c.colno
              FROM systables t
              JOIN syscolumns c ON t.tabid = c.tabid
             WHERE t.tabid >= 100 AND t.tabtype IN ('T', 'V', 'E')
             ORDER BY t.owner, t.tabname, c.colno
            """;

    private static final String SQL_INFORMIX_VIEWS = """
            SELECT t.owner AS schema_name,
                   t.tabname AS view_name,
                   v.seqno,
                   v.viewtext
              FROM systables t
              JOIN sysviews v ON t.tabid = v.tabid
             WHERE t.tabid >= 100
             ORDER BY t.owner, t.tabname, v.seqno
            """;

    private static final String SQL_INFORMIX_PROCEDURES = """
            SELECT p.owner AS schema_name,
                   p.procname AS proc_name,
                   p.isproc,
                   b.seqno,
                   b.data
              FROM sysprocedures p
              JOIN sysprocbody b ON p.procid = b.procid
             WHERE b.datakey = 'T'
             ORDER BY p.owner, p.procname, b.seqno
            """;

    @Override
    public void harvest(
            Connection conn,
            EDbVendor vendor,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) throws SQLException {

        String db = resolveCurrentDatabase(conn, dataSource != null ? dataSource.getDatabase() : null, "default_db");
        if (vendor == EDbVendor.dbvinformix) {
            harvestInformix(conn, db, filter, builder, timeoutSeconds);
        } else {
            harvestSybase(conn, db, filter, builder, timeoutSeconds);
        }
    }

    private void harvestSybase(
            Connection conn,
            String db,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) {

        runQuery(conn, SQL_SYBASE_COLUMNS, timeoutSeconds, builder, "sysobjects/syscolumns", true, (rs, row) -> {
            String schema = firstNonBlank(row.strTrim("schema_name"), "dbo");
            String table = row.strTrim("table_name");
            String colName = row.strTrim("column_name");
            if (table == null || colName == null || !filter.acceptsSchema(db, schema)) {
                return;
            }
            boolean isView = "V".equalsIgnoreCase(row.strTrim("object_type"));
            if (isView && !filter.acceptsView(db, schema, table)) {
                return;
            }
            DbObject parent = isView
                    ? builder.getOrCreateView(db, schema, table)
                    : builder.getOrCreateTable(db, schema, table);
            Integer status = row.intObj("col_status");
            Boolean nullable = status != null ? ((status & 8) != 0) : null;
            String formatted = formatPrecisionScale(
                    row.strTrim("data_type"),
                    row.intObj("col_length"),
                    row.intObj("col_prec"),
                    row.intObj("col_scale"));
            var col = builder.addOrUpdateColumn(parent, colName, formatted, nullable, null, null);
            if (col != null && status != null && (status & 128) != 0) {
                col.setIdentity(true);
            }
        });

        Map<ObjKey, StringBuilder> sources = new LinkedHashMap<>();
        runQuery(conn, SQL_SYBASE_COMMENTS, timeoutSeconds, builder, "syscomments", false, (rs, row) -> {
            String schema = firstNonBlank(row.strTrim("schema_name"), "dbo");
            String name = row.strTrim("object_name");
            String type = row.strTrim("object_type");
            String text = row.str("text");
            if (name != null && type != null && text != null) {
                sources.computeIfAbsent(new ObjKey(schema, name, type.trim().toUpperCase(Locale.ROOT)),
                        k -> new StringBuilder()).append(text);
            }
        });

        for (Map.Entry<ObjKey, StringBuilder> e : sources.entrySet()) {
            ObjKey k = e.getKey();
            String sql = e.getValue().toString().trim();
            if ("V".equals(k.type)) {
                if (filter.acceptsView(db, k.schema, k.name)) {
                    builder.addViewDefinition(db, k.schema, k.name, sql, false);
                }
            } else if (filter.acceptsRoutine(db, k.schema, k.name)) {
                String rtype = switch (k.type) {
                    case "SF", "F" -> "function";
                    case "TR" -> "trigger";
                    default -> "procedure";
                };
                builder.addRoutine(db, k.schema, null, k.name, rtype, sql);
            }
        }
    }

    private void harvestInformix(
            Connection conn,
            String db,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) {

        runQuery(conn, SQL_INFORMIX_COLUMNS, timeoutSeconds, builder, "systables/syscolumns", true, (rs, row) -> {
            String schema = firstNonBlank(row.strTrim("schema_name"), "informix");
            String table = row.strTrim("table_name");
            String colName = row.strTrim("column_name");
            if (table == null || colName == null || !filter.acceptsSchema(db, schema)) {
                return;
            }
            boolean isView = "V".equalsIgnoreCase(row.strTrim("table_type"));
            if (isView && !filter.acceptsView(db, schema, table)) {
                return;
            }
            DbObject parent = isView
                    ? builder.getOrCreateView(db, schema, table)
                    : builder.getOrCreateTable(db, schema, table);
            Integer coltype = row.intObj("coltype");
            Integer collength = row.intObj("collength");
            boolean nullable = coltype == null || (coltype & 256) == 0;
            builder.addOrUpdateColumn(parent, colName, decodeInformixType(coltype, collength), nullable, null, null);
        });

        if (filter.shouldHarvestViews()) {
            Map<ObjKey, StringBuilder> views = new LinkedHashMap<>();
            runQuery(conn, SQL_INFORMIX_VIEWS, timeoutSeconds, builder, "sysviews", false, (rs, row) -> {
                String schema = firstNonBlank(row.strTrim("schema_name"), "informix");
                String name = row.strTrim("view_name");
                String chunk = row.str("viewtext");
                if (name != null && chunk != null && filter.acceptsView(db, schema, name)) {
                    views.computeIfAbsent(new ObjKey(schema, name, "V"), k -> new StringBuilder()).append(chunk);
                }
            });
            for (Map.Entry<ObjKey, StringBuilder> e : views.entrySet()) {
                builder.addViewDefinition(db, e.getKey().schema, e.getKey().name, e.getValue().toString(), false);
            }
        }

        if (filter.shouldHarvestRoutines()) {
            Map<ObjKey, StringBuilder> procs = new LinkedHashMap<>();
            runQuery(conn, SQL_INFORMIX_PROCEDURES, timeoutSeconds, builder, "sysprocbody", false, (rs, row) -> {
                String schema = firstNonBlank(row.strTrim("schema_name"), "informix");
                String name = row.strTrim("proc_name");
                String isproc = row.strTrim("isproc");
                String chunk = row.str("data");
                if (name != null && chunk != null && filter.acceptsRoutine(db, schema, name)) {
                    String kind = "f".equalsIgnoreCase(isproc) ? "function" : "procedure";
                    procs.computeIfAbsent(new ObjKey(schema, name, kind), k -> new StringBuilder()).append(chunk);
                }
            });
            for (Map.Entry<ObjKey, StringBuilder> e : procs.entrySet()) {
                builder.addRoutine(db, e.getKey().schema, null, e.getKey().name, e.getKey().type, e.getValue().toString());
            }
        }
    }

    private static String decodeInformixType(Integer coltype, Integer collength) {
        if (coltype == null) {
            return "VARCHAR";
        }
        int base = coltype & 255;
        return switch (base) {
            case 0 -> "CHAR" + (collength != null && collength > 0 ? "(" + collength + ")" : "");
            case 1 -> "SMALLINT";
            case 2, 6 -> "INTEGER";
            case 3 -> "FLOAT";
            case 4 -> "SMALLFLOAT";
            case 5 -> "DECIMAL";
            case 7 -> "DATE";
            case 8 -> "MONEY";
            case 10 -> "DATETIME";
            case 11 -> "BYTE";
            case 12 -> "TEXT";
            case 13 -> "VARCHAR" + (collength != null && collength > 0 ? "(" + (collength & 255) + ")" : "");
            case 14 -> "INTERVAL";
            case 15 -> "NCHAR";
            case 16 -> "NVARCHAR";
            case 17, 18, 52, 53 -> "BIGINT";
            case 43 -> "LVARCHAR";
            case 45 -> "BOOLEAN";
            default -> "VARCHAR";
        };
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private record ObjKey(String schema, String name, String type) {
    }
}
