import {
  type AiSuggestedQuestion,
  type ApiEnvelope,
  type CallGraphPayload,
  type CouponVo,
  DEFAULT_GUEST_USER_ID,
  type GroupUserVo,
  type GroupVo,
  type PaginatedResult,
  type ProductItem,
  type ProductsResponse,
  SPA_DEFAULT_API_PREFIX,
  SqlFlowApiClient,
  type SqlFlowApiClientOptions,
  type SqlflowJSON,
  type UserSchemaSummaryMap,
  type UserVo,
  resolveBackendApiBase,
  toApiDbVendor
} from "@sqlflow/ui-core";

export interface RuntimePrivateConfig {
  SERVER?: string;
  Product?: string;
  ApiPrefix?: string;
  Authorization?: {
    base64token?: string;
  };
  userId?: string;
  trialVersion?: boolean;
}

export interface ParsedVendorSchemas {
  vendor: string;
  files: string[];
}

export interface PermalinkRecord {
  permalinkId: string;
  sqltext: string;
  dbvendor: string;
  createdAt?: string;
  graph?: SqlflowJSON;
}

export interface AdminSqlRecord {
  userId: string;
  userEmail?: string;
  fileName: string;
  dbvendor?: string;
  submitTime?: string;
  size?: number;
}

export interface AdminRequestLogRecord {
  id?: string;
  userId: string;
  endpoint: string;
  method?: string;
  ip?: string;
  requestTime: string;
  statusCode?: number;
}

/**
 * Parses `displayUserSchemaSummary`'s wire-invariant double-nested array map (`00 §3.3 #11`):
 * e.g. `{ "oracle": [["schema1.sql"], ["schema2.sql"]] }` -> `[{ vendor: "oracle", files: ["schema1.sql", "schema2.sql"] }]`.
 */
export function parseUserSchemaSummaryMap(
  raw?: UserSchemaSummaryMap | Record<string, unknown> | null
): ParsedVendorSchemas[] {
  if (!raw || typeof raw !== "object") {
    return [];
  }
  const result: ParsedVendorSchemas[] = [];
  for (const [vendor, nested] of Object.entries(raw)) {
    if (!Array.isArray(nested)) {
      continue;
    }
    const flatFiles: string[] = [];
    for (const entry of nested) {
      if (Array.isArray(entry)) {
        for (const f of entry) {
          if (typeof f === "string" && f.trim()) {
            flatFiles.push(f.trim());
          }
        }
      } else if (typeof entry === "string" && entry.trim()) {
        flatFiles.push(entry.trim());
      }
    }
    result.push({ vendor, files: Array.from(new Set(flatFiles)) });
  }
  return result;
}

/**
 * Deterministic SQL formatter fallback when server formatter returns empty or is offline.
 */
export function formatSqlLocally(sql: string): string {
  const trimmed = sql.trim();
  if (!trimmed) {
    return sql;
  }
  const majorClauses = [
    "SELECT",
    "FROM",
    "LEFT OUTER JOIN",
    "RIGHT OUTER JOIN",
    "FULL OUTER JOIN",
    "LEFT JOIN",
    "RIGHT JOIN",
    "INNER JOIN",
    "CROSS JOIN",
    "JOIN",
    "WHERE",
    "GROUP BY",
    "HAVING",
    "ORDER BY",
    "UNION ALL",
    "UNION"
  ];
  let out = trimmed.replace(/[ \t]+/g, " ");
  for (const kw of majorClauses) {
    const pattern = new RegExp(`\\s+(${kw.replace(/ /g, "\\s+")})\\b`, "gi");
    out = out.replace(pattern, `\n$1`);
  }
  return out;
}

export class SpaApiService {
  public readonly client: SqlFlowApiClient;
  private fetchImpl: typeof fetch;

