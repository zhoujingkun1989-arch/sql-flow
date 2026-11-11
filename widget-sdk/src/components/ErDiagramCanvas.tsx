/**
 * Interactive Entity-Relationship Diagram Canvas (`<ErDiagramCanvas />`)
 * for `widget.visualizeER(...)` & `widget.visualizeERJSON(...)` (`04 §7.2`, `§11.4`).
 */

import React, { useEffect, useMemo, useState } from "react";
import {
  type CanvasColumnRow,
  type CanvasEdge,
  type CanvasGraphModel,
  type CanvasNode,
  SURFACE_AND_EDGE_TOKENS,
  buildArrowheadPoints,
  buildCubicBezierEdgePath,
  buildOnFieldClickPayload,
  buildOnTableNameClickPayload,
  moveCanvasNode,
  traceLineageHighlight
} from "@sqlflow/ui-core";
import type { WidgetTranslations } from "../i18n.js";
import { buildMermaidErLiveUrl } from "../utils/graphOperations.js";

export interface ErDiagramCanvasProps {
  model: CanvasGraphModel | null;
  scale: number;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onResetZoom: () => void;
  onFitZoom: () => void;
  tableMovable?: boolean;
  i18n: WidgetTranslations;
  onNodeClick?: (node: CanvasNode, payload: ReturnType<typeof buildOnTableNameClickPayload>) => void;
  onColumnClick?: (
    col: CanvasColumnRow,
    node: CanvasNode,
    payload: NonNullable<ReturnType<typeof buildOnFieldClickPayload>>
  ) => void;
  onEdgeClick?: (edge: CanvasEdge) => void;
}

