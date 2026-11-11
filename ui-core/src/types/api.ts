/**
 * Complete HTTP Request & Response Envelopes, Domain DTOs, V1 R<T>,
 * VersionEnvelope, LicenseInfo, JobSummary, DataSource, and AI SSE Event Schemas.
 * Reference: docs/re-spec/00 §3.3, 01 §3.2–§3.4, 02 §4–§5, 04 §9–§12.
 */

import type { DbObjectPosition, Coordinate } from "./coordinates.js";
import type { SqlflowJSON, SummaryInfo, Column } from "./lineage.js";

export const DEFAULT_GUEST_USER_ID = "gudu|0123456789" as const;
export const SPA_DEFAULT_API_PREFIX = "/api/gspLive_backend" as const;
export const WIDGET_DEFAULT_API_PREFIX = "/gspLive_backend" as const;

/**
 * Standard legacy Fastjson response wrapper (00 §3.3 #1, 01 §3.2.2).
 * Note: HTTP status is 200 OK even on business/auth errors (401, 403, 410, 411..417, 426, 500, 509).
 */
export interface ApiEnvelope<T = unknown> {
  code: number | string;
  data?: T;
  sessionId?: string;
  jobId?: string;
  error?: string;
  message?: string;
  "machine.code"?: string | null;
}

/**
 * V1 Public REST API (`/v1/sqlflow/**`) and Anonymous API (`/api/anonymous/**`) R<T> wrapper (01 §3.2.2).
 */
export interface ApiV1Result<T = unknown> {
  code: number;
  success: boolean;
  data: T | null;
  message: string | null;
  error: string | null;
}

export const RESULT_CODES = {
  SUCCESS: { code: 200, message: "Operation successful" },
  FAILURE: { code: 400, message: "Business exception" },
  UN_AUTHORIZED: { code: 401, message: "Request unauthorized" },
  CLIENT_UN_AUTHORIZED: { code: 401, message: "Client request unauthorized" },
  NOT_FOUND: { code: 404, message: "404 Request not found" },
  MSG_NOT_READABLE: { code: 400, message: "Message not readable" },
  METHOD_NOT_SUPPORTED: { code: 405, message: "Current request method not supported" },
  MEDIA_TYPE_NOT_SUPPORTED: { code: 415, message: "Current media type not supported" },
  REQ_REJECT: { code: 403, message: "Request rejected" },
  INTERNAL_SERVER_ERROR: { code: 500, message: "Server exception" },
  PARAM_MISS: { code: 400, message: "Missing required request parameters" },
  PARAM_TYPE_ERROR: { code: 400, message: "Request parameter type error" },
  PARAM_BIND_ERROR: { code: 400, message: "Request parameter binding error" },
  PARAM_VALID_ERROR: { code: 400, message: "Parameter validation failed" }
} as const;

/**
 * WIRE INVARIANT #2 (00 §3.3, 01 §3.3.1, 02 §4.8.1):
 * Unwrapped `/version` response — NO `code` or `data` wrapper!
 */
export interface VersionComponentInfo {
  version: string;
  "release.date": string;
}

export interface VersionSupportFlags {
  "password.security.validate": boolean;
  "login.disable"?: boolean;
  "login.userId"?: string;
  "regular.job": boolean;
  [key: string]: boolean | string | number | undefined;
}

export interface VersionDetails {
  gsp: VersionComponentInfo;
  backend: VersionComponentInfo;
  frontend?: VersionComponentInfo | string;
  ui?: VersionComponentInfo | string;
  support: VersionSupportFlags;
  "machine.code"?: string | null;
  "customer.company.name"?: string;
  "customer.contact.name"?: string;
  "customer.contact.email"?: string;
  "license.expired"?: string;
  "license.max.users"?: string | number;
}

export interface VersionEnvelope {
  version: VersionDetails;
}

/**
 * Clean-room public License status DTO returned inside `data` of `GET|POST /license` (04 §12.4).
 */
