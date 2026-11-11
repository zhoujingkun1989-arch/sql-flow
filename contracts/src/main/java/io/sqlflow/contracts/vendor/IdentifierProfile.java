package io.sqlflow.contracts.vendor;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dialect-specific identifier quoting, case-folding, case-comparison, UTF-8 byte truncation,
 * statement separator, and catalog/schema hierarchy metadata ({@code 03 §2.1–§2.3}).
 */
public final class IdentifierProfile {

    private static final Map<EDbVendor, IdentifierProfile> DEFAULT_PROFILES;

    static {
        EnumMap<EDbVendor, IdentifierProfile> map = new EnumMap<>(EDbVendor.class);
        for (EDbVendor v : EDbVendor.values()) {
            map.put(v, buildDefaultProfile(v));
        }
        DEFAULT_PROFILES = Collections.unmodifiableMap(map);
    }

    private final EDbVendor vendor;
    private final boolean parserSupportsCatalog;
    private final boolean parserSupportsSchema;
    private final boolean metadataSupportsCatalogs;
    private final boolean metadataSupportsSchemas;
    private final List<String> quoteDelimiters;
    private final String statementSeparator;
    private final boolean aliasOccludesColumn;
    private final int maxUtf8Bytes;
    private final IdentifierRule defaultRule;
    private final Map<DataObjectType, IdentifierRule> objectRules;

    public IdentifierProfile(
            EDbVendor vendor,
            boolean parserSupportsCatalog,
            boolean parserSupportsSchema,
            boolean metadataSupportsCatalogs,
            boolean metadataSupportsSchemas,
            List<String> quoteDelimiters,
            String statementSeparator,
            boolean aliasOccludesColumn,
            int maxUtf8Bytes,
            IdentifierRule defaultRule,
            Map<DataObjectType, IdentifierRule> objectRules) {
        this.vendor = Objects.requireNonNull(vendor, "vendor must not be null");
        this.parserSupportsCatalog = parserSupportsCatalog;
        this.parserSupportsSchema = parserSupportsSchema;
        this.metadataSupportsCatalogs = metadataSupportsCatalogs;
        this.metadataSupportsSchemas = metadataSupportsSchemas;
        this.quoteDelimiters = quoteDelimiters == null ? List.of("\"") : List.copyOf(quoteDelimiters);
        this.statementSeparator = statementSeparator == null ? ";" : statementSeparator;
        this.aliasOccludesColumn = aliasOccludesColumn;
        this.maxUtf8Bytes = maxUtf8Bytes;
        this.defaultRule = Objects.requireNonNull(defaultRule, "defaultRule must not be null");
        EnumMap<DataObjectType, IdentifierRule> rules = new EnumMap<>(DataObjectType.class);
        for (DataObjectType type : DataObjectType.values()) {
            IdentifierRule override = objectRules != null ? objectRules.get(type) : null;
            rules.put(type, override != null ? override : defaultRule);
        }
        this.objectRules = Collections.unmodifiableMap(rules);
    }

    /**
     * Returns the canonical {@link IdentifierProfile} for {@code vendor} (falling back to {@link EDbVendor#dbvoracle}
     * if {@code vendor} is {@code null}).
     */
    public static IdentifierProfile forVendor(EDbVendor vendor) {
        EDbVendor effective = vendor != null ? vendor : EDbVendor.dbvoracle;
        return DEFAULT_PROFILES.get(effective);
    }

    /**
     * Returns {@code customProfile} if non-null, else {@link #forVendor(EDbVendor)}.
     */
    public static IdentifierProfile forVendorOr(IdentifierProfile customProfile, EDbVendor vendor) {
        return customProfile != null ? customProfile : forVendor(vendor);
    }

    public EDbVendor vendor() {
        return vendor;
    }

    public boolean parserSupportsCatalog() {
        return parserSupportsCatalog;
    }

    public boolean parserSupportsSchema() {
        return parserSupportsSchema;
    }

    public boolean metadataSupportsCatalogs() {
        return metadataSupportsCatalogs;
    }

    public boolean metadataSupportsSchemas() {
        return metadataSupportsSchemas;
    }

    public List<String> quoteDelimiters() {
        return quoteDelimiters;
    }

    public String primaryQuoteDelimiter() {
        return quoteDelimiters.isEmpty() ? "\"" : quoteDelimiters.get(0);
    }

    public String statementSeparator() {
        return statementSeparator;
    }

    public boolean aliasOccludesColumn() {
        return aliasOccludesColumn;
    }

    public int maxUtf8Bytes() {
        return maxUtf8Bytes;
    }

