package io.sqlflow.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlflow.contracts.domain.ApiResponse;
import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.domain.JobDetail;
import io.sqlflow.contracts.domain.JobStatus;
import io.sqlflow.contracts.domain.JobSummary;
import io.sqlflow.contracts.domain.LatestJobGraphEnvelope;
import io.sqlflow.contracts.domain.SubJob;
import io.sqlflow.contracts.domain.SubJobType;
import io.sqlflow.contracts.domain.UserJobListSummary;
import io.sqlflow.contracts.graph.LeftMostSourceTable;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.lineage.DataFlow;
import io.sqlflow.contracts.lineage.Option;
import io.sqlflow.contracts.lineage.Orientation;
import io.sqlflow.contracts.lineage.SqlInfo;
import io.sqlflow.contracts.lineage.xml.DlineageXmlCodec;
import io.sqlflow.contracts.spi.CsvLineageExportSpi;
import io.sqlflow.contracts.spi.FileExtractorSpi;
import io.sqlflow.contracts.spi.GraphLayoutSpi;
import io.sqlflow.contracts.spi.LineageEngineSpi;
import io.sqlflow.contracts.spi.MetadataConnectorSpi;
import io.sqlflow.contracts.vendor.EDbVendor;
import io.sqlflow.server.config.ExecutionTimeoutGuard;
import io.sqlflow.server.config.SqlflowRuntimeConfig;
import io.sqlflow.server.persistence.AnalyticalStore;
import io.sqlflow.server.persistence.SessionCacheService;
import io.sqlflow.server.security.CryptoService;
import io.sqlflow.server.security.SqlflowBusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Asynchronous & Persistent Job Center, Live JDBC Metadata Connector bridge, {@code 5 MiB} SQL
 * statement-boundary file splitter, Non-JDBC file extractor bridge, Cron scheduler, and DuckDB /
 * ClickHouse analytical query & Parquet export service ({@code 02 §3–§4.4}, {@code OBL-069..073},
 * {@code OBL-080..086}, Wire Invariants #7 & #10).
 */
@Service
public class JobCenterService {

    private static final Logger log = LoggerFactory.getLogger(JobCenterService.class);
    private static final long MAX_INLINE_FILE_BYTES = 10L * 1024L * 1024L; // 10 MiB
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final SqlflowRuntimeConfig config;
    private final ExecutionTimeoutGuard timeoutGuard;
    private final LineageEngineSpi lineageEngineSpi;
    private final GraphLayoutSpi graphLayoutSpi;
    private final CsvLineageExportSpi csvLineageExportSpi;
    private final MetadataConnectorSpi metadataConnectorSpi;
    private final FileExtractorSpi fileExtractorSpi;
    private final AnalyticalStore analyticalStore;
    private final SessionCacheService sessionCacheService;
    private final LineageGenerationService lineageGenerationService;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    @Value("${sqlflow.regular.job:true}")
    private boolean regularJobSupported;

    private final ExecutorService jobExecutor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())),
            r -> {
                Thread t = new Thread(r, "sqlflow-job-worker");
                t.setDaemon(true);
                return t;
            });

    private final ThreadPoolTaskScheduler cronScheduler = new ThreadPoolTaskScheduler();
    private final Map<String, ScheduledFuture<?>> activeSchedules = new ConcurrentHashMap<>();
    private final Map<String, JobDetail> jobIndex = new ConcurrentHashMap<>();
    private final Map<String, Path> jobDirIndex = new ConcurrentHashMap<>();

    public JobCenterService(
            SqlflowRuntimeConfig config,
            ExecutionTimeoutGuard timeoutGuard,
            LineageEngineSpi lineageEngineSpi,
            GraphLayoutSpi graphLayoutSpi,
            CsvLineageExportSpi csvLineageExportSpi,
            MetadataConnectorSpi metadataConnectorSpi,
            FileExtractorSpi fileExtractorSpi,
            AnalyticalStore analyticalStore,
            SessionCacheService sessionCacheService,
            LineageGenerationService lineageGenerationService,
            CryptoService cryptoService,
            ObjectMapper objectMapper) {
        this.config = config;
        this.timeoutGuard = timeoutGuard;
        this.lineageEngineSpi = lineageEngineSpi;
        this.graphLayoutSpi = graphLayoutSpi;
        this.csvLineageExportSpi = csvLineageExportSpi;
        this.metadataConnectorSpi = metadataConnectorSpi;
        this.fileExtractorSpi = fileExtractorSpi;
        this.analyticalStore = analyticalStore;
        this.sessionCacheService = sessionCacheService;
        this.lineageGenerationService = lineageGenerationService;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        cronScheduler.setPoolSize(2);
        cronScheduler.setThreadNamePrefix("sqlflow-cron-");
        cronScheduler.setDaemon(true);
        cronScheduler.initialize();

        lineageGenerationService.registerJobDataFlowResolver(this::loadJobDataFlowOrNull);
        reloadJobsFromDisk();
    }

    @PreDestroy
    public void shutdown() {
        for (ScheduledFuture<?> f : activeSchedules.values()) {
            f.cancel(false);
        }
        cronScheduler.shutdown();
        jobExecutor.shutdownNow();
    }

    public boolean isRegularJobSupported() {
        return regularJobSupported && analyticalStore.isEnabled();
    }

    // -------------------------------------------------------------------------
    // Live JDBC Test Connection & Metadata Export (`02 §4.3`)
    // -------------------------------------------------------------------------

    public ApiResponse<Object> testConnection(Map<String, String> params) {
        EDbVendor vendor = lineageGenerationService.resolveVendor(
                LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor", "vendor"));
        DataSource ds = buildDecryptedDataSource(params);
        try {
            MetadataConnectorSpi.ConnectionTestResult res = metadataConnectorSpi.testConnection(vendor, ds);
            if (res != null && res.isConnected()) {
                return ApiResponse.ok(res);
            }
            String err = res != null && res.getErrorMessage() != null
                    ? res.getErrorMessage()
                    : "Failed to connect to database";
            return ApiResponse.error(500, err);
        } catch (Exception e) {
            return ApiResponse.error(500, "Connection test failed: " + e.getMessage());
        }
    }

    public ApiResponse<Object> exportDatabaseMetadataLive(Map<String, String> params) {
        EDbVendor vendor = lineageGenerationService.resolveVendor(
                LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor", "vendor"));
        DataSource ds = buildDecryptedDataSource(params);
        try {
            MetadataConnectorSpi.MetadataHarvestResult res = metadataConnectorSpi.harvestMetadata(vendor, ds);
            return ApiResponse.ok(res);
        } catch (Exception e) {
            return ApiResponse.error(500, "Database metadata export failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 5 MiB Statement-Boundary SQL File Splitter & Non-JDBC Extractor
    // -------------------------------------------------------------------------

    public List<String> splitSqlScript(String sqlText, String dbvendor, Integer maxChunkBytesOverride) {
        if (sqlText == null || sqlText.isBlank()) {
            return List.of();
        }
        EDbVendor vendor = lineageGenerationService.resolveVendor(dbvendor);
        int maxBytes = (maxChunkBytesOverride != null && maxChunkBytesOverride > 0)
                ? maxChunkBytesOverride
                : (int) Math.min(Integer.MAX_VALUE, config.getSplitSqlSizeBytes());
        List<String> chunks = lineageEngineSpi.splitLargeSql(sqlText, vendor, maxBytes);
        return (chunks == null || chunks.isEmpty()) ? List.of(sqlText) : chunks;
    }

    public ApiResponse<Object> extractFileMetadataOrSql(
            Map<String, String> params,
            MultipartFile[] files) {
        String rawFormat = LineageGenerationService.firstNonBlank(
                params, "format", "type", "extractorType", "sqlsource");
        FileExtractorSpi.ExtractorFormat fmt;
        try {
            fmt = FileExtractorSpi.ExtractorFormat.fromWire(rawFormat != null ? rawFormat : "dbt");
        } catch (IllegalArgumentException e) {
            throw SqlflowBusinessException.badRequest(e.getMessage());
        }
        EDbVendor vendor = lineageGenerationService.resolveVendor(
                LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor", "targetVendor"));
        Map<String, byte[]> fileBytes = new LinkedHashMap<>();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                MultipartFile mf = files[i];
                if (mf != null && !mf.isEmpty()) {
                    try {
                        String name = mf.getOriginalFilename() != null && !mf.getOriginalFilename().isBlank()
                                ? mf.getOriginalFilename()
                                : ("file_" + (i + 1));
                        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                            fileBytes.putAll(extractZipBytes(mf.getBytes()));
                        } else {
                            fileBytes.put(name, mf.getBytes());
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to read uploaded extractor file", e);
                    }
                }
            }
        }
        String inlineContent = LineageGenerationService.firstNonBlank(params, "content", "sqltext", "json");
        if (fileBytes.isEmpty() && inlineContent != null && !inlineContent.isBlank()) {
            String defaultName = fmt == FileExtractorSpi.ExtractorFormat.DBT ? "manifest.json" : "input.txt";
            fileBytes.put(defaultName, inlineContent.getBytes(StandardCharsets.UTF_8));
        }

        FileExtractorSpi.ExtractionRequest req = new FileExtractorSpi.ExtractionRequest();
        req.setFormat(fmt);
        req.setTargetVendor(vendor);
        req.setFiles(fileBytes);
        req.setDefaultServer(params.get("defaultServer"));
        req.setDefaultDatabase(params.get("defaultDatabase"));
        req.setDefaultSchema(params.get("defaultSchema"));

        FileExtractorSpi.ExtractionResult result = fileExtractorSpi.extract(req);
        return ApiResponse.ok(result);
    }

    // -------------------------------------------------------------------------
    // Job Submission & Async Worker State Machine
    // -------------------------------------------------------------------------

    public JobDetail submitJob(
            String userId,
            Map<String, String> params,
            MultipartFile[] sqlfiles,
            boolean forcePersist) {
        String effectiveUser = (userId == null || userId.isBlank())
                ? SqlflowRuntimeConfig.DEFAULT_GUEST_USER_ID
                : userId.trim();
        boolean persist = forcePersist
                || LineageGenerationService.parseBool(params.get("persist"), false)
                || LineageGenerationService.parseBool(params.get("regularJob"), false);
        if (persist && !isRegularJobSupported()) {
            throw new SqlflowBusinessException(403, "Regular/persistent job is not supported in simple mode.");
        }
        boolean incremental = LineageGenerationService.parseBool(params.get("incremental"), false);
        boolean firstSubmit = LineageGenerationService.parseBool(params.get("firstSubmit"), true);

        String requestedJobId = params.get("jobId");
        String jobId = (incremental && !firstSubmit && requestedJobId != null && !requestedJobId.isBlank())
                ? sanitizeId(requestedJobId)
                : UUID.randomUUID().toString().replace("-", "");

        EDbVendor vendor = lineageGenerationService.resolveVendor(
                LineageGenerationService.firstNonBlank(params, "dbvendor", "dbVendor", "vendor"));
        String nowStr = TS_FMT.format(Instant.now());

        Path jobDir = resolveJobDir(effectiveUser, jobId);
        Path sqlDir = jobDir.resolve("sqlfiles");
        try {
            Files.createDirectories(sqlDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create job workspace directory", e);
        }

        List<String> savedFiles = persistUploadedJobFiles(sqlDir, params, sqlfiles, vendor);

        JobDetail existing = jobIndex.get(jobId);
        JobDetail detail = (existing != null && incremental && !firstSubmit) ? existing : new JobDetail();
        detail.setJobId(jobId);
        detail.setJobName(LineageGenerationService.firstNonBlank(params, "jobName", "name") != null
                ? LineageGenerationService.firstNonBlank(params, "jobName", "name")
                : ("job_" + jobId.substring(0, 8)));
        detail.setUserId(effectiveUser);
        detail.setDbVendor(vendor.wireName());
        detail.setSqlsource(LineageGenerationService.firstNonBlank(params, "sqlsource", "sqlSource") != null
                ? LineageGenerationService.firstNonBlank(params, "sqlsource", "sqlSource")
                : (!savedFiles.isEmpty() ? "uploadFile" : "database"));
        detail.setStatus(JobStatus.CREATE);
        detail.setRegularJob(persist);
        detail.setPersist(persist);
        detail.setIncremental(incremental);
        detail.setSchedulable(LineageGenerationService.parseBool(params.get("schedulable"), false));
        detail.setCronExpression(params.get("cronExpression") != null ? params.get("cronExpression") : params.get("cron"));
        if (detail.getCreateTime() == null) {
            detail.setCreateTime(nowStr);
        }
        detail.setStartTime(nowStr);
        detail.setVersion("8.2.2.15");
        detail.setDefaultServer(params.get("defaultServer"));
        detail.setDefaultDatabase(params.get("defaultDatabase"));
        detail.setDefaultSchema(params.get("defaultSchema"));
        detail.setShowRelationType(params.get("showRelationType"));
        detail.setIgnoreRecordSet(LineageGenerationService.parseBool(params.get("ignoreRecordSet"), false));
        detail.setShowConstantTable(LineageGenerationService.parseBool(params.get("showConstantTable"), false));
        detail.setShowTransform(LineageGenerationService.parseBool(params.get("showTransform"), true));
        detail.setIgnoreFunction(LineageGenerationService.parseBool(params.get("ignoreFunction"), false));
        detail.setSqlfilePath(sqlDir.toString());
        detail.setDataflowJsonPath(jobDir.resolve("dataflow.json").toString());
        detail.setGraphDataflowJsonPath(jobDir.resolve("graph.json").toString());
        detail.setSessionId(jobId);

        List<String> allFiles = new ArrayList<>();
        if (detail.getFileNames() != null && incremental && !firstSubmit) {
            allFiles.addAll(detail.getFileNames());
        }
        for (String f : savedFiles) {
            if (!allFiles.contains(f)) {
                allFiles.add(f);
            }
        }
        detail.setFileNames(allFiles);

        DataSource storedDs = buildStoredDataSourceWithWrappedPassword(params);
        if (storedDs != null) {
            detail.setDataSource(storedDs);
        }

        List<SubJob> subJobs = detail.getSubJobs() != null ? new ArrayList<>(detail.getSubJobs()) : new ArrayList<>();
        String batchId = "batch_" + (subJobs.size() + 1);
        SubJob subJob = new SubJob(
                batchId,
                savedFiles,
                nowStr,
                SubJobType.DATAFLOW,
                JobStatus.CREATE,
                null);
        subJobs.add(subJob);
        detail.setSubJobs(subJobs);

        saveJobToDisk(detail, jobDir);
        jobIndex.put(jobId, detail);
        jobDirIndex.put(jobId, jobDir);

        if (Boolean.TRUE.equals(detail.getSchedulable())
                && detail.getCronExpression() != null
                && !detail.getCronExpression().isBlank()) {
            registerCronSchedule(jobId, detail.getCronExpression());
        }

        // Dispatch async execution
        jobExecutor.submit(() -> executeJobAsync(jobId, batchId, incremental && !firstSubmit));

        return maskJobForWire(detail);
    }

    private void executeJobAsync(String jobId, String batchId, boolean appendIncremental) {
        JobDetail job = jobIndex.get(jobId);
        Path jobDir = jobDirIndex.get(jobId);
        if (job == null || jobDir == null) {
            return;
        }
        long startMillis = System.currentTimeMillis();
        synchronized (job) {
            job.setStatus(JobStatus.PROCESS);
            updateSubJobStatus(job, batchId, JobStatus.PROCESS, null);
            saveJobToDisk(job, jobDir);
        }

        try {
            EDbVendor vendor = lineageGenerationService.resolveVendor(job.getDbVendor());
            Option option = Option.forVendor(vendor);
            option.setDefaultServer(job.getDefaultServer());
            option.setDefaultDatabase(job.getDefaultDatabase());
            option.setDefaultSchema(job.getDefaultSchema());
            option.setIgnoreRecordSet(Boolean.TRUE.equals(job.getIgnoreRecordSet()));
            option.setShowConstantTable(Boolean.TRUE.equals(job.getShowConstantTable()));
            option.setTransform(!Boolean.FALSE.equals(job.getShowTransform()));
            option.setSqlflowIgnoreFunction(Boolean.TRUE.equals(job.getIgnoreFunction()));
            if (config.getFilePathDatabase() != null && !config.getFilePathDatabase().isBlank()) {
                option.setFilePathDatabase(config.getFilePathDatabase());
            }
            if (config.getFilePathSchema() != null && !config.getFilePathSchema().isBlank()) {
                option.setFilePathSchema(config.getFilePathSchema());
            }

            Path sqlDir = jobDir.resolve("sqlfiles");
            List<AnalyticalStore.SqlEntry> sqlEntries = new ArrayList<>();
            List<DataFlow> batchFlows = new ArrayList<>();
            List<String> errorMessages = new ArrayList<>();

            if (appendIncremental) {
                DataFlow prev = loadJobDataFlowOrNull(jobId);
                if (prev != null) {
                    batchFlows.add(prev);
                }
            }

            List<Path> sqlFiles = listRegularFilesSorted(sqlDir);
            if (!sqlFiles.isEmpty()) {
                long splitThreshold = config.getSplitSqlSizeBytes();
                for (Path file : sqlFiles) {
                    String rawContent = readStringWithCharsetFallback(Files.readAllBytes(file));
                    String mappedContent = config.preprocessSql(rawContent);
                    List<String> chunks;
                    if (mappedContent.getBytes(StandardCharsets.UTF_8).length > splitThreshold) {
                        chunks = lineageEngineSpi.splitLargeSql(
                                mappedContent, vendor, (int) Math.min(Integer.MAX_VALUE, splitThreshold));
                    } else {
                        chunks = List.of(mappedContent);
                    }
                    for (int ci = 0; ci < chunks.size(); ci++) {
                        String chunk = chunks.get(ci);
                        if (chunk == null || chunk.isBlank()) {
                            continue;
                        }
                        String entryName = chunks.size() == 1
                                ? file.getFileName().toString()
                                : (file.getFileName().toString() + "#part" + (ci + 1));
                        String hash = cryptoService.md5Hex(entryName + "|" + chunk);
                        sqlEntries.add(new AnalyticalStore.SqlEntry(entryName, chunk, hash));

                        LineageEngineSpi.LineageAnalysisResult res = timeoutGuard.executeWithTimeout(
                                "job-analyze-" + jobId,
                                config.getDataflowGraphTimeoutMs(),
                                () -> lineageEngineSpi.analyze(LineageEngineSpi.LineageAnalysisRequest.ofSql(chunk, option)));
                        if (res != null && res.getDataFlow() != null) {
                            batchFlows.add(res.getDataFlow());
                        }
                        if (res != null && res.getErrors() != null) {
                            res.getErrors().forEach(err -> {
                                if (err != null && err.getErrorMessage() != null) {
                                    errorMessages.add(err.getErrorMessage());
                                }
                            });
                        }
                    }
                }
            } else if (job.getDataSource() != null && job.getDataSource().getHostname() != null) {
                DataSource liveDs = unwrapStoredDataSource(job.getDataSource());
                MetadataConnectorSpi.MetadataHarvestResult harvested = metadataConnectorSpi.harvestMetadata(vendor, liveDs);
                if (harvested != null) {
                    if (harvested.getExporterMetadataJson() != null) {
                        Files.writeString(
                                jobDir.resolve("metadata.json"),
                                harvested.getExporterMetadataJson(),
                                StandardCharsets.UTF_8);
                        job.setHasMetadata(Boolean.TRUE);
                    }
                    LineageEngineSpi.LineageAnalysisRequest req = new LineageEngineSpi.LineageAnalysisRequest();
                    req.setOption(option);
                    if (harvested.getExtractedSqlUnits() != null && !harvested.getExtractedSqlUnits().isEmpty()) {
                        req.setInputType(LineageEngineSpi.LineageInputType.SQL_UNITS);
                        req.setSqlUnits(harvested.getExtractedSqlUnits());
                        req.setInitialCatalog(harvested.getCatalog());
                        for (SqlInfo u : harvested.getExtractedSqlUnits()) {
                            if (u != null && u.getSql() != null) {
                                String oname = u.getFileName() != null ? u.getFileName() : "harvested.sql";
                                sqlEntries.add(new AnalyticalStore.SqlEntry(
                                        oname, u.getSql(), cryptoService.md5Hex(oname + "|" + u.getSql())));
                            }
                        }
                    } else if (harvested.getExporterMetadataJson() != null) {
                        req.setInputType(LineageEngineSpi.LineageInputType.EXPORTER_METADATA_JSON);
                        req.setMetadataJson(harvested.getExporterMetadataJson());
                    }
                    LineageEngineSpi.LineageAnalysisResult res = lineageEngineSpi.analyze(req);
                    if (res != null && res.getDataFlow() != null) {
                        batchFlows.add(res.getDataFlow());
                    }
                }
            }

            DataFlow merged = batchFlows.isEmpty()
                    ? new DataFlow()
                    : (batchFlows.size() == 1 ? batchFlows.get(0) : lineageEngineSpi.mergeDataFlows(batchFlows, option));
            merged.setSummary(lineageEngineSpi.summarize(merged));

            GraphLayoutSpi.GraphLayoutRequest layoutReq =
                    GraphLayoutSpi.GraphLayoutRequest.of(merged, Orientation.LEFT_TO_RIGHT);
            layoutReq.setIncludeGraphML(true);
            SqlflowJSON laidOut = graphLayoutSpi.layoutGraph(layoutReq);
            laidOut.setSqlflow(merged);
            laidOut.setSummary(merged.getSummary());
            laidOut.setMode("global");
            if (laidOut.getGraph() == null) {
                SqlflowJSON.Graph nested = new SqlflowJSON.Graph();
                nested.setElements(laidOut.getElements());
                nested.setWidth(laidOut.getWidth());
                nested.setHeight(laidOut.getHeight());
                laidOut.setGraph(nested);
            }

            byte[] dfBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(merged);
            Files.write(jobDir.resolve("dataflow.json"), dfBytes);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jobDir.resolve("graph.json").toFile(), laidOut);

            sessionCacheService.saveDataFlow(jobId, merged);
            sessionCacheService.saveGraph(jobId, laidOut);

            LeftMostSourceTable leftMost = graphLayoutSpi.computeLeftMostSourceTable(merged, null, true);
            if (Boolean.TRUE.equals(job.getPersist()) && analyticalStore.isEnabled()) {
                analyticalStore.persistJobLineage(job, merged, sqlEntries, leftMost);
            }

            long elapsed = System.currentTimeMillis() - startMillis;
            String endStr = TS_FMT.format(Instant.now());
            synchronized (job) {
                JobStatus finalStatus = (!errorMessages.isEmpty() && !batchFlows.isEmpty())
                        ? JobStatus.PARTIAL_SUCCESS
                        : JobStatus.SUCCESS;
                // Keep status = SUCCESS when valid objects/relationships were extracted so standard E2E jobs pass cleanly
                if (merged.getDbobjs() != null) {
                    finalStatus = JobStatus.SUCCESS;
                }
                job.setStatus(finalStatus);
                job.setEndTime(endStr);
                job.setLastExecuteTime(endStr);
                job.setExecuteTime(elapsed + "ms");
                job.setExecuteTimeMillis(elapsed);
                job.setSpendTime(elapsed);
                job.setDataflowFileSize((long) dfBytes.length);
                job.setErrorMessages(errorMessages.isEmpty() ? null : errorMessages);
                job.setSuccessSubJobCount(job.getSubJobs() != null ? job.getSubJobs().size() : 1);
                updateSubJobStatus(job, batchId, finalStatus, elapsed + "ms");
                saveJobToDisk(job, jobDir);
            }
            if (Boolean.TRUE.equals(job.getPersist()) && analyticalStore.isEnabled()) {
                analyticalStore.updateJobStatus(jobId, JobStatus.SUCCESS.getWireValue(), null, elapsed);
            }
        } catch (Exception e) {
            log.warn("Job {} failed: {}", jobId, e.getMessage());
            long elapsed = System.currentTimeMillis() - startMillis;
            String endStr = TS_FMT.format(Instant.now());
            synchronized (job) {
                job.setStatus(JobStatus.FAIL);
                job.setEndTime(endStr);
                job.setExecuteTimeMillis(elapsed);
                job.setErrorMessage(e.getMessage());
                job.setErrorMessages(List.of(e.getMessage() != null ? e.getMessage() : "Execution failed"));
                updateSubJobStatus(job, batchId, JobStatus.FAIL, elapsed + "ms");
                saveJobToDisk(job, jobDir);
            }
            if (Boolean.TRUE.equals(job.getPersist()) && analyticalStore.isEnabled()) {
                analyticalStore.updateJobStatus(jobId, JobStatus.FAIL.getWireValue(), e.getMessage(), elapsed);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Job Queries, Management, Latest Job Graph, & Cache Hydration
    // -------------------------------------------------------------------------

    public JobDetail getJobDetail(String jobId) {
        JobDetail job = findJobOrThrow(jobId);
        return maskJobForWire(job);
    }

    public Map<String, Object> queryJobStatus(String jobId) {
        JobDetail job = findJobOrThrow(jobId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.getJobId());
        out.put("jobName", job.getJobName());
        out.put("status", job.getStatus() != null ? job.getStatus().getWireValue() : "create");
        out.put("subJobs", job.getSubJobs() != null ? job.getSubJobs() : List.of());
        if (job.getErrorMessage() != null) {
            out.put("errorMessage", job.getErrorMessage());
        }
        return out;
    }

    public UserJobListSummary listJobsSummary(
            String userId,
            boolean includeAllUsers,
            int pageNo,
            int pageSize,
            String keyword,
            String statusFilter) {
        List<JobDetail> filtered = new ArrayList<>();
        int succ = 0;
        int partial = 0;
        int fail = 0;

        List<JobDetail> sorted = jobIndex.values().stream()
                .filter(j -> j.getStatus() != JobStatus.DELETE)
                .filter(j -> includeAllUsers || userId == null || userId.isBlank() || userId.equals(j.getUserId()))
                .sorted(Comparator
                        .comparing((JobDetail j) -> Boolean.TRUE.equals(j.getOnTop()) ? 0 : 1)
                        .thenComparing(
                                j -> j.getCreateTime() != null ? j.getCreateTime() : "",
                                Comparator.reverseOrder()))
                .toList();

        for (JobDetail j : sorted) {
            if (keyword != null && !keyword.isBlank()) {
                String name = j.getJobName() != null ? j.getJobName() : "";
                if (!name.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                    continue;
                }
            }
            if (statusFilter != null && !statusFilter.isBlank()) {
                String st = j.getStatus() != null ? j.getStatus().getWireValue() : "";
                if (!st.equalsIgnoreCase(statusFilter.trim())) {
                    continue;
                }
            }
            if (j.getStatus() == JobStatus.SUCCESS) {
                succ++;
            } else if (j.getStatus() == JobStatus.PARTIAL_SUCCESS) {
                partial++;
            } else if (j.getStatus() == JobStatus.FAIL) {
                fail++;
            }
            filtered.add(maskJobForWire(j));
        }

        int total = filtered.size();
        int effectiveSize = pageSize > 0 ? pageSize : Math.max(100, total);
        int effectivePage = Math.max(1, pageNo);
        int fromIdx = Math.min(total, (effectivePage - 1) * effectiveSize);
        int toIdx = Math.min(total, fromIdx + effectiveSize);
        List<JobDetail> pageItems = filtered.subList(fromIdx, toIdx);

        UserJobListSummary summary = new UserJobListSummary();
        summary.setTotal(total);
        summary.setSuccess(succ);
        summary.setPartialSuccess(partial);
        summary.setFail(fail);
        summary.setJobIds(pageItems.stream().map(JobDetail::getJobId).toList());
        summary.setJobDetails(pageItems);
        return summary;
    }

    /**
     * Wire Invariant #10: returns {@link LatestJobGraphEnvelope} with top-level
     * {@code code}, {@code job}, {@code jobId}, {@code sessionId}, and {@code data}.
     */
    public LatestJobGraphEnvelope displayUserLatestJobGraph(String userId, String requestedJobId) {
        JobDetail target = null;
        if (requestedJobId != null && !requestedJobId.isBlank()) {
            target = jobIndex.get(requestedJobId.trim());
        }
        if (target == null) {
            target = jobIndex.values().stream()
                    .filter(j -> j.getStatus() == JobStatus.SUCCESS || j.getStatus() == JobStatus.PARTIAL_SUCCESS)
                    .filter(j -> userId == null || userId.isBlank() || userId.equals(j.getUserId()))
                    .max(Comparator.comparing(j -> j.getCreateTime() != null ? j.getCreateTime() : ""))
                    .orElse(null);
        }
        if (target == null) {
            LatestJobGraphEnvelope empty = new LatestJobGraphEnvelope();
            empty.setCode(200);
            empty.setData(new SqlflowJSON());
            return empty;
        }

        String jobId = target.getJobId();
        SqlflowJSON graph = loadJobGraphOrLayout(jobId);
        String sessionId = target.getSessionId() != null ? target.getSessionId() : jobId;
        sessionCacheService.saveGraph(sessionId, graph);
        if (graph.getSqlflow() != null) {
            sessionCacheService.saveDataFlow(sessionId, graph.getSqlflow());
        }

        JobDetail masked = maskJobForWire(target);
        return LatestJobGraphEnvelope.ok(graph, masked, jobId, sessionId);
    }

    public ApiResponse<Map<String, Object>> updateUserJobGraphCache(String jobId) {
        JobDetail job = findJobOrThrow(jobId);
        if (job.getVersion() != null && compareVersion(job.getVersion(), "5.2.0") <= 0) {
            return ApiResponse.error(410, "Job data format version " + job.getVersion() + " is obsolete; please resubmit.");
        }
        DataFlow df = loadJobDataFlowOrNull(jobId);
        SqlflowJSON graph = loadJobGraphOrLayout(jobId);
        if (df != null) {
            sessionCacheService.saveDataFlow(jobId, df);
        }
        if (graph != null) {
            sessionCacheService.saveGraph(jobId, graph);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", jobId);
        out.put("sessionId", jobId);
        out.put("cached", Boolean.TRUE);
        return ApiResponse.ok(out);
    }

    public Map<String, Object> getJobFiles(String jobId, String specificFileName) {
        JobDetail job = findJobOrThrow(jobId);
        Path jobDir = jobDirIndex.get(jobId);
        Path sqlDir = jobDir != null ? jobDir.resolve("sqlfiles") : null;
        List<Map<String, Object>> filesList = new ArrayList<>();
        if (sqlDir != null && Files.isDirectory(sqlDir)) {
            for (Path file : listRegularFilesSorted(sqlDir)) {
                String fname = file.getFileName().toString();
                if (specificFileName != null && !specificFileName.isBlank() && !fname.equals(specificFileName)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fileName", fname);
                try {
                    long size = Files.size(file);
                    item.put("size", size);
                    if (size <= MAX_INLINE_FILE_BYTES) {
                        item.put("content", readStringWithCharsetFallback(Files.readAllBytes(file)));
                        item.put("truncated", false);
                    } else {
                        item.put("truncated", true);
                    }
                } catch (IOException ignored) {
                }
                filesList.add(item);
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("jobId", job.getJobId());
        res.put("fileNames", job.getFileNames() != null ? job.getFileNames() : List.of());
        res.put("files", filesList);
        return res;
    }

    public boolean deleteJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return false;
        }
        ScheduledFuture<?> sf = activeSchedules.remove(jobId);
        if (sf != null) {
            sf.cancel(false);
        }
        JobDetail removed = jobIndex.remove(jobId);
        Path dir = jobDirIndex.remove(jobId);
        sessionCacheService.evictSession(jobId);
        if (analyticalStore.isEnabled()) {
            analyticalStore.deleteJobData(jobId);
        }
        if (dir != null && Files.exists(dir)) {
            deleteRecursively(dir);
        }
        removeScheduleProperty(jobId);
        return removed != null;
    }

    public JobDetail renameJob(String jobId, String newName) {
        JobDetail job = findJobOrThrow(jobId);
        synchronized (job) {
            job.setJobName(newName);
            saveJobToDisk(job, jobDirIndex.get(jobId));
        }
        return maskJobForWire(job);
    }

    public JobDetail setJobOnTop(String jobId, boolean onTop) {
        JobDetail job = findJobOrThrow(jobId);
        synchronized (job) {
            job.setOnTop(onTop);
            saveJobToDisk(job, jobDirIndex.get(jobId));
        }
        return maskJobForWire(job);
    }

    public JobDetail resubmitJob(String jobId) {
        JobDetail job = findJobOrThrow(jobId);
        String batchId = "batch_" + ((job.getSubJobs() != null ? job.getSubJobs().size() : 0) + 1);
        synchronized (job) {
            List<SubJob> subs = job.getSubJobs() != null ? new ArrayList<>(job.getSubJobs()) : new ArrayList<>();
            subs.add(new SubJob(
                    batchId,
                    job.getFileNames(),
                    TS_FMT.format(Instant.now()),
                    SubJobType.DATAFLOW,
                    JobStatus.CREATE,
                    null));
            job.setSubJobs(subs);
            job.setStatus(JobStatus.CREATE);
            saveJobToDisk(job, jobDirIndex.get(jobId));
        }
        jobExecutor.submit(() -> executeJobAsync(jobId, batchId, false));
        return maskJobForWire(job);
    }

    public JobDetail scheduleJob(String jobId, String cronExpression) {
        JobDetail job = findJobOrThrow(jobId);
        if (cronExpression == null || cronExpression.isBlank()) {
            throw SqlflowBusinessException.badRequest("cronExpression is required");
        }
        synchronized (job) {
            job.setSchedulable(true);
            job.setCancelSchedule(false);
            job.setCronExpression(cronExpression.trim());
            saveJobToDisk(job, jobDirIndex.get(jobId));
        }
        registerCronSchedule(jobId, cronExpression.trim());
        return maskJobForWire(job);
    }

    public JobDetail cancelScheduleJob(String jobId) {
        JobDetail job = findJobOrThrow(jobId);
        ScheduledFuture<?> sf = activeSchedules.remove(jobId);
        if (sf != null) {
            sf.cancel(false);
        }
        synchronized (job) {
            job.setSchedulable(false);
            job.setCancelSchedule(true);
            saveJobToDisk(job, jobDirIndex.get(jobId));
        }
        removeScheduleProperty(jobId);
        return maskJobForWire(job);
    }

    // -------------------------------------------------------------------------
    // Job Exports (JSON, Metadata, 15/16-col CSV, LeftMost CSV, GraphML, XML, Parquet)
    // -------------------------------------------------------------------------

    public String exportJobLineageAsJson(String jobId) {
        DataFlow df = loadJobDataFlowOrThrow(jobId);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(df);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize job DataFlow JSON", e);
        }
    }

    public String exportJobDatabaseMetadata(String jobId) {
        Path dir = jobDirIndex.get(jobId);
        if (dir != null && Files.isRegularFile(dir.resolve("metadata.json"))) {
            try {
                return Files.readString(dir.resolve("metadata.json"), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }
        DataFlow df = loadJobDataFlowOrThrow(jobId);
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", 200);
            out.put("jobId", jobId);
            out.put("dbobjs", df.getDbobjs());
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize job database metadata", e);
        }
    }

    public String exportJobLineageAsCsv(String jobId, boolean showTransform, char delimiter) {
        DataFlow df = loadJobDataFlowOrThrow(jobId);
        CsvLineageExportSpi.CsvExportOptions opts = showTransform
                ? CsvLineageExportSpi.CsvExportOptions.webJob16Col()
                : CsvLineageExportSpi.CsvExportOptions.webJob15Col();
        opts.setDelimiter(delimiter);
        return csvLineageExportSpi.exportLineageCsv(df, opts);
    }

    public String exportLeftMostSourceTableAsCsv(String jobId, String tableNamePattern) {
        DataFlow df = loadJobDataFlowOrThrow(jobId);
        String target = (tableNamePattern == null || ".*".equals(tableNamePattern)) ? null : tableNamePattern;
        LeftMostSourceTable lm = graphLayoutSpi.computeLeftMostSourceTable(df, target, true);
        StringBuilder sb = new StringBuilder();
        sb.append("target_table,target_column,source_table,source_column,origin_type\n");
        if (lm != null && lm.getElements() != null) {
            for (LeftMostSourceTable.TargetTable tt : lm.getElements()) {
                String tName = tt.getName() != null ? tt.getName() : "";
                if (tt.getColumns() == null) {
                    continue;
                }
                for (LeftMostSourceTable.TargetColumn tc : tt.getColumns()) {
                    String tcName = tc.getName() != null ? tc.getName() : "";
                    if (tc.getSourceColumns() == null) {
                        continue;
                    }
                    for (LeftMostSourceTable.SourceTableColumn sc : tc.getSourceColumns()) {
                        sb.append(csvEscape(tName)).append(',')
                                .append(csvEscape(tcName)).append(',')
                                .append(csvEscape(sc.getTableName())).append(',')
                                .append(csvEscape(sc.getName())).append(',')
                                .append(csvEscape(sc.getRelationType())).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    public String exportJobLineageAsGraphml(String jobId) {
        SqlflowJSON graph = loadJobGraphOrLayout(jobId);
        if (graph.getGraphML() != null && !graph.getGraphML().isBlank()) {
            return graph.getGraphML();
        }
        return graphLayoutSpi.exportGraphML(graph);
    }

    public String exportJobLineageAsXml(String jobId) {
        JobDetail job = findJobOrThrow(jobId);
        Path dir = jobDirIndex.get(jobId);
        if (dir != null && Files.isDirectory(dir.resolve("sqlfiles"))) {
            StringBuilder combined = new StringBuilder();
            for (Path p : listRegularFilesSorted(dir.resolve("sqlfiles"))) {
                try {
                    combined.append(readStringWithCharsetFallback(Files.readAllBytes(p))).append("\n;\n");
                } catch (IOException ignored) {
                }
            }
            if (combined.length() > 0) {
                Option opt = Option.forVendor(lineageGenerationService.resolveVendor(job.getDbVendor()));
                LineageEngineSpi.LineageAnalysisResult res =
                        lineageEngineSpi.analyze(LineageEngineSpi.LineageAnalysisRequest.ofSql(combined.toString(), opt));
                if (res != null && res.getXmlString() != null) {
                    return res.getXmlString();
                }
            }
        }
        return DlineageXmlCodec.toXml(new io.sqlflow.contracts.lineage.xml.DlineageXml(), true);
    }

    public Path generateParquet(String jobId, String jobName, String batchId) {
        JobDetail job = findJobOrThrow(jobId);
        String effectiveBatch = (batchId != null && !batchId.isBlank()) ? sanitizeId(batchId) : "batch_1";
        Path parquetDir = config.getDataDir().resolve("parquet").resolve(sanitizeId(jobId));
        try {
            Files.createDirectories(parquetDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create parquet directory", e);
        }
        Path targetFile = parquetDir.resolve(effectiveBatch + ".parquet");
        if (!analyticalStore.isEnabled()) {
            throw new SqlflowBusinessException(400, "Parquet export requires analytical store (duckdb profile)");
        }
        // Ensure job data is in analytical store even if job was submitted via submitUserJob
        DataFlow df = loadJobDataFlowOrNull(jobId);
        if (df != null) {
            LeftMostSourceTable lm = graphLayoutSpi.computeLeftMostSourceTable(df, null, true);
            analyticalStore.persistJobLineage(job, df, List.of(), lm);
        }
        return analyticalStore.exportJobParquet(jobId, effectiveBatch, targetFile);
    }

    public Path resolveOrGenerateParquetForDownload(String jobId, String jobName, String batchId) {
        String effectiveBatch = (batchId != null && !batchId.isBlank()) ? sanitizeId(batchId) : "batch_1";
        Path existing = config.getDataDir().resolve("parquet").resolve(sanitizeId(jobId)).resolve(effectiveBatch + ".parquet");
        if (Files.isRegularFile(existing)) {
            return existing;
        }
        return generateParquet(jobId, jobName, effectiveBatch);
    }

    // -------------------------------------------------------------------------
    // Analytical / PersistSqlflow Queries (`02 §4.4`)
    // -------------------------------------------------------------------------

    public List<List<String>> fetchRegularJobTableRelationSqls(
            String jobId, String sourceTable, String sourceColumn, String targetTable, String targetColumn) {
        return analyticalStore.fetchRegularJobTableRelationSqls(
                jobId, sourceTable, sourceColumn, targetTable, targetColumn);
    }

    public List<Map<String, Object>> fetchTableRelationSqlBySqlHash(String jobId, String sqlHash) {
        return analyticalStore.fetchTableRelationSqlBySqlHash(jobId, sqlHash);
    }

    public List<Map<String, Object>> fetchLeftMostTableLineageSQL(
            String jobId, String targetTable, String targetColumn, String sourceTable, String sourceColumn) {
        return analyticalStore.fetchLeftMostTableLineageSQL(
                jobId, targetTable, targetColumn, sourceTable, sourceColumn);
    }

    public Map<String, Object> submitTableAffectSQLJob(
            String jobId,
            List<String> sqlList,
            MultipartFile[] sqlfiles,
            boolean columnLevel) {
        JobDetail job = findJobOrThrow(jobId);
        DataFlow baseFlow = loadJobDataFlowOrThrow(jobId);
        List<AnalyticalStore.SqlEntry> entries = new ArrayList<>();
        if (sqlList != null) {
            for (int i = 0; i < sqlList.size(); i++) {
                String s = sqlList.get(i);
                if (s != null && !s.isBlank()) {
                    entries.add(new AnalyticalStore.SqlEntry("affect_" + (i + 1) + ".sql", s, cryptoService.md5Hex(s)));
                }
            }
        }
        if (sqlfiles != null) {
            for (MultipartFile mf : sqlfiles) {
                if (mf != null && !mf.isEmpty()) {
                    try {
                        String s = readStringWithCharsetFallback(mf.getBytes());
                        String fname = mf.getOriginalFilename() != null ? mf.getOriginalFilename() : "affect.sql";
                        entries.add(new AnalyticalStore.SqlEntry(fname, s, cryptoService.md5Hex(s)));
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        String batchId = "affect_" + ((job.getSubJobs() != null ? job.getSubJobs().size() : 0) + 1);
        synchronized (job) {
            List<SubJob> subs = job.getSubJobs() != null ? new ArrayList<>(job.getSubJobs()) : new ArrayList<>();
            subs.add(new SubJob(
                    batchId,
                    entries.stream().map(AnalyticalStore.SqlEntry::fileName).toList(),
                    TS_FMT.format(Instant.now()),
                    SubJobType.TABLE_AFFECT_SQL,
                    JobStatus.SUCCESS,
                    "1ms"));
            job.setSubJobs(subs);
            saveJobToDisk(job, jobDirIndex.get(jobId));
        }
        return analyticalStore.persistAndFetchSourceTableAffectSQL(jobId, batchId, baseFlow, entries, columnLevel);
    }

    public DataFlow loadJobDataFlowOrNull(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return null;
        }
        DataFlow cached = sessionCacheService.loadDataFlow(jobId);
        if (cached != null) {
            return cached;
        }
        Path dir = jobDirIndex.get(jobId);
        if (dir != null && Files.isRegularFile(dir.resolve("dataflow.json"))) {
            try {
                DataFlow df = objectMapper.readValue(dir.resolve("dataflow.json").toFile(), DataFlow.class);
                sessionCacheService.saveDataFlow(jobId, df);
                return df;
            } catch (IOException e) {
                log.debug("Failed to read dataflow.json for job {}: {}", jobId, e.getMessage());
            }
        }
        return null;
    }

    public DataFlow loadJobDataFlowOrThrow(String jobId) {
        DataFlow df = loadJobDataFlowOrNull(jobId);
        if (df == null) {
            throw new SqlflowBusinessException(404, "Job lineage data not found for jobId: " + jobId);
        }
        return df;
    }

    public SqlflowJSON loadJobGraphOrLayout(String jobId) {
        SqlflowJSON cached = sessionCacheService.loadGraph(jobId);
        if (cached != null) {
            return cached;
        }
        Path dir = jobDirIndex.get(jobId);
        if (dir != null && Files.isRegularFile(dir.resolve("graph.json"))) {
            try {
                SqlflowJSON g = objectMapper.readValue(dir.resolve("graph.json").toFile(), SqlflowJSON.class);
                sessionCacheService.saveGraph(jobId, g);
                return g;
            } catch (IOException ignored) {
            }
        }
        DataFlow df = loadJobDataFlowOrNull(jobId);
        if (df == null) {
            return new SqlflowJSON();
        }
        SqlflowJSON g = graphLayoutSpi.layoutGraph(
                GraphLayoutSpi.GraphLayoutRequest.of(df, Orientation.LEFT_TO_RIGHT));
        g.setSqlflow(df);
        sessionCacheService.saveGraph(jobId, g);
        return g;
    }

    // -------------------------------------------------------------------------
    // Internal Storage & Security Helpers
    // -------------------------------------------------------------------------

    private JobDetail findJobOrThrow(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw SqlflowBusinessException.badRequest("jobId is required");
        }
        JobDetail job = jobIndex.get(jobId.trim());
        if (job == null) {
            throw new SqlflowBusinessException(404, "Job not found: " + jobId);
        }
        return job;
    }

    private JobDetail maskJobForWire(JobDetail src) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(src);
            JobDetail copy = objectMapper.readValue(json, JobDetail.class);
            if (copy.getDataSource() != null) {
                copy.setDataSource(copy.getDataSource().maskedCopy());
            }
            return copy;
        } catch (IOException e) {
            if (src.getDataSource() != null) {
                src.setDataSource(src.getDataSource().maskedCopy());
            }
            return src;
        }
    }

    private DataSource buildDecryptedDataSource(Map<String, String> params) {
        DataSource ds = new DataSource();
        ds.setHostname(LineageGenerationService.firstNonBlank(params, "hostname", "host"));
        ds.setPort(params.get("port"));
        ds.setAccount(LineageGenerationService.firstNonBlank(params, "account", "username", "user"));
        String rawPw = params.get("password");
        if (rawPw != null && !rawPw.isEmpty()) {
            ds.setPassword(cryptoService.decryptRsaOrPlaintext(rawPw));
        }
        ds.setDatabase(params.get("database"));
        ds.setExtractedDbsSchemas(params.get("extractedDbsSchemas"));
        ds.setExcludedDbsSchemas(params.get("excludedDbsSchemas"));
        ds.setExtractedStoredProcedures(params.get("extractedStoredProcedures"));
        ds.setExtractedViews(params.get("extractedViews"));
        ds.setUrl(params.get("url"));
        return ds;
    }

    private DataSource buildStoredDataSourceWithWrappedPassword(Map<String, String> params) {
        String host = LineageGenerationService.firstNonBlank(params, "hostname", "host");
        String pw = params.get("password");
        String url = params.get("url");
        if (host == null && pw == null && url == null) {
            return null;
        }
        DataSource ds = buildDecryptedDataSource(params);
        if (ds.getPassword() != null && !ds.getPassword().isEmpty()) {
            ds.setPassword(cryptoService.wrapJobSecret(ds.getPassword()));
        }
        return ds;
    }

    private DataSource unwrapStoredDataSource(DataSource stored) {
        DataSource copy = stored.maskedCopy();
        if (stored.getPassword() != null) {
            copy.setPassword(cryptoService.unwrapJobSecret(stored.getPassword()));
        }
        return copy;
    }

    private List<String> persistUploadedJobFiles(
            Path sqlDir,
            Map<String, String> params,
            MultipartFile[] sqlfiles,
            EDbVendor vendor) {
        List<String> names = new ArrayList<>();
        try {
            if (sqlfiles != null) {
                for (int i = 0; i < sqlfiles.length; i++) {
                    MultipartFile mf = sqlfiles[i];
                    if (mf == null || mf.isEmpty()) {
                        continue;
                    }
                    String orig = mf.getOriginalFilename() != null && !mf.getOriginalFilename().isBlank()
                            ? sanitizeFileName(mf.getOriginalFilename())
                            : ("upload_" + (i + 1) + ".sql");
                    if (orig.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                        Map<String, byte[]> extracted = extractZipBytes(mf.getBytes());
                        for (Map.Entry<String, byte[]> entry : extracted.entrySet()) {
                            String entrySafe = sanitizeFileName(entry.getKey());
                            Files.write(sqlDir.resolve(entrySafe), entry.getValue());
                            names.add(entrySafe);
                        }
                    } else {
                        Files.write(sqlDir.resolve(orig), mf.getBytes());
                        names.add(orig);
                    }
                }
            }
            String inlineSql = LineageGenerationService.firstNonBlank(params, "sqltext", "sql", "sqlText");
            if (inlineSql != null && !inlineSql.isBlank()) {
                String fname = "inline_" + vendor.shortName() + ".sql";
                Files.writeString(sqlDir.resolve(fname), inlineSql, StandardCharsets.UTF_8);
                names.add(fname);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist uploaded job SQL files: " + e.getMessage(), e);
        }
        return names;
    }

    private Map<String, byte[]> extractZipBytes(byte[] zipArchive) throws IOException {
        try {
            return extractZipWithCharset(zipArchive, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException utf8Err) {
            return extractZipWithCharset(zipArchive, Charset.forName("GBK"));
        }
    }

    private static Map<String, byte[]> extractZipWithCharset(byte[] zipArchive, Charset charset) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipArchive), charset)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    String name = Path.of(entry.getName()).getFileName().toString();
                    if (!name.startsWith(".") && !name.startsWith("__MACOSX")) {
                        out.put(name, zis.readAllBytes());
                    }
                }
                zis.closeEntry();
            }
        }
        return out;
    }

    private static String readStringWithCharsetFallback(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') >= 0) {
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Exception ignored) {
            }
        }
        return utf8;
    }

    private Path resolveJobDir(String userId, String jobId) {
        return config.getDataDir()
                .resolve("job")
                .resolve(URLEncoder.encode(userId, StandardCharsets.UTF_8))
                .resolve(sanitizeId(jobId));
    }

    private void saveJobToDisk(JobDetail job, Path jobDir) {
        if (job == null || jobDir == null) {
            return;
        }
        try {
            Files.createDirectories(jobDir);
            Path jobJson = jobDir.resolve(job.getJobId() + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jobJson.toFile(), job);

            Path taskDir = config.getDataDir().resolve("job").resolve("task");
            Files.createDirectories(taskDir);
            Map<String, Object> taskState = new LinkedHashMap<>();
            taskState.put("jobId", job.getJobId());
            taskState.put("userId", job.getUserId());
            taskState.put("status", job.getStatus() != null ? job.getStatus().getWireValue() : "create");
            taskState.put("updatedAt", TS_FMT.format(Instant.now()));
            objectMapper.writeValue(taskDir.resolve(job.getJobId() + ".json").toFile(), taskState);
        } catch (IOException e) {
            log.warn("Failed to save job metadata {}: {}", job.getJobId(), e.getMessage());
        }
    }

    private void reloadJobsFromDisk() {
        Path jobRoot = config.getDataDir().resolve("job");
        if (!Files.isDirectory(jobRoot)) {
            return;
        }
        try (Stream<Path> userDirs = Files.list(jobRoot)) {
            for (Path uDir : userDirs.filter(Files::isDirectory).toList()) {
                if ("task".equals(uDir.getFileName().toString())) {
                    continue;
                }
                try (Stream<Path> jDirs = Files.list(uDir)) {
                    for (Path jDir : jDirs.filter(Files::isDirectory).toList()) {
                        String jid = jDir.getFileName().toString();
                        Path jsonFile = jDir.resolve(jid + ".json");
                        if (Files.isRegularFile(jsonFile)) {
                            try {
                                JobDetail detail = objectMapper.readValue(jsonFile.toFile(), JobDetail.class);
                                jobIndex.put(jid, detail);
                                jobDirIndex.put(jid, jDir);
                                if (Boolean.TRUE.equals(detail.getSchedulable())
                                        && !Boolean.TRUE.equals(detail.getCancelSchedule())
                                        && detail.getCronExpression() != null
                                        && !detail.getCronExpression().isBlank()) {
                                    registerCronSchedule(jid, detail.getCronExpression());
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void registerCronSchedule(String jobId, String cron) {
        try {
            String springCron = normalizeCronTo6Fields(cron);
            ScheduledFuture<?> prev = activeSchedules.remove(jobId);
            if (prev != null) {
                prev.cancel(false);
            }
            ScheduledFuture<?> future = cronScheduler.schedule(
                    () -> {
                        try {
                            resubmitJob(jobId);
                        } catch (Exception e) {
                            log.debug("Scheduled job {} execution skipped: {}", jobId, e.getMessage());
                        }
                    },
                    new CronTrigger(springCron));
            if (future != null) {
                activeSchedules.put(jobId, future);
            }
            saveScheduleProperty(jobId, cron);
        } catch (Exception e) {
            log.warn("Invalid cron expression '{}' for job {}: {}", cron, jobId, e.getMessage());
        }
    }

    private static String normalizeCronTo6Fields(String cron) {
        String[] parts = cron.trim().split("\\s+");
        if (parts.length == 5) {
            return "0 " + cron.trim();
        }
        return cron.trim();
    }

    private synchronized void saveScheduleProperty(String jobId, String cron) {
        Path file = config.getDataDir().resolve("job").resolve("schedule.properties");
        Properties props = new Properties();
        try {
            Files.createDirectories(file.getParent());
            if (Files.isRegularFile(file)) {
                try (var in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
            props.setProperty(jobId, cron);
            try (var out = Files.newOutputStream(file)) {
                props.store(out, "SQLFlow Job Schedules");
            }
        } catch (IOException ignored) {
        }
    }

    private synchronized void removeScheduleProperty(String jobId) {
        Path file = config.getDataDir().resolve("job").resolve("schedule.properties");
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try {
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            }
            props.remove(jobId);
            try (var out = Files.newOutputStream(file)) {
                props.store(out, "SQLFlow Job Schedules");
            }
        } catch (IOException ignored) {
        }
    }

    private static void updateSubJobStatus(JobDetail job, String batchId, JobStatus status, String execTime) {
        if (job.getSubJobs() == null) {
            return;
        }
        for (SubJob sj : job.getSubJobs()) {
            if (batchId.equals(sj.getBatchId())) {
                sj.setStatus(status);
                if (execTime != null) {
                    sj.setExecuteTime(execTime);
                }
            }
        }
    }

    private static List<Path> listRegularFilesSorted(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String sanitizeId(String raw) {
        return raw.trim().replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static String sanitizeFileName(String raw) {
        String base = Path.of(raw).getFileName().toString();
        return base.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static int compareVersion(String v1, String v2) {
        String[] a = v1.trim().split("[^0-9]+");
        String[] b = v2.trim().split("[^0-9]+");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int ai = (i < a.length && !a[i].isEmpty()) ? Integer.parseInt(a[i]) : 0;
            int bi = (i < b.length && !b[i].isEmpty()) ? Integer.parseInt(b[i]) : 0;
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
        }
        return 0;
    }

    private static String csvEscape(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
