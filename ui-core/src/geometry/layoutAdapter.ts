/**
 * Client-Side `@dagrejs/dagre` (`LR` / `TB`) Layout Adapter,
 * Pre-Laid-Out `SqlflowJSON` Normalizer, Compound Package/Schema Containers,
 * Recursive Upstream/Downstream Highlight Tracer, 1-Based Token Coordinate Index,
 * Widget Event Payload Builder, and `DependencyGraph` v1.0 Validator/Converter.
 *
 * Reference:
 * - docs/re-spec/04-frontend-ui-and-widget.md §5.4, §6.1–§6.4, §6.9, §11.5–§11.6
 * - docs/re-spec/03-lineage-engine-and-graph-model.md §1.4–§1.5, §4.4
 */

import * as dagreNs from "@dagrejs/dagre";
import type { ApiEnvelope } from "../types/api.js";
import { type Coordinate, normalizeCoordinateSpans } from "../types/coordinates.js";
import {
  DEPENDENCY_GRAPH_KIND,
  DEPENDENCY_GRAPH_VERSION,
  type DependencyGraphV1
} from "../types/dependencyGraph.js";
import type {
  Column,
  DataFlow,
  DatabaseCatalog,
  DbObject,
  LineageError,
  Relationship,
  RelationshipType,
  SchemaCatalog,
  SqlflowCatalog,
  SqlflowJSON,
  SummaryInfo,
  Transform
} from "../types/lineage.js";
import type { OnFieldClickPayload, OnTableNameClickPayload } from "../types/options.js";
import {
  CANVAS_GEOMETRY_TOKENS,
  CONTAINER_DBO_TYPES,
  CONTAINER_STYLE_TOKENS
} from "../theme/tokens.js";
import {
  type CubicBezierEdgeGeometry,
  buildArrowheadPoints,
  buildCubicBezierEdgePath,
  computeSourcePortAnchor,
  computeTargetPortAnchor
} from "./bezier.js";
import {
  computeNodeDimensions,
  formatDbObjectDisplayTitle,
  stripDefaultQualifier
} from "./textMeasure.js";

const dagre =
  (dagreNs as unknown as { default?: typeof dagreNs }).default ?? dagreNs;

export type LayoutRankDir = "LR" | "TB";

export interface CanvasColumnRow {
  id: string;
  nodeId: string;
  rowIndex: number;
  name: string;
  displayLabel: string;
  tooltip?: string;
  dataType?: string;
  primaryKey: boolean;
  /** Preserves wire spelling `"unqiueKey"` (00 §3.3 Invariant #4) */
  unqiueKey: boolean;
  indexKey: boolean;
  foreignKey: boolean;
  coordinates: Coordinate[];
}

export interface CanvasNode {
  id: string;
  name: string;
  displayTitle: string;
  tooltipTitle: string;
  type: string;
  subType?: string;
  database?: string;
  schema?: string;
  parentId?: string;
  x: number;
  y: number;
  width: number;
  height: number;
  headerHeight: number;
  rowHeight: number;
  columns: CanvasColumnRow[];
  coordinates: Coordinate[];
  moreUp: boolean;
  moreDown: boolean;
}

export interface CanvasContainerBox {
  id: string;
  name: string;
  type: string;
  x: number;
  y: number;
  width: number;
  height: number;
  childNodeIds: string[];
}

export interface CanvasEdge {
  id: string;
  sourceNodeId: string;
  sourceColumnId?: string;
  sourceColumnIndex?: number;
  targetNodeId: string;
  targetColumnId?: string;
  targetColumnIndex?: number;
  type: RelationshipType | string;
  effectType?: string;
  joinType?: string;
  condition?: string;
  transforms: Transform[];
  coordinates: Coordinate[];
  bezier: CubicBezierEdgeGeometry;
  arrowPoints: string;
}

export interface TokenCoordinateHit {
  kind: "node" | "column";
  nodeId: string;
  columnId?: string;
}

/**
 * Sparse 1-based `[line][col]` lookup map for Editor -> Canvas hover synchronization (`04 §5.4`).
 */
export type TokenCoordinateIndex = Map<number, Map<number, TokenCoordinateHit>>;

export interface CanvasGraphModel {
  mode: "global" | "ignoreRecordSet" | "summary";
  isSummaryGateActive: boolean;
  summary: SummaryInfo;
  errors: LineageError[];
  nodes: CanvasNode[];
  nodeById: Map<string, CanvasNode>;
  columnById: Map<string, CanvasColumnRow>;
  containers: CanvasContainerBox[];
  edges: CanvasEdge[];
  edgeById: Map<string, CanvasEdge>;
  canvasWidth: number;
  canvasHeight: number;
  rankdir: LayoutRankDir;
  tableLevel: boolean;
  upstreamColumns: Map<string, Array<{ columnId: string; edgeId: string }>>;
  downstreamColumns: Map<string, Array<{ columnId: string; edgeId: string }>>;
  upstreamNodes: Map<string, Array<{ nodeId: string; edgeId: string }>>;
  downstreamNodes: Map<string, Array<{ nodeId: string; edgeId: string }>>;
  tokenIndex: TokenCoordinateIndex;
}

export interface LayoutSqlflowOptions {
  /** Forces client-side Dagre layout even when server-computed coordinates exist */
  layout?: boolean;
  rankdir?: LayoutRankDir;
  tableLevel?: boolean;
  ignoreRecordSet?: boolean;
  ignoreFunction?: boolean;
  showConstant?: boolean;
  includeDirect?: boolean;
  includeIndirect?: boolean;
  summaryThreshold?: number;
  bypassSummaryGate?: boolean;
  /** Optional minimum top margin in px so workspace toolbars never overlap top nodes */
  minTopMargin?: number;
}

function isApiEnvelope(
  input: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON>
): input is ApiEnvelope<SqlflowJSON> {
  return (
    typeof input === "object" &&
    input !== null &&
    "code" in input &&
    ("data" in input || "error" in input)
  );
}

function isSqlflowJSON(input: SqlflowJSON | DataFlow): input is SqlflowJSON {
  return (
    typeof input === "object" &&
    input !== null &&
    ("sqlflow" in input || "graph" in input || "mode" in input)
  );
}

function flattenCatalogDbObjects(catalog: SqlflowCatalog): DbObject[] {
  const out: DbObject[] = [];

  const pushSchemaObjects = (
    schema: SchemaCatalog,
    serverName?: string,
    dbName?: string,
    schemaNameOverride?: string
  ) => {
    const schemaName = schemaNameOverride ?? schema.name;
    const collectTables = (items: SchemaCatalog["tables"], defaultType?: string) => {
      for (const item of items ?? []) {
        out.push({
          id: String(item.id),
          server: item.server ?? serverName,
          database: item.database ?? dbName,
          schema: item.schema ?? schemaName,
          name: item.name,
          displayName: item.displayName,
          alias: item.alias,
          uri: item.uri,
          type: item.type || defaultType || "table",
          subType: item.subType,
          dbLink: item.dbLink,
          columns: item.columns,
          coordinates: item.coordinates,
          more: item.more,
          parent: item.parent
        });
      }
    };

    collectTables(schema.tables, "table");
    collectTables(schema.views, "view");
    collectTables(schema.others, "resultset");

    for (const pkg of schema.packages ?? []) {
      const pkgId = String(pkg.id);
      out.push({
        id: pkgId,
        server: serverName,
        database: dbName,
        schema: schemaName,
        name: pkg.name,
        type: pkg.type || "oracle package",
        coordinates: pkg.coordinates
      });
      for (const routine of [
        ...(pkg.procedures ?? []),
        ...(pkg.functions ?? []),
        ...(pkg.triggers ?? [])
      ]) {
        out.push({
          id: String(routine.id),
          server: serverName,
          database: dbName,
          schema: schemaName,
          name: routine.name,
          type: routine.type || "procedure",
          arguments: routine.arguments,
          coordinates: routine.coordinates,
          parent: pkgId
        });
      }
      for (const variable of pkg.variables ?? []) {
        out.push({
          id: String(variable.id),
          server: serverName,
          database: dbName,
          schema: schemaName,
          name: variable.name,
          type: variable.type || "variable",
          columns: variable.columns,
          coordinates: variable.coordinates,
          parent: pkgId
        });
      }
    }

    for (const routine of [
      ...(schema.procedures ?? []),
      ...(schema.functions ?? []),
      ...(schema.triggers ?? [])
    ]) {
      out.push({
        id: String(routine.id),
        server: serverName,
        database: dbName,
        schema: schemaName,
        name: routine.name,
        type: routine.type || "procedure",
        arguments: routine.arguments,
        coordinates: routine.coordinates
      });
    }
  };

  for (const server of catalog.servers ?? []) {
    for (const db of (server.databases ?? []) as DatabaseCatalog[]) {
      if (db.schemas && db.schemas.length > 0) {
        for (const schema of db.schemas) {
          pushSchemaObjects(schema, server.name, db.name, schema.name);
        }
      }
      pushSchemaObjects(db, server.name, db.name, undefined);
    }
    for (const schema of server.schemas ?? []) {
      pushSchemaObjects(schema, server.name, undefined, schema.name);
    }
  }

  return out;
}

