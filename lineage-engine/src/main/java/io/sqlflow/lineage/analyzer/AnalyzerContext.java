package io.sqlflow.lineage.analyzer;

import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.ResultSetType;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.contracts.vendor.DataObjectType;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundColumn;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundObject;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundPackage;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundProcess;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundRoutine;
import io.sqlflow.lineage.analyzer.ModelBindingManager.BoundSourceRef;
import io.sqlflow.lineage.ast.QueryAst.QueryExpr;
import io.sqlflow.lineage.ast.SqlStmt;
import io.sqlflow.lineage.catalog.TSQLEnv;
import io.sqlflow.lineage.lexer.SourceSpan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared state and scoped execution context for AST-to-DataFlow semantic analysis.
 */
public final class AnalyzerContext {

    private static final Set<String> BUILTIN_FUNCTIONS = Set.of(
            "ABS", "ACOS", "ADD_MONTHS", "ARRAY_AGG", "ARRAY_CONSTRUCT", "ASCII", "ASIN", "ATAN", "AVG",
            "BITAND", "BOOL_AND", "BOOL_OR", "CAST", "CEIL", "CEILING", "CHARINDEX", "CHR", "COALESCE",
            "COLLECT_LIST", "COLLECT_SET", "CONCAT", "CONCAT_WS", "CONVERT", "COUNT", "COUNT_IF", "CUME_DIST",
            "CURRENT_DATE", "CURRENT_TIMESTAMP", "CURRENT_USER", "DATEADD", "DATEDIFF", "DATE_ADD",
            "DATE_DIFF", "DATE_FORMAT", "DATE_PART", "DATE_SUB", "DATE_TRUNC", "DAY", "DECODE", "DENSE_RANK",
            "EXP", "EXPLODE", "EXTRACT", "FIRST", "FIRST_VALUE", "FLATTEN", "FLOOR", "FORMAT", "FROM_JSON",
            "FROM_UNIXTIME", "GENERATE_SERIES", "GETDATE", "GET_JSON_OBJECT", "GREATEST", "GROUPING",
            "GROUP_CONCAT", "HASH", "HEX", "HOUR", "IF", "IFNULL", "IIF", "INITCAP", "INSTR", "ISNULL",
            "JSON_EXTRACT", "JSON_EXTRACT_PATH_TEXT", "JSON_VALUE", "LAG", "LAST", "LAST_DAY", "LAST_VALUE",
            "LCASE", "LEAD", "LEAST", "LEFT", "LEN", "LENGTH", "LISTAGG", "LN", "LOG", "LOWER", "LPAD",
            "LTRIM", "MAX", "MD5", "MEDIAN", "MIN", "MINUTE", "MOD", "MONTH", "MONTHS_BETWEEN", "NOW",
            "NTILE", "NULLIF", "NVL", "NVL2", "OBJECT_CONSTRUCT", "OCTET_LENGTH", "PARSE_JSON",
            "PERCENTILE_CONT", "PERCENTILE_DISC", "PERCENT_RANK", "POSEXPLODE", "POSITION", "POW", "POWER",
            "QUARTER", "RAND", "RANDOM", "RANK", "REGEXP_EXTRACT", "REGEXP_LIKE", "REGEXP_REPLACE",
            "REGEXP_SUBSTR", "REPEAT", "REPLACE", "REVERSE", "RIGHT", "ROUND", "ROW_NUMBER", "RPAD", "RTRIM",
            "SAFE_CAST", "SECOND", "SHA1", "SHA2", "SIGN", "SIN", "SIZE", "SPLIT", "SPLIT_PART", "SQRT",
            "STDDEV", "STDDEV_POP", "STDDEV_SAMP", " STRING_AGG", "STRING_AGG", "STRPOS", "STRUCT", "SUBSTR",
            "SUBSTRING", "SUM", "SYSDATE", "SYSTIMESTAMP", "TAN", "TO_CHAR", "TO_DATE", "TO_JSON",
            "TO_NUMBER", "TO_TIMESTAMP", "TO_VARCHAR", "TRANSLATE", "TRIM", "TRUNC", "TRUNCATE", "TRY_CAST",
            "TRY_CONVERT", "TYPEOF", "UCASE", "UNNEST", "UPPER", "UUID", "VARIANCE", "VAR_POP", "VAR_SAMP",
            "WEEK", "YEAR", "ZEROIFNULL");

    private final ModelBindingManager mgr;
    private final TSQLEnv env;
    private final Option option;
    private EDbVendor vendor;
    private String sourceSql;
    private String sqlHash;

