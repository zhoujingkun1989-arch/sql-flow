/**
 * Exact Visual Design Tokens, 17-Entry `dboColor` Palette, Container Styles,
 * Z-Index Stacking Order, Edge/Surface Colors, and `localStorage` Key Constants.
 * Reference: docs/re-spec/04-frontend-ui-and-widget.md §3, §6, §7, §9, §10.
 */

/**
 * Exact 17-entry `dboColor` node header & border palette (`16` explicit object types + `1` gray fallback)
 * per docs/re-spec/04 §3.3 and docs/re-spec/03 §4.1.2, §4.4.2.
 */
export const DBO_COLORS = {
  table: "#91c051",
  view: "#419a5f",
  resultset: "#d26b58",
  select_list: "#d26b58",
  "insert-select": "#b24b78",
  "update-set": "#d29059",
  "merge-insert": "#893a89",
  "merge-update": "#e46b0a",
  select_union: "#357e7e",
  with_cte: "#ff6e97",
  "update-select": "#5583d8",
  function: "#a0a0a0",
  stage: "#6d30ea",
  variable: "#00a4eb",
  schema: "#1accb3",
  unnest: "#d2d430",
  default_gray: "#dbdada"
} as const;

export type DboColorKey = keyof typeof DBO_COLORS;

export const EXPLICIT_DBO_COLOR_KEYS: readonly Exclude<DboColorKey, "default_gray">[] = [
  "table",
  "view",
  "resultset",
  "select_list",
  "insert-select",
  "update-set",
  "merge-insert",
  "merge-update",
  "select_union",
  "with_cte",
  "update-select",
  "function",
  "stage",
  "variable",
  "schema",
  "unnest"
] as const;

const NORMALIZED_DBO_ALIAS: Readonly<Record<string, DboColorKey>> = {
  materialized_view: "view",
  "materialized view": "view",
  cte: "with_cte",
  insert_select: "insert-select",
  update_set: "update-set",
  merge_insert: "merge-insert",
  merge_update: "merge-update",
  update_select: "update-select",
  "select-list": "select_list",
  "select-union": "select_union",
  procedure: "default_gray",
  package: "default_gray",
  "oracle package": "default_gray",
  trigger: "default_gray",
  database: "default_gray",
  server: "default_gray",
  cursor: "default_gray",
  constant: "default_gray",
  file: "default_gray",
  uri: "default_gray",
  path: "default_gray"
};

/**
 * Resolves the exact node header/border hex color from `DBO_COLORS`.
 * Unknown routine/container types resolve to `DBO_COLORS.default_gray` (`#dbdada`),
 * or `DBO_COLORS.table` (`#91c051`) if `fallbackToTableGreen` is explicitly requested.
 */
export function getDboHeaderColor(
  type?: string | null,
  options?: { fallbackToTableGreen?: boolean }
): string {
  if (!type) {
    return options?.fallbackToTableGreen ? DBO_COLORS.table : DBO_COLORS.default_gray;
  }
  const normalized = type.trim().toLowerCase();
  if (normalized in DBO_COLORS) {
    return DBO_COLORS[normalized as DboColorKey];
  }
  const alias = NORMALIZED_DBO_ALIAS[normalized];
  if (alias) {
    return DBO_COLORS[alias];
  }
  return options?.fallbackToTableGreen ? DBO_COLORS.table : DBO_COLORS.default_gray;
}

export function getDboHeaderTextColor(type?: string | null): string {
  const bg = getDboHeaderColor(type);
  return bg === DBO_COLORS.default_gray ? "#333333" : "#ffffff";
}

export const CONTAINER_DBO_TYPES = new Set([
  "database",
  "oracle package",
  "package",
  "procedure",
  "trigger"
]);

export const CONTAINER_STYLE_TOKENS = {
  headerBg: "#edeef0",
  headerAltBg: "#f5f6f8",
  borderColor: "#cccccc",
  headerTextColor: "#333333",
  bodyBg: "rgba(240,242,245,0.45)",
  paddingX: 16,
  paddingTop: 28,
  paddingBottom: 14
} as const;

export const LOCATED_NODE_TOKENS = {
  boxShadow: "0 0 10px 0 #ff1010bf",
  outline: "2px solid #b33e3ea6"
} as const;

/**
 * Exact Z-Index Stacking Order (`module 84558` & CSS Modules, 04 §3.2).
 */
export const Z_INDEX = {
  header: 50,
  settingPopover: 40,
  treeContextMenu: 31,
  loadingOverlay: 30,
  codeDrawer: 25,
  virtualScrollbar: 25,
  visualizeToolbar: 20,
  summaryInterstitial: 20,
  emptyState: 15,
  slideUpContainer: 15,
  canvasContextMenu: 9,
  actionsBar: 9,
  tryThisBar: 9,
  scaleControls: 5,
  erMinimap: 5,
  outputCopyButton: 2,
  hoveredEdgePath: 1
} as const;

