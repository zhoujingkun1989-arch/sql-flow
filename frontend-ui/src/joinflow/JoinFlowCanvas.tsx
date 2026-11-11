import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Background,
  Controls,
  type Edge,
  Handle,
  MiniMap,
  type Node,
  type NodeProps,
  Position,
  ReactFlow,
  ReactFlowProvider
} from "@xyflow/react";
import ELK, { type ElkNode } from "elkjs/lib/elk.bundled.js";
import {
  JOIN_OPERATOR_COLORS,
  type JoinAnalysisEdge,
  type JoinAnalysisGraph,
  type JoinAnalysisNode,
  type QueryBlockLane,
  type SourceRange
} from "@sqlflow/ui-core";

export interface JoinFlowSelection {
  nodeId?: string;
  edgeId?: string;
  fragmentId?: string;
  sourceRange?: SourceRange;
}

export interface JoinFlowCanvasProps {
  model: JoinAnalysisGraph;
  highlightedNodeIds?: ReadonlySet<string>;
  highlightedEdgeIds?: ReadonlySet<string>;
  onSelect?: (selection: JoinFlowSelection) => void;
}

const elkInstance = new ELK();

interface TableNodeCustomData extends Record<string, unknown> {
  rawNode: JoinAnalysisNode;
  highlighted: boolean;
}

interface JoinOpCustomData extends Record<string, unknown> {
  rawNode: JoinAnalysisNode;
  highlighted: boolean;
}

interface LaneNodeCustomData extends Record<string, unknown> {
  lane: QueryBlockLane;
  collapsed: boolean;
  onToggleCollapse: (laneId: string) => void;
}

const JoinTableNodeCard: React.FC<NodeProps<Node<TableNodeCustomData>>> = ({ data }) => {
  const { rawNode, highlighted } = data;
  const tbl = rawNode.data.table;
  const cols = tbl?.columnsContributed ?? [];
  const qualifier = [tbl?.database, tbl?.schema].filter(Boolean).join(".");

  return (
    <div
      data-testid={`joinflow-node-${rawNode.id}`}
      style={{
        minWidth: 190,
        background: "#ffffff",
        border: highlighted ? "2px solid #dc2626" : "1px solid #94a3b8",
        borderRadius: 6,
        boxShadow: highlighted
          ? "0 0 0 3px rgba(220,38,38,0.22)"
          : "0 2px 5px rgba(15,23,42,0.08)",
        fontSize: 12,
        overflow: "hidden"
      }}
    >
      <Handle type="target" position={Position.Left} style={{ background: "#64748b" }} />
      <div
        style={{
          background: rawNode.type === "output" ? "#1e293b" : "#91c051",
          color: "#ffffff",
          padding: "5px 8px",
          fontWeight: 600,
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 6
        }}
      >
        <span>
          {tbl?.name ?? rawNode.data.label}
          {tbl?.alias ? ` (${tbl.alias})` : ""}
        </span>
        <span
          style={{
            fontSize: 10,
            padding: "1px 5px",
            borderRadius: 3,
            background: "rgba(0,0,0,0.22)"
          }}
        >
          {rawNode.type.toUpperCase()}
        </span>
      </div>
      {qualifier && (
        <div style={{ padding: "3px 8px", fontSize: 10, color: "#64748b", background: "#f8fafc" }}>
          {qualifier}
        </div>
      )}
      {tbl?.isSelfJoinInstance && (
        <div
          style={{
            padding: "2px 8px",
            fontSize: 10,
            background: "#eff6ff",
            color: "#1d4ed8",
            fontWeight: 600
          }}
        >
          Self-Join Instance
        </div>
      )}
      {cols.length > 0 && (
        <div style={{ padding: "4px 8px", borderTop: "1px solid #f1f5f9" }}>
          {cols.slice(0, 6).map((c, idx) => (
            <div
              key={`${c.table}.${c.column}.${idx}`}
              style={{ fontSize: 11, color: "#334155", lineHeight: "16px" }}
            >
              • {c.column}
            </div>
          ))}
        </div>
      )}
      <Handle type="source" position={Position.Right} style={{ background: "#64748b" }} />
    </div>
  );
};

