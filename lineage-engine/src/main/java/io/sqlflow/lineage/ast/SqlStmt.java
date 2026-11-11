package io.sqlflow.lineage.ast;

import io.sqlflow.lineage.ast.QueryAst.JoinClause;
import io.sqlflow.lineage.ast.QueryAst.OutputClause;
import io.sqlflow.lineage.ast.QueryAst.QueryExpr;
import io.sqlflow.lineage.ast.QueryAst.TableSource;
import io.sqlflow.lineage.lexer.SourceSpan;
import java.util.List;
import java.util.Map;

/**
 * Top-level and procedural SQL statement AST hierarchy across all 48 {@code EDbVendor} dialects.
 */
public sealed interface SqlStmt permits
        SqlStmt.QueryStmt,
        SqlStmt.InsertStmt,
        SqlStmt.MultiInsertStmt,
        SqlStmt.UpdateStmt,
        SqlStmt.DeleteStmt,
        SqlStmt.MergeStmt,
        SqlStmt.CopyIoStmt,
        SqlStmt.CreateTableStmt,
        SqlStmt.CreateViewStmt,
        SqlStmt.AlterTableStmt,
        SqlStmt.DropStmt,
        SqlStmt.TruncateStmt,
        SqlStmt.CreateSynonymStmt,
        SqlStmt.CreateSequenceStmt,
        SqlStmt.CreateStageOrStreamStmt,
        SqlStmt.CreateDatabaseOrSchemaStmt,
        SqlStmt.UseContextStmt,
        SqlStmt.CommentOnStmt,
        SqlStmt.CreatePackageStmt,
        SqlStmt.CreateRoutineStmt,
        SqlStmt.AnonymousBlockStmt,
        SqlStmt.DeclareVarStmt,
        SqlStmt.AssignStmt,
        SqlStmt.OpenCursorStmt,
        SqlStmt.FetchCursorStmt,
        SqlStmt.CloseCursorStmt,
        SqlStmt.ForLoopStmt,
        SqlStmt.IfStmt,
        SqlStmt.WhileLoopStmt,
        SqlStmt.ReturnStmt,
        SqlStmt.CallStmt,
        SqlStmt.DynamicSqlStmt,
        SqlStmt.PowerQueryLetStmt,
        SqlStmt.MdxSelectStmt,
        SqlStmt.NoOpStmt,
        SqlStmt.ErrorStmt {

    SourceSpan span();

    // -------------------------------------------------------------------------
    // 1. Queries & DML
    // -------------------------------------------------------------------------

    record QueryStmt(QueryExpr query, SourceSpan span) implements SqlStmt {
    }

    record InsertBranch(
            Expr whenCondition,
            QualifiedName targetTable,
            boolean overwrite,
            boolean directoryTarget,
            List<String> targetColumns,
            List<SourceSpan> targetColumnSpans,
            List<Expr> explicitValues,
            QueryExpr branchQuery,
            SourceSpan span
    ) {
    }

    record InsertStmt(
            boolean replaceInto,
            boolean overwrite,
            boolean directoryTarget,
            QualifiedName targetTable,
            List<String> targetColumns,
            List<SourceSpan> targetColumnSpans,
            QueryExpr sourceQuery,
            OutputClause outputClause,
            SourceSpan span
    ) implements SqlStmt {
    }

    /**
     * Covers both Oracle/Snowflake {@code INSERT ALL/FIRST WHEN ... THEN INTO ... SELECT ...}
     * and Hive/Spark/Databricks {@code FROM src INSERT INTO t1 SELECT ... INSERT OVERWRITE TABLE t2 SELECT ...}.
     */
    record MultiInsertStmt(
            String style, // INSERT_ALL, INSERT_FIRST, FROM_MULTI_INSERT
            List<TableSource> sharedFromSources,
            List<JoinClause> sharedJoins,
            Expr sharedWhere,
            QueryExpr sharedSourceQuery,
            List<InsertBranch> branches,
            SourceSpan span
    ) implements SqlStmt {
    }

    record AssignmentItem(
            List<QualifiedName> targetColumns,
            Expr valueExpr,
            QueryExpr tupleSubquery,
            String rawCode,
            SourceSpan span
    ) {
    }

    record UpdateStmt(
            List<TableSource> targetTables,
            List<JoinClause> targetJoins,
            List<AssignmentItem> assignments,
            List<TableSource> fromSources,
            List<JoinClause> fromJoins,
            Expr whereExpr,
            OutputClause outputClause,
            SourceSpan span
    ) implements SqlStmt {
    }

    record DeleteStmt(
            QualifiedName targetTable,
            String targetAlias,
            List<TableSource> usingSources,
            List<JoinClause> joins,
            Expr whereExpr,
            OutputClause outputClause,
            SourceSpan span
    ) implements SqlStmt {
    }

    record MergeWhenClause(
            boolean matched,
            boolean bySource,
            Expr andCondition,
            String action, // UPDATE, INSERT, DELETE
            List<AssignmentItem> updateAssignments,
            List<String> insertColumns,
            List<Expr> insertValues,
            Expr deleteWhere,
            SourceSpan span
    ) {
    }

    record MergeStmt(
            QualifiedName targetTable,
            String targetAlias,
            TableSource usingSource,
            Expr onCondition,
            String rawOnCondition,
            List<MergeWhenClause> whenClauses,
            OutputClause outputClause,
            SourceSpan span
    ) implements SqlStmt {
    }

    /**
     * Snowflake {@code COPY INTO}, Redshift {@code COPY} / {@code UNLOAD}, Hive/MySQL {@code LOAD DATA},
     * MSSQL {@code BULK INSERT}.
     */
    record CopyIoStmt(
            String operation, // COPY, UNLOAD, LOAD_DATA, BULK_INSERT
            QualifiedName targetObject,
            List<String> targetColumns,
            QualifiedName sourceObject,
            QueryExpr sourceQuery,
            String fileFormat,
            SourceSpan span
    ) implements SqlStmt {
    }

    // -------------------------------------------------------------------------
    // 2. DDL & Catalog Metadata
    // -------------------------------------------------------------------------

    record ForeignKeySpec(
            String constraintName,
            List<String> localColumns,
            QualifiedName referencedTable,
            List<String> referencedColumns,
            SourceSpan span
    ) {
    }

    record ColumnDef(
            String name,
            boolean quoted,
            String dataType,
            Boolean nullable,
            boolean primaryKey,
            boolean uniqueKey,
            boolean indexKey,
            boolean identity,
            boolean computed,
            boolean persisted,
            String computedDefinition,
            Expr computedExpr,
            String defaultValue,
            String defaultName,
            String comment,
            ForeignKeySpec inlineForeignKey,
            SourceSpan span
    ) {
    }

    record TableConstraint(
            String constraintName,
            String constraintType, // PRIMARY_KEY, UNIQUE, FOREIGN_KEY, INDEX, CHECK
            List<String> columns,
            ForeignKeySpec foreignKey,
            SourceSpan span
    ) {
    }

    record CreateTableStmt(
            boolean orReplace,
            boolean temporary,
            boolean globalTemporary,
            boolean external,
            QualifiedName tableName,
            List<ColumnDef> columns,
            List<TableConstraint> constraints,
            QueryExpr asSelect,
            QualifiedName likeTable,
            QualifiedName cloneTable,
            String locationUri,
            String fileFormat,
            String comment,
            SourceSpan span
    ) implements SqlStmt {
    }

    record CreateViewStmt(
            boolean orReplace,
            boolean materialized,
            QualifiedName viewName,
            List<String> columnNames,
            List<SourceSpan> columnSpans,
            QueryExpr query,
            String comment,
            SourceSpan span
    ) implements SqlStmt {
    }

    record AlterAction(
            String actionType, // ADD_CONSTRAINT, ADD_COLUMN, DROP_COLUMN, RENAME_TABLE, RENAME_COLUMN, SWAP_WITH, EXCHANGE_PARTITION, SWITCH_PARTITION
            TableConstraint constraint,
            ColumnDef addedColumn,
            String droppedOrOldColumn,
            String newColumnName,
            QualifiedName otherTable,
            SourceSpan span
    ) {
    }

    record AlterTableStmt(
            QualifiedName tableName,
            List<AlterAction> actions,
            SourceSpan span
    ) implements SqlStmt {
    }

    record DropStmt(
            String objectKind, // TABLE, VIEW, PROCEDURE, FUNCTION, PACKAGE, SYNONYM, SEQUENCE
            List<QualifiedName> names,
            SourceSpan span
    ) implements SqlStmt {
    }

    record TruncateStmt(
            QualifiedName tableName,
            SourceSpan span
    ) implements SqlStmt {
    }

    record CreateSynonymStmt(
            boolean orReplace,
            boolean isPublic,
            QualifiedName synonymName,
            QualifiedName targetObject,
            SourceSpan span
    ) implements SqlStmt {
    }

    record CreateSequenceStmt(
            QualifiedName sequenceName,
            String startWith,
            String incrementBy,
            String minValue,
            String maxValue,
            Boolean cycle,
            Boolean order,
            String cache,
            SourceSpan span
    ) implements SqlStmt {
    }

    record CreateStageOrStreamStmt(
            String kind, // STAGE, STREAM, DATASOURCE
            QualifiedName name,
            String urlOrLocation,
            String fileFormat,
            QualifiedName sourceTable,
            SourceSpan span
    ) implements SqlStmt {
    }

    record CreateDatabaseOrSchemaStmt(
            String kind, // DATABASE, SCHEMA
            QualifiedName name,
            QualifiedName cloneSource,
            SourceSpan span
    ) implements SqlStmt {
    }

    record UseContextStmt(
            String kind, // DATABASE, SCHEMA
            QualifiedName target,
            SourceSpan span
    ) implements SqlStmt {
    }

    record CommentOnStmt(
            String targetKind, // TABLE, VIEW, COLUMN
            QualifiedName target,
            String comment,
            SourceSpan span
    ) implements SqlStmt {
    }

    // -------------------------------------------------------------------------
    // 3. Stored Procedures, Packages, Triggers, & Procedural Control Flow
    // -------------------------------------------------------------------------

    record RoutineArg(
            String name,
            String dataType,
            String inout, // IN, OUT, INOUT, RETURN
            Expr defaultExpr,
            SourceSpan span
    ) {
    }

    record CreatePackageStmt(
            boolean isBody,
            QualifiedName packageName,
            List<DeclareVarStmt> variables,
            List<CreateRoutineStmt> routines,
            List<SqlStmt> initStatements,
            SourceSpan span
    ) implements SqlStmt {
    }

    record CreateRoutineStmt(
            String kind, // PROCEDURE, FUNCTION, TRIGGER, MACRO
            QualifiedName routineName,
            List<RoutineArg> arguments,
            String returnType,
            List<ColumnDef> returnTableColumns,
            boolean returnsTable,
            boolean pipelined,
            QualifiedName triggerTable,
            List<String> triggerEvents,
            List<DeclareVarStmt> declarations,
            List<SqlStmt> bodyStatements,
            SourceSpan span
    ) implements SqlStmt {
    }

    record AnonymousBlockStmt(
            List<DeclareVarStmt> declarations,
            List<SqlStmt> bodyStatements,
            SourceSpan span
    ) implements SqlStmt {
    }

    record DeclareVarStmt(
            String name,
            String varKind, // SCALAR, CURSOR, TABLE, ROWTYPE, TYPE_COLUMN, RECORD
            String dataType,
            QualifiedName refObject,
            String refColumn,
            List<ColumnDef> tableColumns,
            QueryExpr cursorQuery,
            Expr defaultExpr,
            SourceSpan span
    ) implements SqlStmt {
    }

    record AssignStmt(
            String targetVariable,
            String targetField,
            Expr valueExpr,
            SourceSpan span
    ) implements SqlStmt {
    }

    record OpenCursorStmt(
            String cursorName,
            QueryExpr forQuery,
            Expr dynamicSqlExpr,
            SourceSpan span
    ) implements SqlStmt {
    }

    record FetchCursorStmt(
            String cursorName,
            List<String> intoVariables,
            SourceSpan span
    ) implements SqlStmt {
    }

    record CloseCursorStmt(
            String cursorName,
            SourceSpan span
    ) implements SqlStmt {
    }

    record ForLoopStmt(
            String loopVar,
            String cursorName,
            QueryExpr inlineQuery,
            List<SqlStmt> bodyStatements,
            SourceSpan span
    ) implements SqlStmt {
    }

    record ConditionalBranch(
            Expr condition,
            List<SqlStmt> statements,
            SourceSpan span
    ) {
    }

    record IfStmt(
            List<ConditionalBranch> branches,
            List<SqlStmt> elseStatements,
            SourceSpan span
    ) implements SqlStmt {
    }

    record WhileLoopStmt(
            Expr condition,
            List<SqlStmt> bodyStatements,
            SourceSpan span
    ) implements SqlStmt {
    }

    record ReturnStmt(
            Expr returnExpr,
            QueryExpr returnQuery,
            boolean pipeRow,
            SourceSpan span
    ) implements SqlStmt {
    }

    record CallStmt(
            QualifiedName routineName,
            List<Expr> arguments,
            String intoVariable,
            SourceSpan span
    ) implements SqlStmt {
    }

    record DynamicSqlStmt(
            String kind, // EXECUTE_IMMEDIATE, SP_EXECUTESQL, EXEC_DYNAMIC, EXTERNAL_SCRIPT
            Expr sqlExpr,
            List<Expr> usingArgs,
            List<String> intoVariables,
            QueryExpr externalInputQuery,
            List<ColumnDef> externalResultColumns,
            SourceSpan span
    ) implements SqlStmt {
    }

    // -------------------------------------------------------------------------
    // 4. Special Dialects (Power Query M, MDX) & Diagnostics
    // -------------------------------------------------------------------------

    record PowerQueryStep(
            String stepName,
            String funcKind, // SOURCE_DATABASE, TABLE_NAVIGATION, NATIVE_QUERY, SELECT_COLUMNS, RENAME_COLUMNS, ADD_COLUMN, JOIN, FILTER, GENERIC
            List<String> referencedSteps,
            String serverName,
            String databaseName,
            String schemaName,
            String tableName,
            String nativeSql,
            List<String> selectedColumns,
            Map<String, String> renamedColumns,
            String addedColumnName,
            Expr addedColumnExpr,
            SourceSpan span
    ) {
    }

    record PowerQueryLetStmt(
            List<PowerQueryStep> steps,
            String outputStepName,
            SourceSpan span
    ) implements SqlStmt {
    }

    record MdxCalculatedMember(
            QualifiedName memberName,
            Expr formula,
            SourceSpan span
    ) {
    }

    record MdxSelectStmt(
            List<MdxCalculatedMember> calculatedMembers,
            List<Expr> axisExpressions,
            QualifiedName cubeName,
            Expr whereSlicer,
            SourceSpan span
    ) implements SqlStmt {
    }

    record NoOpStmt(String kind, SourceSpan span) implements SqlStmt {
    }

    record ErrorStmt(
            String errorType,
            String message,
            SourceSpan span
    ) implements SqlStmt {
    }
}