function extractDbObjects(dataflow?: DataFlow): DbObject[] {
  if (!dataflow || !dataflow.dbobjs) {
    return [];
  }
  if (Array.isArray(dataflow.dbobjs)) {
    return dataflow.dbobjs;
  }
  return flattenCatalogDbObjects(dataflow.dbobjs);
}

const INTERMEDIATE_RECORDSET_TYPES = new Set([
  "resultset",
  "select_list",
  "insert-select",
  "update-set",
  "merge-insert",
  "merge-update",
  "select_union",
  "with_cte",
  "update-select",
  "unnest",
  "pivot-table",
  "unpivot-table",
  "case-when"
]);

function shouldFilterOutNode(obj: DbObject, opts?: LayoutSqlflowOptions): boolean {
  const t = (obj.type || "").toLowerCase();
  if (opts?.ignoreFunction && t === "function") {
    return true;
  }
  if (opts?.showConstant === false && (t === "constant" || obj.subType === "constant")) {
    return true;
  }
  if (opts?.ignoreRecordSet && INTERMEDIATE_RECORDSET_TYPES.has(t)) {
    return true;
  }
  return false;
}

function toCanvasColumns(
  nodeId: string,
  columns?: Column[],
  resolveColumnTooltip?: (colId: string, defaultLabel: string) => string | undefined
): CanvasColumnRow[] {
  if (!columns || columns.length === 0) {
    return [];
  }
  const visible = columns.filter((col) => col.uiVisible !== false);
  return visible.map((col, idx) => {
    const colId = String(col.id);
    const rawName = col.displayName || col.name;
    const displayLabel = col.dataType ? `${rawName} : ${col.dataType}` : rawName;
    const tooltip = resolveColumnTooltip
      ? resolveColumnTooltip(colId, displayLabel)
      : displayLabel;
    return {
      id: colId,
      nodeId,
      rowIndex: idx,
      name: col.name,
      displayLabel,
      tooltip,
      dataType: col.dataType,
      primaryKey: Boolean(col.primaryKey),
      unqiueKey: Boolean(col.unqiueKey),
      indexKey: Boolean(col.indexKey),
      foreignKey: Boolean(col.foreignKey),
      coordinates: col.coordinates ?? []
    };
  });
}

function computeSummaryFromObjects(
  allObjects: DbObject[],
  relationships: Relationship[],
  existing?: SummaryInfo
): SummaryInfo {
  if (existing) {
    return existing;
  }
  const databases = new Set<string>();
  const schemas = new Set<string>();
  let tables = 0;
  let views = 0;
  let columns = 0;
  let procedures = 0;
  let functions = 0;
  let triggers = 0;
  let synonyms = 0;

  for (const obj of allObjects) {
    const db = stripDefaultQualifier(obj.database);
    const sc = stripDefaultQualifier(obj.schema);
    if (db) {
      databases.add(db.toUpperCase());
    }
    if (sc) {
      schemas.add(`${db ?? ""}.${sc}`.toUpperCase());
    }
    const t = (obj.type || "").toLowerCase();
    if (t === "table") {
      tables += 1;
    } else if (t === "view" || t === "materialized_view" || t === "materialized view") {
      views += 1;
    } else if (t === "procedure" || t === "oracle package" || t === "package") {
      procedures += 1;
    } else if (t === "function") {
      functions += 1;
    } else if (t === "trigger") {
      triggers += 1;
    } else if (t === "synonym") {
      synonyms += 1;
    }
    columns += obj.columns?.length ?? 0;
  }

  return {
    process: 0,
    database: databases.size,
    schema: schemas.size,
    table: tables,
    view: views,
    column: columns,
    relationship: relationships.length,
    procedure: procedures,
    function: functions,
    trigger: triggers,
    synonym: synonyms
  };
}

const STATEMENT_SPAN_NODE_TYPES = new Set([
  "resultset",
  "select_list",
  "insert-select",
  "update-set",
  "merge-insert",
  "merge-update",
  "select_union",
  "with_cte",
  "update-select",
  "unnest"
]);

function indexTokenCoordinates(nodes: CanvasNode[]): TokenCoordinateIndex {
  const index: TokenCoordinateIndex = new Map();

  const addSpans = (
    coords: Coordinate[],
    hit: TokenCoordinateHit,
    skipMultiLineSpan = false
  ) => {
    for (const span of normalizeCoordinateSpans(coords)) {
      if (skipMultiLineSpan && span.endLine > span.startLine) {
        continue;
      }
      for (let line = span.startLine; line <= span.endLine; line++) {
        let lineMap = index.get(line);
        if (!lineMap) {
          lineMap = new Map();
          index.set(line, lineMap);
        }
        const colStart = line === span.startLine ? span.startCol : 1;
        const colEnd = line === span.endLine ? Math.max(colStart, span.endCol) : colStart + 120;
        for (let col = colStart; col <= colEnd; col++) {
          // Prefer more specific column hits over whole-node hits
          const existing = lineMap.get(col);
          if (!existing || hit.kind === "column") {
            lineMap.set(col, hit);
          }
        }
      }
    }
  };

  for (const node of nodes) {
    const isStatementResultSet = STATEMENT_SPAN_NODE_TYPES.has(node.type.toLowerCase());
    if (node.coordinates.length > 0 && !isStatementResultSet) {
      addSpans(node.coordinates, { kind: "node", nodeId: node.id }, true);
    }
    for (const col of node.columns) {
      if (col.coordinates.length > 0) {
        addSpans(
          col.coordinates,
          {
            kind: "column",
            nodeId: node.id,
            columnId: col.id
          },
          col.name.toLowerCase() === "relationrows"
        );
      }
    }
  }

  return index;
}

/**
 * Looks up a 1-based `(line, column)` editor cursor/hover coordinate in `TokenCoordinateIndex` (`04 §5.4`).
 */
export function lookupTokenAtPosition(
  tokenIndex: TokenCoordinateIndex,
  line1Based: number,
  col1Based: number
): TokenCoordinateHit | undefined {
  return tokenIndex.get(line1Based)?.get(col1Based);
}

/**
 * Recomputes a single `CanvasEdge`'s cubic Bézier curve and `10x7` arrowhead points
 * from the current `(x, y, width)` of `nodeById` (used during live node dragging).
 */
export function computeEdgeGeometryForNodes(
  edge: Omit<CanvasEdge, "bezier" | "arrowPoints">,
  nodeById: ReadonlyMap<string, CanvasNode>,
  tableLevel = false
): CanvasEdge | null {
  const srcNode = nodeById.get(edge.sourceNodeId);
  const tgtNode = nodeById.get(edge.targetNodeId);
  if (!srcNode || !tgtNode) {
    return null;
  }

  const srcPort = computeSourcePortAnchor(
    srcNode,
    tableLevel ? undefined : edge.sourceColumnIndex
  );
  const tgtPort = computeTargetPortAnchor(
    tgtNode,
    tableLevel ? undefined : edge.targetColumnIndex
  );
  const bezier = buildCubicBezierEdgePath(srcPort.x, srcPort.y, tgtPort.x, tgtPort.y);
  const arrowPoints = buildArrowheadPoints(tgtPort.x, tgtPort.y);

  return {
    ...edge,
    bezier,
    arrowPoints
  };
}