const JoinOperatorNodeCard: React.FC<NodeProps<Node<JoinOpCustomData>>> = ({ data }) => {
  const { rawNode, highlighted } = data;
  const join = rawNode.data.join;
  const opType = join?.joinType ?? "UNKNOWN";
  const opColor =
    opType in JOIN_OPERATOR_COLORS
      ? JOIN_OPERATOR_COLORS[opType as keyof typeof JOIN_OPERATOR_COLORS]
      : JOIN_OPERATOR_COLORS.DEFAULT;
  const badges = rawNode.data.badges ?? [];
  const predicates = join?.predicates ?? [];

  return (
    <div
      data-testid={`joinflow-node-${rawNode.id}`}
      style={{
        minWidth: 220,
        maxWidth: 280,
        background: "#ffffff",
        border: highlighted ? "2px solid #dc2626" : `1.5px solid ${opColor}`,
        borderRadius: 8,
        boxShadow: highlighted
          ? "0 0 0 3px rgba(220,38,38,0.25)"
          : "0 3px 8px rgba(15,23,42,0.1)",
        fontSize: 12,
        overflow: "hidden"
      }}
    >
      <Handle type="target" position={Position.Left} style={{ background: opColor }} />
      <div
        style={{
          background: opColor,
          color: "#ffffff",
          padding: "6px 10px",
          fontWeight: 700,
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 6
        }}
      >
        <span>{opType} JOIN</span>
        <span style={{ fontSize: 10, fontWeight: 500, opacity: 0.9 }}>
          {join?.clause ?? "ON"}
          {join?.cardinalityHint && join.cardinalityHint !== "unknown"
            ? ` • ${join.cardinalityHint}`
            : ""}
        </span>
      </div>
      <div style={{ padding: "6px 10px" }}>
        {predicates.length > 0 ? (
          predicates.map((p) => (
            <div
              key={p.predicateId}
              style={{
                fontFamily: "monospace",
                fontSize: 11,
                color: "#1e293b",
                background: "#f8fafc",
                padding: "3px 6px",
                borderRadius: 4,
                marginBottom: 4,
                border: "1px solid #e2e8f0",
                wordBreak: "break-word"
              }}
            >
              {p.raw}
            </div>
          ))
        ) : (
          <div style={{ fontSize: 11, color: "#b91c1c", fontStyle: "italic" }}>
            No join predicate (Cartesian / Unconditional)
          </div>
        )}

        {(badges.length > 0 || (join?.patterns && join.patterns.length > 0)) && (
          <div style={{ display: "flex", flexWrap: "wrap", gap: 4, marginTop: 4 }}>
            {badges.map((b, idx) => {
              const fanBg =
                b.level === "high"
                  ? "#fee2e2"
                  : b.level === "medium"
                    ? "#fef3c7"
                    : "#e0f2fe";
              const fanColor =
                b.level === "high"
                  ? "#991b1b"
                  : b.level === "medium"
                    ? "#92400e"
                    : "#075985";
              return (
                <span
                  key={`${b.kind}-${idx}`}
                  style={{
                    fontSize: 10,
                    fontWeight: 600,
                    padding: "1px 6px",
                    borderRadius: 999,
                    background: fanBg,
                    color: fanColor
                  }}
                >
                  {b.kind}
                  {b.level ? `:${b.level}` : ""}
                </span>
              );
            })}
          </div>
        )}
      </div>
      <Handle type="source" position={Position.Right} style={{ background: opColor }} />
    </div>
  );
};

