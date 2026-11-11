/**
 * Public & Internal TypeScript Contracts for `@sqlflow/widget-sdk` (`3.6.16` parity).
 *
 * Covers both:
 * - Shipped `P7` UMD Widget API (`docs/re-spec/04-frontend-ui-and-widget.md` §11.1–§11.6)
 * - Clean-room Obligation Ledger `OBL-111`..`OBL-120` (`docs/re-spec/00-master-architecture-and-ledger.md` §4.8)
 */

import type {
  ApiEnvelope,
  CallGraphPayload,
  CanvasNode,
  Coordinate,
  DataFlow,
  DependencyGraphV1,
  JoinAnalysisGraph,
  LayoutRankDir,
  LineageError,
  LineageSettings,
  JobSummary,
  OnFieldClickPayload,
  OnTableNameClickPayload,
  SQLFlowWidgetComponentOptions,
  SqlflowJSON,
  Transform
} from "@sqlflow/ui-core";

export const WIDGET_VERSION = "3.6.16" as const;
export const WIDGET_RELEASE = "v_3.6.16_20261027_085209" as const;
export const WIDGET_BUILD_DATE = "2026-10-27 08:52:09" as const;

export type WidgetTheme = "light" | "dark";
export type WidgetLang = "en" | "cn";
export type WidgetViewMode = "lineage" | "er" | "join" | "callgraph" | "share";

export interface ExtendedWidgetComponentOptions extends SQLFlowWidgetComponentOptions {
  toolbar?: boolean;
  search?: boolean;
  settings?: boolean;
  export?: boolean;
  sqlList?: boolean;
  scaleControls?: boolean;
}

export interface WidgetVisibilityConfig {
  sqlEditor: boolean;
  graphLocate: boolean;
  minimap: boolean;
  toolbar: boolean;
  settings: boolean;
  export: boolean;
  sqlList: boolean;
  scaleControls: boolean;
}

export interface ReadyEventPayload {
  version: string;
  container: HTMLElement;
  iframe: HTMLIFrameElement | null;
  options: ResolvedWidgetOptions;
}

export interface NodeClickEventPayload {
  nodeId: string;
  database?: string;
  schema?: string;
  table: string;
  type: string;
  displayTitle: string;
  coordinates: Coordinate[];
}

export interface ColumnClickEventPayload extends OnFieldClickPayload {
  field?: string;
  dataType?: string;
  nodeId: string;
  columnId: string;
  coordinates: Coordinate[];
}

export interface EdgeClickEventPayload {
  edgeId: string;
  sourceNodeId: string;
  sourceColumnId?: string;
  targetNodeId: string;
  targetColumnId?: string;
  type: string;
  effectType?: string;
  transforms: Transform[];
  coordinates: Coordinate[];
}

export interface CanvasClickEventPayload {
  x: number;
  y: number;
}

export interface SqlChangeEventPayload {
  sqltext: string;
  dbvendor: string;
}

export interface VendorChangeEventPayload {
  dbvendor: string;
  previousVendor: string;
}

export type VisualizationKind =
  | "sql"
  | "tableLevel"
  | "er"
  | "join"
  | "job"
  | "json"
  | "erJson"
  | "callGraphJson"
  | "dependencyGraph"
  | "upstream"
  | "downstream"
  | "share";

export interface VisualizeStartEventPayload {
  kind: VisualizationKind;
  dbvendor: string;
  sqltext?: string;
  jobId?: string;
}

export interface VisualizeSuccessEventPayload {
  kind: VisualizationKind;
  nodeCount: number;
  edgeCount: number;
  mode: string;
  sessionId?: string;
  jobId?: string;
  data?: unknown;
}

export interface VisualizeErrorEventPayload {
  kind: VisualizationKind;
  error: string;
  errors?: LineageError[];
}

export interface JobSelectEventPayload {
  jobId: string;
  job?: JobSummary;
}

