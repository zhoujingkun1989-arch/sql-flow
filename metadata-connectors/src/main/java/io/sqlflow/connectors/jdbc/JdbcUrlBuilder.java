package io.sqlflow.connectors.jdbc;

import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Builds vendor-accurate JDBC URLs and connection {@link Properties} across all supported JDBC dialects,
 * honoring custom {@link DataSource#getUrl()} overrides, connect/socket timeouts, SSL/warehouse/role/account
 * parameters, and RSA/DES password decryption ({@code OBL-074}).
 */
public final class JdbcUrlBuilder {

    public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_SOCKET_TIMEOUT_SECONDS = 30;

    private JdbcUrlBuilder() {
    }

    public record ResolvedJdbcConfig(
            EDbVendor vendor,
            String jdbcUrl,
            Properties properties,
            List<String> candidateDriverClasses,
            String validationQuery,
            int connectTimeoutSeconds,
            int socketTimeoutSeconds,
            Map<String, String> extraOptions) {
    }

    public static ResolvedJdbcConfig build(
            EDbVendor vendor,
            DataSource dataSource,
            CredentialDecryptor decryptor) {
        return build(vendor, dataSource, decryptor, DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_SOCKET_TIMEOUT_SECONDS);
    }

    public static ResolvedJdbcConfig build(
            EDbVendor vendor,
            DataSource dataSource,
            CredentialDecryptor decryptor,
            int defaultConnectTimeoutSec,
            int defaultSocketTimeoutSec) {
        if (vendor == null) {
            throw new IllegalArgumentException("EDbVendor must not be null");
        }
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource must not be null");
        }
        VendorJdbcProfile profile = VendorJdbcProfile.forVendor(vendor);
        CredentialDecryptor effectiveDecryptor = decryptor != null ? decryptor : new CredentialDecryptor();

        Map<String, String> extraOptions = new LinkedHashMap<>();
        String rawDb = trimToNull(dataSource.getDatabase());
        String cleanDb = extractInlineParams(rawDb, extraOptions);

        String rawHost = trimToNull(dataSource.getHostname());
        String cleanHost = extractInlineParams(rawHost, extraOptions);

        int connectTimeoutSec = parsePositiveInt(
                firstNonNull(extraOptions.get("connecttimeout"), extraOptions.get("logintimeout")),
                defaultConnectTimeoutSec);
        int socketTimeoutSec = parsePositiveInt(
                firstNonNull(extraOptions.get("sockettimeout"), extraOptions.get("readtimeout")),
                defaultSocketTimeoutSec);

        String customUrl = trimToNull(dataSource.getUrl());
        String jdbcUrl = customUrl != null
                ? customUrl
                : constructVendorUrl(vendor, profile, cleanHost, trimToNull(dataSource.getPort()), cleanDb, extraOptions);

        Properties props = new Properties();
        String account = trimToNull(dataSource.getAccount());
        if (account != null) {
            props.setProperty("user", account);
        }
        String decryptedPassword = effectiveDecryptor.decryptPassword(dataSource.getPassword());
        if (decryptedPassword != null) {
            props.setProperty("password", decryptedPassword);
        }

        applyVendorProperties(vendor, jdbcUrl, props, connectTimeoutSec, socketTimeoutSec, extraOptions);

        List<String> candidateDrivers = new ArrayList<>(profile.driverClasses());
        addUrlInferredDrivers(jdbcUrl, candidateDrivers);

        return new ResolvedJdbcConfig(
                vendor,
                jdbcUrl,
                props,
                List.copyOf(candidateDrivers),
                resolveValidationQuery(vendor, jdbcUrl, profile),
                connectTimeoutSec,
                socketTimeoutSec,
                Map.copyOf(extraOptions));
    }

    private static String constructVendorUrl(
            EDbVendor vendor,
            VendorJdbcProfile profile,
            String host,
            String portStr,
            String database,
            Map<String, String> extra) {
        if (vendor == EDbVendor.dbvsqlite) {
            String path = database != null ? database : (host != null ? host : ":memory:");
            if (path.startsWith("jdbc:sqlite:")) {
                return path;
            }
            return "jdbc:sqlite:" + path;
        }
        if (vendor == EDbVendor.dbvduckdb) {
            String path = database != null ? database : (host != null ? host : "");
            if (path.startsWith("jdbc:duckdb:")) {
                return path;
            }
            return "jdbc:duckdb:" + path;
        }
        if (host != null && host.startsWith("jdbc:")) {
            return host;
        }

        String effectiveHost = host != null ? host : "localhost";
        int port = parsePositiveInt(portStr, profile.defaultPort());
        String hostPort = port > 0 ? effectiveHost + ":" + port : effectiveHost;

        return switch (vendor) {
            case dbvoracle -> buildOracleUrl(effectiveHost, port, database, extra);
            case dbvmssql, dbvazuresql -> buildSqlServerUrl(vendor, effectiveHost, portStr, port, database, extra);
            case dbvpostgresql, dbvgreenplum, dbvgaussdb, dbvedb -> {
                String scheme = vendor == EDbVendor.dbvedb ? "jdbc:edb://" : "jdbc:postgresql://";
                String db = database != null ? database : "postgres";
                StringBuilder url = new StringBuilder(scheme).append(hostPort).append('/').append(db);
                appendQueryParams(url, filterKeys(extra, List.of("ssl", "sslmode", "currentSchema", "ApplicationName")));
                yield url.toString();
            }
            case dbvmysql, dbvdoris, dbvstarrocks, dbvoceanbase -> {
                String scheme = vendor == EDbVendor.dbvoceanbase && Boolean.parseBoolean(extra.get("oceanbaseDriver"))
                        ? "jdbc:oceanbase://"
                        : "jdbc:mysql://";
                StringBuilder url = new StringBuilder(scheme).append(hostPort);
                if (database != null) {
                    url.append('/').append(database);
                }
                Map<String, String> params = new LinkedHashMap<>();
                params.put("useUnicode", "true");
                params.put("characterEncoding", "UTF-8");
                params.put("zeroDateTimeBehavior", "convertToNull");
                params.put("useSSL", extra.getOrDefault("ssl", extra.getOrDefault("usessl", "false")));
                params.put("allowPublicKeyRetrieval", extra.getOrDefault("allowpublickeyretrieval", "true"));
                appendQueryParams(url, params);
                yield url.toString();
            }
            case dbvdb2 -> {
                String db = database != null ? database : "SAMPLE";
                StringBuilder url = new StringBuilder("jdbc:db2://").append(hostPort).append('/').append(db);
                if ("true".equalsIgnoreCase(extra.get("ssl")) || "true".equalsIgnoreCase(extra.get("sslconnection"))) {
                    url.append(":sslConnection=true;");
                }
                yield url.toString();
            }
            case dbvteradata -> {
                StringBuilder url = new StringBuilder("jdbc:teradata://").append(effectiveHost);
                List<String> tdOpts = new ArrayList<>();
                if (database != null) {
                    tdOpts.add("DATABASE=" + database);
                }
                if (port > 0) {
                    tdOpts.add("DBS_PORT=" + port);
                }
                tdOpts.add("CHARSET=" + extra.getOrDefault("charset", "UTF8"));
                if (extra.containsKey("tmode")) {
                    tdOpts.add("TMODE=" + extra.get("tmode"));
                }
                if (extra.containsKey("encryptdata") || "true".equalsIgnoreCase(extra.get("ssl"))) {
                    tdOpts.add("ENCRYPTDATA=ON");
                }
                url.append('/').append(String.join(",", tdOpts));
                yield url.toString();
            }
            case dbvnetezza -> {
                String db = database != null ? database : "SYSTEM";
                yield "jdbc:netezza://" + hostPort + "/" + db;
            }
            case dbvsnowflake -> buildSnowflakeUrl(effectiveHost, portStr, database, extra);
            case dbvredshift -> {
                String db = database != null ? database : "dev";
                StringBuilder url = new StringBuilder("jdbc:redshift://").append(hostPort).append('/').append(db);
                if ("true".equalsIgnoreCase(extra.get("ssl"))) {
                    appendQueryParams(url, Map.of("ssl", "true"));
                }
                yield url.toString();
            }
            case dbvhive -> {
                String db = database != null ? database : "default";
                StringBuilder url = new StringBuilder("jdbc:hive2://").append(hostPort).append('/').append(db);
                if ("true".equalsIgnoreCase(extra.get("ssl"))) {
                    url.append(";ssl=true");
                }
                if (extra.containsKey("principal")) {
                    url.append(";principal=").append(extra.get("principal"));
                }
                yield url.toString();
            }
            case dbvimpala -> {
                String db = database != null ? database : "default";
                StringBuilder url = new StringBuilder("jdbc:impala://").append(hostPort).append('/').append(db);
                if (extra.containsKey("authmech")) {
                    url.append(";AuthMech=").append(extra.get("authmech"));
                }
                if ("true".equalsIgnoreCase(extra.get("ssl"))) {
                    url.append(";SSL=1");
                }
                yield url.toString();
            }
            case dbvsybase, dbvsybasease -> {
                StringBuilder url = new StringBuilder("jdbc:sybase:Tds:").append(effectiveHost).append(':').append(port);
                if (database != null) {
                    url.append('/').append(database);
                }
                yield url.toString();
            }
            case dbvsybaseiq, dbvsqlanywhere -> {
                StringBuilder url = new StringBuilder("jdbc:sqlanywhere:Host=").append(hostPort);
                if (database != null) {
                    url.append(";DatabaseName=").append(database);
                }
                yield url.toString();
            }
            case dbvinformix -> {
                String db = database != null ? database : "sysmaster";
                String serverName = extra.getOrDefault("informixserver", "ol_informix1210");
                yield "jdbc:informix-sqli://" + hostPort + "/" + db + ":INFORMIXSERVER=" + serverName;
            }
            case dbvclickhouse -> {
                String db = database != null ? database : "default";
                String protocol = "true".equalsIgnoreCase(extra.get("ssl")) ? "jdbc:clickhouse:https://" : "jdbc:clickhouse://";
                yield protocol + hostPort + "/" + db;
            }
            case dbvbigquery -> buildBigQueryUrl(effectiveHost, port, database, extra);
            case dbvathena -> buildAthenaUrl(effectiveHost, port, database, extra);
            case dbvvertica -> {
                String db = database != null ? database : "VMart";
                yield "jdbc:vertica://" + hostPort + "/" + db;
            }
            case dbvdameng -> {
                StringBuilder url = new StringBuilder("jdbc:dm://").append(hostPort);
                if (database != null) {
                    url.append('/').append(database);
                }
                yield url.toString();
            }
            case dbvodbc -> "jdbc:odbc:" + (database != null ? database : effectiveHost);
            default -> "jdbc:" + vendor.shortName() + "://" + hostPort + (database != null ? "/" + database : "");
        };
    }

    private static String buildOracleUrl(String host, int port, String database, Map<String, String> extra) {
        if (database == null || database.isBlank()) {
            return "jdbc:oracle:thin:@//" + host + ":" + port + "/ORCL";
        }
        String db = database.trim();
        if (db.startsWith("(")) {
            // Full TNS descriptor
            return "jdbc:oracle:thin:@" + db;
        }
        if (db.startsWith("/")) {
            return "jdbc:oracle:thin:@//" + host + ":" + port + db;
        }
        if (db.startsWith(":")) {
            return "jdbc:oracle:thin:@" + host + ":" + port + db;
        }
        if ("sid".equalsIgnoreCase(extra.get("servicetype")) || "true".equalsIgnoreCase(extra.get("usesid"))) {
            return "jdbc:oracle:thin:@" + host + ":" + port + ":" + db;
        }
        return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + db;
    }

    private static String buildSqlServerUrl(
            EDbVendor vendor,
            String host,
            String rawPort,
            int port,
            String database,
            Map<String, String> extra) {
        StringBuilder url = new StringBuilder("jdbc:sqlserver://");
        if (host.contains("\\")) {
            url.append(host);
            if (rawPort != null && !rawPort.isBlank()) {
                url.append(':').append(port);
            }
        } else {
            url.append(host).append(':').append(port);
        }
        if (database != null) {
            url.append(";databaseName=").append(database);
        }
        boolean azure = vendor == EDbVendor.dbvazuresql || host.toLowerCase(Locale.ROOT).endsWith(".database.windows.net");
        String encrypt = extra.getOrDefault("encrypt", azure ? "true" : "false");
        String trustCert = extra.getOrDefault("trustservercertificate", azure ? "false" : "true");
        url.append(";encrypt=").append(encrypt);
        url.append(";trustServerCertificate=").append(trustCert);
        if ("true".equalsIgnoreCase(extra.get("integratedsecurity"))) {
            url.append(";integratedSecurity=true");
        }
        return url.toString();
    }

    private static String buildSnowflakeUrl(String host, String portStr, String database, Map<String, String> extra) {
        String sfHost = host;
        if (!sfHost.contains(".")) {
            sfHost = sfHost + ".snowflakecomputing.com";
        } else if (!sfHost.endsWith(".snowflakecomputing.com") && !sfHost.contains("localhost") && !sfHost.matches(".*\\d+\\.\\d+\\.\\d+\\.\\d+.*")) {
            sfHost = sfHost + ".snowflakecomputing.com";
        }
        StringBuilder url = new StringBuilder("jdbc:snowflake://").append(sfHost);
        if (portStr != null && !portStr.isBlank() && !"443".equals(portStr.trim())) {
            url.append(':').append(portStr.trim());
        }
        url.append('/');
        Map<String, String> params = new LinkedHashMap<>();
        if (database != null) {
            params.put("db", database);
        }
        copyIfPresent(extra, "warehouse", params, "warehouse");
        copyIfPresent(extra, "role", params, "role");
        copyIfPresent(extra, "schema", params, "schema");
        copyIfPresent(extra, "authenticator", params, "authenticator");
        copyIfPresent(extra, "private_key_file", params, "private_key_file");
        appendQueryParams(url, params);
        return url.toString();
    }

    private static String buildBigQueryUrl(String host, int port, String projectOrDataset, Map<String, String> extra) {
        String endpoint = host.contains("googleapis.com") ? host : "https://www.googleapis.com/bigquery/v2";
        String projectId = extra.getOrDefault("projectid", projectOrDataset != null ? projectOrDataset : "default-project");
        String oauthType = extra.getOrDefault("oauthtype", "3");
        StringBuilder url = new StringBuilder("jdbc:bigquery://").append(endpoint).append(':').append(port)
                .append(";ProjectId=").append(projectId)
                .append(";OAuthType=").append(oauthType)
                .append(';');
        if (extra.containsKey("oauthpvtkeypath")) {
            url.append("OAuthPvtKeyPath=").append(extra.get("oauthpvtkeypath")).append(';');
        }
        if (extra.containsKey("oauthserviceacctemail")) {
            url.append("OAuthServiceAcctEmail=").append(extra.get("oauthserviceacctemail")).append(';');
        }
        if (extra.containsKey("defaultdataset")) {
            url.append("DefaultDataset=").append(extra.get("defaultdataset")).append(';');
        }
        return url.toString();
    }

    private static String buildAthenaUrl(String host, int port, String catalogOrRegion, Map<String, String> extra) {
        String region = extra.getOrDefault("awsregion",
                host != null && !host.equals("localhost") && !host.contains(".") ? host : "us-east-1");
        StringBuilder url = new StringBuilder("jdbc:awsathena://AwsRegion=").append(region).append(';');
        if (extra.containsKey("s3outputlocation")) {
            url.append("S3OutputLocation=").append(extra.get("s3outputlocation")).append(';');
        }
        if (catalogOrRegion != null && !catalogOrRegion.equals(region)) {
            url.append("MetadataRetrievalMethod=ProxyAPI;Catalog=").append(catalogOrRegion).append(';');
        }
        return url.toString();
    }

    private static void applyVendorProperties(
            EDbVendor vendor,
            String jdbcUrl,
            Properties props,
            int connectTimeoutSec,
            int socketTimeoutSec,
            Map<String, String> extra) {
        int connectMs = connectTimeoutSec * 1000;
        int socketMs = socketTimeoutSec * 1000;
        String lowerUrl = jdbcUrl.toLowerCase(Locale.ROOT);

        if (lowerUrl.startsWith("jdbc:h2:")) {
            return;
        }
        if (lowerUrl.startsWith("jdbc:sqlite:") || vendor == EDbVendor.dbvsqlite) {
            props.putIfAbsent("busy_timeout", String.valueOf(connectMs));
            return;
        }

        switch (vendor) {
            case dbvoracle -> {
                props.putIfAbsent("oracle.net.CONNECT_TIMEOUT", String.valueOf(connectMs));
                props.putIfAbsent("oracle.jdbc.ReadTimeout", String.valueOf(socketMs));
                props.putIfAbsent("remarksReporting", "true");
            }
            case dbvmssql, dbvazuresql -> {
                props.putIfAbsent("loginTimeout", String.valueOf(connectTimeoutSec));
                props.putIfAbsent("socketTimeout", String.valueOf(socketMs));
            }
            case dbvpostgresql, dbvgreenplum, dbvredshift, dbvgaussdb, dbvedb -> {
                props.putIfAbsent("loginTimeout", String.valueOf(connectTimeoutSec));
                props.putIfAbsent("connectTimeout", String.valueOf(connectTimeoutSec));
                props.putIfAbsent("socketTimeout", String.valueOf(socketTimeoutSec));
            }
            case dbvmysql, dbvdoris, dbvstarrocks, dbvoceanbase -> {
                props.putIfAbsent("connectTimeout", String.valueOf(connectMs));
                props.putIfAbsent("socketTimeout", String.valueOf(socketMs));
                props.putIfAbsent("useInformationSchema", "true");
            }
            case dbvdb2 -> {
                props.putIfAbsent("loginTimeout", String.valueOf(connectTimeoutSec));
                props.putIfAbsent("blockingReadConnectionTimeout", String.valueOf(socketTimeoutSec));
            }
            case dbvteradata -> props.putIfAbsent("LOGIN_TIMEOUT", String.valueOf(connectTimeoutSec));
            case dbvsnowflake -> {
                props.putIfAbsent("loginTimeout", String.valueOf(connectTimeoutSec));
                props.putIfAbsent("networkTimeout", String.valueOf(socketMs));
                copyPropIfPresent(extra, "warehouse", props, "warehouse");
                copyPropIfPresent(extra, "role", props, "role");
                copyPropIfPresent(extra, "account", props, "account");
                copyPropIfPresent(extra, "authenticator", props, "authenticator");
                copyPropIfPresent(extra, "private_key_file", props, "private_key_file");
                copyPropIfPresent(extra, "private_key_file_pwd", props, "private_key_file_pwd");
            }
            case dbvclickhouse -> {
                props.putIfAbsent("connect_timeout", String.valueOf(connectMs));
                props.putIfAbsent("socket_timeout", String.valueOf(socketMs));
            }
            default -> {
                // Standard DriverManager loginTimeout handles remaining vendors
            }
        }
    }

    private static void addUrlInferredDrivers(String jdbcUrl, List<String> drivers) {
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:h2:") && !drivers.contains("org.h2.Driver")) {
            drivers.add(0, "org.h2.Driver");
        } else if (lower.startsWith("jdbc:sqlite:") && !drivers.contains("org.sqlite.JDBC")) {
            drivers.add(0, "org.sqlite.JDBC");
        } else if (lower.startsWith("jdbc:duckdb:") && !drivers.contains("org.duckdb.DuckDBDriver")) {
            drivers.add(0, "org.duckdb.DuckDBDriver");
        } else if (lower.startsWith("jdbc:mariadb:") && !drivers.contains("org.mariadb.jdbc.Driver")) {
            drivers.add(0, "org.mariadb.jdbc.Driver");
        } else if (lower.startsWith("jdbc:postgresql:") && !drivers.contains("org.postgresql.Driver")) {
            drivers.add("org.postgresql.Driver");
        }
    }

    private static String resolveValidationQuery(EDbVendor vendor, String jdbcUrl, VendorJdbcProfile profile) {
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:h2:") || lower.startsWith("jdbc:sqlite:") || lower.startsWith("jdbc:duckdb:")) {
            return "SELECT 1";
        }
        return profile.validationQuery();
    }

    private static String extractInlineParams(String rawValue, Map<String, String> targetParams) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue.startsWith("jdbc:")) {
            return rawValue;
        }
        int qIdx = rawValue.indexOf('?');
        if (qIdx < 0) {
            return rawValue;
        }
        String base = rawValue.substring(0, qIdx).trim();
        String query = rawValue.substring(qIdx + 1).trim();
        for (String pair : query.split("[&;]")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq).trim(), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1).trim(), StandardCharsets.UTF_8);
                targetParams.put(k.toLowerCase(Locale.ROOT), v);
                targetParams.put(k, v);
            }
        }
        return base.isEmpty() ? null : base;
    }

    private static void appendQueryParams(StringBuilder url, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        boolean first = url.indexOf("?") < 0;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            url.append(first ? '?' : '&');
            first = false;
            url.append(entry.getKey()).append('=').append(entry.getValue());
        }
    }

    private static Map<String, String> filterKeys(Map<String, String> extra, List<String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : keys) {
            String val = firstNonNull(extra.get(key), extra.get(key.toLowerCase(Locale.ROOT)));
            if (val != null) {
                out.put(key, val);
            }
        }
        return out;
    }

    private static void copyIfPresent(Map<String, String> src, String key, Map<String, String> dst, String dstKey) {
        String v = firstNonNull(src.get(key), src.get(key.toLowerCase(Locale.ROOT)));
        if (v != null && !v.isBlank()) {
            dst.put(dstKey, v);
        }
    }

    private static void copyPropIfPresent(Map<String, String> src, String key, Properties dst, String dstKey) {
        String v = firstNonNull(src.get(key), src.get(key.toLowerCase(Locale.ROOT)));
        if (v != null && !v.isBlank()) {
            dst.putIfAbsent(dstKey, v);
        }
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
