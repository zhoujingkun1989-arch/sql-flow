package io.sqlflow.contracts.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Frozen 17-entry node header color palette shared identically between backend PNG rendering
 * ({@code GraphRenderer}) and frontend SVG rendering ({@code DboColorPalette}) ({@code 00 §3.3 #20}, {@code 03 §4.4.2}).
 */
public final class DboColorPalette {

    public static final String TABLE = "#91c051";
    public static final String VIEW = "#419a5f";
    public static final String RESULTSET = "#d26b58";
    public static final String SELECT_LIST = "#d26b58";
    public static final String INSERT_SELECT = "#b24b78";
    public static final String UPDATE_SET = "#d29059";
    public static final String MERGE_INSERT = "#893a89";
    public static final String MERGE_UPDATE = "#e46b0a";
    public static final String SELECT_UNION = "#357e7e";
    public static final String WITH_CTE = "#ff6e97";
    public static final String UPDATE_SELECT = "#5583d8";
    public static final String FUNCTION = "#a0a0a0";
    public static final String STAGE = "#6d30ea";
    public static final String VARIABLE = "#00a4eb";
    public static final String SCHEMA = "#1accb3";
    public static final String UNNEST = "#d2d430";
    public static final String PROCEDURE_OR_FALLBACK = "#dbdada";
    public static final String CONSTANT = "#e8c559";

    public static final String CANVAS_BACKGROUND = "#eeeeee";
    public static final String CONTAINER_BORDER = "#bdbdbd";
    public static final String COLUMN_BACKGROUND = "#ffffff";
    public static final String COLUMN_DIVIDER = "#f5f5f5";
    public static final String EDGE_STROKE = "#ababab";

    private static final Map<String, String> HEADER_COLORS;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("table", TABLE);
        map.put("view", VIEW);
        map.put("materialized view", VIEW);
        map.put("resultset", RESULTSET);
        map.put("select_list", SELECT_LIST);
        map.put("insert-select", INSERT_SELECT);
        map.put("update-set", UPDATE_SET);
        map.put("merge-insert", MERGE_INSERT);
        map.put("merge-update", MERGE_UPDATE);
        map.put("select_union", SELECT_UNION);
        map.put("with_cte", WITH_CTE);
        map.put("update-select", UPDATE_SELECT);
        map.put("function", FUNCTION);
        map.put("stage", STAGE);
        map.put("variable", VARIABLE);
        map.put("schema", SCHEMA);
        map.put("unnest", UNNEST);
        map.put("procedure", PROCEDURE_OR_FALLBACK);
        map.put("package", PROCEDURE_OR_FALLBACK);
        map.put("constant", CONSTANT);
        HEADER_COLORS = Collections.unmodifiableMap(map);
    }

    private DboColorPalette() {
    }

    public static Map<String, String> headerColorMap() {
        return HEADER_COLORS;
    }

    public static String resolveHeaderColor(String type, String subType) {
        if (subType != null && !subType.isBlank()) {
            String bySubType = HEADER_COLORS.get(subType.trim().toLowerCase(Locale.ROOT));
            if (bySubType != null) {
                return bySubType;
            }
        }
        if (type != null && !type.isBlank()) {
            String byType = HEADER_COLORS.get(type.trim().toLowerCase(Locale.ROOT));
            if (byType != null) {
                return byType;
            }
        }
        return PROCEDURE_OR_FALLBACK;
    }
}
