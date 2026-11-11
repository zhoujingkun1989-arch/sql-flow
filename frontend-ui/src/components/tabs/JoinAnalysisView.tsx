import React, { useMemo, useState } from "react";
import {
  JOIN_DIAGNOSTIC_DESCRIPTORS,
  type JoinDiagnostic,
  type JoinDiagnosticRole,
  type SourceRange
} from "@sqlflow/ui-core";
import { AlertTriangle, RefreshCw } from "lucide-react";
import { useI18n } from "../../i18n/I18nContext";
import { JoinFlowCanvas, type JoinFlowSelection } from "../../joinflow/JoinFlowCanvas";
import { useApp } from "../../state/AppContext";

export const JoinAnalysisView: React.FC = () => {
  const { locale } = useI18n();
  const isCn = locale === "cn";
  const {
    joinAnalysisData,
    visualizeJoinAnalysis,
    setEditorPinnedCoords,
    setActiveOccurrenceIndex
  } = useApp();

  const [roleFilter, setRoleFilter] = useState<JoinDiagnosticRole>("all");
  const [selectedDiagId, setSelectedDiagId] = useState<string | null>(null);

  const highlightRangeInEditor = (range?: SourceRange) => {
    if (!range) return;
    setEditorPinnedCoords([
      { x: range.startLine, y: range.startColumn },
      { x: range.endLine, y: range.endColumn }
    ]);
    setActiveOccurrenceIndex(0);
  };

  const descriptorByCode = useMemo(
    () => new Map(JOIN_DIAGNOSTIC_DESCRIPTORS.map((d) => [d.code, d])),
    []
  );

  const filteredDiagnostics = useMemo<JoinDiagnostic[]>(() => {
    const all = joinAnalysisData?.diagnostics ?? [];
    if (roleFilter === "all") {
      return all;
    }
    return all.filter(
      (d) => !d.roles || d.roles.length === 0 || d.roles.includes("all") || d.roles.includes(roleFilter)
    );
  }, [joinAnalysisData?.diagnostics, roleFilter]);

  const activeDiagnostic = useMemo(
    () => filteredDiagnostics.find((d) => d.id === selectedDiagId) ?? null,
    [filteredDiagnostics, selectedDiagId]
  );

  const highlightedNodeIds = useMemo(
    () => new Set(activeDiagnostic?.evidenceNodeIds ?? []),
    [activeDiagnostic]
  );
  const highlightedEdgeIds = useMemo(
    () => new Set(activeDiagnostic?.edgeIds ?? []),
    [activeDiagnostic]
  );

  const handleDiagnosticClick = (diag: JoinDiagnostic) => {
    const isDeselecting = selectedDiagId === diag.id;
    setSelectedDiagId(isDeselecting ? null : diag.id);
    if (isDeselecting) {
      setEditorPinnedCoords([]);
      return;
    }
    if (!joinAnalysisData) return;

    const fragmentId = diag.fragmentIds?.[0];
    if (fragmentId) {
      const frag = joinAnalysisData.fragments?.find((f) => f.fragmentId === fragmentId);
      if (frag?.sourceRange) {
        highlightRangeInEditor(frag.sourceRange);
        return;
      }
    }
    const evidenceNodeId = diag.evidenceNodeIds?.[0];
    if (evidenceNodeId) {
      const node = joinAnalysisData.nodes.find((n) => n.id === evidenceNodeId);
      const range =
        node?.data.join?.sourceRange ??
        node?.data.join?.predicates?.[0]?.sourceRange;
      if (range) {
        highlightRangeInEditor(range);
      }
    }
  };

  const handleCanvasSelect = (sel: JoinFlowSelection) => {
    if (sel.sourceRange) {
      highlightRangeInEditor(sel.sourceRange);
    } else {
      setEditorPinnedCoords([]);
    }
  };

  return (
    <div
      data-testid="join-analysis-view"
      style={{
        display: "flex",
        flexDirection: "column",
        width: "100%",
        height: "100%",
        background: "#f8fafc"
      }}
    >
      {/* Top Bar */}
      <div
        style={{
          height: 42,
          padding: "0 12px",
          background: "#ffffff",
          borderBottom: "1px solid #e2e8f0",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between"
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 10, fontSize: 12 }}>
          <strong>{isCn ? "Join 深度关联分析" : "Join Analysis V2"}</strong>
          {joinAnalysisData && (
            <span style={{ color: "#64748b" }}>
              {isCn ? "表：" : "Tables: "}
              {joinAnalysisData.summary?.tableCount ?? joinAnalysisData.nodes.filter((n) => n.type !== "join").length}
              {" • "}
              {isCn ? "连接：" : "Joins: "}
              {joinAnalysisData.summary?.joinCount ?? joinAnalysisData.nodes.filter((n) => n.type === "join").length}
              {" • "}
              {isCn ? "风险项：" : "Risks: "}
              {joinAnalysisData.diagnostics.length}
            </span>
          )}
        </div>

        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <select
            aria-label="Diagnostic Role Filter"
            data-testid="join-role-filter"
            className="sf-select"
            value={roleFilter}
            onChange={(e) => setRoleFilter(e.target.value as JoinDiagnosticRole)}
          >
            <option value="all">{isCn ? "视角：全部" : "Role: All"}</option>
            <option value="data_engineer">{isCn ? "数据工程师" : "Data Engineer"}</option>
            <option value="data_analyst">{isCn ? "数据分析师" : "Data Analyst"}</option>
            <option value="dba">DBA</option>
          </select>
          <button
            type="button"
            data-testid="join-refresh-btn"
            className="sf-btn sf-btn--light"
            onClick={() => void visualizeJoinAnalysis()}
          >
            <RefreshCw size={12} />
            {isCn ? "重新分析 Join" : "Analyze Joins"}
          </button>
        </div>
      </div>

      {/* Fallback Banner */}
      {joinAnalysisData?.fallback && (
        <div
          data-testid="join-fallback-banner"
          style={{
            padding: "6px 12px",
            background: "#fffbeb",
            borderBottom: "1px solid #fde68a",
            color: "#92400e",
            fontSize: 11,
            display: "flex",
            alignItems: "center",
            gap: 6
          }}
        >
          <AlertTriangle size={13} />
          <span>
            Fallback ({joinAnalysisData.fallback.source}):{" "}
            {joinAnalysisData.fallback.limitations.join("; ")}
          </span>
        </div>
      )}

      {/* Split Canvas + 10-DiagnosticCode Inspector */}
      <div style={{ flex: 1, minHeight: 0, display: "flex" }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          {!joinAnalysisData ? (
            <div style={{ padding: 36, textAlign: "center", color: "#64748b", fontSize: 13 }}>
              {isCn ? (
                <>
                  点击右上角 <strong>重新分析 Join</strong> 运行 Join 关联风险诊断。
                </>
              ) : (
                <>
                  Click <strong>Analyze Joins</strong> to run Join Analysis V2 (`join-analysis.v1`).
                </>
              )}
            </div>
          ) : (
            <JoinFlowCanvas
              model={joinAnalysisData}
              highlightedNodeIds={highlightedNodeIds}
              highlightedEdgeIds={highlightedEdgeIds}
              onSelect={handleCanvasSelect}
            />
          )}
        </div>

        {/* Right Diagnostic Inspector Cards */}
        <div
          data-testid="join-diagnostics-inspector"
          style={{
            width: 310,
            background: "#ffffff",
            borderLeft: "1px solid #e2e8f0",
            display: "flex",
            flexDirection: "column",
            overflowY: "auto"
          }}
        >
          <div
            style={{
              padding: "8px 12px",
              borderBottom: "1px solid #e2e8f0",
              fontWeight: 700,
              fontSize: 12,
              background: "#f8fafc"
            }}
          >
            {isCn ? `Join 风险诊断 (${filteredDiagnostics.length})` : `Join Diagnostics (${filteredDiagnostics.length})`}
          </div>

          <div style={{ padding: 8, display: "flex", flexDirection: "column", gap: 8 }}>
            {filteredDiagnostics.length === 0 ? (
              <div style={{ fontSize: 12, color: "#64748b", padding: 8 }}>
                {isCn ? "当前筛选条件下未发现 Join 风险项。" : "No join risk diagnostics flagged for current filter."}
              </div>
            ) : (
              filteredDiagnostics.map((diag) => {
                const desc = descriptorByCode.get(diag.code);
                const selected = diag.id === selectedDiagId;
                const sevBg =
                  diag.severity === "block"
                    ? "#fef2f2"
                    : diag.severity === "warn"
                      ? "#fffbeb"
                      : "#eff6ff";
                const sevBorder =
                  diag.severity === "block"
                    ? "#ef4444"
                    : diag.severity === "warn"
                      ? "#f59e0b"
                      : "#3b82f6";
                return (
                  <div
                    key={diag.id}
                    data-testid={`join-diag-card-${diag.code}`}
                    onClick={() => handleDiagnosticClick(diag)}
                    style={{
                      padding: "8px 10px",
                      borderRadius: 6,
                      background: sevBg,
                      border: selected ? `2px solid ${sevBorder}` : "1px solid #e2e8f0",
                      cursor: "pointer"
                    }}
                  >
                    <div
                      style={{
                        display: "flex",
                        alignItems: "center",
                        justifyContent: "space-between",
                        marginBottom: 4
                      }}
                    >
                      <strong style={{ fontSize: 11, color: "#0f172a" }}>
                        {desc?.label ?? diag.code}
                      </strong>
                      <span
                        style={{
                          fontSize: 9,
                          fontWeight: 700,
                          textTransform: "uppercase",
                          padding: "1px 5px",
                          borderRadius: 4,
                          background: sevBorder,
                          color: "#ffffff"
                        }}
                      >
                        {diag.severity}
                      </span>
                    </div>
                    <div style={{ fontSize: 11, color: "#334155" }}>{diag.message}</div>
                    <div style={{ fontSize: 10, color: "#64748b", marginTop: 4 }}>
                      code: <code>{diag.code}</code>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