    public IdentifierRule defaultRule() {
        return defaultRule;
    }

    public IdentifierRule ruleFor(DataObjectType objectType) {
        if (objectType == null) {
            return defaultRule;
        }
        return objectRules.getOrDefault(objectType, defaultRule);
    }

    public Map<DataObjectType, IdentifierRule> objectRules() {
        return objectRules;
    }

    /**
     * Strips surrounding dialect quote delimiters (e.g., {@code "col"}, {@code `col`}, {@code [col]}, {@code #"col"})
     * and normalizes according to {@code objectType}'s {@link IdentifierRule}.
     */
    public String normalizeIdentifier(DataObjectType objectType, String rawToken) {
        if (rawToken == null) {
            return null;
        }
        String trimmed = rawToken.trim();
        boolean quoted = isQuotedToken(trimmed);
        String unquoted = stripQuotes(trimmed);
        return ruleFor(objectType).normalize(unquoted, quoted, maxUtf8Bytes);
    }

    /**
     * Checks whether {@code rawToken} is enclosed in one of this dialect's identifier quote delimiters.
     */
    public boolean isQuotedToken(String rawToken) {
        if (rawToken == null || rawToken.length() < 2) {
            return false;
        }
        if (vendor == EDbVendor.dbvpowerquery && rawToken.startsWith("#\"") && rawToken.endsWith("\"") && rawToken.length() >= 3) {
            return true;
        }
        char first = rawToken.charAt(0);
        char last = rawToken.charAt(rawToken.length() - 1);
        for (String delim : quoteDelimiters) {
            if (delim.equals("[") && first == '[' && last == ']') {
                return true;
            }
            if (delim.length() == 1 && first == delim.charAt(0) && last == delim.charAt(0)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes outer quote delimiters and unescapes doubled quote characters if present.
     */
    public String stripQuotes(String rawToken) {
        if (!isQuotedToken(rawToken)) {
            return rawToken;
        }
        if (vendor == EDbVendor.dbvpowerquery && rawToken.startsWith("#\"")) {
            return rawToken.substring(2, rawToken.length() - 1).replace("\"\"", "\"");
        }
        char first = rawToken.charAt(0);
        String inner = rawToken.substring(1, rawToken.length() - 1);
        if (first == '[') {
            return inner.replace("]]", "]");
        }
        String q = String.valueOf(first);
        return inner.replace(q + q, q);
    }

    /**
     * Applies runtime {@code conf/customize.yaml} {@code case_sensitive} overrides for this vendor.
     */
    public IdentifierProfile withCustomizeOverrides(CustomizeConfig.CaseSensitiveRules rules) {
        if (rules == null) {
            return this;
        }
        Boolean defaultOverride = findVendorFlag(rules.getDefaults(), vendor);
        IdentifierRule newDefault = defaultOverride != null ? overrideSensitivity(defaultRule, defaultOverride) : defaultRule;

        EnumMap<DataObjectType, IdentifierRule> updated = new EnumMap<>(DataObjectType.class);
        for (DataObjectType type : DataObjectType.values()) {
            IdentifierRule base = defaultOverride != null ? overrideSensitivity(ruleFor(type), defaultOverride) : ruleFor(type);
            updated.put(type, base);
        }
        Boolean catalogOverride = findVendorFlag(rules.getCatalogs(), vendor);
        if (catalogOverride != null) {
            updated.put(DataObjectType.CATALOG, overrideSensitivity(updated.get(DataObjectType.CATALOG), catalogOverride));
            updated.put(DataObjectType.SCHEMA, overrideSensitivity(updated.get(DataObjectType.SCHEMA), catalogOverride));
        }
        Boolean tableOverride = findVendorFlag(rules.getTables(), vendor);
        if (tableOverride != null) {
            updated.put(DataObjectType.TABLE, overrideSensitivity(updated.get(DataObjectType.TABLE), tableOverride));
        }
        Boolean functionOverride = findVendorFlag(rules.getFunctions(), vendor);
        if (functionOverride != null) {
            updated.put(DataObjectType.PROCEDURE, overrideSensitivity(updated.get(DataObjectType.PROCEDURE), functionOverride));
        }
        Boolean columnOverride = findVendorFlag(rules.getColumns(), vendor);
        if (columnOverride != null) {
            updated.put(DataObjectType.COLUMN, overrideSensitivity(updated.get(DataObjectType.COLUMN), columnOverride));
        }
        return new IdentifierProfile(
                vendor,
                parserSupportsCatalog,
                parserSupportsSchema,
                metadataSupportsCatalogs,
                metadataSupportsSchemas,
                quoteDelimiters,
                statementSeparator,
                aliasOccludesColumn,
                maxUtf8Bytes,
                newDefault,
                updated);
    }

    private static Boolean findVendorFlag(List<CustomizeConfig.VendorCaseSensitive> list, EDbVendor target) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        for (CustomizeConfig.VendorCaseSensitive item : list) {
            if (item != null && item.getValue() != null && target.matchesAlias(item.getVendor())) {
                return item.getValue();
            }
        }
        return null;
    }

    private static IdentifierRule overrideSensitivity(IdentifierRule base, boolean caseSensitive) {
        CaseCompareMode cmp = caseSensitive ? CaseCompareMode.SENSITIVE : CaseCompareMode.INSENSITIVE;
        CaseFoldMode unqFold = caseSensitive ? CaseFoldMode.NONE : base.unquotedFold();
        return new IdentifierRule(unqFold, cmp, base.quotedFold(), cmp, base.collapseFold());
    }

    private static IdentifierProfile buildDefaultProfile(EDbVendor v) {
        IdentifierRule upperInsensQuotedSens = IdentifierRule.of(
                CaseFoldMode.UPPER, CaseCompareMode.INSENSITIVE, CaseFoldMode.NONE, CaseCompareMode.SENSITIVE);
        IdentifierRule lowerInsensQuotedSens = IdentifierRule.of(
                CaseFoldMode.LOWER, CaseCompareMode.INSENSITIVE, CaseFoldMode.NONE, CaseCompareMode.SENSITIVE);
        IdentifierRule lowerInsensQuotedInsens = IdentifierRule.of(
                CaseFoldMode.LOWER, CaseCompareMode.INSENSITIVE, CaseFoldMode.NONE, CaseCompareMode.INSENSITIVE);
        IdentifierRule lowerInsensQuotedSame = IdentifierRule.of(
                CaseFoldMode.LOWER, CaseCompareMode.INSENSITIVE, CaseFoldMode.NONE, CaseCompareMode.SAME_AS_UNQUOTED);
        IdentifierRule strictSensitive = IdentifierRule.of(
                CaseFoldMode.NONE, CaseCompareMode.SENSITIVE, CaseFoldMode.NONE, CaseCompareMode.SENSITIVE);
        IdentifierRule collationRule = IdentifierRule.of(
                CaseFoldMode.NONE, CaseCompareMode.COLLATION_BASED, CaseFoldMode.NONE, CaseCompareMode.COLLATION_BASED);
        IdentifierRule preserveInsensitive = IdentifierRule.of(
                CaseFoldMode.NONE, CaseCompareMode.INSENSITIVE, CaseFoldMode.NONE, CaseCompareMode.INSENSITIVE);

        return switch (v) {
            case dbvaccess -> simple(v, List.of("\""), ";", false, lowerInsensQuotedInsens);
            case dbvansi, dbvexasol, dbvfirebird, dbvgeneric, dbvhana, dbvnetezza, dbvodbc, dbvsnowflake ->
                    simple(v, List.of("\""), ";", false, upperInsensQuotedSens);
            case dbvathena -> simple(v, List.of("`"), ";", false, lowerInsensQuotedSame);
            case dbvazuresql, dbvmssql -> simple(v, List.of("[", "\""), ";", false, collationRule);
            case dbvbigquery -> {
                EnumMap<DataObjectType, IdentifierRule> overrides = new EnumMap<>(DataObjectType.class);
                overrides.put(DataObjectType.COLUMN, preserveInsensitive);
                yield new IdentifierProfile(v, true, true, true, true, List.of("`"), ";", false, 0, strictSensitive, overrides);
            }
            case dbvclickhouse, dbvpowerquery -> simple(v, List.of("\""), ";", false, strictSensitive);
            case dbvcouchbase -> simple(v, List.of("`"), ";", false, strictSensitive);
            case dbvdax -> simple(v, List.of("'"), ";", false, lowerInsensQuotedInsens);
            case dbvdb2 -> simple(v, List.of("\""), "@", false, upperInsensQuotedSens);
            case dbvdoris, dbvstarrocks, dbvoceanbase -> {
                EnumMap<DataObjectType, IdentifierRule> overrides = new EnumMap<>(DataObjectType.class);
                overrides.put(DataObjectType.COLUMN, preserveInsensitive);
                overrides.put(DataObjectType.PROCEDURE, preserveInsensitive);
                yield new IdentifierProfile(v, true, true, true, true, List.of("\"", "`"), ";", false, 0, lowerInsensQuotedInsens, overrides);
            }
            case dbvduckdb -> simple(v, List.of("\""), "/", false, lowerInsensQuotedInsens);
            case dbvflink -> simple(v, List.of("\"", "`"), ";", false, strictSensitive);
            case dbvgreenplum -> simple(v, List.of("\""), "/", false, lowerInsensQuotedSens);
            case dbvhive, dbvimpala ->
                    new IdentifierProfile(v, true, false, true, false, List.of("`"), ";", false, 0, lowerInsensQuotedInsens, null);
            case dbvinformix, dbvopenedge, dbvsoql, dbvgaussdb, dbvedb ->
                    simple(v, List.of("\""), ";", false, lowerInsensQuotedSens);
            case dbvmdx, dbvsybasease -> simple(v, List.of("\"", "["), ";", false, lowerInsensQuotedInsens);
            case dbvmysql -> {
                IdentifierRule mysqlDefault = new IdentifierRule(
                        CaseFoldMode.LOWER_SIMPLE, CaseCompareMode.INSENSITIVE,
                        CaseFoldMode.NONE, CaseCompareMode.INSENSITIVE,
                        CaseFoldMode.LOWER_SIMPLE);
                EnumMap<DataObjectType, IdentifierRule> overrides = new EnumMap<>(DataObjectType.class);
                overrides.put(DataObjectType.COLUMN, new IdentifierRule(
                        CaseFoldMode.NONE, CaseCompareMode.INSENSITIVE,
                        CaseFoldMode.NONE, CaseCompareMode.INSENSITIVE,
                        CaseFoldMode.LOWER_SIMPLE));
                overrides.put(DataObjectType.PROCEDURE, new IdentifierRule(
                        CaseFoldMode.NONE, CaseCompareMode.INSENSITIVE,
                        CaseFoldMode.NONE, CaseCompareMode.INSENSITIVE,
                        CaseFoldMode.UPPER_SIMPLE));
                yield new IdentifierProfile(v, true, false, true, false, List.of("`"), "$", false, 0, mysqlDefault, overrides);
            }
            case dbvoracle -> {
                IdentifierRule oracleRule = IdentifierRule.of(
                        CaseFoldMode.UPPER_SIMPLE, CaseCompareMode.INSENSITIVE, CaseFoldMode.NONE, CaseCompareMode.SENSITIVE);
                yield new IdentifierProfile(v, true, true, false, true, List.of("\""), "/", false, 0, oracleRule, null);
            }
            case dbvpostgresql -> {
                IdentifierRule pgRule = new IdentifierRule(
                        CaseFoldMode.LOWER_ASCII, CaseCompareMode.INSENSITIVE,
                        CaseFoldMode.NONE, CaseCompareMode.SENSITIVE,
                        CaseFoldMode.SIMPLE_FOLD);
                yield new IdentifierProfile(v, true, true, true, true, List.of("\""), "/", false, 63, pgRule, null);
            }
            case dbvpresto, dbvtrino, dbvvertica -> simple(v, List.of("\""), ";", false, lowerInsensQuotedSame);
            case dbvredshift -> {
                IdentifierRule rsRule = IdentifierRule.of(
                        CaseFoldMode.LOWER, CaseCompareMode.INSENSITIVE, CaseFoldMode.LOWER, CaseCompareMode.INSENSITIVE);
                yield simple(v, List.of("\""), "/", false, rsRule);
            }
            case dbvsparksql -> simple(v, List.of("\"", "`"), ";", false, lowerInsensQuotedInsens);
            case dbvsqlite -> simple(v, List.of("\"", "`", "["), ";", false, lowerInsensQuotedInsens);
            case dbvsybase, dbvsybaseiq -> simple(v, List.of("\"", "["), ";", true, lowerInsensQuotedInsens);
            case dbvsqlanywhere -> simple(v, List.of("\""), ";", true, lowerInsensQuotedInsens);
            case dbvteradata ->
                    new IdentifierProfile(v, true, false, true, false, List.of("\""), "/", false, 0, lowerInsensQuotedInsens, null);
            case dbvdatabricks -> simple(v, List.of("`"), ";", false, lowerInsensQuotedInsens);
            case dbvdameng ->
                    new IdentifierProfile(v, true, true, false, true, List.of("\""), "/", false, 0, upperInsensQuotedSens, null);
        };
    }

    private static IdentifierProfile simple(
            EDbVendor v,
            List<String> quotes,
            String stmtSep,
            boolean aliasOccludes,
            IdentifierRule rule) {
        return new IdentifierProfile(v, true, true, true, true, quotes, stmtSep, aliasOccludes, 0, rule, null);
    }
}
