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
 * Teradata ({@code dbvteradata}) live catalog harvester ({@code 02 §6.3.3 Teradata}):
 * <ul>
 *   <li>{@code DBC.TablesV} ({@code TableKind} {@code T/O/V/P/E/F/R/M/G}), {@code RequestText}, and {@code DBC.TableTextV} overflow reassembly</li>
 *   <li>{@code DBC.ColumnsV} with full 34-code 2-character Teradata {@code ColumnType} decoder</li>
 *   <li>{@code DBC.IndicesV} (PK/UQ/UPI) &amp; {@code DBC.All_RI_ChildrenV} (FK)</li>
 * </ul>
 */
public final class TeradataMetadataHarvester extends AbstractVendorHarvester {

    private static final String SQL_COLUMNS = """
            SELECT c.DatabaseName,
                   c.TableName,
                   t.TableKind,
                   c.ColumnName,
                   c.ColumnType,
                   c.ColumnLength,
                   c.DecimalTotalDigits,
                   c.DecimalFractionalDigits,
                   c.Nullable,
                   c.DefaultValue,
                   c.CommentString,
                   c.IdColType,
                   c.ColumnId
              FROM DBC.ColumnsV c
              LEFT JOIN DBC.TablesV t
                ON t.DatabaseName = c.DatabaseName
               AND t.TableName = c.TableName
             ORDER BY c.DatabaseName, c.TableName, c.ColumnId
            """;

    private static final String SQL_INDICES = """
            SELECT DatabaseName,
                   TableName,
                   ColumnName,
                   IndexType,
                   UniqueFlag
              FROM DBC.IndicesV
             ORDER BY DatabaseName, TableName, ColumnPosition
            """;

    private static final String SQL_FOREIGN_KEYS = """
            SELECT ChildDB,
                   ChildTable,
                   ChildKeyColumn
              FROM DBC.All_RI_ChildrenV
            """;

    private static final String SQL_TABLES_DDL = """
            SELECT DatabaseName,
                   TableName,
                   TableKind,
                   RequestText,
                   RequestTxtOverFlow
              FROM DBC.TablesV
             WHERE TableKind IN ('V', 'P', 'E', 'F', 'R', 'M', 'G', 'I')
             ORDER BY DatabaseName, TableName
            """;

    private static final String SQL_TABLE_TEXT_OVERFLOW = """
            SELECT DatabaseName,
                   TableName,
                   TableKind,
                   LineNo,
                   RequestText
              FROM DBC.TableTextV
             ORDER BY DatabaseName, TableName, LineNo
            """;

