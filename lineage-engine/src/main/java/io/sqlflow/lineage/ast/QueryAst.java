package io.sqlflow.lineage.ast;

import io.sqlflow.lineage.ast.SqlStmt.ColumnDef;
import io.sqlflow.lineage.lexer.SourceSpan;
import java.util.List;

/**
 * Multi-dialect query, CTE, table-source, join, lateral-view, pivot/unpivot, and window AST nodes.
 */
public final class QueryAst {

    private QueryAst() {
    }

    public sealed interface QueryExpr permits SelectQuery, SetOpQuery, ValuesQuery {
        SourceSpan span();
    }

    public record CteDef(
            String name,
            boolean nameQuoted,
            List<String> columnAliases,
            boolean recursive,
            QueryExpr query,
            SourceSpan nameSpan,
            SourceSpan span
    ) {
    }

    public record SelectItem(
            Expr expr,
            String alias,
            boolean aliasQuoted,
            SourceSpan aliasSpan,
            String rawCode,
            SourceSpan span
    ) {
        public String effectiveOutputName() {
            if (alias != null && !alias.isEmpty()) {
                return alias;
            }
            if (expr instanceof Expr.ColumnRefExpr colRef) {
                return colRef.leafPathOrColumnName();
            }
            if (expr instanceof Expr.VariableExpr varExpr) {
                return varExpr.fieldName() != null ? varExpr.fieldName() : varExpr.cleanName();
            }
            if (expr instanceof Expr.SequenceExpr seqExpr) {
                return seqExpr.operation();
            }
            if (expr instanceof Expr.CastExpr castExpr && castExpr.expr() instanceof Expr.ColumnRefExpr innerCol) {
                return innerCol.leafPathOrColumnName();
            }
            if (expr instanceof Expr.FuncCallExpr fn) {
                return fn.name().lastName();
            }
            return rawCode != null && !rawCode.isBlank() ? rawCode.trim() : "EXPR";
        }
    }

    public record IntoClause(
            List<QualifiedName> targetTables,
            List<Expr.VariableExpr> targetVariables,
            boolean temporaryTable,
            boolean overwriteDirectory,
            String directoryUri,
            SourceSpan span
    ) {
    }

    public record OutputClause(
            String kind, // OUTPUT, RETURNING
            List<SelectItem> items,
            QualifiedName intoTable,
            List<String> intoColumns,
            SourceSpan span
    ) {
    }

    public record OrderByItem(
            Expr expr,
            String direction,
            String nullsOrder,
            SourceSpan span
    ) {
    }

    public record WindowSpec(
            String refWindowName,
            List<Expr> partitionBy,
            List<OrderByItem> orderBy,
            String frameClause,
            SourceSpan span
    ) {
    }

    public record NamedWindowDef(
            String name,
            WindowSpec spec,
            SourceSpan span
    ) {
    }

    public record ConnectByClause(
            Expr startWith,
            Expr connectBy,
            boolean nocycle,
            SourceSpan span
    ) {
    }

    public record LateralViewClause(
            boolean outer,
            QualifiedName funcName,
            List<Expr> args,
            String tableAlias,
            List<String> columnAliases,
            SourceSpan span
    ) {
    }

    public record PivotAggItem(
            Expr aggExpr,
            String alias,
            SourceSpan span
    ) {
    }

    public record PivotValueItem(
            Expr valueExpr,
            String alias,
            SourceSpan span
    ) {
    }

    public record PivotSpec(
            List<PivotAggItem> aggregates,
            List<String> forColumns,
            List<PivotValueItem> inValues,
            String alias,
            SourceSpan span
    ) {
    }

    public record UnpivotSpec(
            List<String> valueColumns,
            List<String> nameColumns,
            List<String> inSourceColumns,
            String alias,
            SourceSpan span
    ) {
    }

    public sealed interface TableSource permits
            NamedTableSource,
            SubqueryTableSource,
            TableFunctionSource,
            ParenthesizedJoinSource {
        String alias();
        SourceSpan span();
    }

    public record NamedTableSource(
            QualifiedName tableName,
            String alias,
            boolean aliasQuoted,
            SourceSpan aliasSpan,
            List<String> columnAliases,
            PivotSpec pivot,
            UnpivotSpec unpivot,
            SourceSpan span
    ) implements TableSource {
    }

    public record SubqueryTableSource(
            QueryExpr query,
            boolean lateral,
            String alias,
            boolean aliasQuoted,
            SourceSpan aliasSpan,
            List<String> columnAliases,
            PivotSpec pivot,
            UnpivotSpec unpivot,
            SourceSpan span
    ) implements TableSource {
    }

    public record TableFunctionSource(
            String kind, // UNNEST, FLATTEN, FUNCTION, EXTERNAL_TABLE
            QualifiedName funcName,
            List<Expr> args,
            Expr inputExpr,
            boolean lateral,
            boolean withOffset,
            String offsetAlias,
            String alias,
            List<String> columnAliases,
            List<ColumnDef> explicitColumns,
            SourceSpan span
    ) implements TableSource {
    }

    public record ParenthesizedJoinSource(
            TableSource left,
            List<JoinClause> joins,
            String alias,
            SourceSpan span
    ) implements TableSource {
    }

    public record JoinClause(
            String joinType, // INNER, LEFT, RIGHT, FULL, CROSS, NATURAL, LEFT SEMI, LEFT ANTI, CROSS APPLY, OUTER APPLY, IMPLICIT
            TableSource right,
            Expr onCondition,
            List<String> usingColumns,
            String rawCondition,
            SourceSpan span
    ) {
    }

    public record SelectQuery(
            List<CteDef> ctes,
            boolean distinct,
            List<SelectItem> selectItems,
            IntoClause intoClause,
            List<TableSource> fromSources,
            List<JoinClause> joins,
            List<LateralViewClause> lateralViews,
            Expr whereExpr,
            ConnectByClause connectBy,
            List<Expr> groupByExprs,
            Expr havingExpr,
            Expr qualifyExpr,
            List<NamedWindowDef> windowDefs,
            List<OrderByItem> orderBy,
            Expr limitExpr,
            Expr offsetExpr,
            OutputClause outputClause,
            SourceSpan span
    ) implements QueryExpr {
    }

    public record SetOpQuery(
            List<CteDef> ctes,
            String operator, // UNION, UNION ALL, INTERSECT, EXCEPT, MINUS
            QueryExpr left,
            QueryExpr right,
            List<OrderByItem> orderBy,
            Expr limitExpr,
            SourceSpan span
    ) implements QueryExpr {
    }

    public record ValuesQuery(
            List<List<Expr>> rows,
            SourceSpan span
    ) implements QueryExpr {
    }
}