    private BoundPackage enclosingPackage;
    private BoundRoutine enclosingRoutine;
    private BoundProcess activeProcess;
    private BoundObject triggerTable;

    private final List<BoundSourceRef> controlPredicates;
    private final Map<String, BoundObject> variables;
    private final Map<String, BoundObject> cursors;
    private final Map<String, QueryExpr> cursorQueries;
    private final Map<String, String> staticStringVars;
    private final Map<String, SqlStmt.CreateRoutineStmt> localRoutineAsts;
    private final Map<String, QueryExpr> viewAsts;
    private int dynamicDepth;
    private int routineCallDepth;

    public AnalyzerContext(ModelBindingManager mgr, TSQLEnv env, Option option, EDbVendor vendor) {
        this.mgr = mgr;
        this.env = env;
        this.option = option != null ? option : new Option();
        this.vendor = vendor != null ? vendor : EDbVendor.dbvoracle;
        this.controlPredicates = new ArrayList<>();
        this.variables = new LinkedHashMap<>();
        this.cursors = new LinkedHashMap<>();
        this.cursorQueries = new LinkedHashMap<>();
        this.staticStringVars = new LinkedHashMap<>();
        this.localRoutineAsts = new LinkedHashMap<>();
        this.viewAsts = new LinkedHashMap<>();
    }

    private AnalyzerContext(AnalyzerContext base, boolean isolateRoutineLocals) {
        this.mgr = base.mgr;
        this.env = base.env;
        this.option = base.option;
        this.vendor = base.vendor;
        this.sourceSql = base.sourceSql;
        this.sqlHash = base.sqlHash;
        this.enclosingPackage = base.enclosingPackage;
        this.enclosingRoutine = base.enclosingRoutine;
        this.activeProcess = base.activeProcess;
        this.triggerTable = base.triggerTable;
        this.controlPredicates = new ArrayList<>(base.controlPredicates);
        this.variables = isolateRoutineLocals ? new LinkedHashMap<>(base.variables) : base.variables;
        this.cursors = isolateRoutineLocals ? new LinkedHashMap<>(base.cursors) : base.cursors;
        this.cursorQueries = isolateRoutineLocals ? new LinkedHashMap<>(base.cursorQueries) : base.cursorQueries;
        this.staticStringVars = isolateRoutineLocals ? new LinkedHashMap<>(base.staticStringVars) : base.staticStringVars;
        this.localRoutineAsts = base.localRoutineAsts;
        this.viewAsts = base.viewAsts;
        this.dynamicDepth = base.dynamicDepth;
        this.routineCallDepth = base.routineCallDepth;
    }

    public AnalyzerContext withUnit(String sql, String hash, EDbVendor unitVendor) {
        AnalyzerContext child = new AnalyzerContext(this, false);
        child.sourceSql = sql;
        child.sqlHash = hash;
        if (unitVendor != null) {
            child.vendor = unitVendor;
        }
        return child;
    }

    public AnalyzerContext withPackage(BoundPackage pkg) {
        AnalyzerContext child = new AnalyzerContext(this, true);
        child.enclosingPackage = pkg;
        return child;
    }

    public AnalyzerContext withRoutine(BoundRoutine routine, BoundObject trigTbl) {
        AnalyzerContext child = new AnalyzerContext(this, true);
        child.enclosingRoutine = routine;
        child.triggerTable = trigTbl;
        child.routineCallDepth = this.routineCallDepth + 1;
        return child;
    }

    public AnalyzerContext withProcess(BoundProcess proc) {
        AnalyzerContext child = new AnalyzerContext(this, false);
        child.activeProcess = proc;
        return child;
    }

    public AnalyzerContext withControlPredicates(List<BoundSourceRef> extraPreds) {
        if (extraPreds == null || extraPreds.isEmpty()) {
            return this;
        }
        AnalyzerContext child = new AnalyzerContext(this, false);
        child.controlPredicates.addAll(extraPreds);
        return child;
    }

    public AnalyzerContext withDynamicSql(String innerSql, EDbVendor innerVendor) {
        AnalyzerContext child = new AnalyzerContext(this, false);
        child.sourceSql = innerSql;
        if (innerVendor != null) {
            child.vendor = innerVendor;
        }
        child.dynamicDepth = this.dynamicDepth + 1;
        return child;
    }

    public String extractSql(SourceSpan span) {
        if (span == null || sourceSql == null || sourceSql.isEmpty()) {
            return null;
        }
        int s = Math.max(0, Math.min(span.startOffset(), sourceSql.length()));
        int e = Math.max(s, Math.min(span.endOffset(), sourceSql.length()));
        String slice = sourceSql.substring(s, e).trim();
        return slice.isEmpty() ? null : slice;
    }

