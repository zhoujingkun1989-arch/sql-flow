/**
 * Complete 74-field Engine `Option` contract (03 §3.5.1), UI `LineageSettings` (04 §10.1),
 * `config.private.json` runtime config (04 §2.3), and Widget SDK init/event payloads (04 §11.3–§11.5).
 */

import type { EDbVendorId } from "./vendor.js";
import type { ResultSetType } from "./lineage.js";

export type AnalyzeMode = "dataflow" | "crud" | "dynamic";
export type DynamicSqlTrustMode = "LEGACY" | "SHADOW";

export interface CaseSensitiveOverride {
  vendor: string;
  value: boolean;
}

export interface CustomizeConfig {
  file_path_database?: string;
  file_path_schema?: string;
  decrypt_replace_password?: {
    enable?: boolean;
    function?: Array<{ name: string; index: number }>;
  };
  case_sensitive?: {
    defaults?: CaseSensitiveOverride[];
    catalogs?: CaseSensitiveOverride[];
    tables?: CaseSensitiveOverride[];
    functions?: CaseSensitiveOverride[];
    columns?: CaseSensitiveOverride[];
  };
  ai?: {
    enabled?: boolean;
    baseUrl?: string;
    apiKey?: string;
    model?: string;
  };
}

export interface IdentifierProfileConfig {
  vendor?: EDbVendorId;
  supportsCatalogs?: boolean;
  supportsSchemas?: boolean;
  caseSensitive?: boolean;
}

export interface DataFlowHandleListener {
  startAnalyze?: () => void;
  endAnalyze?: () => void;
  isCanceled?: () => boolean;
}

/**
 * Complete 74-field Engine Option configuration matrix (03 §3.5.1).
 */
export interface EngineOption {
  vendor: EDbVendorId | null;
  powerQueryInnerVendor: EDbVendorId | null;
  simpleOutput: boolean;
  ignoreRecordSet: boolean;
  sqlflowIgnoreFunction: boolean;
  simpleShowFunction: boolean;
  simpleShowUdfFunctionOnly: boolean;
  simpleShowSynonym: boolean;
  simpleShowCursor: boolean;
  simpleShowVariable: boolean;
  simpleRetainIntermediate: boolean;
  simpleShowTopSelectResultSet: boolean;
  ignoreTopSelect: boolean;
  ignoreTemporaryTable: boolean;
  ignoreUnusedSynonym: boolean;
  ignoreInsertIntoValues: boolean;
  showConstantTable: boolean;
  showCountTableColumn: boolean;
  showCaseWhenAsDirect: boolean;
  showJoin: boolean;
  showCallRelation: boolean;
  showERDiagram: boolean;
  transform: boolean;
  transformCoordinate: boolean;
  ignoreCoordinate: boolean;
  linkOrphanColumnToFirstTable: boolean;
  showCandidateTable: boolean;
  showImplicitSchema: boolean;
  showComment: boolean;
  collectCommentBindings: boolean;
  commentBindingMaxRawLength: number;
  commentBindingMaxCommentsPerSourceUnit: number;
  commentBindingMaxTotalRawLength: number;
  textFormat: boolean;
  output: boolean;
  normalizeOutput: boolean;
  traceSQL: boolean;
  traceProcedure: boolean;
  traceTablePosition: boolean;
  startId: number;
  parallel: number;
  defaultServer: string | null;
  defaultDatabase: string | null;
  defaultSchema: string | null;
  filePathDatabase: string | null;
  filePathSchema: string | null;
  targetResultSetTypes: ResultSetType[];
  filterRelationTypes: string[];
  simpleShowRelationTypes: string[];
  analyzeMode: AnalyzeMode | null;
  identifierProfile: IdentifierProfileConfig | null;
  enableMssqlColonBindVariables: boolean;
  collectAuthoritativeLineageEvidence: boolean;
  analyzeDynamicSql: boolean;
  dynamicSqlTrustMode: DynamicSqlTrustMode;
  reportDynamicSqlSitesAsErrors: boolean;
  dynamicSqlCaseSensitiveCompare: boolean;
  recoverRoutineBodyErrors: boolean;
  assumeExternalScriptPassthrough: boolean;
  excludedProcedureNames: string[];
  excludedProcedurePatterns: string[];
  enablePipelinedStitching: boolean;
  identityFirstVariablePools: boolean;
  maxPipelinedExpansionDepth: number;
  maxStitchedSourcesPerColumn: number;
  autoDetectLargeFile: boolean;
  largeSqlInfoCountThreshold: number;
  largeSqlTotalSizeThreshold: number;
  largeQueryCountThreshold: number;
  largeShardCountThreshold: number;
  largeFileSplitSizeMB: number;
  largeSqlThresholdMultiplier: number;
  estimatedMemoryPerTaskMB: number;
  handleListener: DataFlowHandleListener | null;
}

