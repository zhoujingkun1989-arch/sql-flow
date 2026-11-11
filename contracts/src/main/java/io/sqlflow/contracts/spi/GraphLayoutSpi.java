package io.sqlflow.contracts.spi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.sqlflow.contracts.graph.DbObjectPosition;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.Orientation;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.SqlInfo;
import java.util.List;
import java.util.Set;

/**
 * Frozen Stage 2A SPI for the Graph Layout, Slicing, Closure & Export module ({@code graph-layout})
 * ({@code 00 §5.2}, {@code 03 §1.5, §4.1–§4.5}).
 */
public interface GraphLayoutSpi {

    /**
     * Lays out a {@link DataFlow} model using ELK layered Sugiyama layout into a {@link SqlflowJSON} graph.
     */
    SqlflowJSON layoutGraph(GraphLayoutRequest request);

    /**
     * Slices upstream lineage via BFS from the selected table/column/package IDs and optionally lays out the subgraph.
     */
    SlicingResult sliceUpstream(GraphSliceRequest request);

    /**
     * Slices downstream impact via BFS from the selected table/column/package IDs and optionally lays out the subgraph.
     */
    SlicingResult sliceDownstream(GraphSliceRequest request);

    /**
     * Resolves 1-based SQL token highlight coordinates ({@code [[startLine, startCol], [endLine, endCol]]})
     * across {@link SqlInfo} units for a selected graph node, column, or edge.
     */
    List<DbObjectPosition> getSelectedDbObjectInfo(SelectedObjectRequest request);

    /**
     * Computes the root-origin upstream source closure ({@link LeftMostSourceTable}) for a target table or entire graph.
     */
    LeftMostSourceTable computeLeftMostSourceTable(DataFlow dataFlow, String targetTableId, boolean ignoreRecordSet);

    /**
     * Computes the outgoing forward reachability closure from {@code rootObjectIds} up to {@code maxDepth} hops.
     */
    DataFlow computeOutgoingClosure(DataFlow dataFlow, List<String> rootObjectIds, int maxDepth);

    /**
     * Exports a laid-out {@link SqlflowJSON} graph into GraphML XML format.
     */
    String exportGraphML(SqlflowJSON graph);

