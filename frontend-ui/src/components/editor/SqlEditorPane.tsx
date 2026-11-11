import React, { useCallback, useMemo, useState } from "react";
import {
  SqlCodeEditor,
  type TokenCoordinateHit,
  layoutSqlflowGraph,
  validateClientSqlSubmission
} from "@sqlflow/ui-core";
import { GitBranch, Network, Plus, Table2, Workflow, X } from "lucide-react";
import { useI18n } from "../../i18n/I18nContext";
import { useApp } from "../../state/AppContext";

export const SqlEditorPane: React.FC = () => {
  const { t } = useI18n();
  const {
    api,
    sqlTabs,
    activeSqlTabId,
    activeSqlText,
    setActiveSqlTabId,
    updateActiveSqlText,
    addSqlTab,
    closeSqlTab,
    lineageData,
    sessionId,
    editorHoverCoords,
    editorPinnedCoords,
    activeOccurrenceIndex,
    canvasPinnedTarget,
    setCanvasHoverTarget,
    setCanvasPinnedTarget,
    setLocatedNodeId,
    clearCanvasSelection,
    visualizeLineage,
    visualizeTableLineage,
    visualizeErDiagram,
    visualizeCallGraph,
    visualizeJoinAnalysis,
    setActiveRightTab,
    isGuest,
    loginDisabled,
    runtimeConfig,
    showToast
  } = useApp();

  const [cursorPos, setCursorPos] = useState<{ line: number; col: number }>({
    line: 1,
    col: 1
  });

  const graphModel = useMemo(
    () => (lineageData ? layoutSqlflowGraph(lineageData) : null),
    [lineageData]
  );

  const guardStatus = useMemo(() => {
    if (runtimeConfig.Product === "private") {
      if (runtimeConfig.trialVersion) {
        return validateClientSqlSubmission({
          sqltext: activeSqlText,
          isLogin: true,
          loginDisable: true,
          trialVersion: true
        });
      }
      return { allowed: true as const };
    }
    return validateClientSqlSubmission({
      sqltext: activeSqlText,
      isLogin: !isGuest,
      loginDisable: loginDisabled,
      trialVersion: runtimeConfig.trialVersion
    });
  }, [activeSqlText, isGuest, loginDisabled, runtimeConfig]);

  const handleTokenHover = useCallback(
    (_line1Based: number, _col1Based: number, hit?: TokenCoordinateHit) => {
      if (!hit) {
        setCanvasHoverTarget(null);
        return;
      }
      if (hit.kind === "column" && hit.columnId) {
        setCanvasHoverTarget({ kind: "column", columnId: hit.columnId });
      } else {
        setCanvasHoverTarget({ kind: "node", nodeId: hit.nodeId });
      }
    },
    [setCanvasHoverTarget]
  );

  const handleTokenClick = useCallback(
    async (line1Based: number, col1Based: number, hit?: TokenCoordinateHit) => {
      if (hit) {
        if (hit.kind === "column" && hit.columnId) {
          if (
            canvasPinnedTarget?.kind === "column" &&
            canvasPinnedTarget.columnId === hit.columnId
          ) {
            clearCanvasSelection();
            return;
          }
          setCanvasPinnedTarget({ kind: "column", columnId: hit.columnId });
          setLocatedNodeId(null);
        } else {
          if (
            canvasPinnedTarget?.kind === "node" &&
            canvasPinnedTarget.nodeId === hit.nodeId
          ) {
            clearCanvasSelection();
            return;
          }
          setCanvasPinnedTarget({ kind: "node", nodeId: hit.nodeId });
          setLocatedNodeId(hit.nodeId);
        }
        return;
      }
      if (sessionId) {
        try {
          const res = await api.client.getSelectedDbObjectInfo({
            sessionId,
            coordinates: [{ x: line1Based, y: col1Based }]
          });
          const first = res?.data?.[0];
          if (first && first.positions && first.positions.length > 0) {
            const firstPt = first.positions[0]!;
            const matchNode = graphModel?.nodes.find((n) =>
              n.coordinates.some((c) => c.x === firstPt.x && (!c.x1 || c.x1 === c.x))
            );
            if (matchNode) {
              setCanvasPinnedTarget({ kind: "node", nodeId: matchNode.id });
              setLocatedNodeId(matchNode.id);
              return;
            }
          }
        } catch {
          // Ignore coordinate lookup error when offline
        }
      }
      if (canvasPinnedTarget || editorPinnedCoords.length > 0) {
        clearCanvasSelection();
      }
    },
    [
      api,
      sessionId,
      graphModel,
      canvasPinnedTarget,
      editorPinnedCoords.length,
      setCanvasPinnedTarget,
      setLocatedNodeId,
      clearCanvasSelection
    ]
  );

  const runGuarded = (fn: () => Promise<void>) => {
    if (!guardStatus.allowed) {
      showToast(guardStatus.message);
      return;
    }
    void fn();
  };

  return (
    <div
      data-testid="sql-editor-pane"
      style={{
        display: "flex",
        flexDirection: "column",
        width: "100%",
        height: "100%",
        background: "#ffffff",
        borderRight: "1px solid #e2e8f0",
        minWidth: 0
      }}
    >
      {/* Multi-file SQL Tab Bar */}
      <div
        data-testid="sql-tabs-bar"
        style={{
          display: "flex",
          alignItems: "center",
          gap: 2,
          padding: "4px 6px 0",
          background: "#f1f5f9",
          borderBottom: "1px solid #cbd5e1",
          overflowX: "auto"
        }}
      >
        {sqlTabs.map((tab) => {
          const isActive = tab.id === activeSqlTabId;
          return (
            <div
              key={tab.id}
              data-testid={`sql-tab-${tab.id}`}
              onClick={() => setActiveSqlTabId(tab.id)}
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 6,
                padding: "5px 10px",
                fontSize: 12,
                fontWeight: isActive ? 600 : 400,
                background: isActive ? "#ffffff" : "#e2e8f0",
                color: isActive ? "#0f172a" : "#475569",
                borderTopLeftRadius: 5,
                borderTopRightRadius: 5,
                border: "1px solid #cbd5e1",
                borderBottom: isActive ? "1px solid #ffffff" : "1px solid #cbd5e1",
                cursor: "pointer",
                marginBottom: -1
              }}
            >
              <span>{tab.name}</span>
              {sqlTabs.length > 1 && (
                <button
                  type="button"
                  aria-label={`Close ${tab.name}`}
                  onClick={(e) => {
                    e.stopPropagation();
                    closeSqlTab(tab.id);
                  }}
                  style={{
                    border: "none",
                    background: "transparent",
                    padding: 0,
                    cursor: "pointer",
                    display: "inline-flex",
                    color: "#64748b"
                  }}
                >
                  <X size={12} />
                </button>
              )}
            </div>
          );
        })}
        <button
          type="button"
          data-testid="add-sql-tab-btn"
          onClick={() => addSqlTab()}
          title={t("editor.newTab")}
          style={{
            border: "none",
            background: "transparent",
            padding: "4px 8px",
            cursor: "pointer",
            color: "#334155",
            display: "inline-flex",
            alignItems: "center"
          }}
        >
          <Plus size={14} />
        </button>
      </div>

      {/* Editor Quick Analysis Action Strip */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          flexWrap: "wrap",
          gap: 6,
          padding: "6px 8px",
          background: "#f8fafc",
          borderBottom: "1px solid #e2e8f0"
        }}
      >
        <button
          type="button"
          data-testid="editor-run-column-lineage"
          className="sf-btn sf-btn--light"
          onClick={() => runGuarded(() => visualizeLineage())}
        >
          <GitBranch size={13} />
          {t("editor.visualizeColumn")}
        </button>
        <button
          type="button"
          data-testid="editor-run-table-lineage"
          className="sf-btn sf-btn--light"
          onClick={() => runGuarded(() => visualizeTableLineage())}
        >
          <Table2 size={13} />
          {t("editor.visualizeTable")}
        </button>
        <button
          type="button"
          data-testid="editor-run-er"
          className="sf-btn sf-btn--light"
          onClick={() => runGuarded(() => visualizeErDiagram())}
        >
          <Network size={13} />
          {t("editor.erDiagram")}
        </button>
        <button
          type="button"
          data-testid="editor-run-callgraph"
          className="sf-btn sf-btn--light"
          onClick={() => runGuarded(() => visualizeCallGraph())}
        >
          <Workflow size={13} />
          {t("editor.callGraph")}
        </button>
        <button
          type="button"
          data-testid="editor-run-join"
          className="sf-btn sf-btn--light"
          onClick={() => runGuarded(() => visualizeJoinAnalysis())}
        >
          <GitBranch size={13} />
          {t("editor.joinAnalysis")}
        </button>
      </div>

      {!guardStatus.allowed && (
        <div
          data-testid="sql-guard-warning"
          style={{
            padding: "4px 10px",
            background: "#fef3c7",
            color: "#92400e",
            fontSize: 11,
            borderBottom: "1px solid #fde68a"
          }}
        >
          {guardStatus.message}
        </div>
      )}

      {/* Main Bidirectional SQL Editor */}
      <div style={{ flex: 1, minHeight: 0, position: "relative" }}>
        <SqlCodeEditor
          value={activeSqlText}
          onChange={updateActiveSqlText}
          hoverCoordinates={editorHoverCoords}
          highlightCoordinates={editorPinnedCoords}
          activeOccurrenceIndex={activeOccurrenceIndex}
          errors={graphModel?.errors}
          tokenIndex={graphModel?.tokenIndex}
          onTokenHover={handleTokenHover}
          onTokenClick={(ln, col, hit) => void handleTokenClick(ln, col, hit)}
          onCursorPositionChange={(line, col) => setCursorPos({ line, col })}
          onErrorMarkerClick={() => setActiveRightTab("error")}
          height="100%"
        />
      </div>

      {/* Footer Status Bar */}
      <div
        style={{
          height: 24,
          padding: "0 10px",
          background: "#f8fafc",
          borderTop: "1px solid #e2e8f0",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          fontSize: 11,
          color: "#64748b"
        }}
      >
        <span>
          Ln {cursorPos.line}, Col {cursorPos.col} (1-based)
        </span>
        <span>{activeSqlText.length.toLocaleString()} chars</span>
      </div>
    </div>
  );
};