/**
 * Canvas, Node, Column, and Minimap Geometry Constants (`04 §6.2–§6.10`).
 */
export const CANVAS_GEOMETRY_TOKENS = {
  paddingTop: 70,
  paddingLeft: 35,
  paddingLeftMore: 35,
  nodeHeaderHeight: 22,
  tableLevelNodeHeight: 26,
  fieldRowHeight: 16,
  nodeBorderVerticalPx: 2,
  minNodeWidth: 120,
  nodeHorizontalPadding: 24,
  dagreNodesep: 30,
  dagreRanksep: 80,
  transformOrigin: "40px 60px",
  zoomStepInFactor: 1.2,
  zoomStepOutFactor: 0.8,
  minZoom: 0.15,
  maxZoom: 3.0,
  minimapSize: 110,
  summaryObjectThreshold: 200,
  collapsibleColumnThreshold: 30
} as const;

/**
 * Field Row, SQL Token Highlight, and SVG Edge Visual Tokens (`04 §3.4`).
 */
export const SURFACE_AND_EDGE_TOKENS = {
  appHeaderBg: "#33333d",
  appHeaderHeight: 50,
  leftRailWidth: 62,
  canvasBg: "#eeeeee",
  nodeBodyBg: "#ffffff",
  fieldDefaultBg: "#ffffff",
  fieldDefaultText: "#000000",
  fieldHoverBg: "#faebd7",
  fieldHighlightBg: "#dddddd",
  erAccentTopBar: "#38c172",
  erFieldHoverBg: "#f4f4f4",
  erFieldHoverText: "#a855f7",
  erTypeText: "#bebebe",
  codeTokenHoverBg: "#dadada",
  codeTokenHoverText: "#ffffff",
  codeTokenHighlightBg: "#443b3b",
  codeTokenHighlightText: "#ffffff",
  edgeDefaultStroke: "#ababab",
  edgeDefaultWidth: 1,
  edgeHighlightStroke: "#000000",
  edgeHighlightWidth: 1.4,
  edgeDashArray: "4 2",
  edgeHitTargetWidth: 10,
  edgeJoinDefaultStroke: "#a9ce76",
  edgeJoinHighlightStroke: "#65ab04",
  transformCircleDefaultFill: "#a5a3a3",
  transformCircleHoverFill: "#727070",
  transformCircleHighlightFill: "#000000",
  transformCircleStroke: "#ffffff",
  transformCircleRadius: 4,
  streamMoreButtonBorder: "#e3d6d6",
  minimapNodeBg: "#eeeeaa",
  minimapNodeBorder: "#000000",
  minimapLensBorder: "#1890ff"
} as const;

/**
 * Call Graph & Join Analysis V2 Palettes (`04 §3.5, §9.1`).
 */
export const CALL_GRAPH_PALETTE = {
  canvasBg: "#f8fafc",
  edgeStroke: "#64748b",
  edgeStrokeWidth: 1.5,
  routineKindColors: {
    PROCEDURE: "#3b82f6",
    FUNCTION: "#8b5cf6",
    TRIGGER: "#ef4444",
    ANONYMOUS_BLOCK: "#6b7280",
    NESTED_PROCEDURE: "#0ea5e9",
    NESTED_FUNCTION: "#a855f7",
    TYPE_METHOD: "#f59e0b"
  },
  tableAccessColors: {
    READ: "#22c55e",
    WRITE: "#ef4444",
    READ_WRITE: "#f59e0b"
  }
} as const;

export const JOIN_OPERATOR_COLORS = {
  INNER: "#065f46",
  LEFT: "#b45309",
  RIGHT: "#0e7490",
  FULL: "#6d28d9",
  CROSS: "#b91c1c",
  DEFAULT: "#374151"
} as const;

export const JOB_STATUS_COLORS = {
  create: "#409EFF",
  process: "#409EFF",
  success: "#67C23A",
  partial_success: "#E6A23C",
  fail: "#F56C6C",
  delete: "#909399"
} as const;

/**
 * Exact 4 Browser `localStorage` Keys used by SQLFlow (`04 §10.2`).
 */
export const LOCAL_STORAGE_KEYS = {
  USER: "user",
  ACTIONS_BAR_V1: "sqlflow.actionsBar.v1",
  TRY_THIS_DISMISSED_V1: "sqlflow.tryThis.dismissed.v1",
  JOIN_ANALYSIS_V2: "joinAnalysisV2"
} as const;
