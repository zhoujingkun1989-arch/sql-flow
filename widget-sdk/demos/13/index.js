(async function () {
  "use strict";

  var fixtures = window.SQLFlowDemoFixtures;
  var statusEl = document.getElementById("large-status");
  var largeGraph = fixtures.generateLargeWarehouseGraph(240);

  var widget = await window.SQLFlow.init({
    container: "#sqlflow",
    toolbar: true,
    minimap: true,
    search: true
  });
  window.sqlflowWidget = widget;

  async function showWithSummaryGate() {
    await widget.visualizeJson(largeGraph, { bypassSummaryGate: false });
    statusEl.textContent = "Summary Gate Active (>200 objects protected from main-thread jank)";
  }

  document.getElementById("load-240-gate-btn").addEventListener("click", showWithSummaryGate);

  document.getElementById("bypass-gate-btn").addEventListener("click", async function () {
    await widget.visualizeJson(largeGraph, { bypassSummaryGate: true, tableLevel: true });
    statusEl.textContent = "Rendered all 240 enterprise objects (Table Summary Mode)";
  });

  document.getElementById("slice-orders-domain-btn").addEventListener("click", async function () {
    await widget.visualizeJson(largeGraph, { bypassSummaryGate: true });
    await widget.upstream("mart_orders_001", 6);
    statusEl.textContent = "Sliced 6-hop upstream chain for mart_orders_001";
  });

  await showWithSummaryGate();
})();
