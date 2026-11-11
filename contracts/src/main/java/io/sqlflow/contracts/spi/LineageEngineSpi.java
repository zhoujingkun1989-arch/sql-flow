package io.sqlflow.contracts.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import io.sqlflow.contracts.graph.SummaryInfo;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.SqlflowCatalog;
import io.sqlflow.contracts.lineage.xml.DlineageXml;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.util.List;
import java.util.Locale;

/**
 * Frozen Stage 2A SPI for the SQLFlow Lineage Engine ({@code lineage-engine}) ({@code 00 §5.2}, {@code 03 §1–§3}).
 *
 * <p>Analyzes SQL text, structured {@link SqlInfo} units, Grabit JSON, SQLDep JSON, or Exporter Metadata JSON
 * using the 74-field {@link Option} configuration and optional serialized {@code TSQLEnv} catalog state,
 * producing canonical {@link DataFlow}, {@link DlineageXml} ({@code <dlineage>} XML), and updated catalog bytes.
 */
public interface LineageEngineSpi {

    /**
     * Executes static SQL / metadata lineage analysis and returns both JSON {@link DataFlow} and {@code <dlineage>} XML.
     */
    LineageAnalysisResult analyze(LineageAnalysisRequest request);

    /**
     * Merges multiple batch {@link DataFlow} models into a unified deduplicated {@link DataFlow}.
     */
    DataFlow mergeDataFlows(List<DataFlow> batches, Option option);

    /**
     * Computes object/relationship counts and top most-related tables ({@link SummaryInfo}) for a {@link DataFlow}.
     */
    SummaryInfo summarize(DataFlow dataFlow);

    /**
     * Splits a large SQL script along statement/procedure boundaries so each chunk is at most {@code maxBytesPerChunk}
     * UTF-8 bytes without splitting mid-statement or mid-PL/SQL block.
     */
    List<String> splitLargeSql(String sqlText, EDbVendor vendor, int maxBytesPerChunk);

    enum LineageInputType {
        RAW_SQL("raw_sql"),
        SQL_UNITS("sql_units"),
        SQLFLOW_METADATA_JSON("sqlflow_metadata_json"),
        GRABIT_JSON("grabit_json"),
        SQLDEP_JSON("sqldep_json"),
        EXPORTER_METADATA_JSON("exporter_metadata_json");

        private final String wireValue;

