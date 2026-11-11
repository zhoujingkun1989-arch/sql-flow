(async function () {
  "use strict";

  var hostEl = document.getElementById("sqlflow");
  var themeBtn = document.getElementById("theme-toggle-btn");

  var widget = await window.SQLFlow.init({
    container: hostEl,
    theme: "dark",
    toolbar: true,
    editor: true,
    minimap: true,
    search: true,
    settings: true,
    export: true,
    sqlList: true,
    scaleControls: true
  });
  window.sqlflowWidget = widget;

  await widget.visualizeJson(window.SQLFlowDemoFixtures.ECOMMERCE_LINEAGE_JSON, {
    sqltext: window.SQLFlowDemoFixtures.ECOMMERCE_SQL
  });

  themeBtn.addEventListener("click", function () {
    var nextTheme = widget.getOptions().theme === "dark" ? "light" : "dark";
    widget.setOptions({ theme: nextTheme });
    themeBtn.textContent = "Theme: " + (nextTheme === "dark" ? "Dark" : "Light");
  });

  document.querySelectorAll("[data-vis]").forEach(function (chk) {
    chk.addEventListener("change", function () {
      var key = chk.getAttribute("data-vis");
      var patch = {};
      patch[key] = chk.checked;
      widget.setOptions(patch);
    });
  });

  document.getElementById("size-preset").addEventListener("change", function (e) {
    var dims = e.target.value.split(":");
    hostEl.style.width = dims[0];
    hostEl.style.height = dims[1];
  });
})();
