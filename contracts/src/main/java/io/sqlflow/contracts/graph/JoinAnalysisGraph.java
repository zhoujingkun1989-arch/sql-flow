package io.sqlflow.contracts.graph;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonValue;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.util.List;
import java.util.Locale;

/**
 * Unwrapped {@code JoinAnalysisGraph} ({@code schemaVersion = "join-analysis.v1"}) returned directly by
 * {@code POST /sqlflow/generation/sqlflow/join-analysis} ({@code 00 §3.3 #8}, {@code 02 §4.2.1 & §5.3},
 * {@code 03 §4}, {@code 04 §7.4}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "schemaVersion",
    "graphId",
    "sqlText",
    "dbvendor",
    "layout",
    "queryBlocks",
    "nodes",
    "edges",
    "fragments",
    "diagnostics",
    "fallback",
    "errors",
    "summary",
    "tables",
    "joins",
    "predicates",
    "keys",
    "cardinality"
})
public class JoinAnalysisGraph {

    public static final String SCHEMA_VERSION = "join-analysis.v1";

    private String schemaVersion = SCHEMA_VERSION;
    private String graphId;
    private String sqlText;
    private EDbVendor dbvendor;
    private Layout layout;
    private List<QueryBlockLane> queryBlocks;
    private List<JoinAnalysisNode> nodes;
    private List<JoinAnalysisEdge> edges;
    private List<SqlFragment> fragments;
    private List<JoinDiagnostic> diagnostics;
    private Fallback fallback;
    private List<JoinAnalysisError> errors;

    // Optional tabular summary projections (omitted when null)
    private JoinSummary summary;
    private List<TableData> tables;
    private List<JoinDetail> joins;
    private List<JoinPredicate> predicates;
    private List<ColumnRef> keys;
    private List<CardinalityItem> cardinality;

    public JoinAnalysisGraph() {
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion != null ? schemaVersion : SCHEMA_VERSION;
    }

    public String getGraphId() {
        return graphId;
    }

    public void setGraphId(String graphId) {
        this.graphId = graphId;
    }

    public String getSqlText() {
        return sqlText;
    }

    public void setSqlText(String sqlText) {
        this.sqlText = sqlText;
    }

    public EDbVendor getDbvendor() {
        return dbvendor;
    }

    public void setDbvendor(EDbVendor dbvendor) {
        this.dbvendor = dbvendor;
    }

    public Layout getLayout() {
        return layout;
    }

    public void setLayout(Layout layout) {
        this.layout = layout;
    }

    public List<QueryBlockLane> getQueryBlocks() {
        return queryBlocks;
    }

    public void setQueryBlocks(List<QueryBlockLane> queryBlocks) {
        this.queryBlocks = queryBlocks;
    }

    public List<JoinAnalysisNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<JoinAnalysisNode> nodes) {
        this.nodes = nodes;
    }

    public List<JoinAnalysisEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<JoinAnalysisEdge> edges) {
        this.edges = edges;
    }

    public List<SqlFragment> getFragments() {
        return fragments;
    }

    public void setFragments(List<SqlFragment> fragments) {
        this.fragments = fragments;
    }

    public List<JoinDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    public void setDiagnostics(List<JoinDiagnostic> diagnostics) {
        this.diagnostics = diagnostics;
    }

    public Fallback getFallback() {
        return fallback;
    }

    public void setFallback(Fallback fallback) {
        this.fallback = fallback;
    }

    public List<JoinAnalysisError> getErrors() {
        return errors;
    }

    public void setErrors(List<JoinAnalysisError> errors) {
        this.errors = errors;
    }

    public JoinSummary getSummary() {
        return summary;
    }

    public void setSummary(JoinSummary summary) {
        this.summary = summary;
    }

    public List<TableData> getTables() {
        return tables;
    }

    public void setTables(List<TableData> tables) {
        this.tables = tables;
    }

    public List<JoinDetail> getJoins() {
        return joins;
    }

    public void setJoins(List<JoinDetail> joins) {
        this.joins = joins;
    }

    public List<JoinPredicate> getPredicates() {
        return predicates;
    }

    public void setPredicates(List<JoinPredicate> predicates) {
        this.predicates = predicates;
    }

    public List<ColumnRef> getKeys() {
        return keys;
    }

    public void setKeys(List<ColumnRef> keys) {
        this.keys = keys;
    }

    public List<CardinalityItem> getCardinality() {
        return cardinality;
    }

    public void setCardinality(List<CardinalityItem> cardinality) {
        this.cardinality = cardinality;
    }

    // -------------------------------------------------------------------------
    // Core JoinAnalysis V1 Wire Records (02 §5.3)
    // -------------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"engine", "direction", "rankSpacing", "nodeSpacing"})
    public static class Layout {
        private LayoutEngine engine = LayoutEngine.ELK;
        private LayoutDirection direction = LayoutDirection.RIGHT;
        private Integer rankSpacing;
        private Integer nodeSpacing;

        public Layout() {
        }

        public Layout(LayoutEngine engine, LayoutDirection direction, Integer rankSpacing, Integer nodeSpacing) {
            this.engine = engine != null ? engine : LayoutEngine.ELK;
            this.direction = direction != null ? direction : LayoutDirection.RIGHT;
            this.rankSpacing = rankSpacing;
            this.nodeSpacing = nodeSpacing;
        }

        public LayoutEngine getEngine() {
            return engine;
        }

        public void setEngine(LayoutEngine engine) {
            this.engine = engine;
        }

        public LayoutDirection getDirection() {
            return direction;
        }

        public void setDirection(LayoutDirection direction) {
            this.direction = direction;
        }

        public Integer getRankSpacing() {
            return rankSpacing;
        }

        public void setRankSpacing(Integer rankSpacing) {
            this.rankSpacing = rankSpacing;
        }

        public Integer getNodeSpacing() {
            return nodeSpacing;
        }

        public void setNodeSpacing(Integer nodeSpacing) {
            this.nodeSpacing = nodeSpacing;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({
        "queryBlockId",
        "label",
        "kind",
        "parentQueryBlockId",
        "sourceRange",
        "collapsedByDefault"
    })
    public static class QueryBlockLane {
        private String queryBlockId;
        private String label;
        private LaneKind kind;
        private String parentQueryBlockId;
        private SourceRange sourceRange;
        private Boolean collapsedByDefault;

        public QueryBlockLane() {
        }

        public String getQueryBlockId() {
            return queryBlockId;
        }

        public void setQueryBlockId(String queryBlockId) {
            this.queryBlockId = queryBlockId;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public LaneKind getKind() {
            return kind;
        }

        public void setKind(LaneKind kind) {
            this.kind = kind;
        }

        public String getParentQueryBlockId() {
            return parentQueryBlockId;
        }

        public void setParentQueryBlockId(String parentQueryBlockId) {
            this.parentQueryBlockId = parentQueryBlockId;
        }

        public SourceRange getSourceRange() {
            return sourceRange;
        }

        public void setSourceRange(SourceRange sourceRange) {
            this.sourceRange = sourceRange;
        }

        public Boolean getCollapsedByDefault() {
            return collapsedByDefault;
        }

        public void setCollapsedByDefault(Boolean collapsedByDefault) {
            this.collapsedByDefault = collapsedByDefault;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"id", "type", "position", "data"})
    public static class JoinAnalysisNode {
        private String id;
        private JoinNodeType type;
        private Position position;
        private NodeData data;

        public JoinAnalysisNode() {
        }

        public JoinAnalysisNode(String id, JoinNodeType type, Position position, NodeData data) {
            this.id = id;
            this.type = type;
            this.position = position;
            this.data = data;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public JoinNodeType getType() {
            return type;
        }

        public void setType(JoinNodeType type) {
            this.type = type;
        }

        public Position getPosition() {
            return position;
        }

        public void setPosition(Position position) {
            this.position = position;
        }

        public NodeData getData() {
            return data;
        }

        public void setData(NodeData data) {
            this.data = data;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"x", "y"})
    public static class Position {
        private double x;
        private double y;

        public Position() {
        }

        public Position(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() {
            return x;
        }

        public void setX(double x) {
            this.x = x;
        }

        public double getY() {
            return y;
        }

        public void setY(double y) {
            this.y = y;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({
        "label",
        "summary",
        "queryBlockId",
        "fragmentId",
        "join",
        "table",
        "badges",
        "details"
    })
    public static class NodeData {
        private String label;
        private String summary;
        private String queryBlockId;
        private String fragmentId;
        private JoinDetail join;
        private TableData table;
        private List<JoinBadge> badges;
        private Object details;

        public NodeData() {
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public String getQueryBlockId() {
            return queryBlockId;
        }

        public void setQueryBlockId(String queryBlockId) {
            this.queryBlockId = queryBlockId;
        }

        public String getFragmentId() {
            return fragmentId;
        }

        public void setFragmentId(String fragmentId) {
            this.fragmentId = fragmentId;
        }

        public JoinDetail getJoin() {
            return join;
        }

        public void setJoin(JoinDetail join) {
            this.join = join;
        }

        public TableData getTable() {
            return table;
        }

        public void setTable(TableData table) {
            this.table = table;
        }

        public List<JoinBadge> getBadges() {
            return badges;
        }

        public void setBadges(List<JoinBadge> badges) {
            this.badges = badges;
        }

        public Object getDetails() {
            return details;
        }

        public void setDetails(Object details) {
            this.details = details;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({
        "tableId",
        "database",
        "schema",
        "name",
        "alias",
        "columnsContributed",
        "isSelfJoinInstance"
    })
    public static class TableData {
        private String tableId;
        private String database;
        private String schema;
        private String name;
        private String alias;
        private List<ColumnContribution> columnsContributed;

        @JsonProperty("isSelfJoinInstance")
        @JsonAlias({"isSelfJoinInstance", "selfJoinInstance"})
        private Boolean isSelfJoinInstance;

        public TableData() {
        }

        public String getTableId() {
            return tableId;
        }

        public void setTableId(String tableId) {
            this.tableId = tableId;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAlias() {
            return alias;
        }

        public void setAlias(String alias) {
            this.alias = alias;
        }

        public List<ColumnContribution> getColumnsContributed() {
            return columnsContributed;
        }

        public void setColumnsContributed(List<ColumnContribution> columnsContributed) {
            this.columnsContributed = columnsContributed;
        }

        @JsonProperty("isSelfJoinInstance")
        public Boolean getIsSelfJoinInstance() {
            return isSelfJoinInstance;
        }

        @JsonProperty("isSelfJoinInstance")
        @JsonAlias({"isSelfJoinInstance", "selfJoinInstance"})
        public void setIsSelfJoinInstance(Boolean isSelfJoinInstance) {
            this.isSelfJoinInstance = isSelfJoinInstance;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"table", "column"})
    public static class ColumnContribution {
        private String table;
        private String column;

        public ColumnContribution() {
        }

        public ColumnContribution(String table, String column) {
            this.table = table;
            this.column = column;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }

        public String getColumn() {
            return column;
        }

        public void setColumn(String column) {
            this.column = column;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({
        "joinType",
        "patterns",
        "clause",
        "predicates",
        "order",
        "cardinalityHint",
        "sourceRange"
    })
    public static class JoinDetail {
        private JoinType joinType;
        private List<JoinPattern> patterns;

        @JsonProperty("clause")
        @JsonAlias({"clause", "joinClause"})
        private JoinClause clause;

        private List<JoinPredicate> predicates;
        private Integer order;
        private CardinalityHint cardinalityHint;
        private SourceRange sourceRange;

        public JoinDetail() {
        }

        public JoinType getJoinType() {
            return joinType;
        }

        public void setJoinType(JoinType joinType) {
            this.joinType = joinType;
        }

        public List<JoinPattern> getPatterns() {
            return patterns;
        }

        public void setPatterns(List<JoinPattern> patterns) {
            this.patterns = patterns;
        }

        @JsonProperty("clause")
        public JoinClause getClause() {
            return clause;
        }

        @JsonProperty("clause")
        @JsonAlias({"clause", "joinClause"})
        public void setClause(JoinClause clause) {
            this.clause = clause;
        }

        public List<JoinPredicate> getPredicates() {
            return predicates;
        }

        public void setPredicates(List<JoinPredicate> predicates) {
            this.predicates = predicates;
        }

        public Integer getOrder() {
            return order;
        }

        public void setOrder(Integer order) {
            this.order = order;
        }

        public CardinalityHint getCardinalityHint() {
            return cardinalityHint;
        }

        public void setCardinalityHint(CardinalityHint cardinalityHint) {
            this.cardinalityHint = cardinalityHint;
        }

        public SourceRange getSourceRange() {
            return sourceRange;
        }

        public void setSourceRange(SourceRange sourceRange) {
            this.sourceRange = sourceRange;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"predicateId", "raw", "kind", "op", "left", "right", "sourceRange"})
    public static class JoinPredicate {
        private String predicateId;
        private String raw;
        private PredicateKind kind;
        private String op;
        private ColumnRef left;
        private ColumnRef right;
        private SourceRange sourceRange;

        public JoinPredicate() {
        }

        public String getPredicateId() {
            return predicateId;
        }

        public void setPredicateId(String predicateId) {
            this.predicateId = predicateId;
        }

        public String getRaw() {
            return raw;
        }

        public void setRaw(String raw) {
            this.raw = raw;
        }

        public PredicateKind getKind() {
            return kind;
        }

        public void setKind(PredicateKind kind) {
            this.kind = kind;
        }

        public String getOp() {
            return op;
        }

        public void setOp(String op) {
            this.op = op;
        }

        public ColumnRef getLeft() {
            return left;
        }

        public void setLeft(ColumnRef left) {
            this.left = left;
        }

        public ColumnRef getRight() {
            return right;
        }

        public void setRight(ColumnRef right) {
            this.right = right;
        }

        public SourceRange getSourceRange() {
            return sourceRange;
        }

        public void setSourceRange(SourceRange sourceRange) {
            this.sourceRange = sourceRange;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({
        "tableId",
        "tableName",
        "alias",
        "column",
        "displayName",
        "sourceRange",
        "isPrimaryKey",
        "isUniqueKey",
        "isIndexed"
    })
    public static class ColumnRef {
        private String tableId;
        private String tableName;
        private String alias;
        private String column;
        private String displayName;
        private SourceRange sourceRange;

        @JsonProperty("isPrimaryKey")
        @JsonAlias({"isPrimaryKey", "primaryKey"})
        private Boolean isPrimaryKey;

        @JsonProperty("isUniqueKey")
        @JsonAlias({"isUniqueKey", "uniqueKey"})
        private Boolean isUniqueKey;

        @JsonProperty("isIndexed")
        @JsonAlias({"isIndexed", "indexed"})
        private Boolean isIndexed;

        public ColumnRef() {
        }

        public String getTableId() {
            return tableId;
        }

        public void setTableId(String tableId) {
            this.tableId = tableId;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public String getAlias() {
            return alias;
        }

        public void setAlias(String alias) {
            this.alias = alias;
        }

        public String getColumn() {
            return column;
        }

        public void setColumn(String column) {
            this.column = column;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public SourceRange getSourceRange() {
            return sourceRange;
        }

        public void setSourceRange(SourceRange sourceRange) {
            this.sourceRange = sourceRange;
        }

        @JsonProperty("isPrimaryKey")
        public Boolean getIsPrimaryKey() {
            return isPrimaryKey;
        }

        @JsonProperty("isPrimaryKey")
        @JsonAlias({"isPrimaryKey", "primaryKey"})
        public void setIsPrimaryKey(Boolean isPrimaryKey) {
            this.isPrimaryKey = isPrimaryKey;
        }

        @JsonProperty("isUniqueKey")
        public Boolean getIsUniqueKey() {
            return isUniqueKey;
        }

        @JsonProperty("isUniqueKey")
        @JsonAlias({"isUniqueKey", "uniqueKey"})
        public void setIsUniqueKey(Boolean isUniqueKey) {
            this.isUniqueKey = isUniqueKey;
        }

        @JsonProperty("isIndexed")
        public Boolean getIsIndexed() {
            return isIndexed;
        }

        @JsonProperty("isIndexed")
        @JsonAlias({"isIndexed", "indexed"})
        public void setIsIndexed(Boolean isIndexed) {
            this.isIndexed = isIndexed;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"kind", "level", "heuristic"})
    public static class JoinBadge {
        private BadgeKind kind;
        private FanOutLevel level;
        private Boolean heuristic;

        public JoinBadge() {
        }

        public JoinBadge(BadgeKind kind, FanOutLevel level, Boolean heuristic) {
            this.kind = kind;
            this.level = level;
            this.heuristic = heuristic;
        }

        public BadgeKind getKind() {
            return kind;
        }

        public void setKind(BadgeKind kind) {
            this.kind = kind;
        }

        public FanOutLevel getLevel() {
            return level;
        }

        public void setLevel(FanOutLevel level) {
            this.level = level;
        }

        public Boolean getHeuristic() {
            return heuristic;
        }

        public void setHeuristic(Boolean heuristic) {
            this.heuristic = heuristic;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"id", "source", "target", "data"})
    public static class JoinAnalysisEdge {
        private String id;
        private String source;
        private String target;
        private EdgeData data;

        public JoinAnalysisEdge() {
        }

        public JoinAnalysisEdge(String id, String source, String target, EdgeData data) {
            this.id = id;
            this.source = source;
            this.target = target;
            this.data = data;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public EdgeData getData() {
            return data;
        }

        public void setData(EdgeData data) {
            this.data = data;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"kind", "columns", "note", "dashed", "animated"})
    public static class EdgeData {
        private JoinEdgeKind kind;
        private List<ColumnContribution> columns;
        private String note;
        private Boolean dashed;
        private Boolean animated;

        public EdgeData() {
        }

        public JoinEdgeKind getKind() {
            return kind;
        }

        public void setKind(JoinEdgeKind kind) {
            this.kind = kind;
        }

        public List<ColumnContribution> getColumns() {
            return columns;
        }

        public void setColumns(List<ColumnContribution> columns) {
            this.columns = columns;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public Boolean getDashed() {
            return dashed;
        }

        public void setDashed(Boolean dashed) {
            this.dashed = dashed;
        }

        public Boolean getAnimated() {
            return animated;
        }

        public void setAnimated(Boolean animated) {
            this.animated = animated;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"fragmentId", "nodeIds", "kind", "text", "sourceRange"})
    public static class SqlFragment {
        private String fragmentId;
        private List<String> nodeIds;
        private FragmentKind kind;
        private String text;
        private SourceRange sourceRange;

        public SqlFragment() {
        }

        public String getFragmentId() {
            return fragmentId;
        }

        public void setFragmentId(String fragmentId) {
            this.fragmentId = fragmentId;
        }

        public List<String> getNodeIds() {
            return nodeIds;
        }

        public void setNodeIds(List<String> nodeIds) {
            this.nodeIds = nodeIds;
        }

        public FragmentKind getKind() {
            return kind;
        }

        public void setKind(FragmentKind kind) {
            this.kind = kind;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public SourceRange getSourceRange() {
            return sourceRange;
        }

        public void setSourceRange(SourceRange sourceRange) {
            this.sourceRange = sourceRange;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"startLine", "startColumn", "endLine", "endColumn", "quality"})
    public static class SourceRange {
        private int startLine;
        private int startColumn;
        private int endLine;
        private int endColumn;
        private SourceRangeQuality quality;

        public SourceRange() {
        }

        public SourceRange(
                int startLine, int startColumn, int endLine, int endColumn, SourceRangeQuality quality) {
            this.startLine = startLine;
            this.startColumn = startColumn;
            this.endLine = endLine;
            this.endColumn = endColumn;
            this.quality = quality;
        }

        public int getStartLine() {
            return startLine;
        }

        public void setStartLine(int startLine) {
            this.startLine = startLine;
        }

        public int getStartColumn() {
            return startColumn;
        }

        public void setStartColumn(int startColumn) {
            this.startColumn = startColumn;
        }

        public int getEndLine() {
            return endLine;
        }

        public void setEndLine(int endLine) {
            this.endLine = endLine;
        }

        public int getEndColumn() {
            return endColumn;
        }

        public void setEndColumn(int endColumn) {
            this.endColumn = endColumn;
        }

        public SourceRangeQuality getQuality() {
            return quality;
        }

        public void setQuality(SourceRangeQuality quality) {
            this.quality = quality;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({
        "id",
        "severity",
        "roles",
        "code",
        "message",
        "evidenceNodeIds",
        "fragmentIds",
        "predicateIds",
        "edgeIds"
    })
    public static class JoinDiagnostic {
        private String id;
        private Severity severity;
        private List<Role> roles;
        private DiagnosticCode code;
        private String message;
        private List<String> evidenceNodeIds;
        private List<String> fragmentIds;
        private List<String> predicateIds;
        private List<String> edgeIds;

        public JoinDiagnostic() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Severity getSeverity() {
            return severity;
        }

        public void setSeverity(Severity severity) {
            this.severity = severity;
        }

        public List<Role> getRoles() {
            return roles;
        }

        public void setRoles(List<Role> roles) {
            this.roles = roles;
        }

        public DiagnosticCode getCode() {
            return code;
        }

        public void setCode(DiagnosticCode code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public List<String> getEvidenceNodeIds() {
            return evidenceNodeIds;
        }

        public void setEvidenceNodeIds(List<String> evidenceNodeIds) {
            this.evidenceNodeIds = evidenceNodeIds;
        }

        public List<String> getFragmentIds() {
            return fragmentIds;
        }

        public void setFragmentIds(List<String> fragmentIds) {
            this.fragmentIds = fragmentIds;
        }

        public List<String> getPredicateIds() {
            return predicateIds;
        }

        public void setPredicateIds(List<String> predicateIds) {
            this.predicateIds = predicateIds;
        }

        public List<String> getEdgeIds() {
            return edgeIds;
        }

        public void setEdgeIds(List<String> edgeIds) {
            this.edgeIds = edgeIds;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"source", "limitations"})
    public static class Fallback {
        private FallbackSource source;
        private List<String> limitations;

        public Fallback() {
        }

        public Fallback(FallbackSource source, List<String> limitations) {
            this.source = source;
            this.limitations = limitations;
        }

        public FallbackSource getSource() {
            return source;
        }

        public void setSource(FallbackSource source) {
            this.source = source;
        }

        public List<String> getLimitations() {
            return limitations;
        }

        public void setLimitations(List<String> limitations) {
            this.limitations = limitations;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"code", "message", "errorType", "errorMessage", "sourceRange"})
    public static class JoinAnalysisError {
        private String code;
        private String message;
        private String errorType;
        private String errorMessage;
        private SourceRange sourceRange;

        public JoinAnalysisError() {
        }

        public JoinAnalysisError(String code, String message, SourceRange sourceRange) {
            this.code = code;
            this.message = message;
            this.sourceRange = sourceRange;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getErrorType() {
            return errorType;
        }

        public void setErrorType(String errorType) {
            this.errorType = errorType;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public SourceRange getSourceRange() {
            return sourceRange;
        }

        public void setSourceRange(SourceRange sourceRange) {
            this.sourceRange = sourceRange;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JoinSummary {
        private int tableCount;
        private int joinCount;
        private int predicateCount;
        private int diagnosticCount;
        private int blockCount;
        private int warnCount;
        private int infoCount;

        public JoinSummary() {
        }

        public int getTableCount() {
            return tableCount;
        }

        public void setTableCount(int tableCount) {
            this.tableCount = tableCount;
        }

        public int getJoinCount() {
            return joinCount;
        }

        public void setJoinCount(int joinCount) {
            this.joinCount = joinCount;
        }

        public int getPredicateCount() {
            return predicateCount;
        }

        public void setPredicateCount(int predicateCount) {
            this.predicateCount = predicateCount;
        }

        public int getDiagnosticCount() {
            return diagnosticCount;
        }

        public void setDiagnosticCount(int diagnosticCount) {
            this.diagnosticCount = diagnosticCount;
        }

        public int getBlockCount() {
            return blockCount;
        }

        public void setBlockCount(int blockCount) {
            this.blockCount = blockCount;
        }

        public int getWarnCount() {
            return warnCount;
        }

        public void setWarnCount(int warnCount) {
            this.warnCount = warnCount;
        }

        public int getInfoCount() {
            return infoCount;
        }

        public void setInfoCount(int infoCount) {
            this.infoCount = infoCount;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CardinalityItem {
        private String joinId;
        private CardinalityHint cardinality;
        private FanOutLevel fanOutRisk;
        private String reason;

        public CardinalityItem() {
        }

        public String getJoinId() {
            return joinId;
        }

        public void setJoinId(String joinId) {
            this.joinId = joinId;
        }

        public CardinalityHint getCardinality() {
            return cardinality;
        }

        public void setCardinality(CardinalityHint cardinality) {
            this.cardinality = cardinality;
        }

        public FanOutLevel getFanOutRisk() {
            return fanOutRisk;
        }

        public void setFanOutRisk(FanOutLevel fanOutRisk) {
            this.fanOutRisk = fanOutRisk;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    // -------------------------------------------------------------------------
    // Wire-Exact Enums (02 §5.3)
    // -------------------------------------------------------------------------

    public enum LayoutEngine {
        ELK("elk");
        private final String wire;

        LayoutEngine(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static LayoutEngine fromWire(String raw) {
            return parseEnum(LayoutEngine.class, raw, v -> v.wire);
        }
    }

    public enum LayoutDirection {
        RIGHT("RIGHT"),
        DOWN("DOWN");
        private final String wire;

        LayoutDirection(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static LayoutDirection fromWire(String raw) {
            return parseEnum(LayoutDirection.class, raw, v -> v.wire);
        }
    }

    public enum LaneKind {
        MAIN("main"),
        CTE("cte"),
        SUBQUERY("subquery");
        private final String wire;

        LaneKind(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static LaneKind fromWire(String raw) {
            return parseEnum(LaneKind.class, raw, v -> v.wire);
        }
    }

    public enum JoinNodeType {
        SOURCE("source"),
        TABLE("table"),
        JOIN("join"),
        FILTER("filter"),
        CTE("cte"),
        SUBQUERY("subquery"),
        OUTPUT("output");
        private final String wire;

        JoinNodeType(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static JoinNodeType fromWire(String raw) {
            return parseEnum(JoinNodeType.class, raw, v -> v.wire);
        }
    }

    public enum JoinEdgeKind {
        DATA_FLOW("data_flow"),
        LINEAGE("lineage"),
        CORRELATION("correlation"),
        ANTI_JOIN_EVIDENCE("anti_join_evidence"),
        TABLE_TO_JOIN("table_to_join"),
        JOIN_TO_JOIN("join_to_join"),
        JOIN_TO_OUTPUT("join_to_output");
        private final String wire;

        JoinEdgeKind(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static JoinEdgeKind fromWire(String raw) {
            return parseEnum(JoinEdgeKind.class, raw, v -> v.wire);
        }
    }

    public enum JoinType {
        INNER("INNER"),
        LEFT("LEFT"),
        RIGHT("RIGHT"),
        FULL("FULL"),
        CROSS("CROSS"),
        UNKNOWN("UNKNOWN");
        private final String wire;

        JoinType(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static JoinType fromWire(String raw) {
            return parseEnum(JoinType.class, raw, v -> v.wire);
        }
    }

    public enum JoinClause {
        ON("ON"),
        USING("USING"),
        NATURAL("NATURAL"),
        NONE("NONE"),
        UNKNOWN("UNKNOWN");
        private final String wire;

        JoinClause(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static JoinClause fromWire(String raw) {
            return parseEnum(JoinClause.class, raw, v -> v.wire);
        }
    }

    public enum JoinPattern {
        SELF("self"),
        ANTI("anti"),
        SEMI("semi"),
        CARTESIAN("cartesian");
        private final String wire;

        JoinPattern(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static JoinPattern fromWire(String raw) {
            return parseEnum(JoinPattern.class, raw, v -> v.wire);
        }
    }

    public enum PredicateKind {
        EQUI("equi"),
        NON_EQUI("non_equi"),
        RANGE("range"),
        NULL_CHECK("null_check"),
        CONSTANT("constant"),
        COMPLEX("complex"),
        UNKNOWN("unknown");
        private final String wire;

        PredicateKind(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static PredicateKind fromWire(String raw) {
            return parseEnum(PredicateKind.class, raw, v -> v.wire);
        }
    }

    public enum CardinalityHint {
        ONE_TO_ONE("one_to_one"),
        ONE_TO_MANY("one_to_many"),
        MANY_TO_ONE("many_to_one"),
        MANY_TO_MANY("many_to_many"),
        UNKNOWN("unknown");
        private final String wire;

        CardinalityHint(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static CardinalityHint fromWire(String raw) {
            return parseEnum(CardinalityHint.class, raw, v -> v.wire);
        }
    }

    public enum BadgeKind {
        ANTI_JOIN("anti_join"),
        SEMI_JOIN("semi_join"),
        SELF_JOIN("self_join"),
        CROSS_JOIN("cross_join"),
        CARTESIAN_RISK("cartesian_risk"),
        FAN_OUT("fan_out");
        private final String wire;

        BadgeKind(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static BadgeKind fromWire(String raw) {
            return parseEnum(BadgeKind.class, raw, v -> v.wire);
        }
    }

    public enum FanOutLevel {
        LOW("low"),
        MEDIUM("medium"),
        HIGH("high");
        private final String wire;

        FanOutLevel(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static FanOutLevel fromWire(String raw) {
            return parseEnum(FanOutLevel.class, raw, v -> v.wire);
        }
    }

    public enum FragmentKind {
        QUERY_BLOCK("query_block"),
        TABLE_REF("table_ref"),
        JOIN_CLAUSE("join_clause"),
        JOIN_CONDITION("join_condition"),
        JOIN_PREDICATE("join_predicate"),
        WHERE_FILTER("where_filter"),
        OUTPUT_COLUMN("output_column");
        private final String wire;

        FragmentKind(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static FragmentKind fromWire(String raw) {
            return parseEnum(FragmentKind.class, raw, v -> v.wire);
        }
    }

    public enum SourceRangeQuality {
        EXACT("exact"),
        APPROXIMATE("approximate"),
        COORDINATE_FALLBACK("coordinate_fallback"),
        MISSING("missing");
        private final String wire;

        SourceRangeQuality(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static SourceRangeQuality fromWire(String raw) {
            return parseEnum(SourceRangeQuality.class, raw, v -> v.wire);
        }
    }

    public enum Severity {
        INFO("info"),
        WARN("warn"),
        BLOCK("block");
        private final String wire;

        Severity(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static Severity fromWire(String raw) {
            return parseEnum(Severity.class, raw, v -> v.wire);
        }
    }

    public enum Role {
        DATA_ENGINEER("data_engineer"),
        DATA_ANALYST("data_analyst"),
        DBA("dba"),
        ALL("all");
        private final String wire;

        Role(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static Role fromWire(String raw) {
            return parseEnum(Role.class, raw, v -> v.wire);
        }
    }

    public enum FallbackSource {
        LEGACY_GRAPH("legacy_graph"),
        BACKEND_DEGRADED("backend_degraded");
        private final String wire;

        FallbackSource(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String getWire() {
            return wire;
        }

        @JsonCreator
        public static FallbackSource fromWire(String raw) {
            return parseEnum(FallbackSource.class, raw, v -> v.wire);
        }
    }

    @FunctionalInterface
    private interface WireAccessor<E extends Enum<E>> {
        String wire(E e);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String raw, WireAccessor<E> accessor) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        for (E constant : enumType.getEnumConstants()) {
            if (accessor.wire(constant).equalsIgnoreCase(trimmed)
                    || constant.name().toLowerCase(Locale.ROOT).equals(trimmed.toLowerCase(Locale.ROOT))) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown " + enumType.getSimpleName() + ": " + raw);
    }
}
