(async function () {
  "use strict";

  var resultEl = document.getElementById("hl-result");

  var widget = await window.SQLFlow.init({
    container: "#sqlflow",
    editor: true,
    toolbar: false,
    minimap: true
  });
  window.sqlflowWidget = widget;

  await widget.visualizeJson(window.SQLFlowDemoFixtures.ECOMMERCE_LINEAGE_JSON, {
    sqltext: window.SQLFlowDemoFixtures.ECOMMERCE_SQL
  });

  function reportStatus(ok, label) {
    resultEl.className = ok ? "demo-status" : "demo-status demo-status-warn";
    resultEl.textContent = ok ? "Highlighted: " + label : "Not found: " + label;
  }

  document.querySelectorAll("[data-hl-col]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var parts = btn.getAttribute("data-hl-col").split(":");
      var ok = widget.highlightColumn(parts[0], parts[1]);
      reportStatus(ok, parts[0] + "." + parts[1]);
    });
  });

  document.querySelectorAll("[data-hl-tbl]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var tbl = btn.getAttribute("data-hl-tbl");
      var ok = widget.highlightTable(tbl);
      reportStatus(ok, tbl);
    });
  });

  document.getElementById("apply-hl-btn").addEventListener("click", function () {
    var tbl = document.getElementById("custom-tbl").value.trim();
    var col = document.getElementById("custom-col").value.trim();
    if (!tbl) return;
    if (col) {
      reportStatus(widget.highlightColumn(tbl, col), tbl + "." + col);
    } else {
      reportStatus(widget.highlightTable(tbl), tbl);
    }
  });

  document.getElementById("clear-hl-btn").addEventListener("click", function () {
    widget.clearHighlight();
    resultEl.className = "demo-status";
    resultEl.textContent = "Cleared active highlight";
  });

  // Initial path highlight on load
  widget.highlightColumn("mart_customer_revenue", "lifetime_revenue");
  reportStatus(true, "mart_customer_revenue.lifetime_revenue");
})();
