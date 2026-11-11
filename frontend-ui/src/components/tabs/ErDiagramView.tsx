import React, { useEffect, useMemo, useRef, useState } from "react";
import {
  type Column,
  SURFACE_AND_EDGE_TOKENS,
  layoutSqlflowGraph
} from "@sqlflow/ui-core";
import { Download, ExternalLink, RefreshCw } from "lucide-react";
import { useI18n } from "../../i18n/I18nContext";
import { useApp } from "../../state/AppContext";
import {
  buildMermaidLiveUrl,
  captureHighDpiClientPng,
  extractDataFlowFromAny,
  triggerBrowserDownload
} from "../../utils/exporters";

export const ErDiagramView: React.FC = () => {
  const { locale } = useI18n();
  const isCn = locale === "cn";
  const {
    erData,
    lineageData,
    dialect,
    settings,
    visualizeErDiagram,
    setEditorPinnedCoords,
    showToast
  } = useApp();

  const sourceData = erData ?? lineageData;
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [hoveredColKey, setHoveredColKey] = useState<string | null>(null);
  const [mermaidModal, setMermaidModal] = useState<{ code: string; url: string } | null>(null);

  const baseModel = useMemo(
    () => (sourceData ? layoutSqlflowGraph(sourceData, { rankdir: "LR" }) : null),
    [sourceData]
  );

  const rawColumnMap = useMemo(() => {
    const df = extractDataFlowFromAny(sourceData);
    const map = new Map<string, Column>();
    for (const obj of df.dbobjs ?? []) {
      if ("columns" in obj && Array.isArray(obj.columns)) {
        for (const col of obj.columns) {
          map.set(String(col.id), col);
        }
      }
    }
    return map;
  }, [sourceData]);

  const [positions, setPositions] = useState<Map<string, { x: number; y: number }>>(() => new Map());

  useEffect(() => {
    if (!baseModel) return;
    const rawItems = baseModel.nodes.map((n, idx) => ({
      node: n,
      x: n.x || 40 + (idx % 3) * 300,
      y: n.y || 50 + Math.floor(idx / 3) * 240,
      w: Math.max(220, n.width + 40),
      h: 40 + n.columns.length * 22
    }));

    // Group into X-lanes (within 40px) so wider ER cards & 22px rows never overlap horizontally or vertically
    rawItems.sort((a, b) => a.x - b.x || a.y - b.y);
    const lanes: Array<{ origX: number; maxW: number; items: typeof rawItems }> = [];
    for (const item of rawItems) {
      const lane = lanes.find((l) => Math.abs(l.origX - item.x) <= 40);
      if (lane) {
        lane.maxW = Math.max(lane.maxW, item.w);
        lane.items.push(item);
      } else {
        lanes.push({ origX: item.x, maxW: item.w, items: [item] });
      }
    }

    const next = new Map<string, { x: number; y: number }>();
    let laneLeft = lanes[0]?.origX ?? 40;
    for (let i = 0; i < lanes.length; i++) {
      const lane = lanes[i]!;
      const resolvedX = i === 0 ? Math.max(30, lane.origX) : Math.max(lane.origX, laneLeft);
      lane.items.sort((a, b) => a.y - b.y);
      let cursorY = 30;
      for (const item of lane.items) {
        const resolvedY = Math.max(item.y, cursorY);
        next.set(item.node.id, { x: resolvedX, y: resolvedY });
        cursorY = resolvedY + item.h + 20;
      }
      laneLeft = resolvedX + lane.maxW + 72;
    }
    setPositions(next);
  }, [baseModel]);

  const erSurfaceBounds = useMemo(() => {
    let maxRight = 1200;
    let maxBottom = 800;
    if (baseModel) {
      for (const n of baseModel.nodes) {
        const p = positions.get(n.id) ?? { x: n.x, y: n.y };
        const w = Math.max(220, n.width + 40);
        const h = 40 + n.columns.length * 22;
        maxRight = Math.max(maxRight, p.x + w + 120);
        maxBottom = Math.max(maxBottom, p.y + h + 120);
      }
    }
    return { width: maxRight, height: maxBottom };
  }, [baseModel, positions]);

  const handleOpenMermaid = () => {
    const built = buildMermaidLiveUrl(sourceData);
    setMermaidModal(built);
    try {
      window.open(built.url, "_blank", "noopener,noreferrer");
    } catch {
      // Headless / jsdom safe
    }
  };

  const handleExportErPng = async () => {
    const png = await captureHighDpiClientPng(containerRef.current, { pixelRatio: 2 });
    triggerBrowserDownload(png, `sqlflow-er-${dialect}.png`, "image/png");
    showToast("Exported ER Diagram PNG");
  };

  const handleDragStart = (nodeId: string, e: React.MouseEvent<HTMLDivElement>) => {
    if (!settings.tableMovable) return;
    e.preventDefault();
    const orig = positions.get(nodeId) ?? { x: 40, y: 40 };
    const startX = e.clientX;
    const startY = e.clientY;
    const onMove = (ev: MouseEvent) => {
      setPositions((prev) => {
        const copy = new Map(prev);
        copy.set(nodeId, {
          x: Math.max(12, orig.x + (ev.clientX - startX)),
          y: Math.max(12, orig.y + (ev.clientY - startY))
        });
        return copy;
      });
    };
    const onUp = () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  };

  return (
    <div
      data-testid="er-diagram-view"
      style={{
        display: "flex",
        flexDirection: "column",
        width: "100%",
        height: "100%",
        background: "#f4f6f8"
      }}
    >
      {/* ER Toolbar */}
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
          <strong>{isCn ? "实体关系图 (ER)" : "Entity-Relationship Diagram"}</strong>
          <span style={{ color: "#64748b" }}>
            {isCn
              ? `（${baseModel?.nodes.length ?? 0} 个实体，${baseModel?.edges.length ?? 0} 条关联）`
              : `(${baseModel?.nodes.length ?? 0} entities, ${baseModel?.edges.length ?? 0} relationships)`}
          </span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
          <button
            type="button"
            data-testid="er-refresh-btn"
            className="sf-btn sf-btn--light"
            onClick={() => void visualizeErDiagram()}
          >
            <RefreshCw size={12} />
            {isCn ? "刷新 ER 图" : "Refresh ER"}
          </button>
          <button
            type="button"
            data-testid="er-mermaid-btn"
            className="sf-btn sf-btn--light"
            onClick={handleOpenMermaid}
          >
            <ExternalLink size={12} />
            {isCn ? "在 Mermaid 编辑器打开" : "show graph in mermaid editor"}
          </button>
          <button
            type="button"
            data-testid="er-export-png-btn"
            className="sf-btn sf-btn--light"
            onClick={() => void handleExportErPng()}
          >
            <Download size={12} />
            {isCn ? "导出 PNG" : "Export PNG"}
          </button>
        </div>
      </div>

      {/* ER Canvas */}
      <div
        ref={containerRef}
        onClick={(e) => {
          if (e.target === e.currentTarget) {
            setEditorPinnedCoords([]);
          }
        }}
        style={{
          flex: 1,
          position: "relative",
          overflow: "auto",
          background: "#f8fafc"
        }}
      >
        {!baseModel || baseModel.nodes.length === 0 ? (
          <div style={{ padding: 36, textAlign: "center", color: "#64748b", fontSize: 13 }}>
            {isCn ? (
              <>
                点击右上角 <strong>刷新 ER 图</strong> 或编辑器 <strong>ER 图分析</strong>{" "}
                以可视化主外键实体关系。
              </>
            ) : (
              <>
                Click <strong>Refresh ER</strong> or <strong>ER Diagram</strong> to visualize PK/FK/UK
                relationships.
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
            style={{
              position: "relative",
              minWidth: erSurfaceBounds.width,
              minHeight: erSurfaceBounds.height
            }}
          >
            {/* Relationship Curves */}
            <svg
              width="100%"
              height="100%"
              style={{ position: "absolute", inset: 0, pointerEvents: "none" }}
            >
              {baseModel.edges.map((edge) => {
                const srcPos = positions.get(edge.sourceNodeId);
                const tgtPos = positions.get(edge.targetNodeId);
                const srcNode = baseModel.nodeById.get(edge.sourceNodeId);
                if (!srcPos || !tgtPos || !srcNode) return null;
                const x1 = srcPos.x + Math.max(220, srcNode.width + 40);
                const y1 = srcPos.y + 34 + (edge.sourceColumnIndex ?? 0) * 22;
                const x2 = tgtPos.x;
                const y2 = tgtPos.y + 34 + (edge.targetColumnIndex ?? 0) * 22;
                const cx = (x1 + x2) / 2;
                return (
                  <g key={edge.id}>
                    <path
                      d={`M ${x1} ${y1} C ${cx} ${y1}, ${cx} ${y2}, ${x2} ${y2}`}
                      fill="none"
                      stroke={SURFACE_AND_EDGE_TOKENS.erAccentTopBar}
                      strokeWidth={1.6}
                    />
                    <circle
                      cx={x2}
                      cy={y2}
                      r={3.5}
                      fill={SURFACE_AND_EDGE_TOKENS.erAccentTopBar}
                    />
                  </g>
                );
              })}
            </svg>

            {/* Entity Cards with `#38c172` 6px Top Accent Bar */}
            {baseModel.nodes.map((node) => {
              const pos = positions.get(node.id) ?? { x: node.x, y: node.y };
              const cardWidth = Math.max(220, node.width + 40);
              return (
                <div
                  key={node.id}
                  data-testid={`er-entity-${node.id}`}
                  style={{
                    position: "absolute",
                    left: pos.x,
                    top: pos.y,
                    width: cardWidth,
                    background: "#ffffff",
                    border: "1px solid #cbd5e1",
                    borderTop: `6px solid ${SURFACE_AND_EDGE_TOKENS.erAccentTopBar}`,
                    borderRadius: 6,
                    boxShadow: "0 3px 10px rgba(15,23,42,0.08)",
                    overflow: "hidden"
                  }}
                >
                  <div
                    onMouseDown={(e) => handleDragStart(node.id, e)}
                    style={{
                      padding: "6px 10px",
                      fontWeight: 700,
                      fontSize: 12,
                      color: "#0f172a",
                      borderBottom: "1px solid #e2e8f0",
                      background: "#f8fafc",
                      cursor: settings.tableMovable ? "move" : "default"
                    }}
                  >
                    {node.displayTitle}
                  </div>
                  <div>
                    {node.columns.map((col) => {
                      const rawCol = rawColumnMap.get(col.id);
                      const isPk = col.primaryKey || Boolean(rawCol?.primaryKey);
                      const isUk = col.unqiueKey || Boolean(rawCol?.unqiueKey);
                      const isFk = col.foreignKey || Boolean(rawCol?.foreignKey);
                      const rowKey = `${node.id}:${col.id}`;
                      const hovered = hoveredColKey === rowKey;
                      return (
                        <div
                          key={col.id}
                          data-testid={`er-col-${col.id}`}
                          onMouseEnter={() => setHoveredColKey(rowKey)}
                          onMouseLeave={() => setHoveredColKey(null)}
                          onClick={() => setEditorPinnedCoords(col.coordinates)}
                          style={{
                            display: "flex",
                            alignItems: "center",
                            justifyContent: "space-between",
                            padding: "3px 10px",
                            fontSize: 11,
                            borderBottom: "1px solid #f1f5f9",
                            background: hovered
                              ? SURFACE_AND_EDGE_TOKENS.erFieldHoverBg
                              : "#ffffff",
                            color: hovered
                              ? SURFACE_AND_EDGE_TOKENS.erFieldHoverText
                              : "#1e293b",
                            cursor: "pointer"
                          }}
                        >
                          <div style={{ display: "flex", alignItems: "center", gap: 5 }}>
                            {isPk && (
                              <span
                                data-testid={`er-badge-pk-${col.id}`}
                                style={{
                                  fontSize: 9,
                                  fontWeight: 700,
                                  padding: "0 4px",
                                  borderRadius: 3,
                                  background: "#dcfce7",
                                  color: "#166534"
                                }}
                              >
                                PK
                              </span>
                            )}
                            {isUk && (
                              <span
                                data-testid={`er-badge-uk-${col.id}`}
                                style={{
                                  fontSize: 9,
                                  fontWeight: 700,
                                  padding: "0 4px",
                                  borderRadius: 3,
                                  background: "#dbeafe",
                                  color: "#1e40af"
                                }}
                              >
                                U
                              </span>
                            )}
                            {isFk && (
                              <span
                                data-testid={`er-badge-fk-${col.id}`}
                                style={{
                                  fontSize: 9,
                                  fontWeight: 700,
                                  padding: "0 4px",
                                  borderRadius: 3,
                                  background: "#fef3c7",
                                  color: "#92400e"
                                }}
                              >
                                FK
                              </span>
                            )}
                            <span>{col.name}</span>
                          </div>
                          <span style={{ color: SURFACE_AND_EDGE_TOKENS.erTypeText, fontSize: 10 }}>
                            {col.dataType ?? rawCol?.dataType ?? ""}
                          </span>
                        </div>
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Mermaid Live Export Preview Modal */}
      {mermaidModal && (
        <div className="sf-modal-backdrop" onClick={() => setMermaidModal(null)}>
          <div
            className="sf-modal-card"
            style={{ maxWidth: 640 }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="sf-modal-header">
              <span>Mermaid Live ER Export</span>
              <button
                type="button"
                className="sf-btn sf-btn--light"
                onClick={() => setMermaidModal(null)}
              >
                Close
              </button>
            </div>
            <div className="sf-modal-body">
              <div style={{ marginBottom: 8, fontSize: 12 }}>
                <a
                  data-testid="mermaid-live-link"
                  href={mermaidModal.url}
                  target="_blank"
                  rel="noreferrer"
                  style={{ color: "#2563eb", wordBreak: "break-all" }}
                >
                  {mermaidModal.url}
                </a>
              </div>
              <pre
                data-testid="mermaid-er-code"
                style={{
                  background: "#0f172a",
                  color: "#e2e8f0",
                  padding: 12,
                  borderRadius: 6,
                  fontSize: 11,
                  overflowX: "auto"
                }}
              >
                {mermaidModal.code}
              </pre>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
