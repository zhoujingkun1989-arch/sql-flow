(async function () {
  "use strict";

  var fixtures = window.SQLFlowDemoFixtures;
  var outputEl = document.getElementById("pg-output");
  var eventsEl = document.getElementById("pg-events");
  var widget = null;

  function logEvent(name, detail) {
    var div = document.createElement("div");
    div.className = "demo-log-entry";
    div.textContent = "[" + name + "] " + JSON.stringify(detail);
    eventsEl.insertBefore(div, eventsEl.firstChild);
  }

  async function mountSandbox() {
    if (widget && !widget.isDestroyed()) {
      widget.destroy();
    }
    widget = await window.SQLFlow.init({
      container: "#sqlflow",
      toolbar: true,
      editor: true,
      minimap: true,
      search: true,
      settings: true,
      export: true,
      sqlList: true
    });
    window.sqlflowWidget = widget;

    [
      "ready",
      "nodeClick",
      "columnClick",
      "edgeClick",
      "canvasClick",
      "sqlChange",
      "vendorChange",
      "visualizeStart",
      "visualizeSuccess",
      "visualizeError"
    ].forEach(function (evName) {
      widget.on(evName, function (payload) {
        logEvent(evName, {
          kind: payload && payload.kind,
          table: payload && payload.table,
          column: payload && payload.column,
          nodeCount: payload && payload.nodeCount
        });
      });
    });

    await widget.visualizeJson(fixtures.ECOMMERCE_LINEAGE_JSON, {
      sqltext: fixtures.ECOMMERCE_SQL
    });
    outputEl.value = "Mounted SQLFlow v" + widget.version + " (" + widget.release + ")";
  }

  document.getElementById("pg-vis-json").addEventListener("click", async function () {
    if (!widget || widget.isDestroyed()) return;
    await widget.visualizeJson(fixtures.ECOMMERCE_LINEAGE_JSON, {
      sqltext: fixtures.ECOMMERCE_SQL
    });
    outputEl.value = "Rendered ECOMMERCE_LINEAGE_JSON";
  });

  document.getElementById("pg-vis-dg").addEventListener("click", async function () {
    if (!widget || widget.isDestroyed()) return;
    await widget.visualizeDependencyGraph(fixtures.DEPENDENCY_GRAPH_V1);
    outputEl.value = "Rendered DependencyGraph v1.0 (client-side Dagre)";
  });

  document.getElementById("pg-vis-er").addEventListener("click", async function () {
    if (!widget || widget.isDestroyed()) return;
    await widget.visualizeERJSON(fixtures.ER_DIAGRAM_JSON);
    outputEl.value = "Rendered ER Diagram JSON";
  });

  document.getElementById("pg-vis-join").addEventListener("click", async function () {
    if (!widget || widget.isDestroyed()) return;
    await widget.visualizeJoinJSON(fixtures.JOIN_ANALYSIS_GRAPH);
    outputEl.value = "Rendered JoinAnalysisGraph (join-analysis.v1)";
  });

  document.getElementById("pg-vis-cg").addEventListener("click", async function () {
    if (!widget || widget.isDestroyed()) return;
    await widget.visualizeCGJSON(fixtures.CALL_GRAPH_PAYLOAD);
    outputEl.value = "Rendered CallGraphPayload";
  });

  document.getElementById("pg-hl-col").addEventListener("click", function () {
    if (!widget || widget.isDestroyed()) return;
    var ok = widget.highlightColumn("mart_customer_revenue", "lifetime_revenue");
    outputEl.value = "highlightColumn('mart_customer_revenue', 'lifetime_revenue') => " + ok;
  });

  document.getElementById("pg-upstream").addEventListener("click", async function () {
    if (!widget || widget.isDestroyed()) return;
    await widget.upstream("customer_ltv", 2);
    outputEl.value = "upstream('customer_ltv', 2) completed";
  });

  document.getElementById("pg-downstream").addEventListener("click", async function () {
    if (!widget || widget.isDestroyed()) return;
    await widget.downstream("sales.customers", 2);
    outputEl.value = "downstream('sales.customers', 2) completed";
  });

  document.getElementById("pg-reset").addEventListener("click", function () {
    if (!widget || widget.isDestroyed()) return;
    widget.resetSlice();
    outputEl.value = "resetSlice() completed";
  });

  document.getElementById("pg-clear-hl").addEventListener("click", function () {
    if (!widget || widget.isDestroyed()) return;
    widget.clearHighlight();
    outputEl.value = "clearHighlight() completed";
  });

  document.getElementById("pg-zoom-in").addEventListener("click", function () {
    if (!widget || widget.isDestroyed()) return;
    outputEl.value = "zoomIn() => scale " + widget.zoomIn();
  });

  document.getElementById("pg-zoom-out").addEventListener("click", function () {
    if (!widget || widget.isDestroyed()) return;
    outputEl.value = "zoomOut() => scale " + widget.zoomOut();
  });

  document.getElementById("pg-orient").addEventListener("click", function () {
    if (!widget || widget.isDestroyed()) return;
    var next = widget.getOrientation() === "LR" ? "TB" : "LR";
    widget.setOrientation(next);
    outputEl.value = "setOrientation('" + next + "')";
  });

  document.getElementById("pg-theme").addEventListener("click", function () {
    if (!widget || widget.isDestroyed()) return;
    var next = widget.getOptions().theme === "dark" ? "light" : "dark";
    widget.setOptions({ theme: next });
    outputEl.value = "setOptions({ theme: '" + next + "' })";
  });

  document.getElementById("pg-lang").addEventListener("click", function () {
    if (!widget || widget.isDestroyed()) return;
    var next = widget.getOptions().lang === "en" ? "cn" : "en";
    widget.setOptions({ lang: next });
    outputEl.value = "setOptions({ lang: '" + next + "' })";
  });

  document.getElementById("pg-exp-csv").addEventListener("click", async function () {
    if (!widget || widget.isDestroyed()) return;
    outputEl.value = await widget.exportCsv();
  });

  document.getElementById("pg-exp-xml").addEventListener("click", async function () {
    if (!widget || widget.isDestroyed()) return;
    outputEl.value = await widget.exportXml();
  });

  document.getElementById("pg-exp-json").addEventListener("click", function () {
    if (!widget || widget.isDestroyed()) return;
    outputEl.value = widget.exportJson();
  });

  document.getElementById("pg-get-opts").addEventListener("click", function () {
    if (!widget || widget.isDestroyed()) return;
    outputEl.value = JSON.stringify(widget.getOptions(), null, 2);
  });

  document.getElementById("pg-destroy").addEventListener("click", function () {
    if (!widget) return;
    widget.destroy();
    outputEl.value = "widget.destroy() executed · isDestroyed() = " + widget.isDestroyed();
  });

  document.getElementById("pg-reinit").addEventListener("click", mountSandbox);

  await mountSandbox();
})();