export interface WidgetEventMap {
  ready: ReadyEventPayload;
  nodeClick: NodeClickEventPayload;
  columnClick: ColumnClickEventPayload;
  edgeClick: EdgeClickEventPayload;
  canvasClick: CanvasClickEventPayload;
  sqlChange: SqlChangeEventPayload;
  vendorChange: VendorChangeEventPayload;
  visualizeStart: VisualizeStartEventPayload;
  visualizeSuccess: VisualizeSuccessEventPayload;
  visualizeError: VisualizeErrorEventPayload;
  jobSelect: JobSelectEventPayload;
  /** Shipped `04 §11.5` callback aliases */
  onFieldClick: OnFieldClickPayload;
  onTableNameClick: OnTableNameClickPayload;
}

export type WidgetEventName = keyof WidgetEventMap;
export type WidgetEventListener<K extends WidgetEventName = WidgetEventName> = (
  payload: WidgetEventMap[K]
) => void;

export interface SQLFlowInitOptions {
  /** Mount target element or CSS selector (e.g. `"#sqlflow"`). */
  container: HTMLElement | string;
  /** Container width (`number` -> `"Npx"`, `string` -> CSS dimension; defaults to `"100%"` when omitted). */
  width?: number | string;
  /** Container height (`number` -> `"Npx"`, `string` -> CSS dimension; defaults to `"100%"` when omitted). */
  height?: number | string;
  /** Backend prefix before `/gspLive_backend` (defaults to `"/gspLive_backend"`). */
  apiPrefix?: string;
  /** Optional auth token sent as `Authorization: Token <token>`. */
  token?: string;
  /** Default guest identity `"gudu|0123456789"`. */
  userId?: string;
  /** Active short dialect key (`"oracle"`, `"snowflake"`, `"postgresql"`, etc.) or `dbv*`. */
  dbvendor?: string;
  /** Initial SQL query text. */
  sqltext?: string;
  /** Initial job ID. */
  jobId?: string;
  /** Initial share ID when mounting a shared snapshot. */
  shareId?: string;
  /** Standalone share viewer mode (`04 §11.3`). */
  visualizeShare?: boolean;
  /** Automatically invoke `visualize()` / `visualizeJob()` during `init()` when `sqltext` or `jobId` is set. */
  autoVisualize?: boolean;
  /** Color theme (`"light"` default, `"dark"`). */
  theme?: WidgetTheme;
  /** UI language (`"en"` default, `"cn"` Simplified Chinese). */
  lang?: WidgetLang;
  /** Initial Dagre graph layout rank direction (`"LR"` default, `"TB"`). */
  orientation?: LayoutRankDir;
  /** Mount directly in `container` without an `<iframe>` when `true` (`false` default -> style-isolated same-origin `<iframe>`). */
  inline?: boolean;
  /** Custom `fetch` implementation (useful for testing/proxies). */
  fetchImpl?: typeof fetch;

  /** `04 §11.3` sub-chrome visibility object or boolean shorthand */
  component?: boolean | ExtendedWidgetComponentOptions;

  /** Top-level visibility toggle aliases (`OBL-113`) */
  editor?: boolean;
  toolbar?: boolean;
  minimap?: boolean;
  search?: boolean;
  settings?: boolean;
  export?: boolean;
  sqlList?: boolean;
  scaleControls?: boolean;

  /** Optional inline event listener callbacks (`04 §11.3–§11.5` & `OBL-117`) */
  onReady?: WidgetEventListener<"ready">;
  onFieldClick?: WidgetEventListener<"onFieldClick">;
  onTableNameClick?: WidgetEventListener<"onTableNameClick">;
  onNodeClick?: WidgetEventListener<"nodeClick">;
  onColumnClick?: WidgetEventListener<"columnClick">;
  onEdgeClick?: WidgetEventListener<"edgeClick">;
  onCanvasClick?: WidgetEventListener<"canvasClick">;
  onSqlChange?: WidgetEventListener<"sqlChange">;
  onVendorChange?: WidgetEventListener<"vendorChange">;
  onVisualizeStart?: WidgetEventListener<"visualizeStart">;
  onVisualizeSuccess?: WidgetEventListener<"visualizeSuccess">;
  onVisualizeError?: WidgetEventListener<"visualizeError">;
  onJobSelect?: WidgetEventListener<"jobSelect">;
}

