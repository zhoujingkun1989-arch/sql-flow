/**
 * Interactive Routine Call Graph Canvas (`<CallGraphCanvas />`)
 * for `widget.visualizeCGJSON(...)` (`04 §7.3`, `§11.4`).
 */

import React, { useMemo } from "react";
import * as dagreNs from "@dagrejs/dagre";
import {
  CALL_GRAPH_PALETTE,
  type CallGraphNode,
  type CallGraphPayload,
  type LayoutRankDir,
  buildArrowheadPoints,
  buildCubicBezierEdgePath
} from "@sqlflow/ui-core";
import type { WidgetTranslations } from "../i18n.js";

const dagre =
  (dagreNs as unknown as { default?: typeof dagreNs }).default ?? dagreNs;

export interface CallGraphCanvasProps {
  payload: CallGraphPayload | null;
  rankdir: LayoutRankDir;
  scale: number;
  i18n: WidgetTranslations;
}

function getKindShortBadge(kind: string): string {
  const upper = (kind || "PROCEDURE").toUpperCase();
  if (upper.startsWith("NESTED_P")) return "NP";
  if (upper.startsWith("NESTED_F")) return "NF";
  if (upper.startsWith("TYPE")) return "TM";
  if (upper.startsWith("FUNC")) return "F";
  if (upper.startsWith("TRIG")) return "T";
  if (upper.startsWith("ANON")) return "A";
  return "P";
}

