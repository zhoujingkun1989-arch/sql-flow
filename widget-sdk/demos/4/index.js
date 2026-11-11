(async function () {
  "use strict";

  var readout = document.getElementById("ext-scale-readout");
  var orientBtn = document.getElementById("ext-orient");

  var widget = await window.SQLFlow.init({
    container: "#sqlflow",
    toolbar: false,
    editor: false,
    minimap: true,
    scaleControls: false
  });
  window.sqlflowWidget = widget;

  await widget.visualizeJson(window.SQLFlowDemoFixtures.ECOMMERCE_LINEAGE_JSON);

  function updateScaleBadge(scale) {
    readout.textContent = "Scale: " + Math.round(scale * 100) + "%";
  }

  document.getElementById("ext-zoom-in").addEventListener("click", function () {
    updateScaleBadge(widget.zoomIn());
  });
  document.getElementById("ext-zoom-out").addEventListener("click", function () {
    updateScaleBadge(widget.zoomOut());
  });
  document.getElementById("ext-zoom-fit").addEventListener("click", function () {
    updateScaleBadge(widget.zoomToFit());
  });
  document.getElementById("ext-zoom-reset").addEventListener("click", function () {
    updateScaleBadge(widget.resetZoom());
  });
  orientBtn.addEventListener("click", function () {
    var next = widget.getOrientation() === "LR" ? "TB" : "LR";
    widget.setOrientation(next);
    orientBtn.textContent = "Layout: " + next;
  });
  document.getElementById("ext-search").addEventListener("input", function (e) {
    var matches = widget.search(e.target.value);
    readout.textContent = "Matches: " + matches.length + " tables";
  });
  document.getElementById("ext-indirect").addEventListener("change", function (e) {
    widget.setting.impact.set(e.target.checked);
  });
  document.getElementById("ext-transform").addEventListener("change", function (e) {
    widget.setting.showTransform.set(e.target.checked);
  });
  document.getElementById("ext-export-csv").addEventListener("click", function () {
    widget.exportCsv();
  });
  document.getElementById("ext-export-xml").addEventListener("click", function () {
    widget.exportXml();
  });
  document.getElementById("ext-export-png").addEventListener("click", function () {
    widget.exportImage();
  });
})();
