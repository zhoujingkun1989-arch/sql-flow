package io.sqlflow.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.graph.DboColorPalette;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphColumn;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphEdge;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphPackage;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphTable;
import io.sqlflow.contracts.graph.SqlflowJSON.PortPoint;
import io.sqlflow.contracts.lineage.ArgumentObject;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.Orientation;
import io.sqlflow.contracts.lineage.PackageObject;
import io.sqlflow.contracts.lineage.ProcedureObject;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.spi.GraphLayoutSpi.GraphLayoutRequest;
import io.sqlflow.contracts.spi.GraphLayoutSpi.PngRenderOptions;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

class SugiyamaLayoutAndExportTest {

    private final GraphLayoutService service = new GraphLayoutService();
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(Orientation.class)
    void sugiyamaLayoutProducesNonOverlappingCoordinatesAndBridgedIdsInAllOrientations(Orientation orientation)
            throws Exception {
        DataFlow df = buildWarehouseAndPackageDataFlow();

        GraphLayoutRequest req = GraphLayoutRequest.of(df, orientation);
        req.setShowRelationType(true);
        req.setIncludeSvg(true);
        req.setIncludeGraphML(true);

        SqlflowJSON result = service.layoutGraph(req);
        assertThat(result).isNotNull();
        assertThat(result.getOrientation()).isEqualTo(orientation);
        assertThat(result.getWidth()).isGreaterThan(150.0);
        assertThat(result.getHeight()).isGreaterThan(80.0);
        assertThat(result.getGraph()).isNotNull();
        assertThat(result.getSvg()).startsWith("<svg");
        assertThat(result.getGraphML()).contains("<graphml");

        List<GraphTable> tables = result.resolveElements().getTables();
        List<GraphEdge> edges = result.resolveElements().getEdges();
        List<GraphPackage> packages = result.resolveElements().getPackages();

        assertThat(tables).hasSize(5);
        assertThat(edges).isNotEmpty();
        assertThat(packages).hasSize(1);

        // Verify zero bounding-box overlap across all laid-out tables
        for (int i = 0; i < tables.size(); i++) {
            GraphTable a = tables.get(i);
            assertThat(a.getX()).isGreaterThanOrEqualTo(0.0);
            assertThat(a.getY()).isGreaterThanOrEqualTo(0.0);
            assertThat(a.getWidth()).isGreaterThanOrEqualTo(90.0);
            assertThat(a.getHeight()).isGreaterThanOrEqualTo(24.0);

            for (int j = i + 1; j < tables.size(); j++) {
                GraphTable b = tables.get(j);
                boolean xOverlap = a.getX() < b.getX() + b.getWidth() && a.getX() + a.getWidth() > b.getX();
                boolean yOverlap = a.getY() < b.getY() + b.getHeight() && a.getY() + a.getHeight() > b.getY();
                assertThat(xOverlap && yOverlap)
                        .as("Tables %s and %s must not overlap in %s", a.getName(), b.getName(), orientation)
                        .isFalse();
            }
        }

        // Verify RowLayouter column offsets (x = 1.0, y = 22.0 + j * 16.0)
        GraphTable ordersTbl = tables.stream()
                .filter(t -> t.getName().contains("orders"))
                .findFirst()
                .orElseThrow();
        assertThat(ordersTbl.getColumns()).hasSize(2);
        for (int idx = 0; idx < ordersTbl.getColumns().size(); idx++) {
            GraphColumn gc = ordersTbl.getColumns().get(idx);
            assertThat(gc.getX()).isEqualTo(1.0);
            assertThat(gc.getY()).isEqualTo(22.0 + idx * 16.0);
            assertThat(gc.getHeight()).isEqualTo(16.0);
        }

        // Verify compound package bounding box encloses member procedures
        GraphPackage pkg = packages.get(0);
        assertThat(pkg.getName()).contains("PKG_BILLING");
        List<GraphTable> pkgChildren = tables.stream()
                .filter(t -> pkg.getId().equals(t.getParent()))
                .toList();
        assertThat(pkgChildren).hasSize(2);
        for (GraphTable child : pkgChildren) {
            assertThat(child.getX()).isGreaterThanOrEqualTo(pkg.getX());
            assertThat(child.getY()).isGreaterThanOrEqualTo(pkg.getY());
            assertThat(child.getX() + child.getWidth()).isLessThanOrEqualTo(pkg.getX() + pkg.getWidth() + 0.01);
            assertThat(child.getY() + child.getHeight()).isLessThanOrEqualTo(pkg.getY() + pkg.getHeight() + 0.01);
        }

        // Verify listIdMap (model ID -> visual IDs per 03 §1.5.3), relationshipIdMap, tooltip, and Bezier tangents
        assertThat(result.getListIdMap())
                .containsKey("100")
                .containsKey("101");
        assertThat(result.getListIdMap().values())
                .anySatisfy(ids -> assertThat(ids).contains("n0"));
        assertThat(result.getListIdMap().values())
                .anySatisfy(ids -> assertThat(ids).contains("n0::n0"));
        assertThat(result.getRelationshipIdMap()).containsKey("e0");
        assertThat(result.getRelationshipIdMap().values())
                .anySatisfy(ids -> assertThat(ids).contains("rel-dup-1", "rel-dup-2"));
        assertThat(result.getTooltip()).isNotEmpty();

        for (GraphEdge edge : edges) {
            List<PortPoint> pts = edge.getPoints();
            assertThat(pts.size()).isIn(4, 7);
            if (pts.size() == 4) {
                if (orientation == Orientation.LEFT_TO_RIGHT || orientation == Orientation.RIGHT_TO_LEFT) {
                    assertThat(pts.get(0).getY()).isEqualTo(pts.get(1).getY());
                    assertThat(pts.get(2).getY()).isEqualTo(pts.get(3).getY());
                } else {
                    assertThat(pts.get(0).getX()).isEqualTo(pts.get(1).getX());
                    assertThat(pts.get(2).getX()).isEqualTo(pts.get(3).getX());
                }
            }
        }

        // Verify Jackson wire serialization preserves "unqiueKey"
        String json = mapper.writeValueAsString(result);
        JsonNode root = mapper.readTree(json);
        assertThat(json).contains("\"unqiueKey\":true");
        assertThat(root.get("elements").get("tables").isArray()).isTrue();
    }

