import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  type CanvasColumnRow,
  type CanvasEdge,
  type CanvasNode,
  type Column,
  type HighlightTarget,
  LineageCanvas,
  type LineageCanvasLabels,
  ONBOARDING_CUSTOMER_REVENUE_SQL,
  SqlCodeEditor,
  type Transform,
  Z_INDEX,
  layoutSqlflowGraph,
  normalizeCoordinateSpans,
  traceLineageHighlight
} from "@sqlflow/ui-core";
import {
  ChevronLeft,
  ChevronRight,
  Copy,
  Info,
  Minimize2,
  SlidersHorizontal,
  Sparkles,
  Wrench,
  X
} from "lucide-react";
import { useI18n } from "../../i18n/I18nContext";
import { buildShowRelationTypeParam, useApp } from "../../state/AppContext";
import { captureHighDpiClientPng, triggerBrowserDownload } from "../../utils/exporters";

export interface LineageWorkspaceCanvasProps {
  tableLevel?: boolean;
  exportContainerRef?: React.RefObject<HTMLDivElement | null>;
}

interface ContextMenuState {
  x: number;
  y: number;
  node: CanvasNode;
  column?: CanvasColumnRow;
}

export const LineageWorkspaceCanvas: React.FC<LineageWorkspaceCanvasProps> = ({
  tableLevel = false,
  exportContainerRef
}) => {
  const { locale, t } = useI18n();
  const isCn = locale === "cn";
  const {
    api,
    dialect,
    activeSqlText,
    combinedSqlText,
    lineageData,
    tableLineageData,
    sessionId,
    activeJobId,
    settings,
    updateSettings,
    resetSettings,
    actionsBarState,
    setActionsBarState,
    tryThisDismissed,
    dismissTryThis,
    editorHoverCoords: _editorHoverCoords,
    setEditorHoverCoords,
    setEditorPinnedCoords,
    setActiveOccurrenceIndex,
    canvasHoverTarget,
    setCanvasHoverTarget: _setCanvasHoverTarget,
    canvasPinnedTarget,
    setCanvasPinnedTarget,
    locatedNodeId,
    setLocatedNodeId,
    slideUpDrawer,
    setSlideUpDrawer,
    clearCanvasSelection,
    loadSqlSample,
    setLineageResult,
    visualizeLineage,
    visualizeTableLineage,
    visualizeErDiagram,
    visualizeCallGraph,
    visualizeJoinAnalysis,
    sliceStreamGraph,
    setActiveLeftPanel,
    setActiveModal,
    showToast
  } = useApp();

  const internalRef = useRef<HTMLDivElement | null>(null);
  const containerRef = exportContainerRef ?? internalRef;

  const [infoOpen, setInfoOpen] = useState(false);
  const [gearOpen, setGearOpen] = useState(false);
  const [canvasResetKey, setCanvasResetKey] = useState(0);
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const [expandedStreamIds, setExpandedStreamIds] = useState<Set<string>>(() => new Set());
  const [listedColumnsModal, setListedColumnsModal] = useState<{
    table: string;
    columns: Column[];
  } | null>(null);
  const [ddlViewerModal, setDdlViewerModal] = useState<{
    table: string;
    ddl: string;
  } | null>(null);

  const dragToolbarRef = useRef<{
    startX: number;
    startY: number;
    origX: number;
    origY: number;
  } | null>(null);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setContextMenu(null);
        setInfoOpen(false);
        setGearOpen(false);
        setListedColumnsModal(null);
        setDdlViewerModal(null);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  const sourcePayload = tableLevel ? tableLineageData ?? lineageData : lineageData;

  const layoutOptions = useMemo(
    () => ({
      tableLevel,
      ignoreRecordSet: settings.ignoreRecordSet || settings.simpleOutput,
      ignoreFunction: settings.ignoreFunction,
      showConstant: settings.showConstantTable,
      includeDirect: settings.showDirect,
      includeIndirect: settings.showIndirect,
      rankdir: settings.rankdir,
      minTopMargin: 102
    }),
    [
      tableLevel,
      settings.ignoreRecordSet,
      settings.simpleOutput,
      settings.ignoreFunction,
      settings.showConstantTable,
      settings.showDirect,
      settings.showIndirect,
      settings.rankdir
    ]
  );

  const canvasLabels = useMemo<Partial<LineageCanvasLabels>>(
    () => ({
      emptyGraph: t("canvas.emptyGraph"),
      locatePlaceholder: t("canvas.locatePlaceholder"),
      selectPlaceholder: (count: number) =>
        isCn ? `选择对象 (${count})...` : `Select (${count})...`,
      tablesViewsOnly: t("canvas.tablesViews"),
      showAllObjects: t("canvas.showAll"),
      fitButton: t("canvas.fit"),
      transformPopoverTitle: t("canvas.transformTitle"),
      transformTypeHeader: t("canvas.transformType"),
      transformCodeHeader: t("canvas.transformCode"),
      summaryWarning: isCn
        ? "当前血缘图节点数量较多，建议先通过左侧「元数据」面板筛选表或视图进行可视化。"
        : "Graph has too many nodes to display at once. Open Schema Explorer on the left to pick a schema, table, or view to visualize.",
      showSchemaExplorer: isCn ? "打开元数据浏览器" : "Show Schema Explorer",
      renderFullGraph: isCn
        ? (count: number) => `仍然渲染完整血缘图 (${count} 个对象)`
        : (count: number) => `Render Full Graph (${count} objects)`
    }),
    [isCn, t]
  );

  const computedModel = useMemo(
    () => (sourcePayload ? layoutSqlflowGraph(sourcePayload, layoutOptions) : null),
    [sourcePayload, layoutOptions]
  );

  const handleContextMenu = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      if (!computedModel) return;
      const targetEl = e.target as HTMLElement | null;
      const nodeEl = targetEl?.closest("[data-node-id]") as HTMLElement | null;
      if (!nodeEl) {
        setContextMenu(null);
        return;
      }
      const nodeId = nodeEl.getAttribute("data-node-id");
      if (!nodeId) return;
      const node = computedModel.nodeById.get(nodeId);
      if (!node) return;

      e.preventDefault();
      const colEl = targetEl?.closest("[data-column-id]") as HTMLElement | null;
      const colId = colEl?.getAttribute("data-column-id");
      const column = colId ? computedModel.columnById.get(colId) : undefined;

      const rect = containerRef.current?.getBoundingClientRect();
      const relX = rect ? e.clientX - rect.left : e.clientX;
      const relY = rect ? e.clientY - rect.top : e.clientY;
      setContextMenu({
        x: Math.max(8, relX),
        y: Math.max(8, relY),
        node,
        column
      });
    },
    [computedModel, containerRef]
  );

  const openSqlDrawerFor = useCallback(
    (
      title: string,
      coords: CanvasNode["coordinates"],
      transformCode?: string,
      fallbackTarget?: HighlightTarget,
      alsoPinEditor = false
    ) => {
      const effectiveCoords =
        coords.length > 0
          ? coords
          : fallbackTarget && computedModel
            ? traceLineageHighlight(computedModel, fallbackTarget).coordinates
            : [];
      if (alsoPinEditor) {
        setEditorPinnedCoords(effectiveCoords);
      }
      setActiveOccurrenceIndex(0);
      setSlideUpDrawer({
        open: true,
        title,
        sql: activeSqlText,
        coordinates: effectiveCoords,
        occurrenceIndex: 0,
        transformCode
      });
    },
    [
      activeSqlText,
      computedModel,
      setEditorPinnedCoords,
      setActiveOccurrenceIndex,
      setSlideUpDrawer
    ]
  );

  const handleListColumns = useCallback(
    async (node: CanvasNode) => {
      try {
        const res = await api.client.listTableColumns({
          sessionId,
          dbvendor: dialect,
          database: node.database,
          schema: node.schema,
          table: node.name,
          jobId: activeJobId
        });
        if (Array.isArray(res?.data) && res.data.length > 0) {
          setListedColumnsModal({ table: node.displayTitle, columns: res.data });
          return;
        }
      } catch {
        // Fallback to columns present on the node
      }
      setListedColumnsModal({
        table: node.displayTitle,
        columns: node.columns.map((c) => ({
          id: c.id,
          name: c.name,
          dataType: c.dataType,
          primaryKey: c.primaryKey,
          unqiueKey: c.unqiueKey,
          foreignKey: c.foreignKey
        }))
      });
    },
    [api, sessionId, dialect, activeJobId]
  );

  const handleViewDdl = useCallback((node: CanvasNode) => {
    const colsSql =
      node.columns.length > 0
        ? node.columns
            .map((c) => {
              const dt = c.dataType ?? "VARCHAR(255)";
              const pk = c.primaryKey ? " PRIMARY KEY" : "";
              const uk = !c.primaryKey && c.unqiueKey ? " UNIQUE" : "";
              return `  ${c.name} ${dt}${pk}${uk}`;
            })
            .join(",\n")
        : "  -- No explicit columns resolved";
    const ddl = `CREATE TABLE ${node.displayTitle} (\n${colsSql}\n);`;
    setDdlViewerModal({ table: node.displayTitle, ddl });
  }, []);

  const handleStepSlice = useCallback(
    async (direction: "upstream" | "downstream", node: CanvasNode, col?: CanvasColumnRow) => {
      const params = {
        sessionId,
        dbvendor: dialect,
        tableNamePattern: node.name,
        tableColumn: col?.name,
        distance: 1,
        columnLevel: !tableLevel,
        showRelationType: buildShowRelationTypeParam(settings),
        ignoreRecordSet: settings.ignoreRecordSet,
        ignoreFunction: settings.ignoreFunction,
        showTransform: settings.showTransform,
        jobId: activeJobId
      };
      const res =
        direction === "upstream"
          ? await api.client.upstreamGraph(params)
          : await api.client.downstreamGraph(params);
      if (res?.data) {
        setLineageResult(res.data, res.sessionId ?? sessionId);
      } else {
        void visualizeLineage();
      }
    },
    [api, sessionId, dialect, tableLevel, settings, activeJobId, setLineageResult, visualizeLineage]
  );

  // Drag handler for floating Lineage tools bar (`sqlflow.actionsBar.v1`)
  const onToolbarHeaderMouseDown = (e: React.MouseEvent<HTMLDivElement>) => {
    e.preventDefault();
    dragToolbarRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      origX: actionsBarState.x,
      origY: actionsBarState.y
    };
    const onMove = (ev: MouseEvent) => {
      if (!dragToolbarRef.current) return;
      const nextX = Math.max(8, dragToolbarRef.current.origX + (ev.clientX - dragToolbarRef.current.startX));
      const nextY = Math.max(8, dragToolbarRef.current.origY + (ev.clientY - dragToolbarRef.current.startY));
      setActionsBarState({
        ...actionsBarState,
        x: nextX,
        y: nextY
      });
    };
    const onUp = () => {
      dragToolbarRef.current = null;
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  };

  const drawerSpans = useMemo(
    () => normalizeCoordinateSpans(slideUpDrawer?.coordinates ?? []),
    [slideUpDrawer?.coordinates]
  );

  return (
    <div
      ref={containerRef as React.LegacyRef<HTMLDivElement>}
      data-testid="lineage-workspace-canvas"
      onContextMenu={handleContextMenu}
      onClick={() => {
        setContextMenu(null);
        setInfoOpen(false);
        setGearOpen(false);
      }}
      style={{
        position: "relative",
        width: "100%",
        height: "100%",
        display: "flex",
        flexDirection: "column",
        background: "#eeeeee",
        overflow: "hidden"
      }}
    >
      {/* Top-Left Overlay Controls: Info Stats & Gear Settings (`04 §6.5`) */}
      <div
        style={{
          position: "absolute",
          top: 10,
          left: 12,
          zIndex: Z_INDEX.visualizeToolbar,
          display: "flex",
          alignItems: "center",
          gap: 6
        }}
      >
        <button
          type="button"
          data-testid="canvas-info-btn"
          className="sf-btn sf-btn--light"
          onClick={(e) => {
            e.stopPropagation();
            setInfoOpen((v) => !v);
            setGearOpen(false);
          }}
        >
          <Info size={13} />
          {t("canvas.info")}
        </button>

        <button
          type="button"
          data-testid="canvas-gear-btn"
          className="sf-btn sf-btn--light"
          onClick={(e) => {
            e.stopPropagation();
            setGearOpen((v) => !v);
            setInfoOpen(false);
          }}
        >
          <SlidersHorizontal size={13} />
          {t("canvas.options")}
        </button>
      </div>

      {/* Info Stats Popover */}
      {infoOpen && (
        <div
          data-testid="canvas-info-popover"
          onClick={(e) => e.stopPropagation()}
          style={{
            position: "absolute",
            top: 46,
            left: 12,
            zIndex: Z_INDEX.settingPopover,
            width: 240,
            background: "#ffffff",
            border: "1px solid #cbd5e1",
            borderRadius: 6,
            padding: 12,
            fontSize: 12,
            boxShadow: "0 10px 25px rgba(0,0,0,0.14)"
          }}
        >
          <div style={{ fontWeight: 700, marginBottom: 8 }}>{t("canvas.summaryTitle")}</div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr auto", rowGap: 4 }}>
            <span>{t("canvas.nodesRendered")}</span>
            <strong>{computedModel?.nodes.length ?? 0}</strong>
            <span>{t("canvas.edgesRendered")}</span>
            <strong>{computedModel?.edges.length ?? 0}</strong>
            <span>{t("canvas.containers")}</span>
            <strong>{computedModel?.containers.length ?? 0}</strong>
            <span>{t("canvas.diagnostics")}</span>
            <strong>{computedModel?.errors.length ?? 0}</strong>
          </div>
        </div>
      )}

      {/* Gear Settings Popover (`04 §6.5` -> `/user/saveUserSetting`) */}
      {gearOpen && (
        <div
          data-testid="canvas-gear-popover"
          onClick={(e) => e.stopPropagation()}
          style={{
            position: "absolute",
            top: 46,
            left: 84,
            zIndex: Z_INDEX.settingPopover,
            width: 300,
            background: "#ffffff",
            border: "1px solid #cbd5e1",
            borderRadius: 6,
            padding: 12,
            fontSize: 12,
            boxShadow: "0 10px 25px rgba(0,0,0,0.16)"
          }}
        >
          <div style={{ fontWeight: 700, marginBottom: 8 }}>{t("canvas.preferencesTitle")}</div>
          <label style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 6 }}>
            <input
              type="checkbox"
              checked={settings.showDirect}
              onChange={(e) => void updateSettings({ showDirect: e.target.checked })}
            />
            {t("canvas.direct")}
          </label>
          <label style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 6 }}>
            <input
              type="checkbox"
              checked={settings.showIndirect}
              onChange={(e) => void updateSettings({ showIndirect: e.target.checked })}
            />
            {t("canvas.indirect")}
          </label>
          <label style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 6 }}>
            <input
              type="checkbox"
              checked={settings.ignoreRecordSet}
              onChange={(e) => void updateSettings({ ignoreRecordSet: e.target.checked })}
            />
            {t("canvas.ignoreRecordSet")}
          </label>
          <label style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 6 }}>
            <input
              type="checkbox"
              checked={settings.ignoreFunction}
              onChange={(e) => void updateSettings({ ignoreFunction: e.target.checked })}
            />
            {t("canvas.ignoreFunction")}
          </label>
          <label style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 6 }}>
            <input
              type="checkbox"
              checked={settings.showTransform}
              onChange={(e) => void updateSettings({ showTransform: e.target.checked })}
            />
            {t("canvas.showTransform")}
          </label>
          <label style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 6 }}>
            <input
              type="checkbox"
              checked={settings.showConstantTable}
              onChange={(e) => void updateSettings({ showConstantTable: e.target.checked })}
            />
            {t("canvas.showConstant")}
          </label>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 10 }}>
            <span>{t("canvas.defaultDistance")}</span>
            <input
              type="number"
              min={1}
              max={10}
              value={settings.defaultDistance}
              onChange={(e) =>
                void updateSettings({
                  defaultDistance: Math.min(10, Math.max(1, Number(e.target.value) || 3))
                })
              }
              style={{ width: 54 }}
              className="sf-input"
            />
          </div>
          <div style={{ display: "flex", justifyContent: "flex-end", gap: 6 }}>
            <button
              type="button"
              className="sf-btn sf-btn--light"
              onClick={() => void resetSettings()}
            >
              {t("settings.reset")}
            </button>
            <button
              type="button"
              className="sf-btn sf-btn--primary"
              onClick={() => {
                void updateSettings({}, true);
                setGearOpen(false);
              }}
            >
              {t("settings.save")}
            </button>
          </div>
        </div>
      )}

      {/* Draggable Floating `Lineage tools` Bar (`sqlflow.actionsBar.v1`, `04 §6.7`) */}
      <div
        data-testid="floating-lineage-tools"
        onClick={(e) => e.stopPropagation()}
        style={{
          position: "absolute",
          left: actionsBarState.x,
          top: actionsBarState.y,
          zIndex: Z_INDEX.actionsBar,
          maxWidth: "calc(100% - 24px)",
          background: "#ffffffeb",
          backdropFilter: "blur(4px)",
          border: "1px solid #cbd5e1",
          borderRadius: 6,
          boxShadow: "0 2px 10px rgba(15,23,42,0.1)",
          fontSize: 11,
          display: "inline-flex",
          alignItems: "center",
          flexWrap: "wrap",
          gap: 5,
          padding: "4px 8px"
        }}
      >
        <div
          onMouseDown={onToolbarHeaderMouseDown}
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: 6,
            paddingRight: actionsBarState.collapsed ? 0 : 6,
            borderRight: actionsBarState.collapsed ? "none" : "1px solid #e2e8f0",
            cursor: "move",
            fontWeight: 600,
            color: "#334155",
            userSelect: "none"
          }}
        >
          <span style={{ display: "inline-flex", alignItems: "center", gap: 4 }}>
            <Wrench size={12} />
            {t("canvas.lineageTools")}
          </span>
          <button
            type="button"
            data-testid="toggle-lineage-tools-collapse"
            aria-label="Toggle Lineage tools"
            onClick={() =>
              setActionsBarState({
                ...actionsBarState,
                collapsed: !actionsBarState.collapsed
              })
            }
            style={{
              border: "none",
              background: "transparent",
              cursor: "pointer",
              padding: 2,
              display: "inline-flex",
              color: "#475569"
            }}
          >
            <Minimize2 size={12} />
          </button>
        </div>

        {!actionsBarState.collapsed && (
          <div style={{ display: "inline-flex", alignItems: "center", flexWrap: "wrap", gap: 4 }}>
            <button
              type="button"
              data-testid="tool-fdd-btn"
              className={`sf-btn ${settings.showDirect ? "sf-btn--primary" : "sf-btn--light"}`}
              style={{ height: 22, padding: "0 7px", fontSize: 11 }}
              onClick={() => void updateSettings({ showDirect: !settings.showDirect })}
            >
              fdd
            </button>
            <button
              type="button"
              data-testid="tool-fdr-btn"
              className={`sf-btn ${settings.showIndirect ? "sf-btn--primary" : "sf-btn--light"}`}
              style={{ height: 22, padding: "0 7px", fontSize: 11 }}
              onClick={() => void updateSettings({ showIndirect: !settings.showIndirect })}
            >
              fdr
            </button>
            <button
              type="button"
              data-testid="tool-ignore-recordset-btn"
              className={`sf-btn ${settings.ignoreRecordSet ? "sf-btn--primary" : "sf-btn--light"}`}
              style={{ height: 22, padding: "0 7px", fontSize: 11 }}
              onClick={() => void updateSettings({ ignoreRecordSet: !settings.ignoreRecordSet })}
            >
              {t("canvas.ignoreRecordSetShort")}
            </button>
            <button
              type="button"
              data-testid="tool-simple-output-btn"
              className={`sf-btn ${settings.simpleOutput ? "sf-btn--primary" : "sf-btn--light"}`}
              style={{ height: 22, padding: "0 7px", fontSize: 11 }}
              onClick={() => void updateSettings({ simpleOutput: !settings.simpleOutput })}
            >
              {t("canvas.simpleOutputShort")}
            </button>
            <button
              type="button"
              data-testid="tool-transform-btn"
              className={`sf-btn ${settings.showTransform ? "sf-btn--primary" : "sf-btn--light"}`}
              style={{ height: 22, padding: "0 7px", fontSize: 11 }}
              onClick={() => void updateSettings({ showTransform: !settings.showTransform })}
            >
              f(x)
            </button>
            <button
              type="button"
              data-testid="tool-constant-btn"
              className={`sf-btn ${settings.showConstantTable ? "sf-btn--primary" : "sf-btn--light"}`}
              style={{ height: 22, padding: "0 7px", fontSize: 11 }}
              onClick={() =>
                void updateSettings({ showConstantTable: !settings.showConstantTable })
              }
            >
              {t("canvas.constantsShort")}
            </button>
            <button
              type="button"
              data-testid="tool-trace-source-btn"
              className="sf-btn sf-btn--light"
              style={{ height: 22, padding: "0 7px", fontSize: 11 }}
              onClick={() => {
                const targetNode = computedModel?.nodes[computedModel.nodes.length - 1];
                if (targetNode) {
                  void sliceStreamGraph("leftMost", targetNode.name);
                }
              }}
            >
              {t("canvas.traceSource")}
            </button>
            <button
              type="button"
              data-testid="tool-reset-btn"
              className="sf-btn sf-btn--light"
              style={{ height: 22, padding: "0 7px", fontSize: 11 }}
              onClick={() => {
                clearCanvasSelection();
                setCanvasResetKey((k) => k + 1);
                void visualizeLineage();
              }}
            >
              {t("canvas.reset")}
            </button>
          </div>
        )}
      </div>

      {/* `"New here?"` Interactive 3-Step Onboarding Tour (`sqlflow.tryThis.dismissed.v1`, `04 §6.8`) */}
      {!tryThisDismissed && (
        <div
          data-testid="onboarding-try-this-bar"
          onClick={(e) => e.stopPropagation()}
          style={{
            position: "absolute",
            bottom: slideUpDrawer?.open ? 235 : 14,
            left: 16,
            zIndex: Z_INDEX.tryThisBar,
            background: "#1e293b",
            color: "#f8fafc",
            borderRadius: 8,
            padding: "8px 12px",
            display: "flex",
            alignItems: "center",
            gap: 8,
            fontSize: 12,
            boxShadow: "0 8px 20px rgba(0,0,0,0.25)"
          }}
        >
          <Sparkles size={14} color="#91c051" />
          <span>{t("canvas.newHere")}</span>
          <button
            type="button"
            data-testid="tour-step-1"
            className="sf-btn sf-btn--primary"
            style={{ height: 24, fontSize: 11 }}
            onClick={() => {
              loadSqlSample(ONBOARDING_CUSTOMER_REVENUE_SQL);
              void visualizeLineage(ONBOARDING_CUSTOMER_REVENUE_SQL);
            }}
          >
            {t("canvas.tourStep1")}
          </button>
          <button
            type="button"
            data-testid="tour-step-2"
            className={`sf-btn ${settings.showTransform ? "sf-btn--primary" : ""}`}
            style={{ height: 24, fontSize: 11 }}
            onClick={() => {
              void updateSettings({ showTransform: true });
              showToast(
                isCn
                  ? "已开启 f(x) 转换视图：点击转换边上的圆点即可查看 SQL 计算公式"
                  : "Transform f(x) enabled: click a badge on an edge to inspect its SQL expression"
              );
            }}
          >
            {t("canvas.tourStep2")}
          </button>
          <button
            type="button"
            data-testid="tour-step-3"
            className="sf-btn"
            style={{ height: 24, fontSize: 11 }}
            onClick={() => void visualizeJoinAnalysis()}
          >
            {t("canvas.tourStep3")}
          </button>
          <button
            type="button"
            data-testid="dismiss-try-this-btn"
            aria-label="Dismiss onboarding tour"
            onClick={dismissTryThis}
            style={{
              border: "none",
              background: "transparent",
              color: "#94a3b8",
              cursor: "pointer",
              display: "inline-flex"
            }}
          >
            <X size={14} />
          </button>
        </div>
      )}

      {/* Main Shared `@sqlflow/ui-core` `<LineageCanvas />` */}
      <div style={{ flex: 1, minHeight: 0, position: "relative" }}>
        <LineageCanvas
          key={canvasResetKey}
          data={sourcePayload}
          layoutOptions={layoutOptions}
          showTransform={settings.showTransform}
          treatAggregateAsDirect={settings.treatArgumentsInCountFunctionAsDirectDataflow}
          showMinimap={true}
          showScaleControls={true}
          showLocateBar={true}
          locateBarStyle={{ left: 172, top: 10 }}
          labels={canvasLabels}
          locatedNodeId={locatedNodeId}
          externalHoverTarget={canvasHoverTarget}
          externalPinnedTarget={canvasPinnedTarget}
          expandedStreamNodes={expandedStreamIds}
          onPinnedTargetChange={(next) => {
            if (!next) {
              clearCanvasSelection();
            } else {
              setCanvasPinnedTarget(next);
              if (next.kind !== "node") {
                setLocatedNodeId(null);
              }
            }
          }}
          onHoverCoordinatesChange={setEditorHoverCoords}
          onPinnedCoordinatesChange={setEditorPinnedCoords}
          onShowSchemaExplorer={() => setActiveLeftPanel("schema")}
          onFieldClick={(_payload, column, node) => {
            openSqlDrawerFor(
              `${node.displayTitle}.${column.name}`,
              column.coordinates,
              undefined,
              { kind: "column", columnId: column.id }
            );
          }}
          onTableNameClick={(_payload, node) => {
            openSqlDrawerFor(
              node.displayTitle,
              node.coordinates,
              undefined,
              { kind: "node", nodeId: node.id }
            );
          }}
          onEdgeClick={(edge: CanvasEdge) => {
            const firstTransform = edge.transforms[0]?.code;
            openSqlDrawerFor(
              `Flow: ${edge.sourceNodeId} -> ${edge.targetNodeId}`,
              edge.coordinates,
              firstTransform,
              { kind: "edge", edgeId: edge.id }
            );
          }}
          onTransformClick={(tr: Transform, edge: CanvasEdge) => {
            openSqlDrawerFor(
              `Transform (${tr.type})`,
              tr.coordinates ?? edge.coordinates,
              tr.code,
              { kind: "edge", edgeId: edge.id }
            );
          }}
          onStreamExpandClick={(direction, node, currentlyExpanded) => {
            setExpandedStreamIds((prev) => {
              const next = new Set(prev);
              if (currentlyExpanded) next.delete(node.id);
              else next.add(node.id);
              return next;
            });
            void sliceStreamGraph(direction, node.name);
          }}
        />
      </div>

      {/* 17-Item Right-Click Context Menu (`04 §6.6`, `OBL-097`) */}
      {contextMenu && (
        <div
          data-testid="canvas-context-menu"
          onClick={(e) => e.stopPropagation()}
          style={{
            position: "absolute",
            left: contextMenu.x,
            top: contextMenu.y,
            zIndex: Z_INDEX.canvasContextMenu + 40,
            minWidth: 235,
            maxHeight: 420,
            overflowY: "auto",
            background: "#ffffff",
            border: "1px solid #cbd5e1",
            borderRadius: 6,
            boxShadow: "0 12px 28px rgba(15,23,42,0.22)",
            padding: 4
          }}
        >
          {[
            {
              id: "to-upstream",
              label: t("ctx.toUpstream"),
              run: () =>
                void sliceStreamGraph(
                  "upstream",
                  contextMenu.node.name,
                  contextMenu.column?.name
                )
            },
            {
              id: "to-downstream",
              label: t("ctx.toDownstream"),
              run: () =>
                void sliceStreamGraph(
                  "downstream",
                  contextMenu.node.name,
                  contextMenu.column?.name
                )
            },
            {
              id: "to-upstream-step",
              label: t("ctx.toUpstreamStep"),
              run: () => void handleStepSlice("upstream", contextMenu.node, contextMenu.column)
            },
            {
              id: "to-downstream-step",
              label: t("ctx.toDownstreamStep"),
              run: () =>
                void handleStepSlice("downstream", contextMenu.node, contextMenu.column)
            },
            {
              id: "to-left-most",
              label: t("ctx.toLeftMost"),
              run: () =>
                void sliceStreamGraph(
                  "leftMost",
                  contextMenu.node.name,
                  contextMenu.column?.name,
                  false
                )
            },
            {
              id: "to-left-most-cols",
              label: t("ctx.toLeftMostCols"),
              run: () =>
                void sliceStreamGraph(
                  "leftMost",
                  contextMenu.node.name,
                  contextMenu.column?.name,
                  true
                )
            },
            {
              id: "visualize-tables",
              label: t("ctx.visualizeTables"),
              run: () => void visualizeTableLineage()
            },
            {
              id: "visualize-columns",
              label: t("ctx.visualizeColumns"),
              run: () => void visualizeLineage()
            },
            {
              id: "show-er",
              label: t("ctx.showEr"),
              run: () => void visualizeErDiagram()
            },
            {
              id: "show-call",
              label: t("ctx.showCall"),
              run: () => void visualizeCallGraph()
            },
            {
              id: "show-sql",
              label: t("ctx.showSql"),
              run: () =>
                openSqlDrawerFor(
                  contextMenu.column
                    ? `${contextMenu.node.displayTitle}.${contextMenu.column.name}`
                    : contextMenu.node.displayTitle,
                  contextMenu.column?.coordinates ?? contextMenu.node.coordinates,
                  undefined,
                  contextMenu.column
                    ? { kind: "column", columnId: contextMenu.column.id }
                    : { kind: "node", nodeId: contextMenu.node.id },
                  true
                )
            },
            {
              id: "view-ddl",
              label: t("ctx.viewDdl"),
              run: () => handleViewDdl(contextMenu.node)
            },
            {
              id: "list-columns",
              label: t("ctx.listColumns"),
              run: () => void handleListColumns(contextMenu.node)
            },
            {
              id: "copy-name",
              label: t("ctx.copyName"),
              run: () => {
                const text = contextMenu.column
                  ? `${contextMenu.node.name}.${contextMenu.column.name}`
                  : contextMenu.node.name;
                void navigator.clipboard?.writeText?.(text);
                showToast(isCn ? `已复制：${text}` : `Copied: ${text}`);
              }
            },
            {
              id: "share",
              label: t("ctx.share"),
              run: () => setActiveModal("share")
            },
            {
              id: "download-png",
              label: t("ctx.downloadPng"),
              run: async () => {
                const png = await captureHighDpiClientPng(containerRef.current);
                triggerBrowserDownload(png, `${contextMenu.node.name}.png`, "image/png");
              }
            },
            {
              id: "download-json",
              label: t("ctx.downloadJson"),
              run: () => {
                triggerBrowserDownload(
                  JSON.stringify(sourcePayload ?? {}, null, 2),
                  `${contextMenu.node.name}.json`,
                  "application/json"
                );
              }
            }
          ].map((item) => (
            <button
              key={item.id}
              type="button"
              data-testid={`ctx-${item.id}`}
              className="sf-dropdown-item"
              onClick={() => {
                setContextMenu(null);
                void item.run();
              }}
            >
              {item.label}
            </button>
          ))}
        </div>
      )}

      {/* Bottom `SlideUp` SQL & Transform Expression Drawer (`04 §6.10`, `OBL-099`) */}
      {slideUpDrawer?.open && (
        <div
          data-testid="slideup-sql-drawer"
          style={{
            height: 215,
            minHeight: 170,
            background: "#ffffff",
            borderTop: "2px solid #94a3b8",
            zIndex: Z_INDEX.codeDrawer,
            display: "flex",
            flexDirection: "column"
          }}
        >
          <div
            style={{
              height: 32,
              padding: "0 10px",
              background: "#f1f5f9",
              borderBottom: "1px solid #cbd5e1",
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              fontSize: 12
            }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <strong>{slideUpDrawer.title}</strong>
              {slideUpDrawer.transformCode && (
                <code
                  style={{
                    background: "#e0f2fe",
                    color: "#0369a1",
                    padding: "1px 6px",
                    borderRadius: 4,
                    fontSize: 11
                  }}
                >
                  f(x): {slideUpDrawer.transformCode}
                </code>
              )}
            </div>
            <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
              {drawerSpans.length > 0 && (
                <div style={{ display: "inline-flex", alignItems: "center", gap: 4 }}>
                  <button
                    type="button"
                    aria-label="Previous occurrence"
                    className="sf-btn sf-btn--light"
                    style={{ height: 22, padding: "0 4px" }}
                    onClick={() => {
                      const nextIdx =
                        (slideUpDrawer.occurrenceIndex - 1 + drawerSpans.length) %
                        drawerSpans.length;
                      setActiveOccurrenceIndex(nextIdx);
                      setSlideUpDrawer((prev) =>
                        prev ? { ...prev, occurrenceIndex: nextIdx } : null
                      );
                    }}
                  >
                    <ChevronLeft size={12} />
                  </button>
                  <span>
                    {(slideUpDrawer.occurrenceIndex % drawerSpans.length) + 1} /{" "}
                    {drawerSpans.length}
                  </span>
                  <button
                    type="button"
                    aria-label="Next occurrence"
                    className="sf-btn sf-btn--light"
                    style={{ height: 22, padding: "0 4px" }}
                    onClick={() => {
                      const nextIdx =
                        (slideUpDrawer.occurrenceIndex + 1) % drawerSpans.length;
                      setActiveOccurrenceIndex(nextIdx);
                      setSlideUpDrawer((prev) =>
                        prev ? { ...prev, occurrenceIndex: nextIdx } : null
                      );
                    }}
                  >
                    <ChevronRight size={12} />
                  </button>
                </div>
              )}
              <button
                type="button"
                className="sf-btn sf-btn--light"
                style={{ height: 22, fontSize: 11 }}
                onClick={async () => {
                  const formatted = await api.formatSqlWithDemoFallback(
                    slideUpDrawer.sql || combinedSqlText,
                    dialect
                  );
                  setSlideUpDrawer((prev) => (prev ? { ...prev, sql: formatted } : null));
                }}
              >
                {t("header.formatSql")}
              </button>
              <button
                type="button"
                className="sf-btn sf-btn--light"
                style={{ height: 22, fontSize: 11 }}
                onClick={() => {
                  void navigator.clipboard?.writeText?.(slideUpDrawer.sql);
                  showToast(isCn ? "已复制 SQL" : "SQL copied");
                }}
              >
                <Copy size={11} />
                {t("canvas.copy")}
              </button>
              <button
                type="button"
                data-testid="close-slideup-drawer"
                aria-label="Close SQL drawer"
                onClick={() => clearCanvasSelection()}
                style={{
                  border: "none",
                  background: "transparent",
                  cursor: "pointer",
                  display: "inline-flex"
                }}
              >
                <X size={14} />
              </button>
            </div>
          </div>
          <div style={{ flex: 1, minHeight: 0 }}>
            <SqlCodeEditor
              value={slideUpDrawer.sql}
              readOnly={true}
              highlightCoordinates={slideUpDrawer.coordinates}
              activeOccurrenceIndex={slideUpDrawer.occurrenceIndex}
              height="100%"
            />
          </div>
        </div>
      )}

      {/* `listTableColumns` Modal */}
      {listedColumnsModal && (
        <div className="sf-modal-backdrop" onClick={() => setListedColumnsModal(null)}>
          <div
            className="sf-modal-card"
            style={{ maxWidth: 520 }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="sf-modal-header">
              <span>
                {isCn ? `字段列表：${listedColumnsModal.table}` : `Columns of ${listedColumnsModal.table}`}
              </span>
              <button
                type="button"
                className="sf-btn sf-btn--light"
                onClick={() => setListedColumnsModal(null)}
              >
                {isCn ? "关闭" : "Close"}
              </button>
            </div>
            <div className="sf-modal-body">
              <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 12 }}>
                <thead>
                  <tr style={{ background: "#f8fafc" }}>
                    <th style={{ textAlign: "left", padding: 6, borderBottom: "1px solid #e2e8f0" }}>
                      {isCn ? "字段名" : "Column"}
                    </th>
                    <th style={{ textAlign: "left", padding: 6, borderBottom: "1px solid #e2e8f0" }}>
                      {isCn ? "数据类型" : "Type"}
                    </th>
                    <th style={{ textAlign: "left", padding: 6, borderBottom: "1px solid #e2e8f0" }}>
                      {isCn ? "约束" : "Keys"}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {listedColumnsModal.columns.map((col) => (
                    <tr key={col.id}>
                      <td style={{ padding: 6, borderBottom: "1px solid #f1f5f9" }}>{col.name}</td>
                      <td style={{ padding: 6, borderBottom: "1px solid #f1f5f9", color: "#64748b" }}>
                        {col.dataType ?? "-"}
                      </td>
                      <td style={{ padding: 6, borderBottom: "1px solid #f1f5f9" }}>
                        {[
                          col.primaryKey ? "PK" : null,
                          col.unqiueKey ? "UK" : null,
                          col.foreignKey ? "FK" : null
                        ]
                          .filter(Boolean)
                          .join(", ") || "-"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* `view DDL` Modal */}
      {ddlViewerModal && (
        <div className="sf-modal-backdrop" onClick={() => setDdlViewerModal(null)}>
          <div
            className="sf-modal-card"
            style={{ maxWidth: 600 }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="sf-modal-header">
              <span>DDL — {ddlViewerModal.table}</span>
              <button
                type="button"
                className="sf-btn sf-btn--light"
                onClick={() => setDdlViewerModal(null)}
              >
                {isCn ? "关闭" : "Close"}
              </button>
            </div>
            <div className="sf-modal-body" style={{ height: 260 }}>
              <SqlCodeEditor value={ddlViewerModal.ddl} readOnly={true} height="100%" />
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
