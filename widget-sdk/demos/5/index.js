(async function () {
  "use strict";

  var fixtures = window.SQLFlowDemoFixtures;
  var ddlEl = document.getElementById("er-ddl");
  var vendorEl = document.getElementById("er-vendor");
  var statusEl = document.getElementById("er-status");
  ddlEl.value = fixtures.ER_DDL_SQL;

  var widget = await window.SQLFlow.init({
    container: "#sqlflow",
    toolbar: true,
    dbvendor: vendorEl.value,
    sqltext: ddlEl.value
  });
  window.sqlflowWidget = widget;

  async function renderEr() {
    statusEl.className = "demo-status";
    statusEl.textContent = "Generating ER Diagram...";
    try {
      await widget.visualizeER(ddlEl.value, vendorEl.value);
      statusEl.textContent = "ER Diagram rendered via /gspLive_backend";
    } catch (_err) {
      await widget.visualizeERJSON(fixtures.ER_DIAGRAM_JSON);
      statusEl.className = "demo-status demo-status-warn";
      statusEl.textContent = "Rendered via precomputed ER JSON (4 entities, PK/UK/FK)";
    }
  }

  document.getElementById("run-er-btn").addEventListener("click", renderEr);
  document.getElementById("save-er-png-btn").addEventListener("click", function () {
    widget.saveERAsPng();
  });

  await renderEr();
})();
