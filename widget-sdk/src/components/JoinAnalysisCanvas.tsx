/**
 * Interactive Join Analysis V2 Canvas (`<JoinAnalysisCanvas />`)
 * consuming unwrapped `JoinAnalysisGraph` (`schemaVersion: "join-analysis.v1"`)
 * for `widget.visualizeJoin(...)` & `widget.visualizeJoinJSON(...)` (`04 §7.4`, `OBL-114`, `OBL-119`).
 */

import React, { useMemo, useState } from "react";
import * as dagreNs from "@dagrejs/dagre";
import {
  type Coordinate,
  JOIN_DIAGNOSTIC_DESCRIPTORS,
  JOIN_OPERATOR_COLORS,
  type JoinAnalysisGraph,
  type JoinDiagnostic,
  type LayoutRankDir,
  buildArrowheadPoints,
  buildCubicBezierEdgePath
} from "@sqlflow/ui-core";
import type { WidgetTranslations } from "../i18n.js";

const dagre =
  (dagreNs as unknown as { default?: typeof dagreNs }).default ?? dagreNs;

export interface JoinAnalysisCanvasProps {
  graph: JoinAnalysisGraph | null;
  rankdir: LayoutRankDir;
  scale: number;
  i18n: WidgetTranslations;
  onHighlightCoordinates?: (coords: Coordinate[]) => void;
}

interface PositionedJoinNode {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  raw: JoinAnalysisGraph["nodes"][number];
}

