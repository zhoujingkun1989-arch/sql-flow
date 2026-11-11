package io.sqlflow.lineage.parser;

import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.ast.Expr;
import io.sqlflow.lineage.ast.QualifiedName;
import io.sqlflow.lineage.ast.QueryAst.QueryExpr;
import io.sqlflow.lineage.ast.SqlStmt;
import io.sqlflow.lineage.ast.SqlStmt.AnonymousBlockStmt;
import io.sqlflow.lineage.ast.SqlStmt.AssignStmt;
import io.sqlflow.lineage.ast.SqlStmt.CallStmt;
import io.sqlflow.lineage.ast.SqlStmt.CloseCursorStmt;
import io.sqlflow.lineage.ast.SqlStmt.ColumnDef;
import io.sqlflow.lineage.ast.SqlStmt.ConditionalBranch;
import io.sqlflow.lineage.ast.SqlStmt.CreatePackageStmt;
import io.sqlflow.lineage.ast.SqlStmt.CreateRoutineStmt;
import io.sqlflow.lineage.ast.SqlStmt.DeclareVarStmt;
import io.sqlflow.lineage.ast.SqlStmt.DynamicSqlStmt;
import io.sqlflow.lineage.ast.SqlStmt.FetchCursorStmt;
import io.sqlflow.lineage.ast.SqlStmt.ForLoopStmt;
import io.sqlflow.lineage.ast.SqlStmt.IfStmt;
import io.sqlflow.lineage.ast.SqlStmt.MdxCalculatedMember;
import io.sqlflow.lineage.ast.SqlStmt.MdxSelectStmt;
import io.sqlflow.lineage.ast.SqlStmt.OpenCursorStmt;
import io.sqlflow.lineage.ast.SqlStmt.PowerQueryLetStmt;
import io.sqlflow.lineage.ast.SqlStmt.PowerQueryStep;
import io.sqlflow.lineage.ast.SqlStmt.ReturnStmt;
import io.sqlflow.lineage.ast.SqlStmt.RoutineArg;
import io.sqlflow.lineage.ast.SqlStmt.WhileLoopStmt;
import io.sqlflow.lineage.lexer.SourceSpan;
import io.sqlflow.lineage.lexer.SqlStatementSplitter;
import io.sqlflow.lineage.lexer.SqlStatementSplitter.StatementSlice;
import io.sqlflow.lineage.lexer.SqlToken;
import io.sqlflow.lineage.lexer.TokenType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses stored procedures, functions, packages, triggers, macros, cursors, variables, control-flow,
 * dynamic SQL ({@code EXECUTE IMMEDIATE}, {@code sp_executesql}, {@code sp_execute_external_script}),
 * Power Query M ({@code let ... in ...}), and MDX ({@code SELECT ... ON COLUMNS/ROWS FROM [Cube]}).
 */
public final class ProceduralAndSpecialParser {

    private final ParserCursor cur;
    private final ExpressionParser exprParser;
    private final QueryParser queryParser;
    private StatementParser stmtParser;
    private DdlParser ddlParser;

    public ProceduralAndSpecialParser(ParserCursor cur, ExpressionParser exprParser, QueryParser queryParser) {
        this.cur = cur;
        this.exprParser = exprParser;
        this.queryParser = queryParser;
    }

    public void wire(StatementParser stmtParser, DdlParser ddlParser) {
        this.stmtParser = stmtParser;
        this.ddlParser = ddlParser;
    }

    // -------------------------------------------------------------------------
    // 1. Packages & Routines (PL/SQL, T-SQL, PL/pgSQL, Snowflake, DB2, MySQL, Teradata)
    // -------------------------------------------------------------------------

