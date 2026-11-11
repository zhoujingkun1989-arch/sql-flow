package io.sqlflow.lineage.lexer;

import io.sqlflow.contracts.vendor.EDbVendor;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Dialect-aware lexical rules, reserved SQL keywords, built-in data types, and built-in SQL functions
 * across all 48 {@link EDbVendor} dialects.
 */
public final class SqlDialectRules {

    private static Set<String> wordSet(String... words) {
        return Set.copyOf(Arrays.asList(words));
    }

    private static final Set<String> CORE_RESERVED_KEYWORDS = wordSet(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "MERGE",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "TABLE", "VIEW", "INDEX", "DATABASE", "SCHEMA",
            "PROCEDURE", "FUNCTION", "TRIGGER", "PACKAGE", "SYNONYM", "SEQUENCE", "STAGE", "STREAM",
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL", "ON", "USING",
            "GROUP", "BY", "HAVING", "ORDER", "ASC", "DESC", "NULLS", "LIMIT", "OFFSET", "FETCH",
            "UNION", "INTERSECT", "EXCEPT", "MINUS", "ALL", "DISTINCT", "AS", "WITH", "RECURSIVE",
            "CASE", "WHEN", "THEN", "ELSE", "END", "AND", "OR", "NOT", "IN", "EXISTS", "BETWEEN",
            "LIKE", "ILIKE", "IS", "NULL", "TRUE", "FALSE", "PRIMARY", "FOREIGN", "UNIQUE", "KEY",
            "REFERENCES", "CONSTRAINT", "CHECK", "DEFAULT", "DECLARE", "BEGIN", "IF", "ELSIF",
            "ELSEIF", "WHILE", "FOR", "LOOP", "RETURN", "RETURNS", "OPEN", "CLOSE", "CURSOR",
            "EXEC", "EXECUTE", "CALL", "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "OVER", "PARTITION",
            "WINDOW", "QUALIFY", "PIVOT", "UNPIVOT", "LATERAL", "CONNECT", "PRIOR"
    );

    private static final Set<String> NON_RESERVED_CONTEXT_KEYWORDS = wordSet(
            "REPLACE", "MATERIALIZED", "TEMPORARY", "TEMP", "VOLATILE", "TRANSIENT", "EXTERNAL",
            "OVERWRITE", "DIRECTORY", "LOCAL", "GLOBAL", "BODY", "TYPE", "ROWTYPE", "RECORD",
            "OUT", "INOUT", "PIPELINED", "PIPE", "ROW", "BULK", "COLLECT", "IMMEDIATE",
            "MATCHED", "TARGET", "SOURCE", "SWAP", "CLONE", "EXCHANGE", "SWITCH", "RENAME",
            "ADD", "COLUMN", "COLUMNS", "ROWS", "RANGE", "UNBOUNDED", "PRECEDING", "FOLLOWING",
            "CURRENT", "FIRST", "LAST", "NEXT", "ONLY", "TOP", "PERCENT", "TIES", "SAMPLE",
            "TABLESAMPLE", "UNNEST", "FLATTEN", "EXPLODE", "POSEXPLODE", "INLINE", "APPLY",
            "SEMI", "ANTI", "START", "NOCYCLE", "SIBLINGS", "CUBE", "ROLLUP", "GROUPING", "SETS",
            "OUTPUT", "RETURNING", "COPY", "UNLOAD", "LOAD", "DATA", "INPATH", "LOCATION",
            "FORMAT", "FILE_FORMAT", "PARQUET", "CSV", "JSON", "ORC", "AVRO", "DELTA", "ICEBERG",
            "USE", "SEARCH_PATH", "SESSION", "CURRENT_SCHEMA", "COMMENT", "IDENTITY", "AUTOINCREMENT",
            "AUTO_INCREMENT", "GENERATED", "ALWAYS", "STORED", "VIRTUAL", "PERSISTED", "EXCLUDE",
            "LET", "MEMBER", "NON", "EMPTY", "AXIS", "PAGES", "CHAPTERS", "SECTIONS", "PROPERTIES"
    );

