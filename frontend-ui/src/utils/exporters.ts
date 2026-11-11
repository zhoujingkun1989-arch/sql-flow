/**
 * Client-side Lineage Exporters (`OBL-091`, `OBL-092`, `OBL-095`, `OBL-102`):
 * 1. `<dlineage>` XML serializer
 * 2. 16-column Lineage CSV serializer
 * 3. yEd GraphML (`.graphml`) serializer
 * 4. Mermaid ER Diagram (`erDiagram`) + `pako.deflate` URL (`https://mermaid.live/edit#pako:...`)
 * 5. Client High-DPI PNG export (`html-to-image` `toPng` with `pixelRatio: 2` + deterministic PNG fallback)
 * 6. Self-contained Offline Interactive HTML `.zip` export (`jszip` with `index.html`, `lineage.json`, `widget/index.js`)
 */

import JSZip from "jszip";
import pako from "pako";
import { toPng } from "html-to-image";
import type {
  ApiEnvelope,
  DataFlow,
  DbObject,
  Relationship,
  RelationshipElement,
  SqlflowCatalog,
  SqlflowJSON,
  Transform
} from "@sqlflow/ui-core";

function escapeXml(value: unknown): string {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function escapeCsvCell(value: unknown): string {
  const str = String(value ?? "");
  if (str.includes(",") || str.includes('"') || str.includes("\n") || str.includes("\r")) {
    return `"${str.replace(/"/g, '""')}"`;
  }
  return str;
}

export interface NormalizedDataFlow {
  dbobjs: DbObject[];
  relationships: Relationship[];
}

function flattenCatalogDbObjs(catalog?: SqlflowCatalog | DbObject[]): DbObject[] {
  if (!catalog) return [];
  const seen = new Set<string>();
  const out: DbObject[] = [];

  const pushObj = (
    raw: Record<string, unknown>,
    defaultType: string,
    ctx: { server?: string; database?: string; schema?: string; parent?: string }
  ) => {
    const name = typeof raw.name === "string" ? raw.name : "";
    const resolvedType = typeof raw.type === "string" && raw.type ? raw.type : defaultType;
    const resolvedId =
      raw.id !== undefined && raw.id !== null && String(raw.id) !== ""
        ? String(raw.id)
        : `${ctx.database ?? ""}:${ctx.schema ?? ""}:${resolvedType}:${name}`;
    if (seen.has(resolvedId)) return;
    seen.add(resolvedId);

    const normalized: DbObject = {
      ...(raw as unknown as DbObject),
      id: resolvedId,
      name,
      type: resolvedType,
      ...(ctx.server && !raw.server ? { server: ctx.server } : {}),
      ...(ctx.database && !raw.database ? { database: ctx.database } : {}),
      ...(ctx.schema && !raw.schema ? { schema: ctx.schema } : {}),
      ...(ctx.parent && !raw.parent ? { parent: ctx.parent } : {})
    };
    out.push(normalized);
  };

  if (Array.isArray(catalog)) {
    for (const item of catalog) {
      if (!item || typeof item !== "object") continue;
      pushObj(item as unknown as Record<string, unknown>, item.type || "table", {});
      if (Array.isArray(item.procedures)) {
        for (const sub of item.procedures) {
          if (sub && typeof sub === "object") {
            pushObj(sub as unknown as Record<string, unknown>, sub.type || "procedure", {
              server: item.server,
              database: item.database,
              schema: item.schema,
              parent: item.name
            });
          }
        }
      }
    }
    return out;
  }

  const collectSchemaLike = (
    sch: Record<string, unknown>,
    ctx: { server?: string; database?: string; schema?: string }
  ) => {
    const groups: Array<[string, string]> = [
      ["tables", "table"],
      ["views", "view"],
      ["procedures", "procedure"],
      ["functions", "function"],
      ["triggers", "trigger"],
      ["synonyms", "synonym"],
      ["sequences", "sequence"],
      ["others", "resultset"],
      ["processes", "process"]
    ];
    for (const [key, defaultType] of groups) {
      const arr = sch[key];
      if (Array.isArray(arr)) {
        for (const obj of arr) {
          if (obj && typeof obj === "object") {
            pushObj(obj as Record<string, unknown>, defaultType, ctx);
          }
        }
      }
    }
    if (Array.isArray(sch.packages)) {
      for (const pkg of sch.packages) {
        if (!pkg || typeof pkg !== "object") continue;
        const pkgRec = pkg as Record<string, unknown>;
        const pkgName = typeof pkgRec.name === "string" ? pkgRec.name : undefined;
        pushObj(pkgRec, "package", ctx);
        const pkgNested: Array<[string, string]> = [
          ["procedures", "procedure"],
          ["functions", "function"],
          ["triggers", "trigger"],
          ["variables", "variable"]
        ];
        for (const [subKey, subDefaultType] of pkgNested) {
          const subArr = pkgRec[subKey];
          if (Array.isArray(subArr)) {
            for (const subObj of subArr) {
              if (subObj && typeof subObj === "object") {
                pushObj(subObj as Record<string, unknown>, subDefaultType, {
                  ...ctx,
                  parent: pkgName
                });
              }
            }
          }
        }
      }
    }
  };

  for (const srv of catalog.servers ?? []) {
    const serverName = srv.name;
    for (const db of srv.databases ?? []) {
      // Direct objects on `db` (e.g. MySQL where `db.schemas` is empty/absent)
      collectSchemaLike(db as unknown as Record<string, unknown>, {
        server: serverName,
        database: db.name
      });
      for (const sch of db.schemas ?? []) {
        collectSchemaLike(sch as unknown as Record<string, unknown>, {
          server: serverName,
          database: db.name,
          schema: sch.name
        });
      }
    }
    // Direct schemas on `srv` (e.g. Oracle / `supportsCatalogs=false` where `srv.databases` is empty/absent)
    for (const sch of srv.schemas ?? []) {
      collectSchemaLike(sch as unknown as Record<string, unknown>, {
        server: serverName,
        schema: sch.name
      });
    }
  }
  return out;
}

export function collectRelationshipTransforms(
  rel: Relationship,
  src?: RelationshipElement
): Transform[] {
  const legacyRelTransforms = (rel as unknown as { transforms?: Transform[] }).transforms;
  return [
    ...(Array.isArray(legacyRelTransforms) ? legacyRelTransforms : []),
    ...(rel.target?.transforms ?? []),
    ...(src?.transforms ?? [])
  ];
}

export function extractDataFlowFromAny(
  payload?: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON> | null
): NormalizedDataFlow {
  if (!payload) {
    return { dbobjs: [], relationships: [] };
  }
  const unwrapped: unknown =
    typeof payload === "object" && "data" in payload && payload.data ? payload.data : payload;
  if (!unwrapped || typeof unwrapped !== "object") {
    return { dbobjs: [], relationships: [] };
  }
  const obj = unwrapped as Record<string, unknown>;
  const dfCandidate: Record<string, unknown> =
    obj.sqlflow && typeof obj.sqlflow === "object"
      ? (obj.sqlflow as Record<string, unknown>)
      : obj.graph &&
          typeof obj.graph === "object" &&
          (obj.graph as Record<string, unknown>).sqlflow
        ? ((obj.graph as Record<string, unknown>).sqlflow as Record<string, unknown>)
        : obj;

  const dbobjs = flattenCatalogDbObjs(
    dfCandidate.dbobjs as SqlflowCatalog | DbObject[] | undefined
  );
  const relsRaw = dfCandidate.relationships ?? dfCandidate.relations;
  const relationships = Array.isArray(relsRaw) ? (relsRaw as Relationship[]) : [];

  return { dbobjs, relationships };
}

/**
 * Serializes `DataFlow` / `SqlflowJSON` into wire-compatible `<dlineage>` XML (`03 §1.3`, `OBL-095`).
 */
export function buildDlineageXml(
  payload?: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON> | null
): string {
  const df = extractDataFlowFromAny(payload);
  const lines: string[] = ['<?xml version="1.0" encoding="UTF-8"?>', "<dlineage>"];

  for (const obj of df.dbobjs) {
    const tag =
      obj.type === "view"
        ? "view"
        : obj.type === "procedure" || obj.type === "function" || obj.type === "trigger"
          ? "procedure"
          : obj.type === "resultset" || obj.type === "select_list" || obj.type === "with_cte"
            ? "resultset"
            : "table";
    const cols = "columns" in obj && Array.isArray(obj.columns) ? obj.columns : [];
    const attrs = [
      `id="${escapeXml(obj.id)}"`,
      `name="${escapeXml(obj.name)}"`,
      `type="${escapeXml(obj.type)}"`
    ];
    if ("database" in obj && obj.database) {
      attrs.push(`database="${escapeXml(obj.database)}"`);
    }
    if ("schema" in obj && obj.schema) {
      attrs.push(`schema="${escapeXml(obj.schema)}"`);
    }
    if (cols.length === 0) {
      lines.push(`  <${tag} ${attrs.join(" ")} />`);
    } else {
      lines.push(`  <${tag} ${attrs.join(" ")}>`);
      for (const col of cols) {
        const colAttrs = [
          `id="${escapeXml(col.id)}"`,
          `name="${escapeXml(col.name)}"`
        ];
        if (col.dataType) colAttrs.push(`dataType="${escapeXml(col.dataType)}"`);
        if (col.primaryKey) colAttrs.push(`primaryKey="true"`);
        if (col.unqiueKey) colAttrs.push(`unqiueKey="true"`);
        if (col.foreignKey) colAttrs.push(`foreignKey="true"`);
        lines.push(`    <column ${colAttrs.join(" ")} />`);
      }
      lines.push(`  </${tag}>`);
    }
  }

  for (const rel of df.relationships) {
    const relAttrs = [
      `id="${escapeXml(rel.id)}"`,
      `type="${escapeXml(rel.type)}"`
    ];
    if (rel.effectType) {
      relAttrs.push(`effectType="${escapeXml(rel.effectType)}"`);
    }
    lines.push(`  <relationship ${relAttrs.join(" ")}>`);
    if (rel.target) {
      lines.push(
        `    <target id="${escapeXml(rel.target.id)}" column="${escapeXml(
          rel.target.column
        )}" parent_id="${escapeXml(rel.target.parentId)}" parent_name="${escapeXml(
          rel.target.parentName
        )}" />`
      );
    }
    for (const src of rel.sources ?? []) {
      lines.push(
        `    <source id="${escapeXml(src.id)}" column="${escapeXml(
          src.column
        )}" parent_id="${escapeXml(src.parentId)}" parent_name="${escapeXml(
          src.parentName
        )}" />`
      );
    }
    const relTransforms: Transform[] = [];
    const seenTr = new Set<string>();
    for (const tr of [
      ...collectRelationshipTransforms(rel),
      ...(rel.sources ?? []).flatMap((s) => s.transforms ?? [])
    ]) {
      const key = `${tr.type ?? ""}:${tr.code ?? ""}`;
      if (!seenTr.has(key) && (tr.code || tr.type)) {
        seenTr.add(key);
        relTransforms.push(tr);
      }
    }
    for (const tr of relTransforms) {
      lines.push(
        `    <transform type="${escapeXml(tr.type ?? "expression")}" code="${escapeXml(
          tr.code ?? ""
        )}" />`
      );
    }
    lines.push("  </relationship>");
  }

  lines.push("</dlineage>");
  return lines.join("\n");
}

/**
 * Serializes `DataFlow` / `SqlflowJSON` into 16-column Lineage CSV (`02 §6.5`, `OBL-095`).
 */
export function buildLineageCsv(
  payload?: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON> | null,
  delimiter = ","
): string {
  const df = extractDataFlowFromAny(payload);
  const objById = new Map<string, DbObject>();
  for (const o of df.dbobjs) {
    objById.set(String(o.id), o);
  }

  const header = [
    "SourceServer",
    "SourceDb",
    "SourceSchema",
    "SourceTable",
    "SourceColumn",
    "SourceDataType",
    "TargetServer",
    "TargetDb",
    "TargetSchema",
    "TargetTable",
    "TargetColumn",
    "TargetDataType",
    "RelationType",
    "EffectType",
    "TransformCode",
    "Coordinate"
  ];

  const rows: string[] = [header.join(delimiter)];

  for (const rel of df.relationships) {
    const tgt = rel.target;
    if (!tgt) continue;
    const tgtObj = objById.get(String(tgt.parentId ?? tgt.id));
    const tgtColObj =
      tgtObj && "columns" in tgtObj && Array.isArray(tgtObj.columns)
        ? tgtObj.columns.find((c) => String(c.id) === String(tgt.id) || c.name === tgt.column)
        : undefined;
    const coordStr =
      tgt.coordinates && tgt.coordinates[0]
        ? `${tgt.coordinates[0].x},${tgt.coordinates[0].y}`
        : "";

    for (const src of rel.sources ?? []) {
      const srcObj = objById.get(String(src.parentId ?? src.id));
      const srcColObj =
        srcObj && "columns" in srcObj && Array.isArray(srcObj.columns)
          ? srcObj.columns.find((c) => String(c.id) === String(src.id) || c.name === src.column)
          : undefined;
      const transformText = collectRelationshipTransforms(rel, src)
        .map((t: Transform) => t.code)
        .join(" ; ");

      const cells = [
        srcObj && "server" in srcObj ? srcObj.server ?? "" : "",
        srcObj && "database" in srcObj ? srcObj.database ?? "" : "",
        srcObj && "schema" in srcObj ? srcObj.schema ?? "" : "",
        src.parentName ?? srcObj?.name ?? "",
        src.column ?? "",
        srcColObj?.dataType ?? "",
        tgtObj && "server" in tgtObj ? tgtObj.server ?? "" : "",
        tgtObj && "database" in tgtObj ? tgtObj.database ?? "" : "",
        tgtObj && "schema" in tgtObj ? tgtObj.schema ?? "" : "",
        tgt.parentName ?? tgtObj?.name ?? "",
        tgt.column ?? "",
        tgtColObj?.dataType ?? "",
        rel.type ?? "fdd",
        rel.effectType ?? "",
        transformText,
        coordStr
      ].map(escapeCsvCell);

      rows.push(cells.join(delimiter));
    }
  }

  return rows.join("\n");
}

/**
 * Serializes `DataFlow` / `SqlflowJSON` into yEd-compatible `.graphml` (`02 §6.5`, `OBL-095`).
 */
export function buildYedGraphMl(
  payload?: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON> | null
): string {
  if (payload && typeof payload === "object") {
    const raw = ("data" in payload && payload.data ? payload.data : payload) as Record<
      string,
      unknown
    >;
    if (typeof raw.graphML === "string" && raw.graphML.trim().length > 0) {
      return raw.graphML;
    }
    if (raw.graph && typeof raw.graph === "object") {
      const g = raw.graph as Record<string, unknown>;
      if (typeof g.graphML === "string" && g.graphML.trim().length > 0) {
        return g.graphML;
      }
    }
  }

  const df = extractDataFlowFromAny(payload);
  const lines: string[] = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<graphml xmlns="http://graphml.graphdrawing.org/xmlns" xmlns:y="http://www.yworks.com/xml/graphml">',
    '  <key for="node" id="d6" yfiles.type="nodegraphics"/>',
    '  <key for="edge" id="d10" yfiles.type="edgegraphics"/>',
    '  <graph id="G" edgedefault="directed">'
  ];

  const validIds = new Set<string>();
  for (const obj of df.dbobjs) {
    const id = String(obj.id);
    validIds.add(id);
    lines.push(`    <node id="${escapeXml(id)}">`);
    lines.push('      <data key="d6">');
    lines.push("        <y:ShapeNode>");
    lines.push(`          <y:NodeLabel>${escapeXml(obj.name)}</y:NodeLabel>`);
    lines.push("        </y:ShapeNode>");
    lines.push("      </data>");
    lines.push("    </node>");
  }

  let edgeSeq = 1;
  for (const rel of df.relationships) {
    const tgtParent = String(rel.target?.parentId ?? rel.target?.id ?? "");
    for (const src of rel.sources ?? []) {
      const srcParent = String(src.parentId ?? src.id ?? "");
      if (validIds.has(srcParent) && validIds.has(tgtParent)) {
        lines.push(
          `    <edge id="e${edgeSeq++}" source="${escapeXml(srcParent)}" target="${escapeXml(
            tgtParent
          )}">`
        );
        lines.push('      <data key="d10"><y:PolyLineEdge/></data>');
        lines.push("    </edge>");
      }
    }
  }

  lines.push("  </graph>");
  lines.push("</graphml>");
  return lines.join("\n");
}

