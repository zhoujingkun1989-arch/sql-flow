package io.sqlflow.lineage.demo;

import io.sqlflow.contracts.spi.DemoAstServiceSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.demo.AstXmlAndOpsHelper.ExpressionAnalysisItem;
import io.sqlflow.lineage.demo.AstXmlAndOpsHelper.JoinInfo;
import io.sqlflow.lineage.demo.AstXmlAndOpsHelper.WhereConditionInfo;
import io.sqlflow.lineage.demo.TableColumnInspector.DetailedInspection;
import java.util.List;

/**
 * Production {@link DemoAstServiceSpi} implementation backed by the SQLFlow multi-dialect lexer,
 * AST parser, formatter, and refactoring engines ({@code OBL-061}..{@code OBL-066}).
 */
public class DemoAstService implements DemoAstServiceSpi {

    private final SqlFormatterEngine formatterEngine;
    private final TableColumnInspector tableColumnInspector;
    private final SyntaxAndKeywordService syntaxAndKeywordService;
    private final AntiSqlInjectionDetector antiSqlInjectionDetector;
    private final CallRelationAndAstOpsService callRelationAndAstOpsService;

    public DemoAstService() {
        this.formatterEngine = new SqlFormatterEngine();
        this.tableColumnInspector = new TableColumnInspector();
        this.syntaxAndKeywordService = new SyntaxAndKeywordService();
        this.antiSqlInjectionDetector = new AntiSqlInjectionDetector();
        this.callRelationAndAstOpsService = new CallRelationAndAstOpsService(
                this.syntaxAndKeywordService,
                this.tableColumnInspector);
    }

    @Override
    public FormatResult sqlformat(FormatRequest request) {
        return formatterEngine.format(request);
    }

    @Override
    public TableColumnResult getTableColumns(AstSqlRequest request) {
        return tableColumnInspector.getTableColumns(request);
    }

    @Override
    public TableColumnResult tableColumn(AstSqlRequest request) {
        return tableColumnInspector.tableColumn(request);
    }

    @Override
    public SyntaxCheckResult syntaxCheck(AstSqlRequest request) {
        return syntaxAndKeywordService.syntaxCheck(request);
    }

    @Override
    public TokenizeResult tokenize(AstSqlRequest request) {
        return syntaxAndKeywordService.tokenize(request);
    }

    @Override
    public SqlInjectionResult antiSqlInjection(AstSqlRequest request) {
        return antiSqlInjectionDetector.detect(request);
    }

    @Override
    public CallRelationResult callRelation(AstSqlRequest request) {
        return callRelationAndAstOpsService.callRelation(request);
    }

    @Override
    public ColumnImpactResult columnImpact(AstSqlRequest request) {
        return callRelationAndAstOpsService.columnImpact(request);
    }

    @Override
    public FindConstantsResult findConstants(AstSqlRequest request) {
        return callRelationAndAstOpsService.findConstants(request);
    }

    @Override
    public RewriteSqlResult joinConvert(JoinConvertRequest request) {
        return callRelationAndAstOpsService.joinConvert(request);
    }

    @Override
    public KeywordCheckResult keywordCheck(AstSqlRequest request) {
        return syntaxAndKeywordService.keywordCheck(request);
    }

    @Override
    public RewriteSqlResult renameTable(RenameTableRequest request) {
        return callRelationAndAstOpsService.renameTable(request);
    }

    @Override
    public RewriteSqlResult renameColumn(RenameColumnRequest request) {
        return callRelationAndAstOpsService.renameColumn(request);
    }

    @Override
    public RewriteSqlResult removeSchema(RemoveSchemaRequest request) {
        return callRelationAndAstOpsService.removeSchema(request);
    }

    @Override
    public XmlAstResult toXml(AstSqlRequest request) {
        return callRelationAndAstOpsService.toXml(request);
    }

    // -------------------------------------------------------------------------
    // Extended /demo/* AST Utility & Transformation Operations (OBL-062, OBL-066)
    // -------------------------------------------------------------------------

    public DetailedInspection inspectTableColumnsDetailed(String sql, EDbVendor vendor, String metadataTriples) {
        return tableColumnInspector.inspect(sql, vendor, metadataTriples);
    }

    public String formatTableColumnsCsv(String sql, EDbVendor vendor, String mode, String delimiter) {
        return tableColumnInspector.formatCsv(sql, vendor, mode, delimiter);
    }

    public List<JoinInfo> joins(AstSqlRequest request) {
        return callRelationAndAstOpsService.extractJoins(request);
    }

    public List<WhereConditionInfo> whereCondition(AstSqlRequest request) {
        return callRelationAndAstOpsService.extractWhereConditions(request);
    }

    public RewriteSqlResult removeCondition(AstSqlRequest request, String columnOrParam) {
        return callRelationAndAstOpsService.removeCondition(request, columnOrParam);
    }

    public RewriteSqlResult alterScript(AstSqlRequest sourceDdl, AstSqlRequest targetDdl) {
        return callRelationAndAstOpsService.generateAlterScript(sourceDdl, targetDdl);
    }

    public RewriteSqlResult sqlTranslate(AstSqlRequest request, EDbVendor targetVendor) {
        return callRelationAndAstOpsService.sqlTranslate(request, targetVendor);
    }

    public List<ExpressionAnalysisItem> analyzeExpressions(AstSqlRequest request) {
        return callRelationAndAstOpsService.analyzeExpressions(request);
    }
}