function computeContainerBoxes(
  nodes: CanvasNode[],
  containerObjects: Map<string, { id: string; name: string; type: string }>
): CanvasContainerBox[] {
  const childrenByParent = new Map<string, CanvasNode[]>();
  for (const node of nodes) {
    if (!node.parentId) {
      continue;
    }
    const list = childrenByParent.get(node.parentId) ?? [];
    list.push(node);
    childrenByParent.set(node.parentId, list);
  }

  const containers: CanvasContainerBox[] = [];
  for (const [parentId, children] of childrenByParent.entries()) {
    if (children.length === 0) {
      continue;
    }
    let minX = Number.POSITIVE_INFINITY;
    let minY = Number.POSITIVE_INFINITY;
    let maxX = Number.NEGATIVE_INFINITY;
    let maxY = Number.NEGATIVE_INFINITY;
    for (const child of children) {
      minX = Math.min(minX, child.x);
      minY = Math.min(minY, child.y);
      maxX = Math.max(maxX, child.x + child.width);
      maxY = Math.max(maxY, child.y + child.height);
    }
    const meta = containerObjects.get(parentId);
    containers.push({
      id: parentId,
      name: meta?.name ?? parentId,
      type: meta?.type ?? "package",
      x: minX - CONTAINER_STYLE_TOKENS.paddingX,
      y: minY - CONTAINER_STYLE_TOKENS.paddingTop,
      width: maxX - minX + CONTAINER_STYLE_TOKENS.paddingX * 2,
      height:
        maxY -
        minY +
        CONTAINER_STYLE_TOKENS.paddingTop +
        CONTAINER_STYLE_TOKENS.paddingBottom,
      childNodeIds: children.map((c) => c.id)
    });
  }

  return containers;
}

/**
 * Updates node position `(nextX, nextY)` immutably during interactive header drag,
 * recomputing all incident SVG Bézier paths, container bounding boxes, and canvas dimensions.
 */
export function moveCanvasNode(
  model: CanvasGraphModel,
  nodeId: string,
  nextX: number,
  nextY: number
): CanvasGraphModel {
  const target = model.nodeById.get(nodeId);
  if (!target) {
    return model;
  }

  const clampedX = Math.max(8, Math.round(nextX));
  const clampedY = Math.max(8, Math.round(nextY));
  const updatedNode: CanvasNode = {
    ...target,
    x: clampedX,
    y: clampedY
  };

  const nextNodes = model.nodes.map((n) => (n.id === nodeId ? updatedNode : n));
  const nextNodeById = new Map(model.nodeById);
  nextNodeById.set(nodeId, updatedNode);

  const nextEdges: CanvasEdge[] = [];
  const nextEdgeById = new Map<string, CanvasEdge>();
  for (const edge of model.edges) {
    if (edge.sourceNodeId === nodeId || edge.targetNodeId === nodeId) {
      const recomputed = computeEdgeGeometryForNodes(edge, nextNodeById, model.tableLevel);
      if (recomputed) {
        nextEdges.push(recomputed);
        nextEdgeById.set(recomputed.id, recomputed);
      }
    } else {
      nextEdges.push(edge);
      nextEdgeById.set(edge.id, edge);
    }
  }

  const containerMeta = new Map(
    model.containers.map((c) => [c.id, { id: c.id, name: c.name, type: c.type }])
  );
  const nextContainers = computeContainerBoxes(nextNodes, containerMeta);

  let maxRight = 400;
  let maxBottom = 300;
  for (const n of nextNodes) {
    maxRight = Math.max(maxRight, n.x + n.width + 80);
    maxBottom = Math.max(maxBottom, n.y + n.height + 80);
  }

  return {
    ...model,
    nodes: nextNodes,
    nodeById: nextNodeById,
    containers: nextContainers,
    edges: nextEdges,
    edgeById: nextEdgeById,
    canvasWidth: maxRight,
    canvasHeight: maxBottom
  };
}

/**
 * Normalizes and lays out any `SqlflowJSON`, `DataFlow`, or `ApiEnvelope<SqlflowJSON>`
 * into an interactive `CanvasGraphModel` using `@dagrejs/dagre` (`LR` or `TB`) or server coordinates.
 */
