package io.sqlflow.contracts.spi;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.util.List;
import java.util.Map;

/**
 * Frozen Stage 2A SPI for the {@code /sqlflow/demo/**} SQL AST & Formatting utility endpoints
 * ({@code 02 §4.6 & §4.9}).
 */
public interface DemoAstServiceSpi {

    FormatResult sqlformat(FormatRequest request);

    TableColumnResult getTableColumns(AstSqlRequest request);

    TableColumnResult tableColumn(AstSqlRequest request);

    SyntaxCheckResult syntaxCheck(AstSqlRequest request);

    TokenizeResult tokenize(AstSqlRequest request);

    SqlInjectionResult antiSqlInjection(AstSqlRequest request);

    CallRelationResult callRelation(AstSqlRequest request);

    ColumnImpactResult columnImpact(AstSqlRequest request);

    FindConstantsResult findConstants(AstSqlRequest request);

    RewriteSqlResult joinConvert(JoinConvertRequest request);

    KeywordCheckResult keywordCheck(AstSqlRequest request);

    RewriteSqlResult renameTable(RenameTableRequest request);

    RewriteSqlResult renameColumn(RenameColumnRequest request);

    RewriteSqlResult removeSchema(RemoveSchemaRequest request);

    XmlAstResult toXml(AstSqlRequest request);

    // -------------------------------------------------------------------------
    // Requests & GFmtOpt (19-group SQL formatter options)
    // -------------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class AstSqlRequest {
        private String sql;
        private EDbVendor dbvendor = EDbVendor.dbvoracle;

        public AstSqlRequest() {
        }