/** Alias matching Java Option name in specs */
export type Option = Partial<EngineOption>;

export const DEFAULT_ENGINE_OPTIONS: Readonly<EngineOption> = {
  vendor: null,
  powerQueryInnerVendor: null,
  simpleOutput: false,
  ignoreRecordSet: false,
  sqlflowIgnoreFunction: false,
  simpleShowFunction: false,
  simpleShowUdfFunctionOnly: false,
  simpleShowSynonym: false,
  simpleShowCursor: false,
  simpleShowVariable: false,
  simpleRetainIntermediate: false,
  simpleShowTopSelectResultSet: false,
  ignoreTopSelect: false,
  ignoreTemporaryTable: false,
  ignoreUnusedSynonym: true,
  ignoreInsertIntoValues: true,
  showConstantTable: false,
  showCountTableColumn: true,
  showCaseWhenAsDirect: true,
  showJoin: false,
  showCallRelation: false,
  showERDiagram: false,
  transform: false,
  transformCoordinate: false,
  ignoreCoordinate: false,
  linkOrphanColumnToFirstTable: false,
  showCandidateTable: true,
  showImplicitSchema: false,
  showComment: false,
  collectCommentBindings: false,
  commentBindingMaxRawLength: 8192,
  commentBindingMaxCommentsPerSourceUnit: 50000,
  commentBindingMaxTotalRawLength: 4194304,
  textFormat: false,
  output: true,
  normalizeOutput: false,
  traceSQL: false,
  traceProcedure: false,
  traceTablePosition: false,
  startId: 0,
  parallel: 1,
  defaultServer: null,
  defaultDatabase: null,
  defaultSchema: null,
  filePathDatabase: null,
  filePathSchema: null,
  targetResultSetTypes: [],
  filterRelationTypes: [],
  simpleShowRelationTypes: [],
  analyzeMode: null,
  identifierProfile: null,
  enableMssqlColonBindVariables: false,
  collectAuthoritativeLineageEvidence: false,
  analyzeDynamicSql: true,
  dynamicSqlTrustMode: "LEGACY",
  reportDynamicSqlSitesAsErrors: false,
  dynamicSqlCaseSensitiveCompare: false,
  recoverRoutineBodyErrors: false,
  assumeExternalScriptPassthrough: true,
  excludedProcedureNames: [],
  excludedProcedurePatterns: [],
  enablePipelinedStitching: true,
  identityFirstVariablePools: false,
  maxPipelinedExpansionDepth: 8,
  maxStitchedSourcesPerColumn: 64,
  autoDetectLargeFile: false,
  largeSqlInfoCountThreshold: 1000,
  largeSqlTotalSizeThreshold: 26214400,
  largeQueryCountThreshold: 1000,
  largeShardCountThreshold: 10,
  largeFileSplitSizeMB: 5,
  largeSqlThresholdMultiplier: 1.5,
  estimatedMemoryPerTaskMB: 2560,
  handleListener: null
};

/**
 * UI & Widget Lineage Settings Matrix (04 §10.1).
 */