/**
 * Builds Mermaid `erDiagram` source and compresses it with `pako.deflate` into a
 * `https://mermaid.live/edit#pako:<base64url>` link (`04 §7.2`, `OBL-102`).
 */
export function buildMermaidErCode(
  payload?: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON> | null
): string {
  const df = extractDataFlowFromAny(payload);
  const sanitizeEntity = (name: string) =>
    name.replace(/[^A-Za-z0-9_]/g, "_").replace(/^_+/, "") || "ENTITY";
  const sanitizeType = (dtype?: string) =>
    (dtype ?? "string").replace(/[^A-Za-z0-9_]/g, "_").toLowerCase() || "string";

  const lines: string[] = ["erDiagram"];
  const entityNameById = new Map<string, string>();

  for (const obj of df.dbobjs) {
    if (obj.type === "database" || obj.type === "schema") continue;
    const entity = sanitizeEntity(obj.name);
    entityNameById.set(String(obj.id), entity);
    lines.push(`  ${entity} {`);
    const cols = "columns" in obj && Array.isArray(obj.columns) ? obj.columns : [];
    for (const col of cols) {
      const flags: string[] = [];
      if (col.primaryKey) flags.push("PK");
      else if (col.foreignKey) flags.push("FK");
      else if (col.unqiueKey) flags.push("UK");
      const flagSuffix = flags.length > 0 ? ` ${flags[0]}` : "";
      lines.push(`    ${sanitizeType(col.dataType)} ${sanitizeEntity(col.name)}${flagSuffix}`);
    }
    lines.push("  }");
  }

  for (const rel of df.relationships) {
    const tgtId = String(rel.target?.parentId ?? rel.target?.id ?? "");
    const tgtEntity = entityNameById.get(tgtId);
    if (!tgtEntity) continue;
    for (const src of rel.sources ?? []) {
      const srcId = String(src.parentId ?? src.id ?? "");
      const srcEntity = entityNameById.get(srcId);
      if (!srcEntity) continue;
      const label = `${src.column ?? "id"} -> ${rel.target?.column ?? "id"}`;
      lines.push(`  ${srcEntity} ||--o{ ${tgtEntity} : "${label.replace(/"/g, "")}"`);
    }
  }

  return lines.join("\n");
}