export const JoinAnalysisCanvas: React.FC<JoinAnalysisCanvasProps> = ({
  graph,
  rankdir,
  scale,
  i18n,
  onHighlightCoordinates
}) => {
  const [selectedDiagId, setSelectedDiagId] = useState<string | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);

  const layoutData = useMemo(() => {
    if (!graph || !Array.isArray(graph.nodes) || graph.nodes.length === 0) {
      return null;
    }

    const g = new dagre.graphlib.Graph({ directed: true });
    g.setGraph({
      rankdir,
      nodesep: 38,
      ranksep: 96,
      marginx: 40,
      marginy: 56
    });
    g.setDefaultEdgeLabel(() => ({}));

    const dimsById = new Map<string, { width: number; height: number }>();
    for (const n of graph.nodes) {
      const isJoin = n.type === "join" || Boolean(n.data?.join);
      const predCount = Math.min(4, n.data?.join?.predicates?.length ?? 0);
      const width = isJoin ? 230 : 185;
      const height = isJoin ? 58 + predCount * 20 : 58;
      dimsById.set(n.id, { width, height });
      g.setNode(n.id, { width, height });
    }

    for (const e of graph.edges ?? []) {
      if (dimsById.has(e.source) && dimsById.has(e.target) && e.source !== e.target) {
        g.setEdge(e.source, e.target);
      }
    }

    dagre.layout(g);

    const nodes: PositionedJoinNode[] = graph.nodes.map((n, idx) => {
      const dims = dimsById.get(n.id) ?? { width: 190, height: 60 };
      const laid = g.node(n.id);
      const x =
        n.position && Number.isFinite(n.position.x)
          ? n.position.x
          : laid
            ? Math.round(laid.x - dims.width / 2)
            : 40 + idx * 240;
      const y =
        n.position && Number.isFinite(n.position.y)
          ? n.position.y
          : laid
            ? Math.round(laid.y - dims.height / 2)
            : 80;
      return {
        id: n.id,
        x: Math.max(24, x),
        y: Math.max(48, y),
        width: dims.width,
        height: dims.height,
        raw: n
      };
    });

    const byId = new Map(nodes.map((n) => [n.id, n]));
    let maxW = 840;
    let maxH = 520;
    for (const n of nodes) {
      maxW = Math.max(maxW, n.x + n.width + 80);
      maxH = Math.max(maxH, n.y + n.height + 80);
    }

    return { nodes, byId, canvasWidth: maxW, canvasHeight: maxH };
  }, [graph, rankdir]);

  const activeDiagnostic = useMemo(
    () => graph?.diagnostics?.find((d) => d.id === selectedDiagId) ?? null,
    [graph, selectedDiagId]
  );

  const highlightedNodeIds = useMemo(() => {
    const ids = new Set<string>();
    if (selectedNodeId) {
      ids.add(selectedNodeId);
    }
    for (const nid of activeDiagnostic?.evidenceNodeIds ?? []) {
      ids.add(nid);
    }
    return ids;
  }, [selectedNodeId, activeDiagnostic]);

  const handleSelectDiagnostic = (diag: JoinDiagnostic) => {
    const next = selectedDiagId === diag.id ? null : diag.id;
    setSelectedDiagId(next);
    if (next && diag.fragmentIds && graph?.fragments) {
      const coords: Coordinate[] = [];
      for (const fid of diag.fragmentIds) {
        const frag = graph.fragments.find((f) => f.fragmentId === fid);
        if (frag?.sourceRange) {
          coords.push({
            x: frag.sourceRange.startLine,
            y: frag.sourceRange.startColumn,
            x1: frag.sourceRange.endLine,
            y1: frag.sourceRange.endColumn
          });
        }
      }
      onHighlightCoordinates?.(coords);
    } else {
      onHighlightCoordinates?.([]);
    }
  };

  if (!graph || !layoutData) {
    return (
      <div
        data-testid="sqlflow-join-empty"
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          padding: 24,
          color: "var(--sf-text-muted)"
        }}
      >
        {i18n.emptyJoin}
      </div>
    );
  }

  return (
    <div
      data-testid="sqlflow-join-canvas"
      style={{
        position: "relative",
        width: "100%",
        height: "100%",
        display: "flex",
        flexDirection: "column",
        backgroundColor: "var(--sf-bg)",
        overflow: "hidden"
      }}
    >
      {/* Top Join Diagnostics Inspector Bar (`04 §7.4`) */}
      <div
        data-testid="join-diagnostics-bar"
        style={{
          padding: "6px 12px",
          backgroundColor: "var(--sf-surface)",
          borderBottom: "1px solid var(--sf-border)",
          display: "flex",
          alignItems: "center",
          gap: 8,
          flexWrap: "wrap",
          zIndex: 10
        }}
      >
        <span style={{ fontWeight: 700, fontSize: 11 }}>{i18n.joinDiagnostics}:</span>
        {(graph.diagnostics ?? []).length === 0 ? (
          <span style={{ fontSize: 11, color: "#059669" }}>{i18n.noDiagnostics}</span>
        ) : (
          graph.diagnostics.map((diag) => {
            const desc = JOIN_DIAGNOSTIC_DESCRIPTORS.find((d) => d.code === diag.code);
            const isSelected = selectedDiagId === diag.id;
            const badgeBg =
              diag.severity === "block"
                ? "#fef2f2"
                : diag.severity === "warn"
                  ? "#fffbeb"
                  : "#eff6ff";
            const badgeBorder =
              diag.severity === "block"
                ? "#ef4444"
                : diag.severity === "warn"
                  ? "#f59e0b"
                  : "#3b82f6";
            const badgeFg =
              diag.severity === "block"
                ? "#991b1b"
                : diag.severity === "warn"
                  ? "#92400e"
                  : "#1e40af";
            return (
              <button
                key={diag.id}
                type="button"
                data-testid={`join-diagnostic-${diag.code}`}
                title={diag.message}
                onClick={() => handleSelectDiagnostic(diag)}
                style={{
                  padding: "2px 8px",
                  borderRadius: 12,
                  border: `${isSelected ? 2 : 1}px solid ${badgeBorder}`,
                  backgroundColor: badgeBg,
                  color: badgeFg,
                  fontSize: 11,
                  fontWeight: 600,
                  cursor: "pointer"
                }}
              >
                {desc?.label ?? diag.code}: {diag.message}
              </button>
            );
          })
        )}
      </div>

      {/* Scrollable Join Flow Surface */}
      <div style={{ flex: 1, overflow: "auto", position: "relative" }}>
        <div
          data-testid="sqlflow-join-surface"
          data-scale={scale}
          style={{
            position: "relative",
            width: layoutData.canvasWidth,
            height: layoutData.canvasHeight,
            transformOrigin: "40px 60px",
            transform: `scale(${scale})`
          }}
        >
          <svg
            width={layoutData.canvasWidth}
            height={layoutData.canvasHeight}
            style={{ position: "absolute", left: 0, top: 0, overflow: "visible", pointerEvents: "none" }}
          >
            {(graph.edges ?? []).map((edge) => {
              const src = layoutData.byId.get(edge.source);
              const tgt = layoutData.byId.get(edge.target);
              if (!src || !tgt) {
                return null;
              }
              const x1 = src.x + src.width;
              const y1 = src.y + src.height / 2;
              const x2 = tgt.x;
              const y2 = tgt.y + tgt.height / 2;
              const bezier = buildCubicBezierEdgePath(x1, y1, x2, y2);
              const arrow = buildArrowheadPoints(x2, y2);
              return (
                <g key={edge.id} data-testid={`join-edge-${edge.id}`}>
                  <path
                    d={bezier.d}
                    fill="none"
                    stroke="#64748b"
                    strokeWidth={1.5}
                    strokeDasharray={edge.data?.dashed ? "4 2" : undefined}
                  />
                  <polygon points={arrow} fill="#64748b" />
                </g>
              );
            })}
          </svg>

          {layoutData.nodes.map((node) => {
            const joinInfo = node.raw.data?.join;
            const tableInfo = node.raw.data?.table;
            const isHighlighted = highlightedNodeIds.has(node.id);
            const joinColor = joinInfo
              ? JOIN_OPERATOR_COLORS[
                  joinInfo.joinType as keyof typeof JOIN_OPERATOR_COLORS
                ] ?? JOIN_OPERATOR_COLORS.DEFAULT
              : "#0c4a6e";

            return (
              <div
                key={node.id}
                data-testid={`join-node-${node.id}`}
                onClick={() => {
                  setSelectedNodeId((prev) => (prev === node.id ? null : node.id));
                  const sr = joinInfo?.sourceRange;
                  if (sr) {
                    onHighlightCoordinates?.([
                      {
                        x: sr.startLine,
                        y: sr.startColumn,
                        x1: sr.endLine,
                        y1: sr.endColumn
                      }
                    ]);
                  }
                }}
                style={{
                  position: "absolute",
                  left: node.x,
                  top: node.y,
                  width: node.width,
                  minHeight: node.height,
                  backgroundColor: "var(--sf-surface)",
                  border: `1px solid ${isHighlighted ? "#ef4444" : "#cbd5e1"}`,
                  borderLeft: `4px solid ${joinColor}`,
                  borderRadius: 6,
                  padding: "7px 9px",
                  boxShadow: isHighlighted
                    ? "0 0 0 2px rgba(239,68,68,0.28)"
                    : "0 2px 5px rgba(0,0,0,0.08)",
                  cursor: "pointer"
                }}
              >
                {joinInfo ? (
                  <>
                    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                      <span style={{ fontWeight: 700, fontSize: 11, color: joinColor }}>
                        {joinInfo.joinType} JOIN
                      </span>
                      <span
                        style={{
                          fontSize: 9,
                          padding: "1px 5px",
                          borderRadius: 3,
                          backgroundColor: "#eef2ff",
                          color: "#4f46e5",
                          fontWeight: 600
                        }}
                      >
                        {joinInfo.clause}
                      </span>
                    </div>
                    {joinInfo.cardinalityHint && (
                      <div style={{ fontSize: 10, color: "#6b7280", marginTop: 2 }}>
                        Cardinality: {joinInfo.cardinalityHint}
                      </div>
                    )}
                    <div style={{ marginTop: 5, display: "flex", flexDirection: "column", gap: 3 }}>
                      {(joinInfo.predicates ?? []).slice(0, 4).map((pred) => (
                        <code
                          key={pred.predicateId}
                          style={{
                            fontSize: 10,
                            padding: "2px 4px",
                            borderRadius: 3,
                            backgroundColor: "#f3f4f6",
                            color: "#111827",
                            overflow: "hidden",
                            textOverflow: "ellipsis",
                            whiteSpace: "nowrap"
                          }}
                        >
                          {pred.raw}
                        </code>
                      ))}
                    </div>
                  </>
                ) : (
                  <>
                    <div style={{ fontWeight: 700, fontSize: 12, color: "#0c4a6e" }}>
                      {tableInfo?.name ?? node.raw.data?.label ?? node.id}
                    </div>
                    {tableInfo?.alias && (
                      <div style={{ fontSize: 10, color: "#6b7280", marginTop: 2 }}>
                        Alias: {tableInfo.alias}
                      </div>
                    )}
                    {node.raw.data?.queryBlockId && (
                      <div style={{ fontSize: 9, color: "#6366f1", marginTop: 3 }}>
                        Scope: {node.raw.data.queryBlockId}
                      </div>
                    )}
                  </>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};