export interface LicenseInfo {
  "Supported databases"?: string;
  "Customer company name"?: string;
  "Customer contact name"?: string;
  "Customer contact email"?: string;
  "Product name"?: string;
  "Product version"?: string;
  "Purchase date"?: string;
  "Product license"?: string;
  [key: string]: string | undefined;
}

/**
 * WIRE INVARIANT #3 (00 §3.3):
 * `POST /user/generateSecretKey` and `POST /user/generateToken` return string `"200"` at top-level.
 */
export interface GenerateSecretKeyResponse {
  userId: string;
  code: "200" | string;
  secretKey: string;
  error?: string;
}

export interface GenerateTokenResponse {
  userId: string;
  code: "200" | string;
  token: string;
  error?: string;
}

/**
 * WIRE INVARIANT #14 (00 §3.3):
 * `POST /product/products` returns top-level `products` array instead of `data`.
 */
export interface ProductItem {
  productId: number | string;
  productName: string;
  productPeriod: string;
  recommend: boolean;
  selected: boolean;
  productPrice: string;
  description?: string;
}

export interface ProductsResponse {
  code: number;
  products: ProductItem[];
  error?: string;
}

/**
 * Job & Live JDBC DataSource Contracts (01 §3.3.7, 04 §9, §12.3).
 */
export type JobStatus =
  | "create"
  | "process"
  | "success"
  | "partial_success"
  | "fail"
  | "delete";

export type SqlSourceType =
  | "uploadFile"
  | "fromDatabase"
  | "uploadFileAndFromDatabase"
  | "dbt"
  | "redshiftlog"
  | "snowflakequeryhistory";

export interface DataSource {
  dbvendor: string;
  hostname: string;
  port: number | string;
  database?: string;
  /** Database login username — wire key is `"account"` (04 §9.3) */
  account: string;
  /** RSA-encrypted via JSEncrypt on testConnection/submitUserJob/submitPersistJob ONLY (00 §3.3 #7) */
  password: string;
  extractedDbsSchemas?: string;
  excludedDbsSchemas?: string;
  extractedStoredProcedures?: string | boolean;
  extractedViews?: string | boolean;
  snowflakeDefaultRole?: string;
}

export interface SubJobSummary {
  batchId: string;
  type: "dataflow" | "table_affect_sql" | string;
  status?: JobStatus;
  createTime?: string;
  startTime?: string;
  endTime?: string;
  fileNames?: string[];
  errorMessages?: string[];
}

export interface SnowflakeQueryHistoryExtra {
  enableQueryHistory: boolean;
  duplicateQueryHistory?: boolean;
  excludedHistoryDbsSchemas?: string;
  queryHistoryBlockOfTimeInMinutes?: number;
  queryHistorySqlType?: string;
  snowflakeDefaultRole?: string;
}

export interface JobSummary {
  jobId: string;
  jobName: string;
  dbVendor: string;
  status: JobStatus;
  sqlsource?: SqlSourceType | string;
  regularJob?: boolean;
  persist?: boolean;
  incremental?: boolean;
  onTop?: boolean;
  progress?: string | number;
  version?: string;
  sessionId?: string;
  createTime?: string;
  startTime?: string;
  endTime?: string;
  spendTime?: number | string;
  fileNames?: string[];
  subJobs?: SubJobSummary[];
  errorMessages?: string[];
  errorMessage?: string;
  schedulable?: boolean;
  cancelSchedule?: boolean;
  cronExpression?: string;
  hostname?: string;
  port?: number | string;
  database?: string;
  account?: string;
  extractedDbsSchemas?: string;
  excludedDbsSchemas?: string;
  extractedStoredProcedures?: string | boolean;
  extractedViews?: string | boolean;
  dataSource?: Partial<DataSource> & { password?: string | null };
}

export interface UserJobSummary {
  total: number;
  success: number;
  partialSuccess: number;
  fail: number;
  jobIds: string[];
  jobDetails: JobSummary[];
}

