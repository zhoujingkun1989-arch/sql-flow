(async function () {
  "use strict";

  var fixtures = window.SQLFlowDemoFixtures;
  var jsonEl = document.getElementById("dg-json");
  var statusEl = document.getElementById("dg-status");
  jsonEl.value = JSON.stringify(fixtures.DEPENDENCY_GRAPH_V1, null, 2);

  var widget = await window.SQLFlow.init({
    container: "#sqlflow",
    toolbar: true,
    minimap: true,
    search: true
  });
  window.sqlflowWidget = widget;
  widget.setting.showTransform.set(true);

  async function renderGraph(useDagre) {
    try {
      var parsed = JSON.parse(jsonEl.value);
      await widget.visualizeDependencyGraph(parsed, { layout: useDagre });
      statusEl.className = "demo-status";
      statusEl.textContent =
        "Valid v1.0 (" + (useDagre ? "Dagre" : "Topological BFS") + ") · 0 Backend Calls";
    } catch (err) {
      statusEl.className = "demo-status demo-status-warn";
      statusEl.textContent = err && err.message ? err.message : String(err);
    }
  }

  document.getElementById("dg-dagre-btn").addEventListener("click", function () {
    renderGraph(true);
  });
  document.getElementById("dg-bfs-btn").addEventListener("click", function () {
    renderGraph(false);
  });
  document.getElementById("dg-focus-btn").addEventListener("click", function () {
    try {
      var parsed = JSON.parse(jsonEl.value);
      widget.highlightTable(parsed.focus.nodeId);
    } catch (_e) {}
  });

  await renderGraph(true);
})();
