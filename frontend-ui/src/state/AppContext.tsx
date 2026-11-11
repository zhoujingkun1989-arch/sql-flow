import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState
} from "react";
import {
  type BusinessErrorHookContext,
  type CallGraphPayload,
  type Coordinate,
  DEFAULT_GUEST_USER_ID,
  DEFAULT_UI_DIALECT,
  type HighlightTarget,
  type JobSummary,
  type JoinAnalysisGraph,
  LOCAL_STORAGE_KEYS,
  type LicenseInfo,
  SAMPLE_SQL,
  type SqlflowJSON,
  UI_DIALECTS,
  type UiDialectKey,
  type UserVo,
  type VersionDetails,
  resolveKnownDbVendor
} from "@sqlflow/ui-core";
import { type RuntimePrivateConfig, SpaApiService } from "../services/spaApi";

export type RightWorkspaceTab =
  | "columnLineage"
  | "tableLineage"
  | "er"
  | "callgraph"
  | "join"
  | "output"
  | "error";

export type LeftRailPanel = "sqlflow" | "schema" | "job" | "ai";

export type ActiveModalKind =
  | null
  | "settings"
  | "login"
  | "changePassword"
  | "account"
  | "share"
  | "shareManage"
  | "createTeam"
  | "teamManage"
  | "license"
  | "pricing"
  | "admin"
  | "createJob"
  | "jobDetail";

export interface SqlFileTab {
  id: string;
  name: string;
  content: string;
}

export interface WorkspaceSettings {
  showDirect: boolean;
  showIndirect: boolean;
  ignoreRecordSet: boolean;
  ignoreFunction: boolean;
  simpleOutput: boolean;
  showTransform: boolean;
  showConstantTable: boolean;
  treatArgumentsInCountFunctionAsDirectDataflow: boolean;
  allTablesInFromClauseIncludedInTableLevelLineage: boolean;
  tableMovable: boolean;
  defaultDistance: number;
  rankdir: "LR" | "TB";
  joinAnalysisV2: boolean;
}

export const DEFAULT_WORKSPACE_SETTINGS: WorkspaceSettings = {
  showDirect: true,
  showIndirect: true,
  ignoreRecordSet: false,
  ignoreFunction: false,
  simpleOutput: false,
  showTransform: true,
  showConstantTable: false,
  treatArgumentsInCountFunctionAsDirectDataflow: false,
  allTablesInFromClauseIncludedInTableLevelLineage: false,
  tableMovable: true,
  defaultDistance: 3,
  rankdir: "LR",
  joinAnalysisV2: true
};

export interface ActionsBarPersistedState {
  x: number;
  y: number;
  collapsed: boolean;
}

export interface SlideUpDrawerState {
  open: boolean;
  title: string;
  sql: string;
  coordinates: Coordinate[];
  occurrenceIndex: number;
  transformCode?: string;
}

export interface StoredUserSession {
  id?: string;
  userId: string;
  token?: string;
  email?: string;
  userEmail?: string;
  name?: string;
  userName?: string;
  userRole?: string;
  userType?: string;
  secretKey?: string;
  exp?: number;
}

