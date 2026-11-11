/**
 * Core Lineage Wire Schemas: DataFlow, DbObject, Column ("unqiueKey"), Relationship,
 * SqlflowJSON, LeftMostSourceTable, CommentBindingBundle, and CallGraphPayload.
 * Reference: docs/re-spec/03-lineage-engine-and-graph-model.md §1.3–§1.5, §3.1–§3.3.
 */

import type { Coordinate, Point2D } from "./coordinates.js";

export const RELATIONSHIP_TYPES = [
  "fdd",
  "fddi",
  "frd",
  "fdr",
  "join",
  "call",
  "er",
  "crud"
] as const;

export type RelationshipType = (typeof RELATIONSHIP_TYPES)[number];

export const EFFECT_TYPES = [
  "select",
  "insert",
  "replace",
  "update",
  "join",
  "merge_update",
  "merge_insert",
  "merge_when",
  "create_view",
  "create_table",
  "merge",
  "delete",
  "function",
  "rename_table",
  "swap_table",
  "append_from",
  "like_table",
  "cursor",
  "output",
  "trigger",
  "clone_database",
  "clone_schema",
  "clone_table",
  "rowtype",
  "unload",
  "copy",
  "foreign_key",
  "create_synonym",
  "exchange_partition",
  "synonym",
  "drop_table",
  "drop_table_column",
  "add_table_column",
  "truncate_table",
  "external_script_passthrough",
  "switch_partition"
] as const;

export type EffectType = (typeof EFFECT_TYPES)[number];

export const RESULT_SET_TYPES = [
  "select_list",
  "array",
  "struct",
  "result_of",
  "with_cte",
  "insert-select",
  "update-select",
  "merge-update",
  "merge-insert",
  "output",
  "update-set",
  "pivot-table",
  "unpivot-table",
  "alias",
  "function",
  "case-when",
  "cursor",
  "variable"
] as const;

export type ResultSetType = (typeof RESULT_SET_TYPES)[number];

export const SUB_TYPES = [
  "scalar",
  "cursor",
  "record",
  "record_type",
  "function",
  "unnest",
  "values_table",
  "alias_table",
  "dblink",
  "synonym",
  "in",
  "out",
  "argument",
  "temp_table"
] as const;

export type SubType = (typeof SUB_TYPES)[number];

export const ENDPOINT_KINDS = [
  "CATALOG_OBJECT",
  "LOCAL_TEMP_TABLE",
  "GLOBAL_TEMP_TABLE",
  "TABLE_VARIABLE",
  "TEMPDB_OBJECT",
  "CTE",
  "DERIVED_RESULTSET",
  "TVF_RESULT",
  "CONSTANT",
  "VARIABLE",
  "DYNAMIC_TEMPLATE",
  "UNKNOWN"
] as const;

export type EndpointKind = (typeof ENDPOINT_KINDS)[number];

export const ENDPOINT_INTRODUCTIONS = [
  "CATALOG_REFERENCE",
  "CREATE_TABLE_DDL",
  "SELECT_INTO",
  "CTAS",
  "CLONE_TABLE",
  "CTE_DEFINITION",
  "DERIVED_SUBQUERY",
  "FUNCTION_CALL",
  "LITERAL",
  "VARIABLE_REFERENCE",
  "UNKNOWN"
] as const;

export type EndpointIntroduction = (typeof ENDPOINT_INTRODUCTIONS)[number];

export type GraphOrientation =
  | "LEFT_TO_RIGHT"
  | "RIGHT_TO_LEFT"
  | "TOP_TO_BOTTOM"
  | "BOTTOM_TO_TOP";

/**
 * Column DTO across DataFlow, SqlflowJSON, ER diagrams, and <dlineage>.
 *
 * WIRE INVARIANT #4 (00 §3.3, 03 §1.3.3, 04 §6.3, §7.2):
 * The unique-key boolean property is intentionally spelled `"unqiueKey"` (transposed i/u)
 * on the wire. Clean-room consumers MUST read/write `unqiueKey`.
 */
