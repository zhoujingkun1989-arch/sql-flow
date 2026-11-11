package io.sqlflow.lineage.demo;

import io.sqlflow.contracts.spi.DemoAstServiceSpi.GFmtOpt;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-processing passes for {@link SqlFormatterEngine}: SELECT alias alignment, CREATE TABLE
 * datatype alignment, line-width wrapping, empty-line merging, and optional line numbering.
 */
final class SqlFormatterPostProcessor {

    private static final Pattern AS_ALIAS_LINE = Pattern.compile(
            "^(\\s*)(.+?)\\s+(AS\\s+[^,\\s]+|\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\])(,?)(\\s*(?:--.*|/\\*.*\\*/)?)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_COL_LINE = Pattern.compile(
            "^(\\s+)([A-Za-z_\"`\\[][^\\s,()]+)\\s+([A-Za-z][A-Za-z0-9_]*(?:\\s*\\([^)]*\\))?.*)$");

    private SqlFormatterPostProcessor() {
    }

    static String postProcess(String formatted, GFmtOpt opt) {
        if (formatted == null || formatted.isEmpty()) {
            return "";
        }
        String text = formatted;
        if (isCompactMode(opt.getCompactMode())) {
            text = collapseCompactPreservingLineComments(text);
        } else {
            if (opt.isAlignAliasInSelectList() && isStacked(opt.getSelectColumnlistStyle(), opt.isSelectItemInNewLine())) {
                text = alignSelectAliases(text);
            }
            if (opt.isCreateTableItemAlignDatatype() && isStacked(opt.getCreateTableItemlistStyle(), false)) {
                text = alignCreateTableDatatypes(text);
            }
            if (opt.getLineWidth() > 0) {
                text = wrapLongLines(text, opt.getLineWidth(), indentUnit(opt));
            }
        }
        text = normalizeEmptyLines(text, opt.getEmptyLines());
        if (opt.isLinenumberEnabled()) {
            text = addLineNumbers(text, opt.isLinenumberZeroBased(), opt.getLinenumberLeftMargin());
        }
        return text;
    }

    static String indentUnit(GFmtOpt opt) {
        if (opt.isUseTab()) {
            return "\t";
        }
        return " ".repeat(Math.max(1, opt.getIndentLen()));
    }

    static boolean isStacked(String style, boolean forceNewline) {
        if (forceNewline) {
            return true;
        }
        if (style == null || style.isBlank()) {
            return true;
        }
        String s = style.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("stack") || s.equals("newline") || s.equals("multiline");
    }

    static boolean isLeadingComma(String specificComma, String defaultComma) {
        String c = (specificComma != null && !specificComma.isBlank()) ? specificComma : defaultComma;
        if (c == null) {
            return false;
        }
        String lower = c.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("before") || lower.startsWith("lead") || lower.startsWith("front");
    }