    private static final Set<String> BUILTIN_DATATYPES = wordSet(
            "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "BYTEINT", "HUGEINT",
            "INT2", "INT4", "INT8", "INT16", "INT32", "INT64", "UINT8", "UINT16", "UINT32", "UINT64",
            "NUMBER", "NUMERIC", "DECIMAL", "DEC", "FLOAT", "FLOAT4", "FLOAT8", "DOUBLE", "REAL",
            "MONEY", "SMALLMONEY", "BIGNUMERIC", "BIGDECIMAL",
            "CHAR", "CHARACTER", "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "NCHAR", "STRING",
            "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB", "NCLOB", "BPCHAR", "SYSNAME",
            "BINARY", "VARBINARY", "BLOB", "LONGBLOB", "BYTEA", "BYTES", "RAW",
            "BOOLEAN", "BOOL", "BIT",
            "DATE", "TIME", "DATETIME", "DATETIME2", "SMALLDATETIME", "TIMESTAMP", "TIMESTAMPTZ",
            "TIMESTAMP_NTZ", "TIMESTAMP_LTZ", "TIMESTAMP_TZ", "INTERVAL", "YEAR", "MONTH", "DAY",
            "JSON", "JSONB", "XML", "XMLTYPE", "VARIANT", "OBJECT", "ARRAY", "MAP", "STRUCT", "SUPER",
            "UUID", "UNIQUEIDENTIFIER", "ROWID", "UROWID", "GEOGRAPHY", "GEOMETRY", "SERIAL", "BIGSERIAL"
    );

