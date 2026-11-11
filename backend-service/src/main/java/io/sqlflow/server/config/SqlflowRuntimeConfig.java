package io.sqlflow.server.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.sqlflow.contracts.domain.UserInfo;
import io.sqlflow.contracts.vendor.CustomizeConfig;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Loads and manages SQLFlow runtime directories and operator configuration files:
 * {@code conf/gudu_sqlflow.conf}, {@code conf/customize.yaml}, and {@code conf/sql_content_mapping.conf}.
 */
@Component
public class SqlflowRuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(SqlflowRuntimeConfig.class);
    public static final String DEFAULT_GUEST_USER_ID = UserInfo.GUEST_USER_ID;

    private final Environment env;
    private final YAMLMapper yamlMapper = new YAMLMapper();
    private final Map<String, String> runtimeOverrides = new ConcurrentHashMap<>();

    private volatile Path homeDir;
    private volatile Path confDir;
    private volatile Path dataDir;
    private volatile Path logDir;
    private volatile Path tmpDir;
    private volatile Path staticDir;
    private volatile Path widgetDir;

    private volatile Properties guduConf = new Properties();
    private volatile CustomizeConfig customizeConfig = new CustomizeConfig();
    private volatile AiConfig aiConfig = new AiConfig();
    private volatile List<SqlMappingRule> sqlMappingRules = Collections.emptyList();

    public SqlflowRuntimeConfig(Environment env) {
        this.env = env;
    }

    @PostConstruct
    public synchronized void init() {
        String configuredHome = env.getProperty("sqlflow.home", ".");
        Path resolvedHome = Paths.get(configuredHome).toAbsolutePath().normalize();
        // If CWD ends with /bin and ../conf exists, honor /sqlflow layout automatically
        if (".".equals(configuredHome)
                && resolvedHome.getFileName() != null
                && "bin".equals(resolvedHome.getFileName().toString())
                && Files.isDirectory(resolvedHome.resolve("../conf"))) {
            resolvedHome = resolvedHome.getParent().normalize();
        }
        this.homeDir = resolvedHome;
        this.confDir = resolveSubDir("sqlflow.conf-dir", "conf");
        this.dataDir = resolveSubDir("sqlflow.data-dir", "data");
        this.logDir = resolveSubDir("sqlflow.log-dir", "log");
        this.tmpDir = resolveSubDir("sqlflow.tmp-dir", "tmp");
        this.staticDir = resolveAssetDir("sqlflow.static-dir", "static", "frontend-ui/dist", "index.html");
        this.widgetDir = resolveAssetDir(
                "sqlflow.widget-dir", "widget", "widget-sdk/dist", "sqlflow.widget.3.6.16.js");

        ensureDirectories();
        reload();
    }

    private Path resolveSubDir(String propKey, String defaultChild) {
        String explicit = env.getProperty(propKey);
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }
        return homeDir.resolve(defaultChild).normalize();
    }

    private Path resolveAssetDir(
            String propKey, String packagedChild, String workspaceDistRel, String markerFile) {
        String explicit = env.getProperty(propKey);
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }
        Path packaged = homeDir.resolve(packagedChild).normalize();
        if (Files.isRegularFile(packaged.resolve(markerFile))) {
            return packaged;
        }
        Path directWorkspaceDist = homeDir.resolve(workspaceDistRel).normalize();
        if (Files.isRegularFile(directWorkspaceDist.resolve(markerFile))) {
            return directWorkspaceDist;
        }
        Path parentWorkspaceDist = homeDir.resolve("..").resolve(workspaceDistRel).normalize();
        if (Files.isRegularFile(parentWorkspaceDist.resolve(markerFile))) {
            return parentWorkspaceDist;
        }
        return packaged;
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(confDir);
            Files.createDirectories(dataDir.resolve("session/sqlflow"));
            Files.createDirectories(dataDir.resolve("share"));
            Files.createDirectories(dataDir.resolve("permalink"));
            Files.createDirectories(dataDir.resolve("schema"));
            Files.createDirectories(dataDir.resolve("job/task"));
            Files.createDirectories(dataDir.resolve("user/settings"));
            Files.createDirectories(logDir.resolve("sqlflow_logs"));
            Files.createDirectories(tmpDir.resolve("cache/sqlflow"));
        } catch (IOException e) {
            log.warn("Unable to pre-create runtime directories under {}: {}", homeDir, e.getMessage());
        }
    }

    public synchronized void reload() {
        loadGuduSqlflowConf();
        loadCustomizeYaml();
        loadSqlContentMapping();
    }

    private void loadGuduSqlflowConf() {
        Properties props = new Properties();
        Path confFile = confDir.resolve("gudu_sqlflow.conf");
        if (Files.isRegularFile(confFile)) {
            try (Reader reader = Files.newBufferedReader(confFile, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                log.warn("Failed to read {}: {}", confFile, e.getMessage());
            }
        }
        this.guduConf = props;
    }

    private void loadCustomizeYaml() {
        Path yamlFile = confDir.resolve("customize.yaml");
        CustomizeConfig parsedCustomize = new CustomizeConfig();
        AiConfig parsedAi = defaultAiConfigFromEnv();
        if (Files.isRegularFile(yamlFile)) {
            try {
                byte[] bytes = Files.readAllBytes(yamlFile);
                if (bytes.length > 0) {
                    parsedCustomize = yamlMapper.readValue(bytes, CustomizeConfig.class);
                    JsonNode root = yamlMapper.readTree(bytes);
                    if (root != null && root.has("ai") && root.get("ai").isObject()) {
                        JsonNode ai = root.get("ai");
                        if (ai.has("enabled")) {
                            parsedAi.setEnabled(ai.get("enabled").asBoolean(parsedAi.isEnabled()));
                        }
                        if (ai.hasNonNull("api_url")) {
                            parsedAi.setApiUrl(ai.get("api_url").asText());
                        } else if (ai.hasNonNull("base_url")) {
                            parsedAi.setApiUrl(ai.get("base_url").asText());
                        } else if (ai.hasNonNull("apiUrl")) {
                            parsedAi.setApiUrl(ai.get("apiUrl").asText());
                        }
                        if (ai.hasNonNull("api_key")) {
                            parsedAi.setApiKey(ai.get("api_key").asText());
                        } else if (ai.hasNonNull("apiKey")) {
                            parsedAi.setApiKey(ai.get("apiKey").asText());
                        }
                        if (ai.hasNonNull("model")) {
                            parsedAi.setModel(ai.get("model").asText());
                        }
                        if (ai.hasNonNull("max_tokens")) {
                            parsedAi.setMaxTokens(ai.get("max_tokens").asInt(parsedAi.getMaxTokens()));
                        }
                        if (ai.hasNonNull("temperature")) {
                            parsedAi.setTemperature(ai.get("temperature").asDouble(parsedAi.getTemperature()));
                        }
                        if (ai.hasNonNull("timeout_seconds")) {
                            parsedAi.setTimeoutSeconds(ai.get("timeout_seconds").asInt(parsedAi.getTimeoutSeconds()));
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to parse {}: {}", yamlFile, e.getMessage());
            }
        }
        this.customizeConfig = parsedCustomize != null ? parsedCustomize : new CustomizeConfig();
        this.aiConfig = parsedAi;
    }

    private AiConfig defaultAiConfigFromEnv() {
        AiConfig cfg = new AiConfig();
        cfg.setEnabled(Boolean.parseBoolean(getRaw("ai.enabled", env.getProperty("sqlflow.ai.enabled", "false"))));
        cfg.setApiUrl(getRaw("ai.api_url", env.getProperty("sqlflow.ai.api-url", "https://api.openai.com/v1/chat/completions")));
        cfg.setApiKey(getRaw("ai.api_key", env.getProperty("sqlflow.ai.api-key", "")));
        cfg.setModel(getRaw("ai.model", env.getProperty("sqlflow.ai.model", "gpt-4o-mini")));
        cfg.setMaxTokens(Integer.parseInt(env.getProperty("sqlflow.ai.max-tokens", "2048")));
        cfg.setTemperature(Double.parseDouble(env.getProperty("sqlflow.ai.temperature", "0.2")));
        cfg.setTimeoutSeconds(Integer.parseInt(env.getProperty("sqlflow.ai.timeout-seconds", "30")));
        return cfg;
    }

    private void loadSqlContentMapping() {
        Path mappingFile = confDir.resolve("sql_content_mapping.conf");
        List<SqlMappingRule> rules = new ArrayList<>();
        if (Files.isRegularFile(mappingFile)) {
            try {
                List<String> lines = Files.readAllLines(mappingFile, StandardCharsets.UTF_8);
                for (String rawLine : lines) {
                    if (rawLine == null) {
                        continue;
                    }
                    String trimmed = rawLine.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                        continue;
                    }
                    int eq = rawLine.indexOf('=');
                    if (eq > 0) {
                        String patternStr = rawLine.substring(0, eq).trim();
                        String replacement = rawLine.substring(eq + 1).trim();
                        if (!patternStr.isEmpty()) {
                            try {
                                rules.add(new SqlMappingRule(Pattern.compile(patternStr), replacement));
                            } catch (Exception e) {
                                log.warn("Invalid regex '{}' in sql_content_mapping.conf: {}", patternStr, e.getMessage());
                            }
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read {}: {}", mappingFile, e.getMessage());
            }
        }
        this.sqlMappingRules = Collections.unmodifiableList(rules);
    }

    public void setOverride(String key, String value) {
        if (value == null) {
            runtimeOverrides.remove(key);
        } else {
            runtimeOverrides.put(key, value);
        }
    }

    public void clearOverrides() {
        runtimeOverrides.clear();
    }

    public String getRaw(String key, String defaultValue) {
        String override = runtimeOverrides.get(key);
        if (override != null) {
            return override;
        }
        String envVal = env.getProperty("sqlflow.conf." + key);
        if (envVal != null) {
            return envVal;
        }
        String directEnv = env.getProperty(key);
        if (directEnv != null) {
            return directEnv;
        }
        String fromFile = guduConf.getProperty(key);
        if (fromFile != null) {
            return fromFile.trim();
        }
        return defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String raw = getRaw(key, null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim());
    }

    public int getInt(String key, int defaultValue) {
        String raw = getRaw(key, null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String raw = getRaw(key, null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public Path getHomeDir() {
        return homeDir;
    }

    public Path getConfDir() {
        return confDir;
    }

    public Path getDataDir() {
        return dataDir;
    }

    public Path getLogDir() {
        return logDir;
    }

    public Path getTmpDir() {
        return tmpDir;
    }

    public Path getStaticDir() {
        return staticDir;
    }

    public Path getWidgetDir() {
        return widgetDir;
    }

    public Path resolveSchemaDir() {
        return dataDir.resolve("schema");
    }

    public String getFilePathDatabase() {
        return customizeConfig != null ? customizeConfig.getFilePathDatabase() : null;
    }

    public String getFilePathSchema() {
        return customizeConfig != null ? customizeConfig.getFilePathSchema() : null;
    }

    public Path getSqlitePath() {
        String explicit = env.getProperty("sqlflow.sqlite.path");
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }
        return confDir.resolve("sqlflow.sqlite");
    }

    public Path getDuckDbPath() {
        String explicit = env.getProperty("sqlflow.duckdb.path");
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }
        return confDir.resolve("sqlflow.duckdb");
    }

    public CustomizeConfig getCustomizeConfig() {
        return customizeConfig;
    }

    public AiConfig getAiConfig() {
        boolean hasAiOverride = runtimeOverrides.keySet().stream().anyMatch(k -> k.startsWith("ai."));
        if (hasAiOverride) {
            AiConfig copy = aiConfig.copy();
            String overrideEnabled = runtimeOverrides.get("ai.enabled");
            if (overrideEnabled != null) {
                copy.setEnabled("true".equalsIgnoreCase(overrideEnabled) || "1".equals(overrideEnabled));
            }
            String urlOverride = runtimeOverrides.get("ai.api_url");
            if (urlOverride != null) {
                copy.setApiUrl(urlOverride);
            }
            String keyOverride = runtimeOverrides.get("ai.api_key");
            if (keyOverride != null) {
                copy.setApiKey(keyOverride);
            }
            String modelOverride = runtimeOverrides.get("ai.model");
            if (modelOverride != null) {
                copy.setModel(modelOverride);
            }
            return copy;
        }
        return aiConfig;
    }

    public boolean isOnPremise() {
        String deployment = getRaw("deployment", "premise").toLowerCase(Locale.ROOT);
        return !"cloud".equals(deployment);
    }

    public boolean isIgnoreUserToken() {
        return getBoolean("ignore_user_token", false);
    }

    public String getAnonymousUserId() {
        return getRaw("anonymous_user_id", UserInfo.GUEST_USER_ID);
    }

    public int getAnonymousSqlExecuteLimit() {
        return getInt("anonymous_sql_Execute_limit", 50);
    }

    public List<String> getAllowedOrigins() {
        String cors = getRaw("cors.allowedOrigins", null);
        if (cors == null || cors.isBlank()) {
            cors = getRaw("cros.allowedOrigins", "*");
        }
        List<String> list = new ArrayList<>();
        for (String part : cors.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list.isEmpty() ? List.of("*") : list;
    }

    public boolean isRegularJobEnabled() {
        for (String profile : env.getActiveProfiles()) {
            if ("simple".equalsIgnoreCase(profile)) {
                return false;
            }
        }
        String raw = getRaw("regular.job", env.getProperty("sqlflow.regular-job", "true"));
        return "true".equalsIgnoreCase(raw) || "1".equals(raw);
    }

    public String getAnalyticalStoreType() {
        for (String profile : env.getActiveProfiles()) {
            if ("simple".equalsIgnoreCase(profile)) {
                return "none";
            }
            if ("regular".equalsIgnoreCase(profile) || "scale".equalsIgnoreCase(profile)) {
                return "clickhouse";
            }
        }
        return env.getProperty("sqlflow.analytical-store", "duckdb").toLowerCase(Locale.ROOT);
    }

    public int getRelationLimit() {
        return getInt("relation_limit", 1000);
    }

    public int getRelationTrySimpleLimit() {
        return getInt("relation_try_simple_limit", 20000);
    }

    public int getSplitSqlSizeMb() {
        return Math.max(1, getInt("split_sql_size", 5));
    }

    public long getSplitSqlSizeBytes() {
        String bytesOverride = getRaw("split_sql_size_bytes", null);
        if (bytesOverride != null && !bytesOverride.isBlank()) {
            try {
                return Math.max(256L, Long.parseLong(bytesOverride.trim()));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return getSplitSqlSizeMb() * 1024L * 1024L;
    }

    public int getMaxParallelTask() {
        return Math.max(1, getInt("max_parallel_task", 4));
    }

    public long getDataflowGraphTimeoutMs() {
        String msOverride = getRaw("dataflow_graph_timeout_ms", env.getProperty("sqlflow.dataflow-graph-timeout-ms"));
        if (msOverride != null && !msOverride.isBlank()) {
            try {
                return Math.max(10L, Long.parseLong(msOverride.trim()));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        // In gudu_sqlflow.conf, dataflow_graph_timeout may be specified in ms or seconds; if <= 3600 treat as seconds unless > 3600
        long configured = getLong("dataflow_graph_timeout", 300000L);
        return configured <= 600L ? configured * 1000L : configured;
    }

    public int getJobReservePeriodDays() {
        return getInt("job_reserve_period", 30);
    }

    public boolean isDisableVisualizeJoin() {
        return getBoolean("disable_visualize_join", false);
    }

    public boolean isRecordSql() {
        return getBoolean("record_sql", false);
    }

    public Path getBaseSqlLogDir() {
        String configured = getRaw("base_sql_log_dir", null);
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return logDir.resolve("sqlflow_logs");
    }

    public boolean isExportIncludeTransform() {
        return getBoolean("export_include_transform", false);
    }

    public boolean isPasswordSecurityValidate() {
        return getBoolean("password_security_validate", false);
    }

    public int getUserTokenExpireHours() {
        return Math.max(1, getInt("user_token_expire_hours", 720));
    }

    public String getEngineVersion() {
        return env.getProperty("sqlflow.engine-version", "2.8.5.8");
    }

    public String getBackendVersion() {
        return env.getProperty("sqlflow.backend-version", "8.2.2.15");
    }

    public String getUiVersion() {
        return env.getProperty("sqlflow.ui-version", "3.6.16");
    }

    public String getReleaseDate() {
        return env.getProperty("sqlflow.release-date", "2026-10-27 08:48:51");
    }

    /**
     * Applies {@code conf/sql_content_mapping.conf} regex rules and {@code customize.yaml}
     * {@code decrypt_replace_password} argument redaction before SQL parsing.
     */
    public String preprocessSql(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        String current = sql;
        for (SqlMappingRule rule : sqlMappingRules) {
            current = rule.pattern().matcher(current).replaceAll(rule.replacement());
        }
        CustomizeConfig.DecryptReplacePassword drp = customizeConfig != null
                ? customizeConfig.getDecryptReplacePassword()
                : null;
        if (drp != null && Boolean.TRUE.equals(drp.getEnable()) && drp.getFunction() != null) {
            for (CustomizeConfig.FunctionArgRule fnRule : drp.getFunction()) {
                if (fnRule != null && fnRule.getName() != null && !fnRule.getName().isBlank() && fnRule.getIndex() != null) {
                    current = redactFunctionArgument(current, fnRule.getName(), fnRule.getIndex());
                }
            }
        }
        return current;
    }

    private String redactFunctionArgument(String sql, String fnName, int oneBasedArgIndex) {
        if (oneBasedArgIndex < 1) {
            return sql;
        }
        Pattern fnPattern = Pattern.compile("(?i)\\b" + Pattern.quote(fnName) + "\\s*\\(([^()]*)\\)");
        Matcher m = fnPattern.matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String argsRaw = m.group(1);
            String[] parts = argsRaw.split(",", -1);
            if (parts.length >= oneBasedArgIndex) {
                parts[oneBasedArgIndex - 1] = " '***'";
                String rebuilt = fnName + "(" + String.join(",", parts) + ")";
                m.appendReplacement(sb, Matcher.quoteReplacement(rebuilt));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public Map<String, Object> snapshotMetadata() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("homeDir", homeDir.toString());
        map.put("onPremise", isOnPremise());
        map.put("regularJob", isRegularJobEnabled());
        map.put("analyticalStore", getAnalyticalStoreType());
        return map;
    }

    public record SqlMappingRule(Pattern pattern, String replacement) {
    }

    public static class AiConfig {
        private boolean enabled;
        private String apiUrl = "https://api.openai.com/v1/chat/completions";
        private String apiKey = "";
        private String model = "gpt-4o-mini";
        private int maxTokens = 2048;
        private double temperature = 0.2;
        private int timeoutSeconds = 30;

        public AiConfig copy() {
            AiConfig c = new AiConfig();
            c.enabled = this.enabled;
            c.apiUrl = this.apiUrl;
            c.apiKey = this.apiKey;
            c.model = this.model;
            c.maxTokens = this.maxTokens;
            c.temperature = this.temperature;
            c.timeoutSeconds = this.timeoutSeconds;
            return c;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
