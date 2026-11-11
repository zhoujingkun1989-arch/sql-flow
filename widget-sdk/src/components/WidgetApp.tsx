/**
 * Root React 18 Widget Shell (`<WidgetApp />`) mounted inside `<MemoryRouter>`
 * inside the style-isolated same-origin `<iframe>` sandbox (or inline container).
 */

import React, {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore
} from "react";
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import {
  LineageCanvas,
  SAMPLE_SQL,
  SqlCodeEditor,
  UI_DIALECTS,
  type CanvasColumnRow,
  type CanvasEdge,
  type CanvasNode,
  type Coordinate,
  type OnFieldClickPayload,
  type OnTableNameClickPayload,
  type UiDialectKey
} from "@sqlflow/ui-core";
import { WIDGET_I18N } from "../i18n.js";
import type { SQLFlowWidgetInstance, WidgetViewMode } from "../types.js";
import type { WidgetStore } from "../sdk/widgetStore.js";
import { CallGraphCanvas } from "./CallGraphCanvas.js";
import { ErDiagramCanvas } from "./ErDiagramCanvas.js";
import { JoinAnalysisCanvas } from "./JoinAnalysisCanvas.js";

export interface WidgetAppProps {
  store: WidgetStore;
  sdk: SQLFlowWidgetInstance;
  suppressNextCanvasClickRef: React.MutableRefObject<boolean>;
}

const VIEW_MODE_PATHS: Readonly<Record<WidgetViewMode, string>> = {
  lineage: "/lineage",
  er: "/er",
  join: "/join",
  callgraph: "/callgraph",
  share: "/share"
};

/**
 * Keeps `<MemoryRouter>` location synchronized with `store.state.viewMode`
 * without ever touching the host window's URL or `location.hash` (`OBL-112`).
 */
const MemoryRouteSynchronizer: React.FC<{ viewMode: WidgetViewMode }> = ({ viewMode }) => {
  const navigate = useNavigate();
  const location = useLocation();
  const targetPath = VIEW_MODE_PATHS[viewMode] ?? "/lineage";

  useLayoutEffect(() => {
    if (location.pathname !== targetPath) {
      navigate(targetPath, { replace: true });
    }
  }, [targetPath, location.pathname, navigate]);

  return null;
};

interface ContextMenuState {
  x: number;
  y: number;
  node?: CanvasNode;
  column?: CanvasColumnRow;
}

