package io.sqlflow.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.graph.DbObjectPosition;
import io.sqlflow.contracts.graph.DboColorPalette;
import io.sqlflow.contracts.graph.DiagnosticCode;
import io.sqlflow.contracts.graph.JoinAnalysisGraph;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.graph.SummaryInfo;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.Orientation;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphAndJoinAnalysisContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sqlflowJsonAndDataFlowPreserveSummaryModeShareIdTableLevelAndUnqiueKey() throws Exception {
        SqlflowJSON.GraphColumn gCol = new SqlflowJSON.GraphColumn();
        gCol.setId("11");
        gCol.setName("order_id");
        gCol.setPrimaryKey(true);
        gCol.setUniqueKey(true);
        gCol.setX(0);
        gCol.setY(28);
        gCol.setWidth(180);
        gCol.setHeight(22);

        SqlflowJSON.GraphTable gTbl = new SqlflowJSON.GraphTable();
        gTbl.setId("10");
        gTbl.setName("orders");
        gTbl.setType("table");
        gTbl.setX(24);
        gTbl.setY(24);
        gTbl.setWidth(180);
        gTbl.setHeight(50);
        gTbl.setColumns(List.of(gCol));

        SqlflowJSON.GraphEdge gEdge = new SqlflowJSON.GraphEdge();
        gEdge.setId("e1");
        gEdge.setSourceId("11");
        gEdge.setTargetId("21");
        gEdge.setType("fdd");
        gEdge.setPoints(List.of(new SqlflowJSON.PortPoint(204, 39), new SqlflowJSON.PortPoint(320, 39)));

        SqlflowJSON.GraphPackage gPkg = new SqlflowJSON.GraphPackage();
        gPkg.setId("p1");
        gPkg.setName("PKG_SALES");
        gPkg.setTables(List.of());

        SummaryInfo summary = new SummaryInfo();
        summary.setTable(4);
        summary.setView(1);
        summary.setColumn(18);
        summary.setRelationship(9);

        SqlflowJSON graphJson = new SqlflowJSON();
        graphJson.setElements(new SqlflowJSON.GraphElements(List.of(gTbl), List.of(gEdge), List.of(gPkg)));
        graphJson.setWidth(640.0);
        graphJson.setHeight(480.0);
        graphJson.setOrientation(Orientation.LEFT_TO_RIGHT);
        graphJson.setMode("summary");
        graphJson.setSummary(summary);
        graphJson.setShareId("share-abc");
        graphJson.setTableLevel(true);
        graphJson.setRelationshipIdMap(Map.of("e1", List.of("rel-1")));
        graphJson.setListIdMap(Map.of("n0", List.of("10")));
        graphJson.setTooltip(Map.of("n0", "sales.public.orders"));
        graphJson.setSvg("<svg/>");
        graphJson.setGraphML("<graphml/>");

        String serialized = mapper.writeValueAsString(graphJson);
        assertThat(serialized).contains("\"unqiueKey\":true");
        assertThat(serialized).doesNotContain("\"uniqueKey\"");

        JsonNode graphRoot = mapper.readTree(serialized);
        assertThat(graphRoot.get("mode").asText()).isEqualTo("summary");
        assertThat(graphRoot.get("shareId").asText()).isEqualTo("share-abc");
        assertThat(graphRoot.get("tableLevel").asBoolean()).isTrue();
        assertThat(graphRoot.get("summary").get("table").asInt()).isEqualTo(4);

        SqlflowJSON deserialized = mapper.readValue(serialized, SqlflowJSON.class);
        assertThat(deserialized.resolveElements().getTables()).hasSize(1);
        assertThat(deserialized.resolveElements().getEdges()).hasSize(1);
        assertThat(deserialized.resolveElements().getPackages()).hasSize(1);
        assertThat(deserialized.getOrientation()).isEqualTo(Orientation.LEFT_TO_RIGHT);
        assertThat(deserialized.getMode()).isEqualTo("summary");
        assertThat(deserialized.getShareId()).isEqualTo("share-abc");
        assertThat(deserialized.getTableLevel()).isTrue();
        assertThat(deserialized.getSummary().getTable()).isEqualTo(4);
        assertThat(deserialized.getRelationshipIdMap()).containsEntry("e1", List.of("rel-1"));
        assertThat(deserialized.getListIdMap()).containsEntry("n0", List.of("10"));
        assertThat(deserialized.getTooltip()).containsEntry("n0", "sales.public.orders");
        assertThat(deserialized.getSvg()).isEqualTo("<svg/>");
        assertThat(deserialized.getGraphML()).isEqualTo("<graphml/>");

        DataFlow df = new DataFlow();
        df.setSummary(summary);
        DataFlow parsedDf = mapper.readValue(mapper.writeValueAsString(df), DataFlow.class);
        assertThat(parsedDf.getSummary().getView()).isEqualTo(1);
        assertThat(parsedDf.getSummary().getColumn()).isEqualTo(18);
    }

    @Test
    void leftMostSourceTableSupportsBothTableFlowAndAttributesClosureShapes() throws Exception {
        LeftMostSourceTable.SourceTableColumn srcCol = new LeftMostSourceTable.SourceTableColumn();
        srcCol.setDatabase("dw");
        srcCol.setSchema("raw");
        srcCol.setTableName("orders_raw");
        srcCol.setTableAlias("o");
        srcCol.setName("amount");
        srcCol.setType("table");
        srcCol.setClause("select");
        srcCol.setRelationType("fdd");

        LeftMostSourceTable.TargetColumn tgtCol = new LeftMostSourceTable.TargetColumn();
        tgtCol.setId("201");
        tgtCol.setName("total_amount");
        tgtCol.setDataType("DECIMAL(18,2)");
        tgtCol.setSourceColumns(List.of(srcCol));

        LeftMostSourceTable.TargetTable tgtTable = new LeftMostSourceTable.TargetTable();
        tgtTable.setId("200");
        tgtTable.setDatabase("dw");
        tgtTable.setSchema("mart");
        tgtTable.setName("daily_revenue");
        tgtTable.setAlias("dr");
        tgtTable.setType("view");
        tgtTable.setColumns(List.of(tgtCol));

        LeftMostSourceTable.TableColumn rootAttr = new LeftMostSourceTable.TableColumn();
        rootAttr.setServer("snowflake-prod");
        rootAttr.setDatabaseName("DW");
        rootAttr.setSchemaName("RAW");
        rootAttr.setRelationName("ORDERS_RAW");
        rootAttr.setAttributeName("AMOUNT");

        LeftMostSourceTable.TableColumn leafAttr = new LeftMostSourceTable.TableColumn();
        leafAttr.setServer("snowflake-prod");
        leafAttr.setUserName("ETL");
        leafAttr.setDatabaseName("DW");
        leafAttr.setSchemaName("MART");
        leafAttr.setRelationName("DAILY_REVENUE");
        leafAttr.setAttributeName("TOTAL_AMOUNT");
        leafAttr.setTimestampMin("2026-01-01T00:00:00Z");
        leafAttr.setTimestampMax("2026-01-02T00:00:00Z");
        leafAttr.setUpstreamAttributesIndirect(
                new LeftMostSourceTable.UpstreamAttributesIndirect(List.of(rootAttr)));

        LeftMostSourceTable closure = new LeftMostSourceTable();
        closure.setElements(List.of(tgtTable));
        closure.setTargetTables(List.of(tgtTable));
        closure.setAttributes(List.of(leafAttr));

        String json = mapper.writeValueAsString(closure);
        JsonNode root = mapper.readTree(json);
        assertThat(root.get("elements").get(0).get("alias").asText()).isEqualTo("dr");
        assertThat(root.get("elements")
                        .get(0)
                        .get("columns")
                        .get(0)
                        .get("sourceColumns")
                        .get(0)
                        .get("tableName")
                        .asText())
                .isEqualTo("orders_raw");
        assertThat(root.get("attributes").isArray()).isTrue();
        assertThat(root.get("attributes")
                        .get(0)
                        .get("upstreamAttributesIndirect")
                        .get("nodes")
                        .get(0)
                        .get("relationName")
                        .asText())
                .isEqualTo("ORDERS_RAW");

        LeftMostSourceTable parsed = mapper.readValue(json, LeftMostSourceTable.class);
        assertThat(parsed.getElements().get(0).getColumns().get(0).getDataType())
                .isEqualTo("DECIMAL(18,2)");
        assertThat(parsed.getAttributes()
                        .get(0)
                        .getUpstreamAttributesIndirect()
                        .getNodes()
                        .get(0)
                        .getAttributeName())
                .isEqualTo("AMOUNT");
    }

    @Test
    void dboColorPaletteAndDbObjectPositionFollowWireRules() throws Exception {
        assertThat(DboColorPalette.resolveHeaderColor("table", null)).isEqualTo("#91c051");
        assertThat(DboColorPalette.resolveHeaderColor("view", null)).isEqualTo("#419a5f");
        assertThat(DboColorPalette.resolveHeaderColor("resultset", "with_cte")).isEqualTo("#ff6e97");
        assertThat(DboColorPalette.resolveHeaderColor("resultset", "insert-select")).isEqualTo("#b24b78");
        assertThat(DboColorPalette.resolveHeaderColor("procedure", null)).isEqualTo("#dbdada");
        assertThat(DboColorPalette.CANVAS_BACKGROUND).isEqualTo("#eeeeee");
        assertThat(DboColorPalette.EDGE_STROKE).isEqualTo("#ababab");

        DbObjectPosition pos = mapper.readValue(
                "{\"file\":\"q1.sql\",\"sql\":\"SELECT a FROM t\",\"index\":0,\"positions\":[[1,8],[1,9]]}",
                DbObjectPosition.class);
        assertThat(pos.toSpanPairs()).isEqualTo(List.of(List.of(List.of(1, 8), List.of(1, 9))));
    }

    @Test
    void joinAnalysisGraphEnforcesExactV1XyFlowWireRecordsAndAll10DiagnosticCodes() throws Exception {
        assertThat(DiagnosticCode.values()).hasSize(10);
        assertThat(DiagnosticCode.COMPLEX_PREDICATE.getDefaultSeverity())
                .isEqualTo(JoinAnalysisGraph.Severity.WARN);
        List<String> wireCodes = java.util.Arrays.stream(DiagnosticCode.values())
                .map(DiagnosticCode::getWireValue)
                .toList();
        assertThat(wireCodes).containsExactly(
                "missing_join_condition",
                "cartesian_join",
                "many_to_many_risk",
                "outer_join_filter_risk",
                "anti_join_detected",
                "self_join_detected",
                "semi_join_detected",
                "non_indexed_join_key",
                "complex_predicate",
                "unparsed_condition");

        JoinAnalysisGraph graph = new JoinAnalysisGraph();
        assertThat(graph.getSchemaVersion()).isEqualTo("join-analysis.v1");
        graph.setGraphId("jg-1");
        graph.setDbvendor(EDbVendor.dbvpostgresql);
        graph.setSqlText("SELECT o.id FROM orders o INNER JOIN customers c ON o.cid = c.id");

        // Layout: engine, direction, rankSpacing, nodeSpacing (NO primitive width/height)
        graph.setLayout(new JoinAnalysisGraph.Layout(
                JoinAnalysisGraph.LayoutEngine.ELK, JoinAnalysisGraph.LayoutDirection.RIGHT, 120, 60));

        // QueryBlockLane
        JoinAnalysisGraph.SourceRange mainRange = new JoinAnalysisGraph.SourceRange(
                1, 1, 1, 65, JoinAnalysisGraph.SourceRangeQuality.EXACT);
        JoinAnalysisGraph.QueryBlockLane lane = new JoinAnalysisGraph.QueryBlockLane();
        lane.setQueryBlockId("qb-main");
        lane.setLabel("Main Query");
        lane.setKind(JoinAnalysisGraph.LaneKind.MAIN);
        lane.setSourceRange(mainRange);
        lane.setCollapsedByDefault(false);
        graph.setQueryBlocks(List.of(lane));

        // Table node with TableData & ColumnContribution
        JoinAnalysisGraph.TableData tableData = new JoinAnalysisGraph.TableData();
        tableData.setTableId("t-orders");
        tableData.setName("orders");
        tableData.setAlias("o");
        tableData.setColumnsContributed(
                List.of(new JoinAnalysisGraph.ColumnContribution("orders", "cid")));
        tableData.setIsSelfJoinInstance(true);

        JoinAnalysisGraph.NodeData tableNodeData = new JoinAnalysisGraph.NodeData();
        tableNodeData.setLabel("orders (o)");
        tableNodeData.setQueryBlockId("qb-main");
        tableNodeData.setFragmentId("frag-1");
        tableNodeData.setTable(tableData);

        JoinAnalysisGraph.JoinAnalysisNode tableNode = new JoinAnalysisGraph.JoinAnalysisNode(
                "n-orders",
                JoinAnalysisGraph.JoinNodeType.TABLE,
                new JoinAnalysisGraph.Position(40.0, 80.0),
                tableNodeData);

        // Join node with JoinDetail (wire key "clause"), JoinPredicate, ColumnRef, JoinBadge
        JoinAnalysisGraph.ColumnRef leftRef = new JoinAnalysisGraph.ColumnRef();
        leftRef.setTableId("t-orders");
        leftRef.setTableName("orders");
        leftRef.setAlias("o");
        leftRef.setColumn("cid");
        leftRef.setDisplayName("o.cid");
        leftRef.setSourceRange(mainRange);
        leftRef.setIsPrimaryKey(false);
        leftRef.setIsUniqueKey(false);
        leftRef.setIsIndexed(true);

        JoinAnalysisGraph.ColumnRef rightRef = new JoinAnalysisGraph.ColumnRef();
        rightRef.setTableId("t-customers");
        rightRef.setTableName("customers");
        rightRef.setAlias("c");
        rightRef.setColumn("id");
        rightRef.setIsPrimaryKey(true);
        rightRef.setIsUniqueKey(true);
        rightRef.setIsIndexed(true);

        JoinAnalysisGraph.JoinPredicate pred = new JoinAnalysisGraph.JoinPredicate();
        pred.setPredicateId("p-1");
        pred.setRaw("o.cid = c.id");
        pred.setKind(JoinAnalysisGraph.PredicateKind.EQUI);
        pred.setOp("=");
        pred.setLeft(leftRef);
        pred.setRight(rightRef);
        pred.setSourceRange(mainRange);

        JoinAnalysisGraph.JoinDetail joinDetail = new JoinAnalysisGraph.JoinDetail();
        joinDetail.setJoinType(JoinAnalysisGraph.JoinType.INNER);
        joinDetail.setPatterns(List.of(JoinAnalysisGraph.JoinPattern.SELF));
        joinDetail.setClause(JoinAnalysisGraph.JoinClause.ON);
        joinDetail.setPredicates(List.of(pred));
        joinDetail.setOrder(1);
        joinDetail.setCardinalityHint(JoinAnalysisGraph.CardinalityHint.MANY_TO_ONE);
        joinDetail.setSourceRange(mainRange);

        JoinAnalysisGraph.NodeData joinNodeData = new JoinAnalysisGraph.NodeData();
        joinNodeData.setLabel("INNER JOIN");
        joinNodeData.setSummary("o.cid = c.id");
        joinNodeData.setQueryBlockId("qb-main");
        joinNodeData.setJoin(joinDetail);
        joinNodeData.setBadges(List.of(new JoinAnalysisGraph.JoinBadge(
                JoinAnalysisGraph.BadgeKind.FAN_OUT, JoinAnalysisGraph.FanOutLevel.LOW, true)));

        JoinAnalysisGraph.JoinAnalysisNode joinNode = new JoinAnalysisGraph.JoinAnalysisNode(
                "n-join-1",
                JoinAnalysisGraph.JoinNodeType.JOIN,
                new JoinAnalysisGraph.Position(260.0, 80.0),
                joinNodeData);

        graph.setNodes(List.of(tableNode, joinNode));

        // JoinAnalysisEdge & EdgeData
        JoinAnalysisGraph.EdgeData edgeData = new JoinAnalysisGraph.EdgeData();
        edgeData.setKind(JoinAnalysisGraph.JoinEdgeKind.TABLE_TO_JOIN);
        edgeData.setColumns(List.of(new JoinAnalysisGraph.ColumnContribution("orders", "cid")));
        edgeData.setNote("left input");
        edgeData.setDashed(false);
        edgeData.setAnimated(true);
        graph.setEdges(List.of(
                new JoinAnalysisGraph.JoinAnalysisEdge("e-1", "n-orders", "n-join-1", edgeData)));

        // SqlFragment
        JoinAnalysisGraph.SqlFragment frag = new JoinAnalysisGraph.SqlFragment();
        frag.setFragmentId("frag-1");
        frag.setNodeIds(List.of("n-orders"));
        frag.setKind(JoinAnalysisGraph.FragmentKind.TABLE_REF);
        frag.setText("orders o");
        frag.setSourceRange(mainRange);
        graph.setFragments(List.of(frag));

        // JoinDiagnostic
        JoinAnalysisGraph.JoinDiagnostic diag = new JoinAnalysisGraph.JoinDiagnostic();
        diag.setId("d1");
        diag.setSeverity(JoinAnalysisGraph.Severity.BLOCK);
        diag.setRoles(List.of(JoinAnalysisGraph.Role.ALL));
        diag.setCode(DiagnosticCode.CARTESIAN_JOIN);
        diag.setMessage("Cartesian join detected");
        diag.setEvidenceNodeIds(List.of("n-join-1"));
        diag.setFragmentIds(List.of("frag-1"));
        diag.setPredicateIds(List.of("p-1"));
        diag.setEdgeIds(List.of("e-1"));
        graph.setDiagnostics(List.of(diag));

        // Fallback
        graph.setFallback(new JoinAnalysisGraph.Fallback(
                JoinAnalysisGraph.FallbackSource.LEGACY_GRAPH, List.of("missing catalog keys")));

        String json = mapper.writeValueAsString(graph);
        JsonNode root = mapper.readTree(json);
        assertThat(root.has("code")).isFalse();
        assertThat(root.has("data")).isFalse();
        assertThat(root.get("schemaVersion").asText()).isEqualTo("join-analysis.v1");

        // Verify Layout has no primitive width/height
        assertThat(root.get("layout").get("engine").asText()).isEqualTo("elk");
        assertThat(root.get("layout").get("direction").asText()).isEqualTo("RIGHT");
        assertThat(root.get("layout").get("rankSpacing").asInt()).isEqualTo(120);
        assertThat(root.get("layout").has("width")).isFalse();
        assertThat(root.get("layout").has("height")).isFalse();

        // Verify QueryBlockLane, NodeData, TableData, JoinDetail ("clause"), ColumnRef, EdgeData, Fallback
        assertThat(root.get("queryBlocks").get(0).get("queryBlockId").asText()).isEqualTo("qb-main");
        assertThat(root.get("nodes").get(0).get("position").get("x").asDouble()).isEqualTo(40.0);
        assertThat(root.get("nodes")
                        .get(0)
                        .get("data")
                        .get("table")
                        .get("isSelfJoinInstance")
                        .asBoolean())
                .isTrue();
        JsonNode joinJson = root.get("nodes").get(1).get("data").get("join");
        assertThat(joinJson.get("clause").asText()).isEqualTo("ON");
        assertThat(joinJson.has("joinClause")).isFalse();
        JsonNode leftColJson = joinJson.get("predicates").get(0).get("left");
        assertThat(leftColJson.get("isPrimaryKey").asBoolean()).isFalse();
        assertThat(leftColJson.get("isUniqueKey").asBoolean()).isFalse();
        assertThat(leftColJson.get("isIndexed").asBoolean()).isTrue();
        assertThat(root.get("edges").get(0).get("source").asText()).isEqualTo("n-orders");
        assertThat(root.get("edges").get(0).get("data").get("kind").asText()).isEqualTo("table_to_join");
        assertThat(root.get("fragments").get(0).get("fragmentId").asText()).isEqualTo("frag-1");
        assertThat(root.get("diagnostics").get(0).get("code").asText()).isEqualTo("cartesian_join");
        assertThat(root.get("diagnostics").get(0).get("evidenceNodeIds").get(0).asText())
                .isEqualTo("n-join-1");
        assertThat(root.get("fallback").get("source").asText()).isEqualTo("legacy_graph");
        assertThat(root.get("fallback").get("limitations").get(0).asText())
                .isEqualTo("missing catalog keys");

        JoinAnalysisGraph parsed = mapper.readValue(json, JoinAnalysisGraph.class);
        assertThat(parsed.getSchemaVersion()).isEqualTo("join-analysis.v1");
        assertThat(parsed.getLayout().getRankSpacing()).isEqualTo(120);
        assertThat(parsed.getNodes().get(0).getData().getTable().getIsSelfJoinInstance()).isTrue();
        assertThat(parsed.getNodes().get(1).getData().getJoin().getClause())
                .isEqualTo(JoinAnalysisGraph.JoinClause.ON);
        assertThat(parsed.getNodes()
                        .get(1)
                        .getData()
                        .getJoin()
                        .getPredicates()
                        .get(0)
                        .getRight()
                        .getIsPrimaryKey())
                .isTrue();
        assertThat(parsed.getDiagnostics().get(0).getCode()).isEqualTo(DiagnosticCode.CARTESIAN_JOIN);
        assertThat(parsed.getFallback().getSource())
                .isEqualTo(JoinAnalysisGraph.FallbackSource.LEGACY_GRAPH);
    }
}