/**
 * WIRE INVARIANT #10 (00 §3.3):
 * `POST /sqlflow/job/displayUserLatestJobGraph` returns top-level `job`, `jobId`, and `sessionId`.
 */
export interface LatestJobGraphResponse {
  code: number;
  data: SqlflowJSON;
  job: JobSummary;
  jobId: string;
  sessionId: string;
  error?: string;
}

/**
 * WIRE INVARIANT #11 (00 §3.3):
 * `POST /sqlflow/generation/displayUserSchemaSummary` returns double-nested file arrays per vendor.
 * Example: `{ "oracle": [["schema1.sql", "schema2.sql"]] }` or `{ "oracle": [["schema1.sql"], ["schema2.sql"]] }`.
 */
export type UserSchemaSummaryMap = Record<string, string[][]>;

export interface ShareSummaryItem {
  shareId: string;
  shareDate: string;
  tableLevel: boolean;
}

export interface ShareCreationData {
  shareId: string;
  tableLevel: boolean;
}

/**
 * User, Group, & Coupon Admin DTOs (02 §4.10, 04 §10.3, §12.4).
 */
export type UserRoleCode = "1" | "2" | "3" | "4";

export interface UserVo {
  userId: string;
  userName?: string;
  userEmail: string;
  userType?: "anonymous" | "standard" | "premium" | "enterprise" | string;
  registerDate?: string;
  latestLoginDate?: string;
  createDate?: string;
  beginDate?: string;
  endDate?: string;
  remainingSqls?: string;
  flag?: string | number | boolean;
  userRole?: UserRoleCode | string;
  userGroup?: string;
  userSource?: string;
  secretKey?: string;
  picture?: string;
}

export interface RegisterOrLoginResponseData {
  userId: string;
  userEmail: string;
  userName?: string;
  token?: string;
  userSource?: string;
  first_login?: boolean;
}

export interface GroupVo {
  userGroup: string;
  groupId?: string;
  groupName: string;
  quote: number | string;
  createdDate?: string;
  expiredDate?: string;
  endDate?: string;
  groupUsers?: GroupUserVo[];
}

export interface GroupUserVo {
  userEmail: string;
  userRole: "2" | "3" | string;
  userName?: string;
  userId?: string;
}

export interface CouponVo {
  code: string;
  name?: string;
  period: number;
  expireDate: string;
  used?: boolean;
  usedDate?: string;
  userId?: string;
  userEmail?: string;
}

export interface PaginatedResult<T> {
  result?: T[];
  records?: T[];
  list?: T[];
  total: number;
  currentPage?: number;
}

/**
 * AI Lineage Assistant Unwrapped Status & SSE Event Contracts (00 §3.3 #16, 02 §4.5, 04 §12.2).
 */
export interface AiStatusResponse {
  enabled: boolean;
  model?: string;
}

export interface AiNodeCitation {
  nodeId?: string;
  table?: string;
  column?: string;
  database?: string;
  schema?: string;
}

export interface AiReferencedEdge {
  from: string;
  to: string;
}

export type AiConfidenceLevel = "high" | "medium" | "low";

export interface AiQueryResponse {
  answer: string;
  referencedNodes?: string[];
  referencedEdges?: AiReferencedEdge[];
  confidence?: AiConfidenceLevel;
  basedOn?: string[];
  error?: string;
  citations?: AiNodeCitation[];
  model?: string;
  nodes?: number;
  edges?: number;
  truncated?: boolean;
}

export interface AiSuggestedQuestion {
  question: string;
  category?: string;
  targetNode?: string;
}

export type AiSseEvent =
  | {
      type: "metadata";
      nodes: number;
      edges: number;
      truncated: boolean;
    }
  | {
      type: "chunk";
      text: string;
    }
  | {
      type: "complete";
      response: AiQueryResponse;
    }
  | {
      type: "no_match";
      message: string;
    }
  | {
      type: "error";
      message: string;
    };