export interface ResolvedWidgetOptions {
  width: number | string;
  height: number | string;
  apiPrefix: string;
  token: string;
  userId: string;
  dbvendor: string;
  sqltext: string;
  jobId?: string;
  shareId?: string;
  visualizeShare: boolean;
  theme: WidgetTheme;
  lang: WidgetLang;
  orientation: LayoutRankDir;
  inline: boolean;
  visibility: WidgetVisibilityConfig;
}

export interface VisualizeJsonOptions {
  layout?: boolean;
  sqltext?: string;
  tableLevel?: boolean;
  bypassSummaryGate?: boolean;
}

export interface VisualizeSqlOptions {
  tableLevel?: boolean;
  layout?: boolean;
  showTransform?: boolean;
  ignoreRecordSet?: boolean;
}

export interface VisualizeJobParams {
  database?: string;
  schema?: string;
  table?: string;
  column?: string;
  tableLevel?: boolean;
}

export interface StreamTargetOptions {
  database?: string;
  schema?: string;
  table: string;
  column?: string;
  distance?: number;
  jobId?: string;
  sessionId?: string;
  clientSide?: boolean;
}

export type UpstreamDownstreamTarget = string | StreamTargetOptions;

export interface WidgetTreeNode {
  key: string;
  title: string;
  type: "database" | "schema" | "table" | "view" | "procedure" | "function" | "package" | "column" | string;
  database?: string;
  schema?: string;
  table?: string;
  column?: string;
  children?: WidgetTreeNode[];
}

export interface ValuePropertyAccessor<T> {
  get: () => T;
  set: (value: T) => void;
}

export type WidgetSettingKey =
  | "dataflow"
  | "impact"
  | "dataflowOfAggregateFunction"
  | "showIntermediateRecordset"
  | "showFunction"
  | "showConstant"
  | "showTransform";

export type WidgetSettingsNamespace = {
  [K in WidgetSettingKey]: ValuePropertyAccessor<boolean>;
} & {
  getAll: () => LineageSettings;
};

export interface CallableStreamNamespace {
  (target: UpstreamDownstreamTarget, distance?: number): Promise<SqlflowJSON | void>;
  visualize: (opts: StreamTargetOptions) => Promise<SqlflowJSON | void>;
}

export interface SQLFlowWidgetInstance {
  readonly version: string;
  readonly release: string;

  // Shipped `P7` property namespaces (`04 §11.4`)
  readonly token: ValuePropertyAccessor<string>;
  readonly vendor: ValuePropertyAccessor<string>;
  readonly sqltext: ValuePropertyAccessor<string>;
  readonly setting: WidgetSettingsNamespace;
  readonly config: WidgetSettingsNamespace;
  readonly error: {
    get: () => LineageError[] | undefined;
    clear: () => void;
  };
  readonly message: {
    error: (msg: string) => void;
    info: (msg: string) => void;
    success: (msg: string) => void;
  };
  readonly job: {
    list: {
      get: () => Promise<JobSummary[]>;
    };
    lineage: {
      viewDetailById: (jobId?: string) => Promise<void>;
      getNodes: () => WidgetTreeNode[];
      selectGraph: (dbo: VisualizeJobParams) => Promise<void>;
    };
  };

  // Core Visualization Methods (`04 §11.4` & `OBL-114`..`OBL-115`)
  visualize: () => Promise<SqlflowJSON | void>;
  visualizeSQL: (
    sql: string,
    vendor?: string,
    options?: VisualizeSqlOptions
  ) => Promise<SqlflowJSON | void>;
  visualizeTableLevel: (sql?: string, vendor?: string) => Promise<SqlflowJSON | void>;
  visualizeER: (ddl?: string, vendor?: string) => Promise<SqlflowJSON | void>;
  visualizeJoin: (sql?: string, vendor?: string) => Promise<JoinAnalysisGraph | void>;
  visualizeJob: (jobId?: string, params?: VisualizeJobParams) => Promise<SqlflowJSON | void>;

