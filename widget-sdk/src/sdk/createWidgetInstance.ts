/**
 * Core Widget Sandbox Factory (`SQLFlow.init(options)` -> `Promise<SQLFlowWidgetInstance>`).
 *
 * Implements:
 * - Same-origin style-isolated `<iframe>` sandbox + inline mount fallback (`OBL-112`)
 * - `<MemoryRouter>` host isolation (`window.location` / `location.hash` untouched) (`OBL-112`)
 * - `ResizeObserver` responsive auto-sizing + clean `destroy()` teardown (`OBL-112`)
 * - Complete `init` options & visibility toggles (`04 §11.3`, `OBL-113`)
 * - Programmatic SQL / ER / Join / Job / Precomputed JSON / `DependencyGraph` v1.0 methods (`OBL-114`..`OBL-115`)
 * - Highlighting, Upstream/Downstream, Search, Zoom, Orientation, Options, Exports (`OBL-116`)
 * - Typed Event Bus + `onFieldClick` / `onTableNameClick` callback aliases (`OBL-117`)
 */

import React from "react";
import { flushSync } from "react-dom";
import { createRoot, type Root } from "react-dom/client";
import {
  SqlFlowApiClient,
  convertDependencyGraphToSqlflow,
  layoutSqlflowGraph,
  resolveBackendApiBase,
  toShortVendorKey,
  toShowRelationType,
  validateDependencyGraphV1,
  type ApiEnvelope,
  type CallGraphPayload,
  type CanvasNode,
  type DataFlow,
  type DependencyGraphV1,
  type JobSummary,
  type JoinAnalysisGraph,
  type LayoutRankDir,
  type LineageSettings,
  type SqlflowJSON,
  type SubmitJobParams
} from "@sqlflow/ui-core";
import { WidgetApp } from "../components/WidgetApp.js";
import { WIDGET_INLINE_CSS } from "../styles/widgetStyles.js";
import {
  WIDGET_RELEASE,
  WIDGET_VERSION,
  type CallableStreamNamespace,
  type ResolvedWidgetOptions,
  type SQLFlowInitOptions,
  type SQLFlowWidgetInstance,
  type StreamTargetOptions,
  type UpstreamDownstreamTarget,
  type VisualizationKind,
  type VisualizeJobParams,
  type VisualizeJsonOptions,
  type VisualizeSqlOptions,
  type WidgetEventListener,
  type WidgetEventName,
  type WidgetSettingKey,
  type WidgetSettingsNamespace
} from "../types.js";
import {
  buildWidgetTreeNodes,
  exportDomElementToPngDataUrl,
  findColumnByTableAndName,
  findNodeByNameOrId,
  normalizePrecomputedLineageInput,
  normalizeStreamTarget,
  searchNodesInModel,
  serializeModelToCsv15,
  serializeModelToXml,
  sliceCanvasModelDirection,
  triggerFileDownload
} from "../utils/graphOperations.js";
import {
  WidgetStore,
  normalizeLang,
  normalizeOrientation,
  normalizeTheme,
  resolveVisibilityFromInit
} from "./widgetStore.js";

function validateAndFormatDimension(
  dim: unknown,
  fieldName: "width" | "height"
): string {
  if (dim === undefined) {
    return "100%";
  }
  if (dim === null) {
    throw new Error(`${fieldName} is invalid`);
  }
  if (typeof dim === "number") {
    if (!Number.isFinite(dim) || dim <= 0) {
      throw new Error(`${fieldName} is invalid`);
    }
    return `${dim}px`;
  }
  if (typeof dim === "string") {
    const trimmed = dim.trim();
    if (!trimmed) {
      throw new Error(`${fieldName} is invalid`);
    }
    const numericPrefix = Number.parseFloat(trimmed);
    if (
      (trimmed === "0" ||
        trimmed.startsWith("-") ||
        /^0(px|%|vh|vw|rem|em)?$/i.test(trimmed)) ||
      (Number.isFinite(numericPrefix) && numericPrefix <= 0)
    ) {
      throw new Error(`${fieldName} is invalid`);
    }
    if (/^\d+(\.\d+)?$/.test(trimmed)) {
      return `${trimmed}px`;
    }
    return trimmed;
  }
  throw new Error(`${fieldName} is invalid`);
}

function resolveContainerElement(
  container: HTMLElement | string | undefined | null
): HTMLElement {
  if (!container) {
    throw new Error("SQLFlow.init requires a valid options.container HTMLElement or CSS selector");
  }
  if (typeof container === "string") {
    const trimmed = container.trim();
    if (!trimmed) {
      throw new Error("options.container selector cannot be empty");
    }
    if (typeof document === "undefined") {
      throw new Error("document is not available to resolve options.container selector");
    }
    const found = document.querySelector<HTMLElement>(trimmed);
    if (!found) {
      throw new Error(`Container element not found for selector "${trimmed}"`);
    }
    return found;
  }
  if (typeof container === "object" && "nodeType" in container && container.nodeType === 1) {
    return container;
  }
  throw new Error("options.container must be an HTMLElement or CSS selector string");
}

function unwrapEnvelope<T>(
  envelope: ApiEnvelope<T> | undefined | null,
  fallbackMessage: string
): T {
  if (!envelope || typeof envelope !== "object") {
    throw new Error(fallbackMessage);
  }
  const code =
    typeof envelope.code === "number"
      ? envelope.code
      : typeof envelope.code === "string"
        ? Number.parseInt(envelope.code, 10)
        : 200;
  if (code !== 200) {
    throw new Error(envelope.error || envelope.message || `${fallbackMessage} (code ${code})`);
  }
  if (envelope.data === undefined || envelope.data === null) {
    throw new Error(envelope.error || envelope.message || fallbackMessage);
  }
  return envelope.data;
}

