package io.sqlflow.connectors.jdbc.harvester;

import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.jdbc.SchemaFilterMatcher;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Oracle & Dameng live catalog harvester ({@code 02 §6.3.3 Oracle}):
 * <ul>
 *   <li>{@code ALL_TAB_COLUMNS} + {@code ALL_OBJECTS} + {@code ALL_COL_COMMENTS} (with {@code DBA_*} privilege upgrade when accessible)</li>
 *   <li>{@code ALL_CONSTRAINTS} + {@code ALL_CONS_COLUMNS} ({@code P}, {@code U}, {@code R})</li>
 *   <li>{@code ALL_VIEWS} &amp; {@code ALL_MVIEWS}</li>
 *   <li>{@code ALL_SOURCE} multi-line PL/SQL package/procedure/function/trigger reassembly</li>
 *   <li>{@code ALL_ARGUMENTS}, {@code ALL_SYNONYMS}, {@code ALL_SEQUENCES}, {@code ALL_DB_LINKS}</li>
 * </ul>
 */
public final class OracleMetadataHarvester extends AbstractVendorHarvester {

    private static final String SQL_COLUMNS_WITH_COMMENTS = """
            SELECT c.OWNER,
                   c.TABLE_NAME,
                   c.COLUMN_NAME,
                   c.DATA_TYPE,
                   c.DATA_LENGTH,
                   c.DATA_PRECISION,
                   c.DATA_SCALE,
                   c.NULLABLE,
                   c.DATA_DEFAULT,
                   c.COLUMN_ID,
                   o.OBJECT_TYPE,
                   cm.COMMENTS
              FROM ALL_TAB_COLUMNS c
              LEFT JOIN ALL_OBJECTS o
                ON o.OWNER = c.OWNER AND o.OBJECT_NAME = c.TABLE_NAME
               AND o.OBJECT_TYPE IN ('TABLE', 'VIEW', 'MATERIALIZED VIEW')
              LEFT JOIN ALL_COL_COMMENTS cm
                ON cm.OWNER = c.OWNER AND cm.TABLE_NAME = c.TABLE_NAME AND cm.COLUMN_NAME = c.COLUMN_NAME
             ORDER BY c.OWNER, c.TABLE_NAME, c.COLUMN_ID
            """;

    private static final String SQL_COLUMNS_MINIMAL = """
            SELECT OWNER,
                   TABLE_NAME,
                   COLUMN_NAME,
                   DATA_TYPE,
                   DATA_LENGTH,
                   DATA_PRECISION,
                   DATA_SCALE,
                   NULLABLE,
                   DATA_DEFAULT,
                   COLUMN_ID
              FROM ALL_TAB_COLUMNS
             ORDER BY OWNER, TABLE_NAME, COLUMN_ID
            """;

    private static final String SQL_CONSTRAINTS = """
            SELECT c.OWNER,
                   c.TABLE_NAME,
                   cc.COLUMN_NAME,
                   c.CONSTRAINT_TYPE
              FROM ALL_CONSTRAINTS c
              JOIN ALL_CONS_COLUMNS cc
                ON c.OWNER = cc.OWNER
               AND c.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
               AND c.TABLE_NAME = cc.TABLE_NAME
             WHERE c.CONSTRAINT_TYPE IN ('P', 'U', 'R')
             ORDER BY c.OWNER, c.TABLE_NAME, cc.POSITION
            """;

    private static final String SQL_VIEWS = """
            SELECT OWNER, VIEW_NAME, TEXT
              FROM ALL_VIEWS
             ORDER BY OWNER, VIEW_NAME
            """;

    private static final String SQL_MVIEWS = """
            SELECT OWNER, MVIEW_NAME, QUERY
              FROM ALL_MVIEWS
             ORDER BY OWNER, MVIEW_NAME
            """;

    private static final String SQL_SOURCE = """
            SELECT OWNER, NAME, TYPE, LINE, TEXT
              FROM ALL_SOURCE
             WHERE TYPE IN ('PACKAGE', 'PACKAGE BODY', 'PROCEDURE', 'FUNCTION', 'TRIGGER')
             ORDER BY OWNER, NAME, TYPE, LINE
            """;

    private static final String SQL_ARGUMENTS = """
            SELECT OWNER, PACKAGE_NAME, OBJECT_NAME, ARGUMENT_NAME, DATA_TYPE, IN_OUT, POSITION
              FROM ALL_ARGUMENTS
             WHERE DATA_LEVEL = 0
             ORDER BY OWNER, PACKAGE_NAME, OBJECT_NAME, POSITION
            """;

    private static final String SQL_SYNONYMS = """
            SELECT OWNER, SYNONYM_NAME, TABLE_OWNER, TABLE_NAME, DB_LINK
              FROM ALL_SYNONYMS
             ORDER BY OWNER, SYNONYM_NAME
            """;

