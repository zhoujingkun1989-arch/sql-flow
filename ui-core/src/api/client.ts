/**
 * Shared SQLFlow HTTP Client, Prefix Resolver, Auth & `userId` Injector,
 * Strict-Scope RSA Password Wrapper, and HTTP `200` Business Code Interceptor.
 *
 * Reference:
 * - docs/re-spec/00-master-architecture-and-ledger.md §3.3 (20 Wire Invariants)
 * - docs/re-spec/04-frontend-ui-and-widget.md §10.3, §12.1–§12.4
 */

import {
  type AiNodeQueryParams,
  type AiQueryResponse,
  type AiQueryStreamParams,
  type AiSseEvent,
  type AiStatusResponse,
  type AiSuggestedQuestion,
  type AiSuggestionClickParams,
  type AiSuggestionsParams,
  type ApiEnvelope,
  type DataSource,
  DEFAULT_GUEST_USER_ID,
  type GenerateGraphParams,
  type GenerateSecretKeyResponse,
  type GenerateTokenResponse,
  type JobGraphSummaryResponse,
  type JobSummary,
  type LatestJobGraphResponse,
  type LicenseInfo,
  type ListTableColumnsResponse,
  type RegisterOrLoginResponseData,
  SPA_DEFAULT_API_PREFIX,
  type SelectedDbObjectInfoResponse,
  type SelectedGraphParams,
  type ShareSummaryItem,
  type StreamSliceParams,
  type SubmitJobParams,
  type UserJobSummary,
  type UserSchemaSummaryMap,
  type UserVo,
  type VersionEnvelope,
  WIDGET_DEFAULT_API_PREFIX
} from "../types/api.js";
import type { Coordinate } from "../types/coordinates.js";
import type { AnalyzeJoinsParams, JoinAnalysisGraph } from "../types/joinAnalysis.js";
import type { CallGraphPayload, SqlflowJSON } from "../types/lineage.js";
import { toApiDbVendor } from "../types/vendor.js";
import { LOCAL_STORAGE_KEYS } from "../theme/tokens.js";
import { encryptRsaPassword } from "./rsa.js";
import { streamAiLineageQuery } from "./sse.js";

export type ClientRuntimeMode = "spa" | "widget";

export interface BusinessErrorHookContext {
  code: number;
  error?: string;
  message?: string;
  machineCode?: string | null;
  endpoint: string;
  raw: Record<string, unknown>;
}

export interface SqlFlowApiInterceptors {
  /** Fired on `code === 401` or auth-denial error messages; client also clears `localStorage["user"]` when enabled */
  onUnauthorized?: (ctx: BusinessErrorHookContext) => void;
  /** Fired on `code === 403` (account expired / forbidden) */
  onForbidden?: (ctx: BusinessErrorHookContext) => void;
  /** Fired on `code === 410` (stale job graph cache -> prompt rerun job) */
  onJobCacheStale?: (ctx: BusinessErrorHookContext) => void;
  /** Fired on `411 <= code <= 417` (license / machine code error) */
  onLicenseError?: (ctx: BusinessErrorHookContext) => void;
  /** Fired on `code === 426` (upgrade required) */
  onUpgradeRequired?: (ctx: BusinessErrorHookContext) => void;
  /** Fired on `code === 509` (rate/bandwidth/quota limit exceeded) */
  onLimitExceeded?: (ctx: BusinessErrorHookContext) => void;
}

export interface SqlFlowApiClientOptions {
  /**
   * Backend base prefix.
   * - SPA default (`mode: "spa"`): `"/api/gspLive_backend"` (also accepts `"/api"`, automatically appending `"/gspLive_backend"`).
   * - Widget default (`mode: "widget"`): `"/gspLive_backend"` (also accepts `"http://host:8081/api"`, appending `"/gspLive_backend"`).
   */
  apiPrefix?: string;
  mode?: ClientRuntimeMode;
  token?: string;
  userId?: string;
  rsaPublicKey?: string;
  timeoutMs?: number;
  clearLocalStorageOn401?: boolean;
  storage?: Pick<Storage, "removeItem"> | null;
  fetchImpl?: typeof fetch;
  interceptors?: SqlFlowApiInterceptors;
}

/**
 * Resolves any `apiPrefix` input (`undefined`, `""`, `"/api"`, `"/api/gspLive_backend"`, `"http://localhost:8081/api"`)
 * into the canonical `${base}/gspLive_backend` endpoint root without duplicating `/gspLive_backend`.
 */
export function resolveBackendApiBase(
  apiPrefix?: string | null,
  mode: ClientRuntimeMode = "spa"
): string {
  if (apiPrefix === undefined || apiPrefix === null) {
    return mode === "widget" ? WIDGET_DEFAULT_API_PREFIX : SPA_DEFAULT_API_PREFIX;
  }
  const trimmed = apiPrefix.trim().replace(/\/+$/, "");
  if (!trimmed) {
    return WIDGET_DEFAULT_API_PREFIX;
  }
  if (trimmed.endsWith("/gspLive_backend")) {
    return trimmed;
  }
  return `${trimmed}/gspLive_backend`;
}

