/**
 * Per-Instance Reactive State Store & Typed Event Bus for `@sqlflow/widget-sdk`.
 * Every `SQLFlow.init(...)` call instantiates its own isolated `WidgetStore`
 * so multi-instance pages (Demo 8) never share mutable state.
 */

import {
  DEFAULT_GUEST_USER_ID,
  DEFAULT_LINEAGE_SETTINGS,
  DEFAULT_UI_DIALECT,
  WIDGET_DEFAULT_API_PREFIX,
  type CallGraphPayload,
  type CanvasGraphModel,
  type Coordinate,
  type HighlightTarget,
  type JobSummary,
  type JoinAnalysisGraph,
  type LayoutRankDir,
  type LineageError,
  type LineageSettings,
  type SqlflowJSON,
  layoutSqlflowGraph,
  resolveBackendApiBase,
  toShortVendorKey
} from "@sqlflow/ui-core";
import type {
  ExtendedWidgetComponentOptions,
  ResolvedWidgetOptions,
  SQLFlowInitOptions,
  WidgetEventListener,
  WidgetEventMap,
  WidgetEventName,
  WidgetLang,
  WidgetTheme,
  WidgetViewMode,
  WidgetVisibilityConfig
} from "../types.js";

export interface ToastItem {
  id: number;
  kind: "error" | "info" | "success";
  text: string;
}

export interface WidgetStateSnapshot {
  options: ResolvedWidgetOptions;
  settings: LineageSettings;
  defaultConfig: LineageSettings;
  viewMode: WidgetViewMode;
  tableLevel: boolean;
  loading: boolean;
  sessionId?: string;
  activeJob?: JobSummary;
  baseLineageJson: SqlflowJSON | null;
  activeLineageJson: SqlflowJSON | null;
  lineageModel: CanvasGraphModel | null;
  isSliced: boolean;
  erJson: SqlflowJSON | null;
  erModel: CanvasGraphModel | null;
  joinGraph: JoinAnalysisGraph | null;
  callGraphPayload: CallGraphPayload | null;
  errors: LineageError[];
  pinnedTarget: HighlightTarget | null;
  hoverTarget: HighlightTarget | null;
  locatedNodeId: string | null;
  searchKeyword: string;
  hoverCoordinates: Coordinate[];
  pinnedCoordinates: Coordinate[];
  scale: number;
  containerWidthPx: number;
  containerHeightPx: number;
  toasts: ToastItem[];
  sqlDrawerOpen: boolean;
  expandedStreamNodes: ReadonlySet<string>;
}

export function resolveVisibilityFromInit(
  opts: Partial<SQLFlowInitOptions>,
  base?: WidgetVisibilityConfig
): WidgetVisibilityConfig {
  const prev: WidgetVisibilityConfig = base ?? {
    sqlEditor: false,
    graphLocate: false,
    minimap: false,
    toolbar: false,
    settings: false,
    export: false,
    sqlList: false,
    scaleControls: true
  };

  let next = { ...prev };

  if (typeof opts.component === "boolean") {
    next = {
      ...next,
      sqlEditor: opts.component,
      graphLocate: opts.component,
      minimap: opts.component,
      toolbar: opts.component,
      settings: opts.component,
      export: opts.component,
      sqlList: opts.component
    };
  } else if (opts.component && typeof opts.component === "object") {
    const comp = opts.component as ExtendedWidgetComponentOptions;
    if (comp.sqlEditor !== undefined) next.sqlEditor = Boolean(comp.sqlEditor);
    if (comp.graphLocate !== undefined) next.graphLocate = Boolean(comp.graphLocate);
    if (comp.minimap !== undefined) next.minimap = Boolean(comp.minimap);
    if (comp.toolbar !== undefined) next.toolbar = Boolean(comp.toolbar);
    if (comp.search !== undefined) next.graphLocate = Boolean(comp.search);
    if (comp.settings !== undefined) next.settings = Boolean(comp.settings);
    if (comp.export !== undefined) next.export = Boolean(comp.export);
    if (comp.sqlList !== undefined) next.sqlList = Boolean(comp.sqlList);
    if (comp.scaleControls !== undefined) next.scaleControls = Boolean(comp.scaleControls);
  }

  // Top-level explicit flags override `component` shorthand (`OBL-113`)
  if (opts.editor !== undefined) next.sqlEditor = Boolean(opts.editor);
  if (opts.toolbar !== undefined) next.toolbar = Boolean(opts.toolbar);
  if (opts.minimap !== undefined) next.minimap = Boolean(opts.minimap);
  if (opts.search !== undefined) next.graphLocate = Boolean(opts.search);
  if (opts.settings !== undefined) next.settings = Boolean(opts.settings);
  if (opts.export !== undefined) next.export = Boolean(opts.export);
  if (opts.sqlList !== undefined) next.sqlList = Boolean(opts.sqlList);
  if (opts.scaleControls !== undefined) next.scaleControls = Boolean(opts.scaleControls);

  // If settings or export was explicitly enabled, ensure toolbar is visible to host them
  if ((opts.settings === true || opts.export === true) && opts.toolbar === undefined) {
    next.toolbar = true;
  }

  return next;
}

