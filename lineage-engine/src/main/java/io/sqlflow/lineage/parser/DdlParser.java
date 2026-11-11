package io.sqlflow.lineage.parser;

import io.sqlflow.lineage.ast.Expr;
import io.sqlflow.lineage.ast.QualifiedName;
import io.sqlflow.lineage.ast.QueryAst.QueryExpr;
import io.sqlflow.lineage.ast.SqlStmt;
import io.sqlflow.lineage.ast.SqlStmt.AlterAction;
import io.sqlflow.lineage.ast.SqlStmt.AlterTableStmt;
import io.sqlflow.lineage.ast.SqlStmt.ColumnDef;
import io.sqlflow.lineage.ast.SqlStmt.CommentOnStmt;
import io.sqlflow.lineage.ast.SqlStmt.CreateDatabaseOrSchemaStmt;
import io.sqlflow.lineage.ast.SqlStmt.CreateSequenceStmt;
import io.sqlflow.lineage.ast.SqlStmt.CreateStageOrStreamStmt;
import io.sqlflow.lineage.ast.SqlStmt.CreateSynonymStmt;
import io.sqlflow.lineage.ast.SqlStmt.CreateTableStmt;
import io.sqlflow.lineage.ast.SqlStmt.CreateViewStmt;
import io.sqlflow.lineage.ast.SqlStmt.DropStmt;
import io.sqlflow.lineage.ast.SqlStmt.ForeignKeySpec;
import io.sqlflow.lineage.ast.SqlStmt.TableConstraint;
import io.sqlflow.lineage.ast.SqlStmt.TruncateStmt;
import io.sqlflow.lineage.ast.SqlStmt.UseContextStmt;
import io.sqlflow.lineage.lexer.SourceSpan;
import io.sqlflow.lineage.lexer.SqlToken;
import io.sqlflow.lineage.lexer.TokenType;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-dialect DDL & session-context parser ({@code CREATE TABLE/VIEW/SYNONYM/SEQUENCE/STAGE/STREAM/DATABASE/SCHEMA},
 * {@code ALTER TABLE}, {@code DROP}, {@code TRUNCATE}, {@code RENAME}, {@code COMMENT ON}, {@code USE}/{@code SET}).
 */
public final class DdlParser {

    private final ParserCursor cur;
    private final ExpressionParser exprParser;
    private final QueryParser queryParser;
    private ProceduralAndSpecialParser proceduralParser;

    public DdlParser(ParserCursor cur, ExpressionParser exprParser, QueryParser queryParser) {
        this.cur = cur;
        this.exprParser = exprParser;
        this.queryParser = queryParser;
    }

    public void setProceduralParser(ProceduralAndSpecialParser proceduralParser) {
        this.proceduralParser = proceduralParser;
    }

    public SqlStmt parseCreateStmt() {
        SourceSpan startSpan = cur.expectWord("CREATE").span();
        boolean orReplace = false;
        if (cur.matchWords("OR", "REPLACE") || cur.matchWords("OR", "ALTER")) {
            orReplace = true;
        }

        boolean temporary = false;
        boolean globalTemp = false;
        boolean external = false;
        boolean materialized = false;
        boolean isPublic = false;

        // Consume CREATE modifiers across dialects (GLOBAL TEMPORARY, LOCAL TEMP, VOLATILE, TRANSIENT, MULTISET, SET, EXTERNAL, MATERIALIZED, FORCE, EDITIONABLE, SECURE)
        while (!cur.isAtEnd()) {
            if (cur.matchWord("GLOBAL")) {
                if (cur.matchAnyWord("TEMPORARY", "TEMP")) {
                    temporary = true;
                    globalTemp = true;
                }
            } else if (cur.matchWord("LOCAL") && cur.matchAnyWord("TEMPORARY", "TEMP")) {
                temporary = true;
            } else if (cur.matchAnyWord("TEMPORARY", "TEMP", "VOLATILE")) {
                temporary = true;
            } else if (cur.matchWord("EXTERNAL")) {
                external = true;
            } else if (cur.matchWord("MATERIALIZED")) {
                materialized = true;
            } else if (cur.matchWord("PUBLIC")) {
                isPublic = true;
            } else if (cur.matchAnyWord("TRANSIENT", "MULTISET", "SET", "FORCE", "NOFORCE",
                    "EDITIONABLE", "NONEDITIONABLE", "SECURE", "RECURSIVE", "DYNAMIC",
                    "HYBRID", "ICEBERG", "UNLOGGED", "SHARED", "DEFINER", "AGGREGATE")) {
                // Consume dialect object modifier
                if (cur.previous().isWord("DEFINER") && cur.matchSymbol("=")) {
                    cur.parseQualifiedName();
                }
            } else {
                break;
            }
        }

        if (cur.matchWord("TABLE")) {
            return parseCreateTableBody(startSpan, orReplace, temporary, globalTemp, external);
        }
        if (cur.matchWord("VIEW")) {
            return parseCreateViewBody(startSpan, orReplace, materialized);
        }
        if (cur.matchWord("PACKAGE")) {
            return proceduralParser.parseCreatePackageBody(startSpan);
        }
        if (cur.matchAnyWord("PROCEDURE", "PROC")) {
            return proceduralParser.parseCreateRoutineBody(startSpan, "PROCEDURE");
        }
        if (cur.matchWord("FUNCTION")) {
            return proceduralParser.parseCreateRoutineBody(startSpan, "FUNCTION");
        }
        if (cur.matchWord("TRIGGER")) {
            return proceduralParser.parseCreateRoutineBody(startSpan, "TRIGGER");
        }
        if (cur.matchWord("MACRO")) {
            return proceduralParser.parseCreateRoutineBody(startSpan, "MACRO");
        }
        if (cur.matchWord("SYNONYM") || cur.matchWord("ALIAS")) {
            return parseCreateSynonymBody(startSpan, orReplace, isPublic);
        }
        if (cur.matchWord("SEQUENCE")) {
            return parseCreateSequenceBody(startSpan);
        }
        if (cur.matchWord("STAGE")) {
            return parseCreateStageBody(startSpan);
        }
        if (cur.matchWord("STREAM")) {
            return parseCreateStreamBody(startSpan);
        }
        if (cur.matchWords("DATA", "SOURCE")) {
            return parseCreateDataSourceBody(startSpan);
        }
        if (cur.matchAnyWord("DATABASE", "SCHEMA")) {
            String kind = cur.previous().upperValue();
            return parseCreateDatabaseOrSchemaBody(startSpan, kind);
        }
        // Index, Type, Role, Warehouse, etc. -> consume safely as NoOpStmt
        String objKind = cur.isAtEnd() ? "OBJECT" : cur.advance().upperValue();
        skipRemainingTokens();
        return new SqlStmt.NoOpStmt("CREATE_" + objKind, SourceSpan.between(startSpan, cur.previous().span()));
    }