function isAuthErrorPayload(code: number, messageText: string): boolean {
  if (code === 401) {
    return true;
  }
  const lower = messageText.toLowerCase();
  return (
    lower.includes("invalid user or token") ||
    lower.includes("access deny") ||
    lower.includes("access denied") ||
    lower.includes("unauthorized")
  );
}

function toUrlSearchParams(record: Record<string, unknown>): URLSearchParams {
  const params = new URLSearchParams();
  for (const [key, val] of Object.entries(record)) {
    if (val === undefined) {
      continue;
    }
    if (val === null) {
      params.append(key, "null");
    } else if (typeof val === "object") {
      params.append(key, JSON.stringify(val));
    } else {
      params.append(key, String(val));
    }
  }
  return params;
}

export class SqlFlowApiClient {
  private baseUrl: string;
  private readonly mode: ClientRuntimeMode;
  private token: string;
  private userId: string;
  private rsaPublicKey?: string;
  private readonly timeoutMs: number;
  private readonly clearLocalStorageOn401: boolean;
  private readonly storage?: Pick<Storage, "removeItem"> | null;
  private readonly fetchFn: typeof fetch;
  private interceptors: SqlFlowApiInterceptors;

  public constructor(options: SqlFlowApiClientOptions = {}) {
    this.mode = options.mode ?? "spa";
    this.baseUrl = resolveBackendApiBase(options.apiPrefix, this.mode);
    this.token = options.token ?? "";
    this.userId = options.userId?.trim() || DEFAULT_GUEST_USER_ID;
    this.rsaPublicKey = options.rsaPublicKey;
    this.timeoutMs = options.timeoutMs ?? 120_000;
    this.clearLocalStorageOn401 = options.clearLocalStorageOn401 ?? true;
    this.storage = options.storage;
    this.fetchFn = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
    this.interceptors = options.interceptors ?? {};
  }

  public getBaseUrl(): string {
    return this.baseUrl;
  }

  public setApiPrefix(apiPrefix: string): void {
    this.baseUrl = resolveBackendApiBase(apiPrefix, this.mode);
  }

  public getToken(): string {
    return this.token;
  }

  public setToken(token: string): void {
    this.token = token;
  }

  public getUserId(): string {
    return this.userId;
  }

  public setUserId(userId?: string | null): void {
    this.userId = userId?.trim() || DEFAULT_GUEST_USER_ID;
  }

  public setRsaPublicKey(publicKey: string): void {
    this.rsaPublicKey = publicKey;
  }

  public setInterceptors(interceptors: SqlFlowApiInterceptors): void {
    this.interceptors = { ...this.interceptors, ...interceptors };
  }

  private buildHeaders(extra?: Record<string, string>): Record<string, string> {
    const headers: Record<string, string> = {
      Authorization: `Token ${this.token}`,
      ...extra
    };
    return headers;
  }

  private injectUserId<T extends Record<string, unknown>>(
    data?: T
  ): Record<string, unknown> {
    // Caller-supplied `data.userId` intentionally spreads second to override default `this.userId` (`04 §12.1 #3`)
    return {
      userId: this.userId,
      ...(data ?? {})
    };
  }

  private runBusinessCodeInterceptors(
    endpoint: string,
    payload: unknown
  ): void {
    if (!payload || typeof payload !== "object" || !("code" in payload)) {
      return;
    }
    const raw = payload as Record<string, unknown>;
    const numericCode =
      typeof raw.code === "number"
        ? raw.code
        : typeof raw.code === "string"
          ? Number.parseInt(raw.code, 10)
          : Number.NaN;

    const errorStr = typeof raw.error === "string" ? raw.error : undefined;
    const messageStr = typeof raw.message === "string" ? raw.message : undefined;
    const combinedMsg = `${errorStr ?? ""} ${messageStr ?? ""}`.trim();

    if (!Number.isFinite(numericCode)) {
      return;
    }

    const ctx: BusinessErrorHookContext = {
      code: numericCode,
      error: errorStr,
      message: messageStr,
      machineCode:
        typeof raw["machine.code"] === "string" || raw["machine.code"] === null
          ? (raw["machine.code"] as string | null)
          : undefined,
      endpoint,
      raw
    };

    if (isAuthErrorPayload(numericCode, combinedMsg)) {
      if (this.clearLocalStorageOn401) {
        const targetStorage =
          this.storage !== undefined
            ? this.storage
            : typeof globalThis.localStorage !== "undefined"
              ? globalThis.localStorage
              : null;
        try {
          targetStorage?.removeItem(LOCAL_STORAGE_KEYS.USER);
        } catch {
          // Ignore storage access errors in sandboxed iframes
        }
      }
      this.interceptors.onUnauthorized?.(ctx);
      return;
    }

    if (numericCode === 403) {
      this.interceptors.onForbidden?.(ctx);
    } else if (numericCode === 410) {
      this.interceptors.onJobCacheStale?.(ctx);
    } else if (numericCode >= 411 && numericCode <= 417) {
      this.interceptors.onLicenseError?.(ctx);
    } else if (numericCode === 426) {
      this.interceptors.onUpgradeRequired?.(ctx);
    } else if (numericCode === 509) {
      this.interceptors.onLimitExceeded?.(ctx);
    }
  }