export interface Column {
  id: string;
  name: string;
  displayName?: string;
  dataType?: string;
  comment?: string;
  coordinates?: Coordinate[];
  source?: string;
  uiVisible?: boolean;
  primaryKey?: boolean;
  /** Intentional wire-compatible spelling ("unqiueKey") per 00 §3.3 Invariant #4 */
  unqiueKey?: boolean;
  indexKey?: boolean;
  foreignKey?: boolean;
  nullable?: boolean;
  identity?: boolean;
  computed?: boolean;
  persisted?: boolean;
  computedDefinition?: string;
  defaultValue?: string;
  defaultName?: string;
  star?: boolean;
  isFunction?: boolean;
  qualifiedTable?: string;
}

export interface Transform {
  type: "simple" | "function" | "case" | "subquery" | "expression" | "constant" | "alias" | string;
  code: string;
  coordinates?: Coordinate[];
}

export interface CandidateTable {
  id: string;
  name: string;
}

export interface ArgumentObject {
  id: string;
  name: string;
  dataType?: string;
  datatype?: string;
  inOut?: "IN" | "OUT" | "INOUT" | "RETURN" | string;
  inout?: "IN" | "OUT" | "INOUT" | "RETURN" | string;
  coordinates?: Coordinate[];
}

export interface ProcedureObject {
  id: string;
  name: string;
  type: "procedure" | "function" | "trigger" | "package" | "macro" | string;
  arguments?: ArgumentObject[];
  boundTables?: string;
  coordinates?: Coordinate[];
}

export interface TableObject {
  id: string;
  server?: string;
  database?: string;
  schema?: string;
  name: string;
  displayName?: string;
  alias?: string;
  uri?: string;
  type: string;
  subType?: string;
  dbLink?: string;
  columns?: Column[];
  coordinates?: Coordinate[];
  more?: boolean;
  parent?: string;
  isTarget?: boolean | string;
  fromDDL?: boolean;
  createdInSql?: boolean;
}

export interface PackageObject {
  id: string;
  name: string;
  type?: string;
  procedures?: ProcedureObject[];
  functions?: ProcedureObject[];
  triggers?: ProcedureObject[];
  variables?: TableObject[];
  coordinates?: Coordinate[];
}

export interface SequenceObject {
  id?: string;
  name: string;
  incrementBy?: string | number;
  startWith?: string | number;
  minValue?: string | number;
  maxValue?: string | number;
  cycle?: boolean;
  order?: boolean;
  cache?: string | number;
  coordinates?: Coordinate[];
}

export interface SynonymObject {
  id?: string;
  name: string;
  sourceId?: string;
  sourceName?: string;
  sourceSchema?: string;
  sourceDatabase?: string;
  sourceServer?: string;
  sourceDbLink?: string;
  sourceDbLinkName?: string;
  coordinates?: Coordinate[];
}

export interface DBLinkObject {
  owner?: string;
  name: string;
  userName?: string;
  host?: string;
}

export interface QueryObject {
  database?: string;
  schema?: string;
  name: string;
  type?: string;
  sourceCode: string;
  groupName?: string;
}

export interface ProcessObject {
  id: string;
  name: string;
  procedureId?: string;
  procedureName?: string;
  queryHashId?: string;
  type?: string;
  coordinates?: Coordinate[];
  transforms?: Transform[];
  uiVisible?: boolean;
}

/**
 * Flattened DbObject representation (02 §5.1, 03 §1.3.2).
 */
export interface DbObject {
  id: string;
  server?: string;
  database?: string;
  schema?: string;
  name: string;
  displayName?: string;
  alias?: string;
  uri?: string;
  type: string;
  subType?: string;
  fileType?: string;
  dbLink?: string;
  arguments?: ArgumentObject[];
  procedures?: DbObject[];
  columns?: Column[];
  coordinates?: Coordinate[];
  procedureId?: string;
  queryHashId?: string;
  more?: boolean;
  parent?: string;
}

export interface SchemaCatalog {
  name: string;
  tables?: TableObject[];
  views?: TableObject[];
  packages?: PackageObject[];
  procedures?: ProcedureObject[];
  functions?: ProcedureObject[];
  triggers?: ProcedureObject[];
  synonyms?: SynonymObject[];
  sequences?: SequenceObject[];
  /** ResultSets, CTEs, variables, stages, paths, streams, datasources (03 §1.5.2) */
  others?: TableObject[];
  processes?: ProcessObject[];
}

