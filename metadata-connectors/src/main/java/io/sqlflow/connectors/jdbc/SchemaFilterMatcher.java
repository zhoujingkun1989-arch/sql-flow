package io.sqlflow.connectors.jdbc;

import io.sqlflow.contracts.domain.DataSource;
import io.sqlflow.contracts.vendor.EDbVendor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Evaluates database, schema, view, and stored-procedure include/exclude filter expressions configured on
 * {@link DataSource} ({@code extractedDbsSchemas}, {@code excludedDbsSchemas}, {@code extractedViews},
 * {@code extractedStoredProcedures}) ({@code 02 §5.5, §6.3.2}).
 *
 * <p>Supports comma/semicolon/newline-separated tokens, two-part {@code db.schema} patterns, single-part
 * {@code db} or {@code schema} patterns, SQL {@code %} and glob {@code *} wildcards, and vendor system-schema
 * exclusions unless explicitly included.
 */
public final class SchemaFilterMatcher {

    private final VendorJdbcProfile vendorProfile;
    private final List<FilterRule> includeRules;
    private final List<FilterRule> excludeRules;
    private final List<Pattern> viewPatterns;
    private final boolean viewsDisabled;
    private final List<Pattern> routinePatterns;
    private final boolean routinesDisabled;

    public SchemaFilterMatcher(EDbVendor vendor, DataSource dataSource) {
        this.vendorProfile = VendorJdbcProfile.forVendor(vendor);
        String rawIncludes = dataSource != null ? dataSource.getExtractedDbsSchemas() : null;
        String rawExcludes = dataSource != null ? dataSource.getExcludedDbsSchemas() : null;
        String rawViews = dataSource != null ? dataSource.getExtractedViews() : null;
        String rawProcs = dataSource != null ? dataSource.getExtractedStoredProcedures() : null;

        this.includeRules = parseNamespaceRules(rawIncludes);
        this.excludeRules = parseNamespaceRules(rawExcludes);
        this.viewsDisabled = isExplicitlyDisabled(rawViews);
        this.viewPatterns = viewsDisabled ? List.of() : parseObjectPatterns(rawViews);
        this.routinesDisabled = isExplicitlyDisabled(rawProcs);
        this.routinePatterns = routinesDisabled ? List.of() : parseObjectPatterns(rawProcs);
    }

    /**
     * Checks whether a top-level database/catalog should be scanned at all.
     */
    public boolean acceptsDatabase(String database) {
        String db = normalize(database);
        for (FilterRule ex : excludeRules) {
            if (ex.schemaPattern == null && ex.dbPattern != null && matches(ex.dbPattern, db)) {
                return false;
            }
        }
        if (!includeRules.isEmpty()) {
            if (db.isEmpty()) {
                return true;
            }
            for (FilterRule inc : includeRules) {
                if (inc.dbPattern != null && matches(inc.dbPattern, db)) {
                    return true;
                }
                if (inc.schemaPattern == null && inc.singleTokenPattern != null && matches(inc.singleTokenPattern, db)) {
                    return true;
                }
            }
            // If all include rules are single-token schema filters on a single-DB connection (e.g. Oracle/Postgres),
            // allow scanning the current connection database when database matches DataSource.database or is single-schema style
            boolean anyTwoPartRule = includeRules.stream().anyMatch(r -> r.dbPattern != null && r.schemaPattern != null);
            if (!anyTwoPartRule && !vendorProfile.vendor().identifierProfile().metadataSupportsCatalogs()) {
                return true;
            }
            return !anyTwoPartRule && includeRules.stream().allMatch(r -> r.singleTokenPattern != null);
        }
        return !vendorProfile.isSystemDatabase(db);
    }

    /**
     * Checks whether {@code (database, schema)} passes include, exclude, and default system-schema rules.
     */
    public boolean acceptsSchema(String database, String schema) {
        String db = normalize(database);
        String sch = normalize(schema);

        for (FilterRule ex : excludeRules) {
            if (ex.matchesNamespace(db, sch)) {
                return false;
            }
        }

        if (!includeRules.isEmpty()) {
            for (FilterRule inc : includeRules) {
                if (inc.matchesNamespace(db, sch)) {
                    return true;
                }
            }
            return false;
        }

        if (!db.isEmpty() && vendorProfile.isSystemDatabase(db)) {
            return false;
        }
        if (!sch.isEmpty() && vendorProfile.isSystemSchema(sch)) {
            return false;
        }
        return true;
    }