/**
 * Outbound API Request Parameter Contracts (04 §12.2–§12.4).
 */
export interface GenerateGraphParams {
  userId?: string;
  sqltext: string;
  dbvendor: string;
  showRelationType?: string;
  treatArgumentsInCountFunctionAsDirectDataflow?: boolean;
  ignoreRecordSet?: boolean;
  ignoreFunction?: boolean;
  showConstantTable?: boolean;
  showTransform?: boolean;
  jobId?: string;
  columnLevel?: boolean;
  schemaLevel?: boolean;
  share?: boolean;
}

export interface SelectedGraphParams {
  userId?: string;
  sessionId?: string;
  dbvendor: string;
  showRelationType?: string;
  treatArgumentsInCountFunctionAsDirectDataflow?: boolean;
  ignoreRecordSet?: boolean;
  ignoreFunction?: boolean;
  showConstantTable?: boolean;
  database?: string;
  schema?: string;
  table?: string;
  column?: string;
  jobId?: string;
  columnLevel?: boolean;
  schemaLevel?: boolean;
  showTransform?: boolean;
  share?: boolean;
}

export interface StreamSliceParams {
  userId?: string;
  sessionId?: string;
  dbvendor?: string;
  showRelationType?: string;
  treatArgumentsInCountFunctionAsDirectDataflow?: boolean;
  ignoreRecordSet?: boolean;
  ignoreFunction?: boolean;
  tableNamePattern: string;
  tableColumn?: string;
  jobId?: string;
  distance?: number;
  columnLevel?: boolean;
  showTransform?: boolean;
  share?: boolean;
}

export interface SubmitJobParams {
  userId?: string;
  dbvendor: string;
  jobName: string;
  parallel?: boolean;
  sqlsource: SqlSourceType;
  treatArgumentsInCountFunctionAsDirectDataflow?: boolean;
  ignoreFunction?: boolean;
  ignoreRecordSet?: boolean;
  showConstantTable?: boolean;
  showRelationType?: string;
  showTransform?: boolean;
  sqlfiles?: Array<File | Blob>;
  defaultServer?: string;
  defaultDatabase?: string;
  defaultSchema?: string;
  objectCodeEncloseChar?: string;
  objectCodeEscapeChar?: string;
  csvFormat?: string;
  dataSource?: Partial<DataSource>;
  hostname?: string;
  port?: number | string;
  database?: string;
  account?: string;
  password?: string;
  extractedDbsSchemas?: string;
  excludedDbsSchemas?: string;
  extractedStoredProcedures?: string | boolean;
  extractedViews?: string | boolean;
  firstSubmit?: boolean;
  incremental?: boolean;
  extra?: string | SnowflakeQueryHistoryExtra;
  schedulable?: boolean;
  cronExpression?: string;
}

export interface AiQueryStreamParams {
  sessionId: string;
  question: string;
  dbvendor?: string;
  hopDepth?: number;
  userId?: string;
}

export type AiNodeQueryType = "upstream" | "downstream" | "impact" | "explain";

export interface AiNodeQueryParams {
  sessionId: string;
  nodeId: string;
  nodeName: string;
  nodeType?: string;
  queryType?: AiNodeQueryType;
  dbvendor?: string;
  userId?: string;
}

export interface AiSuggestionsParams {
  sessionId: string;
  dbvendor?: string;
  maxQuestions?: number;
  userId?: string;
}

export interface AiSuggestionClickParams {
  question: string;
  category?: string;
  targetNode?: string;
  userId?: string;
}

export type SelectedDbObjectInfoResponse = ApiEnvelope<DbObjectPosition[]>;
export type ListTableColumnsResponse = ApiEnvelope<Column[]>;
export type JobGraphSummaryResponse = ApiEnvelope<SummaryInfo>;
export type CoordinatesParam = Coordinate[] | string;
