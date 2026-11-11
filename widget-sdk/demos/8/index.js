(async function () {
  "use strict";

  var fixtures = window.SQLFlowDemoFixtures;
  var statusEl = document.getElementById("multi-status");

  var leftWidget = await window.SQLFlow.init({
    container: "#sqlflow-left",
    theme: "light",
    orientation: "LR",
    toolbar: true,
    minimap: true
  });

  var rightWidget = await window.SQLFlow.init({
    container: "#sqlflow-right",
    theme: "dark",
    orientation: "TB",
    toolbar: true,
    minimap: true
  });

  window.sqlflowLeft = leftWidget;
  window.sqlflowRight = rightWidget;
  window.sqlflowWidget = leftWidget;

  await Promise.all([
    leftWidget.visualizeJson(fixtures.ECOMMERCE_LINEAGE_JSON),
    rightWidget.visualizeJson(fixtures.FINANCE_LEDGER_JSON)
  ]);

  document.getElementById("left-hl-btn").addEventListener("click", function () {
    leftWidget.highlightTable("sales.orders");
    statusEl.textContent = "Highlighted sales.orders in Instance A only";
  });

  document.getElementById("right-hl-btn").addEventListener("click", function () {
    rightWidget.highlightColumn("rpt_trial_balance", "net_balance");
    statusEl.textContent = "Highlighted rpt_trial_balance.net_balance in Instance B only";
  });

  document.getElementById("swap-themes-btn").addEventListener("click", function () {
    var leftNext = leftWidget.getOptions().theme === "light" ? "dark" : "light";
    var rightNext = rightWidget.getOptions().theme === "light" ? "dark" : "light";
    leftWidget.setOptions({ theme: leftNext });
    rightWidget.setOptions({ theme: rightNext });
    statusEl.textContent = "Themes swapped (A=" + leftNext + ", B=" + rightNext + ")";
  });
})();