export function layoutSqlflowGraph(
  rawInput: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON>,
  options?: LayoutSqlflowOptions
): CanvasGraphModel {
  const unwrapped: SqlflowJSON | DataFlow = isApiEnvelope(rawInput)
    ? (rawInput.data ?? {})
    : rawInput;
  const payload: SqlflowJSON = isSqlflowJSON(unwrapped)
    ? unwrapped
    : { mode: "global", sqlflow: unwrapped };

  const dataflow: DataFlow | undefined = payload.sqlflow;
  const rankdir: LayoutRankDir =
    options?.rankdir ??
    (payload.graph?.orientation === "TOP_TO_BOTTOM" ||
    dataflow?.orientation === "TOP_TO_BOTTOM"
      ? "TB"
      : "LR");
  const tableLevel = Boolean(options?.tableLevel);
  const includeDirect = options?.includeDirect ?? true;
  const includeIndirect = options?.includeIndirect ?? true;

  const rawObjects = extractDbObjects(dataflow);
  const relationships = dataflow?.relationships ?? [];
  const errors: LineageError[] = [
    ...(dataflow?.errors ?? []),
    ...(!Array.isArray(dataflow?.dbobjs) ? (dataflow?.dbobjs?.errorMessages ?? []) : [])
  ];

  const containerMeta = new Map<string, { id: string; name: string; type: string }>();
  const dbObjectById = new Map<string, DbObject>();
  for (const obj of rawObjects) {
    dbObjectById.set(String(obj.id), obj);
    const lowerType = (obj.type || "").toLowerCase();
    if (CONTAINER_DBO_TYPES.has(lowerType) && (!obj.columns || obj.columns.length === 0)) {
      containerMeta.set(String(obj.id), {
        id: String(obj.id),
        name: formatDbObjectDisplayTitle(obj, { includeDatabase: true }),
        type: lowerType
      });
    }
  }

  const rawGraph = payload.graph;
  const listIdMap = rawGraph?.listIdMap;
  const tooltipMap = rawGraph?.tooltip;
  const relationshipIdMap = rawGraph?.relationshipIdMap;

  // Bridge model IDs ("1", "2") <-> GraphML IDs ("n0", "n0::n0") per 03 §1.5.3
  const resolveGraphId = (modelId: string): string =>
    listIdMap?.[modelId]?.[0] ?? modelId;

  const graphIdToModelId = new Map<string, string>();
  if (listIdMap) {
    for (const [modelId, graphIds] of Object.entries(listIdMap)) {
      if (Array.isArray(graphIds)) {
        for (const gid of graphIds) {
          if (gid && !graphIdToModelId.has(String(gid))) {
            graphIdToModelId.set(String(gid), String(modelId));
          }
        }
      }
    }
  }

  const preLaidTables = rawGraph?.elements?.tables ?? [];
  const preLaidPackages = rawGraph?.elements?.packages ?? [];
  for (const pkg of preLaidPackages) {
    const rawPkgId = String(pkg.id);
    const canonicalPkgId = graphIdToModelId.get(rawPkgId) ?? rawPkgId;
    if (!containerMeta.has(canonicalPkgId)) {
      containerMeta.set(canonicalPkgId, {
        id: canonicalPkgId,
        name:
          tooltipMap?.[rawPkgId] ||
          tooltipMap?.[canonicalPkgId] ||
          pkg.name ||
          pkg.label?.content ||
          canonicalPkgId,
        type: pkg.type || "package"
      });
    }
  }

  const serverTableMap = new Map(preLaidTables.map((t) => [String(t.id), t]));
  const lookupServerTable = (nodeId: string) => {
    const mappedGraphId = resolveGraphId(nodeId);
    return serverTableMap.get(mappedGraphId) ?? serverTableMap.get(nodeId);
  };

  const colOwnerObjectId = new Map<string, string>();
  for (const obj of rawObjects) {
    const oid = String(obj.id);
    for (const col of obj.columns ?? []) {
      colOwnerObjectId.set(String(col.id), oid);
    }
  }
  const activeRelationshipObjectIds = new Set<string>();
  const markEndpointObject = (ep?: { id?: string | number; parentId?: string | number }) => {
    if (!ep) {
      return;
    }
    if (ep.parentId !== undefined && ep.parentId !== null) {
      activeRelationshipObjectIds.add(String(ep.parentId));
      return;
    }
    if (ep.id !== undefined && ep.id !== null) {
      const sid = String(ep.id);
      const owner = colOwnerObjectId.get(sid) ?? (dbObjectById.has(sid) ? sid : undefined);
      if (owner) {
        activeRelationshipObjectIds.add(owner);
      }
    }
  };
  for (const rel of relationships) {
    const relType = rel.type || "fdd";
    if (!includeDirect && relType === "fdd") {
      continue;
    }
    if (!includeIndirect && (relType === "fdr" || relType === "frd")) {
      continue;
    }
    markEndpointObject(rel.target);
    for (const src of rel.sources ?? []) {
      markEndpointObject(src);
    }
  }

  const visibleCandidateObjects = rawObjects.filter((obj) => {
    const nid = String(obj.id);
    return !containerMeta.has(nid) && !shouldFilterOutNode(obj, options);
  });

  const hasMatchedServerObject =
    visibleCandidateObjects.length === 0 ||
    visibleCandidateObjects.some((obj) => {
      const gt = lookupServerTable(String(obj.id));
      return Boolean(gt && Number.isFinite(gt.x) && Number.isFinite(gt.y));
    });

  const allActiveObjectsPositioned = visibleCandidateObjects.every((obj) => {
    const nid = String(obj.id);
    if (!activeRelationshipObjectIds.has(nid)) {
      return true;
    }
    const gt = lookupServerTable(nid);
    return Boolean(gt && Number.isFinite(gt.x) && Number.isFinite(gt.y));
  });

  const useServerCoordinates =
    !options?.layout &&
    preLaidTables.length > 0 &&
    preLaidTables.every((t) => Number.isFinite(t.x) && Number.isFinite(t.y)) &&
    hasMatchedServerObject &&
    allActiveObjectsPositioned;

  const nodes: CanvasNode[] = [];
  const nodeById = new Map<string, CanvasNode>();
  const columnById = new Map<string, CanvasColumnRow>();
  const columnOwnerNode = new Map<string, { nodeId: string; rowIndex: number }>();

  if (preLaidTables.length > 0 && rawObjects.length === 0) {
    for (const gt of preLaidTables) {
      const nodeId = String(gt.id);
      const canonicalParentId = gt.parentId
        ? (graphIdToModelId.get(String(gt.parentId)) ?? String(gt.parentId))
        : undefined;
      const fakeObj: DbObject = {
        id: nodeId,
        database: gt.database,
        schema: gt.schema,
        name: gt.displayName || gt.name || gt.label?.content || nodeId,
        type: gt.type || "table",
        subType: gt.subType,
        more: gt.more,
        parent: canonicalParentId,
        coordinates: gt.coordinates,
        columns: (gt.columns ?? []).map((c) => ({
          id: String(c.id),
          name: c.name || c.label?.content || String(c.id),
          dataType: c.dataType,
          primaryKey: c.primaryKey,
          unqiueKey: c.unqiueKey,
          indexKey: c.indexKey,
          foreignKey: c.foreignKey,
          coordinates: c.coordinates
        }))
      };
      if (shouldFilterOutNode(fakeObj, options)) {
        continue;
      }
      const displayTitle = formatDbObjectDisplayTitle(fakeObj);
      const tooltipTitle =
        tooltipMap?.[nodeId] ??
        formatDbObjectDisplayTitle(fakeObj, { includeDatabase: true });
      const cols = tableLevel
        ? []
        : toCanvasColumns(
            nodeId,
            fakeObj.columns,
            (cid, def) => tooltipMap?.[cid] ?? def
          );
      const dims = computeNodeDimensions({
        title: displayTitle,
        columnLabels: cols.map((c) => c.displayLabel),
        tableLevel
      });
      const node: CanvasNode = {
        id: nodeId,
        name: fakeObj.name,
        displayTitle,
        tooltipTitle,
        type: fakeObj.type,
        subType: fakeObj.subType,
        database: fakeObj.database,
        schema: fakeObj.schema,
        parentId: fakeObj.parent,
        x: useServerCoordinates ? gt.x : 0,
        y: useServerCoordinates ? gt.y : 0,
        width: useServerCoordinates && gt.width > 0 ? gt.width : dims.width,
        height: useServerCoordinates && gt.height > 0 ? gt.height : dims.height,
        headerHeight: dims.headerHeight,
        rowHeight: dims.rowHeight,
        columns: cols,
        coordinates: fakeObj.coordinates ?? [],
        moreUp: Boolean(gt.more),
        moreDown: Boolean(gt.more)
      };
      nodes.push(node);
      nodeById.set(nodeId, node);
      for (const col of cols) {
        columnById.set(col.id, col);
        columnOwnerNode.set(col.id, { nodeId, rowIndex: col.rowIndex });
        const mappedModelColId = graphIdToModelId.get(col.id);
        if (mappedModelColId) {
          columnOwnerNode.set(mappedModelColId, { nodeId, rowIndex: col.rowIndex });
        }
      }
    }
  } else {
    for (const obj of rawObjects) {
      const nodeId = String(obj.id);
      if (containerMeta.has(nodeId)) {
        continue;
      }
      if (shouldFilterOutNode(obj, options)) {
        continue;
      }
      const mappedGraphId = resolveGraphId(nodeId);
      const gt = lookupServerTable(nodeId);
      if (useServerCoordinates && !gt) {
        continue;
      }
      const displayTitle =
        obj.displayName || formatDbObjectDisplayTitle(obj);
      const tooltipTitle =
        tooltipMap?.[mappedGraphId] ??
        tooltipMap?.[nodeId] ??
        formatDbObjectDisplayTitle(obj, { includeDatabase: true });
      const cols = tableLevel
        ? []
        : toCanvasColumns(
            nodeId,
            obj.columns,
            (cid, def) =>
              tooltipMap?.[resolveGraphId(cid)] ?? tooltipMap?.[cid] ?? def
          );
      const dims = computeNodeDimensions({
        title: displayTitle,
        columnLabels: cols.map((c) => c.displayLabel),
        tableLevel
      });
      const resolvedParentId =
        obj.parent ??
        (gt?.parentId
          ? (graphIdToModelId.get(String(gt.parentId)) ?? String(gt.parentId))
          : undefined);
      const node: CanvasNode = {
        id: nodeId,
        name: obj.name,
        displayTitle,
        tooltipTitle,
        type: obj.type || "table",
        subType: obj.subType,
        database: obj.database,
        schema: obj.schema,
        parentId: resolvedParentId,
        x: useServerCoordinates && gt && Number.isFinite(gt.x) ? gt.x : 0,
        y: useServerCoordinates && gt && Number.isFinite(gt.y) ? gt.y : 0,
        width: useServerCoordinates && gt && gt.width > 0 ? gt.width : dims.width,
        height: useServerCoordinates && gt && gt.height > 0 ? gt.height : dims.height,
        headerHeight: dims.headerHeight,
        rowHeight: dims.rowHeight,
        columns: cols,
        coordinates: obj.coordinates ?? gt?.coordinates ?? [],
        moreUp: Boolean(obj.more ?? gt?.more),
        moreDown: Boolean(obj.more ?? gt?.more)
      };
      nodes.push(node);
      nodeById.set(nodeId, node);
      for (const col of cols) {
        columnById.set(col.id, col);
        columnOwnerNode.set(col.id, { nodeId, rowIndex: col.rowIndex });
        // Also index by mapped GraphML column IDs ("n0::n0") and parentId::columnName
        for (const gid of listIdMap?.[col.id] ?? []) {
          if (gid) {
            columnOwnerNode.set(String(gid), { nodeId, rowIndex: col.rowIndex });
          }
        }
        columnOwnerNode.set(`${nodeId}::${col.name.toLowerCase()}`, {
          nodeId,
          rowIndex: col.rowIndex
        });
      }
    }
  }

  // Build logical edge descriptors from DataFlow relationships OR pre-laid-out graph.elements.edges
  interface PendingEdge {
    id: string;
    sourceNodeId: string;
    sourceColumnId?: string;
    sourceColumnIndex?: number;
    targetNodeId: string;
    targetColumnId?: string;
    targetColumnIndex?: number;
    type: RelationshipType | string;
    effectType?: string;
    joinType?: string;
    condition?: string;
    transforms: Transform[];
    coordinates: Coordinate[];
  }

  const pendingEdges: PendingEdge[] = [];
  const seenEdgeKeys = new Set<string>();

  const resolveNodeKey = (id?: string): string | undefined => {
    if (!id) {
      return undefined;
    }
    if (nodeById.has(id)) {
      return id;
    }
    const asModelId = graphIdToModelId.get(id);
    if (asModelId && nodeById.has(asModelId)) {
      return asModelId;
    }
    const asGraphId = resolveGraphId(id);
    if (nodeById.has(asGraphId)) {
      return asGraphId;
    }
    return undefined;
  };

  const resolveEndpoint = (
    elId?: string,
    parentId?: string,
    colName?: string
  ): { nodeId: string; columnId?: string; columnIndex?: number } | null => {
    if (elId) {
      const candidateColKeys = [
        elId,
        graphIdToModelId.get(elId),
        resolveGraphId(elId)
      ].filter((k): k is string => Boolean(k));
      for (const key of candidateColKeys) {
        const owner = columnOwnerNode.get(key);
        if (owner) {
          const col = nodeById.get(owner.nodeId)?.columns[owner.rowIndex];
          return {
            nodeId: owner.nodeId,
            columnId: col?.id,
            columnIndex: owner.rowIndex
          };
        }
      }
    }
    const resolvedParentNodeId = resolveNodeKey(parentId);
    if (resolvedParentNodeId) {
      if (colName) {
        const byName = columnOwnerNode.get(
          `${resolvedParentNodeId}::${colName.toLowerCase()}`
        );
        if (byName) {
          const col = nodeById.get(resolvedParentNodeId)?.columns[byName.rowIndex];
          return {
            nodeId: resolvedParentNodeId,
            columnId: col?.id,
            columnIndex: byName.rowIndex
          };
        }
      }
      return { nodeId: resolvedParentNodeId };
    }
    const resolvedNodeId = resolveNodeKey(elId);
    if (resolvedNodeId) {
      return { nodeId: resolvedNodeId };
    }
    return null;
  };

  if (relationships.length > 0) {
    for (const rel of relationships) {
      const relType = rel.type || "fdd";
      if (!includeDirect && relType === "fdd") {
        continue;
      }
      if (!includeIndirect && (relType === "fdr" || relType === "frd")) {
        continue;
      }
      const targetEl = rel.target;
      if (!targetEl) {
        continue;
      }
      const resolvedTarget = resolveEndpoint(
        targetEl.id ? String(targetEl.id) : undefined,
        targetEl.parentId ? String(targetEl.parentId) : undefined,
        targetEl.column || targetEl.name
      );
      if (!resolvedTarget) {
        continue;
      }

      for (let i = 0; i < (rel.sources ?? []).length; i++) {
        const srcEl = rel.sources![i]!;
        const resolvedSource = resolveEndpoint(
          srcEl.id ? String(srcEl.id) : undefined,
          srcEl.parentId ? String(srcEl.parentId) : undefined,
          srcEl.column || srcEl.name
        );
        if (!resolvedSource) {
          continue;
        }
        if (
          resolvedSource.nodeId === resolvedTarget.nodeId &&
          resolvedSource.columnId === resolvedTarget.columnId
        ) {
          continue;
        }
        const dedupKey = tableLevel
          ? `${resolvedSource.nodeId}->${resolvedTarget.nodeId}::${relType}`
          : `${resolvedSource.nodeId}:${resolvedSource.columnId ?? "*"}->${resolvedTarget.nodeId}:${resolvedTarget.columnId ?? "*"}::${relType}`;
        if (seenEdgeKeys.has(dedupKey)) {
          continue;
        }
        seenEdgeKeys.add(dedupKey);

        const transforms = [
          ...(srcEl.transforms ?? []),
          ...(targetEl.transforms ?? [])
        ];
        const coordinates = [
          ...(srcEl.coordinates ?? []),
          ...(targetEl.coordinates ?? [])
        ];

        pendingEdges.push({
          id: `${rel.id}_${i}`,
          sourceNodeId: resolvedSource.nodeId,
          sourceColumnId: tableLevel ? undefined : resolvedSource.columnId,
          sourceColumnIndex: tableLevel ? undefined : resolvedSource.columnIndex,
          targetNodeId: resolvedTarget.nodeId,
          targetColumnId: tableLevel ? undefined : resolvedTarget.columnId,
          targetColumnIndex: tableLevel ? undefined : resolvedTarget.columnIndex,
          type: relType,
          effectType: rel.effectType,
          joinType: rel.joinType,
          condition: rel.condition,
          transforms,
          coordinates
        });
      }
    }
  } else if (rawGraph?.elements?.edges) {
    const relationshipById = new Map(
      relationships.map((r) => [String(r.id), r])
    );
    for (const ge of rawGraph.elements.edges) {
      const resolvedSource = resolveEndpoint(String(ge.sourceId));
      const resolvedTarget = resolveEndpoint(String(ge.targetId));
      if (!resolvedSource || !resolvedTarget) {
        continue;
      }
      const mappedRelIds = relationshipIdMap?.[String(ge.id)] ?? [];
      const primaryRel = mappedRelIds
        .map((rid) => relationshipById.get(String(rid)))
        .find((r): r is Relationship => Boolean(r));
      const relType = ge.type || primaryRel?.type || "fdd";
      if (!includeDirect && relType === "fdd") {
        continue;
      }
      if (!includeIndirect && (relType === "fdr" || relType === "frd")) {
        continue;
      }
      pendingEdges.push({
        id: String(ge.id),
        sourceNodeId: resolvedSource.nodeId,
        sourceColumnId: tableLevel ? undefined : resolvedSource.columnId,
        sourceColumnIndex: tableLevel ? undefined : resolvedSource.columnIndex,
        targetNodeId: resolvedTarget.nodeId,
        targetColumnId: tableLevel ? undefined : resolvedTarget.columnId,
        targetColumnIndex: tableLevel ? undefined : resolvedTarget.columnIndex,
        type: relType,
        effectType: ge.effectType ?? primaryRel?.effectType,
        joinType: ge.joinType ?? primaryRel?.joinType,
        condition: ge.condition ?? primaryRel?.condition,
        transforms: ge.transforms ?? [],
        coordinates: ge.coordinates ?? []
      });
    }
  }

  const hasLeftMore = nodes.some((n) => n.moreUp);
  const minLeftMargin =
    CANVAS_GEOMETRY_TOKENS.paddingLeft +
    (hasLeftMore ? CANVAS_GEOMETRY_TOKENS.paddingLeftMore : 0);
  const minTopMargin = options?.minTopMargin ?? CANVAS_GEOMETRY_TOKENS.paddingTop;

  // Run client-side `@dagrejs/dagre` layout when unpositioned or explicitly requested
  if (!useServerCoordinates && nodes.length > 0) {
    const g = new dagre.graphlib.Graph({ directed: true, compound: true, multigraph: false });
    const fanOutDensity =
      nodes.length > 0 ? Math.min(80, Math.floor((pendingEdges.length / nodes.length) * 14)) : 0;

    g.setGraph({
      rankdir,
      nodesep: CANVAS_GEOMETRY_TOKENS.dagreNodesep,
      ranksep: CANVAS_GEOMETRY_TOKENS.dagreRanksep + fanOutDensity,
      marginx: minLeftMargin,
      marginy: minTopMargin
    });
    g.setDefaultEdgeLabel(() => ({}));

    const activeParentIds = new Set<string>();
    for (const node of nodes) {
      if (node.parentId && containerMeta.has(node.parentId) && !activeParentIds.has(node.parentId)) {
        activeParentIds.add(node.parentId);
        g.setNode(node.parentId, { label: node.parentId });
      }
      g.setNode(node.id, {
        width: node.width,
        height: node.height
      });
      if (node.parentId && activeParentIds.has(node.parentId)) {
        g.setParent(node.id, node.parentId);
      }
    }

    const addedNodePairs = new Set<string>();
    for (const edge of pendingEdges) {
      if (edge.sourceNodeId === edge.targetNodeId) {
        continue;
      }
      const pairKey = `${edge.sourceNodeId}->${edge.targetNodeId}`;
      if (!addedNodePairs.has(pairKey)) {
        addedNodePairs.add(pairKey);
        g.setEdge(edge.sourceNodeId, edge.targetNodeId);
      }
    }

    dagre.layout(g);

    for (const node of nodes) {
      const laidOut = g.node(node.id);
      if (laidOut && Number.isFinite(laidOut.x) && Number.isFinite(laidOut.y)) {
        // Dagre reports center (x, y); convert to top-left (x, y)
        node.x = Math.round(laidOut.x - node.width / 2);
        node.y = Math.round(laidOut.y - node.height / 2);
      }
    }
  }

  // Positive coordinate normalization (03 §4.4.3): ensure no node is clipped at negative X or Y
  if (nodes.length > 0) {
    let minX = Number.POSITIVE_INFINITY;
    let minY = Number.POSITIVE_INFINITY;
    for (const n of nodes) {
      minX = Math.min(minX, n.x);
      minY = Math.min(minY, n.y);
    }
    const shiftX = minX < minLeftMargin ? minLeftMargin - minX : 0;
    const shiftY = minY < minTopMargin ? minTopMargin - minY : 0;
    if (shiftX !== 0 || shiftY !== 0) {
      for (const n of nodes) {
        n.x = Math.round(n.x + shiftX);
        n.y = Math.round(n.y + shiftY);
      }
    }
  }

  // Materialize SVG cubic Bézier edges + arrowheads and adjacency maps
  const edges: CanvasEdge[] = [];
  const edgeById = new Map<string, CanvasEdge>();
  const upstreamColumns = new Map<string, Array<{ columnId: string; edgeId: string }>>();
  const downstreamColumns = new Map<string, Array<{ columnId: string; edgeId: string }>>();
  const upstreamNodes = new Map<string, Array<{ nodeId: string; edgeId: string }>>();
  const downstreamNodes = new Map<string, Array<{ nodeId: string; edgeId: string }>>();

  for (const pending of pendingEdges) {
    const built = computeEdgeGeometryForNodes(pending, nodeById, tableLevel);
    if (!built) {
      continue;
    }
    edges.push(built);
    edgeById.set(built.id, built);

    const upNodeArr = upstreamNodes.get(built.targetNodeId) ?? [];
    upNodeArr.push({ nodeId: built.sourceNodeId, edgeId: built.id });
    upstreamNodes.set(built.targetNodeId, upNodeArr);

    const downNodeArr = downstreamNodes.get(built.sourceNodeId) ?? [];
    downNodeArr.push({ nodeId: built.targetNodeId, edgeId: built.id });
    downstreamNodes.set(built.sourceNodeId, downNodeArr);

    if (built.sourceColumnId && built.targetColumnId) {
      const upColArr = upstreamColumns.get(built.targetColumnId) ?? [];
      upColArr.push({ columnId: built.sourceColumnId, edgeId: built.id });
      upstreamColumns.set(built.targetColumnId, upColArr);

      const downColArr = downstreamColumns.get(built.sourceColumnId) ?? [];
      downColArr.push({ columnId: built.targetColumnId, edgeId: built.id });
      downstreamColumns.set(built.sourceColumnId, downColArr);
    }
  }

  const containers = computeContainerBoxes(nodes, containerMeta);

  let canvasWidth = payload.graph?.width ?? 640;
  let canvasHeight = payload.graph?.height ?? 420;
  for (const n of nodes) {
    canvasWidth = Math.max(canvasWidth, n.x + n.width + 80);
    canvasHeight = Math.max(canvasHeight, n.y + n.height + 80);
  }
  for (const c of containers) {
    canvasWidth = Math.max(canvasWidth, c.x + c.width + 80);
    canvasHeight = Math.max(canvasHeight, c.y + c.height + 80);
  }

  const summary = computeSummaryFromObjects(
    rawObjects.length > 0
      ? rawObjects
      : nodes.map((n) => ({
          id: n.id,
          name: n.name,
          type: n.type,
          database: n.database,
          schema: n.schema,
          columns: n.columns
        })),
    relationships,
    payload.summary ?? dataflow?.summary
  );

  const threshold = options?.summaryThreshold ?? CANVAS_GEOMETRY_TOKENS.summaryObjectThreshold;
  const mode =
    payload.mode ??
    (nodes.length > threshold ? "summary" : "global");
  const isSummaryGateActive =
    !options?.bypassSummaryGate &&
    (mode === "summary" || nodes.length > threshold);

  const tokenIndex = indexTokenCoordinates(nodes);

  return {
    mode,
    isSummaryGateActive,
    summary,
    errors,
    nodes,
    nodeById,
    columnById,
    containers,
    edges,
    edgeById,
    canvasWidth,
    canvasHeight,
    rankdir,
    tableLevel,
    upstreamColumns,
    downstreamColumns,
    upstreamNodes,
    downstreamNodes,
    tokenIndex
  };
}