    private CreateTableStmt parseCreateTableBody(
            SourceSpan startSpan,
            boolean orReplace,
            boolean temporary,
            boolean globalTemp,
            boolean external) {
        skipIfNotExists();
        QualifiedName tableName = cur.parseQualifiedName();

        List<ColumnDef> columns = new ArrayList<>();
        List<TableConstraint> constraints = new ArrayList<>();
        QueryExpr asSelect = null;
        QualifiedName likeTable = null;
        QualifiedName cloneTable = null;
        String locationUri = null;
        String fileFormat = null;
        String tableComment = null;

        // Case A: CREATE TABLE t LIKE src / CREATE TABLE t (LIKE src)
        if (cur.matchWord("LIKE")) {
            likeTable = cur.parseQualifiedName();
        } else if (cur.matchWord("CLONE")) {
            cloneTable = cur.parseQualifiedName();
        } else if (cur.checkSymbol("(")) {
            // Could be (SELECT ...) direct parenthesized CTAS or (col_defs...)
            if (cur.peek(1).isAnyWord("SELECT", "WITH", "VALUES")) {
                cur.expectSymbol("(");
                asSelect = queryParser.parseQuery();
                cur.expectSymbol(")");
            } else {
                parseTableElementList(columns, constraints);
            }
        }

        // Scan table options (ENGINE, PARTITION BY, CLUSTER BY, LOCATION, STORED AS, USING, COMMENT, CLONE, LIKE, AS SELECT)
        while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            if (cur.matchWord("CLONE")) {
                cloneTable = cur.parseQualifiedName();
                if (cur.checkAnyWord("AT", "BEFORE") && cur.peek(1).isSymbol("(")) {
                    cur.advance();
                    cur.skipParenthesizedGroup();
                }
            } else if (cur.matchWord("LIKE")) {
                likeTable = cur.parseQualifiedName();
            } else if (cur.matchWord("LOCATION")) {
                cur.matchSymbol("=");
                if (cur.peek().type() == TokenType.STRING_LITERAL || cur.peek().type() == TokenType.STAGE_REFERENCE) {
                    locationUri = cur.advance().value();
                }
            } else if (cur.matchWords("STORED", "AS")) {
                if (!cur.isAtEnd()) {
                    fileFormat = cur.advance().upperValue();
                }
            } else if (cur.matchWord("USING") && cur.peek().isIdentifierLike()
                    && !cur.peek(1).isAnyWord("SELECT", "WITH")) {
                fileFormat = cur.advance().upperValue();
            } else if (cur.matchWord("COMMENT")) {
                cur.matchSymbol("=");
                if (cur.peek().type() == TokenType.STRING_LITERAL) {
                    tableComment = cur.advance().value();
                }
            } else if (cur.matchWord("AS")) {
                if (cur.checkSymbol("(") && cur.peek(1).isAnyWord("SELECT", "WITH", "VALUES")) {
                    cur.expectSymbol("(");
                    asSelect = queryParser.parseQuery();
                    cur.expectSymbol(")");
                } else if (cur.checkAnyWord("SELECT", "WITH", "VALUES", "FROM")) {
                    asSelect = queryParser.parseQuery();
                }
                break;
            } else if (cur.checkAnyWord("SELECT", "WITH") && asSelect == null) {
                // BigQuery / Hive / Teradata CTAS without AS keyword, or WITH (...) AS SELECT
                if (cur.checkWord("WITH") && cur.peek(1).isSymbol("(")) {
                    cur.advance();
                    cur.skipParenthesizedGroup();
                    continue;
                }
                asSelect = queryParser.parseQuery();
                break;
            } else if (cur.checkSymbol("(")) {
                cur.skipParenthesizedGroup();
            } else {
                cur.advance();
            }
        }

