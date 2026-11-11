import React, { useEffect, useMemo, useRef, useState } from "react";
import {
  HashRouter,
  MemoryRouter,
  Route,
  Routes,
  useLocation,
  useNavigate,
  useParams,
  useSearchParams
} from "react-router-dom";
import {
  UI_DIALECT_KEYS,
  type UiDialectKey,
  layoutSqlflowGraph,
  normalizeDbVendor,
  toShortVendorKey
} from "@sqlflow/ui-core";
import { LineageWorkspaceCanvas } from "./components/canvas/LineageWorkspaceCanvas";
import { SqlEditorPane } from "./components/editor/SqlEditorPane";
import { SystemModalsHost } from "./components/modals/SystemModals";
import { AiAssistantDrawer } from "./components/panels/AiAssistantDrawer";
import { JobCenterPanel } from "./components/panels/JobCenterPanel";
import { SchemaExplorerPanel } from "./components/panels/SchemaExplorerPanel";
import { LeftIconRail } from "./components/shell/LeftIconRail";
import { TopHeaderBar } from "./components/shell/TopHeaderBar";
import { CallGraphView } from "./components/tabs/CallGraphView";
import { ErDiagramView } from "./components/tabs/ErDiagramView";
import { JoinAnalysisView } from "./components/tabs/JoinAnalysisView";
import { ErrorDiagnosticsView, LineageReportView } from "./components/tabs/OutputAndErrorsView";
import { I18nProvider, type LocaleCode, useI18n } from "./i18n/I18nContext";
import { registerJoinIslandGlobal } from "./joinflow/islandEntry";
import { type SpaApiService } from "./services/spaApi";
import { AppProvider, type RightWorkspaceTab, useApp } from "./state/AppContext";

