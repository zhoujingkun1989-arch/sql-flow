import React from "react";
import { useLocation, useNavigate } from "react-router-dom";
import {
  Bot,
  Briefcase,
  Code2,
  Database,
  HelpCircle,
  History,
  Settings
} from "lucide-react";
import { useI18n } from "../../i18n/I18nContext";
import { type LeftRailPanel, useApp } from "../../state/AppContext";

export const LeftIconRail: React.FC = () => {
  const { t } = useI18n();
  const navigate = useNavigate();
  const location = useLocation();
  const {
    activeLeftPanel,
    setActiveLeftPanel,
    setActiveModal,
    reopenTryThis,
    regularJobSupported
  } = useApp();

  const switchWorkspacePanel = (panel: LeftRailPanel) => {
    setActiveLeftPanel(panel);
    if (location.pathname !== "/") {
      navigate("/");
    }
  };

  return (
    <aside className="sf-left-rail" data-testid="left-icon-rail">
      <div style={{ display: "flex", flexDirection: "column", gap: 6, alignItems: "center" }}>
        <button
          type="button"
          data-testid="rail-sqlflow-btn"
          className={`sf-rail-btn ${
            location.pathname === "/" && activeLeftPanel === "sqlflow"
              ? "sf-rail-btn--active"
              : ""
          }`}
          onClick={() => switchWorkspacePanel("sqlflow")}
          title={t("rail.sqlflow")}
        >
          <Code2 size={18} />
          <span>{t("rail.short.sql")}</span>
        </button>

        <button
          type="button"
          data-testid="rail-schema-btn"
          className={`sf-rail-btn ${
            location.pathname === "/" && activeLeftPanel === "schema"
              ? "sf-rail-btn--active"
              : ""
          }`}
          onClick={() => switchWorkspacePanel("schema")}
          title={t("rail.schema")}
        >
          <Database size={18} />
          <span>{t("rail.short.schema")}</span>
        </button>

        {regularJobSupported && (
          <>
            <button
              type="button"
              data-testid="rail-job-btn"
              className={`sf-rail-btn ${
                location.pathname === "/" && activeLeftPanel === "job"
                  ? "sf-rail-btn--active"
                  : ""
              }`}
              onClick={() => switchWorkspacePanel("job")}
              title={t("rail.job")}
            >
              <Briefcase size={18} />
              <span>{t("rail.short.jobs")}</span>
            </button>

            <button
              type="button"
              data-testid="rail-latest-job-btn"
              className={`sf-rail-btn ${
                location.pathname === "/job/latest" ? "sf-rail-btn--active" : ""
              }`}
              onClick={() =>
                navigate(location.pathname === "/job/latest" ? "/" : "/job/latest")
              }
              title={t("rail.latest")}
            >
              <History size={18} />
              <span>{t("rail.short.latest")}</span>
            </button>
          </>
        )}

        <button
          type="button"
          data-testid="rail-ai-btn"
          className={`sf-rail-btn ${
            location.pathname === "/" && activeLeftPanel === "ai" ? "sf-rail-btn--active" : ""
          }`}
          onClick={() => switchWorkspacePanel("ai")}
          title={t("rail.ai")}
        >
          <Bot size={18} />
          <span>{t("rail.short.ai")}</span>
        </button>
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 6, alignItems: "center" }}>
        <button
          type="button"
          data-testid="rail-settings-btn"
          className="sf-rail-btn"
          onClick={() => setActiveModal("settings")}
          title={t("rail.settings")}
        >
          <Settings size={18} />
          <span>{t("rail.short.settings")}</span>
        </button>

        <button
          type="button"
          data-testid="rail-help-btn"
          className="sf-rail-btn"
          onClick={() => {
            reopenTryThis();
            switchWorkspacePanel("sqlflow");
          }}
          title={t("rail.help")}
        >
          <HelpCircle size={18} />
          <span>{t("rail.short.help")}</span>
        </button>
      </div>
    </aside>
  );
};
