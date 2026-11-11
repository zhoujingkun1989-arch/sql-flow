package io.sqlflow.graph.join;

import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinClause;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.JoinType;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.LaneKind;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.PredicateKind;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.SourceRange;
import io.sqlflow.contracts.graph.JoinAnalysisGraph.SourceRangeQuality;
import io.sqlflow.graph.join.JoinCatalogMetadata.TableMeta;
import io.sqlflow.graph.join.SqlJoinLexer.Token;
import io.sqlflow.graph.join.SqlJoinLexer.TokenKind;
import io.sqlflow.graph.model.DataFlowIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Clean-room recursive-descent SQL parser extracting inline DDL key/index definitions,
 * multi-block CTE/subquery lanes, explicit/implicit/semi/anti joins, predicates, and
 * {@code WHERE} filters with exact 1-based {@link SourceRange} coordinates.
 */
public final class SqlJoinAstParser {

    private static final Set<String> CLAUSE_STOP_WORDS = Set.of(
            "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH",
            "UNION", "INTERSECT", "EXCEPT", "MINUS", "QUALIFY", "WINDOW",
            "FOR", "INTO", "SET", "VALUES", "RETURNING", "OPTION",
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL",
            "SEMI", "ANTI", "OUTER", "ON", "USING", "WHEN", "PIVOT", "UNPIVOT");

    public static final class ParsedColRef {
        public final String qualifier;
        public final String column;
        public final String rawDisplay;
        public final SourceRange sourceRange;

        public ParsedColRef(String qualifier, String column, String rawDisplay, SourceRange sourceRange) {
            this.qualifier = qualifier;
            this.column = column;
            this.rawDisplay = rawDisplay;
            this.sourceRange = sourceRange;
        }
    }

    public static final class ParsedPredicate {
        public final String raw;
        public final PredicateKind kind;
        public final String op;
        public final ParsedColRef left;
        public final ParsedColRef right;
        public final List<ParsedColRef> allColRefs;
        public final boolean complex;
        public final boolean unparsed;
        public final boolean constantOnly;
        public final SourceRange sourceRange;

        public ParsedPredicate(
                String raw,
                PredicateKind kind,
                String op,
                ParsedColRef left,
                ParsedColRef right,
                List<ParsedColRef> allColRefs,
                boolean complex,
                boolean unparsed,
                boolean constantOnly,
                SourceRange sourceRange) {
            this.raw = raw;
            this.kind = kind;
            this.op = op;
            this.left = left;
            this.right = right;
            this.allColRefs = allColRefs != null ? allColRefs : List.of();
            this.complex = complex;
            this.unparsed = unparsed;
            this.constantOnly = constantOnly;
            this.sourceRange = sourceRange;
        }
    }

    public static final class TableRefModel {
        public final String instanceId;
        public final String database;
        public final String schema;
        public final String name;
        public final String alias;
        public final boolean isCteRef;
        public final String referencedCteBlockId;
        public final boolean isSubquery;
        public final String subqueryBlockId;
        public final String rawSql;
        public final SourceRange sourceRange;
        public final Set<String> contributedColumns = new LinkedHashSet<>();
        public boolean selfJoinInstance = false;

        public TableRefModel(
                String instanceId,
                String database,
                String schema,
                String name,
                String alias,
                boolean isCteRef,
                String referencedCteBlockId,
                boolean isSubquery,
                String subqueryBlockId,
                String rawSql,
                SourceRange sourceRange) {
            this.instanceId = instanceId;
            this.database = database;
            this.schema = schema;
            this.name = name;
            this.alias = alias;
            this.isCteRef = isCteRef;
            this.referencedCteBlockId = referencedCteBlockId;
            this.isSubquery = isSubquery;
            this.subqueryBlockId = subqueryBlockId;
            this.rawSql = rawSql;
            this.sourceRange = sourceRange;
        }

        public String effectiveLookupKey() {
            return alias != null && !alias.isBlank()
                    ? DataFlowIndex.normalizeIdentifier(alias)
                    : DataFlowIndex.normalizeIdentifier(name);
        }

        public String canonicalTableIdentity() {
            if (isSubquery) {
                return "subq::" + instanceId;
            }
            StringBuilder sb = new StringBuilder();
            if (database != null && !DataFlowIndex.isDefaultName(database)) {
                sb.append(DataFlowIndex.normalizeIdentifier(database)).append('.');
            }
            if (schema != null && !DataFlowIndex.isDefaultName(schema)) {
                sb.append(DataFlowIndex.normalizeIdentifier(schema)).append('.');
            }
            sb.append(DataFlowIndex.normalizeIdentifier(name));
            return sb.toString();
        }
    }

    public static final class JoinStepModel {
        public final int order;
        public final TableRefModel leftPrimaryTable;
        public final Set<String> leftScopeKeys;
        public final TableRefModel rightTable;
        public JoinType joinType;
        public JoinClause clause;
        public final boolean implicitCommaJoin;
        public boolean missingCondition;
        public boolean explicitSemi;
        public boolean explicitAnti;
        public boolean subquerySemi;
        public boolean subqueryAnti;
        public final List<ParsedPredicate> predicates = new ArrayList<>();
        public final String rawJoinSql;
        public final String rawConditionSql;
        public final SourceRange joinRange;
        public final SourceRange conditionRange;

        public JoinStepModel(
                int order,
                TableRefModel leftPrimaryTable,
                Set<String> leftScopeKeys,
                TableRefModel rightTable,
                JoinType joinType,
                JoinClause clause,
                boolean implicitCommaJoin,
                boolean missingCondition,
                boolean explicitSemi,
                boolean explicitAnti,
                boolean subquerySemi,
                boolean subqueryAnti,
                String rawJoinSql,
                String rawConditionSql,
                SourceRange joinRange,
                SourceRange conditionRange) {
            this.order = order;
            this.leftPrimaryTable = leftPrimaryTable;
            this.leftScopeKeys = leftScopeKeys;
            this.rightTable = rightTable;
            this.joinType = joinType;
            this.clause = clause;
            this.implicitCommaJoin = implicitCommaJoin;
            this.missingCondition = missingCondition;
            this.explicitSemi = explicitSemi;
            this.explicitAnti = explicitAnti;
            this.subquerySemi = subquerySemi;
            this.subqueryAnti = subqueryAnti;
            this.rawJoinSql = rawJoinSql;
            this.rawConditionSql = rawConditionSql;
            this.joinRange = joinRange;
            this.conditionRange = conditionRange;
        }
    }

    public static final class WhereFilterModel {
        public final String rawText;
        public final SourceRange sourceRange;
        public final List<ParsedColRef> colRefs;
        public final boolean isNullCheckOnly;
        public final ParsedColRef nullCheckedCol;
        public final boolean nullRejecting;

        public WhereFilterModel(
                String rawText,
                SourceRange sourceRange,
                List<ParsedColRef> colRefs,
                boolean isNullCheckOnly,
                ParsedColRef nullCheckedCol,
                boolean nullRejecting) {
            this.rawText = rawText;
            this.sourceRange = sourceRange;
            this.colRefs = colRefs;
            this.isNullCheckOnly = isNullCheckOnly;
            this.nullCheckedCol = nullCheckedCol;
            this.nullRejecting = nullRejecting;
        }
    }

    public static final class QueryBlockModel {
        public final String queryBlockId;
        public final String label;
        public final LaneKind kind;
        public final String parentQueryBlockId;
        public final String rawSql;
        public final SourceRange sourceRange;
        public final List<TableRefModel> tables = new ArrayList<>();
        public final List<JoinStepModel> joins = new ArrayList<>();
        public final List<WhereFilterModel> whereFilters = new ArrayList<>();
        public final List<ParsedColRef> selectCols = new ArrayList<>();
        public String selectRawSql;
        public SourceRange selectRange;

        public QueryBlockModel(
                String queryBlockId,
                String label,
                LaneKind kind,
                String parentQueryBlockId,
                String rawSql,
                SourceRange sourceRange) {
            this.queryBlockId = queryBlockId;
            this.label = label;
            this.kind = kind;
            this.parentQueryBlockId = parentQueryBlockId;
            this.rawSql = rawSql;
            this.sourceRange = sourceRange;
        }
    }

    public record ParseErrorItem(String code, String message, SourceRange sourceRange) {
    }

    public record ParseOutcome(
            List<QueryBlockModel> queryBlocks,
            List<ParseErrorItem> errors) {
    }

    private final String sql;
    private final List<Token> tokens;
    private final JoinCatalogMetadata catalogMeta;
    private final String defaultDatabase;
    private final String defaultSchema;
    private final List<QueryBlockModel> allBlocks = new ArrayList<>();
    private final List<ParseErrorItem> parseErrors = new ArrayList<>();
    private final Map<String, String> activeCteBlockIds = new LinkedHashMap<>();

    private int pos = 0;
    private int mainCounter = 0;
    private int subqCounter = 0;
    private int tableCounter = 0;

    private SqlJoinAstParser(
            String sql,
            List<Token> tokens,
            JoinCatalogMetadata catalogMeta,
            String defaultDatabase,
            String defaultSchema) {
        this.sql = sql != null ? sql : "";
        this.tokens = tokens;
        this.catalogMeta = catalogMeta;
        this.defaultDatabase = defaultDatabase;
        this.defaultSchema = defaultSchema;
    }

    public static ParseOutcome parse(
            String sql,
            JoinCatalogMetadata catalogMeta,
            String defaultDatabase,
            String defaultSchema) {
        List<Token> tokens = SqlJoinLexer.tokenize(sql);
        SqlJoinAstParser parser = new SqlJoinAstParser(sql, tokens, catalogMeta, defaultDatabase, defaultSchema);
        parser.parseScript();
        return new ParseOutcome(parser.allBlocks, parser.parseErrors);
    }