    @Override
    public void harvest(
            Connection conn,
            EDbVendor vendor,
            DataSource dataSource,
            SchemaFilterMatcher filter,
            CatalogBuilder builder,
            int timeoutSeconds) throws SQLException {

        runQuery(conn, SQL_COLUMNS, timeoutSeconds, builder, "DBC.ColumnsV", true, (rs, row) -> {
            String db = row.strTrim("DatabaseName");
            String table = row.strTrim("TableName");
            String colName = row.strTrim("ColumnName");
            if (db == null || table == null || colName == null || !filter.acceptsSchema(db, null)) {
                return;
            }
            String kind = row.strTrim("TableKind");
            if (kind != null && !isTabularTableKind(kind)) {
                return;
            }
            boolean isView = "V".equalsIgnoreCase(kind) || "I".equalsIgnoreCase(kind);
            if (isView && !filter.acceptsView(db, null, table)) {
                return;
            }
            DbObject parent = isView
                    ? builder.getOrCreateView(db, null, table)
                    : builder.getOrCreateTable(db, null, table);

            String decodedType = decodeTeradataColumnType(
                    row.strTrim("ColumnType"),
                    row.intObj("ColumnLength"),
                    row.intObj("DecimalTotalDigits"),
                    row.intObj("DecimalFractionalDigits"));
            Column col = builder.addOrUpdateColumn(
                    parent,
                    colName,
                    decodedType,
                    parseNullableFlag(row.strTrim("Nullable")),
                    row.strTrim("DefaultValue"),
                    row.strTrim("CommentString"));
            if (col != null) {
                String idCol = row.strTrim("IdColType");
                if ("GA".equalsIgnoreCase(idCol) || "GD".equalsIgnoreCase(idCol)) {
                    col.setIdentity(true);
                }
            }
        });

        runQuery(conn, SQL_INDICES, timeoutSeconds, builder, "DBC.IndicesV", false, (rs, row) -> {
            String db = row.strTrim("DatabaseName");
            String table = row.strTrim("TableName");
            String col = row.strTrim("ColumnName");
            String idxType = row.strTrim("IndexType");
            String unique = row.strTrim("UniqueFlag");
            if (db == null || !filter.acceptsSchema(db, null)) {
                return;
            }
            if ("K".equalsIgnoreCase(idxType) || ("P".equalsIgnoreCase(idxType) && "Y".equalsIgnoreCase(unique))) {
                builder.markConstraintColumn(db, null, table, col, "P");
            } else if ("U".equalsIgnoreCase(idxType) || "Y".equalsIgnoreCase(unique)) {
                builder.markConstraintColumn(db, null, table, col, "U");
            } else {
                builder.markConstraintColumn(db, null, table, col, "INDEX");
            }
        });

        runQuery(conn, SQL_FOREIGN_KEYS, timeoutSeconds, builder, "DBC.All_RI_ChildrenV", false, (rs, row) -> {
            String db = row.strTrim("ChildDB");
            if (db != null && filter.acceptsSchema(db, null)) {
                builder.markConstraintColumn(db, null, row.strTrim("ChildTable"), row.strTrim("ChildKeyColumn"), "F");
            }
        });

        // Assemble overflow DDL chunks from DBC.TableTextV if present
        Map<String, StringBuilder> overflowText = new LinkedHashMap<>();
        runQuery(conn, SQL_TABLE_TEXT_OVERFLOW, timeoutSeconds, builder, "DBC.TableTextV", false, (rs, row) -> {
            String db = row.strTrim("DatabaseName");
            String name = row.strTrim("TableName");
            String chunk = row.str("RequestText");
            if (db != null && name != null && chunk != null && filter.acceptsSchema(db, null)) {
                overflowText.computeIfAbsent(db.toLowerCase(Locale.ROOT) + "." + name.toLowerCase(Locale.ROOT),
                        k -> new StringBuilder()).append(chunk);
            }
        });

        runQuery(conn, SQL_TABLES_DDL, timeoutSeconds, builder, "DBC.TablesV", false, (rs, row) -> {
            String db = row.strTrim("DatabaseName");
            String name = row.strTrim("TableName");
            String kind = row.strTrim("TableKind");
            if (db == null || name == null || kind == null || !filter.acceptsSchema(db, null)) {
                return;
            }
            String lookupKey = db.toLowerCase(Locale.ROOT) + "." + name.toLowerCase(Locale.ROOT);
            String ddl = overflowText.containsKey(lookupKey)
                    ? overflowText.get(lookupKey).toString()
                    : row.str("RequestText");

            String uKind = kind.toUpperCase(Locale.ROOT);
            switch (uKind) {
                case "V", "I" -> {
                    if (filter.acceptsView(db, null, name)) {
                        builder.addViewDefinition(db, null, name, ddl, "I".equals(uKind));
                    }
                }
                case "P", "E" -> {
                    if (filter.acceptsRoutine(db, null, name)) {
                        builder.addRoutine(db, null, null, name, "procedure", ddl);
                    }
                }
                case "F", "R" -> {
                    if (filter.acceptsRoutine(db, null, name)) {
                        builder.addRoutine(db, null, null, name, "function", ddl);
                    }
                }
                case "M" -> {
                    if (filter.acceptsRoutine(db, null, name)) {
                        builder.addRoutine(db, null, null, name, "macro", ddl);
                    }
                }
                case "G" -> {
                    if (filter.acceptsRoutine(db, null, name)) {
                        builder.addRoutine(db, null, null, name, "trigger", ddl);
                    }
                }
                default -> {
                }
            }
        });
    }

