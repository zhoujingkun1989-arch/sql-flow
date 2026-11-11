(async function () {
  "use strict";

  var fixtures = window.SQLFlowDemoFixtures;
  var statsEl = document.getElementById("demo6-stats");
  var btnTable = document.getElementById("mode-table-btn");
  var btnCol = document.getElementById("mode-column-btn");

  var widget = await window.SQLFlow.init({
    container: "#sqlflow",
    toolbar: true,
    minimap: true,
    search: true
  });
  window.sqlflowWidget = widget;

  widget.on("visualizeSuccess", function (e) {
    statsEl.textContent =
      "Active View · " + e.nodeCount + " tables/views · " + e.edgeCount + " edges";
  });

  async function setTableMode(isTableLevel) {
    btnTable.className = isTableLevel ? "demo-btn demo-btn-primary" : "demo-btn";
    btnCol.className = !isTableLevel ? "demo-btn demo-btn-primary" : "demo-btn";
    await widget.visualizeJson(fixtures.ECOMMERCE_LINEAGE_JSON, {
      tableLevel: isTableLevel,
      sqltext: fixtures.ECOMMERCE_SQL
    });
  }

  btnTable.addEventListener("click", function () {
    setTableMode(true);
  });
  btnCol.addEventListener("click", function () {
    setTableMode(false);
  });
  document.getElementById("live-table-level-btn").addEventListener("click", async function () {
    try {
      await widget.visualizeTableLevel(fixtures.ECOMMERCE_SQL, "postgresql");
    } catch (_err) {
      await setTableMode(true);
    }
  });

  await setTableMode(true);
})();