    private void parseScript() {
        if (peek().kind() == TokenKind.EOF) {
            parseErrors.add(new ParseErrorItem(
                    "parse_failed",
                    "Empty SQL input",
                    new SourceRange(1, 1, 1, 1, SourceRangeQuality.EXACT)));
            return;
        }

        // Quick global unbalanced-paren sanity check
        int depth = 0;
        Token firstUnbalanced = null;
        for (Token t : tokens) {
            if (t.kind() == TokenKind.LPAREN) {
                if (depth == 0) {
                    firstUnbalanced = t;
                }
                depth++;
            } else if (t.kind() == TokenKind.RPAREN) {
                depth--;
                if (depth < 0) {
                    parseErrors.add(new ParseErrorItem(
                            "parse_failed",
                            "Unexpected closing parenthesis ')'",
                            t.toSourceRange()));
                    return;
                }
            }
        }
        if (depth != 0 && firstUnbalanced != null) {
            parseErrors.add(new ParseErrorItem(
                    "parse_failed",
                    "Unclosed parenthesis '(' in SQL statement",
                    firstUnbalanced.toSourceRange()));
            return;
        }

        boolean sawAnyValidStatement = false;
        while (peek().kind() != TokenKind.EOF) {
            while (peek().kind() == TokenKind.SEMICOLON) {
                advance();
            }
            if (peek().kind() == TokenKind.EOF) {
                break;
            }

            Token stmtStart = peek();
            if (stmtStart.isWord("CREATE")) {
                sawAnyValidStatement |= parseCreateStatement();
            } else if (stmtStart.isWord("ALTER")) {
                sawAnyValidStatement |= parseAlterStatement();
            } else if (stmtStart.isWord("INSERT") || stmtStart.isWord("REPLACE")) {
                sawAnyValidStatement |= parseInsertStatement();
            } else if (stmtStart.isWord("EXPLAIN")) {
                advance();
                while (!isAtEnd() && !peek().isWord("SELECT") && !peek().isWord("WITH")) {
                    advance();
                }
            } else if (stmtStart.isWord("WITH") || stmtStart.isWord("SELECT") || stmtStart.kind() == TokenKind.LPAREN) {
                sawAnyValidStatement |= parseQueryRoot(null);
            } else if (stmtStart.isWord("DROP") || stmtStart.isWord("SET") || stmtStart.isWord("USE")
                    || stmtStart.isWord("TRUNCATE") || stmtStart.isWord("COMMIT") || stmtStart.isWord("BEGIN")) {
                skipToSemicolon();
                sawAnyValidStatement = true;
            } else {
                parseErrors.add(new ParseErrorItem(
                        "parse_failed",
                        "Unexpected SQL token '" + stmtStart.text() + "' at start of statement",
                        stmtStart.toSourceRange()));
                skipToSemicolon();
            }
        }

        if (!sawAnyValidStatement && allBlocks.isEmpty() && parseErrors.isEmpty()) {
            parseErrors.add(new ParseErrorItem(
                    "parse_failed",
                    "No analyzable SQL query found",
                    tokens.get(0).toSourceRange()));
        }
    }

    private boolean parseCreateStatement() {
        int saved = pos;
        advance(); // CREATE
        boolean uniqueIndex = false;
        while (!isAtEnd()) {
            Token t = peek();
            if (t.isWord("OR") || t.isWord("REPLACE") || t.isWord("TEMP") || t.isWord("TEMPORARY")
                    || t.isWord("GLOBAL") || t.isWord("LOCAL") || t.isWord("MATERIALIZED")
                    || t.isWord("EXTERNAL") || t.isWord("TRANSIENT") || t.isWord("CLUSTERED")
                    || t.isWord("NONCLUSTERED") || t.isWord("CONCURRENTLY")) {
                advance();
                continue;
            }
            if (t.isWord("UNIQUE")) {
                uniqueIndex = true;
                advance();
                continue;
            }
            break;
        }

        if (peek().isWord("INDEX")) {
            advance();
            if (peek().isWord("IF")) {
                advance();
                matchWord("NOT");
                matchWord("EXISTS");
            }
            readQualifiedName(); // index name
            if (matchWord("ON")) {
                QualifiedIdent tblName = readQualifiedName();
                if (tblName != null && peek().kind() == TokenKind.LPAREN) {
                    TableMeta tm = catalogMeta.getOrCreateTable(tblName.db(), tblName.schema(), tblName.name());
                    List<String> idxCols = readParenColumnList();
                    for (String c : idxCols) {
                        if (uniqueIndex && idxCols.size() == 1) {
                            tm.getOrCreateColumn(c).setUniqueKey(true);
                        } else {
                            tm.getOrCreateColumn(c).setIndexed(true);
                        }
                    }
                }
            }
            skipToSemicolon();
            return true;
        }

        if (peek().isWord("VIEW")) {
            while (!isAtEnd() && !peek().isWord("AS") && peek().kind() != TokenKind.SEMICOLON) {
                advance();
            }
            if (matchWord("AS")) {
                return parseQueryRoot(null);
            }
            skipToSemicolon();
            return true;
        }

        if (peek().isWord("TABLE")) {
            advance();
            if (peek().isWord("IF")) {
                advance();
                matchWord("NOT");
                matchWord("EXISTS");
            }
            QualifiedIdent tblName = readQualifiedName();
            if (matchWord("AS")) {
                return parseQueryRoot(null);
            }
            if (tblName != null && peek().kind() == TokenKind.LPAREN) {
                // Could be CREATE TABLE t (SELECT ...) or CREATE TABLE t (col defs...)
                int afterParen = pos + 1;
                if (afterParen < tokens.size()
                        && (tokens.get(afterParen).isWord("SELECT") || tokens.get(afterParen).isWord("WITH"))) {
                    advance(); // '('
                    boolean ok = parseQueryRoot(null);
                    if (peek().kind() == TokenKind.RPAREN) {
                        advance();
                    }
                    skipToSemicolon();
                    return ok;
                }
                parseCreateTableDdlBody(catalogMeta.getOrCreateTable(tblName.db(), tblName.schema(), tblName.name()));
                // Check if followed by AS SELECT
                while (!isAtEnd() && peek().kind() != TokenKind.SEMICOLON && !peek().isWord("AS")
                        && !peek().isWord("SELECT") && !peek().isWord("WITH")) {
                    advance();
                }
                if (matchWord("AS") || peek().isWord("SELECT") || peek().isWord("WITH")) {
                    return parseQueryRoot(null);
                }
                skipToSemicolon();
                return true;
            }
        }

        pos = saved;
        skipToSemicolon();
        return true;
    }

    private void parseCreateTableDdlBody(TableMeta tm) {
        if (peek().kind() != TokenKind.LPAREN) {
            return;
        }
        advance(); // '('
        while (!isAtEnd() && peek().kind() != TokenKind.RPAREN) {
            if (peek().kind() == TokenKind.COMMA) {
                advance();
                continue;
            }
            // Check table-level constraints
            if (peek().isWord("CONSTRAINT")) {
                advance();
                if (peek().isIdentifierLike()) {
                    advance();
                }
            }
            if (peek().isWord("PRIMARY")) {
                advance();
                matchWord("KEY");
                List<String> pkCols = readParenColumnList();
                for (String c : pkCols) {
                    tm.getOrCreateColumn(c).setPrimaryKey(true);
                }
                skipToCommaOrCloseParen();
                continue;
            }
            if (peek().isWord("UNIQUE")) {
                advance();
                if (peek().isWord("KEY") || peek().isWord("INDEX")) {
                    advance();
                }
                if (peek().isIdentifierLike()) {
                    advance();
                }
                List<String> ukCols = readParenColumnList();
                for (String c : ukCols) {
                    if (ukCols.size() == 1) {
                        tm.getOrCreateColumn(c).setUniqueKey(true);
                    } else {
                        tm.getOrCreateColumn(c).setIndexed(true);
                    }
                }
                skipToCommaOrCloseParen();
                continue;
            }
            if (peek().isWord("INDEX") || peek().isWord("KEY")) {
                advance();
                if (peek().isIdentifierLike()) {
                    advance();
                }
                List<String> idxCols = readParenColumnList();
                for (String c : idxCols) {
                    tm.getOrCreateColumn(c).setIndexed(true);
                }
                skipToCommaOrCloseParen();
                continue;
            }
            if (peek().isWord("FOREIGN") || peek().isWord("CHECK") || peek().isWord("PERIOD")) {
                skipToCommaOrCloseParen();
                continue;
            }

            // Column definition: <colName> <dataType> [inline constraints...]
            if (peek().isIdentifierLike()) {
                String colName = advance().cleanIdentifier();
                JoinCatalogMetadata.ColumnMeta cm = tm.getOrCreateColumn(colName);
                int depth = 0;
                while (!isAtEnd()) {
                    Token t = peek();
                    if (t.kind() == TokenKind.LPAREN) {
                        depth++;
                        advance();
                        continue;
                    }
                    if (t.kind() == TokenKind.RPAREN) {
                        if (depth == 0) {
                            break;
                        }
                        depth--;
                        advance();
                        continue;
                    }
                    if (t.kind() == TokenKind.COMMA && depth == 0) {
                        break;
                    }
                    if (depth == 0) {
                        if (t.isWord("PRIMARY")) {
                            advance();
                            matchWord("KEY");
                            cm.setPrimaryKey(true);
                            continue;
                        }
                        if (t.isWord("UNIQUE")) {
                            advance();
                            cm.setUniqueKey(true);
                            continue;
                        }
                        if (t.isWord("INDEX")) {
                            advance();
                            cm.setIndexed(true);
                            continue;
                        }
                    }
                    advance();
                }
            } else {
                skipToCommaOrCloseParen();
            }
        }
        if (peek().kind() == TokenKind.RPAREN) {
            advance();
        }
    }

    private boolean parseAlterStatement() {
        advance(); // ALTER
        if (matchWord("TABLE")) {
            if (peek().isWord("ONLY")) {
                advance();
            }
            QualifiedIdent tbl = readQualifiedName();
            if (tbl != null && matchWord("ADD")) {
                if (peek().isWord("CONSTRAINT")) {
                    advance();
                    if (peek().isIdentifierLike()) {
                        advance();
                    }
                }
                TableMeta tm = catalogMeta.getOrCreateTable(tbl.db(), tbl.schema(), tbl.name());
                if (matchWord("PRIMARY")) {
                    matchWord("KEY");
                    for (String c : readParenColumnList()) {
                        tm.getOrCreateColumn(c).setPrimaryKey(true);
                    }
                } else if (matchWord("UNIQUE")) {
                    List<String> uk = readParenColumnList();
                    if (uk.size() == 1) {
                        tm.getOrCreateColumn(uk.get(0)).setUniqueKey(true);
                    } else {
                        for (String c : uk) {
                            tm.getOrCreateColumn(c).setIndexed(true);
                        }
                    }
                }
            }
        }
        skipToSemicolon();
        return true;
    }

