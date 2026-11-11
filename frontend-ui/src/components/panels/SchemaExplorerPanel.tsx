import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  DBO_COLORS,
  EXPLICIT_DBO_COLOR_KEYS,
  SqlCodeEditor,
  UI_DIALECTS,
  type UiDialectKey
} from "@sqlflow/ui-core";
import {
  Database,
  FileCode2,
  FolderUp,
  HelpCircle,
  RefreshCw,
  Search,
  Trash2
} from "lucide-react";
import { useI18n } from "../../i18n/I18nContext";
import { type ParsedVendorSchemas } from "../../services/spaApi";
import { useApp } from "../../state/AppContext";
import {
  buildLineageCsv,
  extractDataFlowFromAny,
  triggerBrowserDownload
} from "../../utils/exporters";

interface CatalogTreeItem {
  id: string;
  database: string;
  schema: string;
  name: string;
  type: string;
  columns: string[];
}

export const SchemaExplorerPanel: React.FC = () => {
  const { t } = useI18n();
  const {
    api,
    dialect,
    lineageData,
    sessionId,
    activeJobId,
    setLineageResult,
    setActiveRightTab,
    visualizeLineage,
    visualizeTableLineage,
    visualizeErDiagram,
    visualizeCallGraph,
    sliceStreamGraph,
    showToast
  } = useApp();

  const [uploadVendor, setUploadVendor] = useState<UiDialectKey>(dialect);
  const [userSchemas, setUserSchemas] = useState<ParsedVendorSchemas[]>([]);
  const [rawSearch, setRawSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [legendOpen, setLegendOpen] = useState(false);
  const [previewModal, setPreviewModal] = useState<{ title: string; ddl: string } | null>(null);
  const [ctxMenu, setCtxMenu] = useState<{
    x: number;
    y: number;
    item: CatalogTreeItem;
  } | null>(null);

  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const refreshSchemas = useCallback(async () => {
    try {
      const parsed = await api.listUserSchemas();
      setUserSchemas(parsed);
    } catch {
      // Ignore in offline state
    }
  }, [api]);

  useEffect(() => {
    void refreshSchemas();
  }, [refreshSchemas]);

  // 1000ms debounced search (`04 §8.2`)
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedSearch(rawSearch.trim().toLowerCase());
    }, 1000);
    return () => clearTimeout(timer);
  }, [rawSearch]);

  const handleUploadDdl = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    e.target.value = "";
    if (files.length === 0) return;
    try {
      await api.uploadUserDdlSchema(files, uploadVendor);
      showToast(`Uploaded ${files.length} DDL schema file(s)`);
      await refreshSchemas();
    } catch (err) {
      showToast(err instanceof Error ? err.message : "DDL upload failed");
    }
  };

  const handleDeleteSchemaFile = async (vendor: string, fileName: string) => {
    try {
      await api.client.deleteUserSchema({
        dbvendor: vendor,
        fileName
      });
      await refreshSchemas();
      showToast(`Deleted ${fileName}`);
    } catch {
      showToast("Failed to delete schema file");
    }
  };

  const handlePreviewUserDdl = async (vendor: string, fileName: string) => {
    const content = await api.getUserSchemaDdlContent(vendor, fileName);
    setPreviewModal({
      title: `${vendor} / ${fileName}`,
      ddl: content || `-- DDL file: ${fileName}`
    });
  };

  const catalogItems = useMemo<CatalogTreeItem[]>(() => {
    const df = extractDataFlowFromAny(lineageData);
    const items: CatalogTreeItem[] = [];
    for (const obj of df.dbobjs ?? []) {
      if (obj.type === "database" || obj.type === "schema") continue;
      const cols =
        "columns" in obj && Array.isArray(obj.columns)
          ? obj.columns.map((c: { name: string }) => c.name)
          : [];
      items.push({
        id: String(obj.id),
        database: ("database" in obj && obj.database) || "DEFAULT_DB",
        schema: ("schema" in obj && obj.schema) || "PUBLIC",
        name: obj.name,
        type: obj.type || "table",
        columns: cols
      });
    }
    if (!debouncedSearch) return items;
    return items.filter(
      (it) =>
        it.database.toLowerCase().includes(debouncedSearch) ||
        it.schema.toLowerCase().includes(debouncedSearch) ||
        it.name.toLowerCase().includes(debouncedSearch) ||
        it.columns.some((c) => c.toLowerCase().includes(debouncedSearch))
    );
  }, [lineageData, debouncedSearch]);

  const handleSelectObjectGraph = async (
    item: CatalogTreeItem,
    opts: { schemaLevel?: boolean; columnLevel?: boolean }
  ) => {
    try {
      const res = await api.client.selectGraph({
        sessionId,
        dbvendor: dialect,
        database: item.database === "DEFAULT_DB" ? undefined : item.database,
        schema: item.schema === "PUBLIC" ? undefined : item.schema,
        table: item.name,
        schemaLevel: opts.schemaLevel,
        columnLevel: opts.columnLevel,
        jobId: activeJobId
      });
      if (res?.data) {
        setLineageResult(res.data, res.sessionId ?? sessionId);
        setActiveRightTab("columnLineage");
      } else {
        void visualizeLineage();
      }
    } catch {
      void visualizeLineage();
    }
  };

  return (
    <div
      data-testid="schema-explorer-panel"
      onClick={() => setCtxMenu(null)}
      style={{
        display: "flex",
        flexDirection: "column",
        width: "100%",
        height: "100%",
        background: "#ffffff",
        borderRight: "1px solid #e2e8f0",
        position: "relative"
      }}
    >
      {/* Header */}
      <div
        style={{
          padding: "10px 12px",
          borderBottom: "1px solid #e2e8f0",
          background: "#f8fafc",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between"
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 6, fontWeight: 700, fontSize: 13 }}>
          <Database size={15} />
          <span>{t("schema.title")}</span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 4 }}>
          <button
            type="button"
            data-testid="schema-legend-btn"
            className="sf-btn sf-btn--light"
            style={{ height: 26, padding: "0 6px" }}
            onClick={(e) => {
              e.stopPropagation();
              setLegendOpen((v) => !v);
            }}
            title="Object Type Legend"
          >
            <HelpCircle size={13} />
          </button>
          <button
            type="button"
            data-testid="schema-refresh-btn"
            className="sf-btn sf-btn--light"
            style={{ height: 26, padding: "0 6px" }}
            onClick={() => void refreshSchemas()}
            title="Refresh Schemas"
          >
            <RefreshCw size={13} />
          </button>
        </div>
      </div>

      {/* `?` Color Legend Popover */}
      {legendOpen && (
        <div
          data-testid="schema-color-legend"
          onClick={(e) => e.stopPropagation()}
          style={{
            position: "absolute",
            top: 44,
            right: 10,
            zIndex: 40,
            width: 220,
            background: "#ffffff",
            border: "1px solid #cbd5e1",
            borderRadius: 6,
            padding: 10,
            boxShadow: "0 10px 24px rgba(0,0,0,0.16)",
            fontSize: 11
          }}
        >
          <div style={{ fontWeight: 700, marginBottom: 6 }}>Object Color Legend</div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 4 }}>
            {EXPLICIT_DBO_COLOR_KEYS.map((key) => (
              <div key={key} style={{ display: "flex", alignItems: "center", gap: 5 }}>
                <span
                  style={{
                    width: 10,
                    height: 10,
                    borderRadius: 2,
                    background: DBO_COLORS[key],
                    display: "inline-block"
                  }}
                />
                <span>{key}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* User DDL Upload Bar (`FormData` key `"sqlfiles"`) */}
      <div
        style={{
          padding: "8px 12px",
          borderBottom: "1px solid #e2e8f0",
          display: "flex",
          alignItems: "center",
          gap: 6
        }}
      >
        <select
          aria-label="Schema Upload Vendor"
          data-testid="schema-upload-vendor"
          className="sf-select"
          value={uploadVendor}
          onChange={(e) => setUploadVendor(e.target.value as UiDialectKey)}
          style={{ flex: 1 }}
        >
          {UI_DIALECTS.map((d) => (
            <option key={d.shortKey} value={d.shortKey}>
              {d.label}
            </option>
          ))}
        </select>

        <input
          ref={fileInputRef}
          type="file"
          accept=".sql,.ddl,.zip"
          multiple
          data-testid="schema-ddl-file-input"
          style={{ display: "none" }}
          onChange={(e) => void handleUploadDdl(e)}
        />
        <button
          type="button"
          data-testid="schema-upload-ddl-btn"
          className="sf-btn sf-btn--primary"
          onClick={() => fileInputRef.current?.click()}
        >
          <FolderUp size={13} />
          {t("schema.uploadDdl")}
        </button>
      </div>

      {/* 1000ms Debounced Search */}
      <div style={{ padding: "8px 12px", borderBottom: "1px solid #e2e8f0" }}>
        <div style={{ position: "relative" }}>
          <Search
            size={13}
            style={{ position: "absolute", left: 9, top: 9, color: "#94a3b8" }}
          />
          <input
            type="text"
            data-testid="schema-search-input"
            className="sf-input"
            placeholder={t("schema.searchPlaceholder")}
            value={rawSearch}
            onChange={(e) => setRawSearch(e.target.value)}
            style={{ width: "100%", paddingLeft: 28 }}
          />
        </div>
      </div>

      {/* Body: Uploaded User DDL Tree + Catalog Object Browser */}
      <div style={{ flex: 1, overflowY: "auto", padding: "8px 12px", fontSize: 12 }}>
        <div style={{ fontWeight: 700, color: "#334155", marginBottom: 6 }}>
          Uploaded User DDL Schemas ({userSchemas.reduce((acc, v) => acc + v.files.length, 0)})
        </div>
        {userSchemas.length === 0 ? (
          <div style={{ fontSize: 11, color: "#64748b", marginBottom: 14 }}>
            No user DDL files uploaded yet.
          </div>
        ) : (
          <div style={{ marginBottom: 14, display: "flex", flexDirection: "column", gap: 6 }}>
            {userSchemas.map((group) => (
              <div
                key={group.vendor}
                data-testid={`user-schema-vendor-${group.vendor}`}
                style={{
                  border: "1px solid #e2e8f0",
                  borderRadius: 6,
                  padding: "6px 8px"
                }}
              >
                <div style={{ fontWeight: 600, color: "#0f172a", marginBottom: 4 }}>
                  {group.vendor} ({group.files.length})
                </div>
                {group.files.map((fileName) => (
                  <div
                    key={fileName}
                    data-testid={`user-schema-file-${fileName}`}
                    style={{
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "space-between",
                      padding: "3px 4px",
                      borderTop: "1px solid #f1f5f9"
                    }}
                  >
                    <button
                      type="button"
                      onClick={() => void handlePreviewUserDdl(group.vendor, fileName)}
                      style={{
                        border: "none",
                        background: "transparent",
                        cursor: "pointer",
                        display: "inline-flex",
                        alignItems: "center",
                        gap: 5,
                        color: "#2563eb",
                        fontSize: 11,
                        padding: 0
                      }}
                    >
                      <FileCode2 size={12} />
                      {fileName}
                    </button>
                    <button
                      type="button"
                      aria-label={`Delete ${fileName}`}
                      onClick={() => void handleDeleteSchemaFile(group.vendor, fileName)}
                      style={{
                        border: "none",
                        background: "transparent",
                        cursor: "pointer",
                        color: "#dc2626",
                        padding: 2
                      }}
                    >
                      <Trash2 size={12} />
                    </button>
                  </div>
                ))}
              </div>
            ))}
          </div>
        )}

        <div style={{ fontWeight: 700, color: "#334155", marginBottom: 6 }}>
          Database / Schema / Table Browser ({catalogItems.length})
        </div>
        {catalogItems.length === 0 ? (
          <div style={{ fontSize: 11, color: "#64748b" }}>
            Run lineage visualization or select a persisted job to browse database objects.
          </div>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 5 }}>
            {catalogItems.map((item) => (
              <div
                key={item.id}
                data-testid={`catalog-item-${item.name}`}
                onContextMenu={(e) => {
                  e.preventDefault();
                  setCtxMenu({ x: 24, y: 120, item });
                }}
                style={{
                  border: "1px solid #e2e8f0",
                  borderRadius: 5,
                  padding: "6px 8px",
                  background: "#f8fafc"
                }}
              >
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                  <strong>
                    {item.database}.{item.schema}.{item.name}
                  </strong>
                  <button
                    type="button"
                    data-testid={`catalog-actions-${item.name}`}
                    className="sf-btn sf-btn--light"
                    style={{ height: 22, fontSize: 10 }}
                    onClick={(e) => {
                      e.stopPropagation();
                      setCtxMenu({ x: 24, y: 120, item });
                    }}
                  >
                    Actions
                  </button>
                </div>
                {item.columns.length > 0 && (
                  <div style={{ fontSize: 11, color: "#64748b", marginTop: 3 }}>
                    Columns: {item.columns.join(", ")}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Schema Context Menu */}
      {ctxMenu && (
        <div
          data-testid="schema-context-menu"
          onClick={(e) => e.stopPropagation()}
          style={{
            position: "absolute",
            left: ctxMenu.x,
            top: ctxMenu.y,
            zIndex: 50,
            width: 220,
            background: "#ffffff",
            border: "1px solid #cbd5e1",
            borderRadius: 6,
            boxShadow: "0 12px 24px rgba(0,0,0,0.18)",
            padding: 4
          }}
        >
          {[
            {
              label: "visualize with schemas",
              run: () => void handleSelectObjectGraph(ctxMenu.item, { schemaLevel: true })
            },
            {
              label: "visualize with tables",
              run: () => void visualizeTableLineage()
            },
            {
              label: "visualize with columns",
              run: () => void handleSelectObjectGraph(ctxMenu.item, { columnLevel: true })
            },
            {
              label: "to upstream",
              run: () => void sliceStreamGraph("upstream", ctxMenu.item.name)
            },
            {
              label: "to downstream",
              run: () => void sliceStreamGraph("downstream", ctxMenu.item.name)
            },
            {
              label: "to left most",
              run: () => void sliceStreamGraph("leftMost", ctxMenu.item.name)
            },
            {
              label: "show ER diagram",
              run: () => void visualizeErDiagram()
            },
            {
              label: "show call relationship",
              run: () => void visualizeCallGraph()
            },
            {
              label: "view DDL",
              run: () =>
                setPreviewModal({
                  title: ctxMenu.item.name,
                  ddl: `CREATE TABLE ${ctxMenu.item.schema}.${ctxMenu.item.name} (\n${ctxMenu.item.columns
                    .map((c) => `  ${c} VARCHAR(255)`)
                    .join(",\n")}\n);`
                })
            },
            {
              label: "export CSV",
              run: () =>
                triggerBrowserDownload(
                  buildLineageCsv(lineageData),
                  `${ctxMenu.item.name}.csv`,
                  "text/csv"
                )
            },
            {
              label: "export JSON",
              run: () =>
                triggerBrowserDownload(
                  JSON.stringify(lineageData ?? {}, null, 2),
                  `${ctxMenu.item.name}.json`,
                  "application/json"
                )
            }
          ].map((act) => (
            <button
              key={act.label}
              type="button"
              className="sf-dropdown-item"
              onClick={() => {
                setCtxMenu(null);
                act.run();
              }}
            >
              {act.label}
            </button>
          ))}
        </div>
      )}

      {previewModal && (
        <div className="sf-modal-backdrop" onClick={() => setPreviewModal(null)}>
          <div
            className="sf-modal-card"
            style={{ maxWidth: 560 }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="sf-modal-header">
              <span>DDL — {previewModal.title}</span>
              <button
                type="button"
                className="sf-btn sf-btn--light"
                onClick={() => setPreviewModal(null)}
              >
                Close
              </button>
            </div>
            <div className="sf-modal-body" style={{ height: 240 }}>
              <SqlCodeEditor value={previewModal.ddl} readOnly={true} height="100%" />
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
