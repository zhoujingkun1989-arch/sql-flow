import React, { useMemo, useRef } from "react";
import * as dagreNs from "@dagrejs/dagre";
import {
  CALL_GRAPH_PALETTE,
  type CallGraphEdge,
  type CallGraphNode
} from "@sqlflow/ui-core";
import { Download, RefreshCw } from "lucide-react";
import { useI18n } from "../../i18n/I18nContext";
import { useApp } from "../../state/AppContext";
import {
  captureHighDpiClientPng,
  extractDataFlowFromAny,
  triggerBrowserDownload
} from "../../utils/exporters";

const dagre =
  (dagreNs as unknown as { default?: typeof dagreNs }).default ?? dagreNs;

const CALLABLE_TYPES = new Set([
  "procedure",
  "function",
  "trigger",
  "package",
  "macro",
  "method",
  "routine"
]);

interface LaidOutRoutine extends CallGraphNode {
  x: number;
  y: number;
  width: number;
  height: number;
}

export const CallGraphView: React.FC = () => {
  const { locale } = useI18n();
  const isCn = locale === "cn";
  const {
    callGraphData,
    lineageData,
    dialect,
    visualizeCallGraph,
    setEditorPinnedCoords,
    showToast
  } = useApp();

  const containerRef = useRef<HTMLDivElement | null>(null);

  const { laidOutNodes, edges, canvasWidth, canvasHeight } = useMemo(() => {
    const raw = callGraphData ?? lineageData;
    let nodes: CallGraphNode[] = [];
    let callEdges: CallGraphEdge[] = [];

    if (
      raw &&
      "callGraph" in raw &&
      raw.callGraph &&
      typeof raw.callGraph === "object"
    ) {
      const cg = raw.callGraph as { nodes?: CallGraphNode[]; edges?: CallGraphEdge[] };
      nodes = cg.nodes ?? [];
      callEdges = cg.edges ?? [];
    } else if (raw) {
      const df = extractDataFlowFromAny(raw);
      const nonContainerObjs = df.dbobjs.filter(
        (o) => o.type !== "database" && o.type !== "schema"
      );
      const callableObjs = nonContainerObjs.filter((o) =>
        CALLABLE_TYPES.has(String(o.type || "").toLowerCase())
      );
      const chosenObjs = callableObjs.length > 0 ? callableObjs : nonContainerObjs;
      const nodeById = new Map<string, CallGraphNode>();

      for (const o of chosenObjs) {
        const id = String(o.id);
        nodeById.set(id, {
          id,
          name: o.name,
          kind: String(o.type || "PROCEDURE").toUpperCase(),
          package: o.parent ?? o.schema,
          coordinates: "coordinates" in o ? o.coordinates : undefined
        });
      }

      const ensureEndpointNode = (
        el?: { id?: string; parentId?: string; name?: string; parentName?: string; type?: string }
      ): string => {
        if (!el) return "";
        const id = String(el.parentId ?? el.id ?? "");
        if (!id) return "";
        if (!nodeById.has(id)) {
          const objMatch = nonContainerObjs.find((o) => String(o.id) === id);
          if (objMatch) {
            nodeById.set(id, {
              id,
              name: objMatch.name,
              kind: String(objMatch.type || "PROCEDURE").toUpperCase(),
              package: objMatch.parent ?? objMatch.schema,
              coordinates: objMatch.coordinates
            });
          } else if (el.parentName || el.name) {
            nodeById.set(id, {
              id,
              name: String(el.parentName ?? el.name),
              kind: String(el.type || "PROCEDURE").toUpperCase()
            });
          }
        }
        return id;
      };

      const seenEdgeKeys = new Set<string>();
      const pushEdge = (source: string, target: string, prefix: string) => {
        if (!source || !target || source === target) return;
        const key = `${source}->${target}`;
        if (seenEdgeKeys.has(key)) return;
        seenEdgeKeys.add(key);
        callEdges.push({
          id: `${prefix}-${callEdges.length}`,
          source,
          target
        });
      };

      df.relationships.forEach((rel, idx) => {
        // 1. Call-lineage wire format (`02 §5.1` line 874): `rel.caller` + `rel.callees[]`
        if (rel.caller && Array.isArray(rel.callees) && rel.callees.length > 0) {
          const callerId = ensureEndpointNode(rel.caller);
          rel.callees.forEach((callee) => {
            const calleeId = ensureEndpointNode(callee);
            pushEdge(callerId, calleeId, `cg-call-${idx}`);
          });
        }
        // 2. Standard relationship format: `rel.sources[]` -> `rel.target`
        if (rel.target && Array.isArray(rel.sources) && rel.sources.length > 0) {
          const tgtId = ensureEndpointNode(rel.target);
          rel.sources.forEach((src) => {
            const srcId = ensureEndpointNode(src);
            pushEdge(srcId, tgtId, `cg-rel-${idx}`);
          });
        }
      });

      nodes = Array.from(nodeById.values());
    }

    if (nodes.length === 0) {
      return { laidOutNodes: [] as LaidOutRoutine[], edges: [], canvasWidth: 800, canvasHeight: 500 };
    }

    const g = new dagre.graphlib.Graph();
    g.setGraph({ rankdir: "LR", ranksep: 120, nodesep: 30, marginx: 40, marginy: 40 });
    g.setDefaultEdgeLabel(() => ({}));

    for (const n of nodes) {
      const accessCount = n.tableAccesses?.length ?? 0;
      const height = 62 + Math.min(4, accessCount) * 20;
      g.setNode(n.id, { width: 230, height });
    }
    for (const e of callEdges) {
      if (g.hasNode(e.source) && g.hasNode(e.target)) {
        g.setEdge(e.source, e.target);
      }
    }

    dagre.layout(g);

    let maxW = 900;
    let maxH = 550;
    const positioned: LaidOutRoutine[] = nodes.map((n, idx) => {
      const dn = g.node(n.id) as { x?: number; y?: number; width: number; height: number } | undefined;
      const w = dn?.width ?? 230;
      const h = dn?.height ?? 68;
      const x = dn?.x !== undefined ? dn.x - w / 2 : 50 + (idx % 3) * 290;
      const y = dn?.y !== undefined ? dn.y - h / 2 : 50 + Math.floor(idx / 3) * 140;
      maxW = Math.max(maxW, x + w + 80);
      maxH = Math.max(maxH, y + h + 80);
      return { ...n, x, y, width: w, height: h };
    });

    return {
      laidOutNodes: positioned,
      edges: callEdges,
      canvasWidth: maxW,
      canvasHeight: maxH
    };
  }, [callGraphData, lineageData]);

  const nodeMap = useMemo(
    () => new Map(laidOutNodes.map((n) => [n.id, n])),
    [laidOutNodes]
  );

  const handleExportPng = async () => {
    const png = await captureHighDpiClientPng(containerRef.current, {
      backgroundColor: CALL_GRAPH_PALETTE.canvasBg,
      pixelRatio: 2
    });
    triggerBrowserDownload(png, `sqlflow-callgraph-${dialect}.png`, "image/png");
    showToast("Exported Call Graph PNG");
  };

  return (
    <div
      data-testid="call-graph-view"
      style={{
        display: "flex",
        flexDirection: "column",
        width: "100%",
        height: "100%",
        background: CALL_GRAPH_PALETTE.canvasBg
      }}
    >
      <div
        style={{
          height: 40,
          padding: "0 12px",
          background: "#ffffff",
          borderBottom: "1px solid #e2e8f0",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between"
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 12 }}>
          <strong>{isCn ? "存储过程与函数调用图" : "Routine Call Graph"}</strong>
          <span style={{ color: "#64748b" }}>
            {isCn
              ? `（${laidOutNodes.length} 个例程，${edges.length} 条调用）`
              : `(${laidOutNodes.length} routines, ${edges.length} calls)`}
          </span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
          <button
            type="button"
            data-testid="callgraph-refresh-btn"
            className="sf-btn sf-btn--light"
            onClick={() => void visualizeCallGraph()}
          >
            <RefreshCw size={12} />
            {isCn ? "刷新调用图" : "Refresh Call Graph"}
          </button>
          <button
            type="button"
            data-testid="callgraph-export-png-btn"
            className="sf-btn sf-btn--light"
            onClick={() => void handleExportPng()}
          >
            <Download size={12} />
            {isCn ? "导出 PNG" : "Export PNG"}
          </button>
        </div>
      </div>

      <div
        ref={containerRef}
        onClick={(e) => {
          if (e.target === e.currentTarget) {
            setEditorPinnedCoords([]);
          }
        }}
        style={{ flex: 1, position: "relative", overflow: "auto" }}
      >
        {laidOutNodes.length === 0 ? (
          <div style={{ padding: 36, textAlign: "center", color: "#64748b", fontSize: 13 }}>
            {isCn ? (
              <>
                当前脚本未检测到存储过程或函数调用关系。点击右上角{" "}
                <strong>刷新调用图</strong> 进行分析。
              </>
            ) : (
              <>
                No stored procedures or function call relationships detected yet. Click{" "}
                <strong>Refresh Call Graph</strong> to analyze routines.
              </>
            )}
          </div>
        ) : (
          <div
            onClick={(e) => {
              if (e.target === e.currentTarget) {
                setEditorPinnedCoords([]);
              }
            }}
            style={{ position: "relative", width: canvasWidth, height: canvasHeight }}
          >
            <svg
              width={canvasWidth}
              height={canvasHeight}
              style={{ position: "absolute", inset: 0, pointerEvents: "none" }}
            >
              {edges.map((edge, idx) => {
                const src = nodeMap.get(edge.source);
                const tgt = nodeMap.get(edge.target);
                if (!src || !tgt) return null;
                const x1 = src.x + src.width;
                const y1 = src.y + src.height / 2;
                const x2 = tgt.x;
                const y2 = tgt.y + tgt.height / 2;
                const cx = (x1 + x2) / 2;
                return (
                  <g key={edge.id ?? `e-${idx}`}>
                    <path
                      d={`M ${x1} ${y1} C ${cx} ${y1}, ${cx} ${y2}, ${x2} ${y2}`}
                      fill="none"
                      stroke={CALL_GRAPH_PALETTE.edgeStroke}
                      strokeWidth={CALL_GRAPH_PALETTE.edgeStrokeWidth}
                    />
                    <polygon
                      points={`${x2},${y2} ${x2 - 8},${y2 - 4} ${x2 - 8},${y2 + 4}`}
                      fill={CALL_GRAPH_PALETTE.edgeStroke}
                    />
                  </g>
                );
              })}
            </svg>

            {laidOutNodes.map((routine) => {
              const kindUpper = routine.kind.toUpperCase();
              const badgeColor =
                CALL_GRAPH_PALETTE.routineKindColors[
                  kindUpper as keyof typeof CALL_GRAPH_PALETTE.routineKindColors
                ] ?? "#3b82f6";

              return (
                <div
                  key={routine.id}
                  data-testid={`callgraph-routine-${routine.id}`}
                  onClick={() => {
                    if (routine.coordinates) {
                      setEditorPinnedCoords(routine.coordinates);
                    }
                  }}
                  style={{
                    position: "absolute",
                    left: routine.x,
                    top: routine.y,
                    width: routine.width,
                    background: "#ffffff",
                    border: `1.5px solid ${badgeColor}`,
                    borderRadius: 6,
                    boxShadow: "0 2px 8px rgba(15,23,42,0.08)",
                    padding: "8px 10px",
                    cursor: "pointer"
                  }}
                >
                  <div
                    style={{
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "space-between",
                      gap: 6
                    }}
                  >
                    <strong style={{ fontSize: 12, color: "#0f172a" }}>{routine.name}</strong>
                    <span
                      style={{
                        fontSize: 9,
                        fontWeight: 700,
                        padding: "1px 5px",
                        borderRadius: 4,
                        background: badgeColor,
                        color: "#ffffff"
                      }}
                    >
                      {kindUpper}
                    </span>
                  </div>
                  {routine.package && (
                    <div style={{ fontSize: 10, color: "#64748b", marginTop: 2 }}>
                      pkg: {routine.package}
                    </div>
                  )}
                  {routine.tableAccesses && routine.tableAccesses.length > 0 && (
                    <div style={{ display: "flex", flexWrap: "wrap", gap: 4, marginTop: 6 }}>
                      {routine.tableAccesses.map((ta, tIdx) => {
                        const accColor =
                          CALL_GRAPH_PALETTE.tableAccessColors[ta.access] ?? "#22c55e";
                        const shortAcc =
                          ta.access === "READ_WRITE"
                            ? "RW"
                            : ta.access === "WRITE"
                              ? "W"
                              : "R";
                        return (
                          <span
                            key={`${ta.table}-${tIdx}`}
                            style={{
                              fontSize: 10,
                              padding: "1px 5px",
                              borderRadius: 3,
                              border: `1px solid ${accColor}`,
                              color: accColor,
                              fontWeight: 600
                            }}
                          >
                            {shortAcc}: {ta.table}
                          </span>
                        );
                      })}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};