    private boolean parseInsertStatement() {
        advance(); // INSERT / REPLACE
        while (!isAtEnd() && peek().kind() != TokenKind.SEMICOLON) {
            if (peek().isWord("WITH") || peek().isWord("SELECT")) {
                return parseQueryRoot(null);
            }
            if (peek().isWord("VALUES")) {
                skipToSemicolon();
                return true;
            }
            advance();
        }
        return true;
    }

    private boolean parseQueryRoot(String parentBlockId) {
        while (peek().kind() == TokenKind.LPAREN) {
            advance();
        }

        String mainBlockId = parentBlockId == null
                ? (mainCounter++ == 0 ? "qb-main" : "qb-main-" + mainCounter)
                : "qb-subq-" + (++subqCounter);

        if (peek().isWord("WITH")) {
            advance(); // WITH
            if (peek().isWord("RECURSIVE")) {
                advance();
            }
            while (!isAtEnd()) {
                if (!peek().isIdentifierLike()) {
                    break;
                }
                Token cteNameTok = advance();
                String cteName = cteNameTok.cleanIdentifier();
                // Optional CTE column alias list: cte(a, b)
                if (peek().kind() == TokenKind.LPAREN && !peekNextIsSelectOrWith()) {
                    skipBalancedParens();
                }
                // Optional Postgres MATERIALIZED / NOT MATERIALIZED
                if (peek().isWord("NOT")) {
                    advance();
                }
                if (peek().isWord("MATERIALIZED")) {
                    advance();
                }
                if (!matchWord("AS")) {
                    parseErrors.add(new ParseErrorItem(
                            "parse_failed",
                            "Expected AS after CTE name '" + cteName + "'",
                            peek().toSourceRange()));
                    return false;
                }
                if (peek().kind() != TokenKind.LPAREN) {
                    parseErrors.add(new ParseErrorItem(
                            "parse_failed",
                            "Expected '(' starting CTE body for '" + cteName + "'",
                            peek().toSourceRange()));
                    return false;
                }
                Token openParen = advance(); // '('
                String cteBlockId = "qb-cte-" + DataFlowIndex.normalizeIdentifier(cteName);
                parseSelectChain(cteBlockId, "CTE: " + cteName, LaneKind.CTE, mainBlockId, openParen);
                if (peek().kind() == TokenKind.RPAREN) {
                    advance();
                }
                activeCteBlockIds.put(DataFlowIndex.normalizeIdentifier(cteName), cteBlockId);

                if (peek().kind() == TokenKind.COMMA) {
                    advance();
                } else {
                    break;
                }
            }
        }

        if (!peek().isWord("SELECT")) {
            parseErrors.add(new ParseErrorItem(
                    "parse_failed",
                    "Expected SELECT keyword, found '" + peek().text() + "'",
                    peek().toSourceRange()));
            skipToSemicolon();
            return false;
        }

        String label = parentBlockId == null ? "Main Query" : "Subquery #" + subqCounter;
        LaneKind kind = parentBlockId == null ? LaneKind.MAIN : LaneKind.SUBQUERY;
        parseSelectChain(mainBlockId, label, kind, parentBlockId, peek());

        while (peek().kind() == TokenKind.RPAREN) {
            advance();
        }
        return true;
    }

    private void parseSelectChain(
            String blockId,
            String label,
            LaneKind kind,
            String parentBlockId,
            Token startTok) {
        parseSingleSelectBlock(blockId, label, kind, parentBlockId, startTok);

        int branchNum = 1;
        while (peek().isWord("UNION") || peek().isWord("INTERSECT")
                || peek().isWord("EXCEPT") || peek().isWord("MINUS")) {
            String setOp = advance().upper();
            if (peek().isWord("ALL") || peek().isWord("DISTINCT")) {
                advance();
            }
            while (peek().kind() == TokenKind.LPAREN) {
                advance();
            }
            if (peek().isWord("SELECT")) {
                branchNum++;
                String branchId = blockId + "-u" + branchNum;
                parseSingleSelectBlock(
                        branchId,
                        label + " (" + setOp + " #" + branchNum + ")",
                        kind == LaneKind.MAIN ? LaneKind.SUBQUERY : kind,
                        blockId,
                        peek());
            }
            while (peek().kind() == TokenKind.RPAREN && parentBlockId == null) {
                advance();
            }
        }
    }

    private void parseSingleSelectBlock(
            String blockId,
            String label,
            LaneKind kind,
            String parentBlockId,
            Token blockStartTok) {
        if (!matchWord("SELECT")) {
            return;
        }
        Token selectStart = previous();

        // Handle DISTINCT / ALL / TOP (n)
        if (peek().isWord("DISTINCT") || peek().isWord("ALL")) {
            advance();
        }
        if (peek().isWord("TOP")) {
            advance();
            if (peek().kind() == TokenKind.LPAREN) {
                skipBalancedParens();
            } else if (peek().kind() == TokenKind.NUMBER) {
                advance();
            }
            if (peek().isWord("PERCENT")) {
                advance();
            }
        }

        // Scan SELECT projection list until FROM / INTO / WHERE / GROUP / ORDER / UNION / RPAREN / SEMICOLON / EOF
        int projStartIdx = pos;
        int depth = 0;
        while (!isAtEnd()) {
            Token t = peek();
            if (t.kind() == TokenKind.LPAREN) {
                depth++;
                advance();
                continue;
            }
            if (t.kind() == TokenKind.RPAREN) {
                if (depth == 0) {
                    break;
                }
                depth--;
                advance();
                continue;
            }
            if (depth == 0 && (t.isWord("FROM") || t.isWord("INTO") || t.isWord("WHERE")
                    || t.isWord("GROUP") || t.isWord("HAVING") || t.isWord("ORDER")
                    || t.isWord("LIMIT") || t.isWord("UNION") || t.isWord("INTERSECT")
                    || t.isWord("EXCEPT") || t.isWord("MINUS") || t.kind() == TokenKind.SEMICOLON)) {
                break;
            }
            advance();
        }
        int projEndIdx = pos;

        // Validate non-empty SELECT projection (catches broken `SELECT FROM t` or `SELECT * FROM WHERE`)
        if (projStartIdx == projEndIdx) {
            parseErrors.add(new ParseErrorItem(
                    "parse_failed",
                    "Empty SELECT projection list before '" + peek().text() + "'",
                    peek().toSourceRange()));
        }

        // Optional SELECT ... INTO target_table
        if (matchWord("INTO")) {
            while (!isAtEnd() && !peek().isWord("FROM") && !peek().isWord("WHERE")
                    && peek().kind() != TokenKind.SEMICOLON && peek().kind() != TokenKind.RPAREN) {
                advance();
            }
        }

        List<TableRefModel> blockTables = new ArrayList<>();
        List<JoinStepModel> blockJoins = new ArrayList<>();

        if (matchWord("FROM")) {
            if (isAtEnd() || CLAUSE_STOP_WORDS.contains(peek().upper())
                    || peek().kind() == TokenKind.SEMICOLON || peek().kind() == TokenKind.RPAREN) {
                parseErrors.add(new ParseErrorItem(
                        "parse_failed",
                        "Missing table reference after FROM, found '" + peek().text() + "'",
                        peek().toSourceRange()));
            } else {
                parseFromAndJoins(blockId, blockTables, blockJoins);
            }
        }

        // Scan WHERE clause if present
        List<Token> whereTokens = List.of();
        if (matchWord("WHERE")) {
            int whereStart = pos;
            int wDepth = 0;
            while (!isAtEnd()) {
                Token t = peek();
                if (t.kind() == TokenKind.LPAREN) {
                    wDepth++;
                    advance();
                    continue;
                }
                if (t.kind() == TokenKind.RPAREN) {
                    if (wDepth == 0) {
                        break;
                    }
                    wDepth--;
                    advance();
                    continue;
                }
                if (wDepth == 0 && (t.isWord("GROUP") || t.isWord("HAVING") || t.isWord("ORDER")
                        || t.isWord("LIMIT") || t.isWord("OFFSET") || t.isWord("FETCH")
                        || t.isWord("QUALIFY") || t.isWord("WINDOW") || t.isWord("UNION")
                        || t.isWord("INTERSECT") || t.isWord("EXCEPT") || t.isWord("MINUS")
                        || t.kind() == TokenKind.SEMICOLON)) {
                    break;
                }
                advance();
            }
            if (whereStart == pos) {
                parseErrors.add(new ParseErrorItem(
                        "parse_failed",
                        "Empty WHERE clause",
                        previous().toSourceRange()));
            } else {
                whereTokens = tokens.subList(whereStart, pos);
            }
        }

        // Skip trailing GROUP BY / HAVING / QUALIFY / WINDOW / ORDER BY / LIMIT within this query block
        int tailDepth = 0;
        while (!isAtEnd()) {
            Token t = peek();
            if (t.kind() == TokenKind.LPAREN) {
                tailDepth++;
                advance();
                continue;
            }
            if (t.kind() == TokenKind.RPAREN) {
                if (tailDepth == 0) {
                    break;
                }
                tailDepth--;
                advance();
                continue;
            }
            if (tailDepth == 0 && (t.isWord("UNION") || t.isWord("INTERSECT")
                    || t.isWord("EXCEPT") || t.isWord("MINUS") || t.kind() == TokenKind.SEMICOLON)) {
                break;
            }
            advance();
        }

        Token blockEndTok = pos > 0 ? tokens.get(pos - 1) : selectStart;
        String rawBlockSql = sliceSql(blockStartTok, blockEndTok);
        SourceRange blockRange = SqlJoinLexer.spanRange(blockStartTok, blockEndTok);

        QueryBlockModel block = new QueryBlockModel(
                blockId, label, kind, parentBlockId, rawBlockSql, blockRange);
        block.tables.addAll(blockTables);
        block.joins.addAll(blockJoins);

        if (projEndIdx > projStartIdx) {
            List<Token> projSlice = tokens.subList(projStartIdx, projEndIdx);
            block.selectRawSql = sliceSql(selectStart, tokens.get(projEndIdx - 1));
            block.selectRange = SqlJoinLexer.spanRange(selectStart, tokens.get(projEndIdx - 1));
            block.selectCols.addAll(extractColumnRefsFromTokens(projSlice));
        }

        // Analyze WHERE conjuncts (subquery semi/anti joins, comma-join links, outer-join filters)
        if (!whereTokens.isEmpty()) {
            analyzeWhereClause(block, whereTokens);
        }

        // Attribute contributed columns to block tables
        attributeContributedColumns(block);

        // Mark self-join table instances in this block
        Map<String, List<TableRefModel>> byIdentity = new LinkedHashMap<>();
        for (TableRefModel tr : block.tables) {
            if (!tr.isSubquery) {
                byIdentity.computeIfAbsent(tr.canonicalTableIdentity(), k -> new ArrayList<>()).add(tr);
            }
        }
        for (List<TableRefModel> group : byIdentity.values()) {
            if (group.size() >= 2) {
                for (TableRefModel tr : group) {
                    tr.selfJoinInstance = true;
                }
            }
        }

        allBlocks.add(block);
    }

