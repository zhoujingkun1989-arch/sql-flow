package io.sqlflow.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.sqlflow.contracts.graph.DbObjectPosition;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.graph.LeftMostSourceTable.SourceTableColumn;
import io.sqlflow.contracts.graph.LeftMostSourceTable.TargetColumn;
import io.sqlflow.contracts.graph.LeftMostSourceTable.TargetTable;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.Relationship;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.spi.GraphLayoutSpi.GraphSliceRequest;
import io.sqlflow.contracts.spi.GraphLayoutSpi.SelectedObjectRequest;
import io.sqlflow.contracts.spi.GraphLayoutSpi.SlicingResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlicingLookupAndClosureTest {

    private final GraphLayoutService service = new GraphLayoutService();

    @Test
    void upstreamAndDownstreamSlicingHonorHopDepthRelationTypesConstantsAndIgnoreRecordSet() {
        DataFlow df = buildMultiHopLineageFixture();

        // 1. Upstream slice from mart_revenue.gross_rev with 1 hop only -> stops at v_sales_enriched
        GraphSliceRequest up1 = new GraphSliceRequest();
        up1.setDataFlow(df);
        up1.setSchema("mart");
        up1.setTable("mart_revenue");
        up1.setColumn("gross_rev");
        up1.setMaxHopDepth(1);
        up1.setLayoutResult(false);

        SlicingResult resUp1 = service.sliceUpstream(up1);
        assertThat(resUp1.getGraph()).isNull();
        assertThat(resUp1.getMatchedRootIds()).contains("401");
        assertThat(collectObjectNames(resUp1.getSlicedDataFlow()))
                .containsExactlyInAnyOrder("v_sales_enriched", "mart_revenue");

        // 2. Full upstream slice with ignoreRecordSet = true and relationTypes = {fdd}:
        //    collapses CTE RS-CTE_ORDERS, excludes $constant (showConstantTable=false), and excludes raw_fx_rates (fdr)
        GraphSliceRequest upCollapsed = new GraphSliceRequest();
        upCollapsed.setDataFlow(df);
        upCollapsed.setSchema("mart");
        upCollapsed.setTable("mart_revenue");
        upCollapsed.setColumn("gross_rev");
        upCollapsed.setMaxHopDepth(10);
        upCollapsed.setIgnoreRecordSet(true);
        upCollapsed.setShowConstantTable(false);
        upCollapsed.setRelationTypes(Set.of(RelationshipType.fdd));
        upCollapsed.setLayoutResult(true);

        SlicingResult resCollapsed = service.sliceUpstream(upCollapsed);
        assertThat(resCollapsed.getGraph()).isNotNull();
        assertThat(collectObjectNames(resCollapsed.getSlicedDataFlow()))
                .containsExactlyInAnyOrder("raw_orders", "v_sales_enriched", "mart_revenue")
                .doesNotContain("RS-CTE_ORDERS", "$constant", "raw_fx_rates");

        // 3. Full upstream slice including fdr and $constant
        GraphSliceRequest upAll = new GraphSliceRequest();
        upAll.setDataFlow(df);
        upAll.setDbObjIds(List.of("400"));
        upAll.setMaxHopDepth(10);
        upAll.setIgnoreRecordSet(false);
        upAll.setShowConstantTable(true);
        upAll.setRelationTypes(Set.of(RelationshipType.fdd, RelationshipType.fdr));

        SlicingResult resAll = service.sliceUpstream(upAll);
        assertThat(collectObjectNames(resAll.getSlicedDataFlow()))
                .contains("raw_orders", "raw_fx_rates", "$constant", "RS-CTE_ORDERS", "v_sales_enriched", "mart_revenue");

        // 4. Downstream slice from raw_orders.amount -> reaches RS-CTE_ORDERS, v_sales_enriched, mart_revenue
        GraphSliceRequest downReq = new GraphSliceRequest();
        downReq.setDataFlow(df);
        downReq.setSchema("raw");
        downReq.setTable("raw_orders");
        downReq.setColumn("amount");
        downReq.setMaxHopDepth(10);
        downReq.setLayoutResult(true);

        SlicingResult downRes = service.sliceDownstream(downReq);
        assertThat(downRes.getGraph()).isNotNull();
        assertThat(downRes.getGraph().resolveElements().getTables()).hasSize(4);
        assertThat(collectObjectNames(downRes.getSlicedDataFlow()))
                .containsExactlyInAnyOrder("raw_orders", "RS-CTE_ORDERS", "v_sales_enriched", "mart_revenue");
    }

    @Test
    void getSelectedDbObjectInfoResolves1BasedHighlightPositionsAcrossSqlUnits() {
        String sql0 = "CREATE VIEW mart.v_sales_enriched AS\nSELECT o.amount FROM raw.raw_orders o;";
        String sql1 = "INSERT INTO mart.mart_revenue(gross_rev)\nSELECT amount FROM mart.v_sales_enriched;";

        SqlInfo u0 = new SqlInfo(sql0, "01_view.sql", "sql/01_view.sql", "dbvpostgresql");
        u0.setOriginIndex(0);
        u0.setHashCode("h0");

        SqlInfo u1 = new SqlInfo(sql1, "02_mart.sql", "sql/02_mart.sql", "dbvpostgresql");
        u1.setOriginIndex(1);
        u1.setHashCode("h1");

        DataFlow df = buildMultiHopLineageFixture();

        // Lookup by columnId "101" (o.amount in 01_view.sql line 2 col 8..16, hashCode h0)
        SelectedObjectRequest colReq = new SelectedObjectRequest();
        colReq.setDataFlow(df);
        colReq.setSqlUnits(List.of(u0, u1));
        colReq.setColumnId("101");

        List<DbObjectPosition> colPositions = service.getSelectedDbObjectInfo(colReq);
        assertThat(colPositions).hasSize(1);
        DbObjectPosition p0 = colPositions.get(0);
        assertThat(p0.getFile()).isEqualTo("01_view.sql");
        assertThat(p0.getIndex()).isEqualTo(0);
        assertThat(p0.getSql()).isEqualTo(sql0);
        assertThat(p0.toSpanPairs()).isEqualTo(List.of(List.of(List.of(2, 8), List.of(2, 16))));

        // Lookup by relationId "r4" (spans in 02_mart.sql via hashCode h1)
        SelectedObjectRequest relReq = new SelectedObjectRequest();
        relReq.setDataFlow(df);
        relReq.setSqlUnits(List.of(u0, u1));
        relReq.setRelationIds(List.of("r4"));

        List<DbObjectPosition> relPositions = service.getSelectedDbObjectInfo(relReq);
        assertThat(relPositions).isNotEmpty();
        assertThat(relPositions)
                .anySatisfy(pos -> assertThat(pos.getFile()).isEqualTo("02_mart.sql"));
    }

    @Test
    void leftMostSourceTableAndOutgoingClosuresResolveTransitiveLineageAccurately() {
        DataFlow df = buildMultiHopLineageFixture();

        // 1. Left-most root source closure for target table 400 (mart_revenue)
        LeftMostSourceTable closure = service.computeLeftMostSourceTable(df, "400", true);
        assertThat(closure.getElements()).hasSize(1);
        assertThat(closure.getTargetTables()).hasSize(1);

        TargetTable tgt = closure.getElements().get(0);
        assertThat(tgt.getName()).isEqualTo("mart_revenue");
        assertThat(tgt.getColumns()).hasSize(1);

        TargetColumn grossRev = tgt.getColumns().get(0);
        assertThat(grossRev.getName()).isEqualTo("gross_rev");
        assertThat(grossRev.getSourceColumns())
                .extracting(SourceTableColumn::getTableName)
                .containsExactlyInAnyOrder("raw_orders", "raw_fx_rates");

        // Verify attributes indirect closure projection is also populated
        assertThat(closure.getAttributes()).hasSize(1);
        assertThat(closure.getAttributes().get(0).getAttributeName()).isEqualTo("gross_rev");
        assertThat(closure.getAttributes().get(0).getUpstreamAttributesIndirect().getNodes())
                .extracting(LeftMostSourceTable.TableColumn::getRelationName)
                .containsExactlyInAnyOrder("raw_orders", "raw_fx_rates");

        // 2. Outgoing impact closure from raw_orders (id "100") with depth 1 vs depth 5
        DataFlow out1 = service.computeOutgoingClosure(df, List.of("100"), 1);
        assertThat(collectObjectNames(out1))
                .containsExactlyInAnyOrder("raw_orders", "RS-CTE_ORDERS");

        DataFlow out5 = service.computeOutgoingClosure(df, List.of("101"), 5);
        assertThat(collectObjectNames(out5))
                .containsExactlyInAnyOrder("raw_orders", "RS-CTE_ORDERS", "v_sales_enriched", "mart_revenue");
    }

    private static List<String> collectObjectNames(DataFlow df) {
        List<String> names = new ArrayList<>();
        if (df == null || df.getDbobjs() == null) {
            return names;
        }
        if (df.getDbobjs().getServers() != null) {
            for (ServerObject srv : df.getDbobjs().getServers()) {
                if (srv.getSchemas() != null) {
                    for (SchemaObject sch : srv.getSchemas()) {
                        appendSchemaObjects(names, sch);
                    }
                }
                if (srv.getDatabases() != null) {
                    for (DatabaseObject db : srv.getDatabases()) {
                        appendDatabaseObjects(names, db);
                    }
                }
            }
        }
        if (df.getDbobjs().getDatabases() != null) {
            for (DatabaseObject db : df.getDbobjs().getDatabases()) {
                appendDatabaseObjects(names, db);
            }
        }
        return names;
    }

    private static void appendDatabaseObjects(List<String> names, DatabaseObject db) {
        if (db == null) {
            return;
        }
        appendObjList(names, db.getTables());
        appendObjList(names, db.getViews());
        appendObjList(names, db.getOthers());
        if (db.getSchemas() != null) {
            for (SchemaObject sch : db.getSchemas()) {
                appendSchemaObjects(names, sch);
            }
        }
    }

    private static void appendSchemaObjects(List<String> names, SchemaObject sch) {
        if (sch == null) {
            return;
        }
        appendObjList(names, sch.getTables());
        appendObjList(names, sch.getViews());
        appendObjList(names, sch.getOthers());
    }

    private static void appendObjList(List<String> names, List<DbObject> objs) {
        if (objs == null) {
            return;
        }
        for (DbObject o : objs) {
            if (o != null && o.getName() != null) {
                names.add(o.getName());
            }
        }
    }

    private static DataFlow buildMultiHopLineageFixture() {
        // Table 100: raw.raw_orders(amount [101])
        Column cAmount = new Column("101", "amount");
        cAmount.setDataType("NUMERIC(18,2)");
        cAmount.setCoordinates(Coordinate.span(2, 8, 2, 16, "h0"));
        DbObject rawOrders = new DbObject();
        rawOrders.setId("100");
        rawOrders.setDatabase("dw");
        rawOrders.setSchema("raw");
        rawOrders.setName("raw_orders");
        rawOrders.setType("table");
        rawOrders.setCoordinates(Coordinate.span(2, 22, 2, 38, "h0"));
        rawOrders.setColumns(List.of(cAmount));

        // Table 150: raw.raw_fx_rates(rate [151]) -> indirect FDR filter source
        Column cRate = new Column("151", "rate");
        DbObject rawFx = new DbObject();
        rawFx.setId("150");
        rawFx.setDatabase("dw");
        rawFx.setSchema("raw");
        rawFx.setName("raw_fx_rates");
        rawFx.setType("table");
        rawFx.setColumns(List.of(cRate));

        // Constant pseudo-table 180: $constant('USD' [181])
        Column cConst = new Column("181", "'USD'");
        DbObject constTbl = new DbObject();
        constTbl.setId("180");
        constTbl.setName("$constant");
        constTbl.setType("table");
        constTbl.setColumns(List.of(cConst));

        // CTE 200: RS-CTE_ORDERS(amount [201])
        Column cteAmt = new Column("201", "amount");
        DbObject cteOrders = new DbObject();
        cteOrders.setId("200");
        cteOrders.setName("RS-CTE_ORDERS");
        cteOrders.setType("resultset");
        cteOrders.setSubType("with_cte");
        cteOrders.setColumns(List.of(cteAmt));

        // View 300: mart.v_sales_enriched(amount [301])
        Column vAmt = new Column("301", "amount");
        DbObject viewSales = new DbObject();
        viewSales.setId("300");
        viewSales.setDatabase("dw");
        viewSales.setSchema("mart");
        viewSales.setName("v_sales_enriched");
        viewSales.setType("view");
        viewSales.setColumns(List.of(vAmt));

        // Target Table 400: mart.mart_revenue(gross_rev [401])
        Column mRev = new Column("401", "gross_rev");
        mRev.setDataType("NUMERIC(18,2)");
        DbObject martRev = new DbObject();
        martRev.setId("400");
        martRev.setDatabase("dw");
        martRev.setSchema("mart");
        martRev.setName("mart_revenue");
        martRev.setType("table");
        martRev.setColumns(List.of(mRev));

        SchemaObject rawSchema = new SchemaObject("raw");
        rawSchema.setTables(List.of(rawOrders, rawFx, constTbl));
        rawSchema.setOthers(List.of(cteOrders));

        SchemaObject martSchema = new SchemaObject("mart");
        martSchema.setViews(List.of(viewSales));
        martSchema.setTables(List.of(martRev));

        DatabaseObject dwDb = new DatabaseObject("dw");
        dwDb.setSchemas(List.of(rawSchema, martSchema));

        SqlflowCatalog catalog = new SqlflowCatalog();
        catalog.setDatabases(List.of(dwDb));

        Relationship r1 = makeRel("r1", "fdd", "select", "100", "101", "amount", "200", "201", "amount", null);
        Relationship rConst = makeRel("rConst", "fdd", "select", "180", "181", "'USD'", "200", "201", "amount", null);
        Relationship r2 = makeRel("r2", "fdd", "select", "200", "201", "amount", "300", "301", "amount", null);
        Relationship rFdr = makeRel("rFdr", "fdr", "where", "150", "151", "rate", "300", "301", "amount", null);
        Relationship r4 = makeRel(
                "r4",
                "fdd",
                "insert",
                "300",
                "301",
                "amount",
                "400",
                "401",
                "gross_rev",
                Coordinate.span(2, 8, 2, 14, "h1"));

        DataFlow df = new DataFlow();
        df.setDbobjs(catalog);
        df.setRelationships(List.of(r1, rConst, r2, rFdr, r4));
        return df;
    }

    private static Relationship makeRel(
            String id,
            String type,
            String clause,
            String srcParentId,
            String srcColId,
            String srcColName,
            String tgtParentId,
            String tgtColId,
            String tgtColName,
            List<Coordinate> tgtCoord) {
        Relationship r = new Relationship();
        r.setId(id);
        r.setType(type);
        r.setClause(clause);

        RelationshipElement src = new RelationshipElement();
        src.setId(srcColId);
        src.setColumn(srcColName);
        src.setParentId(srcParentId);
        r.setSources(List.of(src));

        RelationshipElement tgt = new RelationshipElement();
        tgt.setId(tgtColId);
        tgt.setColumn(tgtColName);
        tgt.setParentId(tgtParentId);
        tgt.setCoordinates(tgtCoord);
        r.setTarget(tgt);
        return r;
    }
}
