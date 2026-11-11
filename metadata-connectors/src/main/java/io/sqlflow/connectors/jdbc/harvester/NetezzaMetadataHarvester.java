package io.sqlflow.connectors.jdbc.harvester;

import io.sqlflow.connectors.jdbc.CatalogBuilder;
import io.sqlflow.connectors.jdbc.SchemaFilterMatcher;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * IBM Netezza ({@code dbvnetezza}) live catalog harvester ({@code 02 §6.3.3 Netezza}):
 * <ul>
 *   <li>{@code _v_relation_column}, {@code _v_table}, {@code _v_view}</li>
 *   <li>{@code _v_relation_keydata} ({@code p}/{@code u}/{@code f} constraints)</li>
 *   <li>{@code _v_procedure}, {@code _v_synonym}, {@code _v_sequence}</li>
 * </ul>
 */
public final class NetezzaMetadataHarvester extends AbstractVendorHarvester {

    private static final String SQL_COLUMNS = """
            SELECT DATABASE,
                   SCHEMA,
                   NAME AS TABLE_NAME,
                   TYPE AS OBJECT_TYPE,
                   ATTNAME AS COLUMN_NAME,
                   FORMAT_TYPE AS DATA_TYPE,
                   ATTNOTNULL,
                   COLDEFAULT,
                   DESCRIPTION,
                   ATTNUM
              FROM _v_relation_column
             WHERE TYPE IN ('TABLE', 'VIEW', 'MATERIALIZED VIEW', 'EXTERNAL TABLE')
             ORDER BY SCHEMA, NAME, ATTNUM
            """;

    private static final String SQL_KEYS = """
            SELECT DATABASE,
                   SCHEMA,
                   RELATION AS TABLE_NAME,
                   ATTNAME AS COLUMN_NAME,
                   CONTYPE
              FROM _v_relation_keydata
             ORDER BY SCHEMA, RELATION, CONSEQ
            """;

    private static final String SQL_VIEWS = """
            SELECT DATABASE,
                   SCHEMA,
                   VIEWNAME,
                   DEFINITION
              FROM _v_view
             ORDER BY SCHEMA, VIEWNAME
            """;

    private static final String SQL_PROCEDURES = """
            SELECT DATABASE,
                   SCHEMA,
                   PROCEDURE AS PROC_NAME,
                   PROCEDURESIGNATURE,
                   RETURNS,
                   PROCEDURESOURCE
              FROM _v_procedure
             WHERE BUILTIN = 'f' OR BUILTIN IS NULL OR BUILTIN = 0
             ORDER BY SCHEMA, PROCEDURE
            """;

    private static final String SQL_SYNONYMS = """
            SELECT DATABASE,
                   SCHEMA,
                   SYNONYM_NAME,
                   REFDATABASE,
                   REFSCHEMA,
                   REFOBJNAME
              FROM _v_synonym
             ORDER BY SCHEMA, SYNONYM_NAME
            """;

    private static final String SQL_SEQUENCES = """
            SELECT DATABASE,
                   SCHEMA,
                   SEQNAME
              FROM _v_sequence
             ORDER BY SCHEMA, SEQNAME
            """;