        LineageInputType(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String getWireValue() {
            return wireValue;
        }

        @JsonCreator
        public static LineageInputType fromWire(String raw) {
            if (raw == null || raw.isBlank()) {
                return RAW_SQL;
            }
            String norm = raw.trim().toLowerCase(Locale.ROOT);
            for (LineageInputType t : values()) {
                if (t.wireValue.equals(norm) || t.name().toLowerCase(Locale.ROOT).equals(norm)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("Unknown LineageInputType: " + raw);
        }
    }

    enum DegradationMode {
        FULL("full"),
        SUMMARY("summary"),
        TABLE_ONLY("table");

        private final String wireValue;

        DegradationMode(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String getWireValue() {
            return wireValue;
        }

        @JsonCreator
        public static DegradationMode fromWire(String raw) {
            if (raw == null || raw.isBlank()) {
                return FULL;
            }
            String norm = raw.trim().toLowerCase(Locale.ROOT);
            for (DegradationMode m : values()) {
                if (m.wireValue.equals(norm) || m.name().toLowerCase(Locale.ROOT).equals(norm)) {
                    return m;
                }
            }
            throw new IllegalArgumentException("Unknown DegradationMode: " + raw);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class LineageAnalysisRequest {
        private String sqlText;
        private List<SqlInfo> sqlUnits;
        private String metadataJson;
        private LineageInputType inputType = LineageInputType.RAW_SQL;
        private Option option = new Option();
        private byte[] sqlenvSnapshot;
        private SqlflowCatalog initialCatalog;
        private String delimiter;

        public LineageAnalysisRequest() {
        }

        public static LineageAnalysisRequest ofSql(String sqlText, Option option) {
            LineageAnalysisRequest req = new LineageAnalysisRequest();
            req.setSqlText(sqlText);
            req.setOption(option != null ? option : new Option());
            req.setInputType(LineageInputType.RAW_SQL);
            return req;
        }

        public String getSqlText() {
            return sqlText;
        }

        public void setSqlText(String sqlText) {
            this.sqlText = sqlText;
        }

        public List<SqlInfo> getSqlUnits() {
            return sqlUnits;
        }

        public void setSqlUnits(List<SqlInfo> sqlUnits) {
            this.sqlUnits = sqlUnits;
        }

        public String getMetadataJson() {
            return metadataJson;
        }

        public void setMetadataJson(String metadataJson) {
            this.metadataJson = metadataJson;
        }

        public LineageInputType getInputType() {
            return inputType;
        }

        public void setInputType(LineageInputType inputType) {
            this.inputType = inputType != null ? inputType : LineageInputType.RAW_SQL;
        }

        public Option getOption() {
            return option;
        }

        public void setOption(Option option) {
            this.option = option != null ? option : new Option();
        }

        public byte[] getSqlenvSnapshot() {
            return sqlenvSnapshot;
        }

        public void setSqlenvSnapshot(byte[] sqlenvSnapshot) {
            this.sqlenvSnapshot = sqlenvSnapshot;
        }

        public SqlflowCatalog getInitialCatalog() {
            return initialCatalog;
        }

        public void setInitialCatalog(SqlflowCatalog initialCatalog) {
            this.initialCatalog = initialCatalog;
        }

        public String getDelimiter() {
            return delimiter;
        }

        public void setDelimiter(String delimiter) {
            this.delimiter = delimiter;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    class LineageAnalysisResult {
        private DataFlow dataFlow;
        private DlineageXml dlineageXml;
        private String xmlString;
        private byte[] sqlenvSnapshot;
        private SummaryInfo summary;
        private DegradationMode mode = DegradationMode.FULL;
        private List<ErrorItem> errors;

        public LineageAnalysisResult() {
        }

        public LineageAnalysisResult(
                DataFlow dataFlow,
                DlineageXml dlineageXml,
                String xmlString,
                byte[] sqlenvSnapshot,
                SummaryInfo summary) {
            this.dataFlow = dataFlow;
            this.dlineageXml = dlineageXml;
            this.xmlString = xmlString;
            this.sqlenvSnapshot = sqlenvSnapshot;
            this.summary = summary;
        }

        public DataFlow getDataFlow() {
            return dataFlow;
        }

        public void setDataFlow(DataFlow dataFlow) {
            this.dataFlow = dataFlow;
        }

        public DlineageXml getDlineageXml() {
            return dlineageXml;
        }

        public void setDlineageXml(DlineageXml dlineageXml) {
            this.dlineageXml = dlineageXml;
        }

        public String getXmlString() {
            return xmlString;
        }

        public void setXmlString(String xmlString) {
            this.xmlString = xmlString;
        }

        public byte[] getSqlenvSnapshot() {
            return sqlenvSnapshot;
        }

        public void setSqlenvSnapshot(byte[] sqlenvSnapshot) {
            this.sqlenvSnapshot = sqlenvSnapshot;
        }

        public SummaryInfo getSummary() {
            return summary;
        }

        public void setSummary(SummaryInfo summary) {
            this.summary = summary;
        }

        public DegradationMode getMode() {
            return mode;
        }

        public void setMode(DegradationMode mode) {
            this.mode = mode != null ? mode : DegradationMode.FULL;
        }

        public List<ErrorItem> getErrors() {
            return errors;
        }

        public void setErrors(List<ErrorItem> errors) {
            this.errors = errors;
        }
    }
}