export interface DatabaseCatalog extends SchemaCatalog {
  schemas?: SchemaCatalog[];
}

export interface ServerCatalog {
  name: string;
  dbVendor?: string;
  supportsCatalogs?: boolean;
  supportsSchemas?: boolean;
  databases?: DatabaseCatalog[];
  schemas?: SchemaCatalog[];
  dbLinks?: DBLinkObject[];
  queries?: QueryObject[];
}

export interface LineageError {
  errorMessage: string;
  errorType:
    | "SyntaxError"
    | "SyntaxHint"
    | "AnalyzeError"
    | "MetadataError"
    | "LinkOrphanColumn"
    | "DynamicSqlUnresolved"
    | string;
  coordinates?: Coordinate[];
  sqlFile?: string;
}

export interface SqlflowCatalog {
  createdBy?: string;
  createTime?: string | number;
  physicalInstance?: string;
  servers?: ServerCatalog[];
  errorMessages?: LineageError[];
}

export interface RelationshipElement {
  id: string;
  name?: string;
  column?: string;
  columnType?: string;
  sourceId?: string;
  sourceName?: string;
  parentId?: string;
  parentName?: string;
  parentAlias?: string;
  coordinates?: Coordinate[];
  transforms?: Transform[];
  clauseType?: string;
  candidateParents?: CandidateTable[];
  value?: string;
  source?: string;
  type?: string;
  function?: string;
}

export interface Relationship {
  id: string;
  type: RelationshipType;
  effectType?: EffectType | string;
  target?: RelationshipElement;
  sources?: RelationshipElement[];
  caller?: RelationshipElement;
  callees?: RelationshipElement[];
  processId?: string;
  processType?: string;
  procedureId?: string;
  function?: string;
  partition?: string;
  timestampMin?: string;
  timestampMax?: string;
  distance?: number;
  callStmt?: string;
  callCoordinate?: Coordinate[] | string;
  sqlHash?: string;
  sqlComment?: string;
  builtIn?: boolean;
  /** Present on JoinRelationship (type === "join") */
  joinType?: string;
  clause?: "on" | "where" | "having" | string;
  condition?: string;
}

export interface JoinRelationship extends Relationship {
  type: "join";
  joinType: string;
  clause?: "on" | "where" | "having" | string;
  condition?: string;
}

export interface MostRelationTable {
  database?: string;
  schema?: string;
  table: string;
  relationCount: number;
}

export interface SummaryInfo {
  process: number;
  database: number;
  schema: number;
  table: number;
  view: number;
  column: number;
  relationship: number;
  mostRelationTables?: MostRelationTable[];
  procedure?: number;
  function?: number;
  trigger?: number;
  synonym?: number;
}

export interface CommentBindingRange {
  endOffset?: number;
  startLine: number;
  startColumn: number;
  endLine: number;
  endColumn: number;
}

export interface CommentBindingBundle {
  version: number;
  bindingRulesVersion: string;
  state: string;
  supportedHostKinds: string[];
  sourceUnits: Array<{
    inputOrdinal: number;
    vendor: string;
    contentDigest: string;
    originKind: string;
    fileName?: string;
  }>;
  comments: Array<{
    sourceUnitId: string;
    range: CommentBindingRange;
    lexicalKind: string;
    raw: string;
    rawEncoding?: string;
    originalLength: number;
    rawTruncated: boolean;
  }>;
  hosts: Array<{
    sourceUnitId: string;
    kind: string;
    syntaxKind: string;
    range: CommentBindingRange;
    nameRange?: CommentBindingRange;
    queryBlockId?: string;
    parentHostId?: string;
    ordinal?: number;
    bindingState: string;
    targets?: Array<{ id: string; parentId?: string }>;
    processId?: string;
    procedureId?: string;
  }>;
  bindings: Array<{ hostId: string; placement: string; ruleId: string }>;
  coverage: Array<{ hostKind: string; state: string }>;
  diagnostics: Array<{ sourceUnitId: string; commentId?: string; hostId?: string }>;
  limits: { maxCommentsPerSourceUnit: number; maxTotalRawLength: number };
}

