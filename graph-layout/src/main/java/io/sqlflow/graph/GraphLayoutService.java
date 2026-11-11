package io.sqlflow.graph;

import io.sqlflow.contracts.graph.DbObjectPosition;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.spi.GraphLayoutSpi;
import io.sqlflow.graph.export.GraphMlExporter;
import io.sqlflow.graph.export.SqlflowPngRenderer;
import io.sqlflow.graph.layout.SugiyamaGraphLayouter;
import io.sqlflow.graph.slice.GraphSlicer;
import io.sqlflow.graph.slice.LineageClosureCalculator;
import io.sqlflow.graph.slice.SelectedObjectResolver;
import java.util.List;

/**
 * Production implementation of {@link GraphLayoutSpi} registered via {@code ServiceLoader}
 * ({@code OBL-051..OBL-056, OBL-059..OBL-060, OBL-088, OBL-090}).
 */
public class GraphLayoutService implements GraphLayoutSpi {

    private final SugiyamaGraphLayouter layouter;
    private final GraphSlicer slicer;

    public GraphLayoutService() {
        this.layouter = new SugiyamaGraphLayouter();
        this.slicer = new GraphSlicer(this.layouter);
    }

    @Override
    public SqlflowJSON layoutGraph(GraphLayoutRequest request) {
        return layouter.layout(request);
    }

    @Override
    public SlicingResult sliceUpstream(GraphSliceRequest request) {
        return slicer.slice(request, GraphSlicer.Direction.UPSTREAM);
    }

    @Override
    public SlicingResult sliceDownstream(GraphSliceRequest request) {
        return slicer.slice(request, GraphSlicer.Direction.DOWNSTREAM);
    }

    @Override
    public List<DbObjectPosition> getSelectedDbObjectInfo(SelectedObjectRequest request) {
        return SelectedObjectResolver.resolve(request);
    }

    @Override
    public LeftMostSourceTable computeLeftMostSourceTable(
            DataFlow dataFlow,
            String targetTableId,
            boolean ignoreRecordSet) {
        return LineageClosureCalculator.computeLeftMostSourceTable(dataFlow, targetTableId, ignoreRecordSet);
    }

    @Override
    public DataFlow computeOutgoingClosure(DataFlow dataFlow, List<String> rootObjectIds, int maxDepth) {
        return LineageClosureCalculator.computeOutgoingClosure(dataFlow, rootObjectIds, maxDepth);
    }

    @Override
    public String exportGraphML(SqlflowJSON graph) {
        return GraphMlExporter.export(graph);
    }

    @Override
    public byte[] renderPng(SqlflowJSON graph, PngRenderOptions options) {
        return SqlflowPngRenderer.renderPng(graph, options);
    }
}
