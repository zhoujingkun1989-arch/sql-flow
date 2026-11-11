/**
 * Join Analysis V1 Wire Contract ("join-analysis.v1" + 10 DiagnosticCodes)
 * Reference:
 * - docs/re-spec/02-sqlservice-api-and-domain.md §4.2.1 & §5.3 (lines 930–987)
 * - docs/re-spec/03-lineage-engine-and-graph-model.md §1.5.4
 * - docs/re-spec/04-frontend-ui-and-widget.md §7.4
 */

export const JOIN_ANALYSIS_SCHEMA_VERSION = "join-analysis.v1" as const;

export const DIAGNOSTIC_CODES = [
  "missing_join_condition",
  "cartesian_join",
  "many_to_many_risk",
  "outer_join_filter_risk",
  "anti_join_detected",
  "self_join_detected",
  "semi_join_detected",
  "non_indexed_join_key",
  "complex_predicate",
  "unparsed_condition"
] as const;

export type DiagnosticCode = (typeof DIAGNOSTIC_CODES)[number];

export const UI_DIAGNOSTIC_IDS = DIAGNOSTIC_CODES;
export type UiDiagnosticId = DiagnosticCode;

export type JoinDiagnosticSeverity = "info" | "warn" | "block";

export interface JoinDiagnosticDescriptor {
  readonly code: DiagnosticCode;
  readonly uiId: UiDiagnosticId;
  readonly label: string;
  readonly defaultSeverity: JoinDiagnosticSeverity;
}

export const JOIN_DIAGNOSTIC_DESCRIPTORS: readonly JoinDiagnosticDescriptor[] = [
  {
    code: "missing_join_condition",
    uiId: "missing_join_condition",
    label: "No condition",
    defaultSeverity: "block"
  },
  {
    code: "cartesian_join",
    uiId: "cartesian_join",
    label: "Cartesian",
    defaultSeverity: "block"
  },
  {
    code: "many_to_many_risk",
    uiId: "many_to_many_risk",
    label: "Many-to-many",
    defaultSeverity: "warn"
  },
  {
    code: "outer_join_filter_risk",
    uiId: "outer_join_filter_risk",
    label: "Outer-join filter",
    defaultSeverity: "warn"
  },
  {
    code: "anti_join_detected",
    uiId: "anti_join_detected",
    label: "Anti-join",
    defaultSeverity: "info"
  },
  {
    code: "self_join_detected",
    uiId: "self_join_detected",
    label: "Self-join",
    defaultSeverity: "info"
  },
  {
    code: "semi_join_detected",
    uiId: "semi_join_detected",
    label: "Semi-join",
    defaultSeverity: "info"
  },
  {
    code: "non_indexed_join_key",
    uiId: "non_indexed_join_key",
    label: "Non-indexed key",
    defaultSeverity: "warn"
  },
  {
    code: "complex_predicate",
    uiId: "complex_predicate",
    label: "Complex predicate",
    defaultSeverity: "warn"
  },
  {
    code: "unparsed_condition",
    uiId: "unparsed_condition",
    label: "Unparsed",
    defaultSeverity: "warn"
  }
] as const;

// Exact Wire Enum Values (02 §5.3 lines 959–975)
export type JoinLayoutEngine = "elk";
export type JoinLayoutDirection = "RIGHT" | "DOWN";
export type QueryBlockLaneKind = "main" | "cte" | "subquery";
export type JoinNodeType =
  | "source"
  | "table"
  | "join"
  | "filter"
  | "cte"
  | "subquery"
  | "output";
export type JoinEdgeKind =
  | "data_flow"
  | "lineage"
  | "correlation"
  | "anti_join_evidence"
  | "table_to_join"
  | "join_to_join"
  | "join_to_output";
export type JoinOperatorType =
  | "INNER"
  | "LEFT"
  | "RIGHT"
  | "FULL"
  | "CROSS"
  | "UNKNOWN";
export type JoinClauseKind = "ON" | "USING" | "NATURAL" | "NONE" | "UNKNOWN";
export type JoinPattern = "self" | "anti" | "semi" | "cartesian";
export type PredicateKind =
  | "equi"
  | "non_equi"
  | "range"
  | "null_check"
  | "constant"
  | "complex"
  | "unknown";
export type CardinalityHint =
  | "one_to_one"
  | "one_to_many"
  | "many_to_one"
  | "many_to_many"
  | "unknown";
export type JoinBadgeKind =
  | "anti_join"
  | "semi_join"
  | "self_join"
  | "cross_join"
  | "cartesian_risk"
  | "fan_out";
export type FanOutLevel = "low" | "medium" | "high";
export type SqlFragmentKind =
  | "query_block"
  | "table_ref"
  | "join_clause"
  | "join_condition"
  | "join_predicate"
  | "where_filter"
  | "output_column";
