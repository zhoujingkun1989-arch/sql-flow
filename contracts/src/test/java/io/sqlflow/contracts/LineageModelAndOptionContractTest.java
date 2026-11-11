package io.sqlflow.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.lineage.AnalyzeMode;
import io.sqlflow.contracts.lineage.CandidateTable;
import io.sqlflow.contracts.lineage.Column;
import io.sqlflow.contracts.lineage.Coordinate;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DatabaseObject;
import io.sqlflow.contracts.lineage.DbObject;
import io.sqlflow.contracts.lineage.DynamicSqlTrustMode;
import io.sqlflow.contracts.lineage.EffectType;
import io.sqlflow.contracts.lineage.EndpointIntroduction;
import io.sqlflow.contracts.lineage.EndpointKind;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.lineage.JoinRelationship;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.Orientation;
import io.sqlflow.contracts.lineage.RelationshipElement;
import io.sqlflow.contracts.lineage.RelationshipType;
import io.sqlflow.contracts.lineage.ResultSetType;
import io.sqlflow.contracts.lineage.SchemaObject;
import io.sqlflow.contracts.lineage.ServerObject;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.lineage.SubType;
import io.sqlflow.contracts.lineage.Transform;
import io.sqlflow.contracts.lineage.xml.DlineageXml;
import io.sqlflow.contracts.lineage.xml.DlineageXmlCodec;
import io.sqlflow.contracts.lineage.xml.XmlColumn;
import io.sqlflow.contracts.lineage.xml.XmlEndpoint;
import io.sqlflow.contracts.lineage.xml.XmlError;
import io.sqlflow.contracts.lineage.xml.XmlPackage;
import io.sqlflow.contracts.lineage.xml.XmlProcedure;
import io.sqlflow.contracts.lineage.xml.XmlRelationship;
import io.sqlflow.contracts.lineage.xml.XmlTable;
import io.sqlflow.contracts.lineage.xml.XmlTransform;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineageModelAndOptionContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void columnSerializesMisspelledUnqiueKeyAndAcceptsBothAliases() throws Exception {
        Column col = new Column("101", "customer_id");
        col.setPrimaryKey(true);
        col.setUniqueKey(true);
        col.setDataType("NUMBER(10)");

        String json = mapper.writeValueAsString(col);
        assertThat(json).contains("\"unqiueKey\":true");
        assertThat(json).doesNotContain("\"uniqueKey\"");

        Column fromMisspelled = mapper.readValue(
                "{\"id\":\"101\",\"name\":\"customer_id\",\"unqiueKey\":true}", Column.class);
        assertThat(fromMisspelled.getUnqiueKey()).isTrue();
        assertThat(fromMisspelled.getUniqueKey()).isTrue();

        Column fromCorrectSpelling = mapper.readValue(
                "{\"id\":\"102\",\"name\":\"email\",\"uniqueKey\":true}", Column.class);
        assertThat(fromCorrectSpelling.getUnqiueKey()).isTrue();
        assertThat(fromCorrectSpelling.getUniqueKey()).isTrue();
    }

    @Test
    void lineageEnumsMatchExactWireCountsAndValues() {
        assertThat(RelationshipType.values()).hasSize(8);
        assertThat(EffectType.values()).hasSize(36);
        assertThat(ResultSetType.values()).hasSize(18);
        assertThat(SubType.values()).hasSize(14);
        assertThat(EndpointKind.values()).hasSize(12);
        assertThat(EndpointIntroduction.values()).hasSize(11);
        assertThat(Orientation.values()).hasSize(4);

        assertThat(ResultSetType.insert_select.wireType()).isEqualTo("insert-select");
        assertThat(ResultSetType.cte.wireType()).isEqualTo("with_cte");
        assertThat(ResultSetType.of("with_cte")).isEqualTo(ResultSetType.cte);
        assertThat(RelationshipType.collapseTransitive(RelationshipType.fdd, RelationshipType.fdr))
                .isEqualTo(RelationshipType.fdr);
        assertThat(EffectType.merge_update.wireName()).isEqualTo("merge_update");
    }

    @Test
    void optionDeclaresAll74FieldsWithExactDefaultsAndRoundTripsJson() throws Exception {
        List<Field> instanceFields = Arrays.stream(Option.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic() && !Modifier.isStatic(f.getModifiers()))
                .toList();
        assertThat(instanceFields).hasSize(74);

        Option opt = new Option();
        assertThat(opt.getVendor()).isNull();
        assertThat(opt.isSimpleOutput()).isFalse();
        assertThat(opt.isIgnoreRecordSet()).isFalse();
        assertThat(opt.isOutput()).isTrue();
        assertThat(opt.isIgnoreUnusedSynonym()).isTrue();
        assertThat(opt.isIgnoreInsertIntoValues()).isTrue();
        assertThat(opt.isShowCountTableColumn()).isTrue();
        assertThat(opt.isShowCaseWhenAsDirect()).isTrue();
        assertThat(opt.isShowCandidateTable()).isTrue();
        assertThat(opt.isTransform()).isFalse();
        assertThat(opt.isTransformCoordinate()).isFalse();
        assertThat(opt.isTextFormat()).isFalse();
        assertThat(opt.isLinkOrphanColumnToFirstTable()).isFalse();
        assertThat(opt.isShowCallRelation()).isFalse();
        assertThat(opt.isSqlflowIgnoreFunction()).isFalse();
        assertThat(opt.isShowERDiagram()).isFalse();
        assertThat(opt.isIgnoreCoordinate()).isFalse();
        assertThat(opt.isShowConstantTable()).isFalse();
        assertThat(opt.isTraceProcedure()).isFalse();
        assertThat(opt.getCommentBindingMaxRawLength()).isEqualTo(8192);
        assertThat(opt.getCommentBindingMaxCommentsPerSourceUnit()).isEqualTo(50000);
        assertThat(opt.getCommentBindingMaxTotalRawLength()).isEqualTo(4194304);
        assertThat(opt.getDynamicSqlTrustMode()).isEqualTo(DynamicSqlTrustMode.LEGACY);
        assertThat(opt.isAnalyzeDynamicSql()).isTrue();
        assertThat(opt.isAssumeExternalScriptPassthrough()).isTrue();
        assertThat(opt.isEnablePipelinedStitching()).isTrue();
        assertThat(opt.getMaxPipelinedExpansionDepth()).isEqualTo(8);
        assertThat(opt.getMaxStitchedSourcesPerColumn()).isEqualTo(64);
        assertThat(opt.getLargeSqlInfoCountThreshold()).isEqualTo(1000);
        assertThat(opt.getLargeSqlTotalSizeThreshold()).isEqualTo(26214400L);
        assertThat(opt.getLargeFileSplitSizeMB()).isEqualTo(5);
        assertThat(opt.getEstimatedMemoryPerTaskMB()).isEqualTo(2560L);

        opt.setVendor(EDbVendor.dbvpostgresql);
        opt.setAnalyzeMode(AnalyzeMode.dataflow);
        opt.setTransform(true);
        opt.setDefaultDatabase("analytics");
        opt.setDefaultSchema("public");
        opt.applyOnlyDirect();

        String json = mapper.writeValueAsString(opt);
        Option roundTripped = mapper.readValue(json, Option.class);
        assertThat(roundTripped.getVendor()).isEqualTo(EDbVendor.dbvpostgresql);
        assertThat(roundTripped.getAnalyzeMode()).isEqualTo(AnalyzeMode.dataflow);
        assertThat(roundTripped.isTransform()).isTrue();
        assertThat(roundTripped.getDefaultDatabase()).isEqualTo("analytics");
        assertThat(roundTripped.getDefaultSchema()).isEqualTo("public");
        assertThat(roundTripped.getFilterRelationTypes()).containsExactly("fdd");
    }

    @Test
    void coordinateSupportsOneBasedObjectsTuplesAndXmlFormatting() throws Exception {
        Coordinate c1 = mapper.readValue("{\"x\":12,\"y\":8,\"hashCode\":\"0\"}", Coordinate.class);
        assertThat(c1.getX()).isEqualTo(12L);
        assertThat(c1.getY()).isEqualTo(8L);
        assertThat(c1.getHashCode()).isEqualTo("0");
        assertThat(c1.isOneBasedValid()).isTrue();

        Coordinate tuple2 = mapper.readValue("[12, 19]", Coordinate.class);
        assertThat(tuple2).isEqualTo(new Coordinate(12L, 19L));

        Coordinate tuple3 = mapper.readValue("[12, 19, \"abc\"]", Coordinate.class);
        assertThat(tuple3).isEqualTo(new Coordinate(12L, 19L, "abc"));

        Coordinate singleSpan = mapper.readValue("{\"x\":3,\"y\":5,\"x1\":3,\"y1\":14}", Coordinate.class);
        assertThat(singleSpan.getX1()).isEqualTo(3L);
        assertThat(singleSpan.getY1()).isEqualTo(14L);

        List<Coordinate> span = Coordinate.span(12L, 8L, 12L, 19L, "0");
        assertThat(Coordinate.toLineColTuples(span)).isEqualTo(List.of(List.of(12L, 8L), List.of(12L, 19L)));
        String xmlCoord = Coordinate.formatXmlCoordinate(span);
        assertThat(xmlCoord).isEqualTo("[12,8,0],[12,19,0]");
        assertThat(Coordinate.parseXmlCoordinate(xmlCoord)).isEqualTo(span);
    }

    @Test
    void dataFlowAndDlineageXmlRoundTripPreservingUnqiueKeyAndTagOrder() throws Exception {
        Column srcCol = new Column("11", "amount");
        srcCol.setUniqueKey(true);
        DbObject srcTable = new DbObject("10", "orders", "table");
        srcTable.setDatabase("sales");
        srcTable.setSchema("public");
        srcTable.setColumns(List.of(srcCol));
        srcTable.setCoordinates(Coordinate.span(2, 14, 2, 20, "1"));

        SchemaObject schemaObj = new SchemaObject("public");
        schemaObj.setTables(List.of(srcTable));
        DatabaseObject dbObj = new DatabaseObject("sales");
        dbObj.setSchemas(List.of(schemaObj));
        ServerObject serverObj = new ServerObject("default", "dbvpostgresql", true, true);
        serverObj.setDatabases(List.of(dbObj));

        SqlflowCatalog catalog = new SqlflowCatalog();
        catalog.setServers(List.of(serverObj));
        catalog.setDatabases(List.of(dbObj));

        RelationshipElement targetEl = new RelationshipElement("21", "total_amount", "20", "summary_view");
        targetEl.setTransforms(List.of(new Transform("expression", "SUM(amount)", Coordinate.span(1, 8, 1, 19, "1"))));
        RelationshipElement sourceEl = new RelationshipElement("11", "amount", "10", "orders");
        sourceEl.setCandidateParents(List.of(new CandidateTable("10", "orders")));

        JoinRelationship joinRel = new JoinRelationship();
        joinRel.setId("rel-1");
        joinRel.setEffectType(EffectType.select.wireName());
        joinRel.setTarget(targetEl);
        joinRel.setSources(List.of(sourceEl));
        joinRel.setJoinType("INNER");
        joinRel.setCondition("o.id = c.order_id");

        DataFlow df = new DataFlow();
        df.setDbvendor(EDbVendor.dbvpostgresql.wireName());
        df.setDbobjs(catalog);
        df.setRelationships(List.of(joinRel));
        df.setOrientation(Orientation.LEFT_TO_RIGHT);
        df.setErrors(List.of(new ErrorItem("SyntaxWarning", "Sample warning", Coordinate.span(4, 1, 4, 10, "1"))));

        String dfJson = mapper.writeValueAsString(df);
        JsonNode root = mapper.readTree(dfJson);
        assertThat(root.get("dbvendor").asText()).isEqualTo("dbvpostgresql");
        assertThat(dfJson).contains("\"unqiueKey\":true");
        assertThat(dfJson).doesNotContain("\"uniqueKey\"");

        DataFlow parsedDf = mapper.readValue(dfJson, DataFlow.class);
        assertThat(parsedDf.getDbvendor()).isEqualTo("dbvpostgresql");
        assertThat(parsedDf.getRelationships()).hasSize(1);
        assertThat(parsedDf.getRelationships().get(0)).isInstanceOf(JoinRelationship.class);
        assertThat(parsedDf.getDbobjs()
                        .getDatabases()
                        .get(0)
                        .getSchemas()
                        .get(0)
                        .getTables()
                        .get(0)
                        .getColumns()
                        .get(0)
                        .getUnqiueKey())
                .isTrue();

        // <dlineage> XML round-trip & tag ordering
        DlineageXml xmlModel = new DlineageXml();
        XmlPackage pkg = new XmlPackage();
        pkg.setId("1");
        pkg.setName("PKG_FINANCE");
        xmlModel.setPackages(List.of(pkg));

        XmlProcedure proc = new XmlProcedure();
        proc.setId("2");
        proc.setName("CALC_TOTAL");
        xmlModel.setProcedures(List.of(proc));

        XmlColumn xmlCol = new XmlColumn("11", "amount");
        xmlCol.setUniqueKey(true);
        xmlCol.setCoordinate("[1,8,0],[1,14,0]");
        XmlTable xmlTable = new XmlTable("10", "orders", "table");
        xmlTable.setColumns(List.of(xmlCol));
        xmlModel.setTables(List.of(xmlTable));

        XmlTable xmlRs = new XmlTable("20", "RS-1", "select_list");
        xmlModel.setResultsets(List.of(xmlRs));

        XmlRelationship xmlRel = new XmlRelationship();
        xmlRel.setId("100");
        xmlRel.setType("fdd");
        xmlRel.setEffectType("select");
        XmlEndpoint xmlTarget = new XmlEndpoint();
        xmlTarget.setId("21");
        xmlTarget.setColumn("total_amount");
        xmlTarget.setTransforms(List.of(new XmlTransform("expression", "SUM(amount)", "[1,8,0],[1,19,0]")));
        xmlRel.setTarget(xmlTarget);
        XmlEndpoint xmlSource = new XmlEndpoint();
        xmlSource.setId("11");
        xmlSource.setColumn("amount");
        xmlRel.setSources(List.of(xmlSource));
        xmlModel.setRelationships(List.of(xmlRel));

        xmlModel.setErrors(List.of(new XmlError("SyntaxError", "[5,1,0],[5,6,0]", "Unexpected token")));
        xmlModel.setOrientation(Orientation.LEFT_TO_RIGHT);

        String xmlText = DlineageXmlCodec.toXml(xmlModel);
        assertThat(xmlText).contains("<dlineage>");
        assertThat(xmlText).contains("unqiueKey=\"true\"");
        assertThat(xmlText).doesNotContain("uniqueKey=");

        int pkgIdx = xmlText.indexOf("<package");
        int procIdx = xmlText.indexOf("<procedure");
        int tblIdx = xmlText.indexOf("<table");
        int rsIdx = xmlText.indexOf("<resultset");
        int relIdx = xmlText.indexOf("<relationship");
        int errIdx = xmlText.indexOf("<error");
        int oriIdx = xmlText.indexOf("<orientation>");
        assertThat(pkgIdx).isLessThan(procIdx);
        assertThat(procIdx).isLessThan(tblIdx);
        assertThat(tblIdx).isLessThan(rsIdx);
        assertThat(rsIdx).isLessThan(relIdx);
        assertThat(relIdx).isLessThan(errIdx);
        assertThat(errIdx).isLessThan(oriIdx);

        DlineageXml parsedXml = DlineageXmlCodec.fromXml(xmlText);
        assertThat(parsedXml.getTables()).hasSize(1);
        assertThat(parsedXml.getTables().get(0).getColumns().get(0).getUnqiueKey()).isTrue();
        assertThat(parsedXml.getRelationships()).hasSize(1);
        assertThat(parsedXml.getRelationships().get(0).getTarget().getTransforms()).hasSize(1);
        assertThat(parsedXml.getErrors().get(0).getErrorMessage().trim()).isEqualTo("Unexpected token");
        assertThat(parsedXml.getOrientation()).isEqualTo(Orientation.LEFT_TO_RIGHT);
    }
}