const WidgetShellInner: React.FC<WidgetAppProps> = ({
  store,
  sdk,
  suppressNextCanvasClickRef
}) => {
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getSnapshot);
  const i18n = WIDGET_I18N[state.options.lang] ?? WIDGET_I18N.en;
  const vis = state.options.visibility;

  const [editorWidth, setEditorWidth] = useState<number>(360);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const splitterDragRef = useRef<{ startX: number; origWidth: number } | null>(null);

  const popularDialects = useMemo(
    () => UI_DIALECTS.filter((d) => d.group === "Popular"),
    []
  );
  const otherDialects = useMemo(
    () => UI_DIALECTS.filter((d) => d.group !== "Popular"),
    []
  );

  useEffect(() => {
    const onMove = (e: MouseEvent) => {
      if (!splitterDragRef.current) {
        return;
      }
      const dx = e.clientX - splitterDragRef.current.startX;
      setEditorWidth(Math.max(220, Math.min(680, splitterDragRef.current.origWidth + dx)));
    };
    const onUp = () => {
      splitterDragRef.current = null;
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, []);

  const handleNodeClick = useCallback(
    (payload: OnTableNameClickPayload, node: CanvasNode) => {
      setContextMenu(null);
      store.update(() => ({
        pinnedTarget: { kind: "node", nodeId: node.id },
        locatedNodeId: node.id
      }));
      store.emit("onTableNameClick", payload);
      store.emit("nodeClick", {
        nodeId: node.id,
        database: payload.database,
        schema: payload.schema,
        table: payload.table,
        type: node.type,
        displayTitle: node.displayTitle,
        coordinates: node.coordinates
      });
    },
    [store]
  );

  const handleFieldClick = useCallback(
    (payload: OnFieldClickPayload, col: CanvasColumnRow, node: CanvasNode) => {
      setContextMenu(null);
      store.update(() => ({
        pinnedTarget: { kind: "column", columnId: col.id },
        locatedNodeId: null
      }));
      store.emit("onFieldClick", payload);
      store.emit("columnClick", {
        ...payload,
        field: payload.column,
        dataType: col.dataType,
        nodeId: node.id,
        columnId: col.id,
        coordinates: col.coordinates
      });
    },
    [store]
  );

  const handleEdgeClick = useCallback(
    (edge: CanvasEdge) => {
      setContextMenu(null);
      store.update(() => ({
        pinnedTarget: { kind: "edge", edgeId: edge.id }
      }));
      store.emit("edgeClick", {
        edgeId: edge.id,
        sourceNodeId: edge.sourceNodeId,
        sourceColumnId: edge.sourceColumnId,
        targetNodeId: edge.targetNodeId,
        targetColumnId: edge.targetColumnId,
        type: String(edge.type),
        effectType: edge.effectType,
        transforms: edge.transforms,
        coordinates: edge.coordinates
      });
    },
    [store]
  );

  const handleHoverCoordinatesChange = useCallback(
    (coords: Coordinate[]) => {
      const snap = store.getSnapshot();
      if (snap.hoverCoordinates.length === 0 && coords.length === 0) {
        return;
      }
      store.update(() => ({ hoverCoordinates: coords }));
    },
    [store]
  );

  const handlePinnedCoordinatesChange = useCallback(
    (coords: Coordinate[]) => {
      const snap = store.getSnapshot();
      if (snap.pinnedCoordinates.length === 0 && coords.length === 0) {
        return;
      }
      store.update(() => ({ pinnedCoordinates: coords }));
    },
    [store]
  );

  const handleStreamExpandClick = useCallback(
    (direction: "upstream" | "downstream", node: CanvasNode, isCurrentlyExpanded: boolean) => {
      if (isCurrentlyExpanded) {
        sdk.resetSlice();
      } else if (direction === "upstream") {
        void sdk.upstream({
          database: node.database,
          schema: node.schema,
          table: node.name
        });
      } else {
        void sdk.downstream({
          database: node.database,
          schema: node.schema,
          table: node.name
        });
      }
    },
    [sdk]
  );

  const handlePrimaryVisualize = async () => {
    setSettingsOpen(false);
    setExportOpen(false);
    try {
      if (state.viewMode === "er") {
        await sdk.visualizeER();
      } else if (state.viewMode === "join") {
        await sdk.visualizeJoin();
      } else if (state.tableLevel) {
        await sdk.visualizeTableLevel();
      } else {
        await sdk.visualize();
      }
    } catch {
      // Errors are surfaced via `visualizeError` event and `store.errors` / toast
    }
  };

  const handleLoadDialectSample = () => {
    const key = state.options.dbvendor as UiDialectKey;
    const sample = SAMPLE_SQL[key] ?? SAMPLE_SQL.oracle;
    sdk.sqltext.set(sample);
  };

  const handleFormatSql = async () => {
    const trimmed = state.options.sqltext.trim();
    if (!trimmed) {
      return;
    }
    const formatted = trimmed
      .replace(/\s+/g, " ")
      .replace(/\b(SELECT|FROM|WHERE|GROUP BY|ORDER BY|HAVING|LEFT JOIN|RIGHT JOIN|INNER JOIN|FULL JOIN|CROSS JOIN|JOIN|UNION ALL|UNION|INSERT INTO|VALUES|UPDATE|SET)\b/gi, "\n$1");
    sdk.sqltext.set(formatted.trim());
  };

  const activeModelForStats =
    state.viewMode === "er" ? state.erModel : state.lineageModel;

  const lineageModelKeyRef = useRef<{ prev: typeof state.lineageModel; id: number }>({
    prev: state.lineageModel,
    id: 0
  });
  if (lineageModelKeyRef.current.prev !== state.lineageModel) {
    lineageModelKeyRef.current = {
      prev: state.lineageModel,
      id: lineageModelKeyRef.current.id + 1
    };
  }
  const lineageCanvasKey = `lineage-${lineageModelKeyRef.current.id}`;

  const renderActiveCanvas = () => {
    switch (state.viewMode) {
      case "er":
        return (
          <ErDiagramCanvas
            model={state.erModel}
            scale={state.scale}
            onZoomIn={() => sdk.zoomIn()}
            onZoomOut={() => sdk.zoomOut()}
            onResetZoom={() => sdk.resetZoom()}
            onFitZoom={() => sdk.zoomToFit()}
            tableMovable={state.settings.tableMovable}
            i18n={i18n}
            onNodeClick={(node, payload) => handleNodeClick(payload, node)}
            onColumnClick={(col, node, payload) => handleFieldClick(payload, col, node)}
            onEdgeClick={handleEdgeClick}
          />
        );
      case "join":
        return (
          <JoinAnalysisCanvas
            graph={state.joinGraph}
            rankdir={state.options.orientation}
            scale={state.scale}
            i18n={i18n}
            onHighlightCoordinates={handlePinnedCoordinatesChange}
          />
        );
      case "callgraph":
        return (
          <CallGraphCanvas
            payload={state.callGraphPayload}
            rankdir={state.options.orientation}
            scale={state.scale}
            i18n={i18n}
          />
        );
      case "share":
        return state.lineageModel ? (
          <LineageCanvas
            key={`share-${lineageCanvasKey}`}
            model={state.lineageModel}
            showTransform={state.settings.showTransform}
            showMinimap={vis.minimap}
            showLocateBar={vis.graphLocate}
            showScaleControls={true}
            locatedNodeId={state.locatedNodeId}
            externalHoverTarget={state.hoverTarget ?? undefined}
            externalPinnedTarget={state.pinnedTarget}
            onPinnedTargetChange={(next) =>
              store.update(() => ({
                pinnedTarget: next,
                ...(next ? {} : { locatedNodeId: null, pinnedCoordinates: [] })
              }))
            }
            onTableNameClick={handleNodeClick}
            onFieldClick={handleFieldClick}
            onEdgeClick={handleEdgeClick}
          />
        ) : (
          <div
            data-testid="sqlflow-share-empty"
            style={{
              width: "100%",
              height: "100%",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              color: "var(--sf-text-muted)"
            }}
          >
            {state.loading ? i18n.visualizing : i18n.emptyLineage}
          </div>
        );
      case "lineage":
      default:
        return state.lineageModel ? (
          <LineageCanvas
            key={lineageCanvasKey}
            model={state.lineageModel}
            showTransform={state.settings.showTransform}
            treatAggregateAsDirect={state.settings.dataflowOfAggregateFunction}
            showMinimap={vis.minimap}
            showLocateBar={vis.graphLocate}
            showScaleControls={true}
            style={!vis.scaleControls ? ({ "--hide-scale": "1" } as React.CSSProperties) : undefined}
            locatedNodeId={state.locatedNodeId}
            externalHoverTarget={state.hoverTarget ?? undefined}
            externalPinnedTarget={state.pinnedTarget}
            onPinnedTargetChange={(next) =>
              store.update(() => ({
                pinnedTarget: next,
                ...(next ? {} : { locatedNodeId: null, pinnedCoordinates: [] })
              }))
            }
            expandedStreamNodes={state.expandedStreamNodes}
            onTableNameClick={handleNodeClick}
            onFieldClick={handleFieldClick}
            onEdgeClick={handleEdgeClick}
            onStreamExpandClick={handleStreamExpandClick}
            onHoverCoordinatesChange={handleHoverCoordinatesChange}
            onPinnedCoordinatesChange={handlePinnedCoordinatesChange}
          />
        ) : (
          <div
            data-testid="sqlflow-lineage-empty"
            style={{
              width: "100%",
              height: "100%",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              padding: 24,
              textAlign: "center",
              color: "var(--sf-text-muted)"
            }}
          >
            {state.loading ? i18n.visualizing : i18n.emptyLineage}
          </div>
        );
    }
  };

  return (
    <div
      data-testid="sqlflow-widget-shell"
      data-sqlflow-theme={state.options.theme}
      data-sqlflow-lang={state.options.lang}
      data-view-mode={state.viewMode}
      className="sqlflow-widget-root"
      onClick={() => {
        if (contextMenu) {
          setContextMenu(null);
        }
      }}
    >
      <MemoryRouteSynchronizer viewMode={state.viewMode} />

      {/* Toast Stack (`message.error` / `info` / `success`) */}
      {state.toasts.length > 0 && (
        <div className="sf-toast-stack" data-testid="sqlflow-widget-toasts">
          {state.toasts.map((toast) => (
            <div
              key={toast.id}
              data-testid={`widget-toast-${toast.kind}`}
              className={`sf-toast sf-toast-${toast.kind}`}
            >
              <span>{toast.text}</span>
              <button
                type="button"
                onClick={() => store.dismissToast(toast.id)}
                style={{
                  border: "none",
                  background: "transparent",
                  cursor: "pointer",
                  fontWeight: 700,
                  color: "inherit"
                }}
              >
                ×
              </button>
            </div>
          ))}
        </div>
      )}

      {/* Optional Top Widget Toolbar (`visibility.toolbar`) */}
      {vis.toolbar && (
        <div className="sf-toolbar" data-testid="sqlflow-widget-toolbar" style={{ position: "relative" }}>
          <div className="sf-toolbar-group">
            <span className="sf-brand-pill">SQLFlow</span>

            <select
              aria-label={i18n.dialect}
              data-testid="widget-dialect-select"
              className="sf-select"
              value={state.options.dbvendor}
              onChange={(e) => sdk.vendor.set(e.target.value)}
            >
              <optgroup label={i18n.popularDialects}>
                {popularDialects.map((d) => (
                  <option key={d.shortKey} value={d.shortKey}>
                    {d.label}
                  </option>
                ))}
              </optgroup>
              <optgroup label={i18n.allDialects}>
                {otherDialects.map((d) => (
                  <option key={d.shortKey} value={d.shortKey}>
                    {d.label}
                  </option>
                ))}
              </optgroup>
            </select>

            <button
              type="button"
              data-testid="widget-visualize-btn"
              className="sf-btn sf-btn-primary"
              disabled={state.loading}
              onClick={handlePrimaryVisualize}
            >
              {state.loading ? i18n.visualizing : i18n.visualize}
            </button>

            {/* View Mode Tabs synced with MemoryRouter */}
            <button
              type="button"
              data-testid="widget-mode-column"
              className={`sf-btn${state.viewMode === "lineage" && !state.tableLevel ? " sf-btn-active" : ""}`}
              onClick={() => {
                store.update((prev) => ({
                  viewMode: "lineage",
                  tableLevel: false,
                  lineageModel: store.recomputeLineageModel(prev.activeLineageJson, {
                    tableLevel: false
                  })
                }));
              }}
            >
              {i18n.viewColumn}
            </button>
            <button
              type="button"
              data-testid="widget-mode-table"
              className={`sf-btn${state.viewMode === "lineage" && state.tableLevel ? " sf-btn-active" : ""}`}
              onClick={() => {
                store.update((prev) => ({
                  viewMode: "lineage",
                  tableLevel: true,
                  lineageModel: store.recomputeLineageModel(prev.activeLineageJson, {
                    tableLevel: true
                  })
                }));
              }}
            >
              {i18n.viewTable}
            </button>
            <button
              type="button"
              data-testid="widget-mode-er"
              className={`sf-btn${state.viewMode === "er" ? " sf-btn-active" : ""}`}
              onClick={() => store.update(() => ({ viewMode: "er" }))}
            >
              {i18n.viewEr}
            </button>
            <button
              type="button"
              data-testid="widget-mode-join"
              className={`sf-btn${state.viewMode === "join" ? " sf-btn-active" : ""}`}
              onClick={() => store.update(() => ({ viewMode: "join" }))}
            >
              {i18n.viewJoin}
            </button>
          </div>

          <div className="sf-toolbar-group">
            {state.isSliced && (
              <button
                type="button"
                data-testid="widget-reset-slice-btn"
                className="sf-btn"
                onClick={() => sdk.resetSlice()}
              >
                {i18n.resetSlice}
              </button>
            )}

            <button
              type="button"
              data-testid="widget-orientation-btn"
              className="sf-btn"
              onClick={() =>
                sdk.setOrientation(state.options.orientation === "LR" ? "TB" : "LR")
              }
            >
              {state.options.orientation === "LR" ? "LR" : "TB"}
            </button>

            {vis.settings && (
              <button
                type="button"
                data-testid="widget-settings-btn"
                className={`sf-btn${settingsOpen ? " sf-btn-active" : ""}`}
                onClick={() => {
                  setExportOpen(false);
                  setSettingsOpen((o) => !o);
                }}
              >
                ⚙ {i18n.settings}
              </button>
            )}

            {vis.export && (
              <button
                type="button"
                data-testid="widget-export-btn"
                className={`sf-btn${exportOpen ? " sf-btn-active" : ""}`}
                onClick={() => {
                  setSettingsOpen(false);
                  setExportOpen((o) => !o);
                }}
              >
                ⇩ {i18n.exportMenu}
              </button>
            )}

            <button
              type="button"
              data-testid="widget-lang-btn"
              className="sf-btn"
              onClick={() =>
                sdk.setOptions({ lang: state.options.lang === "en" ? "cn" : "en" })
              }
            >
              {state.options.lang === "en" ? "中文" : "EN"}
            </button>
          </div>

          {/* Settings Popover */}
          {vis.settings && settingsOpen && (
            <div
              className="sf-popover"
              data-testid="widget-settings-popover"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="sf-setting-row">
                <span>{i18n.directDataflow}</span>
                <input
                  type="checkbox"
                  checked={state.settings.dataflow}
                  onChange={(e) => sdk.setting.dataflow.set(e.target.checked)}
                />
              </div>
              <div className="sf-setting-row">
                <span>{i18n.indirectDataflow}</span>
                <input
                  type="checkbox"
                  checked={state.settings.impact}
                  onChange={(e) => sdk.setting.impact.set(e.target.checked)}
                />
              </div>
              <div className="sf-setting-row">
                <span>{i18n.aggregateArgs}</span>
                <input
                  type="checkbox"
                  checked={state.settings.dataflowOfAggregateFunction}
                  onChange={(e) =>
                    sdk.setting.dataflowOfAggregateFunction.set(e.target.checked)
                  }
                />
              </div>
              <div className="sf-setting-row">
                <span>{i18n.showIntermediateRecordset}</span>
                <input
                  type="checkbox"
                  checked={!state.settings.ignoreRecordSet}
                  onChange={(e) =>
                    sdk.setting.showIntermediateRecordset.set(e.target.checked)
                  }
                />
              </div>
              <div className="sf-setting-row">
                <span>{i18n.showFunction}</span>
                <input
                  type="checkbox"
                  checked={!state.settings.ignoreFunction}
                  onChange={(e) => sdk.setting.showFunction.set(e.target.checked)}
                />
              </div>
              <div className="sf-setting-row">
                <span>{i18n.showConstant}</span>
                <input
                  type="checkbox"
                  checked={state.settings.showConstant}
                  onChange={(e) => sdk.setting.showConstant.set(e.target.checked)}
                />
              </div>
              <div className="sf-setting-row">
                <span>{i18n.showTransform}</span>
                <input
                  type="checkbox"
                  checked={state.settings.showTransform}
                  onChange={(e) => sdk.setting.showTransform.set(e.target.checked)}
                />
              </div>
            </div>
          )}

          {/* Export Popover */}
          {vis.export && exportOpen && (
            <div
              className="sf-popover"
              data-testid="widget-export-popover"
              style={{ minWidth: 190 }}
              onClick={(e) => e.stopPropagation()}
            >
              <button
                type="button"
                className="sf-menu-item"
                data-testid="widget-export-png-item"
                onClick={() => {
                  setExportOpen(false);
                  void sdk.saveAsPng();
                }}
              >
                <span>{i18n.exportPng}</span>
                <span>.png</span>
              </button>
              <button
                type="button"
                className="sf-menu-item"
                data-testid="widget-export-json-item"
                onClick={() => {
                  setExportOpen(false);
                  sdk.exportJson();
                }}
              >
                <span>{i18n.exportJson}</span>
                <span>.json</span>
              </button>
              <button
                type="button"
                className="sf-menu-item"
                data-testid="widget-export-xml-item"
                onClick={() => {
                  setExportOpen(false);
                  void sdk.exportXml();
                }}
              >
                <span>{i18n.exportXml}</span>
                <span>.xml</span>
              </button>
              <button
                type="button"
                className="sf-menu-item"
                data-testid="widget-export-csv-item"
                onClick={() => {
                  setExportOpen(false);
                  void sdk.exportCsv();
                }}
              >
                <span>{i18n.exportCsv}</span>
                <span>.csv</span>
              </button>
            </div>
          )}
        </div>
      )}

      {/* Main Split Workspace */}
      <div className="sf-workspace">
        {/* Optional Resizable Left SQL Editor (`visibility.sqlEditor`) */}
        {vis.sqlEditor && (
          <>
            <div
              className="sf-editor-pane"
              data-testid="sqlflow-widget-editor-pane"
              style={{ width: editorWidth }}
            >
              <div className="sf-editor-header">
                <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                  {!vis.toolbar && (
                    <select
                      aria-label={i18n.dialect}
                      data-testid="editor-dialect-select"
                      value={state.options.dbvendor}
                      onChange={(e) => sdk.vendor.set(e.target.value)}
                      style={{ fontSize: 11, padding: "2px 4px" }}
                    >
                      {UI_DIALECTS.map((d) => (
                        <option key={d.shortKey} value={d.shortKey}>
                          {d.label}
                        </option>
                      ))}
                    </select>
                  )}
                  {vis.sqlList && (
                    <button
                      type="button"
                      data-testid="widget-load-sample-btn"
                      onClick={handleLoadDialectSample}
                      style={{
                        fontSize: 11,
                        padding: "2px 6px",
                        border: "1px solid var(--sf-border)",
                        borderRadius: 3,
                        backgroundColor: "var(--sf-surface)",
                        color: "var(--sf-text)",
                        cursor: "pointer"
                      }}
                    >
                      {i18n.loadSample}
                    </button>
                  )}
                </div>

                <div style={{ display: "flex", alignItems: "center", gap: 5 }}>
                  <button
                    type="button"
                    data-testid="widget-format-sql-btn"
                    onClick={handleFormatSql}
                    style={{
                      fontSize: 11,
                      padding: "2px 6px",
                      border: "1px solid var(--sf-border)",
                      borderRadius: 3,
                      backgroundColor: "var(--sf-surface)",
                      color: "var(--sf-text)",
                      cursor: "pointer"
                    }}
                  >
                    {i18n.formatSql}
                  </button>
                  {!vis.toolbar && (
                    <button
                      type="button"
                      data-testid="editor-visualize-btn"
                      onClick={handlePrimaryVisualize}
                      style={{
                        fontSize: 11,
                        padding: "2px 8px",
                        border: "1px solid var(--sf-primary)",
                        borderRadius: 3,
                        backgroundColor: "var(--sf-primary)",
                        color: "#ffffff",
                        fontWeight: 600,
                        cursor: "pointer"
                      }}
                    >
                      {i18n.visualize}
                    </button>
                  )}
                </div>
              </div>

              <div style={{ flex: 1, minHeight: 0 }}>
                <SqlCodeEditor
                  value={state.options.sqltext}
                  onChange={(nextVal) => sdk.sqltext.set(nextVal)}
                  hoverCoordinates={state.hoverCoordinates}
                  highlightCoordinates={state.pinnedCoordinates}
                  errors={state.errors}
                  tokenIndex={activeModelForStats?.tokenIndex}
                  onTokenHover={(_line, _col, hit) => {
                    if (!hit) {
                      store.update(() => ({ hoverTarget: null }));
                    } else if (hit.kind === "column" && hit.columnId) {
                      store.update(() => ({
                        hoverTarget: { kind: "column", columnId: hit.columnId! }
                      }));
                    } else {
                      store.update(() => ({
                        hoverTarget: { kind: "node", nodeId: hit.nodeId }
                      }));
                    }
                  }}
                  onTokenClick={(_line, _col, hit) => {
                    if (!hit) {
                      return;
                    }
                    if (hit.kind === "column" && hit.columnId) {
                      store.update(() => ({
                        pinnedTarget: { kind: "column", columnId: hit.columnId! }
                      }));
                    } else {
                      store.update(() => ({
                        pinnedTarget: { kind: "node", nodeId: hit.nodeId },
                        locatedNodeId: hit.nodeId
                      }));
                    }
                  }}
                />
              </div>
            </div>

            <div
              className="sf-editor-splitter"
              data-testid="widget-editor-splitter"
              onMouseDown={(e) => {
                splitterDragRef.current = {
                  startX: e.clientX,
                  origWidth: editorWidth
                };
              }}
            />
          </>
        )}

        {/* Right Visualization Canvas Host routed by MemoryRouter */}
        <div
          className="sf-canvas-pane"
          data-testid="sqlflow-widget-canvas-pane"
          onContextMenu={(e) => {
            e.preventDefault();
            const targetEl = e.target as HTMLElement | null;
            const colEl = targetEl?.closest?.("[data-column-id]") as HTMLElement | null;
            const nodeEl = targetEl?.closest?.("[data-node-id]") as HTMLElement | null;
            const model = state.lineageModel;
            const col =
              colEl?.dataset.columnId && model
                ? model.columnById.get(colEl.dataset.columnId)
                : undefined;
            const node =
              nodeEl?.dataset.nodeId && model
                ? model.nodeById.get(nodeEl.dataset.nodeId)
                : col && model
                  ? model.nodeById.get(col.nodeId)
                  : undefined;
            setContextMenu({
              x: e.clientX,
              y: e.clientY,
              node,
              column: col
            });
          }}
          onClick={(e) => {
            const target = e.target as HTMLElement | null;
            const isBgClick =
              target?.dataset?.canvasBg === "1" ||
              target?.dataset?.testid === "sqlflow-scroll-viewport" ||
              target?.dataset?.testid === "sqlflow-lineage-empty";
            if (isBgClick) {
              if (suppressNextCanvasClickRef.current) {
                suppressNextCanvasClickRef.current = false;
                return;
              }
              store.update(() => ({
                pinnedTarget: null,
                locatedNodeId: null
              }));
              store.emit("canvasClick", { x: e.clientX, y: e.clientY });
            }
          }}
        >
          <Routes>
            <Route path="*" element={renderActiveCanvas()} />
          </Routes>

          {/* Right-Click Context Menu (`04 §6.8`) */}
          {contextMenu && (
            <div
              className="sf-context-menu"
              data-testid="widget-context-menu"
              style={{ left: contextMenu.x, top: contextMenu.y }}
              onClick={(e) => e.stopPropagation()}
            >
              <div className="sf-context-header">
                {contextMenu.column
                  ? `${contextMenu.node?.name ?? ""}.${contextMenu.column.name}`
                  : contextMenu.node?.displayTitle ?? "SQLFlow Canvas"}
              </div>
              {contextMenu.node && (
                <>
                  <button
                    type="button"
                    className="sf-menu-item"
                    onClick={() => {
                      const n = contextMenu.node!;
                      const c = contextMenu.column;
                      setContextMenu(null);
                      void sdk.upstream({
                        database: n.database,
                        schema: n.schema,
                        table: n.name,
                        column: c?.name
                      });
                    }}
                  >
                    <span>{i18n.contextUpstream}</span>
                  </button>
                  <button
                    type="button"
                    className="sf-menu-item"
                    onClick={() => {
                      const n = contextMenu.node!;
                      const c = contextMenu.column;
                      setContextMenu(null);
                      void sdk.downstream({
                        database: n.database,
                        schema: n.schema,
                        table: n.name,
                        column: c?.name
                      });
                    }}
                  >
                    <span>{i18n.contextDownstream}</span>
                  </button>
                  {state.options.sqltext && !vis.sqlEditor && (
                    <button
                      type="button"
                      className="sf-menu-item"
                      onClick={() => {
                        setContextMenu(null);
                        store.update(() => ({ sqlDrawerOpen: true }));
                      }}
                    >
                      <span>{i18n.contextShowSql}</span>
                    </button>
                  )}
                </>
              )}
              {state.isSliced && (
                <button
                  type="button"
                  className="sf-menu-item"
                  onClick={() => {
                    setContextMenu(null);
                    sdk.resetSlice();
                  }}
                >
                  <span>{i18n.contextShowFull}</span>
                </button>
              )}
              <button
                type="button"
                className="sf-menu-item"
                onClick={() => {
                  setContextMenu(null);
                  void sdk.saveAsPng();
                }}
              >
                <span>{i18n.contextDownloadPng}</span>
              </button>
              <button
                type="button"
                className="sf-menu-item"
                onClick={() => {
                  setContextMenu(null);
                  sdk.exportJson();
                }}
              >
                <span>{i18n.contextDownloadJson}</span>
              </button>
            </div>
          )}

          {/* Bottom SlideUp Read-Only SQL Coordinate Inspector (`04 §6.10`) */}
          {state.sqlDrawerOpen && !vis.sqlEditor && (
            <div className="sf-sql-drawer" data-testid="widget-sql-drawer">
              <div className="sf-sql-drawer-header">
                <span>{i18n.sqlDrawerTitle}</span>
                <button
                  type="button"
                  onClick={() => store.update(() => ({ sqlDrawerOpen: false }))}
                  style={{
                    border: "none",
                    background: "transparent",
                    cursor: "pointer",
                    fontSize: 13,
                    fontWeight: 700,
                    color: "var(--sf-text)"
                  }}
                >
                  {i18n.close} ×
                </button>
              </div>
              <div style={{ flex: 1, minHeight: 0 }}>
                <SqlCodeEditor
                  value={state.options.sqltext}
                  readOnly={true}
                  hoverCoordinates={state.hoverCoordinates}
                  highlightCoordinates={state.pinnedCoordinates}
                  errors={state.errors}
                />
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export const WidgetApp: React.FC<WidgetAppProps> = (props) => {
  const initialMode = props.store.getSnapshot().viewMode;
  const initialEntry = VIEW_MODE_PATHS[initialMode] ?? "/lineage";
  return (
    <MemoryRouter
      initialEntries={[initialEntry]}
      future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
    >
      <WidgetShellInner {...props} />
    </MemoryRouter>
  );
};