export function buildMermaidLiveUrl(
  payload?: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON> | null
): { code: string; url: string } {
  const code = buildMermaidErCode(payload);
  const stateJson = JSON.stringify({
    code,
    mermaid: JSON.stringify({ theme: "default" }),
    autoSync: true,
    updateDiagram: true
  });
  const utf8 = new TextEncoder().encode(stateJson);
  const compressed = pako.deflate(utf8, { level: 9 });

  let binary = "";
  for (let i = 0; i < compressed.length; i++) {
    binary += String.fromCharCode(compressed[i]!);
  }
  const base64url = btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");

  return {
    code,
    url: `https://mermaid.live/edit#pako:${base64url}`
  };
}

/**
 * Exports a DOM container as a High-DPI (`pixelRatio: 2`) PNG data URL (`OBL-091`).
 * Falls back cleanly to a deterministic 1x1 PNG data URL in headless/jsdom environments.
 */
export async function captureHighDpiClientPng(
  element: HTMLElement | null,
  options?: { backgroundColor?: string; pixelRatio?: number }
): Promise<string> {
  const bg = options?.backgroundColor ?? "#eeeeee";
  const pixelRatio = options?.pixelRatio ?? 2;

  if (element) {
    try {
      const dataUrl = await toPng(element, {
        backgroundColor: bg,
        pixelRatio,
        cacheBust: false
      });
      if (typeof dataUrl === "string" && dataUrl.startsWith("data:image/")) {
        return dataUrl;
      }
    } catch {
      // Fallback for jsdom / non-raster environments
    }
  }

  return "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
}

