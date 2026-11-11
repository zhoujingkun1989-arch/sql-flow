package io.sqlflow.lineage.demo;

import io.sqlflow.contracts.spi.DemoAstServiceSpi.FormatConfiguration;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.FormatRequest;
import io.sqlflow.contracts.spi.DemoAstServiceSpi.GFmtOpt;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves {@link GFmtOpt} across direct typed options and all 19 nested {@code format_options}
 * configuration groups ({@code 02 §4.9 #6}, {@code OBL-061}).
 */
final class SqlFormatterOptionsResolver {

    private SqlFormatterOptionsResolver() {
    }

    static GFmtOpt resolve(FormatRequest request) {
        GFmtOpt out = copyOptions(request != null ? request.getOptions() : null);
        if (request == null) {
            return out;
        }
        FormatConfiguration cfg = request.getFormatOption();
        if (cfg == null || cfg.getFormatOptions() == null || cfg.getFormatOptions().isEmpty()) {
            return out;
        }

        Map<String, Object> flat = flattenOptions(cfg.getFormatOptions());
        // 1. Line numbers
        applyBool(flat, out::setLinenumberEnabled, "linenumberEnabled", "linenumber_enabled", "line_number.enabled", "linenumber.enabled");
        applyInt(flat, out::setLinenumberLeftMargin, "linenumberLeftMargin", "linenumber_left_margin", "line_number.left_margin");
        applyBool(flat, out::setLinenumberZeroBased, "linenumberZeroBased", "linenumber_zero_based", "line_number.zero_based");

        // 2. Indentation & tabs
        applyInt(flat, out::setIndentLen, "indentLen", "indent_length", "indent_len", "indentation.indent_length", "indentation.indentLen");
        applyBool(flat, out::setUseTab, "useTab", "use_tab", "indentation.use_tab", "indentation.useTab");
        applyInt(flat, out::setTabSize, "tabSize", "tab_size", "indentation.tab_size", "indentation.tabSize");

        // 3. Capitalization
        applyCase(flat, out::setCaseKeywords, "caseKeywords", "case_keywords", "capitalization.case_keywords", "capitalization.caseKeywords");
        applyCase(flat, out::setCaseIdentifier, "caseIdentifier", "case_identifier", "capitalization.case_identifier", "capitalization.caseIdentifier");
        applyCase(flat, out::setCaseFuncname, "caseFuncname", "case_funcname", "capitalization.case_funcname", "capitalization.caseFuncname");
        applyCase(flat, out::setCaseDatatype, "caseDatatype", "case_datatype", "capitalization.case_datatype", "capitalization.caseDatatype");
        applyCase(flat, out::setCaseQuotedIdentifier, "caseQuotedIdentifier", "case_quoted_identifier", "capitalization.case_quoted_identifier");

        // 4. Whitespace & padding
        applyBool(flat, out::setWsPaddingOperatorArithmetic, "wsPaddingOperatorArithmetic", "ws_padding_operator_arithmetic", "whitespace.padding_operator_arithmetic");
        applyBool(flat, out::setWsPaddingParenthesesInFunction, "wsPaddingParenthesesInFunction", "ws_padding_parentheses_in_function", "whitespace.padding_parentheses_in_function");
        applyBool(flat, out::setWsPaddingParenthesesInFunctionCall, "wsPaddingParenthesesInFunctionCall", "ws_padding_parentheses_in_function_call", "whitespace.padding_parentheses_in_function_call");
        applyBool(flat, out::setWsPaddingParenthesesOfSubQuery, "wsPaddingParenthesesOfSubQuery", "ws_padding_parentheses_of_sub_query", "subquery.padding_parentheses", "whitespace.padding_parentheses_of_subquery");
        applyBool(flat, out::setWsPaddingParenthesesOfTypename, "wsPaddingParenthesesOfTypename", "ws_padding_parentheses_of_typename", "whitespace.padding_parentheses_of_typename");

        // 5-8. SELECT / FROM / JOIN / WHERE
        applyStr(flat, out::setSelectColumnlistStyle, "selectColumnlistStyle", "select_columnlist_style", "select.columnlist_style");
        applyStr(flat, out::setSelectColumnlistComma, "selectColumnlistComma", "select_columnlist_comma", "select.columnlist_comma");
        applyBool(flat, out::setSelectItemInNewLine, "selectItemInNewLine", "select_item_in_new_line", "select.item_in_new_line");
        applyBool(flat, out::setAlignAliasInSelectList, "alignAliasInSelectList", "align_alias_in_select_list", "alignment.align_alias_in_select_list", "select.align_alias");
        applyBool(flat, out::setTreatDistinctAsVirtualColumn, "treatDistinctAsVirtualColumn", "treat_distinct_as_virtual_column", "select.treat_distinct_as_virtual_column");
        applyBool(flat, out::setFromClauseJoinInNewline, "fromClauseJoinInNewline", "from_clause_join_in_newline", "select.from_clause_join_in_newline");
        applyBool(flat, out::setFromClauseInNewline, "fromClauseInNewline", "from_clause_in_newline", "select.from_clause_in_newline");
        applyBool(flat, out::setAlignJoinWithFromKeyword, "alignJoinWithFromKeyword", "align_join_with_from_keyword", "alignment.align_join_with_from_keyword");
        applyBool(flat, out::setAndOrUnderWhere, "andOrUnderWhere", "and_or_under_where", "expression.and_or_under_where");
        applyBool(flat, out::setWhereClauseInNewline, "whereClauseInNewline", "where_clause_in_newline", "select.where_clause_in_newline");

        // 9-14. INSERT / UPDATE / CREATE TABLE / GROUP BY / ORDER BY
        applyStr(flat, out::setInsertColumnlistStyle, "insertColumnlistStyle", "insert_columnlist_style", "insert.columnlist_style");
        applyStr(flat, out::setInsertValuelistStyle, "insertValuelistStyle", "insert_valuelist_style", "insert.valuelist_style");
        applyStr(flat, out::setUpdateSetlistStyle, "updateSetlistStyle", "update_setlist_style", "update.setlist_style");
        applyStr(flat, out::setCreateTableItemlistStyle, "createTableItemlistStyle", "create_table_itemlist_style", "create_table.itemlist_style");
        applyBool(flat, out::setCreateTableItemAlignDatatype, "createTableItemAlignDatatype", "create_table_item_align_datatype", "create_table.align_datatype", "alignment.align_datatype");
        applyStr(flat, out::setGroupbyColumnlistStyle, "groupbyColumnlistStyle", "groupby_columnlist_style", "select.groupby_columnlist_style");
        applyStr(flat, out::setOrderbyColumnlistStyle, "orderbyColumnlistStyle", "orderby_columnlist_style", "select.orderby_columnlist_style");
        applyStr(flat, out::setDefaultCommaOption, "defaultCommaOption", "default_comma_option", "default_option.comma_option");

        // 15-19. Blocks, IF/WHILE, Compact, LineWidth, EmptyLines
        applyBool(flat, out::setBeStyleBlockLeftBEOnNewline, "beStyleBlockLeftBEOnNewline", "be_style_block_left_be_on_newline", "execute.block_left_be_on_newline");
        applyBool(flat, out::setBeStyleBlockRightBEOnNewline, "beStyleBlockRightBEOnNewline", "be_style_block_right_be_on_newline", "execute.block_right_be_on_newline");
        applyInt(flat, out::setBeStyleBlockIndentSize, "beStyleBlockIndentSize", "be_style_block_indent_size", "execute.block_indent_size");
        applyInt(flat, out::setBeStyleIfWhileIndentSize, "beStyleIfWhileIndentSize", "be_style_if_while_indent_size", "execute.if_while_indent_size");
        applyStr(flat, out::setCompactMode, "compactMode", "compact_mode", "default_option.compact_mode");
        applyInt(flat, out::setLineWidth, "lineWidth", "line_width", "line_width.max_length", "line_width.width");
        applyStr(flat, out::setEmptyLines, "emptyLines", "empty_lines", "blankline.empty_lines");
        return out;
    }

    static String formatWordCase(String rawWord, String style) {
        if (rawWord == null || rawWord.isEmpty() || style == null) {
            return rawWord;
        }
        return switch (normalizeCaseStyle(style)) {
            case "Upper" -> rawWord.toUpperCase(Locale.ROOT);
            case "Lower" -> rawWord.toLowerCase(Locale.ROOT);
            case "InitCap" -> toInitCap(rawWord);
            default -> rawWord;
        };
    }

    static String formatQuotedIdentifier(String rawToken, String style) {
        if (rawToken == null || rawToken.length() < 2 || style == null) {
            return rawToken;
        }
        String norm = normalizeCaseStyle(style);
        if ("NoChange".equals(norm)) {
            return rawToken;
        }
        if (rawToken.startsWith("#\"") && rawToken.endsWith("\"") && rawToken.length() >= 3) {
            String inner = rawToken.substring(2, rawToken.length() - 1);
            return "#\"" + formatWordCase(inner, norm) + "\"";
        }
        char open = rawToken.charAt(0);
        char close = rawToken.charAt(rawToken.length() - 1);
        String inner = rawToken.substring(1, rawToken.length() - 1);
        return open + formatWordCase(inner, norm) + close;
    }

    static String normalizeCaseStyle(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NoChange";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "upper", "uppercase", "caseupper" -> "Upper";
            case "lower", "lowercase", "caselower" -> "Lower";
            case "initcap", "capitalize", "caseinitcap" -> "InitCap";
            case "nochange", "unchanged", "preserve", "casenochange" -> "NoChange";
            default -> raw.trim();
        };
    }

    private static String toInitCap(String word) {
        StringBuilder sb = new StringBuilder(word.length());
        boolean capNext = true;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c)) {
                sb.append(capNext ? Character.toUpperCase(c) : Character.toLowerCase(c));
                capNext = false;
            } else {
                sb.append(c);
                capNext = (c == '_' || c == '.' || c == '$');
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> flattenOptions(Map<String, Object> map) {
        Map<String, Object> flat = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            if (e.getValue() instanceof Map<?, ?> sub) {
                for (Map.Entry<?, ?> se : sub.entrySet()) {
                    if (se.getKey() != null && se.getValue() != null) {
                        String subKey = String.valueOf(se.getKey());
                        flat.putIfAbsent(subKey, se.getValue());
                        flat.put(e.getKey() + "." + subKey, se.getValue());
                    }
                }
            } else {
                flat.put(e.getKey(), e.getValue());
            }
        }
        return flat;
    }

    private static GFmtOpt copyOptions(GFmtOpt src) {
        GFmtOpt dst = new GFmtOpt();
        if (src == null) {
            return dst;
        }
        dst.setLinenumberEnabled(src.isLinenumberEnabled());
        dst.setLinenumberLeftMargin(src.getLinenumberLeftMargin());
        dst.setLinenumberZeroBased(src.isLinenumberZeroBased());
        dst.setIndentLen(src.getIndentLen());
        dst.setUseTab(src.isUseTab());
        dst.setTabSize(src.getTabSize());
        dst.setCaseKeywords(src.getCaseKeywords());
        dst.setCaseIdentifier(src.getCaseIdentifier());
        dst.setCaseFuncname(src.getCaseFuncname());
        dst.setCaseDatatype(src.getCaseDatatype());
        dst.setCaseQuotedIdentifier(src.getCaseQuotedIdentifier());
        dst.setWsPaddingOperatorArithmetic(src.isWsPaddingOperatorArithmetic());
        dst.setWsPaddingParenthesesInFunction(src.isWsPaddingParenthesesInFunction());
        dst.setWsPaddingParenthesesInFunctionCall(src.isWsPaddingParenthesesInFunctionCall());
        dst.setWsPaddingParenthesesOfSubQuery(src.isWsPaddingParenthesesOfSubQuery());
        dst.setWsPaddingParenthesesOfTypename(src.isWsPaddingParenthesesOfTypename());
        dst.setSelectColumnlistStyle(src.getSelectColumnlistStyle());
        dst.setSelectColumnlistComma(src.getSelectColumnlistComma());
        dst.setSelectItemInNewLine(src.isSelectItemInNewLine());
        dst.setAlignAliasInSelectList(src.isAlignAliasInSelectList());
        dst.setTreatDistinctAsVirtualColumn(src.isTreatDistinctAsVirtualColumn());
        dst.setFromClauseJoinInNewline(src.isFromClauseJoinInNewline());
        dst.setFromClauseInNewline(src.isFromClauseInNewline());
        dst.setAlignJoinWithFromKeyword(src.isAlignJoinWithFromKeyword());
        dst.setAndOrUnderWhere(src.isAndOrUnderWhere());
        dst.setWhereClauseInNewline(src.isWhereClauseInNewline());
        dst.setInsertColumnlistStyle(src.getInsertColumnlistStyle());
        dst.setInsertValuelistStyle(src.getInsertValuelistStyle());
        dst.setUpdateSetlistStyle(src.getUpdateSetlistStyle());
        dst.setCreateTableItemlistStyle(src.getCreateTableItemlistStyle());
        dst.setCreateTableItemAlignDatatype(src.isCreateTableItemAlignDatatype());
        dst.setGroupbyColumnlistStyle(src.getGroupbyColumnlistStyle());
        dst.setOrderbyColumnlistStyle(src.getOrderbyColumnlistStyle());
        dst.setDefaultCommaOption(src.getDefaultCommaOption());
        dst.setBeStyleBlockLeftBEOnNewline(src.isBeStyleBlockLeftBEOnNewline());
        dst.setBeStyleBlockRightBEOnNewline(src.isBeStyleBlockRightBEOnNewline());
        dst.setBeStyleBlockIndentSize(src.getBeStyleBlockIndentSize());
        dst.setBeStyleIfWhileIndentSize(src.isBeStyleBlockLeftBEOnNewline() ? src.getBeStyleIfWhileIndentSize() : src.getBeStyleIfWhileIndentSize());
        dst.setCompactMode(src.getCompactMode());
        dst.setLineWidth(src.getLineWidth());
        dst.setEmptyLines(src.getEmptyLines());
        return dst;
    }

    private static void applyCase(Map<String, Object> flat, java.util.function.Consumer<String> setter, String... keys) {
        for (String k : keys) {
            if (flat.containsKey(k)) {
                setter.accept(normalizeCaseStyle(String.valueOf(flat.get(k))));
                return;
            }
        }
    }

    private static void applyStr(Map<String, Object> flat, java.util.function.Consumer<String> setter, String... keys) {
        for (String k : keys) {
            if (flat.containsKey(k)) {
                setter.accept(String.valueOf(flat.get(k)).trim());
                return;
            }
        }
    }

    private static void applyInt(Map<String, Object> flat, java.util.function.IntConsumer setter, String... keys) {
        for (String k : keys) {
            if (flat.containsKey(k)) {
                try {
                    setter.accept(Integer.parseInt(String.valueOf(flat.get(k)).trim()));
                    return;
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private static void applyBool(Map<String, Object> flat, java.util.function.Consumer<Boolean> setter, String... keys) {
        for (String k : keys) {
            if (flat.containsKey(k)) {
                String v = String.valueOf(flat.get(k)).trim();
                setter.accept("true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v));
                return;
            }
        }
    }
}