    private void parseFromAndJoins(
            String blockId,
            List<TableRefModel> blockTables,
            List<JoinStepModel> blockJoins) {
        TableRefModel firstTable = parseTableFactor(blockId);
        if (firstTable == null) {
            return;
        }
        blockTables.add(firstTable);
        Set<String> accumulatedLeftScope = new LinkedHashSet<>();
        accumulatedLeftScope.add(firstTable.effectiveLookupKey());
        accumulatedLeftScope.add(DataFlowIndex.normalizeIdentifier(firstTable.name));
        TableRefModel currentLeftPrimary = firstTable;

        int joinOrder = 0;
        while (!isAtEnd()) {
            Token next = peek();
            if (next.kind() == TokenKind.RPAREN || next.kind() == TokenKind.SEMICOLON) {
                break;
            }
            if (next.isWord("WHERE") || next.isWord("GROUP") || next.isWord("HAVING")
                    || next.isWord("ORDER") || next.isWord("LIMIT") || next.isWord("OFFSET")
                    || next.isWord("FETCH") || next.isWord("QUALIFY") || next.isWord("WINDOW")
                    || next.isWord("UNION") || next.isWord("INTERSECT")
                    || next.isWord("EXCEPT") || next.isWord("MINUS")) {
                break;
            }

            // Implicit comma join: FROM a, b
            if (next.kind() == TokenKind.COMMA) {
                Token commaTok = advance();
                TableRefModel rightTbl = parseTableFactor(blockId);
                if (rightTbl == null) {
                    parseErrors.add(new ParseErrorItem(
                            "parse_failed",
                            "Expected table reference after ',' in FROM clause",
                            commaTok.toSourceRange()));
                    break;
                }
                blockTables.add(rightTbl);
                joinOrder++;
                Token endTok = previous();
                JoinStepModel commaJoin = new JoinStepModel(
                        joinOrder,
                        currentLeftPrimary,
                        new LinkedHashSet<>(accumulatedLeftScope),
                        rightTbl,
                        JoinType.INNER,
                        JoinClause.NONE,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        sliceSql(commaTok, endTok),
                        null,
                        SqlJoinLexer.spanRange(commaTok, endTok),
                        null);
                blockJoins.add(commaJoin);
                accumulatedLeftScope.add(rightTbl.effectiveLookupKey());
                accumulatedLeftScope.add(DataFlowIndex.normalizeIdentifier(rightTbl.name));
                currentLeftPrimary = rightTbl;
                continue;
            }

            // Explicit JOIN / APPLY clause
            if (isJoinStartToken(next)) {
                Token joinStartTok = next;
                boolean isNatural = matchWord("NATURAL");
                boolean explicitSemi = false;
                boolean explicitAnti = false;
                JoinType joinType = JoinType.INNER;

                if (matchWord("CROSS")) {
                    if (matchWord("APPLY")) {
                        joinType = JoinType.INNER;
                    } else {
                        matchWord("JOIN");
                        joinType = JoinType.CROSS;
                    }
                } else if (matchWord("OUTER") && matchWord("APPLY")) {
                    joinType = JoinType.LEFT;
                } else if (matchWord("INNER")) {
                    matchWord("JOIN");
                    joinType = JoinType.INNER;
                } else if (matchWord("LEFT")) {
                    if (matchWord("SEMI")) {
                        explicitSemi = true;
                        joinType = JoinType.INNER;
                    } else if (matchWord("ANTI")) {
                        explicitAnti = true;
                        joinType = JoinType.LEFT;
                    } else {
                        matchWord("OUTER");
                        joinType = JoinType.LEFT;
                    }
                    matchWord("JOIN");
                } else if (matchWord("RIGHT")) {
                    matchWord("OUTER");
                    matchWord("JOIN");
                    joinType = JoinType.RIGHT;
                } else if (matchWord("FULL")) {
                    matchWord("OUTER");
                    matchWord("JOIN");
                    joinType = JoinType.FULL;
                } else if (matchWord("SEMI")) {
                    explicitSemi = true;
                    matchWord("JOIN");
                    joinType = JoinType.INNER;
                } else if (matchWord("ANTI")) {
                    explicitAnti = true;
                    matchWord("JOIN");
                    joinType = JoinType.LEFT;
                } else if (matchWord("JOIN")) {
                    joinType = JoinType.INNER;
                } else {
                    break;
                }

                TableRefModel rightTbl = parseTableFactor(blockId);
                if (rightTbl == null) {
                    parseErrors.add(new ParseErrorItem(
                            "parse_failed",
                            "Missing target table after JOIN keyword",
                            joinStartTok.toSourceRange()));
                    break;
                }
                blockTables.add(rightTbl);

                JoinClause clause;
                boolean missingCondition = false;
                String rawConditionSql = null;
                SourceRange condRange = null;
                List<ParsedPredicate> parsedPreds = new ArrayList<>();

                if (matchWord("ON")) {
                    Token onTok = previous();
                    List<Token> condTokens = readJoinConditionTokens();
                    clause = JoinClause.ON;
                    if (condTokens.isEmpty()) {
                        missingCondition = true;
                        condRange = onTok.toSourceRange();
                    } else {
                        Token cFirst = condTokens.get(0);
                        Token cLast = condTokens.get(condTokens.size() - 1);
                        rawConditionSql = sliceSql(cFirst, cLast);
                        condRange = SqlJoinLexer.spanRange(cFirst, cLast);
                        parsedPreds.addAll(parseConditionPredicates(condTokens));
                    }
                } else if (matchWord("USING")) {
                    clause = JoinClause.USING;
                    Token uStart = previous();
                    List<String> usingCols = readParenColumnList();
                    Token uEnd = previous();
                    rawConditionSql = sliceSql(uStart, uEnd);
                    condRange = SqlJoinLexer.spanRange(uStart, uEnd);
                    if (usingCols.isEmpty()) {
                        missingCondition = true;
                    } else {
                        for (String uCol : usingCols) {
                            String lQual = currentLeftPrimary.alias != null
                                    ? currentLeftPrimary.alias
                                    : currentLeftPrimary.name;
                            String rQual = rightTbl.alias != null ? rightTbl.alias : rightTbl.name;
                            ParsedColRef lRef = new ParsedColRef(lQual, uCol, lQual + "." + uCol, condRange);
                            ParsedColRef rRef = new ParsedColRef(rQual, uCol, rQual + "." + uCol, condRange);
                            parsedPreds.add(new ParsedPredicate(
                                    "USING (" + uCol + ")",
                                    PredicateKind.EQUI,
                                    "=",
                                    lRef,
                                    rRef,
                                    List.of(lRef, rRef),
                                    false,
                                    false,
                                    false,
                                    condRange));
                        }
                    }
                } else if (isNatural) {
                    clause = JoinClause.NATURAL;
                } else if (joinType == JoinType.CROSS) {
                    clause = JoinClause.NONE;
                } else {
                    clause = JoinClause.NONE;
                    missingCondition = true;
                }

                Token joinEndTok = previous();
                joinOrder++;
                JoinStepModel step = new JoinStepModel(
                        joinOrder,
                        currentLeftPrimary,
                        new LinkedHashSet<>(accumulatedLeftScope),
                        rightTbl,
                        joinType,
                        clause,
                        false,
                        missingCondition,
                        explicitSemi,
                        explicitAnti,
                        false,
                        false,
                        sliceSql(joinStartTok, joinEndTok),
                        rawConditionSql,
                        SqlJoinLexer.spanRange(joinStartTok, joinEndTok),
                        condRange);
                step.predicates.addAll(parsedPreds);
                blockJoins.add(step);

                accumulatedLeftScope.add(rightTbl.effectiveLookupKey());
                accumulatedLeftScope.add(DataFlowIndex.normalizeIdentifier(rightTbl.name));
                currentLeftPrimary = rightTbl;
                continue;
            }

            // Unexpected token inside FROM clause — skip safely to avoid infinite loop
            advance();
        }
    }

    private TableRefModel parseTableFactor(String parentBlockId) {
        if (peek().isWord("LATERAL")) {
            advance();
        }

        // Derived subquery in FROM: (SELECT ...) [AS] alias
        if (peek().kind() == TokenKind.LPAREN) {
            Token openParen = advance();
            if (peek().isWord("SELECT") || peek().isWord("WITH") || peek().kind() == TokenKind.LPAREN) {
                String subBlockId = "qb-subq-" + (++subqCounter);
                if (peek().isWord("WITH")) {
                    // Nested WITH inside derived table
                    parseQueryRoot(parentBlockId);
                    subBlockId = allBlocks.isEmpty()
                            ? subBlockId
                            : allBlocks.get(allBlocks.size() - 1).queryBlockId;
                } else {
                    parseSelectChain(
                            subBlockId,
                            "Derived Subquery #" + subqCounter,
                            LaneKind.SUBQUERY,
                            parentBlockId,
                            openParen);
                }
                if (peek().kind() == TokenKind.RPAREN) {
                    advance();
                }
                String alias = readOptionalTableAlias();
                Token endTok = previous();
                String displayName = alias != null ? alias : "subquery_" + subqCounter;
                return new TableRefModel(
                        "t-" + (++tableCounter),
                        null,
                        null,
                        displayName,
                        alias,
                        false,
                        null,
                        true,
                        subBlockId,
                        sliceSql(openParen, endTok),
                        SqlJoinLexer.spanRange(openParen, endTok));
            } else {
                // Parenthesized table factor: (orders o)
                TableRefModel inner = parseTableFactor(parentBlockId);
                while (!isAtEnd() && peek().kind() != TokenKind.RPAREN) {
                    advance();
                }
                if (peek().kind() == TokenKind.RPAREN) {
                    advance();
                }
                return inner;
            }
        }

        if (!peek().isIdentifierLike() || CLAUSE_STOP_WORDS.contains(peek().upper())) {
            return null;
        }

        Token startTok = peek();
        QualifiedIdent qi = readQualifiedName();
        if (qi == null) {
            return null;
        }
        // Skip optional table function arguments e.g. UNNEST(...) or TABLE(...)
        if (peek().kind() == TokenKind.LPAREN) {
            skipBalancedParens();
        }
        String alias = readOptionalTableAlias();
        Token endTok = previous();

        String normSimple = DataFlowIndex.normalizeIdentifier(qi.name());
        boolean isCte = qi.db() == null && qi.schema() == null && activeCteBlockIds.containsKey(normSimple);
        String cteBlockId = isCte ? activeCteBlockIds.get(normSimple) : null;

        String effDb = qi.db() != null ? qi.db() : (isCte ? null : defaultDatabase);
        String effSch = qi.schema() != null ? qi.schema() : (isCte ? null : defaultSchema);

        return new TableRefModel(
                "t-" + (++tableCounter),
                effDb,
                effSch,
                qi.name(),
                alias,
                isCte,
                cteBlockId,
                false,
                null,
                sliceSql(startTok, endTok),
                SqlJoinLexer.spanRange(startTok, endTok));
    }