export async function createWidgetInstance(
  initOptions: SQLFlowInitOptions
): Promise<SQLFlowWidgetInstance> {
  if (!initOptions || typeof initOptions !== "object") {
    throw new Error("SQLFlow.init requires an options object");
  }

  const containerEl = resolveContainerElement(initOptions.container);
  const cssWidth = validateAndFormatDimension(initOptions.width, "width");
  const cssHeight = validateAndFormatDimension(initOptions.height, "height");

  const store = new WidgetStore(initOptions);
  const initialOptions = store.getSnapshot().options;

  const safeDefaultFetch: typeof fetch =
    initOptions.fetchImpl ??
    (typeof globalThis.fetch === "function"
      ? globalThis.fetch.bind(globalThis)
      : (async () => {
          throw new Error("fetch is not available in this host environment");
        }) as unknown as typeof fetch);

  const apiClient = new SqlFlowApiClient({
    mode: "widget",
    apiPrefix: initialOptions.apiPrefix,
    token: initialOptions.token,
    userId: initialOptions.userId,
    clearLocalStorageOn401: false,
    fetchImpl: safeDefaultFetch
  });

  const hostDoc = containerEl.ownerDocument ?? document;
  let iframeEl: HTMLIFrameElement | null = null;
  let sandboxDoc: Document = hostDoc;
  let mountWrapperEl: HTMLElement;
  let reactRoot: Root | null = null;
  let resizeObserver: ResizeObserver | null = null;
  let windowResizeHandler: (() => void) | null = null;
  let destroyed = false;

  const suppressNextCanvasClickRef: React.MutableRefObject<boolean> = { current: false };

  // Clear any prior widget child nodes inside `containerEl` if re-initialized on same host node
  const existingIframe = containerEl.querySelector(":scope > iframe.sqlflow-widget-iframe");
  if (existingIframe) {
    existingIframe.remove();
  }
  const existingInline = containerEl.querySelector(":scope > div.sqlflow-widget-inline-host");
  if (existingInline) {
    existingInline.remove();
  }

  if (!initialOptions.inline) {
    const candidateIframe = hostDoc.createElement("iframe");
    candidateIframe.className = "sqlflow-widget-iframe";
    candidateIframe.title = "SQLFlow Lineage Widget";
    candidateIframe.setAttribute("data-sqlflow-version", WIDGET_VERSION);
    candidateIframe.style.width = cssWidth;
    candidateIframe.style.height = cssHeight;
    candidateIframe.style.border = "0";
    candidateIframe.style.display = "block";
    candidateIframe.style.overflow = "hidden";
    candidateIframe.style.backgroundColor = "transparent";
    containerEl.appendChild(candidateIframe);

    if (candidateIframe.contentDocument && candidateIframe.contentWindow) {
      iframeEl = candidateIframe;
      sandboxDoc = candidateIframe.contentDocument;

      if (!sandboxDoc.head) {
        const head = sandboxDoc.createElement("head");
        sandboxDoc.documentElement.insertBefore(head, sandboxDoc.body ?? null);
      }
      if (!sandboxDoc.body) {
        const body = sandboxDoc.createElement("body");
        sandboxDoc.documentElement.appendChild(body);
      }

      sandboxDoc.documentElement.style.cssText =
        "width:100%;height:100%;margin:0;padding:0;overflow:hidden;";
      sandboxDoc.body.style.cssText =
        "width:100%;height:100%;margin:0;padding:0;overflow:hidden;";

      const styleTag = sandboxDoc.createElement("style");
      styleTag.setAttribute("data-sqlflow-widget-styles", WIDGET_VERSION);
      styleTag.textContent = WIDGET_INLINE_CSS;
      sandboxDoc.head.appendChild(styleTag);

      const appDiv = sandboxDoc.createElement("div");
      appDiv.id = "App";
      appDiv.style.width = "100%";
      appDiv.style.height = "100%";
      sandboxDoc.body.appendChild(appDiv);
      mountWrapperEl = appDiv;
    } else {
      // Detached container fallback (e.g. unit test passing an unmounted HTMLElement)
      candidateIframe.remove();
      const inlineDiv = hostDoc.createElement("div");
      inlineDiv.className = "sqlflow-widget-inline-host";
      inlineDiv.id = "App";
      inlineDiv.style.width = cssWidth;
      inlineDiv.style.height = cssHeight;
      inlineDiv.style.position = "relative";
      inlineDiv.style.overflow = "hidden";
      const styleTag = hostDoc.createElement("style");
      styleTag.setAttribute("data-sqlflow-widget-styles", WIDGET_VERSION);
      styleTag.textContent = WIDGET_INLINE_CSS;
      inlineDiv.appendChild(styleTag);
      const rootTarget = hostDoc.createElement("div");
      rootTarget.style.width = "100%";
      rootTarget.style.height = "100%";
      inlineDiv.appendChild(rootTarget);
      containerEl.appendChild(inlineDiv);
      mountWrapperEl = rootTarget;
    }
  } else {
    const inlineDiv = hostDoc.createElement("div");
    inlineDiv.className = "sqlflow-widget-inline-host";
    inlineDiv.id = "App";
    inlineDiv.style.width = cssWidth;
    inlineDiv.style.height = cssHeight;
    inlineDiv.style.position = "relative";
    inlineDiv.style.overflow = "hidden";
    if (!hostDoc.querySelector('style[data-sqlflow-widget-styles]')) {
      const styleTag = hostDoc.createElement("style");
      styleTag.setAttribute("data-sqlflow-widget-styles", WIDGET_VERSION);
      styleTag.textContent = WIDGET_INLINE_CSS;
      (hostDoc.head ?? inlineDiv).appendChild(styleTag);
    }
    containerEl.appendChild(inlineDiv);
    mountWrapperEl = inlineDiv;
  }

  const commitSync = (fn: () => void): void => {
    if (destroyed) {
      return;
    }
    try {
      flushSync(fn);
    } catch {
      fn();
    }
  };

  const syncContainerSize = (widthPx?: number, heightPx?: number): void => {
    if (destroyed) {
      return;
    }
    const rect = containerEl.getBoundingClientRect?.();
    const nextW = Math.round(widthPx ?? rect?.width ?? containerEl.clientWidth ?? 800) || 800;
    const nextH = Math.round(heightPx ?? rect?.height ?? containerEl.clientHeight ?? 600) || 600;
    const snap = store.getSnapshot();
    if (snap.containerWidthPx !== nextW || snap.containerHeightPx !== nextH) {
      store.update(() => ({
        containerWidthPx: nextW,
        containerHeightPx: nextH
      }));
    }
  };

  syncContainerSize();

  if (typeof ResizeObserver !== "undefined") {
    resizeObserver = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (entry?.contentRect) {
        syncContainerSize(entry.contentRect.width, entry.contentRect.height);
      } else {
        syncContainerSize();
      }
    });
    resizeObserver.observe(containerEl);
  } else if (typeof window !== "undefined") {
    windowResizeHandler = () => syncContainerSize();
    window.addEventListener("resize", windowResizeHandler);
  }

  const readRenderedScale = (): number => {
    const scaledSurface = sandboxDoc.querySelector<HTMLElement>(
      '[data-testid="sqlflow-scaled-surface"]'
    );
    if (scaledSurface?.dataset?.scale) {
      const parsed = Number.parseFloat(scaledSurface.dataset.scale);
      if (Number.isFinite(parsed) && parsed > 0) {
        return parsed;
      }
    }
    return store.getSnapshot().scale;
  };

  const clickCanvasScaleButton = (
    testId: string,
    fallbackCompute: (prev: number) => number
  ): number => {
    if (destroyed) {
      return store.getSnapshot().scale;
    }
    const btn = sandboxDoc.querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`);
    if (btn) {
      commitSync(() => {
        btn.click();
      });
      const updated = readRenderedScale();
      store.update(() => ({ scale: updated }));
      return updated;
    }
    let computed = store.getSnapshot().scale;
    commitSync(() => {
      store.update((prev) => {
        computed = Number(Math.min(3.0, Math.max(0.15, fallbackCompute(prev.scale))).toFixed(3));
        return { scale: computed };
      });
    });
    return computed;
  };

  const createSettingsAccessorNamespace = (
    targetBucket: "settings" | "defaultConfig"
  ): WidgetSettingsNamespace => {
    const mapKey = (
      key: WidgetSettingKey,
      val: boolean,
      current: LineageSettings
    ): LineageSettings => {
      switch (key) {
        case "dataflow":
          return { ...current, dataflow: Boolean(val) };
        case "impact":
          return { ...current, impact: Boolean(val) };
        case "dataflowOfAggregateFunction":
          return { ...current, dataflowOfAggregateFunction: Boolean(val) };
        case "showIntermediateRecordset":
          return { ...current, ignoreRecordSet: !val };
        case "showFunction":
          return { ...current, ignoreFunction: !val };
        case "showConstant":
          return { ...current, showConstant: Boolean(val) };
        case "showTransform":
          return { ...current, showTransform: Boolean(val) };
      }
    };

    const readKey = (key: WidgetSettingKey, current: LineageSettings): boolean => {
      switch (key) {
        case "dataflow":
          return current.dataflow;
        case "impact":
          return current.impact;
        case "dataflowOfAggregateFunction":
          return current.dataflowOfAggregateFunction;
        case "showIntermediateRecordset":
          return !current.ignoreRecordSet;
        case "showFunction":
          return !current.ignoreFunction;
        case "showConstant":
          return current.showConstant;
        case "showTransform":
          return current.showTransform;
      }
    };

    const makeAccessor = (key: WidgetSettingKey) => ({
      get: () => readKey(key, store.getSnapshot()[targetBucket]),
      set: (value: boolean) => {
        commitSync(() => {
          store.update((prev) => {
            const updatedSettings = mapKey(key, value, prev[targetBucket]);
            if (targetBucket === "defaultConfig") {
              return { defaultConfig: updatedSettings };
            }
            const recomputed = prev.activeLineageJson
              ? layoutSqlflowGraph(prev.activeLineageJson, {
                  rankdir: prev.options.orientation,
                  tableLevel: prev.tableLevel,
                  ignoreRecordSet: updatedSettings.ignoreRecordSet,
                  ignoreFunction: updatedSettings.ignoreFunction,
                  showConstant: updatedSettings.showConstant,
                  includeDirect: updatedSettings.dataflow,
                  includeIndirect: updatedSettings.impact
                })
              : prev.lineageModel;
            return {
              settings: updatedSettings,
              lineageModel: recomputed
            };
          });
        });
      }
    });

    return {
      dataflow: makeAccessor("dataflow"),
      impact: makeAccessor("impact"),
      dataflowOfAggregateFunction: makeAccessor("dataflowOfAggregateFunction"),
      showIntermediateRecordset: makeAccessor("showIntermediateRecordset"),
      showFunction: makeAccessor("showFunction"),
      showConstant: makeAccessor("showConstant"),
      showTransform: makeAccessor("showTransform"),
      getAll: () => ({ ...store.getSnapshot()[targetBucket] })
    };
  };

  const executeStreamDirection = async (
    direction: "upstream" | "downstream",
    targetInput: UpstreamDownstreamTarget,
    distanceArg?: number
  ): Promise<SqlflowJSON | void> => {
    const snap = store.getSnapshot();
    const baseModel =
      snap.baseLineageJson
        ? store.recomputeLineageModel(snap.baseLineageJson, { bypassSummaryGate: true })
        : snap.lineageModel;
    const normalized = normalizeStreamTarget(
      targetInput,
      baseModel ?? snap.lineageModel,
      distanceArg ?? snap.settings.defaultDistance
    );
    if (distanceArg !== undefined) {
      normalized.distance = distanceArg;
    }

    const kind: VisualizationKind = direction;
    store.emit("visualizeStart", {
      kind,
      dbvendor: snap.options.dbvendor,
      jobId: normalized.jobId ?? snap.options.jobId
    });

    const activeSessionId = normalized.sessionId ?? snap.sessionId;
    const activeJobId = normalized.jobId ?? snap.options.jobId;
    const useBackend = !normalized.clientSide && Boolean(activeSessionId || activeJobId);

    try {
      let resultJson: SqlflowJSON;
      let nextSessionId = activeSessionId;

      if (useBackend) {
        const tableNamePattern = [normalized.database, normalized.schema, normalized.table]
          .filter(Boolean)
          .join(".");
        const sliceParams = {
          sessionId: activeSessionId,
          jobId: activeJobId,
          dbvendor: snap.options.dbvendor,
          tableNamePattern,
          tableColumn: normalized.column,
          distance: normalized.distance ?? 10,
          showRelationType: toShowRelationType(snap.settings),
          treatArgumentsInCountFunctionAsDirectDataflow:
            snap.settings.dataflowOfAggregateFunction,
          ignoreRecordSet: snap.settings.ignoreRecordSet,
          ignoreFunction: snap.settings.ignoreFunction,
          showTransform: snap.settings.showTransform
        };
        const env =
          direction === "upstream"
            ? await apiClient.upstreamGraph(sliceParams)
            : await apiClient.downstreamGraph(sliceParams);
        resultJson = unwrapEnvelope(env, `Failed to load ${direction} lineage`);
        nextSessionId = env.sessionId ?? activeSessionId;
      } else {
        if (!baseModel) {
          throw new Error(
            `Cannot compute client-side ${direction} slice before a lineage graph is loaded`
          );
        }
        resultJson = sliceCanvasModelDirection(baseModel, normalized, direction);
      }

      let nodeCount = 0;
      let edgeCount = 0;
      commitSync(() => {
        const model = store.recomputeLineageModel(resultJson, {
          bypassSummaryGate: true
        });
        nodeCount = model?.nodes.length ?? 0;
        edgeCount = model?.edges.length ?? 0;
        const targetNode = findNodeByNameOrId(model, normalized.table);
        const nextExpanded = new Set<string>();
        if (targetNode) {
          nextExpanded.add(`${direction}:${targetNode.id}`);
        }
        store.update((prev) => ({
          viewMode: "lineage",
          loading: false,
          sessionId: nextSessionId,
          baseLineageJson: prev.baseLineageJson ?? resultJson,
          activeLineageJson: resultJson,
          lineageModel: model,
          isSliced: true,
          expandedStreamNodes: nextExpanded,
          pinnedTarget: targetNode ? { kind: "node", nodeId: targetNode.id } : null,
          locatedNodeId: targetNode?.id ?? null
        }));
      });

      store.emit("visualizeSuccess", {
        kind,
        nodeCount,
        edgeCount,
        mode: resultJson.mode ?? "global",
        sessionId: nextSessionId,
        jobId: activeJobId,
        data: resultJson
      });
      return resultJson;
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      commitSync(() => {
        store.update(() => ({
          loading: false,
          errors: [{ errorType: "StreamError", errorMessage: msg }]
        }));
      });
      store.emit("visualizeError", { kind, error: msg });
      throw err;
    }
  };

  const upstreamFn = Object.assign(
    (target: UpstreamDownstreamTarget, distance?: number) =>
      executeStreamDirection("upstream", target, distance),
    {
      visualize: (opts: StreamTargetOptions) => executeStreamDirection("upstream", opts)
    }
  ) as CallableStreamNamespace;

  const downstreamFn = Object.assign(
    (target: UpstreamDownstreamTarget, distance?: number) =>
      executeStreamDirection("downstream", target, distance),
    {
      visualize: (opts: StreamTargetOptions) => executeStreamDirection("downstream", opts)
    }
  ) as CallableStreamNamespace;

  const runVisualizeSqlInternal = async (
    sqlArg?: string,
    vendorArg?: string,
    opts?: VisualizeSqlOptions,
    overrideKind?: VisualizationKind
  ): Promise<SqlflowJSON | void> => {
    const prevSnap = store.getSnapshot();
    const nextSql = sqlArg !== undefined ? sqlArg : prevSnap.options.sqltext;
    const nextVendor =
      vendorArg !== undefined ? toShortVendorKey(vendorArg) : prevSnap.options.dbvendor;
    const isTableLevel = Boolean(opts?.tableLevel);
    const kind: VisualizationKind = overrideKind ?? (isTableLevel ? "tableLevel" : "sql");

    if (vendorArg !== undefined && nextVendor !== prevSnap.options.dbvendor) {
      store.emit("vendorChange", {
        dbvendor: nextVendor,
        previousVendor: prevSnap.options.dbvendor
      });
    }
    if (sqlArg !== undefined && nextSql !== prevSnap.options.sqltext) {
      store.emit("sqlChange", {
        sqltext: nextSql,
        dbvendor: nextVendor
      });
    }

    commitSync(() => {
      store.update((prev) => ({
        loading: true,
        viewMode: "lineage",
        tableLevel: isTableLevel,
        errors: [],
        options: {
          ...prev.options,
          sqltext: nextSql,
          dbvendor: nextVendor
        }
      }));
    });

    store.emit("visualizeStart", {
      kind,
      dbvendor: nextVendor,
      sqltext: nextSql
    });

    try {
      const settings = store.getSnapshot().settings;
      const env = isTableLevel
        ? await apiClient.generateTableLevelLineage({
            sqltext: nextSql,
            dbvendor: nextVendor
          })
        : await apiClient.generateGraph({
            sqltext: nextSql,
            dbvendor: nextVendor,
            showRelationType: toShowRelationType(settings),
            treatArgumentsInCountFunctionAsDirectDataflow:
              settings.dataflowOfAggregateFunction,
            ignoreRecordSet: opts?.ignoreRecordSet ?? settings.ignoreRecordSet,
            ignoreFunction: settings.ignoreFunction,
            showConstantTable: settings.showConstant,
            showTransform: opts?.showTransform ?? settings.showTransform
          });

      const payload = unwrapEnvelope(env, "Failed to visualize SQL lineage");
      let nodeCount = 0;
      let edgeCount = 0;

      commitSync(() => {
        const model = store.recomputeLineageModel(payload, {
          layout: opts?.layout,
          tableLevel: isTableLevel
        });
        nodeCount = model?.nodes.length ?? 0;
        edgeCount = model?.edges.length ?? 0;
        store.update(() => ({
          loading: false,
          viewMode: "lineage",
          tableLevel: isTableLevel,
          sessionId: env.sessionId,
          baseLineageJson: payload,
          activeLineageJson: payload,
          lineageModel: model,
          isSliced: false,
          expandedStreamNodes: new Set<string>(),
          errors: payload.sqlflow?.errors ?? [],
          pinnedTarget: null,
          locatedNodeId: null
        }));
      });

      store.emit("visualizeSuccess", {
        kind,
        nodeCount,
        edgeCount,
        mode: payload.mode ?? "global",
        sessionId: env.sessionId,
        data: payload
      });
      return payload;
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      commitSync(() => {
        store.update(() => ({
          loading: false,
          errors: [{ errorType: "SyntaxOrApiError", errorMessage: msg }]
        }));
      });
      store.emit("visualizeError", {
        kind,
        error: msg
      });
      throw err;
    }
  };

  const visualizeJsonImpl = async (
    sqlflowOrDataFlowJson: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON>,
    opts?: VisualizeJsonOptions
  ): Promise<void> => {
    const snap = store.getSnapshot();
    store.emit("visualizeStart", {
      kind: "json",
      dbvendor: snap.options.dbvendor,
      sqltext: opts?.sqltext
    });

    try {
      const { payload, sessionId } = normalizePrecomputedLineageInput(sqlflowOrDataFlowJson);
      const isTableLevel = opts?.tableLevel ?? false;
      let nodeCount = 0;
      let edgeCount = 0;

      commitSync(() => {
        const model = store.recomputeLineageModel(payload, {
          layout: opts?.layout,
          tableLevel: isTableLevel,
          bypassSummaryGate: opts?.bypassSummaryGate
        });
        nodeCount = model?.nodes.length ?? 0;
        edgeCount = model?.edges.length ?? 0;
        store.update((prev) => ({
          loading: false,
          viewMode: "lineage",
          tableLevel: isTableLevel,
          sessionId,
          baseLineageJson: payload,
          activeLineageJson: payload,
          lineageModel: model,
          isSliced: false,
          expandedStreamNodes: new Set<string>(),
          errors: payload.sqlflow?.errors ?? [],
          pinnedTarget: null,
          locatedNodeId: null,
          options:
            opts?.sqltext !== undefined
              ? { ...prev.options, sqltext: opts.sqltext }
              : prev.options
        }));
      });

      store.emit("visualizeSuccess", {
        kind: "json",
        nodeCount,
        edgeCount,
        mode: payload.mode ?? "global",
        sessionId,
        data: payload
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      commitSync(() => {
        store.update(() => ({
          loading: false,
          errors: [{ errorType: "InvalidJsonPayload", errorMessage: msg }]
        }));
      });
      store.emit("visualizeError", {
        kind: "json",
        error: msg
      });
      throw err;
    }
  };

  const instance: SQLFlowWidgetInstance = {
    version: WIDGET_VERSION,
    release: WIDGET_RELEASE,

    token: {
      get: () => store.getSnapshot().options.token,
      set: (nextToken: string) => {
        const safe = nextToken ?? "";
        apiClient.setToken(safe);
        commitSync(() => {
          store.update((prev) => ({
            options: { ...prev.options, token: safe }
          }));
        });
      }
    },

    vendor: {
      get: () => store.getSnapshot().options.dbvendor,
      set: (nextVendor: string) => {
        const prevVendor = store.getSnapshot().options.dbvendor;
        const normalized = toShortVendorKey(nextVendor || prevVendor);
        commitSync(() => {
          store.update((prev) => ({
            options: { ...prev.options, dbvendor: normalized }
          }));
        });
        if (normalized !== prevVendor) {
          store.emit("vendorChange", {
            dbvendor: normalized,
            previousVendor: prevVendor
          });
        }
      }
    },

    sqltext: {
      get: () => store.getSnapshot().options.sqltext,
      set: (nextSql: string) => {
        const safeSql = nextSql ?? "";
        const prevSnap = store.getSnapshot();
        commitSync(() => {
          store.update((prev) => ({
            options: { ...prev.options, sqltext: safeSql }
          }));
        });
        if (safeSql !== prevSnap.options.sqltext) {
          store.emit("sqlChange", {
            sqltext: safeSql,
            dbvendor: prevSnap.options.dbvendor
          });
        }
      }
    },

    setting: createSettingsAccessorNamespace("settings"),
    config: createSettingsAccessorNamespace("defaultConfig"),

    error: {
      get: () => {
        const errs = store.getSnapshot().errors;
        return errs.length > 0 ? errs : undefined;
      },
      clear: () => {
        commitSync(() => {
          store.update(() => ({ errors: [] }));
        });
      }
    },

    message: {
      error: (msg: string) => {
        commitSync(() => store.pushToast("error", msg));
      },
      info: (msg: string) => {
        commitSync(() => store.pushToast("info", msg));
      },
      success: (msg: string) => {
        commitSync(() => store.pushToast("success", msg));
      }
    },

    job: {
      list: {
        get: async (): Promise<JobSummary[]> => {
          const env = await apiClient.displayUserJobsSummary();
          const summary = unwrapEnvelope(env, "Failed to load user jobs list");
          return summary.jobDetails ?? [];
        }
      },
      lineage: {
        viewDetailById: async (jobId?: string): Promise<void> => {
          await instance.visualizeJob(jobId);
        },
        getNodes: () => {
          return buildWidgetTreeNodes(store.getSnapshot().lineageModel);
        },
        selectGraph: async (dbo: VisualizeJobParams): Promise<void> => {
          await instance.visualizeJob(store.getSnapshot().options.jobId, dbo);
        }
      }
    },

    visualize: async () => {
      return runVisualizeSqlInternal();
    },

    visualizeSQL: async (
      sql: string,
      vendor?: string,
      options?: VisualizeSqlOptions
    ) => {
      return runVisualizeSqlInternal(sql, vendor, options);
    },

    visualizeTableLevel: async (sql?: string, vendor?: string) => {
      return runVisualizeSqlInternal(sql, vendor, { tableLevel: true }, "tableLevel");
    },

    visualizeER: async (ddl?: string, vendor?: string) => {
      const prevSnap = store.getSnapshot();
      const nextSql = ddl !== undefined ? ddl : prevSnap.options.sqltext;
      const nextVendor =
        vendor !== undefined ? toShortVendorKey(vendor) : prevSnap.options.dbvendor;

      if (vendor !== undefined && nextVendor !== prevSnap.options.dbvendor) {
        store.emit("vendorChange", {
          dbvendor: nextVendor,
          previousVendor: prevSnap.options.dbvendor
        });
      }
      if (ddl !== undefined && nextSql !== prevSnap.options.sqltext) {
        store.emit("sqlChange", { sqltext: nextSql, dbvendor: nextVendor });
      }

      commitSync(() => {
        store.update((prev) => ({
          loading: true,
          viewMode: "er",
          errors: [],
          options: {
            ...prev.options,
            sqltext: nextSql,
            dbvendor: nextVendor
          }
        }));
      });

      store.emit("visualizeStart", {
        kind: "er",
        dbvendor: nextVendor,
        sqltext: nextSql
      });

      try {
        const env = await apiClient.erDiagramSelectGraph({
          sqltext: nextSql,
          dbvendor: nextVendor
        });
        const payload = unwrapEnvelope(env, "Failed to generate ER diagram");
        let nodeCount = 0;
        let edgeCount = 0;

        commitSync(() => {
          const erModel = layoutSqlflowGraph(payload, {
            rankdir: store.getSnapshot().options.orientation,
            bypassSummaryGate: true,
            ignoreRecordSet: true
          });
          nodeCount = erModel.nodes.length;
          edgeCount = erModel.edges.length;
          store.update(() => ({
            loading: false,
            viewMode: "er",
            sessionId: env.sessionId,
            erJson: payload,
            erModel,
            errors: payload.sqlflow?.errors ?? []
          }));
        });

        store.emit("visualizeSuccess", {
          kind: "er",
          nodeCount,
          edgeCount,
          mode: "er",
          sessionId: env.sessionId,
          data: payload
        });
        return payload;
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        commitSync(() => {
          store.update(() => ({
            loading: false,
            errors: [{ errorType: "ErError", errorMessage: msg }]
          }));
        });
        store.emit("visualizeError", { kind: "er", error: msg });
        throw err;
      }
    },

    visualizeJoin: async (sql?: string, vendor?: string) => {
      const prevSnap = store.getSnapshot();
      const nextSql = sql !== undefined ? sql : prevSnap.options.sqltext;
      const nextVendor =
        vendor !== undefined ? toShortVendorKey(vendor) : prevSnap.options.dbvendor;

      if (vendor !== undefined && nextVendor !== prevSnap.options.dbvendor) {
        store.emit("vendorChange", {
          dbvendor: nextVendor,
          previousVendor: prevSnap.options.dbvendor
        });
      }
      if (sql !== undefined && nextSql !== prevSnap.options.sqltext) {
        store.emit("sqlChange", { sqltext: nextSql, dbvendor: nextVendor });
      }

      commitSync(() => {
        store.update((prev) => ({
          loading: true,
          viewMode: "join",
          errors: [],
          options: {
            ...prev.options,
            sqltext: nextSql,
            dbvendor: nextVendor
          }
        }));
      });

      store.emit("visualizeStart", {
        kind: "join",
        dbvendor: nextVendor,
        sqltext: nextSql
      });

      try {
        const raw = await apiClient.analyzeJoins({
          sqltext: nextSql,
          dbvendor: nextVendor
        });
        const maybeRec = raw as unknown as Record<string, unknown>;
        if (maybeRec && "code" in maybeRec && Number(maybeRec.code) !== 200) {
          throw new Error(
            String(maybeRec.error || maybeRec.message || `Join analysis error (${maybeRec.code})`)
          );
        }
        const joinGraph: JoinAnalysisGraph =
          maybeRec && "data" in maybeRec && maybeRec.data && typeof maybeRec.data === "object"
            ? (maybeRec.data as JoinAnalysisGraph)
            : raw;

        if (!joinGraph || !Array.isArray(joinGraph.nodes) || !Array.isArray(joinGraph.edges)) {
          throw new Error("Invalid JoinAnalysisGraph payload returned by backend");
        }

        commitSync(() => {
          store.update(() => ({
            loading: false,
            viewMode: "join",
            joinGraph,
            errors: []
          }));
        });

        store.emit("visualizeSuccess", {
          kind: "join",
          nodeCount: joinGraph.nodes.length,
          edgeCount: joinGraph.edges.length,
          mode: joinGraph.schemaVersion || "join-analysis.v1",
          data: joinGraph
        });
        return joinGraph;
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        commitSync(() => {
          store.update(() => ({
            loading: false,
            errors: [{ errorType: "JoinAnalysisError", errorMessage: msg }]
          }));
        });
        store.emit("visualizeError", { kind: "join", error: msg });
        throw err;
      }
    },

    visualizeJob: async (jobId?: string, params?: VisualizeJobParams) => {
      const prevSnap = store.getSnapshot();
      const targetJobId = jobId ?? prevSnap.options.jobId;
      const isTableLevel = Boolean(params?.tableLevel);

      commitSync(() => {
        store.update((prev) => ({
          loading: true,
          viewMode: "lineage",
          tableLevel: isTableLevel,
          errors: [],
          options: targetJobId ? { ...prev.options, jobId: targetJobId } : prev.options
        }));
      });

      store.emit("visualizeStart", {
        kind: "job",
        dbvendor: prevSnap.options.dbvendor,
        jobId: targetJobId
      });

      try {
        let payload: SqlflowJSON;
        let resolvedJobId = targetJobId;
        let resolvedSessionId = prevSnap.sessionId;
        let activeJob: JobSummary | undefined;

        if (!targetJobId) {
          const latest = await apiClient.displayUserLatestJobGraph();
          if (Number(latest.code) !== 200 || !latest.data) {
            throw new Error(latest.error || "Failed to load latest job graph");
          }
          payload = latest.data;
          resolvedJobId = latest.jobId ?? latest.job?.jobId;
          resolvedSessionId = latest.sessionId ?? latest.job?.sessionId;
          activeJob = latest.job;
        } else {
          const settings = store.getSnapshot().settings;
          const env = isTableLevel
            ? await apiClient.selectTableLevelLineage({
                sessionId: resolvedSessionId ?? targetJobId,
                dbvendor: prevSnap.options.dbvendor,
                jobId: targetJobId
              })
            : await apiClient.selectGraph({
                sessionId: resolvedSessionId,
                jobId: targetJobId,
                dbvendor: prevSnap.options.dbvendor,
                database: params?.database,
                schema: params?.schema,
                table: params?.table,
                column: params?.column,
                showRelationType: toShowRelationType(settings),
                treatArgumentsInCountFunctionAsDirectDataflow:
                  settings.dataflowOfAggregateFunction,
                ignoreRecordSet: settings.ignoreRecordSet,
                ignoreFunction: settings.ignoreFunction,
                showConstantTable: settings.showConstant,
                showTransform: settings.showTransform
              });
          payload = unwrapEnvelope(env, `Failed to load lineage for job ${targetJobId}`);
          resolvedSessionId = env.sessionId ?? resolvedSessionId;
        }

        if (resolvedJobId) {
          store.emit("jobSelect", {
            jobId: resolvedJobId,
            job: activeJob
          });
        }

        let nodeCount = 0;
        let edgeCount = 0;
        commitSync(() => {
          const model = store.recomputeLineageModel(payload, {
            tableLevel: isTableLevel
          });
          nodeCount = model?.nodes.length ?? 0;
          edgeCount = model?.edges.length ?? 0;
          store.update((prev) => ({
            loading: false,
            viewMode: "lineage",
            tableLevel: isTableLevel,
            sessionId: resolvedSessionId,
            activeJob: activeJob ?? prev.activeJob,
            baseLineageJson: payload,
            activeLineageJson: payload,
            lineageModel: model,
            isSliced: false,
            expandedStreamNodes: new Set<string>(),
            errors: payload.sqlflow?.errors ?? [],
            options: resolvedJobId ? { ...prev.options, jobId: resolvedJobId } : prev.options
          }));
        });

        store.emit("visualizeSuccess", {
          kind: "job",
          nodeCount,
          edgeCount,
          mode: payload.mode ?? "global",
          sessionId: resolvedSessionId,
          jobId: resolvedJobId,
          data: payload
        });
        return payload;
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        commitSync(() => {
          store.update(() => ({
            loading: false,
            errors: [{ errorType: "JobLineageError", errorMessage: msg }]
          }));
        });
        store.emit("visualizeError", { kind: "job", error: msg });
        throw err;
      }
    },

    visualizeJson: visualizeJsonImpl,
    visualizeJSON: visualizeJsonImpl,

    visualizeURL: async (url: string, opts?: VisualizeJsonOptions) => {
      const fetchFn = initOptions.fetchImpl ?? globalThis.fetch.bind(globalThis);
      const res = await fetchFn(url, { method: "GET" });
      if (!res.ok) {
        throw new Error(`Failed to fetch lineage JSON from ${url} (${res.status})`);
      }
      const json = (await res.json()) as SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON>;
      await visualizeJsonImpl(json, opts);
    },

    visualizeFreestyle: async (params: { url: string; [key: string]: unknown }) => {
      if (!params || !params.url) {
        throw new Error("visualizeFreestyle requires a non-empty url");
      }
      const { url, ...rest } = params;
      const qs = new URLSearchParams();
      for (const [k, v] of Object.entries(rest)) {
        if (v !== undefined && v !== null) {
          qs.append(k, String(v));
        }
      }
      const fullUrl = qs.toString()
        ? `${url}${url.includes("?") ? "&" : "?"}${qs.toString()}`
        : url;
      await instance.visualizeURL(fullUrl);
    },

    visualizeERJSON: async (
      json: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON>,
      opts?: { layout?: boolean }
    ) => {
      const snap = store.getSnapshot();
      store.emit("visualizeStart", {
        kind: "erJson",
        dbvendor: snap.options.dbvendor
      });
      try {
        const { payload, sessionId } = normalizePrecomputedLineageInput(json);
        let nodeCount = 0;
        let edgeCount = 0;
        commitSync(() => {
          const erModel = layoutSqlflowGraph(payload, {
            layout: opts?.layout,
            rankdir: store.getSnapshot().options.orientation,
            bypassSummaryGate: true,
            ignoreRecordSet: true
          });
          nodeCount = erModel.nodes.length;
          edgeCount = erModel.edges.length;
          store.update(() => ({
            loading: false,
            viewMode: "er",
            sessionId,
            erJson: payload,
            erModel,
            errors: payload.sqlflow?.errors ?? []
          }));
        });
        store.emit("visualizeSuccess", {
          kind: "erJson",
          nodeCount,
          edgeCount,
          mode: "er",
          sessionId,
          data: payload
        });
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        store.emit("visualizeError", { kind: "erJson", error: msg });
        throw err;
      }
    },

    visualizeJoinJSON: async (json: JoinAnalysisGraph) => {
      const snap = store.getSnapshot();
      store.emit("visualizeStart", {
        kind: "join",
        dbvendor: snap.options.dbvendor
      });
      try {
        if (!json || typeof json !== "object" || !Array.isArray(json.nodes) || !Array.isArray(json.edges)) {
          throw new Error("visualizeJoinJSON requires a valid JoinAnalysisGraph with nodes[] and edges[]");
        }
        commitSync(() => {
          store.update(() => ({
            loading: false,
            viewMode: "join",
            joinGraph: json,
            errors: []
          }));
        });
        store.emit("visualizeSuccess", {
          kind: "join",
          nodeCount: json.nodes.length,
          edgeCount: json.edges.length,
          mode: json.schemaVersion || "join-analysis.v1",
          data: json
        });
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        store.emit("visualizeError", { kind: "join", error: msg });
        throw err;
      }
    },

    visualizeCGJSON: async (
      json: CallGraphPayload | (SqlflowJSON & Partial<CallGraphPayload>)
    ) => {
      const snap = store.getSnapshot();
      store.emit("visualizeStart", {
        kind: "callGraphJson",
        dbvendor: snap.options.dbvendor
      });
      try {
        if (!json || typeof json !== "object" || Array.isArray(json)) {
          throw new Error("visualizeCGJSON requires a valid CallGraphPayload object");
        }
        const raw = json as Record<string, unknown>;
        let normalizedCg: CallGraphPayload;

        if (
          raw.callGraph &&
          typeof raw.callGraph === "object" &&
          Array.isArray((raw.callGraph as { nodes?: unknown }).nodes)
        ) {
          normalizedCg = json as CallGraphPayload;
        } else {
          // Adapt SqlflowJSON dbobjs/relationships into CallGraphPayload
          const { payload } = normalizePrecomputedLineageInput(json);
          const rawObjs = payload.sqlflow?.dbobjs;
          const objs = Array.isArray(rawObjs) ? rawObjs : [];
          const rels = payload.sqlflow?.relationships ?? [];
          normalizedCg = {
            callGraph: {
              nodes: objs.map((o) => ({
                id: String(o.id),
                name: o.name,
                kind:
                  o.type === "function"
                    ? "FUNCTION"
                    : o.type === "trigger"
                      ? "TRIGGER"
                      : "PROCEDURE",
                package: o.schema ?? o.database,
                coordinates: o.coordinates
              })),
              edges: rels.map((r, idx) => ({
                id: String(r.id ?? `cg-${idx}`),
                source: String(r.caller?.id ?? r.sources?.[0]?.parentId ?? r.sources?.[0]?.id ?? ""),
                target: String(r.callees?.[0]?.id ?? r.target?.parentId ?? r.target?.id ?? ""),
                callStmt: r.callStmt
              }))
            }
          };
        }

        commitSync(() => {
          store.update(() => ({
            loading: false,
            viewMode: "callgraph",
            callGraphPayload: normalizedCg,
            errors: []
          }));
        });
        store.emit("visualizeSuccess", {
          kind: "callGraphJson",
          nodeCount: normalizedCg.callGraph.nodes.length,
          edgeCount: normalizedCg.callGraph.edges.length,
          mode: "callgraph",
          data: normalizedCg
        });
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        store.emit("visualizeError", { kind: "callGraphJson", error: msg });
        throw err;
      }
    },

    visualizeDependencyGraph: async (
      depGraphV1Json: DependencyGraphV1,
      opts?: { layout?: boolean }
    ) => {
      const snap = store.getSnapshot();
      store.emit("visualizeStart", {
        kind: "dependencyGraph",
        dbvendor: snap.options.dbvendor
      });

      try {
        const validated = validateDependencyGraphV1(depGraphV1Json);
        const convertedSqlflow = convertDependencyGraphToSqlflow(validated, {
          useTopologicalBfsPositioning: opts?.layout === false
        });
        let nodeCount = 0;
        let edgeCount = 0;

        commitSync(() => {
          const model = store.recomputeLineageModel(convertedSqlflow, {
            layout: opts?.layout ?? true
          });
          nodeCount = model?.nodes.length ?? 0;
          edgeCount = model?.edges.length ?? 0;
          store.update(() => ({
            loading: false,
            viewMode: "lineage",
            tableLevel: false,
            sessionId: undefined,
            baseLineageJson: convertedSqlflow,
            activeLineageJson: convertedSqlflow,
            lineageModel: model,
            isSliced: false,
            expandedStreamNodes: new Set<string>(),
            errors: [],
            pinnedTarget: null,
            locatedNodeId: null
          }));
        });

        store.emit("visualizeSuccess", {
          kind: "dependencyGraph",
          nodeCount,
          edgeCount,
          mode: "dependencyGraph",
          data: depGraphV1Json
        });
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        commitSync(() => {
          store.update(() => ({
            loading: false,
            errors: [{ errorType: "DependencyGraphValidationError", errorMessage: msg }]
          }));
        });
        store.emit("visualizeError", {
          kind: "dependencyGraph",
          error: msg
        });
        throw err;
      }
    },

    submitPersistJob: async (
      apiPrefixOrParams: string | Record<string, unknown>,
      maybeParams?: Record<string, unknown>
    ) => {
      let paramsRecord: Record<string, unknown>;
      if (typeof apiPrefixOrParams === "string") {
        apiClient.setApiPrefix(apiPrefixOrParams);
        paramsRecord = maybeParams ?? {};
      } else {
        paramsRecord = apiPrefixOrParams ?? {};
      }

      const snap = store.getSnapshot();
      const submitParams: SubmitJobParams = {
        dbvendor: String(paramsRecord.dbvendor ?? snap.options.dbvendor),
        jobName: String(paramsRecord.jobName ?? `persist-job-${Date.now()}`),
        sqlsource: (paramsRecord.sqlsource as SubmitJobParams["sqlsource"]) ?? "fromDatabase",
        incremental: true,
        ...(paramsRecord as Partial<SubmitJobParams>)
      };

      const env = await apiClient.submitPersistJob(submitParams);
      const data = unwrapEnvelope(env, "Failed to submit persist job");
      return data;
    },

    upstream: upstreamFn,
    downstream: downstreamFn,

    resetSlice: () => {
      commitSync(() => {
        store.update((prev) => {
          if (!prev.baseLineageJson) {
            return { isSliced: false, expandedStreamNodes: new Set<string>() };
          }
          const fullModel = store.recomputeLineageModel(prev.baseLineageJson);
          return {
            activeLineageJson: prev.baseLineageJson,
            lineageModel: fullModel,
            isSliced: false,
            expandedStreamNodes: new Set<string>(),
            pinnedTarget: null,
            locatedNodeId: null
          };
        });
      });
    },

    highlightTable: (nameOrId: string): boolean => {
      const snap = store.getSnapshot();
      const activeModel = snap.viewMode === "er" ? snap.erModel : snap.lineageModel;
      const found = findNodeByNameOrId(activeModel, nameOrId);
      if (!found) {
        return false;
      }
      commitSync(() => {
        store.update(() => ({
          pinnedTarget: { kind: "node", nodeId: found.id },
          locatedNodeId: found.id
        }));
      });
      return true;
    },

    highlightColumn: (table: string, column: string): boolean => {
      const snap = store.getSnapshot();
      const activeModel = snap.viewMode === "er" ? snap.erModel : snap.lineageModel;
      const found = findColumnByTableAndName(activeModel, table, column);
      if (!found) {
        return false;
      }
      commitSync(() => {
        store.update(() => ({
          pinnedTarget: { kind: "column", columnId: found.column.id },
          locatedNodeId: found.node.id
        }));
      });
      return true;
    },

    clearHighlight: () => {
      commitSync(() => {
        store.update(() => ({
          pinnedTarget: null,
          hoverTarget: null,
          locatedNodeId: null,
          pinnedCoordinates: [],
          hoverCoordinates: []
        }));
      });
      // Also clear any internal click-pinned state inside `<LineageCanvas />` without emitting `canvasClick`
      const surface = sandboxDoc.querySelector<HTMLElement>(
        '[data-testid="sqlflow-scaled-surface"]'
      );
      if (surface) {
        suppressNextCanvasClickRef.current = true;
        commitSync(() => {
          surface.click();
        });
        suppressNextCanvasClickRef.current = false;
      }
    },

    search: (keyword: string): CanvasNode[] => {
      const snap = store.getSnapshot();
      const activeModel = snap.viewMode === "er" ? snap.erModel : snap.lineageModel;
      const matches = searchNodesInModel(activeModel, keyword);
      commitSync(() => {
        store.update(() => ({
          searchKeyword: keyword,
          locatedNodeId: keyword.trim() && matches[0] ? matches[0].id : null,
          pinnedTarget:
            keyword.trim() && matches[0]
              ? { kind: "node", nodeId: matches[0].id }
              : snap.pinnedTarget
        }));
      });
      return matches;
    },

    zoomIn: () => clickCanvasScaleButton("zoom-in-btn", (s) => s + 0.15),
    zoomOut: () => clickCanvasScaleButton("zoom-out-btn", (s) => s - 0.15),
    resetZoom: () => clickCanvasScaleButton("zoom-reset-btn", () => 1),
    zoomToFit: () => clickCanvasScaleButton("zoom-fit-btn", () => 0.85),

    getScale: () => readRenderedScale(),

    setOrientation: (orientation: LayoutRankDir) => {
      const normalized = normalizeOrientation(orientation);
      commitSync(() => {
        store.update((prev) => {
          const nextOptions: ResolvedWidgetOptions = {
            ...prev.options,
            orientation: normalized
          };
          const nextLineageModel = prev.activeLineageJson
            ? layoutSqlflowGraph(prev.activeLineageJson, {
                layout: true,
                rankdir: normalized,
                tableLevel: prev.tableLevel,
                ignoreRecordSet: prev.settings.ignoreRecordSet,
                ignoreFunction: prev.settings.ignoreFunction,
                showConstant: prev.settings.showConstant,
                includeDirect: prev.settings.dataflow,
                includeIndirect: prev.settings.impact,
                bypassSummaryGate: Boolean(prev.lineageModel && !prev.lineageModel.isSummaryGateActive)
              })
            : prev.lineageModel;
          const nextErModel = prev.erJson
            ? layoutSqlflowGraph(prev.erJson, {
                layout: true,
                rankdir: normalized,
                bypassSummaryGate: true,
                ignoreRecordSet: true
              })
            : prev.erModel;
          return {
            options: nextOptions,
            lineageModel: nextLineageModel,
            erModel: nextErModel
          };
        });
      });
    },

    getOrientation: () => store.getSnapshot().options.orientation,

    getOptions: (): ResolvedWidgetOptions => {
      const opts = store.getSnapshot().options;
      return {
        ...opts,
        visibility: { ...opts.visibility }
      };
    },

    setOptions: (patch: Partial<SQLFlowInitOptions>) => {
      if (!patch || typeof patch !== "object") {
        return;
      }

      if (patch.width !== undefined) {
        const nextCssW = validateAndFormatDimension(patch.width, "width");
        if (iframeEl) iframeEl.style.width = nextCssW;
        else mountWrapperEl.style.width = nextCssW;
      }
      if (patch.height !== undefined) {
        const nextCssH = validateAndFormatDimension(patch.height, "height");
        if (iframeEl) iframeEl.style.height = nextCssH;
        else mountWrapperEl.style.height = nextCssH;
      }
      if (patch.apiPrefix !== undefined) {
        apiClient.setApiPrefix(patch.apiPrefix);
      }
      if (patch.token !== undefined) {
        apiClient.setToken(patch.token);
      }
      if (patch.userId !== undefined) {
        apiClient.setUserId(patch.userId);
      }

      const prevSnap = store.getSnapshot();
      const nextOrientation =
        patch.orientation !== undefined
          ? normalizeOrientation(patch.orientation)
          : prevSnap.options.orientation;
      const orientationChanged = nextOrientation !== prevSnap.options.orientation;

      const nextVendor =
        patch.dbvendor !== undefined
          ? toShortVendorKey(patch.dbvendor)
          : prevSnap.options.dbvendor;
      const nextSql =
        patch.sqltext !== undefined ? patch.sqltext : prevSnap.options.sqltext;

      commitSync(() => {
        store.update((prev) => {
          const nextVis = resolveVisibilityFromInit(patch, prev.options.visibility);
          const nextOptions: ResolvedWidgetOptions = {
            ...prev.options,
            ...(patch.width !== undefined ? { width: patch.width } : {}),
            ...(patch.height !== undefined ? { height: patch.height } : {}),
            ...(patch.apiPrefix !== undefined
              ? { apiPrefix: resolveBackendApiBase(patch.apiPrefix, "widget") }
              : {}),
            ...(patch.token !== undefined ? { token: patch.token } : {}),
            ...(patch.userId !== undefined ? { userId: apiClient.getUserId() } : {}),
            dbvendor: nextVendor,
            sqltext: nextSql,
            ...(patch.jobId !== undefined ? { jobId: patch.jobId } : {}),
            ...(patch.shareId !== undefined ? { shareId: patch.shareId } : {}),
            ...(patch.visualizeShare !== undefined
              ? { visualizeShare: Boolean(patch.visualizeShare) }
              : {}),
            ...(patch.theme !== undefined ? { theme: normalizeTheme(patch.theme) } : {}),
            ...(patch.lang !== undefined ? { lang: normalizeLang(patch.lang) } : {}),
            orientation: nextOrientation,
            visibility: nextVis
          };

          const nextLineageModel =
            orientationChanged && prev.activeLineageJson
              ? layoutSqlflowGraph(prev.activeLineageJson, {
                  layout: true,
                  rankdir: nextOrientation,
                  tableLevel: prev.tableLevel,
                  ignoreRecordSet: prev.settings.ignoreRecordSet,
                  ignoreFunction: prev.settings.ignoreFunction,
                  showConstant: prev.settings.showConstant,
                  includeDirect: prev.settings.dataflow,
                  includeIndirect: prev.settings.impact
                })
              : prev.lineageModel;

          return {
            options: nextOptions,
            lineageModel: nextLineageModel
          };
        });
      });

      if (patch.dbvendor !== undefined && nextVendor !== prevSnap.options.dbvendor) {
        store.emit("vendorChange", {
          dbvendor: nextVendor,
          previousVendor: prevSnap.options.dbvendor
        });
      }
      if (patch.sqltext !== undefined && nextSql !== prevSnap.options.sqltext) {
        store.emit("sqlChange", {
          sqltext: nextSql,
          dbvendor: nextVendor
        });
      }

      if (patch.onReady) store.on("ready", patch.onReady);
      if (patch.onFieldClick) store.on("onFieldClick", patch.onFieldClick);
      if (patch.onTableNameClick) store.on("onTableNameClick", patch.onTableNameClick);
      if (patch.onNodeClick) store.on("nodeClick", patch.onNodeClick);
      if (patch.onColumnClick) store.on("columnClick", patch.onColumnClick);
      if (patch.onEdgeClick) store.on("edgeClick", patch.onEdgeClick);
      if (patch.onCanvasClick) store.on("canvasClick", patch.onCanvasClick);
      if (patch.onSqlChange) store.on("sqlChange", patch.onSqlChange);
      if (patch.onVendorChange) store.on("vendorChange", patch.onVendorChange);
      if (patch.onVisualizeStart) store.on("visualizeStart", patch.onVisualizeStart);
      if (patch.onVisualizeSuccess) store.on("visualizeSuccess", patch.onVisualizeSuccess);
      if (patch.onVisualizeError) store.on("visualizeError", patch.onVisualizeError);
      if (patch.onJobSelect) store.on("jobSelect", patch.onJobSelect);
    },

    exportImage: async (): Promise<string> => {
      const snap = store.getSnapshot();
      const bg = snap.options.theme === "dark" ? "#181a20" : "#eeeeee";
      const canvasPane = sandboxDoc.querySelector<HTMLElement>(
        '[data-testid="sqlflow-widget-canvas-pane"]'
      );
      const dataUrl = await exportDomElementToPngDataUrl(canvasPane, bg);
      triggerFileDownload(dataUrl, "sqlflow-lineage.png", "image/png", hostDoc);
      return dataUrl;
    },

    saveAsPng: async (): Promise<string> => {
      return instance.exportImage();
    },

    saveERAsPng: async (): Promise<string> => {
      const snap = store.getSnapshot();
      const bg = snap.options.theme === "dark" ? "#181a20" : "#eeeeee";
      const erCanvas =
        sandboxDoc.querySelector<HTMLElement>('[data-testid="sqlflow-er-canvas"]') ??
        sandboxDoc.querySelector<HTMLElement>('[data-testid="sqlflow-widget-canvas-pane"]');
      const dataUrl = await exportDomElementToPngDataUrl(erCanvas, bg);
      triggerFileDownload(dataUrl, "sqlflow-er-diagram.png", "image/png", hostDoc);
      return dataUrl;
    },

    saveCGAsPng: async (): Promise<string> => {
      const snap = store.getSnapshot();
      const bg = snap.options.theme === "dark" ? "#181a20" : "#eeeeee";
      const cgCanvas =
        sandboxDoc.querySelector<HTMLElement>('[data-testid="sqlflow-callgraph-canvas"]') ??
        sandboxDoc.querySelector<HTMLElement>('[data-testid="sqlflow-widget-canvas-pane"]');
      const dataUrl = await exportDomElementToPngDataUrl(cgCanvas, bg);
      triggerFileDownload(dataUrl, "sqlflow-call-graph.png", "image/png", hostDoc);
      return dataUrl;
    },

    exportJson: (): string => {
      const snap = store.getSnapshot();
      const activeData =
        snap.viewMode === "er"
          ? snap.erJson
          : snap.viewMode === "join"
            ? snap.joinGraph
            : snap.viewMode === "callgraph"
              ? snap.callGraphPayload
              : snap.activeLineageJson;
      const serialized = JSON.stringify(activeData ?? { mode: "global", sqlflow: { dbobjs: [], relationships: [] } }, null, 2);
      triggerFileDownload(serialized, "sqlflow-lineage.json", "application/json", hostDoc);
      return serialized;
    },

    exportXml: async (): Promise<string> => {
      const snap = store.getSnapshot();
      const model = snap.viewMode === "er" ? snap.erModel : snap.lineageModel;
      const xml = serializeModelToXml(model);
      triggerFileDownload(xml, "sqlflow-lineage.xml", "application/xml", hostDoc);
      return xml;
    },

    exportCsv: async (): Promise<string> => {
      const snap = store.getSnapshot();
      if (snap.sessionId && snap.options.sqltext.trim()) {
        try {
          const remoteCsv = await apiClient.exportFullLineageAsCsv({
            sqltext: snap.options.sqltext,
            dbvendor: snap.options.dbvendor,
            showRelationType: toShowRelationType(snap.settings),
            treatArgumentsInCountFunctionAsDirectDataflow:
              snap.settings.dataflowOfAggregateFunction,
            ignoreRecordSet: snap.settings.ignoreRecordSet,
            showConstantTable: snap.settings.showConstant,
            showTransform: snap.settings.showTransform
          });
          if (remoteCsv && remoteCsv.includes("SourceTable")) {
            triggerFileDownload(remoteCsv, "sqlflow-lineage.csv", "text/csv", hostDoc);
            return remoteCsv;
          }
        } catch {
          // Fall back to deterministic client-side 15-column CSV serializer
        }
      }
      const model = snap.viewMode === "er" ? snap.erModel : snap.lineageModel;
      const csv = serializeModelToCsv15(model);
      triggerFileDownload(csv, "sqlflow-lineage.csv", "text/csv", hostDoc);
      return csv;
    },

    addEventListener: <K extends WidgetEventName>(
      event: K,
      listener: WidgetEventListener<K>
    ) => {
      store.on(event, listener);
    },

    on: <K extends WidgetEventName>(event: K, listener: WidgetEventListener<K>) => {
      store.on(event, listener);
      return instance;
    },

    once: <K extends WidgetEventName>(event: K, listener: WidgetEventListener<K>) => {
      store.once(event, listener);
      return instance;
    },

    removeEventListener: <K extends WidgetEventName>(
      event: K,
      listener: WidgetEventListener<K>
    ) => {
      store.off(event, listener);
    },

    off: <K extends WidgetEventName>(event: K, listener: WidgetEventListener<K>) => {
      store.off(event, listener);
      return instance;
    },

    removeAllEventListener: (event?: WidgetEventName) => {
      store.clearEvents(event);
    },

    getIframe: () => iframeEl,
    getContainer: () => containerEl,
    isDestroyed: () => destroyed,

    destroy: () => {
      if (destroyed) {
        return;
      }
      destroyed = true;
      if (resizeObserver) {
        resizeObserver.disconnect();
        resizeObserver = null;
      }
      if (windowResizeHandler && typeof window !== "undefined") {
        window.removeEventListener("resize", windowResizeHandler);
        windowResizeHandler = null;
      }
      if (reactRoot) {
        try {
          flushSync(() => {
            reactRoot?.unmount();
          });
        } catch {
          reactRoot.unmount();
        }
        reactRoot = null;
      }
      store.dispose();
      if (iframeEl) {
        try {
          if (iframeEl.contentWindow) {
            delete (iframeEl.contentWindow as unknown as Record<string, unknown>).SQLFlow;
          }
        } catch {
          // Ignore cross-realm cleanup errors
        }
        iframeEl.remove();
        iframeEl = null;
      } else {
        const inlineHost = containerEl.querySelector(":scope > div.sqlflow-widget-inline-host");
        if (inlineHost) {
          inlineHost.remove();
        } else {
          mountWrapperEl.remove();
        }
      }
    }
  };

  if (iframeEl?.contentWindow) {
    (iframeEl.contentWindow as unknown as Record<string, unknown>).SQLFlow = instance;
  }

  // Mount React 18 `<WidgetApp />` synchronously so DOM inside iframe is immediately ready
  reactRoot = createRoot(mountWrapperEl);
  commitSync(() => {
    reactRoot?.render(
      React.createElement(WidgetApp, {
        store,
        sdk: instance,
        suppressNextCanvasClickRef
      })
    );
  });

  store.emit("ready", {
    version: WIDGET_VERSION,
    container: containerEl,
    iframe: iframeEl,
    options: instance.getOptions()
  });

  // Optional automatic visualization on boot (`visualizeShare` or `autoVisualize: true`)
  if (initialOptions.visualizeShare && initialOptions.shareId) {
    try {
      store.emit("visualizeStart", {
        kind: "share",
        dbvendor: initialOptions.dbvendor
      });
      const env = await apiClient.getSharedGraph(initialOptions.shareId);
      const sharedJson = unwrapEnvelope(env, "Failed to load shared lineage snapshot");
      commitSync(() => {
        const model = store.recomputeLineageModel(sharedJson);
        store.update(() => ({
          viewMode: "share",
          sessionId: env.sessionId,
          baseLineageJson: sharedJson,
          activeLineageJson: sharedJson,
          lineageModel: model
        }));
      });
      store.emit("visualizeSuccess", {
        kind: "share",
        nodeCount: store.getSnapshot().lineageModel?.nodes.length ?? 0,
        edgeCount: store.getSnapshot().lineageModel?.edges.length ?? 0,
        mode: sharedJson.mode ?? "global",
        sessionId: env.sessionId,
        data: sharedJson
      });
    } catch {
      // Do not fail `init` if optional initial share fetch fails; `visualizeError` can be observed
    }
  } else if (initOptions.autoVisualize) {
    if (initialOptions.jobId) {
      await instance.visualizeJob(initialOptions.jobId);
    } else if (initialOptions.sqltext.trim()) {
      await instance.visualize();
    }
  }

  return instance;
}