export type SourceRangeQuality =
  | "exact"
  | "approximate"
  | "coordinate_fallback"
  | "missing";
export type JoinDiagnosticRole = "data_engineer" | "data_analyst" | "dba" | "all";
export type JoinFallbackSource = "legacy_graph" | "backend_degraded";

export interface SourceRange {
  startLine: number;
  startColumn: number;
  endLine: number;
  endColumn: number;
  quality?: SourceRangeQuality;
}

export interface JoinAnalysisLayout {
  engine: JoinLayoutEngine;
  direction: JoinLayoutDirection;
  rankSpacing?: number;
  nodeSpacing?: number;
}

export interface QueryBlockLane {
  queryBlockId: string;
  label: string;
  kind: QueryBlockLaneKind;
  parentQueryBlockId?: string | null;
  sourceRange?: SourceRange;
  collapsedByDefault?: boolean;
}

export interface JoinColumnContribution {
  table: string;
  column: string;
}

export interface JoinColumnRef {
  tableId?: string;
  tableName?: string;
  alias?: string;
  column: string;
  displayName?: string;
  sourceRange?: SourceRange;
  isPrimaryKey?: boolean;
  isUniqueKey?: boolean;
  isIndexed?: boolean;
}

export interface JoinPredicate {
  predicateId: string;
  raw: string;
  kind: PredicateKind;
  op?: string;
  left?: JoinColumnRef;
  right?: JoinColumnRef;
  sourceRange?: SourceRange;
}

export interface JoinBadge {
  kind: JoinBadgeKind;
  level?: FanOutLevel;
  heuristic?: boolean;
}

export interface JoinDetail {
  joinType: JoinOperatorType;
  patterns?: JoinPattern[];
  clause: JoinClauseKind;
  predicates: JoinPredicate[];
  order?: number;
  cardinalityHint?: CardinalityHint;
  sourceRange?: SourceRange;
}

export interface JoinTableData {
  tableId?: string;
  database?: string;
  schema?: string;
  name: string;
  alias?: string;
  columnsContributed?: JoinColumnContribution[];
  isSelfJoinInstance?: boolean;
}

export interface JoinNodeData {
  label: string;
  summary?: string;
  queryBlockId?: string;
  fragmentId?: string;
  join?: JoinDetail;
  table?: JoinTableData;
  badges?: JoinBadge[];
  details?: unknown;
}

export interface JoinAnalysisNode {
  id: string;
  type: JoinNodeType;
  position?: { x: number; y: number };
  data: JoinNodeData;
}

export interface JoinEdgeData {
  kind: JoinEdgeKind;
  columns?: JoinColumnContribution[];
  note?: string;
  dashed?: boolean;
  animated?: boolean;
}

export interface JoinAnalysisEdge {
  id: string;
  source: string;
  target: string;
  data?: JoinEdgeData;
}

export interface SqlFragment {
  fragmentId: string;
  nodeIds: string[];
  kind: SqlFragmentKind;
  text: string;
  sourceRange?: SourceRange;
}

export interface JoinDiagnostic {
  id: string;
  severity: JoinDiagnosticSeverity;
  roles: JoinDiagnosticRole[];
  code: DiagnosticCode;
  message: string;
  evidenceNodeIds?: string[];
  fragmentIds?: string[];
  predicateIds?: string[];
  edgeIds?: string[];
}

export interface JoinAnalysisFallback {
  source: JoinFallbackSource;
  limitations: string[];
}

export interface JoinAnalysisErrorItem {
  code?: string;
  message?: string;
  errorType?: string;
  errorMessage?: string;
  sourceRange?: SourceRange;
}

export interface JoinAnalysisSummary {
  tableCount?: number;
  joinCount?: number;
  diagnosticCount?: number;
  queryBlockCount?: number;
}

export interface AnalyzeJoinsParams {
  userId?: string;
  sqltext: string;
  dbvendor: string;
  jobId?: string;
  defaultServer?: string;
  defaultDatabase?: string;
  defaultSchema?: string;
}

/**
 * Unwrapped response from POST /sqlflow/generation/sqlflow/join-analysis (00 §3.3 #15, 02 §4.2.1 & §5.3).
 */
export interface JoinAnalysisGraph {
  schemaVersion: typeof JOIN_ANALYSIS_SCHEMA_VERSION;
  graphId?: string;
  sqlText?: string;
  dbvendor?: string;
  summary?: JoinAnalysisSummary;
  layout?: JoinAnalysisLayout;
  queryBlocks: QueryBlockLane[];
  nodes: JoinAnalysisNode[];
  edges: JoinAnalysisEdge[];
  fragments?: SqlFragment[];
  diagnostics: JoinDiagnostic[];
  errors?: JoinAnalysisErrorItem[];
  fallback?: JoinAnalysisFallback | null;
}