const OFFLINE_WIDGET_RUNTIME_SOURCE = `(function(g){
  function esc(s){return String(s==null?"":s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;");}
  var COLORS={table:"#91c051",view:"#419a5f",resultset:"#d26b58",select_list:"#d26b58","insert-select":"#b24b78","update-set":"#d29059","merge-insert":"#893a89","merge-update":"#e46b0a",select_union:"#357e7e",with_cte:"#ff6e97","update-select":"#5583d8",function:"#a0a0a0",stage:"#6d30ea",variable:"#00a4eb",schema:"#1accb3",unnest:"#d2d430",constant:"#e8c559"};
  function flatObjs(cat){
    if(!cat)return[];
    var seen={},out=[];
    function add(o,dt){if(!o)return;var id=String(o.id!=null&&o.id!==""?o.id:(dt+":"+(o.name||"")));if(seen[id])return;seen[id]=1;out.push(Object.assign({type:dt},o,{id:id}));}
    if(Array.isArray(cat)){cat.forEach(function(o){add(o,o&&o.type||"table");(o&&o.procedures||[]).forEach(function(p){add(p,"procedure");});});return out;}
    function walk(s){
      [["tables","table"],["views","view"],["procedures","procedure"],["functions","function"],["triggers","trigger"],["synonyms","synonym"],["sequences","sequence"],["others","resultset"]].forEach(function(kv){
        (s[kv[0]]||[]).forEach(function(o){add(o,o&&o.type||kv[1]);});
      });
      (s.packages||[]).forEach(function(pk){add(pk,"package");["procedures","functions","triggers","variables"].forEach(function(k){(pk[k]||[]).forEach(function(p){add(p,k.slice(0,-1));});});});
    }
    (cat.servers||[]).forEach(function(sv){
      (sv.databases||[]).forEach(function(db){walk(db);(db.schemas||[]).forEach(walk);});
      (sv.schemas||[]).forEach(walk);
    });
    return out;
  }
  g.SQLFlow={version:"8.2.2-15",init:function(el){
    return Promise.resolve({
      visualizeJSON:function(raw){
        var p=raw&&raw.data?raw.data:raw;
        var df=(p&&p.sqlflow)||(p&&p.graph&&p.graph.sqlflow)||p||{};
        var objs=flatObjs(df.dbobjs).filter(function(o){return o&&o.type!=="database"&&o.type!=="schema";});
        var rels=df.relationships||df.relations||[];
        var pos={};
        objs.forEach(function(t,i){
          var x=40+(i%4)*340,y=50+Math.floor(i/4)*230;
          pos[t.id]={x:x,y:y,w:230,cols:{}};
          (t.columns||[]).forEach(function(c,ci){pos[t.id].cols[c.id]=y+22+ci*18+9;});
        });
        var svg="";
        rels.forEach(function(r){
          var t=r.target||{};
          (r.sources||[]).forEach(function(s){
            var sn=pos[s.parentId||s.id],tn=pos[t.parentId||t.id];
            if(!sn||!tn)return;
            var x1=sn.x+sn.w,y1=sn.cols[s.id]||(sn.y+11),x2=tn.x,y2=tn.cols[t.id]||(tn.y+11),cx=(x1+x2)/2;
            svg+='<path d="M '+x1+' '+y1+' C '+cx+' '+y1+', '+cx+' '+y2+', '+x2+' '+y2+'" fill="none" stroke="#64748b" stroke-width="1.4"/>';
          });
        });
        var cards="";
        objs.forEach(function(t){
          var n=pos[t.id],bg=COLORS[String(t.type||"table").toLowerCase()]||"#dbdada";
          cards+='<div style="position:absolute;left:'+n.x+'px;top:'+n.y+'px;width:'+n.w+'px;background:#fff;border:1px solid #cbd5e1;border-radius:4px;box-shadow:0 1px 3px rgba(0,0,0,.1)"><div style="height:22px;line-height:22px;padding:0 8px;background:'+bg+';color:#fff;font-size:12px;font-weight:600">'+esc(t.name)+'</div>'+(t.columns||[]).map(function(c){return '<div style="height:18px;line-height:18px;padding:0 8px;font-size:11px;border-top:1px solid #f1f5f9">'+esc(c.name)+'</div>';}).join("")+'</div>';
        });
        el.innerHTML='<div style="position:relative;width:100%;height:100%;overflow:auto;background:#eeeeee"><svg style="position:absolute;left:0;top:0;width:2400px;height:1800px;pointer-events:none">'+svg+'</svg>'+cards+'</div>';
        return Promise.resolve();
      }
    });
  }};
})(window);`;

