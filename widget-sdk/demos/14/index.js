(async function () {
  "use strict";

  var fixtures = window.SQLFlowDemoFixtures;
  var sqlEl = document.getElementById("join-sql");
  var vendorEl = document.getElementById("join-vendor");
  var statusEl = document.getElementById("join-status");
  sqlEl.value = fixtures.JOIN_ANALYSIS_SQL;

  var widget = await window.SQLFlow.init({
    container: "#sqlflow",
    toolbar: true,
    editor: true,
    dbvendor: vendorEl.value,
    sqltext: sqlEl.value
  });
  window.sqlflowWidget = widget;

  async function runJoinAnalysis() {
    statusEl.className = "demo-status";
    statusEl.textContent = "Analyzing SQL joins...";
    try {
      var res = await widget.visualizeJoin(sqlEl.value, vendorEl.value);
      statusEl.textContent =
        "Join Analysis complete (" + (res && res.diagnostics ? res.diagnostics.length : 0) + " diagnostics)";
    } catch (_err) {
      await widget.visualizeJoinJSON(fixtures.JOIN_ANALYSIS_GRAPH);
      statusEl.className = "demo-status demo-status-warn";
      statusEl.textContent = "Rendered join-analysis.v1 fixture (3 diagnostics)";
    }
  }

  document.getElementById("run-join-btn").addEventListener("click", runJoinAnalysis);

  await runJoinAnalysis();
})();