    private static boolean isTabularTableKind(String kind) {
        String k = kind.trim().toUpperCase(Locale.ROOT);
        return "T".equals(k) || "O".equals(k) || "V".equals(k) || "I".equals(k);
    }

    /**
     * Decodes Teradata's 2-character {@code DBC.ColumnsV.ColumnType} codes into canonical SQL type strings
     * ({@code 02 §6.3.3 Teradata} — all 34+ Teradata codes).
     */
    public static String decodeTeradataColumnType(String rawCode, Integer length, Integer totalDigits, Integer fracDigits) {
        if (rawCode == null || rawCode.isBlank()) {
            return "VARCHAR";
        }
        String code = rawCode.trim().toUpperCase(Locale.ROOT);
        // If already a full type name (e.g. from custom view), return directly
        if (code.length() > 3) {
            return formatPrecisionScale(code, length, totalDigits, fracDigits);
        }
        return switch (code) {
            case "AT" -> "TIME" + (fracDigits != null ? "(" + fracDigits + ")" : "");
            case "BF" -> "BYTE" + (length != null && length > 0 ? "(" + length + ")" : "");
            case "BO" -> "BLOB" + (length != null && length > 0 ? "(" + length + ")" : "");
            case "BV" -> "VARBYTE" + (length != null && length > 0 ? "(" + length + ")" : "");
            case "CF" -> "CHAR" + (length != null && length > 0 ? "(" + length + ")" : "");
            case "CO" -> "CLOB" + (length != null && length > 0 ? "(" + length + ")" : "");
            case "CV" -> "VARCHAR" + (length != null && length > 0 ? "(" + length + ")" : "");
            case "D" -> formatPrecisionScale("DECIMAL", null, totalDigits, fracDigits);
            case "DA" -> "DATE";
            case "DH" -> "INTERVAL DAY TO HOUR";
            case "DM" -> "INTERVAL DAY TO MINUTE";
            case "DS" -> "INTERVAL DAY TO SECOND";
            case "DY" -> "INTERVAL DAY";
            case "F" -> "FLOAT";
            case "HM" -> "INTERVAL HOUR TO MINUTE";
            case "HR" -> "INTERVAL HOUR";
            case "HS" -> "INTERVAL HOUR TO SECOND";
            case "I1" -> "BYTEINT";
            case "I2" -> "SMALLINT";
            case "I" -> "INTEGER";
            case "I8" -> "BIGINT";
            case "JN" -> "JSON";
            case "MI" -> "INTERVAL MINUTE";
            case "MO" -> "INTERVAL MONTH";
            case "MS" -> "INTERVAL MINUTE TO SECOND";
            case "N" -> totalDigits != null && totalDigits > 0
                    ? formatPrecisionScale("NUMBER", null, totalDigits, fracDigits)
                    : "NUMBER";
            case "PD" -> "PERIOD(DATE)";
            case "PM" -> "PERIOD(TIMESTAMP WITH TIME ZONE)";
            case "PS" -> "PERIOD(TIMESTAMP)";
            case "PT" -> "PERIOD(TIME)";
            case "PZ" -> "PERIOD(TIME WITH TIME ZONE)";
            case "SC" -> "INTERVAL SECOND";
            case "SZ" -> "TIMESTAMP" + (fracDigits != null ? "(" + fracDigits + ")" : "") + " WITH TIME ZONE";
            case "TS" -> "TIMESTAMP" + (fracDigits != null ? "(" + fracDigits + ")" : "");
            case "TZ" -> "TIME" + (fracDigits != null ? "(" + fracDigits + ")" : "") + " WITH TIME ZONE";
            case "UT" -> "UDT";
            case "XM" -> "XML";
            case "YM" -> "INTERVAL YEAR TO MONTH";
            case "YR" -> "INTERVAL YEAR";
            default -> code;
        };
    }
}