  public constructor(options: SqlFlowApiClientOptions = {}) {
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
    this.client = new SqlFlowApiClient({
      mode: "spa",
      apiPrefix: options.apiPrefix ?? SPA_DEFAULT_API_PREFIX,
      userId: options.userId ?? DEFAULT_GUEST_USER_ID,
      ...options,
      fetchImpl: this.fetchImpl
    });
  }

  public async fetchRuntimeConfig(): Promise<RuntimePrivateConfig> {
    try {
      const res = await this.fetchImpl("config.private.json", {
        method: "GET",
        headers: { Accept: "application/json" }
      });
      if (res && res.ok) {
        const cfg = (await res.json()) as RuntimePrivateConfig & { rsaPublicKey?: string };
        const prefix = cfg.SERVER || cfg.ApiPrefix;
        if (prefix) {
          this.client.setApiPrefix(resolveBackendApiBase(prefix, "spa"));
        }
        if (typeof cfg.rsaPublicKey === "string" && cfg.rsaPublicKey.trim()) {
          this.client.setRsaPublicKey(cfg.rsaPublicKey.trim());
        }
        return cfg;
      }
    } catch {
      // Default config fallback
    }
    return {
      SERVER: SPA_DEFAULT_API_PREFIX,
      Product: "private",
      ApiPrefix: "/api",
      userId: DEFAULT_GUEST_USER_ID,
      trialVersion: false
    };
  }