export interface AppContextValue {
  api: SpaApiService;
  bootstrapped: boolean;
  runtimeConfig: RuntimePrivateConfig;
  versionInfo: VersionDetails | null;
  licenseInfo: LicenseInfo | null;
  currentUser: StoredUserSession;
  userProfile: UserVo | null;
  isGuest: boolean;
  loginDisabled: boolean;
  regularJobSupported: boolean;
  dialect: UiDialectKey;
  setDialect: (next: UiDialectKey, loadSample?: boolean) => void;
  isDialectLicensed: (shortKey: UiDialectKey) => boolean;
  sqlTabs: SqlFileTab[];
  activeSqlTabId: string;
  activeSqlText: string;
  combinedSqlText: string;
  setActiveSqlTabId: (id: string) => void;
  updateActiveSqlText: (sql: string) => void;
  addSqlTab: (name?: string, content?: string) => string;
  closeSqlTab: (id: string) => void;
  loadSqlSample: (customSql?: string) => void;
  settings: WorkspaceSettings;
  updateSettings: (patch: Partial<WorkspaceSettings>, persistRemote?: boolean) => Promise<void>;
  resetSettings: () => Promise<void>;
  actionsBarState: ActionsBarPersistedState;
  setActionsBarState: (next: ActionsBarPersistedState) => void;
  tryThisDismissed: boolean;
  dismissTryThis: () => void;
  reopenTryThis: () => void;
  activeLeftPanel: LeftRailPanel;
  setActiveLeftPanel: (panel: LeftRailPanel) => void;
  activeRightTab: RightWorkspaceTab;
  setActiveRightTab: (tab: RightWorkspaceTab) => void;
  activeModal: ActiveModalKind;
  setActiveModal: (modal: ActiveModalKind) => void;
  selectedJobForDetail: JobSummary | null;
  openJobDetailModal: (job: JobSummary) => void;
  businessErrorBanner: BusinessErrorHookContext | null;
  clearBusinessErrorBanner: () => void;
  toastMessage: string | null;
  showToast: (msg: string) => void;
  loadingGraph: boolean;
  sessionId: string | undefined;
  activeJobId: string | undefined;
  activeJobSummary: JobSummary | null;
  lineageData: SqlflowJSON | null;
  tableLineageData: SqlflowJSON | null;
  erData: SqlflowJSON | null;
  callGraphData: (SqlflowJSON & Partial<CallGraphPayload>) | null;
  joinAnalysisData: JoinAnalysisGraph | null;
  setLineageResult: (data: SqlflowJSON | null, sessionId?: string, job?: JobSummary | null) => void;
  visualizeLineage: (overrideSql?: string, overrideDialect?: UiDialectKey) => Promise<void>;
  visualizeTableLineage: (overrideSql?: string) => Promise<void>;
  visualizeErDiagram: (overrideSql?: string) => Promise<void>;
  visualizeCallGraph: (overrideSql?: string) => Promise<void>;
  visualizeJoinAnalysis: (overrideSql?: string) => Promise<void>;
  sliceStreamGraph: (
    direction: "upstream" | "downstream" | "leftMost",
    tableNamePattern: string,
    tableColumn?: string,
    columnLevel?: boolean
  ) => Promise<void>;
  editorHoverCoords: Coordinate[];
  setEditorHoverCoords: (coords: Coordinate[]) => void;
  editorPinnedCoords: Coordinate[];
  setEditorPinnedCoords: (coords: Coordinate[]) => void;
  activeOccurrenceIndex: number;
  setActiveOccurrenceIndex: (idx: number) => void;
  canvasHoverTarget: HighlightTarget | null;
  setCanvasHoverTarget: (t: HighlightTarget | null) => void;
  canvasPinnedTarget: HighlightTarget | null;
  setCanvasPinnedTarget: (t: HighlightTarget | null) => void;
  locatedNodeId: string | null;
  setLocatedNodeId: (id: string | null) => void;
  slideUpDrawer: SlideUpDrawerState | null;
  setSlideUpDrawer: React.Dispatch<React.SetStateAction<SlideUpDrawerState | null>>;
  clearCanvasSelection: () => void;
  applyAuthenticatedUser: (session: StoredUserSession, profile?: UserVo | null) => void;
  logoutUser: () => void;
}

function readStoredUser(): StoredUserSession {
  try {
    const raw = globalThis.localStorage?.getItem(LOCAL_STORAGE_KEYS.USER);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<StoredUserSession>;
      if (typeof parsed.exp === "number" && parsed.exp > 0 && parsed.exp < Date.now()) {
        globalThis.localStorage?.removeItem(LOCAL_STORAGE_KEYS.USER);
        return { userId: DEFAULT_GUEST_USER_ID };
      }
      const resolvedId = (parsed.userId ?? parsed.id ?? "").trim();
      if (resolvedId) {
        return {
          id: resolvedId,
          userId: resolvedId,
          token: parsed.token,
          email: parsed.userEmail ?? parsed.email,
          userEmail: parsed.userEmail ?? parsed.email,
          name: parsed.userName ?? parsed.name,
          userName: parsed.userName ?? parsed.name,
          userRole: parsed.userRole,
          userType: parsed.userType,
          secretKey: parsed.secretKey,
          exp: parsed.exp
        };
      }
    }
  } catch {
    // Ignore storage read error
  }
  return { userId: DEFAULT_GUEST_USER_ID };
}

function readActionsBarState(): ActionsBarPersistedState {
  try {
    const raw = globalThis.localStorage?.getItem(LOCAL_STORAGE_KEYS.ACTIONS_BAR_V1);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<ActionsBarPersistedState>;
      return {
        x: typeof parsed.x === "number" ? parsed.x : 12,
        y: typeof parsed.y === "number" ? parsed.y : 46,
        collapsed: Boolean(parsed.collapsed)
      };
    }
  } catch {
    // Default position
  }
  return { x: 12, y: 46, collapsed: false };
}