    /**
     * Renders a laid-out {@link SqlflowJSON} graph into a PNG image byte array using {@code DboColorPalette}.
     */
    byte[] renderPng(SqlflowJSON graph, PngRenderOptions options);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class GraphLayoutRequest {
        private DataFlow dataFlow;
        private Orientation orientation = Orientation.LEFT_TO_RIGHT;
        private boolean tableLevel = false;
        private boolean showRelationType = false;
        private Set<RelationshipType> relationTypes;
        private boolean includeSvg = false;
        private boolean includeGraphML = false;
        private int maxColumnsVisible = 50;

        public GraphLayoutRequest() {
        }

        public static GraphLayoutRequest of(DataFlow dataFlow, Orientation orientation) {
            GraphLayoutRequest req = new GraphLayoutRequest();
            req.setDataFlow(dataFlow);
            req.setOrientation(orientation != null ? orientation : Orientation.LEFT_TO_RIGHT);
            return req;
        }

        public DataFlow getDataFlow() {
            return dataFlow;
        }

        public void setDataFlow(DataFlow dataFlow) {
            this.dataFlow = dataFlow;
        }

        public Orientation getOrientation() {
            return orientation;
        }

        public void setOrientation(Orientation orientation) {
            this.orientation = orientation != null ? orientation : Orientation.LEFT_TO_RIGHT;
        }

        public boolean isTableLevel() {
            return tableLevel;
        }

        public void setTableLevel(boolean tableLevel) {
            this.tableLevel = tableLevel;
        }

        public boolean isShowRelationType() {
            return showRelationType;
        }

        public void setShowRelationType(boolean showRelationType) {
            this.showRelationType = showRelationType;
        }

        public Set<RelationshipType> getRelationTypes() {
            return relationTypes;
        }

        public void setRelationTypes(Set<RelationshipType> relationTypes) {
            this.relationTypes = relationTypes;
        }

        public boolean isIncludeSvg() {
            return includeSvg;
        }

        public void setIncludeSvg(boolean includeSvg) {
            this.includeSvg = includeSvg;
        }

        public boolean isIncludeGraphML() {
            return includeGraphML;
        }

        public void setIncludeGraphML(boolean includeGraphML) {
            this.includeGraphML = includeGraphML;
        }

        public int getMaxColumnsVisible() {
            return maxColumnsVisible;
        }

        public void setMaxColumnsVisible(int maxColumnsVisible) {
            this.maxColumnsVisible = maxColumnsVisible;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class GraphSliceRequest {
        private DataFlow dataFlow;
        private String database;
        private String schema;
        private String table;
        private String column;
        private List<String> dbObjIds;
        private int maxHopDepth = 20;
        private boolean ignoreRecordSet = false;
        private boolean showConstantTable = false;
        private boolean layoutResult = true;
        private Orientation orientation = Orientation.LEFT_TO_RIGHT;
        private Set<RelationshipType> relationTypes;

        public GraphSliceRequest() {
        }

        public DataFlow getDataFlow() {
            return dataFlow;
        }

        public void setDataFlow(DataFlow dataFlow) {
            this.dataFlow = dataFlow;
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

        public List<String> getDbObjIds() {
            return dbObjIds;
        }

        public void setDbObjIds(List<String> dbObjIds) {
            this.dbObjIds = dbObjIds;
        }

        public int getMaxHopDepth() {
            return maxHopDepth;
        }

        public void setMaxHopDepth(int maxHopDepth) {
            this.maxHopDepth = maxHopDepth;
        }

        public boolean isIgnoreRecordSet() {
            return ignoreRecordSet;
        }

        public void setIgnoreRecordSet(boolean ignoreRecordSet) {
            this.ignoreRecordSet = ignoreRecordSet;
        }

        public boolean isShowConstantTable() {
            return showConstantTable;
        }

        public void setShowConstantTable(boolean showConstantTable) {
            this.showConstantTable = showConstantTable;
        }

        public boolean isLayoutResult() {
            return layoutResult;
        }

        public void setLayoutResult(boolean layoutResult) {
            this.layoutResult = layoutResult;
        }

        public Orientation getOrientation() {
            return orientation;
        }

        public void setOrientation(Orientation orientation) {
            this.orientation = orientation != null ? orientation : Orientation.LEFT_TO_RIGHT;
        }

        public Set<RelationshipType> getRelationTypes() {
            return relationTypes;
        }

        public void setRelationTypes(Set<RelationshipType> relationTypes) {
            this.relationTypes = relationTypes;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class SlicingResult {
        private DataFlow slicedDataFlow;
        private SqlflowJSON graph;
        private List<String> matchedRootIds;

        public SlicingResult() {
        }

        public SlicingResult(DataFlow slicedDataFlow, SqlflowJSON graph, List<String> matchedRootIds) {
            this.slicedDataFlow = slicedDataFlow;
            this.graph = graph;
            this.matchedRootIds = matchedRootIds;
        }

        public DataFlow getSlicedDataFlow() {
            return slicedDataFlow;
        }

        public void setSlicedDataFlow(DataFlow slicedDataFlow) {
            this.slicedDataFlow = slicedDataFlow;
        }

        public SqlflowJSON getGraph() {
            return graph;
        }

        public void setGraph(SqlflowJSON graph) {
            this.graph = graph;
        }

        public List<String> getMatchedRootIds() {
            return matchedRootIds;
        }

        public void setMatchedRootIds(List<String> matchedRootIds) {
            this.matchedRootIds = matchedRootIds;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class SelectedObjectRequest {
        private DataFlow dataFlow;
        private List<SqlInfo> sqlUnits;
        private String dbObjId;
        private String columnId;
        private List<String> relationIds;

        public SelectedObjectRequest() {
        }

        public DataFlow getDataFlow() {
            return dataFlow;
        }

        public void setDataFlow(DataFlow dataFlow) {
            this.dataFlow = dataFlow;
        }

        public List<SqlInfo> getSqlUnits() {
            return sqlUnits;
        }

        public void setSqlUnits(List<SqlInfo> sqlUnits) {
            this.sqlUnits = sqlUnits;
        }

        public String getDbObjId() {
            return dbObjId;
        }

        public void setDbObjId(String dbObjId) {
            this.dbObjId = dbObjId;
        }

        public String getColumnId() {
            return columnId;
        }

        public void setColumnId(String columnId) {
            this.columnId = columnId;
        }

        public List<String> getRelationIds() {
            return relationIds;
        }

        public void setRelationIds(List<String> relationIds) {
            this.relationIds = relationIds;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class PngRenderOptions {
        private double scale = 1.0;
        private int padding = 24;
        private boolean showShadow = false;

        public PngRenderOptions() {
        }

        public double getScale() {
            return scale;
        }

        public void setScale(double scale) {
            this.scale = scale;
        }

        public int getPadding() {
            return padding;
        }

        public void setPadding(int padding) {
            this.padding = padding;
        }

        public boolean isShowShadow() {
            return showShadow;
        }

        public void setShowShadow(boolean showShadow) {
            this.showShadow = showShadow;
        }
    }
}