export type HighlightTarget =
  | { kind: "column"; columnId: string }
  | { kind: "node"; nodeId: string }
  | { kind: "edge"; edgeId: string };

export interface LineageHighlightSet {
  nodeIds: Set<string>;
  columnIds: Set<string>;
  edgeIds: Set<string>;
  coordinates: Coordinate[];
}

function filterConciseCoordinates(
  coords: readonly Coordinate[],
  allowMultiLineStatement = false
): Coordinate[] {
  if (coords.length === 0) {
    return [];
  }
  const spans = normalizeCoordinateSpans(coords);
  const concise = spans.filter((s) => s.endLine - s.startLine <= 1);
  const chosen = concise.length > 0 ? concise : allowMultiLineStatement ? spans : [];
  return chosen.map((s) => ({
    x: s.startLine,
    y: s.startCol,
    x1: s.endLine,
    y1: s.endCol
  }));
}

/**
 * Recursively traces upstream (`backwards`) and downstream (`forwards`) lineage paths
 * for sub-millisecond hover and click pinning on `<LineageCanvas />` (`04 §6.3`).
 */
export function traceLineageHighlight(
  model: CanvasGraphModel,
  target: HighlightTarget | null | undefined
): LineageHighlightSet {
  const nodeIds = new Set<string>();
  const columnIds = new Set<string>();
  const edgeIds = new Set<string>();
  const coordinates: Coordinate[] = [];

  if (!target) {
    return { nodeIds, columnIds, edgeIds, coordinates };
  }

  if (target.kind === "edge") {
    const edge = model.edgeById.get(target.edgeId);
    if (!edge) {
      return { nodeIds, columnIds, edgeIds, coordinates };
    }
    edgeIds.add(edge.id);
    nodeIds.add(edge.sourceNodeId);
    nodeIds.add(edge.targetNodeId);
    if (edge.sourceColumnId) {
      columnIds.add(edge.sourceColumnId);
    }
    if (edge.targetColumnId) {
      columnIds.add(edge.targetColumnId);
    }
    coordinates.push(...filterConciseCoordinates(edge.coordinates, true));
    return { nodeIds, columnIds, edgeIds, coordinates };
  }

  if (target.kind === "node") {
    const rootNode = model.nodeById.get(target.nodeId);
    if (!rootNode) {
      return { nodeIds, columnIds, edgeIds, coordinates };
    }
    nodeIds.add(rootNode.id);
    const isIntermediate = STATEMENT_SPAN_NODE_TYPES.has(rootNode.type.toLowerCase());
    if (isIntermediate && rootNode.columns.length > 0) {
      for (const c of rootNode.columns) {
        if (c.name.toLowerCase() !== "relationrows") {
          coordinates.push(...filterConciseCoordinates(c.coordinates, false));
        }
      }
      if (coordinates.length === 0) {
        coordinates.push(...filterConciseCoordinates(rootNode.coordinates, true));
      }
    } else {
      coordinates.push(...filterConciseCoordinates(rootNode.coordinates, true));
    }

    // Traverse upstream nodes
    const upQueue = [rootNode.id];
    const seenUp = new Set<string>([rootNode.id]);
    while (upQueue.length > 0) {
      const curr = upQueue.shift()!;
      for (const step of model.upstreamNodes.get(curr) ?? []) {
        edgeIds.add(step.edgeId);
        nodeIds.add(step.nodeId);
        const edge = model.edgeById.get(step.edgeId);
        if (edge?.sourceColumnId) {
          columnIds.add(edge.sourceColumnId);
        }
        if (edge?.targetColumnId) {
          columnIds.add(edge.targetColumnId);
        }
        if (!seenUp.has(step.nodeId)) {
          seenUp.add(step.nodeId);
          upQueue.push(step.nodeId);
        }
      }
    }

    // Traverse downstream nodes
    const downQueue = [rootNode.id];
    const seenDown = new Set<string>([rootNode.id]);
    while (downQueue.length > 0) {
      const curr = downQueue.shift()!;
      for (const step of model.downstreamNodes.get(curr) ?? []) {
        edgeIds.add(step.edgeId);
        nodeIds.add(step.nodeId);
        const edge = model.edgeById.get(step.edgeId);
        if (edge?.sourceColumnId) {
          columnIds.add(edge.sourceColumnId);
        }
        if (edge?.targetColumnId) {
          columnIds.add(edge.targetColumnId);
        }
        if (!seenDown.has(step.nodeId)) {
          seenDown.add(step.nodeId);
          downQueue.push(step.nodeId);
        }
      }
    }

    return { nodeIds, columnIds, edgeIds, coordinates };
  }

  // Column-level recursive upstream + downstream trace
  const rootCol = model.columnById.get(target.columnId);
  if (!rootCol) {
    return { nodeIds, columnIds, edgeIds, coordinates };
  }
  columnIds.add(rootCol.id);
  nodeIds.add(rootCol.nodeId);
  coordinates.push(...filterConciseCoordinates(rootCol.coordinates, true));

  const traceIndirect = rootCol.name.toLowerCase() === "relationrows";

  const upColQueue = [rootCol.id];
  const seenUpCol = new Set<string>([rootCol.id]);
  while (upColQueue.length > 0) {
    const curr = upColQueue.shift()!;
    const rawSteps = model.upstreamColumns.get(curr) ?? [];
    const directSteps = rawSteps.filter((s) => {
      const t = model.edgeById.get(s.edgeId)?.type;
      return !t || t === "fdd";
    });
    const stepsToFollow = !traceIndirect && directSteps.length > 0 ? directSteps : rawSteps;

    for (const step of stepsToFollow) {
      edgeIds.add(step.edgeId);
      columnIds.add(step.columnId);
      const colObj = model.columnById.get(step.columnId);
      if (colObj) {
        nodeIds.add(colObj.nodeId);
        coordinates.push(...filterConciseCoordinates(colObj.coordinates, false));
      }
      const edgeObj = model.edgeById.get(step.edgeId);
      if (edgeObj) {
        coordinates.push(...filterConciseCoordinates(edgeObj.coordinates, false));
      }
      if (!seenUpCol.has(step.columnId)) {
        seenUpCol.add(step.columnId);
        upColQueue.push(step.columnId);
      }
    }
  }

  const downColQueue = [rootCol.id];
  const seenDownCol = new Set<string>([rootCol.id]);
  while (downColQueue.length > 0) {
    const curr = downColQueue.shift()!;
    const rawSteps = model.downstreamColumns.get(curr) ?? [];
    const directSteps = rawSteps.filter((s) => {
      const t = model.edgeById.get(s.edgeId)?.type;
      return !t || t === "fdd";
    });
    const stepsToFollow = !traceIndirect && directSteps.length > 0 ? directSteps : rawSteps;

    for (const step of stepsToFollow) {
      edgeIds.add(step.edgeId);
      columnIds.add(step.columnId);
      const colObj = model.columnById.get(step.columnId);
      if (colObj) {
        nodeIds.add(colObj.nodeId);
        coordinates.push(...filterConciseCoordinates(colObj.coordinates, false));
      }
      const edgeObj = model.edgeById.get(step.edgeId);
      if (edgeObj) {
        coordinates.push(...filterConciseCoordinates(edgeObj.coordinates, false));
      }
      if (!seenDownCol.has(step.columnId)) {
        seenDownCol.add(step.columnId);
        downColQueue.push(step.columnId);
      }
    }
  }

  return { nodeIds, columnIds, edgeIds, coordinates };
}