    @Override
    public void harvest(
            Connection conn,
            EDbVendor vendor,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) throws SQLException {

        String defaultDb = resolveCurrentDatabase(conn, dataSource != null ? dataSource.getDatabase() : null, "SYSTEM");

        runQuery(conn, SQL_COLUMNS, timeoutSeconds, builder, "_v_relation_column", true, (rs, row) -> {
            String db = firstNonBlank(row.strTrim("DATABASE"), defaultDb);
            String schema = firstNonBlank(row.strTrim("SCHEMA"), "ADMIN");
            String table = row.strTrim("TABLE_NAME");
            String colName = row.strTrim("COLUMN_NAME");
            if (table == null || colName == null || !filter.acceptsSchema(db, schema)) {
                return;
            }
            String objType = row.strTrim("OBJECT_TYPE");
            boolean isView = objType != null && objType.toUpperCase(Locale.ROOT).contains("VIEW");
            if (isView && !filter.acceptsView(db, schema, table)) {
                return;
            }
            DbObject parent = isView
                    ? builder.getOrCreateView(db, schema, table)
                    : builder.getOrCreateTable(db, schema, table);
            Boolean notNull = row.boolObj("ATTNOTNULL");
            Boolean nullable = notNull != null ? !notNull : null;
            builder.addOrUpdateColumn(
                    parent,
                    colName,
                    row.strTrim("DATA_TYPE"),
                    nullable,
                    row.strTrim("COLDEFAULT"),
                    row.str("DESCRIPTION"));
        });

        runQuery(conn, SQL_KEYS, timeoutSeconds, builder, "_v_relation_keydata", false, (rs, row) -> {
            String db = firstNonBlank(row.strTrim("DATABASE"), defaultDb);
            String schema = firstNonBlank(row.strTrim("SCHEMA"), "ADMIN");
            if (filter.acceptsSchema(db, schema)) {
                builder.markConstraintColumn(db, schema, row.strTrim("TABLE_NAME"), row.strTrim("COLUMN_NAME"), row.strTrim("CONTYPE"));
            }
        });

        if (filter.shouldHarvestViews()) {
            runQuery(conn, SQL_VIEWS, timeoutSeconds, builder, "_v_view", false, (rs, row) -> {
                String db = firstNonBlank(row.strTrim("DATABASE"), defaultDb);
                String schema = firstNonBlank(row.strTrim("SCHEMA"), "ADMIN");
                String viewName = row.strTrim("VIEWNAME");
                if (viewName != null && filter.acceptsView(db, schema, viewName)) {
                    builder.addViewDefinition(db, schema, viewName, row.str("DEFINITION"), false);
                }
            });
        }

        if (filter.shouldHarvestRoutines()) {
            runQuery(conn, SQL_PROCEDURES, timeoutSeconds, builder, "_v_procedure", false, (rs, row) -> {
                String db = firstNonBlank(row.strTrim("DATABASE"), defaultDb);
                String schema = firstNonBlank(row.strTrim("SCHEMA"), "ADMIN");
                String procName = row.strTrim("PROC_NAME");
                String sig = row.strTrim("PROCEDURESIGNATURE");
                String ret = row.strTrim("RETURNS");
                String body = row.str("PROCEDURESOURCE");
                if (procName != null && filter.acceptsRoutine(db, schema, procName)) {
                    String ddl = wrapNetezzaProcedure(db, schema, sig != null ? sig : procName + "()", ret, body);
                    builder.addRoutine(db, schema, null, procName, "procedure", ddl);
                }
            });
        }

        runQuery(conn, SQL_SYNONYMS, timeoutSeconds, builder, "_v_synonym", false, (rs, row) -> {
            String db = firstNonBlank(row.strTrim("DATABASE"), defaultDb);
            String schema = firstNonBlank(row.strTrim("SCHEMA"), "ADMIN");
            if (filter.acceptsSchema(db, schema)) {
                builder.addSynonym(
                        db,
                        schema,
                        row.strTrim("SYNONYM_NAME"),
                        row.strTrim("REFDATABASE"),
                        row.strTrim("REFSCHEMA"),
                        row.strTrim("REFOBJNAME"),
                        null);
            }
        });

        runQuery(conn, SQL_SEQUENCES, timeoutSeconds, builder, "_v_sequence", false, (rs, row) -> {
            String db = firstNonBlank(row.strTrim("DATABASE"), defaultDb);
            String schema = firstNonBlank(row.strTrim("SCHEMA"), "ADMIN");
            if (filter.acceptsSchema(db, schema)) {
                builder.addSequence(db, schema, row.strTrim("SEQNAME"), null, null, null, null, null, null, null);
            }
        });
    }

    private static String wrapNetezzaProcedure(String db, String schema, String signature, String returns, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        if (body.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE ")) {
            return body;
        }
        String retClause = (returns != null && !returns.isBlank()) ? " RETURNS " + returns : " RETURNS INT4";
        return "CREATE OR REPLACE PROCEDURE " + db + "." + schema + "." + signature + retClause
                + " LANGUAGE NZPLSQL AS\nBEGIN_PROC\n" + body + "\nEND_PROC;";
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