    public CreatePackageStmt parseCreatePackageBody(SourceSpan startSpan) {
        boolean isBody = cur.matchWord("BODY");
        if (cur.matchWords("IF", "NOT")) {
            cur.matchWord("EXISTS");
        }
        QualifiedName pkgName = cur.parseQualifiedName();

        // Skip AUTHID CURRENT_USER / DEFINER
        while (!cur.isAtEnd() && !cur.checkAnyWord("IS", "AS")) {
            cur.advance();
        }
        cur.matchAnyWord("IS", "AS");

        List<DeclareVarStmt> pkgVars = new ArrayList<>();
        List<CreateRoutineStmt> routines = new ArrayList<>();
        List<SqlStmt> initStmts = new ArrayList<>();

        while (!cur.isAtEnd() && !cur.checkWord("END") && !cur.checkWord("BEGIN")) {
            if (cur.checkAnyWord("PROCEDURE", "FUNCTION")) {
                SourceSpan rStart = cur.advance().span();
                String rKind = cur.previous().upperValue();
                routines.add(parsePackageRoutineMember(rStart, rKind, isBody));
            } else if (cur.checkWord("CURSOR")) {
                pkgVars.add(parseOracleCursorDecl());
                cur.matchSymbol(";");
            } else if (cur.checkAnyWord("TYPE", "SUBTYPE", "PRAGMA", "EXCEPTION")) {
                skipToSemicolon();
            } else if (cur.peek().isIdentifierLike()) {
                DeclareVarStmt decl = parsePlsqlVariableDecl();
                if (decl != null) {
                    pkgVars.add(decl);
                }
                cur.matchSymbol(";");
            } else {
                cur.advance();
            }
        }

        if (cur.matchWord("BEGIN")) {
            initStmts.addAll(parseStatementListUntilEnd());
        }
        if (cur.matchWord("END")) {
            if (cur.peek().isIdentifierLike()) {
                cur.advance(); // optional trailing package name
            }
        }
        cur.matchSymbol(";");
        return new CreatePackageStmt(isBody, pkgName, pkgVars, routines, initStmts,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private CreateRoutineStmt parsePackageRoutineMember(SourceSpan startSpan, String kind, boolean inPackageBody) {
        QualifiedName name = cur.parseQualifiedName();
        List<RoutineArg> args = cur.checkSymbol("(") ? parseRoutineArgList() : List.of();
        String returnType = null;
        boolean pipelined = false;
        List<ColumnDef> returnTableCols = List.of();

        while (!cur.isAtEnd() && !cur.checkAnyWord("IS", "AS") && !cur.checkSymbol(";")) {
            if (cur.matchAnyWord("RETURN", "RETURNS")) {
                if (cur.matchWord("TABLE") && cur.checkSymbol("(")) {
                    returnTableCols = parseSimpleReturnTableColumns();
                } else {
                    returnType = cur.parseDataType();
                }
            } else if (cur.matchWord("PIPELINED")) {
                pipelined = true;
            } else {
                cur.advance();
            }
        }

        if (cur.matchSymbol(";") || !inPackageBody) {
            if (!cur.previous().isSymbol(";")) {
                skipToSemicolon();
            }
            return new CreateRoutineStmt(
                    kind, name, args, returnType, returnTableCols, !returnTableCols.isEmpty(), pipelined,
                    null, List.of(), List.of(), List.of(),
                    SourceSpan.between(startSpan, cur.previous().span()));
        }

        cur.matchAnyWord("IS", "AS");
        List<DeclareVarStmt> decls = new ArrayList<>();
        parsePlsqlDeclareSection(decls);

        List<SqlStmt> bodyStmts = new ArrayList<>();
        if (cur.matchWord("BEGIN")) {
            bodyStmts.addAll(parseStatementListUntilEnd());
            if (cur.matchWord("END")) {
                if (cur.peek().isIdentifierLike() && !cur.checkAnyWord("PROCEDURE", "FUNCTION", "BEGIN", "END")) {
                    cur.advance();
                }
            }
        }
        cur.matchSymbol(";");
        return new CreateRoutineStmt(
                kind, name, args, returnType, returnTableCols, !returnTableCols.isEmpty(), pipelined,
                null, List.of(), decls, bodyStmts,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    public CreateRoutineStmt parseCreateRoutineBody(SourceSpan startSpan, String kind) {
        if (cur.matchWords("IF", "NOT")) {
            cur.matchWord("EXISTS");
        }
        QualifiedName routineName = cur.parseQualifiedName();

        if ("TRIGGER".equals(kind)) {
            return parseCreateTriggerAfterName(startSpan, routineName);
        }

        // Arguments: either parenthesized (p1 INT, p2 OUT VARCHAR) or T-SQL unparenthesized @p1 INT, @p2 INT OUTPUT
        List<RoutineArg> args = new ArrayList<>();
        if (cur.checkSymbol("(")) {
            args = parseRoutineArgList();
        } else if (cur.peek().type() == TokenType.BIND_VARIABLE && cur.peek().rawText().startsWith("@")) {
            args = parseTsqlUnparenthesizedArgList();
        }

        String returnType = null;
        boolean returnsTable = false;
        boolean pipelined = false;
        List<ColumnDef> returnTableCols = new ArrayList<>();
        SqlToken embeddedBodyString = null;

        while (!cur.isAtEnd()) {
            if (cur.matchAnyWord("RETURNS", "RETURN")) {
                if (cur.matchWord("TABLE")) {
                    returnsTable = true;
                    if (cur.checkSymbol("(")) {
                        returnTableCols = parseSimpleReturnTableColumns();
                    }
                } else if (cur.peek().type() == TokenType.BIND_VARIABLE && cur.peek(1).isWord("TABLE")) {
                    // T-SQL multi-statement TVF: RETURNS @ret TABLE (id INT, name VARCHAR(50))
                    SqlToken retVar = cur.advance();
                    cur.expectWord("TABLE");
                    returnsTable = true;
                    returnType = retVar.rawText();
                    if (cur.checkSymbol("(")) {
                        returnTableCols = parseSimpleReturnTableColumns();
                    }
                } else if (cur.checkAnyWord("SETOF")) {
                    cur.advance();
                    returnType = cur.parseDataType();
                } else if (!cur.checkAnyWord("SELECT", "WITH", "NULL") && !cur.checkSymbol("(")) {
                    returnType = cur.parseDataType();
                } else {
                    // Inline RETURN <expr> or RETURN (SELECT ...) in MySQL / T-SQL / Snowflake / DuckDB!
                    List<SqlStmt> inlineBody = new ArrayList<>();
                    if (cur.matchSymbol("(")) {
                        if (cur.checkAnyWord("SELECT", "WITH", "VALUES")) {
                            QueryExpr q = queryParser.parseQuery();
                            cur.expectSymbol(")");
                            inlineBody.add(new ReturnStmt(null, q, false, q.span()));
                        } else {
                            Expr e = exprParser.parseExpression();
                            cur.expectSymbol(")");
                            inlineBody.add(new ReturnStmt(e, null, false, e.span()));
                        }
                    } else if (cur.checkAnyWord("SELECT", "WITH")) {
                        QueryExpr q = queryParser.parseQuery();
                        inlineBody.add(new ReturnStmt(null, q, false, q.span()));
                    } else {
                        Expr e = exprParser.parseExpression();
                        inlineBody.add(new ReturnStmt(e, null, false, e.span()));
                    }
                    return new CreateRoutineStmt(
                            kind, routineName, args, returnType, returnTableCols, returnsTable, pipelined,
                            null, List.of(), List.of(), inlineBody,
                            SourceSpan.between(startSpan, cur.previous().span()));
                }
            } else if (cur.matchWord("PIPELINED")) {
                pipelined = true;
            } else if (cur.matchAnyWord("AS", "IS")) {
                if (cur.peek().type() == TokenType.STRING_LITERAL) {
                    embeddedBodyString = cur.advance();
                    // Continue scanning trailing LANGUAGE plpgsql / EXECUTE AS ...
                    continue;
                }
                break;
            } else if (cur.checkWord("BEGIN") || cur.checkWord("DECLARE")) {
                break;
            } else if (cur.peek().type() == TokenType.STRING_LITERAL && cur.peek().rawText().startsWith("$")) {
                embeddedBodyString = cur.advance();
            } else {
                cur.advance();
            }
        }

        // Case 1: PostgreSQL / Snowflake / BigQuery / DuckDB string or $$...$$ routine body
        if (embeddedBodyString != null) {
            List<SqlToken> shiftedInner = ParserCursor.relexEmbeddedBody(embeddedBodyString, cur.vendor());
            List<DeclareVarStmt> innerDecls = new ArrayList<>();
            List<SqlStmt> innerStmts = parseEmbeddedRoutineTokens(shiftedInner, innerDecls);
            return new CreateRoutineStmt(
                    kind, routineName, args, returnType, returnTableCols, returnsTable, pipelined,
                    null, List.of(), innerDecls, innerStmts,
                    SourceSpan.between(startSpan, cur.previous().span()));
        }

        // Case 2: Teradata Macro `CREATE MACRO m AS ( SELECT ...; INSERT ...; );` or Snowflake/BigQuery SQL UDF `AS (SELECT ...)`
        if ("MACRO".equals(kind) && cur.matchSymbol("(")) {
            List<SqlStmt> macroStmts = new ArrayList<>();
            while (!cur.isAtEnd() && !cur.checkSymbol(")")) {
                if (cur.matchSymbol(";")) {
                    continue;
                }
                macroStmts.add(stmtParser.parseSingleStatementFromCursor());
                cur.matchSymbol(";");
            }
            cur.matchSymbol(")");
            return new CreateRoutineStmt(
                    kind, routineName, args, returnType, returnTableCols, returnsTable, pipelined,
                    null, List.of(), List.of(), macroStmts,
                    SourceSpan.between(startSpan, cur.previous().span()));
        }

        if ("FUNCTION".equals(kind) && cur.checkSymbol("(")) {
            cur.expectSymbol("(");
            List<SqlStmt> fnStmts = new ArrayList<>();
            if (cur.checkAnyWord("SELECT", "WITH", "VALUES")) {
                QueryExpr q = queryParser.parseQuery();
                fnStmts.add(new ReturnStmt(null, q, false, q.span()));
            } else {
                Expr e = exprParser.parseExpression();
                fnStmts.add(new ReturnStmt(e, null, false, e.span()));
            }
            cur.matchSymbol(")");
            return new CreateRoutineStmt(
                    kind, routineName, args, returnType, returnTableCols, returnsTable, pipelined,
                    null, List.of(), List.of(), fnStmts,
                    SourceSpan.between(startSpan, cur.previous().span()));
        }

        // Case 3: PL/SQL / T-SQL / DB2 / MySQL / Snowflake Scripting native procedural body
        List<DeclareVarStmt> declarations = new ArrayList<>();
        List<SqlStmt> bodyStmts = new ArrayList<>();

        if (cur.matchWord("DECLARE") && !isTsqlInlineDeclare()) {
            parsePlsqlDeclareSection(declarations);
        } else if (isPlsqlImplicitDeclareStart()) {
            parsePlsqlDeclareSection(declarations);
        }

        if (cur.matchWord("BEGIN")) {
            // Snowflake Scripting allows BEGIN ... END; or T-SQL BEGIN ... END
            bodyStmts.addAll(parseStatementListUntilEnd());
            if (cur.matchWord("END")) {
                if (cur.peek().isIdentifierLike()) {
                    cur.advance();
                }
            }
        } else {
            // T-SQL braceless procedure body or single-statement SQL routine!
            while (!cur.isAtEnd()) {
                if (cur.matchSymbol(";")) {
                    continue;
                }
                SqlStmt s = parseProceduralOrSqlStatement();
                if (s instanceof DeclareVarStmt dv) {
                    declarations.add(dv);
                }
                bodyStmts.add(s);
                cur.matchSymbol(";");
            }
        }

        return new CreateRoutineStmt(
                kind, routineName, args, returnType, returnTableCols, returnsTable, pipelined,
                null, List.of(), declarations, bodyStmts,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private CreateRoutineStmt parseCreateTriggerAfterName(SourceSpan startSpan, QualifiedName triggerName) {
        List<String> events = new ArrayList<>();
        QualifiedName onTable = null;

        while (!cur.isAtEnd() && !cur.checkAnyWord("BEGIN", "DECLARE", "AS", "EXECUTE")) {
            if (cur.matchAnyWord("INSERT", "UPDATE", "DELETE")) {
                events.add(cur.previous().upperValue());
            } else if (cur.matchWord("ON")) {
                onTable = cur.parseQualifiedName();
            } else {
                cur.advance();
            }
        }

        List<DeclareVarStmt> decls = new ArrayList<>();
        List<SqlStmt> body = new ArrayList<>();

        if (cur.matchWords("EXECUTE", "FUNCTION") || cur.matchWords("EXECUTE", "PROCEDURE")) {
            // PostgreSQL trigger: CREATE TRIGGER trg AFTER INSERT ON tbl EXECUTE FUNCTION fn()
            QualifiedName callee = cur.parseQualifiedName();
            List<Expr> callArgs = new ArrayList<>();
            if (cur.matchSymbol("(")) {
                if (!cur.checkSymbol(")")) {
                    callArgs = exprParser.parseExpressionList();
                }
                cur.expectSymbol(")");
            }
            body.add(new CallStmt(callee, callArgs, null, callee.span()));
        } else {
            cur.matchWord("AS");
            if (cur.matchWord("DECLARE") && !isTsqlInlineDeclare()) {
                parsePlsqlDeclareSection(decls);
            }
            if (cur.matchWord("BEGIN")) {
                body.addAll(parseStatementListUntilEnd());
                if (cur.matchWord("END")) {
                    if (cur.peek().isIdentifierLike()) {
                        cur.advance();
                    }
                }
            } else {
                while (!cur.isAtEnd()) {
                    if (cur.matchSymbol(";")) {
                        continue;
                    }
                    body.add(parseProceduralOrSqlStatement());
                    cur.matchSymbol(";");
                }
            }
        }

        return new CreateRoutineStmt(
                "TRIGGER", triggerName, List.of(), null, List.of(), false, false,
                onTable, events, decls, body,
                SourceSpan.between(startSpan, cur.previous().span()));
    }

    private List<SqlStmt> parseEmbeddedRoutineTokens(List<SqlToken> shiftedTokens, List<DeclareVarStmt> outDecls) {
        ParserCursor subCur = new ParserCursor(cur.sourceSql(), shiftedTokens, cur.vendor());
        ExpressionParser subExpr = new ExpressionParser(subCur);
        QueryParser subQuery = new QueryParser(subCur, subExpr);
        subExpr.setQueryParser(subQuery);
        DdlParser subDdl = new DdlParser(subCur, subExpr, subQuery);
        ProceduralAndSpecialParser subProc = new ProceduralAndSpecialParser(subCur, subExpr, subQuery);
        StatementParser subStmt = new StatementParser(subCur, subExpr, subQuery, subDdl, subProc);
        subDdl.setProceduralParser(subProc);
        subProc.wire(subStmt, subDdl);

        List<SqlStmt> result = new ArrayList<>();
        if (subCur.matchWord("DECLARE")) {
            subProc.parsePlsqlDeclareSection(outDecls);
        }
        if (subCur.matchWord("BEGIN")) {
            result.addAll(subProc.parseStatementListUntilEnd());
            subCur.matchWord("END");
        } else {
            while (!subCur.isAtEnd()) {
                if (subCur.matchSymbol(";")) {
                    continue;
                }
                result.add(subProc.parseProceduralOrSqlStatement());
                subCur.matchSymbol(";");
            }
        }
        return result;
    }

    private List<RoutineArg> parseRoutineArgList() {
        cur.expectSymbol("(");
        List<RoutineArg> args = new ArrayList<>();
        if (!cur.checkSymbol(")")) {
            do {
                if (cur.checkSymbol(")")) {
                    break;
                }
                SourceSpan aStart = cur.peek().span();
                String inout = "IN";
                if (cur.matchWords("IN", "OUT") || cur.matchWord("INOUT")) {
                    inout = "INOUT";
                } else if (cur.matchWord("OUT") || cur.matchWord("OUTPUT")) {
                    inout = "OUT";
                } else if (cur.matchWord("IN")) {
                    inout = "IN";
                } else {
                    cur.matchWord("VARIADIC");
                }

                String name;
                if (cur.peek().isIdentifierLike()) {
                    SqlToken first = cur.advance();
                    // Check if mode comes AFTER parameter name (PL/SQL: p_id IN NUMBER, p_out OUT VARCHAR2)
                    if (cur.matchWords("IN", "OUT") || cur.matchWord("INOUT")) {
                        inout = "INOUT";
                    } else if (cur.matchWord("OUT") || cur.matchWord("OUTPUT")) {
                        inout = "OUT";
                    } else if (cur.matchWord("IN")) {
                        inout = "IN";
                    }
                    cur.matchWord("NOCOPY");
                    if (cur.checkSymbol(",") || cur.checkSymbol(")")) {
                        // Postgres anonymous typed arg: f(INT, TEXT)
                        args.add(new RoutineArg("$arg" + (args.size() + 1), first.rawText(), inout, null, first.span()));
                        continue;
                    }
                    name = first.value();
                } else {
                    name = "$arg" + (args.size() + 1);
                }

                String dtype = cur.parseDataType();
                if (cur.matchAnyWord("OUT", "OUTPUT")) {
                    inout = "OUT";
                }
                cur.matchWord("READONLY");

                Expr defExpr = null;
                if (cur.matchWord("DEFAULT") || cur.matchSymbol(":=") || cur.matchSymbol("=")) {
                    defExpr = exprParser.parseExpression();
                }
                args.add(new RoutineArg(name, dtype, inout, defExpr,
                        SourceSpan.between(aStart, cur.previous().span())));
            } while (cur.matchSymbol(","));
        }
        cur.expectSymbol(")");
        return args;
    }

    private List<RoutineArg> parseTsqlUnparenthesizedArgList() {
        List<RoutineArg> args = new ArrayList<>();
        do {
            SqlToken pTok = cur.advance();
            cur.matchWord("AS");
            String dtype = cur.parseDataType();
            String inout = "IN";
            Expr defExpr = null;
            if (cur.matchSymbol("=")) {
                defExpr = exprParser.parseExpression();
            }
            if (cur.matchAnyWord("OUT", "OUTPUT")) {
                inout = "OUT";
            }
            cur.matchWord("READONLY");
            args.add(new RoutineArg(pTok.value(), dtype, inout, defExpr,
                    SourceSpan.between(pTok.span(), cur.previous().span())));
        } while (cur.matchSymbol(","));
        return args;
    }

    private List<ColumnDef> parseSimpleReturnTableColumns() {
        cur.expectSymbol("(");
        List<ColumnDef> cols = new ArrayList<>();
        while (!cur.isAtEnd() && !cur.checkSymbol(")")) {
            cols.add(ddlParser.parseColumnDef(null));
            if (!cur.matchSymbol(",")) {
                break;
            }
        }
        cur.expectSymbol(")");
        return cols;
    }

    // -------------------------------------------------------------------------
    // 2. Anonymous Blocks & Procedural Statements
    // -------------------------------------------------------------------------

    public SqlStmt parseAnonymousBlockOrDeclareStmt() {
        SourceSpan startSpan = cur.peek().span();
        if (cur.checkWord("DECLARE")) {
            if (isTsqlInlineDeclare()) {
                return parseTsqlOrMysqlStandaloneDeclare();
            }
            cur.expectWord("DECLARE");
            List<DeclareVarStmt> decls = new ArrayList<>();
            parsePlsqlDeclareSection(decls);
            List<SqlStmt> body = new ArrayList<>();
            if (cur.matchWord("BEGIN")) {
                body.addAll(parseStatementListUntilEnd());
                if (cur.matchWord("END")) {
                    if (cur.peek().isIdentifierLike()) {
                        cur.advance();
                    }
                }
            }
            return new AnonymousBlockStmt(decls, body, SourceSpan.between(startSpan, cur.previous().span()));
        }

        // BEGIN ... END block
        cur.expectWord("BEGIN");
        if (cur.isAtEnd() || cur.checkSymbol(";")
                || cur.checkAnyWord("TRANSACTION", "TRAN", "WORK", "DEFERRED", "IMMEDIATE", "EXCLUSIVE")) {
            skipToSemicolon();
            return new SqlStmt.NoOpStmt("BEGIN_TRANSACTION", SourceSpan.between(startSpan, cur.previous().span()));
        }
        List<DeclareVarStmt> decls = new ArrayList<>();
        if (cur.matchWord("DECLARE") && !isTsqlInlineDeclare()) {
            parsePlsqlDeclareSection(decls);
        }
        List<SqlStmt> body = parseStatementListUntilEnd();
        if (cur.matchWord("END")) {
            if (cur.peek().isIdentifierLike()) {
                cur.advance();
            }
        }
        return new AnonymousBlockStmt(decls, body, SourceSpan.between(startSpan, cur.previous().span()));
    }

    private boolean isTsqlInlineDeclare() {
        // T-SQL: DECLARE @var INT = 1 OR DECLARE cur CURSOR FOR SELECT ...
        // MySQL/DB2: DECLARE v1 INT DEFAULT 0; (inside BEGIN ... END without a following BEGIN block)
        if (cur.peek(1).type() == TokenType.BIND_VARIABLE && cur.peek(1).rawText().startsWith("@")) {
            return true;
        }
        if (cur.peek(1).isIdentifierLike() && cur.peek(2).isWord("CURSOR")) {
            return true;
        }
        if (cur.vendor() == EDbVendor.dbvmssql || cur.vendor() == EDbVendor.dbvazuresql
                || cur.vendor() == EDbVendor.dbvsybase || cur.vendor() == EDbVendor.dbvmysql) {
            return true;
        }
        return false;
    }

    private boolean isPlsqlImplicitDeclareStart() {
        if (cur.checkAnyWord("BEGIN", "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE",
                "IF", "FOR", "WHILE", "LOOP", "OPEN", "FETCH", "CLOSE", "RETURN", "EXEC", "EXECUTE", "CALL", "SET", "WITH")) {
            return false;
        }
        if (cur.checkWord("CURSOR")) {
            return true;
        }
        return cur.peek().isIdentifierLike()
                && !cur.peek(1).isSymbol(":=")
                && !cur.peek(1).isSymbol("=")
                && !cur.peek(1).isSymbol("(")
                && !cur.peek(1).isSymbol(".");
    }

    public void parsePlsqlDeclareSection(List<DeclareVarStmt> out) {
        while (!cur.isAtEnd() && !cur.checkWord("BEGIN") && !cur.checkWord("END")) {
            if (cur.matchSymbol(";")) {
                continue;
            }
            if (cur.checkWord("CURSOR")) {
                out.add(parseOracleCursorDecl());
            } else if (cur.checkAnyWord("TYPE", "SUBTYPE", "PRAGMA", "PROCEDURE", "FUNCTION")) {
                skipToSemicolon();
            } else if (cur.peek().isIdentifierLike()) {
                DeclareVarStmt v = parsePlsqlVariableDecl();
                if (v != null) {
                    out.add(v);
                }
            } else {
                cur.advance();
            }
            cur.matchSymbol(";");
        }
    }

    private DeclareVarStmt parseOracleCursorDecl() {
        SourceSpan start = cur.expectWord("CURSOR").span();
        String cName = cur.expectIdentifier("cursor name").value();
        if (cur.checkSymbol("(")) {
            cur.skipParenthesizedGroup();
        }
        if (cur.matchWord("RETURN")) {
            cur.parseDataType();
        }
        QueryExpr query = null;
        if (cur.matchAnyWord("IS", "AS", "FOR")) {
            query = queryParser.parseQuery();
        }
        return new DeclareVarStmt(cName, "CURSOR", "CURSOR", null, null, List.of(), query, null,
                SourceSpan.between(start, cur.previous().span()));
    }

    private DeclareVarStmt parsePlsqlVariableDecl() {
        SqlToken nameTok = cur.advance();
        SourceSpan start = nameTok.span();
        cur.matchWord("CONSTANT");

        if (cur.matchWord("CURSOR")) {
            // PostgreSQL / Snowflake: c CURSOR FOR SELECT ...
            if (cur.checkSymbol("(")) {
                cur.skipParenthesizedGroup();
            }
            QueryExpr q = null;
            if (cur.matchAnyWord("FOR", "IS")) {
                q = queryParser.parseQuery();
            }
            return new DeclareVarStmt(nameTok.value(), "CURSOR", "CURSOR", null, null, List.of(), q, null,
                    SourceSpan.between(start, cur.previous().span()));
        }

        if (cur.matchWord("EXCEPTION")) {
            return new DeclareVarStmt(nameTok.value(), "SCALAR", "EXCEPTION", null, null, List.of(), null, null,
                    SourceSpan.between(start, cur.previous().span()));
        }

        // Check table%ROWTYPE or table.col%TYPE
        String varKind = "SCALAR";
        QualifiedName refObj = null;
        String refCol = null;
        int saved = cur.position();
        String dtype = cur.parseDataType();
        String upperType = dtype.toUpperCase(Locale.ROOT);
        if (upperType.endsWith("%ROWTYPE")) {
            varKind = "ROWTYPE";
            cur.seek(saved);
            refObj = cur.parseQualifiedName();
            if (cur.matchSymbol("%")) {
                cur.matchWord("ROWTYPE");
            }
        } else if (upperType.endsWith("%TYPE")) {
            varKind = "TYPE_COLUMN";
            cur.seek(saved);
            QualifiedName full = cur.parseQualifiedName();
            if (cur.matchSymbol("%")) {
                cur.matchWord("TYPE");
            }
            refCol = full.lastName();
            if (full.size() >= 2) {
                List<QualifiedName.NamePart> tblParts = full.parts().subList(0, full.size() - 1);
                refObj = new QualifiedName(tblParts, full.dbLink(), false, false, full.templated(), full.span());
            }
        }

        if (cur.matchWords("NOT", "NULL")) {
            // consumed
        }
        Expr defExpr = null;
        if (cur.matchSymbol(":=") || cur.matchSymbol("=") || cur.matchWord("DEFAULT")) {
            defExpr = exprParser.parseExpression();
        }
        return new DeclareVarStmt(nameTok.value(), varKind, dtype, refObj, refCol, List.of(), null, defExpr,
                SourceSpan.between(start, cur.previous().span()));
    }

    public SqlStmt parseTsqlOrMysqlStandaloneDeclare() {
        SourceSpan start = cur.expectWord("DECLARE").span();

        // ANSI / T-SQL / MySQL / DB2 cursor: DECLARE cur_name [INSENSITIVE] [SCROLL] CURSOR FOR SELECT ...
        if (cur.peek().isIdentifierLike() && isCursorKeywordAhead()) {
            String curName = cur.advance().value();
            while (!cur.isAtEnd() && !cur.checkWord("CURSOR")) {
                cur.advance();
            }
            cur.expectWord("CURSOR");
            while (!cur.isAtEnd() && !cur.checkAnyWord("FOR", "IS")) {
                cur.advance();
            }
            QueryExpr q = null;
            if (cur.matchAnyWord("FOR", "IS") && cur.checkAnyWord("SELECT", "WITH", "VALUES")) {
                q = queryParser.parseQuery();
            }
            return new DeclareVarStmt(curName, "CURSOR", "CURSOR", null, null, List.of(), q, null,
                    SourceSpan.between(start, cur.previous().span()));
        }

        // MySQL DECLARE CONTINUE/EXIT HANDLER FOR ...
        if (cur.checkAnyWord("CONTINUE", "EXIT", "UNDO") && cur.peek(1).isWord("HANDLER")) {
            skipToSemicolon();
            return new SqlStmt.NoOpStmt("DECLARE_HANDLER", SourceSpan.between(start, cur.previous().span()));
        }

        List<SqlStmt> declared = new ArrayList<>();
        do {
            SqlToken vTok = cur.advance();
            cur.matchWord("AS");
            if (cur.matchWord("TABLE") && cur.checkSymbol("(")) {
                List<ColumnDef> cols = parseSimpleReturnTableColumns();
                declared.add(new DeclareVarStmt(
                        vTok.value(), "TABLE", "TABLE", null, null, cols, null, null,
                        SourceSpan.between(vTok.span(), cur.previous().span())));
            } else {
                String dtype = cur.parseDataType();
                Expr def = null;
                if (cur.matchSymbol("=") || cur.matchSymbol(":=") || cur.matchWord("DEFAULT")) {
                    def = exprParser.parseExpression();
                }
                declared.add(new DeclareVarStmt(
                        vTok.value(), "SCALAR", dtype, null, null, List.of(), null, def,
                        SourceSpan.between(vTok.span(), cur.previous().span())));
            }
        } while (cur.matchSymbol(","));

        if (declared.size() == 1) {
            return declared.get(0);
        }
        List<DeclareVarStmt> castDecls = new ArrayList<>();
        for (SqlStmt s : declared) {
            if (s instanceof DeclareVarStmt d) {
                castDecls.add(d);
            }
        }
        return new AnonymousBlockStmt(castDecls, List.of(), SourceSpan.between(start, cur.previous().span()));
    }

    private boolean isCursorKeywordAhead() {
        for (int i = 1; i <= 5; i++) {
            SqlToken t = cur.peek(i);
            if (t.isEof() || t.isSymbol(";") || t.isSymbol(",")) {
                return false;
            }
            if (t.isWord("CURSOR")) {
                return true;
            }
        }
        return false;
    }

    public List<SqlStmt> parseStatementListUntilEnd() {
        List<SqlStmt> stmts = new ArrayList<>();
        while (!cur.isAtEnd()) {
            if (cur.matchSymbol(";")) {
                continue;
            }
            if (cur.checkAnyWord("END", "ELSIF", "ELSEIF", "ELSE", "EXCEPTION", "WHEN")) {
                break;
            }
            int before = cur.position();
            try {
                stmts.add(parseProceduralOrSqlStatement());
            } catch (SqlParseException ex) {
                stmts.add(new SqlStmt.ErrorStmt("SyntaxError", ex.formattedSyntaxError(), ex.span()));
                recoverToNextProceduralStatement(before);
            }
            cur.matchSymbol(";");
        }

        // PL/SQL / PL/pgSQL / Snowflake EXCEPTION WHEN ... THEN ... handler section!
        if (cur.matchWord("EXCEPTION")) {
            while (cur.matchWord("WHEN")) {
                while (!cur.isAtEnd() && !cur.checkWord("THEN")) {
                    cur.advance();
                }
                cur.matchWord("THEN");
                while (!cur.isAtEnd() && !cur.checkAnyWord("WHEN", "END")) {
                    if (cur.matchSymbol(";")) {
                        continue;
                    }
                    int before = cur.position();
                    try {
                        stmts.add(parseProceduralOrSqlStatement());
                    } catch (SqlParseException ex) {
                        recoverToNextProceduralStatement(before);
                    }
                    cur.matchSymbol(";");
                }
            }
        }
        return stmts;
    }

    private void recoverToNextProceduralStatement(int beforePos) {
        if (cur.position() == beforePos && !cur.isAtEnd()) {
            cur.advance();
        }
        while (!cur.isAtEnd() && !cur.checkSymbol(";") && !cur.checkWord("END")) {
            cur.advance();
        }
    }

    public SqlStmt parseProceduralOrSqlStatement() {
        SqlToken tok = cur.peek();

        // Strip PL/SQL <<label>>
        if (tok.isSymbol("<<")) {
            cur.advance();
            if (!cur.isAtEnd()) {
                cur.advance();
            }
            cur.matchSymbol(">>");
            tok = cur.peek();
        }

        if (tok.isWord("DECLARE") || tok.isWord("BEGIN")) {
            return parseAnonymousBlockOrDeclareStmt();
        }
        if (tok.isWord("IF")) {
            return parseIfStmt();
        }
        if (tok.isWord("CASE")) {
            return parseProceduralCaseStmt();
        }
        if (tok.isWord("FOR")) {
            return parseForLoopStmt();
        }
        if (tok.isWord("WHILE") || tok.isWord("LOOP")) {
            return parseWhileOrLoopStmt();
        }
        if (tok.isWord("OPEN")) {
            return parseOpenCursorStmt();
        }
        if (tok.isWord("FETCH")) {
            return parseFetchCursorStmt();
        }
        if (tok.isWord("CLOSE")) {
            return parseCloseCursorStmt();
        }
        if (tok.isWord("RETURN")) {
            return parseReturnStmt();
        }
        if (tok.isWord("PIPE") && cur.peek(1).isWord("ROW")) {
            SourceSpan start = cur.advance().span();
            cur.advance();
            Expr val = exprParser.parseExpression();
            return new ReturnStmt(val, null, true, SourceSpan.between(start, val.span()));
        }
        if (tok.isWord("CALL")) {
            return parseCallStmt();
        }
        if (tok.isAnyWord("EXEC", "EXECUTE")) {
            return parseExecOrExecuteStmt();
        }
        if (tok.isWord("PERFORM")) {
            // PostgreSQL PERFORM query / fn() -> evaluate as SELECT
            SourceSpan start = cur.advance().span();
            List<io.sqlflow.lineage.ast.QueryAst.SelectItem> items = queryParser.parseSelectItemList();
            List<io.sqlflow.lineage.ast.QueryAst.TableSource> froms = new ArrayList<>();
            List<io.sqlflow.lineage.ast.QueryAst.JoinClause> joins = new ArrayList<>();
            if (cur.matchWord("FROM")) {
                queryParser.parseFromAndJoins(froms, joins);
            }
            Expr where = cur.matchWord("WHERE") ? exprParser.parseExpression() : null;
            io.sqlflow.lineage.ast.QueryAst.SelectQuery sq = new io.sqlflow.lineage.ast.QueryAst.SelectQuery(
                    List.of(), false, items, null, froms, joins, List.of(),
                    where, null, List.of(), null, null, List.of(), List.of(), null, null, null,
                    SourceSpan.between(start, cur.previous().span()));
            return new SqlStmt.QueryStmt(sq, sq.span());
        }
        if (tok.isWord("SET")) {
            return parseSetStmt();
        }
        if (tok.isAnyWord("NULL", "EXIT", "CONTINUE", "LEAVE", "ITERATE", "RAISE", "THROW", "PRINT", "DBMS_OUTPUT", "COMMIT", "ROLLBACK", "SAVEPOINT", "PRAGMA", "DEALLOCATE")) {
            SourceSpan s = cur.advance().span();
            skipToSemicolon();
            return new SqlStmt.NoOpStmt(tok.upperValue(), SourceSpan.between(s, cur.previous().span()));
        }

        // Assignment statement: var := expr OR rec.field := expr
        if ((tok.isIdentifierLike() || tok.type() == TokenType.BIND_VARIABLE) && isAssignmentAhead()) {
            return parseDirectAssignmentStmt();
        }

        // Procedural call without CALL keyword in PL/SQL: pkg.proc_name(arg1, arg2);
        if (tok.isIdentifierLike() && isBareProcedureCallAhead()) {
            QualifiedName callee = cur.parseQualifiedName();
            List<Expr> args = new ArrayList<>();
            if (cur.matchSymbol("(")) {
                if (!cur.checkSymbol(")")) {
                    args = exprParser.parseExpressionList();
                }
                cur.expectSymbol(")");
            }
            return new CallStmt(callee, args, null, SourceSpan.between(callee.span(), cur.previous().span()));
        }

        return stmtParser.parseSingleStatementFromCursor();
    }

    private boolean isAssignmentAhead() {
        if (cur.peek(1).isSymbol(":=") || cur.peek(1).isSymbol("=")) {
            return true;
        }
        if (cur.peek(1).isSymbol(".") && cur.peek(2).isIdentifierLike()
                && (cur.peek(3).isSymbol(":=") || cur.peek(3).isSymbol("="))) {
            return true;
        }
        return false;
    }

    private boolean isBareProcedureCallAhead() {
        SqlToken first = cur.peek();
        if (Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "WITH", "CREATE", "ALTER", "DROP",
                "TRUNCATE", "COPY", "UNLOAD", "LOAD", "VALUES", "USE", "COMMENT", "REPLACE", "RENAME")
                .contains(first.upperValue())) {
            return false;
        }
        int idx = 1;
        while (cur.peek(idx).isSymbol(".") && cur.peek(idx + 1).isIdentifierLike()) {
            idx += 2;
        }
        return cur.peek(idx).isSymbol("(") || cur.peek(idx).isSymbol(";");
    }

    private AssignStmt parseDirectAssignmentStmt() {
        SqlToken first = cur.advance();
        String varName = first.value();
        String field = null;
        if (cur.matchSymbol(".") && cur.peek().isIdentifierLike()) {
            field = cur.advance().value();
        }
        if (!cur.matchSymbol(":=")) {
            cur.expectSymbol("=");
        }
        Expr val = exprParser.parseExpression();
        return new AssignStmt(varName, field, val, SourceSpan.between(first.span(), val.span()));
    }

    public SqlStmt parseSetStmt() {
        SourceSpan start = cur.expectWord("SET").span();

        // Session context: SET search_path TO analytics, public OR SET SCHEMA 'analytics'
        if (cur.matchAnyWord("SEARCH_PATH", "CURRENT_SCHEMA", "SCHEMA")) {
            cur.matchAnyWord("TO", "=");
            cur.matchSymbol("=");
            SqlToken targetTok = cur.advance();
            QualifiedName qn = QualifiedName.single(
                    targetTok.rawText(), targetTok.value(), targetTok.quoted(), false, targetTok.span());
            skipToSemicolon();
            return new SqlStmt.UseContextStmt("SCHEMA", qn, SourceSpan.between(start, qn.span()));
        }

        // T-SQL options: SET NOCOUNT ON / SET ANSI_NULLS ON / SET QUOTED_IDENTIFIER ON
        if (cur.checkAnyWord("NOCOUNT", "ANSI_NULLS", "QUOTED_IDENTIFIER", "XACT_ABORT", "TRANSACTION", " IDENTITY_INSERT", "IDENTITY_INSERT", "LOCAL", "ROLE", "TIME")) {
            skipToSemicolon();
            return new SqlStmt.NoOpStmt("SET_OPTION", SourceSpan.between(start, cur.previous().span()));
        }

        // Variable assignment: SET @v = expr / SET v = expr / Snowflake SET (v1, v2) = (e1, e2)
        if (cur.matchSymbol("(")) {
            List<String> names = new ArrayList<>();
            do {
                names.add(cur.advance().value());
            } while (cur.matchSymbol(","));
            cur.expectSymbol(")");
            cur.matchSymbol("=");
            Expr rhs = exprParser.parseExpression();
            return new AssignStmt(names.isEmpty() ? "$var" : names.get(0), null, rhs,
                    SourceSpan.between(start, rhs.span()));
        }

        SqlToken targetTok = cur.advance();
        String field = null;
        if (cur.matchSymbol(".") && cur.peek().isIdentifierLike()) {
            field = cur.advance().value();
        }
        if (cur.matchSymbol("=") || cur.matchSymbol(":=") || cur.matchWord("TO")) {
            Expr rhs = exprParser.parseExpression();
            return new AssignStmt(targetTok.value(), field, rhs, SourceSpan.between(start, rhs.span()));
        }
        skipToSemicolon();
        return new SqlStmt.NoOpStmt("SET", SourceSpan.between(start, cur.previous().span()));
    }

    private IfStmt parseIfStmt() {
        SourceSpan start = cur.expectWord("IF").span();
        List<ConditionalBranch> branches = new ArrayList<>();
        Expr cond = exprParser.parseExpression();

        if (cur.matchWord("THEN")) {
            // PL/SQL / PL/pgSQL / Snowflake / MySQL: IF cond THEN ... [ELSIF ... THEN ...] [ELSE ...] END IF
            List<SqlStmt> thenStmts = parseStatementListUntilEnd();
            branches.add(new ConditionalBranch(cond, thenStmts, SourceSpan.between(start, cur.previous().span())));

            while (cur.matchAnyWord("ELSIF", "ELSEIF")) {
                SourceSpan bStart = cur.previous().span();
                Expr bCond = exprParser.parseExpression();
                cur.expectWord("THEN");
                List<SqlStmt> bStmts = parseStatementListUntilEnd();
                branches.add(new ConditionalBranch(bCond, bStmts, SourceSpan.between(bStart, cur.previous().span())));
            }
            List<SqlStmt> elseStmts = List.of();
            if (cur.matchWord("ELSE")) {
                elseStmts = parseStatementListUntilEnd();
            }
            if (cur.matchWord("END")) {
                cur.matchWord("IF");
            }
            return new IfStmt(branches, elseStmts, SourceSpan.between(start, cur.previous().span()));
        } else {
            // T-SQL IF cond stmt [ELSE stmt]
            SqlStmt thenStmt = parseProceduralOrSqlStatement();
            cur.matchSymbol(";");
            branches.add(new ConditionalBranch(cond, List.of(thenStmt), SourceSpan.between(start, thenStmt.span())));
            List<SqlStmt> elseStmts = List.of();
            if (cur.matchWord("ELSE")) {
                SqlStmt elseStmt = parseProceduralOrSqlStatement();
                elseStmts = List.of(elseStmt);
            }
            return new IfStmt(branches, elseStmts, SourceSpan.between(start, cur.previous().span()));
        }
    }

    private IfStmt parseProceduralCaseStmt() {
        SourceSpan start = cur.expectWord("CASE").span();
        Expr selector = null;
        if (!cur.checkWord("WHEN")) {
            selector = exprParser.parseExpression();
        }
        List<ConditionalBranch> branches = new ArrayList<>();
        while (cur.matchWord("WHEN")) {
            SourceSpan wStart = cur.previous().span();
            Expr wCond = exprParser.parseExpression();
            if (selector != null) {
                wCond = new Expr.BinaryExpr("=", selector, wCond, SourceSpan.between(selector.span(), wCond.span()));
            }
            cur.expectWord("THEN");
            List<SqlStmt> stmts = parseStatementListUntilEnd();
            branches.add(new ConditionalBranch(wCond, stmts, SourceSpan.between(wStart, cur.previous().span())));
        }
        List<SqlStmt> elseStmts = List.of();
        if (cur.matchWord("ELSE")) {
            elseStmts = parseStatementListUntilEnd();
        }
        if (cur.matchWord("END")) {
            cur.matchWord("CASE");
        }
        return new IfStmt(branches, elseStmts, SourceSpan.between(start, cur.previous().span()));
    }

    private ForLoopStmt parseForLoopStmt() {
        SourceSpan start = cur.expectWord("FOR").span();
        String loopVar = cur.expectIdentifier("loop variable").value();
        cur.expectWord("IN");
        cur.matchWord("REVERSE");

        String cursorName = null;
        QueryExpr inlineQuery = null;

        if (cur.checkSymbol("(") && cur.peek(1).isAnyWord("SELECT", "WITH")) {
            cur.expectSymbol("(");
            inlineQuery = queryParser.parseQuery();
            cur.expectSymbol(")");
        } else if (cur.checkAnyWord("SELECT", "WITH")) {
            inlineQuery = queryParser.parseQuery();
        } else {
            Expr rangeOrCursor = exprParser.parseExpression();
            if (cur.matchSymbol("..") || cur.matchWord("TO")) {
                exprParser.parseExpression(); // numeric upper bound 1..10
            } else if (rangeOrCursor instanceof Expr.ColumnRefExpr cr) {
                cursorName = cr.name().lastName();
            } else if (rangeOrCursor instanceof Expr.FuncCallExpr fc) {
                cursorName = fc.name().lastName();
            }
        }

        cur.matchAnyWord("LOOP", "DO");
        List<SqlStmt> body = parseStatementListUntilEnd();
        if (cur.matchWord("END")) {
            cur.matchAnyWord("LOOP", "FOR");
        }
        return new ForLoopStmt(loopVar, cursorName, inlineQuery, body,
                SourceSpan.between(start, cur.previous().span()));
    }

    private WhileLoopStmt parseWhileOrLoopStmt() {
        SourceSpan start = cur.advance().span();
        boolean isWhile = cur.previous().isWord("WHILE");
        Expr cond = isWhile ? exprParser.parseExpression() : null;
        if (isWhile && cur.checkWord("BEGIN")) {
            // T-SQL WHILE @i < 10 BEGIN ... END
            SqlStmt blk = parseAnonymousBlockOrDeclareStmt();
            return new WhileLoopStmt(cond, List.of(blk), SourceSpan.between(start, blk.span()));
        }
        cur.matchAnyWord("LOOP", "DO");
        List<SqlStmt> body = parseStatementListUntilEnd();
        if (cur.matchWord("END")) {
            cur.matchAnyWord("LOOP", "WHILE");
        }
        return new WhileLoopStmt(cond, body, SourceSpan.between(start, cur.previous().span()));
    }

    private OpenCursorStmt parseOpenCursorStmt() {
        SourceSpan start = cur.expectWord("OPEN").span();
        String cursorName = cur.advance().value();
        if (cur.checkSymbol("(")) {
            cur.skipParenthesizedGroup();
        }
        QueryExpr forQuery = null;
        Expr dynSql = null;
        if (cur.matchWord("FOR")) {
            if (cur.checkAnyWord("SELECT", "WITH", "VALUES")) {
                forQuery = queryParser.parseQuery();
            } else {
                dynSql = exprParser.parseExpression();
                if (cur.matchWord("USING")) {
                    exprParser.parseExpressionList();
                }
            }
        }
        return new OpenCursorStmt(cursorName, forQuery, dynSql, SourceSpan.between(start, cur.previous().span()));
    }

    private FetchCursorStmt parseFetchCursorStmt() {
        SourceSpan start = cur.expectWord("FETCH").span();
        if (cur.matchAnyWord("NEXT", "PRIOR", "FIRST", "LAST", "ABSOLUTE", "RELATIVE")) {
            if (!cur.checkWord("FROM") && !cur.checkWord("IN") && cur.peek().type() == TokenType.NUMBER_LITERAL) {
                cur.advance();
            }
        }
        cur.matchAnyWord("FROM", "IN");
        String cursorName = cur.advance().value();
        if (cur.matchWords("BULK", "COLLECT")) {
            // PL/SQL BULK COLLECT INTO
        }
        List<String> intoVars = new ArrayList<>();
        if (cur.matchWord("INTO")) {
            do {
                intoVars.add(cur.advance().value());
            } while (cur.matchSymbol(","));
        }
        if (cur.matchWord("LIMIT")) {
            exprParser.parseExpression();
        }
        return new FetchCursorStmt(cursorName, intoVars, SourceSpan.between(start, cur.previous().span()));
    }

    private CloseCursorStmt parseCloseCursorStmt() {
        SourceSpan start = cur.expectWord("CLOSE").span();
        String cursorName = cur.advance().value();
        return new CloseCursorStmt(cursorName, SourceSpan.between(start, cur.previous().span()));
    }

    private ReturnStmt parseReturnStmt() {
        SourceSpan start = cur.expectWord("RETURN").span();
        if (cur.isAtEnd() || cur.checkSymbol(";") || cur.checkWord("END")) {
            return new ReturnStmt(null, null, false, start);
        }
        if (cur.matchWord("QUERY")) {
            if (cur.matchWord("EXECUTE")) {
                Expr dyn = exprParser.parseExpression();
                return new ReturnStmt(dyn, null, false, SourceSpan.between(start, dyn.span()));
            }
            QueryExpr q = queryParser.parseQuery();
            return new ReturnStmt(null, q, false, SourceSpan.between(start, q.span()));
        }
        if (cur.matchWord("TABLE") && cur.checkSymbol("(")) {
            cur.expectSymbol("(");
            QueryExpr q = queryParser.parseQuery();
            SqlToken close = cur.expectSymbol(")");
            return new ReturnStmt(null, q, false, SourceSpan.between(start, close.span()));
        }
        if (cur.checkAnyWord("SELECT", "WITH")) {
            QueryExpr q = queryParser.parseQuery();
            return new ReturnStmt(null, q, false, SourceSpan.between(start, q.span()));
        }
        Expr expr = exprParser.parseExpression();
        return new ReturnStmt(expr, null, false, SourceSpan.between(start, expr.span()));
    }

    public CallStmt parseCallStmt() {
        SourceSpan start = cur.expectWord("CALL").span();
        QualifiedName callee = cur.parseQualifiedName();
        List<Expr> args = new ArrayList<>();
        if (cur.matchSymbol("(")) {
            if (!cur.checkSymbol(")")) {
                args = exprParser.parseExpressionList();
            }
            cur.expectSymbol(")");
        }
        String intoVar = null;
        if (cur.matchWord("INTO")) {
            intoVar = cur.advance().value();
        }
        return new CallStmt(callee, args, intoVar, SourceSpan.between(start, cur.previous().span()));
    }

    public SqlStmt parseExecOrExecuteStmt() {
        SourceSpan start = cur.advance().span(); // EXEC or EXECUTE

        // 1. Oracle / Postgres / Snowflake / BigQuery: EXECUTE IMMEDIATE sql_expr [INTO ...] [USING ...]
        if (cur.matchWord("IMMEDIATE")) {
            Expr sqlExpr = exprParser.parseExpression();
            List<String> intoVars = new ArrayList<>();
            List<Expr> usingArgs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                if (cur.matchWords("BULK", "COLLECT") && cur.matchWord("INTO")) {
                    do {
                        intoVars.add(cur.advance().value());
                    } while (cur.matchSymbol(","));
                } else if (cur.matchWord("INTO")) {
                    do {
                        intoVars.add(cur.advance().value());
                    } while (cur.matchSymbol(","));
                }
                if (cur.matchWord("USING")) {
                    do {
                        cur.matchAnyWord("IN", "OUT", "INOUT");
                        usingArgs.add(exprParser.parseExpression());
                    } while (cur.matchSymbol(","));
                }
            }
            return new DynamicSqlStmt("EXECUTE_IMMEDIATE", sqlExpr, usingArgs, intoVars, null, List.of(),
                    SourceSpan.between(start, cur.previous().span()));
        }

        // 2. T-SQL dynamic SQL: EXEC (@sql) or EXEC ('SELECT ...')
        if (cur.checkSymbol("(")) {
            cur.expectSymbol("(");
            Expr sqlExpr = exprParser.parseExpression();
            SqlToken close = cur.expectSymbol(")");
            return new DynamicSqlStmt("EXEC_DYNAMIC", sqlExpr, List.of(), List.of(), null, List.of(),
                    SourceSpan.between(start, close.span()));
        }

        // Optional T-SQL return status: EXEC @rc = dbo.my_proc ...
        String intoVar = null;
        if (cur.peek().type() == TokenType.BIND_VARIABLE && cur.peek(1).isSymbol("=")) {
            intoVar = cur.advance().value();
            cur.advance(); // '='
        }

        // PostgreSQL EXECUTE dynamic_sql [INTO v] [USING ...] (when string or expression without proc name)
        if (cur.peek().type() == TokenType.STRING_LITERAL) {
            Expr sqlExpr = exprParser.parseExpression();
            List<String> intoVars = new ArrayList<>();
            List<Expr> usingArgs = new ArrayList<>();
            if (cur.matchWord("INTO")) {
                do {
                    intoVars.add(cur.advance().value());
                } while (cur.matchSymbol(","));
            }
            if (cur.matchWord("USING")) {
                usingArgs = exprParser.parseExpressionList();
            }
            return new DynamicSqlStmt("EXECUTE_IMMEDIATE", sqlExpr, usingArgs, intoVars, null, List.of(),
                    SourceSpan.between(start, cur.previous().span()));
        }

        QualifiedName targetName = cur.parseQualifiedName();
        String lowerLast = targetName.lastName().toLowerCase(Locale.ROOT);

        // 3. T-SQL sp_executesql [@stmt =] sqlExpr [, @params, @p1 = v1...]
        if ("sp_executesql".equals(lowerLast)) {
            if (cur.peek().type() == TokenType.BIND_VARIABLE && cur.peek(1).isSymbol("=")) {
                cur.advance();
                cur.advance();
            }
            Expr sqlExpr = exprParser.parseExpression();
            List<Expr> usingArgs = new ArrayList<>();
            while (cur.matchSymbol(",")) {
                if (cur.peek().type() == TokenType.BIND_VARIABLE && cur.peek(1).isSymbol("=")) {
                    cur.advance();
                    cur.advance();
                }
                Expr arg = exprParser.parseExpression();
                cur.matchAnyWord("OUT", "OUTPUT");
                usingArgs.add(arg);
            }
            return new DynamicSqlStmt("SP_EXECUTESQL", sqlExpr, usingArgs, List.of(), null, List.of(),
                    SourceSpan.between(start, cur.previous().span()));
        }

        // 4. T-SQL sp_execute_external_script (@input_data_1 = N'SELECT ...' WITH RESULT SETS ((...)))
        if ("sp_execute_external_script".equals(lowerLast)) {
            return parseExternalScriptStmt(start);
        }

        // 5. Standard stored procedure call: EXEC dbo.proc_name arg1, @p2 = arg2 OUTPUT
        List<Expr> args = new ArrayList<>();
        if (cur.matchSymbol("(")) {
            if (!cur.checkSymbol(")")) {
                args = exprParser.parseExpressionList();
            }
            cur.expectSymbol(")");
        } else if (!cur.isAtEnd() && !cur.checkSymbol(";") && !cur.checkWord("END")) {
            do {
                if (cur.peek().type() == TokenType.BIND_VARIABLE && cur.peek(1).isSymbol("=")) {
                    cur.advance();
                    cur.advance();
                }
                Expr a = exprParser.parseExpression();
                cur.matchAnyWord("OUT", "OUTPUT");
                args.add(a);
            } while (cur.matchSymbol(","));
        }
        return new CallStmt(targetName, args, intoVar, SourceSpan.between(start, cur.previous().span()));
    }

    private DynamicSqlStmt parseExternalScriptStmt(SourceSpan start) {
        Expr inputSqlExpr = null;
        List<ColumnDef> resultCols = new ArrayList<>();
        while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            if (cur.peek().type() == TokenType.BIND_VARIABLE
                    && cur.peek().rawText().equalsIgnoreCase("@input_data_1")
                    && cur.peek(1).isSymbol("=")) {
                cur.advance();
                cur.advance();
                inputSqlExpr = exprParser.parseExpression();
            } else if (cur.matchWords("WITH", "RESULT") && cur.matchWord("SETS")) {
                if (cur.matchSymbol("(")) {
                    if (cur.checkSymbol("(")) {
                        resultCols = parseSimpleReturnTableColumns();
                    }
                    cur.matchSymbol(")");
                }
            } else {
                cur.advance();
            }
        }
        QueryExpr parsedInputQuery = null;
        if (inputSqlExpr instanceof Expr.LiteralExpr lit && !lit.value().isBlank()) {
            try {
                List<SqlStmt> inner = stmtParser.parseScript(lit.value(), cur.vendor(), null, false).statements();
                if (!inner.isEmpty() && inner.get(0) instanceof SqlStmt.QueryStmt qs) {
                    parsedInputQuery = qs.query();
                }
            } catch (RuntimeException ignored) {
                // Handled gracefully
            }
        }
        return new DynamicSqlStmt("EXTERNAL_SCRIPT", inputSqlExpr, List.of(), List.of(), parsedInputQuery, resultCols,
                SourceSpan.between(start, cur.previous().span()));
    }

    // -------------------------------------------------------------------------
    // 3. Power Query M (`let ... in ...`) & MDX (`SELECT ... ON COLUMNS FROM [Cube]`)
    // -------------------------------------------------------------------------

    public PowerQueryLetStmt parsePowerQueryLetStmt() {
        SourceSpan start = cur.expectWord("LET").span();
        List<PowerQueryStep> steps = new ArrayList<>();

        while (!cur.isAtEnd() && !cur.checkWord("IN")) {
            SqlToken stepNameTok = cur.advance();
            String stepName = stepNameTok.value();
            SourceSpan stepStart = stepNameTok.span();
            cur.expectSymbol("=");

            PowerQueryStep step = parseSinglePowerQueryStep(stepName, stepStart);
            steps.add(step);
            cur.matchSymbol(",");
        }

        String outStep = steps.isEmpty() ? "" : steps.get(steps.size() - 1).stepName();
        if (cur.matchWord("IN") && !cur.isAtEnd()) {
            outStep = cur.advance().value();
        }
        return new PowerQueryLetStmt(steps, outStep, SourceSpan.between(start, cur.previous().span()));
    }

    private PowerQueryStep parseSinglePowerQueryStep(String stepName, SourceSpan stepStart) {
        List<String> referencedSteps = new ArrayList<>();
        String funcKind = "GENERIC";
        String server = null;
        String database = null;
        String schema = null;
        String table = null;
        String nativeSql = null;
        List<String> selectedCols = new ArrayList<>();
        Map<String, String> renamedCols = new LinkedHashMap<>();
        String addedColName = null;

        // Collect all tokens until top-level ',' or 'IN'
        List<SqlToken> exprTokens = new ArrayList<>();
        int paren = 0;
        int brace = 0;
        int bracket = 0;
        while (!cur.isAtEnd()) {
            if (paren == 0 && brace == 0 && bracket == 0 && (cur.checkSymbol(",") || cur.checkWord("IN"))) {
                break;
            }
            SqlToken t = cur.advance();
            if (t.isSymbol("(")) {
                paren++;
            } else if (t.isSymbol(")")) {
                paren = Math.max(0, paren - 1);
            } else if (t.isSymbol("{")) {
                brace++;
            } else if (t.isSymbol("}")) {
                brace = Math.max(0, brace - 1);
            } else if (t.isSymbol("[")) {
                bracket++;
            } else if (t.isSymbol("]")) {
                bracket = Math.max(0, bracket - 1);
            }
            exprTokens.add(t);
        }

        String joinedName = readLeadingDottedName(exprTokens);
        List<String> stringLiterals = new ArrayList<>();
        List<String> topLevelArgStrings = new ArrayList<>();
        int callDepth = 0;
        for (SqlToken t : exprTokens) {
            if (t.isSymbol("(")) {
                callDepth++;
            } else if (t.isSymbol(")")) {
                callDepth = Math.max(0, callDepth - 1);
            } else if (t.type() == TokenType.STRING_LITERAL
                    || (t.type() == TokenType.QUOTED_IDENTIFIER && t.rawText().startsWith("\""))) {
                stringLiterals.add(t.value());
                if (callDepth == 1) {
                    topLevelArgStrings.add(t.value());
                }
            }
        }

        if (joinedName.endsWith(".Database") || joinedName.endsWith(".Databases")) {
            funcKind = "SOURCE_DATABASE";
            if (!stringLiterals.isEmpty()) {
                server = stringLiterals.get(0);
            }
            if (stringLiterals.size() >= 2) {
                database = stringLiterals.get(1);
            }
        } else if ("Value.NativeQuery".equalsIgnoreCase(joinedName)) {
            funcKind = "NATIVE_QUERY";
            String srcRef = findFirstIdentifierArgAfterParen(exprTokens);
            if (srcRef != null) {
                referencedSteps.add(srcRef);
            }
            if (!topLevelArgStrings.isEmpty()) {
                nativeSql = topLevelArgStrings.get(0);
            } else if (!stringLiterals.isEmpty()) {
                nativeSql = stringLiterals.get(stringLiterals.size() - 1);
            }
        } else if ("Table.SelectColumns".equalsIgnoreCase(joinedName)) {
            funcKind = "SELECT_COLUMNS";
            String srcRef = findFirstIdentifierArgAfterParen(exprTokens);
            if (srcRef != null) {
                referencedSteps.add(srcRef);
            }
            selectedCols.addAll(stringLiterals);
        } else if ("Table.RenameColumns".equalsIgnoreCase(joinedName)) {
            funcKind = "RENAME_COLUMNS";
            String srcRef = findFirstIdentifierArgAfterParen(exprTokens);
            if (srcRef != null) {
                referencedSteps.add(srcRef);
            }
            for (int i = 0; i + 1 < stringLiterals.size(); i += 2) {
                renamedCols.put(stringLiterals.get(i), stringLiterals.get(i + 1));
            }
        } else if ("Table.AddColumn".equalsIgnoreCase(joinedName)) {
            funcKind = "ADD_COLUMN";
            String srcRef = findFirstIdentifierArgAfterParen(exprTokens);
            if (srcRef != null) {
                referencedSteps.add(srcRef);
            }
            if (!stringLiterals.isEmpty()) {
                addedColName = stringLiterals.get(0);
            }
        } else if (joinedName.startsWith("Table.") && (joinedName.contains("Join") || joinedName.contains("Combine"))) {
            funcKind = "JOIN";
            referencedSteps.addAll(findIdentifierArgsInCall(exprTokens));
        } else if (!exprTokens.isEmpty() && exprTokens.stream().anyMatch(t -> t.isSymbol("{"))) {
            // Navigation syntax: Source{[Schema="dbo",Item="Orders"]}[Data]
            funcKind = "TABLE_NAVIGATION";
            referencedSteps.add(exprTokens.get(0).value());
            for (int i = 0; i + 2 < exprTokens.size(); i++) {
                String k = exprTokens.get(i).value();
                if (exprTokens.get(i + 1).isSymbol("=") && exprTokens.get(i + 2).type() == TokenType.STRING_LITERAL) {
                    String val = exprTokens.get(i + 2).value();
                    if ("Schema".equalsIgnoreCase(k)) {
                        schema = val;
                    } else if ("Item".equalsIgnoreCase(k) || "Name".equalsIgnoreCase(k)) {
                        table = val;
                    } else if ("Database".equalsIgnoreCase(k)) {
                        database = val;
                    }
                }
            }
        } else {
            String srcRef = findFirstIdentifierArgAfterParen(exprTokens);
            if (srcRef != null) {
                referencedSteps.add(srcRef);
            } else if (!exprTokens.isEmpty() && exprTokens.get(0).isIdentifierLike()) {
                referencedSteps.add(exprTokens.get(0).value());
            }
        }

        SourceSpan endSpan = exprTokens.isEmpty() ? stepStart : exprTokens.get(exprTokens.size() - 1).span();
        return new PowerQueryStep(
                stepName, funcKind, referencedSteps, server, database, schema, table,
                nativeSql, selectedCols, renamedCols, addedColName, null,
                SourceSpan.between(stepStart, endSpan));
    }

    private static String readLeadingDottedName(List<SqlToken> tokens) {
        if (tokens.isEmpty() || !tokens.get(0).isIdentifierLike()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(tokens.get(0).value());
        int idx = 1;
        while (idx + 1 < tokens.size() && tokens.get(idx).isSymbol(".") && tokens.get(idx + 1).isIdentifierLike()) {
            sb.append('.').append(tokens.get(idx + 1).value());
            idx += 2;
        }
        return sb.toString();
    }

    private static String findFirstIdentifierArgAfterParen(List<SqlToken> tokens) {
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (tokens.get(i).isSymbol("(") && tokens.get(i + 1).isIdentifierLike()) {
                return tokens.get(i + 1).value();
            }
        }
        return null;
    }

    private static List<String> findIdentifierArgsInCall(List<SqlToken> tokens) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            SqlToken t = tokens.get(i);
            if (t.isSymbol("(")) {
                depth++;
                if (depth == 1 && i + 1 < tokens.size() && tokens.get(i + 1).isIdentifierLike()) {
                    out.add(tokens.get(i + 1).value());
                }
            } else if (t.isSymbol(")")) {
                depth--;
            } else if (depth == 1 && t.isSymbol(",") && i + 1 < tokens.size() && tokens.get(i + 1).isIdentifierLike()) {
                out.add(tokens.get(i + 1).value());
            }
        }
        return out;
    }

    public MdxSelectStmt parseMdxSelectStmt() {
        SourceSpan start = cur.peek().span();
        List<MdxCalculatedMember> members = new ArrayList<>();

        if (cur.matchWord("WITH")) {
            while (cur.checkAnyWord("MEMBER", "SET")) {
                boolean isMember = cur.advance().isWord("MEMBER");
                QualifiedName mName = cur.parseQualifiedName();
                cur.expectWord("AS");
                Expr formula = exprParser.parseExpression();
                // Skip optional solve_order / format_string properties
                while (cur.matchSymbol(",") && cur.peek().isIdentifierLike() && cur.peek(1).isSymbol("=")) {
                    cur.advance();
                    cur.advance();
                    exprParser.parseExpression();
                }
                if (isMember) {
                    members.add(new MdxCalculatedMember(mName, formula, SourceSpan.between(mName.span(), formula.span())));
                }
            }
        }

        cur.expectWord("SELECT");
        List<Expr> axes = new ArrayList<>();
        while (!cur.isAtEnd() && !cur.checkWord("FROM")) {
            cur.matchWords("NON", "EMPTY");
            if (cur.matchSymbol("{")) {
                while (!cur.isAtEnd() && !cur.checkSymbol("}")) {
                    axes.add(exprParser.parseExpression());
                    if (!cur.matchSymbol(",")) {
                        break;
                    }
                }
                cur.matchSymbol("}");
            } else if (!cur.checkWord("ON")) {
                axes.add(exprParser.parseExpression());
            }
            if (cur.matchWord("ON")) {
                if (cur.checkWord("AXIS") && cur.peek(1).isSymbol("(")) {
                    cur.advance();
                    cur.skipParenthesizedGroup();
                } else if (!cur.isAtEnd()) {
                    cur.advance(); // COLUMNS, ROWS, PAGES, 0, 1
                }
            }
            if (!cur.matchSymbol(",")) {
                break;
            }
        }

        QualifiedName cube = null;
        if (cur.matchWord("FROM")) {
            if (cur.checkSymbol("(")) {
                // MDX subcube FROM (SELECT ... FROM [Cube])
                cur.expectSymbol("(");
                MdxSelectStmt sub = parseMdxSelectStmt();
                cur.expectSymbol(")");
                cube = sub.cubeName();
                axes.addAll(sub.axisExpressions());
            } else {
                cube = cur.parseQualifiedName();
            }
        }

        Expr slicer = null;
        if (cur.matchWord("WHERE")) {
            slicer = exprParser.parseExpression();
        }
        return new MdxSelectStmt(members, axes, cube, slicer, SourceSpan.between(start, cur.previous().span()));
    }

    private void skipToSemicolon() {
        while (!cur.isAtEnd() && !cur.checkSymbol(";")) {
            cur.advance();
        }
    }
}
