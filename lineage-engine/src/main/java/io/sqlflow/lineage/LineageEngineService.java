package io.sqlflow.lineage;

import io.sqlflow.contracts.graph.SummaryInfo;
import io.sqlflow.contracts.lineage.CommentBindingBundle;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.DataFlowHandleListener;
import io.sqlflow.contracts.lineage.ErrorItem;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.xml.DlineageXml;
import io.sqlflow.contracts.lineage.xml.DlineageXmlCodec;
import io.sqlflow.contracts.spi.LineageEngineSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.lineage.analyzer.AnalyzerContext;
import io.sqlflow.lineage.analyzer.CommentBindingCollector;
import io.sqlflow.lineage.analyzer.DataFlowMergerAndSummarizer;
import io.sqlflow.lineage.analyzer.DataFlowPostProcessor;
import io.sqlflow.lineage.analyzer.DataFlowSerializer;
import io.sqlflow.lineage.analyzer.DlineageXmlBuilder;
import io.sqlflow.lineage.analyzer.DmlDdlAndProcAnalyzer;
import io.sqlflow.lineage.analyzer.ModelBindingManager;
import io.sqlflow.lineage.catalog.PolymorphicInputLoader;
import io.sqlflow.lineage.catalog.PolymorphicInputLoader.LoadedInput;
import io.sqlflow.lineage.catalog.SqlenvSnapshotCodec;
import io.sqlflow.lineage.lexer.SqlStatementSplitter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Production {@link LineageEngineSpi} implementation ({@code OBL-029..OBL-050}).
 */
public class LineageEngineService implements LineageEngineSpi {

    public LineageEngineService() {
    }

    @Override
    public LineageAnalysisResult analyze(LineageAnalysisRequest request) {
        LineageAnalysisRequest req = request != null ? request : new LineageAnalysisRequest();
        Option opt = req.getOption() != null ? req.getOption() : new Option();

        if (shouldAutoSplitLargeSql(req, opt)) {
            return analyzeSplitBatches(req, opt);
        }
        return analyzeSingleBatch(req, opt);
    }

    @Override
    public DataFlow mergeDataFlows(List<DataFlow> batches, Option option) {
        return DataFlowMergerAndSummarizer.mergeDataFlows(batches, option);
    }

    @Override
    public SummaryInfo summarize(DataFlow dataFlow) {
        return DataFlowMergerAndSummarizer.summarize(dataFlow);
    }

    @Override
    public List<String> splitLargeSql(String sqlText, EDbVendor vendor, int maxBytesPerChunk) {
        EDbVendor effVendor = vendor != null ? vendor : EDbVendor.dbvoracle;
        return SqlStatementSplitter.splitLargeSql(sqlText, effVendor, maxBytesPerChunk);
    }