    private String readOptionalTableAlias() {
        if (matchWord("AS")) {
            if (peek().isIdentifierLike()) {
                return advance().cleanIdentifier();
            }
            return null;
        }
        // Optional Pivot/Sample/Hints skip
        if (peek().isWord("TABLESAMPLE") || peek().isWord("WITH")) {
            // T-SQL WITH (NOLOCK)
            int saved = pos;
            advance();
            if (peek().kind() == TokenKind.LPAREN) {
                int nextIdx = pos + 1;
                if (nextIdx < tokens.size() && !tokens.get(nextIdx).isWord("SELECT")) {
                    skipBalancedParens();
                } else {
                    pos = saved;
                    return null;
                }
            } else {
                pos = saved;
                return null;
            }
        }
        if (peek().isIdentifierLike() && !CLAUSE_STOP_WORDS.contains(peek().upper())
                && !peek().isWord("APPLY")) {
            return advance().cleanIdentifier();
        }
        return null;
    }

    private List<Token> readJoinConditionTokens() {
        int start = pos;
        int depth = 0;
        while (!isAtEnd()) {
            Token t = peek();
            if (t.kind() == TokenKind.LPAREN) {
                depth++;
                advance();
                continue;
            }
            if (t.kind() == TokenKind.RPAREN) {
                if (depth == 0) {
                    break;
                }
                depth--;
                advance();
                continue;
            }
            if (depth == 0) {
                if (t.kind() == TokenKind.COMMA || t.kind() == TokenKind.SEMICOLON) {
                    break;
                }
                if (isJoinStartToken(t) || t.isWord("WHERE") || t.isWord("GROUP")
                        || t.isWord("HAVING") || t.isWord("ORDER") || t.isWord("LIMIT")
                        || t.isWord("OFFSET") || t.isWord("FETCH") || t.isWord("QUALIFY")
                        || t.isWord("WINDOW") || t.isWord("UNION") || t.isWord("INTERSECT")
                        || t.isWord("EXCEPT") || t.isWord("MINUS")) {
                    break;
                }
            }
            advance();
        }
        return tokens.subList(start, pos);
    }

    private List<ParsedPredicate> parseConditionPredicates(List<Token> condTokens) {
        List<Token> trimmed = stripOuterParens(condTokens);
        if (trimmed.isEmpty()) {
            return List.of();
        }

        SourceRange fullRange = SqlJoinLexer.spanRange(trimmed.get(0), trimmed.get(trimmed.size() - 1));
        String fullRaw = sliceSql(trimmed.get(0), trimmed.get(trimmed.size() - 1));

        // 1. Check for template macros / unknown lexer tokens / broken syntax
        if (containsUnparsedTokens(trimmed)) {
            List<ParsedColRef> refs = extractColumnRefsFromTokens(trimmed);
            return List.of(new ParsedPredicate(
                    fullRaw,
                    PredicateKind.UNKNOWN,
                    null,
                    refs.size() >= 1 ? refs.get(0) : null,
                    refs.size() >= 2 ? refs.get(1) : null,
                    refs,
                    false,
                    true,
                    false,
                    fullRange));
        }

        // 2. Check for top-level OR disjunction -> COMPLEX_PREDICATE
        if (containsTopLevelWord(trimmed, "OR")) {
            List<ParsedColRef> refs = extractColumnRefsFromTokens(trimmed);
            return List.of(new ParsedPredicate(
                    fullRaw,
                    PredicateKind.COMPLEX,
                    "OR",
                    refs.size() >= 1 ? refs.get(0) : null,
                    refs.size() >= 2 ? refs.get(1) : null,
                    refs,
                    true,
                    false,
                    refs.isEmpty(),
                    fullRange));
        }

        // 3. Split on top-level AND into individual conjuncts
        List<List<Token>> conjuncts = splitTopLevelWord(trimmed, "AND");
        List<ParsedPredicate> out = new ArrayList<>(conjuncts.size());
        for (List<Token> conj : conjuncts) {
            out.add(classifySingleConjunct(conj));
        }
        return out;
    }

    private ParsedPredicate classifySingleConjunct(List<Token> rawConj) {
        List<Token> conj = stripOuterParens(rawConj);
        if (conj.isEmpty()) {
            return new ParsedPredicate(
                    "", PredicateKind.UNKNOWN, null, null, null, List.of(),
                    false, true, false, new SourceRange(1, 1, 1, 1, SourceRangeQuality.MISSING));
        }

        SourceRange range = SqlJoinLexer.spanRange(conj.get(0), conj.get(conj.size() - 1));
        String raw = sliceSql(conj.get(0), conj.get(conj.size() - 1));
        List<ParsedColRef> allRefs = extractColumnRefsFromTokens(conj);

        if (containsUnparsedTokens(conj)) {
            return new ParsedPredicate(
                    raw, PredicateKind.UNKNOWN, null,
                    allRefs.size() >= 1 ? allRefs.get(0) : null,
                    allRefs.size() >= 2 ? allRefs.get(1) : null,
                    allRefs, false, true, false, range);
        }

        // Check OR inside parenthesized sub-conjunct: (a.x = b.x OR a.y = b.y)
        if (containsTopLevelWord(conj, "OR")) {
            return new ParsedPredicate(
                    raw, PredicateKind.COMPLEX, "OR",
                    allRefs.size() >= 1 ? allRefs.get(0) : null,
                    allRefs.size() >= 2 ? allRefs.get(1) : null,
                    allRefs, true, false, allRefs.isEmpty(), range);
        }

        // Check IS [NOT] NULL
        int isIdx = findTopLevelWord(conj, "IS");
        if (isIdx > 0) {
            boolean isNot = (isIdx + 1 < conj.size() && conj.get(isIdx + 1).isWord("NOT"));
            int nullIdx = isNot ? isIdx + 2 : isIdx + 1;
            if (nullIdx < conj.size() && conj.get(nullIdx).isWord("NULL")) {
                ParsedColRef col = parsePureColumnRef(conj.subList(0, isIdx));
                return new ParsedPredicate(
                        raw, PredicateKind.NULL_CHECK, isNot ? "IS NOT NULL" : "IS NULL",
                        col != null ? col : (allRefs.isEmpty() ? null : allRefs.get(0)),
                        null, allRefs, false, false, allRefs.isEmpty(), range);
            }
        }

        // Check [NOT] BETWEEN
        int betweenIdx = findTopLevelWord(conj, "BETWEEN");
        if (betweenIdx > 0) {
            return new ParsedPredicate(
                    raw, PredicateKind.RANGE, "BETWEEN",
                    allRefs.size() >= 1 ? allRefs.get(0) : null,
                    allRefs.size() >= 2 ? allRefs.get(1) : null,
                    allRefs, true, false, allRefs.isEmpty(), range);
        }

        // Check [NOT] LIKE / ILIKE
        int likeIdx = findTopLevelWord(conj, "LIKE");
        if (likeIdx < 0) {
            likeIdx = findTopLevelWord(conj, "ILIKE");
        }
        if (likeIdx > 0) {
            return new ParsedPredicate(
                    raw, PredicateKind.COMPLEX, "LIKE",
                    allRefs.size() >= 1 ? allRefs.get(0) : null,
                    allRefs.size() >= 2 ? allRefs.get(1) : null,
                    allRefs, true, false, allRefs.isEmpty(), range);
        }

        // Check [NOT] IN (...) or EXISTS (...)
        if (findTopLevelWord(conj, "IN") >= 0 || findTopLevelWord(conj, "EXISTS") >= 0
                || findTopLevelWord(conj, "CASE") >= 0) {
            return new ParsedPredicate(
                    raw, PredicateKind.COMPLEX, "EXPR",
                    allRefs.size() >= 1 ? allRefs.get(0) : null,
                    allRefs.size() >= 2 ? allRefs.get(1) : null,
                    allRefs, true, false, allRefs.isEmpty(), range);
        }

        // Find top-level comparison operator (=, <=>, !=, <>, <, <=, >, >=)
        int opIdx = findTopLevelComparisonOp(conj);
        if (opIdx > 0 && opIdx < conj.size() - 1) {
            String op = conj.get(opIdx).text();
            List<Token> lhsTokens = stripOuterParens(conj.subList(0, opIdx));
            List<Token> rhsTokens = stripOuterParens(conj.subList(opIdx + 1, conj.size()));

            if (lhsTokens.isEmpty() || rhsTokens.isEmpty()
                    || findTopLevelComparisonOp(rhsTokens) >= 0) {
                return new ParsedPredicate(
                        raw, PredicateKind.UNKNOWN, op,
                        allRefs.size() >= 1 ? allRefs.get(0) : null,
                        allRefs.size() >= 2 ? allRefs.get(1) : null,
                        allRefs, false, true, false, range);
            }

            ParsedColRef pureLeft = parsePureColumnRef(lhsTokens);
            ParsedColRef pureRight = parsePureColumnRef(rhsTokens);
            boolean lhsConst = isPureConstantLiteral(lhsTokens);
            boolean rhsConst = isPureConstantLiteral(rhsTokens);

            if (lhsConst && rhsConst) {
                return new ParsedPredicate(
                        raw, PredicateKind.CONSTANT, op, null, null,
                        List.of(), false, false, true, range);
            }

            if (pureLeft != null && pureRight != null) {
                if ("=".equals(op) || "<=>".equals(op)) {
                    return new ParsedPredicate(
                            raw, PredicateKind.EQUI, op, pureLeft, pureRight,
                            List.of(pureLeft, pureRight), false, false, false, range);
                }
                if ("<".equals(op) || "<=".equals(op) || ">".equals(op) || ">=".equals(op)) {
                    return new ParsedPredicate(
                            raw, PredicateKind.RANGE, op, pureLeft, pureRight,
                            List.of(pureLeft, pureRight), true, false, false, range);
                }
                return new ParsedPredicate(
                        raw, PredicateKind.NON_EQUI, op, pureLeft, pureRight,
                        List.of(pureLeft, pureRight), true, false, false, range);
            }

            if ((pureLeft != null && rhsConst) || (lhsConst && pureRight != null)) {
                ParsedColRef singleCol = pureLeft != null ? pureLeft : pureRight;
                boolean rangeOrNonEqui = !"=".equals(op) && !"<=>".equals(op);
                return new ParsedPredicate(
                        raw,
                        rangeOrNonEqui ? PredicateKind.NON_EQUI : PredicateKind.CONSTANT,
                        op,
                        pureLeft,
                        pureRight,
                        List.of(singleCol),
                        rangeOrNonEqui,
                        false,
                        false,
                        range);
            }

            // Function call, arithmetic expression, CAST, COALESCE, etc.
            List<ParsedColRef> lhsRefs = extractColumnRefsFromTokens(lhsTokens);
            List<ParsedColRef> rhsRefs = extractColumnRefsFromTokens(rhsTokens);
            if (!isWellFormedExpressionSide(lhsTokens) || !isWellFormedExpressionSide(rhsTokens)) {
                return new ParsedPredicate(
                        raw, PredicateKind.UNKNOWN, op,
                        lhsRefs.isEmpty() ? null : lhsRefs.get(0),
                        rhsRefs.isEmpty() ? null : rhsRefs.get(0),
                        allRefs, false, true, false, range);
            }
            return new ParsedPredicate(
                    raw, PredicateKind.COMPLEX, op,
                    lhsRefs.isEmpty() ? null : lhsRefs.get(0),
                    rhsRefs.isEmpty() ? null : rhsRefs.get(0),
                    allRefs, true, false, allRefs.isEmpty(), range);
        }

        // Single TRUE / FALSE constant tautology
        if (conj.size() == 1 && (conj.get(0).isWord("TRUE") || conj.get(0).isWord("FALSE"))) {
            return new ParsedPredicate(
                    raw, PredicateKind.CONSTANT, null, null, null,
                    List.of(), false, false, true, range);
        }

        // Anything else in a join condition that lacks a relational operator is unparsed
        return new ParsedPredicate(
                raw, PredicateKind.UNKNOWN, null,
                allRefs.size() >= 1 ? allRefs.get(0) : null,
                allRefs.size() >= 2 ? allRefs.get(1) : null,
                allRefs, false, true, false, range);
    }