/**
 * Layer 2 / Layer 3 DataFlow model (03 §1.4–§1.5.2).
 */
export interface DataFlow {
  dbobjs?: SqlflowCatalog | DbObject[];
  relationships?: Relationship[];
  errors?: LineageError[];
  orientation?: GraphOrientation;
  processes?: ProcessObject[];
  summary?: SummaryInfo;
  commentBindings?: CommentBindingBundle;
}

export interface GraphLabel {
  x: number;
  y: number;
  width: number;
  height: number;
  content: string;
  fontFamily?: string;
  fontSize?: number | string;
}

export interface GraphColumnNode {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  label?: GraphLabel;
  modelId?: string;
  name?: string;
  dataType?: string;
  primaryKey?: boolean;
  unqiueKey?: boolean;
  indexKey?: boolean;
  foreignKey?: boolean;
  coordinates?: Coordinate[];
}

export interface GraphTableNode {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  modelId?: string;
  type?: string;
  subType?: string;
  database?: string;
  schema?: string;
  name?: string;
  displayName?: string;
  more?: boolean;
  parentId?: string;
  label?: GraphLabel;
  columns?: GraphColumnNode[];
  coordinates?: Coordinate[];
}

export interface GraphPackageNode {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  name?: string;
  type?: string;
  label?: GraphLabel;
  children?: string[];
}

export interface GraphEdge {
  id: string;
  sourceId: string;
  targetId: string;
  type?: RelationshipType;
  effectType?: string;
  transforms?: Transform[];
  joinType?: string;
  condition?: string;
  points?: Point2D[];
  coordinates?: Coordinate[];
}

export interface SqlflowGraph {
  elements: {
    tables: GraphTableNode[];
    edges: GraphEdge[];
    packages?: GraphPackageNode[];
  };
  width?: number;
  height?: number;
  orientation?: GraphOrientation;
  tooltip?: Record<string, string>;
  listIdMap?: Record<string, string[]>;
  relationshipIdMap?: Record<string, string[]>;
}

/**
 * Complete laid-out lineage graph payload returned inside `data` by
 * `/sqlflow/generation/sqlflow/graph` and sibling endpoints (03 §1.5.3, 04 §12.2).
 */
export interface SqlflowJSON {
  mode?: "global" | "ignoreRecordSet" | "summary";
  sqlflow?: DataFlow;
  summary?: SummaryInfo;
  dbObjIds?: string[];
  graph?: SqlflowGraph;
  graphML?: string;
  svg?: string;
  shareId?: string;
  createTime?: string | number;
  spendTime?: number;
}

export interface LeftMostSourceColumn {
  database?: string;
  schema?: string;
  tableName: string;
  tableAlias?: string;
  name: string;
  type?: string;
  clause?: string;
  relationType?: RelationshipType | string;
}

export interface LeftMostTargetColumn {
  name: string;
  dataType?: string;
  sourceColumns: LeftMostSourceColumn[];
}

export interface LeftMostTargetTable {
  database?: string;
  schema?: string;
  name: string;
  alias?: string;
  type?: string;
  columns: LeftMostTargetColumn[];
}

export interface LeftMostSourceTable {
  elements: LeftMostTargetTable[];
}

export interface CallGraphTableAccess {
  table: string;
  access: "READ" | "WRITE" | "READ_WRITE";
}

export interface CallGraphNode {
  id: string;
  name: string;
  kind:
    | "PROCEDURE"
    | "FUNCTION"
    | "TRIGGER"
    | "ANONYMOUS_BLOCK"
    | "NESTED_PROCEDURE"
    | "NESTED_FUNCTION"
    | "TYPE_METHOD"
    | string;
  package?: string;
  isExternal?: boolean;
  tableAccesses?: CallGraphTableAccess[];
  coordinates?: Coordinate[];
}

export interface CallGraphEdge {
  id?: string;
  source: string;
  target: string;
  callStmt?: string;
  coordinates?: Coordinate[];
}

export interface CallGraphPayload {
  callGraph: {
    nodes: CallGraphNode[];
    edges: CallGraphEdge[];
  };
}
