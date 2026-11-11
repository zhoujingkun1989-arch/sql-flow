package io.sqlflow.contracts.graph;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.Orientation;
import java.util.List;
import java.util.Map;

/**
 * Laid-out SQLFlow graph payload ({@code SqlflowJSON}) returned by {@code /sqlflow/generation/sqlflow/graph}
 * and related graph endpoints ({@code 03 §1.5.3}, {@code 00 §3.3 #7}).
 *
 * <p>Supports both nested {@code graph} object payloads and flat top-level {@code elements}, {@code width},
 * {@code height}, {@code orientation}, {@code relationshipIdMap}, {@code listIdMap}, {@code tooltip},
 * {@code svg}, and {@code graphML} properties, plus summary/degradation {@code mode} and share metadata.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "mode",
    "summary",
    "sqlflow",
    "dbObjIds",
    "graph",
    "elements",
    "width",
    "height",
    "orientation",
    "relationshipIdMap",
    "listIdMap",
    "tooltip",
    "svg",
    "graphML",
    "shareId",
    "tableLevel",
    "userId",
    "database",
    "schema",
    "table",
    "column",
    "dbobj",
    "createTime",
    "spendTime"
})
public class SqlflowJSON {

    private String mode;
    private SummaryInfo summary;
    private DataFlow sqlflow;
    private List<String> dbObjIds;
    private Graph graph;

    // Flat top-level layout fields (also mirrored inside Graph when wrapped)
    private GraphElements elements;
    private Double width;
    private Double height;
    private Orientation orientation;
    private Map<String, List<String>> relationshipIdMap;
    private Map<String, List<String>> listIdMap;
    private Map<String, String> tooltip;
    private String svg;
    private String graphML;

    // Share & selection metadata
    private String shareId;
    private Boolean tableLevel;
    private String userId;
    private String database;
    private String schema;
    private String table;
    private String column;
    private String dbobj;
    private String createTime;
    private Long spendTime;

    public SqlflowJSON() {
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public SummaryInfo getSummary() {
        return summary;
    }

    public void setSummary(SummaryInfo summary) {
        this.summary = summary;
    }

    public String getShareId() {
        return shareId;
    }

    public void setShareId(String shareId) {
        this.shareId = shareId;
    }

    public Boolean getTableLevel() {
        return tableLevel;
    }

    public void setTableLevel(Boolean tableLevel) {
        this.tableLevel = tableLevel;
    }

    public DataFlow getSqlflow() {
        return sqlflow;
    }

    public void setSqlflow(DataFlow sqlflow) {
        this.sqlflow = sqlflow;
    }

    public List<String> getDbObjIds() {
        return dbObjIds;
    }

    public void setDbObjIds(List<String> dbObjIds) {
        this.dbObjIds = dbObjIds;
    }

    public Graph getGraph() {
        return graph;
    }

    public void setGraph(Graph graph) {
        this.graph = graph;
    }

    public GraphElements getElements() {
        return elements;
    }

    public void setElements(GraphElements elements) {
        this.elements = elements;
    }

    public Double getWidth() {
        return width;
    }

    public void setWidth(Double width) {
        this.width = width;
    }

    public Double getHeight() {
        return height;
    }

    public void setHeight(Double height) {
        this.height = height;
    }

    public Orientation getOrientation() {
        return orientation;
    }

    public void setOrientation(Orientation orientation) {
        this.orientation = orientation;
    }

    public Map<String, List<String>> getRelationshipIdMap() {
        return relationshipIdMap;
    }

    public void setRelationshipIdMap(Map<String, List<String>> relationshipIdMap) {
        this.relationshipIdMap = relationshipIdMap;
    }

    public Map<String, List<String>> getListIdMap() {
        return listIdMap;
    }

    public void setListIdMap(Map<String, List<String>> listIdMap) {
        this.listIdMap = listIdMap;
    }

    public Map<String, String> getTooltip() {
        return tooltip;
    }

    public void setTooltip(Map<String, String> tooltip) {
        this.tooltip = tooltip;
    }

    public String getSvg() {
        return svg;
    }

    public void setSvg(String svg) {
        this.svg = svg;
    }

    public String getGraphML() {
        return graphML;
    }

    public void setGraphML(String graphML) {
        this.graphML = graphML;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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

    public String getDbobj() {
        return dbobj;
    }

    public void setDbobj(String dbobj) {
        this.dbobj = dbobj;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public Long getSpendTime() {
        return spendTime;
    }

    public void setSpendTime(Long spendTime) {
        this.spendTime = spendTime;
    }

    /**
     * Resolves {@link GraphElements} whether populated at top-level or nested under {@link #getGraph()}.
     */
    @JsonIgnore
    public GraphElements resolveElements() {
        if (elements != null) {
            return elements;
        }
        return graph != null ? graph.getElements() : null;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Graph {
        private GraphElements elements;
        private Double width;
        private Double height;
        private Orientation orientation;
        private Map<String, List<String>> relationshipIdMap;
        private Map<String, List<String>> listIdMap;
        private Map<String, String> tooltip;
        private String svg;
        private String graphML;

        public Graph() {
        }

        public GraphElements getElements() {
            return elements;
        }

        public void setElements(GraphElements elements) {
            this.elements = elements;
        }

        public Double getWidth() {
            return width;
        }

        public void setWidth(Double width) {
            this.width = width;
        }

        public Double getHeight() {
            return height;
        }

        public void setHeight(Double height) {
            this.height = height;
        }

        public Orientation getOrientation() {
            return orientation;
        }

        public void setOrientation(Orientation orientation) {
            this.orientation = orientation;
        }

        public Map<String, List<String>> getRelationshipIdMap() {
            return relationshipIdMap;
        }

        public void setRelationshipIdMap(Map<String, List<String>> relationshipIdMap) {
            this.relationshipIdMap = relationshipIdMap;
        }

        public Map<String, List<String>> getListIdMap() {
            return listIdMap;
        }

        public void setListIdMap(Map<String, List<String>> listIdMap) {
            this.listIdMap = listIdMap;
        }

        public Map<String, String> getTooltip() {
            return tooltip;
        }

        public void setTooltip(Map<String, String> tooltip) {
            this.tooltip = tooltip;
        }

        public String getSvg() {
            return svg;
        }

        public void setSvg(String svg) {
            this.svg = svg;
        }

        public String getGraphML() {
            return graphML;
        }

        public void setGraphML(String graphML) {
            this.graphML = graphML;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GraphElements {
        private List<GraphTable> tables;
        private List<GraphEdge> edges;
        private List<GraphPackage> packages;

        public GraphElements() {
        }

        public GraphElements(List<GraphTable> tables, List<GraphEdge> edges, List<GraphPackage> packages) {
            this.tables = tables;
            this.edges = edges;
            this.packages = packages;
        }

        public List<GraphTable> getTables() {
            return tables;
        }

        public void setTables(List<GraphTable> tables) {
            this.tables = tables;
        }

        public List<GraphEdge> getEdges() {
            return edges;
        }

        public void setEdges(List<GraphEdge> edges) {
            this.edges = edges;
        }

        public List<GraphPackage> getPackages() {
            return packages;
        }

        public void setPackages(List<GraphPackage> packages) {
            this.packages = packages;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GraphTable {
        private String id;
        private String name;
        private String displayName;
        private String alias;
        private String type;
        private String subType;
        private String server;
        private String database;
        private String schema;
        private String parent;
        private double x;
        private double y;
        private double width;
        private double height;
        private GraphLabel label;
        private List<GraphColumn> columns;
        private Boolean more;

        public GraphTable() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getAlias() {
            return alias;
        }

        public void setAlias(String alias) {
            this.alias = alias;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getSubType() {
            return subType;
        }

        public void setSubType(String subType) {
            this.subType = subType;
        }

        public String getServer() {
            return server;
        }

        public void setServer(String server) {
            this.server = server;
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

        public String getParent() {
            return parent;
        }

        public void setParent(String parent) {
            this.parent = parent;
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

        public double getWidth() {
            return width;
        }

        public void setWidth(double width) {
            this.width = width;
        }

        public double getHeight() {
            return height;
        }

        public void setHeight(double height) {
            this.height = height;
        }

        public GraphLabel getLabel() {
            return label;
        }

        public void setLabel(GraphLabel label) {
            this.label = label;
        }

        public List<GraphColumn> getColumns() {
            return columns;
        }

        public void setColumns(List<GraphColumn> columns) {
            this.columns = columns;
        }

        public Boolean getMore() {
            return more;
        }

        public void setMore(Boolean more) {
            this.more = more;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GraphColumn {
        private String id;
        private String name;
        private String displayName;
        private String dataType;
        private double x;
        private double y;
        private double width;
        private double height;
        private GraphLabel label;
        private Boolean primaryKey;

        @JsonProperty("unqiueKey")
        @JsonAlias({"uniqueKey", "unqiueKey"})
        private Boolean unqiueKey;

        private Boolean indexKey;
        private Boolean foreignKey;
        private Boolean nullable;
        private Boolean star;

        public GraphColumn() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType;
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

        public double getWidth() {
            return width;
        }

        public void setWidth(double width) {
            this.width = width;
        }

        public double getHeight() {
            return height;
        }

        public void setHeight(double height) {
            this.height = height;
        }

        public GraphLabel getLabel() {
            return label;
        }

        public void setLabel(GraphLabel label) {
            this.label = label;
        }

        public Boolean getPrimaryKey() {
            return primaryKey;
        }

        public void setPrimaryKey(Boolean primaryKey) {
            this.primaryKey = primaryKey;
        }

        @JsonProperty("unqiueKey")
        public Boolean getUnqiueKey() {
            return unqiueKey;
        }

        @JsonProperty("unqiueKey")
        @JsonAlias({"uniqueKey", "unqiueKey"})
        public void setUnqiueKey(Boolean unqiueKey) {
            this.unqiueKey = unqiueKey;
        }

        @JsonIgnore
        public Boolean getUniqueKey() {
            return unqiueKey;
        }

        @JsonIgnore
        public void setUniqueKey(Boolean uniqueKey) {
            this.unqiueKey = uniqueKey;
        }

        public Boolean getIndexKey() {
            return indexKey;
        }

        public void setIndexKey(Boolean indexKey) {
            this.indexKey = indexKey;
        }

        public Boolean getForeignKey() {
            return foreignKey;
        }

        public void setForeignKey(Boolean foreignKey) {
            this.foreignKey = foreignKey;
        }

        public Boolean getNullable() {
            return nullable;
        }

        public void setNullable(Boolean nullable) {
            this.nullable = nullable;
        }

        public Boolean getStar() {
            return star;
        }

        public void setStar(Boolean star) {
            this.star = star;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GraphPackage {
        private String id;
        private String name;
        private String type;
        private String server;
        private String database;
        private String schema;
        private double x;
        private double y;
        private double width;
        private double height;
        private GraphLabel label;
        private List<GraphTable> tables;

        public GraphPackage() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getServer() {
            return server;
        }

        public void setServer(String server) {
            this.server = server;
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

        public double getWidth() {
            return width;
        }

        public void setWidth(double width) {
            this.width = width;
        }

        public double getHeight() {
            return height;
        }

        public void setHeight(double height) {
            this.height = height;
        }

        public GraphLabel getLabel() {
            return label;
        }

        public void setLabel(GraphLabel label) {
            this.label = label;
        }

        public List<GraphTable> getTables() {
            return tables;
        }

        public void setTables(List<GraphTable> tables) {
            this.tables = tables;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GraphEdge {
        private String id;
        private String sourceId;
        private String targetId;
        private String relationId;
        private String type;
        private String effectType;
        private List<PortPoint> points;

        public GraphEdge() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getSourceId() {
            return sourceId;
        }

        public void setSourceId(String sourceId) {
            this.sourceId = sourceId;
        }

        public String getTargetId() {
            return targetId;
        }

        public void setTargetId(String targetId) {
            this.targetId = targetId;
        }

        public String getRelationId() {
            return relationId;
        }

        public void setRelationId(String relationId) {
            this.relationId = relationId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getEffectType() {
            return effectType;
        }

        public void setEffectType(String effectType) {
            this.effectType = effectType;
        }

        public List<PortPoint> getPoints() {
            return points;
        }

        public void setPoints(List<PortPoint> points) {
            this.points = points;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GraphLabel {
        private double x;
        private double y;
        private double width;
        private double height;
        private String content;
        private String fontFamily;
        private Integer fontSize;

        public GraphLabel() {
        }

        public GraphLabel(String content, double x, double y, double width, double height) {
            this.content = content;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
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

        public double getWidth() {
            return width;
        }

        public void setWidth(double width) {
            this.width = width;
        }

        public double getHeight() {
            return height;
        }

        public void setHeight(double height) {
            this.height = height;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getFontFamily() {
            return fontFamily;
        }

        public void setFontFamily(String fontFamily) {
            this.fontFamily = fontFamily;
        }

        public Integer getFontSize() {
            return fontSize;
        }

        public void setFontSize(Integer fontSize) {
            this.fontSize = fontSize;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PortPoint {
        private double x;
        private double y;

        public PortPoint() {
        }

        public PortPoint(double x, double y) {
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
}