        // Consume trailing Teradata `WITH DATA` / `WITH NO DATA` if present
        while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            cur.advance();
        }

        if (likeTable == null) {
            // Check if (LIKE other_tbl) was inside parens
            for (TableConstraint tc : constraints) {
                if ("LIKE".equals(tc.constraintType()) && tc.foreignKey() != null) {
                    likeTable = tc.foreignKey().referencedTable();
                }
            }
        }

        return new CreateTableStmt(
                orReplace,
                temporary,
                globalTemp,
                external,
                tableName,
                columns,
                constraints,
                asSelect,
                likeTable,
                cloneTable,
                locationUri,
                fileFormat,
                tableComment,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private void parseTableElementList(List<ColumnDef> columns, List<TableConstraint> constraints) {
        cur.expectSymbol("(");
        while (!cur.isAtEnd() && !cur.checkSymbol(")")) {
            if (cur.matchWord("LIKE")) {
                QualifiedName liked = cur.parseQualifiedName();
                while (!cur.isAtEnd() && !cur.checkSymbol(",") && !cur.checkSymbol(")")) {
                    cur.advance();
                }
                constraints.add(new TableConstraint(
                        null,
                        "LIKE",
                        List.of(),
                        new ForeignKeySpec(null, List.of(), liked, List.of(), liked.span()),
                        liked.span()));
            } else if (isTableLevelConstraintStart()) {
                TableConstraint tc = parseTableConstraint();
                if (tc != null) {
                    constraints.add(tc);
                }
            } else if (cur.matchWord("PERIOD") && cur.checkWord("FOR")) {
                while (!cur.isAtEnd() && !cur.checkSymbol(",") && !cur.checkSymbol(")")) {
                    if (cur.checkSymbol("(")) {
                        cur.skipParenthesizedGroup();
                    } else {
                        cur.advance();
                    }
                }
            } else {
                ColumnDef col = parseColumnDef(constraints);
                if (col != null) {
                    columns.add(col);
                }
            }
            if (!cur.matchSymbol(",")) {
                break;
            }
        }
        if (cur.checkSymbol(")")) {
            cur.expectSymbol(")");
        }
    }

    private boolean checkWords(String w1) {
        return cur.checkWord(w1);
    }

    private boolean isTableLevelConstraintStart() {
        if (cur.checkAnyWord("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK")) {
            return true;
        }
        if (cur.checkAnyWord("INDEX", "KEY", "FULLTEXT", "SPATIAL")
                && (cur.peek(1).isSymbol("(") || cur.peek(2).isSymbol("("))) {
            return true;
        }
        return false;
    }

    public TableConstraint parseTableConstraint() {
        SourceSpan startSpan = cur.peek().span();
        String constraintName = null;
        if (cur.matchWord("CONSTRAINT")) {
            if (cur.peek().isIdentifierLike()
                    && !cur.checkAnyWord("PRIMARY", "UNIQUE", "FOREIGN", "CHECK")) {
                constraintName = cur.advance().value();
            }
        }

        if (cur.matchWords("PRIMARY", "KEY")) {
            cur.matchAnyWord("CLUSTERED", "NONCLUSTERED");
            List<String> cols = parseConstraintColumnNames();
            skipTrailingConstraintOptions();
            return new TableConstraint(constraintName, "PRIMARY_KEY", cols, null,
                    SourceSpan.between(startSpan, cur.previous().span()));
        }

        if (cur.matchWord("UNIQUE")) {
            cur.matchAnyWord("KEY", "INDEX", "CLUSTERED", "NONCLUSTERED");
            if (constraintName == null && cur.peek().isIdentifierLike() && cur.peek(1).isSymbol("(")) {
                constraintName = cur.advance().value();
            }
            List<String> cols = parseConstraintColumnNames();
            skipTrailingConstraintOptions();
            return new TableConstraint(constraintName, "UNIQUE", cols, null,
                    SourceSpan.between(startSpan, cur.previous().span()));
        }

        if (cur.matchWords("FOREIGN", "KEY")) {
            if (constraintName == null && cur.peek().isIdentifierLike() && cur.peek(1).isSymbol("(")) {
                constraintName = cur.advance().value();
            }
            List<String> localCols = parseConstraintColumnNames();
            QualifiedName refTable = null;
            List<String> refCols = List.of();
            if (cur.matchWord("REFERENCES")) {
                refTable = cur.parseQualifiedName();
                if (cur.checkSymbol("(")) {
                    refCols = parseConstraintColumnNames();
                }
            }
            skipTrailingConstraintOptions();
            ForeignKeySpec fk = refTable != null
                    ? new ForeignKeySpec(constraintName, localCols, refTable, refCols,
                    SourceSpan.between(startSpan, cur.previous().span()))
                    : null;
            return new TableConstraint(constraintName, "FOREIGN_KEY", localCols, fk,
                    SourceSpan.between(startSpan, cur.previous().span()));
        }

        if (cur.matchAnyWord("INDEX", "KEY", "FULLTEXT", "SPATIAL")) {
            cur.matchAnyWord("INDEX", "KEY");
            if (constraintName == null && cur.peek().isIdentifierLike() && cur.peek(1).isSymbol("(")) {
                constraintName = cur.advance().value();
            }
            List<String> cols = cur.checkSymbol("(") ? parseConstraintColumnNames() : List.of();
            skipTrailingConstraintOptions();
            return new TableConstraint(constraintName, "INDEX", cols, null,
                    SourceSpan.between(startSpan, cur.previous().span()));
        }

        // CHECK (...) or dialect table constraint
        skipTrailingConstraintOptions();
        return new TableConstraint(constraintName, "CHECK", List.of(), null,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private List<String> parseConstraintColumnNames() {
        if (!cur.matchSymbol("(")) {
            return List.of();
        }
        List<String> cols = new ArrayList<>();
        if (!cur.checkSymbol(")")) {
            do {
                SqlToken tok = cur.expectIdentifier("constraint column");
                cols.add(tok.value());
                // MySQL key length col(64) or ASC/DESC
                if (cur.checkSymbol("(")) {
                    cur.skipParenthesizedGroup();
                }
                cur.matchAnyWord("ASC", "DESC");
            } while (cur.matchSymbol(","));
        }
        cur.expectSymbol(")");
        return cols;
    }

    private void skipTrailingConstraintOptions() {
        while (!cur.isAtEnd() && !cur.checkSymbol(",") && !cur.checkSymbol(")")
                && !cur.checkAnyWord("ADD", "DROP", "ALTER", "MODIFY", "RENAME")) {
            if (cur.checkSymbol("(")) {
                cur.skipParenthesizedGroup();
            } else {
                cur.advance();
            }
        }
    }

    public ColumnDef parseColumnDef(List<TableConstraint> tableConstraintsOut) {
        SqlToken colTok = cur.expectIdentifier("column");
        SourceSpan startSpan = colTok.span();

        String dataType = null;
        // Computed column without explicit datatype: T-SQL `total AS (price * qty) PERSISTED`
        if (!cur.checkWord("AS")
                && !cur.checkSymbol(",")
                && !cur.checkSymbol(")")
                && !cur.checkAnyWord("PRIMARY", "UNIQUE", "NOT", "NULL", "DEFAULT", "REFERENCES", "CONSTRAINT", "GENERATED")) {
            dataType = cur.parseDataType();
        }

        Boolean nullable = null;
        boolean primaryKey = false;
        boolean uniqueKey = false;
        boolean indexKey = false;
        boolean identity = false;
        boolean computed = false;
        boolean persisted = false;
        String computedDef = null;
        Expr computedExpr = null;
        String defaultValue = null;
        String defaultName = null;
        String comment = null;
        ForeignKeySpec inlineFk = null;
        String pendingConstraintName = null;

        while (!cur.isAtEnd() && !cur.checkSymbol(",") && !cur.checkSymbol(")")) {
            if (cur.matchWord("CONSTRAINT")) {
                if (cur.peek().isIdentifierLike()) {
                    pendingConstraintName = cur.advance().value();
                }
            } else if (cur.matchWords("NOT", "NULL")) {
                nullable = Boolean.FALSE;
                pendingConstraintName = null;
            } else if (cur.matchWord("NULL")) {
                nullable = Boolean.TRUE;
                pendingConstraintName = null;
            } else if (cur.matchWords("PRIMARY", "KEY")) {
                primaryKey = true;
                cur.matchAnyWord("ASC", "DESC", "AUTOINCREMENT", "CLUSTERED", "NONCLUSTERED");
                pendingConstraintName = null;
            } else if (cur.matchWord("UNIQUE")) {
                cur.matchWord("KEY");
                uniqueKey = true;
                pendingConstraintName = null;
            } else if (cur.matchAnyWord("AUTO_INCREMENT", "AUTOINCREMENT", "ROWGUIDCOL")) {
                identity = true;
            } else if (cur.matchWord("IDENTITY")) {
                identity = true;
                if (cur.checkSymbol("(")) {
                    cur.skipParenthesizedGroup();
                }
            } else if (cur.matchWord("GENERATED")) {
                if (cur.matchAnyWord("ALWAYS", "BY")) {
                    cur.matchWord("DEFAULT");
                    if (cur.matchWords("ON", "NULL")) {
                        // Oracle GENERATED BY DEFAULT ON NULL AS IDENTITY
                    }
                }
                if (cur.matchWords("AS", "IDENTITY")) {
                    identity = true;
                    if (cur.checkSymbol("(")) {
                        cur.skipParenthesizedGroup();
                    }
                } else if (cur.matchWord("AS") && cur.checkSymbol("(")) {
                    computed = true;
                    cur.expectSymbol("(");
                    computedExpr = exprParser.parseExpression();
                    computedDef = cur.extractText(computedExpr.span(), computedExpr.span());
                    cur.expectSymbol(")");
                    if (cur.matchAnyWord("STORED", "PERSISTED")) {
                        persisted = true;
                    } else {
                        cur.matchWord("VIRTUAL");
                    }
                }
            } else if (cur.matchWord("AS") && cur.checkSymbol("(")) {
                computed = true;
                cur.expectSymbol("(");
                computedExpr = exprParser.parseExpression();
                computedDef = cur.extractText(computedExpr.span(), computedExpr.span());
                cur.expectSymbol(")");
                if (cur.matchAnyWord("STORED", "PERSISTED")) {
                    persisted = true;
                } else {
                    cur.matchWord("VIRTUAL");
                }
            } else if (cur.matchWord("PERSISTED") || cur.matchWord("STORED")) {
                persisted = true;
            } else if (cur.matchWord("DEFAULT")) {
                defaultName = pendingConstraintName;
                pendingConstraintName = null;
                Expr defExpr = exprParser.parseExpression();
                defaultValue = cur.extractText(defExpr.span(), defExpr.span());
            } else if (cur.matchWord("COMMENT")) {
                if (cur.peek().type() == TokenType.STRING_LITERAL) {
                    comment = cur.advance().value();
                }
            } else if (cur.matchWord("REFERENCES")) {
                QualifiedName refTbl = cur.parseQualifiedName();
                List<String> refCols = cur.checkSymbol("(") ? parseConstraintColumnNames() : List.of();
                inlineFk = new ForeignKeySpec(
                        pendingConstraintName,
                        List.of(colTok.value()),
                        refTbl,
                        refCols,
                        SourceSpan.between(startSpan, cur.previous().span()));
                if (tableConstraintsOut != null) {
                    tableConstraintsOut.add(new TableConstraint(
                            pendingConstraintName, "FOREIGN_KEY", List.of(colTok.value()), inlineFk, inlineFk.span()));
                }
                pendingConstraintName = null;
            } else if (cur.checkSymbol("(")) {
                cur.skipParenthesizedGroup();
            } else {
                cur.advance();
            }
        }

        if (dataType != null && (dataType.equalsIgnoreCase("SERIAL") || dataType.equalsIgnoreCase("BIGSERIAL")
                || dataType.equalsIgnoreCase("SMALLSERIAL"))) {
            identity = true;
        }

        return new ColumnDef(
                colTok.value(),
                colTok.quoted(),
                dataType,
                nullable,
                primaryKey,
                uniqueKey,
                indexKey,
                identity,
                computed,
                persisted,
                computedDef,
                computedExpr,
                defaultValue,
                defaultName,
                comment,
                inlineFk,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private CreateViewStmt parseCreateViewBody(SourceSpan startSpan, boolean orReplace, boolean materialized) {
        skipIfNotExists();
        QualifiedName viewName = cur.parseQualifiedName();

        List<String> colNames = new ArrayList<>();
        List<SourceSpan> colSpans = new ArrayList<>();
        if (cur.checkSymbol("(") && !cur.peek(1).isAnyWord("SELECT", "WITH")) {
            cur.expectSymbol("(");
            if (!cur.checkSymbol(")")) {
                do {
                    SqlToken cTok = cur.expectIdentifier("view column");
                    colNames.add(cTok.value());
                    colSpans.add(cTok.span());
                    // Snowflake view columns can carry COMMENT / MASKING POLICY
                    while (!cur.isAtEnd() && !cur.checkSymbol(",") && !cur.checkSymbol(")")) {
                        if (cur.checkSymbol("(")) {
                            cur.skipParenthesizedGroup();
                        } else {
                            cur.advance();
                        }
                    }
                } while (cur.matchSymbol(","));
            }
            cur.expectSymbol(")");
        }

        String comment = null;
        // Skip view header clauses before AS (WITH SCHEMABINDING, COMMENT = '...', BUILD IMMEDIATE, REFRESH FAST, etc.)
        while (!cur.isAtEnd() && !cur.checkWord("AS")) {
            if (cur.matchWord("COMMENT")) {
                cur.matchSymbol("=");
                if (cur.peek().type() == TokenType.STRING_LITERAL) {
                    comment = cur.advance().value();
                }
            } else if (cur.checkSymbol("(")) {
                cur.skipParenthesizedGroup();
            } else {
                cur.advance();
            }
        }
        cur.expectWord("AS");
        QueryExpr query = queryParser.parseQuery();

        // Skip trailing WITH [CASCADED|LOCAL] CHECK OPTION / WITH NO SCHEMA BINDING
        while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            cur.advance();
        }
        return new CreateViewStmt(
                orReplace,
                materialized,
                viewName,
                colNames,
                colSpans,
                query,
                comment,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    public AlterTableStmt parseAlterTableStmt() {
        SourceSpan startSpan = cur.expectWord("ALTER").span();
        if (cur.matchWord("SESSION")) {
            // Oracle / Snowflake ALTER SESSION SET CURRENT_SCHEMA = hr
            if (cur.matchWord("SET")) {
                if (cur.matchAnyWord("CURRENT_SCHEMA", "SCHEMA")) {
                    cur.matchSymbol("=");
                    QualifiedName schema = cur.parseQualifiedName();
                    return null; // Handled by parseAlterDispatch()
                }
            }
            skipRemainingTokens();
            return new AlterTableStmt(null, List.of(), SourceSpan.between(startSpan, cur.previous().span()));
        }

        cur.matchAnyWord("TABLE", "VIEW", "MATERIALIZED");
        cur.matchWord("VIEW");
        if (cur.matchWords("IF", "EXISTS")) {
            // consumed
        }
        if (cur.matchWord("ONLY")) {
            // PostgreSQL ALTER TABLE ONLY
        }

        QualifiedName tableName = cur.parseQualifiedName();
        List<AlterAction> actions = new ArrayList<>();

        while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            SourceSpan actStart = cur.peek().span();
            if (cur.matchWords("SWAP", "WITH")) {
                QualifiedName other = cur.parseQualifiedName();
                actions.add(new AlterAction("SWAP_WITH", null, null, null, null, other,
                        SourceSpan.between(actStart, other.span())));
            } else if (cur.matchWords("RENAME", "COLUMN")) {
                String oldCol = cur.expectIdentifier("old column").value();
                cur.expectWord("TO");
                String newCol = cur.expectIdentifier("new column").value();
                actions.add(new AlterAction("RENAME_COLUMN", null, null, oldCol, newCol, null,
                        SourceSpan.between(actStart, cur.previous().span())));
            } else if (cur.matchWord("RENAME")) {
                cur.matchWord("TO");
                cur.matchWord("AS");
                QualifiedName newName = cur.parseQualifiedName();
                actions.add(new AlterAction("RENAME_TABLE", null, null, null, null, newName,
                        SourceSpan.between(actStart, newName.span())));
            } else if (cur.matchWords("EXCHANGE", "PARTITION")) {
                if (cur.checkSymbol("(")) {
                    cur.skipParenthesizedGroup();
                } else if (!cur.checkWord("WITH")) {
                    cur.advance();
                }
                while (!cur.isAtEnd() && !cur.checkWord("TABLE") && !cur.checkSymbol(";")) {
                    cur.advance();
                }
                if (cur.matchWord("TABLE")) {
                    QualifiedName other = cur.parseQualifiedName();
                    actions.add(new AlterAction("EXCHANGE_PARTITION", null, null, null, null, other,
                            SourceSpan.between(actStart, other.span())));
                }
            } else if (cur.matchWords("SWITCH", "PARTITION") || cur.matchWord("SWITCH")) {
                while (!cur.isAtEnd() && !cur.checkWord("TO") && !cur.checkSymbol(";")) {
                    cur.advance();
                }
                if (cur.matchWord("TO")) {
                    QualifiedName other = cur.parseQualifiedName();
                    actions.add(new AlterAction("SWITCH_PARTITION", null, null, null, null, other,
                            SourceSpan.between(actStart, other.span())));
                }
            } else if (cur.matchWord("ADD")) {
                if (isTableLevelConstraintStart()) {
                    TableConstraint tc = parseTableConstraint();
                    actions.add(new AlterAction("ADD_CONSTRAINT", tc, null, null, null, null,
                            SourceSpan.between(actStart, cur.previous().span())));
                } else {
                    cur.matchWord("COLUMN");
                    skipIfNotExists();
                    if (cur.matchSymbol("(")) {
                        while (!cur.isAtEnd() && !cur.checkSymbol(")")) {
                            ColumnDef col = parseColumnDef(null);
                            actions.add(new AlterAction("ADD_COLUMN", null, col, null, null, null, col.span()));
                            if (!cur.matchSymbol(",")) {
                                break;
                            }
                        }
                        cur.matchSymbol(")");
                    } else if (cur.peek().isIdentifierLike()) {
                        ColumnDef col = parseColumnDef(null);
                        actions.add(new AlterAction("ADD_COLUMN", null, col, null, null, null, col.span()));
                    }
                }
            } else if (cur.matchWord("DROP")) {
                if (cur.matchAnyWord("CONSTRAINT", "PRIMARY", "FOREIGN", "INDEX", "KEY")) {
                    while (!cur.isAtEnd() && !cur.checkSymbol(",") && !cur.checkSymbol(";")) {
                        cur.advance();
                    }
                } else {
                    cur.matchWord("COLUMN");
                    if (cur.matchWords("IF", "EXISTS")) {
                        // consumed
                    }
                    if (cur.peek().isIdentifierLike()) {
                        String dropped = cur.advance().value();
                        actions.add(new AlterAction("DROP_COLUMN", null, null, dropped, null, null,
                                SourceSpan.between(actStart, cur.previous().span())));
                    }
                }
            } else {
                cur.advance();
            }
            cur.matchSymbol(",");
        }

        return new AlterTableStmt(tableName, actions, SourceSpan.between(startSpan, cur.previous().span()));
    }

    public SqlStmt parseAlterDispatch() {
        if (cur.peek(1).isWord("SESSION")) {
            SourceSpan start = cur.expectWord("ALTER").span();
            cur.expectWord("SESSION");
            if (cur.matchWord("SET") && cur.matchAnyWord("CURRENT_SCHEMA", "SCHEMA")) {
                cur.matchSymbol("=");
                QualifiedName target = readIdentOrStringAsQualifiedName();
                return new UseContextStmt("SCHEMA", target, SourceSpan.between(start, target.span()));
            }
            skipRemainingTokens();
            return new SqlStmt.NoOpStmt("ALTER_SESSION", SourceSpan.between(start, cur.previous().span()));
        }
        if (cur.peek(1).isAnyWord("PROCEDURE", "PROC", "FUNCTION", "TRIGGER", "PACKAGE")) {
            SourceSpan start = cur.expectWord("ALTER").span();
            if (cur.matchWord("PACKAGE")) {
                return proceduralParser.parseCreatePackageBody(start);
            }
            String kind = cur.advance().upperValue();
            if ("PROC".equals(kind)) {
                kind = "PROCEDURE";
            }
            return proceduralParser.parseCreateRoutineBody(start, kind);
        }
        if (cur.peek(1).isWord("VIEW") || (cur.peek(1).isWord("MATERIALIZED") && cur.peek(2).isWord("VIEW"))) {
            // Check if ALTER VIEW v AS SELECT ...
            int saved = cur.position();
            SourceSpan start = cur.expectWord("ALTER").span();
            boolean mat = cur.matchWord("MATERIALIZED");
            cur.expectWord("VIEW");
            QualifiedName vName = cur.parseQualifiedName();
            if (cur.checkWord("AS")) {
                cur.expectWord("AS");
                QueryExpr q = queryParser.parseQuery();
                return new CreateViewStmt(true, mat, vName, List.of(), List.of(), q, null,
                        SourceSpan.between(start, cur.previous().span()));
            }
            cur.seek(saved);
        }
        return parseAlterTableStmt();
    }

    public DropStmt parseDropStmt() {
        SourceSpan start = cur.expectWord("DROP").span();
        cur.matchAnyWord("TEMPORARY", "TEMP", "MATERIALIZED", "PUBLIC", "EXTERNAL");
        String kind = cur.isAtEnd() ? "TABLE" : cur.advance().upperValue();
        if (cur.matchWords("IF", "EXISTS")) {
            // consumed
        }
        List<QualifiedName> names = new ArrayList<>();
        if (cur.peek().isIdentifierLike()) {
            do {
                names.add(cur.parseQualifiedName());
            } while (cur.matchSymbol(","));
        }
        skipRemainingTokens();
        return new DropStmt(kind, names, SourceSpan.between(start, cur.previous().span()));
    }

    public TruncateStmt parseTruncateStmt() {
        SourceSpan start = cur.expectWord("TRUNCATE").span();
        cur.matchWord("TABLE");
        if (cur.matchWords("IF", "EXISTS")) {
            // consumed
        }
        cur.matchWord("ONLY");
        QualifiedName tbl = cur.parseQualifiedName();
        skipRemainingTokens();
        return new TruncateStmt(tbl, SourceSpan.between(start, cur.previous().span()));
    }

    public AlterTableStmt parseStandaloneRenameStmt() {
        // MySQL / Oracle: RENAME [TABLE] old_tbl TO new_tbl
        SourceSpan start = cur.expectWord("RENAME").span();
        cur.matchWord("TABLE");
        QualifiedName oldName = cur.parseQualifiedName();
        cur.expectWord("TO");
        QualifiedName newName = cur.parseQualifiedName();
        AlterAction action = new AlterAction("RENAME_TABLE", null, null, null, null, newName,
                SourceSpan.between(start, newName.span()));
        skipRemainingTokens();
        return new AlterTableStmt(oldName, List.of(action), SourceSpan.between(start, cur.previous().span()));
    }

    private CreateSynonymStmt parseCreateSynonymBody(SourceSpan startSpan, boolean orReplace, boolean isPublic) {
        skipIfNotExists();
        QualifiedName synName = cur.parseQualifiedName();
        cur.expectWord("FOR");
        QualifiedName target = cur.parseQualifiedName();
        skipRemainingTokens();
        return new CreateSynonymStmt(orReplace, isPublic, synName, target,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private CreateSequenceStmt parseCreateSequenceBody(SourceSpan startSpan) {
        skipIfNotExists();
        QualifiedName seqName = cur.parseQualifiedName();
        String startWith = null;
        String incrementBy = null;
        String minVal = null;
        String maxVal = null;
        Boolean cycle = null;
        Boolean order = null;
        String cache = null;

        while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            if (cur.matchWords("START", "WITH") || cur.matchWord("START")) {
                cur.matchSymbol("=");
                if (!cur.isAtEnd()) {
                    startWith = cur.advance().rawText();
                }
            } else if (cur.matchWords("INCREMENT", "BY") || cur.matchWord("INCREMENT")) {
                cur.matchSymbol("=");
                if (!cur.isAtEnd()) {
                    incrementBy = cur.advance().rawText();
                }
            } else if (cur.matchWord("MINVALUE")) {
                if (!cur.isAtEnd()) {
                    minVal = cur.advance().rawText();
                }
            } else if (cur.matchWord("MAXVALUE")) {
                if (!cur.isAtEnd()) {
                    maxVal = cur.advance().rawText();
                }
            } else if (cur.matchWord("CYCLE")) {
                cycle = Boolean.TRUE;
            } else if (cur.matchWords("NO", "CYCLE") || cur.matchWord("NOCYCLE")) {
                cycle = Boolean.FALSE;
            } else if (cur.matchWord("ORDER")) {
                order = Boolean.TRUE;
            } else if (cur.matchWord("NOORDER")) {
                order = Boolean.FALSE;
            } else if (cur.matchWord("CACHE")) {
                if (!cur.isAtEnd()) {
                    cache = cur.advance().rawText();
                }
            } else {
                cur.advance();
            }
        }
        return new CreateSequenceStmt(seqName, startWith, incrementBy, minVal, maxVal, cycle, order, cache,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private CreateStageOrStreamStmt parseCreateStageBody(SourceSpan startSpan) {
        skipIfNotExists();
        QualifiedName name = cur.parseQualifiedName();
        String url = null;
        String format = null;
        while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            if (cur.matchWord("URL")) {
                cur.matchSymbol("=");
                if (cur.peek().type() == TokenType.STRING_LITERAL) {
                    url = cur.advance().value();
                }
            } else if (cur.matchWord("FILE_FORMAT")) {
                cur.matchSymbol("=");
                if (cur.checkSymbol("(")) {
                    cur.expectSymbol("(");
                    while (!cur.isAtEnd() && !cur.checkSymbol(")")) {
                        if (cur.matchWord("TYPE")) {
                            cur.matchSymbol("=");
                            format = cur.advance().value();
                        } else {
                            cur.advance();
                        }
                    }
                    cur.matchSymbol(")");
                }
            } else {
                cur.advance();
            }
        }
        return new CreateStageOrStreamStmt("STAGE", name, url, format, null,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private CreateStageOrStreamStmt parseCreateStreamBody(SourceSpan startSpan) {
        skipIfNotExists();
        QualifiedName name = cur.parseQualifiedName();
        QualifiedName srcTable = null;
        if (cur.matchWord("ON")) {
            cur.matchAnyWord("TABLE", "VIEW", "EXTERNAL", "STAGE");
            cur.matchWord("TABLE");
            srcTable = cur.parseQualifiedName();
        }
        skipRemainingTokens();
        return new CreateStageOrStreamStmt("STREAM", name, null, null, srcTable,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private CreateStageOrStreamStmt parseCreateDataSourceBody(SourceSpan startSpan) {
        QualifiedName name = cur.parseQualifiedName();
        String location = null;
        while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            if (cur.matchWord("LOCATION")) {
                cur.matchSymbol("=");
                if (cur.peek().type() == TokenType.STRING_LITERAL) {
                    location = cur.advance().value();
                }
            } else {
                cur.advance();
            }
        }
        return new CreateStageOrStreamStmt("DATASOURCE", name, location, null, null,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private CreateDatabaseOrSchemaStmt parseCreateDatabaseOrSchemaBody(SourceSpan startSpan, String kind) {
        skipIfNotExists();
        QualifiedName name = cur.parseQualifiedName();
        QualifiedName cloneSrc = null;
        while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            if (cur.matchWord("CLONE")) {
                cloneSrc = cur.parseQualifiedName();
            } else {
                cur.advance();
            }
        }
        return new CreateDatabaseOrSchemaStmt(kind, name, cloneSrc,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    public UseContextStmt parseUseStmt() {
        SourceSpan start = cur.expectWord("USE").span();
        String kind = "DATABASE";
        if (cur.matchWord("SCHEMA")) {
            kind = "SCHEMA";
        } else if (cur.matchAnyWord("DATABASE", "CATALOG")) {
            kind = "DATABASE";
        } else if (cur.matchAnyWord("WAREHOUSE", "ROLE")) {
            skipRemainingTokens();
            return new UseContextStmt("ROLE", QualifiedName.single("", "", false, false, start), start);
        }
        QualifiedName target = cur.parseQualifiedName();
        skipRemainingTokens();
        return new UseContextStmt(kind, target, SourceSpan.between(start, target.span()));
    }

    public CommentOnStmt parseCommentOnStmt() {
        SourceSpan start = cur.expectWord("COMMENT").span();
        cur.expectWord("ON");
        String kind = "TABLE";
        if (cur.matchAnyWord("TABLE", "VIEW", "COLUMN", "MATERIALIZED")) {
            kind = cur.previous().upperValue();
            if ("MATERIALIZED".equals(kind) && cur.matchWord("VIEW")) {
                kind = "VIEW";
            }
        }
        QualifiedName target = cur.parseQualifiedName();
        String comment = "";
        if (cur.matchWord("IS") && cur.peek().type() == TokenType.STRING_LITERAL) {
            comment = cur.advance().value();
        }
        skipRemainingTokens();
        return new CommentOnStmt(kind, target, comment, SourceSpan.between(start, cur.previous().span()));
    }

    private QualifiedName readIdentOrStringAsQualifiedName() {
        if (cur.peek().type() == TokenType.STRING_LITERAL) {
            SqlToken s = cur.advance();
            return QualifiedName.single(s.rawText(), s.value(), false, false, s.span());
        }
        return cur.parseQualifiedName();
    }

    private void skipIfNotExists() {
        if (cur.matchWords("IF", "NOT")) {
            cur.matchWord("EXISTS");
        }
    }

    private void skipRemainingTokens() {
        while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            cur.advance();
        }
    }
}