/**
 * Constructs the exact Widget `"onTableNameClick"` payload, stripping `"DEFAULT"` qualifiers (`04 §11.5`).
 */
export function buildOnTableNameClickPayload(node: CanvasNode): OnTableNameClickPayload {
  const payload: OnTableNameClickPayload = {
    table: node.name
  };
  const db = stripDefaultQualifier(node.database);
  const schema = stripDefaultQualifier(node.schema);
  if (db) {
    payload.database = db;
  }
  if (schema) {
    payload.schema = schema;
  }
  return payload;
}

/**
 * Constructs the exact Widget `"onFieldClick"` payload with upstream (`source`) and downstream (`target`)
 * columns and tables, stripping `"DEFAULT"` qualifiers (`04 §11.5`).
 */
export function buildOnFieldClickPayload(
  model: CanvasGraphModel,
  columnId: string
): OnFieldClickPayload | null {
  const col = model.columnById.get(columnId);
  if (!col) {
    return null;
  }
  const ownerNode = model.nodeById.get(col.nodeId);
  if (!ownerNode) {
    return null;
  }

  const collectDirection = (
    adj: ReadonlyMap<string, Array<{ columnId: string; edgeId: string }>>
  ) => {
    const visitedCols = new Set<string>();
    const visitedNodes = new Set<string>();
    const columnsOut: OnFieldClickPayload["source"]["columns"] = [];
    const tablesOut: OnFieldClickPayload["source"]["tables"] = [];

    const queue = [columnId];
    const seen = new Set<string>([columnId]);
    while (queue.length > 0) {
      const curr = queue.shift()!;
      for (const step of adj.get(curr) ?? []) {
        if (!visitedCols.has(step.columnId)) {
          visitedCols.add(step.columnId);
          const depCol = model.columnById.get(step.columnId);
          const depNode = depCol ? model.nodeById.get(depCol.nodeId) : undefined;
          if (depCol && depNode) {
            const colEntry: OnFieldClickPayload["source"]["columns"][number] = {
              table: depNode.name,
              tableType: depNode.type,
              column: depCol.name
            };
            const db = stripDefaultQualifier(depNode.database);
            const schema = stripDefaultQualifier(depNode.schema);
            if (db) {
              colEntry.database = db;
            }
            if (schema) {
              colEntry.schema = schema;
            }
            columnsOut.push(colEntry);

            if (!visitedNodes.has(depNode.id)) {
              visitedNodes.add(depNode.id);
              const tblEntry: OnFieldClickPayload["source"]["tables"][number] = {
                table: depNode.name,
                tableType: depNode.type
              };
              if (db) {
                tblEntry.database = db;
              }
              if (schema) {
                tblEntry.schema = schema;
              }
              tablesOut.push(tblEntry);
            }
          }
        }
        if (!seen.has(step.columnId)) {
          seen.add(step.columnId);
          queue.push(step.columnId);
        }
      }
    }

    return { columns: columnsOut, tables: tablesOut };
  };

  const payload: OnFieldClickPayload = {
    table: ownerNode.name,
    tableType: ownerNode.type,
    column: col.name,
    source: collectDirection(model.upstreamColumns),
    target: collectDirection(model.downstreamColumns)
  };
  const db = stripDefaultQualifier(ownerNode.database);
  const schema = stripDefaultQualifier(ownerNode.schema);
  if (db) {
    payload.database = db;
  }
  if (schema) {
    payload.schema = schema;
  }
  return payload;
}