    @Test
    void cyclicLineageAndColumnTruncationAndTableLevelModeBehaveCleanly() {
        DbObject tA = makeTable("1", "cycle_alpha", "table", null, 8);
        DbObject tB = makeTable("2", "cycle_beta", "table", null, 3);
        DbObject tC = makeTable("3", "cycle_gamma", "table", null, 3);

        DatabaseObject db = new DatabaseObject("warehouse");
        db.setTables(List.of(tA, tB, tC));
        SqlflowCatalog catalog = new SqlflowCatalog();
        catalog.setDatabases(List.of(db));

        DataFlow df = new DataFlow();
        df.setDbobjs(catalog);
        df.setRelationships(List.of(
                makeRel("r1", "fdd", "1", "1_c0", "2", "2_c0"),
                makeRel("r2", "fdd", "2", "2_c0", "3", "3_c0"),
                makeRel("r3", "fdd", "3", "3_c0", "1", "1_c0"))); // cycle back to tA!

        GraphLayoutRequest colReq = GraphLayoutRequest.of(df, Orientation.LEFT_TO_RIGHT);
        colReq.setMaxColumnsVisible(4);
        SqlflowJSON colGraph = service.layoutGraph(colReq);

        assertThat(colGraph.resolveElements().getTables()).hasSize(3);
        assertThat(colGraph.resolveElements().getEdges()).hasSize(3);
        // At least one reversed feedback edge must route via 7-point smooth C1 double-cubic Bezier
        assertThat(colGraph.resolveElements().getEdges())
                .anySatisfy(e -> assertThat(e.getPoints()).hasSize(7));

        GraphTable alpha = colGraph.resolveElements().getTables().stream()
                .filter(t -> t.getName().contains("cycle_alpha"))
                .findFirst()
                .orElseThrow();
        assertThat(alpha.getMore()).isTrue();
        assertThat(alpha.getColumns()).hasSize(4);

        // Table-level mode collapses columns and routes edges directly between table IDs
        GraphLayoutRequest tblReq = GraphLayoutRequest.of(df, Orientation.LEFT_TO_RIGHT);
        tblReq.setTableLevel(true);
        SqlflowJSON tblGraph = service.layoutGraph(tblReq);
        assertThat(tblGraph.getTableLevel()).isTrue();
        assertThat(tblGraph.resolveElements().getTables())
                .allSatisfy(t -> assertThat(t.getColumns()).isEmpty());
        assertThat(tblGraph.resolveElements().getEdges()).hasSize(3);
    }