    public boolean shouldHarvestViews() {
        return !viewsDisabled;
    }

    public boolean acceptsView(String database, String schema, String viewName) {
        if (viewsDisabled || !acceptsSchema(database, schema)) {
            return false;
        }
        if (viewPatterns.isEmpty()) {
            return true;
        }
        return matchesObjectName(viewPatterns, database, schema, viewName);
    }

    public boolean shouldHarvestRoutines() {
        return !routinesDisabled;
    }

    public boolean acceptsRoutine(String database, String schema, String routineName) {
        if (routinesDisabled || !acceptsSchema(database, schema)) {
            return false;
        }
        if (routinePatterns.isEmpty()) {
            return true;
        }
        return matchesObjectName(routinePatterns, database, schema, routineName);
    }

    private static boolean matchesObjectName(List<Pattern> patterns, String db, String schema, String objName) {
        String name = normalize(objName);
        String schName = schema != null && !schema.isBlank() ? normalize(schema) + "." + name : name;
        String fqn = db != null && !db.isBlank() ? normalize(db) + "." + schName : schName;
        for (Pattern p : patterns) {
            if (p.matcher(name).matches() || p.matcher(schName).matches() || p.matcher(fqn).matches()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExplicitlyDisabled(String raw) {
        if (raw == null) {
            return false;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return "false".equals(t) || "0".equals(t) || "none".equals(t) || "no".equals(t);
    }

    private static List<FilterRule> parseNamespaceRules(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<FilterRule> rules = new ArrayList<>();
        for (String token : raw.split("[,;\\r\\n]+")) {
            String trimmed = stripQuotes(token.trim());
            if (trimmed.isEmpty() || "*".equals(trimmed) || "*.*".equals(trimmed) || "%".equals(trimmed)) {
                continue;
            }
            int slashOrDot = trimmed.indexOf('.');
            if (slashOrDot < 0) {
                slashOrDot = trimmed.indexOf('/');
            }
            if (slashOrDot >= 0) {
                String left = stripQuotes(trimmed.substring(0, slashOrDot).trim());
                String right = stripQuotes(trimmed.substring(slashOrDot + 1).trim());
                // Support 3-part db.schema.obj by taking first two components
                int secondDot = right.indexOf('.');
                if (secondDot >= 0) {
                    right = stripQuotes(right.substring(0, secondDot).trim());
                }
                rules.add(new FilterRule(compileWildcard(left), compileWildcard(right), null));
            } else {
                Pattern single = compileWildcard(trimmed);
                rules.add(new FilterRule(null, null, single));
            }
        }
        return List.copyOf(rules);
    }

    private static List<Pattern> parseObjectPatterns(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(t) || "1".equals(t) || "all".equals(t) || "*".equals(t) || "%".equals(t)) {
            return List.of();
        }
        List<Pattern> list = new ArrayList<>();
        for (String token : raw.split("[,;\\r\\n]+")) {
            String cleaned = stripQuotes(token.trim());
            if (!cleaned.isEmpty()) {
                list.add(compileWildcard(cleaned));
            }
        }
        return List.copyOf(list);
    }

    private static Pattern compileWildcard(String expr) {
        if (expr == null || expr.isEmpty() || "*".equals(expr) || "%".equals(expr)) {
            return Pattern.compile(".*", Pattern.CASE_INSENSITIVE);
        }
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '*' || c == '%') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static boolean matches(Pattern p, String candidate) {
        return p.matcher(candidate == null ? "" : candidate).matches();
    }

    private static String normalize(String s) {
        return s == null ? "" : stripQuotes(s.trim());
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char f = s.charAt(0);
            char l = s.charAt(s.length() - 1);
            if ((f == '"' && l == '"') || (f == '`' && l == '`') || (f == '\'' && l == '\'') || (f == '[' && l == ']')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private record FilterRule(Pattern dbPattern, Pattern schemaPattern, Pattern singleTokenPattern) {
        boolean matchesNamespace(String db, String schema) {
            if (singleTokenPattern != null) {
                if (!schema.isEmpty() && matches(singleTokenPattern, schema)) {
                    return true;
                }
                return !db.isEmpty() && matches(singleTokenPattern, db);
            }
            boolean dbOk = dbPattern == null || db.isEmpty() || matches(dbPattern, db);
            boolean schOk = schemaPattern == null || schema.isEmpty() || matches(schemaPattern, schema);
            return dbOk && schOk;
        }
    }
}