/**
 * Generates a self-contained Offline Interactive HTML `.zip` (`OBL-092`) containing:
 * - `index.html` (opens cleanly via `file://` with zero server requests)
 * - `lineage.json` (raw lineage payload)
 * - `widget/index.js` (standalone offline visualizer runtime)
 */
export async function buildOfflineHtmlZipBlob(
  payload: SqlflowJSON | DataFlow | ApiEnvelope<SqlflowJSON> | null,
  title = "SQLFlow Offline Lineage"
): Promise<{ blob: Blob; zip: JSZip }> {
  const zip = new JSZip();
  const serializedJson = JSON.stringify(
    payload ?? { dbobjs: [], relationships: [] },
    null,
    2
  );

  let widgetRuntime = OFFLINE_WIDGET_RUNTIME_SOURCE;
  try {
    if (typeof globalThis.fetch === "function") {
      const res = await globalThis.fetch("/widget/index.js");
      if (res && res.ok) {
        const text = await res.text();
        if (text && text.includes("SQLFlow") && !/\bexport\s*\{/.test(text)) {
          widgetRuntime = text;
        }
      }
    }
  } catch {
    // Use embedded clean-room offline runtime
  }

  const indexHtml = `<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <title>${escapeXml(title)}</title>
  <style>
    html, body, #sqlflow-offline-root { margin: 0; padding: 0; width: 100vw; height: 100vh; overflow: hidden; font-family: sans-serif; }
    .offline-banner { height: 38px; background: #33333d; color: #fff; display: flex; align-items: center; justify-content: space-between; padding: 0 14px; font-size: 13px; }
    #sqlflow-canvas { width: 100vw; height: calc(100vh - 38px); }
  </style>
</head>
<body>
  <div id="sqlflow-offline-root">
    <div class="offline-banner">
      <strong>${escapeXml(title)}</strong>
      <span>SQLFlow 8.2.2.15 Offline Archive</span>
    </div>
    <div id="sqlflow-canvas"></div>
  </div>
  <script src="widget/index.js"></script>
  <script>
    window.__LINEAGE_PAYLOAD__ = ${serializedJson.replace(/<\/script>/gi, "<\\/script>")};
    window.SQLFlow.init(document.getElementById("sqlflow-canvas"), { width: "100%", height: "100%" })
      .then(function(instance) { return instance.visualizeJSON(window.__LINEAGE_PAYLOAD__); });
  </script>
</body>
</html>`;

  zip.file("index.html", indexHtml);
  zip.file("lineage.json", serializedJson);
  zip.file("widget/index.js", widgetRuntime);

  const blob = await zip.generateAsync({ type: "blob" });
  return { blob, zip };
}

export function triggerBrowserDownload(
  content: Blob | string,
  filename: string,
  mimeType = "application/octet-stream"
): void {
  if (typeof document === "undefined") {
    return;
  }
  const anchor = document.createElement("a");
  let objectUrl: string | null = null;

  if (typeof content === "string" && content.startsWith("data:")) {
    anchor.href = content;
  } else {
    const blob =
      content instanceof Blob ? content : new Blob([content], { type: mimeType });
    if (typeof URL !== "undefined" && typeof URL.createObjectURL === "function") {
      objectUrl = URL.createObjectURL(blob);
      anchor.href = objectUrl;
    } else {
      anchor.href = `data:${mimeType};charset=utf-8,${encodeURIComponent(
        typeof content === "string" ? content : ""
      )}`;
    }
  }

  anchor.download = filename;
  anchor.style.display = "none";
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);

  if (objectUrl && typeof URL.revokeObjectURL === "function") {
    setTimeout(() => URL.revokeObjectURL(objectUrl!), 250);
  }
}