const LaneContainerNode: React.FC<NodeProps<Node<LaneNodeCustomData>>> = ({ data }) => {
  const { lane, collapsed, onToggleCollapse } = data;
  const borderStyle =
    lane.kind === "main"
      ? "1.5px solid #94a3b8"
      : lane.kind === "cte"
        ? "1.5px dashed #3b82f6"
        : "1.5px dashed #8b5cf6";
  const headerBg =
    lane.kind === "main" ? "#f1f5f9" : lane.kind === "cte" ? "#eff6ff" : "#f5f3ff";

  return (
    <div
      data-testid={`joinflow-lane-${lane.queryBlockId}`}
      style={{
        width: "100%",
        height: "100%",
        border: borderStyle,
        borderRadius: 10,
        background: "rgba(248, 250, 252, 0.55)",
        pointerEvents: "all"
      }}
    >
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "5px 10px",
          background: headerBg,
          borderBottom: "1px solid #e2e8f0",
          borderTopLeftRadius: 9,
          borderTopRightRadius: 9,
          fontSize: 11,
          fontWeight: 600,
          color: "#334155"
        }}
      >
        <span>
          [{lane.kind.toUpperCase()}] {lane.label}
        </span>
        {lane.kind !== "main" && (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              onToggleCollapse(lane.queryBlockId);
            }}
            style={{
              border: "1px solid #cbd5e1",
              background: "#ffffff",
              borderRadius: 4,
              fontSize: 10,
              padding: "1px 6px",
              cursor: "pointer"
            }}
          >
            {collapsed ? "Expand Scope" : "Collapse Scope"}
          </button>
        )}
      </div>
    </div>
  );
};

const NODE_TYPES = {
  joinTableNode: JoinTableNodeCard,
  joinOpNode: JoinOperatorNodeCard,
  laneGroupNode: LaneContainerNode
};

function computeSynchronousFallbackPositions(
  nodes: JoinAnalysisNode[],
  lanes: QueryBlockLane[],
  collapsedLanes: ReadonlySet<string>
): {
  positions: Map<string, { x: number; y: number }>;
  laneRects: Map<string, { x: number; y: number; width: number; height: number }>;
} {
  const positions = new Map<string, { x: number; y: number }>();
  const laneRects = new Map<string, { x: number; y: number; width: number; height: number }>();

  const effectiveLanes: QueryBlockLane[] =
    lanes.length > 0
      ? lanes
      : [{ queryBlockId: "main", label: "Main Query", kind: "main" }];

  let currentLaneY = 20;
  for (const lane of effectiveLanes) {
    const isCollapsed = collapsedLanes.has(lane.queryBlockId);
    const laneNodes = nodes.filter(
      (n) => (n.data.queryBlockId ?? effectiveLanes[0]!.queryBlockId) === lane.queryBlockId
    );
    if (isCollapsed) {
      laneRects.set(lane.queryBlockId, {
        x: 20,
        y: currentLaneY,
        width: 360,
        height: 44
      });
      currentLaneY += 68;
      continue;
    }

    const sources = laneNodes.filter((n) => n.type !== "join" && n.type !== "output");
    const joins = laneNodes.filter((n) => n.type === "join");
    const outputs = laneNodes.filter((n) => n.type === "output");

    const maxRows = Math.max(sources.length, joins.length, outputs.length, 1);
    const laneHeight = Math.max(170, 56 + maxRows * 125);
    const laneWidth = 860;

    laneRects.set(lane.queryBlockId, {
      x: 20,
      y: currentLaneY,
      width: laneWidth,
      height: laneHeight
    });

    sources.forEach((n, idx) => {
      positions.set(n.id, n.position ?? { x: 45, y: currentLaneY + 48 + idx * 120 });
    });
    joins.forEach((n, idx) => {
      positions.set(
        n.id,
        n.position ?? { x: 315 + idx * 240, y: currentLaneY + 56 + (idx % 2) * 85 }
      );
    });
    outputs.forEach((n, idx) => {
      positions.set(n.id, n.position ?? { x: 640, y: currentLaneY + 56 + idx * 110 });
    });

    currentLaneY += laneHeight + 28;
  }

  // Position any orphan nodes not matching a lane
  let orphanIdx = 0;
  for (const n of nodes) {
    if (!positions.has(n.id)) {
      positions.set(n.id, n.position ?? { x: 50 + orphanIdx * 250, y: currentLaneY + 20 });
      orphanIdx += 1;
    }
  }

  return { positions, laneRects };
}