const MainWorkspaceShell: React.FC<{ routeBanner?: React.ReactNode }> = ({ routeBanner }) => {
  const { t } = useI18n();
  const {
    activeLeftPanel,
    activeRightTab,
    setActiveRightTab,
    lineageData,
    activeJobSummary,
    visualizeTableLineage,
    visualizeErDiagram,
    visualizeCallGraph,
    visualizeJoinAnalysis,
    tableLineageData,
    erData,
    callGraphData,
    joinAnalysisData,
    toastMessage
  } = useApp();

  const [leftWidth, setLeftWidth] = useState(560);
  const canvasExportRef = useRef<HTMLDivElement | null>(null);

  const errorCount = useMemo(() => {
    const model = lineageData ? layoutSqlflowGraph(lineageData) : null;
    const jobErrs = activeJobSummary?.errorMessages?.length ?? (activeJobSummary?.errorMessage ? 1 : 0);
    return (model?.errors.length ?? 0) + jobErrs;
  }, [lineageData, activeJobSummary]);

  const handleResizerMouseDown = (e: React.MouseEvent<HTMLDivElement>) => {
    e.preventDefault();
    const startX = e.clientX;
    const origWidth = leftWidth;
    const onMove = (ev: MouseEvent) => {
      const next = Math.min(920, Math.max(280, origWidth + (ev.clientX - startX)));
      setLeftWidth(next);
    };
    const onUp = () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  };

  const handleRightTabSelect = (tab: RightWorkspaceTab) => {
    setActiveRightTab(tab);
    if (tab === "tableLineage" && !tableLineageData) {
      void visualizeTableLineage();
    } else if (tab === "er" && !erData) {
      void visualizeErDiagram();
    } else if (tab === "callgraph" && !callGraphData) {
      void visualizeCallGraph();
    } else if (tab === "join" && !joinAnalysisData) {
      void visualizeJoinAnalysis();
    }
  };

  return (
    <div className="sf-app-shell" data-testid="sqlflow-app-shell">
      <TopHeaderBar canvasExportElementRef={canvasExportRef} />
      {routeBanner}

      <div className="sf-main-body">
        <LeftIconRail />

        <div className="sf-workspace-split">
          {/* Resizable Left Pane (`defaultWidth: 560px`) */}
          <div
            style={{
              width: leftWidth,
              minWidth: 280,
              maxWidth: "60vw",
              height: "100%",
              flexShrink: 0
            }}
          >
            {activeLeftPanel === "sqlflow" && <SqlEditorPane />}
            {activeLeftPanel === "schema" && <SchemaExplorerPanel />}
            {activeLeftPanel === "job" && <JobCenterPanel />}
            {activeLeftPanel === "ai" && <AiAssistantDrawer />}
          </div>

          <div
            data-testid="workspace-split-resizer"
            className="sf-resizer-v"
            onMouseDown={handleResizerMouseDown}
          />

          {/* Right Analysis Tabs + Active View */}
          <div
            style={{
              flex: 1,
              minWidth: 0,
              height: "100%",
              display: "flex",
              flexDirection: "column"
            }}
          >
            <div
              data-testid="right-workspace-tabs"
              style={{
                display: "flex",
                alignItems: "center",
                gap: 2,
                padding: "4px 8px 0",
                background: "#e2e8f0",
                borderBottom: "1px solid #cbd5e1",
                overflowX: "auto"
              }}
            >
              {(
                [
                  ["columnLineage", t("tab.columnLineage")],
                  ["tableLineage", t("tab.tableLineage")],
                  ["er", t("tab.er")],
                  ["callgraph", t("tab.callgraph")],
                  ["join", t("tab.join")],
                  ["output", t("tab.output")],
                  ["error", t("tab.error")]
                ] as const
              ).map(([tabKey, label]) => {
                const isActive = activeRightTab === tabKey;
                return (
                  <button
                    key={tabKey}
                    type="button"
                    data-testid={`right-tab-${tabKey}`}
                    onClick={() => handleRightTabSelect(tabKey)}
                    style={{
                      border: "1px solid #cbd5e1",
                      borderBottom: isActive ? "1px solid #ffffff" : "1px solid #cbd5e1",
                      background: isActive ? "#ffffff" : "#f1f5f9",
                      color: isActive ? "#0f172a" : "#475569",
                      fontWeight: isActive ? 700 : 500,
                      fontSize: 12,
                      padding: "6px 12px",
                      borderTopLeftRadius: 6,
                      borderTopRightRadius: 6,
                      cursor: "pointer",
                      marginBottom: -1,
                      display: "inline-flex",
                      alignItems: "center",
                      gap: 5,
                      whiteSpace: "nowrap"
                    }}
                  >
                    <span>{label}</span>
                    {tabKey === "error" && errorCount > 0 && (
                      <span
                        data-testid="error-tab-badge"
                        style={{
                          fontSize: 10,
                          fontWeight: 700,
                          padding: "0 5px",
                          borderRadius: 999,
                          background: "#dc2626",
                          color: "#ffffff"
                        }}
                      >
                        {errorCount}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>

            <div style={{ flex: 1, minHeight: 0, position: "relative" }}>
              {activeRightTab === "columnLineage" && (
                <LineageWorkspaceCanvas
                  tableLevel={false}
                  exportContainerRef={canvasExportRef}
                />
              )}
              {activeRightTab === "tableLineage" && (
                <LineageWorkspaceCanvas
                  tableLevel={true}
                  exportContainerRef={canvasExportRef}
                />
              )}
              {activeRightTab === "er" && <ErDiagramView />}
              {activeRightTab === "callgraph" && <CallGraphView />}
              {activeRightTab === "join" && <JoinAnalysisView />}
              {activeRightTab === "output" && <LineageReportView />}
              {activeRightTab === "error" && <ErrorDiagnosticsView />}
            </div>
          </div>
        </div>
      </div>

      {/* Toast Notification */}
      {toastMessage && (
        <div
          data-testid="app-toast"
          style={{
            position: "fixed",
            bottom: 18,
            right: 18,
            zIndex: 130,
            background: "#0f172a",
            color: "#ffffff",
            padding: "8px 14px",
            borderRadius: 6,
            fontSize: 12,
            boxShadow: "0 8px 20px rgba(0,0,0,0.25)"
          }}
        >
          {toastMessage}
        </div>
      )}

      <SystemModalsHost />
    </div>
  );
};

/**
 * `/#/` Home Route with support for `?show=login` and `#tablename=...` / `?tablename=...` deep links (`OBL-093`).
 */
const HomeRouteView: React.FC = () => {
  const [searchParams] = useSearchParams();
  const location = useLocation();
  const {
    api,
    dialect,
    setActiveModal,
    setLineageResult,
    setLocatedNodeId
  } = useApp();

  useEffect(() => {
    if (searchParams.get("show") === "login") {
      setActiveModal("login");
    }
    const tableParam =
      searchParams.get("tablename") ??
      new URLSearchParams(location.hash.replace(/^#\/?\??/, "")).get("tablename");
    const columnParam = searchParams.get("attributename") ?? undefined;
    const jobIdParam = searchParams.get("jobId") ?? undefined;

    if (tableParam) {
      void api.client
        .selectGraph({
          dbvendor: dialect,
          table: tableParam,
          column: columnParam,
          jobId: jobIdParam,
          columnLevel: true
        })
        .then((res) => {
          if (res?.data) {
            setLineageResult(res.data, res.sessionId);
          }
          setLocatedNodeId(tableParam);
        })
        .catch(() => {
          setLocatedNodeId(tableParam);
        });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.search, location.hash]);

  return <MainWorkspaceShell />;
};

/**
 * `/#/share?shareId=...&tableLevel=true` Shared Graph Route (`OBL-093`).
 */
const ShareRouteView: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { t } = useI18n();
  const { api, setLineageResult, setActiveRightTab } = useApp();
  const shareId = searchParams.get("shareId") ?? "";
  const tableLevel = searchParams.get("tableLevel") === "true";

  useEffect(() => {
    if (!shareId) return;
    void api.client
      .getSharedGraph(shareId)
      .then((res) => {
        if (res?.data) {
          setLineageResult(res.data, res.sessionId);
          setActiveRightTab(tableLevel ? "tableLineage" : "columnLineage");
        }
      })
      .catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shareId, tableLevel]);

  return (
    <MainWorkspaceShell
      routeBanner={
        <div
          data-testid="share-route-banner"
          style={{
            padding: "5px 14px",
            background: "#eff6ff",
            borderBottom: "1px solid #bfdbfe",
            fontSize: 12,
            color: "#1e40af",
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center"
          }}
        >
          <span>
            {t("route.shareBanner")} <code>{shareId || "unspecified"}</code>
          </span>
          <button
            type="button"
            className="sf-btn sf-btn--light"
            style={{ height: 24, fontSize: 11 }}
            onClick={() => navigate("/")}
          >
            {t("route.backWorkspace")}
          </button>
        </div>
      }
    />
  );
};

/**
 * `/#/p/:permalinkId` Permalink Route (`OBL-093`).
 */
const PermalinkRouteView: React.FC = () => {
  const { permalinkId } = useParams<{ permalinkId: string }>();
  const navigate = useNavigate();
  const { t } = useI18n();
  const { api, setDialect, updateActiveSqlText, setLineageResult, visualizeLineage } = useApp();

  useEffect(() => {
    if (!permalinkId) return;
    void api
      .getPermalinkData(permalinkId)
      .then(async (record) => {
        if (!record) {
          const fallbackShare = await api.client.getSharedGraph(permalinkId).catch(() => null);
          if (fallbackShare?.data) {
            setLineageResult(fallbackShare.data, fallbackShare.sessionId);
          }
          return;
        }
        const rawShort = toShortVendorKey(normalizeDbVendor(record.dbvendor));
        const shortDialect: UiDialectKey = (
          UI_DIALECT_KEYS as readonly string[]
        ).includes(rawShort)
          ? (rawShort as UiDialectKey)
          : "oracle";
        setDialect(shortDialect, false);
        if (record.sqltext) {
          updateActiveSqlText(record.sqltext);
        }
        if (record.graph) {
          setLineageResult(record.graph);
        } else if (record.sqltext) {
          void visualizeLineage(record.sqltext, shortDialect);
        }
      })
      .catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [permalinkId]);

  return (
    <MainWorkspaceShell
      routeBanner={
        <div
          data-testid="permalink-route-banner"
          style={{
            padding: "5px 14px",
            background: "#f0fdf4",
            borderBottom: "1px solid #bbf7d0",
            fontSize: 12,
            color: "#166534",
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center"
          }}
        >
          <span>
            {t("route.permalinkBanner")} <code>{permalinkId}</code>
          </span>
          <button
            type="button"
            className="sf-btn sf-btn--light"
            style={{ height: 24, fontSize: 11 }}
            onClick={() => navigate("/")}
          >
            {t("route.openEditor")}
          </button>
        </div>
      }
    />
  );
};

/**
 * `/#/job/latest` Latest Job Route (`OBL-093`, Invariant #10 `res.job`, `res.jobId`, `res.sessionId`, `res.data`).
 */
const LatestJobRouteView: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { t } = useI18n();
  const { api, setLineageResult, openJobDetailModal } = useApp();
  const [latestJobName, setLatestJobName] = useState<string>("");
  const [resolvedEmpty, setResolvedEmpty] = useState(false);

  useEffect(() => {
    void api.client
      .displayUserLatestJobGraph()
      .then((res) => {
        const hasRealJob = Boolean(res?.job || res?.jobId);
        const hasObjects = Boolean(
          res?.data &&
            ((Array.isArray(res.data.sqlflow?.dbobjs) && res.data.sqlflow.dbobjs.length > 0) ||
              (!Array.isArray(res.data.sqlflow?.dbobjs) && res.data.sqlflow?.dbobjs) ||
              (Array.isArray((res.data as { dbobjs?: unknown[] }).dbobjs) &&
                ((res.data as { dbobjs?: unknown[] }).dbobjs?.length ?? 0) > 0))
        );
        if (hasRealJob || hasObjects) {
          setLatestJobName(res.job?.jobName ?? res.jobId ?? "latest");
          setResolvedEmpty(false);
          setLineageResult(res.data ?? null, res.sessionId, res.job ?? null);
          if (searchParams.get("view") === "overview" && res.job) {
            openJobDetailModal(res.job);
          }
        } else {
          setResolvedEmpty(true);
        }
      })
      .catch(() => {
        setResolvedEmpty(true);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <MainWorkspaceShell
      routeBanner={
        <div
          data-testid="latest-job-route-banner"
          style={{
            padding: "5px 14px",
            background: "#fefce8",
            borderBottom: "1px solid #fde047",
            fontSize: 12,
            color: "#854d0e",
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center"
          }}
        >
          <span>
            {t("route.latestJobBanner")}{" "}
            <strong>
              {latestJobName || (resolvedEmpty ? t("route.noJobsYet") : "Loading...")}
            </strong>
          </span>
          <button
            type="button"
            className="sf-btn sf-btn--light"
            style={{ height: 24, fontSize: 11 }}
            onClick={() => navigate("/")}
          >
            {t("route.returnHome")}
          </button>
        </div>
      }
    />
  );
};

export interface AppProps {
  customApi?: SpaApiService;
  initialLocale?: LocaleCode;
  /** Used in automated tests to mount specific routes deterministically; defaults to `HashRouter` in production */
  memoryInitialEntries?: string[];
}

export const App: React.FC<AppProps> = ({
  customApi,
  initialLocale,
  memoryInitialEntries
}) => {
  useEffect(() => {
    registerJoinIslandGlobal();
  }, []);

  const routesTree = (
    <Routes>
      <Route path="/" element={<HomeRouteView />} />
      <Route path="/share" element={<ShareRouteView />} />
      <Route path="/p/:permalinkId" element={<PermalinkRouteView />} />
      <Route path="/job/latest" element={<LatestJobRouteView />} />
      <Route path="*" element={<HomeRouteView />} />
    </Routes>
  );

  return (
    <I18nProvider initialLocale={initialLocale}>
      <AppProvider customApi={customApi}>
        {memoryInitialEntries ? (
          <MemoryRouter
            initialEntries={memoryInitialEntries}
            future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
          >
            {routesTree}
          </MemoryRouter>
        ) : (
          <HashRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
            {routesTree}
          </HashRouter>
        )}
      </AppProvider>
    </I18nProvider>
  );
};

export default App;
