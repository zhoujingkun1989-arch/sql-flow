package io.sqlflow.lineage.ast;

import io.sqlflow.lineage.ast.QueryAst.OrderByItem;
import io.sqlflow.lineage.ast.QueryAst.QueryExpr;
import io.sqlflow.lineage.ast.QueryAst.SelectItem;
import io.sqlflow.lineage.ast.QueryAst.WindowSpec;
import io.sqlflow.lineage.lexer.SourceSpan;
import java.util.List;
import java.util.Map;

/**
 * Multi-dialect SQL expression AST hierarchy with exact 1-based {@link SourceSpan}s.
 */
public sealed interface Expr permits
        Expr.ColumnRefExpr,
        Expr.StarExpr,
        Expr.LiteralExpr,
        Expr.VariableExpr,
        Expr.FuncCallExpr,
        Expr.CaseExpr,
        Expr.BinaryExpr,
        Expr.UnaryExpr,
        Expr.CastExpr,
        Expr.SubqueryExpr,
        Expr.InListExpr,
        Expr.BetweenExpr,
        Expr.ExtractExpr,
        Expr.SequenceExpr,
        Expr.StructArrayExpr {

    SourceSpan span();

    record ColumnRefExpr(
            QualifiedName name,
            List<String> jsonOrStructPath,
            boolean oracleOuterJoinPlus,
            SourceSpan span
    ) implements Expr {
        public String leafPathOrColumnName() {
            if (jsonOrStructPath != null && !jsonOrStructPath.isEmpty()) {
                return jsonOrStructPath.get(jsonOrStructPath.size() - 1);
            }
            return name.lastName();
        }
    }

    record StarExpr(
            QualifiedName qualifier,
            List<String> exceptColumns,
            Map<String, Expr> replaceColumns,
            Map<String, String> renameColumns,
            SourceSpan span
    ) implements Expr {
    }

    record LiteralExpr(
            String value,
            String literalType, // STRING, NUMBER, BOOLEAN, NULL, INTERVAL
            SourceSpan span
    ) implements Expr {
    }

    record VariableExpr(
            String rawName,
            String cleanName,
            String kind, // AT_VAR, COLON_BIND, PSEUDO_RECORD (:NEW/:OLD/INSERTED/DELETED), TEMPLATE
            String fieldName,
            SourceSpan span
    ) implements Expr {
    }

    record FuncCallExpr(
            QualifiedName name,
            boolean distinct,
            List<Expr> args,
            List<OrderByItem> withinGroupOrder,
            Expr filterWhere,
            WindowSpec overWindow,
            SourceSpan span
    ) implements Expr {
    }

    record WhenClause(
            Expr whenExpr,
            Expr thenExpr,
            SourceSpan span
    ) {
    }

    record CaseExpr(
            Expr operand,
            List<WhenClause> whenClauses,
            Expr elseExpr,
            SourceSpan span
    ) implements Expr {
    }

    record BinaryExpr(
            String operator,
            Expr left,
            Expr right,
            SourceSpan span
    ) implements Expr {
    }

    record UnaryExpr(
            String operator,
            Expr operand,
            SourceSpan span
    ) implements Expr {
    }

    record CastExpr(
            Expr expr,
            String targetType,
            boolean safeOrTry,
            SourceSpan span
    ) implements Expr {
    }

    record SubqueryExpr(
            String kind, // SCALAR, EXISTS, NOT_EXISTS, IN, NOT_IN, ARRAY
            Expr testExpr,
            QueryExpr query,
            SourceSpan span
    ) implements Expr {
    }

    record InListExpr(
            Expr expr,
            boolean negated,
            List<Expr> items,
            SourceSpan span
    ) implements Expr {
    }

    record BetweenExpr(
            Expr expr,
            boolean negated,
            Expr low,
            Expr high,
            SourceSpan span
    ) implements Expr {
    }

    record ExtractExpr(
            String field,
            Expr source,
            SourceSpan span
    ) implements Expr {
    }

    record SequenceExpr(
            QualifiedName sequenceName,
            String operation, // NEXTVAL, CURRVAL
            SourceSpan span
    ) implements Expr {
    }

    record StructArrayExpr(
            String kind, // STRUCT, ARRAY, MAP, OBJECT
            List<SelectItem> elements,
            SourceSpan span
    ) implements Expr {
    }
}
