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
import java.util.Map;

/**
 * IBM DB2 LUW ({@code dbvdb2}) catalog harvester ({@code 02 §6.3.3 DB2}):
 * <ul>
 *   <li>{@code SYSCAT.TABLES} &amp; {@code SYSCAT.COLUMNS}</li>
 *   <li>{@code SYSCAT.TABCONST} + {@code SYSCAT.KEYCOLUSE} ({@code P}, {@code U}, {@code F})</li>
 *   <li>{@code SYSCAT.VIEWS} (assembling multi-row {@code VIEWTEXTID} chunks)</li>
 *   <li>{@code SYSCAT.ROUTINES} + {@code SYSCAT.ROUTINEPARMS}, {@code SYSCAT.TRIGGERS}, {@code SYSCAT.SEQUENCES}, and aliases ({@code TYPE='A'})</li>
 * </ul>
 */
public final class Db2MetadataHarvester extends AbstractVendorHarvester {

    private static final String SQL_COLUMNS = """
            SELECT c.TABSCHEMA,
                   c.TABNAME,
                   t.TYPE AS TABLE_TYPE,
                   c.COLNAME,
                   c.TYPENAME,
                   c.LENGTH,
                   c.SCALE,
                   c.NULLS,
                   c."DEFAULT",
                   c."IDENTITY",
                   c.GENERATED,
                   c.TEXT AS GENERATED_TEXT,
                   c.REMARKS,
                   c.KEYSEQ,
                   c.COLNO
              FROM SYSCAT.COLUMNS c
              LEFT JOIN SYSCAT.TABLES t
                ON t.TABSCHEMA = c.TABSCHEMA AND t.TABNAME = c.TABNAME
             WHERE t.TYPE IS NULL OR t.TYPE IN ('T', 'V', 'M', 'N', 'G', 'S')
             ORDER BY c.TABSCHEMA, c.TABNAME, c.COLNO
            """;

    private static final String SQL_CONSTRAINTS = """
            SELECT tc.TABSCHEMA,
                   tc.TABNAME,
                   kcu.COLNAME,
                   tc.TYPE
              FROM SYSCAT.TABCONST tc
              JOIN SYSCAT.KEYCOLUSE kcu
                ON tc.CONSTNAME = kcu.CONSTNAME
               AND tc.TABSCHEMA = kcu.TABSCHEMA
               AND tc.TABNAME = kcu.TABNAME
             WHERE tc.TYPE IN ('P', 'U', 'F')
             ORDER BY tc.TABSCHEMA, tc.TABNAME, kcu.COLSEQ
            """;

    private static final String SQL_VIEWS = """
            SELECT VIEWSCHEMA, VIEWNAME, VIEWTEXTID, TEXT
              FROM SYSCAT.VIEWS
             ORDER BY VIEWSCHEMA, VIEWNAME, VIEWTEXTID
            """;

    private static final String SQL_ROUTINES = """
            SELECT ROUTINESCHEMA, ROUTINENAME, SPECIFICNAME, ROUTINETYPE, TEXT
              FROM SYSCAT.ROUTINES
             WHERE ROUTINETYPE IN ('P', 'F')
             ORDER BY ROUTINESCHEMA, ROUTINENAME
            """;

    private static final String SQL_ROUTINE_PARMS = """
            SELECT ROUTINESCHEMA, ROUTINENAME, PARMNAME, TYPENAME, ROWTYPE, ORDINAL
              FROM SYSCAT.ROUTINEPARMS
             ORDER BY ROUTINESCHEMA, ROUTINENAME, ORDINAL
            """;

    private static final String SQL_TRIGGERS = """
            SELECT TRIGSCHEMA, TRIGNAME, TEXT
              FROM SYSCAT.TRIGGERS
             ORDER BY TRIGSCHEMA, TRIGNAME
            """;

    private static final String SQL_ALIASES = """
            SELECT TABSCHEMA, TABNAME, BASE_TABSCHEMA, BASE_TABNAME
              FROM SYSCAT.TABLES
             WHERE TYPE = 'A'
             ORDER BY TABSCHEMA, TABNAME
            """;

    private static final String SQL_SEQUENCES = """
            SELECT SEQSCHEMA, SEQNAME, "START", "INCREMENT", "MINVALUE", "MAXVALUE", "CYCLE", "ORDER", "CACHE"
              FROM SYSCAT.SEQUENCES
             ORDER BY SEQSCHEMA, SEQNAME
            """;