export const ErDiagramCanvas: React.FC<ErDiagramCanvasProps> = ({
  model,
  scale,
  onZoomIn,
  onZoomOut,
  onResetZoom,
  onFitZoom,
  tableMovable = true,
  i18n,
  onNodeClick,
  onColumnClick,
  onEdgeClick
}) => {
  const [prevModel, setPrevModel] = useState<CanvasGraphModel | null>(model);
  const [liveModel, setLiveModel] = useState<CanvasGraphModel | null>(model);
  const [selectedTarget, setSelectedTarget] = useState<
    { kind: "node"; nodeId: string } | { kind: "column"; columnId: string } | null
  >(null);
  const [dragState, setDragState] = useState<{
    nodeId: string;
    startX: number;
    startY: number;
    origX: number;
    origY: number;
  } | null>(null);

  if (prevModel !== model) {
    setPrevModel(model);
    setLiveModel(model);
    setSelectedTarget(null);
  }

  useEffect(() => {
    if (!dragState) {
      return;
    }
    const onMove = (e: MouseEvent) => {
      const dx = (e.clientX - dragState.startX) / scale;
      const dy = (e.clientY - dragState.startY) / scale;
      setLiveModel((prev) =>
        prev
          ? moveCanvasNode(prev, dragState.nodeId, dragState.origX + dx, dragState.origY + dy)
          : prev
      );
    };
    const onUp = () => setDragState(null);
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, [dragState, scale]);

  const highlightSet = useMemo(
    () =>
      liveModel
        ? traceLineageHighlight(liveModel, selectedTarget)
        : {
            nodeIds: new Set<string>(),
            columnIds: new Set<string>(),
            edgeIds: new Set<string>(),
            coordinates: []
          },
    [liveModel, selectedTarget]
  );

  if (!liveModel || liveModel.nodes.length === 0) {
    return (
      <div
        data-testid="sqlflow-er-empty"
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          padding: 24,
          color: "var(--sf-text-muted)",
          textAlign: "center"
        }}
      >
        {i18n.emptyEr}
      </div>
    );
  }

  return (
    <div
      data-testid="sqlflow-er-canvas"
      style={{
        position: "relative",
        width: "100%",
        height: "100%",
        overflow: "hidden",
        backgroundColor: "var(--sf-bg)"
      }}
    >
      {/* Top-Left ER Actions & No-Relations Notice (`04 §7.2`) */}
      <div
        style={{
          position: "absolute",
          left: 16,
          top: 12,
          zIndex: 12,
          display: "flex",
          alignItems: "center",
          gap: 8
        }}
      >
        <a
          data-testid="er-mermaid-live-link"
          href={buildMermaidErLiveUrl(liveModel)}
          target="_blank"
          rel="noopener noreferrer"
          style={{
            display: "inline-flex",
            alignItems: "center",
            padding: "4px 10px",
            borderRadius: 4,
            backgroundColor: "#38c172",
            color: "#ffffff",
            fontSize: 11,
            fontWeight: 600,
            textDecoration: "none",
            boxShadow: "0 1px 3px rgba(0,0,0,0.14)"
          }}
        >
          {i18n.openMermaidLive} ↗
        </a>
        {liveModel.edges.length === 0 && (
          <span
            data-testid="er-no-relations-banner"
            style={{
              padding: "4px 10px",
              borderRadius: 4,
              backgroundColor: "#fffbeb",
              border: "1px solid #fcd34d",
              color: "#92400e",
              fontSize: 11
            }}
          >
            {i18n.noErRelationsBanner}
          </span>
        )}
      </div>

      {/* Top-Right Zoom Controls */}
      <div
        style={{
          position: "absolute",
          right: 20,
          top: 20,
          zIndex: 12,
          display: "flex",
          flexDirection: "column",
          gap: 5,
          backgroundColor: "#eeeeeea6",
          padding: 4,
          borderRadius: 5
        }}
      >
        <button type="button" data-testid="er-zoom-in" onClick={onZoomIn} style={{ width: 26, height: 24, cursor: "pointer" }}>
          +
        </button>
        <button type="button" data-testid="er-zoom-out" onClick={onZoomOut} style={{ width: 26, height: 24, cursor: "pointer" }}>
          -
        </button>
        <button type="button" data-testid="er-zoom-reset" onClick={onResetZoom} style={{ width: 26, height: 24, fontSize: 10, cursor: "pointer" }}>
          1:1
        </button>
        <button type="button" data-testid="er-zoom-fit" onClick={onFitZoom} style={{ width: 26, height: 24, fontSize: 10, cursor: "pointer" }}>
          Fit
        </button>
      </div>

      <div
        onClick={(e) => {
          if (e.target === e.currentTarget) {
            setSelectedTarget(null);
          }
        }}
        style={{
          width: "100%",
          height: "100%",
          overflow: "auto",
          position: "relative"
        }}
      >
        <div
          data-testid="sqlflow-er-surface"
          data-scale={scale}
          style={{
            position: "relative",
            width: Math.max(800, liveModel.canvasWidth + 80),
            height: Math.max(540, liveModel.canvasHeight + 80),
            transformOrigin: "40px 60px",
            transform: `scale(${scale})`
          }}
        >
          {/* SVG PK <-> FK Edges */}
          <svg
            width={Math.max(800, liveModel.canvasWidth + 80)}
            height={Math.max(540, liveModel.canvasHeight + 80)}
            style={{ position: "absolute", left: 0, top: 0, overflow: "visible", pointerEvents: "none" }}
          >
            {liveModel.edges.map((edge) => {
              const srcNode = liveModel.nodeById.get(edge.sourceNodeId);
              const tgtNode = liveModel.nodeById.get(edge.targetNodeId);
              if (!srcNode || !tgtNode) {
                return null;
              }
              const cardWidthSrc = Math.max(180, srcNode.width + 24);
              const x1 = srcNode.x + cardWidthSrc;
              const y1 =
                srcNode.y +
                34 +
                (edge.sourceColumnIndex !== undefined ? edge.sourceColumnIndex * 18 + 9 : 0);
              const x2 = tgtNode.x;
              const y2 =
                tgtNode.y +
                34 +
                (edge.targetColumnIndex !== undefined ? edge.targetColumnIndex * 18 + 9 : 0);
              const bezier = buildCubicBezierEdgePath(x1, y1, x2, y2);
              const arrow = buildArrowheadPoints(x2, y2);
              const isHighlighted = highlightSet.edgeIds.has(edge.id);

              return (
                <g
                  key={edge.id}
                  data-testid={`er-edge-${edge.id}`}
                  style={{ pointerEvents: "auto", cursor: "pointer" }}
                  onClick={() => onEdgeClick?.(edge)}
                >
                  <path d={bezier.d} fill="none" stroke="transparent" strokeWidth={10} />
                  <path
                    d={bezier.d}
                    fill="none"
                    stroke={
                      isHighlighted
                        ? SURFACE_AND_EDGE_TOKENS.edgeHighlightStroke
                        : "#64748b"
                    }
                    strokeWidth={isHighlighted ? 1.8 : 1.2}
                  />
                  <polygon
                    points={arrow}
                    fill={isHighlighted ? "#000000" : "#64748b"}
                  />
                </g>
              );
            })}
          </svg>

          {/* ER Table Cards (`#38c172` top bar + PK / UK `unqiueKey` / FK rows) */}
          {liveModel.nodes.map((node) => {
            const cardWidth = Math.max(180, node.width + 24);
            return (
              <div
                key={node.id}
                data-testid={`er-node-${node.id}`}
                className="sf-er-card"
                style={{
                  left: node.x,
                  top: node.y,
                  width: cardWidth
                }}
              >
                <div
                  className="sf-er-header"
                  style={{ cursor: tableMovable ? "move" : "pointer" }}
                  onMouseDown={(e) => {
                    if (!tableMovable || e.button !== 0) {
                      return;
                    }
                    e.stopPropagation();
                    setDragState({
                      nodeId: node.id,
                      startX: e.clientX,
                      startY: e.clientY,
                      origX: node.x,
                      origY: node.y
                    });
                  }}
                  onClick={(e) => {
                    e.stopPropagation();
                    setSelectedTarget({ kind: "node", nodeId: node.id });
                    onNodeClick?.(node, buildOnTableNameClickPayload(node));
                  }}
                >
                  {node.displayTitle}
                </div>

                {node.columns.map((col) => {
                  const isColActive = highlightSet.columnIds.has(col.id);
                  return (
                    <div
                      key={col.id}
                      data-testid={`er-col-${col.id}`}
                      className={`sf-er-row${isColActive ? " sf-er-active" : ""}`}
                      onClick={(e) => {
                        e.stopPropagation();
                        setSelectedTarget({ kind: "column", columnId: col.id });
                        const payload = buildOnFieldClickPayload(liveModel, col.id);
                        if (payload) {
                          onColumnClick?.(col, node, payload);
                        }
                      }}
                    >
                      <span style={{ display: "inline-flex", alignItems: "center", gap: 4, overflow: "hidden" }}>
                        {col.primaryKey && (
                          <span
                            data-testid={`er-pk-${col.id}`}
                            style={{
                              fontSize: 9,
                              fontWeight: 700,
                              color: "#b45309",
                              backgroundColor: "#fef3c7",
                              padding: "0 3px",
                              borderRadius: 2
                            }}
                          >
                            PK
                          </span>
                        )}
                        {col.unqiueKey && (
                          <span
                            data-testid={`er-uk-${col.id}`}
                            style={{
                              fontSize: 9,
                              fontWeight: 700,
                              color: "#1d4ed8",
                              backgroundColor: "#dbeafe",
                              padding: "0 3px",
                              borderRadius: 2
                            }}
                          >
                            UK
                          </span>
                        )}
                        {col.foreignKey && (
                          <span
                            data-testid={`er-fk-${col.id}`}
                            style={{
                              fontSize: 9,
                              fontWeight: 700,
                              color: "#6d28d9",
                              backgroundColor: "#ede9fe",
                              padding: "0 3px",
                              borderRadius: 2
                            }}
                          >
                            FK
                          </span>
                        )}
                        <span style={{ fontWeight: col.primaryKey ? 700 : 400 }}>
                          {col.name}
                        </span>
                      </span>
                      <span style={{ color: SURFACE_AND_EDGE_TOKENS.erTypeText, fontSize: 10 }}>
                        {col.dataType ?? ""}
                      </span>
                    </div>
                  );
                })}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};