  private buildFullUrl(subPath: string, query?: Record<string, string | undefined>): string {
    const base = this.client.getBaseUrl();
    const cleanPath = subPath.startsWith("/") ? subPath : `/${subPath}`;
    const url = `${base}${cleanPath}`;
    if (!query) {
      return url;
    }
    const qs = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined) {
        qs.append(k, v);
      }
    }
    const str = qs.toString();
    return str ? `${url}?${str}` : url;
  }

  private authHeaders(extra?: Record<string, string>): Record<string, string> {
    return {
      Authorization: `Token ${this.client.getToken()}`,
      ...(extra ?? {})
    };
  }

  /**
   * Formats SQL via `/demo/sqlformat` first (`OBL-094` / `04 §5.3`), falling back to
   * `/sqlflow/generation/sql/format` and local formatting if unavailable.
   */
  public async formatSqlWithDemoFallback(sqltext: string, dbvendor: string): Promise<string> {
    const apiVendor = toApiDbVendor(dbvendor);
    try {
      const demoRes = await this.client.postForm<
        ApiEnvelope<string | { sql?: string; formattedSql?: string }> & {
          formattedSql?: string;
        }
      >("/demo/sqlformat", {
        sqltext,
        sql: sqltext,
        dbvendor: apiVendor
      });
      if (demoRes) {
        if (typeof demoRes.data === "string" && demoRes.data.trim()) {
          return demoRes.data;
        }
        if (demoRes.data && typeof demoRes.data === "object") {
          const nested = demoRes.data.formattedSql ?? demoRes.data.sql;
          if (typeof nested === "string" && nested.trim()) {
            return nested;
          }
        }
        if (typeof demoRes.formattedSql === "string" && demoRes.formattedSql.trim()) {
          return demoRes.formattedSql;
        }
      }
    } catch {
      // Try secondary endpoint
    }

    try {
      const secondary = await this.client.formatSql({ sqltext, dbvendor: apiVendor });
      if (typeof secondary.data === "string" && secondary.data.trim()) {
        return secondary.data;
      }
    } catch {
      // Fallback to local deterministic formatter
    }

    return formatSqlLocally(sqltext);
  }

  /**
   * Generates Routine Call Graph via `/sqlflow/generation/sqlflow/graph/call_lineage` (`04 §7.3`)
   * with fallback to `/demo/callRelation` (`OBL-102`).
   */
  public async fetchCallGraph(params: {
    sqltext: string;
    dbvendor: string;
    sessionId?: string;
    jobId?: string;
  }): Promise<ApiEnvelope<SqlflowJSON & Partial<CallGraphPayload>>> {
    try {
      const primary = await this.client.generateCallLineage(params);
      if (primary && (Number(primary.code) === 200 || primary.data)) {
        return primary;
      }
    } catch {
      // Fall back to /demo/callRelation
    }
    return this.client.postForm<ApiEnvelope<SqlflowJSON & Partial<CallGraphPayload>>>(
      "/demo/callRelation",
      {
        sqltext: params.sqltext,
        sessionId: params.sessionId,
        dbvendor: toApiDbVendor(params.dbvendor),
        jobId: params.jobId
      }
    );
  }

  // ===========================================================================
  // Schema Explorer (`OBL-104`, Invariant #11)
  // ===========================================================================

  public async uploadUserDdlSchema(
    files: File[],
    dbvendor: string
  ): Promise<ApiEnvelope<unknown>> {
    const fd = new FormData();
    fd.append("userId", this.client.getUserId());
    fd.append("dbvendor", toApiDbVendor(dbvendor));
    for (const file of files) {
      fd.append("sqlfiles", file);
    }
    return this.client.postMultipart<ApiEnvelope<unknown>>(
      "/sqlflow/generation/submitUserSchema",
      fd
    );
  }

  public async listUserSchemas(): Promise<ParsedVendorSchemas[]> {
    const res = await this.client.displayUserSchemaSummary();
    return parseUserSchemaSummaryMap(res.data);
  }

  public async getUserSchemaDdlContent(
    dbvendor: string,
    fileName?: string
  ): Promise<string> {
    const res = await this.client.postForm<
      ApiEnvelope<string | string[] | { content?: string }>
    >("/sqlflow/generation/displayUserDbvendorSchema", {
      dbvendor: toApiDbVendor(dbvendor),
      ...(fileName ? { fileName } : {})
    });
    if (Array.isArray(res.data)) {
      return res.data.join("\n\n");
    }
    if (typeof res.data === "string") {
      return res.data;
    }
    if (res.data && typeof res.data === "object" && typeof res.data.content === "string") {
      return res.data.content;
    }
    return "";
  }

  // ===========================================================================
  // Share & Permalink (`OBL-093`, `OBL-109`)
  // ===========================================================================

  public async createShareGraph(params: {
    sqltext: string;
    dbvendor: string;
    tableLevel?: boolean;
    sessionId?: string;
  }): Promise<{ shareId: string; tableLevel: boolean }> {
    if (params.sessionId && params.tableLevel) {
      const res = await this.client.selectTableLevelLineage({
        sessionId: params.sessionId,
        dbvendor: params.dbvendor,
        share: true
      });
      const dataRecord = (res.data ?? {}) as unknown as Record<string, unknown>;
      const sid = String(dataRecord.shareId ?? res.sessionId ?? `share-${Date.now()}`);
      return { shareId: sid, tableLevel: true };
    }
    const res = await this.client.generateGraph({
      sqltext: params.sqltext,
      dbvendor: params.dbvendor,
      share: true
    });
    const dataRecord = (res.data ?? {}) as unknown as Record<string, unknown>;
    const shareId = String(dataRecord.shareId ?? res.sessionId ?? `share-${Date.now()}`);
    return { shareId, tableLevel: Boolean(params.tableLevel) };
  }

  public async createPermalink(params: {
    sqltext: string;
    dbvendor: string;
  }): Promise<{ permalinkId: string }> {
    const apiVendor = toApiDbVendor(params.dbvendor);
    try {
      const exists = await this.client.postForm<
        ApiEnvelope<{ permalinkId?: string; id?: string }>
      >("/permalink/checkExistence", {
        sqltext: params.sqltext,
        dbvendor: apiVendor
      });
      const existingId = exists.data?.permalinkId ?? exists.data?.id;
      if (existingId) {
        return { permalinkId: existingId };
      }
    } catch {
      // Proceed to create
    }

    const created = await this.client.postForm<
      ApiEnvelope<{ permalinkId?: string; id?: string } | string>
    >("/permalink/createPermalink", {
      sqltext: params.sqltext,
      dbvendor: apiVendor
    });
    if (typeof created.data === "string" && created.data.trim()) {
      return { permalinkId: created.data.trim() };
    }
    if (created.data && typeof created.data === "object") {
      const id = created.data.permalinkId ?? created.data.id;
      if (id) {
        return { permalinkId: id };
      }
    }
    return { permalinkId: `pl-${Date.now()}` };
  }

  public async getPermalinkData(permalinkId: string): Promise<PermalinkRecord | null> {
    const res = await this.client.postForm<ApiEnvelope<PermalinkRecord>>(
      "/permalink/getPermalinkData",
      {
        permalinkId,
        id: permalinkId
      }
    );
    return res.data ?? null;
  }

  // ===========================================================================
  // Server PNG & Job Artifacts (`OBL-095`, `OBL-105`)
  // ===========================================================================

  public async exportServerPngBlob(params: {
    sqltext: string;
    dbvendor: string;
    sessionId?: string;
    jobId?: string;
  }): Promise<Blob> {
    const body = new URLSearchParams({
      userId: this.client.getUserId(),
      sqltext: params.sqltext,
      dbvendor: toApiDbVendor(params.dbvendor),
      ...(params.sessionId ? { sessionId: params.sessionId } : {}),
      ...(params.jobId ? { jobId: params.jobId } : {})
    });
    const res = await this.fetchImpl(
      this.buildFullUrl("/sqlflow/generation/sqlflow/graph/image"),
      {
        method: "POST",
        headers: this.authHeaders({
          "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
        }),
        body: body.toString()
      }
    );
    return res.blob();
  }

  public async cancelUserJob(jobId: string): Promise<ApiEnvelope<unknown>> {
    return this.client.postForm<ApiEnvelope<unknown>>("/sqlflow/job/stopUserJob", { jobId });
  }

  public async toggleScheduleJob(
    jobId: string,
    paused: boolean
  ): Promise<ApiEnvelope<unknown>> {
    const path = paused
      ? "/sqlflow/job/pauseScheduleJob"
      : "/sqlflow/job/resumeScheduleJob";
    return this.client.postForm<ApiEnvelope<unknown>>(path, { jobId });
  }

  public async getJobSqlFileContent(jobId: string, fileName: string): Promise<string> {
    // 1. `POST /sqlflow/job/getJobFiles` (`01 §3.4.14 #26`)
    try {
      const filesRes = await this.client.postForm<
        ApiEnvelope<
          | string
          | Array<{ fileName?: string; name?: string; sql?: string; content?: string }>
          | Record<string, unknown>
        >
      >("/sqlflow/job/getJobFiles", { jobId, fileName });
      if (typeof filesRes.data === "string" && filesRes.data.trim()) {
        return filesRes.data;
      }
      if (Array.isArray(filesRes.data)) {
        const matched =
          filesRes.data.find((f) => f.fileName === fileName || f.name === fileName) ??
          filesRes.data[0];
        const sqlText = matched?.sql ?? matched?.content;
        if (typeof sqlText === "string" && sqlText.trim()) {
          return sqlText;
        }
      } else if (filesRes.data && typeof filesRes.data === "object") {
        const direct = filesRes.data[fileName] ?? filesRes.data.sql ?? filesRes.data.content;
        if (typeof direct === "string" && direct.trim()) {
          return direct;
        }
      }
    } catch {
      // Try GET exportJobFile next
    }

    // 2. `GET /sqlflow/job/exportJobFile?userId=...&jobId=...&fileName=...` (`01 §3.4.14 #39`)
    try {
      const exportUrl = this.buildFullUrl("/sqlflow/job/exportJobFile", {
        userId: this.client.getUserId(),
        jobId,
        fileName
      });
      const exportRes = await this.fetchImpl(exportUrl, {
        method: "GET",
        headers: this.authHeaders()
      });
      if (exportRes && exportRes.ok) {
        const rawText = await exportRes.text();
        if (rawText.trim()) {
          try {
            const parsed = JSON.parse(rawText) as Record<string, unknown>;
            if (typeof parsed.data === "string" && parsed.data.trim()) {
              return parsed.data;
            }
          } catch {
            return rawText;
          }
        }
      }
    } catch {
      // Fallback to displayMetadataQuerySource
    }

    // 3. Fallback `POST /sqlflow/job/displayMetadataQuerySource` (`{ jobId, name, fileName }`)
    const res = await this.client.postForm<
      ApiEnvelope<string | { sql?: string; content?: string }>
    >("/sqlflow/job/displayMetadataQuerySource", {
      jobId,
      name: fileName,
      fileName
    });
    if (typeof res.data === "string") {
      return res.data;
    }
    if (res.data && typeof res.data === "object") {
      return res.data.sql ?? res.data.content ?? "";
    }
    return "";
  }

  public async downloadJobArtifactBlob(
    jobId: string,
    format: "json" | "xml" | "csv" | "zip" | "parquet",
    jobName?: string
  ): Promise<Blob> {
    const userId = this.client.getUserId();

    if (format === "zip") {
      // `GET /sqlflow/job/exportMetadata?userId=...&jobId=...` (`01 §3.4.14 #25`)
      const url = this.buildFullUrl("/sqlflow/job/exportMetadata", { userId, jobId });
      const res = await this.fetchImpl(url, {
        method: "GET",
        headers: this.authHeaders()
      });
      return res.blob();
    }

    if (format === "parquet") {
      // `POST /sqlflow/job/generate/parquet` -> `GET /sqlflow/job/download/parquet` (`01 §3.4.14 #6, #7`)
      const effectiveJobName = jobName ?? jobId;
      await this.client.postForm<ApiEnvelope<unknown>>("/sqlflow/job/generate/parquet", {
        jobId,
        jobName: effectiveJobName,
        batchId: ""
      });
      const url = this.buildFullUrl("/sqlflow/job/download/parquet", {
        userId,
        jobId,
        jobName: effectiveJobName,
        batchId: ""
      });
      const res = await this.fetchImpl(url, {
        method: "GET",
        headers: this.authHeaders()
      });
      return res.blob();
    }

    const endpointByFormat: Record<"json" | "xml" | "csv", string> = {
      json: "/sqlflow/job/exportFullLineageAsJson",
      xml: "/sqlflow/job/exportLineageAsGraphml",
      csv: "/sqlflow/job/exportFullLineageAsCsv"
    };
    const body = new URLSearchParams({
      userId,
      jobId
    });
    const res = await this.fetchImpl(this.buildFullUrl(endpointByFormat[format]), {
      method: "POST",
      headers: this.authHeaders({
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
      }),
      body: body.toString()
    });
    return res.blob();
  }

  // ===========================================================================
  // AI Lineage Assistant Index & Suggestions (`OBL-107`, Invariant #16)
  // ===========================================================================

  public async buildAiLineageIndex(params: {
    sessionId: string;
    dbvendor: string;
    sqltext?: string;
  }): Promise<ApiEnvelope<unknown>> {
    return this.client.postJson<ApiEnvelope<unknown>>("/sqlflow/generation/ai/index", {
      sessionId: params.sessionId,
      dbvendor: toApiDbVendor(params.dbvendor),
      ...(params.sqltext ? { sqltext: params.sqltext } : {})
    });
  }

  public async fetchAiSuggestionsWithFallback(params: {
    sessionId: string;
    dbvendor: string;
  }): Promise<AiSuggestedQuestion[]> {
    try {
      const suggestRes = await this.client.postJson<
        ApiEnvelope<AiSuggestedQuestion[]> | { suggestions?: AiSuggestedQuestion[]; questions?: AiSuggestedQuestion[] }
      >("/sqlflow/generation/ai/suggest", {
        sessionId: params.sessionId,
        dbvendor: toApiDbVendor(params.dbvendor)
      });
      if ("data" in suggestRes && Array.isArray(suggestRes.data)) {
        return suggestRes.data;
      }
      if ("suggestions" in suggestRes && Array.isArray(suggestRes.suggestions)) {
        return suggestRes.suggestions;
      }
      if ("questions" in suggestRes && Array.isArray(suggestRes.questions)) {
        return suggestRes.questions;
      }
    } catch {
      // Fallback to /suggestions
    }
    try {
      const fallback = await this.client.getAiSuggestions({
        sessionId: params.sessionId,
        dbvendor: params.dbvendor
      });
      if (Array.isArray(fallback.data)) {
        return fallback.data;
      }
      const rawFallback = fallback as unknown as { suggestions?: AiSuggestedQuestion[] };
      if (Array.isArray(rawFallback.suggestions)) {
        return rawFallback.suggestions;
      }
    } catch {
      // Return empty if disabled
    }
    return [];
  }

  // ===========================================================================
  // Group / Team Management (`OBL-109`, `01 §3.4.9 #1..#12`)
  // ===========================================================================

  public async createGroup(groupName: string, quote = 10): Promise<ApiEnvelope<GroupVo>> {
    return this.client.postForm<ApiEnvelope<GroupVo>>("/group/createGroup", {
      operatorUserId: this.client.getUserId(),
      groupName,
      quote
    });
  }

  public async listGroups(): Promise<ApiEnvelope<GroupVo[] | PaginatedResult<GroupVo>>> {
    return this.client.postForm<ApiEnvelope<GroupVo[] | PaginatedResult<GroupVo>>>(
      "/group/listGroups",
      {
        operatorUserId: this.client.getUserId(),
        pageNo: 1,
        pageSize: 50
      }
    );
  }

  public async getGroupInfo(groupId?: string): Promise<ApiEnvelope<GroupVo>> {
    return this.client.postForm<ApiEnvelope<GroupVo>>("/group/groupInfo", {
      operatorUserId: this.client.getUserId(),
      ...(groupId ? { groupId, userGroup: groupId } : {})
    });
  }

  public async listGroupUsers(groupId: string): Promise<ApiEnvelope<GroupUserVo[]>> {
    return this.client.postForm<ApiEnvelope<GroupUserVo[]>>("/group/listGroupUsers", {
      operatorUserId: this.client.getUserId(),
      groupId,
      userGroup: groupId
    });
  }

  public async addGroupUser(
    groupId: string,
    userEmail: string,
    userRole: "2" | "3" = "3"
  ): Promise<ApiEnvelope<unknown>> {
    return this.client.postForm<ApiEnvelope<unknown>>("/group/addGroupUser", {
      operatorUserId: this.client.getUserId(),
      groupId,
      userGroup: groupId,
      userEmail,
      userRole
    });
  }

  public async removeGroupUser(
    groupId: string,
    userEmail: string
  ): Promise<ApiEnvelope<unknown>> {
    return this.client.postForm<ApiEnvelope<unknown>>("/group/removeGroupUser", {
      operatorUserId: this.client.getUserId(),
      groupId,
      userGroup: groupId,
      userEmail
    });
  }

  // ===========================================================================
  // Pricing (`POST /product/products` -> top-level `res.products`, Invariant #14)
  // ===========================================================================

  public async listProducts(): Promise<ProductItem[]> {
    const res = await this.client.postForm<ProductsResponse>("/product/products", {});
    return Array.isArray(res.products) ? res.products : [];
  }

  // ===========================================================================
  // Admin Console (`OBL-109`, `01 §3.4.19 #23..#25`, Invariant #12 `downloadUserSql`)
  // ===========================================================================

  public async listAdminUsers(
    pageNo = 1,
    pageSize = 20,
    keyword?: string
  ): Promise<UserVo[]> {
    const res = await this.client.postJson<ApiEnvelope<UserVo[] | PaginatedResult<UserVo>>>(
      "/user/users",
      { pageNo, pageSize, pageNum: pageNo, ...(keyword ? { keyword } : {}) }
    );
    if (Array.isArray(res.data)) {
      return res.data;
    }
    return res.data?.result ?? res.data?.records ?? res.data?.list ?? [];
  }

  public async deleteAdminUser(
    targetUser: string | { userId: string; userEmail?: string }
  ): Promise<ApiEnvelope<unknown>> {
    const targetUserId = typeof targetUser === "string" ? targetUser : targetUser.userId;
    const targetEmail =
      typeof targetUser === "string"
        ? targetUser
        : targetUser.userEmail || targetUser.userId;
    return this.client.postForm<ApiEnvelope<unknown>>("/user/deleteUser", {
      operatorUserId: this.client.getUserId(),
      userEmail: targetEmail,
      targetUserId,
      user: targetEmail
    });
  }

  public async listAdminSqls(pageNo = 1, pageSize = 20): Promise<AdminSqlRecord[]> {
    const res = await this.client.postJson<
      ApiEnvelope<AdminSqlRecord[] | PaginatedResult<AdminSqlRecord>>
    >("/user/sqls", { pageNo, pageSize, pageNum: pageNo });
    if (Array.isArray(res.data)) {
      return res.data;
    }
    return res.data?.result ?? res.data?.records ?? res.data?.list ?? [];
  }

  public async previewAdminUserSql(targetUser: string, fileName: string): Promise<string> {
    const res = await this.client.postForm<ApiEnvelope<string>>("/user/previewUserSql", {
      user: targetUser,
      fileName
    });
    return typeof res.data === "string" ? res.data : "";
  }

  /**
   * WIRE INVARIANT #12 (`00 §3.3`):
   * `POST /user/downloadUserSql?userId=...&user=...&fileName=...` sends query params on `POST` returning `Blob`.
   */
  public async downloadAdminUserSql(targetUser: string, fileName: string): Promise<Blob> {
    const url = this.buildFullUrl("/user/downloadUserSql", {
      userId: this.client.getUserId(),
      user: targetUser,
      fileName
    });
    const res = await this.fetchImpl(url, {
      method: "POST",
      headers: this.authHeaders()
    });
    return res.blob();
  }

  public async listAdminRequestLogs(
    pageNo = 1,
    pageSize = 20
  ): Promise<AdminRequestLogRecord[]> {
    const res = await this.client.postJson<
      ApiEnvelope<AdminRequestLogRecord[] | PaginatedResult<AdminRequestLogRecord>>
    >("/user/userRequests", { pageNo, pageSize, pageNum: pageNo });
    if (Array.isArray(res.data)) {
      return res.data;
    }
    return res.data?.result ?? res.data?.records ?? res.data?.list ?? [];
  }

  public async listAdminCoupons(): Promise<CouponVo[]> {
    const res = await this.client.postJson<ApiEnvelope<CouponVo[] | PaginatedResult<CouponVo>>>(
      "/coupon/coupons",
      { pageNo: 1, pageSize: 50, pageNum: 1 }
    );
    if (Array.isArray(res.data)) {
      return res.data;
    }
    return res.data?.result ?? res.data?.records ?? res.data?.list ?? [];
  }

  public async batchCreateCoupons(
    count: number,
    period: number,
    expireDate: string
  ): Promise<ApiEnvelope<unknown>> {
    return this.client.postForm<ApiEnvelope<unknown>>("/coupon/batchCreateCoupons", {
      count,
      period,
      expireDate
    });
  }

  public async deleteCoupon(code: string): Promise<ApiEnvelope<unknown>> {
    return this.client.postForm<ApiEnvelope<unknown>>("/coupon/deleteCoupon", { code });
  }

  public async applyUserCoupon(code: string): Promise<ApiEnvelope<unknown>> {
    return this.client.postForm<ApiEnvelope<unknown>>("/user/applyUserCoupon", {
      couponCode: code,
      code
    });
  }
}