export interface LineageSettings {
  /** Direct dataflow ("fdd") — default true */
  dataflow: boolean;
  /** Indirect dataflow ("fdr") — default false */
  impact: boolean;
  /** Treat arguments in COUNT/aggregate functions as direct dataflow — default false */
  dataflowOfAggregateFunction: boolean;
  /** Hide intermediate recordsets (!showIntermediateRecordset) — default false on reset, true in Job mode */
  ignoreRecordSet: boolean;
  /** Hide function nodes (!showFunction) — default true */
  ignoreFunction: boolean;
  /** Materialize SQL_CONSTANTS table — default false */
  showConstant: boolean;
  /** Show transform expression f(x) circle badges & extract transforms — default false */
  showTransform: boolean;
  /** Default upstream/downstream hop distance (1..10) — default 3 (10 in widget) */
  defaultDistance: number;
  /** Include all FROM-clause tables in table-level lineage — default false */
  allTablesInFromClauseIncludedInTableLevelLineage: boolean;
  /** ER diagram table movable toggle — default true */
  tableMovable: boolean;
  /** Call graph node movable toggle — default true */
  nodeMovable: boolean;
}

export const DEFAULT_LINEAGE_SETTINGS: Readonly<LineageSettings> = {
  dataflow: true,
  impact: false,
  dataflowOfAggregateFunction: false,
  ignoreRecordSet: false,
  ignoreFunction: true,
  showConstant: false,
  showTransform: false,
  defaultDistance: 3,
  allTablesInFromClauseIncludedInTableLevelLineage: false,
  tableMovable: true,
  nodeMovable: true
};

/**
 * Computes outbound `showRelationType` wire parameter from UI settings (04 §10.1).
 */
export function toShowRelationType(
  settings: Pick<LineageSettings, "dataflow" | "impact">,
  isJoin = false
): string {
  if (isJoin) {
    return "join";
  }
  if (settings.dataflow && settings.impact) {
    return "fdd,fdr";
  }
  if (settings.impact) {
    return "fdr";
  }
  return "fdd";
}

/**
 * Shipped `config.private.json` runtime configuration (04 §2.3).
 */
export interface RuntimePrivateConfig {
  Product: "private" | string;
  ApiPrefix: string;
  Authorization: {
    base64token: string;
  };
  userId: string;
  trialVersion: boolean;
  homepage?: "editor" | "job";
}

/**
 * Widget SDK `SQLFlow.init(options)` contract (04 §11.3).
 */
export interface SQLFlowWidgetComponentOptions {
  sqlEditor?: boolean;
  graphLocate?: boolean;
  minimap?: boolean;
}

export interface SQLFlowWidgetInitOptions {
  container: HTMLElement;
  width: number | string;
  height: number | string;
  apiPrefix?: string;
  token?: string;
  userId?: string;
  visualizeShare?: boolean;
  dbvendor?: string;
  sqltext?: string;
  jobId?: string;
  theme?: "light" | "dark" | string;
  lang?: "en" | "cn";
  component?: boolean | SQLFlowWidgetComponentOptions;
}

/**
 * Widget SDK `onFieldClick` event payload (04 §11.5).
 * Note: "DEFAULT" database/schema values MUST be omitted before firing.
 */
export interface WidgetColumnRef {
  database?: string;
  schema?: string;
  table: string;
  tableType: string;
  column: string;
}

export interface WidgetTableRef {
  database?: string;
  schema?: string;
  table: string;
  tableType: string;
}

export interface OnFieldClickPayload {
  database?: string;
  schema?: string;
  table: string;
  tableType: string;
  column: string;
  source: {
    columns: WidgetColumnRef[];
    tables: WidgetTableRef[];
  };
  target: {
    columns: WidgetColumnRef[];
    tables: WidgetTableRef[];
  };
}

/**
 * Widget SDK `onTableNameClick` event payload (04 §11.5).
 */
export interface OnTableNameClickPayload {
  database?: string;
  schema?: string;
  table: string;
}