function readTryThisDismissed(): boolean {
  try {
    return globalThis.localStorage?.getItem(LOCAL_STORAGE_KEYS.TRY_THIS_DISMISSED_V1) === "1";
  } catch {
    return false;
  }
}

function readJoinAnalysisV2Flag(): boolean {
  try {
    const val = globalThis.localStorage?.getItem(LOCAL_STORAGE_KEYS.JOIN_ANALYSIS_V2);
    if (val === "0" || val === "false") {
      return false;
    }
  } catch {
    // Default true
  }
  return true;
}

export function buildShowRelationTypeParam(settings: WorkspaceSettings): string {
  const parts: string[] = [];
  if (settings.showDirect) parts.push("fdd");
  if (settings.showIndirect) parts.push("fdr", "frd");
  return parts.length > 0 ? parts.join(",") : "fdd";
}

const AppContext = createContext<AppContextValue | null>(null);

export const AppProvider: React.FC<{
  children: React.ReactNode;
  customApi?: SpaApiService;
}> = ({ children, customApi }) => {
  const [currentUser, setCurrentUser] = useState<StoredUserSession>(() => readStoredUser());
  const apiRef = useRef<SpaApiService | null>(null);
  if (!apiRef.current) {
    apiRef.current =
      customApi ??
      new SpaApiService({
        userId: currentUser.userId,
        token: currentUser.token ?? ""
      });
  }
  const api = apiRef.current;

  const [bootstrapped, setBootstrapped] = useState(false);
  const [runtimeConfig, setRuntimeConfig] = useState<RuntimePrivateConfig>({
    SERVER: "/api/gspLive_backend",
    Product: "private",
    ApiPrefix: "/api",
    userId: DEFAULT_GUEST_USER_ID,
    trialVersion: false
  });
  const [versionInfo, setVersionInfo] = useState<VersionDetails | null>(null);
  const [licenseInfo, setLicenseInfo] = useState<LicenseInfo | null>(null);
  const [userProfile, setUserProfile] = useState<UserVo | null>(null);

  const [dialect, setDialectState] = useState<UiDialectKey>(DEFAULT_UI_DIALECT);
  const [sqlTabs, setSqlTabs] = useState<SqlFileTab[]>(() => [
    {
      id: "tab-1",
      name: "query-1.sql",
      content: SAMPLE_SQL[DEFAULT_UI_DIALECT]
    }
  ]);
  const [activeSqlTabId, setActiveSqlTabId] = useState("tab-1");

  const [settings, setSettings] = useState<WorkspaceSettings>(() => ({
    ...DEFAULT_WORKSPACE_SETTINGS,
    joinAnalysisV2: readJoinAnalysisV2Flag()
  }));
  const [actionsBarState, setActionsBarStateInternal] = useState<ActionsBarPersistedState>(() =>
    readActionsBarState()
  );
  const [tryThisDismissed, setTryThisDismissed] = useState<boolean>(() => readTryThisDismissed());

  const [activeLeftPanel, setActiveLeftPanel] = useState<LeftRailPanel>("sqlflow");
  const [activeRightTab, setActiveRightTab] = useState<RightWorkspaceTab>("columnLineage");
  const [activeModal, setActiveModal] = useState<ActiveModalKind>(null);
  const [selectedJobForDetail, setSelectedJobForDetail] = useState<JobSummary | null>(null);

  const [businessErrorBanner, setBusinessErrorBanner] =
    useState<BusinessErrorHookContext | null>(null);
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  const [loadingGraph, setLoadingGraph] = useState(false);
  const [sessionId, setSessionId] = useState<string | undefined>(undefined);
  const [activeJobSummary, setActiveJobSummary] = useState<JobSummary | null>(null);

  const [lineageData, setLineageData] = useState<SqlflowJSON | null>(null);
  const [tableLineageData, setTableLineageData] = useState<SqlflowJSON | null>(null);
  const [erData, setErData] = useState<SqlflowJSON | null>(null);
  const [callGraphData, setCallGraphData] = useState<
    (SqlflowJSON & Partial<CallGraphPayload>) | null
  >(null);
  const [joinAnalysisData, setJoinAnalysisData] = useState<JoinAnalysisGraph | null>(null);

  const [editorHoverCoords, setEditorHoverCoords] = useState<Coordinate[]>([]);
  const [editorPinnedCoords, setEditorPinnedCoords] = useState<Coordinate[]>([]);
  const [activeOccurrenceIndex, setActiveOccurrenceIndex] = useState(0);
  const [canvasHoverTarget, setCanvasHoverTarget] = useState<HighlightTarget | null>(null);
  const [canvasPinnedTarget, setCanvasPinnedTarget] = useState<HighlightTarget | null>(null);
  const [locatedNodeId, setLocatedNodeId] = useState<string | null>(null);
  const [slideUpDrawer, setSlideUpDrawer] = useState<SlideUpDrawerState | null>(null);

  const clearCanvasSelection = useCallback(() => {
    setCanvasPinnedTarget(null);
    setCanvasHoverTarget(null);
    setEditorPinnedCoords([]);
    setEditorHoverCoords([]);
    setLocatedNodeId(null);
    setSlideUpDrawer(null);
  }, []);

  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const showToast = useCallback((msg: string) => {
    setToastMessage(msg);
    if (toastTimerRef.current) {
      clearTimeout(toastTimerRef.current);
    }
    toastTimerRef.current = setTimeout(() => {
      setToastMessage(null);
    }, 3800);
  }, []);

  const logoutUser = useCallback(() => {
    try {
      globalThis.localStorage?.removeItem(LOCAL_STORAGE_KEYS.USER);
    } catch {
      // Ignore storage error
    }
    api.client.setUserId(DEFAULT_GUEST_USER_ID);
    api.client.setToken("");
    setCurrentUser({ userId: DEFAULT_GUEST_USER_ID });
    setUserProfile(null);
  }, [api]);

  const applyAuthenticatedUser = useCallback(
    (session: StoredUserSession, profile?: UserVo | null) => {
      const normalizedSession: StoredUserSession = {
        ...session,
        id: session.userId,
        userId: session.userId,
        email: session.userEmail ?? session.email,
        userEmail: session.userEmail ?? session.email,
        name: session.userName ?? session.name,
        userName: session.userName ?? session.name,
        exp: session.exp ?? Date.now() + 86_400_000
      };
      api.client.setUserId(normalizedSession.userId);
      api.client.setToken(normalizedSession.token ?? "");
      setCurrentUser(normalizedSession);
      if (profile !== undefined) {
        setUserProfile(profile);
      }
      try {
        globalThis.localStorage?.setItem(
          LOCAL_STORAGE_KEYS.USER,
          JSON.stringify(normalizedSession)
        );
      } catch {
        // Ignore storage error
      }
    },
    [api]
  );

  // Register HTTP 200 business error interceptors (`00 §3.3 #1`)
  useEffect(() => {
    api.client.setInterceptors({
      onUnauthorized: (ctx) => {
        logoutUser();
        setBusinessErrorBanner(ctx);
      },
      onForbidden: (ctx) => {
        setBusinessErrorBanner(ctx);
      },
      onJobCacheStale: (ctx) => {
        setBusinessErrorBanner(ctx);
      },
      onLicenseError: (ctx) => {
        setBusinessErrorBanner(ctx);
        setActiveModal("license");
      },
      onUpgradeRequired: (ctx) => {
        setBusinessErrorBanner(ctx);
        setActiveModal("pricing");
      },
      onLimitExceeded: (ctx) => {
        setBusinessErrorBanner(ctx);
      }
    });
  }, [api, logoutUser]);

  // Startup bootstrap sequence (`OBL-093`, `OBL-110`):
  // config.private.json -> /version -> /license -> /user/info + /user/getUserSetting
  useEffect(() => {
    let active = true;
    const bootstrap = async () => {
      try {
        const cfg = await api.fetchRuntimeConfig();
        if (!active) return;
        setRuntimeConfig(cfg);

        if (cfg.Authorization?.base64token && !api.client.getToken()) {
          api.client.setToken(cfg.Authorization.base64token);
        }

        let resolvedUserId = currentUser.userId || cfg.userId || DEFAULT_GUEST_USER_ID;

        try {
          const ver = await api.client.getVersion();
          if (active && ver?.version) {
            setVersionInfo(ver.version);
            if (ver.version.support?.["login.disable"] && ver.version.support?.["login.userId"]) {
              resolvedUserId = String(ver.version.support["login.userId"]);
              api.client.setUserId(resolvedUserId);
              setCurrentUser((prev) => ({ ...prev, userId: resolvedUserId }));
            }
          }
        } catch {
          // Continue bootstrap if version endpoint unreachable in unit test
        }

        try {
          const lic = await api.client.getLicense();
          if (active && lic?.data) {
            setLicenseInfo(lic.data);
          }
        } catch {
          // Continue
        }

        try {
          api.client.setUserId(resolvedUserId);
          const info = await api.client.getUserInfo();
          if (active && info?.data) {
            setUserProfile(info.data);
          }
        } catch {
          // Guest fallback
        }

        try {
          const remoteSetting = await api.client.getUserSetting("querymode");
          if (active && remoteSetting?.data && typeof remoteSetting.data === "object") {
            const raw = remoteSetting.data as Record<string, unknown>;
            setSettings((prev) => ({
              ...prev,
              ...(typeof raw.ignoreRecordSet === "boolean"
                ? { ignoreRecordSet: raw.ignoreRecordSet }
                : {}),
              ...(typeof raw.ignoreFunction === "boolean"
                ? { ignoreFunction: raw.ignoreFunction }
                : {}),
              ...(typeof raw.showConstantTable === "boolean"
                ? { showConstantTable: raw.showConstantTable }
                : {}),
              ...(typeof raw.showTransform === "boolean"
                ? { showTransform: raw.showTransform }
                : {}),
              ...(typeof raw.defaultDistance === "number"
                ? { defaultDistance: raw.defaultDistance }
                : {})
            }));
          }
        } catch {
          // Keep local defaults
        }
      } finally {
        if (active) {
          setBootstrapped(true);
        }
      }
    };
    void bootstrap();
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const isDialectLicensed = useCallback(
    (shortKey: UiDialectKey): boolean => {
      const supported = licenseInfo?.["Supported databases"];
      if (!supported) {
        return true;
      }
      const trimmed = supported.trim();
      if (
        !trimmed ||
        trimmed === "*" ||
        /^all(\b|\s)/i.test(trimmed) ||
        trimmed.toLowerCase().includes("unlimited")
      ) {
        return true;
      }
      const rawTokens = /[,;]/.test(trimmed)
        ? trimmed.split(/[,;]+/)
        : resolveKnownDbVendor(trimmed)
          ? [trimmed]
          : trimmed.split(/\s+/);
      const allowedSet = new Set(
        rawTokens
          .map((s) => resolveKnownDbVendor(s))
          .filter((id): id is NonNullable<typeof id> => id !== null)
          .map((id) => id.slice(3))
      );
      return allowedSet.size === 0 || allowedSet.has(shortKey);
    },
    [licenseInfo]
  );

  const setDialect = useCallback(
    (next: UiDialectKey, loadSample = false) => {
      const prevSample = SAMPLE_SQL[dialect];
      setDialectState(next);
      setSqlTabs((tabs) =>
        tabs.map((tab) => {
          if (tab.id !== activeSqlTabId) {
            return tab;
          }
          if (loadSample || tab.content.trim() === "" || tab.content === prevSample) {
            return { ...tab, content: SAMPLE_SQL[next] };
          }
          return tab;
        })
      );
    },
    [dialect, activeSqlTabId]
  );

  const activeSqlText = useMemo(() => {
    const found = sqlTabs.find((t) => t.id === activeSqlTabId) ?? sqlTabs[0];
    return found?.content ?? "";
  }, [sqlTabs, activeSqlTabId]);

  const combinedSqlText = useMemo(() => {
    if (sqlTabs.length <= 1) {
      return activeSqlText;
    }
    return sqlTabs.map((t) => `-- File: ${t.name}\n${t.content}`).join("\n\n");
  }, [sqlTabs, activeSqlText]);

  const updateActiveSqlText = useCallback(
    (sql: string) => {
      setSqlTabs((tabs) =>
        tabs.map((t) => (t.id === activeSqlTabId ? { ...t, content: sql } : t))
      );
    },
    [activeSqlTabId]
  );

  const addSqlTab = useCallback(
    (name?: string, content?: string): string => {
      const nextIndex = sqlTabs.length + 1;
      const newId = `tab-${Date.now()}-${nextIndex}`;
      const tabName = name ?? `query-${nextIndex}.sql`;
      const tabContent = content ?? `-- ${tabName}\nSELECT 1;`;
      setSqlTabs((prev) => [...prev, { id: newId, name: tabName, content: tabContent }]);
      setActiveSqlTabId(newId);
      return newId;
    },
    [sqlTabs.length]
  );

  const closeSqlTab = useCallback(
    (id: string) => {
      setSqlTabs((prev) => {
        if (prev.length <= 1) {
          return prev;
        }
        const filtered = prev.filter((t) => t.id !== id);
        if (id === activeSqlTabId && filtered[0]) {
          setActiveSqlTabId(filtered[0].id);
        }
        return filtered;
      });
    },
    [activeSqlTabId]
  );

  const loadSqlSample = useCallback(
    (customSql?: string) => {
      const nextSql = customSql ?? SAMPLE_SQL[dialect];
      clearCanvasSelection();
      updateActiveSqlText(nextSql);
    },
    [dialect, clearCanvasSelection, updateActiveSqlText]
  );

  const updateSettings = useCallback(
    async (patch: Partial<WorkspaceSettings>, persistRemote = false) => {
      const merged: WorkspaceSettings = { ...settings, ...patch };
      setSettings(merged);
      try {
        globalThis.localStorage?.setItem(
          LOCAL_STORAGE_KEYS.JOIN_ANALYSIS_V2,
          merged.joinAnalysisV2 ? "1" : "0"
        );
      } catch {
        // Ignore
      }
      if (persistRemote) {
        try {
          await api.client.saveUserSetting(
            {
              ignoreRecordSet: merged.ignoreRecordSet,
              ignoreFunction: merged.ignoreFunction,
              showConstantTable: merged.showConstantTable,
              showTransform: merged.showTransform,
              defaultDistance: merged.defaultDistance,
              treatArgumentsInCountFunctionAsDirectDataflow:
                merged.treatArgumentsInCountFunctionAsDirectDataflow,
              allTablesInFromClauseIncludedInTableLevelLineage:
                merged.allTablesInFromClauseIncludedInTableLevelLineage,
              tableMovable: merged.tableMovable
            },
            "querymode"
          );
          showToast("Settings saved");
        } catch {
          // Ignore network failure in offline/test mode
        }
      }
    },
    [api, settings, showToast]
  );

  const resetSettings = useCallback(async () => {
    await updateSettings(DEFAULT_WORKSPACE_SETTINGS, true);
  }, [updateSettings]);

  const setActionsBarState = useCallback((next: ActionsBarPersistedState) => {
    setActionsBarStateInternal(next);
    try {
      globalThis.localStorage?.setItem(LOCAL_STORAGE_KEYS.ACTIONS_BAR_V1, JSON.stringify(next));
    } catch {
      // Ignore
    }
  }, []);

  const dismissTryThis = useCallback(() => {
    setTryThisDismissed(true);
    try {
      globalThis.localStorage?.setItem(LOCAL_STORAGE_KEYS.TRY_THIS_DISMISSED_V1, "1");
    } catch {
      // Ignore
    }
  }, []);

  const reopenTryThis = useCallback(() => {
    setTryThisDismissed(false);
    try {
      globalThis.localStorage?.removeItem(LOCAL_STORAGE_KEYS.TRY_THIS_DISMISSED_V1);
    } catch {
      // Ignore
    }
  }, []);

  const setLineageResult = useCallback(
    (data: SqlflowJSON | null, nextSessionId?: string, job?: JobSummary | null) => {
      clearCanvasSelection();
      setLineageData(data);
      if (nextSessionId !== undefined) {
        setSessionId(nextSessionId);
      }
      if (job !== undefined) {
        setActiveJobSummary(job);
      }
    },
    [clearCanvasSelection]
  );

  const visualizeLineage = useCallback(
    async (overrideSql?: string, overrideDialect?: UiDialectKey) => {
      const sqlToRun = overrideSql ?? combinedSqlText;
      const targetDialect = overrideDialect ?? dialect;
      setLoadingGraph(true);
      clearCanvasSelection();
      try {
        const res = await api.client.generateGraph({
          sqltext: sqlToRun,
          dbvendor: targetDialect,
          showRelationType: buildShowRelationTypeParam(settings),
          ignoreRecordSet: settings.ignoreRecordSet || settings.simpleOutput,
          ignoreFunction: settings.ignoreFunction,
          showConstantTable: settings.showConstantTable,
          showTransform: settings.showTransform,
          treatArgumentsInCountFunctionAsDirectDataflow:
            settings.treatArgumentsInCountFunctionAsDirectDataflow,
          jobId: activeJobSummary?.jobId
        });
        if (res?.data) {
          setLineageData(res.data);
        }
        if (res?.sessionId) {
          setSessionId(res.sessionId);
        }
        setActiveRightTab("columnLineage");
      } catch (err) {
        showToast(err instanceof Error ? err.message : "Failed to visualize SQL lineage");
      } finally {
        setLoadingGraph(false);
      }
    },
    [
      api,
      combinedSqlText,
      dialect,
      settings,
      activeJobSummary?.jobId,
      clearCanvasSelection,
      showToast
    ]
  );

  const visualizeTableLineage = useCallback(
    async (overrideSql?: string) => {
      const sqlToRun = overrideSql ?? combinedSqlText;
      setLoadingGraph(true);
      clearCanvasSelection();
      try {
        const res = await api.client.generateTableLevelLineage({
          sqltext: sqlToRun,
          dbvendor: dialect,
          allTablesInFromClauseIncludedInTableLevelLineage:
            settings.allTablesInFromClauseIncludedInTableLevelLineage,
          jobId: activeJobSummary?.jobId
        });
        if (res?.data) {
          setTableLineageData(res.data);
        }
        if (res?.sessionId) {
          setSessionId(res.sessionId);
        }
        setActiveRightTab("tableLineage");
      } catch (err) {
        showToast(err instanceof Error ? err.message : "Failed to generate table lineage");
      } finally {
        setLoadingGraph(false);
      }
    },
    [
      api,
      combinedSqlText,
      dialect,
      settings.allTablesInFromClauseIncludedInTableLevelLineage,
      activeJobSummary?.jobId,
      clearCanvasSelection,
      showToast
    ]
  );

  const visualizeErDiagram = useCallback(
    async (overrideSql?: string) => {
      const sqlToRun = overrideSql ?? combinedSqlText;
      setLoadingGraph(true);
      try {
        const res = await api.client.erDiagramSelectGraph({
          sqltext: sqlToRun,
          dbvendor: dialect,
          sessionId,
          jobId: activeJobSummary?.jobId
        });
        if (res?.data) {
          setErData(res.data);
        }
        setActiveRightTab("er");
      } catch (err) {
        showToast(err instanceof Error ? err.message : "Failed to generate ER diagram");
      } finally {
        setLoadingGraph(false);
      }
    },
    [api, combinedSqlText, dialect, sessionId, activeJobSummary?.jobId, showToast]
  );

  const visualizeCallGraph = useCallback(
    async (overrideSql?: string) => {
      const sqlToRun = overrideSql ?? combinedSqlText;
      setLoadingGraph(true);
      try {
        const res = await api.fetchCallGraph({
          sqltext: sqlToRun,
          dbvendor: dialect,
          sessionId,
          jobId: activeJobSummary?.jobId
        });
        if (res?.data) {
          setCallGraphData(res.data);
        }
        setActiveRightTab("callgraph");
      } catch (err) {
        showToast(err instanceof Error ? err.message : "Failed to generate routine call graph");
      } finally {
        setLoadingGraph(false);
      }
    },
    [api, combinedSqlText, dialect, sessionId, activeJobSummary?.jobId, showToast]
  );

  const visualizeJoinAnalysis = useCallback(
    async (overrideSql?: string) => {
      const sqlToRun = overrideSql ?? combinedSqlText;
      setLoadingGraph(true);
      try {
        const res = await api.client.analyzeJoins({
          sqltext: sqlToRun,
          dbvendor: dialect,
          jobId: activeJobSummary?.jobId
        });
        if (res && res.schemaVersion === "join-analysis.v1") {
          setJoinAnalysisData(res);
        }
        setActiveRightTab("join");
      } catch (err) {
        showToast(err instanceof Error ? err.message : "Failed to analyze SQL joins");
      } finally {
        setLoadingGraph(false);
      }
    },
    [api, combinedSqlText, dialect, activeJobSummary?.jobId, showToast]
  );

  const sliceStreamGraph = useCallback(
    async (
      direction: "upstream" | "downstream" | "leftMost",
      tableNamePattern: string,
      tableColumn?: string,
      columnLevel = true
    ) => {
      setLoadingGraph(true);
      clearCanvasSelection();
      try {
        const params = {
          sessionId,
          dbvendor: dialect,
          tableNamePattern,
          tableColumn,
          distance: settings.defaultDistance,
          columnLevel,
          showRelationType: buildShowRelationTypeParam(settings),
          ignoreRecordSet: settings.ignoreRecordSet,
          ignoreFunction: settings.ignoreFunction,
          showTransform: settings.showTransform,
          jobId: activeJobSummary?.jobId
        };
        const res =
          direction === "upstream"
            ? await api.client.upstreamGraph(params)
            : direction === "downstream"
              ? await api.client.downstreamGraph(params)
              : await api.client.leftMostSourceTableGraph(params);
        if (res?.data) {
          setLineageData(res.data);
          if (res.sessionId) {
            setSessionId(res.sessionId);
          }
          setActiveRightTab("columnLineage");
        }
      } catch (err) {
        showToast(err instanceof Error ? err.message : "Stream graph slicing failed");
      } finally {
        setLoadingGraph(false);
      }
    },
    [api, sessionId, dialect, settings, activeJobSummary?.jobId, clearCanvasSelection, showToast]
  );

  const openJobDetailModal = useCallback((job: JobSummary) => {
    setSelectedJobForDetail(job);
    setActiveModal("jobDetail");
  }, []);

  const isGuest = currentUser.userId === DEFAULT_GUEST_USER_ID || !currentUser.token;
  const loginDisabled = Boolean(versionInfo?.support?.["login.disable"]);
  const regularJobSupported = versionInfo?.support?.["regular.job"] !== false;

  const value = useMemo<AppContextValue>(
    () => ({
      api,
      bootstrapped,
      runtimeConfig,
      versionInfo,
      licenseInfo,
      currentUser,
      userProfile,
      isGuest,
      loginDisabled,
      regularJobSupported,
      dialect,
      setDialect,
      isDialectLicensed,
      sqlTabs,
      activeSqlTabId,
      activeSqlText,
      combinedSqlText,
      setActiveSqlTabId,
      updateActiveSqlText,
      addSqlTab,
      closeSqlTab,
      loadSqlSample,
      settings,
      updateSettings,
      resetSettings,
      actionsBarState,
      setActionsBarState,
      tryThisDismissed,
      dismissTryThis,
      reopenTryThis,
      activeLeftPanel,
      setActiveLeftPanel,
      activeRightTab,
      setActiveRightTab,
      activeModal,
      setActiveModal,
      selectedJobForDetail,
      openJobDetailModal,
      businessErrorBanner,
      clearBusinessErrorBanner: () => setBusinessErrorBanner(null),
      toastMessage,
      showToast,
      loadingGraph,
      sessionId,
      activeJobId: activeJobSummary?.jobId,
      activeJobSummary,
      lineageData,
      tableLineageData,
      erData,
      callGraphData,
      joinAnalysisData,
      setLineageResult,
      visualizeLineage,
      visualizeTableLineage,
      visualizeErDiagram,
      visualizeCallGraph,
      visualizeJoinAnalysis,
      sliceStreamGraph,
      editorHoverCoords,
      setEditorHoverCoords,
      editorPinnedCoords,
      setEditorPinnedCoords,
      activeOccurrenceIndex,
      setActiveOccurrenceIndex,
      canvasHoverTarget,
      setCanvasHoverTarget,
      canvasPinnedTarget,
      setCanvasPinnedTarget,
      locatedNodeId,
      setLocatedNodeId,
      slideUpDrawer,
      setSlideUpDrawer,
      clearCanvasSelection,
      applyAuthenticatedUser,
      logoutUser
    }),
    [
      api,
      bootstrapped,
      runtimeConfig,
      versionInfo,
      licenseInfo,
      currentUser,
      userProfile,
      isGuest,
      loginDisabled,
      regularJobSupported,
      dialect,
      setDialect,
      isDialectLicensed,
      sqlTabs,
      activeSqlTabId,
      activeSqlText,
      combinedSqlText,
      updateActiveSqlText,
      addSqlTab,
      closeSqlTab,
      loadSqlSample,
      settings,
      updateSettings,
      resetSettings,
      actionsBarState,
      setActionsBarState,
      tryThisDismissed,
      dismissTryThis,
      reopenTryThis,
      activeLeftPanel,
      activeRightTab,
      activeModal,
      selectedJobForDetail,
      openJobDetailModal,
      businessErrorBanner,
      toastMessage,
      showToast,
      loadingGraph,
      sessionId,
      activeJobSummary,
      lineageData,
      tableLineageData,
      erData,
      callGraphData,
      joinAnalysisData,
      setLineageResult,
      visualizeLineage,
      visualizeTableLineage,
      visualizeErDiagram,
      visualizeCallGraph,
      visualizeJoinAnalysis,
      sliceStreamGraph,
      editorHoverCoords,
      editorPinnedCoords,
      activeOccurrenceIndex,
      canvasHoverTarget,
      canvasPinnedTarget,
      locatedNodeId,
      slideUpDrawer,
      clearCanvasSelection,
      applyAuthenticatedUser,
      logoutUser
    ]
  );

  // Ensure UI_DIALECTS is referenced for bundler tree consistency
  void UI_DIALECTS;

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
};

export function useApp(): AppContextValue {
  const ctx = useContext(AppContext);
  if (!ctx) {
    throw new Error("useApp must be used within AppProvider");
  }
  return ctx;
}
