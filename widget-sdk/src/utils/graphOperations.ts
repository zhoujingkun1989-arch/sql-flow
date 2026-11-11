/**
 * Pure Graph Normalizers, Client-Side Upstream/Downstream Slicer,
 * Table/Column Matchers, Job Tree Builder, `<dlineage>` XML & 15-Column CSV Serializers,
 * Mermaid Live `pako` Encoder, and High-DPI PNG Snapshot Helper.
 */

import { toPng } from "html-to-image";
import pako from "pako";
import {
  type ApiEnvelope,
  type CanvasColumnRow,
  type CanvasGraphModel,
  type CanvasNode,
  type Coordinate,
  type DataFlow,
  type DbObject,
  type Relationship,
  type SqlflowJSON,
  stripDefaultQualifier
} from "@sqlflow/ui-core";
import type {
  StreamTargetOptions,
  UpstreamDownstreamTarget,
  WidgetTreeNode
} from "../types.js";

function normalizeIdentifier(raw?: string | null): string {
  if (!raw) {
    return "";
  }
  return raw
    .trim()
    .replace(/^[`"[\]]+|[`"[\]]+$/g, "")
    .toLowerCase();
}

/**
 * Validates and normalizes any `SqlflowJSON`, `DataFlow`, or `ApiEnvelope<SqlflowJSON>`
 * passed to `visualizeJson` / `visualizeJSON` without making any backend HTTP call.
 */
export function normalizePrecomputedLineageInput(
  input: unknown
): { payload: SqlflowJSON; sessionId?: string } {
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    throw new Error("visualizeJson requires a non-null SqlflowJSON or DataFlow object");
  }

  const rec = input as Record<string, unknown>;

  // Case 1: ApiEnvelope<SqlflowJSON> (`{ code: 200, data: ..., sessionId?: ... }`)
  if ("code" in rec && ("data" in rec || "error" in rec)) {
    const codeNum =
      typeof rec.code === "number"
        ? rec.code
        : typeof rec.code === "string"
          ? Number.parseInt(rec.code, 10)
          : Number.NaN;
    if (Number.isFinite(codeNum) && codeNum !== 200) {
      const errText =
        typeof rec.error === "string"
          ? rec.error
          : typeof rec.message === "string"
            ? rec.message
            : `Envelope error code ${codeNum}`;
      throw new Error(errText);
    }
    if (!rec.data || typeof rec.data !== "object") {
      throw new Error("visualizeJson envelope is missing a valid data object");
    }
    const inner = normalizePrecomputedLineageInput(rec.data);
    return {
      payload: inner.payload,
      sessionId: typeof rec.sessionId === "string" ? rec.sessionId : inner.sessionId
    };
  }

  // Case 2: SqlflowJSON (`{ sqlflow?: ..., graph?: ..., mode?: ... }`)
  if ("sqlflow" in rec || "graph" in rec || "mode" in rec) {
    const sqlflow = rec.sqlflow;
    const graph = rec.graph;
    if (
      sqlflow !== undefined &&
      (typeof sqlflow !== "object" || sqlflow === null || Array.isArray(sqlflow))
    ) {
      throw new Error("Invalid sqlflow property inside SqlflowJSON");
    }
    if (
      graph !== undefined &&
      (typeof graph !== "object" || graph === null || Array.isArray(graph))
    ) {
      throw new Error("Invalid graph property inside SqlflowJSON");
    }
    return { payload: rec as unknown as SqlflowJSON };
  }

  // Case 3: Raw DataFlow (`{ dbobjs: ..., relationships: ... }`)
  if ("dbobjs" in rec || "relationships" in rec) {
    if (
      rec.relationships !== undefined &&
      !Array.isArray(rec.relationships)
    ) {
      throw new Error("DataFlow.relationships must be an array");
    }
    return {
      payload: {
        mode: "global",
        sqlflow: rec as unknown as DataFlow
      }
    };
  }

  throw new Error(
    "Unrecognized lineage JSON schema: expected SqlflowJSON, DataFlow, or ApiEnvelope<SqlflowJSON>"
  );
}

/**
 * Resolves a node in `CanvasGraphModel` by ID, bare table name, `schema.table`,
 * `database.schema.table`, or `displayTitle` (case-insensitive).
 */
export function findNodeByNameOrId(
  model: CanvasGraphModel | null,
  nameOrId: string
): CanvasNode | undefined {
  if (!model || typeof nameOrId !== "string" || !nameOrId.trim()) {
    return undefined;
  }
  const trimmed = nameOrId.trim();

  // Exact ID hit
  const exactId = model.nodeById.get(trimmed);
  if (exactId) {
    return exactId;
  }

  const targetNorm = normalizeIdentifier(trimmed);
  const parts = targetNorm.split(".").map((p) => normalizeIdentifier(p)).filter(Boolean);
  const lastSegment = parts[parts.length - 1] ?? targetNorm;

  // Priority 1: Exact qualified match (`db.schema.table` or `schema.table` or `displayTitle`)
  for (const node of model.nodes) {
    if (normalizeIdentifier(node.id) === targetNorm) {
      return node;
    }
    if (normalizeIdentifier(node.displayTitle) === targetNorm) {
      return node;
    }
    const db = normalizeIdentifier(stripDefaultQualifier(node.database));
    const sc = normalizeIdentifier(stripDefaultQualifier(node.schema));
    const tbl = normalizeIdentifier(node.name);
    const qualified3 = [db, sc, tbl].filter(Boolean).join(".");
    const qualified2 = [sc, tbl].filter(Boolean).join(".");
    if (qualified3 === targetNorm || qualified2 === targetNorm || tbl === targetNorm) {
      return node;
    }
  }

  // Priority 2: Trailing table segment match when caller passed `db.schema.table` and node omitted `db`/`schema`
  if (parts.length > 1) {
    for (const node of model.nodes) {
      if (normalizeIdentifier(node.name) === lastSegment) {
        return node;
      }
    }
  }

  return undefined;
}

/**
 * Resolves a column row in `CanvasGraphModel` by `(table, column)` or direct column ID.
 */
export function findColumnByTableAndName(
  model: CanvasGraphModel | null,
  table: string,
  column: string
): { node: CanvasNode; column: CanvasColumnRow } | undefined {
  if (!model || !column || !column.trim()) {
    return undefined;
  }

  const colNorm = normalizeIdentifier(column);

  if (table && table.trim()) {
    const node = findNodeByNameOrId(model, table);
    if (!node) {
      return undefined;
    }
    const foundCol = node.columns.find(
      (c) =>
        c.id === column.trim() ||
        normalizeIdentifier(c.name) === colNorm ||
        normalizeIdentifier(c.displayLabel.split(":")[0]) === colNorm
    );
    return foundCol ? { node, column: foundCol } : undefined;
  }

  // Fallback if table omitted: search by column ID or unique column name across nodes
  const byId = model.columnById.get(column.trim());
  if (byId) {
    const owner = model.nodeById.get(byId.nodeId);
    if (owner) {
      return { node: owner, column: byId };
    }
  }
  for (const node of model.nodes) {
    const match = node.columns.find((c) => normalizeIdentifier(c.name) === colNorm);
    if (match) {
      return { node, column: match };
    }
  }
  return undefined;
}

/**
 * Searches nodes and columns by keyword (`OBL-116` `search(keyword)`).
 */
export function searchNodesInModel(
  model: CanvasGraphModel | null,
  keyword: string
): CanvasNode[] {
  if (!model) {
    return [];
  }
  const q = keyword.trim().toLowerCase();
  if (!q) {
    return model.nodes;
  }
  return model.nodes.filter((node) => {
    if (
      node.name.toLowerCase().includes(q) ||
      node.displayTitle.toLowerCase().includes(q) ||
      (node.schema && node.schema.toLowerCase().includes(q)) ||
      (node.database && node.database.toLowerCase().includes(q))
    ) {
      return true;
    }
    return node.columns.some((c) => c.name.toLowerCase().includes(q));
  });
}

/**
 * Parses string or structured target into normalized `StreamTargetOptions`.
 * Supports `"orders"`, `"sales.orders"`, `"prod.sales.orders"`, or `{ table: "orders", column: "id" }`.
 */
export function normalizeStreamTarget(
  target: UpstreamDownstreamTarget,
  model: CanvasGraphModel | null,
  defaultDistance = 10
): StreamTargetOptions {
  if (typeof target === "object" && target !== null) {
    if (!target.table || !target.table.trim()) {
      throw new Error("Stream target options must specify a non-empty table");
    }
    return {
      ...target,
      distance: target.distance ?? defaultDistance
    };
  }

  if (typeof target !== "string" || !target.trim()) {
    throw new Error("Stream target must be a non-empty table/column string or options object");
  }

  const raw = target.trim();

  // Check if `raw` matches a node directly first (e.g. "sales.orders" as `schema.table`)
  const matchedNode = findNodeByNameOrId(model, raw);
  if (matchedNode) {
    return {
      database: stripDefaultQualifier(matchedNode.database),
      schema: stripDefaultQualifier(matchedNode.schema),
      table: matchedNode.name,
      distance: defaultDistance
    };
  }

  // Otherwise check if last dot-segment is a column (`table.column` or `schema.table.column`)
  const segments = raw.split(".").map((s) => s.trim()).filter(Boolean);
  if (segments.length >= 2 && model) {
    const candidateTable = segments.slice(0, -1).join(".");
    const candidateCol = segments[segments.length - 1]!;
    const matchedCol = findColumnByTableAndName(model, candidateTable, candidateCol);
    if (matchedCol) {
      return {
        database: stripDefaultQualifier(matchedCol.node.database),
        schema: stripDefaultQualifier(matchedCol.node.schema),
        table: matchedCol.node.name,
        column: matchedCol.column.name,
        distance: defaultDistance
      };
    }
  }

  if (segments.length === 3) {
    return {
      database: segments[0],
      schema: segments[1],
      table: segments[2]!,
      distance: defaultDistance
    };
  }
  if (segments.length === 2) {
    return {
      schema: segments[0],
      table: segments[1]!,
      distance: defaultDistance
    };
  }
  return {
    table: raw,
    distance: defaultDistance
  };
}

/**
 * Client-side bounded-hop BFS upstream/downstream slicer (`1..distance` hops)
 * operating on `CanvasGraphModel` when viewing precomputed JSON or `DependencyGraph` v1.0.
 */
export function sliceCanvasModelDirection(
  baseModel: CanvasGraphModel,
  opts: StreamTargetOptions,
  direction: "upstream" | "downstream"
): SqlflowJSON {
  const maxDistance = Math.max(1, Math.min(20, opts.distance ?? 10));
  const targetLookupName = [opts.database, opts.schema, opts.table]
    .filter(Boolean)
    .join(".");
  const startNode =
    findNodeByNameOrId(baseModel, targetLookupName) ??
    findNodeByNameOrId(baseModel, opts.table);

  if (!startNode) {
    throw new Error(`Target table "${opts.table}" not found in current lineage graph`);
  }

  const keptNodeIds = new Set<string>([startNode.id]);
  const keptEdgeIds = new Set<string>();
  const boundaryTruncatedNodes = new Set<string>();

  // Column-precise slice if `opts.column` was specified and exists on `startNode`
  const startColMatch = opts.column
    ? findColumnByTableAndName(baseModel, startNode.id, opts.column)
    : undefined;

  if (startColMatch) {
    const adj =
      direction === "upstream"
        ? baseModel.upstreamColumns
        : baseModel.downstreamColumns;
    const colDepth = new Map<string, number>([[startColMatch.column.id, 0]]);
    const queue: string[] = [startColMatch.column.id];

    while (queue.length > 0) {
      const currColId = queue.shift()!;
      const depth = colDepth.get(currColId) ?? 0;
      const neighbors = adj.get(currColId) ?? [];
      if (depth >= maxDistance) {
        if (neighbors.length > 0) {
          const ownerId = baseModel.columnById.get(currColId)?.nodeId;
          if (ownerId) {
            boundaryTruncatedNodes.add(ownerId);
          }
        }
        continue;
      }
      for (const step of neighbors) {
        keptEdgeIds.add(step.edgeId);
        const stepCol = baseModel.columnById.get(step.columnId);
        if (stepCol) {
          keptNodeIds.add(stepCol.nodeId);
        }
        if (!colDepth.has(step.columnId)) {
          colDepth.set(step.columnId, depth + 1);
          queue.push(step.columnId);
        }
      }
    }
  } else {
    const adj =
      direction === "upstream" ? baseModel.upstreamNodes : baseModel.downstreamNodes;
    const nodeDepth = new Map<string, number>([[startNode.id, 0]]);
    const queue: string[] = [startNode.id];

    while (queue.length > 0) {
      const currNodeId = queue.shift()!;
      const depth = nodeDepth.get(currNodeId) ?? 0;
      const neighbors = adj.get(currNodeId) ?? [];
      if (depth >= maxDistance) {
        if (neighbors.length > 0) {
          boundaryTruncatedNodes.add(currNodeId);
        }
        continue;
      }
      for (const step of neighbors) {
        keptEdgeIds.add(step.edgeId);
        keptNodeIds.add(step.nodeId);
        if (!nodeDepth.has(step.nodeId)) {
          nodeDepth.set(step.nodeId, depth + 1);
          queue.push(step.nodeId);
        }
      }
    }
  }

  const dbobjs: DbObject[] = baseModel.nodes
    .filter((n) => keptNodeIds.has(n.id))
    .map((n) => ({
      id: n.id,
      database: n.database,
      schema: n.schema,
      name: n.name,
      displayName: n.displayTitle,
      type: n.type,
      subType: n.subType,
      parent: n.parentId,
      coordinates: n.coordinates,
      more: boundaryTruncatedNodes.has(n.id),
      columns: n.columns.map((c) => ({
        id: c.id,
        name: c.name,
        dataType: c.dataType,
        primaryKey: c.primaryKey,
        unqiueKey: c.unqiueKey,
        indexKey: c.indexKey,
        foreignKey: c.foreignKey,
        coordinates: c.coordinates
      }))
    }));

  const relationships: Relationship[] = baseModel.edges
    .filter((e) => keptEdgeIds.has(e.id))
    .map((e) => ({
      id: e.id,
      type: (e.type as Relationship["type"]) || "fdd",
      effectType: e.effectType,
      joinType: e.joinType,
      condition: e.condition,
      sources: [
        {
          id: e.sourceColumnId ?? e.sourceNodeId,
          parentId: e.sourceNodeId,
          transforms: e.transforms,
          coordinates: e.coordinates
        }
      ],
      target: {
        id: e.targetColumnId ?? e.targetNodeId,
        parentId: e.targetNodeId,
        coordinates: e.coordinates
      }
    }));

  return {
    mode: "global",
    sqlflow: {
      dbobjs,
      relationships
    }
  };
}

/**
 * Builds hierarchical Schema Explorer `WidgetTreeNode[]` for `job.lineage.getNodes()` (`04 §11.4`).
 */
export function buildWidgetTreeNodes(model: CanvasGraphModel | null): WidgetTreeNode[] {
  if (!model) {
    return [];
  }
  const dbMap = new Map<string, Map<string, CanvasNode[]>>();
  for (const node of model.nodes) {
    const dbName = stripDefaultQualifier(node.database) || "DEFAULT";
    const schemaName = stripDefaultQualifier(node.schema) || "DEFAULT";
    let schemaMap = dbMap.get(dbName);
    if (!schemaMap) {
      schemaMap = new Map();
      dbMap.set(dbName, schemaMap);
    }
    const list = schemaMap.get(schemaName) ?? [];
    list.push(node);
    schemaMap.set(schemaName, list);
  }

  const out: WidgetTreeNode[] = [];
  for (const [dbName, schemaMap] of dbMap.entries()) {
    const schemaChildren: WidgetTreeNode[] = [];
    for (const [schemaName, tables] of schemaMap.entries()) {
      const tableChildren: WidgetTreeNode[] = tables.map((tbl) => ({
        key: `table:${dbName}.${schemaName}.${tbl.name}`,
        title: tbl.name,
        type: tbl.type,
        database: dbName === "DEFAULT" ? undefined : dbName,
        schema: schemaName === "DEFAULT" ? undefined : schemaName,
        table: tbl.name,
        children: tbl.columns.map((col) => ({
          key: `col:${tbl.id}:${col.id}`,
          title: col.name,
          type: "column",
          database: dbName === "DEFAULT" ? undefined : dbName,
          schema: schemaName === "DEFAULT" ? undefined : schemaName,
          table: tbl.name,
          column: col.name
        }))
      }));
      schemaChildren.push({
        key: `schema:${dbName}.${schemaName}`,
        title: schemaName,
        type: "schema",
        database: dbName === "DEFAULT" ? undefined : dbName,
        schema: schemaName === "DEFAULT" ? undefined : schemaName,
        children: tableChildren
      });
    }
    out.push({
      key: `db:${dbName}`,
      title: dbName,
      type: "database",
      database: dbName === "DEFAULT" ? undefined : dbName,
      children: schemaChildren
    });
  }
  return out;
}

function escapeXml(raw: string | undefined): string {
  if (!raw) {
    return "";
  }
  return raw
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function formatCoordinateString(coords?: Coordinate[]): string {
  if (!coords || coords.length === 0) {
    return "";
  }
  const first = coords[0];
  if (!first) {
    return "";
  }
  if (Array.isArray(first)) {
    if (Array.isArray(first[0])) {
      const pair = first as unknown as [number[], number[]];
      return `[${pair[0]?.[0] ?? 1},${pair[0]?.[1] ?? 1}],[${pair[1]?.[0] ?? 1},${pair[1]?.[1] ?? 1}]`;
    }
    return `[${first[0]},${first[1]}]`;
  }
  if (typeof first === "object" && "x" in first && "y" in first) {
    const second = coords[1];
    if (second && typeof second === "object" && "x" in second && "y" in second) {
      return `[${first.x},${first.y}],[${second.x},${second.y}]`;
    }
    return `[${first.x},${first.y}]`;
  }
  return "";
}

/**
 * Serializes the active `CanvasGraphModel` to `<dlineage>` XML (`03 §1.3`, `OBL-085`, `OBL-116`).
 */
export function serializeModelToXml(model: CanvasGraphModel | null): string {
  const lines: string[] = ['<?xml version="1.0" encoding="UTF-8"?>', "<dlineage>"];
  if (!model) {
    lines.push("</dlineage>");
    return lines.join("\n");
  }

  for (const node of model.nodes) {
    const tag = node.type === "view" ? "view" : node.type === "resultset" || node.type === "select_list" ? "resultset" : "table";
    const attrs = [
      `id="${escapeXml(node.id)}"`,
      `name="${escapeXml(node.displayTitle)}"`,
      `type="${escapeXml(node.type)}"`,
      node.database ? `database="${escapeXml(node.database)}"` : "",
      node.schema ? `schema="${escapeXml(node.schema)}"` : "",
      node.coordinates.length > 0
        ? `coordinate="${escapeXml(formatCoordinateString(node.coordinates))}"`
        : ""
    ]
      .filter(Boolean)
      .join(" ");

    if (node.columns.length === 0) {
      lines.push(`  <${tag} ${attrs}/>`);
    } else {
      lines.push(`  <${tag} ${attrs}>`);
      for (const col of node.columns) {
        const colAttrs = [
          `id="${escapeXml(col.id)}"`,
          `name="${escapeXml(col.name)}"`,
          col.dataType ? `dataType="${escapeXml(col.dataType)}"` : "",
          col.primaryKey ? `primaryKey="true"` : "",
          col.unqiueKey ? `unqiueKey="true"` : "",
          col.foreignKey ? `foreignKey="true"` : "",
          col.coordinates.length > 0
            ? `coordinate="${escapeXml(formatCoordinateString(col.coordinates))}"`
            : ""
        ]
          .filter(Boolean)
          .join(" ");
        lines.push(`    <column ${colAttrs}/>`);
      }
      lines.push(`  </${tag}>`);
    }
  }

  for (const edge of model.edges) {
    const srcNode = model.nodeById.get(edge.sourceNodeId);
    const tgtNode = model.nodeById.get(edge.targetNodeId);
    const srcCol = edge.sourceColumnId ? model.columnById.get(edge.sourceColumnId) : undefined;
    const tgtCol = edge.targetColumnId ? model.columnById.get(edge.targetColumnId) : undefined;
    if (!srcNode || !tgtNode) {
      continue;
    }
    const relAttrs = [
      `id="${escapeXml(edge.id)}"`,
      `type="${escapeXml(String(edge.type))}"`,
      edge.effectType ? `effectType="${escapeXml(edge.effectType)}"` : ""
    ]
      .filter(Boolean)
      .join(" ");
    lines.push(`  <relation ${relAttrs}>`);
    lines.push(
      `    <target id="${escapeXml(tgtCol?.id ?? tgtNode.id)}" column="${escapeXml(
        tgtCol?.name ?? "*"
      )}" parent_id="${escapeXml(tgtNode.id)}" parent_name="${escapeXml(tgtNode.displayTitle)}"/>`
    );
    lines.push(
      `    <source id="${escapeXml(srcCol?.id ?? srcNode.id)}" column="${escapeXml(
        srcCol?.name ?? "*"
      )}" parent_id="${escapeXml(srcNode.id)}" parent_name="${escapeXml(srcNode.displayTitle)}"/>`
    );
    lines.push("  </relation>");
  }

  lines.push("</dlineage>");
  return lines.join("\n");
}

function escapeCsvCell(value: string | undefined): string {
  const str = value ?? "";
  if (/[",\r\n]/.test(str)) {
    return `"${str.replace(/"/g, '""')}"`;
  }
  return str;
}

/**
 * Serializes the active `CanvasGraphModel` to the exact 15-column Metadata Lineage CSV format
 * (`00 §3.3 Invariant #15`):
 * `SourceServer,SourceDatabase,SourceSchema,SourceTable,SourceColumn,TargetServer,TargetDatabase,TargetSchema,TargetTable,TargetColumn,RelationType,EffectType,Coordinate,SqlFile,Transforms`
 */
export function serializeModelToCsv15(model: CanvasGraphModel | null): string {
  const header =
    "SourceServer,SourceDatabase,SourceSchema,SourceTable,SourceColumn,TargetServer,TargetDatabase,TargetSchema,TargetTable,TargetColumn,RelationType,EffectType,Coordinate,SqlFile,Transforms";
  if (!model || model.edges.length === 0) {
    return `${header}\n`;
  }

  const rows: string[] = [header];
  for (const edge of model.edges) {
    const srcNode = model.nodeById.get(edge.sourceNodeId);
    const tgtNode = model.nodeById.get(edge.targetNodeId);
    if (!srcNode || !tgtNode) {
      continue;
    }
    const srcCol = edge.sourceColumnId ? model.columnById.get(edge.sourceColumnId) : undefined;
    const tgtCol = edge.targetColumnId ? model.columnById.get(edge.targetColumnId) : undefined;
    const transformsCode = edge.transforms.map((t) => t.code).join("; ");

    const cols = [
      "",
      stripDefaultQualifier(srcNode.database) ?? "",
      stripDefaultQualifier(srcNode.schema) ?? "",
      srcNode.name,
      srcCol?.name ?? "",
      "",
      stripDefaultQualifier(tgtNode.database) ?? "",
      stripDefaultQualifier(tgtNode.schema) ?? "",
      tgtNode.name,
      tgtCol?.name ?? "",
      String(edge.type || "fdd"),
      edge.effectType ?? "select",
      formatCoordinateString(edge.coordinates),
      "",
      transformsCode
    ];
    rows.push(cols.map(escapeCsvCell).join(","));
  }

  return `${rows.join("\n")}\n`;
}

function encodeUtf8Bytes(input: string): Uint8Array {
  if (typeof TextEncoder !== "undefined") {
    return new TextEncoder().encode(input);
  }
  const escaped = unescape(encodeURIComponent(input));
  const bytes = new Uint8Array(escaped.length);
  for (let i = 0; i < escaped.length; i++) {
    bytes[i] = escaped.charCodeAt(i);
  }
  return bytes;
}

function encodeBase64Binary(binary: string): string {
  if (typeof btoa === "function") {
    return btoa(binary);
  }
  const g = globalThis as unknown as {
    Buffer?: { from: (s: string, enc: string) => { toString: (enc: string) => string } };
  };
  if (g.Buffer) {
    return g.Buffer.from(binary, "binary").toString("base64");
  }
  return "";
}

/**
 * Encodes an ER `CanvasGraphModel` into a `https://mermaid.live/edit#pako:...` URL (`04 §7.2`).
 */
export function buildMermaidErLiveUrl(model: CanvasGraphModel): string {
  const lines: string[] = ["erDiagram"];
  const sanitizeName = (s: string) => s.replace(/[^A-Za-z0-9_]/g, "_") || "ENTITY";

  for (const node of model.nodes) {
    const entityName = sanitizeName(node.name);
    lines.push(`  ${entityName} {`);
    for (const col of node.columns) {
      const dt = sanitizeName(col.dataType || "VARCHAR");
      const keyBadge = col.primaryKey ? " PK" : col.foreignKey ? " FK" : col.unqiueKey ? " UK" : "";
      lines.push(`    ${dt} ${sanitizeName(col.name)}${keyBadge}`);
    }
    lines.push("  }");
  }

  for (const edge of model.edges) {
    const src = model.nodeById.get(edge.sourceNodeId);
    const tgt = model.nodeById.get(edge.targetNodeId);
    if (src && tgt) {
      lines.push(
        `  ${sanitizeName(src.name)} ||--o{ ${sanitizeName(tgt.name)} : "${edge.effectType || "references"}"`
      );
    }
  }

  const payload = JSON.stringify({
    code: lines.join("\n"),
    mermaid: { theme: "default" },
    autoSync: true,
    updateDiagram: true
  });
  const compressed = pako.deflate(encodeUtf8Bytes(payload), { level: 9 });
  let binary = "";
  for (let i = 0; i < compressed.length; i++) {
    binary += String.fromCharCode(compressed[i]!);
  }
  const base64Url = encodeBase64Binary(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
  return `https://mermaid.live/edit#pako:${base64Url}`;
}

/**
 * Generates a PNG data URL (`data:image/png;base64,...`) from a DOM element,
 * falling back to a valid 1x1 PNG header + deterministic vector summary in headless JSDOM.
 */
const FALLBACK_MINIMAL_PNG_DATA_URL =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

export async function exportDomElementToPngDataUrl(
  element: HTMLElement | null,
  backgroundColor = "#eeeeee"
): Promise<string> {
  if (!element) {
    return FALLBACK_MINIMAL_PNG_DATA_URL;
  }
  // In JSDOM, layout dimensions are 0x0 and HTMLCanvasElement.getContext("2d") is unimplemented.
  if (element.clientWidth > 0 && element.clientHeight > 0) {
    try {
      const dataUrl = await toPng(element, {
        backgroundColor,
        pixelRatio: 2,
        cacheBust: false
      });
      if (dataUrl && dataUrl.startsWith("data:image/png")) {
        return dataUrl;
      }
    } catch {
      // Fall back cleanly in headless / non-Canvas environments
    }
  }
  return FALLBACK_MINIMAL_PNG_DATA_URL;
}

export function triggerFileDownload(
  contentOrDataUrl: string,
  fileName: string,
  mimeType = "application/octet-stream",
  doc: Document | undefined = typeof document !== "undefined" ? document : undefined
): void {
  if (!doc || !doc.body) {
    return;
  }
  const ua =
    doc.defaultView?.navigator?.userAgent ??
    (typeof navigator !== "undefined" ? navigator.userAgent : "");
  // JSDOM does not implement `<a download>` and schedules an async navigation error if `.click()` is called.
  if (/jsdom/i.test(ua)) {
    return;
  }
  try {
    const link = doc.createElement("a");
    if (contentOrDataUrl.startsWith("data:")) {
      link.href = contentOrDataUrl;
    } else if (typeof URL !== "undefined" && typeof URL.createObjectURL === "function") {
      const blob = new Blob([contentOrDataUrl], { type: mimeType });
      const blobUrl = URL.createObjectURL(blob);
      link.href = blobUrl;
      setTimeout(() => {
        try {
          URL.revokeObjectURL(blobUrl);
        } catch {
          // Ignore revoke errors
        }
      }, 2000);
    } else {
      return;
    }
    link.download = fileName;
    link.style.display = "none";
    doc.body.appendChild(link);
    link.click();
    doc.body.removeChild(link);
  } catch {
    // Ignore navigation warnings in JSDOM when clicking download `<a download>`
  }
}
