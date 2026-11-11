(async function () {
  "use strict";

  var targetEl = document.getElementById("slice-target");
  var hopsEl = document.getElementById("hop-distance");
  var statusEl = document.getElementById("slice-status");

  var widget = await window.SQLFlow.init({
    container: "#sqlflow",
    toolbar: true,
    minimap: true
  });
  window.sqlflowWidget = widget;

  widget.on("visualizeSuccess", function (e) {
    statusEl.textContent =
      e.kind.toUpperCase() + " · " + e.nodeCount + " nodes · " + e.edgeCount + " edges";
  });

  await widget.visualizeJson(window.SQLFlowDemoFixtures.ECOMMERCE_LINEAGE_JSON);

  document.getElementById("btn-upstream").addEventListener("click", async function () {
    var hops = Number(hopsEl.value) || 2;
    await widget.upstream(targetEl.value, hops);
  });

  document.getElementById("btn-downstream").addEventListener("click", async function () {
    var hops = Number(hopsEl.value) || 2;
    await widget.downstream(targetEl.value, hops);
  });

  document.getElementById("btn-reset-slice").addEventListener("click", function () {
    widget.resetSlice();
    statusEl.textContent = "Restored full graph";
  });
})();