    private static final Set<String> BUILTIN_FUNCTIONS = wordSet(
            // Aggregates
            "COUNT", "SUM", "AVG", "MIN", "MAX", "LISTAGG", "STRING_AGG", "GROUP_CONCAT",
            "ARRAY_AGG", "COLLECT_LIST", "COLLECT_SET", "ANY_VALUE", "ARBITRARY", "BOOL_AND",
            "BOOL_OR", "BIT_AND", "BIT_OR", "STDDEV", "STDDEV_POP", "STDDEV_SAMP", "VARIANCE",
            "VAR_POP", "VAR_SAMP", "CORR", "COVAR_POP", "APPROX_COUNT_DISTINCT", "APPROX_DISTINCT",
            "MEDIAN", "PERCENTILE_CONT", "PERCENTILE_DISC", "OBJECT_AGG", "JSON_ARRAYAGG", "JSON_OBJECTAGG",
            // Window
            "ROW_NUMBER", "RANK", "DENSE_RANK", "NTILE", "LEAD", "LAG", "FIRST_VALUE", "LAST_VALUE",
            "NTH_VALUE", "CUME_DIST", "PERCENT_RANK", "RATIO_TO_REPORT",
            // Null / Conditional
            "COALESCE", "NVL", "NVL2", "IFNULL", "ISNULL", "NULLIF", "ZEROIFNULL", "LNNVL",
            "DECODE", "IIF", "IF", "GREATEST", "LEAST",
            // String
            "CONCAT", "CONCAT_WS", "SUBSTR", "SUBSTRING", "LEFT", "RIGHT", "LENGTH", "CHAR_LENGTH",
            "LEN", "DATALENGTH", "UPPER", "LOWER", "UCASE", "LCASE", "INITCAP", "TRIM", "LTRIM",
            "RTRIM", "BTRIM", "LPAD", "RPAD", "REPLACE", "TRANSLATE", "REGEXP_REPLACE", "REGEXP_SUBSTR",
            "REGEXP_EXTRACT", "REGEXP_LIKE", "REGEXP_INSTR", "SPLIT", "SPLIT_PART", "STRPOS", "INSTR",
            "CHARINDEX", "POSITION", "REVERSE", "REPEAT", "SPACE", "ASCII", "CHR", "CHAR", "FORMAT",
            "PRINTF", "SOUNDEX", "MD5", "SHA1", "SHA2", "SHA256", "HASH", "BASE64_ENCODE", "TO_HEX",
            // Numeric / Math
            "ABS", "ROUND", "TRUNC", "TRUNCATE", "CEIL", "CEILING", "FLOOR", "MOD", "POWER", "POW",
            "SQRT", "EXP", "LN", "LOG", "LOG10", "LOG2", "SIGN", "SIN", "COS", "TAN", "ASIN", "ACOS",
            "ATAN", "ATAN2", "RAND", "RANDOM", "WIDTH_BUCKET", "SAFE_DIVIDE",
            // Date / Time
            "NOW", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "SYSDATE", "SYSTIMESTAMP",
            "GETDATE", "GETUTCDATE", "SYSDATETIME", "DATEADD", "DATEDIFF", "DATE_ADD", "DATE_SUB",
            "DATE_DIFF", "DATE_TRUNC", "DATETRUNC", "DATE_PART", "DATEPART", "EXTRACT", "TO_DATE",
            "TO_TIMESTAMP", "TO_CHAR", "TO_NUMBER", "STR_TO_DATE", "DATE_FORMAT", "PARSE_DATE",
            "PARSE_TIMESTAMP", "FORMAT_DATE", "FORMAT_TIMESTAMP", "ADD_MONTHS", "MONTHS_BETWEEN",
            "LAST_DAY", "NEXT_DAY", "EOMONTH", "FROM_UNIXTIME", "UNIX_TIMESTAMP", "EPOCH",
            // Conversion & Semi-structured
            "CAST", "TRY_CAST", "SAFE_CAST", "CONVERT", "TRY_CONVERT", "PARSE_JSON", "TRY_PARSE_JSON",
            "TO_JSON", "TO_VARIANT", "JSON_EXTRACT", "JSON_EXTRACT_SCALAR", "JSON_VALUE", "JSON_QUERY",
            "GET_JSON_OBJECT", "GET", "GET_PATH", "OBJECT_CONSTRUCT", "ARRAY_CONSTRUCT", "ARRAY_SIZE",
            "ARRAY_LENGTH", "CARDINALITY", "SIZE", "FLATTEN", "UNNEST", "EXPLODE", "POSEXPLODE", "STRUCT",
            // System
            "USER", "CURRENT_USER", "SESSION_USER", "SYSTEM_USER", "DATABASE", "SCHEMA", "UUID_STRING",
            "NEWID", "GEN_RANDOM_UUID", "TYPEOF", "ROWNUM", "LEVEL"
    );

    private static final Set<String> AGGREGATE_FUNCTIONS = wordSet(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "LISTAGG", "STRING_AGG", "GROUP_CONCAT",
            "ARRAY_AGG", "COLLECT_LIST", "COLLECT_SET", "ANY_VALUE", "ARBITRARY", "BOOL_AND",
            "BOOL_OR", "BIT_AND", "BIT_OR", "STDDEV", "STDDEV_POP", "STDDEV_SAMP", "VARIANCE",
            "VAR_POP", "VAR_SAMP", "CORR", "COVAR_POP", "APPROX_COUNT_DISTINCT", "APPROX_DISTINCT",
            "MEDIAN", "PERCENTILE_CONT", "PERCENTILE_DISC", "OBJECT_AGG", "JSON_ARRAYAGG", "JSON_OBJECTAGG"
    );

    private SqlDialectRules() {
    }

