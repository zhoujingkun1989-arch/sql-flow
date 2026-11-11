import React, { useMemo, useState } from "react";
import { type LineageError, layoutSqlflowGraph } from "@sqlflow/ui-core";
import { AlertCircle, Copy } from "lucide-react";
import { useI18n } from "../../i18n/I18nContext";
import { useApp } from "../../state/AppContext";
import {
  buildLineageCsv,
  collectRelationshipTransforms,
  extractDataFlowFromAny
} from "../../utils/exporters";

export const LineageReportView: React.FC = () => {
  const { locale } = useI18n();
  const isCn = locale === "cn";
  const { lineageData, showToast } = useApp();
  const [filterText, setFilterText] = useState("");

  const rows = useMemo(() => {
    const df = extractDataFlowFromAny(lineageData);
    const out: Array<{
      id: string;
      sourceTable: string;
      sourceColumn: string;
      targetTable: string;
      targetColumn: string;
      relationType: string;
      effectType: string;
      transform: string;
    }> = [];

    for (const rel of df.relationships) {
      const tgt = rel.target;
      if (!tgt) continue;
      for (const src of rel.sources ?? []) {
        const transformStr = collectRelationshipTransforms(rel, src)
          .map((t) => t.code)
          .join("; ");
        out.push({
          id: `${rel.id}-${src.id}-${tgt.id}`,
          sourceTable: src.parentName ?? "",
          sourceColumn: src.column ?? "",
          targetTable: tgt.parentName ?? "",
          targetColumn: tgt.column ?? "",
          relationType: rel.type ?? "fdd",
          effectType: rel.effectType ?? "",
          transform: transformStr
        });
      }
    }
    const q = filterText.trim().toLowerCase();
    if (!q) return out;
    return out.filter(
      (r) =>
        r.sourceTable.toLowerCase().includes(q) ||
        r.sourceColumn.toLowerCase().includes(q) ||
        r.targetTable.toLowerCase().includes(q) ||
        r.targetColumn.toLowerCase().includes(q) ||
        r.transform.toLowerCase().includes(q)
    );
  }, [lineageData, filterText]);

  const handleCopy = (delimiter: "," | "\t", label: string) => {
    const text = buildLineageCsv(lineageData, delimiter);
    void navigator.clipboard?.writeText?.(text);
    showToast(`Copied ${label} to clipboard`);
  };

  return (
    <div
      data-testid="lineage-report-view"
      style={{
        display: "flex",
        flexDirection: "column",
        width: "100%",
        height: "100%",
        background: "#ffffff"
      }}
    >
      <div
        style={{
          height: 42,
          padding: "0 12px",
          borderBottom: "1px solid #e2e8f0",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          background: "#f8fafc"
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <strong style={{ fontSize: 12 }}>
            {isCn ? `血缘关系明细表 (${rows.length})` : `Tabular Lineage Report (${rows.length})`}
          </strong>
          <input
            type="text"
            placeholder={isCn ? "筛选表名、字段或转换表达式..." : "Filter table, column, transform..."}
            className="sf-input"
            value={filterText}
            onChange={(e) => setFilterText(e.target.value)}
            style={{ width: 220 }}
          />
        </div>
        <div style={{ display: "flex", gap: 6 }}>
          <button
            type="button"
            data-testid="report-copy-csv-btn"
            className="sf-btn sf-btn--light"
            onClick={() => handleCopy(",", "CSV")}
          >
            <Copy size={12} />
            {isCn ? "复制 CSV" : "Copy CSV"}
          </button>
          <button
            type="button"
            data-testid="report-copy-tsv-btn"
            className="sf-btn sf-btn--light"
            onClick={() => handleCopy("\t", "TSV")}
          >
            <Copy size={12} />
            {isCn ? "复制 TSV" : "Copy TSV"}
          </button>
        </div>
      </div>

      <div style={{ flex: 1, overflow: "auto" }}>
        <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 12 }}>
          <thead>
            <tr style={{ background: "#f1f5f9", position: "sticky", top: 0 }}>
              <th style={{ textAlign: "left", padding: "8px 10px", borderBottom: "1px solid #cbd5e1" }}>
                {isCn ? "源表" : "Source Table"}
              </th>
              <th style={{ textAlign: "left", padding: "8px 10px", borderBottom: "1px solid #cbd5e1" }}>
                {isCn ? "源字段" : "Source Column"}
              </th>
              <th style={{ textAlign: "left", padding: "8px 10px", borderBottom: "1px solid #cbd5e1" }}>
                {isCn ? "目标表" : "Target Table"}
              </th>
              <th style={{ textAlign: "left", padding: "8px 10px", borderBottom: "1px solid #cbd5e1" }}>
                {isCn ? "目标字段" : "Target Column"}
              </th>
              <th style={{ textAlign: "left", padding: "8px 10px", borderBottom: "1px solid #cbd5e1" }}>
                {isCn ? "关系类型" : "Type"}
              </th>
              <th style={{ textAlign: "left", padding: "8px 10px", borderBottom: "1px solid #cbd5e1" }}>
                {isCn ? "转换表达式" : "Transform"}
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={6} style={{ padding: 24, textAlign: "center", color: "#64748b" }}>
                  {isCn ? "暂无血缘明细数据。" : "No lineage rows to display."}
                </td>
              </tr>
            ) : (
              rows.map((r) => (
                <tr key={r.id}>
                  <td style={{ padding: "6px 10px", borderBottom: "1px solid #f1f5f9" }}>
                    {r.sourceTable}
                  </td>
                  <td style={{ padding: "6px 10px", borderBottom: "1px solid #f1f5f9" }}>
                    {r.sourceColumn}
                  </td>
                  <td style={{ padding: "6px 10px", borderBottom: "1px solid #f1f5f9" }}>
                    {r.targetTable}
                  </td>
                  <td style={{ padding: "6px 10px", borderBottom: "1px solid #f1f5f9" }}>
                    {r.targetColumn}
                  </td>
                  <td style={{ padding: "6px 10px", borderBottom: "1px solid #f1f5f9" }}>
                    <code>{r.relationType}</code>
                  </td>
                  <td
                    style={{
                      padding: "6px 10px",
                      borderBottom: "1px solid #f1f5f9",
                      fontFamily: "monospace",
                      color: "#334155"
                    }}
                  >
                    {r.transform || "-"}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export const ErrorDiagnosticsView: React.FC = () => {
  const { locale } = useI18n();
  const isCn = locale === "cn";
  const {
    lineageData,
    activeJobSummary,
    activeSqlText,
    setEditorPinnedCoords,
    setSlideUpDrawer
  } = useApp();
  const [typeFilter, setTypeFilter] = useState("ALL");

  const errors = useMemo<LineageError[]>(() => {
    const model = lineageData ? layoutSqlflowGraph(lineageData) : null;
    const list: LineageError[] = [...(model?.errors ?? [])];
    if (activeJobSummary?.errorMessages) {
      for (const msg of activeJobSummary.errorMessages) {
        list.push({
          errorType: "JobError",
          errorMessage: msg
        });
      }
    } else if (activeJobSummary?.errorMessage) {
      list.push({
        errorType: "JobError",
        errorMessage: activeJobSummary.errorMessage
      });
    }
    if (typeFilter === "ALL") return list;
    return list.filter((e) => (e.errorType ?? "SyntaxError") === typeFilter);
  }, [lineageData, activeJobSummary, typeFilter]);

  const jumpToError = (err: LineageError) => {
    const coords = err.coordinates ?? [{ x: 1, y: 1 }];
    setEditorPinnedCoords(coords);
    setSlideUpDrawer({
      open: true,
      title: `${err.errorType ?? "SyntaxError"}`,
      sql: activeSqlText,
      coordinates: coords,
      occurrenceIndex: 0
    });
  };

  return (
    <div
      data-testid="error-diagnostics-view"
      style={{
        display: "flex",
        flexDirection: "column",
        width: "100%",
        height: "100%",
        background: "#ffffff"
      }}
    >
      <div
        style={{
          height: 42,
          padding: "0 12px",
          borderBottom: "1px solid #e2e8f0",
          background: "#f8fafc",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between"
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 12 }}>
          <AlertCircle size={14} color="#dc2626" />
          <strong>
            {isCn ? `SQL 语法与任务诊断 (${errors.length})` : `SQL & Job Diagnostics (${errors.length})`}
          </strong>
        </div>
        <select
          aria-label="Error Type Filter"
          className="sf-select"
          value={typeFilter}
          onChange={(e) => setTypeFilter(e.target.value)}
        >
          <option value="ALL">{isCn ? "全部类型" : "All Types"}</option>
          <option value="SyntaxError">{isCn ? "语法错误 (SyntaxError)" : "SyntaxError"}</option>
          <option value="SemanticError">{isCn ? "语义错误 (SemanticError)" : "SemanticError"}</option>
          <option value="JobError">{isCn ? "任务错误 (JobError)" : "JobError"}</option>
        </select>
      </div>

      <div style={{ flex: 1, overflowY: "auto", padding: 12, display: "flex", flexDirection: "column", gap: 8 }}>
        {errors.length === 0 ? (
          <div style={{ padding: 24, textAlign: "center", color: "#64748b", fontSize: 13 }}>
            {isCn ? "未检测到任何 SQL 语法或批处理任务错误。" : "Zero syntax or job errors detected."}
          </div>
        ) : (
          errors.map((err, idx) => {
            const firstCoord = err.coordinates?.[0];
            return (
              <div
                key={`err-${idx}`}
                data-testid={`diagnostic-error-item-${idx}`}
                onClick={() => jumpToError(err)}
                style={{
                  padding: "10px 12px",
                  borderRadius: 6,
                  border: "1px solid #fecaca",
                  background: "#fef2f2",
                  cursor: "pointer"
                }}
              >
                <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 4 }}>
                  <strong style={{ fontSize: 12, color: "#991b1b" }}>
                    {err.errorType ?? "SyntaxError"}
                  </strong>
                  {firstCoord && (
                    <span style={{ fontSize: 11, color: "#b91c1c" }}>
                      {isCn ? `第 ${firstCoord.x} 行，第 ${firstCoord.y} 列` : `Line ${firstCoord.x}, Col ${firstCoord.y}`}
                    </span>
                  )}
                </div>
                <div style={{ fontSize: 12, color: "#7f1d1d", fontFamily: "monospace" }}>
                  {err.errorMessage}
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};