/**
 * Strictly validates an external `DependencyGraph` v1.0 payload (`Az` in `04 §11.6`).
 * Throws a descriptive `Error` if any structural invariant is violated.
 */
export function validateDependencyGraphV1(input: unknown): DependencyGraphV1 {
  if (!input || typeof input !== "object") {
    throw new Error("DependencyGraph must be a non-null object");
  }
  const dg = input as Partial<DependencyGraphV1>;
  if (dg.version !== DEPENDENCY_GRAPH_VERSION) {
    throw new Error(`DependencyGraph version must equal "${DEPENDENCY_GRAPH_VERSION}"`);
  }
  if (dg.kind !== DEPENDENCY_GRAPH_KIND) {
    throw new Error(`DependencyGraph kind must equal "${DEPENDENCY_GRAPH_KIND}"`);
  }
  if (dg.direction !== "upstream" && dg.direction !== "downstream") {
    throw new Error('DependencyGraph direction must be "upstream" or "downstream"');
  }
  if (!Array.isArray(dg.nodes) || dg.nodes.length === 0) {
    throw new Error("DependencyGraph nodes must be a non-empty array");
  }

  const nodeIds = new Set<string>();
  for (const node of dg.nodes) {
    if (!node || typeof node.id !== "string" || !node.id.trim()) {
      throw new Error("Every DependencyGraph node must have a non-empty string id");
    }
    if (nodeIds.has(node.id)) {
      throw new Error(`Duplicate DependencyGraph node id: "${node.id}"`);
    }
    nodeIds.add(node.id);
    if (typeof node.table !== "string" || !node.table.trim()) {
      throw new Error(`DependencyGraph node "${node.id}" must specify table`);
    }
    if (typeof node.type !== "string" || !node.type.trim()) {
      throw new Error(`DependencyGraph node "${node.id}" must specify type`);
    }
  }

  if (!dg.focus || typeof dg.focus.nodeId !== "string" || !nodeIds.has(dg.focus.nodeId)) {
    throw new Error("DependencyGraph focus.nodeId must reference an existing node id");
  }

  if (!Array.isArray(dg.edges)) {
    throw new Error("DependencyGraph edges must be an array");
  }
  for (const edge of dg.edges) {
    if (!edge || typeof edge.id !== "string" || !edge.id.trim()) {
      throw new Error("Every DependencyGraph edge must have a non-empty string id");
    }
    if (!nodeIds.has(edge.source)) {
      throw new Error(
        `DependencyGraph edge "${edge.id}" references unknown source node "${edge.source}"`
      );
    }
    if (!nodeIds.has(edge.target)) {
      throw new Error(
        `DependencyGraph edge "${edge.id}" references unknown target node "${edge.target}"`
      );
    }
  }

  return dg as DependencyGraphV1;
}