    private static final String SQL_SEQUENCES = """
            SELECT SEQUENCE_OWNER, SEQUENCE_NAME, MIN_VALUE, MAX_VALUE, INCREMENT_BY, CYCLE_FLAG, ORDER_FLAG, CACHE_SIZE
              FROM ALL_SEQUENCES
             ORDER BY SEQUENCE_OWNER, SEQUENCE_NAME
            """;

    private static final String SQL_DB_LINKS = """
            SELECT OWNER, DB_LINK, USERNAME, HOST
              FROM ALL_DB_LINKS
             ORDER BY OWNER, DB_LINK
            """;

    @Override
    public void harvest(
            Connection conn,
            EDbVendor vendor,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) throws SQLException {

        // 1. Columns + Tables/Views
        boolean loadedRich = runQuery(conn, SQL_COLUMNS_WITH_COMMENTS, timeoutSeconds, builder, "ALL_TAB_COLUMNS(join)", false,
                (rs, row) -> processColumnRow(row, filter, builder));
        if (!loadedRich) {
            runQuery(conn, SQL_COLUMNS_MINIMAL, timeoutSeconds, builder, "ALL_TAB_COLUMNS", true,
                    (rs, row) -> processColumnRow(row, filter, builder));
        }

        // 2. PK / UQ / FK Constraints
        runQuery(conn, SQL_CONSTRAINTS, timeoutSeconds, builder, "ALL_CONSTRAINTS", false, (rs, row) -> {
            String owner = row.strTrim("OWNER");
            String table = row.strTrim("TABLE_NAME");
            String col = row.strTrim("COLUMN_NAME");
            String type = row.strTrim("CONSTRAINT_TYPE");
            if (owner != null && filter.acceptsSchema(null, owner)) {
                builder.markConstraintColumn(null, owner, table, col, type);
            }
        });

        // 3. Views & Materialized Views
        if (filter.shouldHarvestViews()) {
            runQuery(conn, SQL_VIEWS, timeoutSeconds, builder, "ALL_VIEWS", false, (rs, row) -> {
                String owner = row.strTrim("OWNER");
                String viewName = row.strTrim("VIEW_NAME");
                String text = row.str("TEXT");
                if (owner != null && viewName != null && filter.acceptsView(null, owner, viewName)) {
                    builder.addViewDefinition(null, owner, viewName, text, false);
                }
            });
            runQuery(conn, SQL_MVIEWS, timeoutSeconds, builder, "ALL_MVIEWS", false, (rs, row) -> {
                String owner = row.strTrim("OWNER");
                String mviewName = row.strTrim("MVIEW_NAME");
                String query = row.str("QUERY");
                if (owner != null && mviewName != null && filter.acceptsView(null, owner, mviewName)) {
                    builder.addViewDefinition(null, owner, mviewName, query, true);
                }
            });
        }

        // 4. Stored Procedures, Functions, Packages, Triggers from ALL_SOURCE
        if (filter.shouldHarvestRoutines()) {
            Map<SourceKey, StringBuilder> sourceBlocks = new LinkedHashMap<>();
            runQuery(conn, SQL_SOURCE, timeoutSeconds, builder, "ALL_SOURCE", false, (rs, row) -> {
                String owner = row.strTrim("OWNER");
                String name = row.strTrim("NAME");
                String type = row.strTrim("TYPE");
                String lineText = row.str("TEXT");
                if (owner == null || name == null || type == null || !filter.acceptsRoutine(null, owner, name)) {
                    return;
                }
                SourceKey key = new SourceKey(owner, name, type.toUpperCase(Locale.ROOT));
                StringBuilder sb = sourceBlocks.computeIfAbsent(key, k -> new StringBuilder());
                if (lineText != null) {
                    sb.append(lineText);
                    if (!lineText.endsWith("\n")) {
                        sb.append('\n');
                    }
                }
            });

            for (Map.Entry<SourceKey, StringBuilder> entry : sourceBlocks.entrySet()) {
                SourceKey key = entry.getKey();
                String rawBody = entry.getValue().toString().trim();
                if (rawBody.isEmpty()) {
                    continue;
                }
                String ddl = ensureOracleCreateOrReplace(key.owner, key.rawTextPrefix(), rawBody);
                if ("PACKAGE".equals(key.type) || "PACKAGE BODY".equals(key.type)) {
                    builder.getOrCreatePackage(null, key.owner, key.name);
                    String fileKind = "PACKAGE BODY".equals(key.type) ? "package_body" : "package";
                    builder.addQuerySource(null, key.owner, key.name, fileKind, ddl, fileKind + "/" + key.name + ".sql");
                } else {
                    builder.addRoutine(null, key.owner, null, key.name, key.type, ddl);
                }
            }

            runQuery(conn, SQL_ARGUMENTS, timeoutSeconds, builder, "ALL_ARGUMENTS", false, (rs, row) -> {
                String owner = row.strTrim("OWNER");
                String pkg = row.strTrim("PACKAGE_NAME");
                String objName = row.strTrim("OBJECT_NAME");
                String argName = row.strTrim("ARGUMENT_NAME");
                String dataType = row.strTrim("DATA_TYPE");
                String inOut = row.strTrim("IN_OUT");
                if (owner != null && objName != null && filter.acceptsRoutine(null, owner, pkg != null ? pkg : objName)) {
                    builder.addRoutineArgument(null, owner, pkg, objName, argName, dataType, inOut);
                }
            });
        }

        // 5. Synonyms, Sequences, DB Links
        runQuery(conn, SQL_SYNONYMS, timeoutSeconds, builder, "ALL_SYNONYMS", false, (rs, row) -> {
            String owner = row.strTrim("OWNER");
            String synName = row.strTrim("SYNONYM_NAME");
            String targetOwner = row.strTrim("TABLE_OWNER");
            String targetName = row.strTrim("TABLE_NAME");
            String dbLink = row.strTrim("DB_LINK");
            if (owner != null && ("PUBLIC".equalsIgnoreCase(owner) || filter.acceptsSchema(null, owner))) {
                builder.addSynonym(null, owner, synName, null, targetOwner, targetName, dbLink);
            }
        });

        runQuery(conn, SQL_SEQUENCES, timeoutSeconds, builder, "ALL_SEQUENCES", false, (rs, row) -> {
            String owner = row.strTrim("SEQUENCE_OWNER");
            String seqName = row.strTrim("SEQUENCE_NAME");
            if (owner != null && filter.acceptsSchema(null, owner)) {
                builder.addSequence(
                        null,
                        owner,
                        seqName,
                        null,
                        row.strTrim("INCREMENT_BY"),
                        row.strTrim("MIN_VALUE"),
                        row.strTrim("MAX_VALUE"),
                        parseNullableFlag(row.strTrim("CYCLE_FLAG")),
                        parseNullableFlag(row.strTrim("ORDER_FLAG")),
                        row.strTrim("CACHE_SIZE"));
            }
        });

        runQuery(conn, SQL_DB_LINKS, timeoutSeconds, builder, "ALL_DB_LINKS", false, (rs, row) ->
                builder.addDbLink(row.strTrim("OWNER"), row.strTrim("DB_LINK"), row.strTrim("USERNAME"), row.strTrim("HOST")));
    }