export function normalizeTheme(theme?: string): WidgetTheme {
  return theme === "dark" ? "dark" : "light";
}

export function normalizeLang(lang?: string): WidgetLang {
  return lang === "cn" ? "cn" : "en";
}

export function normalizeOrientation(orientation?: string): LayoutRankDir {
  return orientation === "TB" ? "TB" : "LR";
}

export class WidgetStore {
  private state: WidgetStateSnapshot;
  private readonly subscribers = new Set<() => void>();
  private readonly eventListeners = new Map<WidgetEventName, Set<WidgetEventListener<any>>>();
  private nextToastId = 1;

  public constructor(initOptions: SQLFlowInitOptions) {
    const visibility = resolveVisibilityFromInit(initOptions);
    const resolvedVendor = initOptions.dbvendor
      ? toShortVendorKey(initOptions.dbvendor)
      : DEFAULT_UI_DIALECT;
    const resolvedOptions: ResolvedWidgetOptions = {
      width: initOptions.width ?? "100%",
      height: initOptions.height ?? "100%",
      apiPrefix:
        initOptions.apiPrefix !== undefined
          ? resolveBackendApiBase(initOptions.apiPrefix, "widget")
          : WIDGET_DEFAULT_API_PREFIX,
      token: initOptions.token ?? "",
      userId: initOptions.userId?.trim() || DEFAULT_GUEST_USER_ID,
      dbvendor: resolvedVendor,
      sqltext: initOptions.sqltext ?? "",
      jobId: initOptions.jobId,
      shareId: initOptions.shareId,
      visualizeShare: Boolean(initOptions.visualizeShare),
      theme: normalizeTheme(initOptions.theme),
      lang: normalizeLang(initOptions.lang),
      orientation: normalizeOrientation(initOptions.orientation),
      inline: Boolean(initOptions.inline),
      visibility
    };

    const baseSettings: LineageSettings = {
      ...DEFAULT_LINEAGE_SETTINGS,
      defaultDistance: 10
    };

    this.state = {
      options: resolvedOptions,
      settings: { ...baseSettings },
      defaultConfig: { ...baseSettings },
      viewMode: resolvedOptions.visualizeShare ? "share" : "lineage",
      tableLevel: false,
      loading: false,
      sessionId: undefined,
      activeJob: undefined,
      baseLineageJson: null,
      activeLineageJson: null,
      lineageModel: null,
      isSliced: false,
      erJson: null,
      erModel: null,
      joinGraph: null,
      callGraphPayload: null,
      errors: [],
      pinnedTarget: null,
      hoverTarget: null,
      locatedNodeId: null,
      searchKeyword: "",
      hoverCoordinates: [],
      pinnedCoordinates: [],
      scale: 1,
      containerWidthPx: 800,
      containerHeightPx: 600,
      toasts: [],
      sqlDrawerOpen: false,
      expandedStreamNodes: new Set<string>()
    };

    // Register inline callback aliases supplied in `SQLFlow.init(options)`
    if (initOptions.onReady) this.on("ready", initOptions.onReady);
    if (initOptions.onFieldClick) this.on("onFieldClick", initOptions.onFieldClick);
    if (initOptions.onTableNameClick) this.on("onTableNameClick", initOptions.onTableNameClick);
    if (initOptions.onNodeClick) this.on("nodeClick", initOptions.onNodeClick);
    if (initOptions.onColumnClick) this.on("columnClick", initOptions.onColumnClick);
    if (initOptions.onEdgeClick) this.on("edgeClick", initOptions.onEdgeClick);
    if (initOptions.onCanvasClick) this.on("canvasClick", initOptions.onCanvasClick);
    if (initOptions.onSqlChange) this.on("sqlChange", initOptions.onSqlChange);
    if (initOptions.onVendorChange) this.on("vendorChange", initOptions.onVendorChange);
    if (initOptions.onVisualizeStart) this.on("visualizeStart", initOptions.onVisualizeStart);
    if (initOptions.onVisualizeSuccess) this.on("visualizeSuccess", initOptions.onVisualizeSuccess);
    if (initOptions.onVisualizeError) this.on("visualizeError", initOptions.onVisualizeError);
    if (initOptions.onJobSelect) this.on("jobSelect", initOptions.onJobSelect);
  }

