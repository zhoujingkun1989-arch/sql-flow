/**
 * Clean-room standalone offline widget runtime (`widget/index.js`)
 * bundled into self-contained Offline Interactive HTML `.zip` exports (`OBL-092`).
 */
(function (global) {
  "use strict";

  function escapeHtml(text) {
    return String(text == null ? "" : text)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function flattenCatalog(cat) {
    if (!cat) return [];
    var seen = {};
    var out = [];
    function push(o, defaultType) {
      if (!o) return;
      var id = String(o.id != null && o.id !== "" ? o.id : defaultType + ":" + (o.name || ""));
      if (seen[id]) return;
      seen[id] = true;
      out.push(Object.assign({ type: defaultType }, o, { id: id }));
    }
    if (Array.isArray(cat)) {
      cat.forEach(function (o) {
        push(o, (o && o.type) || "table");
        if (o && Array.isArray(o.procedures)) {
          o.procedures.forEach(function (p) {
            push(p, (p && p.type) || "procedure");
          });
        }
      });
      return out;
    }
    function walkSchemaLike(s) {
      if (!s) return;
      [
        ["tables", "table"],
        ["views", "view"],
        ["procedures", "procedure"],
        ["functions", "function"],
        ["triggers", "trigger"],
        ["synonyms", "synonym"],
        ["sequences", "sequence"],
        ["others", "resultset"]
      ].forEach(function (pair) {
        var arr = s[pair[0]];
        if (Array.isArray(arr)) {
          arr.forEach(function (o) {
            push(o, (o && o.type) || pair[1]);
          });
        }
      });
      if (Array.isArray(s.packages)) {
        s.packages.forEach(function (pkg) {
          push(pkg, "package");
          ["procedures", "functions", "triggers", "variables"].forEach(function (subKey) {
            if (Array.isArray(pkg[subKey])) {
              pkg[subKey].forEach(function (p) {
                push(p, (p && p.type) || subKey.slice(0, -1));
              });
            }
          });
        });
      }
    }
    (cat.servers || []).forEach(function (srv) {
      (srv.databases || []).forEach(function (db) {
        walkSchemaLike(db);
        (db.schemas || []).forEach(walkSchemaLike);
      });
      (srv.schemas || []).forEach(walkSchemaLike);
    });
    return out;
  }

  function extractObjectsAndRelations(raw) {
    var payload = raw && raw.data ? raw.data : raw;
    var df =
      (payload && payload.sqlflow) ||
      (payload && payload.graph && payload.graph.sqlflow) ||
      payload ||
      {};
    var dbobjs = flattenCatalog(df.dbobjs);
    var rels = df.relationships || df.relations;
    var relations = Array.isArray(rels) ? rels : [];
    return { dbobjs: dbobjs, relations: relations };
  }

  var DBO_COLORS = {
    table: "#91c051",
    view: "#419a5f",
    resultset: "#d26b58",
    select_list: "#d26b58",
    "insert-select": "#b24b78",
    "update-set": "#d29059",
    "merge-insert": "#893a89",
    "merge-update": "#e46b0a",
    select_union: "#357e7e",
    with_cte: "#ff6e97",
    "update-select": "#5583d8",
    function: "#a0a0a0",
    stage: "#6d30ea",
    variable: "#00a4eb",
    schema: "#1accb3",
    unnest: "#d2d430",
    constant: "#e8c559"
  };

  function createInstance(container, options) {
    var state = {
      container: container,
      options: options || {},
      data: null
    };

    function render(data) {
      state.data = data;
      var parsed = extractObjectsAndRelations(data);
      var tables = parsed.dbobjs.filter(function (o) {
        return o && o.type !== "database" && o.type !== "schema";
      });
      var positions = {};
      var colWidth = 240;
      var colGap = 110;
      var html =
        '<div style="position:relative;width:100%;height:100%;overflow:auto;background:#eeeeee;font-family:sans-serif;">';
      var svgEdges = "";

      tables.forEach(function (tbl, idx) {
        var colIdx = idx % 4;
        var rowIdx = Math.floor(idx / 4);
        var x = 40 + colIdx * (colWidth + colGap);
        var y = 50 + rowIdx * 220;
        positions[String(tbl.id)] = { x: x, y: y, width: colWidth, columns: {} };
        var cols = Array.isArray(tbl.columns) ? tbl.columns : [];
        cols.forEach(function (c, cIdx) {
          positions[String(tbl.id)].columns[String(c.id)] = {
            x: x,
            y: y + 22 + cIdx * 18 + 9
          };
        });
      });

      parsed.relations.forEach(function (rel) {
        var tgt = rel.target || {};
        var sources = Array.isArray(rel.sources) ? rel.sources : [];
        sources.forEach(function (src) {
          var sNode = positions[String(src.parentId || src.id)];
          var tNode = positions[String(tgt.parentId || tgt.id)];
          if (!sNode || !tNode) return;
          var sCol = sNode.columns[String(src.id)];
          var tCol = tNode.columns[String(tgt.id)];
          var x1 = sNode.x + sNode.width;
          var y1 = sCol ? sCol.y : sNode.y + 11;
          var x2 = tNode.x;
          var y2 = tCol ? tCol.y : tNode.y + 11;
          var cx = (x1 + x2) / 2;
          var dash = rel.type === "fdr" || rel.type === "frd" ? ' stroke-dasharray="4 2"' : "";
          svgEdges +=
            '<path d="M ' +
            x1 +
            " " +
            y1 +
            " C " +
            cx +
            " " +
            y1 +
            ", " +
            cx +
            " " +
            y2 +
            ", " +
            x2 +
            " " +
            y2 +
            '" fill="none" stroke="#64748b" stroke-width="1.3"' +
            dash +
            " />";
        });
      });

      html +=
        '<svg style="position:absolute;top:0;left:0;width:2200px;height:1600px;pointer-events:none;">' +
        svgEdges +
        "</svg>";

      tables.forEach(function (tbl) {
        var pos = positions[String(tbl.id)];
        var hdr = DBO_COLORS[String(tbl.type || "table").toLowerCase()] || "#dbdada";
        var cols = Array.isArray(tbl.columns) ? tbl.columns : [];
        html +=
          '<div data-node-id="' +
          escapeHtml(tbl.id) +
          '" style="position:absolute;left:' +
          pos.x +
          "px;top:" +
          pos.y +
          "px;width:" +
          pos.width +
          'px;background:#fff;border:1px solid #cbd5e1;border-radius:3px;box-shadow:0 1px 3px rgba(0,0,0,0.08);">' +
          '<div style="height:22px;line-height:22px;padding:0 8px;background:' +
          hdr +
          ';color:#fff;font-size:12px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' +
          escapeHtml(tbl.displayName || tbl.name) +
          "</div>";
        cols.forEach(function (col) {
          html +=
            '<div style="height:18px;line-height:18px;padding:0 8px;font-size:11px;color:#111827;border-top:1px solid #f1f5f9;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' +
            escapeHtml(col.displayName || col.name) +
            "</div>";
        });
        html += "</div>";
      });

      html += "</div>";
      container.innerHTML = html;
    }

    return {
      visualizeJSON: function (jsonData, opts) {
        if (opts) state.options = Object.assign({}, state.options, opts);
        render(jsonData);
        return Promise.resolve();
      }
    };
  }

  global.SQLFlow = {
    version: "8.2.2-15",
    init: function (container, options) {
      return Promise.resolve(createInstance(container, options));
    }
  };
})(typeof window !== "undefined" ? window : globalThis);