    private static void processColumnRow(RowAccessor row, SchemaFilterMatcher filter, CatalogBuilder builder) throws SQLException {
        String owner = row.strTrim("OWNER");
        String tableName = row.strTrim("TABLE_NAME");
        String colName = row.strTrim("COLUMN_NAME");
        if (owner == null || tableName == null || colName == null || tableName.startsWith("BIN$")) {
            return;
        }
        if (!filter.acceptsSchema(null, owner)) {
            return;
        }
        String objType = row.strTrim("OBJECT_TYPE");
        boolean isView = objType != null && objType.toUpperCase(Locale.ROOT).contains("VIEW");
        if (isView && !filter.acceptsView(null, owner, tableName)) {
            return;
        }
        DbObject parent = isView
                ? builder.getOrCreateView(null, owner, tableName)
                : builder.getOrCreateTable(null, owner, tableName);
        String formattedType = formatPrecisionScale(
                row.strTrim("DATA_TYPE"),
                row.intObj("DATA_LENGTH"),
                row.intObj("DATA_PRECISION"),
                row.intObj("DATA_SCALE"));
        Column col = builder.addOrUpdateColumn(
                parent,
                colName,
                formattedType,
                parseNullableFlag(row.strTrim("NULLABLE")),
                row.str("DATA_DEFAULT"),
                row.str("COMMENTS"));
        if (col != null && "MATERIALIZED VIEW".equalsIgnoreCase(objType)) {
            parent.setSubType("materialized_view");
        }
    }

    private static String ensureOracleCreateOrReplace(String owner, String typeKeyword, String body) {
        String upper = body.stripLeading().toUpperCase(Locale.ROOT);
        if (upper.startsWith("CREATE ")) {
            return body;
        }
        // ALL_SOURCE typically begins with "PROCEDURE foo..." or "PACKAGE pkg..." without "CREATE OR REPLACE"
        String trimmed = body.stripLeading();
        if (upper.startsWith(typeKeyword + " ")) {
            String rest = trimmed.substring(typeKeyword.length()).stripLeading();
            if (!rest.toUpperCase(Locale.ROOT).startsWith(owner.toUpperCase(Locale.ROOT) + ".")
                    && !rest.startsWith("\"" + owner + "\".")) {
                return "CREATE OR REPLACE " + typeKeyword + " " + owner + "." + rest;
            }
        }
        return "CREATE OR REPLACE " + trimmed;
    }

    private record SourceKey(String owner, String name, String type) {
        String rawTextPrefix() {
            return type;
        }
    }
}