    private void analyzeWhereClause(QueryBlockModel block, List<Token> whereTokens) {
        // Split WHERE on top-level AND; note: if BETWEEN ... AND ... is present, keep BETWEEN...AND together!
        List<List<Token>> conjuncts = splitWhereAndConjuncts(whereTokens);

        TableRefModel primaryOuterTable = block.tables.isEmpty() ? null : block.tables.get(0);
        Set<String> outerScope = new LinkedHashSet<>();
        for (TableRefModel t : block.tables) {
            outerScope.add(t.effectiveLookupKey());
            outerScope.add(DataFlowIndex.normalizeIdentifier(t.name));
        }

        for (List<Token> rawConj : conjuncts) {
            List<Token> conj = stripOuterParens(rawConj);
            if (conj.isEmpty()) {
                continue;
            }
            SourceRange range = SqlJoinLexer.spanRange(conj.get(0), conj.get(conj.size() - 1));
            String rawText = sliceSql(conj.get(0), conj.get(conj.size() - 1));

            // 1. Check [NOT] EXISTS (SELECT ...)
            if ((conj.get(0).isWord("EXISTS") && conj.size() >= 2 && conj.get(1).kind() == TokenKind.LPAREN)
                    || (conj.size() >= 3 && conj.get(0).isWord("NOT")
                            && conj.get(1).isWord("EXISTS")
                            && conj.get(2).kind() == TokenKind.LPAREN)) {
                boolean isAnti = conj.get(0).isWord("NOT");
                int lparenIdx = isAnti ? 2 : 1;
                SubqueryJoinResult sqRes = parseWhereSubqueryJoin(
                        block, conj, lparenIdx, isAnti, primaryOuterTable, outerScope, rawText, range);
                if (sqRes != null) {
                    continue;
                }
            }

            // 2. Check <expr> [NOT] IN (SELECT ...)
            int inIdx = findTopLevelWord(conj, "IN");
            if (inIdx > 0 && inIdx + 1 < conj.size() && conj.get(inIdx + 1).kind() == TokenKind.LPAREN) {
                boolean isAnti = conj.get(inIdx - 1).isWord("NOT");
                List<Token> innerParen = extractParenBody(conj, inIdx + 1);
                if (!innerParen.isEmpty()
                        && (innerParen.get(0).isWord("SELECT") || innerParen.get(0).isWord("WITH"))) {
                    int lhsEnd = isAnti ? inIdx - 1 : inIdx;
                    ParsedColRef lhsCol = parsePureColumnRef(conj.subList(0, lhsEnd));
                    SubqueryJoinResult sqRes = parseWhereInSubqueryJoin(
                            block, conj, inIdx + 1, isAnti, lhsCol, primaryOuterTable, outerScope, rawText, range);
                    if (sqRes != null) {
                        continue;
                    }
                }
            }

            // 3. Check if this WHERE conjunct links an implicit comma join in block.joins
            ParsedPredicate asPred = classifySingleConjunct(conj);
            if (asPred.left != null && asPred.right != null && !asPred.constantOnly) {
                JoinStepModel matchedCommaJoin = findMatchingImplicitCommaJoin(block, asPred.left, asPred.right);
                if (matchedCommaJoin != null) {
                    matchedCommaJoin.predicates.add(asPred);
                    matchedCommaJoin.clause = JoinClause.ON;
                    continue;
                }
            }

            // 4. Record as WHERE filter (for OUTER_JOIN_FILTER_RISK & LEFT JOIN ... IS NULL anti-join detection)
            boolean isNullOnly = asPred.kind == PredicateKind.NULL_CHECK && "IS NULL".equals(asPred.op);
            ParsedColRef nullCol = isNullOnly ? asPred.left : null;
            boolean hasTopLevelOrNull = containsTopLevelWord(conj, "OR")
                    && rawText.toUpperCase(Locale.ROOT).contains("IS NULL");
            boolean wrappedNullSafe = containsNullSafeFunction(conj);
            boolean nullRejecting = !isNullOnly && !hasTopLevelOrNull && !wrappedNullSafe && !asPred.allColRefs.isEmpty();

            block.whereFilters.add(new WhereFilterModel(
                    rawText, range, asPred.allColRefs, isNullOnly, nullCol, nullRejecting));
        }
    }

    private record SubqueryJoinResult(TableRefModel subqTable, JoinStepModel joinStep) {
    }

    private SubqueryJoinResult parseWhereSubqueryJoin(
            QueryBlockModel outerBlock,
            List<Token> conj,
            int lparenIdxInConj,
            boolean isAnti,
            TableRefModel outerTable,
            Set<String> outerScope,
            String rawText,
            SourceRange range) {
        List<Token> innerTokens = extractParenBody(conj, lparenIdxInConj);
        if (innerTokens.isEmpty() || !innerTokens.get(0).isWord("SELECT")) {
            return null;
        }
        QueryBlockModel subBlock = parseInlineTokenSliceAsSubquery(
                innerTokens, outerBlock.queryBlockId, isAnti ? "NOT EXISTS Subquery" : "EXISTS Subquery");
        if (subBlock == null) {
            return null;
        }

        TableRefModel subTableRef = new TableRefModel(
                "t-" + (++tableCounter),
                null,
                null,
                subBlock.label,
                subBlock.queryBlockId,
                false,
                null,
                true,
                subBlock.queryBlockId,
                subBlock.rawSql,
                subBlock.sourceRange);
        outerBlock.tables.add(subTableRef);

        JoinStepModel joinStep = new JoinStepModel(
                outerBlock.joins.size() + 1,
                outerTable != null ? outerTable : subTableRef,
                new LinkedHashSet<>(outerScope),
                subTableRef,
                isAnti ? JoinType.LEFT : JoinType.INNER,
                JoinClause.ON,
                false,
                false,
                !isAnti,
                isAnti,
                !isAnti,
                isAnti,
                rawText,
                rawText,
                range,
                range);

        // Extract correlated predicates from subBlock.whereFilters referencing outerScope
        for (WhereFilterModel wf : subBlock.whereFilters) {
            if (wf.colRefs.size() >= 2) {
                ParsedColRef c0 = wf.colRefs.get(0);
                ParsedColRef c1 = wf.colRefs.get(1);
                joinStep.predicates.add(new ParsedPredicate(
                        wf.rawText, PredicateKind.EQUI, "=", c0, c1, wf.colRefs,
                        false, false, false, wf.sourceRange));
            }
        }
        outerBlock.joins.add(joinStep);
        return new SubqueryJoinResult(subTableRef, joinStep);
    }

    private SubqueryJoinResult parseWhereInSubqueryJoin(
            QueryBlockModel outerBlock,
            List<Token> conj,
            int lparenIdxInConj,
            boolean isAnti,
            ParsedColRef lhsCol,
            TableRefModel outerTable,
            Set<String> outerScope,
            String rawText,
            SourceRange range) {
        List<Token> innerTokens = extractParenBody(conj, lparenIdxInConj);
        QueryBlockModel subBlock = parseInlineTokenSliceAsSubquery(
                innerTokens, outerBlock.queryBlockId, isAnti ? "NOT IN Subquery" : "IN Subquery");
        if (subBlock == null) {
            return null;
        }

        TableRefModel subTableRef = new TableRefModel(
                "t-" + (++tableCounter),
                null,
                null,
                subBlock.label,
                subBlock.queryBlockId,
                false,
                null,
                true,
                subBlock.queryBlockId,
                subBlock.rawSql,
                subBlock.sourceRange);
        outerBlock.tables.add(subTableRef);

        JoinStepModel joinStep = new JoinStepModel(
                outerBlock.joins.size() + 1,
                outerTable != null ? outerTable : subTableRef,
                new LinkedHashSet<>(outerScope),
                subTableRef,
                isAnti ? JoinType.LEFT : JoinType.INNER,
                JoinClause.ON,
                false,
                false,
                !isAnti,
                isAnti,
                !isAnti,
                isAnti,
                rawText,
                rawText,
                range,
                range);

        ParsedColRef rhsCol = !subBlock.selectCols.isEmpty() ? subBlock.selectCols.get(0) : null;
        List<ParsedColRef> both = new ArrayList<>();
        if (lhsCol != null) {
            both.add(lhsCol);
        }
        if (rhsCol != null) {
            both.add(rhsCol);
        }
        joinStep.predicates.add(new ParsedPredicate(
                rawText, PredicateKind.EQUI, isAnti ? "NOT IN" : "IN", lhsCol, rhsCol, both,
                false, false, false, range));
        outerBlock.joins.add(joinStep);
        return new SubqueryJoinResult(subTableRef, joinStep);
    }