export const CallGraphCanvas: React.FC<CallGraphCanvasProps> = ({
  payload,
  rankdir,
  scale,
  i18n
}) => {
  const laidOut = useMemo(() => {
    const cg = payload?.callGraph;
    if (!cg || !Array.isArray(cg.nodes) || cg.nodes.length === 0) {
      return null;
    }

    const g = new dagre.graphlib.Graph({ directed: true });
    g.setGraph({
      rankdir,
      ranksep: 120,
      nodesep: 30,
      edgesep: 10,
      marginx: 40,
      marginy: 40
    });
    g.setDefaultEdgeLabel(() => ({}));

    const nodeMeta = new Map<
      string,
      { width: number; height: number; node: CallGraphNode }
    >();

    for (const n of cg.nodes) {
      const accesses = n.tableAccesses ?? [];
      const maxAccessLen = accesses.reduce(
        (acc, a) => Math.max(acc, a.table.length * 7 + 44),
        0
      );
      const pkgWidth = n.package ? n.package.length * 6.5 + 36 : 0;
      const width = Math.max(30 + n.name.length * 7 + 20, pkgWidth, maxAccessLen, 130);
      const height =
        28 +
        (n.package ? 16 : 0) +
        (accesses.length > 0 ? 4 + accesses.length * 17 : 0) +
        6;
      nodeMeta.set(n.id, { width, height, node: n });
      g.setNode(n.id, { width, height });
    }

    for (const e of cg.edges ?? []) {
      if (nodeMeta.has(e.source) && nodeMeta.has(e.target) && e.source !== e.target) {
        g.setEdge(e.source, e.target);
      }
    }

    dagre.layout(g);

    const nodes = cg.nodes.map((n, idx) => {
      const meta = nodeMeta.get(n.id)!;
      const pos = g.node(n.id);
      const x = pos ? Math.round(pos.x - meta.width / 2) : 40 + idx * 220;
      const y = pos ? Math.round(pos.y - meta.height / 2) : 60;
      return {
        id: n.id,
        x: Math.max(20, x),
        y: Math.max(20, y),
        width: meta.width,
        height: meta.height,
        raw: n
      };
    });

    const byId = new Map(nodes.map((n) => [n.id, n]));
    let canvasWidth = 760;
    let canvasHeight = 460;
    for (const n of nodes) {
      canvasWidth = Math.max(canvasWidth, n.x + n.width + 80);
      canvasHeight = Math.max(canvasHeight, n.y + n.height + 80);
    }

    return { nodes, byId, edges: cg.edges ?? [], canvasWidth, canvasHeight };
  }, [payload, rankdir]);

  if (!laidOut) {
    return (
      <div
        data-testid="sqlflow-callgraph-empty"
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          color: "var(--sf-text-muted)"
        }}
      >
        {i18n.emptyCallGraph}
      </div>
    );
  }

  return (
    <div
      data-testid="sqlflow-callgraph-canvas"
      style={{
        width: "100%",
        height: "100%",
        overflow: "auto",
        backgroundColor: CALL_GRAPH_PALETTE.canvasBg,
        position: "relative"
      }}
    >
      <div
        data-testid="sqlflow-callgraph-surface"
        data-scale={scale}
        style={{
          position: "relative",
          width: laidOut.canvasWidth,
          height: laidOut.canvasHeight,
          transformOrigin: "40px 60px",
          transform: `scale(${scale})`
        }}
      >
        <svg
          width={laidOut.canvasWidth}
          height={laidOut.canvasHeight}
          style={{ position: "absolute", left: 0, top: 0, overflow: "visible", pointerEvents: "none" }}
        >
          {laidOut.edges.map((edge, idx) => {
            const src = laidOut.byId.get(edge.source);
            const tgt = laidOut.byId.get(edge.target);
            if (!src || !tgt) {
              return null;
            }
            const x1 = src.x + src.width;
            const y1 = src.y + 14;
            const x2 = tgt.x;
            const y2 = tgt.y + 14;
            const bezier = buildCubicBezierEdgePath(x1, y1, x2, y2);
            const arrow = buildArrowheadPoints(x2, y2);
            return (
              <g key={edge.id ?? `${edge.source}->${edge.target}_${idx}`} data-testid={`cg-edge-${idx}`}>
                <path
                  d={bezier.d}
                  fill="none"
                  stroke={CALL_GRAPH_PALETTE.edgeStroke}
                  strokeWidth={CALL_GRAPH_PALETTE.edgeStrokeWidth}
                />
                <polygon points={arrow} fill={CALL_GRAPH_PALETTE.edgeStroke} />
              </g>
            );
          })}
        </svg>

        {laidOut.nodes.map((node) => {
          const kindKey = (
            node.raw.kind?.toUpperCase() ?? "PROCEDURE"
          ) as keyof typeof CALL_GRAPH_PALETTE.routineKindColors;
          const badgeColor =
            CALL_GRAPH_PALETTE.routineKindColors[kindKey] ??
            CALL_GRAPH_PALETTE.routineKindColors.PROCEDURE;
          const accesses = node.raw.tableAccesses ?? [];

          return (
            <div
              key={node.id}
              data-testid={`cg-node-${node.id}`}
              style={{
                position: "absolute",
                left: node.x,
                top: node.y,
                width: node.width,
                minHeight: node.height,
                backgroundColor: "#ffffff",
                border: `1px ${node.raw.isExternal ? "dashed" : "solid"} #94a3b8`,
                borderRadius: 5,
                boxShadow: "0 2px 5px rgba(15,23,42,0.08)",
                overflow: "hidden"
              }}
            >
              <div
                style={{
                  height: 28,
                  padding: "0 8px",
                  display: "flex",
                  alignItems: "center",
                  gap: 6,
                  borderBottom: accesses.length > 0 || node.raw.package ? "1px solid #e2e8f0" : "none"
                }}
              >
                <span
                  style={{
                    backgroundColor: badgeColor,
                    color: "#ffffff",
                    fontSize: 9,
                    fontWeight: 700,
                    padding: "1px 4px",
                    borderRadius: 3
                  }}
                >
                  {getKindShortBadge(node.raw.kind)}
                </span>
                <span style={{ fontWeight: 600, fontSize: 12, color: "#0f172a" }}>
                  {node.raw.name}
                </span>
              </div>

              {node.raw.package && (
                <div style={{ padding: "1px 8px", fontSize: 10, color: "#94a3b8" }}>
                  pkg: {node.raw.package}
                </div>
              )}

              {accesses.length > 0 && (
                <div style={{ padding: "3px 8px 5px", display: "flex", flexDirection: "column", gap: 2 }}>
                  {accesses.map((acc) => {
                    const pillColor =
                      CALL_GRAPH_PALETTE.tableAccessColors[acc.access] ?? "#22c55e";
                    const pillLabel =
                      acc.access === "READ_WRITE" ? "RW" : acc.access === "WRITE" ? "W" : "R";
                    return (
                      <div
                        key={acc.table}
                        style={{
                          display: "flex",
                          justifyContent: "space-between",
                          alignItems: "center",
                          fontSize: 10
                        }}
                      >
                        <span style={{ color: "#334155" }}>{acc.table}</span>
                        <span
                          style={{
                            backgroundColor: pillColor,
                            color: "#ffffff",
                            fontWeight: 700,
                            fontSize: 9,
                            padding: "0 4px",
                            borderRadius: 3
                          }}
                        >
                          {pillLabel}
                        </span>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};
