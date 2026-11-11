package io.sqlflow.lineage.lexer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies {@code conf/sql_content_mapping.conf} regex substitution rules and credential redaction
 * ({@code OBL-034}, {@code 01 §1.8.3}, {@code 03 §2.4}) while preserving line counts so 1-based line
 * coordinates remain aligned.
 */
public final class SqlContentMapper {

    public record MappingRule(Pattern pattern, String replacement) {
    }

    private static final Pattern PASSWORD_REDACT_PATTERN = Pattern.compile(
            "(?i)(PASSWORD|SECRET|TOKEN|AWS_SECRET_KEY|AWS_KEY_ID|CREDENTIALS|IDENTIFIED\\s+BY)\\s*(=|\\s+)\\s*'[^']*'");

    private final List<MappingRule> rules;
    private final String redactReplacement;

    public SqlContentMapper(List<MappingRule> rules, String redactReplacement) {
        this.rules = rules != null ? List.copyOf(rules) : Collections.emptyList();
        this.redactReplacement = redactReplacement;
    }

    public static SqlContentMapper empty() {
        return new SqlContentMapper(List.of(), null);
    }

    public static SqlContentMapper defaultMapper() {
        try {
            Path p1 = Path.of("conf", "sql_content_mapping.conf");
            if (Files.isRegularFile(p1)) {
                return fromConfFile(p1, null);
            }
            Path p2 = Path.of("/sqlflow/conf/sql_content_mapping.conf");
            if (Files.isRegularFile(p2)) {
                return fromConfFile(p2, null);
            }
        } catch (Exception ignored) {
            // Fall back to empty mapper when conf file is absent or unreadable
        }
        return empty();
    }

    public static SqlContentMapper fromConfText(String confContent) {
        return fromConfText(confContent, null);
    }

    public static SqlContentMapper fromConfText(String confContent, String redactReplacement) {
        if (confContent == null || confContent.isBlank()) {
            return new SqlContentMapper(List.of(), redactReplacement);
        }
        List<MappingRule> parsed = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(confContent))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int sep = findUnescapedEquals(trimmed);
                if (sep <= 0) {
                    continue;
                }
                String regex = trimmed.substring(0, sep).replace("\\=", "=");
                String replacement = trimmed.substring(sep + 1);
                parsed.add(new MappingRule(
                        Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
                        replacement));
            }
        } catch (IOException ignored) {
            // StringReader does not throw IOException
        }
        return new SqlContentMapper(parsed, redactReplacement);
    }

    public static SqlContentMapper fromConfFile(Path path, String redactReplacement) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            return new SqlContentMapper(List.of(), redactReplacement);
        }
        return fromConfText(Files.readString(path, StandardCharsets.UTF_8), redactReplacement);
    }

    public String apply(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        String result = sql;
        for (MappingRule rule : rules) {
            result = applyPreservingNewlines(result, rule.pattern(), rule.replacement());
        }
        if (redactReplacement != null && !redactReplacement.isEmpty()) {
            Matcher m = PASSWORD_REDACT_PATTERN.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(
                        sb,
                        Matcher.quoteReplacement(m.group(1) + m.group(2) + "'" + redactReplacement + "'"));
            }
            m.appendTail(sb);
            result = sb.toString();
        }
        return result;
    }

    private static String applyPreservingNewlines(String input, Pattern pattern, String replacement) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        matcher.reset();
        StringBuilder out = new StringBuilder(input.length());
        while (matcher.find()) {
            String matched = matcher.group();
            String replaced = input.substring(matcher.start(), matcher.end())
                    .replaceFirst(pattern.pattern(), replacement);
            long origNewlines = matched.chars().filter(c -> c == '\n').count();
            long newNewlines = replaced.chars().filter(c -> c == '\n').count();
            if (origNewlines > newNewlines) {
                replaced = replaced + "\n".repeat((int) (origNewlines - newNewlines));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replaced));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static int findUnescapedEquals(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '=' && (i == 0 || line.charAt(i - 1) != '\\')) {
                return i;
            }
        }
        return -1;
    }
}