    static boolean isCompactMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return false;
        }
        String lower = mode.trim().toLowerCase(Locale.ROOT);
        return lower.equals("compact") || lower.equals("singleline") || lower.equals("single_line");
    }

    private static String alignSelectAliases(String text) {
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String trimmed = lines[i].trim().toUpperCase(Locale.ROOT);
            if (trimmed.equals("SELECT") || trimmed.startsWith("SELECT DISTINCT")) {
                int start = i + 1;
                int end = start;
                int maxExprEnd = 0;
                while (end < lines.length) {
                    String t = lines[end].trim().toUpperCase(Locale.ROOT);
                    if (t.isEmpty() || t.startsWith("FROM ") || t.equals("FROM") || t.startsWith("WHERE ")
                            || t.startsWith("INTO ") || t.endsWith(";")) {
                        break;
                    }
                    Matcher m = AS_ALIAS_LINE.matcher(lines[end]);
                    if (m.matches() && m.group(3).toUpperCase(Locale.ROOT).startsWith("AS ")) {
                        maxExprEnd = Math.max(maxExprEnd, m.group(1).length() + m.group(2).length());
                    }
                    end++;
                }
                if (maxExprEnd > 0 && end - start >= 2) {
                    for (int k = start; k < end; k++) {
                        Matcher m = AS_ALIAS_LINE.matcher(lines[k]);
                        if (m.matches() && m.group(3).toUpperCase(Locale.ROOT).startsWith("AS ")) {
                            int curLen = m.group(1).length() + m.group(2).length();
                            int pad = Math.max(1, maxExprEnd - curLen + 1);
                            lines[k] = m.group(1) + m.group(2) + " ".repeat(pad) + m.group(3) + m.group(4) + m.group(5);
                        }
                    }
                }
                i = end;
            } else {
                i++;
            }
        }
        return String.join("\n", lines);
    }

    private static String alignCreateTableDatatypes(String text) {
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String upper = lines[i].trim().toUpperCase(Locale.ROOT);
            if (upper.startsWith("CREATE ") && upper.contains("TABLE ")) {
                int start = i + 1;
                int end = start;
                int maxColEnd = 0;
                while (end < lines.length && !lines[end].trim().startsWith(")")) {
                    Matcher m = CREATE_COL_LINE.matcher(lines[end]);
                    String colUpper = m.matches() ? m.group(2).toUpperCase(Locale.ROOT) : "";
                    if (m.matches() && !isTableConstraintHead(colUpper)) {
                        maxColEnd = Math.max(maxColEnd, m.group(1).length() + m.group(2).length());
                    }
                    end++;
                }
                if (maxColEnd > 0 && end - start >= 2) {
                    for (int k = start; k < end; k++) {
                        Matcher m = CREATE_COL_LINE.matcher(lines[k]);
                        String colUpper = m.matches() ? m.group(2).toUpperCase(Locale.ROOT) : "";
                        if (m.matches() && !isTableConstraintHead(colUpper)) {
                            int curLen = m.group(1).length() + m.group(2).length();
                            int pad = Math.max(1, maxColEnd - curLen + 1);
                            lines[k] = m.group(1) + m.group(2) + " ".repeat(pad) + m.group(3);
                        }
                    }
                }
                i = end;
            } else {
                i++;
            }
        }
        return String.join("\n", lines);
    }

    private static boolean isTableConstraintHead(String word) {
        return word.equals("CONSTRAINT") || word.equals("PRIMARY") || word.equals("FOREIGN")
                || word.equals("UNIQUE") || word.equals("CHECK") || word.equals("INDEX") || word.equals("KEY");
    }

    private static String wrapLongLines(String text, int maxWidth, String indentUnit) {
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (line.length() <= maxWidth || line.trim().startsWith("--") || line.trim().startsWith("/*")) {
                out.add(line);
                continue;
            }
            int leadLen = 0;
            while (leadLen < line.length() && Character.isWhitespace(line.charAt(leadLen))) {
                leadLen++;
            }
            String contIndent = line.substring(0, leadLen) + indentUnit;
            String remaining = line;
            while (remaining.length() > maxWidth) {
                int splitAt = findSafeWrapPoint(remaining, maxWidth);
                if (splitAt <= leadLen) {
                    break;
                }
                out.add(remaining.substring(0, splitAt).stripTrailing());
                remaining = contIndent + remaining.substring(splitAt).stripLeading();
                leadLen = contIndent.length();
            }
            out.add(remaining);
        }
        return String.join("\n", out);
    }

    private static int findSafeWrapPoint(String s, int maxWidth) {
        boolean inSingle = false;
        boolean inDouble = false;
        int best = -1;
        int limit = Math.min(s.length() - 1, maxWidth);
        for (int i = 0; i <= limit; i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble && (c == ' ' || c == ',')) {
                best = c == ',' ? i + 1 : i;
            }
        }
        return best;
    }

    private static String collapseCompactPreservingLineComments(String text) {
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(trimmed);
            if (trimmed.contains("--") || trimmed.contains("//")) {
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static String normalizeEmptyLines(String text, String mode) {
        String m = mode != null ? mode.trim().toLowerCase(Locale.ROOT) : "mergeintoone";
        if (m.startsWith("remove")) {
            return text.replaceAll("(?m)^[ \\t]*\\r?\\n", "").trim();
        }
        if (m.startsWith("preserve")) {
            return text.stripTrailing();
        }
        return text.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String addLineNumbers(String text, boolean zeroBased, int leftMargin) {
        String[] lines = text.split("\n", -1);
        int maxNum = (zeroBased ? 0 : 1) + Math.max(0, lines.length - 1);
        int width = Math.max(1, String.valueOf(maxNum).length());
        String margin = " ".repeat(Math.max(0, leftMargin));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            int num = (zeroBased ? 0 : 1) + i;
            sb.append(margin).append(String.format("%" + width + "d: ", num)).append(lines[i]);
        }
        return sb.toString();
    }
}
