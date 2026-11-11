import React, { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import JSZip from "jszip";
import {
  EXAMPLE_FEATURE_LABELS,
  UI_DIALECTS,
  type UiDialectKey,
  getDialectExamples
} from "@sqlflow/ui-core";
import {
  Download,
  FileCode,
  FolderUp,
  Globe,
  Play,
  Share2,
  Sparkles,
  User,
  Wand2
} from "lucide-react";
import { useI18n } from "../../i18n/I18nContext";
import { useApp } from "../../state/AppContext";
import {
  buildDlineageXml,
  buildLineageCsv,
  buildOfflineHtmlZipBlob,
  buildYedGraphMl,
  captureHighDpiClientPng,
  triggerBrowserDownload
} from "../../utils/exporters";

export interface TopHeaderBarProps {
  canvasExportElementRef?: React.RefObject<HTMLDivElement | null>;
}

export const TopHeaderBar: React.FC<TopHeaderBarProps> = ({ canvasExportElementRef }) => {
  const { t, locale, toggleLocale } = useI18n();
  const navigate = useNavigate();
  const {
    api,
    dialect,
    setDialect,
    isDialectLicensed,
    activeSqlText,
    combinedSqlText,
    updateActiveSqlText,
    addSqlTab,
    loadSqlSample,
    visualizeLineage,
    visualizeJoinAnalysis,
    visualizeErDiagram,
    visualizeCallGraph,
    lineageData,
    tableLineageData,
    activeRightTab,
    sessionId,
    activeJobId,
    runtimeConfig,
    isGuest,
    loginDisabled,
    currentUser,
    setActiveModal,
    logoutUser,
    showToast,
    loadingGraph
  } = useApp();

  const [examplesOpen, setExamplesOpen] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const headerRef = useRef<HTMLElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!examplesOpen && !exportOpen && !userMenuOpen) {
      return undefined;
    }
    const handleOutsideMouseDown = (e: MouseEvent) => {
      if (headerRef.current && !headerRef.current.contains(e.target as Node)) {
        setExamplesOpen(false);
        setExportOpen(false);
        setUserMenuOpen(false);
      }
    };
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setExamplesOpen(false);
        setExportOpen(false);
        setUserMenuOpen(false);
      }
    };
    document.addEventListener("mousedown", handleOutsideMouseDown);
    window.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handleOutsideMouseDown);
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [examplesOpen, exportOpen, userMenuOpen]);

  const popularDialects = useMemo(
    () => UI_DIALECTS.filter((d) => d.group === "Popular"),
    []
  );
  const allOtherDialects = useMemo(
    () => UI_DIALECTS.filter((d) => d.group === "All dialects"),
    []
  );
  const activeDialectMeta = useMemo(
    () => UI_DIALECTS.find((d) => d.shortKey === dialect) ?? UI_DIALECTS[0]!,
    [dialect]
  );

  const examplesList = useMemo(() => getDialectExamples(dialect), [dialect]);

  const handleFormatSql = async () => {
    const formatted = await api.formatSqlWithDemoFallback(activeSqlText, dialect);
    updateActiveSqlText(formatted);
    showToast("SQL formatted");
  };

  const handleUploadFiles = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    e.target.value = "";
    if (files.length === 0) return;

    let importedCount = 0;
    for (const file of files) {
      if (file.name.toLowerCase().endsWith(".zip")) {
        try {
          const zip = await JSZip.loadAsync(file);
          const entries = Object.values(zip.files).filter(
            (entry) => !entry.dir && /\.(sql|ddl|pls|prc|pkb)$/i.test(entry.name)
          );
          for (const entry of entries) {
            const text = await entry.async("string");
            addSqlTab(entry.name.split("/").pop() ?? entry.name, text);
            importedCount += 1;
          }
        } catch {
          showToast(`Failed to extract archive ${file.name}`);
        }
      } else {
        const text = await file.text();
        addSqlTab(file.name, text);
        importedCount += 1;
      }
    }
    if (importedCount > 0) {
      showToast(`Loaded ${importedCount} SQL file(s)`);
    }
  };

  const currentExportSource = activeRightTab === "tableLineage" ? tableLineageData : lineageData;

  const handleExport = async (
    kind: "json" | "xml" | "csv" | "graphml" | "serverPng" | "clientPng" | "offlineZip"
  ) => {
    setExportOpen(false);
    try {
      switch (kind) {
        case "json": {
          const content = JSON.stringify(currentExportSource ?? { dbobjs: [], relations: [] }, null, 2);
          triggerBrowserDownload(content, `sqlflow-lineage-${dialect}.json`, "application/json");
          showToast("Exported Lineage JSON");
          break;
        }
        case "xml": {
          const xml = buildDlineageXml(currentExportSource);
          triggerBrowserDownload(xml, `sqlflow-dlineage-${dialect}.xml`, "application/xml");
          showToast("Exported <dlineage> XML");
          break;
        }
        case "csv": {
          let csv = "";
          try {
            csv = await api.client.exportFullLineageAsCsv({
              sqltext: combinedSqlText,
              dbvendor: dialect
            });
          } catch {
            // Fall back to deterministic local CSV serializer
          }
          if (!csv || !csv.trim()) {
            csv = buildLineageCsv(currentExportSource);
          }
          triggerBrowserDownload(csv, `sqlflow-lineage-${dialect}.csv`, "text/csv");
          showToast("Exported Lineage CSV");
          break;
        }
        case "graphml": {
          const graphml = buildYedGraphMl(currentExportSource);
          triggerBrowserDownload(
            graphml,
            `sqlflow-lineage-${dialect}.graphml`,
            "application/graphml+xml"
          );
          showToast("Exported yEd GraphML");
          break;
        }
        case "serverPng": {
          const blob = await api.exportServerPngBlob({
            sqltext: combinedSqlText,
            dbvendor: dialect,
            sessionId,
            jobId: activeJobId
          });
          triggerBrowserDownload(blob, `sqlflow-server-${dialect}.png`, "image/png");
          showToast("Exported Server PNG");
          break;
        }
        case "clientPng": {
          const dataUrl = await captureHighDpiClientPng(
            canvasExportElementRef?.current ?? null,
            { pixelRatio: 2 }
          );
          triggerBrowserDownload(dataUrl, `sqlflow-hd-${dialect}.png`, "image/png");
          showToast("Exported High-DPI Client PNG");
          break;
        }
        case "offlineZip": {
          const { blob } = await buildOfflineHtmlZipBlob(
            currentExportSource,
            `SQLFlow Lineage (${activeDialectMeta.label})`
          );
          triggerBrowserDownload(blob, `sqlflow-offline-${dialect}.zip`, "application/zip");
          showToast("Exported Offline Interactive HTML ZIP");
          break;
        }
      }
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Export failed");
    }
  };

  return (
    <header ref={headerRef} className="sf-top-header" data-testid="top-header-bar">
      {/* Left Brand + Dialect + Visualize */}
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <div
          onClick={() => navigate("/")}
          style={{
            display: "flex",
            alignItems: "center",
            gap: 6,
            cursor: "pointer",
            marginRight: 4
          }}
        >
          <span
            style={{
              width: 24,
              height: 24,
              borderRadius: 5,
              background: "#91c051",
              color: "#111827",
              display: "inline-flex",
              alignItems: "center",
              justifyContent: "center",
              fontWeight: 800,
              fontSize: 13
            }}
          >
            SF
          </span>
          <strong style={{ fontSize: 15, letterSpacing: 0.2 }}>SQLFlow</strong>
          <span
            style={{
              fontSize: 10,
              padding: "1px 5px",
              borderRadius: 3,
              background: "rgba(255,255,255,0.12)",
              color: "#cbd5e1"
            }}
          >
            v3.6.16
          </span>
          {runtimeConfig.trialVersion && (
            <span
              style={{
                fontSize: 10,
                padding: "1px 6px",
                borderRadius: 999,
                background: "#f59e0b",
                color: "#111827",
                fontWeight: 700
              }}
            >
              {t("header.trialBanner")}
            </span>
          )}
        </div>

        {/* 39-Dialect Grouped Selector (Popular [6] + All dialects [33]) */}
        <div style={{ display: "flex", alignItems: "center", gap: 4 }}>
          <span
            style={{
              fontSize: 10,
              fontWeight: 700,
              padding: "2px 5px",
              borderRadius: 3,
              background: activeDialectMeta.badge.bgColor,
              color: activeDialectMeta.badge.color
            }}
          >
            {activeDialectMeta.badge.code}
          </span>
          <select
            aria-label="SQL Dialect"
            data-testid="dialect-selector"
            value={dialect}
            onChange={(e) => setDialect(e.target.value as UiDialectKey, false)}
            style={{
              height: 30,
              borderRadius: 4,
              border: "1px solid #4b5563",
              background: "#26262f",
              color: "#ffffff",
              padding: "0 8px",
              fontSize: 12
            }}
          >
            <optgroup label={`${t("header.popular")} (${popularDialects.length})`}>
              {popularDialects.map((d) => (
                <option key={d.shortKey} value={d.shortKey}>
                  {d.label} {!isDialectLicensed(d.shortKey) ? "🔒" : ""}
                </option>
              ))}
            </optgroup>
            <optgroup label={`${t("header.allDialects")} (${allOtherDialects.length})`}>
              {allOtherDialects.map((d) => (
                <option key={d.shortKey} value={d.shortKey}>
                  {d.label} {!isDialectLicensed(d.shortKey) ? "🔒" : ""}
                </option>
              ))}
            </optgroup>
          </select>
        </div>

        <button
          type="button"
          data-testid="header-visualize-btn"
          className="sf-btn sf-btn--primary"
          disabled={loadingGraph}
          onClick={() => void visualizeLineage()}
        >
          <Play size={13} />
          {t("header.visualize")}
        </button>

        <button
          type="button"
          data-testid="sample-sql-btn"
          className="sf-btn"
          onClick={() => loadSqlSample()}
        >
          <FileCode size={13} />
          {t("header.sampleSql")}
        </button>

        {/* Categorized Examples Dropdown */}
        <div style={{ position: "relative" }}>
          <button
            type="button"
            data-testid="examples-dropdown-btn"
            className="sf-btn"
            onClick={() => {
              setExamplesOpen((v) => !v);
              setExportOpen(false);
              setUserMenuOpen(false);
            }}
          >
            <Sparkles size={13} />
            {t("header.examples")}
          </button>
          {examplesOpen && (
            <div className="sf-dropdown-menu" style={{ left: 0, right: "auto", minWidth: 240 }}>
              {examplesList.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  className="sf-dropdown-item"
                  onClick={() => {
                    setExamplesOpen(false);
                    loadSqlSample(item.sql);
                    if (item.feature === "join") {
                      void visualizeJoinAnalysis(item.sql);
                    } else if (item.feature === "er") {
                      void visualizeErDiagram(item.sql);
                    } else if (item.feature === "callgraph") {
                      void visualizeCallGraph(item.sql);
                    } else {
                      void visualizeLineage(item.sql);
                    }
                  }}
                >
                  <span>{item.label}</span>
                  <span style={{ fontSize: 10, color: "#64748b" }}>
                    {EXAMPLE_FEATURE_LABELS[item.feature]}
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>

        <button
          type="button"
          data-testid="format-sql-btn"
          className="sf-btn"
          onClick={() => void handleFormatSql()}
        >
          <Wand2 size={13} />
          {t("header.formatSql")}
        </button>

        <input
          ref={fileInputRef}
          type="file"
          accept=".sql,.zip,.ddl,.txt"
          multiple
          data-testid="header-upload-input"
          style={{ display: "none" }}
          onChange={(e) => void handleUploadFiles(e)}
        />
        <button
          type="button"
          data-testid="header-upload-btn"
          className="sf-btn"
          onClick={() => fileInputRef.current?.click()}
        >
          <FolderUp size={13} />
          {t("header.upload")}
        </button>
      </div>

      {/* Right Actions: Export, Share, Locale, User Menu */}
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <div style={{ position: "relative" }}>
          <button
            type="button"
            data-testid="export-menu-btn"
            className="sf-btn"
            onClick={() => {
              setExportOpen((v) => !v);
              setExamplesOpen(false);
              setUserMenuOpen(false);
            }}
          >
            <Download size={13} />
            {t("header.export")}
          </button>
          {exportOpen && (
            <div className="sf-dropdown-menu" data-testid="export-dropdown-menu">
              <button
                type="button"
                data-testid="export-json-btn"
                className="sf-dropdown-item"
                onClick={() => void handleExport("json")}
              >
                {t("export.json")}
              </button>
              <button
                type="button"
                data-testid="export-xml-btn"
                className="sf-dropdown-item"
                onClick={() => void handleExport("xml")}
              >
                {t("export.xml")}
              </button>
              <button
                type="button"
                data-testid="export-csv-btn"
                className="sf-dropdown-item"
                onClick={() => void handleExport("csv")}
              >
                {t("export.csv")}
              </button>
              <button
                type="button"
                data-testid="export-graphml-btn"
                className="sf-dropdown-item"
                onClick={() => void handleExport("graphml")}
              >
                {t("export.graphml")}
              </button>
              <button
                type="button"
                data-testid="export-server-png-btn"
                className="sf-dropdown-item"
                onClick={() => void handleExport("serverPng")}
              >
                {t("export.serverPng")}
              </button>
              <button
                type="button"
                data-testid="export-client-png-btn"
                className="sf-dropdown-item"
                onClick={() => void handleExport("clientPng")}
              >
                {t("export.clientPng")}
              </button>
              <button
                type="button"
                data-testid="export-offline-zip-btn"
                className="sf-dropdown-item"
                onClick={() => void handleExport("offlineZip")}
              >
                {t("export.offlineZip")}
              </button>
            </div>
          )}
        </div>

        <button
          type="button"
          data-testid="share-open-btn"
          className="sf-btn"
          onClick={() => setActiveModal("share")}
        >
          <Share2 size={13} />
          {t("header.share")}
        </button>

        <button
          type="button"
          data-testid="i18n-toggle-btn"
          className="sf-btn"
          onClick={toggleLocale}
          title="Switch Language (EN / 中文)"
        >
          <Globe size={13} />
          {locale === "en" ? "中文" : "EN"}
        </button>

        {/* User / Auth Dropdown */}
        <div style={{ position: "relative" }}>
          <button
            type="button"
            data-testid="user-menu-btn"
            className="sf-btn"
            onClick={() => {
              setUserMenuOpen((v) => !v);
              setExportOpen(false);
              setExamplesOpen(false);
            }}
          >
            <User size={13} />
            <span>
              {isGuest && !loginDisabled
                ? t("header.login")
                : currentUser.userName || currentUser.userEmail || currentUser.userId}
            </span>
          </button>
          {userMenuOpen && (
            <div className="sf-dropdown-menu" data-testid="user-dropdown-menu">
              {!loginDisabled && isGuest && (
                <button
                  type="button"
                  data-testid="menu-login-btn"
                  className="sf-dropdown-item"
                  onClick={() => {
                    setUserMenuOpen(false);
                    setActiveModal("login");
                  }}
                >
                  {t("header.login")}
                </button>
              )}
              <button
                type="button"
                data-testid="menu-account-btn"
                className="sf-dropdown-item"
                onClick={() => {
                  setUserMenuOpen(false);
                  setActiveModal("account");
                }}
              >
                {t("header.account")}
              </button>
              <button
                type="button"
                data-testid="menu-share-manage-btn"
                className="sf-dropdown-item"
                onClick={() => {
                  setUserMenuOpen(false);
                  setActiveModal("shareManage");
                }}
              >
                {t("header.shareManage")}
              </button>
              <button
                type="button"
                data-testid="menu-create-team-btn"
                className="sf-dropdown-item"
                onClick={() => {
                  setUserMenuOpen(false);
                  setActiveModal("createTeam");
                }}
              >
                {t("header.createTeam")}
              </button>
              <button
                type="button"
                data-testid="menu-team-manage-btn"
                className="sf-dropdown-item"
                onClick={() => {
                  setUserMenuOpen(false);
                  setActiveModal("teamManage");
                }}
              >
                {t("header.teamManage")}
              </button>
              <button
                type="button"
                data-testid="menu-change-password-btn"
                className="sf-dropdown-item"
                onClick={() => {
                  setUserMenuOpen(false);
                  setActiveModal("changePassword");
                }}
              >
                {t("header.changePassword")}
              </button>
              <button
                type="button"
                data-testid="menu-pricing-btn"
                className="sf-dropdown-item"
                onClick={() => {
                  setUserMenuOpen(false);
                  setActiveModal("pricing");
                }}
              >
                {t("header.pricing")}
              </button>
              <button
                type="button"
                data-testid="menu-license-btn"
                className="sf-dropdown-item"
                onClick={() => {
                  setUserMenuOpen(false);
                  setActiveModal("license");
                }}
              >
                {t("header.license")}
              </button>
              <button
                type="button"
                data-testid="menu-admin-btn"
                className="sf-dropdown-item"
                onClick={() => {
                  setUserMenuOpen(false);
                  setActiveModal("admin");
                }}
              >
                {t("header.userManage")}
              </button>
              {!isGuest && !loginDisabled && (
                <button
                  type="button"
                  data-testid="menu-logout-btn"
                  className="sf-dropdown-item"
                  onClick={() => {
                    setUserMenuOpen(false);
                    logoutUser();
                  }}
                >
                  {t("header.logout")}
                </button>
              )}
            </div>
          )}
        </div>
      </div>
    </header>
  );
};