    public static boolean isReservedKeyword(String upperWord, EDbVendor vendor) {
        if (upperWord == null || upperWord.isEmpty()) {
            return false;
        }
        if (CORE_RESERVED_KEYWORDS.contains(upperWord)) {
            return true;
        }
        if (vendor == EDbVendor.dbvoracle || vendor == EDbVendor.dbvdameng) {
            return Set.of("ROWNUM", "SYSDATE", "LEVEL", "CONNECT", "START", "PRIOR", "VARCHAR2", "NUMBER")
                    .contains(upperWord);
        }
        if (vendor == EDbVendor.dbvmssql || vendor == EDbVendor.dbvazuresql || vendor == EDbVendor.dbvsybase) {
            return Set.of("TOP", "IDENTITY", "GO", "EXEC", "CLUSTERED", "NONCLUSTERED", "TRAN", "TRANSACTION")
                    .contains(upperWord);
        }
        if (vendor == EDbVendor.dbvpostgresql || vendor == EDbVendor.dbvredshift || vendor == EDbVendor.dbvgreenplum) {
            return Set.of("ILIKE", "RETURNING", "LATERAL", "WINDOW", "VARIADIC", "ANALYZE", "OFFSET")
                    .contains(upperWord);
        }
        if (vendor == EDbVendor.dbvsnowflake) {
            return Set.of("QUALIFY", "FLATTEN", "VARIANT", "ILIKE", "LATERAL", "SAMPLE").contains(upperWord);
        }
        return false;
    }

    public static boolean isAnyKeyword(String upperWord, EDbVendor vendor) {
        return isReservedKeyword(upperWord, vendor) || NON_RESERVED_CONTEXT_KEYWORDS.contains(upperWord);
    }

    public static boolean isBuiltInDataType(String upperWord) {
        return upperWord != null && BUILTIN_DATATYPES.contains(upperWord.toUpperCase(Locale.ROOT));
    }

    public static boolean isBuiltInFunction(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String simple = name;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }
        return BUILTIN_FUNCTIONS.contains(simple.toUpperCase(Locale.ROOT));
    }

    public static boolean isAggregateFunction(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String simple = name;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }
        return AGGREGATE_FUNCTIONS.contains(simple.toUpperCase(Locale.ROOT));
    }

    public static boolean supportsBacktickQuote(EDbVendor vendor) {
        if (vendor == null) {
            return false;
        }
        return switch (vendor) {
            case dbvmysql, dbvhive, dbvimpala, dbvsparksql, dbvdatabricks, dbvbigquery,
                    dbvathena, dbvdoris, dbvstarrocks, dbvoceanbase, dbvflink, dbvcouchbase,
                    dbvsqlite, dbvclickhouse, dbvduckdb -> true;
            default -> false;
        };
    }

    public static boolean supportsBracketQuote(EDbVendor vendor) {
        if (vendor == null) {
            return false;
        }
        return switch (vendor) {
            case dbvmssql, dbvazuresql, dbvsybase, dbvsybasease, dbvsybaseiq,
                    dbvaccess, dbvsqlite, dbvmdx, dbvodbc -> true;
            default -> false;
        };
    }

    public static boolean supportsHashLineComment(EDbVendor vendor) {
        if (vendor == null) {
            return false;
        }
        return switch (vendor) {
            case dbvmysql, dbvdoris, dbvstarrocks, dbvoceanbase, dbvhive, dbvsparksql,
                    dbvdatabricks, dbvbigquery -> true;
            default -> false;
        };
    }

    public static boolean supportsDoubleSlashComment(EDbVendor vendor) {
        if (vendor == null) {
            return false;
        }
        return switch (vendor) {
            case dbvsnowflake, dbvclickhouse, dbvpowerquery, dbvcouchbase, dbvmdx -> true;
            default -> false;
        };
    }

    public static boolean supportsDollarQuotedStrings(EDbVendor vendor) {
        if (vendor == null) {
            return false;
        }
        return switch (vendor) {
            case dbvpostgresql, dbvgreenplum, dbvredshift, dbvedb, dbvgaussdb,
                    dbvsnowflake, dbvduckdb, dbvclickhouse -> true;
            default -> true; // Safe in SQL when $$tag$$ is used in modern dialects
        };
    }
}