  private async executeFetch(
    subPath: string,
    init: RequestInit
  ): Promise<Response> {
    const url = subPath.startsWith("http://") || subPath.startsWith("https://")
      ? subPath
      : `${this.baseUrl}${subPath.startsWith("/") ? subPath : `/${subPath}`}`;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      return await this.fetchFn(url, {
        ...init,
        signal: init.signal ?? controller.signal
      });
    } finally {
      clearTimeout(timer);
    }
  }

  public async getJson<T>(
    subPath: string,
    query?: Record<string, unknown>
  ): Promise<T> {
    const mergedQuery = this.injectUserId(query);
    const qs = toUrlSearchParams(mergedQuery).toString();
    const pathWithQuery = qs ? `${subPath}?${qs}` : subPath;
    const res = await this.executeFetch(pathWithQuery, {
      method: "GET",
      headers: this.buildHeaders({ Accept: "application/json" })
    });
    const json = (await res.json()) as T;
    this.runBusinessCodeInterceptors(subPath, json);
    return json;
  }

  public async postForm<T>(
    subPath: string,
    formFields?: Record<string, unknown>
  ): Promise<T> {
    const merged = this.injectUserId(formFields);
    const body = toUrlSearchParams(merged).toString();
    const res = await this.executeFetch(subPath, {
      method: "POST",
      headers: this.buildHeaders({
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
        Accept: "application/json"
      }),
      body
    });
    const json = (await res.json()) as T;
    this.runBusinessCodeInterceptors(subPath, json);
    return json;
  }

  public async postFormText(
    subPath: string,
    formFields?: Record<string, unknown>
  ): Promise<string> {
    const merged = this.injectUserId(formFields);
    const body = toUrlSearchParams(merged).toString();
    const res = await this.executeFetch(subPath, {
      method: "POST",
      headers: this.buildHeaders({
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
      }),
      body
    });
    return res.text();
  }

  public async postJson<T>(
    subPath: string,
    payload?: Record<string, unknown>
  ): Promise<T> {
    const merged = this.injectUserId(payload);
    const res = await this.executeFetch(subPath, {
      method: "POST",
      headers: this.buildHeaders({
        "Content-Type": "application/json;charset=UTF-8",
        Accept: "application/json"
      }),
      body: JSON.stringify(merged)
    });
    const json = (await res.json()) as T;
    this.runBusinessCodeInterceptors(subPath, json);
    return json;
  }

  public async postMultipart<T>(
    subPath: string,
    formData: FormData
  ): Promise<T> {
    if (!formData.has("userId")) {
      formData.append("userId", this.userId);
    }
    const res = await this.executeFetch(subPath, {
      method: "POST",
      headers: this.buildHeaders({ Accept: "application/json" }),
      body: formData
    });
    const json = (await res.json()) as T;
    this.runBusinessCodeInterceptors(subPath, json);
    return json;
  }

  // =========================================================================
  // 1. System, Version & License Endpoints
  // =========================================================================

  /** Unwrapped `/version` response (`00 §3.3 #2`) */
  public async getVersion(): Promise<VersionEnvelope> {
    return this.postForm<VersionEnvelope>("/version");
  }

  public async getLicense(): Promise<ApiEnvelope<LicenseInfo>> {
    return this.getJson<ApiEnvelope<LicenseInfo>>("/license");
  }

  public async uploadLicense(licenseFile: File | Blob): Promise<ApiEnvelope<unknown>> {
    const fd = new FormData();
    fd.append("userId", this.userId);
    fd.append("licenseFile", licenseFile);
    return this.postMultipart<ApiEnvelope<unknown>>("/uploadLicense", fd);
  }

  // =========================================================================
  // 2. Lineage, Join Analysis, ER, Call Graph & SQL Utilities
  // =========================================================================

  public async generateGraph(
    params: GenerateGraphParams
  ): Promise<ApiEnvelope<SqlflowJSON>> {
    return this.postForm<ApiEnvelope<SqlflowJSON>>("/sqlflow/generation/sqlflow/graph", {
      ...params,
      dbvendor: toApiDbVendor(params.dbvendor)
    });
  }

  public async generateTableLevelLineage(params: {
    sqltext: string;
    dbvendor: string;
    jobId?: string;
    allTablesInFromClauseIncludedInTableLevelLineage?: boolean;
    database?: string;
    schema?: string;
    table?: string;
    column?: string;
  }): Promise<ApiEnvelope<SqlflowJSON>> {
    return this.postForm<ApiEnvelope<SqlflowJSON>>(
      "/sqlflow/generation/sqlflow/graph/table_level_lineage",
      {
        ...params,
        dbvendor: toApiDbVendor(params.dbvendor)
      }
    );
  }

  public async selectGraph(
    params: SelectedGraphParams
  ): Promise<ApiEnvelope<SqlflowJSON>> {
    return this.postForm<ApiEnvelope<SqlflowJSON>>(
      "/sqlflow/generation/sqlflow/selectedgraph",
      {
        ...params,
        dbvendor: toApiDbVendor(params.dbvendor)
      }
    );
  }

  public async selectTableLevelLineage(params: {
    sessionId: string;
    dbvendor: string;
    jobId?: string;
    allTablesInFromClauseIncludedInTableLevelLineage?: boolean;
    share?: boolean;
  }): Promise<ApiEnvelope<SqlflowJSON>> {
    return this.postForm<ApiEnvelope<SqlflowJSON>>(
      "/sqlflow/generation/sqlflow/selectedgraph/table_level_lineage",
      {
        ...params,
        dbvendor: toApiDbVendor(params.dbvendor)
      }
    );
  }

  public async leftMostSourceTableGraph(
    params: StreamSliceParams
  ): Promise<ApiEnvelope<SqlflowJSON>> {
    return this.postForm<ApiEnvelope<SqlflowJSON>>(
      "/sqlflow/generation/sqlflow/leftMostSourceTableGraph",
      {
        ...params,
        ...(params.dbvendor ? { dbvendor: toApiDbVendor(params.dbvendor) } : {})
      }
    );
  }

  public async upstreamGraph(
    params: StreamSliceParams
  ): Promise<ApiEnvelope<SqlflowJSON>> {
    return this.postForm<ApiEnvelope<SqlflowJSON>>(
      "/sqlflow/generation/sqlflow/upstreamGraph",
      {
        distance: 10,
        ...params,
        ...(params.dbvendor ? { dbvendor: toApiDbVendor(params.dbvendor) } : {})
      }
    );
  }

  public async downstreamGraph(
    params: StreamSliceParams
  ): Promise<ApiEnvelope<SqlflowJSON>> {
    return this.postForm<ApiEnvelope<SqlflowJSON>>(
      "/sqlflow/generation/sqlflow/downstreamGraph",
      {
        distance: 10,
        ...params,
        ...(params.dbvendor ? { dbvendor: toApiDbVendor(params.dbvendor) } : {})
      }
    );
  }

  public async listTableColumns(params: {
    sessionId?: string;
    dbvendor: string;
    database?: string;
    schema?: string;
    table: string;
    jobId?: string;
  }): Promise<ListTableColumnsResponse> {
    return this.postForm<ListTableColumnsResponse>(
      "/sqlflow/generation/sqlflow/listTableColumns",
      {
        ...params,
        dbvendor: toApiDbVendor(params.dbvendor)
      }
    );
  }

  public async generateCallLineage(params: {
    sqltext?: string;
    sessionId?: string;
    dbvendor: string;
    jobId?: string;
  }): Promise<ApiEnvelope<SqlflowJSON & Partial<CallGraphPayload>>> {
    return this.postForm<ApiEnvelope<SqlflowJSON & Partial<CallGraphPayload>>>(
      "/sqlflow/generation/sqlflow/graph/call_lineage",
      {
        sqltext: params.sqltext ?? "",
        sessionId: params.sessionId,
        dbvendor: toApiDbVendor(params.dbvendor),
        jobId: params.jobId
      }
    );
  }

  public async erDiagramSelectGraph(params: {
    sqltext?: string;
    dbvendor: string;
    sessionId?: string;
    jobId?: string;
    database?: string;
    schema?: string;
    table?: string;
    column?: string;
  }): Promise<ApiEnvelope<SqlflowJSON>> {
    return this.postForm<ApiEnvelope<SqlflowJSON>>(
      "/sqlflow/generation/sqlflow/erdiagramSelectGraph",
      {
        ...params,
        dbvendor: toApiDbVendor(params.dbvendor)
      }
    );
  }

  /** Unwrapped `JoinAnalysisGraph` (`schemaVersion: "join-analysis.v1"`, `00 §3.3 #15`, `02 §4.2.1`, `04 §7.4`) */
  public async analyzeJoins(params: AnalyzeJoinsParams): Promise<JoinAnalysisGraph> {
    return this.postForm<JoinAnalysisGraph>(
      "/sqlflow/generation/sqlflow/join-analysis",
      {
        ...(params.userId !== undefined ? { userId: params.userId } : {}),
        sqltext: params.sqltext,
        dbvendor: toApiDbVendor(params.dbvendor),
        ...(params.jobId !== undefined ? { jobId: params.jobId } : {}),
        ...(params.defaultServer !== undefined
          ? { defaultServer: params.defaultServer }
          : {}),
        ...(params.defaultDatabase !== undefined
          ? { defaultDatabase: params.defaultDatabase }
          : {}),
        ...(params.defaultSchema !== undefined
          ? { defaultSchema: params.defaultSchema }
          : {})
      }
    );
  }

  public async getSelectedDbObjectInfo(params: {
    sessionId: string;
    coordinates: Coordinate[] | string;
  }): Promise<SelectedDbObjectInfoResponse> {
    return this.postForm<SelectedDbObjectInfoResponse>(
      "/sqlflow/generation/sqlflow/getSelectedDbObjectInfo",
      {
        sessionId: params.sessionId,
        coordinates:
          typeof params.coordinates === "string"
            ? params.coordinates
            : JSON.stringify(params.coordinates)
      }
    );
  }

  public async getSharedGraph(shareId: string): Promise<ApiEnvelope<SqlflowJSON>> {
    return this.getJson<ApiEnvelope<SqlflowJSON>>(
      `/sqlflow/generation/sqlflow/graph/share/${encodeURIComponent(shareId)}`
    );
  }

  public async formatSql(params: {
    sqltext: string;
    dbvendor: string;
  }): Promise<ApiEnvelope<string>> {
    return this.postForm<ApiEnvelope<string>>("/sqlflow/generation/sql/format", {
      sqltext: params.sqltext,
      dbvendor: toApiDbVendor(params.dbvendor)
    });
  }

  public async exportFullLineageAsCsv(params: {
    sqltext: string;
    dbvendor: string;
    showRelationType?: string;
    treatArgumentsInCountFunctionAsDirectDataflow?: boolean;
    ignoreRecordSet?: boolean;
    showConstantTable?: boolean;
    showTransform?: boolean;
  }): Promise<string> {
    return this.postFormText("/sqlflow/generation/sqlflow/exportFullLineageAsCsv", {
      ...params,
      dbvendor: toApiDbVendor(params.dbvendor),
      ignoreFunction: true,
      simpleOutput: true
    });
  }

  // =========================================================================
  // 3. JDBC Connection & Batch / Persist Jobs (STRICT RSA PASSWORD SCOPE!)
  // =========================================================================

  /**
   * Tests live JDBC database connectivity (`POST /database/testConnection`).
   * Strictly RSA-encrypts `DataSource.password` via `JSEncrypt` (`00 §3.3 #7`).
   */
  public async testConnection(
    dataSource: DataSource
  ): Promise<ApiEnvelope<unknown>> {
    const encryptedPassword = encryptRsaPassword(dataSource.password, this.rsaPublicKey);
    return this.postForm<ApiEnvelope<unknown>>("/database/testConnection", {
      ...dataSource,
      dbvendor: toApiDbVendor(dataSource.dbvendor),
      password: encryptedPassword
    });
  }

  private buildJobMultipartFormData(params: SubmitJobParams): FormData {
    const fd = new FormData();
    const resolvedUserId = params.userId ?? this.userId;
    fd.append("userId", resolvedUserId);
    fd.append("dbvendor", toApiDbVendor(params.dbvendor));
    fd.append("jobName", params.jobName);
    fd.append("sqlsource", params.sqlsource);
    fd.append("parallel", String(Boolean(params.parallel)));

    const appendOptionalScalar = (key: string, val: unknown) => {
      if (val !== undefined && val !== null) {
        fd.append(key, String(val));
      }
    };

    appendOptionalScalar(
      "treatArgumentsInCountFunctionAsDirectDataflow",
      params.treatArgumentsInCountFunctionAsDirectDataflow
    );
    appendOptionalScalar("ignoreFunction", params.ignoreFunction);
    appendOptionalScalar("ignoreRecordSet", params.ignoreRecordSet);
    appendOptionalScalar("showConstantTable", params.showConstantTable);
    appendOptionalScalar("showRelationType", params.showRelationType);
    appendOptionalScalar("showTransform", params.showTransform);
    appendOptionalScalar("defaultServer", params.defaultServer);
    appendOptionalScalar("defaultDatabase", params.defaultDatabase);
    appendOptionalScalar("defaultSchema", params.defaultSchema);
    appendOptionalScalar("objectCodeEncloseChar", params.objectCodeEncloseChar);
    appendOptionalScalar("objectCodeEscapeChar", params.objectCodeEscapeChar);
    appendOptionalScalar("csvFormat", params.csvFormat);
    appendOptionalScalar("firstSubmit", params.firstSubmit);
    appendOptionalScalar("incremental", params.incremental);
    appendOptionalScalar("schedulable", params.schedulable);
    appendOptionalScalar("cronExpression", params.cronExpression);

    if (params.extra !== undefined) {
      fd.append(
        "extra",
        typeof params.extra === "string" ? params.extra : JSON.stringify(params.extra)
      );
    }

    // Merge flat JDBC fields and nested `params.dataSource`
    const ds = params.dataSource ?? {};
    const hostname = params.hostname ?? ds.hostname;
    const port = params.port ?? ds.port;
    const database = params.database ?? ds.database;
    const account = params.account ?? ds.account;
    const rawPassword = params.password ?? ds.password;
    const extractedDbsSchemas = params.extractedDbsSchemas ?? ds.extractedDbsSchemas;
    const excludedDbsSchemas = params.excludedDbsSchemas ?? ds.excludedDbsSchemas;
    const extractedStoredProcedures =
      params.extractedStoredProcedures ?? ds.extractedStoredProcedures;
    const extractedViews = params.extractedViews ?? ds.extractedViews;

    appendOptionalScalar("hostname", hostname);
    appendOptionalScalar("port", port);
    appendOptionalScalar("database", database);
    appendOptionalScalar("account", account);
    if (rawPassword !== undefined && rawPassword !== null && rawPassword !== "") {
      fd.append("password", encryptRsaPassword(rawPassword, this.rsaPublicKey));
    }
    appendOptionalScalar("extractedDbsSchemas", extractedDbsSchemas);
    appendOptionalScalar("excludedDbsSchemas", excludedDbsSchemas);
    appendOptionalScalar("extractedStoredProcedures", extractedStoredProcedures);
    appendOptionalScalar("extractedViews", extractedViews);

    for (const file of params.sqlfiles ?? []) {
      fd.append("sqlfiles", file);
    }

    return fd;
  }

  /**
   * Submits a standard batch job (`POST /sqlflow/job/submitUserJob`).
   * Strictly RSA-encrypts `password` when present (`00 §3.3 #7`).
   */
  public async submitUserJob(
    params: SubmitJobParams
  ): Promise<ApiEnvelope<{ jobId: string }>> {
    const fd = this.buildJobMultipartFormData(params);
    return this.postMultipart<ApiEnvelope<{ jobId: string }>>(
      "/sqlflow/job/submitUserJob",
      fd
    );
  }

  /**
   * Submits an incremental / persist job (`POST /sqlflow/job/submitPersistJob`).
   * Strictly RSA-encrypts `password` when present (`00 §3.3 #7`).
   */
  public async submitPersistJob(
    params: SubmitJobParams,
    encoding: "multipart" | "form" = "multipart"
  ): Promise<ApiEnvelope<{ jobId: string }>> {
    const mergedParams: SubmitJobParams = {
      incremental: true,
      ...params
    };
    if (encoding === "form") {
      const ds = mergedParams.dataSource ?? {};
      const rawPassword = mergedParams.password ?? ds.password;
      const formPayload: Record<string, unknown> = {
        ...ds,
        ...mergedParams,
        dbvendor: toApiDbVendor(mergedParams.dbvendor),
        extra:
          mergedParams.extra && typeof mergedParams.extra === "object"
            ? JSON.stringify(mergedParams.extra)
            : mergedParams.extra
      };
      delete formPayload.dataSource;
      delete formPayload.sqlfiles;
      if (typeof rawPassword === "string" && rawPassword.length > 0) {
        formPayload.password = encryptRsaPassword(rawPassword, this.rsaPublicKey);
      }
      return this.postForm<ApiEnvelope<{ jobId: string }>>(
        "/sqlflow/job/submitPersistJob",
        formPayload
      );
    }
    const fd = this.buildJobMultipartFormData(mergedParams);
    return this.postMultipart<ApiEnvelope<{ jobId: string }>>(
      "/sqlflow/job/submitPersistJob",
      fd
    );
  }

  public async displayUserJobsSummary(): Promise<ApiEnvelope<UserJobSummary>> {
    return this.postForm<ApiEnvelope<UserJobSummary>>("/sqlflow/job/displayUserJobsSummary");
  }

  public async displayUserJobSummary(jobName: string): Promise<ApiEnvelope<JobSummary>> {
    return this.postForm<ApiEnvelope<JobSummary>>("/sqlflow/job/displayUserJobSummary", {
      jobName
    });
  }

  public async updateUserJobGraphCache(
    jobId: string
  ): Promise<ApiEnvelope<{ sessionId: string }>> {
    return this.postForm<ApiEnvelope<{ sessionId: string }>>(
      "/sqlflow/job/updateUserJobGraphCache",
      { jobId }
    );
  }

  public async displayUserLatestJobGraph(): Promise<LatestJobGraphResponse> {
    return this.postForm<LatestJobGraphResponse>("/sqlflow/job/displayUserLatestJobGraph");
  }

  public async getJobGraphSummary(params: {
    jobId: string;
    dbvendor: string;
  }): Promise<JobGraphSummaryResponse> {
    return this.postForm<JobGraphSummaryResponse>("/sqlflow/job/graph/summary", {
      jobId: params.jobId,
      dbvendor: toApiDbVendor(params.dbvendor)
    });
  }

  public async deleteUserJob(jobId: string): Promise<ApiEnvelope<unknown>> {
    return this.postForm<ApiEnvelope<unknown>>("/sqlflow/job/deleteUserJob", { jobId });
  }

  public async setJobOnTop(jobId: string, onTop: boolean): Promise<ApiEnvelope<unknown>> {
    return this.postForm<ApiEnvelope<unknown>>("/sqlflow/job/setJobOnTop", { jobId, onTop });
  }

  public async rerunJob(jobId: string, jobName: string): Promise<ApiEnvelope<unknown>> {
    return this.postForm<ApiEnvelope<unknown>>("/sqlflow/job/rerunJob", { jobId, jobName });
  }

  // =========================================================================
  // 4. User Auth, API Tokens, Settings, User Schema & Share (NO RSA ON PASSWORDS!)
  // =========================================================================

  /**
   * On-premise login (`POST /user/register` — `04 §10.3 #1`, `00 §3.3 #7`).
   * Transmits plaintext `password` WITHOUT RSA encryption!
   */
  public async login(params: {
    userEmail: string;
    password: string;
    userId?: string;
  }): Promise<ApiEnvelope<RegisterOrLoginResponseData>> {
    return this.postForm<ApiEnvelope<RegisterOrLoginResponseData>>("/user/register", {
      userEmail: params.userEmail,
      password: params.password,
      ...(params.userId ? { userId: params.userId } : {})
    });
  }

  public async getUserInfo(): Promise<ApiEnvelope<UserVo>> {
    return this.postForm<ApiEnvelope<UserVo>>("/user/info");
  }

  /** Plaintext `oldPassword` and `newPassword` (`04 §10.3 #3`, `00 §3.3 #7`) */
  public async modifyPassword(params: {
    oldPassword: string;
    newPassword: string;
  }): Promise<ApiEnvelope<unknown>> {
    return this.postForm<ApiEnvelope<unknown>>("/user/modifyPassword", {
      oldPassword: params.oldPassword,
      newPassword: params.newPassword
    });
  }

  /** Plaintext `password` (`04 §10.3 #6`, `00 §3.3 #7`) */
  public async addUser(params: {
    operatorUserId: string;
    userEmail: string;
    userName: string;
    password?: string;
    systemAdmin?: boolean;
    expireDate?: string;
  }): Promise<ApiEnvelope<unknown>> {
    return this.postForm<ApiEnvelope<unknown>>("/user/addUser", {
      password: params.password ?? "123456",
      ...params
    });
  }

  /** Plaintext `password` (`04 §10.3 #6`, `00 §3.3 #7`) */
  public async resetPassword(params: {
    operatorUserId: string;
    userEmail: string;
    password?: string;
  }): Promise<ApiEnvelope<unknown>> {
    return this.postForm<ApiEnvelope<unknown>>("/user/resetPassword", {
      password: params.password ?? "123456",
      ...params
    });
  }

  public async upgradeUser(params: {
    targetUserId: string;
    operatorUserId: string;
    renewalDate: string;
  }): Promise<ApiEnvelope<{ endDate: string }>> {
    return this.postForm<ApiEnvelope<{ endDate: string }>>("/user/upgrade", {
      userId: params.targetUserId,
      operatorUserId: params.operatorUserId,
      renewalDate: params.renewalDate
    });
  }

  /** Strict string `code === "200"` at top-level (`00 §3.3 #3`) */
  public async generateSecretKey(): Promise<GenerateSecretKeyResponse> {
    return this.postForm<GenerateSecretKeyResponse>("/user/generateSecretKey");
  }

  /** Strict string `code === "200"` at top-level (`00 §3.3 #3`) */
  public async generateToken(secretKey: string): Promise<GenerateTokenResponse> {
    return this.postForm<GenerateTokenResponse>("/user/generateToken", { secretKey });
  }

  public async generateApiTokenPair(): Promise<{ secretKey: string; token: string }> {
    const skRes = await this.generateSecretKey();
    if (skRes.code !== "200" || !skRes.secretKey) {
      throw new Error(skRes.error || "Failed to generate secretKey");
    }
    const tkRes = await this.generateToken(skRes.secretKey);
    if (tkRes.code !== "200" || !tkRes.token) {
      throw new Error(tkRes.error || "Failed to generate token");
    }
    return { secretKey: skRes.secretKey, token: tkRes.token };
  }

  public async getUserSetting(
    section = "querymode"
  ): Promise<ApiEnvelope<Record<string, unknown>>> {
    return this.postForm<ApiEnvelope<Record<string, unknown>>>("/user/getUserSetting", {
      section
    });
  }

  public async saveUserSetting(
    setting: Record<string, unknown>,
    section = "querymode"
  ): Promise<ApiEnvelope<unknown>> {
    return this.postForm<ApiEnvelope<unknown>>("/user/saveUserSetting", {
      section,
      key: null,
      value: JSON.stringify(setting)
    });
  }

  public async submitUserSchema(params: {
    dbvendor: string;
    sqlfile: File | Blob;
  }): Promise<ApiEnvelope<unknown>> {
    const fd = new FormData();
    fd.append("userId", this.userId);
    fd.append("dbvendor", toApiDbVendor(params.dbvendor));
    fd.append("sqlfiles", params.sqlfile);
    return this.postMultipart<ApiEnvelope<unknown>>(
      "/sqlflow/generation/submitUserSchema",
      fd
    );
  }

  public async displayUserSchemaSummary(): Promise<ApiEnvelope<UserSchemaSummaryMap>> {
    return this.postForm<ApiEnvelope<UserSchemaSummaryMap>>(
      "/sqlflow/generation/displayUserSchemaSummary"
    );
  }

  public async deleteUserSchema(params: {
    dbvendor: string;
    fileName: string;
  }): Promise<ApiEnvelope<unknown>> {
    const fd = new FormData();
    fd.append("userId", this.userId);
    fd.append("dbvendor", toApiDbVendor(params.dbvendor));
    fd.append("fileName", params.fileName);
    return this.postMultipart<ApiEnvelope<unknown>>(
      "/sqlflow/generation/deleteUserSchema",
      fd
    );
  }

  public async displayUserShareSummary(): Promise<ApiEnvelope<ShareSummaryItem[]>> {
    return this.postForm<ApiEnvelope<ShareSummaryItem[]>>("/user/displayUserShareSummary");
  }

  public async deleteUserShare(shareId: string): Promise<ApiEnvelope<unknown>> {
    return this.postForm<ApiEnvelope<unknown>>("/user/deleteUserShare", { shareId });
  }

  // =========================================================================
  // 5. AI Lineage Assistant Endpoints (`00 §3.3 #16`, `02 §4.5` #1–#6)
  // =========================================================================

  /** #1 `GET /sqlflow/generation/ai/status` -> unwrapped `{ enabled, model }` (`00 §3.3 #16`) */
  public async getAiStatus(): Promise<AiStatusResponse> {
    return this.getJson<AiStatusResponse>("/sqlflow/generation/ai/status");
  }

  /** #2 `POST /sqlflow/generation/ai/query` (`@RequestParam` form body -> `text/event-stream`) */
  public async streamAiQuery(
    params: AiQueryStreamParams,
    options?: {
      signal?: AbortSignal;
      onEvent?: (event: AiSseEvent) => void;
    }
  ): Promise<AiSseEvent[]> {
    return streamAiLineageQuery({
      url: `${this.baseUrl}/sqlflow/generation/ai/query`,
      params,
      token: this.token,
      userId: this.userId,
      fetchImpl: this.fetchFn,
      signal: options?.signal,
      onEvent: options?.onEvent
    });
  }

  /** #3 `POST /sqlflow/generation/ai/query/sync` (`@RequestParam` form body -> `ApiEnvelope<AiQueryResponse>`) */
  public async queryAiSync(
    params: AiQueryStreamParams
  ): Promise<ApiEnvelope<AiQueryResponse>> {
    return this.postForm<ApiEnvelope<AiQueryResponse>>(
      "/sqlflow/generation/ai/query/sync",
      {
        ...(params.userId ? { userId: params.userId } : {}),
        sessionId: params.sessionId,
        question: params.question,
        dbvendor: toApiDbVendor(params.dbvendor ?? "dbvoracle"),
        ...(params.hopDepth !== undefined ? { hopDepth: params.hopDepth } : {})
      }
    );
  }

  /** #4 `POST /sqlflow/generation/ai/node-query` (`@RequestParam` form body -> `ApiEnvelope<AiQueryResponse>`) */
  public async queryAiNode(
    params: AiNodeQueryParams
  ): Promise<ApiEnvelope<AiQueryResponse>> {
    return this.postForm<ApiEnvelope<AiQueryResponse>>(
      "/sqlflow/generation/ai/node-query",
      {
        ...(params.userId ? { userId: params.userId } : {}),
        sessionId: params.sessionId,
        nodeId: params.nodeId,
        nodeName: params.nodeName,
        nodeType: params.nodeType ?? "table",
        queryType: params.queryType ?? "explain",
        dbvendor: toApiDbVendor(params.dbvendor ?? "dbvoracle")
      }
    );
  }

  /** #5 `GET /sqlflow/generation/ai/suggestions` -> `ApiEnvelope<AiSuggestedQuestion[]>` */
  public async getAiSuggestions(
    params: AiSuggestionsParams
  ): Promise<ApiEnvelope<AiSuggestedQuestion[]>> {
    return this.getJson<ApiEnvelope<AiSuggestedQuestion[]>>(
      "/sqlflow/generation/ai/suggestions",
      {
        ...(params.userId ? { userId: params.userId } : {}),
        sessionId: params.sessionId,
        ...(params.dbvendor !== undefined
          ? { dbvendor: toApiDbVendor(params.dbvendor) }
          : {}),
        ...(params.maxQuestions !== undefined
          ? { maxQuestions: params.maxQuestions }
          : {})
      }
    );
  }

  /** Alias of `getAiSuggestions` (`GET /sqlflow/generation/ai/suggestions` -> `ApiEnvelope<AiSuggestedQuestion[]>`) */
  public async getAiSuggestedQuestions(
    params: AiSuggestionsParams
  ): Promise<ApiEnvelope<AiSuggestedQuestion[]>> {
    return this.getAiSuggestions(params);
  }

  /** #6 `POST /sqlflow/generation/ai/suggestions/click` (`@RequestParam` form body -> `{"code": 200}`) */
  public async recordAiSuggestionClick(
    params: AiSuggestionClickParams
  ): Promise<ApiEnvelope<unknown>> {
    return this.postForm<ApiEnvelope<unknown>>(
      "/sqlflow/generation/ai/suggestions/click",
      {
        ...(params.userId ? { userId: params.userId } : {}),
        question: params.question,
        category: params.category ?? "",
        targetNode: params.targetNode ?? ""
      }
    );
  }
}

export function createSpaApiClient(
  options: Omit<SqlFlowApiClientOptions, "mode"> = {}
): SqlFlowApiClient {
  return new SqlFlowApiClient({
    ...options,
    mode: "spa",
    apiPrefix: options.apiPrefix ?? SPA_DEFAULT_API_PREFIX
  });
}

export function createWidgetApiClient(
  options: Omit<SqlFlowApiClientOptions, "mode"> = {}
): SqlFlowApiClient {
  return new SqlFlowApiClient({
    ...options,
    mode: "widget",
    apiPrefix: options.apiPrefix ?? WIDGET_DEFAULT_API_PREFIX
  });
}
