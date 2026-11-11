/**
 * Reusable Hybrid HTML + SVG Interactive Lineage Canvas (`<LineageCanvas />`)
 * shared 1:1 between `frontend-ui` and `widget-sdk`.
 *
 * Implements:
 * - Hybrid DOM node/package cards + `16px` column rows + `PK`/`UK` (`unqiueKey`)/`FK` badges (`04 §6.1–§6.3`)
 * - 17-entry `dboColor` node header palette (`04 §3.3`)
 * - Live node header drag with real-time SVG cubic Bézier (`M...C...S...`) & `10x7` arrowhead updates (`04 §6.3–§6.4`)
 * - `fdd` solid, `fdr`/`frd` dashed (`4 2`), and `join` green edge styles + `f(x)` transform badge popovers (`04 §6.4`)
 * - Upstream/downstream `+`/`-` stream expansion buttons (`04 §6.3`)
 * - Collapsible large column lists (`>30` columns)
 * - Recursive upstream/downstream hover (`#faebd7`) & click-pin (`#dddddd`) path highlighting (`04 §6.3`)
 * - Zoom (`+` `×1.2`, `-` `×0.8`, `1:1`, Fit), pan, Locate bar, and `110×110` Minimap (`04 §6.5, §6.10`)
 * - `>200` object Summary Interstitial gate with full object counts & bypass control (`04 §6.9`)
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ApiEnvelope } from "../types/api.js";
import type { Coordinate } from "../types/coordinates.js";
import type { DataFlow, SqlflowJSON, Transform } from "../types/lineage.js";
import type { OnFieldClickPayload, OnTableNameClickPayload } from "../types/options.js";
import {
  CANVAS_GEOMETRY_TOKENS,
  CONTAINER_STYLE_TOKENS,
  LOCATED_NODE_TOKENS,
  SURFACE_AND_EDGE_TOKENS,
  Z_INDEX,
  getDboHeaderColor,
  getDboHeaderTextColor
} from "../theme/tokens.js";
import { resolveEdgeVisualStyle } from "../geometry/bezier.js";
import {
  type CanvasColumnRow,
  type CanvasEdge,
  type CanvasGraphModel,
  type CanvasNode,
  type HighlightTarget,
  type LayoutSqlflowOptions,
  buildOnFieldClickPayload,
  buildOnTableNameClickPayload,
  layoutSqlflowGraph,
  moveCanvasNode,
  traceLineageHighlight
} from "../geometry/layoutAdapter.js";
import { truncateMiddle53 } from "../geometry/textMeasure.js";

export interface LineageCanvasLabels {
  emptyGraph: string;
  locatePlaceholder: string;
  selectPlaceholder: (count: number) => string;
  tablesViewsOnly: string;
  showAllObjects: string;
  fitButton: string;
  transformPopoverTitle: string;
  transformTypeHeader: string;
  transformCodeHeader: string;
  summaryWarning: string;
  showSchemaExplorer: string;
  renderFullGraph: (count: number) => string;
}

const DEFAULT_CANVAS_LABELS: LineageCanvasLabels = {
  emptyGraph: "No lineage graph loaded.",
  locatePlaceholder: "Locate table or view...",
  selectPlaceholder: (count) => `Select (${count})...`,
  tablesViewsOnly: "Tables/Views",
  showAllObjects: "Show All",
  fitButton: "Fit",
  transformPopoverTitle: "Transform Expressions",
  transformTypeHeader: "Transform Type",
  transformCodeHeader: "Expression Code",
  summaryWarning:
    "Graph has too many nodes to display at once. Open Schema Explorer on the left to pick a schema, table, or view to visualize.",
  showSchemaExplorer: "Show Schema Explorer",
  renderFullGraph: (count) => `Render Full Graph (${count} objects)`
};

export interface LineageCanvasProps {
  data?: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON> | null;
  model?: CanvasGraphModel | null;
  layoutOptions?: LayoutSqlflowOptions;
  showTransform?: boolean;
  treatAggregateAsDirect?: boolean;
  showMinimap?: boolean;
  showScaleControls?: boolean;
  showLocateBar?: boolean;
  locateBarStyle?: React.CSSProperties;
  labels?: Partial<LineageCanvasLabels>;
  locatedNodeId?: string | null;
  externalHoverTarget?: HighlightTarget | null;
  externalPinnedTarget?: HighlightTarget | null;
  expandedStreamNodes?: ReadonlySet<string>;
  collapsibleColumnLimit?: number;
  width?: number | string;
  height?: number | string;
  className?: string;
  style?: React.CSSProperties;
  onFieldClick?: (
    payload: OnFieldClickPayload,
    column: CanvasColumnRow,
    node: CanvasNode
  ) => void;
  onTableNameClick?: (
    payload: OnTableNameClickPayload,
    node: CanvasNode
  ) => void;
  onEdgeClick?: (edge: CanvasEdge) => void;
  onTransformClick?: (transform: Transform, edge: CanvasEdge) => void;
  onStreamExpandClick?: (
    direction: "upstream" | "downstream",
    node: CanvasNode,
    isCurrentlyExpanded: boolean
  ) => void;
  onPinnedTargetChange?: (target: HighlightTarget | null) => void;
  onHoverCoordinatesChange?: (coordinates: Coordinate[]) => void;
  onPinnedCoordinatesChange?: (coordinates: Coordinate[]) => void;
  onShowSchemaExplorer?: () => void;
  onNodePositionChange?: (nodeId: string, x: number, y: number) => void;
}

const INTERMEDIATE_TYPES = new Set([
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
  "unnest"
]);

const INDIRECT_CLAUSE_TRANSFORM_TYPES = new Set([
  "join",
  "on",
  "where",
  "groupby",
  "having",
  "qualify",
  "orderby",
  "startwith",
  "connectby"
]);

function isSameHighlightTarget(
  a: HighlightTarget | null | undefined,
  b: HighlightTarget | null | undefined
): boolean {
  if (!a || !b || a.kind !== b.kind) {
    return false;
  }
  if (a.kind === "column" && b.kind === "column") {
    return a.columnId === b.columnId;
  }
  if (a.kind === "node" && b.kind === "node") {
    return a.nodeId === b.nodeId;
  }
  if (a.kind === "edge" && b.kind === "edge") {
    return a.edgeId === b.edgeId;
  }
  return false;
}

function getMeaningfulEdgeTransforms(edge: CanvasEdge): Transform[] {
  if (!edge.transforms || edge.transforms.length === 0) {
    return [];
  }
  return edge.transforms.filter((tx) => {
    const code = tx.code?.trim();
    if (!code) {
      return false;
    }
    const kind = (tx.type || "").trim().toLowerCase();
    if (kind === "alias") {
      return false;
    }
    if (edge.type !== "fdd" && INDIRECT_CLAUSE_TRANSFORM_TYPES.has(kind)) {
      return false;
    }
    if (kind === "select") {
      // Bare pass-through column reference (e.g. `c.customer_name`) or whole-statement view DDL
      if (/^[A-Za-z_"`[\]][\w$."`[\]]*$/.test(code)) {
        return false;
      }
      if (/^(CREATE|ALTER|INSERT|UPDATE|MERGE|SELECT)\b/i.test(code)) {
        return false;
      }
    }
    return true;
  });
}

export const LineageCanvas: React.FC<LineageCanvasProps> = ({
  data,
  model: externalModel,
  layoutOptions,
  showTransform = false,
  treatAggregateAsDirect = false,
  showMinimap = true,
  showScaleControls = true,
  showLocateBar = false,
  locateBarStyle,
  labels,
  locatedNodeId: externalLocatedNodeId,
  externalHoverTarget,
  externalPinnedTarget,
  expandedStreamNodes,
  collapsibleColumnLimit = CANVAS_GEOMETRY_TOKENS.collapsibleColumnThreshold,
  width = "100%",
  height = "100%",
  className,
  style,
  onFieldClick,
  onTableNameClick,
  onEdgeClick,
  onTransformClick,
  onStreamExpandClick,
  onPinnedTargetChange,
  onHoverCoordinatesChange,
  onPinnedCoordinatesChange,
  onShowSchemaExplorer,
  onNodePositionChange
}) => {
  const uiLabels = useMemo<LineageCanvasLabels>(() => {
    if (!labels) {
      return DEFAULT_CANVAS_LABELS;
    }
    const definedEntries = Object.entries(labels).filter(([, v]) => v !== undefined);
    return {
      ...DEFAULT_CANVAS_LABELS,
      ...(Object.fromEntries(definedEntries) as Partial<LineageCanvasLabels>)
    };
  }, [labels]);
  const computedBaseModel = useMemo<CanvasGraphModel | null>(() => {
    if (externalModel) {
      return externalModel;
    }
    if (!data) {
      return null;
    }
    return layoutSqlflowGraph(data, layoutOptions);
  }, [externalModel, data, layoutOptions]);

  const [liveModel, setLiveModel] = useState<CanvasGraphModel | null>(computedBaseModel);
  const [bypassSummary, setBypassSummary] = useState(false);
  const [scale, setScale] = useState(1);
  const [hoverTarget, setHoverTarget] = useState<HighlightTarget | null>(null);
  const [pinnedTarget, setPinnedTarget] = useState<HighlightTarget | null>(null);
  const [activeTransformEdgeId, setActiveTransformEdgeId] = useState<string | null>(null);
  const [expandedColumnNodes, setExpandedColumnNodes] = useState<Set<string>>(() => new Set());
  const [locateFilter, setLocateFilter] = useState<"tables" | "all">("tables");
  const [locateQuery, setLocateQuery] = useState("");
  const [internalLocatedNodeId, setInternalLocatedNodeId] = useState<string | null>(null);
  const [scrollState, setScrollState] = useState({
    scrollLeft: 0,
    scrollTop: 0,
    clientWidth: 800,
    clientHeight: 600
  });

  const scrollViewportRef = useRef<HTMLDivElement | null>(null);
  const didDragNodeRef = useRef(false);
  const didPanCanvasRef = useRef(false);
  const dragNodeRef = useRef<{
    nodeId: string;
    startClientX: number;
    startClientY: number;
    origX: number;
    origY: number;
  } | null>(null);
  const panRef = useRef<{
    startClientX: number;
    startClientY: number;
    origScrollLeft: number;
    origScrollTop: number;
  } | null>(null);

  useEffect(() => {
    setLiveModel(computedBaseModel);
    setBypassSummary(false);
    setActiveTransformEdgeId(null);
  }, [computedBaseModel]);

  useEffect(() => {
    if (externalPinnedTarget !== undefined) {
      setPinnedTarget(externalPinnedTarget);
      if (!externalPinnedTarget) {
        setInternalLocatedNodeId(null);
        setActiveTransformEdgeId(null);
      }
    }
  }, [externalPinnedTarget]);

  const effectiveHover = externalHoverTarget ?? hoverTarget;
  const isPinControlled =
    externalPinnedTarget !== undefined && onPinnedTargetChange !== undefined;
  const effectivePin = isPinControlled
    ? externalPinnedTarget
    : (externalPinnedTarget ?? pinnedTarget);
  const effectiveLocatedNodeId = externalLocatedNodeId ?? internalLocatedNodeId;

  const applyPinnedTarget = useCallback(
    (next: HighlightTarget | null) => {
      setPinnedTarget(next);
      if (!next || next.kind !== "node") {
        setInternalLocatedNodeId(null);
      }
      if (!next) {
        setActiveTransformEdgeId(null);
      }
      onPinnedTargetChange?.(next);
    },
    [onPinnedTargetChange]
  );

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        if (effectivePin || activeTransformEdgeId || effectiveLocatedNodeId) {
          applyPinnedTarget(null);
        }
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [effectivePin, activeTransformEdgeId, effectiveLocatedNodeId, applyPinnedTarget]);

  const hoverHighlightSet = useMemo(
    () =>
      liveModel
        ? traceLineageHighlight(liveModel, effectiveHover)
        : { nodeIds: new Set<string>(), columnIds: new Set<string>(), edgeIds: new Set<string>(), coordinates: [] },
    [liveModel, effectiveHover]
  );

  const pinnedHighlightSet = useMemo(
    () =>
      liveModel
        ? traceLineageHighlight(liveModel, effectivePin)
        : { nodeIds: new Set<string>(), columnIds: new Set<string>(), edgeIds: new Set<string>(), coordinates: [] },
    [liveModel, effectivePin]
  );

  useEffect(() => {
    onHoverCoordinatesChange?.(hoverHighlightSet.coordinates);
  }, [hoverHighlightSet, onHoverCoordinatesChange]);

  useEffect(() => {
    onPinnedCoordinatesChange?.(pinnedHighlightSet.coordinates);
  }, [pinnedHighlightSet, onPinnedCoordinatesChange]);

  const updateScrollMetrics = useCallback(() => {
    const el = scrollViewportRef.current;
    if (!el) {
      return;
    }
    setScrollState({
      scrollLeft: el.scrollLeft,
      scrollTop: el.scrollTop,
      clientWidth: el.clientWidth || 800,
      clientHeight: el.clientHeight || 600
    });
  }, []);

  useEffect(() => {
    updateScrollMetrics();
  }, [liveModel, scale, updateScrollMetrics]);

  // Global mousemove / mouseup handlers for node card dragging and canvas panning
  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (dragNodeRef.current) {
        const drag = dragNodeRef.current;
        const rawDist = Math.hypot(
          e.clientX - drag.startClientX,
          e.clientY - drag.startClientY
        );
        if (rawDist > 4) {
          didDragNodeRef.current = true;
        }
        const dx = (e.clientX - drag.startClientX) / scale;
        const dy = (e.clientY - drag.startClientY) / scale;
        const nextX = drag.origX + dx;
        const nextY = drag.origY + dy;
        setLiveModel((prev) => (prev ? moveCanvasNode(prev, drag.nodeId, nextX, nextY) : prev));
        onNodePositionChange?.(drag.nodeId, Math.round(nextX), Math.round(nextY));
      } else if (panRef.current && scrollViewportRef.current) {
        const pan = panRef.current;
        const rawDist = Math.hypot(
          e.clientX - pan.startClientX,
          e.clientY - pan.startClientY
        );
        if (rawDist > 4) {
          didPanCanvasRef.current = true;
        }
        scrollViewportRef.current.scrollLeft = pan.origScrollLeft - (e.clientX - pan.startClientX);
        scrollViewportRef.current.scrollTop = pan.origScrollTop - (e.clientY - pan.startClientY);
        updateScrollMetrics();
      }
    };

    const handleMouseUp = () => {
      dragNodeRef.current = null;
      panRef.current = null;
    };

    window.addEventListener("mousemove", handleMouseMove);
    window.addEventListener("mouseup", handleMouseUp);
    return () => {
      window.removeEventListener("mousemove", handleMouseMove);
      window.removeEventListener("mouseup", handleMouseUp);
    };
  }, [scale, onNodePositionChange, updateScrollMetrics]);

  const centerNodeInViewport = useCallback(
    (nodeId: string) => {
      if (!liveModel || !scrollViewportRef.current) {
        return;
      }
      const node = liveModel.nodeById.get(nodeId);
      if (!node) {
        return;
      }
      const vp = scrollViewportRef.current;
      const targetLeft = Math.max(
        0,
        (node.x + node.width / 2) * scale - (vp.clientWidth || 800) / 2
      );
      const targetTop = Math.max(
        0,
        (node.y + node.height / 2) * scale - (vp.clientHeight || 600) / 2
      );
      vp.scrollTo?.({ left: targetLeft, top: targetTop, behavior: "smooth" });
      vp.scrollLeft = targetLeft;
      vp.scrollTop = targetTop;
      updateScrollMetrics();
    },
    [liveModel, scale, updateScrollMetrics]
  );

  const handleZoomIn = useCallback(() => {
    setScale((prev) =>
      Math.min(
        CANVAS_GEOMETRY_TOKENS.maxZoom,
        Math.round(prev * CANVAS_GEOMETRY_TOKENS.zoomStepInFactor * 100) / 100
      )
    );
  }, []);

  const handleZoomOut = useCallback(() => {
    setScale((prev) =>
      Math.max(
        CANVAS_GEOMETRY_TOKENS.minZoom,
        Math.round(prev * CANVAS_GEOMETRY_TOKENS.zoomStepOutFactor * 100) / 100
      )
    );
  }, []);

  const handleResetZoom = useCallback(() => {
    setScale(1);
  }, []);

  const handleFitView = useCallback(() => {
    if (!liveModel || !scrollViewportRef.current) {
      return;
    }
    const vpWidth = scrollViewportRef.current.clientWidth || 800;
    const vpHeight = scrollViewportRef.current.clientHeight || 600;
    const fitRatio = Math.min(
      vpWidth / Math.max(1, liveModel.canvasWidth),
      vpHeight / Math.max(1, liveModel.canvasHeight),
      1.0
    );
    setScale(Math.max(CANVAS_GEOMETRY_TOKENS.minZoom, Math.round(fitRatio * 100) / 100));
  }, [liveModel]);

  const handleCenterPinned = useCallback(() => {
    if (!effectivePin || !liveModel) {
      return;
    }
    if (effectivePin.kind === "node") {
      centerNodeInViewport(effectivePin.nodeId);
    } else if (effectivePin.kind === "column") {
      const col = liveModel.columnById.get(effectivePin.columnId);
      if (col) {
        centerNodeInViewport(col.nodeId);
      }
    }
  }, [effectivePin, liveModel, centerNodeInViewport]);

  const locatableNodes = useMemo(() => {
    if (!liveModel) {
      return [];
    }
    return liveModel.nodes.filter((n) => {
      if (locateFilter === "tables" && INTERMEDIATE_TYPES.has(n.type.toLowerCase())) {
        return false;
      }
      if (!locateQuery.trim()) {
        return true;
      }
      return n.displayTitle.toLowerCase().includes(locateQuery.trim().toLowerCase());
    });
  }, [liveModel, locateFilter, locateQuery]);

  const activeTransformEdge = useMemo(
    () => (activeTransformEdgeId && liveModel ? liveModel.edgeById.get(activeTransformEdgeId) : undefined),
    [activeTransformEdgeId, liveModel]
  );

  if (!liveModel) {
    return (
      <div
        data-testid="sqlflow-lineage-empty"
        className={className}
        style={{
          position: "relative",
          width,
          height,
          backgroundColor: SURFACE_AND_EDGE_TOKENS.canvasBg,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          color: "#666666",
          fontFamily: "verdana, sans-serif",
          fontSize: 13,
          ...style
        }}
      >
        {uiLabels.emptyGraph}
      </div>
    );
  }

  const showSummaryGate = liveModel.isSummaryGateActive && !bypassSummary;

  const minimapScale = Math.min(
    CANVAS_GEOMETRY_TOKENS.minimapSize / Math.max(1, liveModel.canvasWidth),
    CANVAS_GEOMETRY_TOKENS.minimapSize / Math.max(1, liveModel.canvasHeight)
  );

  return (
    <div
      data-testid="sqlflow-lineage-canvas"
      className={className}
      style={{
        position: "relative",
        width,
        height,
        overflow: "hidden",
        backgroundColor: SURFACE_AND_EDGE_TOKENS.canvasBg,
        userSelect: "none",
        fontFamily: "verdana, sans-serif",
        ...style
      }}
    >
      {/* Summary Interstitial Gate (>200 objects or mode === "summary", 04 §6.9) */}
      {showSummaryGate && (
        <div
          data-testid="sqlflow-summary-interstitial"
          style={{
            position: "absolute",
            inset: 0,
            zIndex: Z_INDEX.summaryInterstitial,
            backgroundColor: SURFACE_AND_EDGE_TOKENS.canvasBg,
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            justifyContent: "center",
            padding: 24
          }}
        >
          <table
            style={{
              width: 500,
              maxWidth: "100%",
              borderCollapse: "collapse",
              border: "2px solid #d9d9d9",
              fontSize: 13
            }}
          >
            <tbody>
              {[
                ["Databases", liveModel.summary.database],
                ["Schemas", liveModel.summary.schema],
                ["Tables", liveModel.summary.table],
                ["Views", liveModel.summary.view],
                ["Procedures", liveModel.summary.procedure ?? 0],
                ["Functions", liveModel.summary.function ?? 0],
                ["Triggers", liveModel.summary.trigger ?? 0],
                ["Synonyms", liveModel.summary.synonym ?? 0],
                ["Columns", liveModel.summary.column],
                ["Relationships", liveModel.summary.relationship]
              ].map(([label, count]) => (
                <tr key={String(label)} style={{ borderBottom: "1px solid #d9d9d9" }}>
                  <th
                    style={{
                      textAlign: "left",
                      padding: "8px 14px",
                      backgroundColor: "#eeeeee",
                      width: "55%",
                      fontWeight: 600,
                      color: "#333333"
                    }}
                  >
                    {label}
                  </th>
                  <td
                    style={{
                      padding: "8px 14px",
                      backgroundColor: "#e8e8e8",
                      color: "#111111"
                    }}
                  >
                    {count}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <div
            style={{
              marginTop: 16,
              maxWidth: 500,
              textAlign: "center",
              color: "#444444",
              fontSize: 13,
              lineHeight: 1.5
            }}
          >
            <p style={{ margin: "0 0 10px" }}>
              {uiLabels.summaryWarning}
            </p>
            <div style={{ display: "flex", gap: 12, justifyContent: "center" }}>
              {onShowSchemaExplorer && (
                <button
                  type="button"
                  data-testid="summary-show-schema-explorer"
                  onClick={onShowSchemaExplorer}
                  style={{
                    fontSize: 14,
                    padding: "6px 14px",
                    cursor: "pointer",
                    border: "1px solid #1890ff",
                    borderRadius: 4,
                    backgroundColor: "#1890ff",
                    color: "#ffffff"
                  }}
                >
                  {uiLabels.showSchemaExplorer}
                </button>
              )}
              <button
                type="button"
                data-testid="summary-render-anyway"
                onClick={() => setBypassSummary(true)}
                style={{
                  fontSize: 13,
                  padding: "6px 14px",
                  cursor: "pointer",
                  border: "1px solid #bdbdbd",
                  borderRadius: 4,
                  backgroundColor: "#ffffff",
                  color: "#333333"
                }}
              >
                {uiLabels.renderFullGraph(liveModel.nodes.length)}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Optional Top-Left Locate Search Bar (04 §6.5) */}
      {!showSummaryGate && showLocateBar && (
        <div
          data-testid="sqlflow-locate-bar"
          onClick={(e) => e.stopPropagation()}
          style={{
            position: "absolute",
            left: 40,
            top: 15,
            zIndex: Z_INDEX.visualizeToolbar,
            display: "flex",
            alignItems: "center",
            gap: 6,
            backgroundColor: "#ffffffeb",
            padding: "4px 8px",
            borderRadius: 4,
            boxShadow: "0 1px 4px rgba(0,0,0,0.14)",
            ...locateBarStyle
          }}
        >
          <input
            type="text"
            aria-label="Locate graph object"
            placeholder={uiLabels.locatePlaceholder}
            value={locateQuery}
            onChange={(e) => setLocateQuery(e.target.value)}
            style={{
              width: 156,
              fontSize: 12,
              padding: "3px 6px",
              border: "1px solid #d9d9d9",
              borderRadius: 3
            }}
          />
          <select
            aria-label="Select located object"
            value={effectiveLocatedNodeId ?? ""}
            onChange={(e) => {
              const id = e.target.value || null;
              setInternalLocatedNodeId(id);
              if (id) {
                applyPinnedTarget({ kind: "node", nodeId: id });
                centerNodeInViewport(id);
              } else {
                applyPinnedTarget(null);
              }
            }}
            style={{
              width: 140,
              fontSize: 12,
              padding: "3px 4px",
              border: "1px solid #d9d9d9",
              borderRadius: 3
            }}
          >
            <option value="">{uiLabels.selectPlaceholder(locatableNodes.length)}</option>
            {locatableNodes.map((n) => (
              <option key={n.id} value={n.id}>
                {n.displayTitle}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={() => setLocateFilter((f) => (f === "tables" ? "all" : "tables"))}
            style={{
              fontSize: 11,
              padding: "3px 6px",
              border: "1px solid #d9d9d9",
              borderRadius: 3,
              backgroundColor: "#fafafa",
              cursor: "pointer",
              whiteSpace: "nowrap"
            }}
          >
            {locateFilter === "tables" ? uiLabels.tablesViewsOnly : uiLabels.showAllObjects}
          </button>
        </div>
      )}

      {/* Top-Right Scale Controls (+, -, 1:1, Fit, Center Pinned — 04 §6.10) */}
      {!showSummaryGate && showScaleControls && (
        <div
          data-testid="sqlflow-scale-controls"
          style={{
            position: "absolute",
            right: 30,
            top: 30,
            zIndex: Z_INDEX.scaleControls,
            display: "flex",
            flexDirection: "column",
            gap: 6,
            backgroundColor: "#eeeeeea6",
            borderRadius: 5,
            padding: 5
          }}
        >
          <button
            type="button"
            aria-label="Zoom In"
            data-testid="zoom-in-btn"
            onClick={handleZoomIn}
            style={{
              width: 28,
              height: 26,
              cursor: "pointer",
              border: "1px solid #cccccc",
              borderRadius: 3,
              backgroundColor: "#ffffff",
              fontWeight: 600
            }}
          >
            +
          </button>
          <button
            type="button"
            aria-label="Zoom Out"
            data-testid="zoom-out-btn"
            onClick={handleZoomOut}
            style={{
              width: 28,
              height: 26,
              cursor: "pointer",
              border: "1px solid #cccccc",
              borderRadius: 3,
              backgroundColor: "#ffffff",
              fontWeight: 600
            }}
          >
            -
          </button>
          <button
            type="button"
            aria-label="Reset Zoom"
            data-testid="zoom-reset-btn"
            onClick={handleResetZoom}
            style={{
              width: 28,
              height: 26,
              cursor: "pointer",
              border: "1px solid #cccccc",
              borderRadius: 3,
              backgroundColor: "#ffffff",
              fontSize: 10
            }}
          >
            1:1
          </button>
          <button
            type="button"
            aria-label="Fit Graph"
            data-testid="zoom-fit-btn"
            onClick={handleFitView}
            style={{
              width: 28,
              height: 26,
              cursor: "pointer",
              border: "1px solid #cccccc",
              borderRadius: 3,
              backgroundColor: "#ffffff",
              fontSize: 10
            }}
          >
            {uiLabels.fitButton}
          </button>
          <button
            type="button"
            aria-label="Center Selected Node"
            data-testid="center-selected-btn"
            disabled={!effectivePin}
            onClick={handleCenterPinned}
            style={{
              width: 28,
              height: 26,
              cursor: effectivePin ? "pointer" : "not-allowed",
              opacity: effectivePin ? 1 : 0.5,
              border: "1px solid #cccccc",
              borderRadius: 3,
              backgroundColor: "#ffffff",
              fontSize: 11
            }}
          >
            ⌖
          </button>
        </div>
      )}

      {/* Scrollable & Drag-to-Pan Viewport */}
      {!showSummaryGate && (
        <div
          ref={scrollViewportRef}
          data-testid="sqlflow-scroll-viewport"
          onScroll={updateScrollMetrics}
          onMouseDown={(e) => {
            didPanCanvasRef.current = false;
            if (
              e.target === e.currentTarget ||
              (e.target as HTMLElement).dataset?.canvasBg === "1" ||
              (e.target as Element).tagName?.toLowerCase() === "svg"
            ) {
              if (scrollViewportRef.current) {
                panRef.current = {
                  startClientX: e.clientX,
                  startClientY: e.clientY,
                  origScrollLeft: scrollViewportRef.current.scrollLeft,
                  origScrollTop: scrollViewportRef.current.scrollTop
                };
              }
            }
          }}
          onClick={(e) => {
            if (didPanCanvasRef.current) {
              didPanCanvasRef.current = false;
              return;
            }
            if (
              e.target === e.currentTarget ||
              (e.target as HTMLElement).dataset?.canvasBg === "1" ||
              (e.target as Element).tagName?.toLowerCase() === "svg"
            ) {
              applyPinnedTarget(null);
            }
          }}
          style={{
            width: "100%",
            height: "100%",
            overflow: "auto",
            position: "relative",
            cursor: "grab"
          }}
        >
          {/* Scaled Canvas Surface (transformOrigin: "40px 60px") */}
          <div
            data-testid="sqlflow-scaled-surface"
            data-canvas-bg="1"
            data-scale={scale}
            style={{
              position: "relative",
              width: liveModel.canvasWidth,
              height: liveModel.canvasHeight,
              transformOrigin: CANVAS_GEOMETRY_TOKENS.transformOrigin,
              transform: `scale(${scale})`
            }}
          >
            {/* Layer 1: Compound Container Boxes (database / schema / package / procedure) */}
            {liveModel.containers.map((container) => (
              <div
                key={container.id}
                data-testid={`container-box-${container.id}`}
                style={{
                  position: "absolute",
                  left: container.x,
                  top: container.y,
                  width: container.width,
                  height: container.height,
                  border: `1px dashed ${CONTAINER_STYLE_TOKENS.borderColor}`,
                  backgroundColor: CONTAINER_STYLE_TOKENS.bodyBg,
                  borderRadius: 3,
                  pointerEvents: "none"
                }}
              >
                <div
                  style={{
                    height: 20,
                    lineHeight: "20px",
                    padding: "0 8px",
                    backgroundColor: CONTAINER_STYLE_TOKENS.headerBg,
                    color: CONTAINER_STYLE_TOKENS.headerTextColor,
                    fontSize: 11,
                    fontWeight: 600,
                    borderBottom: `1px solid ${CONTAINER_STYLE_TOKENS.borderColor}`,
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                    whiteSpace: "nowrap"
                  }}
                >
                  {container.name}
                </div>
              </div>
            ))}

            {/* Layer 2: SVG Cubic Bézier Edges + 10x7 Arrowheads + Transform Circles */}
            <svg
              data-testid="sqlflow-edges-svg"
              width={liveModel.canvasWidth}
              height={liveModel.canvasHeight}
              style={{
                position: "absolute",
                left: 0,
                top: 0,
                overflow: "visible",
                pointerEvents: "none"
              }}
            >
              {liveModel.edges.map((edge) => {
                const isEdgeHighlighted =
                  hoverHighlightSet.edgeIds.has(edge.id) ||
                  pinnedHighlightSet.edgeIds.has(edge.id);
                const visual = resolveEdgeVisualStyle(edge.type, {
                  highlighted: isEdgeHighlighted,
                  treatAggregateAsDirect
                });
                const meaningfulTransforms = showTransform
                  ? getMeaningfulEdgeTransforms(edge)
                  : [];
                const hasTransforms = meaningfulTransforms.length > 0;

                return (
                  <g
                    key={edge.id}
                    data-testid={`edge-group-${edge.id}`}
                    data-edge-type={edge.type}
                    data-highlighted={isEdgeHighlighted ? "true" : "false"}
                    className={[
                      isEdgeHighlighted ? "highlight" : "",
                      visual.isDashed ? "dash" : ""
                    ]
                      .filter(Boolean)
                      .join(" ")}
                    style={{ pointerEvents: "auto" }}
                  >
                    {/* Wide invisible hit target (`stroke-width="10"`, 04 §6.4 #1) */}
                    <path
                      data-testid={`edge-hit-${edge.id}`}
                      className="hover"
                      d={edge.bezier.d}
                      fill="none"
                      stroke="transparent"
                      strokeWidth={SURFACE_AND_EDGE_TOKENS.edgeHitTargetWidth}
                      style={{ cursor: "pointer" }}
                      onMouseEnter={() => setHoverTarget({ kind: "edge", edgeId: edge.id })}
                      onMouseLeave={() => setHoverTarget(null)}
                      onClick={(e) => {
                        e.stopPropagation();
                        const nextTarget: HighlightTarget = { kind: "edge", edgeId: edge.id };
                        if (isSameHighlightTarget(effectivePin, nextTarget)) {
                          applyPinnedTarget(null);
                          return;
                        }
                        applyPinnedTarget(nextTarget);
                        onEdgeClick?.(edge);
                      }}
                    />

                    {/* Visible Cubic Bézier edge path */}
                    <path
                      data-testid={`edge-path-${edge.id}`}
                      className={visual.isJoin ? "edge join" : "edge"}
                      d={edge.bezier.d}
                      fill="none"
                      stroke={visual.stroke}
                      strokeWidth={visual.strokeWidth}
                      strokeDasharray={visual.strokeDasharray}
                      style={{ pointerEvents: "none" }}
                    />

                    {/* Exact 10 × 7 Arrowhead Polygon (`04 §6.4 #3`) */}
                    <polygon
                      data-testid={`edge-arrow-${edge.id}`}
                      points={edge.arrowPoints}
                      fill={visual.arrowFill}
                      stroke={visual.arrowFill}
                      style={{ pointerEvents: "none" }}
                    />

                    {/* Legacy Join Type Label (`04 §6.4 #2`) */}
                    {visual.isJoin && edge.joinType && (
                      <text
                        x={edge.bezier.mx}
                        y={edge.bezier.my - 6}
                        textAnchor="middle"
                        fontSize={12}
                        fill={visual.stroke}
                        style={{ pointerEvents: "none" }}
                      >
                        {edge.joinType}
                      </text>
                    )}

                    {/* f(x) Transform Expression Badge (`04 §6.4 #4`) */}
                    {hasTransforms && (
                      <circle
                        data-testid={`edge-transform-badge-${edge.id}`}
                        cx={edge.bezier.mx}
                        cy={edge.bezier.my}
                        r={5.5}
                        fill={
                          isEdgeHighlighted || activeTransformEdgeId === edge.id
                            ? "#2563eb"
                            : SURFACE_AND_EDGE_TOKENS.transformCircleDefaultFill
                        }
                        stroke={SURFACE_AND_EDGE_TOKENS.transformCircleStroke}
                        strokeWidth={1.5}
                        style={{ cursor: "pointer" }}
                        onClick={(e) => {
                          e.stopPropagation();
                          setActiveTransformEdgeId((prev) =>
                            prev === edge.id ? null : edge.id
                          );
                        }}
                      >
                        <title>{`f(x): ${meaningfulTransforms.map((t) => t.code).join("; ")}`}</title>
                      </circle>
                    )}
                  </g>
                );
              })}
            </svg>

            {/* Active f(x) Transform Popover (04 §6.4 #4) */}
            {activeTransformEdge && (
              <div
                data-testid="sqlflow-transform-popover"
                style={{
                  position: "absolute",
                  left: activeTransformEdge.bezier.mx + 10,
                  top: Math.max(8, activeTransformEdge.bezier.my - 20),
                  zIndex: Z_INDEX.settingPopover,
                  minWidth: 260,
                  maxWidth: 420,
                  backgroundColor: "#ffffff",
                  border: "1px solid #d9d9d9",
                  borderRadius: 4,
                  boxShadow: "0 4px 12px rgba(0,0,0,0.16)",
                  padding: 8,
                  fontSize: 12
                }}
                onClick={(e) => e.stopPropagation()}
              >
                <div
                  style={{
                    display: "flex",
                    justifyContent: "space-between",
                    alignItems: "center",
                    marginBottom: 6,
                    fontWeight: 600
                  }}
                >
                  <span>{uiLabels.transformPopoverTitle}</span>
                  <button
                    type="button"
                    aria-label="Close transform popover"
                    onClick={() => setActiveTransformEdgeId(null)}
                    style={{
                      border: "none",
                      background: "transparent",
                      cursor: "pointer",
                      fontSize: 14
                    }}
                  >
                    ×
                  </button>
                </div>
                <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 11 }}>
                  <thead>
                    <tr style={{ backgroundColor: "#f5f5f5" }}>
                      <th style={{ textAlign: "left", padding: "4px 6px", border: "1px solid #e8e8e8" }}>
                        {uiLabels.transformTypeHeader}
                      </th>
                      <th style={{ textAlign: "left", padding: "4px 6px", border: "1px solid #e8e8e8" }}>
                        {uiLabels.transformCodeHeader}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {(getMeaningfulEdgeTransforms(activeTransformEdge).length > 0
                      ? getMeaningfulEdgeTransforms(activeTransformEdge)
                      : activeTransformEdge.transforms
                    ).map((tr, idx) => (
                      <tr
                        key={`${tr.type}-${idx}`}
                        onClick={() => onTransformClick?.(tr, activeTransformEdge)}
                        style={{ cursor: "pointer" }}
                      >
                        <td style={{ padding: "4px 6px", border: "1px solid #e8e8e8" }}>
                          {tr.type}
                        </td>
                        <td
                          style={{
                            padding: "4px 6px",
                            border: "1px solid #e8e8e8",
                            fontFamily: "Consolas, Menlo, Monaco, monospace"
                          }}
                        >
                          {tr.code}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {/* Layer 3: HTML Node Cards + PK/UK/FK Column Rows + Stream +/- Buttons */}
            {liveModel.nodes.map((node) => {
              const headerBg = getDboHeaderColor(node.type, { fallbackToTableGreen: true });
              const headerText = getDboHeaderTextColor(node.type);
              const isNodeLocated = effectiveLocatedNodeId === node.id;
              const isNodePinned =
                effectivePin?.kind === "node" && effectivePin.nodeId === node.id;
              const isStreamExpanded = expandedStreamNodes?.has(node.id) ?? false;
              const isColumnListExpanded = expandedColumnNodes.has(node.id);
              const hasCollapsibleColumns =
                !liveModel.tableLevel && node.columns.length > collapsibleColumnLimit;
              const visibleColumns =
                hasCollapsibleColumns && !isColumnListExpanded
                  ? node.columns.slice(0, collapsibleColumnLimit)
                  : node.columns;

              return (
                <div
                  key={node.id}
                  data-testid={`lineage-node-${node.id}`}
                  data-node-id={node.id}
                  data-node-type={node.type}
                  className={[
                    "sqlflow-node-card",
                    node.type,
                    isNodeLocated ? "located" : "",
                    isNodePinned ? "highlight" : ""
                  ]
                    .filter(Boolean)
                    .join(" ")}
                  style={{
                    position: "absolute",
                    left: node.x,
                    top: node.y,
                    width: node.width,
                    backgroundColor: SURFACE_AND_EDGE_TOKENS.nodeBodyBg,
                    border: `1px solid ${headerBg}`,
                    boxShadow: isNodeLocated
                      ? LOCATED_NODE_TOKENS.boxShadow
                      : isNodePinned
                        ? "0 0 0 2px #2563eb"
                        : undefined,
                    outline: isNodeLocated ? LOCATED_NODE_TOKENS.outline : undefined,
                    boxSizing: "border-box"
                  }}
                >
                  {/* Upstream Step-by-Step `+` / `-` Button (`04 §6.3`) */}
                  {node.moreUp && (
                    <button
                      type="button"
                      data-testid={`stream-up-${node.id}`}
                      aria-label={`Expand upstream for ${node.displayTitle}`}
                      onClick={(e) => {
                        e.stopPropagation();
                        onStreamExpandClick?.("upstream", node, isStreamExpanded);
                      }}
                      style={{
                        position: "absolute",
                        left: -24,
                        top: 2,
                        width: 16,
                        height: 16,
                        padding: 0,
                        lineHeight: "14px",
                        textAlign: "center",
                        fontSize: 12,
                        backgroundColor: "#ffffff",
                        border: `1px solid ${SURFACE_AND_EDGE_TOKENS.streamMoreButtonBorder}`,
                        cursor: "pointer"
                      }}
                    >
                      {isStreamExpanded ? "-" : "+"}
                    </button>
                  )}

                  {/* Downstream Step-by-Step `+` / `-` Button (`04 §6.3`) */}
                  {node.moreDown && (
                    <button
                      type="button"
                      data-testid={`stream-down-${node.id}`}
                      aria-label={`Expand downstream for ${node.displayTitle}`}
                      onClick={(e) => {
                        e.stopPropagation();
                        onStreamExpandClick?.("downstream", node, isStreamExpanded);
                      }}
                      style={{
                        position: "absolute",
                        right: -24,
                        top: 2,
                        width: 16,
                        height: 16,
                        padding: 0,
                        lineHeight: "14px",
                        textAlign: "center",
                        fontSize: 12,
                        backgroundColor: "#ffffff",
                        border: `1px solid ${SURFACE_AND_EDGE_TOKENS.streamMoreButtonBorder}`,
                        cursor: "pointer"
                      }}
                    >
                      {isStreamExpanded ? "-" : "+"}
                    </button>
                  )}

                  {/* Draggable Node Header (`height: 22px`, `26px` in tableLevel) */}
                  <div
                    data-testid={`node-header-${node.id}`}
                    title={`${node.tooltipTitle} (${node.type})`}
                    onMouseDown={(e) => {
                      if (e.button !== 0) {
                        return;
                      }
                      e.stopPropagation();
                      didDragNodeRef.current = false;
                      dragNodeRef.current = {
                        nodeId: node.id,
                        startClientX: e.clientX,
                        startClientY: e.clientY,
                        origX: node.x,
                        origY: node.y
                      };
                    }}
                    onMouseEnter={() => setHoverTarget({ kind: "node", nodeId: node.id })}
                    onMouseLeave={() => setHoverTarget(null)}
                    onClick={(e) => {
                      e.stopPropagation();
                      if (didDragNodeRef.current) {
                        didDragNodeRef.current = false;
                        return;
                      }
                      const nextTarget: HighlightTarget = { kind: "node", nodeId: node.id };
                      if (isSameHighlightTarget(effectivePin, nextTarget)) {
                        applyPinnedTarget(null);
                        return;
                      }
                      applyPinnedTarget(nextTarget);
                      onTableNameClick?.(buildOnTableNameClickPayload(node), node);
                    }}
                    style={{
                      height: node.headerHeight,
                      lineHeight: `${node.headerHeight}px`,
                      backgroundColor: headerBg,
                      color: headerText,
                      padding: "0 6px",
                      cursor: "move",
                      overflow: "hidden",
                      whiteSpace: "nowrap",
                      textOverflow: "ellipsis",
                      fontSize: 12
                    }}
                  >
                    <span
                      style={{
                        display: "inline-block",
                        transform: "scale(0.916)",
                        transformOrigin: "left center"
                      }}
                    >
                      {truncateMiddle53(node.displayTitle)}
                    </span>
                  </div>

                  {/* Column Rows (`height: 16px`, `04 §6.3`) */}
                  {!liveModel.tableLevel &&
                    visibleColumns.map((col) => {
                      const isColHovered = hoverHighlightSet.columnIds.has(col.id);
                      const isColPinned = pinnedHighlightSet.columnIds.has(col.id);
                      const rowBg = isColPinned
                        ? SURFACE_AND_EDGE_TOKENS.fieldHighlightBg
                        : isColHovered
                          ? SURFACE_AND_EDGE_TOKENS.fieldHoverBg
                          : SURFACE_AND_EDGE_TOKENS.fieldDefaultBg;

                      return (
                        <div
                          key={col.id}
                          data-testid={`column-row-${col.id}`}
                          data-column-id={col.id}
                          data-hovered={isColHovered ? "true" : "false"}
                          data-pinned={isColPinned ? "true" : "false"}
                          title={col.tooltip ?? col.displayLabel}
                          className={[
                            "sqlflow-field-row",
                            isColHovered ? "mouseover" : "",
                            isColPinned ? "highlight" : ""
                          ]
                            .filter(Boolean)
                            .join(" ")}
                          onMouseEnter={() =>
                            setHoverTarget({ kind: "column", columnId: col.id })
                          }
                          onMouseLeave={() => setHoverTarget(null)}
                          onClick={(e) => {
                            e.stopPropagation();
                            const nextTarget: HighlightTarget = {
                              kind: "column",
                              columnId: col.id
                            };
                            if (isSameHighlightTarget(effectivePin, nextTarget)) {
                              applyPinnedTarget(null);
                              return;
                            }
                            applyPinnedTarget(nextTarget);
                            if (onFieldClick) {
                              const payload = buildOnFieldClickPayload(liveModel, col.id);
                              if (payload) {
                                onFieldClick(payload, col, node);
                              }
                            }
                          }}
                          style={{
                            height: node.rowHeight,
                            lineHeight: `${node.rowHeight}px`,
                            backgroundColor: rowBg,
                            color: SURFACE_AND_EDGE_TOKENS.fieldDefaultText,
                            padding: "0 6px",
                            display: "flex",
                            alignItems: "center",
                            gap: 4,
                            cursor: "pointer",
                            overflow: "hidden",
                            whiteSpace: "nowrap",
                            fontSize: 12
                          }}
                        >
                          {col.primaryKey && (
                            <span
                              data-testid={`badge-pk-${col.id}`}
                              style={{
                                fontSize: 9,
                                fontWeight: 700,
                                color: "#b45309",
                                backgroundColor: "#fef3c7",
                                borderRadius: 2,
                                padding: "0 2px",
                                lineHeight: "12px"
                              }}
                            >
                              PK
                            </span>
                          )}
                          {col.unqiueKey && (
                            <span
                              data-testid={`badge-uk-${col.id}`}
                              style={{
                                fontSize: 9,
                                fontWeight: 700,
                                color: "#1d4ed8",
                                backgroundColor: "#dbeafe",
                                borderRadius: 2,
                                padding: "0 2px",
                                lineHeight: "12px"
                              }}
                            >
                              UK
                            </span>
                          )}
                          {col.foreignKey && (
                            <span
                              data-testid={`badge-fk-${col.id}`}
                              style={{
                                fontSize: 9,
                                fontWeight: 700,
                                color: "#6d28d9",
                                backgroundColor: "#ede9fe",
                                borderRadius: 2,
                                padding: "0 2px",
                                lineHeight: "12px"
                              }}
                            >
                              FK
                            </span>
                          )}
                          <span
                            style={{
                              display: "inline-block",
                              transform: "scale(0.916)",
                              transformOrigin: "left center",
                              fontWeight: col.primaryKey ? 700 : 400
                            }}
                          >
                            {truncateMiddle53(col.displayLabel)}
                          </span>
                        </div>
                      );
                    })}

                  {/* Collapsible Toggle for Large Column Lists */}
                  {hasCollapsibleColumns && (
                    <button
                      type="button"
                      data-testid={`toggle-columns-${node.id}`}
                      onClick={(e) => {
                        e.stopPropagation();
                        setExpandedColumnNodes((prev) => {
                          const next = new Set(prev);
                          if (next.has(node.id)) {
                            next.delete(node.id);
                          } else {
                            next.add(node.id);
                          }
                          return next;
                        });
                      }}
                      style={{
                        width: "100%",
                        height: 18,
                        border: "none",
                        borderTop: "1px solid #eeeeee",
                        backgroundColor: "#fafafa",
                        color: "#1890ff",
                        fontSize: 10,
                        cursor: "pointer"
                      }}
                    >
                      {isColumnListExpanded
                        ? "Collapse columns"
                        : `Show all ${node.columns.length} columns (+${
                            node.columns.length - collapsibleColumnLimit
                          })`}
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Bottom-Right 110 × 110 Minimap (`04 §6.10`) */}
      {!showSummaryGate && showMinimap && (
        <div
          data-testid="sqlflow-minimap"
          onClick={(e) => {
            if (!scrollViewportRef.current) {
              return;
            }
            const rect = e.currentTarget.getBoundingClientRect();
            const clickX = (e.clientX - rect.left) / minimapScale;
            const clickY = (e.clientY - rect.top) / minimapScale;
            scrollViewportRef.current.scrollLeft = Math.max(
              0,
              clickX * scale - scrollState.clientWidth / 2
            );
            scrollViewportRef.current.scrollTop = Math.max(
              0,
              clickY * scale - scrollState.clientHeight / 2
            );
            updateScrollMetrics();
          }}
          style={{
            position: "absolute",
            right: 0,
            bottom: 0,
            width: CANVAS_GEOMETRY_TOKENS.minimapSize,
            height: CANVAS_GEOMETRY_TOKENS.minimapSize,
            backgroundColor: "#f7f7f7",
            border: "1px solid #000000",
            opacity: 0.9,
            zIndex: Z_INDEX.scaleControls,
            overflow: "hidden",
            cursor: "pointer"
          }}
        >
          {liveModel.nodes.map((node) => (
            <div
              key={node.id}
              style={{
                position: "absolute",
                left: node.x * minimapScale,
                top: node.y * minimapScale,
                width: Math.max(2, node.width * minimapScale),
                height: Math.max(2, node.height * minimapScale),
                backgroundColor: SURFACE_AND_EDGE_TOKENS.minimapNodeBg,
                border: `1px solid ${SURFACE_AND_EDGE_TOKENS.minimapNodeBorder}`,
                boxSizing: "border-box"
              }}
            />
          ))}
          <div
            data-testid="sqlflow-minimap-lens"
            style={{
              position: "absolute",
              left: (scrollState.scrollLeft / scale) * minimapScale,
              top: (scrollState.scrollTop / scale) * minimapScale,
              width: Math.min(
                CANVAS_GEOMETRY_TOKENS.minimapSize,
                (scrollState.clientWidth / scale) * minimapScale
              ),
              height: Math.min(
                CANVAS_GEOMETRY_TOKENS.minimapSize,
                (scrollState.clientHeight / scale) * minimapScale
              ),
              border: `1px solid ${SURFACE_AND_EDGE_TOKENS.minimapLensBorder}`,
              backgroundColor: "rgba(24, 144, 255, 0.08)",
              pointerEvents: "none",
              boxSizing: "border-box"
            }}
          />
        </div>
      )}
    </div>
  );
};