    private QueryBlockModel parseInlineTokenSliceAsSubquery(
            List<Token> subTokens,
            String parentBlockId,
            String labelPrefix) {
        List<Token> withEof = new ArrayList<>(subTokens.size() + 1);
        withEof.addAll(subTokens);
        Token last = subTokens.get(subTokens.size() - 1);
        withEof.add(new Token(
                TokenKind.EOF, "", "", last.endOffset(), last.endOffset(),
                last.endLine(), last.endCol(), last.endLine(), last.endCol()));

        SqlJoinAstParser nested = new SqlJoinAstParser(
                this.sql, withEof, this.catalogMeta, this.defaultDatabase, this.defaultSchema);
        nested.subqCounter = this.subqCounter;
        nested.tableCounter = this.tableCounter;
        nested.activeCteBlockIds.putAll(this.activeCteBlockIds);

        String subId = "qb-subq-" + (++this.subqCounter);
        nested.parseSelectChain(subId, labelPrefix + " #" + this.subqCounter, LaneKind.SUBQUERY, parentBlockId, withEof.get(0));
        this.subqCounter = Math.max(this.subqCounter, nested.subqCounter);
        this.tableCounter = nested.tableCounter;

        allBlocks.addAll(nested.allBlocks);
        for (QueryBlockModel b : nested.allBlocks) {
            if (b.queryBlockId.equals(subId)) {
                return b;
            }
        }
        return nested.allBlocks.isEmpty() ? null : nested.allBlocks.get(nested.allBlocks.size() - 1);
    }

    private JoinStepModel findMatchingImplicitCommaJoin(
            QueryBlockModel block,
            ParsedColRef left,
            ParsedColRef right) {
        String lq = left.qualifier != null ? DataFlowIndex.normalizeIdentifier(left.qualifier) : null;
        String rq = right.qualifier != null ? DataFlowIndex.normalizeIdentifier(right.qualifier) : null;
        if (lq == null || rq == null) {
            return null;
        }
        for (JoinStepModel js : block.joins) {
            if (!js.implicitCommaJoin) {
                continue;
            }
            String rightKey = js.rightTable.effectiveLookupKey();
            String rightName = DataFlowIndex.normalizeIdentifier(js.rightTable.name);
            boolean lInLeft = js.leftScopeKeys.contains(lq);
            boolean rIsRight = rq.equals(rightKey) || rq.equals(rightName);
            boolean rInLeft = js.leftScopeKeys.contains(rq);
            boolean lIsRight = lq.equals(rightKey) || lq.equals(rightName);
            if ((lInLeft && rIsRight) || (rInLeft && lIsRight)) {
                return js;
            }
        }
        return null;
    }