/**
 * Converts a validated `DependencyGraphV1` object into a `SqlflowJSON` payload (`04 §11.6`),
 * applying the built-in topological BFS leveler (`x = level * 300`, `y = indexInLevel * (nodeHeight + 40)`,
 * header `32px`, column `28px`, width `max(160, label.length * 9 + 40)`) whenever nodes omit `position`.
 */
export function convertDependencyGraphToSqlflow(
  input: DependencyGraphV1,
  options?: { useTopologicalBfsPositioning?: boolean }
): SqlflowJSON {
  const dg = validateDependencyGraphV1(input);
  const allHavePositions = dg.nodes.every(
    (n) =>
      n.position &&
      Number.isFinite(n.position.x) &&
      Number.isFinite(n.position.y)
  );

  const computedPositions = new Map<string, { x: number; y: number; width: number; height: number }>();

  if (!allHavePositions && (options?.useTopologicalBfsPositioning ?? true)) {
    // Topological BFS leveler per 04 §11.6 (`Az`)
    const inDegree = new Map<string, number>();
    const outgoing = new Map<string, string[]>();
    for (const n of dg.nodes) {
      inDegree.set(n.id, 0);
      outgoing.set(n.id, []);
    }
    for (const e of dg.edges) {
      outgoing.get(e.source)?.push(e.target);
      inDegree.set(e.target, (inDegree.get(e.target) ?? 0) + 1);
    }

    const levelByNode = new Map<string, number>();
    const queue: string[] = [];
    for (const n of dg.nodes) {
      if ((inDegree.get(n.id) ?? 0) === 0) {
        levelByNode.set(n.id, 0);
        queue.push(n.id);
      }
    }
    if (queue.length === 0 && dg.nodes[0]) {
      levelByNode.set(dg.nodes[0].id, 0);
      queue.push(dg.nodes[0].id);
    }

    while (queue.length > 0) {
      const curr = queue.shift()!;
      const currLevel = levelByNode.get(curr) ?? 0;
      for (const next of outgoing.get(curr) ?? []) {
        const nextLevel = currLevel + 1;
        if ((levelByNode.get(next) ?? -1) < nextLevel && nextLevel < dg.nodes.length) {
          levelByNode.set(next, nextLevel);
          queue.push(next);
        }
      }
    }

    const nodesByLevel = new Map<number, typeof dg.nodes>();
    for (const n of dg.nodes) {
      const lvl = levelByNode.get(n.id) ?? 0;
      const arr = nodesByLevel.get(lvl) ?? [];
      arr.push(n);
      nodesByLevel.set(lvl, arr);
    }

    for (const [lvl, levelNodes] of nodesByLevel.entries()) {
      let runningY = CANVAS_GEOMETRY_TOKENS.paddingTop;
      for (const n of levelNodes) {
        const colCount = n.columns?.length ?? 0;
        const width = Math.max(160, (n.label || n.table).length * 9 + 40);
        const height = 32 + colCount * 28;
        computedPositions.set(n.id, {
          x: CANVAS_GEOMETRY_TOKENS.paddingLeft + lvl * 300,
          y: n.position?.y ?? runningY,
          width,
          height
        });
        runningY += height + 40;
      }
    }
  }

  const dbobjs: DbObject[] = dg.nodes.map((n) => ({
    id: n.id,
    database: n.database,
    schema: n.schema,
    name: n.table,
    displayName: n.label || n.table,
    type: n.type,
    more: Boolean(n.expandable),
    columns: (n.columns ?? []).map((c) => ({
      id: c.id,
      name: c.name,
      dataType: c.dataType,
      primaryKey: c.primaryKey,
      unqiueKey: c.unqiueKey,
      foreignKey: c.foreignKey
    }))
  }));

  const relationships: Relationship[] = [];
  for (const edge of dg.edges) {
    if (edge.columnMappings && edge.columnMappings.length > 0) {
      for (let i = 0; i < edge.columnMappings.length; i++) {
        const mapping = edge.columnMappings[i]!;
        relationships.push({
          id: `${edge.id}_col_${i}`,
          type: mapping.relationType ?? edge.relationType ?? "fdd",
          sources: [
            {
              id: mapping.sourceColumnId,
              parentId: edge.source,
              transforms: mapping.transformCode
                ? [{ type: "expression", code: mapping.transformCode }]
                : undefined
            }
          ],
          target: {
            id: mapping.targetColumnId,
            parentId: edge.target
          }
        });
      }
    } else {
      relationships.push({
        id: edge.id,
        type: edge.relationType ?? "fdd",
        sources: [{ id: edge.source, parentId: edge.source }],
        target: { id: edge.target, parentId: edge.target }
      });
    }
  }

  const tables = dg.nodes.map((n) => {
    const pos = computedPositions.get(n.id);
    const colCount = n.columns?.length ?? 0;
    const width = Math.max(160, (n.label || n.table).length * 9 + 40);
    const height = 22 + colCount * 16 + 2;
    return {
      id: n.id,
      x: n.position?.x ?? pos?.x ?? CANVAS_GEOMETRY_TOKENS.paddingLeft,
      y: n.position?.y ?? pos?.y ?? CANVAS_GEOMETRY_TOKENS.paddingTop,
      width: pos?.width ?? width,
      height,
      type: n.type,
      database: n.database,
      schema: n.schema,
      name: n.table,
      displayName: n.label || n.table,
      more: Boolean(n.expandable),
      columns: (n.columns ?? []).map((c, idx) => ({
        id: c.id,
        x: 0,
        y: 22 + idx * 16,
        width: pos?.width ?? width,
        height: 16,
        name: c.name,
        dataType: c.dataType,
        primaryKey: c.primaryKey,
        unqiueKey: c.unqiueKey,
        foreignKey: c.foreignKey
      }))
    };
  });

  return {
    mode: "global",
    sqlflow: {
      dbobjs,
      relationships
    },
    graph: {
      elements: {
        tables,
        edges: dg.edges.map((e) => ({
          id: e.id,
          sourceId: e.source,
          targetId: e.target,
          type: e.relationType ?? "fdd"
        }))
      }
    }
  };
}

/**
 * Validates, converts, and lays out a `DependencyGraphV1` directly into a `CanvasGraphModel`.
 */
export function layoutDependencyGraphV1(
  input: DependencyGraphV1,
  options?: { layout?: boolean; rankdir?: LayoutRankDir }
): CanvasGraphModel {
  const sqlflowJson = convertDependencyGraphToSqlflow(input, {
    useTopologicalBfsPositioning: options?.layout === false
  });
  return layoutSqlflowGraph(sqlflowJson, {
    layout: options?.layout ?? true,
    rankdir: options?.rankdir ?? "LR"
  });
}