    @Test
    void exportGraphMlProducesValidYFilesXmlDomForPackagesTablesColumnsAndEdges() throws Exception {
        DataFlow df = buildWarehouseAndPackageDataFlow();
        SqlflowJSON graph = service.layoutGraph(GraphLayoutRequest.of(df, Orientation.LEFT_TO_RIGHT));

        String xml = service.exportGraphML(graph);
        assertThat(xml)
                .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"")
                .contains("http://www.yworks.com/xml/graphml")
                .contains("<y:ProxyAutoBoundsNode>")
                .contains("<y:GroupNode>")
                .contains("<y:ShapeNode>")
                .contains("<y:PolyLineEdge>");

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("graphml");
        NodeList keys = doc.getElementsByTagNameNS("http://graphml.graphdrawing.org/xmlns", "key");
        assertThat(keys.getLength()).isGreaterThanOrEqualTo(10);

        NodeList groupNodes = doc.getElementsByTagNameNS("http://www.yworks.com/xml/graphml", "GroupNode");
        assertThat(groupNodes.getLength()).isGreaterThanOrEqualTo(6);

        NodeList polyEdges = doc.getElementsByTagNameNS("http://www.yworks.com/xml/graphml", "PolyLineEdge");
        assertThat(polyEdges.getLength()).isEqualTo(graph.resolveElements().getEdges().size());
    }

    @Test
    void headlessJava2dPngRendererDrawsCrispCardsAndExactDboColorPalette() throws Exception {
        DataFlow df = buildWarehouseAndPackageDataFlow();
        SqlflowJSON graph = service.layoutGraph(GraphLayoutRequest.of(df, Orientation.LEFT_TO_RIGHT));

        PngRenderOptions defaultOpts = new PngRenderOptions();
        byte[] pngBytes = service.renderPng(graph, defaultOpts);
        assertThat(pngBytes).isNotEmpty();
        // Verify PNG magic header: 89 50 4E 47 0D 0A 1A 0A
        assertThat(pngBytes[0]).isEqualTo((byte) 0x89);
        assertThat(pngBytes[1]).isEqualTo((byte) 0x50);
        assertThat(pngBytes[2]).isEqualTo((byte) 0x4E);
        assertThat(pngBytes[3]).isEqualTo((byte) 0x47);

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isGreaterThan(200);
        assertThat(img.getHeight()).isGreaterThan(100);

        // Top-left corner pixel uses DboColorPalette.CANVAS_BACKGROUND (#eeeeee)
        Color topLeft = new Color(img.getRGB(2, 2));
        assertThat(topLeft).isEqualTo(Color.decode(DboColorPalette.CANVAS_BACKGROUND));

        // Scan rendered pixels to verify table (#91c051), view (#419a5f), and CTE (#ff6e97) header colors appear
        Set<Integer> uniqueRgb = new HashSet<>();
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                uniqueRgb.add(img.getRGB(x, y) & 0xFFFFFF);
            }
        }
        assertThat(uniqueRgb)
                .contains(0x91c051) // table header green
                .contains(0x419a5f) // view header teal
                .contains(0xff6e97); // with_cte header pink

        // Scaled shadow render produces proportionally larger image
        PngRenderOptions hiDpi = new PngRenderOptions();
        hiDpi.setScale(2.0);
        hiDpi.setPadding(32);
        hiDpi.setShowShadow(true);
        BufferedImage scaledImg = ImageIO.read(new ByteArrayInputStream(service.renderPng(graph, hiDpi)));
        assertThat(scaledImg.getWidth()).isGreaterThan(img.getWidth());
        assertThat(scaledImg.getHeight()).isGreaterThan(img.getHeight());
    }

    private static DataFlow buildWarehouseAndPackageDataFlow() {
        // 1. Source table (with >25 char qualified name for tooltip + PK/UK/FK/Index badges)
        Column cOrderId = new Column("101", "order_id");
        cOrderId.setPrimaryKey(true);
        cOrderId.setUnqiueKey(true);
        cOrderId.setIndexKey(true);

        Column cCustId = new Column("102", "customer_reference_identifier_very_long_column_name");
        cCustId.setForeignKey(true);

        DbObject tblOrders = new DbObject();
        tblOrders.setId("100");
        tblOrders.setDatabase("commerce_enterprise_warehouse");
        tblOrders.setSchema("sales_raw");
        tblOrders.setName("orders");
        tblOrders.setType("table");
        tblOrders.setColumns(List.of(cOrderId, cCustId));

        // 2. CTE resultset (subType = with_cte -> #ff6e97)
        Column cteCol = new Column("201", "order_id");
        DbObject cte = new DbObject();
        cte.setId("200");
        cte.setName("WITH-CTE-ENRICHED_ORDERS");
        cte.setType("resultset");
        cte.setSubType("with_cte");
        cte.setColumns(List.of(cteCol));

        // 3. Target View (#419a5f)
        Column vCol = new Column("301", "total_orders");
        DbObject viewSummary = new DbObject();
        viewSummary.setId("300");
        viewSummary.setSchema("mart");
        viewSummary.setName("v_daily_order_summary");
        viewSummary.setType("view");
        viewSummary.setColumns(List.of(vCol));

        // 4. Oracle Package containing two stored procedures
        ProcedureObject procCalc = new ProcedureObject("400", "CALC_INVOICE", "procedure");
        procCalc.setArguments(List.of(new ArgumentObject("401", "p_order_id", "NUMBER", "IN")));

        ProcedureObject procPost = new ProcedureObject("500", "POST_LEDGER", "procedure");
        procPost.setArguments(List.of(new ArgumentObject("501", "p_summary_out", "NUMBER", "OUT")));

        PackageObject pkg = new PackageObject("pkg-1", "PKG_BILLING");
        pkg.setType("oraclePackage");
        pkg.setProcedures(List.of(procCalc, procPost));

        SchemaObject salesSchema = new SchemaObject("sales_raw");
        salesSchema.setTables(List.of(tblOrders));
        salesSchema.setOthers(List.of(cte));

        SchemaObject martSchema = new SchemaObject("mart");
        martSchema.setViews(List.of(viewSummary));
        martSchema.setPackages(List.of(pkg));

        DatabaseObject db = new DatabaseObject("commerce_enterprise_warehouse");
        db.setSchemas(List.of(salesSchema, martSchema));

        SqlflowCatalog catalog = new SqlflowCatalog();
        catalog.setDatabases(List.of(db));

        DataFlow df = new DataFlow();
        df.setDbobjs(catalog);
        df.setRelationships(List.of(
                makeRel("rel-dup-1", "fdd", "100", "101", "200", "201"),
                makeRel("rel-dup-2", "fdd", "100", "101", "200", "201"),
                makeRel("rel-2", "fdd", "200", "201", "300", "301"),
                makeRel("rel-3", "fdd", "300", "301", "400", "401"),
                makeRel("rel-4", "call", "400", "401", "500", "501")));
        return df;
    }

    private static DbObject makeTable(String id, String name, String type, String subType, int colCount) {
        List<Column> cols = new ArrayList<>(colCount);
        for (int i = 0; i < colCount; i++) {
            cols.add(new Column(id + "_c" + i, "col_" + i));
        }
        DbObject obj = new DbObject();
        obj.setId(id);
        obj.setName(name);
        obj.setType(type);
        obj.setSubType(subType);
        obj.setColumns(cols);
        return obj;
    }

    private static Relationship makeRel(
            String id,
            String type,
            String srcParentId,
            String srcColId,
            String tgtParentId,
            String tgtColId) {
        Relationship r = new Relationship();
        r.setId(id);
        r.setType(type);

        RelationshipElement src = new RelationshipElement();
        src.setId(srcColId);
        src.setParentId(srcParentId);
        r.setSources(List.of(src));

        RelationshipElement tgt = new RelationshipElement();
        tgt.setId(tgtColId);
        tgt.setParentId(tgtParentId);
        r.setTarget(tgt);
        return r;
    }
}