export const JoinFlowCanvas: React.FC<JoinFlowCanvasProps> = ({
  model,
  highlightedNodeIds,
  highlightedEdgeIds,
  onSelect
}) => {
  const [collapsedLanes, setCollapsedLanes] = useState<Set<string>>(() => {
    const init = new Set<string>();
    for (const q of model.queryBlocks ?? []) {
      if (q.collapsedByDefault && q.kind !== "main") {
        init.add(q.queryBlockId);
      }
    }
    return init;
  });

  const toggleLaneCollapse = useCallback((laneId: string) => {
    setCollapsedLanes((prev) => {
      const next = new Set(prev);
      if (next.has(laneId)) {
        next.delete(laneId);
      } else {
        next.add(laneId);
      }
      return next;
    });
  }, []);

  const syncFallback = useMemo(
    () =>
      computeSynchronousFallbackPositions(
        model.nodes ?? [],
        model.queryBlocks ?? [],
        collapsedLanes
      ),
    [model.nodes, model.queryBlocks, collapsedLanes]
  );

  const [elkPositions, setElkPositions] = useState<Map<string, { x: number; y: number }> | null>(
    null
  );

  useEffect(() => {
    let cancelled = false;
    const runElk = async () => {
      const rawNodes = model.nodes ?? [];
      if (rawNodes.length === 0) {
        setElkPositions(null);
        return;
      }
      const direction = model.layout?.direction ?? "RIGHT";
      const elkGraph: ElkNode = {
        id: "root",
        layoutOptions: {
          "elk.algorithm": "layered",
          "elk.direction": direction,
          "elk.layered.spacing.nodeNodeBetweenLayers": String(model.layout?.rankSpacing ?? 90),
          "elk.spacing.nodeNode": String(model.layout?.nodeSpacing ?? 42)
        },
        children: rawNodes.map((n) => ({
          id: n.id,
          width: n.type === "join" ? 250 : 210,
          height: n.type === "join" ? 115 : 95
        })),
        edges: (model.edges ?? []).map((e: JoinAnalysisEdge) => ({
          id: e.id,
          sources: [e.source],
          targets: [e.target]
        }))
      };
      try {
        const laidOut = await elkInstance.layout(elkGraph);
        if (cancelled || !laidOut.children) return;
        const map = new Map<string, { x: number; y: number }>();
        for (const child of laidOut.children) {
          if (typeof child.x === "number" && typeof child.y === "number") {
            map.set(child.id, { x: child.x + 45, y: child.y + 60 });
          }
        }
        setElkPositions(map);
      } catch {
        // Synchronous layout remains active
      }
    };
    void runElk();
    return () => {
      cancelled = true;
    };
  }, [model]);

  const fragmentById = useMemo(() => {
    const map = new Map<string, SourceRange | undefined>();
    for (const f of model.fragments ?? []) {
      map.set(f.fragmentId, f.sourceRange);
    }
    return map;
  }, [model.fragments]);

  const rfNodes = useMemo<Node[]>(() => {
    const out: Node[] = [];
    const lanes =
      model.queryBlocks && model.queryBlocks.length > 0
        ? model.queryBlocks
        : [{ queryBlockId: "main", label: "Main Query", kind: "main" as const }];

    for (const lane of lanes) {
      const rect = syncFallback.laneRects.get(lane.queryBlockId);
      if (!rect) continue;
      out.push({
        id: `lane:${lane.queryBlockId}`,
        type: "laneGroupNode",
        position: { x: rect.x, y: rect.y },
        style: { width: rect.width, height: rect.height, zIndex: 0 },
        selectable: false,
        draggable: false,
        data: {
          lane,
          collapsed: collapsedLanes.has(lane.queryBlockId),
          onToggleCollapse: toggleLaneCollapse
        }
      });
    }

    for (const rawNode of model.nodes ?? []) {
      const laneId = rawNode.data.queryBlockId ?? lanes[0]!.queryBlockId;
      if (collapsedLanes.has(laneId)) {
        continue;
      }
      const pos =
        rawNode.position ??
        elkPositions?.get(rawNode.id) ??
        syncFallback.positions.get(rawNode.id) ?? { x: 60, y: 60 };
      const isHighlighted = Boolean(highlightedNodeIds?.has(rawNode.id));
      out.push({
        id: rawNode.id,
        type: rawNode.type === "join" ? "joinOpNode" : "joinTableNode",
        position: pos,
        style: { zIndex: 2 },
        data: {
          rawNode,
          highlighted: isHighlighted
        }
      });
    }

    return out;
  }, [
    model.queryBlocks,
    model.nodes,
    collapsedLanes,
    syncFallback,
    elkPositions,
    highlightedNodeIds,
    toggleLaneCollapse
  ]);

  const rfEdges = useMemo<Edge[]>(() => {
    return (model.edges ?? []).map((edge) => {
      const isHi = Boolean(highlightedEdgeIds?.has(edge.id));
      const isDashed =
        Boolean(edge.data?.dashed) ||
        edge.data?.kind === "correlation" ||
        edge.data?.kind === "anti_join_evidence";
      const colLabel = edge.data?.columns?.map((c) => c.column).join(", ");
      return {
        id: edge.id,
        source: edge.source,
        target: edge.target,
        label: edge.data?.note ?? colLabel,
        animated: Boolean(edge.data?.animated),
        style: {
          stroke: isHi ? "#dc2626" : "#475569",
          strokeWidth: isHi ? 2.4 : 1.5,
          ...(isDashed ? { strokeDasharray: "5 3" } : {})
        }
      };
    });
  }, [model.edges, highlightedEdgeIds]);

  const handleNodeClick = useCallback(
    (_event: React.MouseEvent, node: Node) => {
      if (node.id.startsWith("lane:")) {
        return;
      }
      const raw = (node.data as { rawNode?: JoinAnalysisNode } | undefined)?.rawNode;
      if (!raw) return;
      const range =
        raw.data.join?.sourceRange ??
        (raw.data.fragmentId ? fragmentById.get(raw.data.fragmentId) : undefined);
      onSelect?.({
        nodeId: raw.id,
        fragmentId: raw.data.fragmentId,
        sourceRange: range
      });
    },
    [fragmentById, onSelect]
  );

  const handleEdgeClick = useCallback(
    (_event: React.MouseEvent, edge: Edge) => {
      onSelect?.({ edgeId: edge.id });
    },
    [onSelect]
  );

  return (
    <div
      data-testid="joinflow-v2-canvas"
      style={{ width: "100%", height: "100%", minHeight: 360, background: "#f8fafc" }}
    >
      <ReactFlowProvider>
        <ReactFlow
          nodes={rfNodes}
          edges={rfEdges}
          nodeTypes={NODE_TYPES}
          onNodeClick={handleNodeClick}
          onEdgeClick={handleEdgeClick}
          fitView
          minZoom={0.2}
          maxZoom={2.5}
          proOptions={{ hideAttribution: true }}
        >
          <Background color="#cbd5e1" gap={18} />
          <Controls showInteractive={false} />
          <MiniMap zoomable pannable />
        </ReactFlow>
      </ReactFlowProvider>
    </div>
  );
};