        public AstSqlRequest(String sql, EDbVendor dbvendor) {
            this.sql = sql;
            this.dbvendor = dbvendor != null ? dbvendor : EDbVendor.dbvoracle;
        }

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
        }

        public EDbVendor getDbvendor() {
            return dbvendor;
        }

        public void setDbvendor(EDbVendor dbvendor) {
            this.dbvendor = dbvendor != null ? dbvendor : EDbVendor.dbvoracle;
        }
    }

    /**
     * Wire request body for {@code POST /sqlflow/demo/sqlformat} ({@code 02 §4.9 #6}), serializing
     * {@code input_sql} and {@code format_option} while preserving typed {@link GFmtOpt} convenience
     * accessors.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"input_sql", "format_option", "options"})
    class FormatRequest {
        @JsonProperty("input_sql")
        @JsonAlias({"sql", "input_sql"})
        private String inputSql;

        @JsonProperty("format_option")
        @JsonAlias({"formatOption", "format_option"})
        private FormatConfiguration formatOption;

        private GFmtOpt options;

        public FormatRequest() {
        }

        public FormatRequest(String sql, EDbVendor dbvendor, GFmtOpt options) {
            this.inputSql = sql;
            EDbVendor resolvedVendor = dbvendor != null ? dbvendor : EDbVendor.dbvoracle;
            this.formatOption = new FormatConfiguration(resolvedVendor.name());
            this.options = options;
        }

        public FormatRequest(String inputSql, FormatConfiguration formatOption) {
            this.inputSql = inputSql;
            this.formatOption = formatOption;
        }

        @JsonProperty("input_sql")
        public String getInputSql() {
            return inputSql;
        }

        @JsonProperty("input_sql")
        @JsonAlias({"sql", "input_sql"})
        public void setInputSql(String inputSql) {
            this.inputSql = inputSql;
        }

        @JsonProperty("format_option")
        public FormatConfiguration getFormatOption() {
            return formatOption;
        }

        @JsonProperty("format_option")
        @JsonAlias({"formatOption", "format_option"})
        public void setFormatOption(FormatConfiguration formatOption) {
            this.formatOption = formatOption;
        }

        public GFmtOpt getOptions() {
            return options;
        }

        public void setOptions(GFmtOpt options) {
            this.options = options;
        }

        @JsonIgnore
        public GFmtOpt resolveOptions() {
            return options != null ? options : new GFmtOpt();
        }

        @JsonIgnore
        public String getSql() {
            return inputSql;
        }

        @JsonIgnore
        public void setSql(String sql) {
            this.inputSql = sql;
        }

        @JsonIgnore
        public EDbVendor getDbvendor() {
            if (formatOption != null && formatOption.getDbVendor() != null) {
                return EDbVendor.fromStringOrDefault(formatOption.getDbVendor(), EDbVendor.dbvoracle);
            }
            return EDbVendor.dbvoracle;
        }

        @JsonIgnore
        public void setDbvendor(EDbVendor dbvendor) {
            EDbVendor resolved = dbvendor != null ? dbvendor : EDbVendor.dbvoracle;
            if (this.formatOption == null) {
                this.formatOption = new FormatConfiguration(resolved.name());
            } else {
                this.formatOption.setDbVendor(resolved.name());
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"db_vendor", "output", "preprocessor", "format_options"})
    class FormatConfiguration {
        @JsonProperty("db_vendor")
        @JsonAlias({"dbVendor", "dbvendor", "db_vendor"})
        private String dbVendor;

        private Object output;
        private Object preprocessor;

        @JsonProperty("format_options")
        @JsonAlias({"formatOptions", "format_options"})
        private Map<String, Object> formatOptions;

        public FormatConfiguration() {
        }

        public FormatConfiguration(String dbVendor) {
            this.dbVendor = dbVendor;
        }

        @JsonProperty("db_vendor")
        public String getDbVendor() {
            return dbVendor;
        }

        @JsonProperty("db_vendor")
        @JsonAlias({"dbVendor", "dbvendor", "db_vendor"})
        public void setDbVendor(String dbVendor) {
            this.dbVendor = dbVendor;
        }

        public Object getOutput() {
            return output;
        }

        public void setOutput(Object output) {
            this.output = output;
        }

        public Object getPreprocessor() {
            return preprocessor;
        }

        public void setPreprocessor(Object preprocessor) {
            this.preprocessor = preprocessor;
        }

        @JsonProperty("format_options")
        public Map<String, Object> getFormatOptions() {
            return formatOptions;
        }

        @JsonProperty("format_options")
        @JsonAlias({"formatOptions", "format_options"})
        public void setFormatOptions(Map<String, Object> formatOptions) {
            this.formatOptions = formatOptions;
        }
    }

    /**
     * 19-group SQL pretty-printer configuration ({@code GFmtOpt}) for {@code POST /sqlflow/demo/sqlformat}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class GFmtOpt {
        // 1. Line numbers
        private boolean linenumberEnabled = false;
        private int linenumberLeftMargin = 0;
        private boolean linenumberZeroBased = false;

        // 2. Indentation & Tabs
        private int indentLen = 2;
        private boolean useTab = false;
        private int tabSize = 4;

        // 3. Casing (Upper, Lower, InitCap, NoChange)
        private String caseKeywords = "Upper";
        private String caseIdentifier = "NoChange";
        private String caseFuncname = "InitCap";
        private String caseDatatype = "Upper";
        private String caseQuotedIdentifier = "NoChange";

        // 4. Whitespace & Padding
        private boolean wsPaddingOperatorArithmetic = true;
        private boolean wsPaddingParenthesesInFunction = false;
        private boolean wsPaddingParenthesesInFunctionCall = false;
        private boolean wsPaddingParenthesesOfSubQuery = false;
        private boolean wsPaddingParenthesesOfTypename = false;

        // 5–8. SELECT / FROM / JOIN / WHERE alignment & wrapping
        private String selectColumnlistStyle = "Stacked";
        private String selectColumnlistComma = "After";
        private boolean selectItemInNewLine = false;
        private boolean alignAliasInSelectList = true;
        private boolean treatDistinctAsVirtualColumn = false;
        private boolean fromClauseJoinInNewline = true;
        private boolean fromClauseInNewline = false;
        private boolean alignJoinWithFromKeyword = false;
        private boolean andOrUnderWhere = false;
        private boolean whereClauseInNewline = false;

        // 9–14. INSERT / UPDATE / CREATE TABLE / GROUP BY / ORDER BY
        private String insertColumnlistStyle = "Wrapped";
        private String insertValuelistStyle = "Wrapped";
        private String updateSetlistStyle = "Stacked";
        private String createTableItemlistStyle = "Stacked";
        private boolean createTableItemAlignDatatype = true;
        private String groupbyColumnlistStyle = "Stacked";
        private String orderbyColumnlistStyle = "Stacked";
        private String defaultCommaOption = "After";

        // 15–19. BEGIN/END blocks, IF/WHILE, Compact mode, Line width, Empty lines
        private boolean beStyleBlockLeftBEOnNewline = true;
        private boolean beStyleBlockRightBEOnNewline = true;
        private int beStyleBlockIndentSize = 2;
        private int beStyleIfWhileIndentSize = 2;
        private String compactMode = "None";
        private int lineWidth = 99;
        private String emptyLines = "MergeIntoOne";

        public GFmtOpt() {
        }

        public boolean isLinenumberEnabled() {
            return linenumberEnabled;
        }

        public void setLinenumberEnabled(boolean linenumberEnabled) {
            this.linenumberEnabled = linenumberEnabled;
        }

        public int getLinenumberLeftMargin() {
            return linenumberLeftMargin;
        }

        public void setLinenumberLeftMargin(int linenumberLeftMargin) {
            this.linenumberLeftMargin = linenumberLeftMargin;
        }

        public boolean isLinenumberZeroBased() {
            return linenumberZeroBased;
        }

        public void setLinenumberZeroBased(boolean linenumberZeroBased) {
            this.linenumberZeroBased = linenumberZeroBased;
        }

        public int getIndentLen() {
            return indentLen;
        }

        public void setIndentLen(int indentLen) {
            this.indentLen = indentLen;
        }

        public boolean isUseTab() {
            return useTab;
        }

        public void setUseTab(boolean useTab) {
            this.useTab = useTab;
        }

        public int getTabSize() {
            return tabSize;
        }

        public void setTabSize(int tabSize) {
            this.tabSize = tabSize;
        }

        public String getCaseKeywords() {
            return caseKeywords;
        }

        public void setCaseKeywords(String caseKeywords) {
            this.caseKeywords = caseKeywords;
        }

        public String getCaseIdentifier() {
            return caseIdentifier;
        }

        public void setCaseIdentifier(String caseIdentifier) {
            this.caseIdentifier = caseIdentifier;
        }

        public String getCaseFuncname() {
            return caseFuncname;
        }

        public void setCaseFuncname(String caseFuncname) {
            this.caseFuncname = caseFuncname;
        }

        public String getCaseDatatype() {
            return caseDatatype;
        }

        public void setCaseDatatype(String caseDatatype) {
            this.caseDatatype = caseDatatype;
        }

        public String getCaseQuotedIdentifier() {
            return caseQuotedIdentifier;
        }

        public void setCaseQuotedIdentifier(String caseQuotedIdentifier) {
            this.caseQuotedIdentifier = caseQuotedIdentifier;
        }

        public boolean isWsPaddingOperatorArithmetic() {
            return wsPaddingOperatorArithmetic;
        }

        public void setWsPaddingOperatorArithmetic(boolean wsPaddingOperatorArithmetic) {
            this.wsPaddingOperatorArithmetic = wsPaddingOperatorArithmetic;
        }

        public boolean isWsPaddingParenthesesInFunction() {
            return wsPaddingParenthesesInFunction;
        }

        public void setWsPaddingParenthesesInFunction(boolean wsPaddingParenthesesInFunction) {
            this.wsPaddingParenthesesInFunction = wsPaddingParenthesesInFunction;
        }

        public boolean isWsPaddingParenthesesInFunctionCall() {
            return wsPaddingParenthesesInFunctionCall;
        }

        public void setWsPaddingParenthesesInFunctionCall(boolean wsPaddingParenthesesInFunctionCall) {
            this.wsPaddingParenthesesInFunctionCall = wsPaddingParenthesesInFunctionCall;
        }

        public boolean isWsPaddingParenthesesOfSubQuery() {
            return wsPaddingParenthesesOfSubQuery;
        }

        public void setWsPaddingParenthesesOfSubQuery(boolean wsPaddingParenthesesOfSubQuery) {
            this.wsPaddingParenthesesOfSubQuery = wsPaddingParenthesesOfSubQuery;
        }

        public boolean isWsPaddingParenthesesOfTypename() {
            return wsPaddingParenthesesOfTypename;
        }

        public void setWsPaddingParenthesesOfTypename(boolean wsPaddingParenthesesOfTypename) {
            this.wsPaddingParenthesesOfTypename = wsPaddingParenthesesOfTypename;
        }

        public String getSelectColumnlistStyle() {
            return selectColumnlistStyle;
        }

        public void setSelectColumnlistStyle(String selectColumnlistStyle) {
            this.selectColumnlistStyle = selectColumnlistStyle;
        }

        public String getSelectColumnlistComma() {
            return selectColumnlistComma;
        }

        public void setSelectColumnlistComma(String selectColumnlistComma) {
            this.selectColumnlistComma = selectColumnlistComma;
        }

        public boolean isSelectItemInNewLine() {
            return selectItemInNewLine;
        }

        public void setSelectItemInNewLine(boolean selectItemInNewLine) {
            this.selectItemInNewLine = selectItemInNewLine;
        }

        public boolean isAlignAliasInSelectList() {
            return alignAliasInSelectList;
        }

        public void setAlignAliasInSelectList(boolean alignAliasInSelectList) {
            this.alignAliasInSelectList = alignAliasInSelectList;
        }

        public boolean isTreatDistinctAsVirtualColumn() {
            return treatDistinctAsVirtualColumn;
        }

        public void setTreatDistinctAsVirtualColumn(boolean treatDistinctAsVirtualColumn) {
            this.treatDistinctAsVirtualColumn = treatDistinctAsVirtualColumn;
        }

        public boolean isFromClauseJoinInNewline() {
            return fromClauseJoinInNewline;
        }

        public void setFromClauseJoinInNewline(boolean fromClauseJoinInNewline) {
            this.fromClauseJoinInNewline = fromClauseJoinInNewline;
        }

        public boolean isFromClauseInNewline() {
            return fromClauseInNewline;
        }

        public void setFromClauseInNewline(boolean fromClauseInNewline) {
            this.fromClauseInNewline = fromClauseInNewline;
        }

        public boolean isAlignJoinWithFromKeyword() {
            return alignJoinWithFromKeyword;
        }

        public void setAlignJoinWithFromKeyword(boolean alignJoinWithFromKeyword) {
            this.alignJoinWithFromKeyword = alignJoinWithFromKeyword;
        }

        public boolean isAndOrUnderWhere() {
            return andOrUnderWhere;
        }

        public void setAndOrUnderWhere(boolean andOrUnderWhere) {
            this.andOrUnderWhere = andOrUnderWhere;
        }

        public boolean isWhereClauseInNewline() {
            return whereClauseInNewline;
        }

        public void setWhereClauseInNewline(boolean whereClauseInNewline) {
            this.whereClauseInNewline = whereClauseInNewline;
        }

        public String getInsertColumnlistStyle() {
            return insertColumnlistStyle;
        }

        public void setInsertColumnlistStyle(String insertColumnlistStyle) {
            this.insertColumnlistStyle = insertColumnlistStyle;
        }

        public String getInsertValuelistStyle() {
            return insertValuelistStyle;
        }

        public void setInsertValuelistStyle(String insertValuelistStyle) {
            this.insertValuelistStyle = insertValuelistStyle;
        }

        public String getUpdateSetlistStyle() {
            return updateSetlistStyle;
        }

        public void setUpdateSetlistStyle(String updateSetlistStyle) {
            this.updateSetlistStyle = updateSetlistStyle;
        }

        public String getCreateTableItemlistStyle() {
            return createTableItemlistStyle;
        }

        public void setCreateTableItemlistStyle(String createTableItemlistStyle) {
            this.createTableItemlistStyle = createTableItemlistStyle;
        }

        public boolean isCreateTableItemAlignDatatype() {
            return createTableItemAlignDatatype;
        }

        public void setCreateTableItemAlignDatatype(boolean createTableItemAlignDatatype) {
            this.createTableItemAlignDatatype = createTableItemAlignDatatype;
        }

        public String getGroupbyColumnlistStyle() {
            return groupbyColumnlistStyle;
        }

        public void setGroupbyColumnlistStyle(String groupbyColumnlistStyle) {
            this.groupbyColumnlistStyle = groupbyColumnlistStyle;
        }

        public String getOrderbyColumnlistStyle() {
            return orderbyColumnlistStyle;
        }

        public void setOrderbyColumnlistStyle(String orderbyColumnlistStyle) {
            this.orderbyColumnlistStyle = orderbyColumnlistStyle;
        }

        public String getDefaultCommaOption() {
            return defaultCommaOption;
        }

        public void setDefaultCommaOption(String defaultCommaOption) {
            this.defaultCommaOption = defaultCommaOption;
        }

        public boolean isBeStyleBlockLeftBEOnNewline() {
            return beStyleBlockLeftBEOnNewline;
        }

        public void setBeStyleBlockLeftBEOnNewline(boolean beStyleBlockLeftBEOnNewline) {
            this.beStyleBlockLeftBEOnNewline = beStyleBlockLeftBEOnNewline;
        }

        public boolean isBeStyleBlockRightBEOnNewline() {
            return beStyleBlockRightBEOnNewline;
        }

        public void setBeStyleBlockRightBEOnNewline(boolean beStyleBlockRightBEOnNewline) {
            this.beStyleBlockRightBEOnNewline = beStyleBlockRightBEOnNewline;
        }

        public int getBeStyleBlockIndentSize() {
            return beStyleBlockIndentSize;
        }

        public void setBeStyleBlockIndentSize(int beStyleBlockIndentSize) {
            this.beStyleBlockIndentSize = beStyleBlockIndentSize;
        }

        public int getBeStyleIfWhileIndentSize() {
            return beStyleIfWhileIndentSize;
        }

        public void setBeStyleIfWhileIndentSize(int beStyleIfWhileIndentSize) {
            this.beStyleIfWhileIndentSize = beStyleIfWhileIndentSize;
        }

        public String getCompactMode() {
            return compactMode;
        }

        public void setCompactMode(String compactMode) {
            this.compactMode = compactMode;
        }

        public int getLineWidth() {
            return lineWidth;
        }

        public void setLineWidth(int lineWidth) {
            this.lineWidth = lineWidth;
        }

        public String getEmptyLines() {
            return emptyLines;
        }

        public void setEmptyLines(String emptyLines) {
            this.emptyLines = emptyLines;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class JoinConvertRequest extends AstSqlRequest {
        private String targetStyle = "ANSI";

        public String getTargetStyle() {
            return targetStyle;
        }

        public void setTargetStyle(String targetStyle) {
            this.targetStyle = targetStyle;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class RenameTableRequest extends AstSqlRequest {
        private String sourceTable;
        private String targetTable;

        public String getSourceTable() {
            return sourceTable;
        }

        public void setSourceTable(String sourceTable) {
            this.sourceTable = sourceTable;
        }

        public String getTargetTable() {
            return targetTable;
        }

        public void setTargetTable(String targetTable) {
            this.targetTable = targetTable;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class RenameColumnRequest extends AstSqlRequest {
        private String sourceColumn;
        private String targetColumn;

        public String getSourceColumn() {
            return sourceColumn;
        }

        public void setSourceColumn(String sourceColumn) {
            this.sourceColumn = sourceColumn;
        }

        public String getTargetColumn() {
            return targetColumn;
        }

        public void setTargetColumn(String targetColumn) {
            this.targetColumn = targetColumn;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class RemoveSchemaRequest extends AstSqlRequest {
        private String schemaName;

        public String getSchemaName() {
            return schemaName;
        }

        public void setSchemaName(String schemaName) {
            this.schemaName = schemaName;
        }
    }

    // -------------------------------------------------------------------------
    // Results
    // -------------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"formatted_sql", "message", "errors"})
    class FormatResult {
        @JsonProperty("formatted_sql")
        @JsonAlias({"formattedSql", "formatted_sql"})
        private String formattedSql;

        private String message;
        private List<ErrorItem> errors;

        public FormatResult() {
        }

        public FormatResult(String formattedSql, List<ErrorItem> errors) {
            this.formattedSql = formattedSql;
            this.errors = errors;
        }

        @JsonProperty("formatted_sql")
        public String getFormattedSql() {
            return formattedSql;
        }

        @JsonProperty("formatted_sql")
        @JsonAlias({"formattedSql", "formatted_sql"})
        public void setFormattedSql(String formattedSql) {
            this.formattedSql = formattedSql;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public List<ErrorItem> getErrors() {
            return errors;
        }

        public void setErrors(List<ErrorItem> errors) {
            this.errors = errors;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class TableColumnResult {
        private Map<String, List<String>> tables;
        private List<String> orphanColumns;
        private List<ErrorItem> errors;

        public TableColumnResult() {
        }

        public Map<String, List<String>> getTables() {
            return tables;
        }

        public void setTables(Map<String, List<String>> tables) {
            this.tables = tables;
        }

        public List<String> getOrphanColumns() {
            return orphanColumns;
        }

        public void setOrphanColumns(List<String> orphanColumns) {
            this.orphanColumns = orphanColumns;
        }

        public List<ErrorItem> getErrors() {
            return errors;
        }

        public void setErrors(List<ErrorItem> errors) {
            this.errors = errors;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"gsp.version", "dbVendor", "usedTime", "errorInfos", "valid", "errors"})
    class SyntaxCheckResult {
        @JsonProperty("gsp.version")
        @JsonAlias({"gspVersion", "gsp.version"})
        private String gspVersion;

        private String dbVendor;
        private String usedTime;
        private List<String> errorInfos;
        private Boolean valid;
        private List<ErrorItem> errors;

        public SyntaxCheckResult() {
        }

        public SyntaxCheckResult(Boolean valid, List<ErrorItem> errors) {
            this.valid = valid;
            this.errors = errors;
        }

        @JsonProperty("gsp.version")
        public String getGspVersion() {
            return gspVersion;
        }

        @JsonProperty("gsp.version")
        @JsonAlias({"gspVersion", "gsp.version"})
        public void setGspVersion(String gspVersion) {
            this.gspVersion = gspVersion;
        }

        public String getDbVendor() {
            return dbVendor;
        }

        public void setDbVendor(String dbVendor) {
            this.dbVendor = dbVendor;
        }

        public String getUsedTime() {
            return usedTime;
        }

        public void setUsedTime(String usedTime) {
            this.usedTime = usedTime;
        }

        public List<String> getErrorInfos() {
            return errorInfos;
        }

        public void setErrorInfos(List<String> errorInfos) {
            this.errorInfos = errorInfos;
        }

        public Boolean getValid() {
            return valid;
        }

        public void setValid(Boolean valid) {
            this.valid = valid;
        }

        @JsonIgnore
        public boolean isValid() {
            return Boolean.TRUE.equals(valid);
        }

        public List<ErrorItem> getErrors() {
            return errors;
        }

        public void setErrors(List<ErrorItem> errors) {
            this.errors = errors;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class TokenItem {
        private String text;
        private String tokenType;
        private long line;
        private long column;
        private long offset;

        public TokenItem() {
        }

        public TokenItem(String text, String tokenType, long line, long column, long offset) {
            this.text = text;
            this.tokenType = tokenType;
            this.line = line;
            this.column = column;
            this.offset = offset;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getTokenType() {
            return tokenType;
        }

        public void setTokenType(String tokenType) {
            this.tokenType = tokenType;
        }

        public long getLine() {
            return line;
        }

        public void setLine(long line) {
            this.line = line;
        }

        public long getColumn() {
            return column;
        }

        public void setColumn(long column) {
            this.column = column;
        }

        public long getOffset() {
            return offset;
        }

        public void setOffset(long offset) {
            this.offset = offset;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class TokenizeResult {
        private List<TokenItem> tokens;

        public TokenizeResult() {
        }

        public TokenizeResult(List<TokenItem> tokens) {
            this.tokens = tokens;
        }

        public List<TokenItem> getTokens() {
            return tokens;
        }

        public void setTokens(List<TokenItem> tokens) {
            this.tokens = tokens;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"type", "message", "coordinate"})
    class SqlInjectionFinding {
        private String type;

        @JsonAlias({"description", "message"})
        private String message;

        private Coordinate coordinate;

        public SqlInjectionFinding() {
        }

        public SqlInjectionFinding(String type, String message, Coordinate coordinate) {
            this.type = type;
            this.message = message;
            this.coordinate = coordinate;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getMessage() {
            return message;
        }

        @JsonAlias({"description", "message"})
        public void setMessage(String message) {
            this.message = message;
        }

        @JsonIgnore
        public String getDescription() {
            return message;
        }

        @JsonIgnore
        public void setDescription(String description) {
            this.message = description;
        }

        public Coordinate getCoordinate() {
            return coordinate;
        }

        public void setCoordinate(Coordinate coordinate) {
            this.coordinate = coordinate;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"found", "message", "errors"})
    class SqlInjectionResult {
        @JsonAlias({"injectable", "found"})
        private boolean found;

        private String message;

        @JsonAlias({"findings", "errors"})
        private List<SqlInjectionFinding> errors;

        public SqlInjectionResult() {
        }

        public SqlInjectionResult(boolean found, List<SqlInjectionFinding> errors) {
            this.found = found;
            this.errors = errors;
        }

        public SqlInjectionResult(boolean found, String message, List<SqlInjectionFinding> errors) {
            this.found = found;
            this.message = message;
            this.errors = errors;
        }

        public boolean isFound() {
            return found;
        }

        @JsonAlias({"injectable", "found"})
        public void setFound(boolean found) {
            this.found = found;
        }

        @JsonIgnore
        public boolean isInjectable() {
            return found;
        }

        @JsonIgnore
        public void setInjectable(boolean injectable) {
            this.found = injectable;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public List<SqlInjectionFinding> getErrors() {
            return errors;
        }

        @JsonAlias({"findings", "errors"})
        public void setErrors(List<SqlInjectionFinding> errors) {
            this.errors = errors;
        }

        @JsonIgnore
        public List<SqlInjectionFinding> getFindings() {
            return errors;
        }

        @JsonIgnore
        public void setFindings(List<SqlInjectionFinding> findings) {
            this.errors = findings;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class CallEdge {
        private String caller;
        private String callee;
        private String callType;
        private List<Coordinate> coordinates;

        public CallEdge() {
        }

        public String getCaller() {
            return caller;
        }

        public void setCaller(String caller) {
            this.caller = caller;
        }

        public String getCallee() {
            return callee;
        }

        public void setCallee(String callee) {
            this.callee = callee;
        }

        public String getCallType() {
            return callType;
        }

        public void setCallType(String callType) {
            this.callType = callType;
        }

        public List<Coordinate> getCoordinates() {
            return coordinates;
        }

        public void setCoordinates(List<Coordinate> coordinates) {
            this.coordinates = coordinates;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class CallRelationResult {
        private List<CallEdge> calls;
        private String xml;

        public CallRelationResult() {
        }

        public List<CallEdge> getCalls() {
            return calls;
        }

        public void setCalls(List<CallEdge> calls) {
            this.calls = calls;
        }

        public String getXml() {
            return xml;
        }

        public void setXml(String xml) {
            this.xml = xml;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class ColumnImpactResult {
        private String xml;
        private Map<String, List<String>> impacts;

        public ColumnImpactResult() {
        }

        public String getXml() {
            return xml;
        }

        public void setXml(String xml) {
            this.xml = xml;
        }

        public Map<String, List<String>> getImpacts() {
            return impacts;
        }

        public void setImpacts(Map<String, List<String>> impacts) {
            this.impacts = impacts;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class ConstantItem {
        private String value;
        private String literalType;
        private Coordinate coordinate;

        public ConstantItem() {
        }

        public ConstantItem(String value, String literalType, Coordinate coordinate) {
            this.value = value;
            this.literalType = literalType;
            this.coordinate = coordinate;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getLiteralType() {
            return literalType;
        }

        public void setLiteralType(String literalType) {
            this.literalType = literalType;
        }

        public Coordinate getCoordinate() {
            return coordinate;
        }

        public void setCoordinate(Coordinate coordinate) {
            this.coordinate = coordinate;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class FindConstantsResult {
        private List<ConstantItem> constants;

        public FindConstantsResult() {
        }

        public FindConstantsResult(List<ConstantItem> constants) {
            this.constants = constants;
        }

        public List<ConstantItem> getConstants() {
            return constants;
        }

        public void setConstants(List<ConstantItem> constants) {
            this.constants = constants;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class KeywordCheckResult {
        private boolean valid;
        private List<TokenItem> reservedKeywordsUsedAsIdentifiers;

        public KeywordCheckResult() {
        }

        public boolean isValid() {
            return valid;
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }

        public List<TokenItem> getReservedKeywordsUsedAsIdentifiers() {
            return reservedKeywordsUsedAsIdentifiers;
        }

        public void setReservedKeywordsUsedAsIdentifiers(List<TokenItem> reservedKeywordsUsedAsIdentifiers) {
            this.reservedKeywordsUsedAsIdentifiers = reservedKeywordsUsedAsIdentifiers;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class RewriteSqlResult {
        private String sql;
        private int replacements;
        private List<ErrorItem> errors;

        public RewriteSqlResult() {
        }

        public RewriteSqlResult(String sql, int replacements, List<ErrorItem> errors) {
            this.sql = sql;
            this.replacements = replacements;
            this.errors = errors;
        }

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
        }

        public int getReplacements() {
            return replacements;
        }

        public void setReplacements(int replacements) {
            this.replacements = replacements;
        }

        public List<ErrorItem> getErrors() {
            return errors;
        }

        public void setErrors(List<ErrorItem> errors) {
            this.errors = errors;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"gsp.version", "dbVendor", "usedTime", "errorInfos", "xml", "errors"})
    class XmlAstResult {
        @JsonProperty("gsp.version")
        @JsonAlias({"gspVersion", "gsp.version"})
        private String gspVersion;

        private String dbVendor;
        private String usedTime;
        private List<String> errorInfos;
        private String xml;
        private List<ErrorItem> errors;

        public XmlAstResult() {
        }

        public XmlAstResult(String xml, List<ErrorItem> errors) {
            this.xml = xml;
            this.errors = errors;
        }

        @JsonProperty("gsp.version")
        public String getGspVersion() {
            return gspVersion;
        }

        @JsonProperty("gsp.version")
        @JsonAlias({"gspVersion", "gsp.version"})
        public void setGspVersion(String gspVersion) {
            this.gspVersion = gspVersion;
        }

        public String getDbVendor() {
            return dbVendor;
        }

        public void setDbVendor(String dbVendor) {
            this.dbVendor = dbVendor;
        }

        public String getUsedTime() {
            return usedTime;
        }

        public void setUsedTime(String usedTime) {
            this.usedTime = usedTime;
        }

        public List<String> getErrorInfos() {
            return errorInfos;
        }

        public void setErrorInfos(List<String> errorInfos) {
            this.errorInfos = errorInfos;
        }

        public String getXml() {
            return xml;
        }

        public void setXml(String xml) {
            this.xml = xml;
        }

        public List<ErrorItem> getErrors() {
            return errors;
        }

        public void setErrors(List<ErrorItem> errors) {
            this.errors = errors;
        }
    }
}