    @Override
    public void harvest(
            Connection conn,
            EDbVendor vendor,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) throws SQLException {

        String db = resolveCurrentDatabase(conn, dataSource != null ? dataSource.getDatabase() : null, "SAMPLE");

        runQuery(conn, SQL_COLUMNS, timeoutSeconds, builder, "SYSCAT.COLUMNS", true, (rs, row) -> {
            String schema = row.strTrim("TABSCHEMA");
            String table = row.strTrim("TABNAME");
            String colName = row.strTrim("COLNAME");
            if (schema == null || table == null || colName == null || !filter.acceptsSchema(db, schema)) {
                return;
            }
            String tblType = row.strTrim("TABLE_TYPE");
            boolean isView = "V".equalsIgnoreCase(tblType) || "M".equalsIgnoreCase(tblType);
            if (isView && !filter.acceptsView(db, schema, table)) {
                return;
            }
            DbObject parent = isView
                    ? builder.getOrCreateView(db, schema, table)
                    : builder.getOrCreateTable(db, schema, table);
            String typeName = row.strTrim("TYPENAME");
            Integer len = row.intObj("LENGTH");
            Integer scale = row.intObj("SCALE");
            String formatted = formatPrecisionScale(typeName, len, len, scale);
            Column col = builder.addOrUpdateColumn(
                    parent,
                    colName,
                    formatted,
                    parseNullableFlag(row.strTrim("NULLS")),
                    row.strTrim("DEFAULT"),
                    row.str("REMARKS"));
            if (col != null) {
                Integer keySeq = row.intObj("KEYSEQ");
                if (keySeq != null && keySeq > 0) {
                    col.setPrimaryKey(true);
                }
                if ("Y".equalsIgnoreCase(row.strTrim("IDENTITY"))) {
                    col.setIdentity(true);
                }
                String gen = row.strTrim("GENERATED");
                if ("A".equalsIgnoreCase(gen) || "D".equalsIgnoreCase(gen)) {
                    col.setComputed(true);
                    col.setComputedDefinition(row.str("GENERATED_TEXT"));
                }
            }
        });

        runQuery(conn, SQL_CONSTRAINTS, timeoutSeconds, builder, "SYSCAT.TABCONST", false, (rs, row) -> {
            String schema = row.strTrim("TABSCHEMA");
            if (schema != null && filter.acceptsSchema(db, schema)) {
                builder.markConstraintColumn(db, schema, row.strTrim("TABNAME"), row.strTrim("COLNAME"), row.strTrim("TYPE"));
            }
        });

        if (filter.shouldHarvestViews()) {
            Map<String, StringBuilder> viewTexts = new LinkedHashMap<>();
            Map<String, String[]> viewNames = new LinkedHashMap<>();
            runQuery(conn, SQL_VIEWS, timeoutSeconds, builder, "SYSCAT.VIEWS", false, (rs, row) -> {
                String schema = row.strTrim("VIEWSCHEMA");
                String viewName = row.strTrim("VIEWNAME");
                String text = row.str("TEXT");
                if (schema != null && viewName != null && filter.acceptsView(db, schema, viewName)) {
                    String key = schema + "." + viewName;
                    viewNames.putIfAbsent(key, new String[]{schema, viewName});
                    StringBuilder sb = viewTexts.computeIfAbsent(key, k -> new StringBuilder());
                    if (text != null) {
                        sb.append(text);
                    }
                }
            });
            for (Map.Entry<String, StringBuilder> e : viewTexts.entrySet()) {
                String[] parts = viewNames.get(e.getKey());
                builder.addViewDefinition(db, parts[0], parts[1], e.getValue().toString(), false);
            }
        }

        if (filter.shouldHarvestRoutines()) {
            runQuery(conn, SQL_ROUTINES, timeoutSeconds, builder, "SYSCAT.ROUTINES", false, (rs, row) -> {
                String schema = row.strTrim("ROUTINESCHEMA");
                String name = row.strTrim("ROUTINENAME");
                String rtype = row.strTrim("ROUTINETYPE");
                String text = row.str("TEXT");
                if (schema != null && name != null && filter.acceptsRoutine(db, schema, name)) {
                    builder.addRoutine(db, schema, null, name, "F".equalsIgnoreCase(rtype) ? "function" : "procedure", text);
                }
            });

            runQuery(conn, SQL_ROUTINE_PARMS, timeoutSeconds, builder, "SYSCAT.ROUTINEPARMS", false, (rs, row) -> {
                String schema = row.strTrim("ROUTINESCHEMA");
                String name = row.strTrim("ROUTINENAME");
                if (schema != null && name != null && filter.acceptsRoutine(db, schema, name)) {
                    String rowType = row.strTrim("ROWTYPE");
                    String inOut = switch (rowType != null ? rowType.toUpperCase() : "P") {
                        case "O" -> "OUT";
                        case "B" -> "INOUT";
                        case "R", "C" -> "RETURN";
                        default -> "IN";
                    };
                    builder.addRoutineArgument(db, schema, null, name, row.strTrim("PARMNAME"), row.strTrim("TYPENAME"), inOut);
                }
            });

            runQuery(conn, SQL_TRIGGERS, timeoutSeconds, builder, "SYSCAT.TRIGGERS", false, (rs, row) -> {
                String schema = row.strTrim("TRIGSCHEMA");
                String name = row.strTrim("TRIGNAME");
                if (schema != null && name != null && filter.acceptsRoutine(db, schema, name)) {
                    builder.addRoutine(db, schema, null, name, "trigger", row.str("TEXT"));
                }
            });
        }

        runQuery(conn, SQL_ALIASES, timeoutSeconds, builder, "SYSCAT.TABLES(A)", false, (rs, row) -> {
            String schema = row.strTrim("TABSCHEMA");
            if (schema != null && filter.acceptsSchema(db, schema)) {
                builder.addSynonym(db, schema, row.strTrim("TABNAME"), db, row.strTrim("BASE_TABSCHEMA"), row.strTrim("BASE_TABNAME"), null);
            }
        });

        runQuery(conn, SQL_SEQUENCES, timeoutSeconds, builder, "SYSCAT.SEQUENCES", false, (rs, row) -> {
            String schema = row.strTrim("SEQSCHEMA");
            if (schema != null && filter.acceptsSchema(db, schema)) {
                builder.addSequence(
                        db,
                        schema,
                        row.strTrim("SEQNAME"),
                        row.strTrim("START"),
                        row.strTrim("INCREMENT"),
                        row.strTrim("MINVALUE"),
                        row.strTrim("MAXVALUE"),
                        parseNullableFlag(row.strTrim("CYCLE")),
                        parseNullableFlag(row.strTrim("ORDER")),
                        row.strTrim("CACHE"));
            }
        });
    }
}