  // Precomputed / Zero-Backend JSON Methods (both `Json` and `JSON` casings supported)
  visualizeJson: (
    sqlflowOrDataFlowJson: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON>,
    opts?: VisualizeJsonOptions
  ) => Promise<void>;
  visualizeJSON: (
    sqlflowOrDataFlowJson: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON>,
    opts?: VisualizeJsonOptions
  ) => Promise<void>;
  visualizeURL: (url: string, opts?: VisualizeJsonOptions) => Promise<void>;
  visualizeFreestyle: (params: { url: string; [key: string]: unknown }) => Promise<void>;
  visualizeERJSON: (
    json: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON>,
    opts?: { layout?: boolean }
  ) => Promise<void>;
  visualizeJoinJSON: (json: JoinAnalysisGraph) => Promise<void>;
  visualizeCGJSON: (
    json: CallGraphPayload | (SqlflowJSON & Partial<CallGraphPayload>),
    opts?: { layout?: boolean }
  ) => Promise<void>;
  visualizeDependencyGraph: (
    depGraphV1Json: DependencyGraphV1,
    opts?: { layout?: boolean }
  ) => Promise<void>;

  // Persist Job Submission (`04 §11.4`)
  submitPersistJob: (
    apiPrefixOrParams: string | Record<string, unknown>,
    maybeParams?: Record<string, unknown>
  ) => Promise<{ jobId?: string } | void>;

  // Upstream & Downstream Navigation (callable directly AND `.visualize(opts)`)
  upstream: CallableStreamNamespace;
  downstream: CallableStreamNamespace;
  resetSlice: () => void;

  // Highlighting, Search, Zoom, Orientation, Options (`OBL-116`)
  highlightTable: (nameOrId: string) => boolean;
  highlightColumn: (table: string, column: string) => boolean;
  clearHighlight: () => void;
  search: (keyword: string) => CanvasNode[];
  zoomIn: () => number;
  zoomOut: () => number;
  resetZoom: () => number;
  zoomToFit: () => number;
  getScale: () => number;
  setOrientation: (orientation: LayoutRankDir) => void;
  getOrientation: () => LayoutRankDir;
  getOptions: () => ResolvedWidgetOptions;
  setOptions: (patch: Partial<SQLFlowInitOptions>) => void;

  // Exports (`OBL-116` & `04 §11.4`)
  exportImage: () => Promise<string>;
  exportJson: () => string;
  exportXml: () => Promise<string>;
  exportCsv: () => Promise<string>;
  saveAsPng: () => Promise<string>;
  saveERAsPng: () => Promise<string>;
  saveCGAsPng: () => Promise<string>;

  // Event Bus (`OBL-117` & `04 §11.4–§11.5`)
  addEventListener: <K extends WidgetEventName>(
    event: K,
    listener: WidgetEventListener<K>
  ) => void;
  on: <K extends WidgetEventName>(
    event: K,
    listener: WidgetEventListener<K>
  ) => SQLFlowWidgetInstance;
  once: <K extends WidgetEventName>(
    event: K,
    listener: WidgetEventListener<K>
  ) => SQLFlowWidgetInstance;
  removeEventListener: <K extends WidgetEventName>(
    event: K,
    listener: WidgetEventListener<K>
  ) => void;
  off: <K extends WidgetEventName>(
    event: K,
    listener: WidgetEventListener<K>
  ) => SQLFlowWidgetInstance;
  removeAllEventListener: (event?: WidgetEventName) => void;

  // Sandbox & Lifecycle (`OBL-112`)
  getIframe: () => HTMLIFrameElement | null;
  getContainer: () => HTMLElement;
  isDestroyed: () => boolean;
  destroy: () => void;
}

export interface SQLFlowGlobalSDK {
  readonly version: typeof WIDGET_VERSION;
  readonly release: typeof WIDGET_RELEASE;
  readonly buildDate: typeof WIDGET_BUILD_DATE;
  init: (options: SQLFlowInitOptions) => Promise<SQLFlowWidgetInstance>;
  create: (options: SQLFlowInitOptions) => Promise<SQLFlowWidgetInstance>;
  default?: SQLFlowGlobalSDK;
  SQLFlow?: SQLFlowGlobalSDK;
}