    private void attributeContributedColumns(QueryBlockModel block) {
        Map<String, TableRefModel> byKey = new LinkedHashMap<>();
        for (TableRefModel tr : block.tables) {
            byKey.put(tr.effectiveLookupKey(), tr);
            byKey.putIfAbsent(DataFlowIndex.normalizeIdentifier(tr.name), tr);
        }

        List<ParsedColRef> allRefs = new ArrayList<>(block.selectCols);
        for (JoinStepModel js : block.joins) {
            for (ParsedPredicate p : js.predicates) {
                allRefs.addAll(p.allColRefs);
            }
        }
        for (WhereFilterModel wf : block.whereFilters) {
            allRefs.addAll(wf.colRefs);
        }

        for (ParsedColRef ref : allRefs) {
            if (ref.qualifier != null && !ref.qualifier.isBlank()) {
                TableRefModel matched = byKey.get(DataFlowIndex.normalizeIdentifier(ref.qualifier));
                if (matched != null) {
                    matched.contributedColumns.add(ref.column);
                }
            } else if (block.tables.size() == 1) {
                block.tables.get(0).contributedColumns.add(ref.column);
            } else {
                // Match against catalog metadata if unambiguous
                for (TableRefModel cand : block.tables) {
                    TableMeta tm = catalogMeta.findTable(cand.database, cand.schema, cand.name);
                    if (tm != null && tm.findColumn(ref.column) != null) {
                        cand.contributedColumns.add(ref.column);
                        break;
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Token Slice & Expression Helpers
    // -------------------------------------------------------------------------

    private record QualifiedIdent(String db, String schema, String name) {
    }

    private QualifiedIdent readQualifiedName() {
        if (!peek().isIdentifierLike()) {
            return null;
        }
        List<String> parts = new ArrayList<>(3);
        parts.add(advance().cleanIdentifier());
        while (peek().kind() == TokenKind.DOT) {
            advance(); // '.'
            if (peek().isIdentifierLike()) {
                parts.add(advance().cleanIdentifier());
            } else {
                break;
            }
        }
        if (parts.size() == 1) {
            return new QualifiedIdent(null, null, parts.get(0));
        }
        if (parts.size() == 2) {
            return new QualifiedIdent(null, parts.get(0), parts.get(1));
        }
        return new QualifiedIdent(
                parts.get(parts.size() - 3),
                parts.get(parts.size() - 2),
                parts.get(parts.size() - 1));
    }

    private List<String> readParenColumnList() {
        List<String> cols = new ArrayList<>();
        if (peek().kind() != TokenKind.LPAREN) {
            return cols;
        }
        advance(); // '('
        int depth = 1;
        while (!isAtEnd() && depth > 0) {
            Token t = peek();
            if (t.kind() == TokenKind.LPAREN) {
                depth++;
                advance();
            } else if (t.kind() == TokenKind.RPAREN) {
                depth--;
                advance();
            } else if (depth == 1 && t.isIdentifierLike()
                    && !t.isWord("ASC") && !t.isWord("DESC") && !t.isWord("NULLS")
                    && !t.isWord("FIRST") && !t.isWord("LAST")) {
                cols.add(advance().cleanIdentifier());
            } else {
                advance();
            }
        }
        return cols;
    }

    private static List<Token> stripOuterParens(List<Token> list) {
        List<Token> cur = list;
        while (cur.size() >= 2
                && cur.get(0).kind() == TokenKind.LPAREN
                && cur.get(cur.size() - 1).kind() == TokenKind.RPAREN) {
            int depth = 0;
            boolean wrapsAll = true;
            for (int i = 0; i < cur.size() - 1; i++) {
                if (cur.get(i).kind() == TokenKind.LPAREN) {
                    depth++;
                } else if (cur.get(i).kind() == TokenKind.RPAREN) {
                    depth--;
                    if (depth == 0) {
                        wrapsAll = false;
                        break;
                    }
                }
            }
            if (wrapsAll) {
                cur = cur.subList(1, cur.size() - 1);
            } else {
                break;
            }
        }
        return cur;
    }

    private static boolean containsUnparsedTokens(List<Token> slice) {
        int depth = 0;
        for (int i = 0; i < slice.size(); i++) {
            Token t = slice.get(i);
            if (t.kind() == TokenKind.TEMPLATE || t.kind() == TokenKind.UNKNOWN) {
                return true;
            }
            if (t.kind() == TokenKind.LPAREN) {
                depth++;
            } else if (t.kind() == TokenKind.RPAREN) {
                depth--;
                if (depth < 0) {
                    return true;
                }
            }
            // Detect broken double operators like '= =' or '==='
            if (t.kind() == TokenKind.OPERATOR && i + 1 < slice.size()
                    && slice.get(i + 1).kind() == TokenKind.OPERATOR) {
                String nextOp = slice.get(i + 1).text();
                if (!"-".equals(nextOp) && !"+".equals(nextOp) && !"~".equals(nextOp)) {
                    return true;
                }
            }
        }
        if (depth != 0) {
            return true;
        }
        Token last = slice.get(slice.size() - 1);
        return last.kind() == TokenKind.OPERATOR || last.kind() == TokenKind.DOT;
    }

    private static boolean isWellFormedExpressionSide(List<Token> side) {
        if (side.isEmpty()) {
            return false;
        }
        if (side.get(side.size() - 1).kind() == TokenKind.OPERATOR
                || side.get(side.size() - 1).kind() == TokenKind.DOT) {
            return false;
        }
        // Check adjacent bare identifiers not separated by dot, operator, comma, or keyword (e.g. "a.id b.id")
        for (int i = 0; i + 1 < side.size(); i++) {
            Token cur = side.get(i);
            Token nxt = side.get(i + 1);
            if (cur.isIdentifierLike() && nxt.isIdentifierLike()) {
                if (!isAllowedAdjacentExprKeyword(cur.upper()) && !isAllowedAdjacentExprKeyword(nxt.upper())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isAllowedAdjacentExprKeyword(String kw) {
        return switch (kw) {
            case "CASE", "WHEN", "THEN", "ELSE", "END", "AND", "OR", "NOT", "IS", "NULL",
                    "AS", "CAST", "DISTINCT", "INTERVAL", "AT", "TIME", "ZONE", "COLLATE",
                    "ESCAPE", "SIMILAR", "TO", "BETWEEN", "IN", "LIKE", "ILIKE" -> true;
            default -> false;
        };
    }

    private static ParsedColRef parsePureColumnRef(List<Token> side) {
        List<Token> s = stripOuterParens(side);
        if (s.isEmpty()) {
            return null;
        }
        if (s.size() == 1 && s.get(0).isIdentifierLike()) {
            Token tok = s.get(0);
            if (tok.isWord("NULL") || tok.isWord("TRUE") || tok.isWord("FALSE")) {
                return null;
            }
            String col = tok.cleanIdentifier();
            return new ParsedColRef(null, col, col, tok.toSourceRange());
        }
        // Qualified: a.col or schema.a.col
        List<String> parts = new ArrayList<>(4);
        for (int i = 0; i < s.size(); i++) {
            Token t = s.get(i);
            if (i % 2 == 0) {
                if (!t.isIdentifierLike()) {
                    return null;
                }
                parts.add(t.cleanIdentifier());
            } else {
                if (t.kind() != TokenKind.DOT) {
                    return null;
                }
            }
        }
        if (s.size() % 2 == 1 && parts.size() >= 2) {
            String qual = parts.get(parts.size() - 2);
            String col = parts.get(parts.size() - 1);
            return new ParsedColRef(
                    qual,
                    col,
                    qual + "." + col,
                    SqlJoinLexer.spanRange(s.get(0), s.get(s.size() - 1)));
        }
        return null;
    }

    private static boolean isPureConstantLiteral(List<Token> side) {
        List<Token> s = stripOuterParens(side);
        if (s.isEmpty()) {
            return false;
        }
        if (s.size() == 1) {
            Token t = s.get(0);
            return t.kind() == TokenKind.NUMBER
                    || t.kind() == TokenKind.STRING
                    || t.isWord("NULL")
                    || t.isWord("TRUE")
                    || t.isWord("FALSE");
        }
        if (s.size() == 2 && ("-".equals(s.get(0).text()) || "+".equals(s.get(0).text()))
                && s.get(1).kind() == TokenKind.NUMBER) {
            return true;
        }
        return false;
    }

    private static List<ParsedColRef> extractColumnRefsFromTokens(List<Token> slice) {
        List<ParsedColRef> refs = new ArrayList<>();
        int n = slice.size();
        int i = 0;
        while (i < n) {
            Token t = slice.get(i);
            if (t.isIdentifierLike() && !isExpressionReservedKeyword(t.upper())) {
                // Skip function name followed immediately by '('
                if (i + 1 < n && slice.get(i + 1).kind() == TokenKind.LPAREN) {
                    i++;
                    continue;
                }
                // Check dotted chain a.b or db.a.b
                int start = i;
                List<String> chain = new ArrayList<>(3);
                chain.add(t.cleanIdentifier());
                while (i + 2 < n
                        && slice.get(i + 1).kind() == TokenKind.DOT
                        && (slice.get(i + 2).isIdentifierLike() || slice.get(i + 2).kind() == TokenKind.STAR)) {
                    if (slice.get(i + 2).kind() == TokenKind.STAR) {
                        i += 2;
                        chain.add("*");
                        break;
                    }
                    chain.add(slice.get(i + 2).cleanIdentifier());
                    i += 2;
                }
                // Skip if the end of the dotted chain is a function call pkg.fn(...)
                if (i + 1 < n && slice.get(i + 1).kind() == TokenKind.LPAREN) {
                    i++;
                    continue;
                }
                // Skip AS <column_alias> in SELECT list
                if (start > 0 && slice.get(start - 1).isWord("AS")) {
                    i++;
                    continue;
                }
                // Skip bare alias right before comma in SELECT expr alias, ...
                if (chain.size() == 1 && start > 0) {
                    Token prev = slice.get(start - 1);
                    if (prev.isIdentifierLike() || prev.kind() == TokenKind.RPAREN
                            || prev.kind() == TokenKind.STRING || prev.kind() == TokenKind.NUMBER) {
                        if (!isExpressionReservedKeyword(prev.upper())) {
                            i++;
                            continue;
                        }
                    }
                }
                String col = chain.get(chain.size() - 1);
                if (!"*".equals(col)) {
                    String qual = chain.size() >= 2 ? chain.get(chain.size() - 2) : null;
                    String disp = qual != null ? qual + "." + col : col;
                    refs.add(new ParsedColRef(
                            qual, col, disp, SqlJoinLexer.spanRange(slice.get(start), slice.get(i))));
                }
            }
            i++;
        }
        return refs;
    }

    private static boolean isExpressionReservedKeyword(String upper) {
        return switch (upper) {
            case "SELECT", "DISTINCT", "ALL", "FROM", "WHERE", "AND", "OR", "NOT", "IN",
                    "IS", "NULL", "TRUE", "FALSE", "AS", "ON", "USING", "CASE", "WHEN",
                    "THEN", "ELSE", "END", "BETWEEN", "LIKE", "ILIKE", "EXISTS", "CAST",
                    "INT", "INTEGER", "BIGINT", "VARCHAR", "CHAR", "TEXT", "DATE",
                    "TIMESTAMP", "NUMERIC", "DECIMAL", "BOOLEAN", "FLOAT", "DOUBLE",
                    "ASC", "DESC", "OVER", "PARTITION", "BY", "ORDER", "ROWS", "RANGE" -> true;
            default -> false;
        };
    }

    private static boolean containsNullSafeFunction(List<Token> conj) {
        for (int i = 0; i + 1 < conj.size(); i++) {
            Token t = conj.get(i);
            if (t.kind() == TokenKind.IDENT && conj.get(i + 1).kind() == TokenKind.LPAREN) {
                String fn = t.upper();
                if ("COALESCE".equals(fn) || "NVL".equals(fn) || "IFNULL".equals(fn) || "ISNULL".equals(fn)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsTopLevelWord(List<Token> list, String word) {
        return findTopLevelWord(list, word) >= 0;
    }

    private static int findTopLevelWord(List<Token> list, String word) {
        int depth = 0;
        for (int i = 0; i < list.size(); i++) {
            Token t = list.get(i);
            if (t.kind() == TokenKind.LPAREN) {
                depth++;
            } else if (t.kind() == TokenKind.RPAREN) {
                depth--;
            } else if (depth == 0 && t.isWord(word)) {
                return i;
            }
        }
        return -1;
    }

    private static int findTopLevelComparisonOp(List<Token> list) {
        int depth = 0;
        for (int i = 0; i < list.size(); i++) {
            Token t = list.get(i);
            if (t.kind() == TokenKind.LPAREN) {
                depth++;
            } else if (t.kind() == TokenKind.RPAREN) {
                depth--;
            } else if (depth == 0 && t.kind() == TokenKind.OPERATOR) {
                String op = t.text();
                if ("=".equals(op) || "<=>".equals(op) || "!=".equals(op) || "<>".equals(op)
                        || "<".equals(op) || "<=".equals(op) || ">".equals(op) || ">=".equals(op)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<List<Token>> splitTopLevelWord(List<Token> list, String word) {
        List<List<Token>> parts = new ArrayList<>();
        int depth = 0;
        boolean pendingBetweenAnd = false;
        int start = 0;
        for (int i = 0; i < list.size(); i++) {
            Token t = list.get(i);
            if (t.kind() == TokenKind.LPAREN) {
                depth++;
            } else if (t.kind() == TokenKind.RPAREN) {
                depth--;
            } else if (depth == 0) {
                if (t.isWord("BETWEEN")) {
                    pendingBetweenAnd = true;
                } else if (t.isWord(word)) {
                    if ("AND".equals(word) && pendingBetweenAnd) {
                        pendingBetweenAnd = false;
                        continue;
                    }
                    parts.add(list.subList(start, i));
                    start = i + 1;
                }
            }
        }
        parts.add(list.subList(start, list.size()));
        return parts;
    }

    private static List<List<Token>> splitWhereAndConjuncts(List<Token> whereTokens) {
        return splitTopLevelWord(whereTokens, "AND");
    }

    private static List<Token> extractParenBody(List<Token> list, int lparenIndex) {
        if (lparenIndex < 0 || lparenIndex >= list.size() || list.get(lparenIndex).kind() != TokenKind.LPAREN) {
            return List.of();
        }
        int depth = 1;
        int i = lparenIndex + 1;
        while (i < list.size() && depth > 0) {
            if (list.get(i).kind() == TokenKind.LPAREN) {
                depth++;
            } else if (list.get(i).kind() == TokenKind.RPAREN) {
                depth--;
                if (depth == 0) {
                    return list.subList(lparenIndex + 1, i);
                }
            }
            i++;
        }
        return List.of();
    }

    private boolean isJoinStartToken(Token t) {
        if (t.kind() != TokenKind.IDENT) {
            return false;
        }
        return switch (t.upper()) {
            case "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "SEMI", "ANTI", "OUTER" -> true;
            default -> false;
        };
    }

    private boolean peekNextIsSelectOrWith() {
        int idx = pos + 1;
        while (idx < tokens.size() && tokens.get(idx).kind() == TokenKind.LPAREN) {
            idx++;
        }
        return idx < tokens.size()
                && (tokens.get(idx).isWord("SELECT") || tokens.get(idx).isWord("WITH"));
    }

    private void skipBalancedParens() {
        if (peek().kind() != TokenKind.LPAREN) {
            return;
        }
        advance();
        int depth = 1;
        while (!isAtEnd() && depth > 0) {
            Token t = advance();
            if (t.kind() == TokenKind.LPAREN) {
                depth++;
            } else if (t.kind() == TokenKind.RPAREN) {
                depth--;
            }
        }
    }

    private void skipToCommaOrCloseParen() {
        int depth = 0;
        while (!isAtEnd()) {
            Token t = peek();
            if (t.kind() == TokenKind.LPAREN) {
                depth++;
                advance();
            } else if (t.kind() == TokenKind.RPAREN) {
                if (depth == 0) {
                    return;
                }
                depth--;
                advance();
            } else if (t.kind() == TokenKind.COMMA && depth == 0) {
                return;
            } else {
                advance();
            }
        }
    }

    private void skipToSemicolon() {
        while (!isAtEnd() && peek().kind() != TokenKind.SEMICOLON) {
            advance();
        }
        if (peek().kind() == TokenKind.SEMICOLON) {
            advance();
        }
    }

    private String sliceSql(Token start, Token end) {
        if (start == null || end == null || sql.isEmpty()) {
            return "";
        }
        int s = Math.max(0, Math.min(sql.length(), start.startOffset()));
        int e = Math.max(s, Math.min(sql.length(), end.endOffset()));
        return sql.substring(s, e).trim();
    }

    private boolean isAtEnd() {
        return peek().kind() == TokenKind.EOF;
    }

    private Token peek() {
        return tokens.get(Math.min(pos, tokens.size() - 1));
    }

    private Token previous() {
        return tokens.get(Math.max(0, pos - 1));
    }

    private Token advance() {
        if (!isAtEnd()) {
            pos++;
        }
        return tokens.get(pos - 1);
    }

    private boolean matchWord(String kw) {
        if (peek().isWord(kw)) {
            advance();
            return true;
        }
        return false;
    }
}