    private LineageAnalysisResult analyzeSingleBatch(LineageAnalysisRequest req, Option opt) {
        DataFlowHandleListener listener = opt.getHandleListener();
        LoadedInput loaded = PolymorphicInputLoader.load(req);
        if (listener != null) {
            listener.startAnalyze(loaded.units().size());
        }

        ModelBindingManager mgr = new ModelBindingManager(loaded.sqlEnv(), opt);
        for (ErrorItem loaderErr : loaded.errors()) {
            mgr.addErrorItem(loaderErr);
        }

        boolean success = false;
        try {
            AnalyzerContext rootCtx = new AnalyzerContext(mgr, loaded.sqlEnv(), opt, loaded.effectiveVendor());
            DmlDdlAndProcAnalyzer.parseAndAnalyzeUnits(loaded.units(), req.getDelimiter(), rootCtx);

            CommentBindingBundle commentBindings = CommentBindingCollector.collect(loaded.units(), mgr, opt);
            DegradationMode mode = DataFlowPostProcessor.process(mgr, opt);

            DlineageXml dlineageXml = DlineageXmlBuilder.build(mgr, opt, commentBindings);
            DataFlow dataFlow = DataFlowSerializer.toDataFlow(mgr, loaded.effectiveVendor(), opt, commentBindings);
            SummaryInfo summary = DataFlowMergerAndSummarizer.summarize(dataFlow);
            dataFlow.setSummary(summary);

            String xmlOrText = null;
            if (opt.isOutput()) {
                xmlOrText = opt.isTextFormat()
                        ? DataFlowSerializer.formatTextLineage(dataFlow)
                        : DlineageXmlCodec.toXml(dlineageXml);
            }

            byte[] snapshot = SqlenvSnapshotCodec.toSnapshot(loaded.sqlEnv());
            LineageAnalysisResult result = new LineageAnalysisResult(dataFlow, dlineageXml, xmlOrText, snapshot, summary);
            result.setMode(mode);
            if (!mgr.errors().isEmpty()) {
                result.setErrors(new ArrayList<>(mgr.errors()));
            }
            success = mgr.errors().isEmpty();
            return result;
        } finally {
            if (listener != null) {
                listener.endAnalyze(success);
            }
        }
    }

    private boolean shouldAutoSplitLargeSql(LineageAnalysisRequest req, Option opt) {
        if (!opt.isAutoDetectLargeFile() || req.getSqlText() == null || req.getSqlText().isEmpty()) {
            return false;
        }
        int splitMb = opt.getLargeFileSplitSizeMB() > 0 ? opt.getLargeFileSplitSizeMB() : 5;
        long maxBytes = splitMb * 1024L * 1024L;
        return req.getSqlText().getBytes(StandardCharsets.UTF_8).length > maxBytes;
    }

    private LineageAnalysisResult analyzeSplitBatches(LineageAnalysisRequest req, Option opt) {
        EDbVendor vendor = opt.getVendor() != null ? opt.getVendor() : EDbVendor.dbvoracle;
        int maxChunkBytes = Math.max(64 * 1024, opt.getLargeFileSplitSizeMB() * 1024 * 1024);
        List<String> chunks = splitLargeSql(req.getSqlText(), vendor, maxChunkBytes);
        if (chunks.size() <= 1) {
            return analyzeSingleBatch(req, opt);
        }

        byte[] runningSnapshot = req.getSqlenvSnapshot();
        List<DataFlow> batchFlows = new ArrayList<>(chunks.size());
        LineageAnalysisResult lastResult = null;
        for (int i = 0; i < chunks.size(); i++) {
            LineageAnalysisRequest subReq = new LineageAnalysisRequest();
            subReq.setSqlText(chunks.get(i));
            subReq.setOption(opt);
            subReq.setDelimiter(req.getDelimiter());
            if (i == 0) {
                subReq.setInitialCatalog(req.getInitialCatalog());
                subReq.setMetadataJson(req.getMetadataJson());
            }
            subReq.setSqlenvSnapshot(runningSnapshot);
            lastResult = analyzeSingleBatch(subReq, opt);
            runningSnapshot = lastResult.getSqlenvSnapshot();
            batchFlows.add(lastResult.getDataFlow());
        }

        DataFlow merged = mergeDataFlows(batchFlows, opt);
        SummaryInfo summary = merged.getSummary() != null ? merged.getSummary() : summarize(merged);
        DlineageXml xml = lastResult != null ? lastResult.getDlineageXml() : new DlineageXml();
        String xmlStr = opt.isOutput()
                ? (opt.isTextFormat() ? DataFlowSerializer.formatTextLineage(merged) : DlineageXmlCodec.toXml(xml))
                : null;
        LineageAnalysisResult combined = new LineageAnalysisResult(merged, xml, xmlStr, runningSnapshot, summary);
        if (merged.getErrors() != null && !merged.getErrors().isEmpty()) {
            combined.setErrors(merged.getErrors());
        }
        return combined;
    }
}