  public getSnapshot = (): WidgetStateSnapshot => {
    return this.state;
  };

  public subscribe = (listener: () => void): (() => void) => {
    this.subscribers.add(listener);
    return () => {
      this.subscribers.delete(listener);
    };
  };

  public update(updater: (prev: WidgetStateSnapshot) => Partial<WidgetStateSnapshot>): void {
    const patch = updater(this.state);
    this.state = {
      ...this.state,
      ...patch
    };
    for (const sub of this.subscribers) {
      sub();
    }
  }

  public recomputeLineageModel(
    json: SqlflowJSON | null,
    opts?: {
      layout?: boolean;
      tableLevel?: boolean;
      bypassSummaryGate?: boolean;
      rankdir?: LayoutRankDir;
    }
  ): CanvasGraphModel | null {
    if (!json) {
      return null;
    }
    const settings = this.state.settings;
    const rankdir = opts?.rankdir ?? this.state.options.orientation;
    const tableLevel = opts?.tableLevel ?? this.state.tableLevel;
    return layoutSqlflowGraph(json, {
      layout: opts?.layout,
      rankdir,
      tableLevel,
      ignoreRecordSet: settings.ignoreRecordSet,
      ignoreFunction: settings.ignoreFunction,
      showConstant: settings.showConstant,
      includeDirect: settings.dataflow,
      includeIndirect: settings.impact,
      bypassSummaryGate: opts?.bypassSummaryGate
    });
  }

  public pushToast(kind: ToastItem["kind"], text: string, durationMs = 4500): void {
    const id = this.nextToastId++;
    this.update((prev) => ({
      toasts: [...prev.toasts, { id, kind, text }]
    }));
    if (durationMs > 0) {
      setTimeout(() => {
        this.dismissToast(id);
      }, durationMs);
    }
  }

  public dismissToast(id: number): void {
    this.update((prev) => ({
      toasts: prev.toasts.filter((t) => t.id !== id)
    }));
  }

  // =========================================================================
  // Event Bus (`OBL-117` & `04 §11.4–§11.5`)
  // =========================================================================

  public on<K extends WidgetEventName>(event: K, listener: WidgetEventListener<K>): void {
    if (typeof listener !== "function") {
      return;
    }
    let set = this.eventListeners.get(event);
    if (!set) {
      set = new Set();
      this.eventListeners.set(event, set);
    }
    set.add(listener);
  }

  public once<K extends WidgetEventName>(event: K, listener: WidgetEventListener<K>): void {
    if (typeof listener !== "function") {
      return;
    }
    const wrapper: WidgetEventListener<K> = (payload) => {
      this.off(event, wrapper);
      listener(payload);
    };
    this.on(event, wrapper);
  }

  public off<K extends WidgetEventName>(event: K, listener: WidgetEventListener<K>): void {
    const set = this.eventListeners.get(event);
    if (set) {
      set.delete(listener);
    }
  }

  public clearEvents(event?: WidgetEventName): void {
    if (event) {
      this.eventListeners.delete(event);
    } else {
      this.eventListeners.clear();
    }
  }

  public emit<K extends WidgetEventName>(event: K, payload: WidgetEventMap[K]): void {
    const set = this.eventListeners.get(event);
    if (!set || set.size === 0) {
      return;
    }
    for (const fn of Array.from(set)) {
      try {
        fn(payload);
      } catch {
        // Isolate host listener exceptions from crashing widget rendering
      }
    }
  }

  public dispose(): void {
    this.eventListeners.clear();
    this.subscribers.clear();
  }
}
