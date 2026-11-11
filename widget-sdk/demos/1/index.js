(async function () {
  "use strict";

  var fixtures = window.SQLFlowDemoFixtures;
  var sqlInput = document.getElementById("sql-input");
  var dialectSelect = document.getElementById("dialect-select");
  var statusEl = document.getElementById("demo1-status");
  sqlInput.value = fixtures.ECOMMERCE_SQL;

  var widget = await window.SQLFlow.init({
    container: "#sqlflow",
    width: "100%",
    height: "100%",
    apiPrefix: "/gspLive_backend",
    dbvendor: dialectSelect.value,
    sqltext: sqlInput.value,
    editor: true,
    toolbar: true,
    minimap: true,
    search: true,
    sqlList: true
  });
  window.sqlflowWidget = widget;

  widget.on("sqlChange", function (evt) {
    if (sqlInput.value !== evt.sqltext) {
      sqlInput.value = evt.sqltext;
    }
  });

  async function runVisualize() {
    var sql = sqlInput.value;
    var vendor = dialectSelect.value;
    statusEl.className = "demo-status";
    statusEl.textContent = "Visualizing (" + vendor + ")...";
    try {
      await widget.visualizeSQL(sql, vendor);
      statusEl.textContent = "Live Backend: visualized (" + vendor + ")";
    } catch (_err) {
      await widget.visualizeJson(fixtures.ECOMMERCE_LINEAGE_JSON, { sqltext: sql });
      statusEl.className = "demo-status demo-status-warn";
      statusEl.textContent = "Offline Preview Fallback (rendered E-Commerce lineage)";
    }
  }

  document.getElementById("run-sql-btn").addEventListener("click", runVisualize);
  document.getElementById("toggle-editor-btn").addEventListener("click", function () {
    var current = widget.getOptions().visibility.sqlEditor;
    widget.setOptions({ editor: !current });
  });

  await runVisualize();
})();