    public List<Transform> makeTransforms(String type, SourceSpan span, String fallbackCode) {
        if (!option.isTransform()) {
            return null;
        }
        String code = extractSql(span);
        if (code == null || code.isBlank()) {
            code = fallbackCode;
        }
        if (code == null || code.isBlank()) {
            return null;
        }
        List<Coordinate> coords = option.isTransformCoordinate() ? mgr.toCoords(span, sqlHash) : null;
        return List.of(new Transform(type, code, coords));
    }

    public String varKey(String name) {
        if (name == null) {
            return "";
        }
        String clean = name.trim();
        if (clean.startsWith("@@")) {
            clean = clean.substring(2);
        } else if (clean.startsWith("@") || clean.startsWith(":")) {
            clean = clean.substring(1);
        }
        return env.lookupKey(DataObjectType.COLUMN, clean, false);
    }

    public BoundObject bindVariable(String rawName, SourceSpan span) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String key = varKey(rawName);
        BoundObject existing = variables.get(key);
        if (existing != null) {
            existing.addCoordinates(mgr.toCoords(span, sqlHash));
            return existing;
        }
        String procId = enclosingRoutine != null ? enclosingRoutine.getId() : null;
        BoundObject varObj = mgr.createResultSet(ResultSetType.variable, rawName, false, procId, sqlHash, span);
        varObj.getOrCreateColumn(mgr, rawName, false, span, sqlHash);
        variables.put(key, varObj);
        if (enclosingPackage != null && enclosingRoutine == null) {
            enclosingPackage.getVariables().add(varObj);
        }
        return varObj;
    }

    public BoundObject findVariable(String rawName) {
        return rawName == null ? null : variables.get(varKey(rawName));
    }

    public BoundObject bindCursor(String cursorName, QueryExpr query, SourceSpan span) {
        String key = varKey(cursorName);
        BoundObject curObj = cursors.get(key);
        if (curObj == null) {
            String procId = enclosingRoutine != null ? enclosingRoutine.getId() : null;
            curObj = mgr.createResultSet(ResultSetType.cursor, cursorName, false, procId, sqlHash, span);
            cursors.put(key, curObj);
        } else {
            curObj.addCoordinates(mgr.toCoords(span, sqlHash));
        }
        if (query != null) {
            cursorQueries.put(key, query);
        }
        return curObj;
    }

    public BoundObject findCursor(String cursorName) {
        return cursorName == null ? null : cursors.get(varKey(cursorName));
    }

    public QueryExpr findCursorQuery(String cursorName) {
        return cursorName == null ? null : cursorQueries.get(varKey(cursorName));
    }

    public BoundSourceRef toSourceRef(BoundObject obj, BoundColumn col, String alias, String clauseType, SourceSpan span) {
        return new BoundSourceRef(
                col,
                obj,
                alias,
                clauseType,
                mgr.toCoords(span, sqlHash),
                makeTransforms(clauseType != null ? clauseType.toLowerCase(Locale.ROOT) : "select", span, null),
                null,
                null,
                null);
    }

    public static boolean isBuiltInFunction(String fnName) {
        if (fnName == null || fnName.isBlank()) {
            return false;
        }
        return BUILTIN_FUNCTIONS.contains(fnName.trim().toUpperCase(Locale.ROOT));
    }

    public ModelBindingManager mgr() {
        return mgr;
    }

    public TSQLEnv env() {
        return env;
    }

    public Option option() {
        return option;
    }

    public EDbVendor vendor() {
        return vendor;
    }

    public String sourceSql() {
        return sourceSql;
    }

    public String sqlHash() {
        return sqlHash;
    }

    public BoundPackage enclosingPackage() {
        return enclosingPackage;
    }

    public BoundRoutine enclosingRoutine() {
        return enclosingRoutine;
    }

    public String routineId() {
        return enclosingRoutine != null ? enclosingRoutine.getId() : null;
    }

    public BoundProcess activeProcess() {
        return activeProcess;
    }

    public BoundObject triggerTable() {
        return triggerTable;
    }

    public List<BoundSourceRef> controlPredicates() {
        return controlPredicates;
    }

    public Map<String, String> staticStringVars() {
        return staticStringVars;
    }

    public Map<String, SqlStmt.CreateRoutineStmt> localRoutineAsts() {
        return localRoutineAsts;
    }

    public Map<String, QueryExpr> viewAsts() {
        return viewAsts;
    }

    public int dynamicDepth() {
        return dynamicDepth;
    }

    public int routineCallDepth() {
        return routineCallDepth;
    }
}
