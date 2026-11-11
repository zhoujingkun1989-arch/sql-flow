(async function () {
  "use strict";

  var fixtures = window.SQLFlowDemoFixtures;
  var jsonEditor = document.getElementById("json-editor");
  var statusEl = document.getElementById("demo2-status");

  var widget = await window.SQLFlow.init({
    container: "#sqlflow",
    width: "100%",
    height: "100%",
    toolbar: true,
    minimap: true,
    search: true,
    export: true
  });
  window.sqlflowWidget = widget;

  widget.on("visualizeSuccess", function (evt) {
    statusEl.className = "demo-status";
    statusEl.textContent =
      "Rendered " + evt.nodeCount + " nodes & " + evt.edgeCount + " edges (0 backend calls)";
  });

  async function loadPayload(payload) {
    jsonEditor.value = JSON.stringify(payload, null, 2);
    await widget.visualizeJson(payload);
  }

  document.getElementById("preset-ecom-btn").addEventListener("click", function () {
    loadPayload(fixtures.ECOMMERCE_LINEAGE_JSON);
  });
  document.getElementById("preset-fin-btn").addEventListener("click", function () {
    loadPayload(fixtures.FINANCE_LEDGER_JSON);
  });
  document.getElementById("apply-json-btn").addEventListener("click", async function () {
    try {
      var parsed = JSON.parse(jsonEditor.value);
      await widget.visualizeJson(parsed);
    } catch (err) {
      statusEl.className = "demo-status demo-status-warn";
      statusEl.textContent = "Invalid JSON: " + (err && err.message ? err.message : String(err));
    }
  });

  await loadPayload(fixtures.ECOMMERCE_LINEAGE_JSON);
})();
